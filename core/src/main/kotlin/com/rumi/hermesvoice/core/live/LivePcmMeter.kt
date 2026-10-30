package com.rumi.hermesvoice.core.live

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Counts the PCM16 (little-endian) chunks one direction of a Live call handed on: the chunks holding any nonzero sample, and their
 * time at speech energy (a mean absolute sample of at least [SPEECH], the app's own activity threshold). Fixed numbers only: no
 * sample is kept. A silenced or never-filled source hands on exact zeros, so it counts no chunk at all.
 */
class LivePcmMeter {
    private val nonzero = AtomicLong()
    private val speechUs = AtomicLong()
    val chunks: Long get() = nonzero.get()
    val speechMs: Long get() = speechUs.get() / 1000

    /** [bytes] bytes of [data] from its absolute index 0; the buffer's position and limit are not moved. */
    fun count(data: ByteBuffer, bytes: Int, channels: Int, rate: Int) {
        meter(minOf(bytes, data.capacity()) / 2, channels, rate) { i -> ((data.get(2 * i).toInt() and 255) or (data.get(2 * i + 1).toInt() shl 8)).toShort().toInt() }
    }

    fun count(data: ByteArray, channels: Int, rate: Int) {
        meter(data.size / 2, channels, rate) { i -> ((data[2 * i].toInt() and 255) or (data[2 * i + 1].toInt() shl 8)).toShort().toInt() }
    }

    private inline fun meter(samples: Int, channels: Int, rate: Int, sample: (Int) -> Int) {
        if (samples <= 0 || channels <= 0 || rate <= 0) return
        var energy = 0L; var any = false
        for (i in 0 until samples) { val s = sample(i); if (s != 0) any = true; energy += kotlin.math.abs(s) }
        if (!any) return
        nonzero.incrementAndGet()
        if (energy / samples >= SPEECH) speechUs.addAndGet(samples.toLong() / channels * 1_000_000L / rate)
    }

    companion object { const val SPEECH = 120 }
}
