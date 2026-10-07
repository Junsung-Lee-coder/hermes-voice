package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.background.BackgroundNotice
import com.rumi.hermesvoice.core.background.HoldReason
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.wake.ClaimVerdict
import com.rumi.hermesvoice.core.wake.ContinuousWakePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * R2 OFF-timer contract, through the real [com.rumi.hermesvoice.core.background.WatchVoiceCoordinator], wake flow, presence,
 * session and holds composed by [ComposedWatch] (only the platform is fake). Turning the Watch standby OFF promptly leaves no idle
 * recognizer window, no LISTEN/HANDOFF hold and no pending re-arm retry timer, while a manual push-to-talk or accepted hands-free
 * recording, its claim and its CAPTURE hold are untouched. OFF is not Stop.
 */
class OffTimerContractTest {
    private enum class Gap { QUIET_TIMEOUT, FAILURE_BACKOFF, BLOCKED_UNREACHABLE, COOLDOWN }

    /** Drives an armed session whose window is open into a pending idle timer of [gap]; returns that timer's delay. */
    private fun ComposedWatch.pendingTimer(gap: Gap): Long {
        assertTrue("a window is open before the gap", windowOpen)
        when (gap) {
            Gap.QUIET_TIMEOUT -> windowTimeout()
            Gap.FAILURE_BACKOFF -> { wake.onError(wake.generation, 5); drain() }
            Gap.BLOCKED_UNREACHABLE -> { windowTimeout(); reachable = false; rearmDue() }
            Gap.COOLDOWN -> { windowTimeout(); cooldownUntil = now + 4_000L; rearmDue() }
        }
        val delay = rearmIn
        assertNotNull("positive control: the $gap timer is really pending before OFF", delay)
        assertFalse("the gap holds no window", windowOpen)
        when (gap) {
            Gap.QUIET_TIMEOUT -> assertEquals(ContinuousWakePolicy.REARM_MS, delay)
            Gap.FAILURE_BACKOFF -> assertEquals(ContinuousWakePolicy.FIRST_BACKOFF_MS, delay)
            Gap.BLOCKED_UNREACHABLE -> assertEquals(ContinuousWakePolicy.BLOCKED_RETRY_MS, delay)
            Gap.COOLDOWN -> assertTrue("cooldown wait is positive: $delay", delay!! > ContinuousWakePolicy.COOLDOWN_MARGIN_MS)
        }
        return delay!!
    }

    /** A manual push-to-talk begins as the runtime does: the recorder's own CAPTURE hold, then the coordinator is told it is busy. */
    private fun ComposedWatch.pttStarts() {
        pttRecording = true
        busy = true
        holds.acquire(HoldReason.CAPTURE)
        coordinator.onBusy(); drain()
    }

    private fun ComposedWatch.pttEnds() {
        pttRecording = false
        busy = false
        holds.release(HoldReason.CAPTURE)
        coordinator.onIdle(); drain()
    }

    private fun ComposedWatch.assertCaptureUntouched(since: Int, why: String) {
        assertTrue("$why: recording still on", pttRecording || capturing)
        assertTrue("$why: no capture was cancelled and the service never stopped: ${log.drop(since)}",
            log.drop(since).none { it.startsWith("cancel_capture") || it == "stopService" })
        assertEquals("$why: the microphone type stays while recording", "microphone|mediaPlayback", serviceType)
        assertTrue("$why: the recorder's CAPTURE hold is kept", HoldReason.CAPTURE in held())
    }

    private fun ComposedWatch.assertIdleListeningGone(why: String) {
        assertNull("$why: no retry alarm/timer remains", rearmIn)
        assertFalse("$why: no listening window", windowOpen)
        assertFalse("$why: no LISTEN hold", HoldReason.LISTEN in held())
        assertFalse("$why: no HANDOFF hold", HoldReason.HANDOFF in held())
        assertNull("$why: no handoff timer", handoffIn)
    }

    /** The alarm that was already in flight when OFF arrived (and a repeat): it must open nothing and schedule nothing. */
    private fun ComposedWatch.lateDueOpensNothing(why: String) {
        val listensBefore = listens()
        repeat(2) { coordinator.onRearmDue(); drain() }
        assertEquals("$why: a late alarm never opens a window", listensBefore, listens())
        assertIdleListeningGap(why)
    }

    private fun ComposedWatch.assertIdleListeningGap(why: String) {
        assertNull("$why: a late alarm leaves no new retry", rearmIn)
        assertFalse("$why: a late alarm opens no window", windowOpen)
        assertFalse("$why: no LISTEN hold after a late alarm", HoldReason.LISTEN in held())
    }

    // ── 1. a really pending gap/retry + a manual recording + OFF (hidden app) ────────────────

    private fun hiddenGapPttOff(gap: Gap, location: WakeLocation, watchStandby: Boolean = true, phoneStandby: Boolean = false): ComposedWatch {
        val w = ComposedWatch(location, watchStandby = watchStandby, phoneStandby = phoneStandby)
        w.show(); w.start(); w.hide()
        w.pendingTimer(gap)
        w.pttStarts()
        assertNotNull("positive control: the timer survives the recording starting", w.rearmIn)
        val since = w.log.size
        w.standbyChange(watch = false)
        w.assertCaptureUntouched(since, "standby OFF, $gap, hidden, push-to-talk")
        w.assertIdleListeningGone("standby OFF, $gap, hidden, push-to-talk")
        w.lateDueOpensNothing("standby OFF, $gap, hidden, push-to-talk")
        w.assertCaptureUntouched(since, "after the late alarm")
        w.pttEnds()
        w.assertIdleListeningGone("recording ended after OFF")
        assertEquals("only now is the microphone type given back", "mediaPlayback", w.serviceType)
        assertTrue(w.held().isEmpty())
        assertEquals(BackgroundNotice.RUNNING, w.notice)
        w.lateDueOpensNothing("after the recording ended")
        return w
    }

    @Test fun `hidden, a pending quiet gap does not survive standby OFF while push-to-talk records`() { hiddenGapPttOff(Gap.QUIET_TIMEOUT, WakeLocation.OFF) }
    @Test fun `hidden, a pending 1 s failure retry does not survive standby OFF while push-to-talk records`() { hiddenGapPttOff(Gap.FAILURE_BACKOFF, WakeLocation.OFF) }
    @Test fun `hidden, a pending blocked retry does not survive standby OFF while push-to-talk records`() { hiddenGapPttOff(Gap.BLOCKED_UNREACHABLE, WakeLocation.OFF) }
    @Test fun `hidden, a pending cooldown re-arm does not survive standby OFF while push-to-talk records`() { hiddenGapPttOff(Gap.COOLDOWN, WakeLocation.OFF) }
    @Test fun `hidden with the foreground location on the Watch, a pending gap does not survive standby OFF while push-to-talk records`() { hiddenGapPttOff(Gap.QUIET_TIMEOUT, WakeLocation.WATCH) }
    @Test fun `hidden with both switches on and the location Both, OFF on the Watch alone cancels the Watch gap and keeps the recording`() { hiddenGapPttOff(Gap.QUIET_TIMEOUT, WakeLocation.BOTH, phoneStandby = true) }

    // ── 2. the selected-foreground window ends into a gap, a recording starts, the app is hidden, then OFF ─

    @Test fun `a foreground window's gap, a push-to-talk started before it fires, the app hidden, then OFF leaves no timer`() {
        val w = ComposedWatch(WakeLocation.WATCH, watchStandby = true)
        w.show(); w.start()
        w.pendingTimer(Gap.QUIET_TIMEOUT)
        w.pttStarts()
        w.hide()
        assertNotNull("positive control: the timer is still pending when the app is hidden", w.rearmIn)
        val since = w.log.size
        w.standbyChange(watch = false)
        w.assertCaptureUntouched(since, "foreground gap, then hidden, then OFF")
        w.assertIdleListeningGone("foreground gap, then hidden, then OFF")
        w.lateDueOpensNothing("foreground gap, then hidden, then OFF")
        w.pttEnds()
        w.assertIdleListeningGone("recording ended")
        assertEquals("mediaPlayback", w.serviceType)
        assertTrue(w.held().isEmpty())
    }

    @Test fun `a foreground window's failure retry, a push-to-talk, the app hidden, then OFF leaves no timer`() {
        val w = ComposedWatch(WakeLocation.WATCH, watchStandby = true)
        w.show(); w.start()
        w.pendingTimer(Gap.FAILURE_BACKOFF)
        w.pttStarts(); w.hide()
        val since = w.log.size
        w.standbyChange(watch = false)
        w.assertCaptureUntouched(since, "foreground failure retry, hidden, OFF")
        w.assertIdleListeningGone("foreground failure retry, hidden, OFF")
        w.lateDueOpensNothing("foreground failure retry, hidden, OFF")
    }

    // ── 3. screen off distinguishes visible && screenOn from the flags ───────────────────────

    @Test fun `screen off over the visible app, a pending gap and a push-to-talk, OFF leaves no timer (visible stays true)`() {
        val w = ComposedWatch(WakeLocation.WATCH, watchStandby = true)
        w.show(); w.start()
        w.pendingTimer(Gap.QUIET_TIMEOUT)
        w.pttStarts()
        w.screenOn = false; w.coordinator.onScreenOff(); w.drain()
        assertTrue("screen off does not make the app hidden", w.coordinator.presence.visible)
        assertNotNull("positive control: the timer is pending with the screen off", w.rearmIn)
        val since = w.log.size
        w.standbyChange(watch = false)
        assertTrue("recording still on", w.pttRecording)
        assertTrue("no capture cancelled and no stop: ${w.log.drop(since)}", w.log.drop(since).none { it.startsWith("cancel_capture") || it == "stopService" })
        assertTrue("CAPTURE hold kept", HoldReason.CAPTURE in w.held())
        w.assertIdleListeningGone("screen off over the visible app, OFF")
        println("OBSERVED screen-off-visible OFF with push-to-talk: serviceType=${w.serviceType}")
        w.lateDueOpensNothing("screen off over the visible app, OFF")
    }

    @Test fun `hidden and screen off, a pending gap and a push-to-talk, OFF leaves no timer`() {
        val w = ComposedWatch(WakeLocation.OFF, watchStandby = true)
        w.show(); w.start(); w.hide()
        w.screenOn = false; w.coordinator.onScreenOff(); w.drain()
        w.pendingTimer(Gap.QUIET_TIMEOUT)
        w.pttStarts()
        val since = w.log.size
        w.standbyChange(watch = false)
        w.assertCaptureUntouched(since, "hidden and screen off, OFF")
        w.assertIdleListeningGone("hidden and screen off, OFF")
        w.lateDueOpensNothing("hidden and screen off, OFF")
    }

    @Test fun `screen on again after OFF while hidden-armed and recording does not bring a timer back`() {
        val w = ComposedWatch(WakeLocation.OFF, watchStandby = true)
        w.show(); w.start(); w.hide()
        w.screenOn = false; w.coordinator.onScreenOff(); w.drain()
        w.pendingTimer(Gap.QUIET_TIMEOUT)
        w.pttStarts()
        w.standbyChange(watch = false)
        w.screenOn = true; w.coordinator.onScreenOn(); w.drain()
        w.assertIdleListeningGone("screen on after OFF")
        w.pttEnds()
        w.assertIdleListeningGone("recording ended after screen on")
    }

    // ── 4. repeated OFF and the other entry (settings events) ────────────────────────────────

    @Test fun `repeated OFF events and unrelated settings revisions keep the recording and add no timer`() {
        val w = ComposedWatch(WakeLocation.OFF, watchStandby = true)
        w.show(); w.start(); w.hide()
        w.pendingTimer(Gap.QUIET_TIMEOUT)
        w.pttStarts()
        val since = w.log.size
        repeat(3) { w.standbyChange(watch = false) }
        repeat(3) { w.coordinator.onEligibilityChanged(); w.drain() }
        w.assertCaptureUntouched(since, "repeated OFF")
        w.assertIdleListeningGone("repeated OFF")
        w.lateDueOpensNothing("repeated OFF")
    }

    // ── 5. accepted hands-free recording and its claim (NB1 witness: no standby_off follow-up) ─

    @Test fun `OFF during an accepted hands-free recording schedules no retry, keeps the recording and never reports a standby_off close`() {
        val w = ComposedWatch(WakeLocation.OFF, watchStandby = true)
        w.show(); w.start(); w.hide()
        w.heard("루미"); w.handoffDue()
        assertTrue(w.capturing)
        assertNull(w.rearmIn)
        val since = w.log.size
        w.standbyChange(watch = false)
        w.assertCaptureUntouched(since, "accepted hands-free, OFF")
        w.assertIdleListeningGone("accepted hands-free, OFF")
        assertTrue("no standby_off close reached the host: ${w.log.drop(since)}", w.log.drop(since).none { it == "closed:standby_off" })
        w.lateDueOpensNothing("accepted hands-free, OFF")
        w.capturing = false; w.holds.release(HoldReason.CAPTURE); w.wake.onRequestCaptureEnded(sent = true)
        w.coordinator.onIdle(); w.drain()
        w.assertIdleListeningGone("accepted hands-free ended")
        assertEquals("mediaPlayback", w.serviceType)
    }

    @Test fun `OFF during an arbitrated accepted recording keeps the claim, never releases it and schedules no retry`() {
        val w = ComposedWatch(WakeLocation.OFF, arbitrated = true, watchStandby = true, phoneStandby = true)
        w.show(); w.start(); w.hide()
        w.heard("루미")
        w.wake.onClaimVerdict("claim-1", ClaimVerdict.GRANTED); w.drain()
        w.handoffDue()
        assertTrue(w.capturing)
        val since = w.log.size
        val claimsBefore = w.claimsSent.toList()
        w.standbyChange(watch = false)
        w.assertCaptureUntouched(since, "arbitrated accepted recording, OFF")
        w.assertIdleListeningGone("arbitrated accepted recording, OFF")
        assertEquals("the accepted claim is not released or renewed by OFF", claimsBefore, w.claimsSent.toList())
        assertTrue(w.log.drop(since).none { it == "closed:standby_off" })
        w.lateDueOpensNothing("arbitrated accepted recording, OFF")
    }

    // ── 6. the two switches are independent; foreground location is a separate axis ──────────

    @Test fun `the Phone switch turning OFF leaves the Watch's pending gap and its listening alone`() {
        val w = ComposedWatch(WakeLocation.OFF, watchStandby = true, phoneStandby = true)
        w.show(); w.start(); w.hide()
        val delay = w.pendingTimer(Gap.QUIET_TIMEOUT)
        w.standbyChange(phone = false)
        assertEquals("the Watch's timer is the very same one", delay, w.rearmIn)
        w.rearmDue()
        assertTrue("the Watch still listens hidden", w.windowOpen)
        assertEquals(BackgroundNotice.LISTENING, w.notice)
    }

    @Test fun `the Watch switch turning OFF stops the Watch whatever the Phone switch says`() {
        val w = ComposedWatch(WakeLocation.OFF, watchStandby = true, phoneStandby = true)
        w.show(); w.start(); w.hide()
        w.pendingTimer(Gap.QUIET_TIMEOUT)
        w.standbyChange(watch = false)
        assertTrue(w.settings.phoneBackgroundWakeEnabled)
        w.assertIdleListeningGone("Watch OFF with Phone ON, no recording")
        assertEquals(BackgroundNotice.RUNNING, w.notice)
        w.lateDueOpensNothing("Watch OFF with Phone ON")
    }

    @Test fun `selected foreground with standby OFF keeps the foreground window and timers while on screen`() {
        val w = ComposedWatch(WakeLocation.WATCH, watchStandby = true)
        w.show(); w.start()
        w.standbyChange(watch = false)
        assertTrue("on screen the selected foreground wake goes on", w.windowOpen)
        assertFalse("the background session is disarmed", w.coordinator.presence.armed)
        w.hide()
        w.assertIdleListeningGone("selected foreground, standby OFF, hidden")
    }

    @Test fun `excluded foreground with standby ON stays excluded on screen and listens only hidden`() {
        val w = ComposedWatch(WakeLocation.OFF, watchStandby = true)
        w.show(); w.start()
        assertFalse("foreground excluded while on screen", w.windowOpen)
        w.hide()
        assertTrue("standby listens hidden", w.windowOpen)
        w.show()
        assertFalse("excluded again on screen", w.windowOpen)
    }

    // ── 7. policy-only adversarial coverage (not by itself a caller-path defect) ─────────────

    @Test fun `policy only, a standby_off close is the OFF decision and never a window failure that earns a backoff retry`() {
        assertNull(ContinuousWakePolicy.next("standby_off", 0))
        assertNull(ContinuousWakePolicy.next("standby_off", 5))
    }

    // ── 8. adjacent-path audit: no real event order leaves an idle timer/window under OFF ────

    private class Event(val name: String, val run: ComposedWatch.() -> Unit)

    private val events = listOf(
        Event("show") { screenOn = true; show() }, // a resumed activity means the screen is on (WakePresence.onActivityResumed)
        Event("hide") { hide() },
        Event("screenOff") { screenOn = false; coordinator.onScreenOff(); drain() },
        Event("screenOn") { screenOn = true; coordinator.onScreenOn(); drain() },
        Event("standbyOff") { standbyChange(watch = false) },
        Event("standbyOn") { standbyChange(watch = true) },
        Event("pttStart") { if (!pttRecording) { pttRecording = true; busy = true; coordinator.onBusy(); drain() } },
        Event("pttEnd") { if (pttRecording) { pttRecording = false; busy = false; coordinator.onIdle(); drain() } },
        Event("windowTimeout") { if (windowOpen) windowTimeout() },
        Event("windowError") { if (windowOpen) { wake.onError(wake.generation, 5); drain() } },
        Event("rearmDue") { rearmDue() },
        Event("lateRearmDue") { coordinator.onRearmDue(); drain() },
        Event("phoneAway") { reachabilityChanged(false) },
        Event("phoneBack") { reachabilityChanged(true) },
        Event("handsFree") { if (windowOpen) { heard("루미"); handoffDue() } },
        Event("handsFreeEnd") { if (capturing) { capturing = false; wake.onRequestCaptureEnded(sent = true); coordinator.onIdle(); drain() } },
    )

    private fun violation(w: ComposedWatch): String? {
        val standbyOff = !w.settings.watchBackgroundWakeEnabled
        val foregroundActive = w.coordinator.presence.visible && w.screenOn
        if (!standbyOff || foregroundActive) return null
        if (w.rearmIn != null) return "retry timer pending (${w.rearmIn} ms)"
        if (w.windowOpen) return "listening window open"
        if (HoldReason.LISTEN in w.held()) return "LISTEN hold"
        return null
    }

    private fun run(location: WakeLocation, script: List<Int>): String? {
        val w = ComposedWatch(location, watchStandby = true)
        w.show(); w.start()
        for ((i, e) in script.withIndex()) {
            val before = w.log.size
            try { events[e].run(w) } catch (t: Throwable) { return "exception at step $i ${events[e].name}: $t" }
            if (events[e].name == "standbyOff" && (w.pttRecording || w.capturing) &&
                w.log.drop(before).any { it.startsWith("cancel_capture") || it == "stopService" }) return "OFF cut a recording at step $i"
            violation(w)?.let { return "after step $i ${events[e].name}: $it" }
        }
        return null
    }

    @Test fun `no real event order leaves an idle retry timer, window or LISTEN hold under standby OFF (exhaustive depth 4, seeded depth 8)`() {
        val bad = mutableListOf<String>()
        var total = 0
        var failing = 0
        fun check(location: WakeLocation, script: List<Int>) {
            total++
            val v = run(location, script) ?: return
            failing++
            if (bad.size < 8) bad += "$location ${script.joinToString(" > ") { events[it].name }}: $v"
        }
        val n = events.size
        for (location in listOf(WakeLocation.OFF, WakeLocation.WATCH)) {
            for (length in 1..4) {
                val script = IntArray(length)
                var count = 1
                repeat(length) { count *= n }
                for (code in 0 until count) {
                    var c = code
                    for (i in 0 until length) { script[i] = c % n; c /= n }
                    check(location, script.toList())
                }
            }
            val random = Random(20261006)
            repeat(30_000) { check(location, List(8) { random.nextInt(n) }) }
        }
        println("OFF_TIMER_EXPLORATION total=$total failing=$failing")
        if (failing > 0) fail("$failing of $total event orders leave idle listening under OFF; first (shortest first): $bad")
    }
}
