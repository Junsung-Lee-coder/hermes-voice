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
    val store: KeyValueStore = InMemoryKeyValueStore(),
    /** The device-local file (excluded from backup and transfer) where the later-reply consent lives, like PhoneApp.localStore. */
    val localStore: KeyValueStore = InMemoryKeyValueStore(),
    /** Where the orchestrator follows later replies of delivered turns (null: the production default off). */
    laterScope: kotlinx.coroutines.CoroutineScope? = null,
    laterWindowMs: Long = 60_000L,
    voiceListener: com.rumi.hermesvoice.core.voice.VoiceTurnListener = object : com.rumi.hermesvoice.core.voice.VoiceTurnListener {},
    wakeListening: (VoiceOrigin) -> Boolean = { false },
    laterWork: suspend (suspend () -> Unit) -> Unit = { it() },
    laterDeferMaxMs: Long = com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator.LATER_DEFER_MAX_MS,
    /** The process's microphones and later-reply speaker (PhoneApp.audio). */
    val ownership: com.rumi.hermesvoice.core.voice.AudioOwnership = com.rumi.hermesvoice.core.voice.AudioOwnership(),
    laterSpeaker: (VoiceOrigin, Boolean) -> Unit = { _, _ -> },
    runtimeSetupBudgetMs: Long = com.rumi.hermesvoice.core.net.GatewayConversationPort.RUNTIME_SETUP_BUDGET_MS,
    /** The routing session's model setup limits: production's, with [runtimeSetupBudgetMs] as the total, unless given. */
    runtimeSetupLimits: com.rumi.hermesvoice.core.net.RuntimeSetupLimits =
        com.rumi.hermesvoice.core.net.RuntimeSetupLimits(budgetMs = runtimeSetupBudgetMs),
) : AutoCloseable {
    val http: OkHttpClient = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
    val tokens = InMemoryHermesTokenStore(HermesBearerSession(fake.accessToken, fake.refreshToken, null, "basic", "jun"))
    val settings = AppSettings(store).apply { dashboardUrl = fake.baseUrl }
    val registry = OwnedSessionRegistry(store)

    /** "Speak later replies", read through the same class and store kind as PhoneApp (off unless set here). */
    val laterConsent = com.rumi.hermesvoice.core.background.LaterReplyConsent(localStore)

    /** Added to the wall clock the wake claim leases run on, so a test can let time pass. */
    @Volatile var clockOffsetMs = 0L
    private val wiring = HermesVoiceCore.connect(HermesDashboardEndpoint.parse(fake.baseUrl), http, tokens, registry, settings,
        voiceListener, clock = { System.currentTimeMillis() + clockOffsetMs }, laterScope = laterScope, laterWindowMs = laterWindowMs,
        laterWork = laterWork, wakeListening = wakeListening, laterDeferMaxMs = laterDeferMaxMs,
        laterEnabled = { laterConsent.enabled }, ownership = ownership, laterSpeaker = laterSpeaker,
        runtimeSetupLimits = runtimeSetupLimits)
    val core: HermesVoiceCore = wiring.first

    override fun close() {
        stop()
        fake.close()
    }

    /** The app process ends; the dashboard and the stored preferences stay. */
    fun stop() = wiring.second.close()
}
