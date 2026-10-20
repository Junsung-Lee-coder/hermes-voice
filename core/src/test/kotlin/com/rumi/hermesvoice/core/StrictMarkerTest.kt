package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.sessions.RecipientCreateException
import com.rumi.hermesvoice.core.voice.CreateIntent
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The final contract: the only authority for showing and accepting a conversation is the server's `sessions.created_source`
 * being exactly the app marker. `source` is neither a permission nor a veto, a local registration is never a second authority,
 * and the same predicate applies to the list, history, chat, voice delivery and the voice destination list.
 */
class StrictMarkerTest {
    private val h = CoreHarness()
    private val repo get() = h.core.sessions
    private val marker = AppSources.CONVERSATION

    @After fun tearDown() = h.close()

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("expected ${T::class.simpleName}, got $error", error)
        }
        throw AssertionError("expected ${T::class.simpleName}")
    }

    private fun markedRow(id: String, title: String, source: String = "discord", say: String = "hello from $source") {
        h.fake.addForeignRow(id, source, title = title)
        h.fake.injectRow(id, "user", say)
        h.fake.setCreatedSource(id, marker)
    }

    private fun list(core: CoreHarness = h, archived: Boolean = false) = runBlocking { core.core.sessions.listConversations(archived) }

    private fun send(id: String, core: CoreHarness = h) = runBlocking { core.core.chat.send(id, "ping") }

    private fun aliases(core: CoreHarness = h) = core.core.sessions.allowlist("router-x").entries.map { it.alias }

    private fun routed(core: CoreHarness, turn: String, alias: String): VoiceTurnOutcome {
        val reply: (String) -> List<Pair<String, org.json.JSONObject?>> =
            { listOf(FakeHermesDashboard.complete("""{"action":"route","destination":"$alias","ack":"Sending to $alias."}""")) }
        core.fake.sourceScripts[AppSources.ROUTER] = reply
        core.registry.router()?.let { core.fake.scripts[it.storedSessionId] = reply }
        return runBlocking {
            withTimeout(20_000) {
                core.core.orchestrator.run(VoiceTurnRequest(turn, VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav", PlaybackSink { _, _ -> }))
            }
        }
    }

    private fun deliveredTo(outcome: VoiceTurnOutcome) = (outcome as? VoiceTurnOutcome.Completed)?.route?.destination?.storedSessionId

    private fun promptsTo(id: String, core: CoreHarness = h) = core.fake.prompts.toList().count { it.first == id }

    // (a) a local registration plus the app's source is not enough ----------------------------------------------------------

    @Test
    fun `a registered session whose created_source is missing, blank or different is refused on every path though its source is the app's`() {
        val variants = listOf<Pair<String, (CoreHarness, String) -> Unit>>(
            "null" to { c, id -> c.fake.setCreatedSource(id, null) },
            "blank" to { c, id -> c.fake.setCreatedSource(id, "") },
            "spaces" to { c, id -> c.fake.setCreatedSource(id, "  ") },
            "other" to { c, id -> c.fake.setCreatedSource(id, "discord") },
            "router" to { c, id -> c.fake.setCreatedSource(id, AppSources.ROUTER) },
            "absent" to { c, _ -> c.fake.omitCreatedSource = true },
        )
        for ((name, apply) in variants) {
            val local = CoreHarness()
            try {
                val mine = runBlocking { local.core.sessions.createConversation("Work", "work", "") }
                val id = mine.storedSessionId
                apply(local, id)
                assertEquals(name, marker, local.fake.rows.getValue(id).source)
                val refused = send(id, local) as ChatSendResult.Failed
                assertTrue("$name ${refused.reason}", refused.reason.contains("marked"))
                assertThrows<SessionNotOwnedException> { runBlocking { local.core.sessions.history(id) } }
                assertThrows<SessionNotOwnedException> { runBlocking { local.core.sessions.recipientCreator().requireDeliverable(id) } }
                assertTrue(name, list(local).isEmpty())
                assertTrue(name, aliases(local).isEmpty())
                assertTrue(name, local.fake.prompts.isEmpty())
                assertEquals(name, mine, local.registry.find(id))
            } finally {
                local.close()
            }
        }
    }

    @Test
    fun `an unregistered session with the app's source but no marker is never adopted`() {
        h.fake.addForeignRow("legacy-1", marker)
        h.fake.setCreatedSource("legacy-1", null)
        h.fake.addForeignRow("legacy-2", marker)
        h.fake.setCreatedSource("legacy-2", "")
        assertTrue(list().isEmpty())
        assertTrue(h.registry.all().isEmpty())
        h.fake.omitCreatedSource = true
        assertTrue(list().isEmpty())
        assertTrue(h.registry.all().isEmpty())
    }

    // (b) the marker alone decides; a changed source neither hides nor admits ------------------------------------------------

    @Test
    fun `a marked session stays visible and usable whatever its current source says`() {
        for ((i, source) in listOf("discord", "tui", "desktop", "cron", AppSources.ROUTER, "weixin").withIndex()) {
            val id = "m-$i"
            markedRow(id, "Topic $i", source)
        }
        val mine = runBlocking { repo.createConversation("Work", "work", "") }.storedSessionId
        h.fake.changeSource(mine, AppSources.ROUTER)
        val listed = list()
        assertEquals((0..5).map { "m-$it" }.toSet() + mine, listed.map { it.stored.id }.toSet())
        for (id in listed.map { it.stored.id }) {
            assertTrue(id, send(id) is ChatSendResult.Replied)
            runBlocking { repo.recipientCreator().requireDeliverable(id) }
            assertTrue(id, runBlocking { repo.history(id) }.messages.isNotEmpty())
        }
        assertEquals(7, aliases().size)
        h.stop()
        val relaunched = CoreHarness(h.fake, h.store)
        try {
            assertEquals(7, list(relaunched).size)
            assertTrue(send("m-1", relaunched) is ChatSendResult.Replied)
        } finally {
            relaunched.stop()
        }
    }

    @Test
    fun `listing sends no source filter at all, not even an exclusion`() {
        markedRow("disc-1", "Weekly planning")
        list()
        list(archived = true)
        val lists = h.fake.restLog.toList().filter { it.contains("/api/sessions?") || it.endsWith("/api/sessions") }
        assertTrue(lists.toString(), lists.size >= 2)
        for (entry in lists) {
            assertFalse(entry, entry.contains("source=") || entry.contains("sources=") || entry.contains("created_source"))
        }
    }

    // (c) the marker on an existing Discord session is all it takes -----------------------------------------------------------

    @Test
    fun `marking an existing Discord session adopts it durably with no create, fork, seed, model or routing change`() {
        markedRow("disc-1", "Weekly planning")
        val before = h.fake.rows.getValue("disc-1").let { listOf(it.source, it.title, it.archived, it.hidden, it.messages.size, it.createdSource) }
        val listed = list()
        assertEquals(listOf("disc-1"), listed.map { it.stored.id })
        assertTrue(h.fake.rpcLog.toList().toString(), h.fake.rpcLog.isEmpty())
        assertTrue(h.fake.restLog.toList().toString(), h.fake.restLog.all { it.startsWith("GET ") })
        assertEquals(setOf("disc-1"), h.fake.rows.keys.toSet())
        assertEquals(before, h.fake.rows.getValue("disc-1").let { listOf(it.source, it.title, it.archived, it.hidden, it.messages.size, it.createdSource) })
        h.stop()
        val relaunched = CoreHarness(h.fake, h.store)
        try {
            assertEquals(listed.single().owned, relaunched.registry.find("disc-1"))
            assertEquals(listOf("weekly-planning"), aliases(relaunched))
            assertTrue(send("disc-1", relaunched) is ChatSendResult.Replied)
            assertEquals(deliveredTo(routed(relaunched, "t-c-0001", "weekly-planning")), "disc-1")
            assertEquals(2, promptsTo("disc-1", relaunched))
            val creates = relaunched.fake.rpcLog.toList().filter { it.optString("method") == "session.create" }
            assertTrue(creates.toString(), creates.all { it.getJSONObject("params").optString("source") == AppSources.ROUTER })
            assertEquals("discord", relaunched.fake.rows.getValue("disc-1").source)
        } finally {
            relaunched.stop()
        }
    }

    @Test
    fun `a marked session that a refresh adopted is a voice destination after a relaunch`() {
        markedRow("disc-1", "Weekly planning")
        list()
        h.stop()
        val relaunched = CoreHarness(h.fake, h.store)
        try {
            val outcome = routed(relaunched, "t-c-0002", "weekly-planning")
            assertEquals(outcome.toString(), "disc-1", deliveredTo(outcome))
            assertEquals(1, promptsTo("disc-1", relaunched))
        } finally {
            relaunched.stop()
        }
    }

    @Test
    fun `a marked session nobody has refreshed yet is not claimed by a voice turn`() {
        markedRow("disc-1", "Weekly planning")
        val outcome = routed(h, "t-c-0002", "weekly-planning")
        assertEquals(outcome.toString(), null, deliveredTo(outcome))
        assertEquals(0, promptsTo("disc-1"))
        assertTrue(h.registry.find("disc-1") == null)
    }

    // (d) taking the marker off ends every kind of eligibility, with no refresh needed ------------------------------------------

    @Test
    fun `taking the marker off refuses chat, history and delivery at once, drops the destination, and putting it back restores the same entry`() {
        markedRow("disc-1", "Weekly planning")
        val entry = list().single().owned
        assertTrue(send("disc-1") is ChatSendResult.Replied)
        assertEquals("disc-1", deliveredTo(routed(h, "t-d-0001", "weekly-planning")))
        assertEquals(2, promptsTo("disc-1"))
        h.fake.setCreatedSource("disc-1", null)
        val refused = send("disc-1") as ChatSendResult.Failed
        assertTrue(refused.reason, refused.reason.contains("marked"))
        assertThrows<SessionNotOwnedException> { runBlocking { repo.history("disc-1") } }
        assertThrows<SessionNotOwnedException> { runBlocking { repo.recipientCreator().requireDeliverable("disc-1") } }
        val second = routed(h, "t-d-0002", "weekly-planning")
        assertEquals(second.toString(), null, deliveredTo(second))
        assertEquals(2, promptsTo("disc-1"))
        assertTrue(list().isEmpty())
        assertTrue(aliases().isEmpty())
        assertEquals(entry, h.registry.find("disc-1"))
        h.fake.setCreatedSource("disc-1", marker)
        assertEquals(listOf("disc-1"), list().map { it.stored.id })
        assertEquals(entry, h.registry.find("disc-1"))
        assertEquals("disc-1", deliveredTo(routed(h, "t-d-0003", "weekly-planning")))
    }

    @Test
    fun `a marker taken off while the app was closed leaves no destination and no chat after the relaunch`() {
        markedRow("disc-1", "Weekly planning")
        list()
        h.stop()
        h.fake.setCreatedSource("disc-1", "discord")
        val relaunched = CoreHarness(h.fake, h.store)
        try {
            val outcome = routed(relaunched, "t-d-0004", "weekly-planning")
            assertEquals(outcome.toString(), null, deliveredTo(outcome))
            assertEquals(0, promptsTo("disc-1", relaunched))
            assertTrue(send("disc-1", relaunched) is ChatSendResult.Failed)
            assertTrue(list(relaunched).isEmpty())
            assertTrue(aliases(relaunched).isEmpty())
        } finally {
            relaunched.stop()
        }
    }

    // (e) the router is the router by its own created_source ----------------------------------------------------------------

    @Test
    fun `a row created as the router stays out even when its source looks like a conversation`() {
        h.fake.addForeignRow("rtr-lookalike", marker)
        h.fake.setCreatedSource("rtr-lookalike", AppSources.ROUTER)
        assertTrue(list().isEmpty())
        assertTrue(h.registry.all().isEmpty())
        val routerId = runBlocking { repo.ensureRoutingSession() }.storedSessionId
        h.fake.changeSource(routerId, marker)
        h.fake.rows.getValue(routerId).archived = true
        assertTrue(list(archived = true).none { it.stored.id == routerId })
        assertTrue(send(routerId) is ChatSendResult.Failed)
        assertEquals(0, promptsTo(routerId))
    }

    @Test
    fun `the registered routing session keeps routing after its source is changed elsewhere`() {
        runBlocking { repo.createConversation("Work", "work", "") }
        assertEquals(h.registry.all().single().storedSessionId, deliveredTo(routed(h, "t-e-0001", "work")))
        val routerId = h.registry.router()!!.storedSessionId
        h.stop()
        h.fake.changeSource(routerId, marker)
        val relaunched = CoreHarness(h.fake, h.store)
        try {
            val outcome = routed(relaunched, "t-e-0002", "work")
            assertEquals(outcome.toString(), relaunched.registry.all().single { it.role == com.rumi.hermesvoice.core.sessions.OwnedRole.CONVERSATION }.storedSessionId, deliveredTo(outcome))
            assertEquals(routerId, relaunched.registry.router()!!.storedSessionId)
        } finally {
            relaunched.stop()
        }
    }

    // creation: a new conversation is registered only when the server shows the marker ---------------------------------------

    @Test
    fun `a conversation the server did not stamp with the marker is not registered`() {
        h.fake.omitCreatedSource = true
        val error = assertThrows<RecipientCreateException> { runBlocking { repo.createForTurn("t-create-1", CreateIntent("Garden", "garden", "")) } }
        assertEquals("create_unverified", error.reason)
        assertTrue(h.registry.all().isEmpty())
    }
}
