package com.rumi.hermesvoice.watch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import android.os.PowerManager
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import io.mockk.*
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual Watch listener -> durable mirror -> running runtime, real battery receiver and service. ASR is the existing debug fixture. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class ChargingWakeR7WatchRoboTest {
    private lateinit var app: WatchApp
    private var service: org.robolectric.android.controller.ServiceController<WatchVoiceService>? = null
    private var rev = 100L
    private fun idle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as WatchApp
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        mockkStatic(Wearable::class)
        val node = mockk<Node>()
        every { node.id } returns "r7-phone"; every { node.isNearby } returns true
        every { node.displayName } returns "phone"
        val info = mockk<CapabilityInfo>()
        every { info.nodes } returns setOf(node); every { info.name } returns WatchLinkPaths.CAPABILITY_PHONE
        val capability = mockk<CapabilityClient>()
        every { capability.getCapability(any(), any()) } returns Tasks.forResult(info)
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } returns Tasks.forResult(1)
        val data = mockk<DataClient>(relaxed = true); val channels = mockk<ChannelClient>(relaxed = true)
        every { Wearable.getCapabilityClient(any<Context>()) } returns capability
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        every { Wearable.getDataClient(any<Context>()) } returns data
        every { Wearable.getChannelClient(any<Context>()) } returns channels
        runBlocking { app.refreshPhoneReachable() }
        @Suppress("DEPRECATION")
        app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, 1)
            .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_FULL))
        app.voice.wake.qaFixtureRecognizer = true
        option(phone = false, watch = true)
        battery(0, BatteryManager.BATTERY_STATUS_DISCHARGING)
    }
    @After fun tearDown() {
        runCatching { app.voice.stopBackground() }
        runCatching { service?.destroy() }
        @Suppress("DEPRECATION")
        app.removeStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED))
        unmockkAll()
    }
    private fun snapshot(phone: Boolean, watch: Boolean, revision: Long = ++rev): String =
        JSONObject(WatchSettings(WakeLocation.WATCH, "루미", revision = revision,
            watchBackgroundWakeEnabled = true).toJson()).put("phone_wake_while_charging", phone)
            .put("watch_wake_while_charging", watch).toString()
    private fun receive(raw: String) {
        val item = mockk<DataItem>(); every { item.uri } returns android.net.Uri.parse("wear://r7-phone" + WatchLinkPaths.SETTINGS)
        val map = DataMap(); map.putString("json", raw)
        val mapped = mockk<DataMapItem>(); every { mapped.dataMap } returns map
        mockkStatic(DataMapItem::class); every { DataMapItem.fromDataItem(item) } returns mapped
        val event = mockk<DataEvent>(); every { event.type } returns DataEvent.TYPE_CHANGED; every { event.dataItem } returns item
        val buffer = mockk<DataEventBuffer>(); every { buffer.iterator() } answers { mutableListOf(event).iterator() }
        val listener = Robolectric.buildService(WatchListenerService::class.java).create()
        try { listener.get().onDataChanged(buffer); idle(100) } finally { listener.destroy() }
    }
    private fun option(phone: Boolean, watch: Boolean) = receive(snapshot(phone, watch))
    private fun battery(plugged: Int, status: Int) {
        app.sendBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, plugged)
            .putExtra(BatteryManager.EXTRA_STATUS, status)); idle()
    }
    private fun startDark() {
        app.voice.onUserOpened()
        val visit = app.voice.onActivityResumed(); app.voice.onSettingsPulled(visit); app.voice.ensureBackground(); idle()
        val intent = shadowOf(app).nextStartedService
        assertNotNull("actual Watch foreground service request", intent)
        service = Robolectric.buildService(WatchVoiceService::class.java, intent).create().startCommand(0, 1); idle()
        app.voice.onActivityPaused()
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF)); idle()
    }
    private fun listening() = app.voice.wake.wake.listening

    @Test fun stickyBatteryIsHydratedWhenObservationRestartsAtANewUserOpen() {
        app.voice.stopBackground(); idle()
        @Suppress("DEPRECATION")
        app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, 1)
            .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_FULL))
        startDark()
        assertTrue("sticky local charge at restart, no live battery event needed", listening())
    }

    @Test fun missingExtrasAndUnsupportedStatusDoNotAuthorizeWatch() {
        startDark(); battery(1, BatteryManager.BATTERY_STATUS_CHARGING); assertTrue(listening())
        for (event in listOf(Intent(Intent.ACTION_BATTERY_CHANGED),
            Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, 1),
            Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_FULL),
            Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, 1).putExtra(BatteryManager.EXTRA_STATUS, 99))) {
            app.sendBroadcast(event); idle(); assertFalse(listening())
            battery(1, BatteryManager.BATTERY_STATUS_CHARGING); assertTrue(listening())
        }
    }

    @Test fun stopUnregistersBatteryAndRejectsItsQueuedCallback() {
        startDark(); battery(1, BatteryManager.BATTERY_STATUS_CHARGING); assertTrue(listening())
        assertTrue(shadowOf(app).registeredReceivers.any { it.intentFilter.hasAction(Intent.ACTION_BATTERY_CHANGED) })
        app.sendBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, 1)
            .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_FULL))
        app.voice.stopBackground(); idle()
        assertFalse(shadowOf(app).registeredReceivers.any { it.intentFilter.hasAction(Intent.ACTION_BATTERY_CHANGED) })
        assertFalse(listening()); assertNull(app.voice.presence.pendingRearm)
    }

    @Test fun permissionLossStillDisarmsChargingWatch() {
        startDark(); battery(1, BatteryManager.BATTERY_STATUS_CHARGING); assertTrue(listening())
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        app.voice.onPermissionResult(); idle(); assertFalse(listening()); assertFalse(app.voice.presence.armed)
    }

    @Test fun serviceDestructionUnregistersLocalBatteryAndLateBroadcastDoesNotArm() {
        startDark(); battery(1, BatteryManager.BATTERY_STATUS_CHARGING); assertTrue(listening())
        service!!.destroy(); service = null; idle()
        assertFalse(shadowOf(app).registeredReceivers.any { it.intentFilter.hasAction(Intent.ACTION_BATTERY_CHANGED) })
        battery(1, BatteryManager.BATTERY_STATUS_FULL); assertFalse(app.voice.presence.armed)
    }

    @Test fun manualAndAcceptedHandsFreeCapturesSurviveChargingRevocation() {
        org.robolectric.shadows.ShadowAudioRecord.setSourceProvider {
            object : org.robolectric.shadows.ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(bytes: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                    java.util.Arrays.fill(bytes, offset, offset + size, 0.toByte()); Thread.sleep(5); return size
                }
            }
        }
        startDark(); battery(1, BatteryManager.BATTERY_STATUS_CHARGING); assertTrue(listening())
        app.voice.wake.qaHeard("루미", true); idle(350)
        assertTrue("real accepted Watch capture", app.voice.capturing)
        battery(0, BatteryManager.BATTERY_STATUS_DISCHARGING); option(false, false)
        assertTrue(app.voice.capturing); assertNull(app.voice.presence.pendingRearm)
        app.voice.stopBackground(); idle()
        option(false, true); startDark(); battery(1, BatteryManager.BATTERY_STATUS_CHARGING)
        assertTrue(app.voice.onTalkPressed()); idle(350)
        assertTrue("real manual capture", app.voice.capturing)
        battery(0, BatteryManager.BATTERY_STATUS_DISCHARGING); option(false, false)
        assertTrue(app.voice.capturing); assertNull(app.voice.presence.pendingRearm)
    }

    @Test fun chargingBroadcastArmsDarkWatchAndFullPluggedRemainsActive() {
        startDark(); assertFalse(listening())
        battery(BatteryManager.BATTERY_PLUGGED_USB, BatteryManager.BATTERY_STATUS_CHARGING)
        assertTrue("actual local battery receiver must lift dark-screen restriction", listening())
        battery(BatteryManager.BATTERY_PLUGGED_USB, BatteryManager.BATTERY_STATUS_FULL)
        assertTrue(listening()); assertFalse(app.voice.keepScreenOn.value)
        battery(0, BatteryManager.BATTERY_STATUS_FULL)
        assertFalse(listening()); assertNull(app.voice.presence.pendingRearm)
    }

    @Test fun listenerPersistsIndependentOptionsAndUpdatesAnAlreadyRunningController() {
        startDark(); battery(BatteryManager.BATTERY_PLUGGED_USB, BatteryManager.BATTERY_STATUS_CHARGING)
        assertTrue(listening())
        option(phone = true, watch = false)
        assertFalse(listening()); assertNull(app.voice.presence.pendingRearm)
        val stored = app.getSharedPreferences("hermes_voice_watch", Context.MODE_PRIVATE).getString("settings_json", null)
        assertNotNull("actual listener persists the mirror", stored)
        val cold = WatchSettings.fromJson(stored)
        assertTrue(JSONObject(cold.toJson()).getBoolean("phone_wake_while_charging"))
        assertFalse(JSONObject(cold.toJson()).getBoolean("watch_wake_while_charging"))
        assertEquals(app.settings.value.revision, cold.revision)
        assertTrue("running mirror retains Phone option independently", JSONObject(app.settings.value.toJson()).getBoolean("phone_wake_while_charging"))
        assertFalse(JSONObject(app.settings.value.toJson()).getBoolean("watch_wake_while_charging"))
        option(phone = true, watch = true)
        assertTrue("a newer snapshot adopts the local charge state without a new Activity", listening())
    }

    @Test fun equalConflictAndOldReplayCannotRestoreListeningAfterLiveOff() {
        startDark(); battery(BatteryManager.BATTERY_PLUGGED_USB, BatteryManager.BATTERY_STATUS_CHARGING)
        assertTrue(listening())
        val old = snapshot(false, true); receive(old)
        val off = snapshot(false, false); receive(off); assertFalse(listening())
        receive(snapshot(false, true, rev)); receive(old)
        assertFalse(listening()); assertNull(app.voice.presence.pendingRearm)
        assertFalse(JSONObject(app.settings.value.toJson()).getBoolean("watch_wake_while_charging"))
    }

    @Test fun pluggedNotChargingUnknownAndDischargingCannotAuthorizeWatch() {
        startDark()
        for (status in listOf(BatteryManager.BATTERY_STATUS_NOT_CHARGING, BatteryManager.BATTERY_STATUS_UNKNOWN, BatteryManager.BATTERY_STATUS_DISCHARGING)) {
            battery(BatteryManager.BATTERY_PLUGGED_USB, status)
            assertFalse("status=$status", listening())
        }
        battery(BatteryManager.BATTERY_PLUGGED_USB, BatteryManager.BATTERY_STATUS_CHARGING)
        assertTrue(listening())
        battery(0, BatteryManager.BATTERY_STATUS_CHARGING)
        assertFalse(listening())
    }

    @Test fun duplicateChargingBroadcastDoesNotRestartWindowAndStopRejectsLateEvents() {
        startDark(); battery(BatteryManager.BATTERY_PLUGGED_USB, BatteryManager.BATTERY_STATUS_CHARGING)
        assertTrue(listening()); val gen = app.voice.wake.wake.generation
        repeat(4) { battery(BatteryManager.BATTERY_PLUGGED_USB, BatteryManager.BATTERY_STATUS_CHARGING) }
        assertEquals(gen, app.voice.wake.wake.generation)
        app.voice.stopBackground(); idle()
        battery(BatteryManager.BATTERY_PLUGGED_USB, BatteryManager.BATTERY_STATUS_FULL)
        assertFalse(listening()); assertFalse(app.voice.presence.armed)
    }
}
