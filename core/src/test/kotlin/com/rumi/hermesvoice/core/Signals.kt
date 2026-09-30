package com.rumi.hermesvoice.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Deterministic PCM16 mono signals at any sample rate, timed in ms, for the shared VAD tests.
 * Levels are frame RMS in PCM16 units. Speech is modelled as syllables (voiced nucleus with 25 ms
 * attack/decay, varying pitch, a harmonic, ±30 % level) separated by short dips, with longer phrase
 * pauses: energy-modulated like real speech, which a sustained tone is not.
 */
class Signals(val rate: Int, seed: Int = 1) {
    private val random = Random(seed)

    fun samples(ms: Long): Int = (rate * ms / 1000).toInt()

    fun silence(ms: Long) = ShortArray(samples(ms))

    /** Uniform noise with RMS [rms] (peak = rms × √3). */
    fun noise(ms: Long, rms: Double): ShortArray {
        val peak = rms * sqrt(3.0)
        return ShortArray(samples(ms)) { ((random.nextDouble() * 2 - 1) * peak).toInt().toShort() }
    }

    /** Uniform integer noise of at most ±[peak] (e.g. the peak-2 emulator glitch). */
    fun noisePeak(ms: Long, peak: Int) = ShortArray(samples(ms)) { random.nextInt(-peak, peak + 1).toShort() }

    /** A steady tone whose RMS is [rms]. */
    fun tone(ms: Long, rms: Double, hz: Double = 300.0) =
        ShortArray(samples(ms)) { (rms * sqrt(2.0) * sin(2 * PI * hz * it / rate)).toInt().toShort() }

    /** One syllable of [ms] whose nucleus frames have RMS about [rms]. */
    fun syllable(ms: Long, rms: Double): ShortArray {
        val n = samples(ms)
        val ramp = samples(25).coerceAtMost(n / 2).coerceAtLeast(1)
        val f0 = 110.0 + random.nextDouble() * 200.0
        val amplitude = rms * sqrt(2.0) / sqrt(0.7 * 0.7 + 0.3 * 0.3)
        return ShortArray(n) { i ->
            val edge = minOf(i, n - 1 - i)
            val envelope = if (edge >= ramp) 1.0 else 0.5 - 0.5 * cos(PI * edge / ramp)
            val t = i.toDouble() / rate
            (amplitude * envelope * (0.7 * sin(2 * PI * f0 * t) + 0.3 * sin(4 * PI * f0 * t))).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /**
     * Continuous speech for [ms]: syllables of 120–320 ms at [rms] ±30 %, dips of 40–160 ms, and a
     * 400–700 ms phrase pause about every 2.5 s; it always ends with a sustained 300 ms vowel.
     */
    fun speech(ms: Long, rms: Double): ShortArray {
        val parts = ArrayList<ShortArray>()
        var t = 0L
        var sincePause = 0L
        while (t < ms - 300) {
            val syllable = 120L + random.nextInt(201)
            parts += syllable(syllable, rms * (0.7 + random.nextDouble() * 0.6))
            val gap = if (sincePause > 2_500) 400L + random.nextInt(301) else 40L + random.nextInt(121)
            sincePause = if (gap >= 400) 0 else sincePause + syllable + gap
            parts += silence(gap)
            t += syllable + gap
        }
        parts += syllable(300, rms)
        return concat(*parts.toTypedArray())
    }

    /** [a] with [b] added sample-wise (clipped); the result is as long as the longer one. */
    fun mix(a: ShortArray, b: ShortArray): ShortArray =
        ShortArray(maxOf(a.size, b.size)) { ((a.getOrElse(it) { 0 }.toInt() + b.getOrElse(it) { 0 }.toInt()).coerceIn(-32768, 32767)).toShort() }

    /** [signal] over background noise of [rms] for its whole length. */
    fun over(signal: ShortArray, rms: Double) = mix(signal, noise(signal.size * 1000L / rate, rms))

    fun concat(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var offset = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, offset, p.size)
            offset += p.size
        }
        return out
    }

    fun ms(samples: ShortArray): Long = samples.size * 1000L / rate

    companion object {
        fun bytes(samples: ShortArray): ByteArray =
            ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it) } }.array()

        fun chunks(bytes: ByteArray, size: Int): Sequence<ByteArray> =
            (bytes.indices step size).asSequence().map { bytes.copyOfRange(it, minOf(it + size, bytes.size)) }
    }
}

/**
 * Backgrounds whose level is not steady, with nobody speaking. Levels are given as the RMS around which the background moves and the standard deviation
 * of its frame level in dB.
 */
object UnsteadyNoise {
    /** Gaussian noise whose level drifts smoothly: per 20 ms frame an AR(1) process in dB with correlation time [corrMs]. */
    fun drift(rate: Int, seed: Int, ms: Long, rms: Double, sigmaDb: Double, corrMs: Double = 300.0): ShortArray {
        val r = java.util.Random(seed.toLong())
        val n = (rate * ms / 1000).toInt()
        val frame = rate / 50
        val a = exp(-20.0 / corrMs)
        val innovation = sigmaDb * sqrt(1 - a * a)
        var x = r.nextGaussian() * sigmaDb
        var next = a * x + innovation * r.nextGaussian()
        return ShortArray(n) { i ->
            val k = i % frame
            if (k == 0 && i > 0) {
                x = next
                next = a * x + innovation * r.nextGaussian()
            }
            val db = x + (next - x) * k / frame
            (r.nextGaussian() * rms * 10.0.pow(db / 20)).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** Uniform noise whose level jumps every [holdMs] to a new value drawn log-normally around [rms]. */
    fun blocks(rate: Int, seed: Int, ms: Long, rms: Double, sigmaDb: Double, holdMs: Long = 100): ShortArray {
        val levels = java.util.Random(seed.toLong())
        val noise = Random(seed)
        val n = (rate * ms / 1000).toInt()
        val hold = (rate * holdMs / 1000).toInt()
        var level = rms
        return ShortArray(n) { i ->
            if (i % hold == 0) level = rms * 10.0.pow(levels.nextGaussian() * sigmaDb / 20)
            ((noise.nextDouble() * 2 - 1) * level * sqrt(3.0)).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
