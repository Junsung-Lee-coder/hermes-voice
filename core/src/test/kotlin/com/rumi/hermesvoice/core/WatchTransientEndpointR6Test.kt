package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.audio.*
import com.rumi.hermesvoice.core.watchlink.*
import kotlin.math.*
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/** Synthetic PCM contracts, not wrist-mic/car recordings or recognizer calibration. */
class WatchTransientEndpointR6Test {
    private fun pcm(ms: Int, speech: Boolean = false, gain: Double = 1.0,
                    period: Int = 0, tickMs: Int = 60, tickPeak: Double = 6000.0,
                    floor: Double = 35.0, seed: Int = 17): ByteArray {
        val random = Random(seed)
        val out = ByteArray(ms * 32)
        for (n in 0 until ms * 16) {
            val t = n / 16.0
            val noise = floor * (random.nextDouble() * 2 - 1) * (1 + 0.2 * sin(t / 123))
            val voice = if (speech) gain * (1100 + 450 * sin(t / 53)) *
                (0.7 * sin(2 * PI * 170 * n / 16000) + 0.3 * sin(2 * PI * 340 * n / 16000)) else 0.0
            val inTick = if (period > 0) t % period else 1e9
            // Initial onset followed by a long fading gray-band tail: hangover alone must not renew speech.
            val tick = when {
                inTick < tickMs -> tickPeak * exp(-inTick / (tickMs / 2.0)) * sin(2 * PI * 1200 * n / 16000)
                // Fading ringing tail remains below onset but above release (~120–140 RMS).
                // R5 counts this entire tail as speech via hangover; Watch must exclude it.
                inTick < tickMs + 160 -> 200 * exp(-(inTick - tickMs) / 1000.0) * sin(2 * PI * 1200 * n / 16000)
                else -> 0.0
            }
            val value = (noise + voice + tick).toInt().coerceIn(-32768, 32767)
            out[n * 2] = value.toByte(); out[n * 2 + 1] = (value shr 8).toByte()
        }
        return out
    }

    private fun watch(silence: Long = 1200): SilenceEndpoint {
        // Same final test bytes compile on R5; only the isolated baseline lacks the new Watch factory.
        // The fallback is exactly R5's production Watch constructor, never a green stub.
        val factory = SilenceEndpoint.Companion.javaClass.methods.firstOrNull { it.name == "forWatch" && it.parameterCount == 2 }
        return if (factory == null) SilenceEndpoint(silenceMs = silence)
        else factory.invoke(SilenceEndpoint.Companion, silence, 16000) as SilenceEndpoint
    }

    private fun feed(endpoint: SilenceEndpoint, bytes: ByteArray): EndpointDecision {
        for (chunk in bytes.asList().chunked(640)) {
            val decision = endpoint.accept(chunk.toByteArray())
            if (decision != EndpointDecision.CONTINUE) return decision
        }
        return EndpointDecision.CONTINUE
    }

    @Test fun decayingPeriodicTicksAfterSpeechCannotKeepWatchRecordingAlive() {
        for (peak in listOf(1400.0, 5000.0, 14000.0)) for (period in listOf(300, 400, 600, 900))
            for (width in listOf(20, 40, 60, 80)) {
            val endpoint = watch()
            assertEquals(EndpointDecision.CONTINUE, feed(endpoint, pcm(400)))
            assertEquals(EndpointDecision.CONTINUE, feed(endpoint, pcm(800, speech = true)))
            // Include a quiet gap so the renewed-speech path, not onset, is specifically exercised.
            feed(endpoint, pcm(80))
            assertEquals("peak=$peak period=$period", EndpointDecision.END_OF_SPEECH,
                feed(endpoint, pcm(4000, period = period, tickMs = width, tickPeak = peak)))
            assertTrue("bounded trailing interval ${endpoint.elapsedMs}", endpoint.elapsedMs <= 1200 + 1200 + 340)
            assertTrue(endpoint.speechDetected)
        }
    }

    @Test fun quietShortCommandWithoutLeadingPostCueSilenceStillStartsAndEnds() {
        val endpoint = watch()
        feed(endpoint, pcm(400))
        assertEquals(EndpointDecision.CONTINUE, feed(endpoint, pcm(200, speech = true, gain = 0.35)))
        assertEquals(EndpointDecision.END_OF_SPEECH, feed(endpoint, pcm(2000, period = 600, tickPeak = 2500.0)))
        assertTrue(endpoint.speechDetected)
    }

    @Test fun resumedSpeechJustBeforeDeadlineRenewsTheConfiguredWindow() {
        val endpoint = watch()
        feed(endpoint, pcm(400)); feed(endpoint, pcm(800, speech = true)); feed(endpoint, pcm(1180))
        assertEquals(EndpointDecision.CONTINUE, feed(endpoint, pcm(240, speech = true, gain = 0.4)))
        assertEquals(EndpointDecision.CONTINUE, feed(endpoint, pcm(1100)))
        assertEquals(EndpointDecision.END_OF_SPEECH, feed(endpoint, pcm(400)))
    }

    @Test fun longSpeechAndSpeechTickMixturesAreNotSubjectToAHardRecordingDeadline() {
        for (seconds in listOf(30, 60, 120)) {
            val endpoint = watch()
            feed(endpoint, pcm(400))
            repeat(seconds * 2) { i ->
                assertEquals("$seconds s at $i", EndpointDecision.CONTINUE,
                    feed(endpoint, pcm(400, speech = true, gain = if (i % 3 == 0) 0.4 else 1.0,
                        period = 600, tickPeak = 4000.0, seed = i)))
                assertEquals(EndpointDecision.CONTINUE, feed(endpoint, pcm(100)))
            }
            assertEquals(EndpointDecision.END_OF_SPEECH, feed(endpoint, pcm(2200)))
        }
    }

    @Test fun steadyAndModulatedSubOnsetNoiseAndAbruptFloorChangeDoNotFabricateRespeech() {
        for (noise in listOf(25.0, 45.0, 65.0, 240.0)) {
            val endpoint = watch()
            feed(endpoint, pcm(400)); feed(endpoint, pcm(800, speech = true))
            assertEquals(EndpointDecision.END_OF_SPEECH, feed(endpoint, pcm(3000, floor = noise)))
        }
    }

    @Test fun pcmLoopWatchCoordinatorStopsAndAdmitsOneUploadOnly() {
        val bytes = pcm(400) + pcm(800, speech = true) + pcm(80) + pcm(4000, period = 600)
        var offset = 0; var stops = 0; var uploads = 0; val ends = mutableListOf<CaptureEnd>()
        lateinit var loop: PcmCaptureLoop
        val coordinator = CaptureCoordinator(object : CapturePort {
            override fun stopRecorder(captureId: String, reason: CaptureStop): ByteArray? {
                stops++; loop.stop(); return PcmCaptureLoop.wav(loop.pcm())
            }
            override fun haptic(event: HapticEvent) {}
            override fun cue(line: String) {}
            override fun upload(captureId: String, trigger: TurnTrigger, wav: ByteArray) { uploads++ }
            override fun uploadRecognized(turnId: String, text: String) { fail("not a recognizer request") }
            override fun discard(message: String) { fail(message) }
        }) // Real WAV eligibility gate, not an injected unconditional admission.
        assertTrue(coordinator.begin("tick-turn-0001", TurnTrigger.WAKE_PHRASE))
        loop = PcmCaptureLoop(PcmSource { target ->
            val n = minOf(target.size, bytes.size - offset)
            if (n > 0) { bytes.copyInto(target, 0, offset, offset + n); offset += n; n } else 0
        }, 8_000_000, watch(), object : PcmCaptureLoop.Listener {
            override fun onLive() { coordinator.onLive("tick-turn-0001") }
            override fun onCalibrated() { coordinator.onCalibrated("tick-turn-0001") }
            override fun onEnd(reason: CaptureEnd) { ends += reason; coordinator.stop("tick-turn-0001", CaptureStop.of(reason)) }
        }, chunkBytes = 640, retryDelay = {})
        loop.run()
        assertEquals(listOf(CaptureEnd.SILENCE), ends)
        assertEquals(1, stops); assertEquals(1, uploads)
        assertFalse(coordinator.stop("tick-turn-0001", CaptureStop.SILENCE))
        assertFalse(coordinator.tap()); assertFalse(coordinator.lifecycle())
        loop.run(); assertEquals(1, uploads)
    }

    @Test fun theExistingSilencePreferenceControlsTheWatchDeadlineNotAHardCaptureCap() {
        for (silence in listOf(500L, 2000L, 5000L)) {
            val endpoint = watch(silence)
            feed(endpoint, pcm(400)); feed(endpoint, pcm(800, speech = true)); feed(endpoint, pcm(80))
            assertEquals(EndpointDecision.END_OF_SPEECH, feed(endpoint, pcm((silence + 1000).toInt(), period = 600)))
            assertTrue(endpoint.elapsedMs >= 1200 + silence)
            assertTrue(endpoint.elapsedMs <= 1200 + silence + 340)
        }
    }

    @Test fun phoneDefaultContinuesToUseItsExistingEnergyPolicy() {
        val phone = SilenceEndpoint(silenceMs = 1200)
        feed(phone, pcm(400)); feed(phone, pcm(800, speech = true)); feed(phone, pcm(80))
        // Regression witness: only Watch opts into transient-resistant trailing time.
        assertEquals(EndpointDecision.CONTINUE, feed(phone, pcm(4000, period = 600)))
    }
}
