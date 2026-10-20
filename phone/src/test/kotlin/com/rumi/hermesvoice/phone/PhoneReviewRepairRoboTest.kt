package com.rumi.hermesvoice.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.rumi.hermesvoice.core.FakeHermesDashboard
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.net.AcceptedTurnReconciler
import com.rumi.hermesvoice.core.notify.FinalReply
import com.rumi.hermesvoice.core.notify.FinalReplySource
import com.rumi.hermesvoice.core.notify.ReplyAlert
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * Review findings F03, F04, F08 and F10 on the Phone, through the REAL PhoneApp / MainActivity / PhoneViewModel / core wiring /
 * ReplyAlerts / ReplyAlertNotifier, Robolectric's NotificationManager and the Hermes dashboard contract double. Simulated: the platform
 * notification service, the Data Layer and the keystore; no device. An arrival alert stays until the tapped conversation was read
 * again, a refused surface never spends the answer's identity, and a reply that can never be matched to its request ends in one visible notice.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class PhoneReviewRepairRoboTest : PhoneRoboBase() {
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

    private fun arrive(session: String, text: String, name: String = "chat-arrive-${++counter}"): String {
        val identity = ReplyAlert.identityOf(name, "reply")
        app.replyAlerts.onFinalReply(FinalReply(identity, session, VoiceOrigin.PHONE, false, false, FinalReplySource.TEXT, text = text))
        return identity
    }

    private fun failReads(work: String, failing: AtomicBoolean) {
        fake.messagesHook = { session, _, _ -> if (session == work && failing.get()) 503 else 0 }
    }

    @Test fun A1_theAlertIsNotRemovedByTheTapItself() {
        val work = conversation("work")
        val identity = arrive(work, "Away answer.")
        assertTrue(await { identity in tags() })
        assertEquals("kept until the conversation was read again", 0, alerts().single().notification.flags and Notification.FLAG_AUTO_CANCEL)
    }

    @Test fun A2_aTapWhoseHistoryReadFailsKeepsTheAlertAndTheOpenRequest_aSecondTapOpensConsumesAndRemovesIt() {
        val work = conversation("work")
        shownWork(work)
        controller!!.pause().stop()
        idle(100)
        fake.injectRow(work, "assistant", "Away answer.")
        val identity = arrive(work, "Away answer.")
        assertTrue(await { identity in tags() })
        closeScreen()
        val failing = AtomicBoolean(true)
        failReads(work, failing)
        showing(replyTap(work))
        settle()
        assertTrue("the conversation could not be read, so its alert stays: ${tags()}", identity in tags())
        assertEquals("and the open request is kept for the next tap", work, app.pendingOpen.value)
        failing.set(false)
        controller!!.newIntent(replyTap(work))
        val model = model()
        assertTrue("the second tap opens it", await(20_000) { model.state.value.selected?.storedSessionId == work })
        assertTrue("the request is spent only now", await { app.pendingOpen.value == null })
        assertTrue("and the alert goes once the history was read: ${tags()}", await(20_000) { tags().isEmpty() })
    }

    @Test fun A3_aRefusedShowKeepsTheIdentityUnspent_theRetryShowsItOnce_andTheNextRepeatIsDeduplicated() {
        val work = conversation("work")
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val identity = arrive(work, "Away answer.", "chat-refused")
        settle()
        assertTrue("nothing could be shown: ${tags()}", tags().isEmpty())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        arrive(work, "Away answer.", "chat-refused")
        assertTrue("the same final identity is shown once the surface works", await { identity in tags() })
        arrive(work, "Away answer.", "chat-refused")
        settle()
        assertEquals("one alert per final identity", 1, alerts().size)
    }

    @Test fun A4_aVisibleConversationThatCannotBeReadAlerts_theRetryOfThatIdentityIsDeduplicated_andTheTapRemovesItAfterAReadSucceeds() {
        val work = conversation("work")
        val model = shownWork(work)
        val failing = AtomicBoolean(true)
        failReads(work, failing)
        fake.injectRow(work, "assistant", "Arrived answer.")
        val identity = arrive(work, "Arrived answer.", "chat-unread")
        assertTrue("alerted because nothing proves the answer is on screen: ${tags()}", await(20_000) { identity in tags() })
        failing.set(false)
        arrive(work, "Arrived answer.", "chat-unread")
        settle()
        assertEquals("the final identity is decided once", setOf(identity), tags())
        controller!!.pause().stop()
        idle(100)
        controller!!.restart().start().resume().visible()
        assertTrue("read again successfully: removed", await(20_000) { tags().isEmpty() })
        assertTrue(model.state.value.history.any { it.text == "Arrived answer." })
    }

    @Test fun A5_aReplyThatCanNeverBeMatchedToItsRequestEndsInOneVisibleNoticeAndNotInSilence() {
        val work = conversation("work")
        fake.scripts[work] = { listOf(FakeHermesDashboard.complete("", "error")) }
        fake.turnBehavior = { "hold" }
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
        assertTrue("a notice is shown: ${tags()}", await(20_000) { alerts().isNotEmpty() })
        settle()
        val shown = alerts().single().notification
        assertEquals(AcceptedTurnReconciler.NOTICE_TITLE, shown.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(shown.extras.getCharSequence(Notification.EXTRA_TEXT).toString().startsWith("Your message reached Hermes"))
        app.wiring()
        settle()
        assertEquals("deduplicated for that exact send", 1, alerts().size)
        assertEquals("the request is never sent again", listOf("go"), prompts())
    }

    @Test fun A7_aTapOfTheAlreadySelectedConversationKeepsTheOpenRequestUntilItsReadSucceeds() {
        val work = conversation("work")
        shownWork(work)
        val failing = AtomicBoolean(true)
        failReads(work, failing)
        val identity = arrive(work, "New unseen answer.", "chat-selected-tap")
        assertTrue(await(20_000) { identity in tags() })
        controller!!.newIntent(replyTap(work))
        settle()
        assertTrue("failed read keeps the existing alert", identity in tags())
        assertEquals("an already-selected conversation does not consume the tap before reading", work, app.pendingOpen.value)
        failing.set(false)
        controller!!.newIntent(replyTap(work))
        assertTrue("successful re-read consumes the request", await(20_000) { app.pendingOpen.value == null })
        assertTrue("and then dismisses the alert", await(20_000) { identity !in tags() })
    }

    @Test fun A6_aReplyWhoseStoredDisplayTextDiffersIsNotMistakenForOneOnScreenAndStillAlerts() {
        val work = conversation("work")
        val model = shownWork(work)
        fake.injectRow(work, "assistant", "Streamed text.", displayContent = "Stored display text.")
        val identity = arrive(work, "Streamed text.", "chat-display")
        assertTrue("the safe direction: an extra alert, not silence: ${tags()}", await(20_000) { identity in tags() })
        assertNotNull(model.state.value.selected)
    }
}
