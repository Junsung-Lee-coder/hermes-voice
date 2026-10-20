package com.rumi.hermesvoice.phone

import androidx.lifecycle.ViewModelProvider
import com.rumi.hermesvoice.core.FakeHermesDashboard
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual PhoneApp -> TextSends -> ChatService -> WebSocket and actual retryFailedSend caller.
 * Platform/keystore/Data Layer are simulated. No real server/device or private user data. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class PromptUncertainRetryR7RoboTest : PhoneRoboBase() {
    @Before fun setUp() = baseSetUp()
    @After fun tearDown() = baseTearDown()

    @Test fun R08_acceptedReplyLostThenFirstCompletionAndExplicitRetryNeverResubmitsSameSend() {
        val work = conversation("work")
        val model = open(work)
        val accepted = AtomicBoolean(false)
        fake.turnBehavior = { "async_hold" }
        fake.beforeAsyncReply = { accepted.set(true); fake.dropGateway() }
        model.setDraft("R"); model.send()
        val id = app.textSends.ids.get()
        assertTrue("server accepted R, reply lost, caller terminated", await(20_000) { accepted.get() && model.state.value.sendsInFlight == 0 })
        // The original R completes remotely BEFORE the user presses the stale Retry callback.
        fake.pushLaterTurn(work, FakeHermesDashboard.complete("original-R-only"))
        fake.beforeAsyncReply = null; fake.turnBehavior = { "start_first" }
        fake.scripts[work] = { listOf(FakeHermesDashboard.complete("new-request-answer")) }
        model.retryFailedSend(id)
        assertTrue(await { model.state.value.sendsInFlight == 0 })
        assertEquals("POSSIBLY_SENT cannot offer/resubmit the same request through real Retry", listOf("R"), prompts())
        assertTrue("not an unsent FailedSend", model.state.value.failedSends.none { it.send.id == id })
        assertFalse("uncertain submission must not say Not sent", model.state.value.status.contains("Not sent", ignoreCase = true))
        model.setDraft("R"); model.send() // independently composed NEW request is still usable
        assertTrue(await { prompts().size == 2 && model.state.value.sendsInFlight == 0 })
        assertEquals(listOf("R", "R"), prompts())
    }
    @Test fun R09_definitiveRefusalStillOffersExactlyOneRetry() {
        val work = conversation("work")
        val model = open(work)
        fake.turnBehavior = { "not_owned" }
        model.setDraft("unsent"); model.send()
        assertTrue(await { model.state.value.failedSends.size == 1 && model.state.value.sendsInFlight == 0 })
        val id = model.state.value.failedSends.single().send.id
        fake.turnBehavior = { "start_first" }
        fake.scripts[work] = { listOf(FakeHermesDashboard.complete("retried-unsent-answer")) }
        model.retryFailedSend(id)
        assertTrue(await { prompts().size == 1 && model.state.value.sendsInFlight == 0 })
        assertEquals(listOf("unsent"), prompts())
        assertEquals("refusal + one explicit retry frame", 2, fake.rpcLog.count { it.optString("method") == "prompt.submit" })
        assertEquals(listOf(work), fake.notOwnedRefusals.toList())
        assertTrue(model.state.value.failedSends.isEmpty())
    }
}
