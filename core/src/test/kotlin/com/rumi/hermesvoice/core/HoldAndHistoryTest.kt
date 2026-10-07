package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.net.HistoryMessage
import com.rumi.hermesvoice.core.net.HistoryPages
import com.rumi.hermesvoice.core.watchlink.HoldToggleTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Watch's one-second hold toggle and the Phone's history refresh (both used by the real UI adapters). */
class HoldAndHistoryTest {
    private val slop = 10f

    @Test
    fun `the hold is one second, a stationary press fires once and keeps the rest of that press`() {
        assertEquals(1_000L, HoldToggleTracker.HOLD_MS)
        val hold = HoldToggleTracker(slop)
        hold.onMove(3f, -4f, consumedByOther = false)
        assertTrue(hold.onHoldElapsed())
        assertFalse("never twice for one press", hold.onHoldElapsed())
        hold.onMove(200f, 0f, consumedByOther = true)
        assertTrue("after it fired, the rest of the press is swallowed (no click, scroll or swipe)", hold.swallowing)
        hold.onUp()
        assertFalse(hold.swallowing)
    }

    @Test
    fun `movement past the touch slop, even back again, cancels for good`() {
        val within = HoldToggleTracker(slop)
        within.onMove(6f, 8f, consumedByOther = false) // exactly the slop
        assertTrue(within.onHoldElapsed())

        val outAndBack = HoldToggleTracker(slop)
        outAndBack.onMove(0f, 10.5f, consumedByOther = false)
        outAndBack.onMove(0f, 0f, consumedByOther = false)
        assertFalse(outAndBack.onHoldElapsed())
        assertFalse(outAndBack.swallowing)
    }

    @Test
    fun `a child that consumed the movement, a second finger, an interruption or an early release cancel it`() {
        HoldToggleTracker(slop).apply { onMove(1f, 1f, consumedByOther = true); assertFalse(onHoldElapsed()) }
        HoldToggleTracker(slop).apply { onOtherPointer(); assertFalse(onHoldElapsed()) }
        HoldToggleTracker(slop).apply { onInterrupted(); assertFalse(onHoldElapsed()) }
        HoldToggleTracker(slop).apply { onUp(); assertFalse(onHoldElapsed()) }
    }

    private fun row(id: Long, text: String = "m$id") = HistoryMessage(id, if (id % 2 == 0L) "user" else "assistant", text, id.toDouble())

    @Test
    fun `refreshing the latest page keeps the older rows already loaded and takes the new text of the newest rows`() {
        val current = (1L..8L).map { row(it) }
        val page = listOf(row(5), row(6), row(7), row(8, "m8 grown"), row(9))
        val merged = HistoryPages.refreshLatest(current, page)
        assertEquals((1L..9L).toList(), merged.map { it.rowId })
        assertEquals("m8 grown", merged.first { it.rowId == 8L }.text)
    }

    @Test
    fun `an empty or unrelated page replaces nothing it should not`() {
        assertEquals(listOf(row(3)), HistoryPages.refreshLatest(emptyList(), listOf(row(3))))
        val current = listOf(row(1), row(2))
        assertEquals("an empty page keeps what is shown", current, HistoryPages.refreshLatest(current, emptyList()))
    }
}
