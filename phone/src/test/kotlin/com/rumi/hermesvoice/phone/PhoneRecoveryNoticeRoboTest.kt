package com.rumi.hermesvoice.phone

import android.Manifest
import androidx.compose.ui.test.onAllNodesWithText
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Which reply-recovery notices the Phone draws beside Talk (parent HOLD P1 of the owner's UI contract of 2026-10-27): only a failure the
 * user must act on (notifications not allowed, device storage) is a line on the screen; a reply still owed to the Watch and an ordinary
 * connection retry of the recovery are diagnostics, not a line. The real FinalRecovery reconcile and drain, ReceivedConversations,
 * ReplyAlertNotifier, PhoneViewModel and MainActivity against the dashboard contract double; the Watch node and its (absent) receipt are
 * synthetic. Device storage failures stay covered by DurableBodiesR10RoboTest.B04 and ReceivedFinalR10RoboTest.N05.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1280dp")
class PhoneRecoveryNoticeRoboTest : PhoneNavRoboBase() {
    @After fun clearFaults() { fake.failListFromOffset = -1 }

    private fun shown(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun notice() = app.finalRecovery.notice.value.orEmpty()
    private fun clearSyntheticSeed(id: String) { val rows = fake.rows.getValue(id).messages; synchronized(rows) { rows.clear() } }

    @Test fun R27_01_aReplyStillOwedToTheWatchIsNotALineBesideTalk() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val a = titled("Watch owed", "owed")
        val vm = showWith(a)
        clearSyntheticSeed(a)
        val ns = app.bodyScope()!!
        assertTrue("owed to the Watch", app.received.scoped(ns) { expect(a, 1L, "watch-node", quiet = false) } == true)
        fake.injectFinalRow(a, "answer owed to the Watch", 101.0, "owed-1")
        assertTrue("the final is admitted", runBlocking { app.finalRecovery.observedNewest(app.wiring().core.sessions.history(a), ns) })
        app.finalRecovery.requestReconcile()
        waitFor("the reply is still owed to the Watch: ${notice()}") { notice().startsWith("Watch reply alerts pending") }
        settle()
        assertFalse("not drawn", shown("Watch reply alerts pending"))
        assertFalse("not labelled a storage failure", exists("storage_failure"))
        assertTrue(vm.state.value.signedIn); assertTrue(fake.prompts.isEmpty())
    }

    @Test fun R27_02_anOrdinaryConnectionRetryOfTheRecoveryIsNotALineBesideTalk() {
        titled("Alpha", "alpha")
        showing()
        fake.failListFromOffset = 0
        app.finalRecovery.requestReconcile()
        waitFor("the recovery run met the connection failure: ${notice()}") { notice().startsWith("Reply recovery pending") }
        settle()
        assertFalse("not drawn", shown("Reply recovery pending"))
        assertFalse(exists("storage_failure"))
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun R27_03_notificationsNotAllowedForANewReplyIsStillSaidBesideTalk() {
        val a = titled("Not allowed", "blocked")
        showWith(a)
        fake.injectFinalRow(a, "a reply that cannot alert", 201.0, "blocked-1")
        app.finalRecovery.requestReconcile()
        waitFor("the alert could not be posted: ${notice()}") { notice().startsWith("Reply alerts pending. Allow notifications") }
        settle()
        assertTrue("an action the user must take is said", shown("Allow notifications"))
        assertTrue(fake.prompts.isEmpty())
    }
}
