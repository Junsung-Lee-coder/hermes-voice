package com.rumi.hermesvoice.watch

import android.Manifest
import android.app.NotificationManager
import android.content.Context
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
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.watchlink.PlayRequest
import com.rumi.hermesvoice.core.watchlink.PlayedAck
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.time.Duration
import java.util.Collections
import org.json.JSONObject
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
 * SCRATCH HARNESS (never packaged). Private headset output on the Watch (amendment J): once the Phone's snapshot says the
 * personal headset is the only output, the REAL WatchApp stops what it is playing, refuses every later clip with an ACK that
 * says "not played", reports what it applied back to the Phone (revision + suppressed) and shows its arrival alert silently.
 * The Data Layer is simulated (mockk); no real speaker, audio routing, vibration or Do Not Disturb is exercised.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchPrivateAudioRoboTest {
    private lateinit var app: WatchApp
    private lateinit var manager: NotificationManager
    private val sent: MutableList<Triple<String, String, ByteArray>> = Collections.synchronizedList(mutableListOf())

    private val phone = "phone-node"

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

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as WatchApp
        manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        stubWearable()
    }

    @After fun tearDown() = unmockkAll()

    private var revision = 1000L
    private fun snapshot(private: Boolean, rev: Long = ++revision) = WatchSettings(privateAudio = private, revision = rev).toJson()
    private fun offer(private: Boolean, rev: Long = ++revision) { app.applySettings(snapshot(private, rev)); settle() }

    private fun field(name: String) = app.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun setField(name: String, value: Any?) = field(name).set(app, value)
    private fun player(): Any? = field("player").get(app)

    private fun request(id: String, seq: Int = 0, later: Boolean = true) = PlayRequest(id, seq, "final", "audio/wav", byteArrayOf(1, 2, 3), later = later)
    private fun acks() = sent.filter { it.second == WatchLinkPaths.PLAYED }.mapNotNull { PlayedAck.decode(it.third) }
    private fun receipts() = sent.filter { it.second == "/hv/v1/private_audio" }.map { JSONObject(String(it.third)) }

    private fun deliverReply(identity: String) {
        val service = Robolectric.setupService(WatchListenerService::class.java)
        val event = mockk<MessageEvent>()
        every { event.path } returns WatchLinkPaths.REPLY
        every { event.sourceNodeId } returns phone
        every { event.data } returns """{"v":1,"identity":"$identity","session_id":"20260101_120000_workwork"}""".toByteArray()
        service.onMessageReceived(event)
        settle()
    }

    @Test fun P01_activatingWhileAClipPlaysStopsThatPlayerAndTellsThePhoneItWasNotPlayed() {
        val running = request("playing-turn")
        val mp = mockk<android.media.MediaPlayer>(relaxed = true)
        setField("player", mp); setField("playing", running); setField("playingNode", phone)
        app.holds.acquire(com.rumi.hermesvoice.core.background.HoldReason.PLAYBACK)
        offer(private = true)
        verify(exactly = 1) { mp.stop() }
        verify(exactly = 1) { mp.release() }
        assertNull(player())
        val ack = acks().single { it.turnId == "playing-turn" }
        assertFalse("a stopped clip is never reported as heard", ack.ok)
        assertTrue(app.holds.held().isEmpty())
    }

    @Test fun P02_whileActiveEveryClipIsRefusedWithoutAPlayerAndTheAckSaysNotPlayed() {
        offer(private = true)
        sent.clear()
        app.play(request("refused-1"), phone); settle()
        app.playReceived(request("refused-2", later = false), phone, app.playbackStopGeneration); settle()
        assertNull("no player was created", player())
        val got = acks().associateBy { it.turnId }
        assertEquals(setOf("refused-1", "refused-2"), got.keys)
        assertTrue(got.values.none { it.ok })
        assertTrue(got.values.all { it.error == "private_headset" })
        assertTrue(app.holds.held().isEmpty())
    }

    @Test fun P03_theWatchReportsWhatItAppliedWithTheRevisionAndSuppressed() {
        offer(private = true, rev = 2000)
        val r = receipts().last()
        assertEquals(2000L, r.getLong("revision"))
        assertTrue(r.getBoolean("suppressed"))
        offer(private = false, rev = 2001)
        val off = receipts().last()
        assertEquals(2001L, off.getLong("revision"))
        assertFalse(off.getBoolean("suppressed"))
    }

    @Test fun P04_aStaleSnapshotCannotLiftTheSuppressionAndTheReceiptNamesTheRevisionActuallyApplied() {
        offer(private = true, rev = 3000)
        sent.clear()
        offer(private = false, rev = 2500)
        assertTrue(app.settings.value.privateAudio)
        app.play(request("still-refused"), phone); settle()
        assertNull(player())
        assertEquals(false, acks().single { it.turnId == "still-refused" }.ok)
        receipts().forEach {
            assertEquals(3000L, it.getLong("revision"))
            assertTrue(it.getBoolean("suppressed"))
        }
    }

    @Test fun P05_leavingPrivateModeAllowsPlaybackAgainAndTheStateIsPersisted() {
        offer(private = true, rev = 4000)
        val stored = app.getSharedPreferences("hermes_voice_watch", Context.MODE_PRIVATE).getString("settings_json", null)
        assertTrue("persisted for a restarted Watch", JSONObject(stored!!).getBoolean("private_audio"))
        offer(private = false, rev = 4001)
        sent.clear()
        app.play(request("plays-again"), phone); settle()
        val got = acks().filter { it.turnId == "plays-again" }
        assertTrue("not refused as private and past the focus check (the clip reached the player; Robolectric's player rejects the dummy bytes)",
            got.none { it.error == "private_headset" || it.error == "audio focus denied" })
    }

    @Test fun P06_theWatchArrivalAlertIsShownSilentlyWhilePrivate() {
        offer(private = true)
        deliverReply("turn-1#later1")
        val shown = manager.activeNotifications.single()
        assertEquals("reply_arrival_quiet", shown.notification.channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel("reply_arrival_quiet").importance)
        assertNull(shown.notification.sound)
        assertTrue("still local-only and visible", shown.notification.flags and android.app.Notification.FLAG_LOCAL_ONLY != 0)
    }

    @Test fun P07_theWatchArrivalAlertKeepsItsNormalChannelWhenNotPrivate() {
        offer(private = false)
        deliverReply("turn-2#later1")
        assertEquals("reply_arrival", manager.activeNotifications.single().notification.channelId)
    }
}
