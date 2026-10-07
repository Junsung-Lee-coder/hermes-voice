package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.sessions.OwnedRole
import com.rumi.hermesvoice.core.sessions.OwnedSession
import com.rumi.hermesvoice.core.voice.AssembledRoute
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.RoutedNavigation
import com.rumi.hermesvoice.core.voice.RoutingContract
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Router-only model (Luna at low reasoning) and the "a newly created conversation's reply is not
 * spoken" report, through the production wiring against [FakeHermesDashboard] extended with the
 * gateway behaviours read from tui_gateway's source: per-session `session.create` model overrides,
 * a session-scoped `config.set model ... --session` switch with resume readback, the real turn-start
 * orderings, and the failure frames a turn can end with before its `message.start`.
 */
class RouterModelAndNewSessionTest {
    private val luna = FakeHermesDashboard.Runtime("gpt-5.6-luna", "openai-codex", "low")
    private val create = """{"action":"create","title":"Garden plans","alias":"garden","description":"Plants","ack":"x"}"""

    private class Turn(val outcome: VoiceTurnOutcome?, val played: List<String>)

    private fun route(h: CoreHarness, reply: String) {
        h.fake.sourceScripts[AppSources.ROUTER] = { listOf(FakeHermesDashboard.complete(reply)) }
        h.registry.router()?.let { r -> h.fake.scripts[r.storedSessionId] = { listOf(FakeHermesDashboard.complete(reply)) } }
    }

    /** One Phone voice turn, bounded so a turn that never ends shows as null instead of hanging the suite. */
    private fun phoneTurn(h: CoreHarness, turnId: String, boundMs: Long = 20_000): Turn {
        val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val outcome = runBlocking {
            withTimeoutOrNull(boundMs) {
                h.core.orchestrator.run(VoiceTurnRequest(turnId, VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav",
                    PlaybackSink { audio, cue -> played += "phone:${cue.role}:${h.fake.decodeSpoken(audio)}" }))
            }
        }
        return Turn(outcome, played)
    }

    /** A paired Watch through the real WatchPlaybackSink and ACK registry. */
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

    private fun createsOf(h: CoreHarness, source: String) =
        h.fake.rpcLog.toList().filter { it.optString("method") == "session.create" && it.getJSONObject("params").optString("source") == source }
            .map { it.getJSONObject("params") }

    private fun configSets(h: CoreHarness) = h.fake.rpcLog.toList().filter { it.optString("method") == "config.set" }.map { it.getJSONObject("params") }

    // ── A: the router, and only the router, runs on Luna at low reasoning ──────────────────────

    @Test
    fun `a new router is created with Luna at low reasoning, and recipients keep the profile's model`() {
        CoreHarness().use { h ->
            route(h, create)
            val first = phoneTurn(h, "rm-0001")
            assertTrue("${first.outcome}", first.outcome is VoiceTurnOutcome.Completed)
            val router = createsOf(h, AppSources.ROUTER).single()
            assertEquals("gpt-5.6-luna", router.optString("model"))
            assertEquals("openai-codex", router.optString("provider"))
            assertEquals("low", router.optString("reasoning_effort"))
            val recipient = createsOf(h, AppSources.CONVERSATION).single()
            assertFalse("a recipient never gets the router's model", recipient.has("model") || recipient.has("reasoning_effort") || recipient.has("provider"))
            val routerId = h.registry.router()!!.storedSessionId
            val made = h.registry.all().single { it.role == OwnedRole.CONVERSATION }.storedSessionId
            assertEquals(luna, h.fake.turnModels.first { it.first == routerId }.second)
            assertEquals(h.fake.profileDefault, h.fake.turnModels.first { it.first == made }.second)
            assertTrue(h.fake.globalConfigWrites.isEmpty())
            // A conversation made by hand keeps the profile's model too.
            runBlocking { h.core.sessions.createConversation("Notes", "notes", "notes") }
            assertFalse(createsOf(h, AppSources.CONVERSATION).last().has("model"))
        }
    }

    @Test
    fun `an existing router is switched once, session-scoped, read back, and never touches the profile's config`() {
        CoreHarness().use { h ->
            val old = h.fake.addStoredSession(AppSources.ROUTER, "Hermes Voice router", hidden = true) { listOf(FakeHermesDashboard.complete(create)) }
            h.registry.put(OwnedSession(old, OwnedRole.ROUTER, "Hermes Voice router", "", "", archived = false, createdAtMs = 1,
                contract = RoutingContract.VERSION))
            val turn = phoneTurn(h, "rm-0002")
            assertTrue("${turn.outcome}", turn.outcome is VoiceTurnOutcome.Completed)
            assertEquals("the existing router is kept, not replaced", old, h.registry.router()!!.storedSessionId)
            val set = configSets(h).single()
            assertEquals("model", set.optString("key"))
            assertEquals("gpt-5.6-luna --provider openai-codex --reasoning low --session", set.optString("value"))
            assertEquals("rt-$old", set.optString("session_id"))
            assertFalse("never auto-confirms a guarded switch", set.optBoolean("confirm_expensive_model", false))
            assertEquals(luna, h.fake.turnModels.first { it.first == old }.second)
            assertTrue(h.fake.globalConfigWrites.isEmpty())
            route(h, """{"action":"route","destination":"garden","ack":"To garden."}""")
            phoneTurn(h, "rm-0003")
            assertEquals("switched once; later turns read it back and reuse it", 1, configSets(h).size)
            assertEquals(luna, h.fake.turnModels.last { it.first == old }.second)
        }
    }

    @Test
    fun `a router switch that fails or needs confirmation is a visible bounded error, never a silent fallback`() {
        for (behaviour in listOf("error", "confirm", "deferred")) {
            CoreHarness().use { h ->
                h.fake.modelSwitchBehavior = { behaviour }
                val old = h.fake.addStoredSession(AppSources.ROUTER, "Hermes Voice router", hidden = true) { listOf(FakeHermesDashboard.complete(create)) }
                h.registry.put(OwnedSession(old, OwnedRole.ROUTER, "Hermes Voice router", "", "", archived = false, createdAtMs = 1,
                    contract = RoutingContract.VERSION))
                val turn = phoneTurn(h, "rm-fail-$behaviour")
                val outcome = turn.outcome
                assertTrue("$behaviour: $outcome", outcome is VoiceTurnOutcome.NotDelivered && outcome.reason.contains("router model"))
                assertTrue("$behaviour: nothing routed on the old model", h.fake.turnModels.none { it.first == old })
                assertTrue(turn.played.isEmpty())
                assertTrue(h.fake.globalConfigWrites.isEmpty())
            }
        }
    }

    @Test
    fun `a switch cut off by a dropped connection fails visibly, and the next request reconnects and switches once`() {
        CoreHarness().use { h ->
            val old = h.fake.addStoredSession(AppSources.ROUTER, "Hermes Voice router", hidden = true) { listOf(FakeHermesDashboard.complete(create)) }
            h.registry.put(OwnedSession(old, OwnedRole.ROUTER, "Hermes Voice router", "", "", archived = false, createdAtMs = 1,
                contract = RoutingContract.VERSION))
            h.fake.modelSwitchBehavior = { "drop" }
            val first = phoneTurn(h, "rm-drop-1").outcome
            assertTrue("$first", first is VoiceTurnOutcome.NotDelivered && first.reason.contains("router model"))
            assertTrue(h.fake.turnModels.isEmpty())
            h.fake.modelSwitchBehavior = { "ok" }
            assertTrue(phoneTurn(h, "rm-drop-2").outcome is VoiceTurnOutcome.Completed)
            assertEquals(luna, h.fake.turnModels.first { it.first == old }.second)
            assertEquals(2, configSets(h).size)
            assertTrue(h.fake.globalConfigWrites.isEmpty())
        }
    }

    @Test
    fun `a new router whose create ignored the override is switched and read back before its first use`() {
        CoreHarness().use { h ->
            h.fake.ignoreCreateOverrides = true
            route(h, create)
            assertTrue(phoneTurn(h, "rm-ignored-1").outcome is VoiceTurnOutcome.Completed)
            val router = h.registry.router()!!.storedSessionId
            assertEquals("rt-$router", configSets(h).single().optString("session_id"))
            assertEquals(luna, h.fake.turnModels.first { it.first == router }.second)
        }
    }

    @Test
    fun `source gate - one config set call site, key model with --session only, and only the routing session is ever pinned`() {
        val root = generateSequence(java.io.File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { java.io.File(it, "settings.gradle.kts").isFile }
        val all = root.resolve("core/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.readText() } +
            listOf("phone", "watch").flatMap { m -> root.resolve("$m/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.readText() } }
        assertEquals(1, all.sumOf { Regex("\"config\\.set\"").findAll(it).count() })
        val port = root.resolve("core/src/main/kotlin/com/rumi/hermesvoice/core/net/Conversations.kt").readText()
        assertTrue(port.contains(""".put("key", "model").put("value", runtime.switchValue())"""))
        assertTrue(port.contains("--provider \$provider --reasoning \$reasoningEffort --session"))
        assertFalse("never the reasoning key", port.contains(""".put("key", "reasoning")"""))
        val sessions = root.resolve("core/src/main/kotlin/com/rumi/hermesvoice/core/sessions/AppSessions.kt").readText()
        assertEquals(1, Regex("conversations\\.ensureRuntime\\(").findAll(sessions).count())
        assertTrue(sessions.contains("conversations.ensureRuntime(router.storedSessionId, spec)"))
        assertEquals("only the router is created with a runtime", 1,
            Regex("hidden = (true|false), spec\\)").findAll(sessions).count())
        assertTrue(sessions.contains("conversations.create(AppSources.ROUTER, \"Hermes Voice router\", RoutingContract.ROUTER_SEED, hidden = true, spec)"))
    }

    // ── B: a turn that fails before it starts ──────────────────────────────────────────────────

    @Test
    fun `a new conversation's turn that fails before its message start ends promptly with the reason, not after 15 minutes`() {
        for (behaviour in listOf("error_before_start", "init_failed")) {
            CoreHarness().use { h ->
                route(h, create)
                h.fake.turnBehavior = { stored -> if (h.fake.rows[stored]?.source == AppSources.CONVERSATION) behaviour else "start_first" }
                val turn = phoneTurn(h, "ns-fail-$behaviour", boundMs = 10_000)
                val outcome = turn.outcome
                assertNotNull("$behaviour: the turn never ended (the app waited for a message.start that never comes)", outcome)
                assertTrue("$behaviour: $outcome", outcome is VoiceTurnOutcome.DeliveredResponseFailed)
                assertEquals(listOf("phone:ACK:Creating a new conversation called Garden plans, alias garden, and sending this there."), turn.played)
            }
        }
    }

    // ── B: the matrix (falsifiers of new-session-only causes) ──────────────────────────────────

    @Test
    fun `new and existing destinations, Phone and Watch, every real turn-start order and auto-navigation - ACK and FINAL both play`() {
        var case = 0
        for (order in listOf("start_first", "start_after_reply", "double_start")) for (autoNav in listOf(true, false)) {
            case += 1
            val opened: MutableList<String> = Collections.synchronizedList(mutableListOf())
            lateinit var nav: RoutedNavigation
            val listener = object : VoiceTurnListener {
                override fun onAccepted(turnId: String, origin: VoiceOrigin) = nav.onAccepted(turnId, origin)
                override fun onDelivered(route: AssembledRoute) = nav.onDelivered(route)
            }
            nav = RoutedNavigation(autoNavigate = { autoNav }) { opened += it }
            CoreHarness(voiceListener = listener).use { h ->
                h.fake.turnBehavior = { order }
                route(h, create)
                // Phone origin, newly created destination.
                val first = phoneTurn(h, "mx-turn-$case-1")
                val made = h.registry.all().single { it.role == OwnedRole.CONVERSATION }.storedSessionId
                assertTrue("$order/$autoNav: ${first.outcome}", first.outcome is VoiceTurnOutcome.Completed)
                assertEquals("$order/$autoNav", listOf("phone:ACK:Creating a new conversation called Garden plans, alias garden, and sending this there.",
                    "phone:FINAL:reply from $made"), first.played)
                // Watch origin, the now existing destination; then a NEW one from the Watch.
                val watch = Watch(h)
                route(h, """{"action":"route","destination":"garden","ack":"To garden."}""")
                assertTrue(watchTurn(h, watch, "mx-turn-$case-2") is VoiceTurnOutcome.Completed)
                route(h, """{"action":"create","title":"Trip","alias":"trip","description":"Travel","ack":"x"}""")
                assertTrue(watchTurn(h, watch, "mx-turn-$case-3") is VoiceTurnOutcome.Completed)
                val trip = h.registry.all().single { it.alias == "trip" }.storedSessionId
                assertEquals("$order/$autoNav", listOf("watch:ACK:To garden.", "watch:FINAL:reply from $made",
                    "watch:ACK:Creating a new conversation called Trip, alias trip, and sending this there.", "watch:FINAL:reply from $trip"),
                    watch.played.toList())
                assertEquals(if (autoNav) listOf(made, made, trip) else emptyList<String>(), opened.toList())
                assertEquals(VoiceOrigin.WATCH, h.core.orchestrator.playbackRoute.device.value)
            }
        }
    }

    @Test
    fun `a new conversation reached over another socket after create still plays its first reply, without later replies on`() {
        CoreHarness().use { h ->
            h.fake.dropAfterCreateReply = true
            route(h, create)
            val turn = phoneTurn(h, "ns-socket-1")
            val made = h.registry.all().single { it.role == OwnedRole.CONVERSATION }.storedSessionId
            assertTrue("${turn.outcome}", turn.outcome is VoiceTurnOutcome.Completed)
            assertEquals("phone:FINAL:reply from $made", turn.played.last())
            assertFalse(h.laterConsent.enabled)
        }
    }

    @Test
    fun `an active-session limit on the new conversation is reported, after the acknowledgement, and nothing is delivered`() {
        CoreHarness().use { h ->
            route(h, create)
            h.fake.turnBehavior = { stored -> if (h.fake.rows[stored]?.source == AppSources.CONVERSATION) "session_limit" else "start_first" }
            val turn = phoneTurn(h, "ns-limit-1")
            val outcome = turn.outcome
            assertTrue("$outcome", outcome is VoiceTurnOutcome.NotDelivered && outcome.reason.contains("active session limit"))
            assertEquals(1, turn.played.size)
        }
    }
}
