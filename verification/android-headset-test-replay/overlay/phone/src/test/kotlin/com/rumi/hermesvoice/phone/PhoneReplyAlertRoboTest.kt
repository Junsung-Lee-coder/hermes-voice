package com.rumi.hermesvoice.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.service.notification.StatusBarNotification
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.ChatSendResult
import com.rumi.hermesvoice.core.FakeHermesDashboard
import com.rumi.hermesvoice.core.auth.HermesBearerSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog

/**
 * SCRATCH HARNESS (never packaged). The Phone's arrival alert for a text reply, through the REAL PhoneApp wiring (the production
 * HermesVoiceCore against the contract double of the Hermes dashboard, a signed-in session), ChatService, the app's alert decision and
 * notifier and the platform NotificationManager (Robolectric's), and the tap through the REAL MainActivity and PhoneViewModel (cold
 * launch and a warm onNewIntent). It uses only names that exist without the feature (platform APIs and wire literals), so the same
 * file runs on the immutable repair baseline. Simulated: the Data Layer (mockk), the hardware keystore (in-memory AES, see
 * [FakeAndroidKeyStore]) and the system notification policy; no device, no Do Not Disturb, no vibration or sound is exercised.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class PhoneReplyAlertRoboTest {
    private object Wire {
        const val CHANNEL_ID = "reply_arrival"
        const val TITLE = "New reply"
        const val BODY = "A new reply is ready"
        const val PUBLIC_TITLE = "Hermes Voice"
        const val PUBLIC_BODY = "New reply"
        const val ACTION_OPEN_REPLY = "com.rumi.hermesvoice.action.OPEN_REPLY"
        const val EXTRA_SESSION_ID = "hv_reply_session"
    }

    private lateinit var app: PhoneApp
    private lateinit var manager: NotificationManager
    private lateinit var fake: FakeHermesDashboard
    private var controller: ActivityController<MainActivity>? = null

    private fun stubWearable() {
        mockkStatic(Wearable::class)
        val info = mockk<CapabilityInfo>()
        every { info.nodes } returns emptySet()
        every { info.name } returns "hermes_voice_watch"
        val capability = mockk<CapabilityClient>()
        every { capability.getCapability(any(), any()) } returns Tasks.forResult(info)
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } returns Tasks.forResult(1)
        val channels = mockk<ChannelClient>(relaxed = true)
        val data = mockk<DataClient>(relaxed = true)
        every { Wearable.getCapabilityClient(any<Context>()) } returns capability
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        every { Wearable.getChannelClient(any<Context>()) } returns channels
        every { Wearable.getDataClient(any<Context>()) } returns data
        every { Wearable.getDataClient(any<android.app.Activity>()) } returns data
    }

    private fun idle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Polls (bounded) until [done], letting the main looper and the app's own background work run; false if it never became true. */
    private fun await(timeoutMs: Long = 8_000, done: () -> Boolean): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            idle(50)
            if (done()) return true
            if (System.nanoTime() > end) return false
            Thread.sleep(25)
        }
    }

    /** Failure context only: the screen's own state and its latest app log lines (counts and tags, no reply text exists in them). */
    private fun why(): String {
        val ui = runCatching { model().state.value }.getOrNull()
        val logs = ShadowLog.getLogs().filter { it.type >= android.util.Log.INFO && it.tag.startsWith("HermesVoice") || it.type >= android.util.Log.WARN }
            .takeLast(25).joinToString(" | ") { "${it.tag}:${it.msg.take(140)}${it.throwable?.let { t -> " [" + t.javaClass.simpleName + ": " + t.message?.take(120) + "]" } ?: ""}" }
        return "selected=${ui?.selected?.storedSessionId} signedIn=${ui?.signedIn} conversations=${ui?.conversations?.size} status='${ui?.status}' pending=${runCatching { (app.javaClass.getMethod("getPendingOpen").invoke(app) as kotlinx.coroutines.flow.StateFlow<*>).value }.getOrNull()} logs=[$logs]"
    }

    private fun settle() = repeat(8) { idle(50); Thread.sleep(25) }

    private fun alerts(): List<StatusBarNotification> = manager.activeNotifications.filter { it.notification.channelId == Wire.CHANNEL_ID }

    @Before
    fun setUp() {
        // lifecycle keeps the first Application in a static default ViewModel factory; every test has its own Application.
        ViewModelProvider.AndroidViewModelFactory::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == ViewModelProvider.AndroidViewModelFactory::class.java }
            .forEach { it.isAccessible = true; it.set(null, null) }
        FakeAndroidKeyStore.install()
        app = RuntimeEnvironment.getApplication() as PhoneApp
        manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        stubWearable()
        fake = FakeHermesDashboard()
        app.settings.dashboardUrl = fake.baseUrl
        app.tokens.save(HermesBearerSession(fake.accessToken, fake.refreshToken, null, "basic", "jun"))
    }

    @After
    fun tearDown() {
        runCatching { controller?.destroy() }
        runCatching { fake.close() }
        unmockkAll()
    }

    private fun conversation(alias: String, reply: String = "Done."): String {
        val owned = runBlocking { app.wiring().core.sessions.createConversation(alias.replaceFirstChar { it.uppercase() }, alias, "$alias things") }
        fake.scripts[owned.storedSessionId] = { listOf(FakeHermesDashboard.complete(reply)) }
        return owned.storedSessionId
    }

    private fun chat(session: String, text: String) {
        assertTrue(runBlocking { app.wiring().core.chat.send(session, text) } is ChatSendResult.Replied)
    }

    private fun open(intent: Intent? = null) {
        controller = Robolectric.buildActivity(MainActivity::class.java, intent ?: Intent(app, MainActivity::class.java)).create()
        idle(300)
    }

    private fun model() = ViewModelProvider(controller!!.get())[PhoneViewModel::class.java]

    private fun selected() = model().state.value.selected?.storedSessionId

    private fun tapIntent(n: Notification): Intent = shadowOf(n.contentIntent).savedIntent

    private fun literalTap(session: String, identity: String = "chat-x#reply") = Intent(app, MainActivity::class.java)
        .setAction(Wire.ACTION_OPEN_REPLY).putExtra(Wire.EXTRA_SESSION_ID, session)
        .setData(android.net.Uri.parse("hermesvoice://reply/$session?r=$identity"))

    @Test fun P01_theAppCreatesTheReplyChannelWithTheSystemsDefaultAlertAndAPrivateLockScreen() {
        val channel = manager.getNotificationChannel(Wire.CHANNEL_ID)
        assertNotNull("the reply channel exists", channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertEquals(Notification.VISIBILITY_PRIVATE, channel.lockscreenVisibility)
    }

    @Test fun P02_aTextReplyThroughTheRealPhoneWiringShowsOnePrivateAlertPreviewingItsAnswer() {
        val work = conversation("work")
        chat(work, "hello")
        assertTrue("the alert arrived", await { alerts().isNotEmpty() })
        settle()
        val shown = alerts().single()
        assertTrue(shown.tag, shown.tag.startsWith("chat-") && shown.tag.endsWith("#reply"))
        val n = shown.notification
        assertEquals(Wire.TITLE, n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("the collapsed text is the delivered answer", "Done.", n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals("the expanded text is the delivered answer", "Done.", n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
        assertTrue("an expandable BigText notification: ${n.extras.getString(Notification.EXTRA_TEMPLATE)}", n.extras.getString(Notification.EXTRA_TEMPLATE)?.contains("BigTextStyle") == true)
        assertTrue("dismissed on tap", n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals("not local-only: the paired system may bridge it", 0, n.flags and Notification.FLAG_LOCAL_ONLY)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals(Wire.PUBLIC_TITLE, n.publicVersion.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(Wire.PUBLIC_BODY, n.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertTrue("the redacted lock-screen version carries no answer text", !n.publicVersion.extras.toString().contains("Done."))
        assertTrue(shadowOf(n.contentIntent).isActivity)
        assertTrue(shadowOf(n.contentIntent).flags and PendingIntent.FLAG_IMMUTABLE != 0)
        val intent = tapIntent(n)
        assertEquals(Wire.ACTION_OPEN_REPLY, intent.action)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(work, intent.getStringExtra(Wire.EXTRA_SESSION_ID))
    }

    @Test fun P03_twoRepliesWithIdenticalTextAreTwoAlerts_andLoadingHistoryNeverAlerts() {
        val work = conversation("work", "Same text.")
        runBlocking { app.wiring().core.sessions.history(work) }
        settle()
        assertTrue("old history is not an arrival", alerts().isEmpty())
        chat(work, "hello")
        chat(work, "hello again")
        assertTrue("two alerts", await { alerts().size >= 2 })
        settle()
        assertEquals(2, alerts().map { it.tag }.toSet().size)
    }

    @Test fun P04_aFailedTextSendNeverAlerts() {
        val work = conversation("work")
        fake.scripts[work] = { listOf(FakeHermesDashboard.complete("partial", "interrupted")) }
        runBlocking { app.wiring().core.chat.send(work, "hello") }
        settle()
        assertTrue(alerts().isEmpty())
    }

    @Test fun P05_theTapFromTheShownAlertOpensItsConversationInTheRealScreen() {
        val work = conversation("work")
        chat(work, "hello")
        assertTrue("the alert arrived", await { alerts().isNotEmpty() })
        val intent = tapIntent(alerts().single().notification)
        val autoNavigate = app.settings.autoNavigateToRouted
        val routing = app.settings.routingEnabled
        open(intent)
        assertTrue("the conversation is open ${why()}", await { selected() == work })
        assertEquals("the optional auto-switch preference is untouched", autoNavigate, app.settings.autoNavigateToRouted)
        assertEquals(routing, app.settings.routingEnabled)
    }

    @Test fun P06_aColdTapNamingAnOwnedConversationOpensIt() {
        val first = conversation("work")
        val second = conversation("home")
        open(literalTap(second))
        assertTrue("the named conversation is open, not another ${why()}", await { selected() == second })
    }

    @Test fun P07_aWarmTapSwitchesTheRunningScreenToTheNamedConversation() {
        val first = conversation("work")
        val second = conversation("home")
        open(literalTap(first))
        assertTrue("the cold tap opened the first ${why()}", await { selected() == first })
        val autoNavigate = app.settings.autoNavigateToRouted
        val warm = literalTap(second, "chat-y#reply")
        controller!!.newIntent(warm)
        assertTrue("the running screen shows the other conversation ${why()}", await { selected() == second })
        assertEquals(autoNavigate, app.settings.autoNavigateToRouted)
        assertNull("the tapped intent is spent", warm.action)
    }

    @Test fun P08_aTamperedOrForeignTapOpensNothing() {
        val work = conversation("work")
        open()
        assertTrue(await { model().state.value.signedIn })
        val tampered = listOf(
            literalTap(work).setAction("android.intent.action.VIEW"),
            literalTap("../../x"),
            literalTap("20260101_130000_ghost000"),
            Intent(app, MainActivity::class.java).setAction(Wire.ACTION_OPEN_REPLY),
        )
        for (intent in tampered) {
            controller!!.newIntent(intent)
            settle()
            assertNull(selected())
        }
    }

    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT)
    private fun bigText(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)

    @Test fun N01_aMarkdownAnswerIsShownAsBoundedPlainTextAndExpands() {
        val work = conversation("work", "## Plan\n\nSee **the** [docs](https://secret.example/token=abc) now.\n- first\n- second")
        chat(work, "hello")
        assertTrue("the alert arrived", await { alerts().isNotEmpty() })
        settle()
        val n = alerts().single().notification
        assertEquals("Plan See the docs now. • first • second", text(n)?.toString())
        assertEquals("Plan\n\nSee the docs now.\n• first\n• second", bigText(n)?.toString())
        assertTrue("plain text, never a span or markup the system would interpret", text(n) !is android.text.Spanned && bigText(n) !is android.text.Spanned)
        assertTrue("no link address leaks into any notification text", !n.extras.toString().contains("secret.example") && !n.publicVersion.extras.toString().contains("secret.example"))
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals(Wire.PUBLIC_BODY, n.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }

    @Test fun N02_twoDistinctRepliesNeverMix_andEachTapsItsOwnConversation() {
        val work = conversation("work", "Alpha answer for the work conversation.")
        val home = conversation("home", "Beta answer for the home conversation.")
        chat(work, "one")
        chat(home, "two")
        assertTrue("two alerts", await { alerts().size >= 2 })
        settle()
        val bySession = alerts().associate { tapIntent(it.notification).getStringExtra(Wire.EXTRA_SESSION_ID) to it.notification }
        assertEquals(setOf(work, home), bySession.keys)
        assertEquals("Alpha answer for the work conversation.", text(bySession.getValue(work))?.toString())
        assertEquals("Alpha answer for the work conversation.", bigText(bySession.getValue(work))?.toString())
        assertEquals("Beta answer for the home conversation.", text(bySession.getValue(home))?.toString())
        assertEquals("Beta answer for the home conversation.", bigText(bySession.getValue(home))?.toString())
    }

    @Test fun N03_aLongAnswerIsBoundedInTheNotificationAndWholeInTheChat() {
        val long = "Section\n" + "긴 답변의 한 문장입니다. ".repeat(400)
        val work = conversation("work", long)
        val sent = runBlocking { app.wiring().core.chat.send(work, "hello") } as ChatSendResult.Replied
        assertEquals("the chat reply is the untruncated canonical answer", long, sent.text)
        assertTrue("the alert arrived", await { alerts().isNotEmpty() })
        settle()
        val n = alerts().single().notification
        assertTrue("collapsed text is bounded: ${text(n)?.length}", (text(n)?.length ?: 0) in 1..140 && text(n).toString().endsWith("…"))
        assertTrue("expanded text is bounded and longer: ${bigText(n)?.length}", (bigText(n)?.length ?: 0) in 141..1000 && bigText(n).toString().endsWith("…"))
        assertTrue(bigText(n).toString().startsWith("Section\n긴 답변의 한 문장입니다."))
        val stored = runBlocking { app.wiring().core.sessions.history(work).messages }
        assertTrue("the stored history keeps the whole answer", stored.any { it.text.trimEnd() == long.trimEnd() })
    }

    @Test fun N04_theAnswerTextNeverReachesTheAppLog() {
        val marker = "ZZ-UNIQUE-PRIVATE-ANSWER-ZZ"
        val work = conversation("work", "The result is $marker.")
        chat(work, "hello")
        assertTrue("the alert arrived", await { alerts().isNotEmpty() })
        settle()
        assertTrue(text(alerts().single().notification)?.contains(marker) == true)
        assertTrue("no log line carries the answer", ShadowLog.getLogs().none { it.msg.contains(marker) || (it.throwable?.message?.contains(marker) == true) })
    }

    @Test fun N05_anAnswerWithNothingReadableKeepsTheGenericAlert() {
        val work = conversation("work", "```\n```")
        chat(work, "hello")
        assertTrue("the alert arrived", await { alerts().isNotEmpty() })
        settle()
        val n = alerts().single().notification
        assertEquals(Wire.BODY, text(n)?.toString())
        assertNull("no expandable body when there is nothing to expand", bigText(n))
        assertEquals(Wire.PUBLIC_BODY, n.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }
}
