package com.rumi.hermesvoice.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.rumi.hermesvoice.core.FakeHermesDashboard
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.notify.FinalReply
import com.rumi.hermesvoice.core.notify.FinalReplySource
import com.rumi.hermesvoice.core.notify.ReplyAlert
import kotlinx.coroutines.async
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
 * SCRATCH HARNESS (never packaged). This app's new-reply alerts are removed when their exact conversation is actually shown, through the
 * REAL PhoneApp / MainActivity / PhoneViewModel / ReplyAlerts / ReplyAlertNotifier and Robolectric's NotificationManager. Simulated:
 * the platform notification service (Robolectric's), the Data Layer and the keystore; no device, Do Not Disturb or lock screen.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class PhoneAlertDismissRoboTest : PhoneRoboBase() {
    val rule = createEmptyComposeRule()
    @get:Rule val cleanupThenCompose: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(ComposeDispatcherCleanup()).around(rule)

    private lateinit var manager: NotificationManager
    private var counter = 0

    @Before fun setUp() {
        baseSetUp()
        manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After fun tearDown() = baseTearDown()

    override fun pump() { idle(50); rule.waitForIdle() }

    private fun replyAlerts(): List<StatusBarNotification> = manager.activeNotifications.filter {
        it.notification.channelId == "reply_arrival" || it.notification.channelId == "reply_arrival_quiet"
    }

    private fun tags() = replyAlerts().map { it.tag }.toSet()

    /** One distinct final reply for [session], delivered through the app's real decision and notifier; returns its identity. */
    private fun reply(session: String, quiet: Boolean = false, text: String = "answer ${++counter}"): String {
        val identity = ReplyAlert.identityOf("chat-n${++counter}", "reply")
        app.replyAlerts.onFinalReply(FinalReply(identity, session, VoiceOrigin.PHONE, heard = false, cancelled = false,
            source = FinalReplySource.TEXT, text = text, quiet = quiet))
        assertTrue("alert $identity shown", await { identity in tags() })
        return identity
    }

    private fun showing(intent: android.content.Intent? = null) {
        controller = Robolectric.buildActivity(MainActivity::class.java, intent ?: android.content.Intent(app, MainActivity::class.java)).setup()
        idle(300)
        settle()
    }

    private fun model() = androidx.lifecycle.ViewModelProvider(controller!!.get())[PhoneViewModel::class.java]

    @Test fun D01_choosingAConversationFromTheListRemovesEveryStackedAlertOfThatConversationOnly() {
        val work = conversation("work")
        val home = conversation("home")
        val w1 = reply(work)
        val w2 = reply(work, quiet = true)
        val h1 = reply(home)
        showing()
        val model = model()
        assertTrue(await { model.state.value.conversations.size == 2 })
        rule.onNodeWithTag("conversation_row_$work").performClick()
        assertTrue("work is shown", await { model.state.value.selected?.storedSessionId == work })
        assertTrue("both of work's alerts (normal and quiet channel) are gone: ${tags()}", await { w1 !in tags() && w2 !in tags() })
        assertEquals("home's alert is untouched", setOf(h1), tags())
    }

    @Test fun D02_aTappedAlertWhileTheAppIsOnScreenOpensAndDismissesThatConversationsAlerts() {
        val work = conversation("work")
        val home = conversation("home")
        showing()
        val w1 = reply(work)
        val w2 = reply(work)
        val h1 = reply(home)
        controller!!.newIntent(replyTap(work))
        assertTrue(await { model().state.value.selected?.storedSessionId == work })
        assertTrue(await { w1 !in tags() && w2 !in tags() })
        assertEquals(setOf(h1), tags())
    }

    @Test fun D03_aColdOpenFromANotificationTapDismissesTheStack() {
        val work = conversation("work")
        val home = conversation("home")
        val w1 = reply(work)
        val h1 = reply(home)
        showing(replyTap(work))
        assertTrue(await { model().state.value.selected?.storedSessionId == work })
        assertTrue(await { w1 !in tags() })
        assertEquals(setOf(h1), tags())
    }

    @Test fun D04_selectingInTheBackgroundDoesNotCountButComingToTheForegroundDoes() {
        val work = conversation("work")
        showing(replyTap(work))
        val model = model()
        assertTrue(await { model.state.value.selected?.storedSessionId == work })
        controller!!.pause().stop()
        idle(100)
        val w1 = reply(work)
        val w2 = reply(work)
        settle()
        assertEquals("not visible: both alerts stay", setOf(w1, w2), tags())
        controller!!.restart().start().resume().visible()
        assertTrue("foreground resume while already selected dismisses them", await { tags().isEmpty() })
    }

    @Test fun D05_openingADifferentScreenDoesNotDismissAndAReplyWhileVisibleIsShownNotAlerted() {
        val work = conversation("work")
        val home = conversation("home")
        showing()
        val model = model()
        assertTrue(await { model.state.value.conversations.size == 2 })
        val w1 = reply(work)
        settle()
        assertEquals("the Conversations tab is not the conversation", setOf(w1), tags())
        controller!!.newIntent(replyTap(home))
        assertTrue(await { model.state.value.selected?.storedSessionId == home })
        assertEquals("opening home leaves work's alert alone", setOf(w1), tags())
        settle()
        val before = tags()
        assertTrue("the creation seed is not a displayed row anchor", model.state.value.history.isEmpty())
        val identity = ReplyAlert.identityOf("chat-visible", "reply")
        val delivered = FinalReply(identity, home, VoiceOrigin.PHONE, false, false, FinalReplySource.TEXT, text = "now")
        fake.injectRow(home, "assistant", "now")
        app.replyAlerts.onFinalReply(delivered)
        assertTrue("arrival still refreshes the actual reader", await { model.state.value.history.any { it.text == "now" } })
        settle()
        // No pre-read visible cursor: new text alone cannot bind this final identity.
        assertEquals("unbound arrival alerts conservatively, preserving work", before + identity, tags())
        assertEquals(home, model.state.value.selected?.storedSessionId)
        app.replyAlerts.onFinalReply(delivered)
        settle()
        assertEquals("exact replay never duplicates or changes the decision", before + identity, tags())
        controller!!.newIntent(replyTap(home))
        assertTrue("a fresh successful opening dismisses only home", await { tags() == before })
        assertEquals(setOf(w1), tags())
    }

    @Test fun D06_aReplyArrivingWhileItsConversationIsShownIsStillShownInTheTranscriptAndPostsNoAlert() {
        val work = conversation("work")
        showing(replyTap(work))
        val model = model()
        assertTrue(await { model.state.value.selected?.storedSessionId == work })
        assertTrue("only the hidden creation seed exists", model.state.value.history.isEmpty())
        val realDisplay = app.conversationDisplay!!
        val captured = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val held = java.util.concurrent.atomic.AtomicBoolean(true)
        val observed = java.util.concurrent.atomic.AtomicReference<FinalReply>()
        val confirmed = java.util.concurrent.atomic.AtomicReference<Boolean>()
        // Hold REST materialization, not the send or gateway. All raw rows remain intact.
        fake.messagesHook = { session, _, _ ->
            if (session == work && held.get()) check(release.await(8, java.util.concurrent.TimeUnit.SECONDS))
            0
        }
        app.conversationDisplay = object : ConversationDisplay {
            override suspend fun confirmArrival(reply: FinalReply): Boolean = kotlinx.coroutines.coroutineScope {
                observed.set(reply)
                // Run the REAL confirmation through its first suspension: its pre-read cursor
                // is captured before any held history response can update the ViewModel.
                val actual = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    realDisplay.confirmArrival(reply)
                }
                captured.countDown()
                actual.await().also { confirmed.set(it) }
            }
            override suspend fun confirmOpened(storedSessionId: String) = realDisplay.confirmOpened(storedSessionId)
        }
        try {
            model.setDraft("go")
            model.send()
            assertTrue(await { prompts() == listOf("go") && model.state.value.sendsInFlight == 1 })
            assertTrue("accepted reload cannot manufacture a visible anchor", model.state.value.history.isEmpty())
            fake.pushLaterTurn(work, FakeHermesDashboard.complete("Visible answer."), withStart = false)
            assertTrue("the real arrival confirmation captured the missing cursor", captured.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(model.state.value.history.isEmpty())
            held.set(false)
            release.countDown()
            assertTrue("the answer is in the transcript", await { model.state.value.history.any { it.text == "Visible answer." } })
            assertTrue("the real confirmation returned", await { confirmed.get() != null })
            assertEquals("refresh without a pre-read row anchor is not positive binding", false, confirmed.get())
            assertTrue("TextSends finished without retry", await { model.state.value.sendsInFlight == 0 })
            val reply = observed.get()!!
            assertEquals(work, reply.storedSessionId)
            assertEquals("Visible answer.", reply.text)
            assertTrue(reply.identity.startsWith("chat-") && reply.identity.endsWith("#reply"))
            assertTrue("one conservative exact-send alert", await { tags() == setOf(reply.identity) })
            assertEquals(listOf("go"), prompts())
            app.replyAlerts.onFinalReply(reply)
            settle()
            assertEquals("replay keeps the original exact identity", setOf(reply.identity), tags())
            assertEquals(work, model.state.value.selected?.storedSessionId)
            controller!!.newIntent(replyTap(work))
            assertTrue("successful current reader opening dismisses it", await { tags().isEmpty() })
            assertTrue(model.state.value.history.any { it.text == "Visible answer." })
            assertEquals(listOf("go"), prompts())
        } finally {
            held.set(false)
            release.countDown()
            fake.messagesHook = null
            app.conversationDisplay = realDisplay
        }
    }

    @Test fun D07_aNewDistinctReplyAfterLeavingAlertsAgain_andAReplayOfADismissedOneDoesNot() {
        val work = conversation("work")
        showing()
        val w1 = reply(work)
        controller!!.newIntent(replyTap(work))
        assertTrue(await { w1 !in tags() })
        val replay = FinalReply(w1, work, VoiceOrigin.PHONE, false, false, FinalReplySource.TEXT, text = "answer")
        app.replyAlerts.onFinalReply(replay)
        settle()
        assertTrue("a viewed identity is never posted again: ${tags()}", tags().isEmpty())
        controller!!.pause().stop()
        idle(100)
        val w3 = reply(work)
        assertEquals(setOf(w3), tags())
    }

    @Test fun D08_unrelatedNotificationsAndTheNotifierItselfSurvive_andDismissalIsRecoverableFromPlatformStateAlone() {
        val work = conversation("work")
        val home = conversation("home")
        val other = Notification.Builder(app, "reply_arrival").setSmallIcon(R.drawable.ic_stat_voice).setContentTitle("other app channel").build()
        manager.notify("elsewhere", 7, other.also { })
        manager.notify(41, Notification.Builder(app, "reply_arrival").setSmallIcon(R.drawable.ic_stat_voice).setContentTitle("service").build())
        val w1 = reply(work)
        val h1 = reply(home)
        val fresh = ReplyAlertNotifier(app)
        assertEquals("a notifier that never posted anything finds the stack from the platform alone", 1, fresh.dismissConversation(work))
        assertTrue(w1 !in tags())
        assertTrue(h1 in tags())
        assertTrue("an unrelated notification with another id/tag is untouched", manager.activeNotifications.any { it.id == 41 })
        assertEquals("dismissing a conversation with none is a quiet no-op", 0, fresh.dismissConversation(work))
    }
}
