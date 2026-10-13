package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.headset.RecordingCueTone
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Amendment K: the locally generated ding - one for an accepted start, two for an accepted stop. No network, no TTS, no system sound. */
class RecordingCueToneTest {
    private fun samples(pcm: ByteArray): ShortArray {
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(pcm.size / 2) { buffer.short }
    }

    /** Bursts of sound separated by silence, found on 5 ms windows. */
    private fun bursts(pcm: ByteArray): Int {
        val s = samples(pcm)
        val window = RecordingCueTone.SAMPLE_RATE / 200
        var count = 0
        var inBurst = false
        var i = 0
        while (i + window <= s.size) {
            var sum = 0.0
            for (k in i until i + window) sum += (s[k] / 32768.0) * (s[k] / 32768.0)
            val loud = sqrt(sum / window) > 0.02
            if (loud && !inBurst) count++
            inBurst = loud
            i += window
        }
        return count
    }

    @Test
    fun `one ding is one burst and two dings are two bursts`() {
        assertEquals(1, bursts(RecordingCueTone.pcm(1)))
        assertEquals(2, bursts(RecordingCueTone.pcm(2)))
    }

    @Test
    fun `it is short  quiet and click-free  and the same every time`() {
        for (dings in 1..2) {
            val pcm = RecordingCueTone.pcm(dings)
            val s = samples(pcm)
            val ms = s.size * 1000 / RecordingCueTone.SAMPLE_RATE
            assertTrue("a short cue, got $ms ms", ms in 80..450)
            assertTrue("no amplification: peak ${s.maxOf { abs(it.toInt()) }}", s.maxOf { abs(it.toInt()) } <= (0.35 * 32767).toInt())
            assertTrue("a real tone", s.maxOf { abs(it.toInt()) } > 2_000)
            assertTrue("starts without a click", abs(s.first().toInt()) < 300)
            assertTrue("ends without a click", abs(s.last().toInt()) < 300)
            assertTrue(pcm.contentEquals(RecordingCueTone.pcm(dings)))
        }
        assertTrue("two dings are longer than one", RecordingCueTone.pcm(2).size > RecordingCueTone.pcm(1).size)
    }

    @Test
    fun `it is 16-bit mono PCM with an even byte count and no other count of dings`() {
        assertEquals(0, RecordingCueTone.pcm(1).size % 2)
        assertEquals(0, RecordingCueTone.pcm(2).size % 2)
        assertTrue(RecordingCueTone.pcm(0).isEmpty())
    }
}
