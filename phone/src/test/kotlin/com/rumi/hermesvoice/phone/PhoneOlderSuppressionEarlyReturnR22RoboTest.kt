package com.rumi.hermesvoice.phone

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Real PhoneViewModel.open/loadOlder -> repository -> Android parser, using the existing dashboard
 * double. No private reducer invocation or replacement pagination algorithm. Synthetic rows only.
 * Suppression is authoritative even when raw pagination is held as SUSPECT or refused as STALLED.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1280dp")
class PhoneOlderSuppressionEarlyReturnR22RoboTest : PhoneNavRoboBase() {
    private val sameWords = "same words"

    private fun model(store: ViewModelStore) = ViewModelProvider(
        store, ViewModelProvider.AndroidViewModelFactory.getInstance(app)
    )[PhoneViewModel::class.java]

    private fun ids(vm: PhoneViewModel) = vm.state.value.history.map { it.rowId }

    /** Exactly three raw pages, with distinct stable identities even where their words agree. */
    private fun initialRows(id: String) {
        val rows = fake.rows.getValue(id).messages
        synchronized(rows) {
            rows.clear()
            for (n in 1..150) {
                val role = if (n == 53 || n == 103) "user" else "assistant"
                val text = if (n in 51..54 || n in 101..104) sameWords else "row-$n"
                rows += JSONObject().put("id", n).put("session_id", id).put("role", role)
                    .put("content", text).put("timestamp", 1_700_000_000.0 + n)
                    .put("message_uid", "uid-$n").put("finish_reason", "stop")
            }
        }
    }

    private fun retag(id: String, rowId: Long, kind: String) {
        val rows = fake.rows.getValue(id).messages
        synchronized(rows) { rows.first { it.getLong("id") == rowId }.put("display_kind", kind) }
    }

    private fun openNewest(vm: PhoneViewModel, id: String) {
        vm.open(app.wiring().core.sessions.registry.find(id)!!)
        waitFor("the real newest raw page is shown") {
            !vm.state.value.opening && vm.state.value.rawLoaded == 50 && ids(vm) == (101L..150L).toList()
        }
        assertTrue(vm.state.value.hasOlder)
        assertEquals(101L, vm.state.value.olderBound)
        assertNotNull(vm.state.value.historyNamespace)
        assertEquals(app.bodyScope(), vm.state.value.historyNamespace)
    }

    private fun genuineSameTextRowsRemain(vm: PhoneViewModel, assistantId: Long, userId: Long) {
        val assistant = vm.state.value.history.single { it.rowId == assistantId }
        val user = vm.state.value.history.single { it.rowId == userId }
        assertEquals("assistant", assistant.role)
        assertEquals("uid-$assistantId", assistant.messageUid)
        assertEquals(sameWords, assistant.text)
        assertEquals("user", user.role)
        assertEquals("uid-$userId", user.messageUid)
        assertEquals(sameWords, user.text)
    }

    @Test fun O01_honestArrivalDriftCommitsSuppressionOnBothSuspectReturnsAndDoesNotResurrectIt() {
        val id = titled("Older suppression drift", "suppression-drift")
        initialRows(id)
        val store = ViewModelStore()
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val vm = model(store)
            openNewest(vm, id)
            vm.loadOlder()
            waitFor("a normal older page joined before the drift") {
                !vm.state.value.olderLoading && vm.state.value.rawLoaded == 100 && ids(vm) == (51L..150L).toList()
            }
            val before = vm.state.value
            retag(id, 101, "internal_notification")
            retag(id, 51, "async_delegation_complete")
            // Honest raw-offset drift: the previously loaded 100 rows shift back by two pages.
            seed(id, 100, "arrived-without-refresh")
            fake.messagesHook = { session, offset, _ ->
                if (session == id && offset == 200) {
                    reached.countDown()
                    check(release.await(30, TimeUnit.SECONDS))
                }
                0
            }
            vm.loadOlder()
            waitFor("both known full pages were processed before the unseen page is answered") { reached.count == 0L }
            val whileHeld = vm.state.value
            release.countDown()
            waitFor("the unseen page joined and the held raw pages were passed") {
                !vm.state.value.olderLoading && vm.state.value.rawLoaded == 250
            }
            val after = vm.state.value

            assertEquals(listOf(50, 100, 150, 200), olderOffsets(id))
            assertEquals("only the first held raw page has been passed", 150, whileHeld.rawLoaded)
            assertEquals(51L, whileHeld.olderBound)
            assertEquals(before.olderEpoch, whileHeld.olderEpoch)
            assertTrue(whileHeld.olderLoading)
            assertTrue(whileHeld.hasOlder)
            assertFalse(whileHeld.olderError)
            assertEquals(before.historyNamespace, whileHeld.historyNamespace)
            assertEquals(before.pendingSends, whileHeld.pendingSends)
            assertEquals(
                "both SUSPECT returns must commit positively named removals, not just a raw cursor",
                (51L..150L).filterNot { it == 51L || it == 101L },
                whileHeld.history.map { it.rowId },
            )
            assertEquals("the following ADDED page cannot resurrect either stale body",
                (1L..150L).filterNot { it == 51L || it == 101L }, ids(vm))
            assertEquals(1L, after.olderBound)
            assertEquals(before.olderEpoch, after.olderEpoch)
            assertTrue("a full raw page does not prove the end", after.hasOlder)
            assertFalse(after.olderError)
            assertEquals(before.historyNamespace, after.historyNamespace)
            assertEquals(before.pendingSends, after.pendingSends)
            genuineSameTextRowsRemain(vm, 52, 53)
            genuineSameTextRowsRemain(vm, 102, 103)
            assertTrue(fake.prompts.isEmpty())
        } finally {
            release.countDown()
            fake.messagesHook = null
            store.clear()
        }
    }

    @Test fun O02_aRepeatedRawPageCommitsItsNewSuppressionWhileStillStoppingWithRetry() {
        val id = titled("Older suppression refusal", "suppression-refusal")
        initialRows(id)
        val store = ViewModelStore()
        val second = CountDownLatch(1)
        try {
            val vm = model(store)
            openNewest(vm, id)
            val before = vm.state.value
            retag(id, 101, "internal_notification")
            // Existing refusing-server fixture: both older offsets return the same newest raw ids.
            fake.messagesOffsetOverride = { session, offset -> if (session == id && offset > 0) 0 else offset }
            fake.messagesHook = { session, offset, _ ->
                if (session == id && offset == 100) {
                    // New evidence only on the second response: fixing SUSPECT alone is insufficient.
                    retag(id, 104, "async_delegation_complete")
                    second.countDown()
                }
                0
            }
            vm.loadOlder()
            waitFor("the repeated full raw page stopped the walk with Retry") {
                vm.state.value.olderError && !vm.state.value.olderLoading
            }
            val after = vm.state.value
            assertEquals(0L, second.count)
            assertEquals(listOf(50, 100), olderOffsets(id))
            assertEquals("a refused page cannot advance the cursor", before.rawLoaded, after.rawLoaded)
            assertEquals(before.olderBound, after.olderBound)
            assertEquals(before.olderEpoch, after.olderEpoch)
            assertTrue("refusal is not end-of-history", after.hasOlder)
            assertEquals(before.historyNamespace, after.historyNamespace)
            assertEquals(before.pendingSends, after.pendingSends)
            assertEquals("STALLED must not discard the second page's new authoritative removal",
                (101L..150L).filterNot { it == 101L || it == 104L }, ids(vm))
            genuineSameTextRowsRemain(vm, 102, 103)
            settle()
            assertEquals("no automatic hot loop after Retry is offered", listOf(50, 100), olderOffsets(id))
            assertTrue(fake.prompts.isEmpty())
        } finally {
            fake.messagesHook = null
            fake.messagesOffsetOverride = null
            store.clear()
        }
    }
}
