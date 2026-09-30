package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.watchlink.ReaderAction
import com.rumi.hermesvoice.core.watchlink.ReaderGestureMapping
import com.rumi.hermesvoice.core.watchlink.SwipeDirection
import com.rumi.hermesvoice.core.watchlink.SwipeTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderGestureTest {
    private fun tracker() = SwipeTracker(touchSlopPx = 10f, minDistancePx = 60f)

    /** Feeds cumulative (dx, dy) moves; returns the swipe recognised at release. */
    private fun swipe(vararg moves: Pair<Float, Float>, consumedAt: Int = -1): SwipeDirection? {
        val t = tracker()
        moves.forEachIndexed { i, (dx, dy) -> t.onMove(dx, dy, consumedByOther = i == consumedAt) }
        val (dx, dy) = moves.last()
        return t.onUp(dx, dy)
    }

    @Test
    fun `clear horizontal swipes are recognised in both directions`() {
        assertEquals(SwipeDirection.RIGHT_TO_LEFT, swipe(-5f to 0f, -20f to 2f, -80f to 6f))
        assertEquals(SwipeDirection.LEFT_TO_RIGHT, swipe(5f to 1f, 20f to 3f, 90f to 10f))
    }

    @Test
    fun `a vertical scroll never becomes a horizontal action, even if it drifts sideways later`() {
        assertNull(swipe(0f to -15f, -10f to -40f, -90f to -60f))
        assertNull("the list consumed the scroll first", swipe(-3f to -12f, -70f to -14f, consumedAt = 0))
    }

    @Test
    fun `diagonal and short drags do nothing`() {
        assertNull(swipe(-12f to -9f, -70f to -50f))
        assertNull(swipe(-12f to 0f, -40f to 2f))
    }

    @Test
    fun `claiming happens only after horizontal slop with dominance`() {
        val t = tracker()
        assertFalse(t.onMove(-6f, 0f, consumedByOther = false))
        assertTrue(t.onMove(-14f, 1f, consumedByOther = false))
        assertTrue("stays claimed", t.onMove(-30f, 5f, consumedByOther = false))
        val v = tracker()
        assertFalse(v.onMove(2f, 14f, consumedByOther = false))
        assertFalse(v.onMove(40f, 16f, consumedByOther = false))
    }

    @Test
    fun `left toggles the reader surfaces and right backgrounds the app, as confirmed`() {
        assertEquals(ReaderAction.TOGGLE_SURFACE, ReaderGestureMapping.action(SwipeDirection.RIGHT_TO_LEFT))
        assertEquals(ReaderAction.BACKGROUND_APP, ReaderGestureMapping.action(SwipeDirection.LEFT_TO_RIGHT))
        assertEquals("CONFIRMED", ReaderGestureMapping.STATUS)
    }
}
