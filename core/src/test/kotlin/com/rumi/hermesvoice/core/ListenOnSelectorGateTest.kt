package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.settings.WakeGate
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BUDGET = 5_000L
private const val HOUR = 3_600_000L

/** A hidden device built with "Listen on" [location], its own standby switch and its screen-off preference, on the real Phone / Watch classes. */
internal fun listenDev(device: VoiceOrigin, location: WakeLocation, standby: Boolean = true, screenOff: Boolean = false): Dev =
    if (device == VoiceOrigin.PHONE) PhoneDev(pref = screenOff, master = standby).also {
        it.rig.settings = it.rig.settings.copy(wakeLocation = location)
        it.eligibility()
    } else WatchDev(pref = screenOff, master = standby, mode = location)

/** A newer Phone snapshot with a new "Listen on" choice, delivered the way each device receives it. */
internal fun Dev.listenOn(location: WakeLocation) {
    if (this is PhoneDev) rig.settings = rig.settings.copy(wakeLocation = location, revision = rig.settings.revision + 1)
    else if (this is WatchDev) w.settings = w.settings.copy(wakeLocation = location, revision = w.settings.revision + 1)
    eligibility()
}

private fun Dev.standbyFlag(on: Boolean) {
    if (this is PhoneDev) rig.settings = rig.settings.copy(phoneBackgroundWakeEnabled = on, revision = rig.settings.revision + 1)
    else if (this is WatchDev) w.settings = w.settings.copy(watchBackgroundWakeEnabled = on, revision = w.settings.revision + 1)
    eligibility()
}

private fun Dev.unselectedStaysSilent(why: String, listensBefore: Int = listenCount()) {
    assertDark(why)
    assertEquals("$name $why: no window was ever opened or reopened", listensBefore, listenCount())
    assertTrue("$name $why: nothing was sent: ${sent()}", sent().isEmpty())
    assertEquals("$name $why: nothing was recorded", 0, captured())
}

private val SEL_DEVICES = listOf(VoiceOrigin.PHONE, VoiceOrigin.WATCH)
private val SEL_LOCATIONS = WakeLocation.values().toList()
private fun picks(device: VoiceOrigin, location: WakeLocation) = location.listensOn(device)

private fun excludingOf(device: VoiceOrigin): List<WakeLocation> = listOf(WakeLocation.OFF, if (device == VoiceOrigin.PHONE) WakeLocation.WATCH else WakeLocation.PHONE)

/** "Listen on" is the top-level gate of the wake phrase, hidden or visible, whatever a device's standby or screen-off flag says. */
class ListenOnSelectorGateTest {
    // ── the settings truth table ─────────────────────────────────────────────────────────────

    @Test
    fun `Listen on is the master gate of every settings question, standby and screen-off flags only narrow it`() {
        for (location in SEL_LOCATIONS) for (phone in listOf(false, true)) for (watch in listOf(false, true)) for (po in listOf(false, true)) for (wo in listOf(false, true)) {
            val s = WatchSettings(location, "루미", revision = 1, phoneBackgroundWakeEnabled = phone, watchBackgroundWakeEnabled = watch)
                .withScreenOff(phone = po, watch = wo)
            for (device in SEL_DEVICES) {
                val standby = if (device == VoiceOrigin.PHONE) phone else watch
                val screenOff = if (device == VoiceOrigin.PHONE) po else wo
                val pick = picks(device, location)
                val label = "$location $device standby=$standby screenOff=$screenOff"
                assertEquals("$label: foreground", pick, s.listensIn(device, WakeGate.FOREGROUND))
                assertEquals("$label: background", pick && standby, s.listensIn(device, WakeGate.STANDBY))
                assertEquals("$label: standby, screen on", pick && standby, s.standbyListens(device, screenInteractive = true))
                assertEquals("$label: standby, screen off", pick && standby && screenOff, s.standbyListens(device, screenInteractive = false))
                assertEquals("$label: may listen", pick, s.mayListen(device))
            }
            assertEquals("$location phone=$phone watch=$watch: arbitration only when Listen on selects both", location == WakeLocation.BOTH, s.arbitrationRequired)
        }
    }

    // ── a hidden device ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a hidden device listens exactly when Listen on selects it, its standby is on and its screen allows it`() {
        for (device in SEL_DEVICES) for (location in SEL_LOCATIONS) for (standby in listOf(false, true)) for (pref in listOf(false, true)) {
            val d = listenDev(device, location, standby, pref)
            val label = "$device $location standby=$standby screenOff=$pref"
            d.boot()
            val expected = picks(device, location) && standby
            assertEquals("$label: listening with the screen on", expected, d.wake.listening)
            assertEquals("$label: LISTEN hold", expected, d.listenHold())
            val visibleWindow = device == VoiceOrigin.WATCH && picks(device, location)
            assertEquals("$label: windows opened", if (expected || visibleWindow) 1 else 0, d.listenCount())
            if (!picks(device, location)) d.unselectedStaysSilent("$label: not selected")
            d.deactivate()
            d.advanceTo(d.now + 60_000)
            if (!(expected && pref)) d.assertDark("$label: screen off")
            if (!picks(device, location)) d.unselectedStaysSilent("$label: not selected, screen off", 0)
            val before = d.listenCount()
            d.activate()
            val t = d.now
            if (expected && !pref) d.assertListeningUntil(t + BUDGET, "$label: a new activation opens one 5 s budget")
            if (!picks(device, location)) d.unselectedStaysSilent("$label: not selected, new activation", 0)
            d.advanceTo(t + HOUR)
            if (!picks(device, location)) d.unselectedStaysSilent("$label: not selected, an hour later", 0)
            if (expected && !pref) {
                d.assertDark("$label: budget over")
                assertEquals("$label: one window for that activation", before + 1, d.listenCount())
            }
        }
    }

    @Test
    fun `an unselected device with screen-off recognition on never starts, whatever the screen does`() {
        for (device in SEL_DEVICES) for (location in excludingOf(device)) {
            val d = listenDev(device, location, standby = true, screenOff = true)
            d.boot()
            d.unselectedStaysSilent("$device $location boot", 0)
            d.deactivate(); d.advanceTo(d.now + 120_000); d.activate(); d.advanceTo(d.now + 120_000)
            d.hide(); d.replaySettings(); d.eligibility(); d.idle(); d.staleRearm(); d.playbackBusy()
            d.advanceTo(d.now + HOUR)
            d.unselectedStaysSilent("$device $location screen and standby churn", 0)
        }
    }

    @Test
    fun `a selected device with screen-off recognition on keeps the continuous background behaviour`() {
        for (device in SEL_DEVICES) for (location in listOf(selectedOnly(device), WakeLocation.BOTH)) {
            val d = listenDev(device, location, standby = true, screenOff = true)
            d.boot()
            assertTrue("$device $location: listening", d.wake.listening)
            d.deactivate()
            d.advanceTo(d.now + 120_000)
            assertTrue("$device $location: screen off keeps listening or re-arming", d.wake.listening || d.rearmAt != null)
            assertTrue("$device $location: windows follow one another", d.listenCount() > 1)
            d.activate()
            d.advanceTo(d.now + 120_000)
            assertTrue("$device $location: still continuous", d.wake.listening || d.rearmAt != null)
        }
    }

    private fun selectedOnly(device: VoiceOrigin) = if (device == VoiceOrigin.PHONE) WakeLocation.PHONE else WakeLocation.WATCH

    // ── visible app ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a visible Watch listens exactly when Listen on selects it, and hiding it never grants what Listen on refuses`() {
        for (location in SEL_LOCATIONS) for (standby in listOf(false, true)) for (pref in listOf(false, true)) {
            val d = WatchDev(pref = pref, master = standby, mode = location)
            val label = "WATCH $location standby=$standby screenOff=$pref"
            d.bootVisible()
            val pick = picks(VoiceOrigin.WATCH, location)
            assertEquals("$label: visible", pick, d.wake.listening)
            d.hide()
            d.advanceTo(d.now + 60_000)
            if (!pick) d.unselectedStaysSilent("$label: hidden")
            if (!pick) assertEquals("$label: nothing ever listened", 0, d.listenCount())
        }
    }

    // ── permission, reachability, busy ───────────────────────────────────────────────────────

    @Test
    fun `an unselected Watch schedules no retry while the Phone is unreachable, a selected one does`() {
        for (location in SEL_LOCATIONS) {
            val d = WatchDev(pref = true, master = true, mode = location)
            d.w.reachable = false
            d.boot()
            val pick = picks(VoiceOrigin.WATCH, location)
            if (pick) assertTrue("$location: a blocked retry waits for the Phone", d.rearmAt != null) else {
                d.unselectedStaysSilent("$location: unreachable Phone", 0)
            }
            d.reachability(true)
            d.advanceTo(d.now + 1_000)
            if (!pick) d.unselectedStaysSilent("$location: the Phone is back", 0)
        }
    }

    @Test
    fun `permission granted, busy ending and playback never wake an unselected device`() {
        for (device in SEL_DEVICES) {
            val d = listenDev(device, WakeLocation.OFF, standby = true, screenOff = true)
            if (d is PhoneDev) d.rig.permission = false else if (d is WatchDev) d.w.micPermission = false
            d.boot()
            if (d is PhoneDev) d.rig.permission = true else if (d is WatchDev) d.w.micPermission = true
            d.busy(true); d.busy(false); d.idle(); d.playbackBusy(); d.eligibility()
            d.advanceTo(d.now + HOUR)
            d.unselectedStaysSilent("$device", 0)
        }
    }

    // ── deselect tears the idle loop down ────────────────────────────────────────────────────

    private enum class State { IDLE_LISTEN, QUIET_GAP, FAILURE_BACKOFF, PARTIAL_NOT_ACCEPTED, BLOCKED_RETRY }

    private fun reach(device: VoiceOrigin, state: State): Dev {
        val d = listenDev(device, WakeLocation.BOTH, standby = true, screenOff = true)
        if (state == State.BLOCKED_RETRY) (d as WatchDev).w.reachable = false
        d.boot()
        when (state) {
            State.IDLE_LISTEN -> assertTrue("$device: listening", d.wake.listening)
            State.QUIET_GAP -> d.error(7)
            State.FAILURE_BACKOFF -> d.error(5)
            State.PARTIAL_NOT_ACCEPTED -> d.heard("루미", final = false)
            State.BLOCKED_RETRY -> assertTrue("blocked retry waits", d.rearmAt != null)
        }
        return d
    }

    @Test
    fun `deselecting a device in any idle state tears the loop down and nothing stale brings it back`() {
        for (device in SEL_DEVICES) for (state in State.values()) {
            if (state == State.BLOCKED_RETRY && device == VoiceOrigin.PHONE) continue
            for (target in excludingOf(device)) {
                val d = reach(device, state)
                val label = "$device $state -> $target"
                val before = d.listenCount()
                d.listenOn(target)
                d.assertDark("$label: deselected")
                assertEquals("$label: nothing reopened", before, d.listenCount())
                d.staleRearm(); d.replaySettings(); d.eligibility(); d.idle(); d.error(5); d.error(7)
                d.heard("루미 안녕하세요"); d.heard("루미", final = false)
                (d as? WatchDev)?.reachability(true)
                d.advanceTo(d.now + HOUR)
                d.assertDark("$label: an hour later, after stale callbacks")
                assertEquals("$label: stale callbacks reopened nothing", before, d.listenCount())
                assertTrue("$label: no stale callback admitted a wake command: ${d.sent()}", d.sent().isEmpty())
                assertEquals("$label: no recording", 0, d.captured())
            }
        }
    }

    @Test
    fun `flipping the standby switch or the screen-off flag of a deselected device reacquires nothing`() {
        for (device in SEL_DEVICES) {
            val d = reach(device, State.IDLE_LISTEN)
            d.listenOn(WakeLocation.OFF)
            val before = d.listenCount()
            d.standbyFlag(false); d.standbyFlag(true); d.screenOffPref(false); d.screenOffPref(true)
            d.deactivate(); d.advanceTo(d.now + 60_000); d.activate()
            d.advanceTo(d.now + HOUR)
            d.unselectedStaysSilent("$device flags flipped while deselected", before)
        }
    }

    // ── what survives a deselect ─────────────────────────────────────────────────────────────

    @Test
    fun `a wake request accepted before the deselect is recorded and sent, then nothing listens again`() {
        for (device in SEL_DEVICES) {
            val d = reach(device, State.IDLE_LISTEN)
            d.heard("루미")
            d.handoffDue()
            assertEquals("$device: the recording started", 1, d.captured())
            d.listenOn(WakeLocation.OFF)
            assertFalse("$device: the accepted recording was not cut by the deselect", d.captureCancelled())
            assertFalse("$device: the deselect stopped the idle listening", d.wake.listening)
            assertFalse("$device: no LISTEN hold", d.listenHold())
            d.captureEnded()
            d.advanceTo(d.now + HOUR)
            d.assertDark("$device: after the recording ended")
            assertFalse("$device: the recording still counts as handed over, not cancelled", d.captureCancelled())
            assertEquals("$device: exactly one window, the one that heard the phrase", 1, d.listenCount())
        }
    }

    @Test
    fun `a request the recognizer heard in full before the deselect stays sent`() {
        for (device in SEL_DEVICES) {
            val d = reach(device, State.IDLE_LISTEN)
            d.heard("루미 안녕하세요")
            assertEquals("$device: sent", 1, d.sent().size)
            d.listenOn(WakeLocation.OFF)
            assertEquals("$device: still sent once", 1, d.sent().size)
            d.advanceTo(d.now + HOUR)
            d.assertDark("$device: after")
            assertEquals("$device: one window", 1, d.listenCount())
        }
    }

    @Test
    fun `a manual recording is not touched by a deselect`() {
        val phone = listenDev(VoiceOrigin.PHONE, WakeLocation.BOTH, standby = true, screenOff = true) as PhoneDev
        phone.boot()
        phone.rig.calls.clear()
        phone.rig.recording = true
        phone.listenOn(WakeLocation.OFF)
        assertTrue("phone: the manual recording goes on", phone.rig.recording)
        assertFalse("phone: the host was not told to cancel a recording", phone.rig.calls.any { it.startsWith("cancel_capture") })
        assertFalse("phone: the session is not stopped", phone.rig.calls.contains("service_stop"))
        val watch = listenDev(VoiceOrigin.WATCH, WakeLocation.BOTH, standby = true, screenOff = true) as WatchDev
        watch.boot()
        watch.w.log.clear()
        watch.w.pttRecording = true
        watch.listenOn(WakeLocation.OFF)
        assertTrue("watch: push-to-talk goes on", watch.w.pttRecording)
        assertFalse("watch: no capture was cancelled", watch.w.log.any { it.startsWith("cancel_capture") })
        assertFalse("watch: the session is not stopped", watch.w.log.contains("stopService"))
    }

    // ── the five-second epoch across a re-select ─────────────────────────────────────────────

    @Test
    fun `re-selecting after the five seconds are spent opens nothing, and the same activation never gets a second budget`() {
        for (device in SEL_DEVICES) {
            val d = listenDev(device, WakeLocation.BOTH, standby = true, screenOff = false)
            d.boot()
            val t0 = d.now
            d.advanceTo(t0 + BUDGET)
            d.assertDark("$device: spent")
            d.advanceTo(t0 + 6_000)
            d.listenOn(WakeLocation.OFF)
            d.advanceTo(t0 + 10_000)
            d.listenOn(WakeLocation.BOTH)
            d.assertDark("$device: re-selected in the same epoch")
            d.replaySettings(); d.eligibility(); d.idle(); d.staleRearm()
            d.advanceTo(t0 + HOUR)
            d.assertDark("$device: an hour later")
            assertEquals("$device: one window in the whole epoch", 1, d.windows.count { it.first >= t0 })
        }
    }

    @Test
    fun `re-selecting inside the budget listens only for what is left of it`() {
        for (device in SEL_DEVICES) {
            val d = listenDev(device, WakeLocation.BOTH, standby = true, screenOff = false)
            d.boot()
            val t0 = d.now
            d.advanceTo(t0 + 2_000)
            d.listenOn(WakeLocation.OFF)
            d.assertDark("$device: deselected inside the budget")
            d.advanceTo(t0 + 3_000)
            d.listenOn(WakeLocation.BOTH)
            d.assertListeningUntil(t0 + BUDGET, "$device: re-selected, the remaining time only")
            d.advanceTo(t0 + BUDGET)
            d.assertDark("$device: spent")
        }
    }

    @Test
    fun `a screen activation that happened while the device was deselected opens one budget that a later select continues`() {
        for (device in SEL_DEVICES) {
            val d = listenDev(device, WakeLocation.OFF, standby = true, screenOff = false)
            d.boot()
            d.deactivate()
            d.advanceTo(d.now + 60_000)
            d.activate()
            val t = d.now
            d.unselectedStaysSilent("$device: activation while deselected", 0)
            d.advanceTo(t + 1_000)
            d.listenOn(selectedOnly(device))
            d.assertListeningUntil(t + BUDGET, "$device: selected inside that activation's budget")
            d.advanceTo(t + BUDGET)
            d.assertDark("$device: spent")
            d.advanceTo(t + 8_000)
            d.listenOn(WakeLocation.OFF)
            d.advanceTo(t + 9_000)
            d.listenOn(WakeLocation.BOTH)
            d.assertDark("$device: re-select after the budget")
            d.deactivate(); d.advanceTo(d.now + 30_000); d.activate()
            d.assertListeningUntil(d.now + BUDGET, "$device: a genuinely new activation opens one new budget")
        }
    }

    @Test
    fun `selecting a device after its screen-on budget expired opens nothing until the next activation`() {
        for (device in SEL_DEVICES) {
            val d = listenDev(device, WakeLocation.OFF, standby = true, screenOff = false)
            d.boot()
            d.advanceTo(d.now + 20_000)
            d.listenOn(selectedOnly(device))
            d.assertDark("$device: selected after the budget")
            assertEquals("$device: nothing listened", 0, d.listenCount())
        }
    }

    // ── the other device is independent ──────────────────────────────────────────────────────

    @Test
    fun `each device follows Listen on alone and the other device's flags change nothing`() {
        for (location in SEL_LOCATIONS) {
            val phone = listenDev(VoiceOrigin.PHONE, location, standby = true, screenOff = false)
            val watch = listenDev(VoiceOrigin.WATCH, location, standby = true, screenOff = false)
            phone.boot(); watch.boot()
            assertEquals("$location: phone", picks(VoiceOrigin.PHONE, location), phone.wake.listening)
            assertEquals("$location: watch", picks(VoiceOrigin.WATCH, location), watch.wake.listening)
            watch.standbyFlag(false); watch.screenOffPref(true); watch.standbyFlag(true)
            assertEquals("$location: the watch flags never moved the phone", picks(VoiceOrigin.PHONE, location), phone.wake.listening)
            phone.standbyFlag(false); phone.screenOffPref(true); phone.standbyFlag(true)
            assertFalse("$location: the phone flags never made the watch listen when it is not selected", !picks(VoiceOrigin.WATCH, location) && watch.wake.listening)
            if (!picks(VoiceOrigin.PHONE, location)) phone.unselectedStaysSilent("$location: phone", 0)
            if (!picks(VoiceOrigin.WATCH, location)) watch.unselectedStaysSilent("$location: watch", 0)
        }
    }

    @Test
    fun `Listen on Off disables new wake recognition on both SEL_DEVICES at once`() {
        for (device in SEL_DEVICES) {
            val d = reach(device, State.IDLE_LISTEN)
            d.listenOn(WakeLocation.OFF)
            d.assertDark("$device: Off")
            d.advanceTo(d.now + HOUR)
            d.assertDark("$device: Off, an hour later")
        }
    }
}
