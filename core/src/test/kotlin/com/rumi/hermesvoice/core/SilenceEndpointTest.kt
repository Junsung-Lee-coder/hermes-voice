package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.audio.EndpointDecision
import com.rumi.hermesvoice.core.audio.SilenceEndpoint
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceEndpointTest {
    /** 100 ms of a 300 Hz tone at [amplitude] (0 = digital silence plus tiny noise). */
    private fun frame(amplitude: Int): ByteArray {
        val out = ByteArray(3_200)
        for (n in 0 until 1_600) {
            val sample = (amplitude * sin(2 * PI * 300 * n / 16_000.0)).toInt() + (n % 7) - 3
            out[2 * n] = (sample and 0xff).toByte()
            out[2 * n + 1] = ((sample shr 8) and 0xff).toByte()
        }
        return out
    }

    private fun feed(endpoint: SilenceEndpoint, frames: List<ByteArray>): Pair<EndpointDecision, Int> {
        frames.forEachIndexed { index, f ->
            val decision = endpoint.accept(f)
            if (decision != EndpointDecision.CONTINUE) return decision to index
        }
        return EndpointDecision.CONTINUE to frames.size
    }

    @Test
    fun `speech followed by silence ends the turn after the silence window`() {
        val quiet = List(4) { frame(100) }
        val speech = List(10) { frame(4_000) }
        val tail = List(20) { frame(100) }
        val endpoint = SilenceEndpoint()
        val (decision, index) = feed(endpoint, quiet + speech + tail)
        assertEquals(EndpointDecision.END_OF_SPEECH, decision)
        assertTrue(endpoint.speechDetected)
        // 3 calibration + 1 quiet + 10 speech frames, then the 12th quiet 100 ms frame completes 1.2 s of silence.
        assertEquals(25, index)
    }

    @Test
    fun `no speech times out and a short click does not count as speech`() {
        val frames = List(3) { frame(100) } + frame(6_000) + List(70) { frame(100) }
        val (decision, _) = feed(SilenceEndpoint(), frames)
        assertEquals(EndpointDecision.NO_SPEECH, decision)
    }

    @Test
    fun `continuous speech stops at the max duration`() {
        val (decision, _) = feed(SilenceEndpoint(maxDurationMs = 2_000), List(4) { frame(100) } + List(40) { frame(4_000) })
        assertEquals(EndpointDecision.MAX_DURATION, decision)
    }
}
