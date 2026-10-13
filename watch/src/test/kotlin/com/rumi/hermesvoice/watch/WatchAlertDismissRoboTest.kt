package com.rumi.hermesvoice.watch

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.service.notification.StatusBarNotification
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItemBuffer
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.notify.ReplyAlert
import com.rumi.hermesvoice.core.watchlink.ReaderSurface
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.time.Duration
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
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

/**
 * SCRATCH HARNESS (never packaged). The Watch removes ITS OWN stacked reply alerts for a conversation once that conversation is actually
 * on screen, through the REAL WatchApp / WatchActivity / WatchListenerService / WatchReplyAlertNotifier and Robolectric's
 * NotificationManager. Simulated: the Data Layer (mockk) and the platform notification service; no watch, display or Wear delivery.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchAlertDismissRoboTest {
    private lateinit var app: WatchApp
    private lateinit var manager: NotificationManager
    private val sent: MutableList<Triple<String, String, ByteArray>> = Collections.synchronizedList(mutableListOf())
    private var controller: ActivityController<WatchActivity>? = null
    private var n = 0

    private val phone = "phone-node"
    private val work = "20260101_120000_workwork"
    private val home = "20260101_130000_homehome"

    private fun stubWearable() {
        mockkStatic(Wearable::class)
        val node = mockk<Node>()
        every { node.id } returns phone
        every { node.isNearby } returns true
        every { node.displayName } returns "phone"
        val info = mockk<CapabilityInfo>()
        every { info.nodes } returns setOf(node)
        every { info.name } returns WatchLinkPaths.CAPABILITY_PHONE
        val capability = mockk<CapabilityClient>()
        every { capability.getCapability(any(), any()) } returns Tasks.forResult(info)
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } answers {
            sent += Triple(firstArg<String>(), secondArg<String>(), thirdArg<ByteArray>())
            Tasks.forResult(1)
        }
        val channels = mockk<ChannelClient>()
        val data = mockk<DataClient>()
        every { data.getDataItems(any<Uri>()) } returns Tasks.forException<DataItemBuffer>(IllegalStateException("harness: no data item"))
        every { Wearable.getCapabilityClient(any<Context>()) } returns capability
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        every { Wearable.getChannelClient(any<Context>()) } returns channels
        every { Wearable.getDataClient(any<Context>()) } returns data
    }

    private fun idle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun settle() = repeat(8) { idle(50); Thread.sleep(25) }
    private fun await(timeoutMs: Long = 5000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (condition()) return true; idle(50); Thread.sleep(10) }
        return condition()
    }

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as WatchApp
        manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO)
        stubWearable()
    }

    @After fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
        unmockkAll()
        drainAndResetComposeDispatcher()
    }

    /**
     * Compose's process-wide AndroidUiDispatcher keeps its "dispatch already posted" flags when Robolectric empties the main looper between
     * tests, which leaves every later Compose test in the JVM unable to go idle. Run what is posted, then clear only those flags.
     */
    private fun drainAndResetComposeDispatcher() {
        runCatching { shadowOf(Looper.getMainLooper()).idle() }
        val companion = Class.forName("androidx.compose.ui.platform.AndroidUiDispatcher").getDeclaredField("Companion").also { it.isAccessible = true }.get(null)
        val context = companion.javaClass.getMethod("getMain").invoke(companion) as kotlin.coroutines.CoroutineContext
        val dispatcher = context[kotlin.coroutines.ContinuationInterceptor]!!
        for (name in listOf("scheduledTrampolineDispatch", "scheduledFrameDispatch")) {
            dispatcher.javaClass.getDeclaredField(name).also { it.isAccessible = true }.setBoolean(dispatcher, false)
        }
    }

    private fun alerts(): List<StatusBarNotification> = manager.activeNotifications.filter {
        it.notification.channelId == "reply_arrival" || it.notification.channelId == "reply_arrival_quiet"
    }
    private fun tags() = alerts().map { it.tag }.toSet()

    private fun deliverReply(identity: String, session: String) {
        val service = Robolectric.setupService(WatchListenerService::class.java)
        val event = mockk<MessageEvent>()
        every { event.path } returns WatchLinkPaths.REPLY
        every { event.sourceNodeId } returns phone
        every { event.data } returns """{"v":1,"identity":"$identity","session_id":"$session"}""".toByteArray()
        service.onMessageReceived(event)
        settle()
    }

    private fun reply(session: String): String {
        val identity = "turn-${++n}#later1"
        deliverReply(identity, session)
        assertTrue("alert $identity shown", await { identity in tags() })
        return identity
    }

    private fun tap(session: String, identity: String = "tap-${++n}#later1") =
        WatchReplyAlertNotifier.openIntent(app, ReplyAlert(identity, session))

    private fun launch(intent: android.content.Intent? = null) {
        controller = Robolectric.buildActivity(WatchActivity::class.java, intent ?: android.content.Intent(app, WatchActivity::class.java)).setup()
        idle(300)
    }

    private fun readerRequests() = sent.count { it.second == WatchLinkPaths.READER_REQUEST }

    @Test fun W01_aTappedAlertOpensItsConversationAndRemovesEveryStackedAlertOfThatConversationOnly() {
        val w1 = reply(work)
        val w2 = reply(work)
        val h1 = reply(home)
        launch(tap(work))
        assertTrue(await { app.reader.value.selectedSessionId == work })
        assertTrue("both of work's alerts are gone: ${tags()}", await { w1 !in tags() && w2 !in tags() })
        assertEquals("home's alert is untouched", setOf(h1), tags())
    }

    @Test fun W02_aManualSelectionWhileTheAppIsOnScreenDismissesButOneInTheBackgroundDoesNot() {
        launch()
        val h1 = reply(home)
        val w1 = reply(work)
        controller!!.pause().stop()
        idle(100)
        app.selectSession(work)
        settle()
        assertEquals("selected while the screen was off: nothing counts", setOf(h1, w1), tags())
        controller!!.restart().start().resume().visible()
        assertTrue("on screen with work selected: its alerts go", await { w1 !in tags() })
        assertEquals(setOf(h1), tags())
        app.selectSession(home)
        assertTrue(await { tags().isEmpty() })
    }

    @Test fun W04_aReplyArrivingWhileItsConversationIsShownPostsNoAlertAndAsksThePhoneForTheNewMessage() {
        launch()
        app.selectSession(work)
        idle(100)
        val before = readerRequests()
        deliverReply("turn-visible#later1", work)
        settle()
        assertTrue("no alert for what is already on screen: ${tags()}", tags().isEmpty())
        assertTrue("the conversation on screen is re-read", readerRequests() > before)
        val other = reply(home)
        assertEquals("a reply for another conversation still alerts", setOf(other), tags())
    }

    @Test fun W05_aViewedIdentityIsNeverPostedAgainAndANewDistinctReplyAfterLeavingIsShown() {
        val w1 = reply(work)
        launch(tap(work))
        assertTrue(await { w1 !in tags() })
        deliverReply(w1, work)
        settle()
        assertTrue("replaying a dismissed identity posts nothing: ${tags()}", tags().isEmpty())
        controller!!.pause().stop()
        idle(100)
        val w2 = reply(work)
        assertEquals(setOf(w2), tags())
    }

    @Test fun W06_anotherNotificationAndFreshNotifierState_theStackIsFoundFromThePlatformAlone() {
        manager.notify(41, Notification.Builder(app, "reply_arrival").setSmallIcon(R.drawable.ic_stat_voice).setContentTitle("service").build())
        val w1 = reply(work)
        val h1 = reply(home)
        assertEquals(1, WatchReplyAlertNotifier(app).dismissConversation(work))
        assertTrue(w1 !in tags())
        assertTrue(h1 in tags())
        assertTrue("an unrelated notification is untouched", manager.activeNotifications.any { it.id == 41 })
        assertEquals(0, WatchReplyAlertNotifier(app).dismissConversation(work))
    }

    @Test fun W07_leavingTheChatSurfaceStopsCountingAndReturningCountsAgain() {
        launch()
        app.selectSession(work)
        idle(100)
        app.toggleReaderSurface()
        assertEquals(ReaderSurface.SESSIONS, app.reader.value.surface)
        val w1 = reply(work)
        settle()
        assertEquals("the list is on screen, not the conversation", setOf(w1), tags())
        app.toggleReaderSurface()
        assertTrue(await { w1 !in tags() })
    }
}
