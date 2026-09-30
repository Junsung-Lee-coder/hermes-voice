package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.audio.EndpointDecision
import com.rumi.hermesvoice.core.audio.MicReadGuard
import com.rumi.hermesvoice.core.audio.SilenceEndpoint
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun feed(endpoint: SilenceEndpoint, frames: Sequence<ByteArray>): Pair<EndpointDecision, Int> {
        frames.forEachIndexed { index, f ->
            val decision = endpoint.accept(f)
            if (decision != EndpointDecision.CONTINUE) return decision to index
        }
        return EndpointDecision.CONTINUE to -1
    }

    private fun quiet(n: Int) = generateSequence { frame(100) }.take(n)
    private fun speech(n: Int, amplitude: Int = 4_000) = generateSequence { frame(amplitude) }.take(n)

    @Test
    fun `speech followed by silence ends the turn after the silence window`() {
        val endpoint = SilenceEndpoint(silenceMs = 1_200)
        val (decision, index) = feed(endpoint, quiet(4) + speech(10) + quiet(20))
        assertEquals(EndpointDecision.END_OF_SPEECH, decision)
        assertTrue(endpoint.speechDetected)
        // 4 calibration frames + 10 speech frames, then the 12th quiet 100 ms frame completes 1.2 s of silence.
        assertEquals(25, index)
    }

    @Test
    fun `calibration completes before the ready cue and is reported once`() {
        val endpoint = SilenceEndpoint()
        assertFalse(endpoint.calibrated)
        quiet(3).forEach { endpoint.accept(it) }
        assertFalse(endpoint.calibrated)
        endpoint.accept(frame(100))
        assertTrue(endpoint.calibrated)
    }

    @Test
    fun `no speech times out separately and a short click does not count as speech`() {
        val endpoint = SilenceEndpoint(noSpeechTimeoutMs = 8_000)
        val (decision, index) = feed(endpoint, quiet(4) + speech(1, 6_000) + quiet(200))
        assertEquals(EndpointDecision.NO_SPEECH, decision)
        // The no-speech clock starts after calibration (the ready cue): the 80th armed frame (8 s) ends it.
        assertEquals(4 + 79, index)
    }

    @Test
    fun `continuous speech of 30, 60 and 120 seconds is never cut, then trailing silence ends it once`() {
        for (seconds in listOf(30, 60, 120)) {
            val endpoint = SilenceEndpoint()
            val frames = quiet(4) + speech(seconds * 10) + quiet(40)
            val (decision, index) = feed(endpoint, frames)
            assertEquals("$seconds s", EndpointDecision.END_OF_SPEECH, decision)
            // Ends only after the default 2 s of trailing silence, never inside the speech.
            assertEquals("$seconds s", 4 + seconds * 10 + 19, index)
            assertEquals(EndpointDecision.CONTINUE, endpoint.accept(frame(100)))
        }
    }

    @Test
    fun `short impulses in the pause do not reset silence, sustained renewed speech does`() {
        val clicks = quiet(4) + speech(20) + quiet(8) + speech(1) + quiet(8) + speech(1) + quiet(10)
        val (clickDecision, clickIndex) = feed(SilenceEndpoint(), clicks)
        assertEquals(EndpointDecision.END_OF_SPEECH, clickDecision)
        assertEquals(4 + 20 + 19, clickIndex)

        val resumed = quiet(4) + speech(20) + quiet(15) + speech(5) + quiet(30)
        val (decision, index) = feed(SilenceEndpoint(), resumed)
        assertEquals(EndpointDecision.END_OF_SPEECH, decision)
        assertEquals(4 + 20 + 15 + 5 + 19, index)
    }

    @Test
    fun `stationary background noise above an absolute threshold still endpoints`() {
        fun noisy(n: Int) = generateSequence { frame(1_500) }.take(n)
        val frames = noisy(4) + speech(20, 9_000) + noisy(40)
        val (decision, _) = feed(SilenceEndpoint(), frames)
        assertEquals(EndpointDecision.END_OF_SPEECH, decision)
    }

    @Test
    fun `repeated empty microphone reads are bounded`() {
        val guard = MicReadGuard(maxConsecutiveFailures = 3)
        assertTrue(guard.onRead(0))
        assertTrue(guard.onRead(-3))
        assertTrue(guard.onRead(320))
        assertTrue(guard.positiveReadSeen)
        assertTrue(guard.onRead(0))
        assertTrue(guard.onRead(0))
        assertFalse(guard.onRead(0))
    }
}
