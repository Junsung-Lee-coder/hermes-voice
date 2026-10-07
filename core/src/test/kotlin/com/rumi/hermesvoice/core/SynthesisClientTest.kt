package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.HermesBearerSession
import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.auth.InMemoryHermesTokenStore
import com.rumi.hermesvoice.core.net.HermesDashboardClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `/api/audio/speak` answers ONE atomic JSON document only after the whole text is synthesized: its client has no read or call
 * time limit (the caller's inactivity policy and Stop end a dead request), while every other request keeps the bounded client.
 * Real sockets against the contract double with short real delays (no minutes-long waits).
 */
class SynthesisClientTest {
    private val fake = FakeHermesDashboard()
    private val http = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).callTimeout(600, TimeUnit.MILLISECONDS).build()
    private val tokens = InMemoryHermesTokenStore(HermesBearerSession(fake.accessToken, fake.refreshToken, null, "basic", "jun"))
    private val client = HermesDashboardClient(HermesDashboardEndpoint.parse(fake.baseUrl), http, tokens)

    @After fun tearDown() = fake.close()

    @Test
    fun `a synthesis slower than the ordinary read and call limits still completes with exact audio`() = runBlocking {
        fake.speakDelayMs = 1_500
        val audio = client.speak("A long answer.")
        assertEquals("A long answer.", fake.decodeSpoken(audio))
        assertEquals("audio/mpeg", audio.mimeType)
    }

    @Test
    fun `the same delay on another request is still bounded, so auth routing and transcription keep their limits`() = runBlocking {
        fake.transcribeDelayMs = 1_500
        try {
            client.transcribe(ByteArray(44) { 1 }, "audio/wav")
            fail("transcription must still time out on the bounded client")
        } catch (_: IOException) {
        }
    }

    @Test
    fun `cancelling a synthesis in flight cancels its call promptly`() = runBlocking {
        fake.speakDelayMs = 2_500
        val job = async { runCatching { client.speak("never finishes") } }
        withTimeout(5_000) { while (http.dispatcher.runningCallsCount() == 0) delay(10) }
        val started = System.nanoTime()
        job.cancel()
        withTimeout(3_000) { while (http.dispatcher.runningCallsCount() != 0) delay(10) }
        assertTrue("the call ended long before the server answered", (System.nanoTime() - started) / 1_000_000 < 3_000)
        assertEquals(0, http.dispatcher.runningCallsCount())
    }
}
