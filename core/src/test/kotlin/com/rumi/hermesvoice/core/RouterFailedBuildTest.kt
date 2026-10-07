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
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import com.rumi.hermesvoice.core.watchlink.LinkFrame
import com.rumi.hermesvoice.core.watchlink.PlayRequest
import com.rumi.hermesvoice.core.watchlink.PlayedAck
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchTransport
import com.rumi.hermesvoice.core.watchlink.WatchTurnUpload
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The routing session's model setup when its agent isn't built (b37 policy). SOURCE-MODELLED from the frozen
 * tui_gateway, not a live gateway. server._session_live_status reports a live record without an agent as "starting" while
 * a build runs and as "idle" both when its build completed and FAILED and when its build has NOT STARTED (a cold resume
 * only schedules it on a threading.Timer(0.05), and another client's lazy or hydrating record never starts it). No
 * read-only answer tells those two apart, so recovering a failed build is UNSUPPORTED (RECOVERY_UNSUPPORTED_PROTOCOL_
 * AMBIGUITY): an "idle" record gets no write at all and the request ends promptly as initialization_unconfirmed.
 *
 * POLICY DELTA from b36 (whose version of this file asserted one recovery switch to an "idle" record): every former
 * recovery expectation is now a zero-write initialization_unconfirmed; the rebuild-only cases (rebuild fails again,
 * rebuild keeps the stored effort, recovery reserve and timeouts) are gone with the path they tested. Kept: cold
 * pending builds waited for, the ordinary switch of a built agent with its 5 s reserve and readback, outcome_unknown
 * after a write, stage-named endings, cancellation, and the end-to-end first ACK/FINAL once the router is built.
 */
class RouterFailedBuildTest {
    private val luna = RouterRuntime.LUNA_LOW
    private val sentinel = "/home/sentinel-user/.hermes/profiles/x/SENTINEL-TOKEN-0000"

    private class Api : HermesSessionsApi {
        override suspend fun listSessions(source: String, archived: ArchivedFilter, limit: Int, offset: Int): StoredSessionPage = error("unused")
        override suspend fun getSession(sessionId: String): StoredSession? = error("unused")
        override suspend fun getMessages(sessionId: String, limit: Int, offset: Int): HistoryPage = error("unused")
        override suspend fun setArchived(sessionId: String, archived: Boolean) = error("unused")
    }

    /**
     * One router record as the frozen source keeps it: `agent_ready` set or not, build started or not, `agent_error`, and
     * _session_live_status from those ("starting" iff a build started and ready isn't set, else "idle" unless running).
     * [beforeRead] drives the build's timeline (timer firing, completing, failing) before each resume is answered.
     */
    private class Record(
        val stored: Triple<String, String, String> = Triple("gpt-6.1-sol", "openai-codex", "high"),
        val firstLive: Boolean = false, val callDelayMs: Long = 0,
    ) : GatewayRpc {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val configTimeouts: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        @Volatile var agent: Triple<String, String, String>? = null
        @Volatile var agentError: String? = null
        @Volatile var readySet = false
        @Volatile var buildStarted = false
        @Volatile var restarts = 0
        @Volatile var beforeRead: Record.(Int) -> Unit = {}

        /** "ok", "lose" (apply, then the connection breaks), "hang", "confirm", "deferred", "scope", "refuse". */
        @Volatile var configAnswer = "ok"
        @Volatile var refuseText = "Model switch failed"

        /** After a switch: "ok", "lost", "hang", "drift", "lazy". */
        @Volatile var readback = "ok"
        @Volatile var statusOverride: ((Int) -> Any?)? = null
        @Volatile var running: Any? = false
        @Volatile var lazyInfo = true
        @Volatile var driftAt = -1
        @Volatile var resumeDelayMs: (Int) -> Long = { 0 }
        private var reads = 0

        fun startBuild() { buildStarted = true }
        fun completeBuild(success: Boolean) {
            if (success) agent = stored else agentError = "agent init failed"
            readySet = true
        }

        override suspend fun call(method: String, params: JSONObject, timeoutMs: Long): JSONObject {
            if (callDelayMs > 0) delay(callDelayMs)
            calls += method
            return when (method) {
                "session.resume" -> {
                    val n = reads++
                    resumeDelayMs(n).takeIf { it > 0 }?.let { delay(it) }
                    beforeRead(n)
                    val afterSwitch = calls.contains("config.set")
                    if (afterSwitch) when (readback) {
                        "lost" -> throw HermesProtocolException("gateway connection lost")
                        "hang" -> awaitCancellation()
                    }
                    val drift = (afterSwitch && readback == "drift") || n == driftAt
                    val result = JSONObject().put("session_id", if (drift) "rt-other" else "rt-router").put("session_key", "s-router")
                    running?.let { result.put("running", it) }
                    val a = agent
                    val lazyNow = a == null || (afterSwitch && readback == "lazy")
                    result.put("info", if (!lazyNow) JSONObject().put("model", a!!.first).put("provider", a.second).put("reasoning_effort", a.third)
                        else JSONObject().put("model", "gpt-6.1-sol").put("tools", JSONObject()).apply { if (lazyInfo) put("lazy", true) })
                    val status: Any? = when {
                        n == 0 && !firstLive -> "idle"  // _resume_response default for the cold resume
                        statusOverride != null && lazyNow -> statusOverride!!(n)
                        !readySet && buildStarted -> "starting"
                        running == true -> "working"
                        else -> "idle"
                    }
                    status?.let { result.put("status", it) }
                    result
                }
                "config.set" -> {
                    configTimeouts += timeoutMs
                    val m = params.getString("value").substringBefore(' ')
                    when (configAnswer) {
                        "confirm" -> return JSONObject().put("key", "model").put("value", m).put("confirm_required", true).put("scope", "session")
                        "deferred" -> return JSONObject().put("key", "model").put("value", m).put("deferred", true).put("scope", "session")
                        "refuse" -> throw HermesRpcException(5001, refuseText)
                    }
                    // methods_config_set._set_model: failed_agent_init restarts the build; a built agent switches in place.
                    if (agent == null && agentError != null && readySet) restarts += 1
                    agent?.let { agent = Triple(m, "openai-codex", "low") }
                    when (configAnswer) {
                        "lose" -> throw HermesProtocolException("gateway connection lost")
                        "hang" -> awaitCancellation()
                    }
                    JSONObject().put("key", "model").put("value", m).put("confirm_required", false)
                        .put("scope", if (configAnswer == "scope") "global" else "session")
                }
                else -> error(method)
            }
        }

        override fun subscribe(sessionId: String): GatewaySubscription = error("unused")
        fun writes() = calls.count { it == "config.set" }
    }

    private fun failedLive() = Record(stored = Triple("gpt-5.6-luna", "openai-codex", "low"), firstLive = true).apply { startBuild(); completeBuild(false) }
    private fun builtLive() = Record(firstLive = true).apply { startBuild(); completeBuild(true) }

    private fun registry() = OwnedSessionRegistry(InMemoryKeyValueStore()).apply {
        put(OwnedSession("s-router", OwnedRole.ROUTER, "Hermes Voice router", "", "", archived = false, createdAtMs = 1, contract = RoutingContract.VERSION))
    }

    /** Short, deterministic limits (production: 90 s, 750 ms, 5 s reserve of which 2 s for the readback). */
    private val short = RuntimeSetupLimits(budgetMs = 8_000, pollMs = 100, switchReserveMs = 1_000, switchReadbackMs = 400)

    private class Repo(val repo: AppSessionRepository, val registry: OwnedSessionRegistry, val lines: MutableList<String>)

    private fun repo(rpc: suspend () -> GatewayRpc, limits: RuntimeSetupLimits = short): Repo {
        val reg = registry()
        val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())
        return Repo(AppSessionRepository(Api(), GatewayConversationPort(null, limits, rpc), reg, routerRuntime = luna, diagnostic = { lines += it }), reg, lines)
    }

    private fun repo(rpc: GatewayRpc, limits: RuntimeSetupLimits = short) = repo({ rpc }, limits)

    private class Ended(val error: Throwable?, val ms: Long) {
        val reason get() = (error as? RouterModelException)?.reason
        val text get() = error?.message.orEmpty()
    }

    private fun Repo.run(): Ended {
        val started = System.nanoTime()
        val error = runCatching { runBlocking { repo.ensureRoutingSession() } }.exceptionOrNull()
        return Ended(error, (System.nanoTime() - started) / 1_000_000)
    }

    // ── an unbuilt router: waited for while starting, never written to while idle ─────────────────

    @Test
    fun `a cold router whose build starts and completes is waited for, then switched once and read back`() {
        val rpc = Record().apply { beforeRead = { n -> if (n == 1) startBuild(); if (n == 4) completeBuild(true) } }
        val r = repo(rpc)
        assertEquals(null, r.run().error)
        assertEquals(List(5) { "session.resume" } + listOf("config.set", "session.resume"), rpc.calls.toList())
        assertEquals(luna.key, r.registry.router()!!.modelSpec)
        assertTrue(r.lines.single().startsWith("router_model=switched spec=gpt-5.6-luna|openai-codex|low build_polls=4 "))
        // Already Luna low once built: nothing sent.
        val already = Record(stored = Triple("gpt-5.6-luna", "openai-codex", "low")).apply { beforeRead = { n -> if (n == 1) { startBuild(); completeBuild(true) } } }
        assertEquals(null, repo(already).run().error)
        assertEquals(0, already.writes())
    }

    @Test
    fun `one idle read after the cold one is not yet a verdict - a build seen starting or built next is used`() {
        val late = Record().apply { beforeRead = { n -> if (n == 2) startBuild(); if (n == 3) completeBuild(true) } }
        assertEquals(null, repo(late).run().error)
        assertEquals(1, late.writes())
        // idle, starting, idle, starting... never two idle reads in a row: waited for, and switched once built.
        val flapping = Record().apply { beforeRead = { n -> buildStarted = n % 2 == 0; if (n == 7) completeBuild(true) } }
        assertEquals(null, repo(flapping).run().error)
        assertEquals(1, flapping.writes())
    }

    @Test
    fun `T1 policy - a healthy router whose build timer fires late gets no write and fails safely as initialization_unconfirmed, production limits`() {
        val rpc = Record(stored = Triple("gpt-5.6-luna", "openai-codex", "low")).apply { beforeRead = { n -> if (n == 3) startBuild(); if (n == 5 && !readySet) completeBuild(true) } }
        val r = repo(rpc, RuntimeSetupLimits())
        val ended = r.run()
        assertEquals("${ended.error}", "initialization_unconfirmed", ended.reason)
        assertEquals(0, rpc.writes())
        assertTrue("${ended.ms}", ended.ms < 3_000)
        assertEquals(listOf("router_model=failed reason=initialization_unconfirmed stage=poll write=none"), r.lines.toList())
        assertEquals("", r.registry.router()!!.modelSpec)
        // Its build ran meanwhile: the next request finds it built (already Luna low) and records it with no write.
        val next = r.run()
        assertEquals("${next.error}", null, next.error)
        assertEquals(0, rpc.writes())
        assertEquals(luna.key, r.registry.router()!!.modelSpec)
    }

    @Test
    fun `T2 policy - a live record whose build never starts gets no write and no claim of any rebuild`() {
        val rpc = Record(stored = Triple("gpt-5.6-luna", "openai-codex", "low"), firstLive = true)
        val r = repo(rpc)
        val ended = r.run()
        assertEquals("initialization_unconfirmed", ended.reason)
        assertEquals(0, rpc.writes())
        assertFalse(ended.text, Regex("rebuil|failed again|build failed").containsMatchIn(ended.text))
        assertTrue(ended.text.contains("nothing was switched"))
        assertTrue(ended.text.contains("your request was not sent"))
    }

    @Test
    fun `a router whose build completed and failed gets no write either - recovery is unsupported - and every request ends at once`() {
        val rpc = failedLive()
        val r = repo(rpc, RuntimeSetupLimits())
        val first = r.run()
        val second = r.run()
        assertEquals("initialization_unconfirmed", first.reason)
        assertEquals("initialization_unconfirmed", second.reason)
        assertEquals(0, rpc.writes())
        assertEquals(0, rpc.restarts)
        assertTrue("${first.ms} ${second.ms}", first.ms < 3_000 && second.ms < 3_000)
        assertEquals("", r.registry.router()!!.modelSpec)
        // If something outside the app gets it built later, the next request does the ordinary switch.
        rpc.agentError = null; rpc.agent = rpc.stored.copy(first = "gpt-6.1-sol", third = "high")
        assertEquals(null, r.run().error)
        assertEquals(1, rpc.writes())
    }

    @Test
    fun `no status, another status, a non-string status, no running flag or a non-lazy unbuilt info fail at once with no write`() {
        val cases = mapOf<String, (Record) -> Unit>(
            "no status" to { it.statusOverride = { null } }, "waiting" to { it.statusOverride = { "waiting" } },
            "working" to { it.statusOverride = { "working" } }, "number" to { it.statusOverride = { 1 } },
            "no running" to { it.running = null }, "running" to { it.running = true; it.statusOverride = { "idle" } },
            "not lazy" to { it.lazyInfo = false })
        for ((name, tweak) in cases) {
            val rpc = Record(firstLive = true).also(tweak)
            val ended = repo(rpc).run()
            assertEquals("$name: ${ended.error}", "build_state_unknown", ended.reason)
            assertEquals(name, 0, rpc.writes())
            assertTrue("$name: fast ${ended.ms}", ended.ms < 1_500)
        }
    }

    @Test
    fun `another live session answering while waiting stops before any switch`() {
        val rpc = Record().apply { driftAt = 1; beforeRead = { n -> if (n == 0) startBuild() } }
        val ended = repo(rpc).run()
        assertEquals("${ended.error}", "identity_changed", ended.reason)
        assertEquals(0, rpc.writes())
    }

    // ── the ordinary switch of a built agent: reserve, outcomes, guards, privacy ─────────────────

    @Test
    fun `production limits - a built agent with less than 5 s left gets no write, otherwise the switch may use the time left minus 2 s`() {
        val late = Record().apply { beforeRead = { n -> if (n == 1) startBuild(); if (n == 3) completeBuild(true) } }
        val b = repo(late, RuntimeSetupLimits(budgetMs = 6_000)).run()
        assertEquals("${b.error}", "insufficient_time", b.reason)
        assertEquals(0, late.writes())
        assertTrue(b.text.contains("nothing was switched"))
        val ok = builtLive()
        assertEquals(null, repo(ok, RuntimeSetupLimits()).run().error)
        assertTrue(ok.configTimeouts.toString(), ok.configTimeouts.single() in 85_000L..88_000L)
    }

    @Test
    fun `the old deadline edge - a built answer arriving about 100 ms before the end - sends no late write`() {
        val rpc = Record().apply { beforeRead = { n -> if (n == 1) { startBuild(); completeBuild(true) } }; resumeDelayMs = { if (it == 1) 1_800 else 0 } }
        val edge = RuntimeSetupLimits(budgetMs = 2_000, pollMs = 100, switchReserveMs = 1_000, switchReadbackMs = 400)
        val ended = repo(rpc, edge).run()
        assertEquals("${ended.error}", "insufficient_time", ended.reason)
        assertEquals(0, rpc.writes())
    }

    @Test
    fun `an applied switch whose answer or readback is lost, or whose readback shows no built model, is outcome_unknown`() {
        for (case in listOf("lose", "hang", "readback lost", "readback hang", "readback drift", "readback lazy")) {
            val rpc = builtLive()
            when (case) {
                "lose", "hang" -> rpc.configAnswer = case
                else -> rpc.readback = case.removePrefix("readback ")
            }
            val r = repo(rpc, short.copy(budgetMs = 3_000))
            val ended = r.run()
            assertEquals("$case: ${ended.error}", "outcome_unknown", ended.reason)
            assertFalse(case, ended.text.contains("nothing was switched"))
            assertTrue(case, ended.text.contains("unknown"))
            assertTrue(case, ended.ms < 4_000)
            assertEquals(case, "", r.registry.router()!!.modelSpec)
            assertTrue(case, r.lines.single().startsWith("router_model=failed reason=outcome_unknown "))
            assertTrue(case, r.lines.single().endsWith("write=switch"))
        }
    }

    @Test
    fun `after an applied but unanswered switch the next request reads it back and records it without a second switch`() {
        val rpc = builtLive().apply { configAnswer = "lose" }
        val r = repo(rpc)
        assertEquals("outcome_unknown", r.run().reason)
        rpc.configAnswer = "ok"
        assertEquals(null, r.run().error)
        assertEquals(1, rpc.writes())
        assertTrue(r.lines.last(), r.lines.last().startsWith("router_model=already "))
    }

    @Test
    fun `every answer guard fails closed and never confirms, and a refusal shows only its code - never the server's text`() {
        for ((answer, reason) in listOf("confirm" to "confirmation_required", "deferred" to "deferred", "scope" to "scope", "refuse" to "refused")) {
            val rpc = builtLive().apply { configAnswer = answer; refuseText = "Model switch failed: catalog at $sentinel unreadable" }
            val r = repo(rpc)
            val ended = r.run()
            assertEquals("$answer: ${ended.error}", reason, ended.reason)
            assertEquals(1, rpc.writes())
            assertEquals("", r.registry.router()!!.modelSpec)
            assertFalse(answer, ended.text.contains("SENTINEL") || ended.text.contains("/home/") || ended.text.contains("catalog"))
            assertTrue(answer, r.lines.none { it.contains("SENTINEL") || it.contains("/home/") })
            if (answer == "refuse") {
                assertTrue(ended.text, ended.text.contains("code 5001"))
                assertTrue(ended.text, ended.text.contains("unknown"))
                assertFalse(ended.text.contains("nothing was switched"))
            }
        }
    }

    // ── stages: connect, poll, and what a hang is called ─────────────────────────────────────────

    @Test
    fun `a server error while connecting or reading is named by its real stage and code only`() {
        val connect = repo({ throw HermesRpcException(4001, "session $sentinel not found") })
        val a = connect.run()
        assertEquals("rpc_4001", a.reason)
        assertEquals(listOf("router_model=failed reason=rpc_4001 stage=connect write=none"), connect.lines.toList())
        assertFalse(a.text.contains("SENTINEL"))
        val read = object : GatewayRpc {
            override suspend fun call(method: String, params: JSONObject, timeoutMs: Long): JSONObject = throw HermesRpcException(4007, "no $sentinel")
            override fun subscribe(sessionId: String): GatewaySubscription = error("unused")
        }
        val polled = repo(read)
        val b = polled.run()
        assertEquals(listOf("router_model=failed reason=rpc_4007 stage=poll write=none"), polled.lines.toList())
        assertFalse(b.text.contains("SENTINEL"))
        val broken = repo({ throw HermesProtocolException("gateway connection failed: $sentinel") })
        val c = broken.run()
        assertEquals("transport", c.reason)
        assertTrue(broken.lines.single().endsWith("stage=connect write=none"))
        assertFalse(c.text.contains("SENTINEL"))
    }

    @Test
    fun `hangs are cut by the one budget and named from their stage - a write already sent is outcome_unknown`() {
        val hangSwitch = builtLive().apply { configAnswer = "hang" }
        assertEquals("outcome_unknown", repo(hangSwitch, short.copy(budgetMs = 2_000)).run().reason)
        val b = repo({ awaitCancellation() }, short.copy(budgetMs = 1_000)).run()
        assertEquals("transport", b.reason)
        assertTrue(b.text.contains("couldn't be reached"))
        val hangRead = object : GatewayRpc {
            override suspend fun call(method: String, params: JSONObject, timeoutMs: Long): JSONObject = awaitCancellation()
            override fun subscribe(sessionId: String): GatewaySubscription = error("unused")
        }
        val c = repo(hangRead, short.copy(budgetMs = 1_000)).run()
        assertEquals("transport", c.reason)
        assertTrue(c.text.contains("didn't answer"))
        for (callDelay in listOf(0L, 150L)) {
            val starting = Record(callDelayMs = callDelay).apply { beforeRead = { n -> if (n == 0) startBuild() } }
            val d = repo(starting, short.copy(budgetMs = 1_500)).run()
            assertEquals("$callDelay", "not_ready", d.reason)
            assertTrue("$callDelay ${d.ms}", d.ms < 2_200)
            assertEquals(0, starting.writes())
        }
    }

    @Test
    fun `injected limits are validated - no busy polling, no empty budget, a readback share inside the reserve`() {
        for (bad in listOf<() -> RuntimeSetupLimits>({ RuntimeSetupLimits(pollMs = 0) }, { RuntimeSetupLimits(budgetMs = 0) },
                { RuntimeSetupLimits(budgetMs = -1) }, { RuntimeSetupLimits(switchReserveMs = 1_000, switchReadbackMs = 1_000) },
                { RuntimeSetupLimits(switchReadbackMs = 0) })) {
            assertTrue(runCatching { bad() }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(90_000L, RuntimeSetupLimits().budgetMs)
        assertEquals(5_000L, RuntimeSetupLimits().switchReserveMs)
    }

    // ── cancellation ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a caller cancelling while connecting, polling, switching or reading back is cancelled, records nothing and frees the lock`() {
        for (where in listOf("connect", "polling", "switch", "readback")) {
            val rpc = when (where) {
                "polling" -> Record().apply { beforeRead = { n -> if (n == 0) startBuild() } }
                "switch" -> builtLive().apply { configAnswer = "hang" }
                else -> builtLive().apply { readback = "hang" }
            }
            var hang = where == "connect"
            val r = repo({ if (hang) awaitCancellation(); rpc }, short.copy(budgetMs = 30_000))
            val job = CoroutineScope(Dispatchers.Default).async { r.repo.ensureRoutingSession() }
            runBlocking { delay(500) }
            val cancelAt = System.nanoTime()
            job.cancel()
            val error = runBlocking { runCatching { job.await() }.exceptionOrNull() }
            assertTrue("$where: $error", error is CancellationException)
            assertTrue(where, (System.nanoTime() - cancelAt) / 1_000_000 < 1_000)
            assertEquals(where, "", r.registry.router()!!.modelSpec)
            assertTrue("$where: ${r.lines}", r.lines.none { it.startsWith("router_model=") })
            hang = false
            val before = rpc.calls.size
            val next = CoroutineScope(Dispatchers.Default).async { r.repo.ensureRoutingSession() }
            runBlocking { delay(300) }
            assertTrue(where, rpc.calls.size > before)
            next.cancel()
        }
    }

    // ── through the production core and the MockWebServer gateway double ───────────────────────

    private val create = """{"action":"create","title":"Garden plans","alias":"garden","description":"Plants","ack":"x"}"""

    private fun failedRouter(h: CoreHarness, live: Boolean): String {
        val old = h.fake.addStoredSession(AppSources.ROUTER, "Hermes Voice router", hidden = true) { listOf(FakeHermesDashboard.complete(create)) }
        h.fake.runtimes[old] = FakeHermesDashboard.Runtime("gpt-6.1-sol", "openai-codex", "high")
        h.fake.coldResumesBeforeBuild = { if (it == old) 1 else null }
        h.fake.buildFailures = { if (it == old) 1 else 0 }
        if (live) h.fake.failedLiveBuild(old)
        h.registry.put(OwnedSession(old, OwnedRole.ROUTER, "Hermes Voice router", "", "", archived = false, createdAtMs = 1, contract = RoutingContract.VERSION))
        return old
    }

    private fun phoneTurn(h: CoreHarness, id: String, routing: TurnRouting = TurnRouting.Model): Pair<VoiceTurnOutcome?, List<String>> {
        val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val outcome = runBlocking {
            withTimeoutOrNull(20_000) {
                h.core.orchestrator.run(VoiceTurnRequest(id, VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav",
                    PlaybackSink { audio, cue -> played += "phone:${cue.role}:${h.fake.decodeSpoken(audio)}" }, routing = routing))
            }
        }
        return outcome to played
    }

    private inner class Watch(val h: CoreHarness) : WatchTransport {
        override val nodeId = "watch-node-1"
        val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun sendMessage(path: String, bytes: ByteArray) {}
        override suspend fun sendChannel(path: String, bytes: ByteArray) {
            val play = PlayRequest.fromFrame(LinkFrame.decode(bytes))
            played += "watch:${play.role}:${String(play.audio).removePrefix("AUDIO:")}"
            h.core.watchAcks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, true).encode())
        }
    }

    private fun watchTurn(h: CoreHarness, watch: Watch, turnId: String): VoiceTurnOutcome? = runBlocking {
        withTimeoutOrNull(20_000) {
            val frame = WatchTurnUpload(turnId, TurnTrigger.PUSH_TO_TALK, WatchTurnUpload.MIME_WAV, TestAudio.speechWav()).toFrame().encode()
            h.core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath(turnId), frame, watch)
        }
    }

    private fun configSets(h: CoreHarness) = h.fake.rpcLog.toList().filter { it.optString("method") == "config.set" }

    @Test
    fun `end to end - a router whose cold build fails is never written to, the request ends safely, and once built elsewhere the next creates the conversation with ACK and FINAL`() {
        CoreHarness().use { h ->
            val old = failedRouter(h, live = false)
            val started = System.nanoTime()
            val (outcome, played) = phoneTurn(h, "fb-e2e-1")
            assertTrue((System.nanoTime() - started) / 1_000_000 < 8_000)
            assertTrue("$outcome", outcome is VoiceTurnOutcome.NotDelivered && outcome.reason.contains("initialization_unconfirmed")
                && outcome.reason.contains("your request was not sent"))
            assertTrue(played.isEmpty())
            assertTrue(configSets(h).isEmpty())
            assertTrue(h.fake.rebuilds.isEmpty())
            assertTrue(h.fake.prompts.isEmpty())
            assertEquals("", h.registry.router()!!.modelSpec)
            // Built by something outside this app (e.g. another client's prompt): the next request switches it once, reads it
            // back, routes on Luna low and the new conversation plays its first ACK and FINAL; later replies stay off.
            h.fake.builtElsewhere(old)
            val (second, secondPlayed) = phoneTurn(h, "fb-e2e-2")
            assertTrue("$second", second is VoiceTurnOutcome.Completed)
            val made = h.registry.all().single { it.role == OwnedRole.CONVERSATION }.storedSessionId
            assertEquals(listOf("phone:ACK:Creating a new conversation called Garden plans, alias garden, and sending this there.",
                "phone:FINAL:reply from $made"), secondPlayed)
            assertEquals(1, configSets(h).size)
            assertEquals(FakeHermesDashboard.Runtime("gpt-5.6-luna", "openai-codex", "low"), h.fake.turnModels.single { it.first == old }.second)
            assertEquals(h.fake.profileDefault, h.fake.turnModels.single { it.first == made }.second)
            assertEquals("transcript submitted once", 1, h.fake.prompts.count { it.first == made })
            assertTrue(h.fake.rebuilds.isEmpty())
            assertTrue(h.fake.globalConfigWrites.isEmpty())
            assertFalse("later replies stay off by default", h.laterConsent.enabled)
            // The newest voice sender owns playback: a Watch request next plays there.
            val watch = Watch(h)
            h.fake.scripts[old] = { listOf(FakeHermesDashboard.complete("""{"action":"route","destination":"garden","ack":"To garden."}""")) }
            assertTrue(watchTurn(h, watch, "fb-e2e-3") is VoiceTurnOutcome.Completed)
            assertEquals(listOf("watch:ACK:To garden.", "watch:FINAL:reply from $made"), watch.played.toList())
            assertEquals(VoiceOrigin.WATCH, h.core.orchestrator.playbackRoute.device.value)
            assertEquals(1, configSets(h).size)
        }
    }

    @Test
    fun `end to end - a failed-build router fails every request at once with no write, frees the app's conversation actions, and routing off still works`() {
        CoreHarness().use { h ->
            val old = failedRouter(h, live = true)
            for (i in 1..2) {
                val started = System.nanoTime()
                val (outcome, played) = phoneTurn(h, "fb-e2e-fail-$i")
                assertTrue((System.nanoTime() - started) / 1_000_000 < 8_000)
                assertTrue("$outcome", outcome is VoiceTurnOutcome.NotDelivered && outcome.reason.contains("initialization_unconfirmed"))
                assertTrue(played.isEmpty())
            }
            assertTrue(configSets(h).isEmpty())
            assertTrue(h.fake.rebuilds.isEmpty())
            assertTrue(h.fake.prompts.isEmpty())
            val made = System.nanoTime()
            runBlocking { withTimeoutOrNull(5_000) { h.core.sessions.createConversation("Notes", "notes", "notes") } }!!
            assertTrue((System.nanoTime() - made) / 1_000_000 < 3_000)
            val direct = h.registry.all().single { it.alias == "notes" }.storedSessionId
            val resumes = h.fake.rpcLog.toList().count { it.optString("method") == "session.resume" && it.getJSONObject("params").optString("session_id") == old }
            val (off, offPlayed) = phoneTurn(h, "fb-e2e-off", TurnRouting.Direct(direct))
            assertTrue("$off", off is VoiceTurnOutcome.Completed)
            assertEquals("phone:FINAL:reply from $direct", offPlayed.last())
            assertEquals(resumes, h.fake.rpcLog.toList().count { it.optString("method") == "session.resume" && it.getJSONObject("params").optString("session_id") == old })
        }
    }

    @Test
    fun `end to end - a gateway without a live status never gets a write, and the request ends at once`() {
        CoreHarness().use { h ->
            failedRouter(h, live = true)
            h.fake.resumeStatus = { _, _ -> null }
            val (outcome, _) = phoneTurn(h, "fb-e2e-nostatus")
            assertTrue("$outcome", outcome is VoiceTurnOutcome.NotDelivered && outcome.reason.contains("build_state_unknown"))
            assertTrue(configSets(h).isEmpty())
        }
    }

    @Test
    fun `end to end - a refused switch shows its code only - the server's text reaches neither the user nor any diagnostic`() {
        val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val listener = object : VoiceTurnListener {
            override fun onDiagnostic(turnId: String, stage: String, detail: String) { lines += "$stage $detail" }
        }
        CoreHarness(voiceListener = listener).use { h ->
            val old = h.fake.addStoredSession(AppSources.ROUTER, "Hermes Voice router", hidden = true) { listOf(FakeHermesDashboard.complete(create)) }
            h.registry.put(OwnedSession(old, OwnedRole.ROUTER, "Hermes Voice router", "", "", archived = false, createdAtMs = 1, contract = RoutingContract.VERSION))
            h.fake.modelSwitchBehavior = { "error" }
            h.fake.modelSwitchErrorText = { "Model switch failed: provider catalog at $sentinel unreadable" }
            val (outcome, played) = phoneTurn(h, "fb-e2e-privacy")
            assertTrue("$outcome", outcome is VoiceTurnOutcome.NotDelivered && outcome.reason.contains("refused") && outcome.reason.contains("5001"))
            val reason = (outcome as VoiceTurnOutcome.NotDelivered).reason
            assertFalse(reason, reason.contains("SENTINEL") || reason.contains("/home/") || reason.contains("catalog"))
            assertTrue(lines.toString(), lines.isNotEmpty() && lines.none { it.contains("SENTINEL") || it.contains("/home/") })
            assertTrue(lines.toString(), lines.any { it.contains("router_model=failed reason=refused stage=switch write=switch") })
            assertTrue(played.isEmpty())
            assertTrue(h.fake.prompts.isEmpty())
        }
    }
}
