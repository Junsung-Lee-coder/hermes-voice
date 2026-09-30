package com.rumi.hermesvoice.core.audio

import kotlin.math.max
import kotlin.math.sqrt

enum class EndpointDecision { CONTINUE, END_OF_SPEECH, NO_SPEECH, MAX_DURATION }

/**
 * Noise-relative end-of-speech detector for hands-free (wake phrase) turns, fed 16-bit
 * little-endian mono PCM frames. The first [calibrationMs] estimate the noise floor; speech
 * starts after [minSpeechMs] above `floor × onsetRatio`; the turn ends after [silenceMs] below
 * `floor × releaseRatio` once speech started. Push-to-talk turns do not use this.
 */
class SilenceEndpoint(
    private val sampleRate: Int = 16_000,
    private val calibrationMs: Long = 300,
    private val minSpeechMs: Long = 200,
    private val silenceMs: Long = 1_200,
    private val noSpeechTimeoutMs: Long = 6_000,
    private val maxDurationMs: Long = 60_000,
    private val onsetRatio: Double = 3.0,
    private val releaseRatio: Double = 2.0,
) {
    private var elapsedMs = 0L
    private val calibration = ArrayList<Double>()
    private var floor = MIN_FLOOR
    private var speechMs = 0L
    private var speaking = false
    private var quietMs = 0L

    val speechDetected: Boolean get() = speaking

    fun accept(frame: ByteArray): EndpointDecision {
        val samples = frame.size / 2
        if (samples == 0) return EndpointDecision.CONTINUE
        val durationMs = samples * 1000L / sampleRate
        elapsedMs += durationMs
        val level = rms(frame)
        if (elapsedMs <= calibrationMs) {
            calibration += level
            if (elapsedMs + durationMs > calibrationMs) floor = max(MIN_FLOOR, calibration.sorted()[calibration.size / 2])
            return EndpointDecision.CONTINUE
        }
        if (elapsedMs >= maxDurationMs) return EndpointDecision.MAX_DURATION
        if (!speaking) {
            speechMs = if (level >= floor * onsetRatio) speechMs + durationMs else 0
            if (speechMs >= minSpeechMs) speaking = true
            return if (!speaking && elapsedMs >= noSpeechTimeoutMs) EndpointDecision.NO_SPEECH else EndpointDecision.CONTINUE
        }
        quietMs = if (level < floor * releaseRatio) quietMs + durationMs else 0
        return if (quietMs >= silenceMs) EndpointDecision.END_OF_SPEECH else EndpointDecision.CONTINUE
    }

    companion object {
        private const val MIN_FLOOR = 60.0

        fun rms(frame: ByteArray): Double {
            var sum = 0.0
            var i = 0
            while (i + 1 < frame.size) {
                val sample = ((frame[i + 1].toInt() shl 8) or (frame[i].toInt() and 0xff)).toShort().toDouble()
                sum += sample * sample
                i += 2
            }
            return sqrt(sum / (frame.size / 2).coerceAtLeast(1))
        }
    }
}
