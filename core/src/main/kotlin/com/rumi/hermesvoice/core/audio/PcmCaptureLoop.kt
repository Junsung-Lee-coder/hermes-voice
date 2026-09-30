package com.rumi.hermesvoice.core.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

/** Why a capture stopped on its own (a user tap or lifecycle stop is decided by the caller). There is no duration limit. */
enum class CaptureEnd { SILENCE, NO_SPEECH, LIMIT, MIC_ERROR }

/**
 * Privacy-safe aggregates of what the microphone delivered: no audio or text. For hands-free
 * captures, [silenceMs] is the trailing-silence setting the capture ran with, [speechEndMs] the
 * audio time of the last voiced frame (-1 without speech) and [endMs] the audio time it ended.
 */
data class CaptureStats(
    val pcmBytes: Int,
    val peak: Int,
    val rms: Int,
    val speech: Boolean,
    val silenceMs: Long = -1,
    val speechEndMs: Long = -1,
    val endMs: Long = -1,
)

/** The microphone as the capture loop reads it (`AudioRecord.read`): bytes read, or ≤ 0 for none. */
fun interface PcmSource {
    fun read(buffer: ByteArray): Int
}

/**
 * The recording loop shared by the Phone and Watch recorders, independent of Android: reads the
 * [source] into memory, reports the first real audio ([Listener.onLive]), feeds the optional
 * hands-free [endpoint] and reports its calibration ([Listener.onCalibrated], the "speak now" cue)
 * and its end, the storage bound [limitBytes] (reported as [CaptureEnd.LIMIT]: the caller discards,
 * never sends a truncated request) and repeated empty reads ([CaptureEnd.MIC_ERROR]).
 * [Listener.onEnd] is called at most once, from the loop's thread.
 */
class PcmCaptureLoop(
    private val source: PcmSource,
    private val limitBytes: Long,
    private val endpoint: SilenceEndpoint?,
    private val listener: Listener,
    private val chunkBytes: Int = 3_200,
    private val retryDelay: (Long) -> Unit = { Thread.sleep(it) },
) {
    interface Listener {
        fun onLive()
        fun onCalibrated()
        fun onEnd(reason: CaptureEnd)
    }

    private val pcm = ByteArrayOutputStream()
    private val running = AtomicBoolean(true)
    @Volatile private var peak = 0
    @Volatile private var sumSquares = 0.0
    @Volatile private var samples = 0L

    /** Runs until [stop], an end reason, or a microphone failure. */
    fun run() {
        val buffer = ByteArray(chunkBytes)
        val guard = MicReadGuard()
        var live = false
        var cued = false
        while (running.get()) {
            val read = source.read(buffer)
            if (!guard.onRead(read)) {
                finish(CaptureEnd.MIC_ERROR)
                break
            }
            if (read <= 0) {
                retryDelay(READ_RETRY_MS)
                continue
            }
            if (!running.get()) break
            if (!live) {
                live = true
                listener.onLive()
            }
            val chunk = buffer.copyOf(read)
            measure(chunk)
            val size = synchronized(pcm) { pcm.write(chunk); pcm.size() }
            val decision = endpoint?.accept(chunk) ?: EndpointDecision.CONTINUE
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

    /** Stops the loop without an end callback (the caller decided the stop). */
    fun stop() = running.set(false)

    fun pcm(): ByteArray = synchronized(pcm) { pcm.toByteArray() }

    fun stats(): CaptureStats = CaptureStats(
        pcmBytes = synchronized(pcm) { pcm.size() },
        peak = peak,
        rms = if (samples == 0L) 0 else sqrt(sumSquares / samples).toInt(),
        speech = endpoint?.speechDetected ?: false,
        silenceMs = endpoint?.silenceMs ?: -1,
        speechEndMs = endpoint?.lastVoicedEndMs ?: -1,
        endMs = endpoint?.elapsedMs ?: -1,
    )

    private fun finish(reason: CaptureEnd) {
        if (running.getAndSet(false)) listener.onEnd(reason)
    }

    private fun measure(chunk: ByteArray) {
        var i = 0
        var localPeak = peak
        var squares = 0.0
        while (i + 1 < chunk.size) {
            val sample = ((chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 0xff)).toShort().toInt()
            localPeak = maxOf(localPeak, abs(sample))
            squares += sample.toDouble() * sample
            i += 2
        }
        peak = localPeak
        sumSquares += squares
        samples += chunk.size / 2
    }

    companion object {
        private const val READ_RETRY_MS = 20L

        /** A 16-bit mono PCM WAV of [pcm], or null for under 100 ms of audio ("too short"). */
        fun wav(pcm: ByteArray, sampleRate: Int = 16_000): ByteArray? {
            if (pcm.size < sampleRate / 5) return null
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
