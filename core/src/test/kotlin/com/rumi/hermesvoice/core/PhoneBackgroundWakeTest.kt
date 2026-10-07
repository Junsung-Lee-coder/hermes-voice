package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.background.BackgroundNotice
import com.rumi.hermesvoice.core.background.BackgroundPort
import com.rumi.hermesvoice.core.background.HoldReason
import com.rumi.hermesvoice.core.background.NotificationCapability
import com.rumi.hermesvoice.core.background.PhoneBackgroundHost
import com.rumi.hermesvoice.core.background.PhoneBackgroundWake
import com.rumi.hermesvoice.core.background.PhoneWakeStatus
import com.rumi.hermesvoice.core.background.WakeHolds
import com.rumi.hermesvoice.core.background.WakeLockPort
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.wake.ContinuousWakePolicy
import com.rumi.hermesvoice.core.wake.WakeArmInputs
import com.rumi.hermesvoice.core.wake.WakeContract
import com.rumi.hermesvoice.core.wake.WakeDeviceController
import com.rumi.hermesvoice.core.wake.WakeDevicePort
import com.rumi.hermesvoice.core.wake.WakeLoop
import com.rumi.hermesvoice.core.wake.WakeRecognizerPort
import com.rumi.hermesvoice.core.wake.WakeTimerPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Phone's opt-in background listening, through the real [WakeDeviceController],
 * [com.rumi.hermesvoice.core.wake.WakePresence], [com.rumi.hermesvoice.core.background.BackgroundSession]
 * and [WakeHolds], with the platform (service, recognizer, recorder, timers, wake locks) faked at
 * the ports the Android runtime implements. The foreground wake flow is a separate controller and
 * is not involved: these prove who owns the microphone when.
 */
class PhoneBackgroundWakeTest {
    private class Phone {
        val calls = mutableListOf<String>()
        var now = 0L
        var settings = WatchSettings(WakeLocation.PHONE, "루미", revision = 1, phoneBackgroundWakeEnabled = true)
        var recording = false

        /** The Phone's live screen state (the platform's answer; the earlier fixture reported it off always). */
        var screenOn = true
        var permission = true
        var onDevice = true
        var notifications = NotificationCapability.SHOWN
        var busy = false
        var accepted = 0
        var recognizerUp = true
        val statuses = mutableListOf<PhoneWakeStatus>()
        val posted = ArrayDeque<() -> Unit>()
        val held = HashMap<HoldReason, Long>()

        val service = object : BackgroundPort {
            override fun startService(microphone: Boolean): Boolean { calls += "service_start:mic=$microphone"; return true }
            override fun retypeService(microphone: Boolean): Boolean { calls += "service_retype:mic=$microphone"; return true }
            override fun stopService() { calls += "service_stop" }
        }

        val host = object : PhoneBackgroundHost {
            override fun settings() = settings
            override fun microphonePermission() = permission
            override fun onDeviceRecognizer() = onDevice
            override fun notifications() = notifications
            override fun scheduleRearm(delayMs: Long) { calls += "rearm_in:$delayMs" }
            override fun cancelRearm() { calls += "rearm_cancel" }
            override fun cancelCapture(reason: String) { calls += "cancel_capture:$reason" }
            override fun post(block: () -> Unit) { posted += block }
            override fun statusChanged(status: PhoneWakeStatus) { statuses += status }
            override fun recordingActive() = recording
        }

        val holds = WakeHolds(object : WakeLockPort {
            override fun acquire(reason: HoldReason, timeoutMs: Long) { held[reason] = timeoutMs }
            override fun release(reason: HoldReason) { held.remove(reason) }
        }, { now })

        val store = InMemoryKeyValueStore()
        val background = PhoneBackgroundWake(store, "phone_background_wake", service, host, holds) { now }

        private val inner = object : WakeDevicePort {
            override fun windowChanged(open: Boolean) { calls += "window:$open" }
            override fun scheduleHandoff(delayMs: Long) { calls += "handoff_in" }
            override fun cancelHandoff() {}
            override fun startRequestCapture(silenceMs: Long): Boolean { calls += "capture"; return true }
            override fun cancelRequestCapture(reason: String) { calls += "capture_cancelled:$reason" }
            override fun sendRecognized(request: String) { calls += "send:$request" }
            override fun closed(reason: String) { calls += "closed:$reason" }
            override fun wakeAccepted() { accepted += 1 }
            override fun armInputs() = WakeArmInputs(enabled = settings.mayListen(VoiceOrigin.PHONE), resumed = false,
                interactive = screenOn, ambient = false, permission = permission, microphoneMuted = false, talkIdle = !busy,
                phoneReachable = true, nowMs = now, cooldownUntilMs = 0, generation = 0, lastArmedGeneration = null)
        }

        private val recognizer = object : WakeRecognizerPort, WakeTimerPort {
            override fun available() = recognizerUp
            override fun start(generation: Long): Boolean { calls += "listen:$generation"; return true }
            override fun release() { calls += "recognizer_release" }
            override fun schedule(delayMs: Long) {}
            override fun cancel() {}
        }

        val wake = WakeDeviceController(VoiceOrigin.PHONE, recognizer, recognizer, background.devicePort(inner), { now }, settings)
            .also { background.attach(it) }

        fun drain() { while (posted.isNotEmpty()) posted.removeFirst()() }
        fun listened() = calls.count { it.startsWith("listen:") }
        fun status() = background.status
    }

    private fun startedAndHidden(): Phone = Phone().apply {
        background.onAppShown()
        background.start()
        background.onAppHidden()
        drain()
    }

    @Test
    fun `off by default, started only from the visible app, and never listening while the app is on screen`() {
        val phone = Phone()
        assertEquals(BackgroundNotice.OFF, phone.status().session.notice)
        assertEquals(BackgroundNotice.NEEDS_VISIBLE, phone.background.start().notice)
        assertFalse(phone.calls.any { it.startsWith("service_start") })
        phone.background.onAppShown()
        val started = phone.background.start()
        assertTrue(started.running && started.microphone)
        assertEquals(listOf("service_start:mic=true"), phone.calls.filter { it.startsWith("service_") })
        assertEquals("the app's own flow owns the microphone on screen", 0, phone.listened())
        assertFalse(phone.background.owning)
    }

    @Test
    fun `hidden, the armed session listens in long windows with only bounded holds, screen off included`() {
        val phone = startedAndHidden()
        assertTrue(phone.background.owning)
        assertEquals(1, phone.listened())
        assertEquals(WakeContract.BACKGROUND_WINDOW_MS, phone.wake.windowMs)
        assertTrue(phone.status().listeningNow)
        val listen = phone.held.getValue(HoldReason.LISTEN)
        assertTrue("bounded by the window", listen in 1..(WakeContract.BACKGROUND_WINDOW_MS + 10_000))
        assertTrue("no hold without a reason", phone.held.keys.all { it in setOf(HoldReason.LISTEN, HoldReason.HANDOFF) })
    }

    @Test
    fun `showing the app takes the microphone back at once, an unfinished recording is dropped unsent`() {
        val phone = startedAndHidden()
        phone.wake.onResults(phone.wake.generation, listOf("루미"), final = true)
        assertTrue(phone.wake.onHandoffDue(captureIdle = true))
        assertTrue(phone.calls.contains("capture"))
        phone.calls.clear()
        phone.background.onAppShown()
        phone.drain()
        assertFalse(phone.background.owning)
        assertTrue(phone.calls.contains("capture_cancelled:pause"))
        assertTrue(phone.calls.contains("cancel_capture:pause"))
        assertTrue(phone.held.isEmpty())
        assertFalse("nothing reopens behind the app", phone.calls.any { it.startsWith("listen:") })
        assertTrue("the session itself goes on", phone.status().session.running)
    }

    @Test
    fun `Stop ends everything now, unsent, and a late recognizer result changes nothing`() {
        val phone = startedAndHidden()
        val gen = phone.wake.generation
        phone.wake.onResults(gen, listOf("루미"), final = true)
        phone.calls.clear()
        phone.background.stop()
        phone.drain()
        assertTrue(phone.calls.contains("service_stop"))
        assertTrue(phone.calls.contains("rearm_cancel"))
        assertFalse(phone.wake.onHandoffDue(captureIdle = true))
        phone.wake.onResults(gen, listOf("루미 불 꺼"), final = true)
        assertFalse(phone.calls.any { it.startsWith("send:") || it == "capture" })
        assertTrue(phone.held.isEmpty())
        assertEquals(BackgroundNotice.OFF, phone.status().session.notice)
        phone.background.onAppHidden()
        phone.drain()
        assertEquals("stopped for good: no window after Stop (the log was cleared at Stop)", 0, phone.listened())
    }

    @Test
    fun `each blocker keeps the microphone off and says why`() {
        val cases = listOf<Pair<(Phone) -> Unit, BackgroundNotice>>(
            { p: Phone -> p.settings = WatchSettings(WakeLocation.WATCH, "루미", revision = 1) } to BackgroundNotice.RUNNING,
            // The foreground location alone never lets a hidden Phone listen: only its own standby switch does.
            { p: Phone -> p.settings = WatchSettings(WakeLocation.PHONE, "루미", revision = 1) } to BackgroundNotice.RUNNING,
            { p: Phone -> p.permission = false } to BackgroundNotice.NEEDS_PERMISSION,
            { p: Phone -> p.onDevice = false } to BackgroundNotice.NO_RECOGNIZER,
            { p: Phone -> p.notifications = NotificationCapability.NOT_ALLOWED } to BackgroundNotice.NEEDS_NOTIFICATIONS,
        )
        for ((blockIt, notice) in cases) {
            val phone = Phone()
            blockIt(phone)
            phone.background.onAppShown()
            val status = phone.background.start()
            assertEquals(notice, status.notice)
            assertFalse(status.microphone)
            phone.background.onAppHidden()
            assertEquals("$notice: never listens", 0, phone.listened())
        }
    }

    @Test
    fun `a blocker arriving while hidden disarms at once, and nothing re-arms until the app is shown`() {
        val phone = startedAndHidden()
        phone.settings = WatchSettings(WakeLocation.PHONE, "루미", revision = 2, phoneBackgroundWakeEnabled = false)
        phone.background.onEligibilityChanged()
        phone.drain()
        assertFalse(phone.background.owning)
        assertFalse(phone.status().session.microphone)
        assertTrue(phone.calls.contains("service_retype:mic=false"))
        phone.settings = WatchSettings(WakeLocation.PHONE, "루미", revision = 3, phoneBackgroundWakeEnabled = true)
        phone.background.onEligibilityChanged()
        phone.background.onRearmDue()
        assertEquals("still not armed from the background", 1, phone.listened())
        phone.background.onAppShown()
        phone.background.onAppHidden()
        phone.drain()
        assertTrue(phone.background.owning)
        assertEquals(2, phone.listened())
    }

    @Test
    fun `failures back off up to a minute, a quiet window rearms quickly, and no recognizer stops the loop`() {
        val phone = startedAndHidden()
        phone.wake.onTimer() // no-op: deadline not reached
        phone.wake.onError(phone.wake.generation, 2) // network-type error: a failure
        assertTrue(phone.calls.contains("rearm_in:${ContinuousWakePolicy.FIRST_BACKOFF_MS}"))
        repeat(10) {
            phone.background.onRearmDue()
            phone.wake.onError(phone.wake.generation, 2)
        }
        val delays = phone.calls.filter { it.startsWith("rearm_in:") }.map { it.substringAfter(":").toLong() }
        assertEquals(ContinuousWakePolicy.MAX_BACKOFF_MS, delays.last())
        assertTrue("never a tight loop", delays.all { it >= ContinuousWakePolicy.FIRST_BACKOFF_MS })
        assertEquals(WakeLoop.RETRYING, phone.status().loop)

        phone.background.onRearmDue()
        phone.wake.onError(phone.wake.generation, 7) // nothing matched: the next window soon
        assertEquals(ContinuousWakePolicy.REARM_MS, phone.calls.last { it.startsWith("rearm_in:") }.substringAfter(":").toLong())

        phone.recognizerUp = false // e.g. the on-device model lacks the language: no cloud fallback
        phone.background.onRearmDue()
        phone.drain()
        assertEquals(WakeLoop.OFF, phone.status().loop)
        assertEquals(BackgroundNotice.NO_RECOGNIZER, phone.status().session.notice)
        assertTrue(phone.held.isEmpty())
    }

    @Test
    fun `a background match pulses once and records, and a same-breath request is sent as text`() {
        val phone = startedAndHidden()
        phone.wake.onResults(phone.wake.generation, listOf("루미"), final = false)
        assertEquals(0, phone.accepted)
        phone.wake.onResults(phone.wake.generation, listOf("루미"), final = true)
        assertEquals(1, phone.accepted)
        assertTrue(phone.wake.onHandoffDue(captureIdle = true))
        assertTrue(phone.calls.contains("capture"))
        phone.wake.onRequestCaptureEnded(sent = true)
        phone.background.onIdle()
        phone.drain()
        assertTrue("listens again after the request", phone.listened() >= 2)

        val other = startedAndHidden()
        other.wake.onResults(other.wake.generation, listOf("루미 불 꺼"), final = true)
        assertTrue(other.calls.contains("send:불 꺼"))
        assertEquals(1, other.accepted)
    }

    @Test
    fun `busy closes the window and idle rearms, and the system ending the service pauses it until started again`() {
        val phone = startedAndHidden()
        phone.busy = true
        phone.background.onBusy()
        assertFalse(phone.wake.listening)
        phone.busy = false
        phone.background.onIdle()
        assertTrue(phone.wake.listening)
        phone.background.onServiceGone(phone.background.session.generation)
        phone.drain()
        assertFalse(phone.background.owning)
        assertEquals(BackgroundNotice.PAUSED, phone.status().session.notice)
        assertTrue(phone.held.isEmpty())
        phone.background.onAppShown()
        assertEquals("a microphone session resumes only by the user's start", BackgroundNotice.PAUSED, phone.status().session.notice)
    }

    // ── the Phone's own background standby switch ────────────────────────────────────────────

    @Test
    fun `phone standby on listens hidden even though the foreground location is off`() {
        val phone = Phone()
        phone.settings = WatchSettings(WakeLocation.OFF, "루미", revision = 1, phoneBackgroundWakeEnabled = true)
        phone.background.onAppShown()
        assertTrue(phone.background.start().microphone)
        phone.background.onAppHidden()
        phone.drain()
        assertTrue(phone.background.owning)
        assertEquals(1, phone.listened())
    }

    @Test
    fun `phone standby off during an open window releases the recognizer and holds at once`() {
        val phone = startedAndHidden()
        assertTrue("a window is open", phone.wake.listening)
        phone.calls.clear()
        phone.settings = phone.settings.copy(revision = 2, phoneBackgroundWakeEnabled = false)
        phone.background.onEligibilityChanged()
        phone.drain()
        assertFalse(phone.background.owning)
        assertTrue(phone.calls.contains("recognizer_release"))
        assertTrue(phone.calls.contains("rearm_cancel"))
        assertTrue(phone.held.isEmpty())
        assertTrue(phone.calls.contains("service_retype:mic=false"))
        assertFalse("not a Stop of the session", phone.calls.contains("service_stop"))
        assertTrue(phone.status().session.running)
    }

    @Test
    fun `phone standby off in the gap between windows cancels the re-arm and a stale due listens to nothing`() {
        val phone = startedAndHidden()
        phone.wake.onTimer()
        phone.wake.onError(phone.wake.generation, 7)
        assertTrue("the next window is scheduled", phone.calls.any { it.startsWith("rearm_in:") })
        phone.calls.clear()
        phone.settings = phone.settings.copy(revision = 2, phoneBackgroundWakeEnabled = false)
        phone.background.onEligibilityChanged()
        phone.drain()
        assertFalse(phone.background.owning)
        assertTrue(phone.calls.contains("rearm_cancel"))
        assertTrue(phone.held.isEmpty())
        val listens = phone.listened()
        phone.background.onRearmDue()
        phone.drain()
        assertEquals("a stale timer callback listens to nothing", listens, phone.listened())
    }

    @Test
    fun `the standby switch turned off ends the session and leaves nothing scheduled or held`() {
        val phone = startedAndHidden()
        phone.calls.clear()
        phone.settings = phone.settings.copy(revision = 2, phoneBackgroundWakeEnabled = false)
        val status = phone.background.onStandbyOff()
        phone.drain()
        assertFalse(status.wanted)
        assertFalse(phone.status().session.running)
        assertTrue(phone.calls.contains("service_stop"))
        assertTrue(phone.calls.contains("rearm_cancel"))
        assertTrue(phone.held.isEmpty())
        assertFalse(phone.background.owning)
    }

    @Test
    fun `the standby switch turned off while hidden and recording lets the recording finish before the session ends`() {
        val phone = startedAndHidden()
        phone.wake.onResults(phone.wake.generation, listOf("루미"), final = true)
        assertTrue(phone.wake.onHandoffDue(captureIdle = true))
        phone.recording = true
        phone.calls.clear()
        phone.settings = phone.settings.copy(revision = 2, phoneBackgroundWakeEnabled = false)
        phone.background.onStandbyOff()
        phone.drain()
        assertFalse(phone.calls.any { it.startsWith("capture_cancelled") || it.startsWith("cancel_capture") })
        assertFalse("the session stays until the recording ends", phone.calls.contains("service_stop"))
        phone.recording = false
        phone.wake.onRequestCaptureEnded(sent = true)
        phone.background.onIdle()
        phone.drain()
        assertTrue(phone.calls.contains("service_stop"))
        assertTrue(phone.held.isEmpty())
        assertFalse(phone.background.owning)
    }

    @Test
    fun `a standby switched back on before the recording ends keeps the session`() {
        val phone = startedAndHidden()
        phone.wake.onResults(phone.wake.generation, listOf("루미"), final = true)
        assertTrue(phone.wake.onHandoffDue(captureIdle = true))
        phone.recording = true
        phone.settings = phone.settings.copy(revision = 2, phoneBackgroundWakeEnabled = false)
        phone.background.onStandbyOff()
        phone.settings = phone.settings.copy(revision = 3, phoneBackgroundWakeEnabled = true)
        phone.recording = false
        phone.wake.onRequestCaptureEnded(sent = true)
        phone.background.onIdle()
        phone.drain()
        assertFalse(phone.calls.contains("service_stop"))
        assertTrue(phone.status().session.running)
    }

    @Test
    fun `phone standby off never cancels a hands-free recording already under way`() {
        val phone = startedAndHidden()
        phone.wake.onResults(phone.wake.generation, listOf("루미"), final = true)
        assertTrue(phone.wake.onHandoffDue(captureIdle = true))
        phone.recording = true
        phone.calls.clear()
        phone.settings = phone.settings.copy(revision = 2, phoneBackgroundWakeEnabled = false)
        phone.background.onEligibilityChanged()
        phone.drain()
        assertFalse(phone.calls.any { it.startsWith("capture_cancelled") || it.startsWith("cancel_capture") })
        assertFalse("the microphone type stays until the recording ends", phone.calls.contains("service_retype:mic=false"))
        phone.recording = false
        phone.wake.onRequestCaptureEnded(sent = true)
        phone.background.onIdle()
        phone.drain()
        assertTrue(phone.calls.contains("service_retype:mic=false"))
        assertTrue(phone.held.isEmpty())
        assertFalse(phone.background.owning)
    }

    @Test
    fun `the gaps between windows hold no wake lock on the phone either`() {
        val phone = startedAndHidden()
        phone.wake.onTimer()
        repeat(50) {
            phone.wake.onError(phone.wake.generation, 7)
            assertTrue("gap $it holds nothing", phone.held.isEmpty())
            phone.background.onRearmDue()
        }
        assertTrue(phone.held.containsKey(HoldReason.LISTEN))
    }
}
