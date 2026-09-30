package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.HermesBearerSession
import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.auth.InMemoryHermesTokenStore
import com.rumi.hermesvoice.core.net.ArchivedFilter
import com.rumi.hermesvoice.core.net.HermesDashboardClient
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the emulator-found NetworkOnMainThreadException: callers run on Android's main
 * dispatcher, so no response body may be read on the caller's thread. OkHttp reports
 * `responseBodyEnd` on the thread that consumed the body.
 */
class NetworkOffCallerThreadTest {
    private val fake = FakeHermesDashboard()
    private val readers: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val http = OkHttpClient.Builder().eventListener(object : EventListener() {
        override fun responseBodyEnd(call: Call, byteCount: Long) { readers += Thread.currentThread().name }
    }).build()
    private val main = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }

    @After fun tearDown() {
        main.shutdownNow()
        fake.close()
    }

    @Test
    fun `dashboard responses are never read on the calling thread`() = runBlocking {
        val tokens = InMemoryHermesTokenStore(HermesBearerSession("AT-1", "RT-1", null, "basic", "jun"))
        val client = HermesDashboardClient(HermesDashboardEndpoint.parse(fake.baseUrl), http, tokens)
        fake.rejectAccessTokens = setOf("AT-1") // also exercises the refresh path
        withContext(main.asCoroutineDispatcher()) {
            client.speak("hello")
            client.mintWsTicket()
            client.listSessions("recorder-phone", ArchivedFilter.EXCLUDE, 10, 0)
        }
        assertEquals(1, fake.refreshes)
        assertTrue(readers.toString(), readers.size >= 4 && readers.none { it == "fake-main" })
    }
}
