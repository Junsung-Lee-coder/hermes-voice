package com.rumi.hermesvoice.core.audio

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/** How one 20 ms frame compares with the background: clearly louder, a little louder, or background. */
enum class VadClass { VOICED, GRAY, QUIET }

/**
 * Thresholds of the shared energy voice-activity detector. Levels are RMS of 16-bit PCM; ratios are
 * relative to the adaptive background floor (never below [minFloor]).
 *
 * - A frame at or above `floor × onsetRatio` is voiced. Once voiced, frames down to
 *   `floor × releaseRatio` stay voiced for at most [hangoverMs] (bounded hysteresis: a syllable's
 *   fading tail is not a pause, but background that rose after speech cannot keep "speaking").
 * - Speech is qualified after [minSpeechMs] of voiced audio in which no gap lasted more than
 *   [maxDipMs]: syllables separated by short dips count, a single click or knock never does.
 * - The floor follows the background (time constant [floorTimeConstantMs]) on non-voiced frames of
 *   a non-voiced stretch longer than [qualifiedQuietMs], so the short dips between syllables never
 *   move it. It also rises towards the quietest frame of the last [minimumWindowMs] when that whole
 *   window is stationary (its loudest frame within [stationaryRatio] × its quietest, about 6 dB)
 *   and even its quietest frame is above the floor. Speech is strongly modulated (syllables against
 *   dips), so it never raises the floor; noise that rose and stays steady (even right at the onset,
 *   where it would flicker between voiced and not) does, so it cannot hold a request open forever.
 *   A steady sound held for over a second, e.g. a sung note or a hum, is treated as background too.
 */
data class VadProfile(
    val onsetRatio: Double,
    val releaseRatio: Double,
    val minFloor: Double = 60.0,
    val minSpeechMs: Long = MIN_SPEECH_MS,
    val maxDipMs: Long = 200,
    val hangoverMs: Long = 200,
    val floorTimeConstantMs: Double = 2_000.0,
    val qualifiedQuietMs: Long = 200,
    val minimumWindowMs: Long = 1_000,
    val stationaryRatio: Double = 2.0,
) {
    companion object {
        /** The shortest voiced sound that counts as speech: a short word, but longer than any click (≤ 100 ms). */
        const val MIN_SPEECH_MS = 140L

        /** Hands-free requests: confident onset, so speech must stand out clearly to keep a request open. */
        val HANDS_FREE = VadProfile(onsetRatio = 3.0, releaseRatio = 2.0)

        /**
         * Finished recordings (push-to-talk and hands-free) before speech-to-text: the same detector
         * with a lower onset, so it only refuses recordings with nothing clearly above their background.
         */
        val ELIGIBILITY = VadProfile(onsetRatio = 2.0, releaseRatio = 2.0)
    }
}

/**
 * The adaptive energy-floor detector shared by the Phone and the Watch: the hands-free endpoint
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

    private var dipMs = 0L
    private var inRun = false
    private var hangMs = 0L
    private var quietStreakMs = 0L
    private var window = DoubleArray(0)
    private var windowFilled = 0
    private var windowNext = 0

    /** The current run is long enough to be speech. */
    val qualified: Boolean get() = runMs >= profile.minSpeechMs

    fun observe(level: Double, frameMs: Long): VadClass {
        trackMinimum(level, frameMs)
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
            dipMs = 0
            quietStreakMs = 0
        } else {
            if (runMs > 0) {
                dipMs += frameMs
                if (dipMs > profile.maxDipMs) resetRun()
            }
            quietStreakMs += frameMs
            if (quietStreakMs > profile.qualifiedQuietMs) {
                floor += (level - floor) * (1 - exp(-frameMs / profile.floorTimeConstantMs))
                floor = max(floor, 1.0)
            }
        }
        return cls
    }

    /** Minimum statistics: a full, stationary window whose quietest frame is above the floor pulls the floor up. */
    private fun trackMinimum(level: Double, frameMs: Long) {
        if (window.isEmpty()) window = DoubleArray((profile.minimumWindowMs / frameMs).toInt().coerceAtLeast(1))
        window[windowNext] = level
        windowNext = (windowNext + 1) % window.size
        if (windowFilled < window.size) {
            windowFilled++
            return
        }
        val minimum = window.min()
        if (minimum > floor && window.max() < minimum * profile.stationaryRatio) {
            floor += (minimum - floor) * (1 - exp(-frameMs / profile.floorTimeConstantMs))
        }
    }

    /** Starts counting a new run (e.g. renewed speech after the request paused). */
    fun resetRun() {
        runMs = 0
        dipMs = 0
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
