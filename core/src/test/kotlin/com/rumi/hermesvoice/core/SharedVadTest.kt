package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.audio.AudioInputGate
import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import com.rumi.hermesvoice.core.audio.EndpointDecision
import com.rumi.hermesvoice.core.audio.EnergyVad
import com.rumi.hermesvoice.core.audio.PcmCaptureLoop
import com.rumi.hermesvoice.core.audio.SilenceEndpoint
import com.rumi.hermesvoice.core.audio.VadClass
import com.rumi.hermesvoice.core.audio.VadProfile
import com.rumi.hermesvoice.core.settings.VadSilence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared energy VAD, as the hands-free endpoint (both devices) and the recording eligibility
 * check (push-to-talk and hands-free, both devices) run it: timing in audio time, independent of
 * chunk size and sample rate; background tracking; hysteresis; impulse rejection; and agreement
 * between the two uses on the same audio.
 */
class SharedVadTest {
    private data class Run(val decision: EndpointDecision, val elapsedMs: Long, val lastVoicedEndMs: Long, val speech: Boolean, val consumed: ByteArray)

    /** Feeds [pcm] in [chunkMs] chunks until the endpoint decides; what a recorder would have captured is [Run.consumed]. */
    private fun run(endpoint: SilenceEndpoint, rate: Int, pcm: ShortArray, chunkMs: Int = 100): Run {
        val bytes = Signals.bytes(pcm)
        val chunk = rate * chunkMs / 1000 * 2
        var consumed = 0
        for (c in Signals.chunks(bytes, chunk)) {
            consumed += c.size
            val d = endpoint.accept(c)
            if (d != EndpointDecision.CONTINUE) return Run(d, endpoint.elapsedMs, endpoint.lastVoicedEndMs, endpoint.speechDetected, bytes.copyOf(consumed))
        }
        return Run(EndpointDecision.CONTINUE, endpoint.elapsedMs, endpoint.lastVoicedEndMs, endpoint.speechDetected, bytes)
    }

    private fun gate(pcm: ShortArray, rate: Int = 16_000) = AudioInputGate.assess(PcmCaptureLoop.wav(Signals.bytes(pcm), rate)!!, "audio/wav")

    private val baselines = listOf(20.0, 150.0, 600.0, 2_000.0)

    @Test
    fun `every trailing-silence choice ends the request exactly that long after the last voiced frame`() {
        for (seconds in VadSilence.choices) {
            val s = Signals(16_000, seed = 3)
            val pcm = s.over(s.concat(s.silence(400), s.speech(3_000, 2_000.0), s.silence(VadSilence.millis(seconds) + 3_000)), 40.0)
            val r = run(SilenceEndpoint.forSilenceSeconds(seconds), 16_000, pcm)
            assertEquals("$seconds s", EndpointDecision.END_OF_SPEECH, r.decision)
            assertTrue(r.speech)
            assertEquals("$seconds s", VadSilence.millis(seconds), r.elapsedMs - r.lastVoicedEndMs)
        }
    }

    @Test
    fun `timing depends on audio time only, not on the read size or the sample rate`() {
        val results = HashSet<Pair<Long, Long>>()
        for (rate in listOf(8_000, 16_000, 32_000, 48_000)) {
            for (chunkMs in listOf(20, 50, 100, 160)) {
                val s = Signals(rate, seed = 5)
                val pcm = s.over(s.concat(s.silence(400), s.speech(4_000, 2_500.0), s.silence(4_000)), 50.0)
                val r = run(SilenceEndpoint(sampleRate = rate, silenceMs = 1_500), rate, pcm, chunkMs)
                assertEquals("$rate Hz / $chunkMs ms", EndpointDecision.END_OF_SPEECH, r.decision)
                assertEquals("$rate Hz / $chunkMs ms", 1_500L, r.elapsedMs - r.lastVoicedEndMs)
                // What a recorder captured: at most one read beyond the decision.
                val capturedMs = r.consumed.size / 2 * 1000L / rate
                assertTrue("$rate Hz / $chunkMs ms captured $capturedMs ms", capturedMs - r.elapsedMs in 0 until chunkMs)
                results += r.elapsedMs to r.lastVoicedEndMs
            }
        }
        assertEquals("same decision frame at every rate and chunk size: $results", 1, results.size)
    }

    @Test
    fun `no speech ever is NO_SPEECH at the timeout, and the gate refuses the same audio`() {
        val s = Signals(16_000, seed = 7)
        val inputs = mapOf(
            "all-zero" to s.silence(10_000),
            "peak-2 glitch" to s.noisePeak(10_000, 2),
        ) + baselines.associate { "noise rms $it" to s.noise(10_000, it) }
        for ((name, pcm) in inputs) {
            val r = run(SilenceEndpoint(), 16_000, pcm)
            assertEquals(name, EndpointDecision.NO_SPEECH, r.decision)
            assertEquals("$name: 8 s after the 400 ms calibration", 8_400L, r.elapsedMs)
            assertFalse(name, r.speech)
            assertTrue(name, gate(pcm) in setOf(AudioInputVerdict.SILENT, AudioInputVerdict.NO_SPEECH_ENERGY))
        }
        assertEquals(AudioInputVerdict.SILENT, gate(s.silence(3_000)))
        assertEquals(AudioInputVerdict.SILENT, gate(s.noisePeak(9_200, 2)))
    }

    @Test
    fun `clicks and knocks up to 100 ms never qualify, at any frame alignment`() {
        for (offsetMs in 0L until 20L step 3) {
            val s = Signals(16_000, seed = 11)
            val parts = mutableListOf(s.silence(400 + offsetMs))
            repeat(12) {
                parts += s.noise(if (it % 2 == 0) 100 else 10, 8_000.0)
                parts += s.silence(600)
            }
            val pcm = s.over(s.concat(*parts.toTypedArray(), s.silence(2_000)), 40.0)
            assertEquals("offset $offsetMs", EndpointDecision.NO_SPEECH, run(SilenceEndpoint(), 16_000, pcm).decision)
            assertEquals("offset $offsetMs", AudioInputVerdict.NO_SPEECH_ENERGY, gate(pcm))
        }
    }

    @Test
    fun `speech over every background level is found and ends after the setting`() {
        for (baseline in baselines) {
            val s = Signals(16_000, seed = 13)
            val speechRms = maxOf(baseline * 6, 800.0)
            val pcm = s.over(s.concat(s.silence(400), s.speech(5_000, speechRms), s.silence(5_000)), baseline)
            val r = run(SilenceEndpoint(silenceMs = 2_000), 16_000, pcm)
            assertEquals("baseline $baseline", EndpointDecision.END_OF_SPEECH, r.decision)
            assertEquals("baseline $baseline", 2_000L, r.elapsedMs - r.lastVoicedEndMs)
            assertEquals("baseline $baseline", AudioInputVerdict.USABLE, gate(pcm))
        }
    }

    @Test
    fun `a 2_5x or 2_9x background rise after speech cannot hold the request open`() {
        for (seconds in listOf(0.5, 2.0, 10.0)) {
            for (ratio in listOf(2.5, 2.9)) {
                val s = Signals(16_000, seed = 17)
                val base = 100.0
                // Under 2.5 s: short dips only, no phrase pause (see the next test for pauses).
                val speech = s.speech(2_000, 2_000.0)
                val pcm = s.concat(s.over(s.concat(s.silence(400), speech), base), s.noise(40_000, base * ratio))
                val speechEnd = 400 + s.ms(speech)
                val r = run(SilenceEndpoint.forSilenceSeconds(seconds), 16_000, pcm)
                assertEquals("$ratio x / $seconds s", EndpointDecision.END_OF_SPEECH, r.decision)
                val tail = r.elapsedMs - speechEnd
                val silenceMs = VadSilence.millis(seconds)
                // Documented bound: the minimum window (1 s) plus floor adaptation, then at most 2 x the setting.
                assertTrue("$ratio x / $seconds s ended ${tail} ms after speech", tail in silenceMs..(2 * silenceMs + 2_000))
            }
        }
    }

    @Test
    fun `the trailing-silence setting is exactly how long a pause may be`() {
        val s = Signals(16_000, seed = 43)
        val first = s.speech(1_500, 2_000.0)
        val pause = s.over(s.concat(s.silence(400), first, s.silence(700), s.speech(1_500, 2_000.0), s.silence(3_000)), 40.0)
        val resumeMs = 400 + s.ms(first) + 700
        val short = run(SilenceEndpoint.forSilenceSeconds(0.5), 16_000, pause)
        assertEquals(EndpointDecision.END_OF_SPEECH, short.decision)
        assertTrue("0.5 s ends in the 700 ms pause (${short.elapsedMs} ms)", short.elapsedMs <= resumeMs)
        val default = run(SilenceEndpoint.forSilenceSeconds(2.0), 16_000, pause)
        assertTrue("2 s waits through it", default.lastVoicedEndMs > resumeMs + 1_000)
    }

    @Test
    fun `speech never raises the background floor, 60 seconds of it included`() {
        val s = Signals(16_000, seed = 19)
        val endpoint = SilenceEndpoint()
        val pcm = s.over(s.concat(s.silence(400), s.speech(60_000, 1_500.0)), 100.0)
        run(endpoint, 16_000, s.concat(pcm.copyOf(s.samples(400))))
        val calibrated = endpoint.floor
        val r = run(endpoint, 16_000, pcm.copyOfRange(s.samples(400), pcm.size))
        assertEquals(EndpointDecision.CONTINUE, r.decision)
        assertTrue("floor ${endpoint.floor} vs calibrated $calibrated", endpoint.floor in calibrated * 0.8..calibrated * 1.1)
    }

    @Test
    fun `continuous modulated speech of 30, 60 and 120 seconds is never cut and ends once`() {
        for (seconds in listOf(30L, 60L, 120L)) {
            val s = Signals(16_000, seed = seconds.toInt())
            val speech = s.speech(seconds * 1_000, 1_800.0)
            val pcm = s.over(s.concat(s.silence(400), speech, s.silence(4_000)), 60.0)
            val endpoint = SilenceEndpoint()
            val r = run(endpoint, 16_000, pcm)
            assertEquals("$seconds s", EndpointDecision.END_OF_SPEECH, r.decision)
            assertTrue("$seconds s ended at ${r.elapsedMs} ms, inside the speech", r.lastVoicedEndMs >= 400 + s.ms(speech) - 40)
            assertEquals(EndpointDecision.CONTINUE, endpoint.accept(Signals.bytes(s.silence(1_000))))
        }
    }

    @Test
    fun `short commands and soft speech qualify, the minimum utterance is short`() {
        assertTrue("minimum voiced time ${VadProfile.MIN_SPEECH_MS} ms", VadProfile.MIN_SPEECH_MS <= 150)
        val s = Signals(16_000, seed = 23)
        val word = s.over(s.concat(s.silence(400), s.syllable(220, 1_500.0), s.silence(3_000)), 40.0)
        val r = run(SilenceEndpoint(), 16_000, word)
        assertEquals("one short word", EndpointDecision.END_OF_SPEECH, r.decision)
        assertEquals(AudioInputVerdict.USABLE, gate(word))
        // Soft hands-free speech: nuclei 3.5 x the background.
        val soft = s.over(s.concat(s.silence(400), s.speech(4_000, 3.5 * 150), s.silence(4_000)), 150.0)
        assertEquals("soft speech", EndpointDecision.END_OF_SPEECH, run(SilenceEndpoint(), 16_000, soft).decision)
        // Soft push-to-talk speech in a noisy room: nuclei 2.4 x the background.
        assertEquals(AudioInputVerdict.USABLE, gate(s.over(s.speech(3_000, 2.4 * 500), 500.0)))
        assertEquals(AudioInputVerdict.NO_SPEECH_ENERGY, gate(s.noise(3_000, 500.0)))
    }

    @Test
    fun `whenever the endpoint ends on speech, the gate accepts what was captured`() {
        var checked = 0
        for (seed in 1..12) {
            for (baseline in baselines) {
                val s = Signals(16_000, seed = seed)
                val level = maxOf(baseline * (3.2 + seed * 0.3), 400.0)
                val pcm = s.over(s.concat(s.silence(400), s.speech(1_000L + seed * 300, level), s.silence(3_000)), baseline)
                val r = run(SilenceEndpoint(), 16_000, pcm)
                if (r.decision != EndpointDecision.END_OF_SPEECH) continue
                checked++
                val captured = AudioInputGate.assess(PcmCaptureLoop.wav(r.consumed)!!, "audio/wav")
                assertEquals("seed $seed baseline $baseline", AudioInputVerdict.USABLE, captured)
            }
        }
        assertTrue("checked $checked", checked >= 40)
    }

    @Test
    fun `hysteresis keeps a fading syllable voiced for at most the hangover`() {
        val vad = EnergyVad(VadProfile.HANDS_FREE, 100.0)
        assertEquals(VadClass.VOICED, vad.observe(400.0, 20))
        val tail = (1..20).map { vad.observe(250.0, 20) }
        assertEquals("200 ms of hangover, then a little-above-background frame", List(10) { VadClass.VOICED } + List(10) { VadClass.GRAY }, tail)
        assertEquals(VadClass.QUIET, EnergyVad(VadProfile.HANDS_FREE, 100.0).observe(150.0, 20))
        assertEquals("never voiced without an onset first", VadClass.GRAY, EnergyVad(VadProfile.HANDS_FREE, 100.0).observe(250.0, 20))
    }
}
