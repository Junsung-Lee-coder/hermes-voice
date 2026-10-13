package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.background.BackgroundNotice
import com.rumi.hermesvoice.core.background.BackgroundText
import com.rumi.hermesvoice.core.background.HoldReason
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.wake.ClaimVerdict
import com.rumi.hermesvoice.core.wake.ContinuousWakePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Watch's "recognition while the screen is off" preference, through the real coordinator, wake flow, presence, session and
 * holds composed by [ComposedWatch] (only the platform is fake). A hidden Watch listens only when its own standby master is ON
 * and (its own screen is interactive OR its own screen-off preference is ON). Turning the screen off, or the preference off,
 * while the Watch is not eligible tears down the idle recognizer, LISTEN/HANDOFF holds and pending retry timers; recordings,
 * claims and the session are left alone. Screen off is never Stop and never bypasses "Listen on".
 */
class ScreenOffWatchPolicyTest {
    private enum class Gap { QUIET_TIMEOUT, FAILURE_BACKOFF, BLOCKED_UNREACHABLE, COOLDOWN }

    private fun hidden(
        pref: Boolean,
        master: Boolean = true,
        location: WakeLocation = WakeLocation.WATCH,
        phoneStandby: Boolean = false,
        arbitrated: Boolean = false,
    ) = ComposedWatch(location, arbitrated = arbitrated, watchStandby = master, phoneStandby = phoneStandby, watchScreenOff = pref)
        .apply { show(); start(); hide() }

    /** A quiet window ends: at its deadline in a continuous background, early (nothing matched) inside a screen activation's budget, where a gap after the deadline is never scheduled. */
    private fun ComposedWatch.quietClose() {
        if (wake.budgeted) { wake.onError(wake.generation, 7); drain() } else windowTimeout()
    }

    private fun ComposedWatch.pendingTimer(gap: Gap) {
        assertTrue("a window is open before the gap", windowOpen)
        when (gap) {
            Gap.QUIET_TIMEOUT -> quietClose()
            Gap.FAILURE_BACKOFF -> { wake.onError(wake.generation, 5); drain() }
            Gap.BLOCKED_UNREACHABLE -> { quietClose(); reachable = false; rearmDue() }
            Gap.COOLDOWN -> { quietClose(); cooldownUntil = now + 4_000L; rearmDue() }
        }
        assertNotNull("positive control: the $gap timer is really pending", rearmIn)
        assertFalse(windowOpen)
    }

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

    private fun ComposedWatch.assertListening(why: String) {
        assertTrue("$why: window open", windowOpen)
        assertTrue("$why: LISTEN hold", HoldReason.LISTEN in held())
        assertEquals("$why: says listening", BackgroundNotice.LISTENING, notice)
        assertTrue("$why: armed", coordinator.presence.armed)
    }

    /** The idle part of the wake flow is gone while the session itself (the microphone type, the notification) is not stopped. */
    private fun ComposedWatch.assertIdleGone(why: String) {
        assertNull("$why: no retry alarm/timer remains", rearmIn)
        assertFalse("$why: no listening window", windowOpen)
        assertFalse("$why: no LISTEN hold", HoldReason.LISTEN in held())
        assertFalse("$why: no HANDOFF hold", HoldReason.HANDOFF in held())
        assertNull("$why: no handoff timer", handoffIn)
        assertFalse("$why: this is not Stop: ${log}", log.contains("stopService"))
        assertTrue("$why: the session goes on", status.session.running)
    }

    private fun ComposedWatch.lateDueOpensNothing(why: String) {
        val before = listens()
        repeat(2) { coordinator.onRearmDue(); drain() }
        assertEquals("$why: a late alarm opens nothing", before, listens())
        assertNull("$why: a late alarm schedules nothing", rearmIn)
        assertFalse("$why: a late alarm holds nothing", HoldReason.LISTEN in held())
    }

    // ── the policy table ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a hidden Watch listens exactly when its master is on and the screen is interactive or its preference is on`() {
        for (master in listOf(true, false)) for (pref in listOf(true, false)) for (screenOff in listOf(false, true)) {
            val w = hidden(pref, master)
            if (screenOff) w.screenOff()
            val expected = master && (!screenOff || pref)
            val label = "master=$master pref=$pref screenOff=$screenOff"
            assertEquals("$label: window", expected, w.windowOpen)
            assertEquals("$label: LISTEN hold", expected, HoldReason.LISTEN in w.held())
            repeat(3) { if (w.windowOpen) w.windowTimeout(); w.rearmDue() }
            // Without the preference the one budget of the screen activation is spent after these cycles; with it the loop is continuous.
            val continuous = expected && (screenOff || pref)
            assertEquals("$label: window after rearm cycles", continuous, w.windowOpen)
            if (expected && !continuous) assertNull("$label: the spent budget leaves no timer", w.rearmIn)
            if (!expected) {
                assertNull("$label: no timer", w.rearmIn)
                assertFalse("$label: no window", w.windowOpen)
            }
            if (!master) {
                assertEquals("$label: a screen preference never arms the microphone", 0, w.micRequests())
                assertFalse(label, w.coordinator.presence.armed)
            }
        }
    }

    @Test
    fun `the same table is reached by changing the preference while the screen is already in that state`() {
        for (master in listOf(true, false)) for (screenOff in listOf(false, true)) for (prefAfter in listOf(true, false)) {
            val w = hidden(pref = !prefAfter, master = master)
            if (screenOff) w.screenOff()
            w.screenOffPreference(watch = prefAfter)
            val expected = master && (!screenOff || prefAfter)
            val label = "master=$master screenOff=$screenOff prefAfter=$prefAfter"
            assertEquals("$label: window", expected, w.windowOpen)
            assertEquals("$label: LISTEN hold", expected, HoldReason.LISTEN in w.held())
            if (!expected) assertNull("$label: no timer", w.rearmIn)
        }
    }

    @Test
    fun `the Phone preference and the Phone screen never decide the Watch`() {
        val w = hidden(pref = false)
        w.screenOffPreference(phone = true)
        w.screenOff()
        assertFalse("the Phone's preference does not let a screen-off Watch listen", w.windowOpen)
        w.screenOffPreference(phone = false, watch = true)
        w.assertListening("only the Watch's own preference does")
        w.screenOffPreference(phone = true, watch = false)
        assertFalse(w.windowOpen)
    }

    // ── prompt teardown, preserving the session ──────────────────────────────────────────────

    @Test
    fun `the screen turning off with the preference off tears down the idle window, hold and timers but not the session`() {
        val w = hidden(pref = false)
        w.assertListening("screen on")
        val requests = w.micRequests()
        w.screenOff()
        w.assertIdleGone("screen off, preference off")
        assertTrue("armed state is kept so the screen coming back needs no visit", w.coordinator.presence.armed)
        assertEquals("microphone|mediaPlayback", w.serviceType)
        assertEquals(requests, w.micRequests())
        assertEquals("the status says it waits for the screen, not that it listens", "WAITING_FOR_SCREEN", w.status.loop.name)
        assertTrue(BackgroundText.watchNotification(w.status.session, w.status.loop)!!.contains("screen"))
        assertTrue(BackgroundText.watchLabel(w.status.session, w.status.loop).contains("screen"))
        w.lateDueOpensNothing("screen off")
    }

    @Test
    fun `every kind of pending idle timer is cancelled when the screen turns off with the preference off`() {
        for (gap in Gap.values()) {
            val w = hidden(pref = false)
            if (gap == Gap.BLOCKED_UNREACHABLE) {
                // A 15 s blocked retry would land after the 5 s budget: nothing is scheduled at all.
                w.quietClose(); w.reachable = false; w.rearmDue()
                assertNull("an unreachable Phone schedules no retry past the budget", w.rearmIn)
                continue
            }
            w.pendingTimer(gap)
            w.screenOff()
            w.assertIdleGone("screen off during $gap")
            w.lateDueOpensNothing("screen off during $gap")
            w.screenOnEvent()
            assertTrue("the screen coming back lets it listen again after $gap", w.windowOpen || w.rearmIn != null)
        }
    }

    @Test
    fun `turning the preference off while the screen is already off tears down the same way`() {
        val w = hidden(pref = true)
        w.screenOff()
        w.assertListening("preference on, screen off")
        w.screenOffPreference(watch = false)
        w.assertIdleGone("preference turned off while the screen is off")
        w.lateDueOpensNothing("preference turned off while the screen is off")
        assertTrue(w.coordinator.presence.armed)
    }

    @Test
    fun `turning the preference off while screen off cancels a pending retry of every kind`() {
        for (gap in Gap.values()) {
            val w = hidden(pref = true)
            w.screenOff()
            w.pendingTimer(gap)
            w.screenOffPreference(watch = false)
            w.assertIdleGone("preference off during $gap")
            w.lateDueOpensNothing("preference off during $gap")
        }
    }

    @Test
    fun `a wake phrase heard but not yet accepted is dropped by the screen teardown`() {
        val w = hidden(pref = false)
        w.heard("루미")
        assertNotNull(w.handoffIn)
        w.screenOff()
        w.assertIdleGone("screen off with a pending handoff")
        w.handoffDue()
        assertFalse("a late handoff never starts a recording", w.capturing)
        assertFalse(w.log.contains("capture"))
    }

    @Test
    fun `late callbacks of the old window cannot resurrect listening`() {
        val w = hidden(pref = false)
        val old = w.wake.generation
        w.screenOff()
        val listens = w.listens()
        w.wake.onResults(old, listOf("루미"), true); w.drain()
        w.wake.onTimer(); w.drain()
        w.wake.onError(old, 5); w.drain()
        w.handoffDue()
        w.coordinator.onRearmDue(); w.drain()
        assertEquals(listens, w.listens())
        w.assertIdleGone("late callbacks")
        assertFalse(w.capturing)
        assertTrue(w.claimsSent.isEmpty())
        assertFalse(w.log.any { it.startsWith("send:") })
    }

    // ── recordings, claims and the session are not touched ───────────────────────────────────

    @Test
    fun `an accepted hands-free recording survives the screen turning off and nothing listens afterwards until the screen is back`() {
        val w = hidden(pref = false)
        w.heard("루미"); w.handoffDue()
        assertTrue(w.capturing)
        val since = w.log.size
        w.screenOff()
        assertTrue("the recording goes on", w.capturing)
        assertTrue("no capture was cancelled and nothing stopped: ${w.log.drop(since)}", w.log.drop(since).none { it.startsWith("cancel_capture") || it == "stopService" })
        assertEquals("microphone|mediaPlayback", w.serviceType)
        assertEquals(setOf(HoldReason.CAPTURE), w.held())
        assertFalse(w.windowOpen)
        assertNull(w.rearmIn)
        w.capturing = false
        w.holds.release(HoldReason.CAPTURE)
        w.wake.onRequestCaptureEnded(sent = true)
        w.coordinator.onIdle(); w.drain()
        w.assertIdleGone("after the recording, screen still off")
        val requests = w.micRequests()
        w.screenOnEvent()
        w.assertListening("screen back on")
        assertEquals("no new service start", requests, w.micRequests())
    }

    @Test
    fun `a manual push-to-talk with a pending gap survives the screen turning off while the gap timer does not`() {
        val w = hidden(pref = false)
        w.pendingTimer(Gap.QUIET_TIMEOUT)
        w.pttStarts()
        assertNotNull("positive control: the timer survives the recording starting", w.rearmIn)
        val since = w.log.size
        w.screenOff()
        assertTrue(w.pttRecording)
        assertTrue(w.log.drop(since).none { it.startsWith("cancel_capture") || it == "stopService" })
        assertEquals("microphone|mediaPlayback", w.serviceType)
        assertTrue(HoldReason.CAPTURE in w.held())
        assertNull("the idle gap timer is gone", w.rearmIn)
        assertFalse(w.windowOpen)
        w.lateDueOpensNothing("recording under way")
        assertTrue(w.pttRecording)
        w.pttEnds()
        w.assertIdleGone("recording ended, screen still off")
        w.screenOnEvent()
        w.assertListening("screen back on")
    }

    @Test
    fun `an accepted claim and its recording are untouched by the screen turning off`() {
        val w = hidden(pref = false, location = WakeLocation.BOTH, phoneStandby = true, arbitrated = true)
        w.heard("루미")
        assertEquals(listOf("claim:claim-1"), w.claimsSent)
        w.wake.onClaimVerdict("claim-1", ClaimVerdict.GRANTED); w.drain()
        w.handoffDue()
        assertTrue(w.capturing)
        val since = w.log.size
        w.screenOff()
        assertTrue(w.capturing)
        assertTrue(w.log.drop(since).none { it.startsWith("cancel_capture") })
        assertTrue("the claim is not released: ${w.claimsSent}", w.claimsSent.none { it.startsWith("release:") })
        assertTrue(HoldReason.CAPTURE in w.held())
        assertEquals("microphone|mediaPlayback", w.serviceType)
    }

    // ── re-enable and recovery ───────────────────────────────────────────────────────────────

    @Test
    fun `the screen coming back opens exactly one window and repeats are deduped`() {
        val w = hidden(pref = false)
        w.screenOff()
        val listens = w.listens()
        val requests = w.micRequests()
        w.screenOnEvent()
        w.assertListening("screen back on")
        assertEquals(listens + 1, w.listens())
        repeat(3) { w.screenOnEvent(); w.coordinator.onEligibilityChanged(); w.drain() }
        assertEquals("a repeated event never listens twice", listens + 1, w.listens())
        assertEquals(requests, w.micRequests())
        val acquires = w.lockLog.count { it.startsWith("acquire:LISTEN") }
        repeat(3) { w.screenOnEvent(); w.drain() }
        assertEquals("no repeat takes the LISTEN hold again", acquires, w.lockLog.count { it.startsWith("acquire:LISTEN") })
    }

    @Test
    fun `turning the preference on while the screen is off listens once and repeats are deduped`() {
        val w = hidden(pref = false)
        w.screenOff()
        w.assertIdleGone("denied")
        val listens = w.listens()
        val requests = w.micRequests()
        w.screenOffPreference(watch = true)
        w.assertListening("preference on")
        assertEquals(listens + 1, w.listens())
        repeat(3) { w.screenOffPreference(watch = true); w.coordinator.onEligibilityChanged(); w.drain() }
        assertEquals(listens + 1, w.listens())
        assertEquals("no new service start: the preference is a request, the visit's arming is reused", requests, w.micRequests())
    }

    @Test
    fun `the preference on without a legal armed session arms nothing, with or without a screen`() {
        val w = ComposedWatch(WakeLocation.WATCH, watchStandby = true, watchScreenOff = true)
        w.screenOn = false
        w.start()
        assertEquals("never armed from the background", 0, w.micRequests())
        assertFalse(w.windowOpen)
        assertFalse(w.coordinator.presence.armed)
        assertTrue("never says it listens", w.notice != BackgroundNotice.LISTENING)
        w.screenOff(); w.screenOnEvent(); w.screenOff()
        w.screenOffPreference(watch = true)
        w.coordinator.ensureDefault()
        assertEquals(0, w.micRequests())
        assertFalse(w.windowOpen)
        assertTrue(w.held().isEmpty())
        assertTrue("never says it listens", w.notice != BackgroundNotice.LISTENING)
    }

    @Test
    fun `the preference on never bypasses the microphone permission`() {
        val w = hidden(pref = true)
        w.micPermission = false
        w.screenOff()
        w.windowTimeout(); w.rearmDue()
        val listens = w.listens()
        w.windowTimeout(); w.rearmDue()
        assertEquals(listens, w.listens())
        assertFalse(w.windowOpen)
    }

    // ── ambient / always-on display ──────────────────────────────────────────────────────────

    @Test
    fun `an eligible Watch keeps listening through the always-on display and ambient never stalls it`() {
        val w = hidden(pref = true)
        val requests = w.micRequests()
        val mark = w.log.size
        w.enterAmbient()
        w.assertListening("ambient with the preference on")
        repeat(4) {
            w.windowTimeout()
            assertNotNull(w.rearmIn)
            w.rearmDue()
            w.assertListening("ambient window $it")
            assertTrue("ambient is not a stall: ${w.log}", w.log.drop(mark).none { it.startsWith("blocked:") && !it.endsWith(":ALREADY_ARMED") })
        }
        assertEquals(BackgroundNotice.LISTENING, w.status.session.notice)
        assertEquals("no new service start", requests, w.micRequests())
        w.leaveAmbient()
        w.assertListening("screen interactive again")
    }

    @Test
    fun `an ineligible Watch in the always-on display stops listening and resumes when it is interactive again`() {
        val w = hidden(pref = false)
        w.enterAmbient()
        w.assertIdleGone("ambient with the preference off")
        w.lateDueOpensNothing("ambient")
        assertTrue(w.coordinator.presence.armed)
        w.leaveAmbient()
        w.assertListening("interactive again")
    }

    @Test
    fun `turning the preference on in the always-on display makes it listen, off in the always-on display makes it stop`() {
        val w = hidden(pref = false)
        w.enterAmbient()
        val mark = w.log.size
        w.screenOffPreference(watch = true)
        w.assertListening("ambient, preference on")
        assertTrue("ambient is not a stall: ${w.log}", w.log.drop(mark).none { it.startsWith("blocked:") && !it.endsWith(":ALREADY_ARMED") })
        w.screenOffPreference(watch = false)
        w.assertIdleGone("ambient, preference off")
    }

    // ── the foreground gate is never bypassed or misclassified ───────────────────────────────

    @Test
    fun `a visible interactive Watch the foreground location excludes does not listen whatever the preference says`() {
        for (pref in listOf(true, false)) {
            val w = ComposedWatch(WakeLocation.PHONE, watchStandby = true, watchScreenOff = pref)
            w.show(); w.start()
            assertFalse("pref=$pref: the foreground selector excludes the Watch", w.windowOpen)
            assertTrue(w.held().isEmpty())
            w.windowTimeout(); w.rearmDue(); w.screenOffPreference(watch = pref)
            assertFalse("pref=$pref: still excluded while interactive and on screen", w.windowOpen)
            assertTrue(w.held().isEmpty())
        }
    }

    @Test
    fun `a screen-off Watch that Listen on excludes listens neither with the screen on nor off, whatever its standby and preference say`() {
        for (pref in listOf(true, false)) {
            val w = ComposedWatch(WakeLocation.PHONE, watchStandby = true, watchScreenOff = pref)
            w.show(); w.start()
            w.screenOff()
            assertFalse("pref=$pref: screen off, not selected", w.windowOpen)
            w.screenOnEvent()
            assertFalse("pref=$pref: interactive again, still not selected", w.windowOpen)
            w.screenOffPreference(watch = !pref)
            assertFalse("pref=$pref: a preference change selects nothing", w.windowOpen)
            assertFalse(HoldReason.LISTEN in w.held())
            assertNull(w.rearmIn)
        }
    }

    @Test
    fun `a stale visible flag cannot make a screen-off Watch listen under the foreground location`() {
        val w = ComposedWatch(WakeLocation.WATCH, watchStandby = true, watchScreenOff = false)
        w.show(); w.start()
        assertTrue("on screen the foreground location listens", w.windowOpen)
        w.screenOn = false // the platform's fact; the visible flag has not been updated by any event yet
        w.windowTimeout(); w.rearmDue()
        assertFalse("the live screen is off and the preference is off: no foreground window", w.windowOpen)
        assertFalse(HoldReason.LISTEN in w.held())
        assertNull(w.rearmIn)
    }

    @Test
    fun `a stale visible flag with the screen off and the preference on follows the background rules`() {
        val w = ComposedWatch(WakeLocation.WATCH, watchStandby = true, watchScreenOff = true)
        w.show(); w.start()
        assertTrue("selected: the foreground listens on screen", w.windowOpen)
        w.screenOn = false
        w.screenOffPreference(watch = true)
        w.assertListening("screen off + master + preference: background eligibility")
    }

    // ── policy-level defence for the close reason ────────────────────────────────────────────

    @Test
    fun `a window closed by the screen while the Watch is still eligible schedules its next window instead of staying blocked`() {
        val w = hidden(pref = true)
        w.screenOff()
        w.wake.onScreenOff(); w.drain()
        assertNotNull("an eligible idle retry is not blocked by the close reason: ${w.log}", w.rearmIn)
        w.rearmDue()
        w.assertListening("the next window")
    }

    @Test
    fun `window after window the screen-off Watch keeps one idle gap without a hold`() {
        val w = hidden(pref = true)
        w.screenOff()
        repeat(20) {
            assertTrue("window $it", w.windowOpen && HoldReason.LISTEN in w.held())
            w.windowTimeout()
            assertTrue("gap $it holds nothing", w.held().isEmpty())
            assertEquals(ContinuousWakePolicy.REARM_MS, w.rearmIn)
            w.rearmDue()
        }
    }
}
