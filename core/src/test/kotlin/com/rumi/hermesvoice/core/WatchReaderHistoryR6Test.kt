package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.watchlink.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Actual HTTP parser/repository/Phone service → wire codec → Watch reducer, without live transport. */
class WatchReaderHistoryR6Test {
    private val h = CoreHarness()
    @After fun close() = h.close()
    private fun ask(id: String, req: String = "history-r6-0001", offset: Int = 0): ReaderResponse = runBlocking {
        ReaderResponse.decode(PhoneReaderService(h.core.sessions).handle(ReaderRequest(req, ReaderKind.HISTORY, id, offset).encode())!!)!!
    }
    private fun response(req: String, rows: List<ReaderMessageRow>, older: Boolean = true, next: Int = 20, offset: Int = 0) =
        ReaderResponse(req, ReaderKind.HISTORY, true, sessionId = "a", messages = rows, hasOlder = older, nextOffset = next, offset = offset)
    private fun row(id: Long, text: String = "row $id") = ReaderMessageRow(id, "assistant", text, false)

    @Test fun newestConversationalRowsAreFoundBeyondToolOnlyRawPagesWithoutLoadOlder() = runBlocking {
        val id = h.core.sessions.createConversation("R6", "r6", "").storedSessionId
        // Conversation creation stores one non-displayable system seed; raw cursors count it.
        val seed = ask(id, "history-r6-seed-0001")
        assertTrue(seed.ok)
        assertTrue(seed.messages.isEmpty())
        assertEquals(1, seed.nextOffset)
        h.fake.injectRow(id, "user", "question")
        h.fake.injectRow(id, "assistant", "**answer** [file](files/report.txt)")
        repeat(60) { h.fake.injectRow(id, if (it % 2 == 0) "tool" else "assistant", "hidden activity", hiddenKind = true) }
        val page = ask(id)
        assertTrue(page.ok)
        assertEquals(listOf("question", "**answer** [file](files/report.txt)"), page.messages.map { it.text })
        assertEquals(1 + 2 + 60, page.nextOffset); assertFalse(page.hasOlder)
        val state = WatchReaderState().select(id, page.reqId).onResponse(page)
        assertEquals(page.messages, state.selectedHistory!!.messages)
        assertTrue(h.fake.prompts.isEmpty())
    }

    @Test fun olderPagesSkipHiddenActivityAndKeepExactRawCursorAndSessionIdentity() = runBlocking {
        val id = h.core.sessions.createConversation("R6", "r6", "").storedSessionId
        repeat(25) { h.fake.injectRow(id, "user", "old $it") }
        repeat(20) { h.fake.injectRow(id, "tool", "hidden") }
        repeat(20) { h.fake.injectRow(id, "assistant", "new $it") }
        val latest = ask(id)
        assertEquals(20, latest.messages.size); assertEquals(20, latest.nextOffset)
        val older = ask(id, "history-r6-0002", latest.nextOffset)
        assertEquals(20, older.messages.size); assertEquals(60, older.nextOffset)
        assertEquals("old 5", older.messages.first().text)
        assertEquals(id, older.sessionId)
        assertEquals(20, older.offset)
    }

    @Test fun boundedHiddenScanReturnsExplicitFailureNotEmptyPaginationSuccess() = runBlocking {
        val id = h.core.sessions.createConversation("R6", "r6", "").storedSessionId
        h.fake.injectRow(id, "assistant", "older answer")
        repeat(180) { h.fake.injectRow(id, "tool", "hidden") }
        val page = ask(id)
        assertFalse(page.ok); assertNotNull(page.error)
        assertTrue("bounded network work", h.fake.restLog.count { it.contains("/messages?") } <= 8)
        assertTrue(h.fake.prompts.isEmpty())
    }

    @Test fun transientEmptyLatestPageCannotEraseCachedCanonicalMessages() {
        val state = WatchReaderState().select("a", "req-0001").onResponse(response("req-0001", listOf(row(1), row(2))))
        val next = state.refreshLatest("req-0002")!!.onResponse(response("req-0002", emptyList(), next = 40))
        assertEquals(state.selectedHistory!!.messages, next.selectedHistory!!.messages)
        assertEquals(LoadStatus.READY, next.selectedHistory!!.status)
    }

    @Test fun finalGrowthReplacesSameCanonicalRowWithoutDuplicatingOrLosingOlderRows() {
        val state = WatchReaderState().select("a", "req-0001").onResponse(response("req-0001", listOf(row(1), row(2, "partial"))))
        val next = state.refreshLatest("req-0002")!!.onResponse(response("req-0002", listOf(row(2, "**final**"), row(3))))
        assertEquals(listOf(1L, 2L, 3L), next.selectedHistory!!.messages.map { it.rowId })
        assertEquals("**final**", next.selectedHistory!!.messages[1].text)
    }

    @Test fun mismatchedPageOffsetCannotReplaceAnExactPendingLatestRequest() {
        val state = WatchReaderState().select("a", "req-0001")
        assertEquals(state, state.onResponse(response("req-0001", listOf(row(1)), offset = 20)))
        assertEquals(LoadStatus.LOADING, state.selectedHistory!!.status)
    }

    @Test fun lateForeignSessionAndRepeatedRepliesCannotMoveTheSelectedConversation() {
        val a = WatchReaderState().select("a", "req-0001")
        val b = a.select("b", "req-0002")
        assertEquals(b, b.onResponse(response("req-0001", listOf(row(1)))))
        assertEquals("b", b.selectedSessionId)
        val settled = a.onResponse(response("req-0001", listOf(row(1))))
        assertEquals(settled, settled.onResponse(response("req-0001", listOf(row(1)))))
    }
}
