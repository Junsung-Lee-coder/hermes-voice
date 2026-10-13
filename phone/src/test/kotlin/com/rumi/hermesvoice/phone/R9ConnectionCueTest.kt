package com.rumi.hermesvoice.phone

import com.rumi.hermesvoice.core.FakeDevices
import com.rumi.hermesvoice.core.Gear
import com.rumi.hermesvoice.core.headset.HeadsetPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Reflection adapter keeps this exact test compilable on R8, where the connection lifecycle is absent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class R9ConnectionCueTest {
    private class Rig(initialConnected: Boolean = false) {
        var enabled = true
        var busy = false
        var plays = 0
        var closes = 0
        val states = mutableListOf<Boolean>()
        val devices = FakeDevices(if (initialConnected) listOf(Gear.speaker, Gear.wiredHeadset) else listOf(Gear.speaker), emptyList())
        val policy = HeadsetPolicy({ enabled }, devices)
        val cues = RecordingCues(policy, CueOutput { _, _, target, _ ->
            assertEquals(Gear.wiredHeadset.id, target.id)
            plays++
            AutoCloseable { closes++ }
        })
        val controller: Any
        init {
            val type = runCatching { Class.forName("com.rumi.hermesvoice.phone.ConnectionCueController") }.getOrNull()
            assertNotNull("one fresh-output connection cue controller must exist", type)
            controller = type!!.constructors.single().newInstance(policy, cues, { busy }, { on: Boolean -> states.add(on); Unit })
        }
        fun refresh() { controller.javaClass.getMethod("refresh").invoke(controller) }
        fun cancel() { controller.javaClass.getMethod("cancel").invoke(controller) }
        fun connect() { devices.set(listOf(Gear.speaker, Gear.wiredHeadset), emptyList()) }
        fun disconnect() { devices.set(listOf(Gear.speaker), emptyList()) }
    }
    @Test fun freshOutputPlaysOnceRepeatedReportsDoNotReplay() { val r = Rig(); r.connect(); r.connect(); r.refresh(); assertEquals(1, r.plays) }
    @Test fun startupConnectedAndSettingHydrationNeverInventConnection() { val r = Rig(true); r.refresh(); assertEquals(0, r.plays) }
    @Test fun reconnectCreatesOneNewEpochAndCancelsOldCue() { val r = Rig(); r.connect(); r.disconnect(); r.connect(); assertEquals(2, r.plays); assertTrue(r.closes >= 1) }
    @Test fun disableAndReenableOnSameOutputNeverReplay() { val r = Rig(); r.connect(); r.enabled = false; r.refresh(); r.enabled = true; r.refresh(); r.connect(); assertEquals(1, r.plays); assertTrue(r.closes >= 1) }
    @Test fun connectionDuringCaptureIsConsumedWithoutDeferredReplay() { val r = Rig(); r.busy = true; r.connect(); r.busy = false; r.refresh(); assertEquals(0, r.plays) }
    @Test fun inputOnlyAndOffCannotPlay() { val r = Rig(); r.devices.set(listOf(Gear.speaker), listOf(Gear.wiredHeadsetMic)); assertEquals(0, r.plays); r.enabled = false; r.connect(); assertEquals(0, r.plays) }
    @Test fun explicitStopCancelsAndNoSameRouteReplay() { val r = Rig(); r.connect(); r.cancel(); r.refresh(); r.connect(); assertEquals(1, r.plays); assertTrue(r.closes >= 1); assertEquals(false, r.states.last()) }
}
