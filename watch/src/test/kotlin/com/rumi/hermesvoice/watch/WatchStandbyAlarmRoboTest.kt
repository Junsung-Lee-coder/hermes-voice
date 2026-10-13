package com.rumi.hermesvoice.watch

import android.Manifest
import android.app.AlarmManager
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
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSpeechRecognizer

/**
 * SCRATCH HARNESS (never packaged), added by the standby change. The screen-off re-arm of the hidden Watch session through
 * the REAL call sites: WatchVoiceRuntime schedules the gap before the next window, the platform alarm (Robolectric's
 * ShadowAlarmManager) is delivered to the REAL StandbyAlarmReceiver, and turning the standby OFF leaves no alarm behind.
 * The handler is deliberately NOT advanced when the alarm is delivered: that models a CPU asleep after the screen went off.
 * This is Robolectric bookkeeping (alarm scheduling, receiver delivery, wake-lock state), not proof of real Doze or CPU behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchStandbyAlarmRoboTest {
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

    /** Opens the app, lets the default session arm its microphone, then hides it with the screen off (a hidden armed session). */
    private fun hiddenArmedWithScreenOff() {
        scenario = ActivityScenario.launch(WatchActivity::class.java)
        idle(500)
        deliverService()
        assertTrue("armed while visible", app.voice.presence.armed)
        scenario!!.moveToState(Lifecycle.State.CREATED)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        idle(200)
        assertTrue("listening hidden", listening())
    }

    /** Lets the clock run (a 5 s first window, then 30 s windows) until the first window closes and its gap alarm exists. */
    private fun untilGap() {
        var spent = 0L
        while (alarms().peekNextScheduledAlarm() == null && spent < MAX_WAIT_MS) {
            idle(50)
            spent += 50
        }
        assertNotNull("a window closed and scheduled its gap within ${MAX_WAIT_MS} ms", alarms().peekNextScheduledAlarm())
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
        settings(WakeLocation.WATCH, standby = true)
    }

    @After
    fun tearDown() {
        scenario?.close()
        WatchVoiceService.running?.finish()
        unmockkAll()
    }

    @Test fun S01_theGapAfterAClosedWindowIsBackedByOneNonExactAllowWhileIdleAlarm() {
        hiddenArmedWithScreenOff()
        assertNull("nothing scheduled while a window is open", alarms().peekNextScheduledAlarm())
        untilGap() // the window gives up without the phrase; the re-arm gap starts
        val alarm = alarms().peekNextScheduledAlarm()
        assertNotNull("the gap is scheduled with the platform", alarm)
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm!!.type)
        assertEquals("one alarm, never a growing set", 1, alarms().scheduledAlarms.size)
    }

    @Test fun S02_whenTheCpuSleptTheAlarmAloneOpensTheNextWindowAndTheLateHandlerDoesNotDouble() {
        hiddenArmedWithScreenOff()
        untilGap()
        assertTrue("the window closed", !listening())
        val operation = alarms().peekNextScheduledAlarm()!!.operation
        // The CPU slept: the handler did not run. The platform delivers the alarm.
        StandbyAlarmReceiver().onReceive(app, shadowOf(operation).savedIntent)
        idle(0)
        assertTrue("the alarm reopened listening", listening())
        val generation = app.voice.wake.wake.generation
        idle(1_000) // the CPU is back: the handler's own copy of the same re-arm must find nothing to do
        assertEquals("no second window for the same gap", generation, app.voice.wake.wake.generation)
        assertNull("the used alarm is not left behind", alarms().peekNextScheduledAlarm())
    }

    @Test fun S03_standbyOffLeavesNoAlarmAndNoWindowAndAStaleAlarmOpensNothing() {
        hiddenArmedWithScreenOff()
        untilGap()
        val operation = alarms().peekNextScheduledAlarm()!!.operation
        settings(WakeLocation.WATCH, standby = false)
        assertNull("OFF cancels the pending alarm", alarms().peekNextScheduledAlarm())
        assertTrue(!listening())
        // A copy of the alarm that was already in flight when OFF arrived (a hidden Wear receives OFF the same way).
        StandbyAlarmReceiver().onReceive(app, shadowOf(operation).savedIntent)
        idle(1_000)
        assertTrue("a stale alarm never re-enables listening", !listening())
        assertNull(alarms().peekNextScheduledAlarm())
        assertEquals("the persisted OFF is what the app reports", false, app.settings.value.watchBackgroundWakeEnabled)
    }

    @Test fun S04_theOpenWindowHoldsTheCpuAndTheGapBetweenWindowsHoldsNothing() {
        hiddenArmedWithScreenOff()
        val duringWindow = org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock()
        assertNotNull("the open window took a wake lock (so the gap assertion below can fail)", duringWindow)
        assertTrue("the open window's wake lock is held", duringWindow!!.isHeld)
        untilGap()
        assertTrue("the window's wake lock was released before the gap", !duringWindow.isHeld)
        val locks = org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock()
        assertTrue("no wake lock is held in the gap", locks == null || !locks.isHeld)
    }

    private companion object {
        const val MAX_WAIT_MS = 70_000L
    }
}
