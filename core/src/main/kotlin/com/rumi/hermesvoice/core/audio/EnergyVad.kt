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
 * - The floor (when [adaptive]) is estimated from the last [windowMs] of frame levels:
 *   - it FALLS quickly (time constant [fallTimeConstantMs]) to the window's low level (its
 *     [lowRank]-th quietest frame) whenever that is below it, so a floor that was set too high,
 *     by talking through the calibration or by a steady sound, recovers at the next natural dips
 *     of speech;
 *   - it RISES (time constant [riseTimeConstantMs]) only towards the quietest frame of a
 *     STATIONARY window (loudest frame under [stationaryRatio] × quietest, about 6 dB), and only
 *     if that window is not voiced, or is voiced but either barely above the onset (under
 *     [nearOnsetRatio] × onset) or clearly quieter than the speech heard so far (at most
 *     [absorbBelowSpeech] × the typical voiced level). Speech is strongly modulated, so it never
 *     raises the floor; a steady sound as loud as the speech (a held vowel, a vacuum while
 *     talking) is never taken for background either.
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
    val lowRank: Int = 3,
    val fallTimeConstantMs: Double = 100.0,
    val riseTimeConstantMs: Double = 1_000.0,
    val stationaryRatio: Double = 2.0,
    val nearOnsetRatio: Double = 1.5,
    val absorbBelowSpeech: Double = 0.45,
) {
    companion object {
        /** The shortest voiced sound that counts as speech: a short word, but longer than any isolated click (≤ 100 ms). */
        const val MIN_SPEECH_MS = 140L

        /** Hands-free requests: confident onset, so speech must stand out clearly to keep a request open. */
        val HANDS_FREE = VadProfile(onsetRatio = 3.0, releaseRatio = 2.0)

        /**
         * Finished recordings (push-to-talk and hands-free) before speech-to-text: the same frames
         * and qualification, against a fixed floor (the whole recording is known, so nothing
         * adapts) and a low onset. It only refuses recordings in which nothing rises half again
         * above their own background: silence and steady noise, not speech in noise.
         */
        val ELIGIBILITY = VadProfile(onsetRatio = 1.5, releaseRatio = 1.5, adaptive = false)
    }
}

/**
 * The energy-floor detector shared by the Phone and the Watch: the hands-free endpoint
 * ([SilenceEndpoint]) and the recording eligibility check ([AudioInputGate]) both run it on the
 * same 20 ms frames ([PcmFramer]). It measures loudness against the background, not speech: loud
 * non-speech sound counts as voiced and very soft speech may not.
 */
class EnergyVad(val profile: VadProfile, initialFloor: Double) {
    /** Current background estimate (RMS). */
    var floor: Double = max(initialFloor, 1.0)
        private set

    /** Voiced time of the current run, tolerating dips of at most [VadProfile.maxDipMs]. */
    var runMs: Long = 0
        private set

    /** Time since the current run started, dips included. */
    private var spanMs = 0L
    private var dipMs = 0L
    private var inRun = false
    private var hangMs = 0L
    private var window = DoubleArray(0)
    private var sorted = DoubleArray(0)
    private var windowFilled = 0
    private var windowNext = 0

    /** Mean level of the voiced frames of qualified, modulated speech so far; 0 before any. */
    private var speechLevel = 0.0
    private var speechFrames = 0L

    /** The current run is long and dense enough to be speech. */
    val qualified: Boolean get() = qualifiedFor(profile.minSpeechMs)

    /** The current run has at least [voicedMs] of voiced audio and is dense enough to be speech. */
    fun qualifiedFor(voicedMs: Long): Boolean = runMs >= voicedMs && runMs >= spanMs * profile.minDensity

    fun observe(level: Double, frameMs: Long): VadClass {
        val stationary = if (profile.adaptive) track(level, frameMs) else false
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
        if (inRun) {
            runMs += frameMs
            spanMs += frameMs
            dipMs = 0
            // Learn how loud this talker is, from real (modulated, qualified) speech only.
            if (hangMs == 0L && !stationary && qualified) {
                speechFrames += 1
                speechLevel += (level - speechLevel) / speechFrames
            }
        } else if (runMs > 0) {
            dipMs += frameMs
            spanMs += frameMs
            if (dipMs > profile.maxDipMs) resetRun()
        }
        return cls
    }

    /** Starts counting a new run (e.g. renewed speech after the request paused). */
    fun resetRun() {
        runMs = 0
        spanMs = 0
        dipMs = 0
    }

    /** Updates the floor from the recent window; returns whether that window is stationary. */
    private fun track(level: Double, frameMs: Long): Boolean {
        if (window.isEmpty()) {
            window = DoubleArray((profile.windowMs / frameMs).toInt().coerceAtLeast(profile.lowRank))
            sorted = DoubleArray(window.size)
        }
        window[windowNext] = level
        windowNext = (windowNext + 1) % window.size
        if (windowFilled < window.size) windowFilled++
        if (windowFilled < MIN_WINDOW_FRAMES.coerceAtMost(window.size)) return false
        System.arraycopy(window, 0, sorted, 0, windowFilled)
        java.util.Arrays.sort(sorted, 0, windowFilled)
        val minimum = sorted[0]
        val maximum = sorted[windowFilled - 1]
        val low = sorted[(profile.lowRank - 1).coerceAtMost(windowFilled - 1)]
        if (low < floor) {
            floor = max(1.0, floor + (low - floor) * (1 - exp(-frameMs / profile.fallTimeConstantMs)))
        }
        val stationary = windowFilled == window.size && maximum < minimum * profile.stationaryRatio
        if (stationary && minimum > floor) {
            val onset = max(floor, profile.minFloor) * profile.onsetRatio
            val background = maximum < onset * profile.nearOnsetRatio ||
                (speechLevel > 0 && maximum <= speechLevel * profile.absorbBelowSpeech)
            if (background) floor += (minimum - floor) * (1 - exp(-frameMs / profile.riseTimeConstantMs))
        }
        return stationary
    }

    private companion object {
        const val MIN_WINDOW_FRAMES = 15
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
