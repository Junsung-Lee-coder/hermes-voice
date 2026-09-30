package com.rumi.hermesvoice.watch

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.rumi.hermesvoice.core.audio.EndpointDecision
import com.rumi.hermesvoice.core.audio.MicReadGuard
import com.rumi.hermesvoice.core.audio.SilenceEndpoint
import com.rumi.hermesvoice.core.watchlink.LinkFrame
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

/** Why a capture stopped on its own (a user tap or lifecycle stop is decided by the caller). There is no duration limit. */
enum class CaptureEnd { SILENCE, NO_SPEECH, LIMIT, MIC_ERROR }

/** Privacy-safe aggregates of what the microphone delivered: no audio or text. */
data class CaptureStats(val pcmBytes: Int, val peak: Int, val rms: Int, val speech: Boolean)

/**
 * 16 kHz mono PCM16 capture to an in-memory WAV on the app's own `AudioRecord`, with an optional
 * hands-free [SilenceEndpoint]. Callbacks run on the capture thread; the caller hops to the UI.
 *
 * - [Listener.onLive]: first positive read — the microphone really delivers audio (start haptic
 *   for push-to-talk).
 * - [Listener.onCalibrated]: the endpoint knows the noise floor — the "speak now" cue for a
 *   wake-phrase request.
 * - [Listener.onEnd]: trailing silence, no speech, the size bound, or a microphone failure.
 *
 * No capture has a duration cap: [limitBytes] is only the Data Layer frame bound (a storage limit).
 */
class WatchCapture(
    val turnId: String,
    val trigger: TurnTrigger,
    private val limitBytes: Long,
    private val endpoint: SilenceEndpoint?,
    private val listener: Listener,
) {
    interface Listener {
        fun onLive()
        fun onCalibrated()
        fun onEnd(reason: CaptureEnd)
    }

    private val pcm = ByteArrayOutputStream()
    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var peak = 0
    @Volatile private var sumSquares = 0.0
    @Volatile private var samples = 0L

    @SuppressLint("MissingPermission") // Checked by the activity before starting.
    fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, min.coerceAtLeast(FRAME_BYTES * 2))
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: return false
        val started = runCatching { recorder.startRecording() }.isSuccess &&
            recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
        if (!started) {
            recorder.release()
            return false
        }
        record = recorder
        running.set(true)
        worker = Thread({ loop(recorder) }, "hermes-voice-watch-capture").apply { start() }
        return true
    }

    private fun loop(recorder: AudioRecord) {
        val buffer = ByteArray(FRAME_BYTES)
        val guard = MicReadGuard()
        var live = false
        var cued = false
        while (running.get()) {
            val read = recorder.read(buffer, 0, buffer.size)
            if (!guard.onRead(read)) {
                finish(CaptureEnd.MIC_ERROR)
                break
            }
            if (read <= 0) {
                Thread.sleep(READ_RETRY_MS)
                continue
            }
            if (!live) {
                live = true
                listener.onLive()
            }
            val frame = buffer.copyOf(read)
            measure(frame)
            val size = synchronized(pcm) { pcm.write(frame); pcm.size() }
            val decision = endpoint?.accept(frame) ?: EndpointDecision.CONTINUE
            if (endpoint != null && !cued && endpoint.calibrated) {
                cued = true
                listener.onCalibrated()
            }
            when {
                decision == EndpointDecision.END_OF_SPEECH -> finish(CaptureEnd.SILENCE)
                decision == EndpointDecision.NO_SPEECH -> finish(CaptureEnd.NO_SPEECH)
                size >= limitBytes -> finish(CaptureEnd.LIMIT)
            }
        }
    }

    private fun finish(reason: CaptureEnd) {
        if (running.getAndSet(false)) listener.onEnd(reason)
    }

    private fun measure(frame: ByteArray) {
        var i = 0
        var localPeak = peak
        var squares = 0.0
        while (i + 1 < frame.size) {
            val sample = ((frame[i + 1].toInt() shl 8) or (frame[i].toInt() and 0xff)).toShort().toInt()
            localPeak = maxOf(localPeak, abs(sample))
            squares += sample.toDouble() * sample
            i += 2
        }
        peak = localPeak
        sumSquares += squares
        samples += frame.size / 2
    }

    fun stats(): CaptureStats = CaptureStats(synchronized(pcm) { pcm.size() }, peak,
        if (samples == 0L) 0 else sqrt(sumSquares / samples).toInt(), endpoint?.speechDetected ?: false)

    /** Stops the microphone and returns the WAV, or null when too little was captured. */
    fun stop(): ByteArray? {
        running.set(false)
        runCatching { record?.stop() }
        if (Thread.currentThread() !== worker) runCatching { worker?.join(1_500) }
        runCatching { record?.release() }
        record = null
        val data = synchronized(pcm) { pcm.toByteArray() }
        if (data.size < SAMPLE_RATE / 5) return null
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.size)
        }
        return header.array() + data
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_BYTES = 3_200
        private const val READ_RETRY_MS = 20L

        /** The largest WAV one Data Layer turn frame can carry (about 13 minutes): a storage bound, not a time limit. */
        const val FRAME_BOUND_PCM_BYTES: Long = LinkFrame.MAX_PAYLOAD_BYTES - 44L
    }
}
