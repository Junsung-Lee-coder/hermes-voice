package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.*
import com.rumi.hermesvoice.core.net.*
import com.rumi.hermesvoice.core.sessions.*
import com.rumi.hermesvoice.core.settings.*
import com.rumi.hermesvoice.core.voice.*
import com.rumi.hermesvoice.core.watchlink.*

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HermesDashboardClientTest {
    private val fake = FakeHermesDashboard()
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private val tokens = InMemoryHermesTokenStore()
    private val client = HermesDashboardClient(HermesDashboardEndpoint.parse(fake.baseUrl), http, tokens)

    @After fun tearDown() = fake.close()

    private fun signIn() = tokens.save(HermesBearerSession(fake.accessToken, fake.refreshToken, null, "basic", "jun"))

    @Test
    fun `native code exchange stores the bearer pair`() = runBlocking {
        val pkce = PkcePair.generate()
        fake.expectedChallenge = pkce.challenge
        try {
            client.exchangeNativeCode("gw-code", PkcePair.generate().verifier)
            fail("wrong verifier must not be accepted")
        } catch (error: HermesHttpException) {
            assertEquals(400, error.status)
        }
        assertNull(tokens.load())
        val session = client.exchangeNativeCode("gw-code", pkce.verifier)
        assertEquals("AT-1", session.accessToken)
        assertEquals(session, tokens.load())
    }

    @Test
    fun `audio routes need a session and never fake one`() = runBlocking {
        try {
            client.transcribe(byteArrayOf(1), "audio/wav")
            fail("expected auth required")
        } catch (_: HermesAuthRequiredException) {
        }
        assertEquals(0, fake.server.requestCount)
    }

    @Test
    fun `transcribe and speak use the existing dashboard audio routes`() = runBlocking {
        signIn()
        assertEquals("move my 2pm meeting to 3", client.transcribe(ByteArray(44) { 1 }, "audio/wav"))
        val audio = client.speak("On it.")
        assertEquals("audio/mpeg", audio.mimeType)
        assertEquals("On it.", fake.decodeSpoken(audio))
        val recorded = fake.server.takeRequest()
        assertEquals("/api/audio/transcribe", recorded.requestUrl!!.encodedPath)
        assertEquals("Bearer AT-1", recorded.getHeader("Authorization"))
    }

    @Test
    fun `a 401 refreshes once and retries with the rotated token`() = runBlocking {
        signIn()
        fake.rejectAccessTokens = setOf("AT-1")
        assertEquals("On it.", fake.decodeSpoken(client.speak("On it.")))
        assertEquals(1, fake.refreshes)
        assertEquals("AT-2", tokens.load()!!.accessToken)
        assertEquals("RT-2", tokens.load()!!.refreshToken)
    }

    @Test
    fun `a rejected refresh clears the session and requires sign-in`() = runBlocking {
        signIn()
        fake.rejectAccessTokens = setOf("AT-1")
        fake.refreshAccepted = false
        try {
            client.speak("hi")
            fail("expected auth required")
        } catch (_: HermesAuthRequiredException) {
        }
        assertNull(tokens.load())
        assertFalse(client.isSignedIn())
    }

    @Test
    fun `ws ticket is minted with the bearer`() = runBlocking {
        signIn()
        assertTrue(client.mintWsTicket().startsWith("ticket-"))
    }
}
