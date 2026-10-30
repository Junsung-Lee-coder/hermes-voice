package com.rumi.hermesvoice.phone.live

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaRecorder
import com.rumi.hermesvoice.core.diag.DiagStage
import com.rumi.hermesvoice.core.diag.diagMark
import com.rumi.hermesvoice.core.net.HermesDashboardClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Replaceable media boundary for deterministic offline tests; the production adapter below owns real native WebRTC. */
interface LiveTransport : AutoCloseable {
    suspend fun connect(history: JSONArray, event: (String) -> Unit)
    fun send(json: String): Boolean
    fun mute(muted: Boolean)
    fun silence()
    fun output(enabled: Boolean)
    fun observeActivity(listener: (Boolean) -> Unit) {}
    fun observeMicrophone(listener: (Boolean) -> Unit) {}
    /** This call's own fixed media numbers for its one diagnostics observation, read before close; null when the media has none. */
    suspend fun counters(): LiveMediaCounters? = null
    /** The provider's speech can be played on this call's admitted output now (a relay or private output may still await admission). */
    fun outputReady(): Boolean = true
}

/** What one call's WebRTC media observed while started (see [com.rumi.hermesvoice.core.diag.DiagCode.LIVE_INPUT_AUDIO]): nonzero PCM
 * chunks and their speech-energy time per direction, and the audio RTP packet counts when the stats expose them (null: not exposed).
 * R33: [inputHandedMs] is the input audio time the pinned ADM buffer callback handed to WebRTC's send path while started, every
 * frame whether zero or not (null without that callback). Fixed numbers only: never a stats map, identifier, SDP or audio. */
data class LiveMediaCounters(val inputChunks: Long, val inputSpeechMs: Long, val playoutChunks: Long, val playoutSpeechMs: Long,
                             val rtpSent: Long?, val rtpReceived: Long?, val inputHandedMs: Long? = null, val relay: LiveRelayCounters? = null)

/** R41: what the Watch relay of a started Watch-origin call saw (see [com.rumi.hermesvoice.core.diag.DiagCode.LIVE_RELAY_END]): the relay
 *  boundary that ended it ([end], 0 none), Started to that end ([endMs], null before Started), Watch packets received after Started and
 *  the longest wait for one, packets written to the Watch after Started and the longest single write. Fixed numbers only. */
data class LiveRelayCounters(val end: Int, val endMs: Long?, val upPackets: Long, val upGapMs: Long, val downPackets: Long, val downWriteMs: Long)

/** The time [bytes] of PCM16 at [channels] and [rate] hold, in microseconds; 0 for an impossible format. */
internal fun pcm16Micros(bytes: Int, channels: Int, rate: Int): Long =
    if (bytes <= 0 || channels <= 0 || rate <= 0) 0L else bytes / 2L / channels * 1_000_000L / rate

class WebRtcLiveTransport(private val context: Context, private val dashboard: HermesDashboardClient,
    private val externalInput: ExternalLiveInput? = null,
    private val remoteAudio: ((com.rumi.hermesvoice.core.live.LivePcmFrame) -> Unit)? = null,
) : LiveTransport {
    private val closed = AtomicBoolean(false)
    private var factory: PeerConnectionFactory? = null
    private var module: JavaAudioDeviceModule? = null
    private var peer: PeerConnection? = null
    private var source: AudioSource? = null
    private var input: AudioTrack? = null
    private var data: DataChannel? = null
    private var remoteTrack: AudioTrack? = null
    private val pcmSequence = java.util.concurrent.atomic.AtomicLong()
    private val remoteSink = AudioTrackSink { buffer,bits,rate,channels,frames,_ ->
        if (!closed.get() && outputEnabled) remoteAudio?.let { consume ->
            com.rumi.hermesvoice.core.live.WatchLivePcm.copy(buffer,bits,rate,channels,frames,pcmSequence.incrementAndGet())?.let { frame ->
                samples(JavaAudioDeviceModule.AudioSamples(android.media.AudioFormat.ENCODING_PCM_16BIT,channels,rate,frame.pcm),false)
                consume(frame)
            }
        }
    }
    private fun attachRemote(receiver: RtpReceiver) {
        if(remoteAudio==null)return
        val track=receiver.track() as? AudioTrack ?: return
        synchronized(this){if(closed.get()||remoteTrack===track)return;remoteTrack?.removeSink(remoteSink);remoteTrack=track;track.addSink(remoteSink)}
    }
    private var deliver: ((String) -> Unit)? = null
    private val ice = CompletableDeferred<Unit>()
    @Volatile private var activity: ((Boolean) -> Unit)? = null
    @Volatile private var microphone: ((Boolean) -> Unit)? = null
    override fun observeMicrophone(listener: (Boolean) -> Unit) { microphone = listener }
    @Volatile private var inputMuted = false
    @Volatile private var outputEnabled = false
    private val lastActivity = java.util.concurrent.atomic.AtomicLong(0)
    /** Counted only while the call is started (its output admitted), so they describe what could reach and come from the provider. */
    private val inputMeter = com.rumi.hermesvoice.core.live.LivePcmMeter()
    private val playoutMeter = com.rumi.hermesvoice.core.live.LivePcmMeter()
    /** R33: every frame the pinned external-input thread handed on (one per [JavaAudioDeviceModule.AudioBufferCallback] return; its
     *  only pacing), as audio time while started. Against the started time it shows the uplink's real cadence. */
    private val handedUs = java.util.concurrent.atomic.AtomicLong()
    override fun observeActivity(listener: (Boolean) -> Unit) { activity = listener }
    private fun samples(samples: JavaAudioDeviceModule.AudioSamples, input: Boolean) {
        if (closed.get() || (input && inputMuted) || (!input && !outputEnabled) || samples.audioFormat != android.media.AudioFormat.ENCODING_PCM_16BIT) return
        val data = samples.data
        if (data.size < 2) return
        if (!input) playoutMeter.count(data, samples.channelCount, samples.sampleRate)
        var energy = 0L
        for (i in 0 until data.size - 1 step 2) energy += kotlin.math.abs(((data[i].toInt() and 255) or (data[i + 1].toInt() shl 8)).toShort().toInt())
        if (energy / (data.size / 2) < 120) return
        val now = android.os.SystemClock.elapsedRealtime(); val previous = lastActivity.get()
        if (now - previous >= 250 && lastActivity.compareAndSet(previous, now)) activity?.invoke(input)
    }

    override suspend fun connect(history: JSONArray, event: (String) -> Unit) = withTimeout(60_000) {
        diagMark(DiagStage.LIVE_MEDIA) // setup stage for a type-only failure observation; the session request marks its own
        check(!closed.get()); deliver = event
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
            .setEnableInternalTracer(false).setInjectableLogger({ _, _, _ -> }, Logging.Severity.LS_VERBOSE).createInitializationOptions())
        check(!closed.get())
        val builder = JavaAudioDeviceModule.builder(context.applicationContext)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setUseHardwareAcousticEchoCanceler(externalInput == null && JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
            .setUseHardwareNoiseSuppressor(externalInput == null && JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
            .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                override fun onWebRtcAudioRecordStart() { if (!closed.get() && externalInput == null) microphone?.invoke(true) }
                override fun onWebRtcAudioRecordStop() { if (!closed.get() && externalInput == null) microphone?.invoke(false) }
            })
            .setSamplesReadyCallback { samples(it, true) }
            .setPlaybackSamplesReadyCallback { samples(it, false) }
            .setEnableVolumeLogger(false).setStopRecordingOnMute(externalInput == null)
        // R40: the pinned Builder's setSampleRate sets both rates and setInputSampleRate then only the input rate, to what the external
        // source fills (16 kHz Watch relay; 48 kHz headset or Phone capture, as before). Remote playout stays 48 kHz.
        if(externalInput != null) builder.setSampleRate(48000).setInputSampleRate(externalInput.inputRate).setUseStereoInput(false).setUseStereoOutput(false)
            .setAudioBufferCallback { buffer,format,channels,rate,_,_ -> externalInput.fill(buffer,format,channels,rate).also {
                if (outputEnabled && !closed.get() && format == android.media.AudioFormat.ENCODING_PCM_16BIT) {
                    inputMeter.count(buffer, buffer.capacity(), channels, rate)
                    handedUs.addAndGet(pcm16Micros(buffer.capacity(), channels, rate))
                }
            } }
        module = builder.createAudioDeviceModule()
        // PINNED vendor API: disable AudioRecord BEFORE getNative/createPeerConnectionFactory can initialize capture.
        if(externalInput != null) module!!.setAudioRecordEnabled(false)
        module!!.setSpeakerMute(true) // controller positively confirms the output route before unmute.
        if(externalInput == null) module!!.setMicrophoneMute(inputMuted) else externalInput.mute(inputMuted)
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
        val config = PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        peer = factory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
                if (s == PeerConnection.IceConnectionState.FAILED || s == PeerConnection.IceConnectionState.DISCONNECTED) emit("{\"type\":\"error\"}")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) { if (s == PeerConnection.IceGatheringState.COMPLETE) ice.complete(Unit) }
            override fun onIceCandidate(c: IceCandidate) = Unit
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = attachRemote(receiver)
            override fun onTrack(transceiver: RtpTransceiver) = attachRemote(transceiver.receiver)
        }) ?: error("WebRTC peer could not be created")
        // Register both media and event listeners BEFORE producing the offer.
        source = factory!!.createAudioSource(MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", (externalInput == null || externalInput is LiveCaptureInput).toString()))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", (externalInput == null || externalInput is LiveCaptureInput).toString()))
        })
        input = factory!!.createAudioTrack("hermes-live-input", source)
        input!!.setEnabled(!inputMuted)
        peer!!.addTrack(input, listOf("hermes-live"))
        data = peer!!.createDataChannel("oai-events", DataChannel.Init()).also { channel ->
            channel.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit
                override fun onStateChange() = Unit
                override fun onMessage(buffer: DataChannel.Buffer) {
                    if (buffer.binary || buffer.data.remaining() > 65536) return
                    val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
                    emit(bytes.toString(Charsets.UTF_8))
                }
            })
        }
        val offer = CompletableDeferred<SessionDescription>()
        peer!!.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) { offer.complete(sdp) }
            override fun onCreateFailure(error: String) { offer.completeExceptionally(IllegalStateException("WebRTC offer failed")) }
        }, MediaConstraints())
        setDescription(local = true, offer.await())
        withTimeout(12_000) { ice.await() }
        check(!closed.get())
        val local = peer!!.localDescription.description
        val (_, answer) = dashboard.createLiveSession(local, history) // ONCE; never retry creation after uncertainty.
        diagMark(DiagStage.LIVE_ANSWER)
        check(!closed.get())
        setDescription(local = false, SessionDescription(SessionDescription.Type.ANSWER, answer))
        // WebRTC starts at negotiation. No WebSocket-only session.start is sent.
    }
    private suspend fun setDescription(local: Boolean, sdp: SessionDescription) {
        val ready = CompletableDeferred<Unit>()
        val observer = object : SdpAdapter() {
            override fun onSetSuccess() { ready.complete(Unit) }
            override fun onSetFailure(error: String) { ready.completeExceptionally(IllegalStateException("WebRTC description failed")) }
        }
        check(!closed.get())
        if (local) peer!!.setLocalDescription(observer, sdp) else peer!!.setRemoteDescription(observer, sdp)
        ready.await()
    }
    private fun emit(raw: String) { if (!closed.get()) deliver?.invoke(raw) }
    override fun send(json: String): Boolean {
        val channel = data ?: return false
        return !closed.get() && channel.state() == DataChannel.State.OPEN && channel.bufferedAmount() < 65536 &&
            channel.send(DataChannel.Buffer(ByteBuffer.wrap(json.toByteArray(Charsets.UTF_8)), false))
    }
    override fun mute(muted: Boolean) {
        inputMuted = muted
        input?.setEnabled(!muted)
        if(externalInput == null) module?.setMicrophoneMute(muted) else externalInput.mute(muted)
    }
    override fun output(enabled: Boolean) { outputEnabled = enabled; module?.setSpeakerMute(remoteAudio != null || !enabled) }
    override fun silence() { requestStats(); outputEnabled = false; mute(true); module?.setSpeakerMute(true); peer?.setAudioPlayout(false); peer?.setAudioRecording(false) }
    /** The call's one stats request, made when its audio stops (the end) so its totals are ready when [counters] reads them. */
    private val statsRequested = AtomicBoolean(false)
    private val stats = CompletableDeferred<RTCStatsReport>()
    private fun requestStats() {
        val pc = peer ?: return
        if (closed.get() || !statsRequested.compareAndSet(false, true)) return
        runCatching { pc.getStats(RTCStatsCollectorCallback { stats.complete(it) }) }
    }
    /** The pinned `getStats(RTCStatsCollectorCallback)` audio RTP packet totals, read once (bounded by real time, never by a clock the
     *  close path waits on); only these numbers are kept. */
    override suspend fun counters(): LiveMediaCounters {
        var sent: Long? = null; var received: Long? = null
        requestStats()
        val report = if (!statsRequested.get()) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeoutOrNull(300) { stats.await() }
        }
        report?.let { r -> runCatching {
            for (stats in r.statsMap.values) {
                if (stats.members["kind"] != "audio") continue
                when (stats.type) {
                    "outbound-rtp" -> (stats.members["packetsSent"] as? Number)?.let { sent = (sent ?: 0L) + it.toLong() }
                    "inbound-rtp" -> (stats.members["packetsReceived"] as? Number)?.let { received = (received ?: 0L) + it.toLong() }
                }
            }
        } }
        return LiveMediaCounters(inputMeter.chunks, inputMeter.speechMs, playoutMeter.chunks, playoutMeter.speechMs, sent, received,
            inputHandedMs = if (externalInput != null) handedUs.get() / 1000 else null)
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        deliver = null; activity = null; microphone = null
        synchronized(this) { runCatching { remoteTrack?.removeSink(remoteSink) }; remoteTrack=null }
        runCatching { silence() }
        runCatching { data?.unregisterObserver(); data?.close(); data?.dispose() }; data = null
        runCatching { peer?.close(); peer?.dispose() }; peer = null
        runCatching { input?.dispose() }; input = null
        runCatching { source?.dispose() }; source = null
        runCatching { factory?.dispose() }; factory = null
        runCatching { module?.release() }; module = null
        ice.cancel()
    }
    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }
}
