package com.rumi.hermesvoice.watch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.PowerManager
import android.speech.SpeechRecognizer
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItemBuffer
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSpeechRecognizer

/**
 * SCRATCH HARNESS (never packaged). Background standby is background only: through the REAL WatchActivity, WatchApp,
 * WatchVoiceRuntime, coordinator and wake flow, an app that is on screen listens exactly when the Phone-owned foreground
 * location includes the Watch, and a hidden armed session (or the screen off) listens when the Watch's own standby is on,
 * whatever the location. Simulated platform inputs only (recognizer, permission answers, service delivery, Data Layer mocks).
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchForegroundSeparationRoboTest {
    private lateinit var app: WatchApp
    private var scenario: ActivityScenario<WatchActivity>? = null
    private var revision = 0L

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
        every { Wearable.getDataClient(any<android.app.Activity>()) } returns data
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun settings(location: WakeLocation, standby: Boolean) {
        revision += 1
        app.applySettings(watchScreenOffOn(WatchSettings(location, "루미", watchBackgroundWakeEnabled = standby, revision = revision).toJson()))
        idle(50)
    }

    private fun deliverService() {
        val intent = shadowOf(app).nextStartedService ?: return
        Robolectric.buildService(WatchVoiceService::class.java, intent).create().startCommand(0, 1)
        idle(200)
    }

    private fun listening() = app.voice.wakeListening.value

    private fun launchAndEnterService() {
        scenario = ActivityScenario.launch(WatchActivity::class.java)
        idle(500)
        deliverService()
    }

    private fun screen(on: Boolean) {
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(on)
        app.sendBroadcast(Intent(if (on) Intent.ACTION_SCREEN_ON else Intent.ACTION_SCREEN_OFF))
        idle(200)
    }

    @Before
    fun setUp() {
        WatchVoiceService.running?.finish()
        ShadowLog.stream = System.out
        app = RuntimeEnvironment.getApplication() as WatchApp
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        stubWearable()
    }

    @After
    fun tearDown() {
        scenario?.close()
        WatchVoiceService.running?.finish()
        unmockkAll()
    }

    @Test fun F01_locationPhoneWithWatchStandbyOnOpensNoWatchForegroundListeningButArmsTheService() {
        settings(WakeLocation.PHONE, standby = true)
        launchAndEnterService()
        assertTrue("the visible visit arms the legal standby service", app.voice.presence.armed)
        assertFalse("the Watch is excluded from the foreground by the location: nothing listens on screen", listening())
    }

    @Test fun F02_locationOffWithWatchStandbyOnOpensNoWatchForegroundListening() {
        settings(WakeLocation.OFF, standby = true)
        launchAndEnterService()
        assertTrue(app.voice.presence.armed)
        assertFalse("location OFF excludes the foreground: nothing listens on screen", listening())
    }

    @Test fun F03_theExcludedWatchNeverListensHiddenOrWithTheScreenOffWhateverStandbySays() {
        settings(WakeLocation.PHONE, standby = true)
        launchAndEnterService()
        assertFalse("excluded on screen", listening())
        scenario!!.moveToState(Lifecycle.State.CREATED); idle(300)
        assertFalse("hidden: standby never selects a Watch that Listen on leaves out", listening())
        scenario!!.moveToState(Lifecycle.State.RESUMED); idle(500)
        assertFalse("shown again: still excluded", listening())
        assertTrue("the armed service survives the visit", app.voice.presence.armed)
        screen(false)
        assertFalse("screen off with the app still shown: still not selected", listening())
        screen(true)
        assertFalse("screen back on over the visible app: the selector still excludes it", listening())
        assertTrue(app.voice.presence.armed)
    }

    @Test fun F04_aSelectedWatchLocationWithStandbyOffListensOnScreenAndNeverArmsOrListensHidden() {
        settings(WakeLocation.WATCH, standby = false)
        launchAndEnterService()
        assertTrue("foreground selected: listens on screen with the standby off", listening())
        assertFalse("standby off: the microphone is never armed for the background", app.voice.presence.armed)
        scenario!!.moveToState(Lifecycle.State.CREATED); idle(300)
        assertFalse("hidden with the standby off: nothing listens", listening())
    }

    @Test fun F05_aSelectedWatchLocationWithStandbyOnListensOnScreenAndHidden() {
        settings(WakeLocation.WATCH, standby = true)
        launchAndEnterService()
        assertTrue(listening())
        assertTrue(app.voice.presence.armed)
        scenario!!.moveToState(Lifecycle.State.CREATED); idle(300)
        assertTrue("hidden: the standby listens", listening())
    }

    @Test fun F06_turningStandbyOnInTheVisibleAppForAnExcludedLocationOpensNoForegroundWindow() {
        settings(WakeLocation.PHONE, standby = false)
        launchAndEnterService()
        assertFalse(listening())
        assertFalse(app.voice.presence.armed)
        settings(WakeLocation.PHONE, standby = true)
        assertFalse("a standby switch is not a foreground selection", listening())
    }
}
