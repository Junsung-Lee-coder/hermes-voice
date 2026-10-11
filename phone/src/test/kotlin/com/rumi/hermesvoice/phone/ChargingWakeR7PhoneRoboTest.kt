package com.rumi.hermesvoice.phone

import android.Manifest
import android.content.Intent
import android.os.BatteryManager
import android.os.PowerManager
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSpeechRecognizer

/** Real PhoneApp store, PhoneBackgroundRuntime receiver/controller, PhoneWakeService; platform ASR and battery inputs are simulated. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class ChargingWakeR7PhoneRoboTest : PhoneRoboBase() {
    private var service: org.robolectric.android.controller.ServiceController<PhoneWakeService>? = null

    @Before fun setUp() {
        baseSetUp()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        save(true, false)
    }
    @After fun tearDown() {
        runCatching { app.phoneWake.stop() }
        runCatching { service?.destroy() }
        @Suppress("DEPRECATION")
        app.removeStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED))
        baseTearDown()
    }
    private fun save(phone: Boolean, watch: Boolean, screenOff: Boolean = false) {
        val owner = WatchSettings(WakeLocation.PHONE, "루미", phoneBackgroundWakeEnabled = true,
            phoneBackgroundWakeScreenOffEnabled = screenOff)
        val json = JSONObject(owner.toJson()).put("phone_wake_while_charging", phone).put("watch_wake_while_charging", watch)
        app.settings.saveWatchSettings(requireNotNull(WatchSettings.parse(json.toString())))
    }
    private fun battery(plugged: Int, status: Int, sticky: Boolean = false) {
        val event = Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, plugged)
            .putExtra(BatteryManager.EXTRA_STATUS, status)
        @Suppress("DEPRECATION")
        if (sticky) app.sendStickyBroadcast(event) else app.sendBroadcast(event)
        idle(50)
    }
    private fun hideAndDark() {
        app.phoneWake.onAppShown(); idle(50)
        val intent = shadowOf(app).nextStartedService
        assertNotNull("visible saved standby requests the real service", intent)
        assertEquals(PhoneWakeService::class.java.name, intent.component?.className)
        service = Robolectric.buildService(PhoneWakeService::class.java, intent).create().startCommand(0, 1)
        app.phoneWake.onAppHidden(); idle(800)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF)); idle(50)
    }
    private fun listening() = app.phoneWake.status.value.listeningNow

    @Test fun missingExtrasAndUnsupportedStatusAreFailClosedAtTheRealReceiver() {
        hideAndDark()
        battery(BatteryManager.BATTERY_PLUGGED_USB, BatteryManager.BATTERY_STATUS_CHARGING)
        assertTrue(listening())
        for (event in listOf(Intent(Intent.ACTION_BATTERY_CHANGED),
            Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, 1),
            Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_FULL),
            Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, 1).putExtra(BatteryManager.EXTRA_STATUS, 99))) {
            app.sendBroadcast(event); idle(50); assertFalse(listening())
            battery(BatteryManager.BATTERY_PLUGGED_USB, BatteryManager.BATTERY_STATUS_CHARGING); assertTrue(listening())
        }
    }

    @Test fun permissionLossAndServiceRefusalCannotBeBypassed() {
        hideAndDark(); battery(1, BatteryManager.BATTERY_STATUS_CHARGING); assertTrue(listening())
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        app.phoneWake.onEligibilityChanged(); idle(50); assertFalse(listening())
        app.phoneWake.background.onMicrophoneRefused(app.phoneWake.background.session.generation)
        battery(1, BatteryManager.BATTERY_STATUS_FULL); assertFalse(listening())
    }

    @Test fun chargingDoesNotInterruptAcceptedBusyTurns() {
        hideAndDark(); battery(1, BatteryManager.BATTERY_STATUS_CHARGING); assertTrue(listening())
        app.phoneTurns.value = 1
        assertTrue("real Default-dispatcher busy collector reached the main controller", await { !listening() })
        val turns = app.phoneTurns.value
        battery(0, BatteryManager.BATTERY_STATUS_DISCHARGING); save(false, false)
        app.phoneWake.onEligibilityChanged(); idle(50)
        assertEquals(turns, app.phoneTurns.value); assertFalse(listening())
    }

    @Test fun offRemovesRealBackoffAlarmAndLateCallbackCannotRestart() {
        hideAndDark(); battery(1, BatteryManager.BATTERY_STATUS_CHARGING); assertTrue(listening())
        val recognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()!!
        org.robolectric.shadow.api.Shadow.extract<ShadowSpeechRecognizer>(recognizer).triggerOnError(android.speech.SpeechRecognizer.ERROR_AUDIO)
        idle(50)
        val alarms = shadowOf(app.getSystemService(android.app.AlarmManager::class.java))
        assertNotNull(alarms.peekNextScheduledAlarm())
        save(false, false); app.phoneWake.onEligibilityChanged(); idle(50)
        assertNull(alarms.peekNextScheduledAlarm())
        app.phoneWake.onRearmAlarm(); idle(50); assertFalse(listening())
    }

    @Test fun stopUnregistersOnlyItsBatteryObserverAndQueuedBatteryCannotRearm() {
        hideAndDark(); battery(1, BatteryManager.BATTERY_STATUS_CHARGING); assertTrue(listening())
        assertTrue(shadowOf(app).registeredReceivers.any { it.intentFilter.hasAction(Intent.ACTION_BATTERY_CHANGED) })
        app.sendBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, 1)
            .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_FULL))
        app.phoneWake.stop(); idle(50)
        assertFalse(shadowOf(app).registeredReceivers.any { it.intentFilter.hasAction(Intent.ACTION_BATTERY_CHANGED) })
        assertFalse(listening())
    }

    @Test fun foregroundChargingStartsWhenStandbyServiceIsAdmittedAfterOrdinaryWindowExpired() {
        app.settings.saveWatchSettings(app.settings.watchSettings().copy(phoneBackgroundWakeEnabled = false))
        battery(1, BatteryManager.BATTERY_STATUS_CHARGING, sticky = true)
        controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        val foreground = org.robolectric.util.ReflectionHelpers.getField<PhoneWakeController>(controller!!.get(), "phoneWake")
        foreground.qaFixtureRecognizer = true
        controller!!.resume(); idle(5600)
        assertFalse("ordinary foreground window is spent while standby is off", foreground.wake.listening)
        val model = androidx.lifecycle.ViewModelProvider(controller!!.get())[PhoneViewModel::class.java]
        model.setPhoneBackgroundWake(true); idle(100)
        assertFalse("a service request alone must not grant continuous recognition", foreground.wake.listening)
        val intent = shadowOf(app).nextStartedService
        assertNotNull("real standby service was requested", intent)
        service = Robolectric.buildService(PhoneWakeService::class.java, intent).create().startCommand(0, 1)
        idle(100)
        assertTrue("actual current microphone service entry must reopen charging foreground without a new Activity or battery event", foreground.wake.listening)
    }

    @Test fun chargingForegroundUsesBoundedRearmAndUnplugReturnsToOrdinaryOneWindow() {
        battery(1, BatteryManager.BATTERY_STATUS_CHARGING, sticky = true)
        controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        val foreground = org.robolectric.util.ReflectionHelpers.getField<PhoneWakeController>(controller!!.get(), "phoneWake")
        foreground.qaFixtureRecognizer = true
        controller!!.resume(); idle(100)
        val intent = shadowOf(app).nextStartedService
        assertNotNull(intent)
        service = Robolectric.buildService(PhoneWakeService::class.java, intent).create().startCommand(0, 1)
        assertTrue(foreground.wake.listening)
        val gen = foreground.wake.generation
        idle(5600)
        assertTrue("charging foreground reopens through bounded windows", foreground.wake.listening)
        assertTrue(foreground.wake.generation > gen)
        battery(0, BatteryManager.BATTERY_STATUS_DISCHARGING)
        assertFalse("unplug after the original activation expired closes idle foreground immediately", foreground.wake.listening)
        idle(6000); assertFalse("ordinary foreground budget never reopens from battery", foreground.wake.listening)
    }

    @Test fun signoutClosesChargingIdleRecognitionAndLateEventsCannotSubmit() {
        hideAndDark(); battery(1, BatteryManager.BATTERY_STATUS_CHARGING); assertTrue(listening())
        val model = PhoneViewModel(app)
        model.signOut(); idle(50)
        assertFalse("sign-out is still an admission fence", listening())
        battery(1, BatteryManager.BATTERY_STATUS_FULL); app.phoneWake.onRearmAlarm(); idle(50)
        assertFalse(listening()); assertTrue(fake.rawPrompts.isEmpty())
    }

    @Test fun endedServiceUnregistersBatteryAndDoesNotRestartFromAQueuedEvent() {
        hideAndDark(); battery(1, BatteryManager.BATTERY_STATUS_CHARGING); assertTrue(listening())
        service!!.destroy(); service = null; idle(50)
        assertFalse(shadowOf(app).registeredReceivers.any { it.intentFilter.hasAction(Intent.ACTION_BATTERY_CHANGED) })
        battery(1, BatteryManager.BATTERY_STATUS_FULL); assertFalse(listening())
    }

    @Test fun mutedMicrophoneRetainsOnlyBoundedRetryAndOffClearsIt() {
        hideAndDark(); battery(1, BatteryManager.BATTERY_STATUS_CHARGING); assertTrue(listening())
        val audio = app.getSystemService(android.media.AudioManager::class.java)
        audio.isMicrophoneMute = true
        val recognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()!!
        org.robolectric.shadow.api.Shadow.extract<ShadowSpeechRecognizer>(recognizer).triggerOnError(android.speech.SpeechRecognizer.ERROR_NO_MATCH)
        idle(400)
        assertFalse(listening())
        val alarms = shadowOf(app.getSystemService(android.app.AlarmManager::class.java))
        assertNotNull("mute uses established bounded retry", alarms.peekNextScheduledAlarm())
        save(false, false); app.phoneWake.onEligibilityChanged(); idle(50)
        assertNull(alarms.peekNextScheduledAlarm()); audio.isMicrophoneMute = false
    }

    @Test fun acceptedBackgroundCaptureSurvivesUnplugAndLiveOff() {
        org.robolectric.shadows.ShadowAudioRecord.setSourceProvider {
            object : org.robolectric.shadows.ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(bytes: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                    java.util.Arrays.fill(bytes, offset, offset + size, 0.toByte()); Thread.sleep(5); return size
                }
            }
        }
        hideAndDark(); battery(1, BatteryManager.BATTERY_STATUS_CHARGING); assertTrue(listening())
        val recognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()!!
        org.robolectric.shadow.api.Shadow.extract<ShadowSpeechRecognizer>(recognizer).triggerOnResults(
            android.os.Bundle().apply { putStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("루미")) })
        idle(350); assertTrue("actual accepted recorder owns mic", app.phoneWake.capturing)
        battery(0, BatteryManager.BATTERY_STATUS_DISCHARGING); save(false, false)
        app.phoneWake.onEligibilityChanged(); idle(50)
        assertTrue(app.phoneWake.capturing)
        assertFalse(listening())
    }

    @Test fun localChargingBroadcastAllowsDarkPhoneAndUnplugImmediatelyClosesIt() {
        hideAndDark(); assertFalse(listening())
        battery(BatteryManager.BATTERY_PLUGGED_USB, BatteryManager.BATTERY_STATUS_CHARGING)
        assertTrue("actual Phone receiver must lift only screen eligibility", listening())
        battery(0, BatteryManager.BATTERY_STATUS_DISCHARGING)
        assertFalse(listening())
        app.phoneWake.onRearmAlarm(); idle(50); assertFalse(listening())
    }

    @Test fun fullAndPluggedStayEligibleButDisconnectedUnknownAndNotChargingDoNot() {
        hideAndDark()
        val cases = listOf(
            BatteryManager.BATTERY_PLUGGED_AC to BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_PLUGGED_USB to BatteryManager.BATTERY_STATUS_FULL,
            0 to BatteryManager.BATTERY_STATUS_FULL,
            BatteryManager.BATTERY_PLUGGED_AC to BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            BatteryManager.BATTERY_PLUGGED_AC to BatteryManager.BATTERY_STATUS_DISCHARGING,
            BatteryManager.BATTERY_PLUGGED_AC to BatteryManager.BATTERY_STATUS_UNKNOWN,
            0 to BatteryManager.BATTERY_STATUS_CHARGING,
        )
        for ((plugged, status) in cases) {
            battery(plugged, status)
            assertEquals("plugged=$plugged status=$status", plugged != 0 && status in listOf(BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL), listening())
        }
    }

    @Test fun stickyBatteryAtLazyRuntimeCreationHydratesBeforeFirstDarkWindow() {
        battery(BatteryManager.BATTERY_PLUGGED_USB, BatteryManager.BATTERY_STATUS_CHARGING, sticky = true)
        hideAndDark()
        assertTrue("initial sticky battery must reach actual runtime", listening())
    }

    @Test fun watchOptionDoesNotAuthorizeThePhoneAndLiveOffRevokesIdleListening() {
        save(false, true); hideAndDark()
        battery(BatteryManager.BATTERY_PLUGGED_USB, BatteryManager.BATTERY_STATUS_CHARGING)
        assertFalse("only Watch opted in", listening())
        save(true, true); app.phoneWake.onEligibilityChanged(); idle(50)
        assertTrue(listening())
        save(false, true); app.phoneWake.onEligibilityChanged(); idle(50)
        assertFalse(listening())
        app.phoneWake.onRearmAlarm(); idle(50); assertFalse(listening())
    }

    @Test fun existingScreenOffOptionKeepsWorkingUnpluggedAndStopCannotBeUndoneByBattery() {
        save(false, false, screenOff = true); hideAndDark()
        battery(0, BatteryManager.BATTERY_STATUS_DISCHARGING)
        assertTrue("existing screen-off recognition remains independent", listening())
        app.phoneWake.stop(); idle(50)
        battery(BatteryManager.BATTERY_PLUGGED_USB, BatteryManager.BATTERY_STATUS_CHARGING)
        assertFalse(listening()); assertFalse(app.phoneWake.status.value.session.wanted)
    }
}
