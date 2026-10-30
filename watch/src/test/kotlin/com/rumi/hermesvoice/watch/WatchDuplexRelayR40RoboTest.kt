package com.rumi.hermesvoice.watch

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.live.LivePcmFrame
import com.rumi.hermesvoice.core.live.WatchRelayAudio
import io.mockk.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * R40: the real default Watch media (WatchChannelMedia: AudioRecord -> bounded queue -> the R39 whole-frame writer -> Wear stream;
 * Wear stream -> downlink reader -> AudioTrack) in sustained full duplex with the Phone over ONE shared link ([R40Link], a prospective
 * envelope, not a measured link speed). Platform doubles only: the recorder produces 2 * rate bytes a second of real time at the rate
 * the production asked for (READ_BLOCKING returns when the bytes exist); the player drains 2 * rate bytes a second from the buffer it
 * was built with and, like a WRITE_NON_BLOCKING AudioTrack, accepts no more than its free space. The Phone side decodes every uplink
 * byte with the real codec and writes 48 kHz mono 10 ms provider frames in real time through its own 32-frame bounded queue with the
 * field-by-field codec writer (WatchLiveTransport); an overflow there is the Phone's "Watch output congested" end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class WatchDuplexRelayR40RoboTest {
    private class Setup(message: String) : IllegalStateException(message)
    private lateinit var app: WatchApp
    private lateinit var media: WatchChannelMedia
    private lateinit var record: AudioRecord
    private lateinit var player: AudioTrack
    private var link: R40Link? = null
    private val errors = ConcurrentLinkedQueue<Throwable>(); private val stops = ConcurrentLinkedQueue<() -> Unit>()
    private val failures = AtomicInteger(); private val released = AtomicInteger()
    private val offers = ConcurrentLinkedQueue<String>(); private val microphone = ConcurrentLinkedQueue<Boolean>()
    @Volatile private var captureFormat: AudioFormat? = null; @Volatile private var recorderBuffer = 0
    @Volatile private var stopped = false; @Volatile private var recordStart = 0L; @Volatile private var recordBase = 0L
    @Volatile private var recordings = 0
    private val recorded = AtomicLong()
    @Volatile private var playerFormat: AudioFormat? = null; @Volatile private var playerBuffer = 0
    private val rendered = ByteArrayOutputStream(); private var level = 0L; private var levelAt = 0L // guarded by rendered

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
    private fun await(seconds: Long = 5, done: () -> Boolean): Boolean { val end = System.nanoTime() + seconds * 1_000_000_000; while (System.nanoTime() < end) { idle(); if (done()) return true; Thread.sleep(10) }; return done() }
    private fun elapse(ms: Long, stopEarly: () -> Boolean) { val end = System.nanoTime() + ms * 1_000_000; while (System.nanoTime() < end && !stopEarly()) { idle(); Thread.sleep(10) } }
    private fun thread(name: String, body: () -> Unit) = Thread({ try { body() } catch (e: Throwable) { errors += e } }, name).apply { isDaemon = true; start() }
    private fun until(due: Long, running: () -> Boolean) { while (running()) { val left = due - System.nanoTime(); if (left <= 0) return; if (left > 2_500_000) Thread.sleep(1) else Thread.onSpinWait() } }
    /** A distinct, never-zero PCM16 sample per (stream, index): a hole, repeat or reorder cannot match. */
    private fun sample(stream: Int, s: Long): Int = ((((s + stream * 1_000_003L) * -7046029254386353131L) ushr 49).toInt() and 0x7fff).coerceAtLeast(1)
    private fun pcm(stream: Int, first: Long, bytes: Int) = ByteArray(bytes).also { b -> for (i in 0 until bytes / 2) { val v = sample(stream, first + i); b[2 * i] = v.toByte(); b[2 * i + 1] = (v ushr 8).toByte() } }
    private fun downPcm(j: Int) = pcm(100, j * 480L, 960)
    private fun downFrame(j: Int) = ByteArrayOutputStream().also { WatchRelayAudio.write(DataOutputStream(it), WatchRelayAudio.DOWNLINK, LivePcmFrame(j + 1L, 48000, 1, downPcm(j))) }.toByteArray()
    private fun concat(parts: List<ByteArray>) = ByteArrayOutputStream().also { o -> parts.forEach { o.write(it) } }.toByteArray()
    private fun played() = synchronized(rendered) { rendered.toByteArray() }
    private fun account(name: String, vararg pairs: Pair<String, Any?>) = println("R40-ACCOUNT " + JSONObject().put("test", name).apply { pairs.forEach { (k, v) -> put(k, v) } })
    private fun rate() = captureFormat!!.sampleRate
    private fun packetNs() = WatchRelayAudio.FRAME_BYTES / 2 * 1_000_000_000L / rate()

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as WatchApp
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val address = WatchRelayAudio.Address("duplex-control", "media", "epoch", "stream")
        mockkStatic(Wearable::class)
        val client = mockk<ChannelClient>(relaxed = true); val channel = mockk<ChannelClient.Channel>()
        every { channel.nodeId } returns "phone"; every { channel.path } returns address.path()
        every { client.openChannel(any(), any()) } returns Tasks.forResult(channel)
        every { client.getInputStream(channel) } answers { Tasks.forResult(link!!.down.input) }
        every { client.getOutputStream(channel) } answers { Tasks.forResult(link!!.up.output) }
        every { client.close(channel) } returns Tasks.forResult(null)
        every { Wearable.getChannelClient(any<android.content.Context>()) } returns client
        Class.forName("android.media.AudioRecord"); Class.forName("android.media.AudioTrack")
        mockkStatic(AudioRecord::class); every { AudioRecord.getMinBufferSize(any(), any(), any()) } answers { firstArg<Int>() / 50 * 2 } // 20 ms of mono PCM16
        mockkConstructor(AudioRecord.Builder::class); record = mockk(relaxed = true)
        every { anyConstructed<AudioRecord.Builder>().setAudioSource(any()) } answers { self as AudioRecord.Builder }
        every { anyConstructed<AudioRecord.Builder>().setAudioFormat(any()) } answers { captureFormat = firstArg(); self as AudioRecord.Builder }
        every { anyConstructed<AudioRecord.Builder>().setBufferSizeInBytes(any()) } answers { recorderBuffer = firstArg(); self as AudioRecord.Builder }
        every { anyConstructed<AudioRecord.Builder>().build() } returns record
        every { record.state } returns AudioRecord.STATE_INITIALIZED
        every { record.recordingState } returns AudioRecord.RECORDSTATE_RECORDING
        every { record.audioSessionId } returns 40
        every { record.startRecording() } answers { stopped = false; recordings++; recordBase = recorded.get(); recordStart = System.nanoTime(); Unit }
        every { record.read(any<ByteArray>(), any(), any(), any()) } answers {
            if (stopped) return@answers AudioRecord.ERROR_INVALID_OPERATION
            val off = secondArg<Int>(); val want = thirdArg<Int>(); val from = recorded.get() - recordBase
            val due = recordStart + (from + want) * 1_000_000_000L / (2L * rate())
            while (!stopped && System.nanoTime() < due) Thread.sleep(1)
            if (stopped) return@answers AudioRecord.ERROR_INVALID_OPERATION
            pcm(10 + recordings, from / 2, want).copyInto(firstArg<ByteArray>(), off); recorded.addAndGet(want.toLong()); want
        }
        every { record.stop() } answers { stopped = true; Unit }
        every { record.release() } answers { released.incrementAndGet(); Unit }
        mockkStatic(AudioTrack::class); every { AudioTrack.getMinBufferSize(any(), any(), any()) } returns 3840
        mockkConstructor(AudioTrack.Builder::class); player = mockk(relaxed = true)
        every { anyConstructed<AudioTrack.Builder>().setAudioAttributes(any()) } answers { self as AudioTrack.Builder }
        every { anyConstructed<AudioTrack.Builder>().setAudioFormat(any()) } answers { playerFormat = firstArg(); self as AudioTrack.Builder }
        every { anyConstructed<AudioTrack.Builder>().setTransferMode(any()) } answers { self as AudioTrack.Builder }
        every { anyConstructed<AudioTrack.Builder>().setBufferSizeInBytes(any()) } answers { playerBuffer = firstArg(); self as AudioTrack.Builder }
        every { anyConstructed<AudioTrack.Builder>().build() } returns player
        every { player.state } returns AudioTrack.STATE_INITIALIZED
        every { player.play() } answers { synchronized(rendered) { level = 0; levelAt = System.nanoTime() }; Unit }
        every { player.write(any<ByteArray>(), any(), any(), any()) } answers {
            synchronized(rendered) {
                val now = System.nanoTime(); val perSecond = 2L * playerFormat!!.sampleRate; val drained = (now - levelAt) * perSecond / 1_000_000_000L
                if (drained >= level) { level = 0; levelAt = now } else { level -= drained; levelAt += drained * 1_000_000_000L / perSecond }
                val n = minOf(thirdArg<Int>().toLong(), playerBuffer - level).toInt().coerceAtLeast(0)
                rendered.write(firstArg<ByteArray>(), secondArg<Int>(), n); level += n; n
            }
        }
        media = WatchChannelMedia(app, "phone", address, { true }, { true }, { failures.incrementAndGet(); media.close() })
        media.observeMicrophone { microphone += it }
    }
    @After fun cleanup() {
        stops.forEach { runCatching { it() } }; if (::media.isInitialized) media.close(); link?.close()
        idle(); app.scope.coroutineContext[Job]?.cancel(); unmockkAll()
    }
    private fun connect(bytesPerSecond: Long, held: Boolean = false) {
        link = R40Link(bytesPerSecond).also { it.held = held }
        runBlocking { media.connect({ offers += it; "phone-relay-ready" }, {}) }; media.output(true)
        if (!await { recorded.get() > 0 }) throw Setup("the real capture loop did not read the recorder")
    }
    /** The Phone's side of the link (see the class comment). [provider] off: it only reads the uplink. */
    private inner class Phone(val provider: Boolean = true) {
        val uplink = ConcurrentLinkedQueue<LivePcmFrame>(); val fed = AtomicInteger(); val sent = AtomicInteger()
        @Volatile var congested = false; @Volatile var running = true
        private val queue = ArrayBlockingQueue<LivePcmFrame>(32)
        fun run(): Phone {
            stops += { running = false }
            thread("r40-phone-up") { val input = DataInputStream(link!!.up.input); try { while (running) uplink += WatchRelayAudio.read(input, WatchRelayAudio.UPLINK) } catch (_: IOException) {} }
            if (!provider) return this
            val t0 = System.nanoTime()
            thread("r40-phone-provider") { var j = 0; while (running) { until(t0 + j * 10_000_000L) { running }; if (!running) break
                if (!queue.offer(LivePcmFrame(j + 1L, 48000, 1, downPcm(j)))) { congested = true; break }; fed.incrementAndGet(); j++ } }
            thread("r40-phone-down") { val out = DataOutputStream(link!!.down.output)
                try { while (running) { val f = queue.poll(20, TimeUnit.MILLISECONDS) ?: continue; WatchRelayAudio.write(out, WatchRelayAudio.DOWNLINK, f); sent.incrementAndGet() } } catch (_: IOException) {} }
            return this
        }
    }
    /** Packets of one capture epoch: contiguous counters from 1, the recorder's exact bytes in order, the recorded format. */
    private fun assertExactUplink(frames: List<LivePcmFrame>, epoch: Long, recording: Int) {
        frames.forEachIndexed { i, f ->
            assertEquals("capture epoch", epoch, WatchRelayAudio.captureEpoch(f.sequence)); assertEquals("no sequence hole", i + 1L, WatchRelayAudio.counter(f.sequence))
            assertEquals(rate(), f.sampleRate); assertEquals(1, f.channels)
            assertArrayEquals("exact microphone PCM, packet ${i + 1}", pcm(10 + recording, i.toLong() * WatchRelayAudio.FRAME_BYTES / 2, WatchRelayAudio.FRAME_BYTES), f.pcm)
        }
    }
    private fun assertExactRendered(bytes: ByteArray) = assertArrayEquals("exact provider PCM rendered in order", concat((0..bytes.size / 960).map { downPcm(it) }).copyOf(bytes.size), bytes)
    private fun duplex(name: String, bytesPerSecond: Long): Triple<Long, Phone, Boolean> {
        connect(bytesPerSecond); val phone = Phone().run(); val began = System.nanoTime()
        elapse(RUN_MS) { failures.get() > 0 || phone.congested }
        val ranMs = (System.nanoTime() - began) / 1_000_000; val congested = phone.congested; val loss = media.lossStep
        phone.running = false; media.close(); idle()
        val l = link!!; val read = (recorded.get() - recordBase)
        account(name, "envelope_bytes_per_s" to bytesPerSecond, "ran_ms" to ranMs, "capture_rate" to rate(), "packet_bytes" to WatchRelayAudio.FRAME_BYTES,
            "recorded_bytes" to read, "up_write_calls" to l.up.calls.get(), "up_bytes_written" to l.up.written.get(), "up_bytes_crossed" to l.up.crossed.get(),
            "up_packets_decoded" to phone.uplink.size, "provider_frames" to phone.fed.get(), "down_frames_written" to phone.sent.get(),
            "down_write_calls" to l.down.calls.get(), "down_bytes_crossed" to l.down.crossed.get(), "rendered_bytes" to played().size,
            "watch_loss" to loss?.name, "phone_downlink_congested" to congested)
        return Triple(ranMs, phone, congested)
    }

    // ── sustained full duplex at the prospective envelope ────────────────────────────────────────────────────────

    @Test fun W01_sustainedDuplexAtTheEnvelopeKeepsTheWatchQueueBoundedAndEverySampleInOrderBothWays() {
        val (ranMs, phone, congested) = duplex("W01", ENVELOPE)
        assertEquals("no Watch loss at the envelope (n44 is CAPTURE_OVERFLOW)", null, media.lossStep)
        assertFalse("the Phone's 32-frame downlink queue never overflowed", congested); assertEquals(0, failures.get())
        assertTrue("thread errors: ${errors.toList()}", errors.isEmpty())
        val frames = phone.uplink.toList(); assertExactUplink(frames, 0, 1)
        assertTrue("sustained uplink: ${frames.size} packets in $ranMs ms", frames.size >= ranMs * 1_000_000 / packetNs() * 85 / 100)
        val bytes = played(); assertExactRendered(bytes)
        assertTrue("sustained downlink: ${bytes.size / 960} frames rendered in $ranMs ms", bytes.size / 960 >= ranMs / 10 * 85 / 100)
        assertTrue("only the bounded queue is in flight: ${phone.fed.get()} fed, ${bytes.size / 960} rendered", phone.fed.get() - bytes.size / 960 <= 34)
    }

    @Test fun W02_belowTheDuplexDemandTheCallEndsExplicitlyWithEveryDeliveredSampleExact() {
        val (_, phone, congested) = duplex("W02", BELOW)
        assertTrue("a link below the duplex demand ends explicitly: Watch n44 or the Phone's downlink congestion (${media.lossStep})",
            congested || media.lossStep == WatchLiveSetupStep.CAPTURE_OVERFLOW)
        assertTrue("thread errors: ${errors.toList()}", errors.isEmpty())
        assertExactUplink(phone.uplink.toList(), 0, 1); assertExactRendered(played())
    }

    // ── formats and bounds ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun W03_theWatchRecordsSpeechGrade16kHzMonoIn20msPacketsAndPlaysThe48kHzDownlinkUnchanged() {
        connect(FAST); val phone = Phone().run()
        assertTrue("duplex frames flowed", await { phone.uplink.size >= 5 && played().size >= 5 * 960 })
        val f = captureFormat!!
        assertEquals("AudioRecord at the speech-grade rate", 16000, f.sampleRate)
        assertEquals(AudioFormat.CHANNEL_IN_MONO, f.channelMask); assertEquals(AudioFormat.ENCODING_PCM_16BIT, f.encoding)
        val up = phone.uplink.first(); assertEquals("uplink header rate", 16000, up.sampleRate); assertEquals("a 20 ms packet", 640, up.pcm.size)
        assertEquals("the recorder buffer is still 40 ms of audio", 40, recorderBuffer * 1000 / (2 * f.sampleRate))
        assertEquals("one offer naming the current wire", listOf(WatchRelayAudio.OFFER), offers.toList())
        assertNotEquals("not the raw 48 kHz relay", "phone-relay/pcm16/48000/mono", offers.single())
        val p = playerFormat!!; assertEquals(48000, p.sampleRate); assertEquals(AudioFormat.CHANNEL_OUT_MONO, p.channelMask)
        assertEquals("the player buffer is unchanged: 80 ms", 7680, playerBuffer)
        assertEquals(null, media.lossStep)
    }

    @Test fun W04_aStalledLinkEndsTheCallWithin320msOfQueuedAudioAsBefore() {
        connect(FAST, held = true) // the link is stalled before the first packet
        assertTrue("the call ends on the full queue", await { failures.get() == 1 })
        assertEquals(WatchLiveSetupStep.CAPTURE_OVERFLOW, media.lossStep)
        val readMs = (recorded.get() - recordBase) * 1000 / (2 * rate()); val packetMs = packetNs() / 1_000_000; val bufferMs = 2048L * 1000 / (2 * rate())
        assertTrue("bounded latency: $readMs ms read before the end (queue 320 ms + 2 packets + the link's send buffer)", readMs <= 320 + 2 * packetMs + bufferMs + 10)
        assertTrue(await { released.get() >= 1 }); assertEquals(false, microphone.last())
    }

    // ── downlink partial, burst and EOF ──────────────────────────────────────────────────────────────────────────

    @Test fun W05_downlinkBytesSplitAnywhereAndABurstWithinThePlayerBufferRenderExactlyInOrder() {
        connect(FAST); Phone(provider = false).run(); val out = link!!.down.output
        out.write(concat((0 until 6).map { downFrame(it) })); out.flush() // 60 ms at once, inside the 80 ms player buffer
        assertTrue("the burst rendered", await { played().size >= 6 * 960 })
        Thread.sleep(80)
        val split = concat((6 until 12).map { downFrame(it) }); var at = 0
        while (at < split.size) { val len = minOf(5, split.size - at); out.write(split, at, len); at += len }
        out.flush()
        assertTrue("the split frames rendered", await { played().size >= 12 * 960 })
        assertArrayEquals(concat((0 until 12).map { downPcm(it) }), played())
        assertEquals(null, media.lossStep); assertEquals(0, failures.get())
    }

    @Test fun W06_aDownlinkBurstBeyondThePlayerBufferEndsTheCallExplicitlyNeverDroppingSilently() {
        connect(FAST); Phone(provider = false).run()
        link!!.down.output.write(concat((0 until 12).map { downFrame(it) })) // 120 ms at once, beyond the 80 ms player buffer
        assertTrue("the call ends", await { failures.get() == 1 })
        assertEquals(WatchLiveSetupStep.OUTPUT_PLAYBACK, media.lossStep)
        val bytes = played(); assertTrue(bytes.size < 12 * 960); assertExactRendered(bytes)
    }

    @Test fun W07_aDownlinkEndingMidFrameEndsTheCallAndRendersNoPartialFrame() {
        connect(FAST); Phone(provider = false).run(); val out = link!!.down.output
        out.write(downFrame(0)); out.write(downFrame(1)); val cut = downFrame(2); out.write(cut, 0, 28 + 480); out.flush()
        assertTrue(await { played().size >= 2 * 960 }); Thread.sleep(100); link!!.down.close()
        assertTrue("EOF ends the call", await { failures.get() == 1 })
        assertEquals(WatchLiveSetupStep.RELAY_DOWNLINK, media.lossStep)
        assertArrayEquals("exactly the two whole frames", concat(listOf(downPcm(0), downPcm(1))), played())
    }

    // ── Stop during duplex ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun W08_stopDuringSustainedDuplexWritesAndRendersNothingMoreAndReleasesTheDevices() {
        connect(ENVELOPE); val phone = Phone().run(); elapse(1_000) { failures.get() > 0 }
        assertTrue("duplex flowed", phone.uplink.isNotEmpty() && played().isNotEmpty())
        media.close(); idle(); Thread.sleep(50)
        val calls = link!!.up.calls.get(); val bytes = played().size
        Thread.sleep(300); idle()
        assertEquals("no uplink write after Stop", calls, link!!.up.calls.get()); assertEquals("nothing rendered after Stop", bytes, played().size)
        assertEquals("Stop is not a loss", null, media.lossStep); assertEquals(0, failures.get())
        assertTrue(await { released.get() >= 1 }); assertEquals(false, microphone.last())
        verify { player.stop() }; verify { player.release() }
        assertTrue("thread errors: ${errors.toList()}", errors.isEmpty())
    }

    companion object {
        /** Prospective envelope: one link sharing 160 kB/s across both directions (R40 duplex demand 132.2 kB/s; T39 197.6 kB/s). */
        const val ENVELOPE = 160_000L
        /** Below the R40 duplex demand. */
        const val BELOW = 110_000L
        const val FAST = 20_000_000L
        const val RUN_MS = 6_000L
    }
}
