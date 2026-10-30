package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.*
import com.rumi.hermesvoice.core.net.*
import com.rumi.hermesvoice.core.settings.WatchSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

class LiveContractTest {
    @Test fun independentOriginMatrixRoundTripsAndStrictlyRejectsWrongTypes() {
        for (phone in listOf(false,true)) for (watch in listOf(false,true)) {
            val s = WatchSettings(phoneLiveEnabled=phone, watchLiveEnabled=watch)
            val restored = WatchSettings.fromJson(s.toJson())
            assertEquals(phone, restored.liveEnabled(VoiceOrigin.PHONE)); assertEquals(watch, restored.liveEnabled(VoiceOrigin.WATCH))
        }
        assertNull(WatchSettings.parse("{\"phone_live_enabled\":\"true\"}"))
        assertNull(WatchSettings.parse("{\"watch_live_enabled\":1}"))
        assertFalse(WatchSettings.fromJson("{}").phoneLiveEnabled); assertFalse(WatchSettings.fromJson("{}").watchLiveEnabled)
    }
    @Test fun liveSdpUsesExistingAuthenticatedHostBoundaryAndPreservesCrlf() = runBlocking {
        val server=MockWebServer(); server.start()
        try {
            val tokens=InMemoryHermesTokenStore(); tokens.save(HermesBearerSession("synthetic-at","synthetic-rt",null,"basic","test"))
            val client=HermesDashboardClient(HermesDashboardEndpoint.parse(server.url("/").toString()),OkHttpClient(),tokens)
            val offer="v=0\r\no=synthetic\r\n"; val answer="v=0\r\na=synthetic\r\n"
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody(JSONObject().put("ok",true).put("session",JSONObject().put("id","p"))
                .put("transport",JSONObject().put("type","webrtc").put("sdp",answer)).toString()))
            assertEquals("p" to answer, client.createLiveSession(offer))
            val request=server.takeRequest()
            assertEquals("/api/audio/voice-live/session", request.requestUrl!!.encodedPath)
            assertEquals("Bearer synthetic-at",request.getHeader("Authorization"))
            val j=JSONObject(request.body.readUtf8()); assertEquals(offer,j.getString("sdp"))
            assertEquals(setOf("sdp","history"),j.keySet())
        } finally { server.shutdown() }
    }
    @Test fun noAuthOrMalformedAnswerDoesNotInventAConnectionOrRetry() = runBlocking {
        val server=MockWebServer(); server.start()
        try {
            val tokens=InMemoryHermesTokenStore()
            val client=HermesDashboardClient(HermesDashboardEndpoint.parse(server.url("/").toString()),OkHttpClient(),tokens)
            try { client.createLiveSession("v=0\r\n"); fail("auth required") } catch (_: HermesAuthRequiredException) { }
            assertEquals(0,server.requestCount)
            tokens.save(HermesBearerSession("a","r",null,"basic","test"))
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            try { client.createLiveSession("v=0\r\n"); fail("invalid answer") } catch (_: HermesProtocolException) { }
            assertEquals(1,server.requestCount)
        } finally { server.shutdown() }
    }
}
