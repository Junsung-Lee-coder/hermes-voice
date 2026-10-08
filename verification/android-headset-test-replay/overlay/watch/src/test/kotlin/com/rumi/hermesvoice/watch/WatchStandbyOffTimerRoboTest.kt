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
 * SCRATCH HARNESS (never packaged). R2: turning the Watch standby OFF while a manual push-to-talk or an accepted hands-free
 * recording is under way and a re-arm gap (the platform alarm) is pending. Everything goes through the REAL call sites:
 * WatchApp.applySettings (the Phone snapshot), WatchVoiceRuntime.onTalkPressed, the real recognizer callbacks, the real
 * StandbyScheduler alarm and StandbyAlarmReceiver, WatchVoiceService's foreground type. Robolectric's alarm, wake-lock and
 * service bookkeeping only: no claim about real Doze, the real microphone or paired devices.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchStandbyOffTimerRoboTest {
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

    private fun alarms() = shadowOf(app.getSystemService(AlarmManager::class.java))

    private fun deliverService() {
        val intent = shadowOf(app).nextStartedService ?: return
        Robolectric.buildService(WatchVoiceService::class.java, intent).create().startCommand(0, 1)
        idle(200)
    }

    private fun listening() = app.voice.wakeListening.value

    /** The foreground type the running service last entered the foreground with (a real WatchVoiceService.enter). */
    private fun serviceType(): Int {
        val service = WatchVoiceService.running ?: return -1
        return ReflectionHelpers.callInstanceMethod<Int>(shadowOf(service) as ShadowService, "getForegroundServiceType")
    }

    private val microphoneType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    private val playbackOnlyType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

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

    private fun untilGap() {
        var spent = 0L
        while (alarms().peekNextScheduledAlarm() == null && spent < MAX_WAIT_MS) { idle(50); spent += 50 }
        assertNotNull("a window closed and scheduled its gap within $MAX_WAIT_MS ms", alarms().peekNextScheduledAlarm())
    }

    /** A real recognizer failure closes the window: the 1 s failure backoff (timer + platform alarm) is pending, long enough to outlast a recording start. */
    private fun failGap() {
        ready()
        org.robolectric.shadow.api.Shadow.extract<ShadowSpeechRecognizer>(recognizer()).triggerOnError(SpeechRecognizer.ERROR_AUDIO)
        idle(30)
        assertNotNull("a recognizer failure scheduled the 1 s backoff alarm", alarms().peekNextScheduledAlarm())
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

    /** The core of the contract: OFF with a pending gap alarm and a manual recording under way. Returns the alarm that was pending. */
    private fun offWithPendingGapAndPtt(location: WakeLocation, hidden: Boolean, screenOff: Boolean) {
        settings(location, standby = true)
        startNoisePcm()
        openArmed()
        if (hidden) hide()
        if (screenOff) screenOff()
        assertTrue("listening before the gap", listening())
        failGap()
        val pending = alarms().peekNextScheduledAlarm()
        assertNotNull("positive control: the gap alarm is pending before the recording", pending)
        val operation = pending!!.operation

        assertTrue(app.voice.onTalkPressed()); idle(100)
        assertTrue("a manual push-to-talk is really recording", app.voice.capturing)
        assertNotNull("positive control: the gap alarm survives the recording starting", alarms().peekNextScheduledAlarm())
        assertEquals("positive control: microphone type held", microphoneType, serviceType())
        assertTrue("positive control: CAPTURE held by the recorder: ${held()}", HoldReason.CAPTURE in held())

        // The Phone's snapshot turns the Watch standby OFF while the recording goes on.
        settings(location, standby = false)
        assertNull("OFF cancels the pending gap alarm promptly", alarms().peekNextScheduledAlarm())
        assertTrue("the recording is not cut by OFF", app.voice.capturing)
        assertEquals("the microphone type stays until the recording ends", microphoneType, serviceType())
        assertTrue("CAPTURE hold kept: ${held()}", HoldReason.CAPTURE in held())
        assertFalse("no LISTEN hold: ${held()}", HoldReason.LISTEN in held())
        assertFalse("no HANDOFF hold: ${held()}", HoldReason.HANDOFF in held())
        assertFalse("no idle recognizer window", listening())

        // The alarm that was already in flight when OFF arrived: it opens nothing and leaves nothing behind.
        StandbyAlarmReceiver().onReceive(app, shadowOf(operation).savedIntent)
        idle(1_000)
        assertFalse("a stale alarm never reopens listening", listening())
        assertNull("a stale alarm schedules no new gap", alarms().peekNextScheduledAlarm())
        assertTrue("still recording after the stale alarm", app.voice.capturing)
        assertTrue("CAPTURE still held", HoldReason.CAPTURE in held())

        // The user ends the recording: only now is the microphone type given back, and nothing listens or is scheduled.
        assertTrue(app.voice.onTalkPressed()); idle(1_000)
        assertFalse("the recording ended", app.voice.capturing)
        assertEquals("the microphone type is given back after the recording", playbackOnlyType, serviceType())
        assertNull(alarms().peekNextScheduledAlarm())
        assertFalse(listening())
        assertFalse("no LISTEN hold after the recording: ${held()}", HoldReason.LISTEN in held())
        assertFalse(app.settings.value.watchBackgroundWakeEnabled)
    }

    @Test fun O01_hiddenGapAlarmAndManualRecordingAndOffLeavesNoAlarm() = offWithPendingGapAndPtt(WakeLocation.WATCH, hidden = true, screenOff = false)

    @Test fun O02_hiddenScreenOffGapAlarmAndManualRecordingAndOffLeavesNoAlarm() = offWithPendingGapAndPtt(WakeLocation.WATCH, hidden = true, screenOff = true)

    @Test fun O03_selectedForegroundGapThenManualRecordingThenHiddenThenOffLeavesNoAlarm() {
        settings(WakeLocation.WATCH, standby = true)
        startNoisePcm()
        openArmed()
        assertTrue("the foreground window listens", listening())
        failGap()
        val operation = alarms().peekNextScheduledAlarm()!!.operation
        assertTrue(app.voice.onTalkPressed()); idle(100)
        assertTrue("a manual push-to-talk is really recording", app.voice.capturing)
        hide()
        assertNotNull("positive control: the foreground window's gap alarm survives hiding", alarms().peekNextScheduledAlarm())
        assertTrue(app.voice.capturing)
        settings(WakeLocation.WATCH, standby = false)
        assertNull("OFF cancels the pending gap alarm promptly", alarms().peekNextScheduledAlarm())
        assertTrue("the recording is not cut by OFF", app.voice.capturing)
        assertTrue("CAPTURE hold kept", HoldReason.CAPTURE in held())
        StandbyAlarmReceiver().onReceive(app, shadowOf(operation).savedIntent)
        idle(1_000)
        assertFalse(listening())
        assertNull(alarms().peekNextScheduledAlarm())
        assertTrue(app.voice.capturing)
    }

    @Test fun O04_repeatedOffAndTheSameSnapshotAgainKeepTheRecordingAndAddNoAlarm() {
        settings(WakeLocation.WATCH, standby = true)
        startNoisePcm()
        openArmed(); hide()
        assertTrue(listening())
        failGap()
        assertTrue(app.voice.onTalkPressed()); idle(100)
        assertTrue(app.voice.capturing)
        assertNotNull("positive control: the gap alarm survives the recording starting", alarms().peekNextScheduledAlarm())
        settings(WakeLocation.WATCH, standby = false)
        settings(WakeLocation.WATCH, standby = false)
        app.applySettings(WatchSettings(WakeLocation.WATCH, "루미", watchBackgroundWakeEnabled = false, revision = revision).toJson()) // an equal revision again
        idle(200)
        assertNull(alarms().peekNextScheduledAlarm())
        assertTrue(app.voice.capturing)
        assertEquals(microphoneType, serviceType())
        assertFalse(listening())
    }

    @Test fun O05_acceptedHandsFreeRecordingIsKeptByOffAndNoAlarmIsScheduled() {
        settings(WakeLocation.WATCH, standby = true)
        startNoisePcm()
        openArmed(); hide()
        assertTrue("listening hidden", listening())
        ready()
        results("루미"); idle(1_000)
        assertTrue("the accepted hands-free recording is really under way", app.voice.capturing)
        assertNull("positive control: nothing is scheduled while the recording runs", alarms().peekNextScheduledAlarm())
        settings(WakeLocation.WATCH, standby = false)
        assertTrue("OFF never cancels an accepted recording", app.voice.capturing)
        assertNull("OFF schedules no retry", alarms().peekNextScheduledAlarm())
        assertEquals(microphoneType, serviceType())
        assertTrue(HoldReason.CAPTURE in held())
        assertFalse(listening())
        idle(1_000)
        assertNull("no retry appears later either", alarms().peekNextScheduledAlarm())
        assertTrue(app.voice.capturing)
    }

    @Test fun O06_phoneSwitchAloneIsNotTheWatchSwitch() {
        settings(WakeLocation.WATCH, standby = true)
        openArmed(); hide()
        untilGap()
        val pending = alarms().peekNextScheduledAlarm()
        assertNotNull(pending)
        revision += 1
        app.applySettings(watchScreenOffOn(WatchSettings(WakeLocation.WATCH, "루미", watchBackgroundWakeEnabled = true, phoneBackgroundWakeEnabled = false, revision = revision).toJson()))
        idle(50)
        assertNotNull("the Watch's own gap alarm is not touched by the Phone switch", alarms().peekNextScheduledAlarm())
    }

    private companion object {
        const val MAX_WAIT_MS = 70_000L
    }
}
