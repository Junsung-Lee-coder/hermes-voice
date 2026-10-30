package com.rumi.hermesvoice.phone

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.live.LivePcmFrame
import com.rumi.hermesvoice.core.live.WatchLiveWire
import com.rumi.hermesvoice.core.live.WatchRelayAudio
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.phone.live.ExternalLiveInput
import com.rumi.hermesvoice.phone.live.WebRtcLiveTransport
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.*
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * R40: the complete Phone side of Watch-origin full duplex through its actual default adapters. Uplink: Watch bytes -> the real
 * WatchLiveTransport reader and codec -> WatchRelayInput -> the pinned ADM AudioBufferCallback, at the input rate the production gave
 * the pinned Builder. Downlink: provider PCM -> the pinned remote AudioTrackSink -> the real WatchLiveTransport writer -> Watch bytes.
 *
 * Doubles: the Data Layer, the provider and the native WebRTC objects only. The Builder double keeps the pinned semantics (sdk-probe
 * ADM1: setSampleRate sets both rates, setInputSampleRate / setOutputSampleRate one each). [Adm] is the pinned external-input loop
 * (ADM3/ADM4: one direct buffer of 2 * rate / 100 bytes, zero-filled, then onBuffer(buffer, PCM16, 1, rate, capacity, ts)). The paired
 * Watch is THIS release's: it sends the one uplink format the shared codec accepts (R40: 16 kHz, 640-byte 20 ms packets; T39: 48 kHz,
 * 960-byte 10 ms frames) from a real-time recorder through its own 320 ms bounded queue with ONE write per packet (the R39 writer), and
 * decodes the downlink with the real codec. [R40Link] is a prospective envelope, not a measured link speed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchDuplexRelayR40RoboTest : PhoneRoboBase() {
    private class Setup(message: String) : IllegalStateException(message)
    private val packets = ConcurrentLinkedQueue<WatchLiveWire.Packet>()
    private val factories = AtomicInteger()
    private val errors = ConcurrentLinkedQueue<Throwable>()
    private val stops = ConcurrentLinkedQueue<() -> Unit>()
    private var link: R40Link? = null
    @Volatile private var inputCallback: JavaAudioDeviceModule.AudioBufferCallback? = null
    @Volatile private var remoteSink: AudioTrackSink? = null
    @Volatile private var inputRate = 0
    @Volatile private var outputRate = 0
    private var offer: WatchLiveWire.Packet? = null
    private var seq = 0L

    /** THIS release's Watch uplink: the one rate the shared codec accepts for a FRAME_BYTES packet. */
    private val upRate = listOf(16000, 48000).single { WatchRelayAudio.valid(LivePcmFrame(1, it, 1, ByteArray(WatchRelayAudio.FRAME_BYTES)), WatchRelayAudio.UPLINK) }
    private val upBytes = WatchRelayAudio.FRAME_BYTES
    private val upNs = upBytes / 2 * 1_000_000_000L / upRate
    /** The Watch's own bounded uplink queue: 320 ms of packets. */
    private val watchQueue = (320_000_000L / upNs).toInt()

    @Before fun setup() {
        baseSetUp(); shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled = false, watchLiveEnabled = true, liveIdleSeconds = 60))
        app.live.admission = { app.live.admitted(it) }
        val n = mockk<Node>(); every { n.id } returns "watch-node"; val info = mockk<CapabilityInfo>(); every { info.nodes } returns setOf(n)
        val caps = mockk<CapabilityClient>(); every { caps.getCapability(any(), any()) } returns Tasks.forResult(info)
        every { Wearable.getCapabilityClient(any<Context>()) } returns caps
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } answers { WatchLiveWire.parse(thirdArg<ByteArray>())?.let { packets += it }; Tasks.forResult(1) }
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        val channels = mockk<ChannelClient>(relaxed = true); every { Wearable.getChannelClient(any<Context>()) } returns channels
        every { channels.getInputStream(any()) } answers { Tasks.forResult(link!!.up.input) }
        every { channels.getOutputStream(any()) } answers { Tasks.forResult(link!!.down.output) }
        every { channels.close(any()) } returns Tasks.forResult(null)
        mockkStatic(PeerConnectionFactory::class); mockkStatic(JavaAudioDeviceModule::class)
        every { PeerConnectionFactory.initialize(any()) } just Runs
        val module = mockk<JavaAudioDeviceModule>(relaxed = true)
        val b = mockk<JavaAudioDeviceModule.Builder>(relaxed = true)
        every { JavaAudioDeviceModule.builder(any()) } returns b
        every { JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported() } returns false
        every { JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported() } returns false
        every { b.setAudioSource(any()) } returns b; every { b.setAudioAttributes(any()) } returns b
        every { b.setUseHardwareAcousticEchoCanceler(any()) } returns b; every { b.setUseHardwareNoiseSuppressor(any()) } returns b
        every { b.setAudioRecordStateCallback(any()) } returns b; every { b.setSamplesReadyCallback(any()) } returns b
        every { b.setPlaybackSamplesReadyCallback(any()) } returns b; every { b.setEnableVolumeLogger(any()) } returns b
        every { b.setStopRecordingOnMute(any()) } returns b
        // The pinned Builder's own rate fields (sdk-probe ADM1).
        every { b.setSampleRate(any()) } answers { inputRate = firstArg(); outputRate = firstArg(); b }
        every { b.setInputSampleRate(any()) } answers { inputRate = firstArg(); b }
        every { b.setOutputSampleRate(any()) } answers { outputRate = firstArg(); b }
        every { b.setUseStereoInput(any()) } returns b; every { b.setUseStereoOutput(any()) } returns b
        every { b.setAudioBufferCallback(any()) } answers { inputCallback = firstArg(); b }
        every { b.createAudioDeviceModule() } returns module
        val factory = mockk<PeerConnectionFactory>(relaxed = true); val fb = mockk<PeerConnectionFactory.Builder>(relaxed = true)
        every { PeerConnectionFactory.builder() } returns fb; every { fb.setAudioDeviceModule(any()) } returns fb
        every { fb.createPeerConnectionFactory() } answers { factories.incrementAndGet(); factory }
        val peer = mockk<PeerConnection>(relaxed = true); var observer: PeerConnection.Observer? = null; var events: DataChannel.Observer? = null
        every { factory.createPeerConnection(any<PeerConnection.RTCConfiguration>(), any<PeerConnection.Observer>()) } answers { observer = secondArg(); peer }
        every { factory.createAudioSource(any()) } returns mockk(relaxed = true)
        every { factory.createAudioTrack(any(), any()) } returns mockk(relaxed = true)
        every { peer.createOffer(any(), any()) } answers { firstArg<SdpObserver>().onCreateSuccess(SessionDescription(SessionDescription.Type.OFFER, "v=0\r\nPHONE-OWNED\r\n")) }
        every { peer.setLocalDescription(any(), any()) } answers { firstArg<SdpObserver>().onSetSuccess(); observer!!.onIceGatheringChange(PeerConnection.IceGatheringState.COMPLETE) }
        every { peer.localDescription } returns SessionDescription(SessionDescription.Type.OFFER, "v=0\r\nPHONE-OWNED\r\n")
        val data = mockk<DataChannel>(relaxed = true); every { peer.createDataChannel(any(), any()) } returns data
        every { data.registerObserver(any()) } answers { events = firstArg() }; every { data.state() } returns DataChannel.State.OPEN
        every { data.bufferedAmount() } returns 0; every { data.send(any()) } returns true
        val track = mockk<org.webrtc.AudioTrack>(relaxed = true); every { track.addSink(any()) } answers { remoteSink = firstArg() }
        val receiver = mockk<RtpReceiver>(); every { receiver.track() } returns track
        every { peer.setRemoteDescription(any(), any()) } answers {
            firstArg<SdpObserver>().onSetSuccess(); observer!!.onAddTrack(receiver, emptyArray())
            val json = JSONObject().put("type", "session.started").put("session", JSONObject().put("id", "fixture")).toString()
            events?.onMessage(DataChannel.Buffer(ByteBuffer.wrap(json.toByteArray()), false))
        }
        val original = fake.server.dispatcher
        fake.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(r: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                if (r.path == "/api/audio/voice-live/session") return okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"ok\":true,\"session\":{\"id\":\"fixture\"},\"transport\":{\"type\":\"webrtc\",\"sdp\":\"v=0\\r\\nfixture\\r\\n\"}}")
                return original.dispatch(r)
            }
        }
    }
    @After fun cleanup() { stops.forEach { runCatching { it() } }; app.live.end(); link?.close(); settle(); baseTearDown() }

    // ── harness ──────────────────────────────────────────────────────────────────────────────────────────────────

    private fun thread(name: String, body: () -> Unit) = Thread({ try { body() } catch (e: Throwable) { errors += e } }, name).apply { isDaemon = true; start() }
    private fun until(due: Long, running: () -> Boolean) { while (running()) { val left = due - System.nanoTime(); if (left <= 0) return; if (left > 2_500_000) Thread.sleep(1) else Thread.onSpinWait() } }
    private fun elapse(ms: Long, stopEarly: () -> Boolean) { val end = System.nanoTime() + ms * 1_000_000; while (System.nanoTime() < end && !stopEarly()) { idle(20); Thread.sleep(10) } }
    /** A distinct, never-zero PCM16 sample per (stream, index): a hole, repeat or reorder cannot match. */
    private fun sample(stream: Int, s: Long): Int = ((((s + stream * 1_000_003L) * -7046029254386353131L) ushr 49).toInt() and 0x7fff).coerceAtLeast(1)
    private fun pcm(stream: Int, first: Long, bytes: Int) = ByteArray(bytes).also { b -> for (i in 0 until bytes / 2) { val v = sample(stream, first + i); b[2 * i] = v.toByte(); b[2 * i + 1] = (v ushr 8).toByte() } }
    private fun upPcm(k: Int, epoch: Int = 0) = pcm(1 + epoch, k.toLong() * (upBytes / 2), upBytes)
    private fun downPcm(j: Int) = pcm(100, j * 480L, 960)
    /** One uplink packet on the wire, field by field as the Watch release encodes it (counter k + 1 of capture epoch [epoch]). */
    private fun packet(k: Int, epoch: Int = 0, rate: Int = upRate, bytes: ByteArray = upPcm(k, epoch)) = ByteArrayOutputStream(28 + bytes.size).also {
        DataOutputStream(it).apply { writeInt(0x48565233); writeInt(1); writeLong((epoch.toLong() shl 32) or (k + 1L)); writeInt(rate); writeInt(1); writeInt(bytes.size); write(bytes) }
    }.toByteArray()
    private fun silent(b: ByteArray) = b.all { it == 0.toByte() }
    private fun concat(parts: List<ByteArray>) = ByteArrayOutputStream().also { o -> parts.forEach { o.write(it) } }.toByteArray()
    private fun account(name: String, vararg pairs: Pair<String, Any?>) = println("R40-ACCOUNT " + JSONObject().put("test", name).apply { pairs.forEach { (k, v) -> put(k, v) } })
    private fun status() = app.live.state.value.status
    private fun incoming(op: String, body: String = "") { val p = offer!!; app.liveWatch.receive("watch-node", WatchLiveWire.packet(p.id, p.media, p.epoch, ++seq, op, body)) }
    private fun channel(): ChannelClient.Channel {
        val p = offer!!; val c = mockk<ChannelClient.Channel>(); every { c.nodeId } returns "watch-node"
        every { c.path } returns WatchRelayAudio.Address(p.id, p.media, p.epoch, JSONObject(p.body).getString("stream")).path(); return c
    }
    private fun start(bytesPerSecond: Long, offerBody: String = WatchRelayAudio.OFFER) {
        link = R40Link(bytesPerSecond); conversation("alpha")
        app.liveWatch.receive("watch-node", JSONObject().put("v", 1).put("id", "relay-control").put("op", "start").put("probe", "p").toString().toByteArray())
        if (!await { packets.any { it.op == "open" } }) throw Setup("no Watch open"); offer = packets.last { it.op == "open" }
        incoming("offer", offerBody); app.liveWatch.audioChannel(channel())
    }
    private fun ready() {
        if (!await { factories.get() == 1 && inputCallback != null && remoteSink != null }) throw Setup("the Phone provider peer was not built")
        incoming("connected"); if (!await { app.live.state.value.ready }) throw Setup("the call did not become ready: ${status()}")
        incoming("microphone", "true") // the Watch reports its microphone running; WatchRelayInput arms its stall check
    }

    /** The pinned external-input loop at the rate the production gave the Builder. On a device fill() paces it to 10 ms with
     *  LockSupport.parkNanos; a JVM host's park granularity is not a device's, so [run] applies fill()'s own rule itself
     *  (next = max(next + 10 ms, now) after each return), which never leaves fill() an early call to park. [step] is one callback. */
    private inner class Adm(val rate: Int = inputRate) {
        val buffer: ByteBuffer = ByteBuffer.allocateDirect(2 * rate / 100); private val zeros = ByteArray(buffer.capacity())
        val handed = ConcurrentLinkedQueue<ByteArray>(); val starts = ConcurrentLinkedQueue<Long>()
        @Volatile var running = false; private var loop: Thread? = null
        fun step(): ByteArray {
            buffer.clear(); buffer.put(zeros); starts += System.nanoTime()
            inputCallback!!.onBuffer(buffer, AudioFormat.ENCODING_PCM_16BIT, 1, rate, buffer.capacity(), 0)
            return ByteArray(buffer.capacity()).also { buffer.duplicate().apply { clear(); get(it) }; handed += it }
        }
        fun run(): Adm {
            running = true; stops += { stop() }
            loop = thread("r40-adm") { var due = System.nanoTime(); while (running) { until(due) { running }; if (!running) break; step(); due = maxOf(due + 10_000_000L, System.nanoTime()) } }
            return this
        }
        fun stop() { running = false; loop?.join(2000) }
    }
    /** THIS release's paired Watch over the link: a real-time recorder into its 320 ms bounded queue (a refusal is its n44), ONE link
     *  write per packet (the R39 writer), and the downlink decoded with the real codec. */
    private inner class Watch(val epoch: Int = 0) {
        private val queue = ArrayBlockingQueue<ByteArray>(watchQueue)
        val captured = AtomicInteger(); val sent = AtomicInteger(); val down = ConcurrentLinkedQueue<LivePcmFrame>()
        @Volatile var overflow = false; @Volatile var running = true; @Volatile var outOfOrder = false
        fun run(): Watch {
            val t0 = System.nanoTime(); stops += { running = false }
            thread("r40-watch-mic") { var k = 0; while (running) { until(t0 + (k + 1) * upNs) { running }; if (!running) break
                if (!queue.offer(packet(k, epoch))) { overflow = true; running = false; break }; captured.incrementAndGet(); k++ } }
            thread("r40-watch-up") { val out = link!!.up.output
                try { while (running) { val p = queue.poll(20, TimeUnit.MILLISECONDS) ?: continue; out.write(p); out.flush(); sent.incrementAndGet() } } catch (_: IOException) {} }
            thread("r40-watch-down") { val input = DataInputStream(link!!.down.input); var previous = 0L
                try { while (running) { val f = WatchRelayAudio.read(input, WatchRelayAudio.DOWNLINK); if (f.sequence != previous + 1) outOfOrder = true; previous = f.sequence; down += f } } catch (_: IOException) {} }
            return this
        }
    }
    /** The provider's decoded output at the pinned remote sink: 10 ms of 48 kHz mono every 10 ms of real time. */
    private inner class Provider {
        val fed = AtomicInteger(); @Volatile var running = true
        fun run(): Provider {
            val t0 = System.nanoTime(); stops += { running = false }
            thread("r40-provider") { var j = 0; while (running) { until(t0 + j * 10_000_000L) { running }; if (!running) break
                remoteSink!!.onData(ByteBuffer.wrap(downPcm(j)), 16, 48000, 1, 480, 0); fed.incrementAndGet(); j++ } }
            return this
        }
    }
    private class Duplex(val ranMs: Long, val active: Boolean, val status: String, val handed: List<ByteArray>, val starts: List<Long>, val sentPcm: ByteArray, val frames: List<LivePcmFrame>, val fed: Int)
    private fun duplex(name: String, bytesPerSecond: Long): Pair<Duplex, Watch> {
        start(bytesPerSecond); ready()
        val adm = Adm().run(); val watch = Watch().run(); val provider = Provider().run(); val began = System.nanoTime()
        elapse(RUN_MS) { !app.live.active || watch.overflow }
        val ranMs = (System.nanoTime() - began) / 1_000_000; val active = app.live.active; val status = status()
        provider.running = false; watch.running = false; adm.stop(); Thread.sleep(50)
        val handed = adm.handed.toList(); val first = handed.indexOfFirst { !silent(it) }
        val d = Duplex(ranMs, active, status, handed, adm.starts.toList(), concat((0 until watch.sent.get()).map { upPcm(it) }), watch.down.toList(), provider.fed.get())
        val l = link!!
        account(name, "envelope_bytes_per_s" to bytesPerSecond, "ran_ms" to ranMs, "up_rate" to upRate, "up_packet_bytes" to upBytes, "adm_input_rate" to inputRate,
            "adm_output_rate" to outputRate, "adm_buffer_bytes" to adm.buffer.capacity(), "captured_packets" to watch.captured.get(), "written_packets" to watch.sent.get(),
            "up_write_calls" to l.up.calls.get(), "up_bytes_written" to l.up.written.get(), "up_bytes_crossed" to l.up.crossed.get(), "native_callbacks" to handed.size,
            "native_audio_buffers" to handed.count { !silent(it) }, "native_silent_after_first_audio" to (if (first < 0) -1 else handed.drop(first).count { silent(it) }),
            "provider_frames" to provider.fed.get(), "down_write_calls" to l.down.calls.get(), "down_bytes_written" to l.down.written.get(),
            "down_bytes_crossed" to l.down.crossed.get(), "down_frames_decoded" to d.frames.size, "watch_queue_overflow" to watch.overflow, "phone_active" to active)
        return d to watch
    }
    /** Uplink: every Watch sample handed to the native buffer once, in order; downlink: every provider frame in order, exact. */
    private fun assertExactPrefixes(d: Duplex, watch: Watch) {
        assertTrue("thread errors: ${errors.toList()}", errors.isEmpty())
        val audio = concat(d.handed.filter { !silent(it) })
        assertArrayEquals("exact Watch PCM in the native buffers, in order, no hole or repeat", d.sentPcm.copyOf(audio.size), audio)
        assertFalse("downlink sequence in order", watch.outOfOrder)
        d.frames.forEachIndexed { i, f ->
            assertEquals("downlink sequence", i + 1L, f.sequence); assertEquals(48000, f.sampleRate); assertEquals(1, f.channels)
            assertArrayEquals("exact provider PCM, frame $i", downPcm(i), f.pcm)
        }
    }

    // ── sustained full duplex at the prospective envelope ────────────────────────────────────────────────────────

    @Test fun P01_sustainedDuplexAtTheEnvelopeHandsEveryWatchSampleOnceInOrderAndReturnsEveryProviderFrame() {
        val (d, watch) = duplex("P01", ENVELOPE)
        assertTrue("the Phone call stayed up at the envelope (status: ${d.status})", d.active)
        assertFalse("the paired Watch's 320 ms uplink queue never overflowed (its n44)", watch.overflow)
        assertExactPrefixes(d, watch)
        val first = d.handed.indexOfFirst { !silent(it) }; assertTrue("Watch audio reached the native buffer", first >= 0)
        val audio = d.handed.count { !silent(it) } * d.handed[0].size
        assertTrue("handed $audio of ${d.sentPcm.size} bytes: only the bounded in-flight remainder is missing", d.sentPcm.size - audio <= (watchQueue + 2) * upBytes)
        val after = d.handed.size - first; val spanMs = (d.starts.last() - d.starts[first]) / 1_000_000
        assertTrue("the native callback kept its 10 ms media clock: $after callbacks in $spanMs ms", after >= spanMs / 10 * 95 / 100)
        assertTrue("silence after the first audio is jitter padding only", d.handed.drop(first).count { silent(it) } <= 3)
        assertTrue("sustained uplink: ${d.handed.size - first} buffers in ${d.ranMs} ms", after >= d.ranMs / 10 * 85 / 100)
        assertTrue("returned ${d.frames.size} of ${d.fed} provider frames: only the bounded queue is in flight", d.fed - d.frames.size <= 34)
        assertTrue("sustained downlink: ${d.frames.size} frames in ${d.ranMs} ms", d.frames.size >= d.ranMs / 10 * 90 / 100)
    }

    @Test fun P02_belowTheDuplexDemandTheCallEndsExplicitlyAndEveryDeliveredSampleIsStillExact() {
        val (d, watch) = duplex("P02", BELOW)
        assertTrue("a link below the duplex demand ends the call explicitly, never silently thins it (status: ${d.status})", !d.active || watch.overflow)
        assertExactPrefixes(d, watch)
    }

    // ── the native ADM binding ───────────────────────────────────────────────────────────────────────────────────

    @Test fun P03_watchPcmBindsThePinnedAdmInputAt16kHzMonoAndEach20msPacketFillsExactlyTwo10msNativeBuffers() {
        start(FAST); ready()
        assertEquals("the pinned ADM input rate for Watch PCM (setInputSampleRate)", 16000, inputRate)
        assertEquals("the ADM output rate is unchanged", 48000, outputRate)
        val adm = Adm(); assertEquals("the pinned record's 10 ms buffer at that rate: 2 * 16000 / 100", 320, adm.buffer.capacity())
        val out = link!!.up.output; out.write(packet(0)); out.write(packet(1)); out.flush()
        val buffers = (0 until 4).map { adm.step() }
        for (i in 0 until 4) assertArrayEquals("packet ${i / 2 + 1}, 10 ms half ${i % 2 + 1}", upPcm(i / 2).copyOfRange(i % 2 * 320, i % 2 * 320 + 320), buffers[i])
        assertTrue("nothing more queued: bounded-wait silence, never a repeat", silent(adm.step()))
        assertTrue(status(), app.live.active)
        val wrong = ByteBuffer.allocateDirect(960)
        inputCallback!!.onBuffer(wrong, AudioFormat.ENCODING_PCM_16BIT, 1, 48000, 960, 0)
        assertTrue("an unsupported native buffer gets no Watch audio", silent(ByteArray(960).also { wrong.duplicate().apply { clear(); get(it) } }))
        assertTrue("an unsupported native format ends the call, never resampled or guessed", await { !app.live.active })
        assertTrue(status(), status().contains("unsupported native format"))
    }

    @Test fun P04_aDirectPhoneOrHeadsetInputKeepsThePinnedAdmAt48kHzAsBefore() {
        val source = object : ExternalLiveInput {
            override fun fill(buffer: ByteBuffer, format: Int, channels: Int, rate: Int) = 0L
            override fun mute(muted: Boolean) {}
            override fun close() {}
        }
        val media = WebRtcLiveTransport(app, app.wiring().dashboard, source, null)
        CoroutineScope(Dispatchers.IO).launch { runCatching { media.connect(JSONArray()) {} } }
        assertTrue("the provider media built its ADM", await { inputCallback != null })
        media.close()
        assertEquals("headset or Phone capture input rate unchanged", 48000, inputRate); assertEquals(48000, outputRate)
    }

    // ── mixed versions and frame identity ────────────────────────────────────────────────────────────────────────

    @Test fun P05_aWatch39OfferIsRefusedBeforeAnyProviderOrMicrophoneWork() {
        start(FAST, offerBody = "phone-relay/pcm16/48000/mono")
        assertTrue("a mixed pair ends", await { !app.live.active })
        assertTrue(status(), status().contains("Watch media version unavailable"))
        assertEquals("no provider peer for a mixed pair", 0, factories.get()); assertTrue(fake.prompts.isEmpty())
    }

    @Test fun P06_aRaw48kHzWatchFrameOnTheCurrentStreamIsRefusedAndNeverHanded() {
        start(FAST); ready(); val adm = Adm()
        link!!.up.output.write(packet(0, rate = 48000, bytes = ByteArray(960) { 9 }))
        assertTrue("a frame in another format ends the call", await { !app.live.active })
        assertTrue(status(), status().contains("Watch input channel unavailable, malformed or congested"))
        assertTrue("nothing of it reached the native buffer", silent(adm.step()))
    }

    // ── burst, partial and EOF on the uplink ─────────────────────────────────────────────────────────────────────

    @Test fun P07_aBurstWithinThe320msBoundAndArbitrarilySplitWritesAreHandedExactlyInOrder() {
        start(FAST); ready(); val adm = Adm().run(); val out = link!!.up.output
        val n = (240_000_000L / upNs).toInt() // 240 ms of Watch audio at once
        fun handed() = adm.handed.count { !silent(it) } * adm.buffer.capacity()
        out.write(concat((0 until n).map { packet(it) })); out.flush()
        assertTrue("the burst was handed", await { handed() >= n * upBytes })
        val split = concat((n until 2 * n).map { packet(it) }); var at = 0
        while (at < split.size) { val len = minOf(7, split.size - at); out.write(split, at, len); at += len }
        out.flush()
        assertTrue("the split packets were handed", await { handed() >= 2 * n * upBytes })
        adm.stop()
        assertTrue(status(), app.live.active)
        assertArrayEquals(concat((0 until 2 * n).map { upPcm(it) }), concat(adm.handed.filter { !silent(it) }))
    }

    @Test fun P08_aBurstBeyondThe320msBoundIsAnExplicitEndNeverASilentDrop() {
        start(FAST); ready()
        val n = (400_000_000L / upNs).toInt() // 400 ms at once with nothing draining it
        // The Phone ends and closes the channel mid-burst, so the rest of the burst may no longer be writable (as on a Watch).
        runCatching { link!!.up.output.write(concat((0 until n).map { packet(it) })); link!!.up.output.flush() }
        assertTrue("an over-bound burst ends the call", await { !app.live.active })
        assertTrue(status(), status().contains("Watch input channel unavailable, malformed or congested"))
    }

    @Test fun P09_aStreamEndingMidPacketEndsTheCallAndNoPartOfThatPacketIsHanded() {
        start(FAST); ready(); val adm = Adm(); val out = link!!.up.output
        out.write(packet(0)); val cut = packet(1); out.write(cut, 0, 28 + upBytes / 2); out.flush()
        assertArrayEquals(upPcm(0).copyOfRange(0, adm.buffer.capacity()), adm.step())
        Thread.sleep(100); link!!.up.close()
        assertTrue("EOF ends the call", await { !app.live.active })
        assertTrue(status(), status().contains("Watch input channel unavailable, malformed or congested"))
        repeat(3) { adm.step() }
        val partial = upPcm(1)
        assertTrue("no part of the cut packet was handed", adm.handed.none { h -> partial.copyOfRange(0, h.size).contentEquals(h) || partial.copyOfRange(partial.size - h.size, partial.size).contentEquals(h) })
    }

    // ── mute epochs and Stop with a held half ────────────────────────────────────────────────────────────────────

    @Test fun P10_muteNeverHandsTheHeldHalfOrAnOlderEpochAndStopEndsAllAudio() {
        start(FAST); ready(); val adm = Adm(); val out = link!!.up.output; val cap = adm.buffer.capacity()
        out.write(packet(0)); out.flush()
        assertArrayEquals(upPcm(0).copyOfRange(0, cap), adm.step())
        app.live.setMuted(true) // capture epoch 1 on both apps
        out.write(packet(1)); out.flush(); Thread.sleep(100) // in flight from before the Watch saw the mute
        assertTrue("muted: neither the held half nor the late older-epoch packet", (0 until 3).map { adm.step() }.all { silent(it) })
        app.live.setMuted(false) // capture epoch 2
        out.write(packet(0, epoch = 2)); out.write(packet(1, epoch = 2)); out.flush()
        assertArrayEquals("only the new epoch, exact", concat(listOf(upPcm(0, 2), upPcm(1, 2))), concat((0 until 2 * upBytes / cap).map { adm.step() }))
        out.write(packet(2, epoch = 2)); out.flush()
        assertArrayEquals(upPcm(2, 2).copyOfRange(0, cap), adm.step())
        app.live.end(); settle()
        assertTrue("after Stop nothing more is handed, not even a held half", (0 until 2).map { adm.step() }.all { silent(it) })
        assertTrue(status(), status().startsWith("Live ended"))
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
