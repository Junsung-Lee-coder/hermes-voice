package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.wake.WakeArmInputs
import com.rumi.hermesvoice.core.wake.WakeBlock
import com.rumi.hermesvoice.core.wake.WakeContract
import com.rumi.hermesvoice.core.wake.WakeDeviceController
import com.rumi.hermesvoice.core.wake.WakeDevicePort
import com.rumi.hermesvoice.core.wake.WakeRecognizerPort
import com.rumi.hermesvoice.core.wake.WakeTimerPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-device wake flow both adapters (Phone MainActivity, Watch WatchActivity) delegate to:
 * which device listens for each wake location, convergence when the mode changes, the recorder
 * handoff with the trailing-silence snapshot, and same-breath requests.
 */
class WakeDeviceControllerTest {
    private class Device(val origin: VoiceOrigin, settings: WatchSettings) : WakeRecognizerPort, WakeTimerPort, WakeDevicePort {
        val calls = mutableListOf<String>()
        var available = true
        var now = 0L
        var busy = false
        val controller = WakeDeviceController(origin, this, this, this, { now }, settings)

        override fun available() = available
        override fun start(generation: Long): Boolean { calls += "listen:$generation"; return true }
        override fun release() { calls += "release" }
        override fun schedule(delayMs: Long) {}
        override fun cancel() {}
        override fun windowChanged(open: Boolean) { calls += "window:$open" }
        override fun scheduleHandoff(delayMs: Long) { calls += "handoff_in:$delayMs" }
        override fun cancelHandoff() {}
        override fun startRequestCapture(silenceMs: Long): Boolean { calls += "capture:$silenceMs"; return true }
        override fun cancelRequestCapture(reason: String) { calls += "cancel_capture:$reason" }
        override fun sendRecognized(request: String) { calls += "send:$request" }
        override fun closed(reason: String) { calls += "closed:$reason" }
        override fun armInputs() = WakeArmInputs(enabled = true, resumed = true, interactive = true, ambient = false, permission = true,
            microphoneMuted = false, talkIdle = !busy, phoneReachable = true, nowMs = now, cooldownUntilMs = 0, generation = 0,
            lastArmedGeneration = null)

        fun listened() = calls.count { it.startsWith("listen:") }
    }

    private fun settings(mode: WakeLocation, seconds: Double = 2.0, revision: Long = 1) =
        WatchSettings(mode, "루미", vadSilenceSeconds = seconds, revision = revision)

    @Test
    fun `each wake location listens only on the devices it names`() {
        for (mode in WakeLocation.values()) {
            for (origin in VoiceOrigin.values()) {
                val device = Device(origin, settings(mode))
                device.controller.onResume()
                assertEquals("$mode on $origin", if (mode.listensOn(origin)) 1 else 0, device.listened())
                device.controller.qaSecondUtterance()
                device.controller.onResults(device.controller.generation, listOf("루미 불 꺼"), final = true)
                val responded = device.calls.any { it.startsWith("handoff_in") || it.startsWith("send:") }
                assertEquals("$mode on $origin responds", mode.listensOn(origin), responded)
            }
        }
    }

    @Test
    fun `excluding the device mid-window closes it and later results are ignored`() {
        val watch = Device(VoiceOrigin.WATCH, settings(WakeLocation.BOTH))
        watch.controller.onResume()
        val gen = watch.controller.generation
        watch.controller.onResults(gen, listOf("루미 불"), final = false)
        watch.controller.onSettings(settings(WakeLocation.PHONE, revision = 2))
        assertTrue(watch.calls.containsAll(listOf("release", "window:false", "cancel_capture:opt_out")))
        watch.calls.clear()
        watch.controller.onResults(gen, listOf("루미 불 꺼"), final = true)
        watch.controller.onTimer()
        assertEquals("nothing sent, handed off or reopened", emptyList<String>(), watch.calls)
        watch.controller.onScreenOn()
        watch.controller.onIdle()
        assertEquals(0, watch.listened())
    }

    @Test
    fun `excluding the device leaves an accepted recorder handoff and hands-free capture to finish, and accepts nothing new`() {
        val phone = Device(VoiceOrigin.PHONE, settings(WakeLocation.PHONE))
        phone.controller.onResume()
        phone.controller.onResults(phone.controller.generation, listOf("루미"), final = true)
        assertTrue(phone.calls.contains("handoff_in:${WakeContract.MIC_HANDOFF_MS}"))
        phone.controller.onSettings(settings(WakeLocation.OFF, revision = 2))
        assertTrue("the accepted handoff still starts its recording", phone.controller.onHandoffDue(captureIdle = true))
        assertTrue(phone.calls.toString(), phone.calls.any { it.startsWith("capture:") })
        assertFalse(phone.calls.toString(), phone.calls.any { it.startsWith("cancel_capture:") })
        val before = phone.listened()
        phone.controller.onResults(phone.controller.generation, listOf("루미 불 꺼"), final = true)
        assertEquals("nothing new is accepted while it records", before, phone.listened())

        val watch = Device(VoiceOrigin.WATCH, settings(WakeLocation.WATCH))
        watch.controller.onResume()
        watch.controller.qaSecondUtterance()
        assertTrue(watch.controller.onHandoffDue(captureIdle = true))
        watch.controller.onSettings(settings(WakeLocation.OFF, revision = 2))
        assertFalse(watch.calls.toString(), watch.calls.any { it.startsWith("cancel_capture:") })
        watch.controller.onRequestCaptureEnded(sent = true)
        watch.controller.onScreenOn()
        watch.controller.onIdle()
        assertEquals("and it does not listen again once it is over", 1, watch.listened())
    }

    @Test
    fun `a capture keeps the silence it started with and the next one uses the new setting`() {
        for (origin in VoiceOrigin.values()) {
            val device = Device(origin, settings(WakeLocation.BOTH, seconds = 2.0))
            device.controller.onResume()
            device.controller.qaSecondUtterance()
            assertTrue(device.controller.onHandoffDue(captureIdle = true))
            device.controller.onSettings(settings(WakeLocation.BOTH, seconds = 5.5, revision = 2))
            assertFalse("a silence change alone does not stop the capture", device.calls.any { it.startsWith("cancel_capture") })
            device.controller.onPause()
            device.controller.onResume()
            device.controller.qaSecondUtterance()
            assertTrue(device.controller.onHandoffDue(captureIdle = true))
            assertEquals("$origin", listOf("capture:2000", "capture:5500"), device.calls.filter { it.startsWith("capture:") })
        }
    }

    @Test
    fun `a same-breath final request is sent as text once, partials never are`() {
        val phone = Device(VoiceOrigin.PHONE, settings(WakeLocation.PHONE))
        phone.controller.onResume()
        val gen = phone.controller.generation
        phone.controller.onResults(gen, listOf("루미 내일 일정"), final = false)
        assertFalse(phone.calls.any { it.startsWith("send:") })
        phone.controller.onResults(gen, listOf("루미 내일 일정 알려줘"), final = true)
        phone.controller.onResults(gen, listOf("루미 내일 일정 알려줘"), final = true)
        assertEquals(listOf("send:내일 일정 알려줘"), phone.calls.filter { it.startsWith("send:") })
        assertTrue("recognizer released before sending", phone.calls.indexOf("release") < phone.calls.indexOf("send:내일 일정 알려줘"))
    }

    @Test
    fun `pausing or a new generation drops the handoff and stops the capture without sending`() {
        val device = Device(VoiceOrigin.PHONE, settings(WakeLocation.PHONE))
        device.controller.onResume()
        device.controller.qaSecondUtterance()
        device.controller.onPause()
        assertTrue(device.calls.contains("cancel_capture:pause"))
        device.controller.onResume()
        assertFalse("stale generation", device.controller.onHandoffDue(captureIdle = true))
        device.controller.qaSecondUtterance()
        device.controller.onScreenOff()
        assertFalse(device.controller.onHandoffDue(captureIdle = true))
        assertTrue(device.calls.contains("cancel_capture:screen_off"))
        assertFalse(device.calls.any { it.startsWith("capture:") })
    }

    @Test
    fun `the watch waits for current settings after resuming before it listens`() {
        val watch = Device(VoiceOrigin.WATCH, settings(WakeLocation.WATCH))
        assertEquals(WakeBlock.DISABLED, run { watch.controller.onResume(settingsPending = true); watch.controller.requestArm("check") })
        assertEquals(0, watch.listened())
        // The synced item says the Phone turned the Watch off meanwhile: it never listens.
        watch.controller.onSettings(settings(WakeLocation.PHONE, revision = 2))
        watch.controller.onSettingsCurrent()
        assertEquals(0, watch.listened())
        watch.controller.onSettings(settings(WakeLocation.WATCH, revision = 3))
        assertEquals(1, watch.listened())
    }

    @Test
    fun `the debug handoff releases an open recognizer before the recorder starts`() {
        val phone = Device(VoiceOrigin.PHONE, settings(WakeLocation.PHONE))
        phone.controller.onResume()
        assertEquals(1, phone.listened())
        phone.controller.qaSecondUtterance()
        assertTrue(phone.controller.onHandoffDue(captureIdle = true))
        val release = phone.calls.indexOf("release")
        assertTrue("released at $release before ${phone.calls}", release in 0 until phone.calls.indexOf("capture:2000"))
    }

    @Test
    fun `no window opens while the recorder handoff is pending`() {
        val watch = Device(VoiceOrigin.WATCH, settings(WakeLocation.WATCH))
        watch.controller.onResume(settingsPending = true)
        watch.controller.qaSecondUtterance()
        watch.controller.onSettingsCurrent()
        assertEquals(WakeBlock.BUSY, watch.controller.requestArm("check"))
        assertEquals("the recognizer never overlaps the pending recorder", 0, watch.listened())
        assertTrue(watch.controller.onHandoffDue(captureIdle = true))
    }

    @Test
    fun `busy or unavailable devices do not listen, and busy closes an open window`() {
        val phone = Device(VoiceOrigin.PHONE, settings(WakeLocation.PHONE))
        phone.busy = true
        phone.controller.onResume()
        assertEquals(0, phone.listened())
        phone.busy = false
        phone.controller.onIdle()
        assertEquals(1, phone.listened())
        phone.controller.onBusy()
        assertTrue(phone.calls.containsAll(listOf("release", "closed:busy")))

        val watch = Device(VoiceOrigin.WATCH, settings(WakeLocation.WATCH))
        watch.available = false
        watch.controller.onResume()
        assertEquals(listOf("closed:unavailable"), watch.calls)
    }
}
