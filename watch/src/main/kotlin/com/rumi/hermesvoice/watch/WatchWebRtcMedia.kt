package com.rumi.hermesvoice.watch

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Replaceable media boundary for deterministic offline tests; the production adapter below owns real native WebRTC. */
interface WatchLiveMedia : AutoCloseable {
    suspend fun connect(signal: suspend (String) -> String, event: (String) -> Unit)
    fun send(json: String): Boolean
    fun mute(muted: Boolean)
    fun silence()
    fun output(enabled: Boolean)
    fun observeActivity(listener: (Boolean) -> Unit) {}
    fun observeMicrophone(listener: (Boolean) -> Unit) {}
    /** Initial route negotiation only: true proves no Watch capture has been created or started. */
    fun holdInput(held:Boolean):Boolean=false
    fun observeInputRoute(listener:(String)->Unit) {}
    /** Select exclusive Phone output without transferring Watch input ownership. Legacy peer adapters expose their remote-track sink here. */
    fun privateOutput(listener: ((com.rumi.hermesvoice.core.live.LivePcmFrame) -> Unit)?) {}
    /** Paired channel selects Phone-local output; explicit legacy peer fixtures retain sink suppression. */
    fun phoneOutput(){privateOutput{}}
    /** R32: the setup step [connect] last entered, when the adapter reports its own (the setup observation only). */
    val setupStep:WatchLiveSetupStep? get()=null
}

/** B (Watch-direct experiment): the Watch's own WebRTC peer for an ordinary Watch-origin call (Watch microphone and speaker through this
 *  audio device module) on the Watch's OS default internet route as Android chose it, including the Phone-proxied Bluetooth uplink. It never
 *  requests or binds a network, holds no credential and makes no Phone audio; the Phone turns [connect]'s offer into the session. Chosen only
 *  by the Phone's direct open; NEVER a fallback for the paired relay, and a failure is never followed by one. */
class WatchWebRtcMedia(private val context: Context) : WatchLiveMedia {
    @Volatile private var connectStep: WatchLiveSetupStep? = null
    override val setupStep: WatchLiveSetupStep? get() = connectStep
    private val closed = AtomicBoolean(false)
    private var factory: PeerConnectionFactory? = null
    private var module: JavaAudioDeviceModule? = null
    private var peer: PeerConnection? = null
    private var source: AudioSource? = null
    private var input: AudioTrack? = null
    private var data: DataChannel? = null
    private var remoteTrack: AudioTrack? = null
    @Volatile private var privateSink: ((com.rumi.hermesvoice.core.live.LivePcmFrame) -> Unit)? = null
    private val pcmSequence = java.util.concurrent.atomic.AtomicLong()
    private val remoteSink = AudioTrackSink { buffer,bits,rate,channels,frames,_ ->
        if (!closed.get() && outputEnabled) privateSink?.let { consume ->
            com.rumi.hermesvoice.core.live.WatchLivePcm.copy(buffer,bits,rate,channels,frames,pcmSequence.incrementAndGet())?.let { frame ->
                samples(JavaAudioDeviceModule.AudioSamples(android.media.AudioFormat.ENCODING_PCM_16BIT,channels,rate,frame.pcm),false)
                consume(frame)
            }
        }
    }
    override fun privateOutput(listener: ((com.rumi.hermesvoice.core.live.LivePcmFrame) -> Unit)?) {
        // Suppress local audibility BEFORE publishing the consumer. Never disable the remote track:
        // decoded PCM must continue to reach its sink even though the Watch ADM writes zeros.
        if (listener != null) module?.setSpeakerMute(true)
        privateSink = listener
        module?.setSpeakerMute(!outputEnabled || listener != null)
    }
    private fun attachRemote(receiver: RtpReceiver) {
        val track = receiver.track() as? AudioTrack ?: return
        synchronized(this) {
            if (closed.get() || remoteTrack === track) return
            remoteTrack?.removeSink(remoteSink)
            remoteTrack = track; track.addSink(remoteSink)
        }
    }
    private var deliver: ((String) -> Unit)? = null
    private val ice = CompletableDeferred<Unit>()
    @Volatile private var activity: ((Boolean) -> Unit)? = null
    @Volatile private var microphone: ((Boolean) -> Unit)? = null
    override fun observeMicrophone(listener: (Boolean) -> Unit) { microphone = listener }
    @Volatile private var inputMuted = false
    @Volatile private var silenced = false
    @Volatile private var outputEnabled = false
    private val lastActivity = java.util.concurrent.atomic.AtomicLong(0)
    override fun observeActivity(listener: (Boolean) -> Unit) { activity = listener }
    private fun samples(samples: JavaAudioDeviceModule.AudioSamples, input: Boolean) {
        if (closed.get() || (input && inputMuted) || (!input && !outputEnabled) || samples.audioFormat != android.media.AudioFormat.ENCODING_PCM_16BIT) return
        val data = samples.data
        if (data.size < 2) return
        var energy = 0L
        for (i in 0 until data.size - 1 step 2) energy += kotlin.math.abs(((data[i].toInt() and 255) or (data[i + 1].toInt() shl 8)).toShort().toInt())
        if (energy / (data.size / 2) < 120) return
        val now = android.os.SystemClock.elapsedRealtime(); val previous = lastActivity.get()
        if (now - previous >= 250 && lastActivity.compareAndSet(previous, now)) activity?.invoke(input)
    }

    override suspend fun connect(signal: suspend (String) -> String, event: (String) -> Unit) = withTimeout(60_000) {
        connectStep = WatchLiveSetupStep.DIRECT_NETWORK
        check(!closed.get()); deliver = event
        // The OS default network exactly as Android chose it (Bluetooth via the Phone, Wi-Fi or LTE: any transport), validated for internet.
        // Nothing is requested or bound here; before this check passes no WebRTC, audio device module, microphone or offer exists.
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        val capabilities = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        check(capabilities != null && capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) { "Watch has no validated default internet route" }
        connectStep = WatchLiveSetupStep.DIRECT_PEER
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
            .setEnableInternalTracer(false).setInjectableLogger({ _, _, _ -> }, Logging.Severity.LS_VERBOSE).createInitializationOptions())
        check(!closed.get())
        module = JavaAudioDeviceModule.builder(context.applicationContext)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
            .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
            .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                override fun onWebRtcAudioRecordStart() { if (!closed.get()) microphone?.invoke(true) }
                override fun onWebRtcAudioRecordStop() { if (!closed.get()) microphone?.invoke(false) }
            })
            .setSamplesReadyCallback { samples(it, true) }
            .setPlaybackSamplesReadyCallback { samples(it, false) }
            .setEnableVolumeLogger(false).setStopRecordingOnMute(true).createAudioDeviceModule()
        module!!.setSpeakerMute(true) // controller positively confirms the output route before unmute.
        module!!.setMicrophoneMute(inputMuted) // a Mute received before media admission is the real capture state from the start.
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
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
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
        val answer = signal(local) // ONCE; never retry creation after uncertainty.
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
        if (silenced && !muted) return // B: nothing reopens this peer's capture after its Stop, not even a state already on its way.
        inputMuted = muted
        input?.setEnabled(!muted)
        module?.setMicrophoneMute(muted) // stopRecordingOnMute closes the native capture path too.
    }
    override fun output(enabled: Boolean) { outputEnabled = enabled; module?.setSpeakerMute(!enabled || privateSink != null) }
    override fun silence() { silenced = true; outputEnabled = false; mute(true); module?.setSpeakerMute(true); peer?.setAudioPlayout(false); peer?.setAudioRecording(false) }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        deliver = null; activity = null; microphone = null
        privateSink = null
        synchronized(this) { runCatching { remoteTrack?.removeSink(remoteSink) }; remoteTrack = null }
        runCatching { silence() }
        // B: the provider session ends with this peer: this peer asks once on its own open data channel (the Phone's own close may also have
        // crossed; a second close is harmless). Delivery is not confirmed and no receipt can come back after this close, so the Phone
        // truthfully keeps the call's final usage unconfirmed.
        runCatching { data?.takeIf { it.state() == DataChannel.State.OPEN }?.send(DataChannel.Buffer(ByteBuffer.wrap(com.rumi.hermesvoice.core.live.LiveWire.close().toByteArray(Charsets.UTF_8)), false)) }
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
