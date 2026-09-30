package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.*
import com.rumi.hermesvoice.core.net.*
import com.rumi.hermesvoice.core.sessions.*
import com.rumi.hermesvoice.core.settings.*
import com.rumi.hermesvoice.core.voice.*
import com.rumi.hermesvoice.core.watchlink.*

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesNativeSignInTest {
    @Test
    fun `pkce challenge matches the RFC 7636 appendix B vector`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            PkcePair.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val pair = PkcePair.generate()
        assertEquals(64, pair.verifier.length)
        assertEquals(PkcePair.challengeFor(pair.verifier), pair.challenge)
        assertFalse(pair.toString().contains(pair.verifier))
    }

    @Test
    fun `authorize url targets the dashboard native route under a prefix`() {
        val endpoint = HermesDashboardEndpoint.parse("https://hermes.example.ts.net/dash/")
        val pkce = PkcePair.generate()
        val url = HermesNativeSignIn.authorizeUrl(endpoint, pkce, "http://127.0.0.1:43123/hermes-native-callback", "st")
        assertEquals("/dash/auth/native/authorize", url.encodedPath)
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals(pkce.challenge, url.queryParameter("code_challenge"))
        assertEquals("http://127.0.0.1:43123/hermes-native-callback", url.queryParameter("redirect_uri"))
        assertEquals(null, url.queryParameter("code_verifier"))
        assertEquals("/api/audio/speak", HermesDashboardEndpoint.parse("https://h.example").route("api/audio/speak").encodedPath)
    }

    @Test
    fun `loopback redirect and endpoint transport rules`() {
        assertThrows(IllegalArgumentException::class.java) { HermesNativeSignIn.requireLoopbackRedirect("http://localhost:1/cb") }
        assertThrows(IllegalArgumentException::class.java) { HermesNativeSignIn.requireLoopbackRedirect("https://127.0.0.1:1/cb") }
        assertThrows(IllegalArgumentException::class.java) { HermesNativeSignIn.requireLoopbackRedirect("http://10.0.0.2:1/cb") }
        HermesNativeSignIn.requireLoopbackRedirect("http://127.0.0.1:1/cb")
        assertThrows(IllegalArgumentException::class.java) { HermesDashboardEndpoint.parse("http://192.168.1.5:9119") }
        assertThrows(IllegalArgumentException::class.java) { HermesDashboardEndpoint.parse("https://user:pw@h.example") }
        assertThrows(IllegalArgumentException::class.java) { HermesDashboardEndpoint.parse("https://h.example/?token=x") }
        HermesDashboardEndpoint.parse("http://100.101.102.103:9119")
        HermesDashboardEndpoint.parse("http://127.0.0.1:9119")
        assertThrows(IllegalArgumentException::class.java) { HermesDashboardEndpoint.parse("http://100.200.1.1:9119") }
    }

    @Test
    fun `callback parser checks path and state`() {
        val path = HermesNativeSignIn.CALLBACK_PATH
        assertEquals(null, LoopbackCallbackParser.parse("/favicon.ico", path, "s1"))
        assertEquals(LoopbackCallbackResult.Code("c+1"), LoopbackCallbackParser.parse("$path?code=c%2B1&state=s1", path, "s1"))
        assertEquals(LoopbackCallbackResult.Failed("state_mismatch"), LoopbackCallbackParser.parse("$path?code=c&state=s2", path, "s1"))
        assertEquals(LoopbackCallbackResult.Failed("code_missing"), LoopbackCallbackParser.parse("$path?state=s1", path, "s1"))
        assertEquals(LoopbackCallbackResult.Failed("authorization_error"), LoopbackCallbackParser.parse("$path?error=x", path, "s1"))
    }

    @Test
    fun `loopback server receives the browser redirect on 127_0_0_1`() {
        val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        val executor = Executors.newSingleThreadExecutor()
        LoopbackCallbackServer().use { server ->
            assertTrue(server.redirectUri.startsWith("http://127.0.0.1:"))
            HermesNativeSignIn.requireLoopbackRedirect(server.redirectUri)
            val pending = executor.submit<LoopbackCallbackResult> { server.awaitCallback("state-1", 10_000) }
            val base = server.redirectUri.substringBefore(HermesNativeSignIn.CALLBACK_PATH)
            http.newCall(Request.Builder().url("$base/favicon.ico").build()).execute().use { assertEquals(404, it.code) }
            http.newCall(Request.Builder().url("${server.redirectUri}?code=gw-code&state=state-1").build()).execute().use {
                assertEquals(200, it.code)
                assertTrue(it.body!!.string().contains("Signed in"))
            }
            assertEquals(LoopbackCallbackResult.Code("gw-code"), pending.get(5, TimeUnit.SECONDS))
        }
        LoopbackCallbackServer().use { server ->
            assertEquals(LoopbackCallbackResult.Failed("timeout"), server.awaitCallback("s", 200))
        }
        executor.shutdownNow()
    }

    @Test
    fun `bearer session never prints tokens`() {
        val session = HermesBearerSession.fromJson(
            """{"access_token":"AT-secret","refresh_token":"RT-secret","token_type":"Bearer","expires_at":123,"provider":"basic","user_id":"jun"}""")
        assertFalse(session.toString().contains("secret"))
        assertEquals(session, HermesBearerSession.fromJson(session.toJson()))
        assertThrows(HermesProtocolException::class.java) { HermesBearerSession.fromJson("""{"refresh_token":"x"}""") }
    }
}
