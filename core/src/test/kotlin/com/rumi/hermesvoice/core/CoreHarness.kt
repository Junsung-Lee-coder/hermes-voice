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
class CoreHarness(
    /** Pass an earlier harness's [fake] and [store] to model an app restart against the same dashboard. */
    val fake: FakeHermesDashboard = FakeHermesDashboard(),
    val store: InMemoryKeyValueStore = InMemoryKeyValueStore(),
) : AutoCloseable {
    val http: OkHttpClient = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
    val tokens = InMemoryHermesTokenStore(HermesBearerSession(fake.accessToken, fake.refreshToken, null, "basic", "jun"))
    val settings = AppSettings(store).apply { dashboardUrl = fake.baseUrl }
    val registry = OwnedSessionRegistry(store)
    private val wiring = HermesVoiceCore.connect(HermesDashboardEndpoint.parse(fake.baseUrl), http, tokens, registry, settings)
    val core: HermesVoiceCore = wiring.first

    override fun close() {
        stop()
        fake.close()
    }

    /** The app process ends; the dashboard and the stored preferences stay. */
    fun stop() = wiring.second.close()
}
