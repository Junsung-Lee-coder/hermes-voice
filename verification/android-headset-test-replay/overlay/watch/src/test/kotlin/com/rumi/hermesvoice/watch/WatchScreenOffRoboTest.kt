package com.rumi.hermesvoice.watch

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Bundle
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
import com.rumi.hermesvoice.core.background.HoldReason
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.time.Duration
import kotlin.random.Random
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
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowService
import org.robolectric.shadows.ShadowSpeechRecognizer
import org.robolectric.util.ReflectionHelpers

/**
 * SCRATCH HARNESS (never packaged). R3: the Watch's own "background wake recognition with screen off" preference, through the REAL
 * call sites: WatchApp.applySettings (the Phone snapshot, carrying the preference only in its wire format), the real screen
 * broadcast receiver, the real recognizer callbacks, StandbyScheduler/StandbyAlarmReceiver and WatchVoiceService's foreground type.
 * Robolectric bookkeeping only: no claim about real Doze, AOD hardware, the real microphone or paired devices.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchScreenOffRoboTest {
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

    /** [screenOff] null: a legacy snapshot without the preference key (migrates to OFF). */
    private fun settings(location: WakeLocation, standby: Boolean, screenOff: Boolean?) {
        revision += 1
        val json = JSONObject(WatchSettings(location, "루미", watchBackgroundWakeEnabled = standby, revision = revision).toJson())
        if (screenOff != null) json.put("watch_background_wake_screen_off_enabled", screenOff) else json.remove("watch_background_wake_screen_off_enabled")
        app.applySettings(json.toString())
        idle(50)
    }

    private fun alarms() = shadowOf(app.getSystemService(AlarmManager::class.java))

    private fun deliverService() {
        val intent = shadowOf(app).nextStartedService ?: return
        Robolectric.buildService(WatchVoiceService::class.java, intent).create().startCommand(0, 1)
        idle(200)
    }

    private fun listening() = app.voice.wakeListening.value

    private fun serviceType(): Int {
        val service = WatchVoiceService.running ?: return -1
        return ReflectionHelpers.callInstanceMethod<Int>(shadowOf(service) as ShadowService, "getForegroundServiceType")
    }

    private val microphoneType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

    private fun startNoisePcm() {
        val random = Random(7)
        ShadowAudioRecord.setSourceProvider {
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int, isBlocking: Boolean): Int {
                    for (i in 0 until sizeInBytes / 2) {
                        val s = random.nextInt(-40, 40)
                        audioData[offsetInBytes + 2 * i] = (s and 0xff).toByte()
                        audioData[offsetInBytes + 2 * i + 1] = (s shr 8 and 0xff).toByte()
                    }
                    Thread.sleep(5)
                    return sizeInBytes - sizeInBytes % 2
                }
            }
        }
    }

    private fun recognizer(): SpeechRecognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()!!
    private fun ready() { org.robolectric.shadow.api.Shadow.extract<ShadowSpeechRecognizer>(recognizer()).triggerOnReadyForSpeech(Bundle()); idle(30) }
    private fun results(text: String) {
        org.robolectric.shadow.api.Shadow.extract<ShadowSpeechRecognizer>(recognizer())
            .triggerOnResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) })
        idle(30)
    }

    private fun openArmed() {
        scenario = ActivityScenario.launch(WatchActivity::class.java)
        idle(500)
        deliverService()
        assertTrue("armed while visible", app.voice.presence.armed)
    }

    private fun hide() { scenario!!.moveToState(Lifecycle.State.CREATED); idle(200) }

    private fun screenOff() {
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        idle(200)
    }

    private fun screenOn() {
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        idle(200)
    }

    private fun untilListening() {
        var spent = 0L
        while (!listening() && spent < MAX_WAIT_MS) { idle(50); spent += 50 }
    }

    private fun failGap() {
        ready()
        org.robolectric.shadow.api.Shadow.extract<ShadowSpeechRecognizer>(recognizer()).triggerOnError(SpeechRecognizer.ERROR_AUDIO)
        idle(30)
        assertNotNull("a recognizer failure scheduled the backoff alarm", alarms().peekNextScheduledAlarm())
        assertFalse("the failed window is closed", listening())
    }

    private fun held() = app.holds.held()

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

    private fun hiddenListening(pref: Boolean?) {
        settings(WakeLocation.WATCH, standby = true, screenOff = pref)
        startNoisePcm()
        openArmed(); hide()
        untilListening()
        assertTrue("positive control: hidden with the screen on, the standby listens", listening())
        assertTrue(HoldReason.LISTEN in held())
    }

    @Test fun S01_screenOffWithPreferenceOffTearsDownTheIdleListeningAtOnce() {
        hiddenListening(pref = false)
        screenOff()
        assertFalse("the idle recognizer is gone", listening())
        assertFalse("no LISTEN hold: ${held()}", HoldReason.LISTEN in held())
        assertNull("no pending gap alarm", alarms().peekNextScheduledAlarm())
        assertEquals("the armed session keeps its microphone type", microphoneType, serviceType())
        idle(5_000)
        assertFalse("nothing resurrects it", listening())
        assertNull(alarms().peekNextScheduledAlarm())
    }

    @Test fun S02_screenOffWithPreferenceOnKeepsListeningAndKeepsItsGaps() {
        hiddenListening(pref = true)
        screenOff()
        failGap()
        assertNotNull("an eligible screen-off Watch keeps scheduling its gap", alarms().peekNextScheduledAlarm())
        untilListening()
        assertTrue("and opens the next window with the screen off", listening())
        assertEquals(microphoneType, serviceType())
    }

    @Test fun S03_aLegacySnapshotWithoutTheKeyMeansOff() {
        hiddenListening(pref = null)
        screenOff()
        assertFalse(listening())
        assertNull(alarms().peekNextScheduledAlarm())
    }

    @Test fun S04_pendingGapAlarmIsCancelledOnScreenOffAndAStaleAlarmOpensNothing() {
        hiddenListening(pref = false)
        failGap()
        val operation = alarms().peekNextScheduledAlarm()!!.operation
        screenOff()
        assertNull("the platform alarm of the gap is cancelled", alarms().peekNextScheduledAlarm())
        StandbyAlarmReceiver().onReceive(app, shadowOf(operation).savedIntent)
        idle(1_000)
        assertFalse("a stale alarm opens nothing", listening())
        assertNull("and schedules nothing", alarms().peekNextScheduledAlarm())
        assertFalse(HoldReason.LISTEN in held())
    }

    @Test fun S05_turningThePreferenceOffWhileAlreadyScreenOffTearsDownAndOnRestoresOneWindow() {
        hiddenListening(pref = true)
        screenOff()
        assertTrue(listening())
        settings(WakeLocation.WATCH, standby = true, screenOff = false)
        assertFalse("OFF while already screen off tears the idle recognizer down", listening())
        assertFalse(HoldReason.LISTEN in held())
        assertNull(alarms().peekNextScheduledAlarm())
        settings(WakeLocation.WATCH, standby = true, screenOff = true)
        untilListening()
        assertTrue("ON while screen off opens a window", listening())
        settings(WakeLocation.WATCH, standby = true, screenOff = true)
        assertTrue("the same snapshot again is not a second window", listening())
    }

    @Test fun S06_screenBackOnResumesTheBackgroundListeningWithoutAVisit() {
        hiddenListening(pref = false)
        screenOff()
        assertFalse(listening())
        screenOn()
        untilListening()
        assertTrue("interactive and hidden is the ordinary background case", listening())
    }

    @Test fun S07_aManualRecordingWithAPendingGapSurvivesTheScreenOffTeardown() {
        hiddenListening(pref = false)
        failGap()
        assertTrue(app.voice.onTalkPressed()); idle(100)
        assertTrue("a manual push-to-talk is really recording", app.voice.capturing)
        screenOff()
        assertNull("the idle gap alarm is cancelled", alarms().peekNextScheduledAlarm())
        assertTrue("the recording is not cut by the screen going off", app.voice.capturing)
        assertTrue(HoldReason.CAPTURE in held())
        assertEquals(microphoneType, serviceType())
        assertFalse(HoldReason.LISTEN in held())
    }

    @Test fun S08_anAcceptedHandsFreeRecordingSurvivesTheScreenOffTeardown() {
        hiddenListening(pref = false)
        ready()
        results("루미"); idle(1_000)
        assertTrue("the accepted hands-free recording is under way", app.voice.capturing)
        screenOff()
        assertTrue("an accepted recording is never cut by the screen going off", app.voice.capturing)
        assertTrue(HoldReason.CAPTURE in held())
        assertFalse(listening())
        assertNull(alarms().peekNextScheduledAlarm())
    }

    @Test fun S09_screenOffWithTheMasterOffAndThePreferenceOnListensToNothing() {
        settings(WakeLocation.WATCH, standby = false, screenOff = true)
        startNoisePcm()
        scenario = ActivityScenario.launch(WatchActivity::class.java)
        idle(500)
        assertFalse("master off: nothing arms", app.voice.presence.armed)
        assertTrue("the selected Watch listens on screen on Listen on alone", listening())
        hide(); screenOff()
        idle(2_000)
        assertFalse("a screen-off preference is never a master", listening())
        assertNull(alarms().peekNextScheduledAlarm())
    }

    @Test fun S10_aWatchThatListenOnLeavesOutNeverListensWhateverThePreferenceSays() {
        settings(WakeLocation.PHONE, standby = true, screenOff = true)
        startNoisePcm()
        openArmed()
        assertFalse("the visible Watch app follows the Listen on selector alone", listening())
        hide()
        idle(1_000)
        assertFalse("hidden: standby and the screen-off preference never select a Watch that Listen on leaves out", listening())
        screenOff()
        idle(1_000)
        assertFalse("screen off: still not selected, still silent", listening())
        screenOn()
        idle(1_000)
        assertFalse("interactive again: the selector still excludes it", listening())
        assertNull(alarms().peekNextScheduledAlarm())
    }

    private companion object {
        const val MAX_WAIT_MS = 70_000L
    }
}
