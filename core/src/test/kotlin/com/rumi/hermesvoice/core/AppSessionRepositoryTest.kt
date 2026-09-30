package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.sessions.OwnedRole
import com.rumi.hermesvoice.core.sessions.OwnedSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AppSessionRepositoryTest {
    private val h = CoreHarness()
    private val repo get() = h.core.sessions

    @After fun tearDown() = h.close()

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("expected ${T::class.simpleName}, got $error", error)
        }
        fail("expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    @Test
    fun `create tags the app source, seeds a hidden row and registers the exact id`() = runBlocking {
        val owned = repo.createConversation("Work", "Work", "Calendar and email")
        assertEquals("work", owned.alias)
        val create = h.fake.rpcLog.single { it.getString("method") == "session.create" }.getJSONObject("params")
        assertEquals(AppSources.CONVERSATION, create.getString("source"))
        assertFalse(create.getBoolean("hidden"))
        assertEquals("hidden", create.getJSONArray("messages").getJSONObject(0).getString("display_kind"))
        val row = h.fake.rows.getValue(owned.storedSessionId)
        assertEquals(AppSources.CONVERSATION, row.source)
        assertEquals(owned, h.registry.find(owned.storedSessionId))
    }

    @Test
    fun `list shows only app-source rows this install created, never the router`() = runBlocking {
        val mine = repo.createConversation("Work", "work", "")
        repo.ensureRoutingSession()
        h.fake.addForeignRow("other-install", AppSources.CONVERSATION)
        h.fake.addForeignRow("desktop-chat", "desktop")
        val listed = repo.listConversations(archived = false)
        assertEquals(listOf(mine.storedSessionId), listed.map { it.stored.id })
        assertTrue(h.fake.restLog.any { it.contains("source=${AppSources.CONVERSATION}") && it.contains("archived=exclude") })
        val router = h.registry.router()!!
        assertEquals(AppSources.ROUTER, h.fake.rows.getValue(router.storedSessionId).source)
        assertTrue(h.fake.rows.getValue(router.storedSessionId).hidden)
    }

    @Test
    fun `history hides the seed row and paginates newest first`() = runBlocking {
        val owned = repo.createConversation("Work", "work", "")
        h.core.chat.send(owned.storedSessionId, "hello")
        val page = repo.history(owned.storedSessionId, limit = 10)
        assertEquals(listOf("user:hello", "assistant:reply from ${owned.storedSessionId}"), page.messages.map { "${it.role}:${it.text}" })
        val older = repo.history(owned.storedSessionId, limit = 1, offset = 1)
        assertEquals(listOf("hello"), older.messages.map { it.text })
    }

    @Test
    fun `archive round trip moves the conversation between lists and out of the voice allowlist`() = runBlocking {
        val work = repo.createConversation("Work", "work", "")
        val home = repo.createConversation("Home", "home", "")
        repo.setArchived(work.storedSessionId, true)
        assertTrue(h.fake.rows.getValue(work.storedSessionId).archived)
        assertEquals(listOf(home.storedSessionId), repo.listConversations(false).map { it.stored.id })
        assertEquals(listOf(work.storedSessionId), repo.listConversations(true).map { it.stored.id })
        assertEquals(listOf("home"), repo.allowlist("router-x").entries.map { it.alias })
        repo.setArchived(work.storedSessionId, false)
        assertEquals(setOf("home", "work"), repo.allowlist("router-x").entries.map { it.alias }.toSet())
    }

    @Test
    fun `foreign ids are never archived, read or submitted to`() = runBlocking {
        h.fake.addForeignRow("desktop-chat", "desktop")
        h.fake.addForeignRow("other-install", AppSources.CONVERSATION)
        for (id in listOf("desktop-chat", "other-install")) {
            assertThrows<SessionNotOwnedException> { runBlocking { repo.setArchived(id, true) } }
            assertThrows<SessionNotOwnedException> { runBlocking { repo.history(id) } }
            val sent = h.core.chat.send(id, "hi") as ChatSendResult.Failed
            assertTrue(sent.reason, sent.reason.contains("not created by this app"))
        }
        assertFalse(h.fake.restLog.any { it.startsWith("PATCH") })
        assertTrue(h.fake.prompts.isEmpty())
    }

    @Test
    fun `a registry entry whose server row carries another source fails closed`() = runBlocking {
        h.fake.addForeignRow("desktop-chat", "desktop")
        h.registry.put(OwnedSession("desktop-chat", OwnedRole.CONVERSATION, "t", "desk", "", false, 0))
        assertThrows<SessionNotOwnedException> { runBlocking { repo.setArchived("desktop-chat", true) } }
        val sent = h.core.chat.send("desktop-chat", "hi") as ChatSendResult.Failed
        assertTrue(sent.reason.contains("source"))
        assertFalse(h.fake.rows.getValue("desktop-chat").archived)
        assertTrue(h.fake.prompts.isEmpty())
        assertTrue(repo.listConversations(false).isEmpty())
    }

    @Test
    fun `aliases stay unique among active conversations, including on unarchive`() = runBlocking {
        val first = repo.createConversation("Work", "work", "")
        assertThrows<IllegalArgumentException> { runBlocking { repo.createConversation("Work 2", "WORK", "") } }
        assertThrows<IllegalArgumentException> { runBlocking { repo.createConversation("Bad", "no spaces", "") } }
        repo.setArchived(first.storedSessionId, true)
        repo.createConversation("Work 2", "work", "")
        assertThrows<IllegalArgumentException> { runBlocking { repo.setArchived(first.storedSessionId, false) } }
        assertTrue(h.fake.rows.getValue(first.storedSessionId).archived)
    }

    @Test
    fun `the routing session is created once and reused`() = runBlocking {
        val a = repo.ensureRoutingSession()
        val b = repo.ensureRoutingSession()
        assertEquals(a, b)
        assertEquals(1, h.fake.rpcLog.count { it.getString("method") == "session.create" })
    }
}
