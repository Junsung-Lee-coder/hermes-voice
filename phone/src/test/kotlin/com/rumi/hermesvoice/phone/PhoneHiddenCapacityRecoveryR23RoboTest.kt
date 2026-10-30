package com.rumi.hermesvoice.phone

import android.Manifest
import android.app.NotificationManager
import com.rumi.hermesvoice.core.net.HistoryPage
import com.rumi.hermesvoice.core.sessions.ReceivedConversations
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Recovery capacity against positively hidden receipts, through the real repository history,
 * HistoryPage.parse, FinalRecovery.observedNewest / reconcile and the durable receive store.
 * Rows arrive in 50-row history pages as ordinary answers and the server later names them hidden.
 * No Activity is mounted and no prompt is submitted. Synthetic data only; not installed-device proof.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1280dp")
class PhoneHiddenCapacityRecoveryR23RoboTest : PhoneNavRoboBase() {
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
    private fun savedRow(id: String, rowId: Long, uid: String): JSONObject? =
        savedRows(id).optJSONObject(ReceivedConversations.identity(app.bodyScope()!!, id, rowId, uid))
    private fun cold() = ReceivedConversations(app.localStore, app::bodyScope)

    /** Android's org.json has no similar(): equal key sets and equal values, independent of key order. */
    private fun same(a: JSONObject, b: JSONObject): Boolean {
        val keys = a.keys().asSequence().toSet()
        return keys == b.keys().asSequence().toSet() && keys.all { a.get(it).toString() == b.get(it).toString() }
    }

    private fun alerts() = app.getSystemService(NotificationManager::class.java).activeNotifications
        .filter { it.notification.channelId in setOf("reply_arrival", "reply_arrival_quiet") && !it.tag.endsWith("#notice") }

    /** Rows [first, first+count) arrive as ordinary shown finals; one 50-row newest page is observed. */
    private fun arrive(id: String, first: Int, count: Int) {
        repeat(count) { n -> fake.injectFinalRow(id, "answer ${first + n}", 100.0 + first + n, "legacy-${first + n}") }
        val (page, ok) = observe(id)
        assertTrue("legacy page admitted", ok)
        assertEquals(count, page.messages.count { it.messageUid.startsWith("legacy-") && it.rowId >= first })
    }

    /** The server later stamps the same stored rows with a typed hidden kind. */
    private fun stamp(id: String, first: Int, count: Int, kind: String) {
        val rows = fake.rows.getValue(id).messages
        synchronized(rows) {
            for (row in rows) {
                val n = row.getString("message_uid").removePrefix("legacy-").toIntOrNull() ?: continue
                if (n in first until first + count) row.put("display_kind", kind)
            }
        }
    }

    /** 256 legacy receipts in five 50-row pages and a final page of six. */
    private fun fill(id: String, hideAsYouGo: Boolean) {
        var first = 1
        var round = 0
        while (first <= ReceivedConversations.MAX_ROWS) {
            val count = minOf(50, ReceivedConversations.MAX_ROWS - first + 1)
            arrive(id, first, count)
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
    }

    @Test fun P01_aNewGenuineFinalIsRecoveredWhenAllRecoverySlotsHoldPositivelyHiddenReceipts() {
        val id = titled("Hidden capacity", "hidden-capacity-p01")
        clearSyntheticSeed(id)
        fill(id, hideAsYouGo = true)
        assertFalse(app.received.unread(id))
        assertTrue(app.received.pending().isEmpty())
        val receivedBefore = app.received.receivedAt(id)
        val before = savedRows(id)

        fake.injectFinalRow(id, "a genuine answer after the hidden machinery", 9_000.0, "genuine-fresh")
        val (page, ok) = observe(id)
        assertTrue(page.messages.any { it.messageUid == "genuine-fresh" })
        assertTrue("the genuine final must be admitted: ${app.finalRecovery.notice.value}", ok)
        assertTrue(app.finalRecovery.notice.value?.contains("not saved") != true)

        val rows = savedRows(id)
        assertEquals("storage stays at the recovery bound", ReceivedConversations.MAX_ROWS, rows.length())
        val fresh = savedRow(id, 257, "genuine-fresh")!!
        assertTrue(fresh.getBoolean("final"))
        assertFalse(fresh.getBoolean("read"))
        assertFalse(fresh.getBoolean("baseline"))
        assertFalse(fresh.getBoolean("phone"))
        assertNull("the oldest hidden receipt was retired for capacity", savedRow(id, 1, "legacy-1"))
        for (n in 2..256) {
            val row = savedRow(id, n.toLong(), "legacy-$n")!!
            assertTrue("hidden receipt $n unchanged: no read, post, Watch ACK or reorder",
                same(row, before.getJSONObject(ReceivedConversations.identity(app.bodyScope()!!, id, n.toLong(), "legacy-$n"))))
            assertTrue(row.getBoolean("display_hidden"))
            assertFalse(row.getBoolean("read"))
        }
        assertTrue(app.received.unread(id))
        assertEquals(9_000.0, app.received.receivedAt(id), 0.0)
        assertTrue(receivedBefore < app.received.receivedAt(id))
        assertEquals(listOf("genuine-fresh"), app.received.pending().map { it.getString("uid") })
        // Receive-store restart over the same durable bytes.
        assertTrue(cold().unread(id))
        assertEquals(listOf("genuine-fresh"), cold().pending().map { it.getString("uid") })

        // Delivery eligibility through the real recovery caller: one alert, for the genuine final only.
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { withTimeout(20_000) { app.finalRecovery.reconcile() } }
        assertEquals(1, alerts().size)
        assertTrue(savedRow(id, 257, "genuine-fresh")!!.getBoolean("phone"))
        assertFalse("posting is not a read", savedRow(id, 257, "genuine-fresh")!!.getBoolean("read"))
        assertTrue(app.received.pending().isEmpty())
        assertEquals(ReceivedConversations.MAX_ROWS, savedRows(id).length())
        for (n in 2..256) {
            val row = savedRow(id, n.toLong(), "legacy-$n")!!
            assertTrue("hidden receipt $n was never posted or read, and kept every field",
                same(row, before.getJSONObject(ReceivedConversations.identity(app.bodyScope()!!, id, n.toLong(), "legacy-$n"))))
        }
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun P02_theSamePageThatNamesTheHiddenReceiptsFreesTheSlotForItsGenuineFinal() {
        val id = titled("Hidden same page", "hidden-capacity-p02")
        clearSyntheticSeed(id)
        fill(id, hideAsYouGo = false)
        assertTrue("every legacy receipt is still visible and owes an alert", app.received.unread(id))
        val before = savedRows(id)
        // Only now does the server name all of them hidden, and a genuine final arrives behind them.
        stamp(id, 1, 256, "internal_notification")
        fake.injectFinalRow(id, "a genuine answer on the same page", 9_000.0, "genuine-fresh")
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        runBlocking { withTimeout(30_000) { app.finalRecovery.reconcile() } }

        val rows = savedRows(id)
        assertEquals(ReceivedConversations.MAX_ROWS, rows.length())
        assertTrue("recovery reconciled: ${app.finalRecovery.notice.value}", app.finalRecovery.notice.value?.contains("could not be reconciled") != true)
        val fresh = savedRow(id, 257, "genuine-fresh")
        assertNotNull("the genuine final was admitted by recovery", fresh)
        assertEquals("exactly one alert, for the genuine final", 1, alerts().size)
        assertTrue(fresh!!.getBoolean("phone"))
        assertFalse(fresh.getBoolean("read"))
        val retired = (1..256).filter { savedRow(id, it.toLong(), "legacy-$it") == null }
        assertEquals("one hidden receipt retired", 1, retired.size)
        assertTrue("the retired one was named hidden by the newest page", retired.single() >= 208)
        for (n in (1..256) - retired.toSet()) {
            val row = savedRow(id, n.toLong(), "legacy-$n")!!
            val old = before.getJSONObject(ReceivedConversations.identity(app.bodyScope()!!, id, n.toLong(), "legacy-$n"))
            assertTrue("receipt $n is positively hidden", row.getBoolean("display_hidden"))
            row.remove("display_hidden")
            assertTrue("receipt $n kept its read/phone/watch fields exactly", same(row, old))
        }
        assertTrue("the posted genuine final is still unread until it is displayed", app.received.unread(id))
        assertTrue(app.received.pending().isEmpty())
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun P03_protectedVisibleUnreadRowsStillRefuseANewFinalAndStayUntouched() {
        val id = titled("Protected unread", "hidden-capacity-p03")
        clearSyntheticSeed(id)
        fill(id, hideAsYouGo = false)
        val before = app.localStore.getString(ReceivedConversations.KEY)
        fake.injectFinalRow(id, "a genuine answer with no room", 9_000.0, "genuine-fresh")
        val (page, ok) = observe(id)
        assertTrue(page.messages.any { it.messageUid == "genuine-fresh" })
        assertFalse("nothing may be evicted to make room for it", ok)
        assertEquals("Receive state not saved. Check storage and refresh.", app.finalRecovery.notice.value)
        assertEquals("a refused admission leaves the durable record untouched", before, app.localStore.getString(ReceivedConversations.KEY))
        assertNull(savedRow(id, 257, "genuine-fresh"))
        assertEquals(ReceivedConversations.MAX_ROWS, savedRows(id).length())
        assertTrue(app.received.unread(id))
        assertTrue(cold().unread(id))
        assertTrue(fake.prompts.isEmpty())
    }
}
