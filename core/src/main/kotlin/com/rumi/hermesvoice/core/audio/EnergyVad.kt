package com.rumi.hermesvoice.core.audio

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/** How one 20 ms frame compares with the background: clearly louder, a little louder, or background. */
enum class VadClass { VOICED, GRAY, QUIET }

/**
 * Thresholds of the shared energy voice-activity detector. Levels are RMS of 16-bit PCM; ratios are
 * relative to the background floor (never below [minFloor]).
 *
 * - A frame at or above `floor × onsetRatio` is voiced. Once voiced, frames down to
 *   `floor × releaseRatio` stay voiced for at most [hangoverMs] (bounded hysteresis: a syllable's
 *   fading tail is not a pause, but background that rose after speech cannot keep "speaking").
 * - Speech is qualified after [minSpeechMs] of voiced audio in a run whose gaps never exceeded
 *   [maxDipMs] and in which at least [minDensity] of the time was voiced: syllables separated by
 *   short dips count; an isolated click or knock, or sparse clicks such as typing, do not.
 * - The floor (when [adaptive]) follows the last [windowMs] of frame levels, differently before
 *   and after speech was heard in the request:
 *   - **Before speech**, the background is the typical level. When that second is one level (its
 *     upper quartile within [compactRatio] × its lower quartile, about 6 dB) the floor follows its
 *     median: up within [riseTimeConstantMs] if at most [backgroundVoicedShare] of it was voiced,
 *     down slowly ([settleTimeConstantMs]). So a background whose level wanders is not mistaken
 *     for speech at its louder moments.
 *   - **Once speech was heard**, the background is what the speech sits on: the floor follows the
 *     second's lower quartile (the dips between words) within [followTimeConstantMs]. It rises
 *     only to levels at most [absorbBelowSpeech] × this talker's typical voiced level, and only
 *     slowly ([creepTimeConstantMs]) while more than [backgroundVoicedShare] of the second is
 *     voiced: soft words stay above it, a background that rose does not hold the request open.
 *   - A floor far too high (the lower quartile under [farBelowRatio] × it, e.g. after talking
 *     through the calibration) falls at once ([fallTimeConstantMs]).
 *   - A STEADY loud sound (a full second whose loudest frame is under [stationaryRatio] × its
 *     quietest) is absorbed ([followTimeConstantMs]) when it is barely above the onset (under
 *     [nearOnsetRatio] × onset), clearly quieter than the speech heard so far, or, before any
 *     voice-like speech, when its level does not even wobble like a voice (its 90th-percentile
 *     frame under [steadyRatio] × its 10th, over at least [steadyFrames] frames): noise that
 *     starts with nobody speaking. A held vowel, a sound as loud as the speech, or a vacuum while
 *     talking is never taken for background.
 */
data class VadProfile(
    val onsetRatio: Double,
    val releaseRatio: Double,
    val minFloor: Double = 60.0,
    val minSpeechMs: Long = MIN_SPEECH_MS,
    val maxDipMs: Long = 200,
    val minDensity: Double = 0.45,
    val hangoverMs: Long = 200,
    val adaptive: Boolean = true,
    val windowMs: Long = 1_000,
    val compactRatio: Double = 2.0,
    val backgroundVoicedShare: Double = 0.2,
    val riseTimeConstantMs: Double = 300.0,
    val settleTimeConstantMs: Double = 2_000.0,
    val followTimeConstantMs: Double = 1_000.0,
    val creepTimeConstantMs: Double = 4_000.0,
    val farBelowRatio: Double = 0.5,
    val fallTimeConstantMs: Double = 100.0,
    val stationaryRatio: Double = 2.0,
    val nearOnsetRatio: Double = 1.5,
    val absorbBelowSpeech: Double = 0.45,
    val steadyRatio: Double = 1.15,
    val steadyFrames: Int = 25,
) {
    companion object {
        /** The shortest voiced sound that counts as speech: a short word, but longer than any isolated click (≤ 100 ms). */
        const val MIN_SPEECH_MS = 140L

        /** Hands-free requests: confident onset, so speech must stand out clearly to keep a request open. */
        val HANDS_FREE = VadProfile(onsetRatio = 3.0, releaseRatio = 2.0)

        /**
         * Finished recordings (push-to-talk and hands-free) before speech-to-text: the same frames
         * and qualification, against a fixed floor (the whole recording is known, so nothing
         * adapts) and a low onset that [AudioInputGate] raises when the background itself varies.
         */
        val ELIGIBILITY = VadProfile(onsetRatio = 1.5, releaseRatio = 1.5, adaptive = false)
    }
}

/**
 * The energy-floor detector shared by the Phone and the Watch: the hands-free endpoint
 * ([SilenceEndpoint]) and the recording eligibility check ([AudioInputGate]) both run it on the
 * same 20 ms frames ([PcmFramer]). It measures loudness against the background, not speech: loud
 * changing non-speech sound counts as voiced and speech that does not stand out from the
 * background may not. [history] is background already heard (the calibration frames).
 */
class EnergyVad(val profile: VadProfile, initialFloor: Double, history: DoubleArray = DoubleArray(0)) {
    /** Current background estimate (RMS). */
    var floor: Double = max(initialFloor, 1.0)
        private set

    /** Voiced time of the current run, tolerating dips of at most [VadProfile.maxDipMs]. */
    var runMs: Long = 0
        private set

    /** Speech qualified in this request (and was not taken back by [forgetSpeech]). */
    var heard: Boolean = false
        private set

    /** Speech that wobbles like a voice was heard: a steady sound can no longer be the first thing in the request. */
    var voiceHeard: Boolean = false
        private set

    /** The last frame was absorbed as steady noise with no voice before it (see [VadProfile.steadyRatio]). */
    var absorbingSteadyNoise: Boolean = false
        private set

    /** Time since the current run started, dips included. */
    private var spanMs = 0L
    private var dipMs = 0L
    private var inRun = false
    private var hangMs = 0L

    /** The last [VadProfile.windowMs] of levels, and whether each frame before the newest was voiced. */
    private var window = DoubleArray(0)
    private var voicedFlags = BooleanArray(0)
    private var sorted = DoubleArray(0)
    private var windowFilled = 0
    private var windowNext = 0
    private var flagsFilled = 0
    private var flagsNext = 0
    private var voicedInWindow = 0
    private var pending: DoubleArray? = history.takeIf { it.isNotEmpty() }

    /** Levels of the voiced frames of the current stretch (hangover excluded), for [wobble]. */
    private val loud = DoubleArray(LOUD_FRAMES)
    private val loudSorted = DoubleArray(LOUD_FRAMES)
    private var loudCount = 0

    /** Mean level of the voiced frames of qualified speech so far; 0 before any. */
    private var speechLevel = 0.0
    private var speechFrames = 0L

    /** The current run is long and dense enough to be speech. */
    val qualified: Boolean get() = qualifiedFor(profile.minSpeechMs)

    /** The current run has at least [voicedMs] of voiced audio and is dense enough to be speech. */
    fun qualifiedFor(voicedMs: Long): Boolean = runMs >= voicedMs && runMs >= spanMs * profile.minDensity

    fun observe(level: Double, frameMs: Long): VadClass {
        if (profile.adaptive) {
            pending?.let { primed ->
                pending = null
                for (past in primed) {
                    push(past, frameMs)
                    flag(false)
                }
            }
            push(level, frameMs)
            track(frameMs)
        }
        val base = max(floor, profile.minFloor)
        val cls = when {
            level >= base * profile.onsetRatio -> {
                hangMs = 0
                VadClass.VOICED
            }
            inRun && level >= base * profile.releaseRatio && hangMs + frameMs <= profile.hangoverMs -> {
                hangMs += frameMs
                VadClass.VOICED
            }
            level >= base * profile.releaseRatio -> VadClass.GRAY
            else -> VadClass.QUIET
        }
        inRun = cls == VadClass.VOICED
        if (profile.adaptive) flag(inRun)
        if (inRun) {
            runMs += frameMs
            spanMs += frameMs
            dipMs = 0
            if (hangMs == 0L) {
                loud[loudCount % loud.size] = level
                loudCount++
            }
            if (qualified) {
                heard = true
                val wobble = wobble()
                if (wobble != null && wobble >= profile.steadyRatio) voiceHeard = true
                // How loud this talker is: learnt from speech, never from a sound known to be steady.
                if (hangMs == 0L && !(wobble != null && wobble < profile.steadyRatio)) {
                    speechFrames += 1
                    speechLevel += (level - speechLevel) / speechFrames
                }
            }
        } else if (runMs > 0) {
            dipMs += frameMs
            spanMs += frameMs
            if (dipMs > profile.maxDipMs) {
                resetRun()
                loudCount = 0
            }
        }
        return cls
    }

    /** Starts counting a new run (e.g. renewed speech after the request paused). */
    fun resetRun() {
        runMs = 0
        spanMs = 0
        dipMs = 0
    }

    /** What qualified was steady noise, not a request: forget it and what it taught about the talker. */
    fun forgetSpeech() {
        resetRun()
        heard = false
        speechLevel = 0.0
        speechFrames = 0
        loudCount = 0
    }

    /** How much the recent voiced frames vary (90th over 10th percentile), or null with too few to tell. */
    private fun wobble(): Double? {
        val n = minOf(loudCount, loud.size)
        if (n < profile.steadyFrames) return null
        System.arraycopy(loud, 0, loudSorted, 0, n)
        java.util.Arrays.sort(loudSorted, 0, n)
        return loudSorted[n - 1 - n / 10] / max(loudSorted[n / 10], 1e-9)
    }

    private fun push(level: Double, frameMs: Long) {
        if (window.isEmpty()) {
            window = DoubleArray((profile.windowMs / frameMs).toInt().coerceAtLeast(MIN_WINDOW_FRAMES))
            voicedFlags = BooleanArray(window.size)
            sorted = DoubleArray(window.size)
        }
        window[windowNext] = level
        windowNext = (windowNext + 1) % window.size
        if (windowFilled < window.size) windowFilled++
    }

    private fun flag(voiced: Boolean) {
        if (voicedFlags.isEmpty()) return
        if (flagsFilled == voicedFlags.size) {
            if (voicedFlags[flagsNext]) voicedInWindow--
        } else {
            flagsFilled++
        }
        voicedFlags[flagsNext] = voiced
        if (voiced) voicedInWindow++
        flagsNext = (flagsNext + 1) % voicedFlags.size
    }

    /** Updates the floor from the recent window (see [VadProfile]). */
    private fun track(frameMs: Long) {
        absorbingSteadyNoise = false
        val n = windowFilled
        if (n < MIN_WINDOW_FRAMES) return
        System.arraycopy(window, 0, sorted, 0, n)
        java.util.Arrays.sort(sorted, 0, n)
        val lowerQuartile = sorted[(n - 1) / 4]
        val median = sorted[(n - 1) / 2]
        val upperQuartile = sorted[3 * (n - 1) / 4]
        val fewVoiced = voicedInWindow <= profile.backgroundVoicedShare * n
        if (heard) {
            if (lowerQuartile < floor) {
                follow(lowerQuartile, if (lowerQuartile < floor * profile.farBelowRatio) profile.fallTimeConstantMs else profile.followTimeConstantMs, frameMs)
            } else if (speechLevel <= 0 || lowerQuartile <= speechLevel * profile.absorbBelowSpeech) {
                follow(lowerQuartile, if (fewVoiced) profile.followTimeConstantMs else profile.creepTimeConstantMs, frameMs)
            }
        } else if (upperQuartile <= lowerQuartile * profile.compactRatio) {
            if (median < floor) {
                follow(median, if (median < floor * profile.farBelowRatio) profile.fallTimeConstantMs else profile.settleTimeConstantMs, frameMs)
            } else if (fewVoiced) {
                follow(median, profile.riseTimeConstantMs, frameMs)
            }
        } else if (lowerQuartile < floor * profile.farBelowRatio) {
            follow(lowerQuartile, profile.fallTimeConstantMs, frameMs)
        }
        val minimum = sorted[0]
        val maximum = sorted[n - 1]
        if (n == window.size && maximum < minimum * profile.stationaryRatio && minimum > floor) {
            val onset = max(floor, profile.minFloor) * profile.onsetRatio
            val wobble = wobble()
            val steadyNoise = !voiceHeard && wobble != null && wobble < profile.steadyRatio
            if (maximum < onset * profile.nearOnsetRatio || (speechLevel > 0 && maximum <= speechLevel * profile.absorbBelowSpeech) || steadyNoise) {
                follow(minimum, profile.followTimeConstantMs, frameMs)
                absorbingSteadyNoise = steadyNoise
            }
        }
    }

    private fun follow(target: Double, timeConstantMs: Double, frameMs: Long) {
        floor = max(1.0, floor + (target - floor) * (1 - exp(-frameMs / timeConstantMs)))
    }

    private companion object {
        /** Half a second of frames: the fewest that say anything about the background. */
        const val MIN_WINDOW_FRAMES = 25
        const val LOUD_FRAMES = 50
    }
}

/** Cuts 16-bit little-endian mono PCM, delivered in any chunk size, into exact [FRAME_MS] frames. */
class PcmFramer(sampleRate: Int) {
    val frameSamples: Int = (sampleRate * FRAME_MS / 1000).coerceAtLeast(1)
    private val frame = ByteArray(frameSamples * 2)
    private var filled = 0

    /** Feeds [chunk]; [onFrame] gets each complete frame's RMS and returns false to stop early. */
    fun push(chunk: ByteArray, onFrame: (Double) -> Boolean) {
        var offset = 0
        while (offset < chunk.size) {
            val n = minOf(chunk.size - offset, frame.size - filled)
            System.arraycopy(chunk, offset, frame, filled, n)
            filled += n
            offset += n
            if (filled == frame.size) {
                filled = 0
                if (!onFrame(rms(frame))) return
            }
        }
    }

    companion object {
        const val FRAME_MS = 20

        fun rms(pcm: ByteArray, length: Int = pcm.size): Double {
            var sum = 0.0
            var i = 0
            while (i + 1 < length) {
                val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toDouble()
                sum += sample * sample
                i += 2
            }
            return sqrt(sum / (length / 2).coerceAtLeast(1))
        }
    }
}
