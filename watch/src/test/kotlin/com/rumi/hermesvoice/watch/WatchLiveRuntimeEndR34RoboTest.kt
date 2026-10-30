package com.rumi.hermesvoice.watch

import android.Manifest
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.diag.*
import com.rumi.hermesvoice.core.live.WatchLiveWire
import com.rumi.hermesvoice.core.settings.WatchSettings
import io.mockk.*
import kotlinx.coroutines.Job
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.*
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * R34: a Watch Live call the Watch stops AFTER the Phone relayed its session.started leaves exactly one fixed-step, type-only
 * observation in the Watch's own journal: live_ended (origin watch), [DiagEvent.n] the same fixed [WatchLiveSetupStep] (for a media
 * loss the step the media reported), stage live_start, fail/cause by type, [DiagEvent.ms] since the Phone opened the media. The same
 * refusal before the relayed start stays the R32 setup observation; a Phone end whose silence arrives first is never recorded. The Phone
 * relay's production order is kept: its `output` command precedes the relayed session.started.
 * Real default runtime and Channel media; AudioRecord/AudioTrack, the native route and the Wear Channel/messages are deterministic
 * platform doubles, so these prove the observation and its boundaries, never a physical cause.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class WatchLiveRuntimeEndR34RoboTest {
    private lateinit var app: WatchApp; private lateinit var live: WatchLiveVoiceRuntime
    private val sent = ConcurrentLinkedQueue<ByteArray>(); private val opens = AtomicInteger()
    private val microphone = ByteArray(960) { (it % 119).toByte() }; private val uplink = ByteArrayOutputStream()
    private val downlink = PipedInputStream(65536); private val phone = PipedOutputStream(downlink)
    private val played = ConcurrentLinkedQueue<ByteArray>(); private val permits = Semaphore(0)
    @Volatile private var stopped = false; @Volatile private var taken: (Int) -> Int = { it }
    private var routeValid = true; private var lost: (() -> Unit)? = null
    private var now = 1000L; private var id = ""; private var sequence = 0L; private var downSequence = 0L
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
    private fun await(condition: () -> Boolean): Boolean { val end = System.nanoTime() + 5_000_000_000; while (System.nanoTime() < end) { idle(); if (condition()) return true; Thread.sleep(10) }; return condition() }
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as WatchApp; app.localStore.putString("live_control_id", ""); app.localStore.putString("live_control_node", "")
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.applySettings(WatchSettings(phoneLiveEnabled = false, watchLiveEnabled = true, revision = 100).toJson()); idle()
        mockkStatic(Wearable::class); val messages = mockk<MessageClient>(); every { messages.sendMessage(any(), any(), any()) } answers { sent += thirdArg<ByteArray>(); Tasks.forResult(1) }
        every { Wearable.getMessageClient(any<android.content.Context>()) } returns messages
        var openedPath = ""; val channels = mockk<ChannelClient>(relaxed = true); val c = mockk<ChannelClient.Channel>(); every { c.nodeId } returns "phone"; every { c.path } answers { openedPath }
        every { channels.openChannel(any(), any()) } answers { openedPath = secondArg(); opens.incrementAndGet(); Tasks.forResult(c) }
        every { channels.getInputStream(any()) } returns Tasks.forResult(downlink as InputStream); every { channels.getOutputStream(any()) } returns Tasks.forResult(uplink as OutputStream)
        every { channels.close(any()) } returns Tasks.forResult(null)
        every { Wearable.getChannelClient(any<android.content.Context>()) } returns channels
        Class.forName("android.media.AudioRecord"); Class.forName("android.media.AudioTrack")
        mockkStatic(AudioRecord::class); every { AudioRecord.getMinBufferSize(any(), any(), any()) } returns 1920
        mockkConstructor(AudioRecord.Builder::class); val record = mockk<AudioRecord>(relaxed = true)
        every { anyConstructed<AudioRecord.Builder>().setAudioSource(any()) } answers { self as AudioRecord.Builder }; every { anyConstructed<AudioRecord.Builder>().setAudioFormat(any()) } answers { self as AudioRecord.Builder }
        every { anyConstructed<AudioRecord.Builder>().setBufferSizeInBytes(any()) } answers { self as AudioRecord.Builder }; every { anyConstructed<AudioRecord.Builder>().build() } returns record
        every { record.state } returns AudioRecord.STATE_INITIALIZED; every { record.recordingState } returns AudioRecord.RECORDSTATE_RECORDING; every { record.audioSessionId } returns 17
        val audio = app.getSystemService(AudioManager::class.java); mockkObject(audio)
        val original = mockk<AudioDeviceInfo>(relaxed = true); every { original.id } returns 7001; every { original.type } returns AudioDeviceInfo.TYPE_BUILTIN_MIC
        every { original.address } returns "synthetic-watch-input"; every { original.isSource } returns true; every { original.isSink } returns false
        every { audio.getDevices(any()) } answers { if (firstArg<Int>() == AudioManager.GET_DEVICES_INPUTS) arrayOf(original) else emptyArray() }
        every { record.setPreferredDevice(any()) } answers { firstArg<AudioDeviceInfo?>()?.id == original.id }; every { record.routedDevice } returns original
        every { record.read(any<ByteArray>(), any(), any(), any()) } answers { permits.tryAcquire(2, java.util.concurrent.TimeUnit.SECONDS); if (stopped) -3 else { microphone.copyInto(firstArg<ByteArray>(), secondArg<Int>(), 0, thirdArg<Int>()); thirdArg<Int>() } }
        every { record.stop() } answers { stopped = true; permits.release() }
        mockkStatic(AudioTrack::class); every { AudioTrack.getMinBufferSize(any(), any(), any()) } returns 1920
        mockkConstructor(AudioTrack.Builder::class); val player = mockk<AudioTrack>(relaxed = true)
        every { anyConstructed<AudioTrack.Builder>().setAudioAttributes(any()) } answers { self as AudioTrack.Builder }; every { anyConstructed<AudioTrack.Builder>().setAudioFormat(any()) } answers { self as AudioTrack.Builder }
        every { anyConstructed<AudioTrack.Builder>().setTransferMode(any()) } answers { self as AudioTrack.Builder }; every { anyConstructed<AudioTrack.Builder>().setBufferSizeInBytes(any()) } answers { self as AudioTrack.Builder }
        every { anyConstructed<AudioTrack.Builder>().build() } returns player
        every { player.state } returns AudioTrack.STATE_INITIALIZED
        // A non-blocking write takes what fits: [taken] says how much of the frame the track accepted.
        every { player.write(any<ByteArray>(), any(), any(), any()) } answers { played += firstArg<ByteArray>().copyOf(); taken(thirdArg()) }
        live = WatchLiveVoiceRuntime(app) { "phone" }; live.admission = { live.admitted(it) }; live.now = { now }
        live.routeFactory = { l -> lost = l; object : WatchLiveRoute { override fun open() = true; override fun valid() = routeValid; override fun close() {} } }
        // DO NOT replace mediaFactory: the production default Channel media reports its own losses.
    }
    @After fun cleanup() { stopped = true; permits.release(4); if (::live.isInitialized) live.end(); runCatching { phone.close() }; downlink.close(); idle(); app.scope.coroutineContext[Job]?.cancel(); unmockkAll() }
    private fun packet(op: String, body: String = "") = live.onStatus("phone", WatchLiveWire.packet(id, "media", "epoch", ++sequence, op, body))
    private fun media(op: String) = sent.mapNotNull { WatchLiveWire.parse(it) }.filter { it.op == op }
    private fun ended() = app.diag.snapshot().filter { it.code == DiagCode.LIVE_ENDED }
    private fun setupFailures() = app.diag.snapshot().filter { it.code == DiagCode.LIVE_SETUP_FAILED }
    /** The Phone relay's media open, its answer and, when [started], its output command then the relayed session.started (the order the
     *  Phone's controller sends them in: output(true) runs inline before the event is relayed). */
    private fun open(started: Boolean = true) {
        live.toggle(); idle(); id = sent.map { JSONObject(it.toString(Charsets.UTF_8)) }.first { it.optString("op") == "start" }.getString("id")
        packet("open", JSONObject().put("stream", "audio-stream").put("relay", true).put("muted", false).put("private", false).toString())
        assertTrue("the default Watch media opens its Channel", await { opens.get() == 1 })
        assertTrue(await { media("offer").isNotEmpty() }); packet("answer", "phone-relay-ready")
        assertTrue("normal control: connected", await { media("connected").isNotEmpty() })
        if (started) { packet("output", "true"); packet("event", "{\"type\":\"session.started\",\"session\":{\"id\":\"fixture\"}}") }
        idle()
    }
    /** One Phone downlink frame on the Channel (WatchRelayAudio's DOWNLINK framing). */
    private fun frame() = DataOutputStream(phone).apply { writeInt(0x48565233); writeInt(2); writeLong(++downSequence); writeInt(48000); writeInt(1); writeInt(960); write(ByteArray(960) { 5 }); flush() }
    private fun runtimeEnd(): DiagEvent {
        val events = ended(); assertEquals("exactly one Watch runtime end: $events", 1, events.size)
        assertTrue("never also a setup observation", setupFailures().isEmpty())
        return events.single().also { e -> assertEquals(DiagOrigin.WATCH, e.origin); assertNull("no correlation id", e.ref); assertEquals(DiagStage.LIVE_START, e.stage) }
    }

    @Test fun R01_aWatchRefusalAfterTheRelayedStartIsObservedOnceAtItsFixedStep() {
        open(); now += 1600
        sequence++; packet("ping"); assertTrue("a Phone control packet missing after the start", await { media("refused").isNotEmpty() })
        assertEquals("the Watch still tells the Phone the same refusal", "Phone control sequence lost; Watch microphone closed", media("refused").single().body)
        val e = runtimeEnd()
        assertEquals(31, e.n); assertEquals(DiagFail.PROTOCOL, e.fail); assertEquals(DiagCause.LOCAL, e.cause); assertEquals(1600L, e.ms)
    }

    @Test fun R02_aWatchSpeakerThatDoesNotTakeAWholeFrameIsObservedAtThePlaybackStep() {
        open(); taken = { it / 2 }
        frame(); assertTrue(await { media("refused").isNotEmpty() })
        assertEquals("Phone audio relay unavailable; Watch audio stopped without fallback", media("refused").single().body)
        val e = runtimeEnd()
        assertEquals("the Watch speaker boundary, apart from the downlink read", 56, e.n); assertEquals(DiagFail.UNKNOWN, e.fail)
    }

    /** Also what a Phone teardown that closes the Channel before its silence arrives looks like: only the Phone's end tells them apart. */
    @Test fun R03_aDownlinkThatEndsAfterTheStartIsObservedAtTheDownlinkStepAsIo() {
        open()
        frame(); assertTrue("the speaker took the whole frame", await { played.size == 1 }); assertTrue(media("refused").isEmpty())
        phone.close(); assertTrue(await { media("refused").isNotEmpty() })
        val e = runtimeEnd()
        assertEquals(33, e.n); assertEquals(DiagFail.IO, e.fail); assertEquals(DiagCause.DISCONNECT, e.cause)
    }

    @Test fun R04_aRouteLostAfterTheStartIsObservedAtTheRouteStepOnlyOnce() {
        open(); assertNotNull(lost)
        lost!!(); assertTrue(await { media("refused").isNotEmpty() })
        assertEquals("Watch audio route/focus lost; no speaker fallback", media("refused").single().body)
        lost!!(); now += 100; repeat(4) { idle() }
        val e = runtimeEnd()
        assertEquals(17, e.n); assertEquals(DiagFail.REFUSED, e.fail); assertEquals(DiagCause.LOCAL, e.cause)
        assertEquals("one refusal", 1, media("refused").size)
    }

    @Test fun R05_aPhoneEndWhoseSilenceArrivesBeforeItsTeardownIsNeverRecorded() {
        open()
        frame(); assertTrue(await { played.size == 1 })
        packet("silence"); idle(); phone.close(); idle(); packet("close"); now += 6_000; repeat(6) { idle() }
        assertTrue("the Phone's end is no Watch refusal", media("refused").isEmpty())
        assertTrue(ended().isEmpty()); assertTrue(setupFailures().isEmpty())
    }

    @Test fun R06_theSameRefusalBeforeTheRelayedStartStaysTheSetupObservation() {
        open(started = false); routeValid = false
        packet("output", "true"); assertTrue(await { media("refused").isNotEmpty() })
        assertEquals("Watch output route unconfirmed", media("refused").single().body)
        val e = setupFailures().single()
        assertEquals(51, e.n); assertEquals(DiagStage.LIVE_ROUTE, e.stage); assertEquals(DiagOrigin.WATCH, e.origin)
        assertTrue("before the relayed start it is no runtime end", ended().isEmpty())
    }
}
