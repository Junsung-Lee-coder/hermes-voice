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
import com.rumi.hermesvoice.core.wake.WakeArmInputs
import com.rumi.hermesvoice.core.wake.WakeContract
import com.rumi.hermesvoice.core.wake.WakeDeviceController
import com.rumi.hermesvoice.core.wake.WakeDevicePort
import com.rumi.hermesvoice.core.wake.WakeRecognizerPort
import com.rumi.hermesvoice.core.wake.WakeTimerPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Background standby is BACKGROUND ONLY: while the app is on screen the foreground wake location alone decides
 * whether a device listens, and a hidden armed session is decided by that device's own standby switch alone.
 * Every case drives the real controller, coordinator and presence through the same calls the Android adapters make.
 */
class ForegroundBackgroundSeparationTest {
    private val locations = WakeLocation.values().toList()
    private val flags = listOf(false, true)

    private fun settings(location: WakeLocation, phone: Boolean, watch: Boolean, revision: Long = 1) =
        WatchSettings(location, "루미", revision = revision, phoneBackgroundWakeEnabled = phone, watchBackgroundWakeEnabled = watch)

    // ── the foreground controller of either device (the Phone's own flow and the Watch's visible flow) ──────────────

    private class Foreground(val device: VoiceOrigin, initial: WatchSettings) {
        var now = 1_000L
        var windowOpen = false
        val log = mutableListOf<String>()

        private val port = object : WakeDevicePort {
            override fun windowChanged(open: Boolean) { windowOpen = open }
            override fun scheduleHandoff(delayMs: Long) { log += "handoff_in" }
            override fun cancelHandoff() {}
            override fun startRequestCapture(silenceMs: Long): Boolean { log += "capture"; return true }
            override fun cancelRequestCapture(reason: String) {}
            override fun sendRecognized(request: String) { log += "send:$request" }
            override fun closed(reason: String) { log += "closed:$reason" }
            override fun armInputs() = WakeArmInputs(enabled = true, resumed = true, interactive = true, ambient = false,
                permission = true, microphoneMuted = false, talkIdle = true, phoneReachable = true, nowMs = now,
                cooldownUntilMs = 0, generation = 0, lastArmedGeneration = null)
        }

        private val recognizer = object : WakeRecognizerPort, WakeTimerPort {
            override fun available() = true
            override fun start(generation: Long): Boolean { log += "listen:$generation"; return true }
            override fun release() { log += "recognizer_release" }
            override fun schedule(delayMs: Long) {}
            override fun cancel() {}
        }

        val wake = WakeDeviceController(device, recognizer, recognizer, port, { now }, initial)

        fun listens() = log.count { it.startsWith("listen:") }
        fun sent() = log.filter { it.startsWith("send:") }
    }

    @Test
    fun `on screen a device listens exactly when the foreground location includes it, whatever the four standby combinations`() {
        val violations = mutableListOf<String>()
        for (device in VoiceOrigin.values()) for (location in locations) for (phone in flags) for (watch in flags) {
            val fg = Foreground(device, settings(location, phone, watch))
            fg.wake.onResume(settingsPending = false)
            val expected = location.listensOn(device)
            if (fg.wake.listening != expected || fg.windowOpen != expected) {
                violations += "$device location=$location phoneStandby=$phone watchStandby=$watch: expected foreground listening=$expected, was listening=${fg.wake.listening} window=${fg.windowOpen}"
            }
        }
        assertEquals("foreground listening follows wakeLocation alone:\n" + violations.joinToString("\n"), emptyList<String>(), violations)
    }

    @Test
    fun `location PHONE with the Watch standby on opens no Watch foreground window`() {
        val fg = Foreground(VoiceOrigin.WATCH, settings(WakeLocation.PHONE, phone = false, watch = true))
        fg.wake.onResume(settingsPending = false)
        fg.wake.onSettingsCurrent()
        assertFalse("the Watch foreground is excluded by the location", fg.wake.listening)
        assertEquals(0, fg.listens())
        assertFalse(fg.windowOpen)
    }

    @Test
    fun `location OFF with the Phone standby on opens no Phone foreground window`() {
        val fg = Foreground(VoiceOrigin.PHONE, settings(WakeLocation.OFF, phone = true, watch = false))
        fg.wake.onResume(settingsPending = false)
        assertFalse("the Phone foreground is excluded by the location", fg.wake.listening)
        assertEquals(0, fg.listens())
        assertFalse(fg.windowOpen)
    }

    @Test
    fun `a selected foreground location still listens with the standby switch off, and the switch toggling never closes it`() {
        for (device in VoiceOrigin.values()) {
            val location = if (device == VoiceOrigin.PHONE) WakeLocation.PHONE else WakeLocation.WATCH
            val phoneFlag = device == VoiceOrigin.PHONE
            val fg = Foreground(device, settings(location, phone = false, watch = false))
            fg.wake.onResume(settingsPending = false)
            assertTrue("$device selected, standby off", fg.wake.listening)
            fg.wake.onSettings(settings(location, phone = phoneFlag, watch = !phoneFlag, revision = 2))
            assertTrue("$device standby switched on leaves the selected foreground window", fg.wake.listening)
            fg.wake.onSettings(settings(location, phone = false, watch = false, revision = 3))
            assertTrue("$device standby switched off leaves the selected foreground window", fg.wake.listening)
        }
    }

    @Test
    fun `a phrase heard while only the standby switch included the device is never handed off or sent`() {
        val fg = Foreground(VoiceOrigin.WATCH, settings(WakeLocation.PHONE, phone = false, watch = true))
        fg.wake.onResume(settingsPending = false)
        fg.wake.onResults(fg.wake.generation, listOf("루미 불 꺼"), final = true)
        assertEquals("an excluded foreground sends nothing", emptyList<String>(), fg.sent())
        assertFalse(fg.log.contains("handoff_in"))
    }

    @Test
    fun `a settings change that excludes the foreground closes the window, and one that only turns standby on opens none`() {
        val selected = Foreground(VoiceOrigin.PHONE, settings(WakeLocation.PHONE, phone = true, watch = false))
        selected.wake.onResume(settingsPending = false)
        assertTrue(selected.wake.listening)
        selected.wake.onSettings(settings(WakeLocation.WATCH, phone = true, watch = false, revision = 2))
        assertFalse("the location no longer includes the Phone", selected.wake.listening)
        assertFalse(selected.windowOpen)

        val excluded = Foreground(VoiceOrigin.WATCH, settings(WakeLocation.PHONE, phone = false, watch = false))
        excluded.wake.onResume(settingsPending = false)
        excluded.wake.onSettings(settings(WakeLocation.PHONE, phone = false, watch = true, revision = 2))
        assertFalse("turning the standby on is not a foreground selection", excluded.wake.listening)
        assertEquals(0, excluded.listens())
    }

    // ── the Watch: the real coordinator, presence, session and holds (see ComposedWatch) ────────────────────────────

    @Test
    fun `a Watch the location excludes never listens on screen because its standby is on, armed or not`() {
        for (location in listOf(WakeLocation.PHONE, WakeLocation.OFF)) {
            val w = ComposedWatch(location, watchStandby = true)
            w.show()
            assertFalse("$location visible, before Start", w.windowOpen)
            assertEquals("$location visible, before Start", 0, w.listens())
            w.start()
            assertTrue("$location: the legal service is armed at the visible visit", w.coordinator.presence.armed)
            assertEquals(BackgroundNotice.LISTENING, w.notice)
            assertFalse("$location visible, armed: the foreground stays excluded", w.windowOpen)
            assertEquals("$location visible, armed", 0, w.listens())
            assertTrue("no wake lock for a window that never opened", w.held().isEmpty())
        }
    }

    @Test
    fun `the same excluded Watch listens once hidden, stops when shown again, and the service survives both`() {
        val w = ComposedWatch(WakeLocation.PHONE, watchStandby = true)
        w.show(); w.start()
        w.hide()
        assertTrue("hidden standby listens irrespective of the location", w.windowOpen)
        assertTrue(HoldReason.LISTEN in w.held())
        assertEquals(WakeContract.BACKGROUND_WINDOW_MS, w.wake.windowMs)
        w.show()
        assertFalse("shown again: the excluded foreground is closed", w.windowOpen)
        assertTrue("the armed service survives the visit", w.coordinator.presence.armed)
        assertEquals("microphone|mediaPlayback", w.serviceType)
        assertNull("no idle window is scheduled behind the visible app", w.rearmIn)
        assertTrue(w.held().isEmpty())
        w.hide()
        assertTrue("hidden again: standby listens again", w.windowOpen)
    }

    @Test
    fun `screen off while the excluded Watch app is visible and armed is the hidden standby, and screen on gives the foreground back`() {
        val w = ComposedWatch(WakeLocation.PHONE, watchStandby = true)
        w.show(); w.start()
        assertFalse(w.windowOpen)
        w.screenOn = false; w.coordinator.onScreenOff(); w.drain()
        assertTrue("screen off: only the standby can listen", w.windowOpen)
        w.windowTimeout()
        assertTrue("between windows nothing is held", w.held().isEmpty())
        assertEquals(com.rumi.hermesvoice.core.wake.ContinuousWakePolicy.REARM_MS, w.rearmIn)
        w.rearmDue()
        assertTrue(w.windowOpen)
        w.screenOn = true; w.coordinator.onScreenOn(); w.drain()
        assertFalse("screen on with the app visible: the excluded foreground is closed", w.windowOpen)
        assertNull("and no idle window is left scheduled", w.rearmIn)
        assertTrue(w.coordinator.presence.armed)
    }

    @Test
    fun `on screen the Watch follows the location alone for all location and flag combinations`() {
        val violations = mutableListOf<String>()
        for (location in locations) for (watch in flags) for (phone in flags) {
            val w = ComposedWatch(location, watchStandby = watch, phoneStandby = phone)
            w.show()
            val expected = location.listensOn(VoiceOrigin.WATCH)
            if (w.windowOpen != expected) violations += "visible location=$location watchStandby=$watch phoneStandby=$phone: expected window=$expected was ${w.windowOpen}"
            w.start()
            if (w.windowOpen != expected) violations += "visible+started location=$location watchStandby=$watch phoneStandby=$phone: expected window=$expected was ${w.windowOpen}"
            w.hide()
            if (w.windowOpen != watch) violations += "hidden location=$location watchStandby=$watch phoneStandby=$phone: expected window=$watch was ${w.windowOpen}"
        }
        assertEquals("Watch eligibility by mode:\n" + violations.joinToString("\n"), emptyList<String>(), violations)
    }

    @Test
    fun `a selected Watch with standby off listens on screen and never hidden, and an excluded OFF Watch that turns standby on while visible still opens nothing`() {
        val off = ComposedWatch(WakeLocation.WATCH, watchStandby = false)
        off.show()
        assertTrue(off.windowOpen)
        off.hide()
        assertFalse(off.windowOpen)

        val w = ComposedWatch(WakeLocation.PHONE, watchStandby = false)
        w.show()
        w.standbyChange(watch = true)
        assertFalse("the standby switch turning on in the visible app opens no foreground window", w.windowOpen)
        assertEquals(0, w.listens())
    }

    @Test
    fun `hidden OFF then ON with an excluded location is only requested, and the next visit arms without listening on screen`() {
        val w = ComposedWatch(WakeLocation.PHONE, watchStandby = true)
        w.show(); w.start(); w.hide()
        w.standbyChange(watch = false)
        val requests = w.micRequests()
        w.standbyChange(watch = true)
        assertEquals("never armed from the background", requests, w.micRequests())
        assertFalse(w.windowOpen)
        assertEquals(BackgroundNotice.NEEDS_VISIBLE_TO_LISTEN, w.notice)
        w.show()
        assertEquals("the visit arms the legal service", BackgroundNotice.LISTENING, w.notice)
        assertTrue(w.coordinator.presence.armed)
        assertFalse("but the excluded foreground is not opened", w.windowOpen)
        w.hide()
        assertTrue(w.windowOpen)
    }

    @Test
    fun `an accepted hands-free recording survives a visit that excludes the foreground, and is sent as usual`() {
        val w = ComposedWatch(WakeLocation.PHONE, watchStandby = true)
        w.show(); w.start(); w.hide()
        w.heard("루미")
        w.handoffDue()
        assertTrue(w.capturing)
        w.log.clear()
        w.show()
        assertTrue("a recording already accepted is not cancelled by the visit", w.capturing)
        assertFalse(w.log.any { it.startsWith("cancel_capture") })
    }

    @Test
    fun `one device in the foreground and the other in the background are still arbitrated to one turn`() {
        val w = ComposedWatch(WakeLocation.PHONE, arbitrated = true, watchStandby = true, phoneStandby = false)
        w.show(); w.start(); w.hide()
        w.heard("루미")
        assertEquals("the Watch's background phrase asks the Phone for the claim", listOf("claim:claim-1"), w.claimsSent)
        assertNull("nothing is recorded before the Phone grants the claim", w.handoffIn)
        w.wake.onClaimVerdict("claim-1", com.rumi.hermesvoice.core.wake.ClaimVerdict.GRANTED); w.drain()
        assertTrue("granted: recorded", w.handoffIn != null)
    }

    // ── the Phone: its own foreground flow and its background flow are separate controllers ──────────────────────────

    private class PhoneBoth(initial: WatchSettings) {
        var now = 0L
        var settings = initial
        val calls = mutableListOf<String>()
        val held = HashMap<HoldReason, Long>()
        val posted = ArrayDeque<() -> Unit>()
        var fgWindow = false
        var bgWindow = false

        private val service = object : BackgroundPort {
            override fun startService(microphone: Boolean): Boolean { calls += "service_start:mic=$microphone"; return true }
            override fun retypeService(microphone: Boolean): Boolean { calls += "service_retype:mic=$microphone"; return true }
            override fun stopService() { calls += "service_stop" }
        }

        private val host = object : PhoneBackgroundHost {
            override fun settings() = settings
            override fun microphonePermission() = true
            override fun onDeviceRecognizer() = true
            override fun notifications() = NotificationCapability.SHOWN
            override fun scheduleRearm(delayMs: Long) { calls += "rearm_in:$delayMs" }
            override fun cancelRearm() { calls += "rearm_cancel" }
            override fun cancelCapture(reason: String) {}
            override fun post(block: () -> Unit) { posted += block }
            override fun statusChanged(status: PhoneWakeStatus) {}
        }

        val holds = WakeHolds(object : WakeLockPort {
            override fun acquire(reason: HoldReason, timeoutMs: Long) { held[reason] = timeoutMs }
            override fun release(reason: HoldReason) { held.remove(reason) }
        }, { now })

        val background = PhoneBackgroundWake(InMemoryKeyValueStore(), "phone_background_wake", service, host, holds) { now }

        private fun device(onWindow: (Boolean) -> Unit, tag: String) = object : WakeDevicePort {
            override fun windowChanged(open: Boolean) { onWindow(open) }
            override fun scheduleHandoff(delayMs: Long) {}
            override fun cancelHandoff() {}
            override fun startRequestCapture(silenceMs: Long): Boolean = true
            override fun cancelRequestCapture(reason: String) {}
            override fun sendRecognized(request: String) { calls += "$tag:send:$request" }
            override fun closed(reason: String) {}
            override fun armInputs() = WakeArmInputs(enabled = true, resumed = true, interactive = true, ambient = false,
                permission = true, microphoneMuted = false, talkIdle = true, phoneReachable = true, nowMs = now,
                cooldownUntilMs = 0, generation = 0, lastArmedGeneration = null)
        }

        private val recognizer = object : WakeRecognizerPort, WakeTimerPort {
            override fun available() = true
            override fun start(generation: Long): Boolean { calls += "listen"; return true }
            override fun release() {}
            override fun schedule(delayMs: Long) {}
            override fun cancel() {}
        }

        /** The Phone app's own wake flow (PhoneWakeController.wake): resumed and paused by the activity. */
        val foreground = WakeDeviceController(VoiceOrigin.PHONE, recognizer, recognizer, device({ fgWindow = it }, "fg"), { now }, initial)

        /** The background-only flow, built on the session's device port as PhoneBackgroundRuntime builds it. */
        val backgroundWake = WakeDeviceController(VoiceOrigin.PHONE, recognizer, recognizer,
            background.devicePort(device({ bgWindow = it }, "bg")), { now }, initial).also { background.attach(it) }

        fun drain() { while (posted.isNotEmpty()) posted.removeFirst()() }

        /** MainActivity.onResume: the background flow gives the microphone back, then the app's own flow resumes. */
        fun show() { background.onAppShown(); foreground.onResume(settingsPending = false); drain() }

        /** MainActivity.onPause: the app's own flow stops, then an armed session's flow takes over. */
        fun hide() { foreground.onPause(); background.onAppHidden(); drain() }

        fun settingsChange(next: WatchSettings) {
            settings = next
            foreground.onSettings(next)
            background.onEligibilityChanged()
            drain()
        }
    }

    @Test
    fun `Phone location OFF with the Phone standby on listens hidden and never in the visible app`() {
        val phone = PhoneBoth(settings(WakeLocation.OFF, phone = true, watch = false))
        phone.show()
        assertFalse("the Phone's foreground is excluded by the location", phone.foreground.listening)
        assertFalse(phone.fgWindow)
        assertTrue(phone.background.start().microphone)
        assertFalse(phone.foreground.listening)
        assertFalse("not the background flow either while the app is on screen", phone.backgroundWake.listening)
        phone.hide()
        assertTrue("hidden: the standby flow owns the microphone", phone.background.owning && phone.backgroundWake.listening)
        phone.show()
        assertFalse(phone.backgroundWake.listening)
        assertFalse("visible again: still no foreground window under location OFF", phone.foreground.listening)
    }

    @Test
    fun `Phone location PHONE with the standby off listens in the visible app and never hidden`() {
        val phone = PhoneBoth(settings(WakeLocation.PHONE, phone = false, watch = false))
        phone.show()
        assertTrue(phone.foreground.listening)
        assertFalse("standby off: Start is refused for the wake phrase", phone.background.start().microphone)
        phone.hide()
        assertFalse(phone.foreground.listening)
        assertFalse(phone.backgroundWake.listening)
        assertFalse(phone.background.owning)
    }

    @Test
    fun `the Phone standby switch turning on or the location excluding the Phone in the visible app changes the foreground only through the location`() {
        val phone = PhoneBoth(settings(WakeLocation.WATCH, phone = false, watch = false))
        phone.show()
        phone.settingsChange(settings(WakeLocation.WATCH, phone = true, watch = false, revision = 2))
        assertFalse("standby on does not open the excluded Phone foreground", phone.foreground.listening)
        phone.settingsChange(settings(WakeLocation.PHONE, phone = true, watch = false, revision = 3))
        assertTrue("the location now includes the Phone", phone.foreground.listening)
        phone.settingsChange(settings(WakeLocation.WATCH, phone = true, watch = false, revision = 4))
        assertFalse("the location excludes the Phone again; the standby does not keep the foreground open", phone.foreground.listening)
    }
}
