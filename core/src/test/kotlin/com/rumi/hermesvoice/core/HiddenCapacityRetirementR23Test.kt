package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.net.HistoryMessage
import com.rumi.hermesvoice.core.net.SuppressedRow
import com.rumi.hermesvoice.core.sessions.ReceivedConversations
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Recovery capacity versus positively hidden receipts, through the public receive store only.
 * Rows arrive in supported 50-row history pages, are stored under their stable identities and are
 * later named hidden by a page's suppressed list. Synthetic data; no server, device or UI.
 */
class HiddenCapacityRetirementR23Test {
    private val ns = "scope-r23"
    private val session = "conversation-r23"
    private val store = InMemoryKeyValueStore()
    private val received = ReceivedConversations(store) { ns }
    private fun cold() = ReceivedConversations(store) { ns }

    private fun message(row: Long, text: String = "answer $row", finish: String? = "stop") =
        HistoryMessage(row, "assistant", text, 1_000.0 + row, messageUid = "uid-$row", finishReason = finish)
    private fun hiddenIds(rows: LongRange) = rows.map { SuppressedRow(it, "uid-$it") }

    /** The page loop of FinalRecovery: 50 raw rows per page, ingest, then the page's identities. */
    private fun arrive(rows: LongRange, finish: (Long) -> String? = { "stop" }) {
        for (page in rows.chunked(50)) {
            assertTrue(received.ingest(session, page.map { message(it, finish = finish(it)) }, baseline = false, watch = false))
        }
    }
    private fun hide(rows: LongRange) {
        for (page in rows.chunked(50)) assertTrue(received.reclassify(session, hiddenIds(page.first()..page.last()), emptyList()))
    }
    private fun saved(store: KeyValueStore = this.store): JSONObject = JSONObject(store.getString(ReceivedConversations.KEY)!!)
    private fun rows(root: JSONObject = saved()) = root.getJSONObject("sessions").getJSONObject(session).getJSONObject("rows")
    private fun key(row: Long) = ReceivedConversations.identity(ns, session, row, "uid-$row")
    private fun row(row: Long, root: JSONObject = saved()) = rows(root).optJSONObject(key(row))

    /** Legacy receipts: ingested while shown (finals and unfinished legacy rows), then stamped hidden. */
    private fun fillHidden(count: Int) {
        val range = 1L..count.toLong()
        arrive(range) { if (it % 4 == 0L) null else "stop" }
        hide(range)
        assertEquals(count, rows().length())
        assertFalse(received.unread(session))
        assertTrue(received.pending().isEmpty())
    }

    @Test fun H01_aNewGenuineFinalIsAdmittedWhenEveryRecoverySlotHoldsAPositivelyHiddenReceipt() {
        fillHidden(ReceivedConversations.MAX_ROWS)
        val before = saved()
        assertEquals(256, rows(before).length())

        assertTrue("a genuine final must be admitted", received.ingest(session, listOf(message(1_000, "fresh genuine final")), baseline = false, watch = false))

        val after = saved()
        assertEquals("storage stays bounded", ReceivedConversations.MAX_ROWS, rows(after).length())
        assertTrue(store.getString(ReceivedConversations.KEY)!!.toByteArray().size <= ReceivedConversations.MAX_BYTES)
        val fresh = row(1_000, after)!!
        assertTrue(fresh.getBoolean("final"))
        assertFalse(fresh.getBoolean("read"))
        assertFalse(fresh.getBoolean("baseline"))
        assertFalse("owes the Phone alert", fresh.getBoolean("phone"))
        assertEquals("fresh genuine final", fresh.getString("text"))
        assertTrue(received.unread(session))
        assertEquals(listOf("uid-1000"), received.pending().map { it.getString("uid") })
        assertFalse("hiding is not delivery: no hidden row became pending", received.pending().any { it.getLong("row_id") != 1_000L })
        // Restart: a cold store over the same durable bytes agrees.
        assertTrue(cold().unread(session))
        assertEquals(listOf("uid-1000"), cold().pending().map { it.getString("uid") })
        assertEquals(1_000.0 + 1_000, received.receivedAt(session), 0.0)
    }

    @Test fun H02_onlyTheOldestHiddenReceiptIsRetiredAndNoSurvivorChanges() {
        fillHidden(ReceivedConversations.MAX_ROWS)
        val before = saved()
        val cursor = before.getJSONObject("sessions").getJSONObject(session).getLong("cursor")
        assertTrue(received.ingest(session, listOf(message(1_000)), baseline = false, watch = false))
        val after = saved()

        assertNull("the lowest hidden row id is the one retired", row(1, after))
        for (id in 2L..256L) {
            assertTrue("hidden receipt $id is unchanged: not read, posted, ACKed or reordered",
                row(id, before)!!.similar(row(id, after)))
            val survivor = row(id, after)!!
            assertTrue(survivor.getBoolean("display_hidden"))
            assertFalse(survivor.getBoolean("read"))
        }
        assertEquals(255, (2L..256L).count { row(it, after) != null })
        val state = after.getJSONObject("sessions").getJSONObject(session)
        assertEquals("no fake receive cursor", cursor, state.getLong("cursor"))
        assertEquals(0, state.getJSONArray("expected").length())
        assertFalse("nothing was marked read", rows(after).keys().asSequence().any { rows(after).getJSONObject(it).getBoolean("read") })
    }

    @Test fun H03_protectedUnreadRowsAreNeverRetiredToMakeRoom() {
        // A full recovery table of genuine, visible, unread rows is the real protected condition.
        arrive(1L..256L)
        val before = store.getString(ReceivedConversations.KEY)
        val failure = runCatching { received.ingest(session, listOf(message(1_000)), baseline = false, watch = false) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("Read messages to continue recovery", failure!!.message)
        assertEquals("a refused admission leaves durable state untouched", before, store.getString(ReceivedConversations.KEY))
        assertNull(row(1_000))
        assertEquals(256, rows().length())
        assertTrue(received.unread(session))
    }

    @Test fun H04_hiddenReceiptsAreRetiredFirstAndOnceOnlyUnreadOnesRemainTheTableIsProtected() {
        // 200 genuine unread rows plus 56 hidden ones fill the table.
        arrive(1L..256L)
        hide(201L..256L)
        for (n in 0 until 56) {
            assertTrue(received.ingest(session, listOf(message(2_000L + n, "new $n")), baseline = false, watch = false))
        }
        val full = saved()
        assertEquals(256, rows(full).length())
        for (id in 201L..256L) assertNull("hidden receipt $id retired", row(id, full))
        for (id in 1L..200L) assertFalse("unread row $id survives", row(id, full)!!.getBoolean("read"))
        assertEquals(256, rows(full).keys().asSequence().count { !rows(full).getJSONObject(it).optBoolean("display_hidden") })
        // The 57th arrival has nothing hidden left to retire and is refused like any protected overflow.
        val before = store.getString(ReceivedConversations.KEY)
        val failure = runCatching { received.ingest(session, listOf(message(3_000)), baseline = false, watch = false) }.exceptionOrNull()
        assertEquals("Read messages to continue recovery", failure?.message)
        assertEquals(before, store.getString(ReceivedConversations.KEY))
    }

    @Test fun H05_aReceiptShownAgainIsProtectedAgainAndNotRetired() {
        fillHidden(ReceivedConversations.MAX_ROWS)
        // The server shows row 1 again: the durable hidden bit is removed by the positive shown identity.
        assertTrue(received.reclassify(session, emptyList(), listOf(message(1))))
        assertFalse(row(1)!!.has("display_hidden"))
        assertTrue(received.ingest(session, listOf(message(1_000)), baseline = false, watch = false))
        assertNotNull("a shown row is not a capacity victim", row(1))
        assertNull("the next oldest hidden receipt is retired instead", row(2))
        assertTrue(received.unread(session))
    }

    @Test fun H06_aPendingShownProjectionProtectsItsRowFromRetirement() {
        fillHidden(ReceivedConversations.MAX_ROWS)
        val projection = received.projectVisibility(ns, session, emptyList(), listOf(message(1)))
        assertNotNull(projection)
        try {
            assertTrue(received.ingest(session, listOf(message(1_000)), baseline = false, watch = false))
        } finally { received.visibilitySettled(projection) }
        assertNotNull("a row the current page shows is not retired", row(1))
        assertNull(row(2))
    }

    @Test fun H07_repeatedGenuineArrivalsConsumeOnlyHiddenReceiptsAndStayBounded() {
        fillHidden(ReceivedConversations.MAX_ROWS)
        for (n in 0 until 256) {
            assertTrue("arrival $n", received.ingest(session, listOf(message(5_000L + n, "genuine $n")), baseline = false, watch = false))
            assertTrue(rows().length() <= ReceivedConversations.MAX_ROWS)
        }
        assertEquals(256, rows().length())
        assertEquals("every hidden receipt was retired, none was read", 0,
            rows().keys().asSequence().count { rows().getJSONObject(it).optBoolean("display_hidden") })
        assertEquals(256, cold().pending().size)
        val refused = runCatching { received.ingest(session, listOf(message(9_000)), baseline = false, watch = false) }.exceptionOrNull()
        assertEquals("genuine unread arrivals remain protected", "Read messages to continue recovery", refused?.message)
    }
}
