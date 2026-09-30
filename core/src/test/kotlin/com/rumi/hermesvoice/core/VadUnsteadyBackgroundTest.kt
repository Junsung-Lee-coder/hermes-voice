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
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared VAD where the background is not steady and where a request does not start or go on
 * cleanly: a background whose level drifts or jumps ([UnsteadyNoise]), steady noise that starts
 * with nobody speaking, noise after speech of different loudness, and words said one at a time.
 * Each matrix runs through the real [SilenceEndpoint], the recorders' [PcmCaptureLoop] with
 * [CaptureCoordinator], or [AudioInputGate], next to controls that must keep working (soft speech,
 * speech in noise).
 *
 * An energy detector cannot tell every changing noise from speech, so several of these assert a
 * measured rate, not zero: the bounds are what this detector does on these signals.
 */
class VadUnsteadyBackgroundTest {
    private val rate = 16_000
    private val bg = 80.0

    private class Res(val decision: EndpointDecision, val endMs: Long, val lastVoicedMs: Long, val speech: Boolean)

    private fun endpoint(pcm: ShortArray, silenceMs: Long = 2_000, chunk: Int = 3_200): Res {
        val e = SilenceEndpoint(sampleRate = rate, silenceMs = silenceMs)
        var d = EndpointDecision.CONTINUE
        for (c in Signals.chunks(Signals.bytes(pcm), chunk)) {
            d = e.accept(c)
            if (d != EndpointDecision.CONTINUE) break
        }
        return Res(d, e.elapsedMs, e.lastVoicedEndMs, e.speechDetected)
    }

    private fun gate(pcm: ShortArray) = AudioInputGate.assess(PcmCaptureLoop.wav(Signals.bytes(pcm), rate)!!, "audio/wav")

    /** What a hands-free recorder did with [pcm]: how it ended, and whether the recording was uploaded (and how long) or discarded. */
    private class Capture(val end: CaptureEnd?, val uploadedMs: Long?, val discarded: String?, val speech: Boolean)

    private fun handsFree(pcm: ShortArray, chunkBytes: Int = 3_200): Capture {
        val bytes = Signals.bytes(pcm)
        var offset = 0
        val mic = PcmSource { buffer ->
            val n = minOf(buffer.size, bytes.size - offset)
            if (n <= 0) 0 else { System.arraycopy(bytes, offset, buffer, 0, n); offset += n; n }
        }
        var uploaded: Long? = null
        var discarded: String? = null
        var ended: CaptureEnd? = null
        lateinit var loop: PcmCaptureLoop
        val capture = CaptureCoordinator(object : CapturePort {
            override fun stopRecorder(captureId: String, reason: CaptureStop): ByteArray? { loop.stop(); return PcmCaptureLoop.wav(loop.pcm()) }
            override fun haptic(event: HapticEvent) {}
            override fun cue(line: String) {}
            override fun upload(captureId: String, trigger: TurnTrigger, wav: ByteArray) { uploaded = (wav.size - 44) / 32L }
            override fun uploadRecognized(turnId: String, text: String) {}
            override fun discard(message: String) { discarded = message }
        })
        capture.begin("c", TurnTrigger.WAKE_PHRASE)
        val endpoint = SilenceEndpoint(sampleRate = rate)
        loop = PcmCaptureLoop(mic, Long.MAX_VALUE, endpoint, object : PcmCaptureLoop.Listener {
            override fun onLive() {}
            override fun onCalibrated() = capture.onCalibrated("c")
            override fun onEnd(reason: CaptureEnd) { ended = reason; capture.stop("c", CaptureStop.of(reason)) }
        }, chunkBytes = chunkBytes, retryDelay = {})
        loop.run()
        // The audio ran out with the recorder still open: only a tap would end it.
        return Capture(ended.takeIf { it != CaptureEnd.MIC_ERROR }, uploaded, discarded, endpoint.speechDetected)
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

    /** Words of [onMs] separated by [offMs] of nothing, for [ms]. */
    private fun paced(s: Signals, ms: Long, onMs: Long, offMs: Long, rms: Double): ShortArray {
        val parts = ArrayList<ShortArray>()
        var t = 0L
        while (t < ms) {
            parts += s.syllable(onMs, rms)
            parts += s.silence(offMs)
            t += onMs + offMs
        }
        return s.concat(*parts.toTypedArray())
    }

    // ── P1-A: a background that is not steady ────────────────────────────────────────────────

    @Test
    fun `a drifting background with nobody speaking is rarely recorded as a request and more rarely uploaded`() {
        // 40 seeds × 30 s per cell, through the recorder: qualified as speech / uploaded after the recording check.
        // A floor that sits at the background's quietest moments takes its ordinary louder moments for speech
        // (most seeds at 3 dB); following the typical level keeps that to the rates below.
        val bounds = mapOf(1.0 to (0 to 0), 2.0 to (0 to 0), 3.0 to (0 to 0), 4.5 to (10 to 10))
        for (room in listOf(100.0, 300.0)) for ((sigma, bound) in bounds) {
            val qualified = ArrayList<Int>()
            val uploaded = ArrayList<Int>()
            for (seed in 1..40) {
                val c = handsFree(UnsteadyNoise.drift(rate, seed, 30_000, room, sigma))
                if (c.speech) qualified += seed
                if (c.uploadedMs != null) uploaded += seed
                assertTrue("room $room drift $sigma seed $seed: still recording at 30 s", c.end != null)
            }
            assertTrue("room $room, drift $sigma dB: qualified as speech for seeds $qualified", qualified.size <= bound.first)
            assertTrue("room $room, drift $sigma dB: uploaded for seeds $uploaded", uploaded.size <= bound.second)
        }
        // Under the absolute minimum floor nothing qualifies at any drift.
        for (sigma in bounds.keys) for (seed in 1..40) {
            assertFalse("room 40 drift $sigma seed $seed", endpoint(UnsteadyNoise.drift(rate, seed, 30_000, 40.0, sigma)).speech)
        }
    }

    @Test
    fun `a request under a drifting background is never cut and ends soon after the speech`() {
        // The same noise under and after a 5 s request: it must end, soon, and never before the speech does.
        val bounds = mapOf(1.0 to (2_050L to 2_100L), 2.0 to (2_050L to 2_100L), 3.0 to (2_250L to 4_900L), 4.5 to (3_200L to 10_500L))
        for (room in listOf(100.0, 300.0)) for ((sigma, bound) in bounds) {
            var sum = 0L
            var worst = 0L
            for (seed in 1..40) {
                val s = Signals(rate, seed)
                val sp = s.speech(5_000, 3_000.0)
                val speechEnd = 600 + s.ms(sp)
                val r = endpoint(s.mix(s.concat(s.silence(600), sp), UnsteadyNoise.drift(rate, seed + 500, speechEnd + 60_000, room, sigma)))
                assertEquals("room $room drift $sigma seed $seed", EndpointDecision.END_OF_SPEECH, r.decision)
                assertTrue("room $room drift $sigma seed $seed: ended at ${r.endMs}, inside the speech (to $speechEnd)", r.endMs >= speechEnd)
                sum += r.endMs - speechEnd
                worst = maxOf(worst, r.endMs - speechEnd)
            }
            assertTrue("room $room, drift $sigma dB: mean ${sum / 40} ms after the speech", sum / 40 <= bound.first)
            assertTrue("room $room, drift $sigma dB: worst $worst ms after the speech", worst <= bound.second)
        }
    }

    @Test
    fun `the recording check refuses most recordings of a drifting background alone`() {
        // 6 s of noise alone at the send check: a fixed "half again above the floor" lets nearly all of it through.
        val bounds = mapOf(1.0 to 0, 2.0 to 2, 3.0 to 4, 4.5 to 24)
        for ((sigma, bound) in bounds) {
            val usable = (1..40).filter { gate(UnsteadyNoise.drift(rate, it, 6_000, 300.0, sigma)) == AudioInputVerdict.USABLE }
            assertTrue("drift $sigma dB: usable for seeds $usable", usable.size <= bound)
        }
        // Longer recordings of it are refused at least as well.
        for ((sigma, bound) in mapOf(1.0 to 0, 2.0 to 0, 3.0 to 3)) {
            val usable = (1..40).filter { gate(UnsteadyNoise.drift(rate, it + 300, 12_000, 300.0, sigma)) == AudioInputVerdict.USABLE }
            assertTrue("12 s, drift $sigma dB: usable for seeds $usable", usable.size <= bound)
        }
    }

    @Test
    fun `a background whose level jumps every 100 ms - rarely a request, and a request in it still ends`() {
        // Level jumps look like syllables: at 4.5 dB and above such noise is often taken for speech, which an
        // energy detector cannot avoid; a request in it must still end.
        val alone = mapOf(2.0 to 0, 3.0 to 0, 4.5 to 8, 6.0 to 33)
        val meanEnd = mapOf(2.0 to 2_050L, 3.0 to 2_200L, 4.5 to 3_300L, 6.0 to 11_000L)
        for ((sigma, bound) in alone) {
            val qualified = ArrayList<Int>()
            val open = ArrayList<Int>()
            var sum = 0L
            for (seed in 1..40) {
                val s = Signals(rate, seed)
                if (endpoint(UnsteadyNoise.blocks(rate, seed, 30_000, 150.0, sigma)).speech) qualified += seed
                val sp = s.over(s.speech(5_000, 3_000.0), 0.0)
                val speechEnd = 600 + s.ms(sp)
                val r = endpoint(s.concat(UnsteadyNoise.blocks(rate, seed + 100, 600, 150.0, sigma),
                    s.mix(sp, UnsteadyNoise.blocks(rate, seed + 200, s.ms(sp), 150.0, sigma)), UnsteadyNoise.blocks(rate, seed + 300, 60_000, 150.0, sigma)))
                if (r.decision == EndpointDecision.CONTINUE) {
                    open += seed
                    continue
                }
                assertEquals("sigma $sigma seed $seed: a request in it", EndpointDecision.END_OF_SPEECH, r.decision)
                assertTrue("sigma $sigma seed $seed: ended at ${r.endMs}, inside the speech (to $speechEnd)", r.endMs >= speechEnd)
                sum += r.endMs - speechEnd
            }
            assertTrue("sigma $sigma dB: noise alone qualified for seeds $qualified", qualified.size <= bound)
            // Jumps of 6 dB every 100 ms are as loud and as changing as soft speech: there a request may wait for a tap.
            assertTrue("sigma $sigma dB: still recording a minute after the speech for seeds $open", open.size <= if (sigma >= 6.0) 1 else 0)
            val mean = sum / (40 - open.size)
            assertTrue("sigma $sigma dB: requests end on average $mean ms after the speech", mean <= meanEnd.getValue(sigma))
        }
    }

    @Test
    fun `speech in a drifting background is found, never cut, and passes the recording check`() {
        // Nearby positive control: the floor follows the background, not the speech. A request 3 s after the
        // cue, 15 to 30 dB above a background that drifts by 2 or 3 dB.
        for (sigma in listOf(2.0, 3.0)) for (snrDb in listOf(15, 20, 30)) for (seed in 1..30) {
            val s = Signals(rate, seed + 400)
            val sp = s.speech(8_000, 200.0 * 10.0.pow(snrDb / 20.0))
            val speechEnd = 3_400 + s.ms(sp)
            val pcm = s.mix(s.concat(s.silence(3_400), sp), UnsteadyNoise.drift(rate, seed + 1_300, speechEnd + 20_000, 200.0, sigma))
            val r = endpoint(pcm)
            val label = "drift $sigma dB, speech $snrDb dB above it, seed $seed"
            assertEquals(label, EndpointDecision.END_OF_SPEECH, r.decision)
            assertTrue("$label: ended at ${r.endMs}, speech to $speechEnd", r.endMs >= speechEnd - 60)
            assertTrue("$label: ended ${r.endMs - speechEnd} ms after the speech", r.endMs - speechEnd <= 4_000)
            assertEquals("$label: what was recorded", AudioInputVerdict.USABLE, gate(pcm.copyOf(s.samples(r.endMs))))
        }
    }

    @Test
    fun `soft speech a few times the background is never cut in a minute of talking`() {
        // The floor must not creep into quiet speech: following every sound under the onset would cut all of these.
        for (times in listOf(3.2, 3.5, 4.0, 5.0)) for (base in listOf(80.0, 150.0)) for (seed in 1..10) {
            val s = Signals(rate, 300 + seed)
            val sp = s.speech(60_000, times * base)
            val speechEnd = 600 + s.ms(sp)
            val r = endpoint(s.over(s.concat(s.silence(600), sp, s.silence(6_000)), base))
            val label = "speech $times x a background of $base, seed $seed"
            assertEquals(label, EndpointDecision.END_OF_SPEECH, r.decision)
            assertTrue("$label: ended at ${r.endMs}, speech to $speechEnd", r.endMs >= speechEnd - 60)
        }
    }

    // ── P2-C: steady sound with nobody speaking; noise after speech ──────────────────────────

    @Test
    fun `steady noise that starts after the cue with nobody speaking is not a request`() {
        // Marked as speech, such noise would be recorded until a tap.
        for (times in listOf(2.5, 4.0, 5.0, 10.0, 30.0)) for (room in listOf(bg, 300.0)) {
            val s = Signals(rate, 5)
            val pcm = s.concat(s.noise(1_600, room), s.noise(60_000, room * times))
            val r = endpoint(pcm)
            val label = "noise $times x a background of $room"
            assertEquals(label, EndpointDecision.NO_SPEECH, r.decision)
            assertEquals("$label: 8 s after the cue", 8_400L, r.endMs)
            assertFalse(label, r.speech)
            val c = handsFree(pcm)
            assertEquals(label, CaptureEnd.NO_SPEECH, c.end)
            assertEquals("$label: ${c.uploadedMs}", "Didn't hear a request", c.discarded)
        }
        // The same when it starts late, or is Gaussian noise: it is absorbed, then the request is "not started".
        for (startMs in listOf(3_000L, 7_500L)) {
            val s = Signals(rate, 9)
            val r = endpoint(s.concat(s.noise(400 + startMs, bg), s.noise(30_000, 800.0)))
            assertEquals("starts $startMs ms after the cue", EndpointDecision.NO_SPEECH, r.decision)
            // 8 s after the cue, or as soon after that as the noise is absorbed.
            assertTrue("starts $startMs ms after the cue: ended at ${r.endMs}", r.endMs in 8_400L..maxOf(8_400L, 400 + startMs + 2_000))
        }
        val gaussian = endpoint(Signals(rate, 3).let { it.concat(it.noise(1_600, bg), UnsteadyNoise.drift(rate, 3, 30_000, 900.0, 0.0)) })
        assertEquals(EndpointDecision.NO_SPEECH, gaussian.decision)
    }

    @Test
    fun `a request said over noise that started first is found and ends after the speech`() {
        // Steady noise starts with nobody speaking and is absorbed; then the user speaks clearly above it.
        for (seed in 1..10) {
            val s = Signals(rate, seed)
            val sp = s.speech(5_000, 3_000.0)
            val speechEnd = 1_600 + 3_000 + s.ms(sp)
            val r = endpoint(s.concat(s.noise(1_600, bg), s.noise(3_000, 400.0), s.over(sp, 400.0), s.noise(12_000, 400.0)))
            assertEquals("seed $seed", EndpointDecision.END_OF_SPEECH, r.decision)
            assertTrue("seed $seed: ended ${r.endMs - speechEnd} ms after the speech", r.endMs - speechEnd in 1_900..3_000)
        }
    }

    @Test
    fun `a held voice at the start is kept - the request that follows it is complete`() {
        // A held "uhh" wobbles like a voice (±1 dB), unlike steady noise: it is never absorbed, first thing or not.
        for (dur in listOf(1_000L, 2_000L, 3_000L, 5_000L)) for (level in listOf(800.0, 3_000.0)) {
            val s = Signals(rate, 31)
            val pcm = s.concat(s.noise(600, bg), s.over(held(s, dur, level), bg), s.over(s.speech(10_000, level), bg), s.noise(15_000, bg))
            val speechEnd = s.ms(pcm) - 15_000
            val r = endpoint(pcm)
            assertEquals("held $dur ms at $level first", EndpointDecision.END_OF_SPEECH, r.decision)
            assertTrue("held $dur ms at $level first: ended at ${r.endMs}, speech to $speechEnd", r.endMs in speechEnd..(speechEnd + 3_000))
        }
        val s = Signals(rate, 32)
        val alone = endpoint(s.concat(s.noise(600, bg), s.over(held(s, 4_000, 2_000.0), bg), s.noise(6_000, bg)))
        assertEquals("a held voice alone ends when it stops", EndpointDecision.END_OF_SPEECH, alone.decision)
        assertTrue("ended at ${alone.endMs}", alone.endMs in 6_500L..7_000L)
    }

    @Test
    fun `steady noise after speech ends the request only when it is clearly quieter than the speech was`() {
        // Speech of three loudnesses (5 s), then 60 s of steady noise. The request ends when the noise is at most
        // 45 % of the talker's typical voiced level or barely above the onset (under 4.5 times the old background).
        // Louder noise could be the talker holding a sound, so the request stays open for a tap: never cut.
        // (Absorbing any steady sound would end all twelve, and cut requests with a held sound for the same reason.)
        val ends = setOf(400.0 to 200.0, 800.0 to 200.0, 3_000.0 to 200.0, 3_000.0 to 400.0, 3_000.0 to 800.0)
        for (speech in listOf(400.0, 800.0, 3_000.0)) for (noise in listOf(200.0, 400.0, 800.0, 1_500.0)) {
            val s = Signals(rate, 6)
            val pcm = s.concat(s.noise(600, bg), s.over(s.speech(5_000, speech), bg), s.noise(60_000, noise))
            val r = endpoint(pcm)
            val label = "speech $speech then noise $noise"
            if (speech to noise in ends) {
                assertEquals(label, EndpointDecision.END_OF_SPEECH, r.decision)
                assertTrue("$label: ended ${r.endMs - 5_600} ms after the speech", r.endMs - 5_600 in 2_000..3_900)
            } else {
                assertEquals("$label stays open for a tap", EndpointDecision.CONTINUE, r.decision)
                // The tap sends everything recorded: the speech is in it, so the recording check lets it through.
                assertEquals(label, AudioInputVerdict.USABLE, gate(pcm.copyOf(s.samples(12_000))))
            }
        }
    }

    // ── P3-4: words said one at a time ───────────────────────────────────────────────────────

    @Test
    fun `words said one at a time keep the request open and it ends the set silence after the last word`() {
        // A word that starts a request (140 ms) also continues one: with a higher bar for continuing, 160 ms
        // words with 240 ms gaps end the request while the talking goes on.
        val cadences = listOf(200L to 150L, 150L to 180L, 120L to 180L, 100L to 150L, 100L to 200L, 160L to 240L,
            160L to 400L, 180L to 300L, 200L to 600L, 300L to 900L, 160L to 1_200L, 250L to 1_500L)
        val failures = ArrayList<String>()
        for ((on, off) in cadences) for (level in listOf(3_000.0, 800.0, 400.0)) for (silenceMs in listOf(500L, 2_000L)) for (chunk in listOf(3_200, 5_120)) {
            if (off > silenceMs - 150) continue   // a gap as long as the setting ends the request, by design
            // A word has to be voiced for 140 ms. A soft one (5 times the background) is voiced only in its
            // middle, so it has to last 160 ms; loud ones count from 100 ms when the gaps are short.
            if (level < 500 && on < 160) continue
            val s = Signals(rate, 8)
            val sp = s.over(paced(s, 10_000, on, off, level), bg)
            val lastWordEnd = 600 + s.ms(sp) - off
            val r = endpoint(s.concat(s.noise(600, bg), sp, s.noise(14_000, bg)), silenceMs, chunk)
            val label = "$on ms words / $off ms gaps at $level, setting $silenceMs ms, reads of $chunk bytes"
            if (r.decision != EndpointDecision.END_OF_SPEECH || r.endMs < lastWordEnd || r.endMs - r.lastVoicedMs != silenceMs ||
                r.lastVoicedMs !in (lastWordEnd - 60)..(lastWordEnd + 40)) {
                failures += "$label: ${r.decision} at ${r.endMs} ms, last voiced ${r.lastVoicedMs}, the last word ends at $lastWordEnd"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `clicks still never start or continue a request, and one 300 ms word still does`() {
        val s = Signals(rate, 42)
        fun clicks(ms: Long, everyMs: Long, clickMs: Long): ShortArray {
            val parts = ArrayList<ShortArray>()
            var t = 0L
            while (t < ms) {
                parts += s.tone(clickMs, 4_000.0, 1_200.0)
                parts += s.noise(everyMs - clickMs, bg)
                t += everyMs
            }
            return s.concat(*parts.toTypedArray())
        }
        for ((every, click) in listOf(200L to 20L, 150L to 40L, 1_000L to 60L)) {
            assertEquals("$click ms clicks every $every ms", EndpointDecision.NO_SPEECH, endpoint(s.concat(s.noise(600, bg), clicks(12_000, every, click))).decision)
            // Cut to whole 20 ms frames, so a click is its own length in frames here too.
            val sp = s.over(s.speech(5_000, 3_000.0), bg).let { it.copyOf(it.size / 320 * 320) }
            val after = endpoint(s.concat(s.noise(600, bg), sp, clicks(60_000, every, click)))
            assertEquals("$click ms clicks every $every ms after a request", EndpointDecision.END_OF_SPEECH, after.decision)
            assertTrue("held ${after.endMs - 600 - s.ms(sp)} ms", after.endMs - 600 - s.ms(sp) <= 3_500)
        }
        val word = endpoint(s.concat(s.noise(600, bg), s.over(s.syllable(300, 1_500.0), bg), s.noise(5_000, bg)))
        assertEquals(EndpointDecision.END_OF_SPEECH, word.decision)
        assertEquals(2_000L, word.endMs - word.lastVoicedMs)
    }

    @Test
    fun `a string of short knocks does not start a request however dense, a word of the same total length does`() {
        // Starting a request takes one unbroken 100 ms at or above the onset, which a syllable has. Knocks of 40–80 ms
        // in quick succession are voiced half the time, enough by density alone, but never unbroken for that long.
        val s = Signals(rate, 44)
        fun knocks(ms: Long, everyMs: Long, knockMs: Long): ShortArray {
            val parts = ArrayList<ShortArray>()
            var t = 0L
            while (t < ms) {
                parts += s.noise(knockMs, 4_000.0)
                parts += s.noise(everyMs - knockMs, bg)
                t += everyMs
            }
            return s.concat(*parts.toTypedArray())
        }
        for ((every, knock) in listOf(120L to 60L, 100L to 60L, 160L to 80L, 80L to 40L)) {
            val pcm = s.concat(s.noise(600, bg), knocks(12_000, every, knock))
            assertEquals("$knock ms knocks every $every ms", EndpointDecision.NO_SPEECH, endpoint(pcm).decision)
            assertEquals("$knock ms knocks every $every ms, as a recording", AudioInputVerdict.NO_SPEECH_ENERGY, gate(pcm.copyOf(s.samples(6_600))))
        }
        // The same sound unbroken for 140 ms is a (very short) word; and once a request is under way such knocks are
        // loud sound like any other: they can hold it open, which a request's end may wait out, never cut.
        val short = endpoint(s.concat(s.noise(600, bg), s.over(s.syllable(160, 3_000.0), bg), s.noise(5_000, bg)))
        assertEquals(EndpointDecision.END_OF_SPEECH, short.decision)
    }

    // ── the recording check: nearby controls ─────────────────────────────────────────────────

    @Test
    fun `the recording check still accepts speech a few dB above steady noise and speech well above a drifting one`() {
        // Gaussian steady noise (its frame level varies more than uniform noise): 5 and 7 dB always, 3 dB nearly.
        for (secs in listOf(4, 20)) for ((snr, noise, atLeast) in listOf(Triple("7 dB", 1_340.0, 30), Triple("5 dB", 1_700.0, 30), Triple("3 dB", 2_100.0, 29))) {
            var usable = 0
            for (seed in 1..30) {
                val s = Signals(rate, seed + 50)
                val sp = s.speech(secs * 1_000L, 3_000.0)
                if (gate(s.mix(s.concat(s.silence(300), sp), UnsteadyNoise.drift(rate, seed + 70, s.ms(sp) + 600, noise, 0.0))) == AudioInputVerdict.USABLE) usable++
            }
            assertTrue("$secs s at $snr over Gaussian noise: $usable of 30", usable >= atLeast)
        }
        // Over a background that drifts by 2 or 3 dB, speech has to stand out from the drift: 10 dB above it passes.
        for (sigma in listOf(2.0, 3.0)) for ((snrDb, atLeast) in listOf(10 to 29, 15 to 30, 20 to 30)) {
            var usable = 0
            for (seed in 1..30) {
                val s = Signals(rate, seed + 200)
                val sp = s.speech(5_000, 300.0 * 10.0.pow(snrDb / 20.0))
                if (gate(s.mix(s.concat(s.silence(1_000), sp), UnsteadyNoise.drift(rate, seed + 800, 1_000 + s.ms(sp) + 2_000, 300.0, sigma))) == AudioInputVerdict.USABLE) usable++
            }
            assertTrue("speech $snrDb dB above a background drifting by $sigma dB: $usable of 30", usable >= atLeast)
        }
    }

    @Test
    fun `a few dropped frames or a very short recording do not raise the bar of the recording check`() {
        val s = Signals(rate, 12)
        // Soft speech in a quiet room, with digital silence where the microphone started late or dropped out.
        val soft = s.over(s.concat(s.silence(500), s.speech(3_000, 250.0), s.silence(500)), 70.0)
        for (dropMs in listOf(0L, 60L, 120L, 300L)) {
            val pcm = s.concat(s.silence(dropMs), soft)
            assertEquals("$dropMs ms of digital silence first", AudioInputVerdict.USABLE, gate(pcm))
        }
        // Under a second of audio says too little about its background: the plain check applies.
        assertEquals(AudioInputVerdict.USABLE, gate(s.syllable(300, 3_000.0)))
        assertEquals(AudioInputVerdict.USABLE, gate(s.over(s.concat(s.silence(200), s.syllable(300, 1_000.0), s.silence(200)), bg)))
        assertEquals(AudioInputVerdict.NO_SPEECH_ENERGY, gate(s.noise(900, 500.0)))
    }

    // ── recovery of a floor that started wrong ───────────────────────────────────────────────

    @Test
    fun `a loud moment during the calibration or a background that drops does not lose the request`() {
        for (loud in listOf(1_000.0, 4_000.0)) for (seed in 1..10) {
            val s = Signals(rate, seed + 500)
            val sp = s.over(s.speech(5_000, 2_000.0), bg)
            val speechEnd = 1_900 + s.ms(sp)
            val r = endpoint(s.concat(s.noise(400, loud), s.noise(1_500, bg), sp, s.noise(8_000, bg)))
            assertEquals("calibrated at $loud, seed $seed", EndpointDecision.END_OF_SPEECH, r.decision)
            assertTrue("calibrated at $loud, seed $seed: ended ${r.endMs - speechEnd} ms after the speech", r.endMs - speechEnd in 1_900..2_100)
        }
        for (seed in 1..10) {
            val s = Signals(rate, seed + 600)
            val sp = s.speech(4_000, 3_000.0)
            val speechEnd = 600 + s.ms(sp)
            val r = endpoint(s.concat(s.noise(600, 600.0), s.over(sp, 600.0), s.noise(1_000, 600.0), s.noise(10_000, bg)))
            assertEquals("seed $seed", EndpointDecision.END_OF_SPEECH, r.decision)
            assertTrue("seed $seed: ended ${r.endMs - speechEnd} ms after the speech", r.endMs - speechEnd in 1_900..2_100)
        }
    }
}
