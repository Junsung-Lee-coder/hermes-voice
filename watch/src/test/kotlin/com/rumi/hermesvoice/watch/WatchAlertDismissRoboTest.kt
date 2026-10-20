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
import com.rumi.hermesvoice.core.watchlink.LoadStatus
import com.rumi.hermesvoice.core.watchlink.ReaderError
import com.rumi.hermesvoice.core.watchlink.ReaderKind
import com.rumi.hermesvoice.core.watchlink.ReaderMessageRow
import com.rumi.hermesvoice.core.watchlink.ReaderRequest
import com.rumi.hermesvoice.core.watchlink.ReaderResponse
import com.rumi.hermesvoice.core.watchlink.ReaderSurface
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
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
    private val requests = ConcurrentLinkedQueue<ReaderRequest>()
    private var readerRows = listOf(ReaderMessageRow(1, "user", "question", false), ReaderMessageRow(2, "assistant", "answer", false))
    private var readerError: String? = null
    private var answerReads = true

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
            if (secondArg<String>() == WatchLinkPaths.READER_REQUEST) ReaderRequest.decode(thirdArg<ByteArray>())?.let(requests::add)
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

    /** Synthetic Phone pages reach the real request/node-fenced reader; no row carries a final-alert identity. */
    private fun pump() {
        if (!answerReads) return
        while (true) {
            val request = requests.poll() ?: break
            app.onReaderResponse(phone, ReaderResponse(request.reqId, request.kind, readerError == null, error = readerError,
                sessionId = request.sessionId, messages = if (readerError == null) readerRows else emptyList(), nextOffset = readerRows.size).encode())
        }
    }

    private fun idle(ms: Long = 50) {
        pump()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        pump()
    }
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

    private fun historyRequests() = sent.toList().filter { it.second == WatchLinkPaths.READER_REQUEST }
        .mapNotNull { ReaderRequest.decode(it.third) }.filter { it.kind == ReaderKind.HISTORY }

    private fun openWork() {
        launch()
        app.selectSession(work)
        assertTrue("initial Phone page is displayed", await {
            app.reader.value.historyFor(work)?.let { it.status == LoadStatus.READY && it.messages == readerRows } == true
        })
        settle()
    }

    private fun assertDestination(identity: String, session: String) {
        val notification = alerts().single { it.tag == identity }.notification
        assertEquals(session, notification.extras.getString("hv_reply_session"))
        assertEquals(session, shadowOf(notification.contentIntent).savedIntent.getStringExtra("hv_reply_session"))
    }


    private fun heldOpeningResponse(request: ReaderRequest) = ReaderResponse(request.reqId, request.kind, true,
        sessionId = request.sessionId, messages = readerRows, nextOffset = readerRows.size).encode()

    @Test fun W12_leavingToListDuringOpeningReadKeepsAlertUntilCurrentSuccessfulRead() {
        val identity = reply(work)
        answerReads = false
        launch(tap(work))
        assertTrue(await { historyRequests().isNotEmpty() })
        val request = historyRequests().last()
        app.toggleReaderSurface()
        assertEquals(ReaderSurface.SESSIONS, app.reader.value.surface)
        app.onReaderResponse(phone, heldOpeningResponse(request))
        settle()
        assertTrue("an exact response off the CHAT surface cannot dismiss", identity in tags())
        answerReads = true
        app.toggleReaderSurface()
        assertTrue("a current successful opening can dismiss", await { identity !in tags() })
    }

    @Test fun W13_backgroundDuringOpeningReadKeepsAlertUntilNewForegroundRead() {
        val identity = reply(work)
        answerReads = false
        launch(tap(work))
        assertTrue(await { historyRequests().isNotEmpty() })
        val request = historyRequests().last()
        controller!!.pause().stop()
        idle(50)
        app.onReaderResponse(phone, heldOpeningResponse(request))
        settle()
        assertTrue("successful history is not a foreground read after pause", identity in tags())
        answerReads = true
        controller!!.restart().start().resume().visible()
        assertTrue(await { identity !in tags() })
    }

    @Test fun W14_sameSelectedWarmTapRetriesReadWithoutLifecycleRoundTrip() {
        openWork()
        val identity = reply(work)
        readerError = ReaderError.UNAVAILABLE
        controller!!.newIntent(tap(work, identity))
        settle()
        assertTrue("refused read retains the only alert", identity in tags())
        readerError = null
        val before = historyRequests().size
        controller!!.newIntent(tap(work, identity))
        assertTrue(await { historyRequests().size > before })
        assertTrue("same selected warm tap dismisses only after successful read", await { identity !in tags() })
        assertEquals(ReaderSurface.CHAT, app.reader.value.surface)
    }

    @Test fun W15_newOpeningCannotUseSupersededHeldReadOrWrongNodePage() {
        val identity = reply(work)
        answerReads = false
        launch(tap(work))
        assertTrue(await { historyRequests().isNotEmpty() })
        val old = historyRequests().last()
        app.toggleReaderSurface()
        app.toggleReaderSurface()
        settle()
        val current = historyRequests().last()
        app.onReaderResponse(phone, heldOpeningResponse(old))
        settle()
        assertTrue("an older opening cannot dismiss the newer opening", identity in tags())
        app.onReaderResponse("wrong-node", heldOpeningResponse(current))
        app.onReaderResponse(phone, ReaderResponse(current.reqId, ReaderKind.HISTORY, true, sessionId = work,
            messages = readerRows, offset = 99, nextOffset = 101).encode())
        settle()
        assertTrue("wrong node/page cannot dismiss", identity in tags())
        app.onReaderResponse(phone, heldOpeningResponse(current))
        assertTrue(await { identity !in tags() })
    }

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

    // Policy migration of historical W04: visibility / an old assistant row cannot prove this final identity.
    // Retain the real Phone request and strengthen it to actual new-row display plus no forced navigation.
    @Test fun W04_aVisibleArrivalRefreshesTheActualReaderAndKeepsAnUnboundFinalAlertWithoutSwitching() {
        openWork()
        val before = historyRequests().size
        readerRows = readerRows + ReaderMessageRow(3, "assistant", "new synthetic final", false)
        deliverReply("turn-visible#later1", work)
        assertTrue("arrival requests the current conversation from Phone", await { historyRequests().size > before })
        val refresh = historyRequests().drop(before).single()
        assertEquals(work, refresh.sessionId)
        assertEquals(0, refresh.offset)
        assertTrue(sent.toList().filter { it.second == WatchLinkPaths.READER_REQUEST }.all { it.first == phone })
        assertTrue("the new page actually updates the displayed history, preserving old rows", await {
            app.reader.value.selectedHistory?.let { it.status == LoadStatus.READY && it.messages == readerRows } == true
        })
        assertEquals(work, app.reader.value.selectedSessionId)
        assertEquals(ReaderSurface.CHAT, app.reader.value.surface)
        assertEquals("no supported final-to-row binding: keep the conservative alert", setOf("turn-visible#later1"), tags())
        assertDestination("turn-visible#later1", work)
        val after = historyRequests().size
        val other = reply(home)
        assertEquals("another conversation cannot refresh or switch this reader", after, historyRequests().size)
        assertEquals(work, app.reader.value.selectedSessionId)
        assertEquals(readerRows, app.reader.value.selectedHistory?.messages)
        assertEquals("both exact destinations keep their own alerts", setOf("turn-visible#later1", other), tags())
        assertDestination(other, home)
    }

    @Test fun W08_anUnchangedOldAssistantPageIsRefreshedButCannotSuppressAndReplayDoesNotDuplicate() {
        openWork()
        val old = app.reader.value.selectedHistory!!.messages
        val before = historyRequests().size
        deliverReply("turn-stale#later1", work)
        assertTrue("even unchanged history must be requested again", await { historyRequests().size > before })
        assertEquals(old, app.reader.value.selectedHistory?.messages)
        assertEquals(LoadStatus.READY, app.reader.value.selectedHistory?.status)
        assertEquals(setOf("turn-stale#later1"), tags())
        assertDestination("turn-stale#later1", work)
        val after = historyRequests().size
        deliverReply("turn-stale#later1", work)
        assertEquals(after, historyRequests().size)
        assertEquals(setOf("turn-stale#later1"), tags())
    }

    @Test fun W09_aFailedArrivalReadPreservesOldHistoryAndAnUndeliveredIdentityRemainsRetryable() {
        openWork()
        val old = app.reader.value.selectedHistory!!.messages
        val before = historyRequests().size
        readerError = ReaderError.UNAVAILABLE
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        deliverReply("turn-read-failed#later1", work)
        assertTrue("failed read is still a real Phone request", await { historyRequests().size > before })
        assertTrue("failure is not relabelled successful display", await { app.reader.value.selectedHistory?.error == ReaderError.UNAVAILABLE })
        assertEquals(old, app.reader.value.selectedHistory?.messages)
        assertTrue(tags().isEmpty())
        assertTrue("neither a failed read nor a denied alert spends the identity",
            !app.localStore.getString("reply_alert_ledger_v1").orEmpty().contains("turn-read-failed#later1"))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        deliverReply("turn-read-failed#later1", work)
        assertEquals(setOf("turn-read-failed#later1"), tags())
        assertDestination("turn-read-failed#later1", work)
        assertEquals(ReaderError.UNAVAILABLE, app.reader.value.selectedHistory?.error)
        deliverReply("turn-read-failed#later1", work)
        assertEquals(setOf("turn-read-failed#later1"), tags())
    }

    @Test fun W10_aDelayedArrivalPageCannotSwitchBackAfterUserNavigationOrUpdateTheWrongConversation() {
        openWork()
        val before = historyRequests().size
        answerReads = false
        deliverReply("turn-delayed#later1", work)
        assertTrue("arrival really started a refresh", await { historyRequests().size > before })
        val refresh = historyRequests().drop(before).single()
        app.selectSession(home)
        assertTrue(await { app.reader.value.selectedSessionId == home })
        app.onReaderResponse(phone, ReaderResponse(refresh.reqId, ReaderKind.HISTORY, true, sessionId = work,
            messages = listOf(ReaderMessageRow(99, "assistant", "late old destination", false)), nextOffset = 1).encode())
        assertEquals(home, app.reader.value.selectedSessionId)
        assertTrue(app.reader.value.selectedHistory?.messages?.none { it.rowId == 99L } != false)
        assertEquals(setOf("turn-delayed#later1"), tags())
        assertDestination("turn-delayed#later1", work)
        answerReads = true
        settle()
        assertEquals(home, app.reader.value.selectedSessionId)
        assertEquals(readerRows, app.reader.value.selectedHistory?.messages)
        assertEquals(setOf("turn-delayed#later1"), tags())
    }

    @Test fun W11_aTimedOutArrivalReadKeepsTheAlertAndDoesNotPretendTheOldPageWasRefreshed() {
        openWork()
        val old = app.reader.value.selectedHistory!!.messages
        val before = historyRequests().size
        answerReads = false
        deliverReply("turn-timeout#later1", work)
        assertTrue("arrival starts a bounded Phone request", await { historyRequests().size > before })
        // Drive the production dispatch timeout through its main-looper coroutine; do not inject a reducer timeout.
        assertTrue("timeout remains visible as timeout", await(20_000) { app.reader.value.selectedHistory?.error == ReaderError.TIMEOUT })
        assertEquals(old, app.reader.value.selectedHistory?.messages)
        assertEquals(setOf("turn-timeout#later1"), tags())
        assertDestination("turn-timeout#later1", work)
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
