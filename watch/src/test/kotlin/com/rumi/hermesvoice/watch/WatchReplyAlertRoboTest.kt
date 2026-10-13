package com.rumi.hermesvoice.watch

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
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
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.time.Duration
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). The Watch's arrival alert through the REAL WatchListenerService (REPLY message), WatchApp,
 * WatchReplyAlertNotifier and the platform NotificationManager (Robolectric's), and the tap through the REAL WatchActivity
 * (cold launch and a warm onNewIntent). Simulated: the Data Layer (mockk) and the system notification policy; no device, no
 * Do Not Disturb, no vibration or sound is exercised.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchReplyAlertRoboTest {
    private lateinit var app: WatchApp
    private lateinit var manager: NotificationManager
    private var controller: org.robolectric.android.controller.ActivityController<WatchActivity>? = null

    private object Wire {
        const val CHANNEL_ID = "reply_arrival"
        const val TITLE = "New reply"
        const val BODY = "A new reply is ready"
        const val PUBLIC_TITLE = "Hermes Voice"
        const val PUBLIC_BODY = "New reply"
        const val ACTION_OPEN_REPLY = "com.rumi.hermesvoice.action.OPEN_REPLY"
        const val EXTRA_SESSION_ID = "hv_reply_session"
        const val REPLY_PATH = "/hv/v1/reply"
        const val LEDGER_KEY = "reply_alert_ledger_v1"
    }

    private val work = "20260101_120000_workwork"
    private val other = "20260101_130000_otherone"

    private fun stubWearable() {
        mockkStatic(Wearable::class)
        val node = mockk<Node>()
        every { node.id } returns "phone-node"
        every { node.isNearby } returns true
        every { node.displayName } returns "phone"
        val info = mockk<CapabilityInfo>()
        every { info.nodes } returns setOf(node)
        every { info.name } returns WatchLinkPaths.CAPABILITY_PHONE
        val capability = mockk<CapabilityClient>()
        every { capability.getCapability(any(), any()) } returns Tasks.forResult(info)
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } returns Tasks.forResult(1)
        val channels = mockk<ChannelClient>()
        val data = mockk<DataClient>()
        every { data.getDataItems(any<Uri>()) } returns Tasks.forException<DataItemBuffer>(IllegalStateException("harness: no data item"))
        every { Wearable.getCapabilityClient(any<Context>()) } returns capability
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        every { Wearable.getChannelClient(any<Context>()) } returns channels
        every { Wearable.getDataClient(any<Context>()) } returns data
        every { Wearable.getDataClient(any<Activity>()) } returns data
    }

    private fun idle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Lets the app's own background work reach the main looper (bounded: about half a second). */
    private fun settle() = repeat(8) { idle(50); Thread.sleep(25) }

    private fun deliver(bytes: ByteArray) {
        val service = Robolectric.setupService(WatchListenerService::class.java)
        val event = mockk<MessageEvent>()
        every { event.path } returns Wire.REPLY_PATH
        every { event.sourceNodeId } returns "phone-node"
        every { event.data } returns bytes
        service.onMessageReceived(event)
        settle()
    }

    private fun alert(identity: String, session: String = work) = """{"v":1,"identity":"$identity","session_id":"$session"}""".toByteArray()

    private fun posted() = manager.activeNotifications.toList()

    private fun tapIntent(n: Notification): Intent = shadowOf(n.contentIntent).savedIntent

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as WatchApp
        manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        stubWearable()
    }

    @After
    fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
        unmockkAll()
    }

    private fun open(intent: Intent? = null) {
        val launch = intent ?: Intent(app, WatchActivity::class.java)
        controller = Robolectric.buildActivity(WatchActivity::class.java, launch).setup()
        idle(300)
    }

    /** The platform delivers a tap to the running singleTask activity (the real protected callback through the controller). */
    private fun tapWarm(intent: Intent) {
        controller!!.newIntent(intent)
        idle(200)
    }

    @Test fun W01_theReplyMessageShowsOneLocalOnlyGenericAlertThroughTheRealListener() {
        deliver(alert("turn-1#later2"))
        val shown = posted().single()
        assertEquals("turn-1#later2", shown.tag)
        val n = shown.notification
        assertEquals(Wire.CHANNEL_ID, n.channelId)
        assertEquals(Wire.TITLE, n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(Wire.BODY, n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertTrue("local-only: never bridged to the Phone", n.flags and Notification.FLAG_LOCAL_ONLY != 0)
        assertTrue("dismissed on tap", n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals(Wire.PUBLIC_TITLE, n.publicVersion.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(Wire.PUBLIC_BODY, n.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertNull("no sound or vibration of its own beyond the channel's", n.sound)
        val channel = manager.getNotificationChannel(Wire.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }

    @Test fun W02_theSameIdentityTwiceIsOneAlert_aDifferentIdentityIsAnother_evenForTheSameConversation() {
        deliver(alert("turn-1#later2"))
        deliver(alert("turn-1#later2"))
        assertEquals(1, posted().size)
        deliver(alert("turn-1#later3"))
        deliver(alert("turn-9#final", other))
        assertEquals(setOf("turn-1#later2", "turn-1#later3", "turn-9#final"), posted().map { it.tag }.toSet())
    }

    @Test fun W03_anAlertAlreadyHandledStaysHandledAcrossTheStoredLedger() {
        deliver(alert("turn-5#final"))
        val tag = posted().single().tag
        assertTrue("the identity is durably claimed", app.localStore.getString(Wire.LEDGER_KEY).orEmpty().split(' ').contains(tag))
        manager.cancelAll()
        deliver(alert("turn-5#final"))
        assertTrue(posted().isEmpty())
    }

    @Test fun W04_aDeniedPermissionOrDisabledNotificationsShowNothingAndBreakNothing() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        deliver(alert("turn-2#final"))
        assertTrue(posted().isEmpty())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(false)
        deliver(alert("turn-3#final"))
        assertTrue(posted().isEmpty())
        shadowOf(manager).setNotificationsEnabled(true)
        deliver(alert("turn-4#final"))
        assertEquals("the app still works afterwards", listOf("turn-4#final"), posted().map { it.tag })
    }

    @Test fun W05_aDisabledChannelShowsNothing() {
        manager.deleteNotificationChannel(Wire.CHANNEL_ID)
        manager.createNotificationChannel(NotificationChannel(Wire.CHANNEL_ID, "x", NotificationManager.IMPORTANCE_NONE))
        deliver(alert("turn-6#final"))
        assertTrue(posted().isEmpty())
    }

    @Test fun W06_aMalformedOrForeignPayloadShowsNothing() {
        deliver("not json".toByteArray())
        deliver("""{"v":1,"identity":"a b","session_id":"s-1"}""".toByteArray())
        deliver("""{"v":1,"identity":"a#b","session_id":"../x"}""".toByteArray())
        deliver("{}".toByteArray())
        assertTrue(posted().isEmpty())
    }

    @Test fun W07_theTapNamesExactlyOneConversationAndIsExplicitAndImmutable() {
        deliver(alert("turn-1#later2", other))
        val n = posted().single().notification
        val intent = tapIntent(n)
        assertEquals(Wire.ACTION_OPEN_REPLY, intent.action)
        assertEquals(WatchActivity::class.java.name, intent.component?.className)
        assertEquals(other, intent.getStringExtra(Wire.EXTRA_SESSION_ID))
        assertTrue(shadowOf(n.contentIntent).isActivity)
        assertTrue(shadowOf(n.contentIntent).flags and android.app.PendingIntent.FLAG_IMMUTABLE != 0)
        deliver(alert("turn-1#later3", work))
        val second = posted().first { it.tag == "turn-1#later3" }.notification
        assertEquals(work, tapIntent(second).getStringExtra(Wire.EXTRA_SESSION_ID))
        assertTrue("distinct pending intents", tapIntent(second).data != intent.data)
    }

    @Test fun W08_aColdTapOpensTheNamedConversation() {
        deliver(alert("turn-1#later2", other))
        val intent = tapIntent(posted().single().notification)
        assertNull(app.reader.value.selectedSessionId)
        open(intent)
        assertEquals(other, app.reader.value.selectedSessionId)
    }

    @Test fun W09_aWarmTapOpensTheNamedConversationOnTheRunningActivity() {
        open()
        assertNull(app.reader.value.selectedSessionId)
        deliver(alert("turn-1#later2", other))
        val intent = tapIntent(posted().single().notification)
        tapWarm(intent)
        assertEquals(other, app.reader.value.selectedSessionId)
        assertNull("the intent is spent", controller!!.get().intent.action)
    }

    @Test fun W10_anIntentWithAnotherActionOrAnUnsafeSessionOpensNothing() {
        open()
        val base = { Intent(app, WatchActivity::class.java) }
        val tampered = listOf(
            base().setAction("android.intent.action.VIEW").putExtra(Wire.EXTRA_SESSION_ID, work),
            base().setAction(Wire.ACTION_OPEN_REPLY).putExtra(Wire.EXTRA_SESSION_ID, "../../x"),
            base().setAction(Wire.ACTION_OPEN_REPLY),
        )
        for (intent in tampered) {
            tapWarm(intent)
            assertNull(app.reader.value.selectedSessionId)
        }
    }
}
