package com.rumi.hermesvoice.phone

import android.Manifest
import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.rumi.hermesvoice.core.FakeHermesDashboard
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.notify.FinalReply
import com.rumi.hermesvoice.core.notify.FinalReplySource
import com.rumi.hermesvoice.core.notify.ReplyAlert
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). Where a text answer goes when the screen that sent the request is gone, and how an answer that
 * arrives for the conversation on screen reaches that screen, through the REAL PhoneApp / MainActivity / PhoneViewModel / core
 * wiring / ReplyAlerts / ReplyAlertNotifier, Robolectric's NotificationManager and the Hermes dashboard contract double (a held
 * turn keeps running on the double while the screen is destroyed). Simulated: the platform notification service, the Data Layer
 * and the keystore; no device, Doze, force-stop or real process death (an app restart is a new core over the same stored preferences).
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class PhoneReplyDeliveryRoboTest : PhoneRoboBase() {
    val rule = createEmptyComposeRule()
    @get:Rule val cleanupThenCompose: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(ComposeDispatcherCleanup()).around(rule)

    private lateinit var manager: NotificationManager
    private var counter = 0

    @Before fun setUp() {
        baseSetUp()
        manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After fun tearDown() {
        fake.messagesHook = null
        baseTearDown()
    }

    override fun pump() { idle(50); rule.waitForIdle() }

    private fun alerts(): List<StatusBarNotification> = manager.activeNotifications.filter {
        it.notification.channelId == "reply_arrival" || it.notification.channelId == "reply_arrival_quiet"
    }

    private fun tags() = alerts().map { it.tag }.toSet()

    private fun showing(intent: android.content.Intent? = null) {
        controller = Robolectric.buildActivity(MainActivity::class.java, intent ?: android.content.Intent(app, MainActivity::class.java)).setup()
        idle(300)
        settle()
    }

    private fun model() = androidx.lifecycle.ViewModelProvider(controller!!.get())[PhoneViewModel::class.java]

    private fun shownWork(work: String): PhoneViewModel {
        showing(replyTap(work))
        val model = model()
        assertTrue("the conversation is shown", await { model.state.value.selected?.storedSessionId == work })
        return model
    }

    private fun closeScreen() {
        controller!!.pause().stop().destroy()
        controller = null
        idle(200)
    }

    private fun holdingTurn(work: String, answer: String) {
        fake.scripts[work] = { listOf(FakeHermesDashboard.complete(answer)) }
        fake.turnBehavior = { "hold" }
    }

    private fun arrive(session: String, text: String, name: String = "chat-arrive-${++counter}", heard: Boolean = false): String {
        val identity = ReplyAlert.identityOf(name, "reply")
        app.replyAlerts.onFinalReply(FinalReply(identity, session, VoiceOrigin.PHONE, heard, false, FinalReplySource.TEXT, text = text))
        return identity
    }

    private fun messageReads(stored: String) = fake.restLog.toList().count { it.startsWith("GET /api/sessions/$stored/messages?") }


    @Test fun R12_liveEpochFailureAfterScreenDetachShowsExactNoticeWithoutResubmission() {
        val work = conversation("work")
        holdingTurn(work, "Never recovered.")
        val model = shownWork(work)
        model.setDraft("go")
        model.send()
        assertTrue(await { fake.heldTurns.containsKey(work) })
        closeScreen()
        fake.replayEpoch = "e2"
        fake.dropGateway()
        assertTrue("detached accepted failure must not disappear", await(20_000) { alerts().isNotEmpty() })
        val notice = alerts().single()
        assertTrue(notice.tag.endsWith("#notice"))
        assertEquals(com.rumi.hermesvoice.core.net.AcceptedTurnReconciler.NOTICE_TITLE,
            notice.notification.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertTrue(notice.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().startsWith("Your message reached Hermes"))
        app.wiring().core.reconcileAccepted()
        settle()
        assertEquals(1, alerts().size)
        assertEquals(listOf("go"), prompts())
    }

    @Test fun R13_anUnchangedOldEqualAnswerCannotSpendTheDistinctFinalIdentity() {
        val work = conversation("work")
        fake.injectRow(work, "assistant", "Done.")
        val model = shownWork(work)
        assertTrue(await { model.state.value.history.any { it.text == "Done." } })
        val old = model.state.value.history.toList()
        val identity = arrive(work, "Done.", "chat-distinct-equal")
        assertTrue("unchanged old equal text is not positive display of this final", await(20_000) { identity in tags() })
        assertEquals(old, model.state.value.history)
        arrive(work, "Done.", "chat-distinct-equal")
        settle()
        assertEquals(setOf(identity), tags())
        // A genuinely new row is merged, retaining the old row. A conservative alert is still
        // allowed when the protocol cannot bind the final identity to this row.
        fake.injectRow(work, "assistant", "Done.")
        arrive(work, "Done.", "chat-real-new-equal")
        assertTrue(await { model.state.value.history.count { it.text == "Done." } == 2 })
        settle()
        assertTrue(model.state.value.history.containsAll(old))
    }

    @Test fun R14_refusedStaleEqualFinalIsStillRetryable() {
        val work = conversation("work")
        fake.injectRow(work, "assistant", "Done.")
        shownWork(work)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        arrive(work, "Done.", "chat-equal-refused")
        settle()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val identity = arrive(work, "Done.", "chat-equal-refused")
        assertTrue("a refused stale-equal final must not be falsely consumed as displayed", await(20_000) { identity in tags() })
    }

    // ── a text request waiting when its screen goes away ─────────────────────────────────────

    @Test fun R01_aTextRequestStillWaitingWhenItsScreenIsDestroyedDeliversItsAnswerOnce() {
        val work = conversation("work")
        holdingTurn(work, "Late answer.")
        val model = shownWork(work)
        model.setDraft("go")
        model.send()
        assertTrue("accepted by the server", await { fake.heldTurns.containsKey(work) })
        closeScreen()
        fake.finishHeldTurn(work)
        assertTrue("the answer alerts: ${tags()}", await(20_000) { tags().size == 1 })
        settle()
        assertEquals(1, tags().size)
        assertEquals("the request is never sent again", listOf("go"), prompts())
    }

    @Test fun R02_aRecreatedScreenStillShowsTheWaitingRequestAndThenItsAnswerWithoutSendingAgain() {
        val work = conversation("work")
        holdingTurn(work, "Late answer.")
        val first = shownWork(work)
        first.setDraft("go")
        first.send()
        assertTrue("accepted by the server", await { fake.heldTurns.containsKey(work) })
        closeScreen()
        val second = shownWork(work)
        assertTrue("the request is still waiting: ${second.state.value.sendsInFlight}", await { second.state.value.sendsInFlight == 1 })
        assertTrue("and it is shown, as a waiting bubble or as its stored row",
            second.state.value.pendingSends.any { it.send.text == "go" } || second.state.value.history.any { it.text == "go" })
        fake.finishHeldTurn(work)
        assertTrue("the answer is shown on the visible conversation", await(20_000) { second.state.value.history.any { it.text == "Late answer." } })
        assertTrue("the waiting request is released", await { second.state.value.pendingSends.isEmpty() && second.state.value.sendsInFlight == 0 })
        settle()
        // R10 every-final policy: actual display still clears waiting/read state, not the alert obligation.
        assertEquals("the displayed final still posts exactly one alert", 1, tags().size)
        assertEquals(listOf("go"), prompts())
    }

    @Test fun R03_anAnswerFinishedWhileTheAppWasRestartedIsAlertedOnceAfterTheNewCoreStarts() {
        val work = conversation("work")
        holdingTurn(work, "Late answer.")
        val model = shownWork(work)
        model.setDraft("go")
        model.send()
        assertTrue("accepted by the server", await { fake.heldTurns.containsKey(work) })
        closeScreen()
        val profile = app.settings.profile
        app.settings.profile = "elsewhere"
        app.wiring()
        fake.finishHeldTurn(work)
        app.settings.profile = profile
        app.wiring()
        assertTrue("the reconciled answer alerts: ${tags()}", await(20_000) { tags().size == 1 })
        settle()
        assertEquals(1, tags().size)
        assertEquals(listOf("go"), prompts())
    }

    // ── an answer arriving for the conversation on screen ────────────────────────────────────

    @Test fun R04_anAnswerForTheVisibleConversationIsMergedIntoItsHistoryAndPostsNoAlert() {
        val work = conversation("work")
        for (i in 1..100) fake.injectRow(work, if (i % 2 == 1) "user" else "assistant", "old-$i")
        val model = shownWork(work)
        val before = model.state.value.history.size
        assertTrue(model.state.value.hasOlder)
        fake.injectRow(work, "assistant", "Arrived answer.")
        arrive(work, "Arrived answer.")
        assertTrue("the visible history shows the arrival", await { model.state.value.history.any { it.text == "Arrived answer." } })
        settle()
        assertEquals("R10 every-final policy also alerts while the actual reader is visible", 1, tags().size)
        assertTrue("older rows stay and the older cursor is kept", model.state.value.hasOlder && model.state.value.history.size >= before)
    }

    @Test fun R05_ifTheVisibleHistoryCannotBeReloadedTheAnswerStillAlerts() {
        val work = conversation("work")
        val model = shownWork(work)
        val failing = AtomicBoolean(true)
        fake.messagesHook = { session, _, _ -> if (session == work && failing.get()) 503 else 0 }
        fake.injectRow(work, "assistant", "Arrived answer.")
        val identity = arrive(work, "Arrived answer.")
        assertTrue("the alert is not lost: ${tags()}", await(20_000) { identity in tags() })
        assertTrue("the stale history did not pretend to show it", model.state.value.history.none { it.text == "Arrived answer." })
    }

    @Test fun R06_anAnswerTheReloadedHistoryDoesNotContainStillAlerts() {
        val work = conversation("work")
        shownWork(work)
        val identity = arrive(work, "Not stored anywhere yet.")
        assertTrue("the alert is not lost: ${tags()}", await(20_000) { identity in tags() })
    }

    @Test fun R07_ifTheUserLeavesWhileTheReloadIsPendingTheAnswerStillAlerts() {
        val work = conversation("work")
        shownWork(work)
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holding = AtomicBoolean(true)
        fake.messagesHook = { session, _, _ ->
            if (session == work && holding.get()) { arrived.countDown(); release.await(30, TimeUnit.SECONDS) }
            0
        }
        fake.injectRow(work, "assistant", "Arrived answer.")
        val identity = arrive(work, "Arrived answer.")
        assertTrue("the reload started", arrived.await(20, TimeUnit.SECONDS))
        controller!!.pause().stop()
        idle(100)
        holding.set(false)
        release.countDown()
        assertTrue("the alert is not lost: ${tags()}", await(20_000) { identity in tags() })
    }

    @Test fun R08_aReplayedAnswerIdentityIsDecidedOnce() {
        val work = conversation("work")
        val model = shownWork(work)
        fake.injectRow(work, "assistant", "Arrived answer.")
        val name = "chat-replayed"
        arrive(work, "Arrived answer.", name)
        assertTrue(await { model.state.value.history.any { it.text == "Arrived answer." } })
        settle()
        val reads = messageReads(work)
        // R02 policy migration: another in-flight read may already have projected this row
        // before final confirmation captures its local cursor. Without a final-to-row binding,
        // one conservative exact-identity alert is allowed; replay may neither read nor alert again.
        val decided = tags()
        val identity = ReplyAlert.identityOf(name, "reply")
        assertTrue("only this exact final may conservatively alert: $decided", decided.isEmpty() || decided == setOf(identity))
        arrive(work, "Arrived answer.", name)
        settle()
        assertEquals("a replayed identity reloads nothing", reads, messageReads(work))
        assertEquals("replay cannot change the one original decision", decided, tags())
    }

    @Test fun R09_anAnswerForAnotherConversationAlertsAndLeavesTheVisibleOneAlone() {
        val work = conversation("work")
        val home = conversation("home")
        shownWork(work)
        settle()
        val reads = messageReads(work)
        fake.injectRow(home, "assistant", "Elsewhere.")
        val identity = arrive(home, "Elsewhere.")
        assertTrue(await { identity in tags() })
        settle()
        assertEquals("the visible conversation was not reloaded for it", reads, messageReads(work))
    }

    @Test fun R10_anAnswerWhileTheAppIsInTheBackgroundAlertsAndAHeardOneDoesNot() {
        val work = conversation("work")
        shownWork(work)
        controller!!.pause().stop()
        idle(100)
        fake.injectRow(work, "assistant", "Away answer.")
        val identity = arrive(work, "Away answer.")
        assertTrue(await { identity in tags() })
        val heardIdentity = arrive(work, "Heard answer.", heard = true)
        settle()
        assertEquals("R10: hearing changes speech/read handling, not the independent final alert", setOf(identity, heardIdentity), tags())
    }

    @Test fun R11_openingAConversationDoesNotDismissItsAlertsUntilItsHistoryWasReadAgain() {
        val work = conversation("work")
        shownWork(work)
        controller!!.pause().stop()
        idle(100)
        fake.injectRow(work, "assistant", "Away answer.")
        val identity = arrive(work, "Away answer.")
        assertTrue(await { identity in tags() })
        val failing = AtomicBoolean(true)
        fake.messagesHook = { session, _, _ -> if (session == work && failing.get()) 503 else 0 }
        controller!!.restart().start().resume().visible()
        settle()
        assertTrue("the history could not be read, so the only evidence stays: ${tags()}", identity in tags())
        failing.set(false)
        controller!!.pause().stop()
        idle(100)
        controller!!.restart().start().resume().visible()
        assertTrue("dismissed once the history was read: ${tags()}", await(20_000) { tags().isEmpty() })
    }
}
