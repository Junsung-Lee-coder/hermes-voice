package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.audio.AudioInputGate
import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import com.rumi.hermesvoice.core.audio.CaptureEnd
import com.rumi.hermesvoice.core.audio.EndpointDecision
import com.rumi.hermesvoice.core.audio.PcmCaptureLoop
import com.rumi.hermesvoice.core.audio.PcmSource
import com.rumi.hermesvoice.core.audio.SilenceEndpoint
import com.rumi.hermesvoice.core.watchlink.CaptureCoordinator
import com.rumi.hermesvoice.core.watchlink.CapturePort
import com.rumi.hermesvoice.core.watchlink.CaptureStop
import com.rumi.hermesvoice.core.watchlink.HapticEvent
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressions from the independent review of the shared VAD (its reproducers J, N, D, K, E/F and
 * the push-to-talk SNR matrix, with the same signal definitions), run through the real
 * [SilenceEndpoint], the recorders' [PcmCaptureLoop] with [CaptureCoordinator], and
 * [AudioInputGate]. A request must never be ended, and so sent truncated, while clearly audible
 * speech continues.
 */
class VadReviewMatrixTest {
    private val rate = 16_000
    private val bg = 80.0

    private class Res(val decision: EndpointDecision, val endMs: Long, val lastVoicedMs: Long)

    private fun endpoint(pcm: ShortArray, silenceMs: Long = 2_000, chunk: Int = 3_200): Res {
        val e = SilenceEndpoint(sampleRate = rate, silenceMs = silenceMs)
        var d = EndpointDecision.CONTINUE
        for (c in Signals.chunks(Signals.bytes(pcm), chunk)) {
            d = e.accept(c)
            if (d != EndpointDecision.CONTINUE) break
        }
        return Res(d, e.elapsedMs, e.lastVoicedEndMs)
    }

    /** A held vowel ("uhh"): steady voiced sound with a slight natural wobble (±1 dB). */
    private fun held(s: Signals, ms: Long, rms: Double): ShortArray {
        val n = s.samples(ms)
        val ramp = s.samples(40)
        return ShortArray(n) { i ->
            val t = i.toDouble() / rate
            val env = 1.0 + 0.1 * sin(2 * PI * 5 * t)
            val edge = minOf(i, n - 1 - i)
            val fade = if (edge >= ramp) 1.0 else edge.toDouble() / ramp
            (rms * sqrt(2.0) * env * fade * (0.7 * sin(2 * PI * 130 * t) + 0.3 * sin(2 * PI * 260 * t)) / sqrt(0.58))
                .toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** Continuous voiced sound with no gaps whose level swings between [rms] and [rms] × [depth] at 4 Hz. */
    private fun shallow(s: Signals, ms: Long, rms: Double, depth: Double): ShortArray = ShortArray(s.samples(ms)) { i ->
        val t = i.toDouble() / rate
        val env = depth + (1 - depth) * (0.5 + 0.5 * sin(2 * PI * 4 * t))
        (rms * sqrt(2.0) * env * (0.7 * sin(2 * PI * 150 * t) + 0.3 * sin(2 * PI * 300 * t)) / sqrt(0.58)).toInt().coerceIn(-32768, 32767).toShort()
    }

    private fun clicks(s: Signals, ms: Long, everyMs: Long, clickMs: Long, rms: Double): ShortArray {
        val parts = ArrayList<ShortArray>()
        var t = 0L
        while (t < ms) {
            parts += s.tone(clickMs, rms, 1_200.0)
            parts += s.noise(everyMs - clickMs, bg)
            t += everyMs
        }
        return s.concat(*parts.toTypedArray())
    }

    private fun endsAfterSpeech(label: String, pcm: ShortArray, speechEndMs: Long, silenceMs: Long = 2_000) {
        val r = endpoint(pcm, silenceMs)
        assertEquals(label, EndpointDecision.END_OF_SPEECH, r.decision)
        assertTrue("$label: ended at ${r.endMs} ms, speech really ends at $speechEndMs ms (truncated request would be sent)", r.endMs >= speechEndMs)
        assertTrue("$label: ended ${r.endMs - speechEndMs} ms after the speech", r.endMs <= speechEndMs + silenceMs + 1_000)
    }

    // ── P1-1 ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `J - a held filler sound in the middle of a request never ends it`() {
        for (rel in listOf(0.5, 0.7, 1.0)) for (dur in listOf(1_200L, 1_500L, 2_000L, 2_500L, 3_000L)) {
            val s = Signals(rate, 7)
            val level = 3_000.0
            val pcm = s.concat(s.noise(600, bg), s.over(s.speech(8_000, level), bg), s.over(held(s, dur, level * rel), bg),
                s.over(s.speech(20_000, level), bg), s.noise(15_000, bg))
            endsAfterSpeech("held ${rel}x for $dur ms", pcm, s.ms(pcm) - 15_000)
        }
    }

    @Test
    fun `J - also at other speech levels, shorter silence settings and the phone read size`() {
        for (level in listOf(600.0, 1_500.0)) for (silenceMs in listOf(1_000L, 2_000L, 5_000L)) {
            val s = Signals(rate, 21)
            val pcm = s.concat(s.noise(600, bg), s.over(s.speech(6_000, level), bg), s.over(held(s, 900, level), bg),
                s.over(s.speech(10_000, level), bg), s.noise(15_000, bg))
            endsAfterSpeech("level $level, setting $silenceMs", pcm, s.ms(pcm) - 15_000, silenceMs)
        }
    }

    @Test
    fun `N - a steady noise burst while talking never ends the request`() {
        for (lvl in listOf(1.0, 2.0)) for (dur in listOf(3_000L, 5_000L)) {
            val s = Signals(rate, 11)
            val a = s.speech(8_000, 3_000.0)
            s.speech(3_000, 3_000.0)
            val c = s.speech(20_000, 3_000.0)
            val mid = s.mix(s.speech(dur, 3_000.0), s.noise(dur, 3_000.0 * lvl))
            val pcm = s.concat(s.noise(600, bg), s.over(a, bg), mid, s.over(c, bg), s.noise(15_000, bg))
            endsAfterSpeech("noise burst ${lvl}x for $dur ms", pcm, s.ms(pcm) - 15_000)
        }
    }

    @Test
    fun `D - continuous voiced sound with shallow modulation is not cut`() {
        for (depth in listOf(0.3, 0.5, 0.6, 0.8)) {
            val s = Signals(rate, 42)
            val pcm = s.concat(s.noise(600, bg), s.over(shallow(s, 30_000, 3_000.0, depth), bg), s.noise(15_000, bg))
            endsAfterSpeech("trough/peak $depth", pcm, 30_600)
        }
    }

    @Test
    fun `the recorders never upload a request that a held sound would have truncated`() {
        for (chunkBytes in listOf(3_200, 5_120)) {
            val s = Signals(rate, 7)
            val pcm = s.concat(s.noise(600, bg), s.over(s.speech(8_000, 3_000.0), bg), s.over(held(s, 3_000, 3_000.0), bg),
                s.over(s.speech(20_000, 3_000.0), bg), s.noise(15_000, bg))
            val speechEndMs = s.ms(pcm) - 15_000
            val bytes = Signals.bytes(pcm)
            var offset = 0
            val mic = PcmSource { buffer ->
                val n = minOf(buffer.size, bytes.size - offset)
                if (n <= 0) 0 else { System.arraycopy(bytes, offset, buffer, 0, n); offset += n; n }
            }
            val calls = mutableListOf<String>()
            lateinit var loop: PcmCaptureLoop
            val capture = CaptureCoordinator(object : CapturePort {
                override fun stopRecorder(captureId: String, reason: CaptureStop): ByteArray? { calls += "stop:$reason"; loop.stop(); return PcmCaptureLoop.wav(loop.pcm()) }
                override fun haptic(event: HapticEvent) {}
                override fun cue(line: String) {}
                override fun upload(captureId: String, trigger: TurnTrigger, wav: ByteArray) { calls += "upload:${(wav.size - 44) / 32}" }
                override fun uploadRecognized(turnId: String, text: String) {}
                override fun discard(message: String) { calls += "discard:$message" }
            })
            capture.begin("c", TurnTrigger.WAKE_PHRASE)
            loop = PcmCaptureLoop(mic, Long.MAX_VALUE, SilenceEndpoint(), object : PcmCaptureLoop.Listener {
                override fun onLive() {}
                override fun onCalibrated() = capture.onCalibrated("c")
                override fun onEnd(reason: CaptureEnd) { capture.stop("c", CaptureStop.of(reason)) }
            }, chunkBytes = chunkBytes, retryDelay = {})
            loop.run()
            assertEquals("$chunkBytes: $calls", "stop:SILENCE", calls.first())
            val uploadedMs = calls.single { it.startsWith("upload:") }.substringAfter(':').toLong()
            assertTrue("$chunkBytes: uploaded $uploadedMs ms, speech ends at $speechEndMs ms", uploadedMs >= speechEndMs)
        }
    }

    // ── P2-2 ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `K - speaking before the cue is heard in at least 38 of 40 and never sent truncated`() {
        for (level in listOf(500.0, 800.0, 1_500.0, 3_000.0)) {
            var ok = 0
            var noSpeech = 0
            var cut = 0
            for (seed in 1..40) {
                val s = Signals(rate, seed)
                val sp = s.over(s.speech(20_000, level), bg)
                val total = s.ms(sp)
                val r = endpoint(s.concat(sp, s.noise(15_000, bg)))
                when {
                    r.decision == EndpointDecision.NO_SPEECH -> noSpeech++
                    r.decision == EndpointDecision.END_OF_SPEECH && r.endMs < total -> cut++
                    else -> ok++
                }
            }
            assertEquals("rms $level: truncated sends (ok=$ok noSpeech=$noSpeech)", 0, cut)
            assertTrue("rms $level: ok=$ok noSpeech=$noSpeech", ok >= 38)
        }
    }

    // ── rising stationary noise must still end (kept from the candidate) ─────────────────────

    @Test
    fun `F - background that rises 2_5x to 10x after speech still ends the request`() {
        for (ratio in listOf(2.5, 2.9, 5.0, 10.0)) {
            val s = Signals(rate, 42)
            val sp = s.over(s.speech(5_000, 3_000.0), bg)
            val speechEnd = 600 + s.ms(sp)
            val r = endpoint(s.concat(s.noise(600, bg), sp, s.noise(60_000, bg * ratio)))
            assertEquals("$ratio x", EndpointDecision.END_OF_SPEECH, r.decision)
            assertTrue("$ratio x ended ${r.endMs - speechEnd} ms after speech", r.endMs - speechEnd in 2_000..6_000)
        }
    }

    // ── P3-5 ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `E - isolated clicks and sparse typing-like clicks are not speech, a short word still is`() {
        val s = Signals(rate, 42)
        assertEquals("one 60 ms click per second", EndpointDecision.NO_SPEECH,
            endpoint(s.concat(s.noise(600, bg), clicks(s, 12_000, 1_000, 60, 4_000.0))).decision)
        assertEquals("20 ms clicks every 200 ms", EndpointDecision.NO_SPEECH,
            endpoint(s.concat(s.noise(600, bg), clicks(s, 12_000, 200, 20, 4_000.0))).decision)
        assertEquals("40 ms clicks every 150 ms", EndpointDecision.NO_SPEECH,
            endpoint(s.concat(s.noise(600, bg), clicks(s, 12_000, 150, 40, 4_000.0))).decision)
        val sp5 = s.over(s.speech(5_000, 3_000.0), bg)
        val after = endpoint(s.concat(s.noise(600, bg), sp5, clicks(s, 60_000, 200, 20, 4_000.0)))
        assertEquals(EndpointDecision.END_OF_SPEECH, after.decision)
        assertTrue("typing after the request holds it ${after.endMs - 600 - s.ms(sp5)} ms", after.endMs - 600 - s.ms(sp5) <= 3_500)
        for (silenceMs in listOf(500L, 2_000L, 5_000L, 10_000L)) {
            val word = s.over(s.concat(s.syllable(300, 3_000.0), s.silence(80), s.syllable(300, 3_000.0)), bg)
            val r = endpoint(s.concat(s.noise(600, bg), word, s.noise(15_000, bg)), silenceMs)
            assertEquals("two-syllable command, $silenceMs", EndpointDecision.END_OF_SPEECH, r.decision)
            assertEquals(silenceMs, r.endMs - r.lastVoicedMs)
        }
        val single = endpoint(s.concat(s.noise(600, bg), s.over(s.syllable(200, 1_500.0), bg), s.noise(5_000, bg)))
        assertEquals("one 200 ms word", EndpointDecision.END_OF_SPEECH, single.decision)
    }

    // ── P2-4 ─────────────────────────────────────────────────────────────────────────────────

    private fun gate(pcm: ShortArray) = AudioInputGate.assess(PcmCaptureLoop.wav(Signals.bytes(pcm), rate)!!, "audio/wav")

    @Test
    fun `push-to-talk speech in loud steady noise at 3, 5 and 7 dB is accepted for every seed and length`() {
        for (secs in listOf(4, 20, 60)) for ((snr, noise) in listOf("7 dB" to 1_340.0, "5 dB" to 1_700.0, "3 dB" to 2_100.0)) {
            var usable = 0
            for (seed in 1..30) {
                val s = Signals(rate, seed)
                val pcm = s.concat(s.noise(300, noise), s.over(s.speech(secs * 1_000L, 3_000.0), noise), s.noise(300, noise))
                if (gate(pcm) == AudioInputVerdict.USABLE) usable++
            }
            assertEquals("$secs s at $snr", 30, usable)
        }
    }

    @Test
    fun `steady noise that starts and stops inside the recording is refused, speech in the same place is not`() {
        // Found on the paired emulators: a push-to-talk recording of quiet + steady noise + quiet.
        for (seed in 1..10) for (level in listOf(300.0, 700.0, 1_200.0, 3_000.0)) for (secs in listOf(1L, 2L, 6L, 30L)) {
            val s = Signals(rate, seed)
            val stepNoise = s.concat(s.noise(500, 3.0), s.noise(secs * 1_000, level), s.noise(600, 3.0))
            assertEquals("seed $seed: ${secs}s of steady noise $level between quiet margins", AudioInputVerdict.NO_SPEECH_ENERGY, gate(stepNoise))
            val inRoom = s.concat(s.noise(500, bg), s.noise(secs * 1_000, level), s.noise(600, bg))
            assertEquals("seed $seed: the same over room noise", AudioInputVerdict.NO_SPEECH_ENERGY, gate(inRoom))
        }
        for (seed in 1..10) {
            val s = Signals(rate, seed)
            assertEquals("a perfectly steady tone alone", AudioInputVerdict.NO_SPEECH_ENERGY, gate(s.concat(s.noise(300, bg), s.tone(3_000, 3_000.0), s.noise(300, bg))))
            // A held vowel wobbles like a voice (±1 dB): the check stays on the permissive side and lets it through.
            assertEquals("a held vowel alone", AudioInputVerdict.USABLE, gate(s.concat(s.noise(300, bg), held(s, 3_000, 3_000.0), s.noise(300, bg))))
            assertEquals("speech between quiet margins", AudioInputVerdict.USABLE,
                gate(s.concat(s.noise(500, 3.0), s.speech(6_000, 1_200.0), s.noise(600, 3.0))))
            assertEquals("speech after a steady noise", AudioInputVerdict.USABLE,
                gate(s.concat(s.noise(500, bg), s.noise(3_000, 1_200.0), s.over(s.speech(4_000, 3_000.0), bg), s.noise(600, bg))))
            assertEquals("one short word", AudioInputVerdict.USABLE, gate(s.concat(s.noise(500, bg), s.over(s.syllable(220, 1_500.0), bg), s.noise(600, bg))))
        }
    }

    @Test
    fun `recordings with no speech are still refused and soft or tight speech still passes`() {
        val s = Signals(rate, 42)
        assertEquals(AudioInputVerdict.SILENT, gate(s.silence(5_000)))
        assertEquals(AudioInputVerdict.SILENT, gate(s.noisePeak(9_200, 2)))
        for (level in listOf(80.0, 300.0, 1_000.0, 2_000.0, 5_000.0)) for (secs in listOf(2L, 5L, 60L)) {
            val expected = if (level < 100) AudioInputVerdict.SILENT else AudioInputVerdict.NO_SPEECH_ENERGY
            assertEquals("steady noise $level for $secs s", expected, gate(s.noise(secs * 1_000, level)))
        }
        assertEquals("sparse clicks only", AudioInputVerdict.NO_SPEECH_ENERGY, gate(s.concat(s.noise(600, bg), clicks(s, 6_000, 1_000, 60, 4_000.0))))
        assertEquals(AudioInputVerdict.USABLE, gate(s.syllable(300, 3_000.0)))
        assertEquals(AudioInputVerdict.USABLE, gate(s.speech(1_500, 3_000.0)))
        assertEquals(AudioInputVerdict.USABLE, gate(s.speech(60_000, 3_000.0)))
        assertEquals(AudioInputVerdict.USABLE, gate(s.concat(s.noise(300, bg), s.over(s.speech(3_000, 200.0), bg), s.noise(300, bg))))
    }
}
