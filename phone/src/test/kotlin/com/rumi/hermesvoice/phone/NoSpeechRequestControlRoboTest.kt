package com.rumi.hermesvoice.phone

import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fixture control for the phone tests' "no speech synthesis / no transcription" checks, not a product test: the real
 * HermesDashboardClient makes one actual /api/audio/speak and one /api/audio/transcribe request to the dashboard contract double, and
 * the counters those checks read move, while the sessions request log never records either route.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoSpeechRequestControlRoboTest : PhoneRoboBase() {
    @Before fun setUp() = baseSetUp()
    @After fun tearDown() = baseTearDown()

    private fun logged(route: String) = synchronized(fake.restLog) { fake.restLog.any { it.contains(route) } }

    @Test fun T25_01_anActualSpeechSynthesisRequestIsCountedButNeverInTheSessionsRequestLog() {
        assertEquals(0, fake.speakRequests.get())
        runBlocking { app.wiring().dashboard.speak("synthetic control") }
        assertEquals("the no-speech checks see an actual synthesis request", 1, fake.speakRequests.get())
        assertFalse("the sessions request log would not have seen it", logged("/api/audio/speak"))
    }

    @Test fun T25_02_anActualTranscriptionRequestIsSeenButNeverInTheSessionsRequestLog() {
        assertNull(fake.lastTranscribedWav)
        runBlocking { app.wiring().dashboard.transcribe(byteArrayOf(1, 2, 3, 4), "audio/wav") }
        assertNotNull("the no-transcription checks see an actual transcription request", fake.lastTranscribedWav)
        assertFalse("the sessions request log would not have seen it", logged("/api/audio/transcribe"))
    }
}
