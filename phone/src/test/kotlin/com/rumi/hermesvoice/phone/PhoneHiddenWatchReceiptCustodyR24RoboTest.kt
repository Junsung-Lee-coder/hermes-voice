package com.rumi.hermesvoice.phone

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.net.HistoryPage
import com.rumi.hermesvoice.core.sessions.ReceivedConversations
import com.rumi.hermesvoice.core.watchlink.ReaderKind
import com.rumi.hermesvoice.core.watchlink.ReaderResponse
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A positively hidden receipt that was already sent to the Watch must keep the Phone's authority to
 * acknowledge the Watch's delayed "posted" receipt while capacity is reclaimed from other hidden rows.
 * Real repository history, HistoryPage.parse, FinalRecovery.observedNewest, the Phone reader enrichment that
 * hands finals to the Watch, and the actual PhoneWatchListenerService receipt path. Rows arrive in 50-row
 * history pages. The only synthetic Watch behaviour is the receipt message the Watch would send. No Activity,
 * device or prompt. Synthetic data only; not installed-device proof.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1280dp")
class PhoneHiddenWatchReceiptCustodyR24RoboTest : PhoneNavRoboBase() {
    private val node = "watch-node"
    private val wire = ConcurrentLinkedQueue<Triple<String, String, ByteArray>>()

    private fun captureWire() {
        val client = mockk<MessageClient>()
        every { Wearable.getMessageClient(any<Context>()) } returns client
        every { client.sendMessage(any(), any(), any()) } answers { wire += Triple(firstArg(), secondArg(), thirdArg<ByteArray>().copyOf()); Tasks.forResult(1) }
    }

    private fun observe(id: String): Pair<HistoryPage, Boolean> = runBlocking {
        val page = app.wiring().core.sessions.history(id)
        page to app.finalRecovery.observedNewest(page, app.bodyScope())
    }

    private fun clearSyntheticSeed(id: String) {
        val rows = fake.rows.getValue(id).messages
        synchronized(rows) { rows.clear() }
    }

    private fun saved(): JSONObject = JSONObject(app.localStore.getString(ReceivedConversations.KEY)!!)
    private fun savedRows(id: String): JSONObject = saved().getJSONObject("sessions").getJSONObject(id).getJSONObject("rows")
    private fun key(id: String, rowId: Int) = ReceivedConversations.identity(app.bodyScope()!!, id, rowId.toLong(), "legacy-$rowId")
    private fun savedRow(id: String, rowId: Int): JSONObject? = savedRows(id).optJSONObject(key(id, rowId))
    private fun cold() = ReceivedConversations(app.localStore, app::bodyScope)

    /** Android's org.json has no similar(): equal key sets and equal values, independent of key order. */
    private fun same(a: JSONObject, b: JSONObject): Boolean {
        val keys = a.keys().asSequence().toSet()
        return keys == b.keys().asSequence().toSet() && keys.all { a.get(it).toString() == b.get(it).toString() }
    }

    /** Rows [first, first+count) arrive as shown finals; rows up to [owed] are expected on the Watch node first. */
    private fun arrive(id: String, first: Int, count: Int, owed: Int) {
        val ns = app.bodyScope()!!
        for (n in first until first + count) {
            if (n <= owed) assertTrue(app.received.scoped(ns) { expect(id, n.toLong(), node, quiet = false) } == true)
            fake.injectFinalRow(id, "answer $n", 100.0 + n, "legacy-$n")
        }
        val (page, ok) = observe(id)
        assertTrue("legacy page admitted", ok)
        assertEquals(count, page.messages.count { it.messageUid.startsWith("legacy-") && it.rowId >= first })
    }

    private fun stamp(id: String, first: Int, count: Int, kind: String) {
        val rows = fake.rows.getValue(id).messages
        synchronized(rows) {
            for (row in rows) {
                val n = row.getString("message_uid").removePrefix("legacy-").toIntOrNull() ?: continue
                if (n in first until first + count) row.put("display_kind", kind)
            }
        }
    }

    /** The Phone's real hand-off of owed finals to the Watch: the reader response enrichment. */
    private fun sentToWatch(id: String): List<String> {
        val ns = app.bodyScope()!!
        val enriched = app.finalRecovery.reader(node, ReaderResponse("reader-r24", ReaderKind.HISTORY, true, sessionId = id, scope = app.sessionListScope(), bodyScope = ns))
        return enriched.finals.map { it.getString("identity") }
    }

    /** 256 receipts in five 50-row pages and a page of six. Rows up to [owed] are owed to the Watch. */
    private fun fill(id: String, owed: Int, hideAsYouGo: Boolean): List<String> {
        var first = 1
        var round = 0
        var handed = emptyList<String>()
        while (first <= ReceivedConversations.MAX_ROWS) {
            val count = minOf(50, ReceivedConversations.MAX_ROWS - first + 1)
            arrive(id, first, count, owed)
            if (round == 0 && owed > 0) handed = sentToWatch(id)
            if (hideAsYouGo) {
                stamp(id, first, count, if (round % 2 == 0) "hidden" else "internal_notification")
                val (page, ok) = observe(id)
                assertTrue(ok)
                assertTrue(page.messages.none { it.rowId >= first })
            }
            first += count
            round++
        }
        assertEquals(ReceivedConversations.MAX_ROWS, savedRows(id).length())
        return handed
    }

    private fun deliverReceipt(posted: List<String>, from: String = node) {
        val ns = app.bodyScope()!!
        val controller = Robolectric.buildService(PhoneWatchListenerService::class.java).create()
        try {
            val event = mockk<MessageEvent>()
            every { event.path } returns FinalRecovery.RECEIPT
            every { event.sourceNodeId } returns from
            every { event.data } returns JSONObject().put("v", 1).put("scope", ns).put("posted", JSONArray(posted)).put("read", JSONArray()).toString().toByteArray()
            controller.get().onMessageReceived(event)
        } finally { controller.destroy() }
    }

    private fun acknowledgedOnWire(): Set<String> = wire.filter { it.first == node && it.second == FinalRecovery.RECEIPT_ACK }
        .flatMap { m -> JSONObject(String(m.third)).getJSONArray("posted").let { a -> (0 until a.length()).map(a::getString) } }.toSet()

    @Test fun W01_aHiddenRowAlreadySentToTheWatchKeepsItsReceiptAuthorityWhileSafeHiddenRowsAreRetired() {
        captureWire()
        val id = titled("Hidden Watch custody", "hidden-watch-w01")
        clearSyntheticSeed(id)
        val handed = fill(id, owed = 50, hideAsYouGo = true)
        assertEquals("the Phone really handed the 50 owed finals to the Watch node", (1..50).map { key(id, it) }.toSet(), handed.toSet())
        assertFalse(app.received.unread(id))
        assertTrue("hidden rows are no longer offered to the Watch", app.received.pending(true).isEmpty())
        val before = savedRows(id)

        fake.injectFinalRow(id, "a genuine answer after the hidden machinery", 9_000.0, "legacy-257")
        val (page, ok) = observe(id)
        assertTrue(page.messages.any { it.messageUid == "legacy-257" })
        assertTrue("the genuine final must be admitted: ${app.finalRecovery.notice.value}", ok)

        val rows = savedRows(id)
        assertEquals(ReceivedConversations.MAX_ROWS, rows.length())
        for (n in 1..50) {
            assertNotNull("hidden row $n owed to the Watch must not be retired", savedRow(id, n))
            assertTrue("hidden row $n owed to the Watch survives unchanged", same(savedRow(id, n)!!, before.getJSONObject(key(id, n))))
        }
        assertNull("the oldest hidden row that owes the Watch nothing was retired", savedRow(id, 51))
        for (n in 52..256) assertTrue(same(savedRow(id, n)!!, before.getJSONObject(key(id, n))))
        assertNotNull(savedRow(id, 257))
        assertFalse(savedRow(id, 257)!!.getBoolean("phone"))

        // The Watch's delayed posted receipt arrives through the real listener: it is acknowledged, not dropped.
        deliverReceipt((1..50).map { key(id, it) }, from = "other-node")
        settle()
        assertTrue("a different node earns nothing", acknowledgedOnWire().isEmpty())
        assertFalse(savedRow(id, 1)!!.getBoolean("watch_done"))
        deliverReceipt((1..50).map { key(id, it) })
        waitFor("every delayed posted receipt is acknowledged back to its Watch") { acknowledgedOnWire().size == 50 }
        waitFor("watch_done is recorded") { (1..50).all { savedRow(id, it)?.optBoolean("watch_done") == true } }
        for (n in 1..50) {
            val row = savedRow(id, n)!!
            assertTrue(row.getBoolean("display_hidden"))
            assertFalse("a receipt is not a read", row.getBoolean("read"))
            assertFalse("and not a Phone post", row.getBoolean("phone"))
        }
        assertEquals(setOf(257L), app.received.pending().map { it.getLong("row_id") }.toSet())
        assertTrue(cold().unread(id))
        assertEquals(ReceivedConversations.MAX_ROWS, savedRows(id).length())
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun W02_aTableOfHiddenRowsAllOwedToTheWatchRefusesAdmissionUntilAReceiptSettlesOne() {
        captureWire()
        val id = titled("Hidden Watch all owed", "hidden-watch-w02")
        clearSyntheticSeed(id)
        fill(id, owed = 256, hideAsYouGo = true)
        val before = app.localStore.getString(ReceivedConversations.KEY)

        fake.injectFinalRow(id, "a genuine answer with every slot owed", 9_000.0, "legacy-257")
        val (_, refused) = observe(id)
        assertFalse("no device obligation may be dropped to admit it", refused)
        assertEquals("Receive state not saved. Check storage and refresh.", app.finalRecovery.notice.value)
        assertEquals("a refused admission leaves the durable record untouched", before, app.localStore.getString(ReceivedConversations.KEY))
        assertNull(savedRow(id, 257))
        assertTrue(cold().watchReceiptAllowed(key(id, 1), node))

        deliverReceipt(listOf(key(id, 1)))
        waitFor("the one delayed receipt is acknowledged") { acknowledgedOnWire() == setOf(key(id, 1)) }
        waitFor("watch_done recorded") { savedRow(id, 1)?.optBoolean("watch_done") == true }
        val settled = savedRows(id)

        val (_, admitted) = observe(id)
        assertTrue("with one obligation settled the genuine final fits: ${app.finalRecovery.notice.value}", admitted)
        assertNull("the settled row is the one reclaimed", savedRow(id, 1))
        assertNotNull(savedRow(id, 257))
        for (n in 2..256) assertTrue(same(savedRow(id, n)!!, settled.getJSONObject(key(id, n))))
        assertEquals(ReceivedConversations.MAX_ROWS, savedRows(id).length())
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun W03_aNewestPageThatNamesTheHiddenRowsAndCarriesTheNewFinalIsAdmittedInOneObservation() {
        captureWire()
        val id = titled("Hidden same newest page", "hidden-watch-w03")
        clearSyntheticSeed(id)
        fill(id, owed = 0, hideAsYouGo = false)
        assertTrue(app.received.unread(id))
        val before = savedRows(id)
        stamp(id, 1, 256, "internal_notification")
        fake.injectFinalRow(id, "a genuine answer on the same newest page", 9_000.0, "legacy-257")

        val (page, ok) = observe(id)
        assertEquals("newest page carries 49 hidden identities and the genuine row", 49, page.suppressed.size)
        assertTrue("one observation names the hidden rows and admits the final: ${app.finalRecovery.notice.value}", ok)
        assertNotNull(savedRow(id, 257))
        assertEquals(ReceivedConversations.MAX_ROWS, savedRows(id).length())
        val retired = (1..256).filter { savedRow(id, it) == null }
        assertEquals(1, retired.size)
        assertTrue("the retired row was named hidden by this very page", retired.single() >= 208)
        for (n in (1..256) - retired.toSet()) {
            val row = savedRow(id, n)!!
            val hidden = n >= 208
            assertEquals("only the rows named by this page are hidden yet", hidden, row.optBoolean("display_hidden"))
            row.remove("display_hidden")
            assertTrue(same(row, before.getJSONObject(key(id, n))))
        }
        assertTrue(fake.prompts.isEmpty())
    }
}
