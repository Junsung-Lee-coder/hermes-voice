package com.rumi.hermesvoice.phone

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.rumi.hermesvoice.core.HermesPlaybackException
import com.rumi.hermesvoice.core.SpokenAudio
import com.rumi.hermesvoice.core.headset.HeadsetDevices
import com.rumi.hermesvoice.core.headset.HeadsetText
import com.rumi.hermesvoice.core.headset.MicPlan
import com.rumi.hermesvoice.core.headset.MicReport
import com.rumi.hermesvoice.core.voice.PlaybackCue
import com.rumi.hermesvoice.core.voice.PlaybackSink
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 16 kHz mono PCM16 capture into an in-memory WAV (the format `/api/audio/transcribe` accepts as audio/wav).
 * With a [MicPlan] (the "Use headset" choice made when this capture was prepared) it asks Android for the headset input as the
 * preferred device and reports the source Android's readback confirms after the first audio ([start]'s `onSource`).
 */
class WavRecorder(private val maxSeconds: Int = 120, private val records: RecordFactory) {
    private var record: RecordPort? = null
    private var worker: Thread? = null
    private var plan: MicPlan? = null
    private val pcm = ByteArrayOutputStream()
    private val running = AtomicBoolean(false)

    val isRecording: Boolean get() = running.get()

    @Volatile private var admitted = true

    /** From here on what the microphone hears is kept; before it (see [start]'s `admitted`) it is read and dropped. */
    fun admit() {
        admitted = true
    }

    /**
     * [onSource] runs once, on the recorder thread, when [plan] is given. A failure to open throws and releases [plan].
     * With [admitted] false the microphone is open but nothing is kept until [admit] (used while a private cue plays).
     */
    fun start(plan: MicPlan? = null, admitted: Boolean = true, onSource: (MicReport) -> Unit = {}) {
        if (running.get()) {
            plan?.release()
            return
        }
        this.admitted = admitted
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = records.create(minBuffer.coerceAtLeast(FRAME_BYTES))
        if (recorder == null || !recorder.initialized) {
            recorder?.release()
            plan?.release()
            throw IllegalStateException("microphone unavailable")
        }
        plan?.preferred?.let { recorder.setPreferredDevice(it.id) }
        pcm.reset()
        record = recorder
        this.plan = plan
        running.set(true)
        if (!recorder.start()) {
            running.set(false)
            record = null
            this.plan = null
            recorder.release()
            plan?.release()
            throw IllegalStateException("microphone unavailable")
        }
        val limit = SAMPLE_RATE * 2L * maxSeconds
        worker = Thread({
            val buffer = ByteArray(FRAME_BYTES)
            var reported = plan == null
            while (running.get() && pcm.size() < limit) {
                val keep = this.admitted
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    if (!reported) {
                        reported = true
                        plan?.let { onSource(it.report(recorder.routedDeviceId())) }
                    }
                    if (keep) synchronized(pcm) { pcm.write(buffer, 0, read) }
                }
            }
        }, "hermes-voice-recorder").apply { start() }
    }

    /** Stops and returns the WAV, or null when nothing usable was captured. */
    fun stop(): ByteArray? {
        if (!running.getAndSet(false)) return null
        runCatching { record?.stop() }
        runCatching { worker?.join(1_500) }
        runCatching { record?.release() }
        // The headset link and audio mode this capture took are given back here, on every way a capture ends.
        runCatching { plan?.release() }
        record = null
        plan = null
        worker = null
        val data = synchronized(pcm) { pcm.toByteArray() }
        return if (data.size < SAMPLE_RATE / 5) null else wav(data)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val FRAME_BYTES = 5_120

        /** Largest absolute PCM16 sample after the 44-byte header (0 means digital silence); for logs only. */
        fun peak(wav: ByteArray): Int {
            var peak = 0
            var i = 44
            while (i + 1 < wav.size) {
                val sample = (wav[i].toInt() and 0xFF) or (wav[i + 1].toInt() shl 8)
                peak = maxOf(peak, kotlin.math.abs(sample.toShort().toInt()))
                i += 2
            }
            return peak
        }

        fun wav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(pcm.size)
            }
            return header.array() + pcm
        }
    }
}

/**
 * Plays on the Phone speaker; returns when playback ends; cancellation stops playback immediately.
 * It asks for audio focus like any player (transient, others may duck): without it (a call, or the
 * app is closed with no background relay running) the utterance is not played and fails as a
 * playback error; losing it ends the utterance the same way. Volume and Do Not Disturb are the system's.
 *
 * "Use headset": a cue that carries a [PlaybackCue.headset] plays on THAT output only. It fails (nothing played, focus, player
 * and file released) when the device is not connected at the start, when Android won't take it as the preferred output, when
 * Android's readback shows the player on a different output, and when the device disconnects while it plays
 * ([HeadsetDevices.watch], registered only around the clip). It never falls back to the speaker. The app does not touch
 * the audio mode or Bluetooth SCO for playback: a headset's media output is chosen by Android.
 */
class PhoneSpeakerSink(
    private val context: Context,
    private val devices: HeadsetDevices = AndroidHeadsetDevices(context),
    private val players: PlayerFactory = AndroidPlayers.factory(context),
) : PlaybackSink {
    override suspend fun play(audio: SpokenAudio, cue: PlaybackCue) = playConfirmed(audio, cue) {}

    /**
     * [finished] runs from the player's own completion callback (main thread), only while this call
     * is still waiting for it, and before this call resumes: never after a stop, an error or a lost focus.
     */
    override suspend fun playConfirmed(audio: SpokenAudio, cue: PlaybackCue, finished: () -> Unit) {
        val extension = when {
            audio.mimeType.contains("ogg") -> "ogg"
            audio.mimeType.contains("wav") -> "wav"
            audio.mimeType.contains("flac") -> "flac"
            else -> "mp3"
        }
        val bound = cue.headset
        val file = File(context.cacheDir, "hv-${cue.turnId.hashCode().toUInt()}-${cue.sequence}${if (cue.part > 0) "-p${cue.part}" else ""}.$extension")
        withContext(Dispatchers.IO) { file.writeBytes(audio.bytes) }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val audioManager = context.getSystemService(AudioManager::class.java)
        var focus: AudioFocusRequest? = null
        var watch: AutoCloseable? = null
        var routeWatch: AutoCloseable? = null
        val main = Handler(Looper.getMainLooper())
        var tick: Runnable? = null
        var deadline: Runnable? = null
        var player: PlayerPort? = null
        var closed = false
        // A personal-output clip is silenced first; every end of the player goes through here once.
        fun closePlayer(stop: Boolean) {
            val current = player ?: return
            if (closed) return
            closed = true
            if (bound != null) runCatching { current.setMuted(true) }
            if (stop) runCatching { current.stop() }
            runCatching { current.release() }
        }
        try {
            withContext(Dispatchers.Main) {
                try {
                    suspendCancellableCoroutine { continuation ->
                        if (bound != null && devices.outputs().none { it.sameDevice(bound) }) {
                            continuation.resumeWithException(HermesPlaybackException(HeadsetText.PLAYBACK_LOST))
                            return@suspendCancellableCoroutine
                        }
                        val created = players.create(attributes)
                        player = created
                        fun fail(detail: String) {
                            if (!continuation.isActive) return
                            closePlayer(stop = true)
                            continuation.resumeWithException(HermesPlaybackException(detail))
                        }
                        continuation.invokeOnCancellation {
                            if (Looper.myLooper() == Looper.getMainLooper()) closePlayer(stop = true) else main.post { closePlayer(stop = true) }
                        }
                        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(attributes)
                            .setOnAudioFocusChangeListener { change ->
                                if ((change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) && continuation.isActive) {
                                    fail("phone playback stopped: audio focus lost")
                                }
                            }.build()
                        focus = request
                        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                            closePlayer(stop = false)
                            continuation.resumeWithException(HermesPlaybackException("phone playback refused: audio focus denied"))
                            return@suspendCancellableCoroutine
                        }
                        var started = false
                        var confirmed = false
                        // The personal output is proven (Android's readback names exactly it) before the clip is made audible, and is
                        // watched for as long as it plays: any other route, a missing one or the output going away ends it, muted.
                        fun evaluate() {
                            if (bound == null || !continuation.isActive || closed) return
                            if (devices.outputs().none { it.sameDevice(bound) }) return fail(HeadsetText.PLAYBACK_LOST)
                            val routed = created.routedDeviceId()
                            if (routed != null && routed != bound.id) return fail(HeadsetText.PLAYBACK_UNROUTED)
                            if (confirmed && routed == null) return fail(HeadsetText.PLAYBACK_UNROUTED)
                            if (confirmed || !started || routed == null) return
                            confirmed = true
                            deadline?.let { main.removeCallbacks(it) }
                            tick?.let { main.removeCallbacks(it) }
                            if (runCatching { created.setMuted(false) }.isFailure) fail(HeadsetText.PLAYBACK_UNROUTED)
                        }
                        if (bound != null) {
                            if (runCatching { created.setMuted(true) }.isFailure) return@suspendCancellableCoroutine fail(HeadsetText.PLAYBACK_UNROUTED)
                            if (!created.setPreferredDevice(bound.id)) return@suspendCancellableCoroutine fail(HeadsetText.PLAYBACK_UNROUTED)
                            watch = devices.watch { evaluate() }
                            // It may have gone between the first check and the watch.
                            if (devices.outputs().none { it.sameDevice(bound) }) return@suspendCancellableCoroutine fail(HeadsetText.PLAYBACK_LOST)
                            routeWatch = runCatching { created.watchRoute({ evaluate() }, { fail(HeadsetText.PLAYBACK_LOST) }) }.getOrNull()
                                ?: return@suspendCancellableCoroutine fail(HeadsetText.PLAYBACK_UNROUTED)
                            // A loss delivered while the subscription was being made already ended it: nothing may start after it.
                            if (closed || !continuation.isActive) return@suspendCancellableCoroutine
                            val poll = object : Runnable {
                                override fun run() {
                                    evaluate()
                                    if (!confirmed && continuation.isActive && !closed) main.postDelayed(this, ROUTE_POLL_MS)
                                }
                            }
                            tick = poll
                            main.postDelayed(poll, ROUTE_POLL_MS)
                            val limit = Runnable { if (!confirmed) fail(HeadsetText.PLAYBACK_UNROUTED) }
                            deadline = limit
                            main.postDelayed(limit, ROUTE_CONFIRM_MS)
                        }
                        try {
                            created.play(file.absolutePath, onStarted = {
                                started = true
                                evaluate()
                            }, onCompleted = {
                                if (bound != null && !confirmed && continuation.isActive) {
                                    // Finished before the personal output was ever proven: nothing was audible, so it did not play.
                                    fail(HeadsetText.PLAYBACK_UNROUTED)
                                } else {
                                    closePlayer(stop = false)
                                    if (continuation.isActive) {
                                        finished()
                                        continuation.resume(Unit)
                                    }
                                }
                            }, onError = { what, extra ->
                                closePlayer(stop = false)
                                if (continuation.isActive) continuation.resumeWithException(HermesPlaybackException("phone playback error $what/$extra"))
                            })
                        } catch (error: Exception) {
                            closePlayer(stop = false)
                            continuation.resumeWithException(HermesPlaybackException("phone playback failed: ${error.javaClass.simpleName}"))
                        }
                    }
                } finally {
                    // Every ending (done, error, focus, route, cancel) passes here: mute and stop first, then drop what listens.
                    closePlayer(stop = true)
                    tick?.let { main.removeCallbacks(it) }
                    deadline?.let { main.removeCallbacks(it) }
                    runCatching { routeWatch?.close() }
                    runCatching { watch?.close() }
                    focus?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
                }
            }
        } finally {
            file.delete()
            PhoneApp.from(context).lastPhonePlaybackEndedAtMs = SystemClock.elapsedRealtime()
        }
    }

    companion object {
        const val ROUTE_POLL_MS = 100L
        const val ROUTE_CONFIRM_MS = 3000L
    }
}
