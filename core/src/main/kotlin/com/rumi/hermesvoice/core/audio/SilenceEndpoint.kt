package com.rumi.hermesvoice.core.audio

import kotlin.math.max

enum class EndpointDecision { CONTINUE, END_OF_SPEECH, NO_SPEECH }

/**
 * End-of-request detector for hands-free (wake phrase) requests on the Phone and the Watch, fed
 * the 16-bit little-endian mono PCM the microphone actually delivered, in any chunk size. It runs
 * the shared [EnergyVad] ([VadProfile.HANDS_FREE]) on exact 20 ms frames, so its timing depends on
 * audio time only, not on the device's read size or sample rate.
 *
 * - The first [calibrationMs] estimate the background (their 25th-percentile frame level);
 *   [calibrated] then turns true, which is when the device cues "speak now", so the request itself
 *   is never part of the calibration.
 * - Until speech qualifies ([VadProfile.minSpeechMs]), [noSpeechTimeoutMs] counted from the cue
 *   ends a request that never started ([EndpointDecision.NO_SPEECH]: nothing is sent). A steady
 *   sound that started with nobody speaking first counts as speech, but once the detector absorbs
 *   it as background ([EnergyVad.absorbingSteadyNoise]) the request is back to "not started", on
 *   the same clock from the cue.
 * - Once speech started there is NO duration cap. The request ends after [silenceMs] (the user's
 *   "trailing silence" setting) of counted non-voice: background frames count fully, frames a
 *   little above it ([VadClass.GRAY], e.g. a fan that started) at half rate while the floor adapts
 *   to them, so such a tail still ends the request, within 2 × [silenceMs]. Voiced frames pause
 *   the count; [respeechMs] of renewed speech (dips tolerated) restarts it, which is the same bar
 *   as the first speech, so words said one at a time keep the request open like the first one did.
 * Push-to-talk does not use this: it ends only when the user taps.
 */
class SilenceEndpoint(
    private val sampleRate: Int = 16_000,
    val silenceMs: Long = 2_000,
    private val noSpeechTimeoutMs: Long = 8_000,
    private val calibrationMs: Long = 400,
    private val profile: VadProfile = VadProfile.HANDS_FREE,
    private val respeechMs: Long = profile.minSpeechMs,
) {
    private enum class Phase { CALIBRATING, ARMED, SPEECH, SILENCE, DONE }

    private val framer = PcmFramer(sampleRate)
    private val frameMs = PcmFramer.FRAME_MS.toLong()
    private var phase = Phase.CALIBRATING
    private val calibration = ArrayList<Double>()
    private var vad: EnergyVad? = null
    private var cuedAtMs = 0L

    /** Something was said and ended before: whatever follows belongs to a request that did start. */
    private var pausedBefore = false
    private var quietMs = 0L

    /** Audio time processed so far, in ms. */
    var elapsedMs: Long = 0
        private set

    /** Audio time at the end of the last voiced frame, or -1 before any speech. */
    var lastVoicedEndMs: Long = -1
        private set

    var speechDetected: Boolean = false
        private set

    /** True once the background is known: the moment to cue the user. */
    val calibrated: Boolean get() = phase != Phase.CALIBRATING

    /** The adaptive background floor (RMS), or NaN while calibrating. */
    val floor: Double get() = vad?.floor ?: Double.NaN

    fun accept(chunk: ByteArray): EndpointDecision {
        if (phase == Phase.DONE) return EndpointDecision.CONTINUE
        var decision = EndpointDecision.CONTINUE
        framer.push(chunk) { level ->
            decision = frame(level)
            decision == EndpointDecision.CONTINUE
        }
        return decision
    }

    private fun frame(level: Double): EndpointDecision {
        elapsedMs += frameMs
        if (phase == Phase.CALIBRATING) {
            calibration += level
            if (calibration.size * frameMs >= calibrationMs) {
                val sorted = calibration.sorted()
                vad = EnergyVad(profile, max(profile.minFloor, sorted[(sorted.size - 1) / 4]), calibration.toDoubleArray())
                cuedAtMs = elapsedMs
                phase = Phase.ARMED
            }
            return EndpointDecision.CONTINUE
        }
        val vad = vad!!
        val cls = vad.observe(level, frameMs)
        if (cls == VadClass.VOICED) lastVoicedEndMs = elapsedMs
        when (phase) {
            Phase.ARMED -> if (vad.qualified) {
                phase = Phase.SPEECH
                speechDetected = true
            } else if (notStarted()) {
                return EndpointDecision.NO_SPEECH
            }
            Phase.SPEECH -> if (cls != VadClass.VOICED) {
                if (!pausedBefore && !vad.voiceHeard && vad.absorbingSteadyNoise) {
                    // What qualified was steady noise that started with nobody speaking: not a request yet.
                    phase = Phase.ARMED
                    speechDetected = false
                    lastVoicedEndMs = -1
                    vad.forgetSpeech()
                    if (notStarted()) return EndpointDecision.NO_SPEECH
                } else {
                    phase = Phase.SILENCE
                    pausedBefore = true
                    quietMs = weight(cls)
                    vad.resetRun()
                }
            }
            Phase.SILENCE -> {
                if (cls != VadClass.VOICED) quietMs += weight(cls)
                if (vad.qualifiedFor(respeechMs)) {
                    phase = Phase.SPEECH
                    quietMs = 0
                }
            }
            else -> Unit
        }
        if (phase == Phase.SILENCE && quietMs >= silenceMs) {
            phase = Phase.DONE
            return EndpointDecision.END_OF_SPEECH
        }
        return EndpointDecision.CONTINUE
    }

    private fun weight(cls: VadClass): Long = if (cls == VadClass.QUIET) frameMs else frameMs / 2

    /** No request [noSpeechTimeoutMs] after the cue: ends the capture as "no speech". */
    private fun notStarted(): Boolean {
        if (elapsedMs - cuedAtMs < noSpeechTimeoutMs) return false
        phase = Phase.DONE
        return true
    }

    companion object {
        /** A hands-free endpoint for a validated trailing-silence setting (see VadSilence). */
        fun forSilenceSeconds(seconds: Double, sampleRate: Int = 16_000): SilenceEndpoint =
            SilenceEndpoint(sampleRate = sampleRate, silenceMs = Math.round(seconds * 1000))

        fun rms(frame: ByteArray): Double = PcmFramer.rms(frame)
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
