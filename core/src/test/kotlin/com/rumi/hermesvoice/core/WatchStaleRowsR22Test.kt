package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.watchlink.PhoneReaderService
import com.rumi.hermesvoice.core.watchlink.ReaderKind
import com.rumi.hermesvoice.core.watchlink.ReaderRequest
import com.rumi.hermesvoice.core.watchlink.ReaderResponse
import com.rumi.hermesvoice.core.watchlink.WatchReaderLimits
import com.rumi.hermesvoice.core.watchlink.WatchReaderState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Watch reader keeps what it already shows when a fresh page has no shown row, so a row the server has since hidden must be named
 * to it by the Phone. Only the rows the page itself names go; another identity with the same words stays. Synthetic data only.
 */
class WatchStaleRowsR22Test {
    private val h = CoreHarness()
    @After fun close() = h.close()

    private fun conversation(): String = runBlocking { h.core.sessions.createConversation("Synthetic", "synthetic", "").storedSessionId }

    private fun serve(stored: String, vararg rows: JSONObject) {
        val list = h.fake.rows.getValue(stored).messages
        synchronized(list) { list.clear(); rows.forEach { list += it.put("session_id", stored) } }
    }

    private fun row(id: Long, role: String, text: String, kind: String? = null, uid: String? = null) =
        JSONObject().put("id", id).put("role", role).put("content", text).put("timestamp", 1_700_000_000.0 + id)
            .apply { if (kind != null) put("display_kind", kind); if (uid != null) put("message_uid", uid).put("finish_reason", "stop") }

    private fun retag(stored: String, id: Long, kind: String) {
        val list = h.fake.rows.getValue(stored).messages
        synchronized(list) { list.first { it.getLong("id") == id }.put("display_kind", kind) }
    }

    private fun ask(id: String, req: String): ReaderResponse = runBlocking {
        ReaderResponse.decode(PhoneReaderService(h.core.sessions).handle(ReaderRequest(req, ReaderKind.HISTORY, id, 0).encode())!!)!!
    }

    private fun opened(id: String): WatchReaderState {
        val first = ask(id, "stale-r22-open-0001")
        assertTrue(first.ok)
        return WatchReaderState().select(id, first.reqId).onResponse(first)
    }

    private fun refreshed(state: WatchReaderState, id: String, req: String): WatchReaderState {
        val next = ask(id, req)
        assertTrue(next.ok)
        return state.refreshLatest(next.reqId)!!.onResponse(next)
    }

    private fun texts(state: WatchReaderState, id: String) = state.historyFor(id)!!.messages.map { it.text }

    @Test fun W01_aRowTheServerNowHidesIsDroppedWhenTheNewestShownRowsAreOlderThanIt() {
        val id = conversation()
        serve(id, row(1, "user", "question"), row(2, "assistant", "stale internal body"))
        val state = opened(id)
        assertEquals(listOf("question", "stale internal body"), texts(state, id))
        retag(id, 2, "internal_notification")
        val list = h.fake.rows.getValue(id).messages
        synchronized(list) { (3L..27L).forEach { list += row(it, "user", "machinery $it", kind = "internal_notification").put("session_id", id) } }
        val after = refreshed(state, id, "stale-r22-scan-0001")
        assertEquals(listOf("question"), texts(after, id))
    }

    @Test fun W02_aPageWithNothingShownClearsOnlyTheRowsItNames() {
        val id = conversation()
        serve(id, row(1, "user", "question"), row(2, "assistant", "stale internal body"))
        val state = opened(id)
        retag(id, 1, "hidden")
        retag(id, 2, "internal_notification")
        val after = refreshed(state, id, "stale-r22-empty-0001")
        assertEquals(emptyList<String>(), texts(after, id))
    }

    @Test fun W03_anotherIdentityWithTheSameWordsStaysVisible() {
        val id = conversation()
        serve(id, row(1, "assistant", "same words", uid = "uid-a"), row(2, "user", "same words"))
        val state = opened(id)
        assertEquals(listOf(1L, 2L), state.historyFor(id)!!.messages.map { it.rowId })
        retag(id, 1, "internal_notification")
        val after = refreshed(state, id, "stale-r22-twin-0001")
        assertEquals(listOf(2L), after.historyFor(id)!!.messages.map { it.rowId })
        assertEquals(listOf("user"), after.historyFor(id)!!.messages.map { it.role })
    }

    @Test fun W04_aStableUidThatMovedToANewPhysicalRowIsNotShownTwiceButAnotherUidIs() {
        val id = conversation()
        serve(id, row(5, "assistant", "same words", uid = "uid-a"))
        val state = opened(id)
        serve(id, row(9, "assistant", "same words", uid = "uid-a"), row(10, "assistant", "same words", uid = "uid-b"))
        val after = refreshed(state, id, "stale-r22-moved-0001")
        assertEquals(listOf(9L, 10L), after.historyFor(id)!!.messages.map { it.rowId })
    }

    @Test fun W05_aLongHiddenRunStillFitsOneDataLayerMessageAndKeepsTheShownRows() {
        val id = conversation()
        serve(id, row(1, "user", "question"), row(2, "assistant", "an answer"))
        val list = h.fake.rows.getValue(id).messages
        synchronized(list) { (3L..150L).forEach { list += row(it, "user", "machinery $it", kind = "internal_notification").put("session_id", id) } }
        val bytes = runBlocking { PhoneReaderService(h.core.sessions).handle(ReaderRequest("stale-r22-long-0001", ReaderKind.HISTORY, id, 0).encode())!! }
        assertTrue("${bytes.size}", bytes.size <= WatchReaderLimits.MAX_RESPONSE_BYTES)
        val response = ReaderResponse.decode(bytes)!!
        assertTrue("${response.error}", response.ok)
        assertEquals(listOf("question", "an answer"), response.messages.map { it.text })
    }
}
