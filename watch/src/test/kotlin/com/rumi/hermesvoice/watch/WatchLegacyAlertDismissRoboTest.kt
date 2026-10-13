package com.rumi.hermesvoice.watch

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
 * SCRATCH HARNESS (never packaged). Alerts posted by the previously installed Watch build (no session extra, an immutable explicit
 * activity PendingIntent keyed by identity.hashCode() and the conversation/identity data URI) are removed when their exact conversation
 * is on screen, through the REAL WatchApp / WatchActivity / WatchListenerService / WatchReplyAlertNotifier and Robolectric's NotificationManager.
 *
 * BOUNDARY: Robolectric's PendingIntent registry and NotificationManager approximate the real OS; they prove the code's decisions (what it
 * matches, that the probe is FLAG_NO_CREATE and never creates, updates or sends). The real system token registry, cross-process
 * PendingIntent equality, a real Wear shade and delivery are NOT_RUN.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchLegacyAlertDismissRoboTest {
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

    private val immutableUpdate = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    private val noCreate = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE

    private fun canonicalIntent(session: String, identity: String, marker: String? = null): Intent =
        WatchReplyAlertNotifier.openIntent(app, ReplyAlert(identity, session)).also { if (marker != null) it.putExtra("marker", marker) }

    private fun legacyContentIntent(session: String, identity: String, marker: String? = null): PendingIntent =
        PendingIntent.getActivity(app, identity.hashCode(), canonicalIntent(session, identity, marker), immutableUpdate)

    private fun legacy(session: String, identity: String = "w-n${++n}#reply", quiet: Boolean = false, marker: String? = null): String {
        val notification = Notification.Builder(app, if (quiet) "reply_arrival_quiet" else "reply_arrival")
            .setSmallIcon(R.drawable.ic_stat_voice).setContentTitle("Hermes replied").setContentText("A new reply is ready")
            .setContentIntent(legacyContentIntent(session, identity, marker)).setAutoCancel(true).build()
        assertTrue("a legacy alert carries no session extra", !notification.extras.containsKey("hv_reply_session"))
        manager.notify(identity, 7, notification)
        assertTrue(identity in tags())
        return identity
    }

    private fun custom(identity: String?, channel: String = "reply_arrival", id: Int = 7, intent: PendingIntent?, extras: Bundle? = null) {
        val builder = Notification.Builder(app, channel).setSmallIcon(R.drawable.ic_stat_voice).setContentTitle("custom")
        if (intent != null) builder.setContentIntent(intent)
        if (extras != null) builder.addExtras(extras)
        if (identity == null) manager.notify(id, builder.build()) else manager.notify(identity, id, builder.build())
    }

    private fun others() = manager.activeNotifications.map { it.tag to it.id }.toSet()

    @Test fun X01_aStackOfLegacyAlertsOnBothChannelsIsRemovedForItsConversationOnly() {
        val a1 = legacy(work)
        val a2 = legacy(work, quiet = true)
        val b1 = legacy(home)
        val b2 = legacy(home, quiet = true)
        assertEquals(2, WatchReplyAlertNotifier(app).dismissConversation(work))
        assertEquals(setOf(b1, b2), tags())
        assertTrue(a1 !in tags() && a2 !in tags())
        assertEquals(2, WatchReplyAlertNotifier(app).dismissConversation(home))
        assertTrue(tags().isEmpty())
    }

    @Test fun X02_aNotifierCreatedAfterProcessRecreationFindsTheLegacyStackFromPlatformStateAlone() {
        val a = legacy(work)
        val b = legacy(home)
        assertEquals(1, WatchReplyAlertNotifier(app.applicationContext).dismissConversation(work))
        assertEquals(setOf(b), tags())
        assertTrue(a !in tags())
        assertEquals(0, WatchReplyAlertNotifier(app).dismissConversation(work))
    }

    @Test fun X03_aTappedLegacyAlertOnTheRealPathDismissesItsStack_aReplayStaysClaimed_andALaterDistinctReplyAlertsAgain() {
        val w1 = reply(work)
        val h1 = reply(home)
        // The Watch's real ledger now holds both identities; replace the posted alerts with the previous build's form under the same tags.
        legacy(work, identity = w1)
        legacy(home, identity = h1)
        assertTrue(!manager.activeNotifications.single { it.tag == w1 }.notification.extras.containsKey("hv_reply_session"))
        launch(tap(work))
        assertTrue(await { app.reader.value.selectedSessionId == work })
        assertTrue("work's legacy alert is gone: ${tags()}", await { w1 !in tags() })
        assertEquals("home's legacy alert is untouched", setOf(h1), tags())
        deliverReply(w1, work)
        settle()
        assertEquals("a dismissed identity is never posted again", setOf(h1), tags())
        controller!!.pause().stop()
        idle(100)
        val later = reply(work)
        assertEquals(setOf(h1, later), tags())
    }

    @Test fun X04_newAlertsAreStillMatchedByTheirExtra_andConflictingOrMalformedExtrasAreNeverOverriddenByTheContentIntent() {
        val notifier = WatchReplyAlertNotifier(app)
        assertTrue(notifier.show(ReplyAlert("fresh-1#reply", work)))
        assertTrue(notifier.show(ReplyAlert("fresh-2#reply", home), null, true))
        custom("conflict#reply", intent = legacyContentIntent(work, "conflict#reply"), extras = Bundle().apply { putString("hv_reply_session", home) })
        custom("integer#reply", intent = legacyContentIntent(work, "integer#reply"), extras = Bundle().apply { putInt("hv_reply_session", 5) })
        custom("blank#reply", intent = legacyContentIntent(work, "blank#reply"), extras = Bundle().apply { putString("hv_reply_session", "") })
        custom("nullvalue#reply", intent = legacyContentIntent(work, "nullvalue#reply"), extras = Bundle().apply { putString("hv_reply_session", null) })
        assertEquals(1, notifier.dismissConversation(work))
        assertEquals(setOf("fresh-2#reply", "conflict#reply", "integer#reply", "blank#reply", "nullvalue#reply"), tags())
        assertEquals(2, notifier.dismissConversation(home))
        assertEquals(setOf("integer#reply", "blank#reply", "nullvalue#reply"), tags())
    }

    @Test fun X05_unknownNullOrMismatchedLegacyAlertsArePreserved() {
        val notifier = WatchReplyAlertNotifier(app)
        custom("nointent#reply", intent = null)
        custom(null, intent = legacyContentIntent(work, "notag#reply"))
        custom("bad tag!", intent = legacyContentIntent(work, "badtag#reply"))
        custom("other-request#reply", intent = PendingIntent.getActivity(app, 12345, canonicalIntent(work, "other-request#reply"), immutableUpdate))
        custom("other-action#reply", intent = PendingIntent.getActivity(app, "other-action#reply".hashCode(),
            canonicalIntent(work, "other-action#reply").setAction("elsewhere"), immutableUpdate))
        custom("other-session#reply", intent = legacyContentIntent(home, "other-session#reply"))
        custom("unregistered#reply", intent = PendingIntent.getActivity(app, 7, Intent(app, WatchActivity::class.java).setAction("x"), immutableUpdate))
        val before = others()
        assertEquals(0, notifier.dismissConversation(work))
        assertEquals(before, others())
        assertEquals(1, notifier.dismissConversation(home))
        assertTrue("other-session#reply" !in tags())
        assertEquals(0, notifier.dismissConversation("not a session id!"))
        assertEquals(0, notifier.dismissConversation(""))
        assertEquals(before.size - 1, manager.activeNotifications.size)
    }

    @Test fun X06_aHashCollisionOfTwoIdentitiesNeverMatchesTheOtherConversation() {
        val ids = listOf("AaAa", "AaBB", "BBAa", "BBBB")
        assertEquals(1, ids.map { it.hashCode() }.toSet().size)
        legacy(work, identity = "AaAa")
        legacy(home, identity = "BBBB")
        custom("AaBB", intent = legacyContentIntent(work, "BBAa"))
        assertEquals(1, WatchReplyAlertNotifier(app).dismissConversation(work))
        assertEquals(setOf("BBBB", "AaBB"), tags())
        assertEquals(1, WatchReplyAlertNotifier(app).dismissConversation(home))
        assertEquals(setOf("AaBB"), tags())
    }

    @Test fun X07_theProbeNeverCreatesUpdatesOrSendsAnything() {
        val matchA = legacy(work, marker = "original-a")
        val keepB = legacy(home, marker = "original-b")
        custom("probe-only#reply", intent = legacyContentIntent(home, "probe-only#reply", "original-probe"))
        assertNull(PendingIntent.getActivity(app, "probe-only#reply".hashCode(), canonicalIntent(work, "probe-only#reply"), noCreate))
        WatchReplyAlertNotifier(app).dismissConversation(work)
        assertTrue(matchA !in tags())
        assertNull("the probe did not create the token it asked about", PendingIntent.getActivity(app, "probe-only#reply".hashCode(), canonicalIntent(work, "probe-only#reply"), noCreate))
        assertNotNull(PendingIntent.getActivity(app, keepB.hashCode(), canonicalIntent(home, keepB), noCreate))
        val untouched = manager.activeNotifications.single { it.tag == keepB }.notification.contentIntent
        assertEquals("a surviving alert's token keeps its original saved intent", "original-b", shadowOf(untouched).savedIntent.getStringExtra("marker"))
        assertNull("no activity was started (nothing was sent)", shadowOf(app).nextStartedActivity)
    }

    @Test fun X08_unrelatedNotificationsAndStateAreInvariant() {
        val a = legacy(work)
        val b = legacy(home)
        custom("service", channel = "hermes_service", id = 41, intent = legacyContentIntent(work, "service"))
        custom("other-channel#reply", channel = "recording", id = 7, intent = legacyContentIntent(work, "other-channel#reply"))
        custom("same-channel-other-id#reply", id = 8, intent = legacyContentIntent(work, "same-channel-other-id#reply"))
        val ledgerBefore = app.localStore.getString("reply_alert_ledger_v1")
        val unrelated = others() - setOf(a to 7, b to 7)
        assertEquals(1, WatchReplyAlertNotifier(app).dismissConversation(work))
        assertEquals(unrelated + (b to 7), others())
        assertEquals(ledgerBefore, app.localStore.getString("reply_alert_ledger_v1"))
    }
}
