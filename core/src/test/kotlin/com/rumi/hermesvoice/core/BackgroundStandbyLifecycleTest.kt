package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.background.BackgroundNotice
import com.rumi.hermesvoice.core.background.BackgroundText
import com.rumi.hermesvoice.core.background.HoldReason
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.wake.ClaimVerdict
import com.rumi.hermesvoice.core.wake.ContinuousWakePolicy
import com.rumi.hermesvoice.core.wake.WakeContract
import com.rumi.hermesvoice.core.wake.WakeLoop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Watch's background standby switch, through the real coordinator, wake flow, presence, session and
 * holds with only the platform faked (see [ComposedWatch]). The switch decides whether a HIDDEN Watch may
 * wait for the wake phrase; it is not the session's Stop, and it never selects a device: "Listen on" does.
 */
class BackgroundStandbyLifecycleTest {
    private fun hiddenStandby(location: WakeLocation = WakeLocation.WATCH) = ComposedWatch(location, watchStandby = true).apply {
        show(); start(); hide()
    }

    // ── what the switch means ────────────────────────────────────────────────────────────────

    @Test
    fun `standby on listens hidden when Listen on selects the Watch, and never when it does not`() {
        val w = hiddenStandby()
        assertEquals(BackgroundNotice.LISTENING, w.notice)
        assertTrue(w.windowOpen)
        assertTrue(HoldReason.LISTEN in w.held())
        assertEquals(WakeContract.BACKGROUND_WINDOW_MS, w.wake.windowMs)
        for (location in listOf(WakeLocation.OFF, WakeLocation.PHONE)) {
            val excluded = hiddenStandby(location)
            assertFalse("$location: Listen on leaves the Watch out, standby adds nothing", excluded.windowOpen)
            assertTrue("$location: no hold", excluded.held().isEmpty())
            assertNull("$location: no timer", excluded.rearmIn)
            assertEquals("$location: no window was ever opened", 0, excluded.listens())
        }
    }

    @Test
    fun `standby off keeps the foreground-only wake of the location, which ends with the screen`() {
        val w = ComposedWatch(WakeLocation.WATCH, watchStandby = false)
        w.show()
        assertTrue("foreground wake still works on screen", w.windowOpen)
        w.start()
        assertEquals("the session runs for replies, it never arms the microphone", BackgroundNotice.RUNNING, w.notice)
        assertEquals(0, w.micRequests())
        assertFalse(w.coordinator.presence.armed)
        w.hide()
        assertFalse(w.windowOpen)
        assertTrue(w.held().isEmpty())
        assertNull(w.rearmIn)
        assertEquals("replies only", "mediaPlayback", w.serviceType)
    }

    @Test
    fun `the status of a session with standby off says standby is off, not that the wake phrase is off`() {
        val w = ComposedWatch(WakeLocation.WATCH, watchStandby = false)
        w.show(); w.start(); w.hide()
        val line = BackgroundText.watchNotification(w.status.session, w.status.loop)!!
        assertTrue(line, line.contains("standby"))
    }

    // ── OFF → ON → OFF while hidden ──────────────────────────────────────────────────────────

    @Test
    fun `hidden OFF releases the recognizer, timers and holds at once without stopping the session`() {
        val w = hiddenStandby()
        w.windowTimeout()
        assertNotNull(w.rearmIn)
        w.log.clear()
        w.standbyChange(watch = false)
        assertFalse(w.windowOpen)
        assertTrue("no standby hold is left", w.held().isEmpty())
        assertNull("no re-arm timer is left", w.rearmIn)
        assertNull(w.handoffIn)
        assertEquals("the microphone type is given back", "mediaPlayback", w.serviceType)
        assertFalse(w.log.contains("stopService"))
        assertTrue("the session itself goes on for replies", w.status.session.running)
        assertEquals(BackgroundNotice.RUNNING, w.notice)
        assertEquals(WakeLoop.OFF, w.status.loop)
        val listens = w.listens()
        w.coordinator.onRearmDue(); w.drain()
        assertFalse("a stale timer callback opens nothing", w.windowOpen)
        assertEquals(listens, w.listens())
    }

    @Test
    fun `hidden OFF then ON asks for nothing from the background and says to open the Watch, which arms at the next real visit`() {
        val w = hiddenStandby()
        w.standbyChange(watch = false)
        val requests = w.micRequests()
        w.standbyChange(watch = true)
        assertEquals("never armed from the background", requests, w.micRequests())
        assertFalse(w.windowOpen)
        assertEquals(BackgroundNotice.NEEDS_VISIBLE_TO_LISTEN, w.notice)
        assertEquals("mediaPlayback", w.serviceType)
        assertTrue(w.held().isEmpty())
        w.show()
        assertEquals(BackgroundNotice.LISTENING, w.notice)
        assertTrue("the visit arms the legal service", w.coordinator.presence.armed)
        // Migration: Listen on selects the Watch, so the visit listens on screen and hidden standby keeps listening.
        assertTrue("selected: the visit listens while the app is on screen", w.windowOpen)
        w.hide()
        assertTrue("hidden: the standby listens for the selected Watch", w.windowOpen)
        w.standbyChange(watch = false)
        assertTrue(w.held().isEmpty())
        assertFalse(w.windowOpen)
    }

    @Test
    fun `turning standby off drops a wake phrase heard but not yet accepted for recording`() {
        val w = hiddenStandby()
        w.heard("루미")
        assertNotNull(w.handoffIn)
        assertTrue(HoldReason.HANDOFF in w.held())
        w.standbyChange(watch = false)
        assertNull(w.handoffIn)
        assertTrue(w.held().isEmpty())
        assertFalse(w.capturing)
        assertFalse(w.log.contains("capture"))
        w.handoffDue()
        assertFalse("a late handoff timer never starts a recording", w.capturing)
    }

    @Test
    fun `turning standby off never cancels a hands-free recording already under way, the microphone type stays until it ends`() {
        val w = hiddenStandby()
        w.heard("루미")
        w.handoffDue()
        assertTrue(w.capturing)
        w.log.clear()
        w.standbyChange(watch = false)
        assertTrue("the recording goes on", w.capturing)
        assertFalse(w.log.any { it.startsWith("cancel_capture") })
        assertEquals("the microphone type is kept for the recording", "microphone|mediaPlayback", w.serviceType)
        assertFalse("no further listening window", w.windowOpen)
        assertNull(w.rearmIn)
        assertEquals(setOf(HoldReason.CAPTURE), w.held())
        // The recording ends (sent): only now is the standby given back, and nothing listens again.
        w.capturing = false
        w.holds.release(HoldReason.CAPTURE)
        w.wake.onRequestCaptureEnded(sent = true)
        w.coordinator.onIdle(); w.drain()
        assertEquals("mediaPlayback", w.serviceType)
        assertFalse(w.windowOpen)
        assertNull(w.rearmIn)
        assertTrue(w.held().isEmpty())
        assertEquals(BackgroundNotice.RUNNING, w.notice)
    }

    @Test
    fun `turning standby off never cancels an explicit push-to-talk recording`() {
        val w = hiddenStandby()
        w.pttRecording = true
        w.log.clear()
        w.standbyChange(watch = false)
        assertFalse(w.log.any { it.startsWith("cancel_capture") })
        assertEquals("microphone|mediaPlayback", w.serviceType)
        assertFalse(w.windowOpen)
        w.pttRecording = false
        w.coordinator.onIdle(); w.drain()
        assertEquals("mediaPlayback", w.serviceType)
        assertTrue(w.held().isEmpty())
    }

    @Test
    fun `turning standby off with the app on screen leaves the foreground window to the location`() {
        val w = ComposedWatch(WakeLocation.WATCH, watchStandby = true)
        w.show(); w.start()
        assertEquals(BackgroundNotice.LISTENING, w.notice)
        w.standbyChange(watch = false)
        assertFalse(w.coordinator.presence.armed)
        assertTrue("on screen the foreground wake of the location goes on", w.windowOpen)
        w.hide()
        assertFalse(w.windowOpen)
        assertTrue(w.held().isEmpty())
    }

    // ── a persisted OFF is never re-enabled ──────────────────────────────────────────────────

    @Test
    fun `a restart or reconnect with standby off never arms the microphone, whatever else happens`() {
        val w = ComposedWatch(WakeLocation.OFF, watchStandby = false)
        w.coordinator.onUserOpened()
        w.show()
        w.coordinator.ensureDefault()
        for (reachable in listOf(false, null, true)) w.reachabilityChanged(reachable)
        w.coordinator.onScreenOff(); w.coordinator.onScreenOn(); w.coordinator.onEligibilityChanged(); w.drain()
        w.hide(); w.show(); w.hide()
        assertEquals(0, w.micRequests())
        assertFalse(w.coordinator.presence.armed)
        assertFalse(w.windowOpen)
        assertTrue(w.held().isEmpty())
        assertNull(w.rearmIn)
    }

    // ── Both and arbitration ─────────────────────────────────────────────────────────────────

    @Test
    fun `when both devices may listen through their standby switches a wake episode is claimed when Listen on is Both`() {
        val w = ComposedWatch(WakeLocation.BOTH, arbitrated = true, watchStandby = true, phoneStandby = true)
        w.show(); w.start(); w.hide()
        w.heard("루미")
        assertEquals(listOf("claim:claim-1"), w.claimsSent)
        assertNull("nothing is recorded before the Phone grants the claim", w.handoffIn)
        w.wake.onClaimVerdict("claim-1", ClaimVerdict.GRANTED); w.drain()
        assertNotNull(w.handoffIn)
    }

    @Test
    fun `a lone listener needs no claim, so its request is never delayed by a Phone that is not listening`() {
        val w = ComposedWatch(WakeLocation.WATCH, arbitrated = true, watchStandby = true, phoneStandby = false)
        w.show(); w.start(); w.hide()
        w.heard("루미")
        assertTrue(w.claimsSent.isEmpty())
        assertNotNull(w.handoffIn)
    }

    @Test
    fun `turning the Phone standby on or off while the Watch holds a claim renews or fails it, never sends twice`() {
        val w = ComposedWatch(WakeLocation.BOTH, arbitrated = true, watchStandby = true, phoneStandby = true)
        w.show(); w.start(); w.hide()
        w.heard("루미")
        assertEquals(listOf("claim:claim-1"), w.claimsSent)
        w.standbyChange(phone = false)
        assertFalse("the episode that waited for a claim is not sent under other settings", w.log.any { it.startsWith("send:") })
        assertNull(w.handoffIn)
    }

    // ── idle work ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `window after window the idle gap holds no wake lock and only the open window and a handoff are ever held`() {
        val w = hiddenStandby()
        w.screenOn = false; w.coordinator.onScreenOff(); w.drain()
        repeat(100) {
            assertTrue("window $it open and held", w.windowOpen && HoldReason.LISTEN in w.held())
            w.windowTimeout()
            assertTrue("gap $it holds nothing", w.held().isEmpty())
            assertEquals("the next window comes from the timer", ContinuousWakePolicy.REARM_MS, w.rearmIn)
            w.rearmDue()
        }
        assertTrue(w.lockLog.none { it.contains("REARM") })
        assertTrue(w.lockLog.count { it.startsWith("acquire:LISTEN") } >= 100)
        w.stop()
        assertTrue(w.held().isEmpty())
        assertNull(w.rearmIn)
    }

    @Test
    fun `an unreachable Phone is retried with a doubling bounded wait and the window opens at once when it returns`() {
        val w = hiddenStandby()
        w.reachable = false
        w.windowTimeout(); w.rearmDue()
        assertTrue(w.log.last().endsWith(":PHONE_UNREACHABLE"))
        assertEquals(WakeLoop.RETRYING, w.status.loop)
        assertTrue("the wait between blocked retries holds no wake lock", w.held().isEmpty())
        val waits = mutableListOf<Long>()
        var elapsed = 0L
        while (elapsed < 3_600_000L) {
            val wait = w.rearmIn ?: break
            waits += wait
            elapsed += wait
            w.rearmDue()
        }
        assertTrue("bounded: ${waits.size} attempts in an hour", waits.size <= 20)
        assertEquals(ContinuousWakePolicy.blockedRetryDelay(0), waits.first())
        assertEquals(ContinuousWakePolicy.MAX_BLOCKED_RETRY_MS, waits.last())
        assertTrue("never shorter than before", waits.zipWithNext().all { (a, b) -> b >= a })
        w.reachabilityChanged(true)
        assertTrue("the Phone's return opens a window now, not at the next timer", w.windowOpen)
        assertNull(w.rearmIn)
        assertEquals(WakeLoop.ACTIVE, w.status.loop)
    }

    @Test
    fun `reachability events while nothing waits for them schedule and open nothing`() {
        val w = hiddenStandby()
        val listens = w.listens()
        repeat(20) { w.reachabilityChanged(true) }
        assertEquals(listens, w.listens())
        w.standbyChange(watch = false)
        repeat(20) { w.reachabilityChanged(it % 2 == 0) }
        assertEquals(listens, w.listens())
        assertNull(w.rearmIn)
        assertTrue(w.held().isEmpty())
    }

    @Test
    fun `busy and standby off schedule no retry, and the first blocked retry delay is the documented base`() {
        val w = hiddenStandby()
        w.busy = true
        w.coordinator.onBusy(); w.drain()
        assertNull("busy waits for idle, it does not poll", w.rearmIn)
        w.busy = false
        w.coordinator.onIdle(); w.drain()
        assertTrue(w.windowOpen)
        assertEquals(ContinuousWakePolicy.BLOCKED_RETRY_MS, ContinuousWakePolicy.blockedRetryDelay(0))
        assertEquals(2 * ContinuousWakePolicy.BLOCKED_RETRY_MS, ContinuousWakePolicy.blockedRetryDelay(1))
        assertEquals(ContinuousWakePolicy.MAX_BLOCKED_RETRY_MS, ContinuousWakePolicy.blockedRetryDelay(40))
        assertNotEquals(0L, ContinuousWakePolicy.blockedRetryDelay(Int.MAX_VALUE))
    }

    private fun assertNotNull(value: Any?) = org.junit.Assert.assertNotNull(value)
    private fun assertNotNull(message: String, value: Any?) = org.junit.Assert.assertNotNull(message, value)
}
