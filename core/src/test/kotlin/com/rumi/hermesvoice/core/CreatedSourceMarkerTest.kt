package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.sessions.LocalStoreException
import com.rumi.hermesvoice.core.sessions.OwnedRole
import com.rumi.hermesvoice.core.sessions.OwnedSession
import com.rumi.hermesvoice.core.watchlink.PhoneReaderService
import com.rumi.hermesvoice.core.watchlink.ReaderKind
import com.rumi.hermesvoice.core.watchlink.ReaderRequest
import com.rumi.hermesvoice.core.watchlink.ReaderResponse
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Eligibility is the server's `sessions.created_source`, not the mutable `sessions.source`: a session whose created_source was
 * set to this app's marker (by hand, on the server) is listed and usable after a refresh, with no local enrollment, and it stays
 * usable when `source` changes. Everything without the marker, and every router/internal row, stays fenced.
 */
class CreatedSourceMarkerTest {
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

    private fun prop(listing: Any, name: String): Any? = try {
        listing.javaClass.getMethod(name).invoke(listing)
    } catch (_: NoSuchMethodException) {
        throw AssertionError("the listing does not report $name")
    }

    private fun methods() = h.fake.rpcLog.map { it.getString("method") }

    @Test
    fun `a Discord session marked by created_source is listed and usable with no local enrollment`() {
        markedRow("disc-1", "Weekly planning")
        markedRow("bad id!", "Bad id")
        assertTrue(h.registry.all().isEmpty())
        val listed = list()
        assertEquals(listOf("disc-1"), listed.map { it.stored.id })
        val owned = listed.single().owned
        assertEquals("weekly-planning", owned.alias)
        assertEquals(OwnedRole.CONVERSATION, owned.role)
        assertFalse(owned.archived)
        assertEquals(owned, h.registry.find("disc-1"))
        assertEquals("discord", h.fake.rows.getValue("disc-1").source)
        assertEquals(marker, h.fake.rows.getValue("disc-1").createdSource)
        assertFalse(h.fake.rows.getValue("disc-1").archived)
        assertTrue(h.fake.rpcLog.toList().toString(), h.fake.rpcLog.isEmpty())
        assertTrue(h.fake.restLog.toList().toString(), h.fake.restLog.all { it.startsWith("GET ") })
        assertTrue(runBlocking { repo.history("disc-1") }.messages.any { it.text == "hello from discord" })
        runBlocking { repo.recipientCreator().requireDeliverable("disc-1") }
        assertEquals(listOf("weekly-planning"), repo.allowlist("router-x").entries.map { it.alias })
        val sent = send("disc-1")
        assertTrue(sent.toString(), sent is ChatSendResult.Replied)
        assertEquals(listOf("disc-1" to "ping"), h.fake.prompts.toList())
        assertTrue(methods().none { it == "session.create" || it == "config.set" })
        assertEquals("discord", h.fake.rows.getValue("disc-1").source)
    }

    @Test
    fun `a conversation stays eligible after its source changes, also after a relaunch`() {
        val mine = runBlocking { repo.createConversation("Work", "work", "") }
        val id = mine.storedSessionId
        assertEquals(marker, h.fake.rows.getValue(id).createdSource)
        h.fake.changeSource(id, "discord")
        assertEquals(listOf(id), list().map { it.stored.id })
        assertTrue(send(id) is ChatSendResult.Replied)
        assertEquals(listOf("work"), repo.allowlist("router-x").entries.map { it.alias })
        h.fake.changeSource(id, "tui")
        h.stop()
        val relaunched = CoreHarness(h.fake, h.store)
        try {
            assertEquals(listOf(id), list(relaunched).map { it.stored.id })
            assertTrue(send(id, relaunched) is ChatSendResult.Replied)
            assertEquals(mine, relaunched.registry.find(id))
        } finally {
            relaunched.stop()
        }
    }

    @Test
    fun `rows without the marker and router or internal rows are never listed, adopted or sent to`() {
        h.fake.addForeignRow("plain-discord", "discord")
        h.fake.addForeignRow("legacy-null", marker)
        h.fake.setCreatedSource("legacy-null", null)
        h.fake.addForeignRow("cron-1", "cron")
        h.fake.addForeignRow("created-elsewhere", marker)
        h.fake.setCreatedSource("created-elsewhere", "discord")
        h.fake.addForeignRow("router-created", marker)
        h.fake.setCreatedSource("router-created", AppSources.ROUTER)
        val ids = listOf("plain-discord", "legacy-null", "cron-1", "created-elsewhere", "router-created")
        assertTrue(list().isEmpty())
        assertTrue(h.registry.all().isEmpty())
        for (id in ids) {
            val sent = send(id) as ChatSendResult.Failed
            assertTrue(sent.reason, sent.reason.contains("not created by this app"))
        }
        assertTrue(h.fake.prompts.isEmpty())
        assertTrue(h.fake.restLog.none { it.startsWith("PATCH") })
    }

    @Test
    fun `the registered routing session is never listed even when its created_source is forged to the marker, while a row that only has the router source is an ordinary marked conversation`() {
        val router = runBlocking { repo.ensureRoutingSession() }
        h.fake.setCreatedSource(router.storedSessionId, marker)
        h.fake.addForeignRow("rtr-lookalike", AppSources.ROUTER)
        h.fake.setCreatedSource("rtr-lookalike", marker)
        assertEquals(listOf("rtr-lookalike"), list().map { it.stored.id })
        assertEquals(router, h.registry.find(router.storedSessionId))
        assertTrue(send(router.storedSessionId) is ChatSendResult.Failed)
        assertTrue(h.fake.prompts.isEmpty())
    }

    @Test
    fun `a failed durable write grants no authority and a restart reads what really reached storage`() {
        val backing = InMemoryKeyValueStore()
        val flaky = object : KeyValueStore by backing {
            @Volatile var failCommit = false
            override fun commitString(key: String, value: String): Boolean = if (failCommit) false else backing.commitString(key, value)
        }
        val local = CoreHarness(store = flaky)
        try {
            local.fake.addForeignRow("disc-1", "discord", title = "Weekly planning")
            local.fake.setCreatedSource("disc-1", marker)
            flaky.failCommit = true
            val error = assertThrows<LocalStoreException> { list(local) }
            assertEquals("store_write_failed", error.reason)
            flaky.failCommit = false
            assertThrows<LocalStoreException> { list(local) }
            assertFalse(send("disc-1", local) is ChatSendResult.Replied)
            assertTrue(local.fake.prompts.isEmpty())
            local.stop()
            val relaunched = CoreHarness(local.fake, backing)
            try {
                assertEquals(listOf("disc-1"), list(relaunched).map { it.stored.id })
                assertTrue(send("disc-1", relaunched) is ChatSendResult.Replied)
            } finally {
                relaunched.stop()
            }
        } finally {
            local.close()
        }
    }

    @Test
    fun `a listing cancelled before it finishes registers nothing and the next one still works`() {
        markedRow("disc-1", "Weekly planning")
        h.fake.listDelayMs = 600
        runBlocking {
            val job = launch { repo.listConversations(false) }
            delay(150)
            job.cancelAndJoin()
        }
        assertTrue(h.registry.all().isEmpty())
        assertTrue(send("disc-1") is ChatSendResult.Failed)
        assertTrue(h.fake.prompts.isEmpty())
        h.fake.listDelayMs = 0
        assertEquals(listOf("disc-1"), list().map { it.stored.id })
    }

    @Test
    fun `a session deleted on the server is neither listed nor sent to`() {
        markedRow("disc-1", "Weekly planning")
        assertEquals(1, list().size)
        h.fake.rows.remove("disc-1")
        assertTrue(list().isEmpty())
        val sent = send("disc-1") as ChatSendResult.Failed
        assertTrue(sent.reason, sent.reason.contains("no longer exists"))
        assertThrows<SessionNotOwnedException> { runBlocking { repo.recipientCreator().requireDeliverable("disc-1") } }
        assertTrue(h.fake.prompts.isEmpty())
    }

    @Test
    fun `taking the marker off hides the conversation again and the next refresh refuses it, the entry is kept`() {
        markedRow("disc-1", "Weekly planning")
        val before = list().single().owned
        assertTrue(send("disc-1") is ChatSendResult.Replied)
        h.fake.setCreatedSource("disc-1", "discord")
        assertTrue(list().isEmpty())
        val refused = send("disc-1") as ChatSendResult.Failed
        assertTrue(refused.reason, refused.reason.contains("marked"))
        assertThrows<SessionNotOwnedException> { runBlocking { repo.recipientCreator().requireDeliverable("disc-1") } }
        assertEquals(1, h.fake.prompts.size)
        assertEquals(before, h.registry.find("disc-1"))
        h.fake.setCreatedSource("disc-1", marker)
        assertEquals(listOf("disc-1"), list().map { it.stored.id })
        assertEquals(before, h.registry.find("disc-1"))
    }

    @Test
    fun `an older server without a created_source value fences every conversation, registered or not, with no fallback to source`() {
        for (omit in listOf(true, false)) {
            val local = CoreHarness()
            try {
                val mine = runBlocking { local.core.sessions.createConversation("Work", "work", "") }.storedSessionId
                val flipped = runBlocking { local.core.sessions.createConversation("Home", "home", "") }.storedSessionId
                local.fake.omitCreatedSource = omit
                if (!omit) {
                    local.fake.setCreatedSource(mine, null)
                    local.fake.setCreatedSource(flipped, null)
                }
                local.fake.changeSource(flipped, "discord")
                local.fake.addForeignRow("stranger", marker)
                local.fake.setCreatedSource("stranger", null)
                local.fake.addForeignRow("plain-discord", "discord")
                assertTrue("omit=$omit", list(local).isEmpty())
                for (id in listOf(mine, flipped, "stranger", "plain-discord")) assertTrue("omit=$omit $id", send(id, local) is ChatSendResult.Failed)
                assertEquals(setOf(mine, flipped), local.registry.all().map { it.storedSessionId }.toSet())
            } finally {
                local.close()
            }
        }
    }

    @Test
    fun `a scan reads on to the end of the list, so a marked session behind more than a thousand rows is adopted`() {
        markedRow("late-marked", "Late")
        val work = runBlocking { repo.createConversation("Work", "work", "") }
        for (i in 1..1100) h.fake.addForeignRow("noise-$i", "cron")
        val listed = list()
        assertEquals(setOf(work.storedSessionId, "late-marked"), listed.map { it.stored.id }.toSet())
        assertEquals(true, prop(listed, "getComplete"))
        assertEquals(1102, prop(listed, "getScanned"))
        assertEquals(1102, prop(listed, "getTotal"))
        assertTrue(send("late-marked") is ChatSendResult.Replied)
        assertEquals(setOf(work.storedSessionId, "late-marked"), h.registry.all().map { it.storedSessionId }.toSet())
    }

    @Test
    fun `a page that fails mid-scan keeps what was read, reports the listing as partial and names no server text`() {
        for (i in 1..150) h.fake.addForeignRow("noise-$i", "cron")
        markedRow("newest-marked", "Newest")
        h.fake.failListFromOffset = 100
        val listed = list()
        assertEquals(listOf("newest-marked"), listed.map { it.stored.id })
        assertEquals(false, prop(listed, "getComplete"))
        val error = prop(listed, "getError") as String?
        assertTrue(error.toString(), !error.isNullOrBlank() && !error.contains("busy"))
        h.fake.failListFromOffset = 0
        val failure = runCatching { list() }.exceptionOrNull()
        assertTrue(failure.toString(), failure is HermesException)
    }

    @Test
    fun `a complete scan reports complete and aliases from titles are unique, deterministic and valid`() {
        val work = runBlocking { repo.createConversation("Work", "work", "") }
        markedRow("m-work", "Work")
        markedRow("m-plan-1", "Plan")
        markedRow("m-plan-2", "Plan")
        markedRow("m-korean", "주간 회의")
        val listed = list()
        assertEquals(true, prop(listed, "getComplete"))
        assertEquals(0, prop(listed, "getNotAdopted"))
        val aliases = h.registry.all().associate { it.storedSessionId to it.alias }
        assertEquals(5, aliases.size)
        assertEquals(5, aliases.values.toSet().size)
        assertEquals("work", aliases.getValue(work.storedSessionId))
        assertTrue(aliases.getValue("m-work"), aliases.getValue("m-work").startsWith("work-"))
        assertTrue(aliases.values.all { com.rumi.hermesvoice.core.voice.DestinationAllowlist.normalizeAlias(it) == it })
        assertEquals(aliases, h.registry.all().associate { it.storedSessionId to it.alias })
        list()
        assertEquals(aliases, h.registry.all().associate { it.storedSessionId to it.alias })
        h.stop()
        val relaunched = CoreHarness(h.fake, h.store)
        try {
            list(relaunched)
            assertEquals(aliases, relaunched.registry.all().associate { it.storedSessionId to it.alias })
        } finally {
            relaunched.stop()
        }
    }

    @Test
    fun `no more than the destination limit is adopted and the rest are reported, not hidden`() {
        for (i in 1..101) markedRow("m$i", "Topic $i")
        val listed = list()
        assertEquals(100, listed.size)
        assertEquals(1, prop(listed, "getNotAdopted"))
        assertEquals((2..101).map { "m$it" }.toSet(), listed.map { it.stored.id }.toSet())
        assertEquals(100, h.registry.all().size)
        assertEquals(100, repo.allowlist("router-x").entries.size)
        assertTrue(send("m1") is ChatSendResult.Failed)
        assertTrue(h.fake.prompts.isEmpty())
    }

    @Test
    fun `a marked session that compressed is followed to its live tip through the verified lineage`() {
        markedRow("disc-1", "Weekly planning")
        val first = list().single().owned
        val mid = h.fake.compress("disc-1")
        val tip = h.fake.compress(mid)
        assertEquals("discord", h.fake.rows.getValue(tip).createdSource)
        val listed = list()
        assertEquals(listOf(tip), listed.map { it.stored.id })
        assertEquals(listOf(first.copy(storedSessionId = tip)), h.registry.all())
        assertTrue(send(tip) is ChatSendResult.Replied)
        assertEquals(listOf(tip to "ping"), h.fake.prompts.toList())
        assertTrue(runBlocking { repo.history(tip) }.messages.any { it.text == "hello from discord" })
        assertTrue(send("disc-1") is ChatSendResult.Failed)
        assertTrue(send(mid) is ChatSendResult.Failed)
        assertEquals(1, h.fake.prompts.size)
    }

    @Test
    fun `a marked session that already compressed before the first refresh is adopted under its tip`() {
        markedRow("disc-1", "Weekly planning")
        val tip = h.fake.compress("disc-1")
        assertEquals(listOf(tip), list().map { it.stored.id })
        assertEquals(listOf(tip), h.registry.all().map { it.storedSessionId })
        assertTrue(send(tip) is ChatSendResult.Replied)
    }

    @Test
    fun `a branch or a cut lineage is not admitted and a registered id outside a verified lineage is refused`() {
        markedRow("disc-1", "Weekly planning")
        list()
        h.fake.branch("disc-1", "br-1")
        assertEquals(listOf("disc-1"), list().map { it.stored.id })
        assertTrue(send("br-1") is ChatSendResult.Failed)
        h.registry.put(OwnedSession("br-1", OwnedRole.CONVERSATION, "b", "branch", "", false, 0))
        assertEquals(listOf("disc-1"), list().map { it.stored.id })
        val refused = send("br-1") as ChatSendResult.Failed
        assertTrue(refused.reason, refused.reason.contains("marked"))
        assertTrue(h.fake.prompts.isEmpty())
    }

    @Test
    fun `taking the marker off the root also refuses its compression tip`() {
        markedRow("disc-1", "Weekly planning")
        list()
        val tip = h.fake.compress("disc-1")
        assertEquals(listOf(tip), list().map { it.stored.id })
        h.fake.setCreatedSource("disc-1", "discord")
        assertTrue(list().isEmpty())
        assertTrue(send(tip) is ChatSendResult.Failed)
        assertTrue(h.fake.prompts.isEmpty())
    }

    @Test
    fun `the Watch reader never adopts, and shows a marked session once the Phone has`() {
        markedRow("disc-1", "Weekly planning")
        val reader = PhoneReaderService(h.core.sessions)
        fun ask(id: String): ReaderResponse = runBlocking { ReaderResponse.decode(reader.handle(ReaderRequest(id, ReaderKind.SESSIONS).encode())!!)!! }
        assertTrue(ask("req-00000001").sessions.isEmpty())
        assertTrue(h.registry.all().isEmpty())
        list()
        assertEquals(listOf("disc-1"), ask("req-00000002").sessions.map { it.id })
    }

    @Test
    fun `listing leaves titles, archive state and every other row untouched`() {
        markedRow("disc-1", "Weekly planning")
        h.fake.addForeignRow("plain-discord", "discord")
        list()
        assertEquals("Weekly planning", h.fake.rows.getValue("disc-1").title)
        assertFalse(h.fake.rows.getValue("plain-discord").archived)
        assertNotEquals(marker, h.fake.rows.getValue("plain-discord").createdSource)
        assertTrue(h.fake.restLog.none { it.startsWith("PATCH") || it.startsWith("POST") || it.startsWith("DELETE") })
    }
}
