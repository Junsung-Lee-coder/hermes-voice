package com.rumi.hermesvoice.watch

import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import io.mockk.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/**
 * R36: what the real WatchNativeLiveRoute treats as a lost route. Every source below reaches the same `lost()` (the runtime's one
 * ROUTE_LOST, step 17), so a step 17 alone cannot tell them apart. A route whose communication device has not converged yet is
 * refused at open (ROUTE_NOT_VALID, step 16), not lost later. Platform leaves are doubles: the communication-device API and device
 * callback of the real AudioManager are stubbed, Robolectric's audio shadow records the focus request.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class WatchLiveRouteLossR36RoboTest {
    private lateinit var app: WatchApp
    private lateinit var audio: AudioManager
    private lateinit var speaker: AudioDeviceInfo
    private var current: AudioDeviceInfo? = null
    private var mode = AudioManager.MODE_NORMAL
    private val outputs = ArrayList<AudioDeviceInfo>()
    private var comm: AudioManager.OnCommunicationDeviceChangedListener? = null
    private var devices: AudioDeviceCallback? = null
    private var cleared = 0
    private var losses = 0
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
    private fun device(id: Int, type: Int): AudioDeviceInfo {
        val d = mockk<AudioDeviceInfo>(relaxed = true); every { d.id } returns id; every { d.type } returns type
        every { d.address } returns ""; every { d.isSink } returns true; every { d.isSource } returns false; return d
    }
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as WatchApp
        audio = app.getSystemService(AudioManager::class.java); mockkObject(audio)
        speaker = device(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER); outputs += speaker; current = speaker
        every { audio.getDevices(any()) } answers { if (firstArg<Int>() == AudioManager.GET_DEVICES_OUTPUTS) outputs.toTypedArray() else emptyArray() }
        every { audio.availableCommunicationDevices } answers { outputs.toList() }
        every { audio.setCommunicationDevice(any()) } returns true
        every { audio.communicationDevice } answers { current }
        every { audio.clearCommunicationDevice() } answers { cleared++; Unit }
        every { audio.addOnCommunicationDeviceChangedListener(any(), any()) } answers { comm = secondArg() }
        every { audio.removeOnCommunicationDeviceChangedListener(any()) } answers { comm = null }
        every { audio.registerAudioDeviceCallback(any(), any()) } answers { devices = firstArg() }
        every { audio.unregisterAudioDeviceCallback(any()) } answers { devices = null }
        every { audio.mode } answers { mode }; every { audio.mode = any() } answers { mode = firstArg(); Unit }
    }
    @After fun cleanup() { unmockkAll() }
    private fun route() = WatchNativeLiveRoute(app) { losses++ }
    private fun opened(): WatchNativeLiveRoute { val r = route(); assertTrue("admitted at open", r.open()); assertNull(r.refusal); return r }
    private fun focus(change: Int) { shadowOf(audio).lastAudioFocusRequest.listener.onAudioFocusChange(change); idle() }

    @Test fun RT01_anImmediatelyConvergedRouteSurvivesItsOwnQueuedCallbacks() {
        val r = opened()
        devices!!.onAudioDevicesAdded(outputs.toTypedArray()) // the registration-time delivery of every connected device
        comm!!.onCommunicationDeviceChanged(speaker); idle()  // routing applied for the requested device
        assertEquals(0, losses); assertTrue(r.valid()); assertEquals(AudioManager.MODE_IN_COMMUNICATION, mode)
    }

    @Test fun RT02_aRouteNotConvergedAtOpenIsRefusedAsNotValidNotLostAndALateConvergenceDoesNothingAfterClose() {
        current = null
        val r = route(); assertFalse(r.open()); assertEquals("step 16, not step 17", WatchLiveSetupStep.ROUTE_NOT_VALID, r.refusal)
        val late = comm!!; val lateDevices = devices!!
        r.close(); current = speaker; late.onCommunicationDeviceChanged(speaker); lateDevices.onAudioDevicesAdded(outputs.toTypedArray()); idle()
        assertEquals(0, losses); assertEquals(AudioManager.MODE_NORMAL, mode); assertEquals(1, cleared)
    }

    @Test fun RT03_everyKindOfFocusLossIsALostRoute() {
        for (change in listOf(AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)) {
            val before = losses; val r = opened(); focus(change)
            assertEquals("focus change $change", before + 1, losses); r.close()
        }
    }

    @Test fun RT04_theCommunicationDeviceMovingAwayIsALostRoute() {
        opened(); current = device(9, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE); comm!!.onCommunicationDeviceChanged(current); idle()
        assertEquals(1, losses)
    }

    @Test fun RT05_aPersonalOutputAppearingIsALostRouteForPrivacy() {
        opened(); val earbuds = device(7, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP); outputs += earbuds
        devices!!.onAudioDevicesAdded(arrayOf(earbuds)); idle()
        assertEquals(1, losses)
    }

    @Test fun RT06_becomingNoisyIsALostRoute() {
        opened(); app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY)); idle()
        assertEquals(1, losses)
    }

    @Test fun RT07_aDeviceCallbackThatLeavesTheRouteValidIsNoLoss() {
        opened(); devices!!.onAudioDevicesRemoved(arrayOf(device(11, AudioDeviceInfo.TYPE_USB_DEVICE))); comm!!.onCommunicationDeviceChanged(speaker); idle()
        assertEquals(0, losses)
    }

    @Test fun RT08_afterCloseNoLateCallbackIsALossAndTheRouteIsReleased() {
        val r = opened(); val listener = shadowOf(audio).lastAudioFocusRequest.listener; val late = comm!!; val lateDevices = devices!!
        r.close()
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS); current = null; late.onCommunicationDeviceChanged(null)
        lateDevices.onAudioDevicesRemoved(arrayOf(speaker)); app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY)); idle()
        assertEquals(0, losses); assertEquals(AudioManager.MODE_NORMAL, mode); assertEquals(1, cleared); assertFalse(r.valid())
    }
}
