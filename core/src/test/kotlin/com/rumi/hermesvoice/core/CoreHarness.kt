package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.HermesBearerSession
import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.auth.InMemoryHermesTokenStore
import com.rumi.hermesvoice.core.sessions.OwnedSessionRegistry
import com.rumi.hermesvoice.core.settings.AppSettings
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * The production [HermesVoiceCore.connect] wiring (real OkHttp HTTP + WebSocket) against
 * [FakeHermesDashboard]. This is a contract double, NOT an authenticated Hermes E2E.
 */
class CoreHarness : AutoCloseable {
    val fake = FakeHermesDashboard()
    val http: OkHttpClient = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
    val tokens = InMemoryHermesTokenStore(HermesBearerSession("AT-1", "RT-1", null, "basic", "jun"))
    val store = InMemoryKeyValueStore()
    val settings = AppSettings(store).apply { dashboardUrl = fake.baseUrl }
    val registry = OwnedSessionRegistry(store)
    private val wiring = HermesVoiceCore.connect(HermesDashboardEndpoint.parse(fake.baseUrl), http, tokens, registry, settings)
    val core: HermesVoiceCore = wiring.first

    override fun close() {
        wiring.second.close()
        fake.close()
    }
}
