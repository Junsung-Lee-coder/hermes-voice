package com.rumi.hermesvoice.phone

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.diag.DiagEvent
import com.rumi.hermesvoice.core.diag.DiagOrigin
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * R41: the Phone side of a started Watch-origin call through its actual default adapters, at the boundary the R40 trial ended on: the
 * Phone reached Started, then ended itself 1.2 s later (failure, cause unknown) with no nonzero Watch input handed and nothing in the
 * Watch journal. Started is when the Phone begins the continuous provider downlink, so from then on both directions share the paired
 * channel (R40: 33.4 kB/s up plus 98.8 kB/s down). Uplink: Watch bytes -> the real WatchLiveTransport reader and shared codec ->
 * WatchRelayInput -> the pinned ADM AudioBufferCallback. Downlink: provider PCM at the pinned remote AudioTrackSink -> the real
 * WatchLiveTransport conversion and writer -> Watch bytes, decoded with the shared codec.
 *
 * Doubles: the Data Layer, the provider and the native WebRTC objects only, as in R40 (Builder rate semantics sdk-probe ADM1; [Adm] the
 * pinned external-input loop ADM3/ADM4). The paired Watch is simulated with the shared codec's own write and read: it starts with
 * capture-start silence, writes ONE link write per packet from a real-time recorder through its own 320 ms bounded queue, and what the
 * Phone can hand for a packet is that codec's own decoding of it ([heard]). [R40Link] envelopes are prospective and constrained (32 kB/s
 * shared, 12 kB/s below demand, link stalls), never a measured Wear link. The relay journal is the ended call's own fixed numbers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchRelayRuntimeR41RoboTest : PhoneRoboBase() {
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

    private val upBytes = WatchRelayAudio.FRAME_BYTES
    private val upNs = upBytes / 2 * 1_000_000_000L / WatchRelayAudio.UPLINK_RATE
    private val downNs = WatchRelayAudio.DOWNLINK_FRAME_BYTES / 2 * 1_000_000_000L / WatchRelayAudio.DOWNLINK_RATE
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
    /** One uplink packet exactly as the paired Watch writes it: the shared codec's own write, gathered into ONE link write. */
    private fun packet(k: Int, epoch: Int = 0, counter: Long = k + 1L, bytes: ByteArray = upPcm(k, epoch)) = ByteArrayOutputStream().also {
        WatchRelayAudio.write(DataOutputStream(it), WatchRelayAudio.UPLINK, LivePcmFrame(WatchRelayAudio.sequence(epoch.toLong(), counter), WatchRelayAudio.UPLINK_RATE, 1, bytes))
    }.toByteArray()
    private val decodedUp = HashMap<Pair<Int, Int>, ByteArray>()
    /** What the Phone can hand for audio packet k: the shared codec's own decoding of it, whatever the release's wire is. */
    private fun heard(k: Int, epoch: Int = 0) = decodedUp.getOrPut(k to epoch) { WatchRelayAudio.read(DataInputStream(ByteArrayInputStream(packet(k, epoch))), WatchRelayAudio.UPLINK).pcm }
    /** The provider's 48 kHz mono at the pinned remote sink: speech-band 1 kHz plus 9.5 kHz, which a 16 kHz Watch cannot carry (it would
     *  fold to 6.5 kHz, off the 1 kHz tone's own harmonics, so codec distortion is not mistaken for aliasing). */
    private fun downPcm(j: Int) = ByteArray(960).also { b -> for (i in 0 until 480) { val n = j * 480L + i
        val v = Math.round(TONE * sin(2 * PI * 1000 * n / 48000.0) + HIGH * sin(2 * PI * 9500 * n / 48000.0)).toInt()
        b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() } }
    /** One DFT bin's amplitude over the longest prefix holding whole periods of 1 and 6.5 kHz at both 16 and 48 kHz (480 samples). */
    private fun amplitude(pcm: ByteArray, rate: Int, hz: Int): Double {
        val n = pcm.size / 2 / 480 * 480; var re = 0.0; var im = 0.0
        for (i in 0 until n) { val v = ((pcm[2 * i].toInt() and 255) or (pcm[2 * i + 1].toInt() shl 8)).toShort().toDouble(); val a = 2 * PI * hz * i / rate; re += v * cos(a); im -= v * sin(a) }
        return 2 * sqrt(re * re + im * im) / n
    }
    private fun silent(b: ByteArray) = b.all { it == 0.toByte() }
    private fun concat(parts: List<ByteArray>) = ByteArrayOutputStream().also { o -> parts.forEach { o.write(it) } }.toByteArray()
    private fun account(name: String, vararg pairs: Pair<String, Any?>) = println("R41-ACCOUNT " + JSONObject().put("test", name).apply { pairs.forEach { (k, v) -> put(k, v) } })
    private fun status() = app.live.state.value.status
    private fun relay(wire: String) = app.diagnostics.log.snapshot().filter { it.code.wire == wire }
    /** The ended call's one relay end observation, recorded with its media numbers after the end (null when none is recorded). */
    private fun relayEnd(): DiagEvent? { await { relay("live_relay_end").isNotEmpty() }; return relay("live_relay_end").singleOrNull() }
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
            loop = thread("r41-adm") { var due = System.nanoTime(); while (running) { until(due) { running }; if (!running) break; step(); due = maxOf(due + 10_000_000L, System.nanoTime()) } }
            return this
        }
        fun stop() { running = false; loop?.join(2000) }
    }
    /** The paired Watch over the link: capture-start silence ([WARMUP] all-zero packets), then audio packet k as counter WARMUP + k + 1,
     *  from a real-time recorder into its 320 ms bounded queue (a refusal is its n44), ONE link write per packet, and the downlink read
     *  with the shared codec. */
    private inner class Watch {
        private val queue = ArrayBlockingQueue<ByteArray>(watchQueue)
        val captured = AtomicInteger(); val sent = AtomicInteger(); val down = ConcurrentLinkedQueue<LivePcmFrame>()
        @Volatile var overflow = false; @Volatile var running = true; @Volatile var outOfOrder = false
        fun run(): Watch {
            val t0 = System.nanoTime(); stops += { running = false }
            thread("r41-watch-mic") { var c = 0; while (running) { until(t0 + (c + 1) * upNs) { running }; if (!running) break
                val p = if (c < WARMUP) packet(0, counter = c + 1L, bytes = ByteArray(upBytes)) else packet(c - WARMUP, counter = c + 1L)
                if (!queue.offer(p)) { overflow = true; running = false; break }; captured.incrementAndGet(); c++ } }
            thread("r41-watch-up") { val out = link!!.up.output
                try { while (running) { val p = queue.poll(20, TimeUnit.MILLISECONDS) ?: continue; out.write(p); out.flush(); sent.incrementAndGet() } } catch (_: IOException) {} }
            thread("r41-watch-down") { val input = DataInputStream(link!!.down.input); var previous = 0L
                try { while (running) { val f = WatchRelayAudio.read(input, WatchRelayAudio.DOWNLINK); if (f.sequence != previous + 1) outOfOrder = true; previous = f.sequence; down += f } } catch (_: IOException) {} }
            return this
        }
        /** The audio packets this Watch wrote, as the Phone can hand them. */
        fun sentAudio() = concat((0 until maxOf(0, sent.get() - WARMUP)).map { heard(it) })
    }
    /** The provider's decoded output at the pinned remote sink: 10 ms of 48 kHz mono every 10 ms of real time. */
    private inner class Provider {
        val fed = AtomicInteger(); @Volatile var running = true
        fun run(): Provider {
            val t0 = System.nanoTime(); stops += { running = false }
            thread("r41-provider") { var j = 0; while (running) { until(t0 + j * 10_000_000L) { running }; if (!running) break
                remoteSink!!.onData(ByteBuffer.wrap(downPcm(j)), 16, 48000, 1, 480, 0); fed.incrementAndGet(); j++ } }
            return this
        }
    }
    private class Duplex(val ranMs: Long, val active: Boolean, val status: String, val handed: List<ByteArray>, val starts: List<Long>, val sentPcm: ByteArray, val frames: List<LivePcmFrame>, val fed: Int)
    /** One started call at [bytesPerSecond] for up to [RUN_MS] (until the Phone ends), with the link held for each (at, for) ms in [holds]. */
    private fun duplex(name: String, bytesPerSecond: Long, holds: List<Pair<Long, Long>> = emptyList()): Pair<Duplex, Watch> {
        start(bytesPerSecond); ready()
        val adm = Adm().run(); val watch = Watch().run(); val provider = Provider().run(); val began = System.nanoTime()
        if (holds.isNotEmpty()) thread("r41-stalls") { for ((at, ms) in holds) { until(began + at * 1_000_000) { provider.running }; link!!.held = true; Thread.sleep(ms); link!!.held = false } }
        elapse(RUN_MS) { !app.live.active }
        val ranMs = (System.nanoTime() - began) / 1_000_000; val active = app.live.active; val status = status()
        provider.running = false; watch.running = false; adm.stop(); link!!.held = false; Thread.sleep(50)
        val handed = adm.handed.toList(); val first = handed.indexOfFirst { !silent(it) }
        val d = Duplex(ranMs, active, status, handed, adm.starts.toList(), watch.sentAudio(), watch.down.toList(), provider.fed.get())
        val l = link!!
        account(name, "envelope_bytes_per_s" to bytesPerSecond, "holds" to holds.toString(), "ran_ms" to ranMs, "phone_active" to active, "status" to status,
            "up_packet_pcm_bytes" to upBytes, "adm_input_rate" to inputRate, "adm_output_rate" to outputRate, "adm_buffer_bytes" to adm.buffer.capacity(),
            "captured_packets" to watch.captured.get(), "written_packets" to watch.sent.get(), "up_write_calls" to l.up.calls.get(),
            "up_bytes_written" to l.up.written.get(), "up_bytes_crossed" to l.up.crossed.get(), "native_callbacks" to handed.size,
            "native_audio_buffers" to handed.count { !silent(it) }, "native_silent_after_first_audio" to (if (first < 0) -1 else handed.drop(first).count { silent(it) }),
            "provider_frames" to provider.fed.get(), "down_write_calls" to l.down.calls.get(), "down_bytes_written" to l.down.written.get(),
            "down_bytes_crossed" to l.down.crossed.get(), "down_packets_decoded" to d.frames.size, "watch_queue_overflow" to watch.overflow)
        return d to watch
    }
    /** Uplink: every Watch sample handed to the native buffer once, in order. Downlink: whole packets in order at the Watch rate. */
    private fun assertExactPrefixes(d: Duplex, watch: Watch) {
        assertTrue("thread errors: ${errors.toList()}", errors.isEmpty())
        val audio = concat(d.handed.filter { !silent(it) })
        assertArrayEquals("exact Watch audio in the native buffers, in order, no hole or repeat", d.sentPcm.copyOf(audio.size), audio)
        assertFalse("downlink sequence in order", watch.outOfOrder)
        d.frames.forEachIndexed { i, f ->
            assertEquals("downlink sequence", i + 1L, f.sequence); assertEquals(WatchRelayAudio.DOWNLINK_RATE, f.sampleRate); assertEquals(1, f.channels)
            assertEquals("whole downlink packets only", WatchRelayAudio.DOWNLINK_FRAME_BYTES, f.pcm.size)
        }
    }

    // ── a started call at a constrained shared envelope ─────────────────────────────────────────────────────────

    @Test fun Q01_atAConstrainedSharedEnvelopeTheStartedCallStaysUpHandsEveryWatchSampleOnceAndJournalsOnlyFixedNumbers() {
        val (d, watch) = duplex("Q01", ENVELOPE)
        assertTrue("the started call stays up at the constrained envelope (status: ${d.status})", d.active)
        assertFalse("the paired Watch's 320 ms uplink queue never overflowed (its n44)", watch.overflow)
        assertExactPrefixes(d, watch)
        val first = d.handed.indexOfFirst { !silent(it) }; assertTrue("Watch audio reached the native buffer after the capture-start silence", first >= 0)
        val audio = d.handed.count { !silent(it) } * d.handed[0].size
        assertTrue("handed $audio of ${d.sentPcm.size} bytes: only the bounded in-flight remainder is missing", d.sentPcm.size - audio <= (watchQueue + 2) * upBytes)
        val after = d.handed.size - first; val spanMs = (d.starts.last() - d.starts[first]) / 1_000_000
        assertTrue("the native callback kept its 10 ms media clock: $after callbacks in $spanMs ms", after >= spanMs / 10 * 95 / 100)
        assertTrue("silence after the first audio is jitter padding only", d.handed.drop(first).count { silent(it) } <= 3)
        assertTrue("sustained uplink: $after buffers in ${d.ranMs} ms", after >= d.ranMs / 10 * 85 / 100)
        val inFlightMs = d.fed * 10L - d.frames.size * downNs / 1_000_000
        assertTrue("downlink: ${d.frames.size} packets for ${d.fed} provider frames, only the bounded queue in flight ($inFlightMs ms)", inFlightMs <= 600)
        assertTrue("sustained downlink: ${d.frames.size} packets in ${d.ranMs} ms", d.frames.size >= d.ranMs * 1_000_000 / downNs * 90 / 100)
        val heard = concat(d.frames.drop(1).map { it.pcm }); val rate = WatchRelayAudio.DOWNLINK_RATE
        val tone = amplitude(heard, rate, 1000); val folded = amplitude(heard, rate, 6500)
        assertTrue("the provider's 1 kHz speech-band tone reaches the Watch at its level ($tone of $TONE)", tone in TONE * 0.93..TONE * 1.07)
        assertTrue("its 9.5 kHz never folds into the Watch's band at 6.5 kHz ($folded, under 2% of $HIGH)", folded < HIGH * 0.02)
        app.live.end(); settle()
        val end = relayEnd(); assertNotNull("the ended call's relay end is journaled", end)
        assertEquals("a Phone Stop is no relay boundary", 0, end!!.n)
        assertTrue("Started to that end (${end.ms} ms)", (end.ms ?: -1L) >= d.ranMs * 8 / 10)
        val up = relay("live_relay_up").single(); val down = relay("live_relay_down").single()
        assertTrue("Watch packets received after Started (${up.n})", (up.n ?: 0) >= d.ranMs * 1_000_000 / upNs * 80 / 100)
        assertTrue("packets written to the Watch after Started (${down.n})", (down.n ?: 0) >= d.frames.size)
        for (e in listOf(end, up, down)) {
            assertEquals(DiagOrigin.WATCH, e.origin); assertNull("no reference", e.ref); assertNull("no failure text", e.fail)
            assertNotNull("a fixed number", e.n); assertNotNull("a fixed duration", e.ms)
        }
    }

    @Test fun Q02_twoLinkStallsDelayButNeverDropReorderOrRepeatWatchAudioAndTheCallStaysUp() {
        val (d, watch) = duplex("Q02", ENVELOPE, holds = listOf(1_500L to 120L, 3_500L to 120L))
        assertTrue("two 120 ms stalls stay within the bounded queues (status: ${d.status})", d.active)
        assertFalse("the Watch queue held the stalls", watch.overflow)
        assertExactPrefixes(d, watch)
        assertTrue("Watch audio reached the native buffer", d.handed.any { !silent(it) })
    }

    @Test fun Q03_belowTheDuplexDemandTheCallEndsExplicitlyAndJournalsItsRelayBoundary() {
        val (d, watch) = duplex("Q03", BELOW)
        assertFalse("a link below the duplex demand ends the call, never silently thins it (status: ${d.status})", d.active)
        assertExactPrefixes(d, watch)
        val end = relayEnd(); assertNotNull("the relay boundary that ended it is journaled", end)
        assertTrue("downlink congested (5) or no Watch audio for over a second (1): ${end!!.n}", end.n == 1 || end.n == 5)
        assertNotNull("Started to that end", end.ms)
    }

    // ── the native ADM binding ───────────────────────────────────────────────────────────────────────────────────

    @Test fun Q04_watchAudioBindsThePinnedAdmInputAt16kHzEachPacketFillsTwo10msBuffersAndAWrongBufferIsJournaled() {
        start(FAST); ready()
        assertEquals("the pinned ADM input rate for Watch audio (setInputSampleRate)", 16000, inputRate)
        assertEquals("the ADM output rate is unchanged", 48000, outputRate)
        val adm = Adm(); assertEquals("the pinned record's 10 ms buffer at that rate: 2 * 16000 / 100", 320, adm.buffer.capacity())
        val out = link!!.up.output; out.write(packet(0)); out.write(packet(1)); out.flush()
        assertTrue("both packets crossed", await { link!!.up.crossed.get() == link!!.up.written.get() }); Thread.sleep(50)
        val buffers = (0 until 4).map { adm.step() }
        for (i in 0 until 4) assertArrayEquals("packet ${i / 2 + 1}, 10 ms half ${i % 2 + 1}", heard(i / 2).copyOfRange(i % 2 * 320, i % 2 * 320 + 320), buffers[i])
        assertTrue("nothing more queued: bounded-wait silence, never a repeat", silent(adm.step()))
        assertTrue(status(), app.live.active)
        val wrong = ByteBuffer.allocateDirect(960)
        inputCallback!!.onBuffer(wrong, AudioFormat.ENCODING_PCM_16BIT, 1, 48000, 960, 0)
        assertTrue("an unsupported native buffer gets no Watch audio", silent(ByteArray(960).also { wrong.duplicate().apply { clear(); get(it) } }))
        assertTrue("an unsupported native format ends the call, never resampled or guessed", await { !app.live.active })
        assertTrue(status(), status().contains("unsupported native format"))
        assertEquals("the native format boundary is journaled", 2, relayEnd()?.n)
    }

    @Test fun Q05_aDirectPhoneOrHeadsetInputKeepsThePinnedAdmAt48kHzAsBefore() {
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

    @Test fun Q06_aWatch40OfferIsRefusedBeforeAnyProviderOrMicrophoneWork() {
        start(FAST, offerBody = WATCH40_OFFER)
        assertTrue("a pair across the R41 wire change ends", await { !app.live.active })
        assertTrue(status(), status().contains("Watch media version unavailable"))
        assertEquals("no provider peer for a mixed pair", 0, factories.get()); assertTrue(fake.prompts.isEmpty())
    }

    @Test fun Q07_aWatch39OfferIsStillRefusedBeforeAnyProviderOrMicrophoneWork() {
        start(FAST, offerBody = "phone-relay/pcm16/48000/mono")
        assertTrue("a mixed pair ends", await { !app.live.active })
        assertTrue(status(), status().contains("Watch media version unavailable"))
        assertEquals("no provider peer for a mixed pair", 0, factories.get()); assertTrue(fake.prompts.isEmpty())
    }

    @Test fun Q08_anR40PcmFrameOnTheCurrentStreamIsRefusedNeverHandedAndJournaled() {
        start(FAST); ready(); val adm = Adm()
        val r40 = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(R40_MAGIC); writeInt(WatchRelayAudio.UPLINK); writeLong(1); writeInt(16000); writeInt(1); writeInt(640); write(upPcm(0)) } }.toByteArray()
        link!!.up.output.write(r40); link!!.up.output.flush()
        assertTrue("an earlier wire's frame ends the call, never read as audio", await { !app.live.active })
        assertTrue(status(), status().contains("Watch input channel unavailable, malformed or congested"))
        assertTrue("nothing of it reached the native buffer", silent(adm.step()))
        assertEquals("a refused Watch packet is journaled", 3, relayEnd()?.n)
    }

    // ── burst, partial and EOF on the uplink ─────────────────────────────────────────────────────────────────────

    @Test fun Q09_aBurstWithinThe320msBoundAndArbitrarilySplitWritesAreHandedExactlyInOrder() {
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
        assertArrayEquals(concat((0 until 2 * n).map { heard(it) }), concat(adm.handed.filter { !silent(it) }))
    }

    @Test fun Q10_aBurstBeyondThe320msBoundIsAnExplicitJournaledEndNeverASilentDrop() {
        start(FAST); ready()
        val n = (400_000_000L / upNs).toInt() // 400 ms at once with nothing draining it
        // The Phone ends and closes the channel mid-burst, so the rest of the burst may no longer be writable (as on a Watch).
        runCatching { link!!.up.output.write(concat((0 until n).map { packet(it) })); link!!.up.output.flush() }
        assertTrue("an over-bound burst ends the call", await { !app.live.active })
        assertTrue(status(), status().contains("Watch input channel unavailable, malformed or congested"))
        assertEquals("the refused packet is journaled", 3, relayEnd()?.n)
    }

    @Test fun Q11_aStreamEndingMidPacketEndsTheCallHandsNoPartOfThatPacketAndIsJournaled() {
        start(FAST); ready(); val adm = Adm(); val out = link!!.up.output
        out.write(packet(0)); val cut = packet(1); out.write(cut, 0, 28 + (cut.size - 28) / 2); out.flush()
        assertTrue("the first packet crossed", await { link!!.up.crossed.get() == link!!.up.written.get() }); Thread.sleep(50)
        assertArrayEquals(heard(0).copyOfRange(0, adm.buffer.capacity()), adm.step())
        Thread.sleep(100); link!!.up.close()
        assertTrue("EOF ends the call", await { !app.live.active })
        assertTrue(status(), status().contains("Watch input channel unavailable, malformed or congested"))
        repeat(3) { adm.step() }
        val partial = heard(1)
        assertTrue("no part of the cut packet was handed", adm.handed.none { h -> partial.copyOfRange(0, h.size).contentEquals(h) || partial.copyOfRange(partial.size - h.size, partial.size).contentEquals(h) })
        assertEquals("the ended Watch stream is journaled", 4, relayEnd()?.n)
    }

    // ── mute epochs, Stop with a held half, and no downlink after Stop ───────────────────────────────────────────

    @Test fun Q12_muteNeverHandsTheHeldHalfOrAnOlderEpochAndAfterStopNothingIsHandedOrWrittenToTheWatch() {
        start(FAST); ready(); val adm = Adm(); val out = link!!.up.output; val cap = adm.buffer.capacity()
        out.write(packet(0)); out.flush(); Thread.sleep(50)
        assertArrayEquals(heard(0).copyOfRange(0, cap), adm.step())
        app.live.setMuted(true) // capture epoch 1 on both apps
        out.write(packet(1)); out.flush(); Thread.sleep(100) // in flight from before the Watch saw the mute
        assertTrue("muted: neither the held half nor the late older-epoch packet", (0 until 3).map { adm.step() }.all { silent(it) })
        app.live.setMuted(false) // capture epoch 2
        out.write(packet(0, epoch = 2)); out.write(packet(1, epoch = 2)); out.flush(); Thread.sleep(50)
        assertArrayEquals("only the new epoch, exact", concat(listOf(heard(0, 2), heard(1, 2))), concat((0 until 2 * upBytes / cap).map { adm.step() }))
        out.write(packet(2, epoch = 2)); out.flush(); Thread.sleep(50)
        assertArrayEquals(heard(2, 2).copyOfRange(0, cap), adm.step())
        remoteSink!!.onData(ByteBuffer.wrap(downPcm(0)), 16, 48000, 1, 480, 0) // half of a 16 kHz packet, then Stop
        app.live.end(); settle()
        assertTrue("after Stop nothing more is handed, not even a held half", (0 until 2).map { adm.step() }.all { silent(it) })
        assertTrue(status(), status().startsWith("Live ended"))
        val written = link!!.down.written.get()
        for (j in 1..4) runCatching { remoteSink!!.onData(ByteBuffer.wrap(downPcm(j)), 16, 48000, 1, 480, 0) }
        settle()
        assertEquals("nothing reaches the Watch speaker after Stop, not even a held partial packet", written, link!!.down.written.get())
    }

    companion object {
        /** Prospective constrained envelope: one link sharing 32 kB/s across both directions (R40 duplex demand 132.2 kB/s). */
        const val ENVELOPE = 32_000L
        /** Below any 20 ms duplex speech relay this release could carry with its headers. */
        const val BELOW = 12_000L
        const val FAST = 20_000_000L
        const val RUN_MS = 6_000L
        /** The Watch's capture-start silence before its first nonzero packet: 300 ms. */
        const val WARMUP = 15
        const val TONE = 8000.0
        const val HIGH = 4000.0
        /** The R40 pair's wire, which an R41 Phone must refuse rather than misread. */
        const val WATCH40_OFFER = "phone-relay/pcm16/up16000-20ms/down48000/mono"
        const val R40_MAGIC = 0x48565233
    }
}
