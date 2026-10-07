package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.background.HoldReason
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.wake.ContinuousWakePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replaces the removed REARM-hold assertions of the inherited coordinator tests: the idle gap between two windows takes no
 * CPU hold at all (the host's timer brings the next window), while every open window and every pending handoff is held.
 * Each test asserts the positive LISTEN hold first, so a missing hold cannot pass as "nothing held".
 */
class IdleGapNoHoldTest {
    private fun hidden() = ComposedWatch(WakeLocation.OFF, watchStandby = true).apply { show(); start(); hide() }

    @Test
    fun `a window is held while open, released before the gap, and the gap holds nothing`() {
        val w = hidden()
        assertTrue(w.windowOpen)
        assertEquals(setOf(HoldReason.LISTEN), w.held())
        w.lockLog.clear()
        w.windowTimeout()
        assertTrue("the window's lock was let go", w.lockLog.contains("release:LISTEN"))
        assertTrue("nothing is held across the gap", w.held().isEmpty())
        assertNotNull("the timer alone schedules the next window", w.rearmIn)
        assertTrue(w.lockLog.none { it.contains("REARM") })
        w.lockLog.clear()
        w.rearmDue()
        assertTrue(w.windowOpen)
        assertEquals(setOf(HoldReason.LISTEN), w.held())
        assertTrue(w.lockLog.any { it.startsWith("acquire:LISTEN") })
    }

    @Test
    fun `the wait of a blocked retry and of a recognizer back-off holds nothing`() {
        val w = hidden()
        w.reachable = false
        w.windowTimeout(); w.rearmDue()
        assertEquals(ContinuousWakePolicy.BLOCKED_RETRY_MS, w.rearmIn)
        assertTrue(w.held().isEmpty())
        w.reachabilityChanged(true)
        assertTrue(w.windowOpen)
        assertEquals(setOf(HoldReason.LISTEN), w.held())
        repeat(5) {
            w.wake.onError(w.wake.generation, 5); w.drain()
            assertTrue("back-off gap $it holds nothing", w.held().isEmpty())
            assertNotNull(w.rearmIn)
            w.rearmDue()
            assertEquals(setOf(HoldReason.LISTEN), w.held())
        }
        assertFalse(w.lockLog.any { it.contains("REARM") })
    }
}
