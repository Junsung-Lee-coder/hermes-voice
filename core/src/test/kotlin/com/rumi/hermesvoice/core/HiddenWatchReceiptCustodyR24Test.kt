package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.net.HistoryMessage
import com.rumi.hermesvoice.core.net.SuppressedRow
import com.rumi.hermesvoice.core.sessions.ReceivedConversations
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * A positively hidden receipt that was already sent to the Watch still owes the Phone one thing: the
 * authority to acknowledge the Watch's delayed "posted" receipt (watchReceiptAllowed). Capacity retirement
 * must not take that authority away. Public receive store only; rows arrive in supported 50-row pages.
 * Synthetic data; no Watch, server, device or UI.
 */
class HiddenWatchReceiptCustodyR24Test {
    private val ns = "scope-r24"
    private val session = "conversation-r24"
    private val node = "watch-node-r24"
    private val store = InMemoryKeyValueStore()
    private val received = ReceivedConversations(store) { ns }
    private fun cold() = ReceivedConversations(store) { ns }

    private fun message(row: Long, text: String = "answer $row") =
        HistoryMessage(row, "assistant", text, 1_000.0 + row, messageUid = "uid-$row", finishReason = "stop")
    private fun key(row: Long) = ReceivedConversations.identity(ns, session, row, "uid-$row")
    private fun saved() = JSONObject(store.getString(ReceivedConversations.KEY)!!)
    private fun rows(root: JSONObject = saved()) = root.getJSONObject("sessions").getJSONObject(session).getJSONObject("rows")
    private fun row(row: Long, root: JSONObject = saved()) = rows(root).optJSONObject(key(row))

    /** Rows [range] arrive in 50-row pages; those up to [toWatch] are sent to [node] (watch obligation owed). */
    private fun arrive(range: LongRange, toWatch: Long) {
        for (page in range.chunked(50)) {
            val owed = page.filter { it <= toWatch }
            val plain = page.filter { it > toWatch }
            if (owed.isNotEmpty()) assertTrue(received.ingest(session, owed.map { message(it) }, baseline = false, watch = true, watchNode = node))
            if (plain.isNotEmpty()) assertTrue(received.ingest(session, plain.map { message(it) }, baseline = false, watch = false))
        }
    }
    private fun hide(range: LongRange) {
        for (page in range.chunked(50)) assertTrue(received.reclassify(session, page.map { SuppressedRow(it, "uid-$it") }, emptyList()))
    }
    /** [owed] rows 1..owed carry an outstanding Watch obligation; every one of the 256 rows is later named hidden. */
    private fun fullHiddenTable(owed: Long) {
        arrive(1L..256L, toWatch = owed)
        hide(1L..256L)
        assertEquals(256, rows().length())
        assertFalse(received.unread(session))
        assertTrue("hidden rows are not pending, so nothing re-sends them", received.pending(true).isEmpty())
        for (r in 1L..owed) {
            assertTrue(row(r)!!.getBoolean("watch"))
            assertFalse(row(r)!!.getBoolean("watch_done"))
            assertTrue("the delayed posted receipt is acceptable for row $r", received.watchReceiptAllowed(key(r), node))
        }
    }

    @Test fun C01_aHiddenRowStillOwedByTheWatchKeepsItsReceiptAuthorityWhileSafeCapacityIsReclaimed() {
        fullHiddenTable(owed = 50)
        val before = saved()

        assertTrue(received.ingest(session, listOf(message(1_000, "fresh genuine final")), baseline = false, watch = false))

        val after = saved()
        assertEquals("storage stays bounded", 256, rows(after).length())
        for (r in 1L..50L) {
            assertTrue("owed hidden row $r is unchanged", row(r, before)!!.similar(row(r, after)))
            assertTrue("the Watch's delayed posted receipt for row $r is still acknowledgeable", received.watchReceiptAllowed(key(r), node))
        }
        assertNull("the oldest hidden row with no outstanding Watch obligation is the one retired", row(51, after))
        for (r in 52L..256L) assertTrue(row(r, before)!!.similar(row(r, after)))
        assertTrue(row(1_000, after)!!.getBoolean("final"))
        assertFalse(row(1_000, after)!!.getBoolean("phone"))
        assertEquals(listOf("uid-1000"), received.pending().map { it.getString("uid") })
        assertEquals(listOf("uid-1000"), cold().pending().map { it.getString("uid") })
        assertTrue(cold().watchReceiptAllowed(key(1), node))
        assertFalse("another node never gains the authority", received.watchReceiptAllowed(key(1), "other-node"))

        // The delayed receipt: acknowledging changes only watch_done, never read or phone.
        assertTrue(received.acknowledge(key(1), watch = true))
        val acked = row(1)!!
        assertTrue(acked.getBoolean("watch_done"))
        assertFalse(acked.getBoolean("read"))
        assertFalse(acked.getBoolean("phone"))
        assertTrue(acked.getBoolean("display_hidden"))
        assertTrue(received.pending(true).isEmpty())
    }

    @Test fun C02_aTableWhereEveryHiddenRowIsStillOwedByTheWatchRefusesAdmissionAndLosesNothing() {
        fullHiddenTable(owed = 256)
        val before = store.getString(ReceivedConversations.KEY)
        val failure = runCatching { received.ingest(session, listOf(message(1_000)), baseline = false, watch = false) }.exceptionOrNull()
        assertTrue("admission must be refused, not made by dropping an owed row: $failure", failure is IllegalStateException)
        assertEquals("Read messages to continue recovery", failure!!.message)
        assertEquals("a refused admission leaves durable state untouched", before, store.getString(ReceivedConversations.KEY))
        assertNull(row(1_000))
        for (r in 1L..256L) assertTrue("row $r keeps its receipt authority", received.watchReceiptAllowed(key(r), node))

        // Only when the Watch's receipt settles one obligation does that row become reclaimable.
        assertTrue(received.acknowledge(key(7), watch = true))
        assertTrue(received.ingest(session, listOf(message(1_000)), baseline = false, watch = false))
        val after = saved()
        assertNull("the settled row was reclaimed", row(7, after))
        assertEquals(256, rows(after).length())
        for (r in (1L..256L) - 7L) assertTrue(received.watchReceiptAllowed(key(r), node))
        assertNotNull(row(1_000, after))
    }

    @Test fun C03_unreadGenuineRowsAndOwedHiddenRowsTogetherAreNeverEvictedForAdmission() {
        // 100 visible unread rows, 156 hidden rows all owed to the Watch.
        arrive(1L..256L, toWatch = 256)
        hide(101L..256L)
        assertTrue(received.unread(session))
        val before = store.getString(ReceivedConversations.KEY)
        val failure = runCatching { received.ingest(session, listOf(message(1_000)), baseline = false, watch = false) }.exceptionOrNull()
        assertEquals("Read messages to continue recovery", failure?.message)
        assertEquals(before, store.getString(ReceivedConversations.KEY))
        assertEquals(256, rows().length())
        assertTrue(received.unread(session))
        for (r in 101L..256L) assertTrue(received.watchReceiptAllowed(key(r), node))
    }

    @Test fun C04_hiddenRowsWithASettledOrAbsentWatchObligationAreStillRetiredFirst() {
        // Rows 1..30 are owed to the Watch and 11..20 of them are already acknowledged; the rest are plain hidden receipts: rows that owe nothing go first, in row order.
        fullHiddenTable(owed = 30)
        for (r in 11L..20L) assertTrue(received.acknowledge(key(r), watch = true))
        for (n in 0 until 10) assertTrue(received.ingest(session, listOf(message(2_000L + n)), baseline = false, watch = false))
        val after = saved()
        for (r in 11L..20L) assertNull("settled row $r retired first", row(r, after))
        assertNotNull("the next candidate, a still-owed row 31 is not reached", row(31, after))
        for (r in listOf(1L, 2L, 10L, 21L, 30L)) assertNotNull("owed row $r survives", row(r, after))
        assertEquals(256, rows(after).length())
    }
}
