package com.rumi.hermesvoice.watch

import android.Manifest
import android.media.AudioRecord
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.live.LivePcmFrame
import com.rumi.hermesvoice.core.live.WatchRelayAudio
import io.mockk.*
import kotlinx.coroutines.*
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * R39: the real default Watch uplink, AudioRecord -> WatchChannelMedia's bounded 32-frame queue -> the real WatchRelayAudio codec ->
 * the Wear ChannelClient output stream, against the n44 CAPTURE_OVERFLOW the Watch38 trial ended with. Platform doubles only: the
 * recorder delivers one distinct FRAME_BYTES frame per frame duration of real time at the rate the production asked for (R40: 20 ms at
 * 16 kHz; before: 10 ms at 48 kHz), optionally after a startup backlog, and each test chooses the stream's cost (per write call, per byte,
 * or held). Every byte the stream accepted is decoded with the real codec. R40: bounds and counts are stated in time (the queue is
 * 320 ms), so each test keeps its meaning at either frame duration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class WatchLiveUplinkWriterR39RoboTest {
    private class Setup(message: String) : IllegalStateException(message)
    /** The Wear stream double: every accepted write call and its length; [perCallNanos] and [perByteNanos] are its cost; [gate] holds writes. */
    private class Stream(val perCallNanos: Long = 0, val perByteNanos: () -> Long = { 0 }) : OutputStream() {
        private val bytes = ByteArrayOutputStream(); val calls = ConcurrentLinkedQueue<Int>(); val closes = AtomicInteger()
        @Volatile var closed = false; @Volatile var gate: CountDownLatch? = null; @Volatile var lateWrites = 0
        private fun spin(nanos: Long) { val end = System.nanoTime() + nanos; while (System.nanoTime() < end) Thread.onSpinWait() }
        private fun accept(b: ByteArray, off: Int, len: Int) {
            if (closed) { lateWrites++; throw IOException("stream closed") }
            gate?.await(10, TimeUnit.SECONDS)
            if (closed) throw IOException("stream closed")
            spin(perCallNanos + perByteNanos() * len)
            if (closed) throw IOException("stream closed")
            synchronized(bytes) { bytes.write(b, off, len) }; calls += len
        }
        override fun write(b: Int) = accept(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) = accept(b, off, len)
        override fun close() { closes.incrementAndGet(); closed = true; gate?.countDown() }
        fun wire(): ByteArray = synchronized(bytes) { bytes.toByteArray() }
        /** The complete frames accepted so far, decoded with the real codec (a trailing partial frame is not a frame). */
        fun frames(): List<LivePcmFrame> {
            val input = DataInputStream(ByteArrayInputStream(wire())); val out = ArrayList<LivePcmFrame>()
            while (input.available() >= WIRE) out += WatchRelayAudio.read(input, WatchRelayAudio.UPLINK)
            return out
        }
    }
    private lateinit var app: WatchApp
    private lateinit var media: WatchChannelMedia
    private lateinit var record: AudioRecord
    private lateinit var stream: Stream
    private val reads = AtomicInteger(); private val failures = AtomicInteger(); private val released = AtomicInteger()
    private val microphone = ConcurrentLinkedQueue<Boolean>()
    private val sourceRelease = CountDownLatch(1)
    @Volatile private var stopped = false; @Volatile private var scheduleStart = 0L; @Volatile private var scheduleBase = 0
    private var backlogMs = 0L; private var shortReads = false
    @Volatile private var rate = 0
    /** One frame's duration at the recorded rate, and the writer queue's bound in frames (320 ms). */
    private fun frameNs() = WatchRelayAudio.FRAME_BYTES / 2 * 1_000_000_000L / rate
    private fun framesIn(ms: Long) = (ms * 1_000_000 / frameNs()).toInt()
    private val queueFrames get() = framesIn(320)
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
    private fun await(seconds: Long = 5, done: () -> Boolean): Boolean { val end = System.nanoTime() + seconds * 1_000_000_000; while (System.nanoTime() < end) { idle(); if (done()) return true; Thread.sleep(10) }; return done() }
    /** Real time passes with the main looper served (the media reports a loss through app.scope). */
    private fun elapse(millis: Long, stopEarly: () -> Boolean = { false }) { val end = System.nanoTime() + millis * 1_000_000; while (System.nanoTime() < end && !stopEarly()) { idle(); Thread.sleep(10) } }
    /** Frame [k] of the recorder: its index in the first four bytes, then a pattern; distinct per frame. */
    private fun pcm(k: Int) = ByteArray(WatchRelayAudio.FRAME_BYTES) { i -> if (i < 4) (k ushr (24 - 8 * i)).toByte() else ((k * 7 + i) % 251).toByte() }
    private fun index(frame: LivePcmFrame) = (0 until 4).fold(0) { acc, i -> (acc shl 8) or (frame.pcm[i].toInt() and 255) }

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as WatchApp
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val address = WatchRelayAudio.Address("uplink-control", "media", "epoch", "stream")
        mockkStatic(Wearable::class)
        val client = mockk<ChannelClient>(relaxed = true); val channel = mockk<ChannelClient.Channel>()
        every { channel.nodeId } returns "phone"; every { channel.path } returns address.path()
        val source = object : InputStream() { override fun read(): Int { sourceRelease.await(30, TimeUnit.SECONDS); return -1 }; override fun close() { sourceRelease.countDown() } }
        every { client.openChannel(any(), any()) } returns Tasks.forResult(channel)
        every { client.getInputStream(channel) } returns Tasks.forResult(source as InputStream)
        every { client.getOutputStream(channel) } answers { Tasks.forResult(stream as OutputStream) }
        every { client.close(channel) } returns Tasks.forResult(null)
        every { Wearable.getChannelClient(any<android.content.Context>()) } returns client
        Class.forName("android.media.AudioRecord")
        mockkStatic(AudioRecord::class); every { AudioRecord.getMinBufferSize(any(), any(), any()) } returns 3840
        mockkConstructor(AudioRecord.Builder::class); record = mockk(relaxed = true)
        every { anyConstructed<AudioRecord.Builder>().setAudioSource(any()) } answers { self as AudioRecord.Builder }
        every { anyConstructed<AudioRecord.Builder>().setAudioFormat(any()) } answers { rate = firstArg<android.media.AudioFormat>().sampleRate; self as AudioRecord.Builder }
        every { anyConstructed<AudioRecord.Builder>().setBufferSizeInBytes(any()) } answers { self as AudioRecord.Builder }
        every { anyConstructed<AudioRecord.Builder>().build() } returns record
        every { record.state } returns AudioRecord.STATE_INITIALIZED
        every { record.recordingState } returns AudioRecord.RECORDSTATE_RECORDING
        every { record.audioSessionId } returns 39
        every { record.startRecording() } answers { stopped = false; scheduleBase = reads.get(); scheduleStart = System.nanoTime(); Unit }
        // READ_BLOCKING at the microphone's own pace: frame k is ready one frame duration after frame k-1; a startup backlog is ready at once.
        // [shortReads]: each read returns at most half a frame, as READ_BLOCKING may; the capture loop assembles whole frames.
        every { record.read(any<ByteArray>(), any(), any(), any()) } answers {
            if (stopped) return@answers AudioRecord.ERROR_INVALID_OPERATION
            val off = secondArg<Int>(); val want = thirdArg<Int>()
            val k = if (off == 0) reads.getAndIncrement().also { k ->
                val due = scheduleStart + (k - scheduleBase - framesIn(backlogMs) + 1).coerceAtLeast(0) * frameNs()
                while (!stopped && System.nanoTime() < due) Thread.sleep(1)
            } else reads.get() - 1
            if (stopped) return@answers AudioRecord.ERROR_INVALID_OPERATION
            val len = if (shortReads) minOf(want, WatchRelayAudio.FRAME_BYTES / 2) else want
            pcm(k).copyInto(firstArg<ByteArray>(), off, off, off + len); len
        }
        every { record.stop() } answers { stopped = true; Unit }
        every { record.release() } answers { released.incrementAndGet(); Unit }
        media = WatchChannelMedia(app, "phone", address, { true }, { true }, { failures.incrementAndGet(); media.close() })
        media.observeMicrophone { microphone += it }
    }
    @After fun cleanup() {
        if (::media.isInitialized) media.close(); sourceRelease.countDown(); if (::stream.isInitialized) stream.gate?.countDown()
        idle(); app.scope.coroutineContext[Job]?.cancel(); unmockkAll()
    }
    private fun connect() { runBlocking { media.connect({ "phone-relay-ready" }, {}) }; if (!await { reads.get() > 0 }) throw Setup("the real capture loop did not read the recorder") }
    /** Frames of one capture epoch: contiguous counters from 1, contiguous recorder frames, exact PCM. */
    private fun assertExact(frames: List<LivePcmFrame>, epoch: Long) {
        frames.forEachIndexed { i, f ->
            assertEquals("capture epoch", epoch, WatchRelayAudio.captureEpoch(f.sequence)); assertEquals("no sequence hole", i + 1L, WatchRelayAudio.counter(f.sequence))
            if (i > 0) assertEquals("no frame dropped or repeated", index(frames[i - 1]) + 1, index(f))
            assertArrayEquals("exact PCM", pcm(index(f)), f.pcm)
        }
    }

    // ── the writer's own cost ────────────────────────────────────────────────────────────────────────────────────

    @Test fun U01_eachUplinkFrameReachesTheWearStreamAsOneWriteOfItsExactCodecBytes() {
        stream = Stream(); connect()
        if (!await { stream.frames().size >= 20 }) throw Setup("no uplink frames")
        media.close(); idle()
        val frames = stream.frames(); assertExact(frames, 0)
        val encoded = ByteArrayOutputStream().also { out -> val d = DataOutputStream(out); frames.forEach { WatchRelayAudio.write(d, WatchRelayAudio.UPLINK, it) } }.toByteArray()
        assertArrayEquals("the wire bytes are exactly the codec's encoding of those frames", encoded, stream.wire().copyOf(encoded.size))
        assertEquals("every write call to the Wear stream carries one whole frame", listOf(WIRE), stream.calls.toList().distinct())
        assertEquals("one stream write per frame", frames.size, stream.calls.size)
    }

    @Test fun U02_aWearStreamThatCostsPerWriteKeepsUpWithTheMicrophone() {
        stream = Stream(perCallNanos = 2_000_000); connect() // 2 ms per write call: 500 writes a second, at least five times the frame rate
        elapse(2_000) { failures.get() > 0 }
        assertEquals("no capture overflow while the stream sustains 500 writes a second", null, media.lossStep); assertEquals(0, failures.get())
        media.close(); idle()
        val frames = stream.frames(); assertTrue("frames delivered: ${frames.size}", frames.size >= framesIn(2_000) * 70 / 100); assertExact(frames, 0)
    }

    @Test fun U03_aStartupRecorderBacklogIsDeliveredInOrderWithinTheBound() {
        backlogMs = 160; stream = Stream(); connect() // 160 ms of startup backlog: half the queue bound
        elapse(600) { failures.get() > 0 }
        assertEquals(null, media.lossStep); assertEquals(0, failures.get())
        media.close(); idle()
        val frames = stream.frames(); assertTrue(frames.size >= framesIn(600) * 2 / 3); assertExact(frames, 0); assertEquals("the backlog came first, in order", 0, index(frames.first()))
    }

    // ── genuine congestion still ends the call honestly, within bounded memory ───────────────────────────────────

    @Test fun U04_aWearStreamSlowerThanRealTimeEndsTheCallHonestlyWithNoHole() {
        stream = Stream(perByteNanos = { frameNs() * 5 / 2 / WIRE }); connect() // a frame takes 2.5 times its own duration to write
        assertTrue("the call ends on the full queue", await { failures.get() == 1 })
        assertEquals(WatchLiveSetupStep.CAPTURE_OVERFLOW, media.lossStep)
        val frames = stream.frames(); assertExact(frames, 0)
        assertTrue("bounded memory: read ${reads.get()}, delivered ${frames.size}", reads.get() - frames.size <= queueFrames + 3)
        assertEquals("nothing written after the end", 0, stream.lateWrites); assertTrue(stream.closes.get() >= 1)
        assertTrue(await { released.get() >= 1 }); assertEquals(false, microphone.last())
    }

    @Test fun U05_aHeldWearStreamIsReleasedByStopWithNoLateWriteAndNoLoss() {
        stream = Stream().also { it.gate = CountDownLatch(1) }; connect()
        if (!await { reads.get() >= 10 }) throw Setup("the recorder did not run")
        media.close(); idle()
        assertEquals("Stop is not a loss", null, media.lossStep); assertEquals(0, failures.get())
        assertEquals("the held write was released by the close, not completed", 0, stream.wire().size); assertEquals(0, stream.lateWrites)
        assertTrue(stream.closes.get() >= 1); assertTrue(await { released.get() >= 1 }); assertEquals(false, microphone.last())
        verify { record.stop() }
    }

    @Test fun U06_aWearStreamHeldBeyondTheQueueBoundEndsTheCallHonestly() {
        stream = Stream().also { it.gate = CountDownLatch(1) }; connect()
        assertTrue("the call ends on the full queue", await { failures.get() == 1 })
        assertEquals(WatchLiveSetupStep.CAPTURE_OVERFLOW, media.lossStep)
        assertTrue("bounded memory: read ${reads.get()}", reads.get() <= queueFrames + 3)
        assertTrue("the failure closed the stream", stream.closes.get() >= 1); assertEquals(0, stream.wire().size); assertEquals(0, stream.lateWrites)
        assertTrue(await { released.get() >= 1 }); assertEquals(false, microphone.last())
    }

    // ── mute epochs ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun U07_muteDropsQueuedOldEpochFramesAndUnmuteStartsANewContiguousEpoch() {
        stream = Stream(); connect()
        if (!await { stream.frames().size >= 10 }) throw Setup("no uplink frames")
        val held = CountDownLatch(1); stream.gate = held; val readAtHold = reads.get()
        if (!await { reads.get() >= readAtHold + 6 }) throw Setup("no frames queued behind the held stream")
        val atHold = stream.frames().size // the writer is now held on its next write; frames read since are queued behind it
        media.mute(true); stream.gate = null; held.countDown()
        elapse(150)
        media.mute(false)
        // Each mute edge is a capture epoch (WatchChannelMedia.mute, WatchRelayInput.mute): one mute and unmute is epoch 2.
        if (!await { stream.frames().count { WatchRelayAudio.captureEpoch(it.sequence) == 2L } >= 10 }) throw Setup("no frames after unmute")
        media.close(); idle()
        val frames = stream.frames(); val old = frames.takeWhile { WatchRelayAudio.captureEpoch(it.sequence) == 0L }; val new = frames.drop(old.size)
        assertExact(old, 0); assertExact(new, 2)
        assertTrue("the frames queued at mute were dropped (only the one in flight completed): ${old.size} vs $atHold", old.size <= atHold + 1)
        assertEquals(null, media.lossStep); assertEquals(0, failures.get())
    }

    @Test fun U08_shortRecorderReadsAreAssembledIntoExactWholeFrames() {
        shortReads = true; stream = Stream(); connect()
        elapse(500) { failures.get() > 0 }
        assertEquals(null, media.lossStep); assertEquals(0, failures.get())
        media.close(); idle()
        val frames = stream.frames(); assertTrue(frames.size >= framesIn(500) * 6 / 10); assertExact(frames, 0)
    }

    companion object { const val WIRE = 28 + WatchRelayAudio.FRAME_BYTES }
}
