package com.rumi.hermesvoice.phone

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import com.rumi.hermesvoice.core.chat.FailedSend
import com.rumi.hermesvoice.core.chat.PendingSends
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). A failed send belongs to ITS conversation: it is shown and retryable only while that conversation
 * is the selected one, and a retry is admitted atomically from one state snapshot with that conversation's own loaded history as the
 * baseline. Runs the REAL MainActivity (production projection of the chat pane), PhoneViewModel, PhoneApp wiring and ChatService against
 * the Hermes dashboard contract double. Simulated: the Data Layer (mockk) and the keystore; no device, no real network.
 *
 * The contract double records a rejected submit's user row before it answers (a real rejected submit stores none), so every scenario
 * reloads the conversation after the failure; the baseline checked is then the highest row the screen actually holds.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class PhoneRetryScopeRoboTest : PhoneRoboBase() {
    val rule = createEmptyComposeRule()
    @get:Rule val cleanupThenCompose: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(ComposeDispatcherCleanup()).around(rule)

    @Before fun setUp() = baseSetUp()
    @After fun tearDown() = baseTearDown()

    override fun pump() { idle(50); rule.waitForIdle() }

    private fun showing(session: String): PhoneViewModel {
        controller = Robolectric.buildActivity(MainActivity::class.java, replyTap(session)).setup()
        idle(300)
        settle()
        val model = ViewModelProvider(controller!!.get())[PhoneViewModel::class.java]
        assertTrue("the conversation is selected", await { model.state.value.selected?.storedSessionId == session })
        return model
    }

    private fun userRows(model: PhoneViewModel, text: String) = model.state.value.history.count { it.role == "user" && it.text == text }

    private fun nodes(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun reload(model: PhoneViewModel, session: String, userRowsOfOk: Int) {
        model.open(app.wiring().core.sessions.activeConversation(session)!!)
        assertTrue("history of $session reloaded", await {
            model.state.value.selected?.storedSessionId == session && userRows(model, "ok") == userRowsOfOk
        })
    }

    /** Conversation [a] holds two OLDER canonical identical rows and an exactly UNSENT identical request.
     * The independent historical row preserves the original duplicate-row/baseline geometry without inventing an effect for a refusal. */
    private class Scenario(val a: String, val b: String, val model: PhoneViewModel, val failed: FailedSend, val inFlight: Int) { val failedId get() = failed.send.id }

    private fun failedInA(): Scenario {
        val a = conversation("alpha")
        val b = conversation("bravo")
        val model = showing(a)
        model.setDraft("ok")
        model.send()
        assertTrue("the older send reached Hermes", await { prompts() == listOf("ok") })
        settle()
        fake.injectRow(a, "user", "ok") // independent older stored row, NOT the refused request
        fake.turnBehavior = { "not_owned" }
        model.setDraft("ok")
        model.send()
        assertTrue("the second send failed and is kept", await { model.state.value.failedSends.size == 1 })
        fake.turnBehavior = { "start_first" }
        reload(model, a, userRowsOfOk = 2)
        assertTrue("older bubble gone, failed row still kept", await { model.state.value.pendingSends.isEmpty() })
        return Scenario(a, b, model, model.state.value.failedSends.single(), model.state.value.sendsInFlight)
    }

    @Test fun R01_theFailedRowIsShownOnlyInsideItsOwnConversation_andReturnsWithIt() {
        val s = failedInA()
        assertEquals("shown in its own conversation", 1, nodes("failed_send_${s.failedId}"))
        assertEquals(1, nodes("retry_${s.failedId}"))
        openOwned(s.model, s.b)
        assertTrue("not shown in another conversation", await { nodes("failed_send_${s.failedId}") == 0 })
        assertEquals("no retry control for it either", 0, nodes("retry_${s.failedId}"))
        assertEquals("but the failed send is kept", 1, s.model.state.value.failedSends.size)
        openOwned(s.model, s.a)
        assertTrue("shown again when its conversation returns", await { nodes("failed_send_${s.failedId}") == 1 })
    }

    @Test fun R02_aRetryFromAnotherConversationIsRefusedAndNothingIsChanged() {
        val s = failedInA()
        val keptBefore = s.model.state.value.failedSends.single()
        openOwned(s.model, s.b)
        s.model.setDraft("bravo draft")
        val file = File(app.cacheDir, "b-pick.png").also { it.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)) }
        s.model.addAttachment(android.net.Uri.fromFile(file))
        assertTrue(await { s.model.state.value.attachments.size == 1 })
        val attachments = s.model.state.value.attachments
        val promptsBefore = prompts().size
        s.model.retryFailedSend(s.failedId)
        settle()
        val after = s.model.state.value
        assertSame("the kept failed send is untouched", keptBefore, after.failedSends.single())
        assertTrue("nothing was admitted", after.pendingSends.isEmpty())
        assertEquals(s.inFlight, after.sendsInFlight)
        assertEquals("no request reached Hermes", promptsBefore, prompts().size)
        assertEquals("bravo's draft is untouched", "bravo draft", after.draft)
        assertSame("bravo's attachments are untouched", attachments, after.attachments)
        assertEquals(s.b, after.selected?.storedSessionId)
    }

    @Test fun R03_afterReturningToItsConversationTheRetryHasItsOwnBaseline_oldIdenticalRowsNeverClaimIt() {
        val s = failedInA()
        val model = s.model
        openOwned(model, s.b)
        model.retryFailedSend(s.failedId)
        assertTrue("refused while the other conversation is shown", model.state.value.pendingSends.isEmpty())
        reload(model, s.a, userRowsOfOk = 2)
        val oldRows = model.state.value.history
        val newest = oldRows.maxOf { it.rowId }
        val promptsBefore = prompts().size
        val latch = CountDownLatch(1)
        fake.turnBehavior = { latch.await(20, TimeUnit.SECONDS); "start_first" }
        model.setDraft("typed meanwhile")
        model.retryFailedSend(s.failedId)
        val admitted = model.state.value
        assertEquals(1, admitted.pendingSends.size)
        assertEquals("baseline is this conversation's loaded history, not zero", newest, admitted.pendingSends.single().baselineRowId)
        assertSame("immutable original payload", s.failed.send, admitted.pendingSends.single().send)
        assertEquals("ok", admitted.pendingSends.single().send.text)
        assertEquals(s.failedId, admitted.pendingSends.single().send.id)
        assertEquals(s.a, admitted.pendingSends.single().send.sessionId)
        assertTrue("admitted rows leave the failed list", admitted.failedSends.isEmpty())
        assertEquals("the draft is not touched", "typed meanwhile", admitted.draft)
        assertEquals(s.inFlight + 1, admitted.sendsInFlight)
        assertEquals("the older identical rows cannot claim the retry",
            1, PendingSends.reconcile(admitted.pendingSends, s.a, oldRows).size)
        latch.countDown()
        assertTrue("it reaches Hermes through the normal path, once", await { prompts().size == promptsBefore + 1 && prompts().last() == "ok" })
        reload(model, s.a, userRowsOfOk = 3)
        assertTrue("its own new row reconciles it one-for-one", await { model.state.value.pendingSends.isEmpty() })
        assertEquals("three canonical rows, none duplicated", 3, userRows(model, "ok"))
        assertTrue(model.state.value.failedSends.isEmpty())
        assertEquals("typed meanwhile", model.state.value.draft)
        settle()
        assertEquals("exactly one request for the retry", promptsBefore + 1, prompts().size)
    }

    @Test fun R04_aStaleCallbackFromTheOldConversationIsRefused() {
        val s = failedInA()
        val stale: (Long) -> Unit = s.model::retryFailedSend
        openOwned(s.model, s.b)
        val promptsBefore = prompts().size
        stale(s.failedId)
        settle()
        assertEquals(1, s.model.state.value.failedSends.size)
        assertTrue(s.model.state.value.pendingSends.isEmpty())
        assertEquals(promptsBefore, prompts().size)
    }

    @Test fun R05_aRetryTappedWhileNavigatingAwayIsEitherAdmittedForItsOwnConversationOrRefused_neverMisattributed() {
        val s = failedInA()
        val model = s.model
        val newest = model.state.value.history.maxOf { it.rowId }
        val promptsBefore = prompts().size
        val latch = CountDownLatch(1)
        fake.turnBehavior = { latch.await(20, TimeUnit.SECONDS); "start_first" }
        val bHistoryEntered = CountDownLatch(1)
        val releaseBHistory = CountDownLatch(1)
        val bHistoryExited = CountDownLatch(1)
        val previousMessagesHook = fake.messagesHook
        fake.messagesHook = { rowId, offset, limit ->
            if (rowId == s.b) {
                bHistoryEntered.countDown()
                try {
                    assertTrue("B history is released within the bounded fixture window", releaseBHistory.await(20, TimeUnit.SECONDS))
                } finally {
                    bHistoryExited.countDown()
                }
            }
            previousMessagesHook?.invoke(rowId, offset, limit) ?: 0
        }
        try {
            model.open(app.wiring().core.sessions.activeConversation(s.b)!!)
            assertTrue("the actual B history request entered", await { bHistoryEntered.count == 0L })
            assertEquals("B history is still held before Retry admission", 1L, bHistoryExited.count)
            assertEquals("the page of the other conversation has not landed: A is still selected", s.a, model.state.value.selected?.storedSessionId)
            model.retryFailedSend(s.failedId)
            val pending = model.state.value.pendingSends.single()
            assertEquals(s.a, pending.send.sessionId)
            assertEquals("A's own baseline", newest, pending.baselineRowId)
            assertEquals("B history stayed held through A's actual Retry admission", 1L, bHistoryExited.count)
            releaseBHistory.countDown()
            assertTrue(await { model.state.value.selected?.storedSessionId == s.b })
            assertEquals("the other conversation shows nothing of A", 0, nodes("failed_send_${s.failedId}"))
            assertTrue(model.state.value.pendingSends.filter { it.send.sessionId == s.b }.isEmpty())
            latch.countDown()
            assertTrue(await { prompts().size == promptsBefore + 1 })
            openOwned(model, s.a)
            assertTrue(await { userRows(model, "ok") == 3 && model.state.value.pendingSends.isEmpty() })
            settle()
            assertEquals(promptsBefore + 1, prompts().size)
        } finally {
            releaseBHistory.countDown()
            latch.countDown()
            fake.messagesHook = previousMessagesHook
        }
    }

    @Test fun R06_aSecondTapOnTheSameRetryIsRefused_oneAdmissionOneDispatch() {
        val s = failedInA()
        val model = s.model
        val promptsBefore = prompts().size
        val latch = CountDownLatch(1)
        fake.turnBehavior = { latch.await(20, TimeUnit.SECONDS); "start_first" }
        model.retryFailedSend(s.failedId)
        model.retryFailedSend(s.failedId)
        assertEquals(1, model.state.value.pendingSends.size)
        assertEquals(s.inFlight + 1, model.state.value.sendsInFlight)
        latch.countDown()
        assertTrue(await { prompts().size == promptsBefore + 1 })
        settle()
        assertEquals("a double tap sends once", promptsBefore + 1, prompts().size)
    }

    @Test fun R07_concurrentTapsAdmitOnce() {
        val s = failedInA()
        val model = s.model
        val promptsBefore = prompts().size
        val latch = CountDownLatch(1)
        fake.turnBehavior = { latch.await(20, TimeUnit.SECONDS); "start_first" }
        val threads = 8
        val barrier = CyclicBarrier(threads)
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val workers = List(threads) {
            Thread {
                try { barrier.await(10, TimeUnit.SECONDS); model.retryFailedSend(s.failedId) } catch (error: Throwable) { errors += error }
            }.also { it.start() }
        }
        workers.forEach { it.join(15_000) }
        assertTrue("no tap threw: $errors", errors.isEmpty())
        assertEquals("one admission", 1, model.state.value.pendingSends.size)
        assertEquals(s.inFlight + 1, model.state.value.sendsInFlight)
        latch.countDown()
        assertTrue(await { prompts().size == promptsBefore + 1 })
        settle()
        assertEquals("one dispatch", promptsBefore + 1, prompts().size)
    }

    @Test fun R08_aRetryThatFailsAgainReturnsTheSameImmutableRequestToItsOwnConversationOnly() {
        val s = failedInA()
        val model = s.model
        val original = model.state.value.failedSends.single()
        fake.turnBehavior = { "not_owned" }
        model.setDraft("draft stays")
        model.retryFailedSend(s.failedId)
        assertTrue("failed again", await { model.state.value.failedSends.size == 1 && model.state.value.sendsInFlight == s.inFlight })
        val again = model.state.value.failedSends.single()
        assertSame("same immutable request object", original.send, again.send)
        assertEquals("ok", again.send.text)
        assertTrue(model.state.value.pendingSends.isEmpty())
        assertEquals("draft stays", model.state.value.draft)
        assertTrue("shown again in its conversation", await { nodes("failed_send_${s.failedId}") == 1 })
        openOwned(model, s.b)
        assertTrue(await { nodes("failed_send_${s.failedId}") == 0 })
    }

    @Test fun R09_theRetryButtonOfTheShownConversationStillRetries_andAttachmentsAreKeptByIdentity() {
        val a = conversation("alpha")
        val b = conversation("bravo")
        val model = showing(a)
        val file = File(app.cacheDir, "a-pick.png").also { it.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)) }
        model.addAttachment(android.net.Uri.fromFile(file))
        assertTrue(await { model.state.value.attachments.size == 1 })
        fake.turnBehavior = { "not_owned" }
        model.setDraft("with file")
        model.send()
        assertTrue(await { model.state.value.failedSends.size == 1 })
        val failed = model.state.value.failedSends.single()
        assertEquals(1, failed.send.attachments.size)
        openOwned(model, b)
        model.retryFailedSend(failed.send.id)
        assertSame("refused: the kept request is the very same", failed, model.state.value.failedSends.single())
        openOwned(model, a)
        assertTrue(await { nodes("retry_${failed.send.id}") == 1 })
        val latch = CountDownLatch(1)
        fake.turnBehavior = { latch.await(20, TimeUnit.SECONDS); "start_first" }
        val promptsBefore = prompts().size
        rule.onAllNodesWithTag("retry_${failed.send.id}")[0].performClick()
        assertTrue("the visible Retry button admitted it", await { model.state.value.failedSends.isEmpty() })
        val pending = model.state.value.pendingSends.single { it.send.id == failed.send.id }
        assertSame("same attachments, not copied or replaced", failed.send.attachments, pending.send.attachments)
        assertEquals("with file", pending.send.text)
        latch.countDown()
        assertTrue(await { prompts().size == promptsBefore + 1 && prompts().last().contains("with file") })
    }
}
