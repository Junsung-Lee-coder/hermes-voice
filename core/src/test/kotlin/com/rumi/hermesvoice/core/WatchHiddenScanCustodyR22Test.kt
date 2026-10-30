package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.watchlink.PhoneReaderService
import com.rumi.hermesvoice.core.watchlink.ReaderError
import com.rumi.hermesvoice.core.watchlink.ReaderKind
import com.rumi.hermesvoice.core.watchlink.ReaderRequest
import com.rumi.hermesvoice.core.watchlink.ReaderResponse
import com.rumi.hermesvoice.core.watchlink.WatchReaderLimits
import com.rumi.hermesvoice.core.watchlink.WatchReaderState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Real reader producer/wire/consumer; synthetic identities, no device or live server. */
class WatchHiddenScanCustodyR22Test {
    private val h = CoreHarness()
    @After fun close() = h.close()

    private fun conversation() = runBlocking {
        h.core.sessions.createConversation("Scan custody", "scan-custody", "").storedSessionId
    }
    private fun row(id: Long, role: String, uid: String, hidden: Boolean = false) =
        JSONObject().put("id", id).put("role", role).put("content", "same words")
            .put("timestamp", 1_700_000_000.0 + id).put("message_uid", uid).put("finish_reason", "stop")
            .apply { if (hidden) put("display_kind", "internal_notification") }
    private fun serve(id: String, rows: List<JSONObject>) {
        val stored = h.fake.rows.getValue(id).messages
        synchronized(stored) { stored.clear(); rows.forEach { stored += it.put("session_id", id) } }
    }
    private fun ask(id: String, req: String): ByteArray = runBlocking {
        PhoneReaderService(h.core.sessions).handle(ReaderRequest(req, ReaderKind.HISTORY, id).encode())!!
    }
    private fun opened(id: String): WatchReaderState {
        val response = ReaderResponse.decode(ask(id, "scan-custody-open-001"))!!
        assertTrue(response.ok)
        return WatchReaderState().select(id, response.reqId).onResponse(response)
    }

    private fun longScan(moved: Boolean) {
        val id = conversation()
        serve(id, listOf(row(1, "assistant", "genuine-uid"), row(2, "user", "human-uid"), row(150, "assistant", "hidden-target")))
        val cached = opened(id)
        assertEquals(listOf(1L, 2L, 150L), cached.historyFor(id)!!.messages.map { it.rowId })
        // The first scanned page names the newest stale target. More than 64 older hidden
        // identities follow it before the eighth (last permitted) page exposes the controls.
        serve(id, listOf(row(1, "assistant", "genuine-uid"), row(2, "user", "human-uid")) +
            (3L..150L).map { n -> row(n, "assistant", if (n == 150L) { if (moved) "other-hidden" else "hidden-target" }
                else if (moved && n == 149L) "hidden-target" else "hidden-$n", hidden = true) })
        val bytes = ask(id, "scan-custody-refresh-001")
        assertTrue("bounded response: ${bytes.size}", bytes.size <= WatchReaderLimits.MAX_RESPONSE_BYTES)
        val response = ReaderResponse.decode(bytes)!!
        assertTrue("${response.error}", response.ok)
        assertEquals(150, response.nextOffset)
        assertFalse(response.hasOlder)
        val after = cached.refreshLatest(response.reqId)!!.onResponse(response)
        assertEquals("positive suppression survives the complete bounded scan", listOf(1L, 2L), after.historyFor(id)!!.messages.map { it.rowId })
        assertEquals(listOf("assistant", "user"), after.historyFor(id)!!.messages.map { it.role })
        assertEquals(listOf("same words", "same words"), after.historyFor(id)!!.messages.map { it.text })
        assertTrue(h.fake.prompts.isEmpty())
    }

    @Test fun C01_newestSuppressedIdentityIsNotDroppedAfterMoreThan64OlderHiddenRows() = longScan(false)
    @Test fun C02_newestMovedUidIsRetractedWithoutHidingItsSameTextHumanAndDistinctUidNeighbours() = longScan(true)

    @Test fun C03_oversizedIdentityMetadataReturnsABoundedRefusalNotAnOversizedDataLayerMessage() {
        val id = conversation()
        serve(id, listOf(row(1, "user", "human")) + (2L..150L).map {
            row(it, "assistant", "long-$it-" + "\uD83D\uDE42".repeat(1024), hidden = true)
        })
        val bytes = ask(id, "scan-custody-oversize-001")
        assertTrue("identity metadata cannot bypass the payload ceiling: ${bytes.size}", bytes.size <= WatchReaderLimits.MAX_RESPONSE_BYTES)
        val response = ReaderResponse.decode(bytes)!!
        assertFalse("unrepresentable authoritative identity set is an honest read refusal", response.ok)
        assertEquals(ReaderError.UNAVAILABLE, response.error)
        assertEquals(id, response.sessionId)
        assertTrue(response.messages.isEmpty())
        assertTrue(h.fake.prompts.isEmpty())
    }
}
