package com.rumi.hermesvoice.watch

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
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
import com.rumi.hermesvoice.core.KeyValueStore
import com.rumi.hermesvoice.core.notify.ReplyAlert
import com.rumi.hermesvoice.core.watchlink.ReaderMessageRow
import com.rumi.hermesvoice.core.watchlink.ReaderRequest
import com.rumi.hermesvoice.core.watchlink.ReaderResponse
import com.rumi.hermesvoice.core.watchlink.TurnStateMessage
import com.rumi.hermesvoice.core.watchlink.WatchFailureInbox
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
import org.junit.Assert.assertFalse
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

/**
 * Review findings F01, F06 and F08 on the Watch, through the REAL WatchListenerService, WatchApp, WatchActivity (lifecycle), the durable
 * inbox over a store that can refuse writes, and Robolectric's NotificationManager. Simulated: the Data Layer (mockk) and the Phone's
 * answers to reader requests; no device. A "not sent" state from the Phone is acknowledged only after the resumed screen showed it and
 * that is saved. The content-free arrival wire cannot bind a final identity to a history row: even a successful read must not mistake
 * an older assistant message for positive display of the new answer. In that case the safe policy is one deduplicated extra alert.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchFailureDisplayRoboTest {
    private lateinit var app: WatchApp
    private lateinit var manager: NotificationManager
    private var controller: ActivityController<WatchActivity>? = null
    private val sent: MutableList<Triple<String, String, ByteArray>> = Collections.synchronizedList(mutableListOf())
    private val requests = ConcurrentLinkedQueue<ReaderRequest>()
    private var answerReads = true
    private var failReaderSend = false
    private var newestRole = "assistant"
    private var n = 0

    private val phone = "phone-node"
    private val work = "20260101_120000_workwork"
    private val notSent = "Not sent: the recording was rejected. Say it again"

    private class RefusableStore(val inner: KeyValueStore) : KeyValueStore by inner {
        @Volatile var failing = false
        override fun putString(key: String, value: String) { if (!failing) inner.putString(key, value) }
        override fun commitString(key: String, value: String): Boolean = if (failing) false else inner.commitString(key, value)
    }

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
            val path = secondArg<String>()
            if (path == WatchLinkPaths.READER_REQUEST) {
                ReaderRequest.decode(thirdArg<ByteArray>())?.let(requests::add)
                if (failReaderSend) return@answers Tasks.forException<Int>(java.io.IOException("harness: phone unreachable"))
            }
            sent += Triple(firstArg<String>(), path, thirdArg<ByteArray>())
            Tasks.forResult(1)
        }
        val channels = mockk<ChannelClient>()
        val data = mockk<DataClient>()
        every { data.getDataItems(any<Uri>()) } returns Tasks.forException<DataItemBuffer>(IllegalStateException("harness: no data item"))
        every { Wearable.getCapabilityClient(any<Context>()) } returns capability
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        every { Wearable.getChannelClient(any<Context>()) } returns channels
        every { Wearable.getDataClient(any<Context>()) } returns data
        every { Wearable.getDataClient(any<Activity>()) } returns data
    }

    private fun pump() {
        while (true) {
            val request = requests.poll() ?: break
            if (!answerReads) continue
            val rows = listOf(ReaderMessageRow(1, "user", "question", false), ReaderMessageRow(2, newestRole, "answer", false))
            app.onReaderResponse(phone, ReaderResponse(request.reqId, request.kind, true, sessionId = request.sessionId, messages = rows, nextOffset = 2).encode())
        }
    }

    private fun idle(ms: Long = 50) {
        pump()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        pump()
    }

    private fun await(timeoutMs: Long = 6000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (condition()) return true; idle(50); Thread.sleep(10) }
        return condition()
    }

    private fun holdsFor(ms: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (!condition()) return false; idle(50); Thread.sleep(10) }
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
        runCatching { shadowOf(Looper.getMainLooper()).idle() }
        val companion = Class.forName("androidx.compose.ui.platform.AndroidUiDispatcher").getDeclaredField("Companion").also { it.isAccessible = true }.get(null)
        val context = companion.javaClass.getMethod("getMain").invoke(companion) as kotlin.coroutines.CoroutineContext
        val dispatcher = context[kotlin.coroutines.ContinuationInterceptor]!!
        for (name in listOf("scheduledTrampolineDispatch", "scheduledFrameDispatch")) {
            dispatcher.javaClass.getDeclaredField(name).also { it.isAccessible = true }.setBoolean(dispatcher, false)
        }
    }

    private fun useStore(store: KeyValueStore) {
        fun field(name: String) = WatchApp::class.java.getDeclaredField(name).also { it.isAccessible = true }
        field("localStore\$delegate").set(app, lazyOf(store))
        recreateInbox()
    }

    /** The process ends and a new one starts: the inbox is rebuilt from the file alone and the screen asks it what is owed. */
    private fun recreateInbox() {
        val delegate = WatchApp::class.java.getDeclaredField("failureInbox\$delegate").also { it.isAccessible = true }
        delegate.set(app, lazy { WatchFailureInbox(app.localStore) })
        app.hydrateOwedFailure()
    }

    private fun phoneSays(turnId: String, detail: String = notSent) {
        val service = Robolectric.setupService(WatchListenerService::class.java)
        val event = mockk<MessageEvent>()
        every { event.path } returns WatchLinkPaths.STATE
        every { event.sourceNodeId } returns phone
        every { event.data } returns TurnStateMessage(turnId, "done", detail, true).encode()
        service.onMessageReceived(event)
        idle(100)
    }

    private fun acks(turnId: String) = sent.toList().filter { it.second == WatchLinkPaths.STATE_ACK && TurnStateMessage.decode(it.third)?.turnId == turnId }

    private fun launch(intent: Intent? = null) {
        controller = Robolectric.buildActivity(WatchActivity::class.java, intent ?: Intent(app, WatchActivity::class.java)).setup()
        idle(300)
    }

    private fun hide() {
        controller!!.pause().stop()
        idle(100)
    }

    private fun show() {
        controller!!.restart().start().resume().visible()
        idle(300)
    }

    private fun alerts() = manager.activeNotifications.filter { it.notification.channelId.startsWith("reply_arrival") }
    private fun tags() = alerts().map { it.tag }.toSet()

    private fun deliverReply(identity: String, session: String = work) {
        val service = Robolectric.setupService(WatchListenerService::class.java)
        val event = mockk<MessageEvent>()
        every { event.path } returns WatchLinkPaths.REPLY
        every { event.sourceNodeId } returns phone
        every { event.data } returns """{"v":1,"identity":"$identity","session_id":"$session"}""".toByteArray()
        service.onMessageReceived(event)
        idle(100)
    }

    private fun tapIntent(session: String) = WatchReplyAlertNotifier.openIntent(app, ReplyAlert("tap-${++n}#later1", session))

    // ── F01 ──────────────────────────────────────────────────────────────────────────────────

    @Test fun F1_aFailureArrivingWhileTheAppIsNotOnScreenIsKeptOwedAndAcknowledgedOnlyAfterTheNextResume() {
        val id = "watch-hidden-0001"
        phoneSays(id)
        assertEquals("owed with its exact request id", id, app.owedFailure.value?.turnId)
        assertTrue("saved before anything is claimed: ${app.localStore.getString(WatchFailureInbox.KEY)}", app.localStore.getString(WatchFailureInbox.KEY).orEmpty().contains(id))
        assertTrue("not on screen, so not acknowledged", holdsFor(1_200) { acks(id).isEmpty() })
        launch()
        assertTrue("acknowledged after the resumed screen showed it", await { acks(id).isNotEmpty() })
        assertTrue(acks(id).all { it.first == phone })
        assertNull("nothing owed any more", app.owedFailure.value)
        assertTrue("the shown state is saved", app.localStore.getString(WatchFailureInbox.KEY).orEmpty().contains(id))
        assertTrue(holdsFor(600) { acks(id).size == 1 })
    }

    @Test fun F2_aPausedActivityDoesNotCountAsShownAndTheNextForegroundShowsItOnce() {
        val id = "watch-hidden-0002"
        launch()
        hide()
        phoneSays(id)
        assertEquals(id, app.owedFailure.value?.turnId)
        assertTrue("the app is hidden: no acknowledgement", holdsFor(1_200) { acks(id).isEmpty() })
        show()
        assertTrue(await { acks(id).isNotEmpty() })
        assertEquals(1, acks(id).size)
        assertNull(app.owedFailure.value)
    }

    @Test fun F3_aReplayAfterDisplayIsAcknowledgedAgainAndNotShownAgain() {
        val id = "watch-replay-0003"
        launch()
        phoneSays(id)
        assertTrue(await { acks(id).isNotEmpty() })
        assertNull(app.owedFailure.value)
        phoneSays(id)
        assertTrue("acknowledged again so the Phone can stop", await { acks(id).size >= 2 })
        assertNull("but not shown again", app.owedFailure.value)
    }

    @Test fun F4_aFailureReceivedButNotYetDisplayedSurvivesAProcessRestartAndIsThenShownAndAcknowledgedOnce() {
        val id = "watch-restart-0004"
        phoneSays(id)
        assertTrue(holdsFor(600) { acks(id).isEmpty() })
        recreateInbox()
        assertEquals("rebuilt from the file with the Phone node it came from", id, app.owedFailure.value?.turnId)
        assertEquals(phone, app.owedFailure.value?.source)
        launch()
        assertTrue(await { acks(id).isNotEmpty() })
        assertTrue(holdsFor(600) { acks(id).size == 1 })
        assertTrue(acks(id).all { it.first == phone })
    }

    @Test fun F5_aFailureTheWatchCouldNotSaveIsNotAcknowledgedUntilItIsSavedAndThePhoneRepeatsIt() {
        val store = RefusableStore(app.localStore)
        useStore(store)
        val id = "watch-nosave-0005"
        store.failing = true
        phoneSays(id)
        launch()
        assertNull("shown on screen", app.owedFailure.value)
        assertTrue("shown but not saved: the Phone is not told it was handled", holdsFor(1_500) { acks(id).isEmpty() })
        store.failing = false
        phoneSays(id)
        assertTrue("the Phone's repeat finds the shown state saved and is acknowledged", await { acks(id).isNotEmpty() })
        assertNull(app.owedFailure.value)
        assertTrue(store.inner.getString(WatchFailureInbox.KEY).orEmpty().contains(id))
    }

    @Test fun F6_aFailureOnTheSessionListIsNotShownYetAndNotAcknowledged() {
        val id = "watch-list-0006"
        launch()
        app.selectSession(work)
        idle(100)
        app.toggleReaderSurface()
        idle(200)
        assertEquals(com.rumi.hermesvoice.core.watchlink.ReaderSurface.SESSIONS, app.reader.value.surface)
        phoneSays(id)
        assertTrue("the list is on screen, not the status line", holdsFor(1_200) { acks(id).isEmpty() })
        app.toggleReaderSurface()
        assertTrue(await { acks(id).isNotEmpty() })
    }

    @Test fun F7_theStatusTextForTheOwedFailureIsTheExactDetail() {
        phoneSays("watch-text-0007", "Not sent: the recording was rejected. Say it again")
        val owed = app.owedFailure.value!!
        assertEquals("Not sent: the recording was rejected. Say it again", talkHint(app.talk.value, false, owed.detail))
    }

    // ── F06 / F08 ────────────────────────────────────────────────────────────────────────────

    @Test fun A1_aVisibleConversationWhoseReadFailsStillGetsTheAlert() {
        launch()
        app.selectSession(work)
        idle(100)
        failReaderSend = true
        deliverReply("turn-a1#later1")
        assertTrue("the re-read failed, so nothing proves the answer is on screen: ${tags()}", await { "turn-a1#later1" in tags() })
    }

    @Test fun A2_aVisibleConversationWhoseNewestMessageIsNotTheAssistantsGetsTheAlert() {
        launch()
        app.selectSession(work)
        idle(100)
        newestRole = "user"
        deliverReply("turn-a2#later1")
        assertTrue(await { "turn-a2#later1" in tags() })
    }

    @Test fun A3_aVisibleConversationWithoutExactFinalRowAuthorityStillGetsOneAlert() {
        launch()
        app.selectSession(work)
        idle(100)
        deliverReply("turn-a3#later1")
        assertTrue("a successful read ending in an assistant row is not proof of this final identity", await { "turn-a3#later1" in tags() })
        deliverReply("turn-a3#later1")
        assertEquals("one alert for the exact final identity", setOf("turn-a3#later1"), tags())
    }

    @Test fun A7_aReadReturningOnlyThePreviouslyVisibleAnswerMustNotConsumeANewFinalIdentity() {
        launch()
        app.selectSession(work)
        assertTrue(await { app.reader.value.historyFor(work)?.messages?.any { it.rowId == 2L && it.role == "assistant" } == true })
        // The reader double returns exactly the same old rows on refresh. No row proves the new final arrived.
        deliverReply("turn-new-unseen#later1")
        assertTrue("old assistant text is not positive display of this new final", await { "turn-new-unseen#later1" in tags() })
        deliverReply("turn-new-unseen#later1")
        assertEquals(setOf("turn-new-unseen#later1"), tags())
    }

    @Test fun A4_aFailedShowDoesNotSpendTheIdentityAndTheRetryShowsItOnceThenIsDeduplicated() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        deliverReply("turn-a4#later1")
        assertTrue(tags().isEmpty())
        assertFalse("not spent", app.localStore.getString("reply_alert_ledger_v1").orEmpty().contains("turn-a4#later1"))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        deliverReply("turn-a4#later1")
        assertEquals(setOf("turn-a4#later1"), tags())
        deliverReply("turn-a4#later1")
        assertEquals("one alert per final identity", 1, alerts().size)
        assertTrue(app.localStore.getString("reply_alert_ledger_v1").orEmpty().contains("turn-a4#later1"))
    }

    @Test fun A5_theAlertDoesNotCancelItselfOnTap() {
        deliverReply("turn-a5#later1")
        assertTrue(await { "turn-a5#later1" in tags() })
        val flags = alerts().single().notification.flags
        assertEquals("kept until the conversation was read again", 0, flags and android.app.Notification.FLAG_AUTO_CANCEL)
    }

    @Test fun A6_aTapWhoseReadFailsKeepsTheAlertAndALaterSuccessfulReadRemovesIt() {
        deliverReply("turn-a6#later1")
        assertEquals(setOf("turn-a6#later1"), tags())
        failReaderSend = true
        launch(tapIntent(work))
        assertEquals(work, app.reader.value.selectedSessionId)
        assertTrue("the read failed: the alert stays: ${tags()}", holdsFor(1_500) { "turn-a6#later1" in tags() })
        failReaderSend = false
        hide()
        show()
        assertTrue("read again successfully: removed", await { "turn-a6#later1" !in tags() })
    }
}
