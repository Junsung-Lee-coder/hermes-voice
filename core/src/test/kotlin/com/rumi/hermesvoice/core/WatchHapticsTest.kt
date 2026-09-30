package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.watchlink.HapticEvent
import com.rumi.hermesvoice.core.watchlink.HapticPattern
import com.rumi.hermesvoice.core.watchlink.RecordingHapticLatch
import com.rumi.hermesvoice.core.watchlink.RotaryScrollAccumulator
import com.rumi.hermesvoice.core.watchlink.ScrollHapticGate
import com.rumi.hermesvoice.core.watchlink.WatchHapticPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchHapticsTest {
    @Test
    fun `patterns match the original Watch haptic policy`() {
        assertEquals(HapticPattern(50, 1), WatchHapticPolicy.patternFor(HapticEvent.RECORDING_START))
        assertEquals(HapticPattern(30, 2), WatchHapticPolicy.patternFor(HapticEvent.RECORDING_END))
        assertEquals(HapticPattern(10, 1), WatchHapticPolicy.patternFor(HapticEvent.SCROLL_STEP))
        assertArrayEquals(longArrayOf(0, 50), WatchHapticPolicy.patternFor(HapticEvent.RECORDING_START).timings())
        assertArrayEquals(longArrayOf(0, 30, 12, 30), WatchHapticPolicy.patternFor(HapticEvent.RECORDING_END).timings())
    }

    @Test
    fun `recording start and end each fire once per capture, end only after a real start`() {
        val latch = RecordingHapticLatch()
        assertNull("no start haptic on button intent", latch.onEnded("turn-a"))
        assertEquals(HapticEvent.RECORDING_START, latch.onStarted("turn-a"))
        assertNull(latch.onStarted("turn-a"))
        assertEquals(HapticEvent.RECORDING_END, latch.onEnded("turn-a"))
        assertNull(latch.onEnded("turn-a"))
        assertNull("a stale capture cannot end the next one", latch.onEnded("turn-old"))
        assertEquals(HapticEvent.RECORDING_START, latch.onStarted("turn-b"))
        assertNull(latch.onEnded("turn-a"))
        assertEquals(HapticEvent.RECORDING_END, latch.onEnded("turn-b"))
    }

    @Test
    fun `scroll haptics pulse only for scroll that actually moved, coalesced in time`() {
        val gate = ScrollHapticGate(minIntervalMs = 50)
        assertTrue(gate.shouldPulse(requestedPx = 40f, consumedPx = 40f, nowMs = 1_000))
        assertFalse("coalesced", gate.shouldPulse(40f, 40f, nowMs = 1_020))
        assertTrue(gate.shouldPulse(-40f, -40f, nowMs = 1_060))
        assertFalse("at the list bound nothing scrolls", gate.shouldPulse(40f, 0f, nowMs = 2_000))
        assertFalse("ignored deltas", gate.shouldPulse(0f, 0f, nowMs = 3_000))
        assertTrue(gate.shouldPulse(10f, 3f, nowMs = 4_000))
    }

    @Test
    fun `rotary deltas arriving during a scroll are accumulated, not dropped`() {
        val acc = RotaryScrollAccumulator()
        assertTrue(acc.add(30f))
        assertFalse("already draining", acc.add(20f))
        assertFalse(acc.add(-5f))
        assertEquals(45f, acc.drain(), 0.001f)
        assertEquals(0f, acc.drain(), 0.001f)
        assertTrue("next burst starts a new drain", acc.add(12f))
        assertEquals(12f, acc.drain(), 0.001f)
        acc.finish()
        assertTrue(acc.add(1f))
        assertFalse(acc.add(7f))
        acc.finish()
        assertTrue("a cancelled drain drops its pending rotation", acc.add(2f))
        assertEquals(2f, acc.drain(), 0.001f)
    }
}
