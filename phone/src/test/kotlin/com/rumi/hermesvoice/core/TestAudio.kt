package com.rumi.hermesvoice.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Deterministic 16 kHz mono PCM16 WAV fixtures for the audio input gate and voice-turn tests. */
object TestAudio {
    const val RATE = 16_000

    fun wav(samples: ShortArray, rate: Int = RATE, bits: Int = 16, channels: Int = 1): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it) } }.array()
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort())
            putInt(rate); putInt(rate * channels * bits / 8); putShort((channels * bits / 8).toShort()); putShort(bits.toShort())
            put("data".toByteArray()); putInt(data.size)
        }.array() + data
    }

    private fun samples(seconds: Double) = (seconds * RATE).toInt()

    fun silence(seconds: Double) = ShortArray(samples(seconds))

    /** Uniform noise of at most ±[peak]: e.g. the 9 s, peak-2 capture of turn fa21e6a0. */
    fun noise(seconds: Double, peak: Int, seed: Int = 1): ShortArray {
        val random = Random(seed)
        return ShortArray(samples(seconds)) { random.nextInt(-peak, peak + 1).toShort() }
    }

    /** Speech-like audio: syllables of varying pitch/level at about [rms], 100 ms dips between words. */
    fun speech(seconds: Double, rms: Int, seed: Int = 2): ShortArray {
        val random = Random(seed)
        val out = ShortArray(samples(seconds))
        var i = 0
        while (i < out.size) {
            val syllable = RATE * (150 + random.nextInt(250)) / 1000
            val f0 = 110.0 + random.nextDouble() * 200.0
            val level = rms * (0.8 + random.nextDouble() * 0.5) * 1.41
            for (n in 0 until syllable) {
                if (i >= out.size) break
                val envelope = sin(PI * n / syllable)
                out[i++] = (level * envelope * sin(2 * PI * f0 * n / RATE)).toInt().coerceIn(-32768, 32767).toShort()
            }
            i += RATE / 10
        }
        return out
    }

    /** [a] with [b] mixed in (sample-wise sum, clipped). */
    fun mix(a: ShortArray, b: ShortArray): ShortArray =
        ShortArray(maxOf(a.size, b.size)) { ((a.getOrElse(it) { 0 }.toInt() + b.getOrElse(it) { 0 }.toInt()).coerceIn(-32768, 32767)).toShort() }

    fun concat(vararg parts: ShortArray): ShortArray = parts.reduce { acc, p -> acc + p }

    /** A short but real utterance with room-quiet margins: the default "voice request" in tests. */
    fun speechWav(): ByteArray = wav(concat(noise(0.4, 40), speech(1.6, 2_500), noise(0.4, 40)))
}
