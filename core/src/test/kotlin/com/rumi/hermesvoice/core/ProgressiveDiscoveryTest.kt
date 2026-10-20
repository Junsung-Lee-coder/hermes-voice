package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.sessions.OwnedRole
import com.rumi.hermesvoice.core.sessions.RecipientCreateException
import com.rumi.hermesvoice.core.voice.CreateIntent
import com.rumi.hermesvoice.core.voice.DestinationAllowlist
import com.rumi.hermesvoice.core.voice.DestinationEntry
import com.rumi.hermesvoice.core.voice.RoutingContract
import com.rumi.hermesvoice.core.voice.RoutingParseResult
import com.rumi.hermesvoice.core.watchlink.PhoneReaderService
import com.rumi.hermesvoice.core.watchlink.ReaderKind
import com.rumi.hermesvoice.core.watchlink.ReaderRequest
import com.rumi.hermesvoice.core.watchlink.ReaderResponse
import com.rumi.hermesvoice.core.watchlink.ReaderSessionRow
import com.rumi.hermesvoice.core.watchlink.WatchReaderLimits
import com.rumi.hermesvoice.core.watchlink.WatchReaderState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Active conversations: 100. Session discovery: every page the server has, one page at a time, with each page's eligible sessions
 * registered and usable as soon as the page is read. Real repository, registry, gateway chat and Watch reader over the dashboard
 * contract double (not an authenticated Hermes E2E).
 */
class ProgressiveDiscoveryTest {
    private val h = CoreHarness()
    private val repo get() = h.core.sessions
    private val marker = AppSources.CONVERSATION
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() {
        h.fake.listPageHook = null
        scope.cancel()
        h.close()
    }

    private fun marked(id: String, title: String = "Topic $id") {
        h.fake.addForeignRow(id, "discord", title = title)
        h.fake.setCreatedSource(id, marker)
    }

    private fun noise(count: Int, prefix: String = "noise") {
        for (i in 1..count) h.fake.addForeignRow("$prefix-$i", "cron")
    }

    private fun list(core: CoreHarness = h, archived: Boolean = false) = runBlocking { core.core.sessions.listConversations(archived) }

    private fun send(id: String, core: CoreHarness = h) = runBlocking { core.core.chat.send(id, "ping") }

    private fun registered() = h.registry.all().map { it.storedSessionId }

    private fun listRequests() = h.fake.restLog.toList().filter { it.startsWith("GET /api/sessions?") }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("expected ${T::class.simpleName}, got $error", error)
        }
        throw AssertionError("expected ${T::class.simpleName}")
    }

    private fun latchFrom(offset: Int, archived: String = "exclude"): Pair<CountDownLatch, CountDownLatch> {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        h.fake.listPageHook = { at, filter ->
            if (at >= offset && filter == archived) {
                reached.countDown()
                release.await(60, TimeUnit.SECONDS)
            }
        }
        return reached to release
    }

    @Test
    fun `eligible sessions older than the thousandth row are found across more than thirty pages`() {
        marked("old-1")
        marked("old-2")
        marked("old-3")
        noise(3250)
        marked("new-1")
        val listed = list()
        assertEquals(true, listed.complete)
        assertEquals(3254, listed.scanned)
        assertEquals(3254, listed.total)
        assertEquals(0, listed.notAdopted)
        assertEquals(setOf("new-1", "old-1", "old-2", "old-3"), listed.map { it.stored.id }.toSet())
        assertEquals(4, listed.size)
        assertEquals(setOf("new-1", "old-1", "old-2", "old-3"), registered().toSet())
        val requests = listRequests()
        assertEquals(requests.toString(), 33, requests.size)
        assertEquals((0..32).map { it * 100 }, requests.map { Regex("offset=(\\d+)").find(it)!!.groupValues[1].toInt() })
        assertTrue(requests.toString(), requests.all { it.contains("limit=100") && it.contains("order=recent") && !it.contains("source") })
        assertTrue(h.fake.restLog.toList().toString(), h.fake.restLog.all { it.startsWith("GET ") })
    }

    @Test
    fun `scanning goes on to the end of the server list after one hundred conversations are registered`() {
        marked("oldest")
        noise(1100)
        for (i in 1..100) marked("m$i")
        val listed = list()
        assertEquals(true, listed.complete)
        assertEquals(1201, listed.scanned)
        assertEquals(1201, listed.total)
        assertEquals(1, listed.notAdopted)
        assertEquals(100, listed.size)
        assertEquals(100, registered().size)
        assertTrue("oldest" !in registered())
        assertTrue(send("oldest") is ChatSendResult.Failed)
        assertTrue(h.fake.prompts.isEmpty())
    }

    @Test
    fun `sessions found on earlier pages are registered and usable while a later page has not answered`() {
        marked("old-1")
        noise(250)
        marked("new-1")
        marked("new-2")
        val (reached, release) = latchFrom(100)
        val scan = scope.async { repo.listConversations(false) }
        try {
            assertTrue("the second page was requested", reached.await(30, TimeUnit.SECONDS))
            assertEquals(setOf("new-1", "new-2"), registered().toSet())
            assertEquals(setOf("topic-new-1", "topic-new-2"), repo.allowlist("router-x").entries.map { it.alias }.toSet())
            assertTrue(send("new-1") is ChatSendResult.Replied)
            assertTrue(send("old-1") is ChatSendResult.Failed)
            val mine = runBlocking { withTimeout(15_000) { repo.createConversation("Mine", "mine", "") } }
            assertTrue(mine.storedSessionId in registered())
        } finally {
            release.countDown()
        }
        val listed = runBlocking { withTimeout(60_000) { scan.await() } }
        assertEquals(true, listed.complete)
        assertTrue("old-1" in listed.map { it.stored.id })
        assertTrue(listed.map { it.stored.id }.containsAll(listOf("new-1", "new-2", "old-1")))
        assertEquals(4, registered().size)
        assertTrue(send("old-1") is ChatSendResult.Replied)
    }

    @Test
    fun `a cancelled scan keeps what its finished pages registered and does not call the list complete`() {
        marked("old-1")
        noise(250)
        marked("new-1")
        val (reached, release) = latchFrom(100)
        val scan = scope.async { repo.listConversations(false) }
        try {
            assertTrue(reached.await(30, TimeUnit.SECONDS))
            runBlocking { scan.cancelAndJoin() }
            assertTrue(scan.isCancelled)
        } finally {
            release.countDown()
        }
        assertEquals(listOf("new-1"), registered())
        assertTrue(send("new-1") is ChatSendResult.Replied)
        assertTrue(send("old-1") is ChatSendResult.Failed)
        h.fake.listPageHook = null
        val again = list()
        assertEquals(true, again.complete)
        assertEquals(setOf("new-1", "old-1"), registered().toSet())
    }

    @Test
    fun `a session that appears again on a later page is registered and listed once under the same alias`() {
        marked("tail-1")
        noise(100, "early")
        marked("dup-c")
        marked("dup-b")
        marked("dup-a")
        noise(97, "late")
        val shifted = AtomicBoolean(false)
        h.fake.listPageHook = { at, _ ->
            if (at == 100 && shifted.compareAndSet(false, true)) noise(3, "shift")
        }
        val listed = list()
        assertTrue("the rows were shifted while the list was read", shifted.get())
        val ids = listed.map { it.stored.id }
        assertEquals(ids.toString(), ids.toSet().size, ids.size)
        assertEquals(setOf("dup-a", "dup-b", "dup-c", "tail-1"), ids.toSet())
        assertEquals(registered().toString(), 4, registered().size)
        assertEquals(setOf("topic-dup-a", "topic-dup-b", "topic-dup-c", "topic-tail-1"), h.registry.all().map { it.alias }.toSet())
        assertEquals(true, listed.complete)
    }

    @Test
    fun `a failed later page keeps the registered conversations usable and revokes nothing it did not see`() {
        val old = runBlocking { repo.createConversation("Old", "old", "") }
        noise(150)
        marked("new-1")
        h.fake.failListFromOffset = 100
        val listed = list()
        assertEquals(false, listed.complete)
        assertTrue(listed.error.toString(), !listed.error.isNullOrBlank() && !listed.error!!.contains("busy"))
        assertTrue("new-1" in listed.map { it.stored.id })
        assertTrue(old.storedSessionId in registered())
        assertEquals(setOf(old.storedSessionId, "new-1"), registered().toSet())
        h.fake.failListFromOffset = -1
        assertTrue(send(old.storedSessionId) is ChatSendResult.Replied)
        assertEquals(true, list().complete)
    }

    @Test
    fun `one hundred and one marked sessions adopt the newest hundred and report the rest`() {
        for (i in 1..101) marked("m$i")
        val listed = list()
        assertEquals(100, listed.size)
        assertEquals(1, listed.notAdopted)
        assertEquals((2..101).map { "m$it" }.toSet(), listed.map { it.stored.id }.toSet())
        assertEquals(100, h.registry.all().size)
        assertEquals(100, repo.allowlist("router-x").entries.size)
        assertTrue(send("m1") is ChatSendResult.Failed)
        assertTrue(h.fake.prompts.isEmpty())
        assertTrue(send("m100") is ChatSendResult.Replied)
    }

    @Test
    fun `conversations can be created up to one hundred, the next is refused, and a restart and a revocation keep every place`() {
        val created = ArrayList<String>()
        runBlocking {
            for (i in 1..100) {
                created += repo.createConversation("C$i", "c$i", "").storedSessionId
                if (i == 33 || i == 99 || i == 100) {
                    assertEquals(i, h.registry.all().count { it.role == OwnedRole.CONVERSATION && !it.archived })
                    assertEquals(i, repo.allowlist("router-x").entries.size)
                }
            }
        }
        val creates = h.fake.rpcLog.toList().count { it.getString("method") == "session.create" }
        assertThrows<IllegalArgumentException> { runBlocking { repo.createConversation("C101", "c101", "") } }
        val refused = runCatching { runBlocking { repo.createForTurn("limit-1", CreateIntent("X", "x", "")) } }.exceptionOrNull()
        assertTrue(refused.toString(), refused is RecipientCreateException && refused.reason == "create_limit")
        assertEquals(creates, h.fake.rpcLog.toList().count { it.getString("method") == "session.create" })
        val aliases = h.registry.all().associate { it.storedSessionId to it.alias }
        assertEquals(100, aliases.size)

        h.stop()
        val relaunched = CoreHarness(h.fake, h.store)
        try {
            assertEquals(aliases, relaunched.registry.all().associate { it.storedSessionId to it.alias })
            assertEquals(100, relaunched.core.sessions.allowlist("router-x").entries.size)
            val listed = list(relaunched)
            assertEquals(100, listed.size)
            assertEquals(0, listed.notAdopted)
            assertEquals(aliases, relaunched.registry.all().associate { it.storedSessionId to it.alias })
            assertTrue(send(created[99], relaunched) is ChatSendResult.Replied)

            h.fake.setCreatedSource(created[4], null)
            val afterRevoke = list(relaunched)
            assertEquals(99, afterRevoke.size)
            assertTrue(send(created[4], relaunched) is ChatSendResult.Failed)
            assertEquals(aliases, relaunched.registry.all().associate { it.storedSessionId to it.alias })
            assertThrows<IllegalArgumentException> { runBlocking { relaunched.core.sessions.createConversation("C102", "c102", "") } }
        } finally {
            relaunched.stop()
        }
    }

    @Test
    fun `a routed allowlist holds one hundred destinations and refuses a hundred and first`() {
        val entries = (1..100).map { DestinationEntry("d$it", "sess_$it", "Destination $it") }
        val allow = DestinationAllowlist.create(entries, "sess_router")
        assertEquals(100, allow.entries.size)
        val parsed = RoutingContract.parse("""{"action":"route","destination":"d100","ack":"ok"}""", "complete", allow)
        assertTrue(parsed.toString(), parsed is RoutingParseResult.Accepted)
        assertThrows<IllegalArgumentException> { DestinationAllowlist.create(entries + DestinationEntry("d101", "sess_101", "Destination 101"), "sess_router") }
        assertEquals(100, DestinationAllowlist.MAX_ENTRIES)
    }

    @Test
    fun `the Watch reader answers with all one hundred conversations inside the message bound`() = runBlocking {
        val ids = (1..100).map { i ->
            val owned = h.core.sessions.createConversation("가".repeat(70) + i, "c$i", "")
            h.fake.injectRow(owned.storedSessionId, "user", "나".repeat(300) + i)
            owned.storedSessionId
        }
        val bytes = PhoneReaderService(h.core.sessions).handle(ReaderRequest("req-00000001", ReaderKind.SESSIONS).encode())!!
        assertTrue("${bytes.size}", bytes.size <= WatchReaderLimits.MAX_RESPONSE_BYTES)
        val response = ReaderResponse.decode(bytes)!!
        assertTrue(response.ok)
        assertEquals(100, response.sessions.size)
        assertEquals(ids.toSet(), response.sessions.map { it.id }.toSet())
        assertEquals(100, response.sessions.map { it.alias }.toSet().size)
    }

    @Test
    fun `the Watch reader codec and state keep one hundred session rows`() {
        val rows = (1..100).map { ReaderSessionRow("s$it", "Title $it", "a$it", "preview $it", it.toDouble()) }
        val response = ReaderResponse("req-00000001", ReaderKind.SESSIONS, ok = true, sessions = rows)
        assertEquals(100, ReaderResponse.decode(response.encode())!!.sessions.size)
        val state = WatchReaderState().requestSessions("req-00000001").onResponse(response)
        assertEquals(100, state.sessions.rows.size)
        assertNotNull(state.sessions.rows.last())
        assertEquals(100, WatchReaderLimits.MAX_SESSIONS)
    }

    @Test
    fun `each finished page is handed over as an in-progress listing that is already registered, and the last result is final`() {
        marked("old-1")
        noise(250)
        marked("new-1")
        class Step(val inProgress: Boolean, val complete: Boolean, val scanned: Int, val total: Int, val ids: Set<String>, val registered: Set<String>)
        val steps = ArrayList<Step>()
        val listed = runBlocking {
            repo.listConversations(false) { p ->
                steps += Step(p.inProgress, p.complete, p.scanned, p.total, p.conversations.map { it.stored.id }.toSet(), registered().toSet())
            }
        }
        assertEquals(steps.map { it.scanned }.toString(), listOf(100, 200), steps.map { it.scanned })
        assertTrue(steps.all { it.inProgress && !it.complete && it.total == 252 })
        assertTrue(steps.all { it.ids == setOf("new-1") && it.registered == setOf("new-1") })
        assertEquals(false, listed.inProgress)
        assertEquals(true, listed.complete)
        assertEquals(252, listed.scanned)
        assertEquals(setOf("new-1", "old-1"), listed.map { it.stored.id }.toSet())
    }

    @Test
    fun `a full page that holds only rows already read ends the scan as a failure to progress, not as a limit`() {
        marked("old-1")
        noise(250)
        marked("new-1")
        val shifted = AtomicBoolean(false)
        h.fake.listPageHook = { at, _ ->
            if (at == 100 && shifted.compareAndSet(false, true)) noise(100, "shift")
        }
        val listed = list()
        assertTrue(shifted.get())
        assertEquals(false, listed.complete)
        assertEquals(100, listed.scanned)
        assertTrue(listed.error.toString(), listed.error!!.contains("no progress"))
        assertEquals(listOf("new-1"), listed.map { it.stored.id })
        assertEquals(listOf("new-1"), registered())
        h.fake.listPageHook = null
        val again = list()
        assertEquals(true, again.complete)
        assertEquals(setOf("new-1", "old-1"), registered().toSet())
    }

    @Test
    fun `a registered conversation the scan has not reached yet stays usable and is not revoked for being unseen`() {
        val mine = runBlocking { repo.createConversation("Mine", "mine", "") }
        noise(250)
        marked("new-1")
        val (reached, release) = latchFrom(100)
        val scan = scope.async { repo.listConversations(false) }
        try {
            assertTrue(reached.await(30, TimeUnit.SECONDS))
            assertEquals(setOf("mine", "topic-new-1"), repo.allowlist("router-x").entries.map { it.alias }.toSet())
            assertTrue(send(mine.storedSessionId) is ChatSendResult.Replied)
        } finally {
            release.countDown()
        }
        val listed = runBlocking { withTimeout(60_000) { scan.await() } }
        assertEquals(true, listed.complete)
        assertEquals(setOf(mine.storedSessionId, "new-1"), listed.map { it.stored.id }.toSet())
    }
}
