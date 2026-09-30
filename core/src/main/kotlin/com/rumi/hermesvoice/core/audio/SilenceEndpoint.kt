package com.rumi.hermesvoice.core.audio

import kotlin.math.max
import kotlin.math.sqrt

enum class EndpointDecision { CONTINUE, END_OF_SPEECH, NO_SPEECH, MAX_DURATION }

/**
 * Noise-relative end-of-speech detector for hands-free (wake phrase) requests, fed 16-bit
 * little-endian mono PCM frames that the microphone actually delivered.
 *
 * - The first [calibrationMs] estimate the noise floor; [calibrated] then turns true, which is when
 *   the Watch gives its "speak now" cue, so the request itself is never part of the calibration.
 * - Voiced audio is energy above `floor × onsetRatio`. Speech is qualified after [minSpeechMs] of
 *   voiced audio in which no dip lasts longer than [maxDipMs], so syllables separated by short dips
 *   count, while isolated clicks never do. Until then the floor adapts slowly, and
 *   [noSpeechTimeoutMs] (counted from the cue) ends a request that never started.
 * - Once speech started there is NO duration cap. Trailing silence is counted only on frames below
 *   `floor × releaseRatio`; any louder frame pauses the count, and [respeechMs] of renewed voiced
 *   audio (again tolerating short dips) resets it. The request ends after [silenceMs] of silence.
 * Push-to-talk does not use this.
 */
class SilenceEndpoint(
    private val sampleRate: Int = 16_000,
    private val calibrationMs: Long = 400,
    private val minSpeechMs: Long = 300,
    private val maxDipMs: Long = 200,
    private val silenceMs: Long = 2_000,
    private val noSpeechTimeoutMs: Long = 8_000,
    private val respeechMs: Long = 200,
    private val onsetRatio: Double = 3.0,
    private val releaseRatio: Double = 2.0,
) {
    private enum class Phase { CALIBRATING, ARMED, SPEECH, SILENCE, DONE }

    private var phase = Phase.CALIBRATING
    private var calibrationElapsedMs = 0L
    private var armedMs = 0L
    private val calibration = ArrayList<Double>()
    private var floor = MIN_FLOOR
    private var voicedMs = 0L
    private var dipMs = 0L
    private var quietMs = 0L

    var speechDetected: Boolean = false
        private set

    /** True once the noise floor is known: the moment to cue the user. */
    val calibrated: Boolean get() = phase != Phase.CALIBRATING

    fun accept(frame: ByteArray): EndpointDecision {
        val samples = frame.size / 2
        if (samples == 0 || phase == Phase.DONE) return EndpointDecision.CONTINUE
        val durationMs = samples * 1000L / sampleRate
        val level = rms(frame)
        val voiced = level >= floor * onsetRatio
        when (phase) {
            Phase.CALIBRATING -> {
                calibration += level
                calibrationElapsedMs += durationMs
                if (calibrationElapsedMs >= calibrationMs) {
                    val sorted = calibration.sorted()
                    floor = max(MIN_FLOOR, sorted[(sorted.size - 1) / 2])
                    phase = Phase.ARMED
                }
            }
            Phase.ARMED -> {
                armedMs += durationMs
                track(voiced, durationMs)
                if (!voiced && voicedMs == 0L) adapt(level)
                if (voicedMs >= minSpeechMs) {
                    phase = Phase.SPEECH
                    speechDetected = true
                } else if (armedMs >= noSpeechTimeoutMs) {
                    phase = Phase.DONE
                    return EndpointDecision.NO_SPEECH
                }
            }
            Phase.SPEECH -> if (level < floor * releaseRatio) {
                phase = Phase.SILENCE
                quietMs = durationMs
                voicedMs = 0
                dipMs = 0
            }
            Phase.SILENCE -> {
                if (level < floor * releaseRatio) quietMs += durationMs
                track(voiced, durationMs)
                if (voicedMs >= respeechMs) {
                    phase = Phase.SPEECH
                    quietMs = 0
                } else if (quietMs >= silenceMs) {
                    phase = Phase.DONE
                    return EndpointDecision.END_OF_SPEECH
                }
            }
            Phase.DONE -> Unit
        }
        return EndpointDecision.CONTINUE
    }

    /** Voiced time accumulated across dips of at most [maxDipMs]; a longer dip starts over. */
    private fun track(voiced: Boolean, durationMs: Long) {
        if (voiced) {
            voicedMs += durationMs
            dipMs = 0
        } else if (voicedMs > 0) {
            dipMs += durationMs
            if (dipMs > maxDipMs) {
                voicedMs = 0
                dipMs = 0
            }
        }
    }

    /** Slow floor tracking for stationary noise, only from non-speech frames. */
    private fun adapt(level: Double) {
        floor = max(MIN_FLOOR, floor * (1 - ADAPTATION) + level * ADAPTATION)
    }

    companion object {
        private const val MIN_FLOOR = 60.0
        private const val ADAPTATION = 0.02

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

/**
 * Bounds repeated `AudioRecord.read() <= 0`: a capture that keeps getting no audio is a
 * microphone failure, not a silent recording. [onRead] returns false once the bound is hit.
 */
class MicReadGuard(private val maxConsecutiveFailures: Int = 25) {
    private var failures = 0

    var positiveReadSeen: Boolean = false
        private set

    fun onRead(bytesRead: Int): Boolean {
        if (bytesRead > 0) {
            failures = 0
            positiveReadSeen = true
            return true
        }
        failures += 1
        return failures < maxConsecutiveFailures
    }
}
