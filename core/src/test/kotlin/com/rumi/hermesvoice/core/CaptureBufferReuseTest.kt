package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.audio.CaptureEnd
import com.rumi.hermesvoice.core.audio.PcmCaptureLoop
import com.rumi.hermesvoice.core.audio.PcmSource
import com.rumi.hermesvoice.core.audio.SilenceEndpoint
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The capture loop reuses one read buffer (no per-chunk copy): the recorded PCM and its statistics must be exactly what the microphone delivered. */
class CaptureBufferReuseTest {
    private class Events : PcmCaptureLoop.Listener {
        var end: CaptureEnd? = null
        override fun onLive() {}
        override fun onCalibrated() {}
        override fun onEnd(reason: CaptureEnd) { end = reason }
    }

    /** Delivers [bytes] in reads of the given sizes (even: 16-bit samples), then empty reads. */
    private fun source(bytes: ByteArray, sizes: List<Int>): PcmSource {
        var offset = 0
        var index = 0
        return PcmSource { buffer ->
            if (offset >= bytes.size) return@PcmSource 0
            val n = minOf(sizes[index++ % sizes.size], buffer.size, bytes.size - offset)
            System.arraycopy(bytes, offset, buffer, 0, n)
            offset += n
            // Poison the rest of the buffer: stale bytes past the read length must never be recorded or measured.
            java.util.Arrays.fill(buffer, n, buffer.size, 0x7f)
            n
        }
    }

    private fun pcm(): ByteArray {
        val out = ByteArray(2 * 4_000)
        for (i in 0 until 4_000) {
            val sample = (((i * 37) % 2_001) - 1_000).toShort().toInt()
            out[2 * i] = (sample and 0xff).toByte()
            out[2 * i + 1] = (sample shr 8).toByte()
        }
        return out
    }

    @Test
    fun `the recorded pcm and its stats are exactly what a reused buffer was filled with`() {
        val bytes = pcm()
        val events = Events()
        val loop = PcmCaptureLoop(source(bytes, listOf(3_200, 1_000, 1_002, 640)), limitBytes = 1_000_000, endpoint = null,
            listener = events, retryDelay = {})
        loop.run()
        assertEquals(CaptureEnd.MIC_ERROR, events.end)
        val recorded = loop.pcm()
        assertArrayEquals(bytes, recorded)
        val samples = bytes.size / 2
        var peak = 0
        var squares = 0.0
        for (i in 0 until samples) {
            val s = ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xff)).toShort().toInt()
            peak = maxOf(peak, abs(s))
            squares += s.toDouble() * s
        }
        val stats = loop.stats()
        assertEquals(bytes.size, stats.pcmBytes)
        assertEquals(peak, stats.peak)
        assertEquals(sqrt(squares / samples).toInt(), stats.rms)
    }

    @Test
    fun `an endpoint fed from the reused buffer reaches the same decision as one fed copies`() {
        val bytes = pcm()
        fun run(copies: Boolean): List<Any> {
            val endpoint = SilenceEndpoint()
            val decisions = mutableListOf<Any>()
            var offset = 0
            val buffer = ByteArray(3_200)
            while (offset < bytes.size) {
                val n = minOf(3_200, bytes.size - offset)
                System.arraycopy(bytes, offset, buffer, 0, n)
                offset += n
                decisions += if (copies) endpoint.accept(buffer.copyOf(n)) else endpoint.accept(buffer, n)
            }
            decisions += endpoint.elapsedMs
            return decisions
        }
        assertEquals(run(copies = true), run(copies = false))
    }
}
