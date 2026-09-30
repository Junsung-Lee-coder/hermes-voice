package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.watchlink.LoadStatus
import com.rumi.hermesvoice.core.watchlink.ReaderError
import com.rumi.hermesvoice.core.watchlink.ReaderKind
import com.rumi.hermesvoice.core.watchlink.ReaderMessageRow
import com.rumi.hermesvoice.core.watchlink.ReaderResponse
import com.rumi.hermesvoice.core.watchlink.ReaderScrollPolicy
import com.rumi.hermesvoice.core.watchlink.ReaderSessionRow
import com.rumi.hermesvoice.core.watchlink.ReaderSurface
import com.rumi.hermesvoice.core.watchlink.WatchReaderLimits
import com.rumi.hermesvoice.core.watchlink.WatchReaderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Watch-side reader model: request ids, late responses, bounded cache and explicit states. */
class WatchReaderStateTest {
    private fun session(id: String) = ReaderSessionRow(id, "Title $id", id, "preview", 0.0)
    private fun msg(id: Long, role: String = if (id % 2 == 0L) "assistant" else "user") = ReaderMessageRow(id, role, "m$id", false)

    private fun sessions(reqId: String, vararg ids: String) =
        ReaderResponse(reqId, ReaderKind.SESSIONS, ok = true, sessions = ids.map(::session))

    private fun page(reqId: String, sessionId: String, ids: LongRange, offset: Int, nextOffset: Int, hasOlder: Boolean) =
        ReaderResponse(reqId, ReaderKind.HISTORY, ok = true, sessionId = sessionId, messages = ids.map { msg(it) },
            offset = offset, nextOffset = nextOffset, hasOlder = hasOlder)

    @Test
    fun `sessions load shows loading then ready, and a stale response is ignored`() {
        var state = WatchReaderState().requestSessions("req-sess-0001")
        assertEquals(LoadStatus.LOADING, state.sessions.status)
        state = state.requestSessions("req-sess-0002")
        val afterStale = state.onResponse(sessions("req-sess-0001", "old"))
        assertSame(state, afterStale)
        state = state.onResponse(sessions("req-sess-0002", "a", "b"))
        assertEquals(LoadStatus.READY, state.sessions.status)
        assertEquals(listOf("a", "b"), state.sessions.rows.map { it.id })
        assertNull(state.sessions.pendingReqId)
        // A duplicate of an already-settled response changes nothing.
        assertSame(state, state.onResponse(sessions("req-sess-0002", "zzz")))
    }

    @Test
    fun `selecting a session opens chat and only the latest selection's history applies`() {
        var state = WatchReaderState().onResponse(sessions("req-x", "a", "b"))
        state = state.select("a", "req-hist-0001").select("b", "req-hist-0002")
        assertEquals(ReaderSurface.CHAT, state.surface)
        assertEquals("b", state.selectedSessionId)
        val late = state.onResponse(page("req-hist-0001", "a", 1L..3L, 0, 3, false))
        assertTrue(late.historyFor("a") == null || late.historyFor("a")!!.messages.isEmpty())
        assertEquals(LoadStatus.LOADING, late.historyFor("b")!!.status)
        // A response carrying the right request id but the wrong session is rejected too.
        assertSame(late, late.onResponse(page("req-hist-0002", "a", 1L..3L, 0, 3, false)))
        state = late.onResponse(page("req-hist-0002", "b", 5L..8L, 0, 4, true))
        val history = state.historyFor("b")!!
        assertEquals(LoadStatus.READY, history.status)
        assertEquals(listOf(5L, 6L, 7L, 8L), history.messages.map { it.rowId })
        assertTrue(history.hasOlder)
    }

    @Test
    fun `older pages prepend without duplicates and latest refresh appends new rows`() {
        var state = WatchReaderState().select("a", "req-h1").onResponse(page("req-h1", "a", 11L..20L, 0, 10, true))
        state = state.requestOlder("req-h2")!!
        assertTrue(state.historyFor("a")!!.loadingOlder)
        // Two new rows landed meanwhile, so the older page overlaps by two; overlap is de-duplicated.
        state = state.onResponse(page("req-h2", "a", 3L..12L, 10, 20, false))
        assertEquals((3L..20L).toList(), state.historyFor("a")!!.messages.map { it.rowId })
        assertFalse(state.historyFor("a")!!.hasOlder)
        assertNull(state.requestOlder("req-h3"))
        state = state.refreshLatest("req-h4")!!.onResponse(page("req-h4", "a", 13L..22L, 0, 10, true))
        assertEquals((3L..22L).toList(), state.historyFor("a")!!.messages.map { it.rowId })
        assertFalse("older pagination is kept", state.historyFor("a")!!.hasOlder)
    }

    @Test
    fun `cache is bounded per session and across sessions`() {
        var state = WatchReaderState()
        var n = 0
        for (id in listOf("a", "b", "c", "d", "e", "f")) {
            val req = "req-lru-${n++}"
            state = state.select(id, req).onResponse(page(req, id, 1L..3L, 0, 3, false))
        }
        assertEquals(WatchReaderLimits.MAX_CACHED_SESSIONS, state.histories.size)
        assertNull(state.historyFor("a"))
        assertEquals("f", state.selectedSessionId)

        state = state.select("g", "req-big-0").onResponse(page("req-big-0", "g", 1001L..1020L, 0, 20, true))
        var offset = 20
        var top = 1001L
        var i = 1
        while (true) {
            val req = "req-big-$i"
            val next = state.requestOlder(req) ?: break
            state = next.onResponse(page(req, "g", (top - 20)..(top - 1), offset, offset + 20, true))
            offset += 20; top -= 20; i++
            assertTrue(state.historyFor("g")!!.messages.size <= WatchReaderLimits.MAX_MESSAGES_PER_SESSION)
        }
        assertTrue(state.historyFor("g")!!.olderBlocked)
        // A latest refresh that would exceed the bound resets to the latest page, keeping pagination exact.
        state = state.refreshLatest("req-big-r")!!.onResponse(page("req-big-r", "g", 1011L..1030L, 0, 20, true))
        val g = state.historyFor("g")!!
        assertTrue(g.messages.size <= WatchReaderLimits.MAX_MESSAGES_PER_SESSION)
        assertEquals(1030L, g.messages.last().rowId)
    }

    @Test
    fun `offline, errors and timeouts are explicit and keep cached rows`() {
        var state = WatchReaderState().select("a", "req-o1").onResponse(page("req-o1", "a", 1L..4L, 0, 4, false))
        state = state.refreshLatest("req-o2")!!.onSendFailed("req-o2")
        assertEquals(LoadStatus.OFFLINE, state.historyFor("a")!!.status)
        assertEquals(4, state.historyFor("a")!!.messages.size)
        state = state.refreshLatest("req-o3")!!.onResponse(
            ReaderResponse("req-o3", ReaderKind.HISTORY, ok = false, error = ReaderError.SIGN_IN_REQUIRED, sessionId = "a"))
        assertEquals(LoadStatus.ERROR, state.historyFor("a")!!.status)
        assertEquals(ReaderError.SIGN_IN_REQUIRED, state.historyFor("a")!!.error)
        state = state.refreshLatest("req-o4")!!.onTimeout("req-o4")
        assertEquals(LoadStatus.ERROR, state.historyFor("a")!!.status)
        assertEquals(ReaderError.TIMEOUT, state.historyFor("a")!!.error)
        assertSame(state, state.onTimeout("req-unknown"))
        state = state.requestSessions("req-o5").onSendFailed("req-o5")
        assertEquals(LoadStatus.OFFLINE, state.sessions.status)
    }

    @Test
    fun `not-owned history clears the selection instead of showing foreign content`() {
        var state = WatchReaderState().select("gone", "req-n1")
        state = state.onResponse(ReaderResponse("req-n1", ReaderKind.HISTORY, ok = false, error = ReaderError.NOT_OWNED, sessionId = "gone"))
        assertNull(state.historyFor("gone"))
        assertNull(state.selectedSessionId)
        assertEquals(ReaderSurface.SESSIONS, state.surface)
    }

    @Test
    fun `surface toggles between chat and the session browser`() {
        val state = WatchReaderState()
        assertEquals(ReaderSurface.CHAT, state.surface)
        assertEquals(ReaderSurface.SESSIONS, state.toggleSurface().surface)
        assertEquals(ReaderSurface.CHAT, state.toggleSurface().toggleSurface().surface)
    }

    @Test
    fun `scroll follows the newest message only when the user was already at the latest`() {
        assertTrue(ReaderScrollPolicy.followLatest(wasAtLatest = true, before = emptyList(), after = listOf(1L, 2L)))
        assertTrue(ReaderScrollPolicy.followLatest(wasAtLatest = false, before = emptyList(), after = listOf(1L, 2L)))
        assertTrue(ReaderScrollPolicy.followLatest(wasAtLatest = true, before = listOf(1L, 2L), after = listOf(1L, 2L, 3L)))
        assertFalse(ReaderScrollPolicy.followLatest(wasAtLatest = false, before = listOf(1L, 2L), after = listOf(1L, 2L, 3L)))
        // Older rows prepended: the newest row is unchanged, so the reader's position is preserved.
        assertFalse(ReaderScrollPolicy.followLatest(wasAtLatest = true, before = listOf(5L, 6L), after = listOf(3L, 4L, 5L, 6L)))
    }
}
