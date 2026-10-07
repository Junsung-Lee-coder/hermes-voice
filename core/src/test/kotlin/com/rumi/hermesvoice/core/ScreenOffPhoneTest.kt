package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.background.BackgroundNotice
import com.rumi.hermesvoice.core.background.BackgroundPort
import com.rumi.hermesvoice.core.background.BackgroundText
import com.rumi.hermesvoice.core.background.HoldReason
import com.rumi.hermesvoice.core.background.NotificationCapability
import com.rumi.hermesvoice.core.background.PhoneBackgroundHost
import com.rumi.hermesvoice.core.background.PhoneBackgroundWake
import com.rumi.hermesvoice.core.background.PhoneWakeStatus
import com.rumi.hermesvoice.core.background.WakeHolds
import com.rumi.hermesvoice.core.background.WakeLockPort
import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.wake.WakeArmInputs
import com.rumi.hermesvoice.core.wake.WakeDeviceController
import com.rumi.hermesvoice.core.wake.WakeDevicePort
import com.rumi.hermesvoice.core.wake.WakeRecognizerPort
import com.rumi.hermesvoice.core.wake.WakeTimerPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Phone's "recognition while the screen is off" preference, through the real [PhoneBackgroundWake], [WakeDeviceController],
 * presence, session and holds with only the platform faked at the ports the Android runtime implements. The Phone reports its
 * own screen through [PhoneBackgroundWake.onEligibilityChanged] (what the runtime's screen receiver posts). The Watch's screen and
 * preference are not inputs of this device.
 */
class ScreenOffPhoneTest {
    internal class Rig(
        pref: Boolean,
        master: Boolean = true,
        location: WakeLocation = WakeLocation.PHONE,
        watchPref: Boolean = false,
        val store: InMemoryKeyValueStore = InMemoryKeyValueStore(),
    ) {
        val calls = mutableListOf<String>()
        var now = 0L
        var settings = WatchSettings(location, "루미", revision = 1, phoneBackgroundWakeEnabled = master, watchBackgroundWakeEnabled = true)
            .withScreenOff(phone = pref, watch = watchPref)
        var recording = false
        var screenOn = true
        var permission = true
        var busy = false
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
            override fun onDeviceRecognizer() = true
            override fun notifications() = NotificationCapability.SHOWN
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

        val background = PhoneBackgroundWake(store, "phone_background_wake", service, host, holds) { now }

        private val inner = object : WakeDevicePort {
            override fun windowChanged(open: Boolean) { calls += "window:$open" }
            override fun scheduleHandoff(delayMs: Long) { calls += "handoff_in" }
            override fun cancelHandoff() { calls += "handoff_cancel" }
            override fun startRequestCapture(silenceMs: Long): Boolean { calls += "capture"; return true }
            override fun cancelRequestCapture(reason: String) { calls += "capture_cancelled:$reason" }
            override fun sendRecognized(request: String) { calls += "send:$request" }
            override fun closed(reason: String) { calls += "closed:$reason" }
            override fun armInputs() = WakeArmInputs(enabled = settings.mayListen(VoiceOrigin.PHONE), resumed = false,
                interactive = screenOn, ambient = false, permission = permission, microphoneMuted = false, talkIdle = !busy,
                phoneReachable = true, nowMs = now, cooldownUntilMs = 0, generation = 0, lastArmedGeneration = null)
        }

        private val recognizer = object : WakeRecognizerPort, WakeTimerPort {
            override fun available() = true
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

        /** The visible app starts the session, then the app is hidden (the screen is on). */
        fun startedAndHidden(): Rig = apply {
            background.onAppShown()
            background.start()
            background.onAppHidden()
            drain()
        }

        /** The Phone's screen receiver / settings observers report; the live screen is [screenOn]. */
        fun screenOff() { screenOn = false; background.onEligibilityChanged(); drain() }
        fun screenOnEvent() { screenOn = true; background.onEligibilityChanged(); drain() }
        fun preference(on: Boolean) {
            settings = settings.copy(revision = settings.revision + 1).withScreenOff(phone = on)
            background.onEligibilityChanged(); drain()
        }
    }

    private fun Rig.assertListening(why: String) {
        assertTrue("$why: listening", wake.listening)
        assertTrue("$why: LISTEN hold", HoldReason.LISTEN in held)
        assertTrue("$why: owning", background.owning)
        assertTrue("$why: status", status().listeningNow)
    }

    private fun Rig.assertIdleGone(why: String) {
        assertFalse("$why: no listening", wake.listening)
        assertFalse("$why: no LISTEN hold", HoldReason.LISTEN in held)
        assertFalse("$why: no HANDOFF hold", HoldReason.HANDOFF in held)
        assertFalse("$why: not Stop: $calls", calls.contains("service_stop"))
        assertTrue("$why: the session goes on", status().session.running)
        assertTrue("$why: still armed, so the screen coming back needs no visit", status().session.microphone && background.owning)
        assertFalse("$why: does not say it listens", status().listeningNow)
    }

    private fun Rig.lateDueListensToNothing(why: String) {
        val listens = listened()
        repeat(2) { background.onRearmDue(); drain() }
        assertEquals("$why: a late alarm listens to nothing", listens, listened())
        assertFalse("$why: a late alarm holds nothing", HoldReason.LISTEN in held)
    }

    // ── the policy table ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a hidden Phone listens exactly when its master is on and the screen is interactive or its preference is on`() {
        for (master in listOf(true, false)) for (pref in listOf(true, false)) for (screenOff in listOf(false, true)) {
            val phone = Rig(pref, master).apply { background.onAppShown(); background.start(); if (screenOff) screenOn = false; background.onAppHidden(); drain() }
            val expected = master && (!screenOff || pref)
            val label = "master=$master pref=$pref screenOff=$screenOff"
            assertEquals("$label: listening", expected, phone.wake.listening)
            assertEquals("$label: LISTEN hold", expected, HoldReason.LISTEN in phone.held)
            assertEquals("$label: window opened", if (expected) 1 else 0, phone.listened())
            if (!master) assertFalse("$label: a preference never arms or owns", phone.background.owning)
        }
    }

    @Test
    fun `the table follows screen and preference changes while hidden in every order`() {
        for (master in listOf(true, false)) for (screenOff in listOf(false, true)) for (prefAfter in listOf(true, false)) {
            val phone = Rig(!prefAfter, master).startedAndHidden()
            if (screenOff) phone.screenOff()
            phone.preference(prefAfter)
            val expected = master && (!screenOff || prefAfter)
            val label = "master=$master screenOff=$screenOff prefAfter=$prefAfter"
            assertEquals("$label: listening", expected, phone.wake.listening)
            assertEquals("$label: LISTEN hold", expected, HoldReason.LISTEN in phone.held)
        }
    }

    @Test
    fun `the screen being off when the app is hidden never listens without the preference, and the screen coming back does`() {
        val phone = Rig(pref = false)
        phone.background.onAppShown()
        phone.background.start()
        phone.screenOn = false
        phone.background.onEligibilityChanged()
        phone.background.onAppHidden()
        phone.drain()
        phone.assertIdleGone("hidden with the screen off")
        assertEquals(0, phone.listened())
        assertTrue(phone.held.isEmpty())
        phone.screenOnEvent()
        phone.assertListening("screen on")
        assertEquals(1, phone.calls.count { it == "service_start:mic=true" })
    }

    // ── prompt teardown ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the screen turning off with the preference off releases the recognizer, the alarm and the holds but not the session`() {
        val phone = Rig(pref = false).startedAndHidden()
        phone.assertListening("screen on")
        phone.calls.clear()
        phone.screenOff()
        phone.assertIdleGone("screen off, preference off")
        assertTrue(phone.calls.contains("recognizer_release"))
        assertTrue(phone.calls.contains("rearm_cancel"))
        assertTrue(phone.held.isEmpty())
        assertFalse("the microphone type is kept: Android cannot start it from the background", phone.calls.contains("service_retype:mic=false"))
        assertEquals("WAITING_FOR_SCREEN", phone.status().loop.name)
        val line = BackgroundText.phoneWakeStatus(phone.status(), notifications = true)
        assertTrue(line, line.contains("waiting for the screen"))
        assertTrue(BackgroundText.phoneWakeNotification(phone.status())!!.contains("waiting for the screen", ignoreCase = true))
        phone.lateDueListensToNothing("screen off")
    }

    @Test
    fun `a pending re-arm alarm is cancelled and its late due does nothing when the screen turns off`() {
        val phone = Rig(pref = false).startedAndHidden()
        phone.wake.onTimer()
        phone.wake.onError(phone.wake.generation, 7)
        assertTrue("positive control: the next window is scheduled", phone.calls.any { it.startsWith("rearm_in:") })
        phone.calls.clear()
        phone.screenOff()
        assertTrue(phone.calls.contains("rearm_cancel"))
        assertFalse("no new alarm is scheduled by the teardown: ${phone.calls}", phone.calls.any { it.startsWith("rearm_in:") })
        phone.assertIdleGone("screen off in a gap")
        phone.lateDueListensToNothing("screen off in a gap")
        assertFalse(phone.calls.any { it.startsWith("rearm_in:") })
    }

    @Test
    fun `turning the preference off while the screen is already off tears down the same way`() {
        val phone = Rig(pref = true).startedAndHidden()
        phone.screenOff()
        phone.assertListening("preference on, screen off")
        phone.calls.clear()
        phone.preference(false)
        phone.assertIdleGone("preference turned off while screen off")
        assertTrue(phone.calls.contains("recognizer_release"))
        assertTrue(phone.calls.contains("rearm_cancel"))
        phone.lateDueListensToNothing("preference turned off while screen off")
    }

    @Test
    fun `a wake phrase heard but not yet accepted is dropped, and an old result changes nothing`() {
        val phone = Rig(pref = false).startedAndHidden()
        val generation = phone.wake.generation
        phone.wake.onResults(generation, listOf("루미"), final = true)
        assertTrue("positive control: the episode is pending", HoldReason.HANDOFF in phone.held || phone.calls.contains("handoff_in"))
        phone.calls.clear()
        phone.screenOff()
        phone.assertIdleGone("screen off with a pending handoff")
        assertFalse(phone.wake.onHandoffDue(captureIdle = true).also { phone.drain() })
        phone.wake.onResults(generation, listOf("루미"), final = true)
        phone.wake.onError(generation, 7)
        phone.wake.onTimer()
        phone.drain()
        assertFalse("nothing records after the screen turned off", phone.calls.contains("capture"))
        assertFalse(phone.calls.any { it.startsWith("send:") })
        phone.assertIdleGone("late callbacks")
    }

    // ── recordings and the session are not touched ───────────────────────────────────────────

    @Test
    fun `an accepted hands-free recording survives the screen turning off, and nothing listens until the screen is back`() {
        val phone = Rig(pref = false).startedAndHidden()
        phone.wake.onResults(phone.wake.generation, listOf("루미"), final = true)
        assertTrue(phone.wake.onHandoffDue(captureIdle = true))
        phone.recording = true
        phone.calls.clear()
        phone.screenOff()
        assertFalse("the recording is not cancelled: ${phone.calls}", phone.calls.any { it.startsWith("capture_cancelled") || it.startsWith("cancel_capture") })
        assertFalse(phone.calls.contains("service_stop"))
        assertFalse("the microphone type stays for the recording", phone.calls.contains("service_retype:mic=false"))
        assertFalse("nothing new listens", phone.calls.any { it.startsWith("listen:") })
        phone.recording = false
        phone.wake.onRequestCaptureEnded(sent = true)
        phone.background.onIdle()
        phone.drain()
        phone.assertIdleGone("recording ended, screen still off")
        phone.screenOnEvent()
        phone.assertListening("screen back on")
    }

    // ── re-enable and recovery ───────────────────────────────────────────────────────────────

    @Test
    fun `the screen coming back or the preference being turned on listens once and repeats are deduped`() {
        val phone = Rig(pref = false).startedAndHidden()
        phone.screenOff()
        phone.assertIdleGone("denied")
        val listens = phone.listened()
        phone.preference(true)
        phone.assertListening("preference on")
        assertEquals(listens + 1, phone.listened())
        repeat(3) { phone.preference(true); phone.background.onEligibilityChanged(); phone.drain() }
        assertEquals("repeats never listen twice", listens + 1, phone.listened())
        phone.preference(false)
        phone.assertIdleGone("denied again")
        phone.screenOnEvent()
        phone.assertListening("screen back on")
        repeat(3) { phone.screenOnEvent() }
        assertEquals(listens + 2, phone.listened())
        assertEquals("no service start from the background", 1, phone.calls.count { it.startsWith("service_start") })
    }

    @Test
    fun `a process restart arms nothing from the background whatever the stored preference says`() {
        val first = Rig(pref = true).startedAndHidden()
        first.screenOff()
        first.assertListening("before the process died")
        val reborn = Rig(pref = true, store = first.store)
        reborn.screenOn = false
        reborn.background.onEligibilityChanged()
        reborn.drain()
        assertFalse("a stored preference is a request, not an armed microphone", reborn.calls.any { it.startsWith("service_start:mic=true") || it.startsWith("listen:") })
        assertFalse(reborn.wake.listening)
        assertTrue(reborn.held.isEmpty())
        reborn.background.onRearmDue(); reborn.drain()
        assertFalse(reborn.wake.listening)
    }

    @Test
    fun `the persisted settings drive the Phone after a restart with the visit that arms it`() {
        val store = InMemoryKeyValueStore()
        val saver = AppSettings(store)
        saver.saveWatchSettings(saver.watchSettings().copy(wakeLocation = WakeLocation.PHONE, phoneBackgroundWakeEnabled = true).withScreenOff(phone = true), 1_000L)
        val rig = Rig(pref = false, store = InMemoryKeyValueStore())
        rig.settings = AppSettings(store).watchSettings()
        rig.background.onEligibilityChanged()
        rig.background.onAppShown(); rig.background.start(); rig.screenOn = false; rig.background.onAppHidden(); rig.drain()
        rig.assertListening("persisted preference on, screen off")
        saver.saveWatchSettings(saver.watchSettings().withScreenOff(phone = false), 2_000L)
        rig.settings = AppSettings(store).watchSettings()
        rig.background.onEligibilityChanged(); rig.drain()
        rig.assertIdleGone("persisted preference off")
    }

    // ── what the preference is not ───────────────────────────────────────────────────────────

    @Test
    fun `the preference on with the master off never listens, and the master off tears down whatever the screen does`() {
        val phone = Rig(pref = true, master = false)
        phone.background.onAppShown()
        phone.background.start()
        phone.background.onAppHidden()
        phone.drain()
        phone.screenOff(); phone.screenOnEvent(); phone.screenOff()
        assertEquals(0, phone.listened())
        assertFalse(phone.background.owning)
        assertTrue(phone.held.isEmpty())
        val on = Rig(pref = true).startedAndHidden()
        on.screenOff()
        on.assertListening("master on")
        on.settings = on.settings.copy(revision = 9, phoneBackgroundWakeEnabled = false)
        on.background.onEligibilityChanged(); on.drain()
        assertFalse(on.wake.listening)
        assertFalse(on.background.owning)
        assertTrue(on.held.isEmpty())
        assertFalse("retained preference never revives it", on.also { it.screenOff(); it.screenOnEvent() }.wake.listening)
    }

    @Test
    fun `Listen on selecting the Phone does not keep a screen-off Phone listening and the Watch settings are not its inputs`() {
        val phone = Rig(pref = false, location = WakeLocation.BOTH, watchPref = true).startedAndHidden()
        phone.assertListening("hidden, screen on")
        phone.screenOff()
        phone.assertIdleGone("location Both and the Watch preference do not decide the Phone")
        val other = Rig(pref = true, location = WakeLocation.PHONE, watchPref = false).startedAndHidden()
        other.screenOff()
        other.assertListening("the Phone's own preference decides")
        val excluded = Rig(pref = true, location = WakeLocation.WATCH, watchPref = false).startedAndHidden()
        excluded.screenOff()
        excluded.assertIdleGone("Listen on leaves the Phone out: its own preference and standby decide nothing")
    }

    @Test
    fun `an eligible screen-off Phone survives windows one after another with no hold in the gaps`() {
        val phone = Rig(pref = true).startedAndHidden()
        phone.screenOff()
        phone.wake.onTimer()
        repeat(20) {
            phone.wake.onError(phone.wake.generation, 7)
            assertTrue("gap $it holds nothing", phone.held.isEmpty())
            phone.background.onRearmDue()
        }
        assertTrue(phone.held.containsKey(HoldReason.LISTEN))
        assertEquals(BackgroundNotice.LISTENING, phone.status().session.notice)
    }

    @Test
    fun `showing the app takes the microphone back whatever the preference, and hiding it with the screen off follows the table`() {
        for (pref in listOf(true, false)) {
            val phone = Rig(pref).startedAndHidden()
            phone.background.onAppShown(); phone.drain()
            assertFalse("pref=$pref: the visible app owns the microphone", phone.background.owning)
            assertTrue(phone.held.isEmpty())
            phone.screenOn = false
            phone.background.onAppHidden(); phone.drain()
            assertEquals("pref=$pref: hidden with the screen off", pref, phone.wake.listening)
        }
    }
}
