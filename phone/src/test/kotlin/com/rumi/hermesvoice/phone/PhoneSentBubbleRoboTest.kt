package com.rumi.hermesvoice.phone

import com.rumi.hermesvoice.core.FakeHermesDashboard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). Immediate sent bubbles through the REAL MainActivity, PhoneViewModel, PhoneApp wiring and ChatService.
 * Replies are withheld (scripts = empty) until the test pushes them, so what the screen holds before any answer is observable.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class PhoneSentBubbleRoboTest : PhoneRoboBase() {
    @Before fun setUp() = baseSetUp()
    @After fun tearDown() = baseTearDown()

    private fun userRows(model: PhoneViewModel, text: String) = model.state.value.history.count { it.role == "user" && it.text == text }

    @Test fun B01_theSentMessageIsInTheConversationTheInstantItIsSent_composerClearedAndNothingSentTwice() {
        val work = conversation("work")
        val model = open(work)
        model.setDraft("hello there")
        model.send()
        val state = model.state.value
        assertEquals("shown in the same step as the tap, before any backend work", 1, state.pendingSends.size)
        assertEquals("hello there", state.pendingSends.single().send.text)
        assertEquals(work, state.pendingSends.single().send.sessionId)
        assertEquals("the composer is empty at once", "", state.draft)
        assertTrue("it still reaches Hermes exactly once", await { prompts() == listOf("hello there") })
        settle()
        assertEquals("one submission, no extra request", listOf("hello there"), prompts())
    }

    @Test fun B02_theServersOwnRowReplacesTheBubbleWithoutDuplicationEvenBeforeAnyAnswer() {
        val work = conversation("work")
        val model = open(work)
        model.setDraft("please run it")
        model.send()
        assertTrue("Hermes has the message", await { prompts() == listOf("please run it") })
        model.open(app.wiring().core.sessions.activeConversation(work)!!)
        assertTrue("the refreshed history carries the canonical user row and the bubble is gone",
            await { userRows(model, "please run it") == 1 && model.state.value.pendingSends.isEmpty() })
        settle()
        assertEquals("exactly one user row, text unmutated", 1, userRows(model, "please run it"))
        assertTrue(model.state.value.pendingSends.isEmpty())
        fake.pushLaterTurn(work, FakeHermesDashboard.complete("Done."), withStart = false)
        assertTrue(await { model.state.value.history.any { it.role == "assistant" && it.text == "Done." } })
        assertEquals(1, userRows(model, "please run it"))
        assertTrue(model.state.value.pendingSends.isEmpty())
    }

    @Test fun B03_identicalConsecutiveSendsAreTwoBubblesAndTwoRows_neverCollapsedByText() {
        val work = conversation("work")
        val model = open(work)
        model.setDraft("ok")
        model.send()
        model.setDraft("ok")
        model.send()
        assertEquals("both are shown at once", listOf("ok", "ok"), model.state.value.pendingSends.map { it.send.text })
        assertEquals(2, model.state.value.pendingSends.map { it.send.id }.toSet().size)
        assertTrue(await { prompts() == listOf("ok", "ok") })
        model.open(app.wiring().core.sessions.activeConversation(work)!!)
        assertTrue(await { userRows(model, "ok") == 2 && model.state.value.pendingSends.isEmpty() })
        settle()
        assertEquals(2, userRows(model, "ok"))
    }

    @Test fun B04_aTextSentWhilePriorHistoryHasTheSameWordsIsNotClaimedByTheOldRow() {
        val work = conversation("work")
        val model = open(work)
        model.setDraft("ok")
        model.send()
        assertTrue(await { prompts() == listOf("ok") })
        fake.pushLaterTurn(work, FakeHermesDashboard.complete("ok"), withStart = false)
        model.open(app.wiring().core.sessions.activeConversation(work)!!)
        assertTrue(await { userRows(model, "ok") == 1 && model.state.value.history.any { it.role == "assistant" } })
        fake.turnBehavior = { "start_first" }
        model.setDraft("ok")
        model.send()
        assertEquals("the new send is its own bubble although an old row says the same", 1, model.state.value.pendingSends.size)
    }

    @Test fun B05_aRejectedSendBecomesAVisibleFailedSendWithItsContent_andRetryShowsAFreshBubble() {
        val work = conversation("work")
        fake.turnBehavior = { "not_owned" }
        val model = open(work)
        model.setDraft("lost words")
        model.send()
        assertEquals("shown at once, before the server answered", 1, model.state.value.pendingSends.size)
        assertTrue(await { model.state.value.failedSends.size == 1 })
        assertTrue("the rejected bubble is not left as if it were sent", model.state.value.pendingSends.isEmpty())
        val failed = model.state.value.failedSends.single()
        assertEquals("lost words", failed.send.text)
        model.setDraft("newer words")
        fake.turnBehavior = { "start_first" }
        model.retryFailedSend(failed.send.id)
        assertEquals("retry is visible at once", listOf("lost words"), model.state.value.pendingSends.map { it.send.text })
        assertTrue(model.state.value.failedSends.isEmpty())
        assertEquals("a newer draft is never erased", "newer words", model.state.value.draft)
        assertTrue(await { prompts().lastOrNull() == "lost words" })
    }

    @Test fun B06_leavingAndComingBackKeepsTheBubbleInItsOwnConversationOnly() {
        val work = conversation("work")
        val home = conversation("home")
        val serverMayAccept = java.util.concurrent.CountDownLatch(1)
        fake.turnBehavior = { serverMayAccept.await(20, java.util.concurrent.TimeUnit.SECONDS); "start_first" }
        val model = open(work)
        model.setDraft("for work")
        model.send()
        assertEquals(1, model.state.value.pendingSends.size)
        openOwned(model, home)
        assertEquals("the work bubble is still held for work", listOf(work), model.state.value.pendingSends.map { it.send.sessionId })
        assertEquals("another conversation's open does not remove it", 1, model.state.value.pendingSends.size)
        serverMayAccept.countDown()
        assertTrue(await { prompts() == listOf("for work") })
        openOwned(model, work)
        assertTrue("canonical row shown once and the bubble reconciled",
            await { userRows(model, "for work") == 1 && model.state.value.pendingSends.isEmpty() })
        assertEquals(0, model.state.value.history.count { it.text == "for work" && it.role != "user" })
    }

    @Test fun B07_aNewerDraftTypedAfterTheTapSurvivesBubbleReconciliationAndTheFinalReply() {
        val work = conversation("work")
        val model = open(work)
        model.setDraft("first")
        model.send()
        model.setDraft("typed after the tap")
        assertTrue(await { prompts() == listOf("first") })
        fake.pushLaterTurn(work, FakeHermesDashboard.complete("answer"), withStart = false)
        assertTrue(await { model.state.value.history.any { it.text == "answer" } && model.state.value.pendingSends.isEmpty() })
        settle()
        assertEquals("typed after the tap", model.state.value.draft)
        assertEquals(1, userRows(model, "first"))
    }

    @Test fun B08_aRefreshWhileTheMessageIsStillBeingDeliveredNeverLosesTheBubble() {
        val work = conversation("work")
        val model = open(work)
        model.setDraft("slow words")
        model.send()
        model.loadOlder()
        settle()
        assertTrue("reconciled once Hermes has the row, and never before: ${model.state.value.pendingSends}",
            model.state.value.pendingSends.size <= 1)
        assertTrue(await { prompts() == listOf("slow words") })
        model.open(app.wiring().core.sessions.activeConversation(work)!!)
        assertTrue(await { userRows(model, "slow words") == 1 && model.state.value.pendingSends.isEmpty() })
    }
}
