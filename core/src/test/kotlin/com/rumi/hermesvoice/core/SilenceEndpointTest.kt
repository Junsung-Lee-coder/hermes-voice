package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.audio.EndpointDecision
import com.rumi.hermesvoice.core.audio.MicReadGuard
import com.rumi.hermesvoice.core.audio.SilenceEndpoint
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
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

    /** 100 ms of a voiced-like frame: varying pitch (110-320 Hz), a harmonic and a syllable envelope. */
    private fun voiced(random: Random): ByteArray {
        val out = ByteArray(3_200)
        val f0 = 110.0 + random.nextDouble() * 210.0
        val peak = 1_800 + random.nextInt(4_500)
        for (n in 0 until 1_600) {
            val envelope = 0.55 + 0.45 * sin(PI * n / 1_600.0)
            val sample = (peak * envelope * (0.7 * sin(2 * PI * f0 * n / 16_000.0) + 0.3 * sin(4 * PI * f0 * n / 16_000.0))).toInt() +
                random.nextInt(61) - 30
            out[2 * n] = (sample and 0xff).toByte()
            out[2 * n + 1] = ((sample shr 8) and 0xff).toByte()
        }
        return out
    }

    /**
     * Speech-like cadence for [seconds]: voiced runs separated by short dips and phrase pauses
     * (e.g. 300/100, 200/100 ms, and 400-800 ms gaps), always ending on a full voiced run.
     */
    private fun cadence(seconds: Int, pattern: List<Pair<Int, Int>>, seed: Int): List<ByteArray> {
        val random = Random(seed)
        val frames = ArrayList<ByteArray>()
        var i = 0
        while (frames.size < seconds * 10) {
            val (on, off) = pattern[i++ % pattern.size]
            repeat(on) { frames += voiced(random) }
            if (frames.size < seconds * 10) repeat(off) { frames += frame(100) }
        }
        while (frames.isNotEmpty() && SilenceEndpoint.rms(frames.last()) < 1_000) frames.removeAt(frames.size - 1)
        repeat(3) { frames += voiced(random) }
        return frames
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
    fun `a steady tone held for seconds is not speech - it becomes background and ends the request`() {
        // Documented limit of an energy VAD with a stationary-noise floor: a sustained, unmodulated
        // sound (a hum, a held note) is absorbed into the background after about a second, so it
        // cannot hold a request open; real speech is modulated (see SharedVadTest).
        val endpoint = SilenceEndpoint()
        val (decision, index) = feed(endpoint, quiet(4) + speech(300) + quiet(40))
        assertEquals(EndpointDecision.END_OF_SPEECH, decision)
        assertTrue("ended ${index - 4} frames into the tone", index - 4 in 20..80)
    }

    @Test
    fun `modulated speech of 30, 60 and 120 seconds with short pauses is never cut and ends once`() {
        val cadences = listOf(
            listOf(3 to 1),
            listOf(2 to 1),
            listOf(15 to 4, 9 to 1, 20 to 8, 6 to 2, 12 to 6),
        )
        var seed = 1
        for (seconds in listOf(30, 60, 120)) {
            for (pattern in cadences) {
                val speech = cadence(seconds, pattern, seed++)
                val endpoint = SilenceEndpoint()
                val (decision, index) = feed(endpoint, quiet(4) + speech.asSequence() + quiet(40))
                assertEquals("$seconds s $pattern", EndpointDecision.END_OF_SPEECH, decision)
                assertEquals("$seconds s $pattern ends 2 s after the last speech", 4 + speech.size + 19, index)
                assertTrue(endpoint.speechDetected)
            }
        }
    }

    @Test
    fun `speech with short dips from the first syllable is detected`() {
        val random = Random(7)
        val frames = quiet(4) + generateSequence { listOf(voiced(random), voiced(random), frame(100)) }.take(20).flatten() + quiet(30)
        val endpoint = SilenceEndpoint()
        val (decision, _) = feed(endpoint, frames)
        assertEquals(EndpointDecision.END_OF_SPEECH, decision)
        assertTrue(endpoint.speechDetected)
    }

    @Test
    fun `isolated clicks never qualify as speech`() {
        val sparse = quiet(4) + generateSequence { sequenceOf(frame(6_000)) + quiet(4) }.take(30).flatten()
        assertEquals(EndpointDecision.NO_SPEECH, feed(SilenceEndpoint(), sparse).first)
        val pairs = quiet(4) + generateSequence { sequenceOf(frame(6_000)) + quiet(3) + sequenceOf(frame(6_000)) + quiet(6) }.take(12).flatten()
        assertEquals(EndpointDecision.NO_SPEECH, feed(SilenceEndpoint(), pairs).first)
    }

    @Test
    fun `short impulses in the pause do not reset silence, sustained renewed speech does`() {
        val clicks = quiet(4) + speech(20) + quiet(8) + speech(1) + quiet(8) + speech(1) + quiet(10)
        val (clickDecision, clickIndex) = feed(SilenceEndpoint(), clicks)
        assertEquals(EndpointDecision.END_OF_SPEECH, clickDecision)
        // Each click only pauses the silence count for its own 100 ms; it never prevents the endpoint.
        assertEquals(4 + 20 + 21, clickIndex)

        val resumed = quiet(4) + speech(20) + quiet(15) + speech(5) + quiet(30)
        val (decision, index) = feed(SilenceEndpoint(), resumed)
        assertEquals(EndpointDecision.END_OF_SPEECH, decision)
        assertEquals(4 + 20 + 15 + 5 + 19, index)
    }

    /** Stationary noise frames at [rms] (a 300 Hz tone scaled to that RMS). */
    private fun noise(n: Int, rms: Double) = generateSequence { frame((rms * 1.4142).toInt()) }.take(n)

    @Test
    fun `background noise that rises after speech still endpoints within 6 seconds`() {
        val floor = SilenceEndpoint.rms(frame(100))
        for (ratio in listOf(2.5, 2.9)) {
            val endpoint = SilenceEndpoint()
            val (decision, index) = feed(endpoint, quiet(4) + speech(30) + noise(6_000, floor * ratio))
            assertEquals("$ratio x floor", EndpointDecision.END_OF_SPEECH, decision)
            val tailFrames = index - (4 + 30) + 1
            assertTrue("$ratio x floor ended after ${tailFrames * 100} ms", tailFrames <= 60)
        }
    }

    @Test
    fun `soft speech just above the onset keeps the request open`() {
        val floor = SilenceEndpoint.rms(frame(100))
        val random = Random(11)
        // Soft syllables (200 ms at 3.6 x the background) with 100 ms dips, for 30 s.
        val soft = generateSequence { listOf(frame((floor * 3.6 * 1.4142).toInt() + random.nextInt(40)),
            frame((floor * 3.6 * 1.4142).toInt() + random.nextInt(40)), frame(100)) }.take(100).flatten().toList().dropLast(1)
        val (decision, index) = feed(SilenceEndpoint(), quiet(4) + speech(10) + soft.asSequence() + quiet(40))
        assertEquals(EndpointDecision.END_OF_SPEECH, decision)
        assertEquals("ends only after the soft speech", 4 + 10 + soft.size + 19, index)
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
