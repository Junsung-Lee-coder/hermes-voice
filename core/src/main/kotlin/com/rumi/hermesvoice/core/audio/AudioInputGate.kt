package com.rumi.hermesvoice.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class AudioInputVerdict {
    /** Has sound loud enough, above its own background, to be worth transcribing. */
    USABLE,

    /** Not a readable 16-bit PCM WAV, or under 100 ms of audio. */
    INVALID,

    /** (Near) digital silence: nothing reached even a quiet speaking level (e.g. a dead or glitched microphone). */
    SILENT,

    /** Only steady background (room, fan) with nothing clearly louder than it. */
    NO_SPEECH_ENERGY,
}

/**
 * A conservative acoustic check run on a finished recording BEFORE it is uploaded, transcribed,
 * routed or allowed to change the playback target. It exists because speech-to-text can "hear"
 * words in silence (a near-silent emulator capture was once transcribed as a web address and
 * delivered). It rejects only recordings with no usable audio:
 *
 * - [AudioInputVerdict.INVALID]: unreadable, not 16-bit PCM, or under [MIN_AUDIO_MS];
 * - [AudioInputVerdict.SILENT]: peak below [MIN_PEAK] (about −44 dBFS), far below soft speech;
 * - [AudioInputVerdict.NO_SPEECH_ENERGY]: less than [MIN_VOICED_MS] of 100 ms frames louder than
 *   [VOICED_RATIO] × the recording's own background (its 10th-percentile frame level, capped at
 *   [FLOOR_CAP] so a recording that is speech from start to end is not mistaken for background)
 *   and at least [MIN_VOICED_RMS].
 *
 * It is an energy test, not a speech detector: loud non-speech sound passes it (and may still be
 * mis-transcribed), and it deliberately leans towards letting soft and short speech through.
 */
object AudioInputGate {
    const val MIN_AUDIO_MS = 100L
    const val MIN_PEAK = 200
    const val MIN_VOICED_MS = 150L
    const val MIN_VOICED_RMS = 100.0
    const val VOICED_RATIO = 1.8
    const val FLOOR_CAP = 400.0
    private const val FRAME_MS = 100L

    /** Recordings in formats this gate cannot read (none today) are left to the dashboard. */
    fun assess(audio: ByteArray, mimeType: String): AudioInputVerdict {
        if (!mimeType.substringBefore(';').trim().equals("audio/wav", ignoreCase = true)) return AudioInputVerdict.USABLE
        val pcm = PcmWav.parse(audio) ?: return AudioInputVerdict.INVALID
        val frameSamples = (pcm.sampleRate * FRAME_MS / 1000).toInt() * pcm.channels
        if (frameSamples <= 0 || pcm.sampleCount < frameSamples) return AudioInputVerdict.INVALID
        var peak = 0
        val levels = DoubleArray(pcm.sampleCount / frameSamples) { frame ->
            var sum = 0.0
            for (i in frame * frameSamples until (frame + 1) * frameSamples) {
                val sample = pcm.sample(i)
                peak = max(peak, abs(sample))
                sum += sample.toDouble() * sample
            }
            sqrt(sum / frameSamples)
        }
        if (peak < MIN_PEAK) return AudioInputVerdict.SILENT
        val sorted = levels.sorted()
        val floor = min(sorted[(sorted.size - 1) / 10], FLOOR_CAP)
        val threshold = max(MIN_VOICED_RMS, floor * VOICED_RATIO)
        val voicedMs = levels.count { it >= threshold } * FRAME_MS
        return if (voicedMs >= MIN_VOICED_MS) AudioInputVerdict.USABLE else AudioInputVerdict.NO_SPEECH_ENERGY
    }
}

/** A minimal RIFF/WAVE reader for the 16-bit PCM recordings the Phone and Watch produce; samples are read in place. */
class PcmWav private constructor(
    val sampleRate: Int,
    val channels: Int,
    private val buffer: ByteBuffer,
    private val dataOffset: Int,
    val sampleCount: Int,
) {
    fun sample(index: Int): Int = buffer.getShort(dataOffset + index * 2).toInt()

    companion object {
        fun parse(bytes: ByteArray): PcmWav? {
            if (bytes.size < 12 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var offset = 12
            var rate = 0
            var channels = 0
            var bits = 0
            var format = 0
            while (offset + 8 <= bytes.size) {
                val id = String(bytes, offset, 4, Charsets.US_ASCII)
                val length = buffer.getInt(offset + 4)
                if (length < 0) return null
                val body = offset + 8
                when (id) {
                    "fmt " -> {
                        if (length < 16 || body + 16 > bytes.size) return null
                        format = buffer.getShort(body).toInt()
                        channels = buffer.getShort(body + 2).toInt()
                        rate = buffer.getInt(body + 4)
                        bits = buffer.getShort(body + 14).toInt()
                    }
                    "data" -> {
                        if (format != 1 || bits != 16 || channels <= 0 || rate <= 0) return null
                        return PcmWav(rate, channels, buffer, body, min(length, bytes.size - body) / 2)
                    }
                }
                offset = body + length + (length and 1)
            }
            return null
        }
    }
}
