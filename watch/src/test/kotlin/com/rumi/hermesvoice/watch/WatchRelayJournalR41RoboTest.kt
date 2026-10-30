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
import com.rumi.hermesvoice.core.live.LivePcmFrame
import com.rumi.hermesvoice.core.live.WatchLiveWire
import com.rumi.hermesvoice.core.live.WatchRelayAudio
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
 * R41: the R40 trial's Watch journal was empty because a Phone-side end after Started is no Watch refusal (R34), so the Watch recorded
 * nothing about its own microphone or relay. Now every media whose call started leaves, once, however it stops (a Phone End too), its
 * relay counts in the Watch's own journal: live_relay_capture, live_relay_nonzero and live_relay_sent, fixed numbers only. A Phone end is
 * still no Watch runtime end, and a media that never started records none. Real default runtime and Channel media; AudioRecord,
 * AudioTrack, the native route and the Wear Channel/messages are deterministic platform doubles (the R34 harness).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class WatchRelayJournalR41RoboTest {
    private lateinit var app: WatchApp; private lateinit var live: WatchLiveVoiceRuntime
    private val sent = ConcurrentLinkedQueue<ByteArray>(); private val opens = AtomicInteger()
    private val microphone = ByteArray(960) { (it % 119).toByte() }; private val uplink = ByteArrayOutputStream()
    private val downlink = PipedInputStream(65536); private val phone = PipedOutputStream(downlink)
    private val played = ConcurrentLinkedQueue<ByteArray>(); private val permits = Semaphore(0)
    @Volatile private var stopped = false
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
        // One permit is one recorder read: the test decides how much the microphone captured.
        every { record.read(any<ByteArray>(), any(), any(), any()) } answers { permits.tryAcquire(2, java.util.concurrent.TimeUnit.SECONDS); if (stopped) -3 else { microphone.copyInto(firstArg<ByteArray>(), secondArg<Int>(), 0, thirdArg<Int>()); thirdArg<Int>() } }
        every { record.stop() } answers { stopped = true; permits.release() }
        mockkStatic(AudioTrack::class); every { AudioTrack.getMinBufferSize(any(), any(), any()) } returns 1920
        mockkConstructor(AudioTrack.Builder::class); val player = mockk<AudioTrack>(relaxed = true)
        every { anyConstructed<AudioTrack.Builder>().setAudioAttributes(any()) } answers { self as AudioTrack.Builder }; every { anyConstructed<AudioTrack.Builder>().setAudioFormat(any()) } answers { self as AudioTrack.Builder }
        every { anyConstructed<AudioTrack.Builder>().setTransferMode(any()) } answers { self as AudioTrack.Builder }; every { anyConstructed<AudioTrack.Builder>().setBufferSizeInBytes(any()) } answers { self as AudioTrack.Builder }
        every { anyConstructed<AudioTrack.Builder>().build() } returns player
        every { player.state } returns AudioTrack.STATE_INITIALIZED
        every { player.write(any<ByteArray>(), any(), any(), any()) } answers { played += firstArg<ByteArray>().copyOf(); thirdArg() }
        live = WatchLiveVoiceRuntime(app) { "phone" }; live.admission = { live.admitted(it) }; live.now = { now }
        live.routeFactory = { _ -> object : WatchLiveRoute { override fun open() = true; override fun valid() = true; override fun close() {} } }
        // DO NOT replace mediaFactory: the production default Channel media counts its own relay.
    }
    @After fun cleanup() { stopped = true; permits.release(4); if (::live.isInitialized) live.end(); runCatching { phone.close() }; downlink.close(); idle(); app.scope.coroutineContext[Job]?.cancel(); unmockkAll() }
    private fun packet(op: String, body: String = "") = live.onStatus("phone", WatchLiveWire.packet(id, "media", "epoch", ++sequence, op, body))
    private fun media(op: String) = sent.mapNotNull { WatchLiveWire.parse(it) }.filter { it.op == op }
    private fun relay(wire: String) = app.diag.snapshot().filter { it.code.wire == wire }
    private fun ended() = app.diag.snapshot().filter { it.code == DiagCode.LIVE_ENDED }
    /** The Phone relay's media open, its answer and, when [started], its output command then the relayed session.started (R34 order). */
    private fun open(started: Boolean = true) {
        live.toggle(); idle(); id = sent.map { JSONObject(it.toString(Charsets.UTF_8)) }.first { it.optString("op") == "start" }.getString("id")
        packet("open", JSONObject().put("stream", "audio-stream").put("relay", true).put("muted", false).put("private", false).toString())
        assertTrue("the default Watch media opens its Channel", await { opens.get() == 1 })
        assertTrue(await { media("offer").isNotEmpty() }); packet("answer", "phone-relay-ready")
        assertTrue("normal control: connected", await { media("connected").isNotEmpty() })
        if (started) { packet("output", "true"); packet("event", "{\"type\":\"session.started\",\"session\":{\"id\":\"fixture\"}}") }
        idle()
    }
    /** One Phone downlink packet on the Channel, written with the shared codec. */
    private fun frame() = WatchRelayAudio.write(DataOutputStream(phone), WatchRelayAudio.DOWNLINK,
        LivePcmFrame(++downSequence, WatchRelayAudio.DOWNLINK_RATE, 1, ByteArray(WatchRelayAudio.DOWNLINK_FRAME_BYTES) { 5 }))
    /** [packets] microphone packets captured and written to the Phone. */
    private fun capture(packets: Int) { val before = uplink.size(); permits.release(packets); assertTrue("the Watch wrote its packets", await { uplink.size() > before }); Thread.sleep(100); idle() }
    private fun observations(): List<DiagEvent> {
        val all = listOf("live_relay_capture", "live_relay_nonzero", "live_relay_sent").map { relay(it) }
        all.forEach { assertEquals("exactly one of each relay observation: $all", 1, it.size) }
        return all.map { it.single() }.onEach { e ->
            assertEquals(DiagOrigin.WATCH, e.origin); assertNull("no correlation id", e.ref); assertNull("no failure text", e.fail)
            assertNotNull("a fixed number", e.n); assertNotNull("a fixed duration", e.ms)
        }
    }

    @Test fun J01_aPhoneEndAfterTheRelayedStartLeavesTheWatchsOwnRelayCountsOnceButNoRuntimeEnd() {
        open(); capture(4)
        frame(); assertTrue("the speaker took the packet", await { played.size == 1 })
        packet("silence"); idle(); phone.close(); idle(); packet("close"); now += 6_000; repeat(6) { idle() }
        assertTrue("the Phone's end is no Watch refusal", media("refused").isEmpty()); assertTrue("and no Watch runtime end", ended().isEmpty())
        val (captured, nonzero, written) = observations()
        assertTrue("packets captured (${captured.n})", (captured.n ?: 0) >= 1)
        assertTrue("captured packets holding a nonzero sample (${nonzero.n})", (nonzero.n ?: 0) in 1..(captured.n ?: 0))
        assertTrue("packets written to the Phone (${written.n})", (written.n ?: 0) in 1..(captured.n ?: 0))
        live.end(); idle(); repeat(4) { idle() }
        observations() // still exactly once
    }

    @Test fun J02_aWatchRefusalAfterTheRelayedStartKeepsItsOneRuntimeEndAndAddsTheRelayCountsOnce() {
        open(); capture(3); now += 1600
        sequence++; packet("ping"); assertTrue("a Phone control packet missing after the start", await { media("refused").isNotEmpty() })
        repeat(4) { idle() }
        assertEquals("one Watch runtime end", 1, ended().size)
        val (captured, _, written) = observations()
        assertTrue((captured.n ?: 0) >= 1); assertTrue((written.n ?: 0) >= 1)
    }

    @Test fun J03_aMediaThatNeverStartedRecordsNoRelayCounts() {
        open(started = false); permits.release(2); Thread.sleep(150); idle()
        packet("silence"); idle(); packet("close"); repeat(6) { idle() }
        for (w in listOf("live_relay_capture", "live_relay_nonzero", "live_relay_sent")) assertTrue("no $w before the relayed start", relay(w).isEmpty())
    }
}
