package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.net.ArchivedFilter
import com.rumi.hermesvoice.core.net.GatewayConversationPort
import com.rumi.hermesvoice.core.net.GatewayRpc
import com.rumi.hermesvoice.core.net.GatewaySubscription
import com.rumi.hermesvoice.core.net.HistoryPage
import com.rumi.hermesvoice.core.net.RuntimeSetupLimits
import com.rumi.hermesvoice.core.net.StoredSession
import com.rumi.hermesvoice.core.net.StoredSessionPage
import com.rumi.hermesvoice.core.sessions.AppSessionRepository
import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.sessions.HermesSessionsApi
import com.rumi.hermesvoice.core.sessions.OwnedRole
import com.rumi.hermesvoice.core.sessions.OwnedSession
import com.rumi.hermesvoice.core.sessions.OwnedSessionRegistry
import com.rumi.hermesvoice.core.sessions.RouterModelException
import com.rumi.hermesvoice.core.sessions.RouterRuntime
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.RoutingContract
import com.rumi.hermesvoice.core.voice.TurnRouting
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B34-R1: the routing session's model switch when the router session is COLD. tui_gateway (read-only
 * source): a cold `session.resume` returns `_lazy_resume_info` and only schedules the agent build; a
 * live resume without an agent returns `_fallback_session_info`; `config.set` with an explicit
 * provider does not wait for the build and only pins the override in memory, which the build then
 * ignores (it prefers the stored runtime). The app must wait for a BUILT agent (`_session_info`:
 * "reasoning_effort", no "lazy") within one bounded budget, then switch and read back.
 */
class RouterColdStartTest {
    private val profileDefault = "gpt-6.1-sol"

    private class Api : HermesSessionsApi {
        override suspend fun listSessions(source: String, archived: ArchivedFilter, limit: Int, offset: Int): StoredSessionPage = error("unused")
        override suspend fun getSession(sessionId: String): StoredSession? = error("unused")
        override suspend fun getMessages(sessionId: String, limit: Int, offset: Int): HistoryPage = error("unused")
        override suspend fun setArchived(sessionId: String, archived: Boolean) = error("unused")
    }

    /**
     * A router session as the frozen source handles it. [resumesBeforeBuild]: live resumes that still find no agent
     * (null: the build never finishes); [buildAfterMs]: or the build finishes this long after the cold resume.
     */
    private class ColdGateway(
        var storedModel: String, var storedProvider: String, var storedEffort: String, val profileDefault: String,
        val resumesBeforeBuild: Int? = 1, val buildAfterMs: Long? = null, val callDelayMs: Long = 0,
    ) : GatewayRpc {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var live = false
        @Volatile var agentBuilt = false
        @Volatile var agentModel = ""
        @Volatile var agentProvider = ""
        @Volatile var agentEffort = ""
        @Volatile var pinnedWithoutAgent = 0
        @Volatile var configOutcome = "ok"
        @Volatile var dropOnResume = -1
        private var liveResumes = 0
        private var coldAt = 0L

        private fun build() {
            agentBuilt = true; agentModel = storedModel; agentProvider = storedProvider; agentEffort = storedEffort
        }

        override suspend fun call(method: String, params: JSONObject, timeoutMs: Long): JSONObject {
            if (callDelayMs > 0) delay(callDelayMs)
            calls += method
            return when (method) {
                "session.resume" -> {
                    if (dropOnResume >= 0 && calls.count { it == "session.resume" } > dropOnResume) throw HermesProtocolException("gateway closed (1011)")
                    val info = JSONObject()
                    // _resume_response's default status for the cold resume; _session_live_status for the live record.
                    var status = "idle"
                    if (!live) {
                        live = true
                        coldAt = System.nanoTime()
                        info.put("model", storedModel).put("lazy", true).put("tools", JSONObject()).put("skills", JSONObject())
                        if (storedProvider.isNotEmpty()) info.put("provider", storedProvider)
                    } else {
                        if (!agentBuilt) {
                            liveResumes += 1
                            val byCount = resumesBeforeBuild != null && buildAfterMs == null && liveResumes > resumesBeforeBuild
                            val byTime = buildAfterMs != null && (System.nanoTime() - coldAt) / 1_000_000 >= buildAfterMs
                            if (byCount || byTime) build()
                        }
                        if (agentBuilt) info.put("model", agentModel).put("provider", agentProvider).put("reasoning_effort", agentEffort).put("running", false)
                        else info.put("model", profileDefault).put("lazy", true).put("tools", JSONObject()).put("skills", JSONObject()).also { status = "starting" }
                    }
                    JSONObject().put("session_id", "rt-router").put("session_key", "s-router").put("info", info).put("queued", JSONObject.NULL)
                        .put("running", false).put("status", status)
                }
                "config.set" -> {
                    val value = params.getString("value")
                    val model = value.substringBefore(' ')
                    when (configOutcome) {
                        "refused" -> throw HermesRpcException(5001, "Model switch failed")
                        "confirm" -> return JSONObject().put("key", "model").put("value", model).put("confirm_required", true).put("scope", "session")
                        "deferred" -> return JSONObject().put("key", "model").put("value", model).put("deferred", true).put("scope", "session")
                    }
                    if (agentBuilt) {
                        agentModel = if (configOutcome == "wrong_model") "some-other-model" else model
                        agentProvider = "openai-codex"
                        agentEffort = if (configOutcome == "wrong_effort") "high" else "low"
                        storedModel = agentModel; storedProvider = agentProvider; storedEffort = agentEffort
                    } else {
                        pinnedWithoutAgent += 1
                    }
                    JSONObject().put("key", "model").put("value", model).put("warning", "").put("confirm_required", false)
                        .put("confirm_message", "").put("scope", "session")
                }
                else -> error("unexpected $method")
            }
        }

        override fun subscribe(sessionId: String): GatewaySubscription = error("unused")
    }

    private fun registryWithRouter(spec: String = ""): OwnedSessionRegistry = OwnedSessionRegistry(InMemoryKeyValueStore()).apply {
        put(OwnedSession("s-router", OwnedRole.ROUTER, "Hermes Voice router", "", "", archived = false, createdAtMs = 1,
            contract = RoutingContract.VERSION, modelSpec = spec))
    }

    private fun repo(gateway: GatewayRpc, registry: OwnedSessionRegistry, lines: MutableList<String>, budgetMs: Long = 10_000,
                     limits: RuntimeSetupLimits = RuntimeSetupLimits(budgetMs = budgetMs)) =
        AppSessionRepository(Api(), GatewayConversationPort(null, limits) { gateway }, registry,
            routerRuntime = RouterRuntime.LUNA_LOW, diagnostic = { lines += it })

    private fun failure(block: suspend () -> Unit): Throwable? = runCatching { runBlocking { block() } }.exceptionOrNull()

    @Test
    fun `a cold router made by an earlier build waits for its agent, then switches it and reads back Luna low`() {
        val gateway = ColdGateway(profileDefault, "openai-codex", "high", profileDefault, resumesBeforeBuild = 3)
        val registry = registryWithRouter()
        val lines = mutableListOf<String>()
        runBlocking { repo(gateway, registry, lines).ensureRoutingSession() }
        assertEquals(RouterRuntime.LUNA_LOW.key, registry.router()!!.modelSpec)
        assertEquals(List(5) { "session.resume" } + listOf("config.set", "session.resume"), gateway.calls.toList())
        assertEquals(0, gateway.pinnedWithoutAgent)
        assertEquals(listOf("gpt-5.6-luna", "openai-codex", "low"), listOf(gateway.agentModel, gateway.agentProvider, gateway.agentEffort))
        assertTrue(lines.toString(), lines.single().startsWith("router_model=switched spec=gpt-5.6-luna|openai-codex|low build_polls=4 "))
    }

    @Test
    fun `a cold router already switched to Luna low (by the parent) is waited for, read back and recorded without a switch`() {
        val gateway = ColdGateway("gpt-5.6-luna", "openai-codex", "low", profileDefault, resumesBeforeBuild = 2)
        val registry = registryWithRouter()
        val lines = mutableListOf<String>()
        runBlocking { repo(gateway, registry, lines).ensureRoutingSession() }
        assertEquals(RouterRuntime.LUNA_LOW.key, registry.router()!!.modelSpec)
        assertFalse(gateway.calls.contains("config.set"))
        assertTrue(lines.single().startsWith("router_model=already"))
    }

    @Test
    fun `control - a live router with a built agent switches at once`() {
        val gateway = ColdGateway(profileDefault, "openai-codex", "high", profileDefault)
        gateway.live = true
        gateway.agentBuilt = true; gateway.agentModel = profileDefault; gateway.agentProvider = "openai-codex"; gateway.agentEffort = "high"
        val registry = registryWithRouter()
        val lines = mutableListOf<String>()
        runBlocking { repo(gateway, registry, lines).ensureRoutingSession() }
        assertEquals(listOf("session.resume", "config.set", "session.resume"), gateway.calls.toList())
        assertTrue(lines.single().contains("build_polls=0"))
    }

    @Test
    fun `a build finishing just inside the budget succeeds, one finishing just after it fails as not ready without a switch`() {
        // Budget 3 s, polls every 750 ms: a build finishing at ~1.6 s is seen; one at ~6 s never is. The switch reserve is
        // shortened to fit this budget (production keeps 5 s; RouterFailedBuildTest covers the reserve itself).
        val early = ColdGateway(profileDefault, "openai-codex", "high", profileDefault, buildAfterMs = 1_600)
        val earlyRegistry = registryWithRouter()
        val short = RuntimeSetupLimits(budgetMs = 3_000, switchReserveMs = 500, switchReadbackMs = 200)
        runBlocking { repo(early, earlyRegistry, mutableListOf(), limits = short).ensureRoutingSession() }
        assertEquals(RouterRuntime.LUNA_LOW.key, earlyRegistry.router()!!.modelSpec)
        val late = ColdGateway(profileDefault, "openai-codex", "high", profileDefault, buildAfterMs = 6_000)
        val lateRegistry = registryWithRouter()
        val started = System.nanoTime()
        val error = failure { repo(late, lateRegistry, mutableListOf(), budgetMs = 3_000).ensureRoutingSession() }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("$error", error is RouterModelException && error.reason == "not_ready")
        assertTrue("bounded by the budget: $ms ms", ms in 2_000..4_000)
        assertFalse(late.calls.contains("config.set"))
        assertEquals("", lateRegistry.router()!!.modelSpec)
    }

    @Test
    fun `a router whose agent never builds fails visibly as not ready within the total budget, slow calls included, and sends no switch`() {
        for (callDelay in listOf(0L, 400L)) {
            val gateway = ColdGateway(profileDefault, "openai-codex", "high", profileDefault, resumesBeforeBuild = null, callDelayMs = callDelay)
            val registry = registryWithRouter()
            val lines = mutableListOf<String>()
            val started = System.nanoTime()
            val error = failure { repo(gateway, registry, lines, budgetMs = 2_000).ensureRoutingSession() }
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue("$callDelay: $error", error is RouterModelException && error.reason == "not_ready" && error.message!!.contains("router model"))
            assertTrue("$callDelay: total $ms ms within the 2 s budget plus slack", ms < 3_000)
            assertFalse(gateway.calls.contains("config.set"))
            assertEquals(0, gateway.pinnedWithoutAgent)
            assertEquals("", registry.router()!!.modelSpec)
            assertEquals(listOf("router_model=failed reason=not_ready stage=poll write=none"), lines)
        }
    }

    @Test
    fun `a request cancelled while the router builds sends no switch, records nothing, and the next request proceeds`() {
        val gateway = ColdGateway(profileDefault, "openai-codex", "high", profileDefault, resumesBeforeBuild = null)
        val registry = registryWithRouter()
        val repository = repo(gateway, registry, mutableListOf(), budgetMs = 30_000)
        val job = CoroutineScope(Dispatchers.Default).async { repository.ensureRoutingSession() }
        runBlocking { delay(1_000) }
        job.cancel()
        val error = runBlocking { runCatching { job.await() }.exceptionOrNull() }
        assertTrue("$error", error is CancellationException)
        assertFalse(gateway.calls.contains("config.set"))
        assertEquals("", registry.router()!!.modelSpec)
        // The same repository's lock was released: once the agent is built, the next request runs and switches.
        gateway.agentModel = profileDefault; gateway.agentProvider = "openai-codex"; gateway.agentEffort = "high"; gateway.agentBuilt = true
        val outcome = runBlocking { withTimeoutOrNull(10_000) { repository.ensureRoutingSession() } }
        assertEquals(RouterRuntime.LUNA_LOW.key, outcome!!.modelSpec)
        assertEquals(1, gateway.calls.count { it == "config.set" })
    }

    @Test
    fun `a connection lost while waiting fails at once as transport, with no switch`() {
        val gateway = ColdGateway(profileDefault, "openai-codex", "high", profileDefault, resumesBeforeBuild = null)
        gateway.dropOnResume = 2
        val registry = registryWithRouter()
        val started = System.nanoTime()
        val error = failure { repo(gateway, registry, mutableListOf()).ensureRoutingSession() }
        assertTrue("$error", error is RouterModelException && error.reason == "transport")
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        assertFalse(gateway.calls.contains("config.set"))
    }

    @Test
    fun `after the build, a refusal, confirmation, deferral or a wrong readback still fails closed and records nothing`() {
        for ((outcome, reason) in listOf("refused" to "refused", "confirm" to "confirmation_required", "deferred" to "deferred",
                "wrong_model" to "readback", "wrong_effort" to "readback")) {
            val gateway = ColdGateway(profileDefault, "openai-codex", "high", profileDefault, resumesBeforeBuild = 1)
            gateway.configOutcome = outcome
            val registry = registryWithRouter()
            val error = failure { repo(gateway, registry, mutableListOf()).ensureRoutingSession() }
            assertTrue("$outcome: $error", error is RouterModelException && error.reason == reason)
            assertEquals(outcome, "", registry.router()!!.modelSpec)
            assertEquals(outcome, 1, gateway.calls.count { it == "config.set" })
        }
    }

    @Test
    fun `readiness is a built agent's shape - lazy shapes, a bare model or missing fields are never taken for one`() {
        val init = { info: JSONObject? -> GatewayConversationPort.initialized(info) }
        assertFalse(init(null))
        assertFalse("bare model", init(JSONObject().put("model", "gpt-5.6-luna").put("provider", "openai-codex")))
        assertFalse("_lazy_resume_info", init(JSONObject().put("model", "m").put("lazy", true).put("tools", JSONObject())))
        assertFalse("_fallback_session_info", init(JSONObject().put("model", "m").put("lazy", true).put("skills", JSONObject())))
        assertFalse("lazy wins", init(JSONObject().put("lazy", true).put("reasoning_effort", "low").put("running", false)))
        assertFalse("_cwd_info lazy shape", init(JSONObject().put("cwd", "/w").put("lazy", true)))
        assertFalse("empty info", init(JSONObject()))
        assertTrue("_session_info, effort unset", init(JSONObject().put("model", "m").put("reasoning_effort", "").put("running", false)))
        assertTrue("effort, no lazy", init(JSONObject().put("model", "m").put("reasoning_effort", "low")))
        assertTrue(init(JSONObject().put("model", "m").put("provider", "p").put("reasoning_effort", "low").put("running", true)))
    }

    // ── through the production core, against the gateway test double in its real cold mode ─────────

    private val create = """{"action":"create","title":"Garden plans","alias":"garden","description":"Plants","ack":"x"}"""

    private fun coldRouterHarness(stored: FakeHermesDashboard.Runtime, resumesBeforeBuild: Int, budgetMs: Long = 10_000): Pair<CoreHarness, String> {
        val h = CoreHarness(runtimeSetupBudgetMs = budgetMs)
        val old = h.fake.addStoredSession(AppSources.ROUTER, "Hermes Voice router", hidden = true) { listOf(FakeHermesDashboard.complete(create)) }
        h.fake.runtimes[old] = stored
        h.fake.coldResumesBeforeBuild = { if (it == old) resumesBeforeBuild else null }
        h.registry.put(OwnedSession(old, OwnedRole.ROUTER, "Hermes Voice router", "", "", archived = false, createdAtMs = 1,
            contract = RoutingContract.VERSION))
        return h to old
    }

    private fun phoneTurn(h: CoreHarness, id: String, routing: TurnRouting = TurnRouting.Model): Pair<VoiceTurnOutcome?, List<String>> {
        val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val outcome = runBlocking {
            withTimeoutOrNull(30_000) {
                h.core.orchestrator.run(VoiceTurnRequest(id, VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav",
                    PlaybackSink { audio, cue -> played += "${cue.role}:${h.fake.decodeSpoken(audio)}" }, routing = routing))
            }
        }
        return outcome to played
    }

    @Test
    fun `the first voice request after installing reaches a cold old router, switches it and is routed and answered once`() {
        val (h, old) = coldRouterHarness(FakeHermesDashboard.Runtime(profileDefault, "openai-codex", "high"), resumesBeforeBuild = 2)
        h.use {
            val (outcome, played) = phoneTurn(h, "cold-e2e-1")
            assertTrue("$outcome", outcome is VoiceTurnOutcome.Completed)
            val made = h.registry.all().single { it.role == OwnedRole.CONVERSATION }.storedSessionId
            assertEquals(FakeHermesDashboard.Runtime("gpt-5.6-luna", "openai-codex", "low"), h.fake.turnModels.single { it.first == old }.second)
            assertTrue("no switch reached a session without an agent", h.fake.switchesWithoutAgent.isEmpty())
            assertEquals("transcript submitted once", 1, h.fake.prompts.count { it.first == made })
            assertEquals(1, h.fake.rpcLog.toList().count { it.optString("method") == "config.set" })
            assertEquals(h.fake.profileDefault, h.fake.turnModels.single { it.first == made }.second)
            assertTrue(h.fake.globalConfigWrites.isEmpty())
            assertEquals(2, played.size)
        }
    }

    @Test
    fun `a cold router the parent already set to Luna low is routed on it without any switch`() {
        val (h, old) = coldRouterHarness(FakeHermesDashboard.Runtime("gpt-5.6-luna", "openai-codex", "low"), resumesBeforeBuild = 1)
        h.use {
            assertTrue(phoneTurn(h, "cold-e2e-2").first is VoiceTurnOutcome.Completed)
            assertTrue(h.fake.rpcLog.toList().none { it.optString("method") == "config.set" })
            assertEquals(FakeHermesDashboard.Runtime("gpt-5.6-luna", "openai-codex", "low"), h.fake.turnModels.single { it.first == old }.second)
        }
    }

    @Test
    fun `a router that never builds fails the request visibly within the budget, sends nothing, and routing off still works`() {
        val (h, old) = coldRouterHarness(FakeHermesDashboard.Runtime(profileDefault, "openai-codex", "high"), resumesBeforeBuild = -1, budgetMs = 2_000)
        h.use {
            val started = System.nanoTime()
            val (outcome, played) = phoneTurn(h, "cold-e2e-3")
            assertTrue((System.nanoTime() - started) / 1_000_000 < 6_000)
            assertTrue("$outcome", outcome is VoiceTurnOutcome.NotDelivered && outcome.reason.contains("router model") && outcome.reason.contains("not_ready"))
            assertTrue(played.isEmpty())
            assertTrue(h.fake.prompts.isEmpty())
            assertTrue(h.fake.rpcLog.toList().none { it.optString("method") == "config.set" })
            // Routing off never touches the router.
            val direct = runBlocking { h.core.sessions.createConversation("Notes", "notes", "notes") }
            val resumesBefore = h.fake.rpcLog.toList().count { it.optString("method") == "session.resume" && it.getJSONObject("params").optString("session_id") == old }
            val (off, _) = phoneTurn(h, "cold-e2e-4", TurnRouting.Direct(direct.storedSessionId))
            assertTrue("$off", off is VoiceTurnOutcome.Completed)
            assertEquals(resumesBefore, h.fake.rpcLog.toList().count { it.optString("method") == "session.resume" && it.getJSONObject("params").optString("session_id") == old })
        }
    }

    // ── B34-N3: the drop fixtures close the socket on purpose (a close frame, not a library crash) ───

    @Test
    fun `a lost create answer and a lost config answer both end promptly as intended (graceful close frame)`() {
        CoreHarness().use { h ->
            h.fake.createBehavior = { p -> if (p.optString("source") == AppSources.CONVERSATION) "drop_after_create" else "ok" }
            h.fake.sourceScripts[AppSources.ROUTER] = { listOf(FakeHermesDashboard.complete(create)) }
            val started = System.nanoTime()
            val (outcome, _) = phoneTurn(h, "drop-create-1")
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
            assertTrue("$outcome", outcome is VoiceTurnOutcome.NotDelivered && outcome.reason.contains("create_ambiguous"))
        }
        CoreHarness().use { h ->
            val old = h.fake.addStoredSession(AppSources.ROUTER, "Hermes Voice router", hidden = true) { listOf(FakeHermesDashboard.complete(create)) }
            h.registry.put(OwnedSession(old, OwnedRole.ROUTER, "Hermes Voice router", "", "", archived = false, createdAtMs = 1,
                contract = RoutingContract.VERSION))
            h.fake.modelSwitchBehavior = { "drop" }
            val started = System.nanoTime()
            val error = runCatching { runBlocking { h.core.sessions.ensureRoutingSession() } }.exceptionOrNull()
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
            // b36 (B35-N1): the switch was sent before the connection closed, so its effect is unknown (was "transport").
            assertTrue("$error", error is RouterModelException && error.reason == "outcome_unknown")
            assertFalse(error!!.message!!.contains("nothing was switched"))
        }
    }
}
