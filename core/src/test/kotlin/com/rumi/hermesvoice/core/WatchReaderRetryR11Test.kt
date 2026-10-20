package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.watchlink.PhoneReaderService
import com.rumi.hermesvoice.core.watchlink.ReaderError
import com.rumi.hermesvoice.core.watchlink.ReaderKind
import com.rumi.hermesvoice.core.watchlink.ReaderRequest
import com.rumi.hermesvoice.core.watchlink.ReaderResponse
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R11: the Phone-side read the Watch asks for is refreshed again, within one shared budget (the first try and at most two
 * retries, after 1 s and 2 s), when the dashboard read fails for a transient reason. Real client, repository and reader over
 * the dashboard contract double. Synthetic content only; nothing here is a hardware or authenticated E2E proof.
 */
class WatchReaderRetryR11Test {
    private val h = CoreHarness()
    private val reader get() = PhoneReaderService(h.core.sessions)

    @After fun tearDown() = h.close()

    private fun ask(request: ReaderRequest): ReaderResponse = runBlocking { ReaderResponse.decode(reader.handle(request.encode())!!)!! }

    private fun sessionsRequest(id: String = "req-00000101") = ReaderRequest(id, ReaderKind.SESSIONS)
    private fun historyRequest(session: String, id: String = "req-00000102") = ReaderRequest(id, ReaderKind.HISTORY, session)

    private fun answer(status: Int, retryAfter: String? = null) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").apply { if (retryAfter != null) setHeader("Retry-After", retryAfter) }
        .setBody("""{"detail":"synthetic read fault"}""")

    private fun failFirst(kind: String, times: Int, offset: Int? = null, make: () -> MockResponse = { answer(503) }) {
        val left = AtomicInteger(times)
        h.fake.readFault = { k, at -> if (k == kind && (offset == null || at == offset) && left.getAndDecrement() > 0) make() else null }
    }

    private fun arrivals(kind: String) = h.fake.readArrivals.toList().filter { it.first == kind }

    private fun timed(block: () -> ReaderResponse): Pair<ReaderResponse, Long> {
        val started = System.nanoTime()
        val response = block()
        return response to (System.nanoTime() - started) / 1_000_000
    }

    @Test
    fun `a transient session list failure is read again after a one second pause and the list is shown`() {
        val work = runBlocking { h.core.sessions.createConversation("Work", "work", "") }
        failFirst("list", 1)
        val (response, elapsed) = timed { ask(sessionsRequest()) }
        assertTrue(response.error.orEmpty(), response.ok)
        assertEquals(listOf(work.storedSessionId), response.sessions.map { it.id })
        assertTrue(response.sessionsComplete)
        assertEquals(2, arrivals("list").size)
        assertTrue("the first retry waits about one second: $elapsed ms", elapsed in 900..2900)
    }

    @Test
    fun `a transient history failure is retried twice at one and two seconds and then the page arrives`() {
        val work = runBlocking { h.core.sessions.createConversation("Work", "work", "") }
        runBlocking { h.core.chat.send(work.storedSessionId, "synthetic hello") }
        failFirst("messages", 2)
        val (response, elapsed) = timed { ask(historyRequest(work.storedSessionId)) }
        assertTrue(response.error.orEmpty(), response.ok)
        assertTrue(response.messages.any { it.text == "synthetic hello" })
        val tries = arrivals("messages")
        assertEquals(3, tries.size)
        assertTrue("1 s then 2 s: ${tries.map { it.third }}", tries[1].third - tries[0].third >= 900 && tries[2].third - tries[1].third >= 1900)
        assertTrue("$elapsed ms", elapsed >= 2900)
    }

    @Test
    fun `retries stop after two and the failure is reported once as unavailable without any non read request`() {
        val work = runBlocking { h.core.sessions.createConversation("Work", "work", "") }
        failFirst("messages", 50)
        val promptsBefore = h.fake.prompts.size
        val restBefore = h.fake.restLog.size
        val (response, elapsed) = timed { ask(historyRequest(work.storedSessionId)) }
        assertFalse(response.ok)
        assertEquals(ReaderError.UNAVAILABLE, response.error)
        assertEquals("one first try plus two retries, never a fourth", 3, arrivals("messages").size)
        assertTrue("$elapsed ms", elapsed in 2900..8000)
        assertEquals(promptsBefore, h.fake.prompts.size)
        val added = h.fake.restLog.toList().drop(restBefore)
        assertTrue(added.toString(), added.all { it.startsWith("GET ") })
    }

    @Test
    fun `one budget covers the verification read and the page read of a single history refresh`() {
        val work = runBlocking { h.core.sessions.createConversation("Work", "work", "") }
        val detail = AtomicInteger(1)
        val messages = AtomicInteger(5)
        h.fake.readFault = { kind, _ ->
            when {
                kind == "detail" && detail.getAndDecrement() > 0 -> answer(503)
                kind == "messages" && messages.getAndDecrement() > 0 -> answer(503)
                else -> null
            }
        }
        val response = ask(historyRequest(work.storedSessionId))
        assertFalse(response.ok)
        assertEquals(2, arrivals("detail").size)
        assertEquals("two retries in total, shared by both reads", 2, arrivals("messages").size)
    }

    @Test
    fun `the later page of a session scan is retried in place and the scan is not restarted`() {
        val work = runBlocking { h.core.sessions.createConversation("Work", "work", "") }
        for (i in 1..150) h.fake.addForeignRow("noise-$i", "cron")
        failFirst("list", 1, offset = 100)
        val response = ask(sessionsRequest())
        assertTrue(response.error.orEmpty(), response.ok)
        assertTrue("the whole list was read", response.sessionsComplete)
        assertEquals(listOf(work.storedSessionId), response.sessions.map { it.id })
        assertEquals("page 0 once, page 100 twice, never page 0 again", listOf(0, 100, 100), arrivals("list").map { it.second })
    }

    @Test
    fun `a dropped connection is a transient transport failure and is retried`() {
        val work = runBlocking { h.core.sessions.createConversation("Work", "work", "") }
        failFirst("messages", 2) { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        val response = ask(historyRequest(work.storedSessionId))
        assertTrue(response.error.orEmpty(), response.ok)
        val tries = arrivals("messages")
        assertTrue("$tries", tries.size >= 3)
        assertTrue("our own pause preceded the last try, beyond any immediate HTTP-client reconnect: ${tries.map { it.third }}",
            tries.last().third - tries[tries.size - 2].third >= 900)
    }

    @Test
    fun `each retry eligible server status is retried once and then succeeds`() {
        val work = runBlocking { h.core.sessions.createConversation("Work", "work", "") }
        for (status in listOf(408, 429, 500, 502, 503, 504)) {
            h.fake.readArrivals.clear()
            failFirst("messages", 1) { answer(status) }
            val response = ask(historyRequest(work.storedSessionId))
            assertTrue("status $status: ${response.error}", response.ok)
            assertEquals("status $status", 2, arrivals("messages").size)
        }
    }

    @Test
    fun `terminal statuses are never retried`() {
        val work = runBlocking { h.core.sessions.createConversation("Work", "work", "") }
        for (status in listOf(400, 403, 404, 409, 410, 422)) {
            h.fake.readArrivals.clear()
            failFirst("messages", 50) { answer(status) }
            val (response, elapsed) = timed { ask(historyRequest(work.storedSessionId)) }
            assertFalse("status $status", response.ok)
            assertEquals("status $status", 1, arrivals("messages").size)
            assertTrue("status $status took $elapsed ms", elapsed < 900)
        }
    }

    @Test
    fun `a rejected session is a sign in result and is not retried`() {
        val work = runBlocking { h.core.sessions.createConversation("Work", "work", "") }
        h.fake.readFault = { kind, _ -> if (kind == "messages") answer(401) else null }
        val (response, elapsed) = timed { ask(historyRequest(work.storedSessionId)) }
        assertFalse(response.ok)
        assertEquals(ReaderError.SIGN_IN_REQUIRED, response.error)
        assertEquals("the client's own single refresh and nothing more", 2, arrivals("messages").size)
        assertTrue("$elapsed ms", elapsed < 1500)
    }

    @Test
    fun `an unmarked or foreign session fails closed on the first answer and is not retried`() {
        h.fake.addForeignRow("desktop-chat", "desktop")
        val owned = runBlocking { h.core.sessions.createConversation("Work", "work", "") }
        h.fake.setCreatedSource(owned.storedSessionId, null)
        val (response, elapsed) = timed { ask(historyRequest(owned.storedSessionId)) }
        assertFalse(response.ok)
        assertEquals(ReaderError.NOT_OWNED, response.error)
        assertEquals(1, arrivals("detail").size)
        assertEquals(0, arrivals("messages").size)
        assertTrue("$elapsed ms", elapsed < 900)
    }

    @Test
    fun `an unreadable success body is a protocol failure and is not retried`() {
        val work = runBlocking { h.core.sessions.createConversation("Work", "work", "") }
        failFirst("messages", 50) { MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("<html>not json</html>") }
        val (response, elapsed) = timed { ask(historyRequest(work.storedSessionId)) }
        assertFalse(response.ok)
        assertEquals(1, arrivals("messages").size)
        assertTrue("$elapsed ms", elapsed < 900)
    }

    @Test
    fun `a Retry-After that is longer than the retry window ends the refresh at once without an earlier retry`() {
        val work = runBlocking { h.core.sessions.createConversation("Work", "work", "") }
        failFirst("messages", 50) { answer(429, "600") }
        val (response, elapsed) = timed { ask(historyRequest(work.storedSessionId)) }
        assertFalse(response.ok)
        assertEquals(ReaderError.UNAVAILABLE, response.error)
        assertEquals("a local 1 s pause must never replace the server's longer instruction", 1, arrivals("messages").size)
        assertTrue("$elapsed ms", elapsed < 900)
    }

    @Test
    fun `a Retry-After inside the window is waited for in full before the retry`() {
        val work = runBlocking { h.core.sessions.createConversation("Work", "work", "") }
        failFirst("messages", 1) { answer(429, "3") }
        val response = ask(historyRequest(work.storedSessionId))
        assertTrue(response.error.orEmpty(), response.ok)
        val tries = arrivals("messages")
        assertEquals(2, tries.size)
        assertTrue("waited ${tries[1].third - tries[0].third} ms", tries[1].third - tries[0].third >= 2900)
    }

    @Test
    fun `cancelling the read while it waits to retry sends no further request`() = runBlocking {
        val work = h.core.sessions.createConversation("Work", "work", "")
        failFirst("messages", 50)
        val job = CoroutineScope(Dispatchers.IO).async { reader.handle(historyRequest(work.storedSessionId).encode()) }
        var waited = 0
        while (arrivals("messages").isEmpty() && waited < 5000) { delay(20); waited += 20 }
        assertEquals(1, arrivals("messages").size)
        assertTrue("the read is waiting to retry, not finished", job.isActive)
        job.cancel()
        delay(3500)
        assertTrue(job.isCancelled)
        assertEquals("no request after the cancellation", 1, arrivals("messages").size)
    }
}
