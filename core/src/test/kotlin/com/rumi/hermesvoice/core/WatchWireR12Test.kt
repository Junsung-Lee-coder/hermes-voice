package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.watchlink.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** The Phone-to-Watch history wire carries no whole-response assistant control, advances over raw control-only pages like any other
 * hidden page, and keeps a genuine answer that merely mentions a marker. Real REST parser/repository/PhoneReaderService + wire/cache; synthetic dashboard only. */
class WatchWireR12Test {
    private val h = CoreHarness()
    @After fun close() = h.close()
    private fun add(id: String, role: String, body: String): Long {
        val list = h.fake.rows.getValue(id).messages
        synchronized(list) {
            val n = list.size + 1L
            list += JSONObject().put("id", n).put("role", role).put("content", body).put("timestamp", 1700000000.0 + n)
            return n
        }
    }
    private suspend fun create() = h.core.sessions.createConversation("Synthetic", "synthetic", "").storedSessionId
    private suspend fun reader(id: String) = ReaderResponse.decode(PhoneReaderService(h.core.sessions).handle(
        ReaderRequest("r12-history", ReaderKind.HISTORY, id).encode())!!)!!

    @Test fun W01_controlOnlyRawPagesAdvanceThroughTheRealReaderWireAndNeverReachTheWatchCache() = runBlocking {
        val id = create()
        val seed = reader(id).nextOffset
        val a = add(id, "user", "older human")
        val b = add(id, "assistant", "older answer")
        repeat(60) { add(id, "assistant", if (it % 2 == 0) "NO_REPLY" else "[SILENT]") }
        val page = reader(id)
        assertTrue(page.ok)
        assertEquals(listOf(a, b), page.messages.map { it.rowId })
        assertEquals(seed + 62, page.nextOffset)
        assertFalse(page.hasOlder)
        val cached = WatchReaderState().select(id, page.reqId).onResponse(ReaderResponse.decode(page.encode())!!)
        assertEquals(listOf("older human", "older answer"), cached.selectedHistory!!.messages.map { it.text })
        assertTrue(h.fake.prompts.isEmpty())
    }

    @Test fun W02_aGenuineAnswerThatMentionsTheMarkerStaysOnTheWireAsAReceivedRow() = runBlocking {
        val id = create()
        add(id, "assistant", "NO_REPLY")
        val kept = add(id, "assistant", "NO_REPLY is the marker I use.")
        val page = reader(id)
        assertTrue(page.ok)
        assertEquals(listOf(kept), page.messages.map { it.rowId })
        assertEquals(listOf("NO_REPLY is the marker I use."), page.messages.map { it.text })
        assertTrue(page.messages.single().received)
    }
}
