package com.rumi.hermesvoice.phone

import com.rumi.hermesvoice.core.net.HistoryPage
import com.rumi.hermesvoice.core.sessions.ReceivedConversations
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Producer-boundary guards for the unchanged N04 model/cache witness. Real repository history,
 * Android HistoryPage.parse, FinalRecovery.observedNewest and durable ReceivedConversations.
 * No Activity is mounted: fetching cannot supply a foreground content-display receipt.
 * Synthetic data only. These tests are compiled and executed in the native Phone suite.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1280dp")
class PhoneUnreadVisibilityGuardR22RoboTest : PhoneNavRoboBase() {
    /** The same producer called asynchronously by showNewest/mergeLatest, awaited here so
     * a projection assertion cannot accidentally test IO scheduling instead of its contract. */
    private fun observe(id: String): HistoryPage = runBlocking {
        val page = app.wiring().core.sessions.history(id)
        app.finalRecovery.observedNewest(page, app.bodyScope())
        page
    }

    private fun saved(id: String, rowId: Long, uid: String = ""): JSONObject {
        val ns = app.bodyScope()!!
        val root = JSONObject(app.localStore.getString(ReceivedConversations.KEY)!!)
        assertEquals(ns, root.getString("scope"))
        return root.getJSONObject("sessions").getJSONObject(id).getJSONObject("rows")
            .getJSONObject(ReceivedConversations.identity(ns, id, rowId, uid))
    }

    private fun cold() = ReceivedConversations(app.localStore, app::bodyScope)

    private fun clearSyntheticSeed(id: String) {
        val rows = fake.rows.getValue(id).messages
        synchronized(rows) { rows.clear() }
    }

    private fun assertUnspent(before: JSONObject, after: JSONObject) {
        // Visibility may change independently. None of these authority fields is a visibility bit.
        for (field in listOf("identity", "session_id", "row_id", "uid", "timestamp", "final",
            "read", "baseline", "quiet", "private_origin", "phone", "watch", "node", "watch_done")) {
            assertEquals("visibility must not spend or rewrite $field", before.get(field), after.get(field))
        }
    }

    @Test fun U01_aGenuineArrivalAfterCreationRemainsUnreadAcrossFetchReplayAndReceiveStoreRestart() {
        val id = titled("Genuine unread", "genuine-unread-guard")
        assertEquals("creation establishes an empty arrival frontier, not historic bootstrap", 0L, app.received.cursor(id) ?: -1L)
        clearSyntheticSeed(id)
        fake.injectFinalRow(id, "a genuine answer", 100.0, "genuine-unread")
        val page = observe(id)
        assertEquals(listOf("a genuine answer"), page.messages.map { it.text })
        assertTrue(page.suppressed.isEmpty())
        val before = saved(id, 1, "genuine-unread")
        assertFalse(before.getBoolean("read"))
        assertFalse(before.getBoolean("baseline"))
        assertFalse(before.getBoolean("phone"))
        assertTrue(app.received.unread(id))
        assertEquals(100.0, app.received.receivedAt(id), 0.0)

        observe(id)
        assertUnspent(before, saved(id, 1, "genuine-unread"))
        assertTrue("a successful repeated fetch is not a read", app.received.unread(id))
        assertTrue("durable receive restart is not a read either", cold().unread(id))
        assertTrue(app.received.pending().any { it.getString("uid") == "genuine-unread" })
        assertFalse(app.presence.isVisible(id))
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun U02_aHiddenOnlyPageChangesAttentionEligibilityWithoutAReadBaselineRewriteOrDeviceAcknowledgment() {
        val id = titled("Hidden attention", "hidden-attention-guard")
        val ns = app.bodyScope()!!
        // Explicit synthetic canonical final address, owing both Phone and Watch receipts.
        assertTrue(app.received.scoped(ns) { expect(id, 1, "watch-guard", quiet = false) } == true)
        val rows = fake.rows.getValue(id).messages
        synchronized(rows) {
            rows.clear()
            rows += JSONObject().put("id", 1).put("session_id", id).put("role", "assistant")
                .put("content", "previously visible synthetic body").put("timestamp", 100.0).put("finish_reason", "stop")
        }
        observe(id)
        val before = saved(id, 1)
        assertFalse(before.getBoolean("read"))
        assertFalse(before.getBoolean("baseline"))
        assertFalse(before.getBoolean("phone"))
        assertTrue(before.getBoolean("watch"))
        assertFalse(before.getBoolean("watch_done"))
        assertTrue(app.received.unread(id))
        val receivedAt = app.received.receivedAt(id)

        synchronized(rows) { rows.single().put("display_kind", "hidden") }
        val hidden = observe(id)
        assertTrue(hidden.messages.isEmpty())
        assertEquals(listOf(1L), hidden.rawIds)
        assertEquals(1, hidden.returned)
        assertEquals(listOf(1L), hidden.suppressed.map { it.rowId })
        assertEquals(listOf(""), hidden.suppressed.map { it.uid })
        assertUnspent(before, saved(id, 1))
        assertEquals("reclassification is not a new receive order", receivedAt, app.received.receivedAt(id), 0.0)
        assertFalse("the positively hidden identity is not unread displayable content", app.received.unread(id))
        assertFalse("the visibility correction survives a receive-store restart without a fabricated read", cold().unread(id))
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun U03_aMovedHiddenUidCannotReadItsSameTextNeighbourOrTheSameUidInAnotherConversation() {
        val id = titled("Identity attention", "identity-attention-guard")
        val other = titled("Other unread", "other-unread-guard")
        clearSyntheticSeed(id)
        clearSyntheticSeed(other)
        fake.injectFinalRow(id, "same words", 100.0, "hidden-uid")
        fake.injectFinalRow(id, "same words", 101.0, "genuine-uid")
        fake.injectFinalRow(other, "same words", 102.0, "hidden-uid")
        observe(id)
        observe(other)
        val targetBefore = saved(id, 1, "hidden-uid")
        val neighbourBefore = saved(id, 2, "genuine-uid")
        val otherBefore = saved(other, 1, "hidden-uid")
        val receivedAt = app.received.receivedAt(id)
        val rows = fake.rows.getValue(id).messages
        synchronized(rows) {
            rows.first { it.getString("message_uid") == "hidden-uid" }
                .put("id", 30).put("display_kind", "internal_notification")
        }
        val page = observe(id)
        assertEquals(listOf(2L), page.messages.map { it.rowId })
        assertEquals(listOf(30L), page.suppressed.map { it.rowId })
        assertEquals(listOf("hidden-uid"), page.suppressed.map { it.uid })
        assertUnspent(targetBefore, saved(id, 1, "hidden-uid"))
        assertUnspent(neighbourBefore, saved(id, 2, "genuine-uid"))
        assertUnspent(otherBefore, saved(other, 1, "hidden-uid"))
        assertEquals(receivedAt, app.received.receivedAt(id), 0.0)
        assertTrue("the same-text genuine neighbour is still unread", app.received.unread(id))
        assertTrue("the other conversation is still unread", app.received.unread(other))

        // Explicit exact-row read-receipt fixture at the store boundary; not a claim that a UI
        // was mounted here. Only this genuine neighbour is acknowledged, never the hidden row.
        assertTrue(app.received.scoped(app.bodyScope()!!) { displayed(id, page.messages) } == true)
        assertTrue(saved(id, 2, "genuine-uid").getBoolean("read"))
        assertUnspent(targetBefore, saved(id, 1, "hidden-uid"))
        assertUnspent(otherBefore, saved(other, 1, "hidden-uid"))
        assertFalse("only the hidden target is left without a read; it must not keep attention bold", app.received.unread(id))
        assertTrue("a receipt for one conversation never reads another", cold().unread(other))
        assertTrue(fake.prompts.isEmpty())
    }
}
