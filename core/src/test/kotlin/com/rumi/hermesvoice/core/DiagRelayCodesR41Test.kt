package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.diag.*
import org.junit.Test
import org.junit.Assert.*

/**
 * R41: the relay of a started Watch-origin call has its own fixed journal events on both apps, so a Phone-side end after Started is
 * never again only "failure, unknown" with an empty Watch journal. They are v3 LIVE events (exported, never legacy) and carry only the
 * journal's bounded numbers: a recorded event keeps no reference or failure text and clamps its numbers.
 */
class DiagRelayCodesR41Test {
    private val wires = listOf("live_relay_end", "live_relay_up", "live_relay_down", "live_relay_capture", "live_relay_nonzero", "live_relay_sent")

    @Test fun G01_theSixRelayEventsExistAsV3LiveCodes() {
        for (w in wires) {
            val code = DiagCode.fromWire(w); assertNotNull("$w is a journal code", code)
            assertEquals(w, code!!.wire); assertEquals(DiagCategory.LIVE, code.category); assertTrue("$w is exported", code.v3); assertFalse("$w is not legacy", code.legacy)
        }
        assertEquals("distinct wire names", wires.size, wires.mapNotNull { DiagCode.fromWire(it) }.toSet().size)
    }

    @Test fun G02_aRecordedRelayEventKeepsOnlyBoundedNumbers() {
        var now = 1_000L; val log = DiagLog(clock = { now++ })
        for (w in wires) log.record(DiagCode.fromWire(w)!!, DiagOrigin.WATCH, n = Int.MAX_VALUE, ms = Long.MAX_VALUE)
        val events = log.snapshot().filter { it.code.wire in wires }
        assertEquals(wires.size, events.size)
        for (e in events) {
            assertNull(e.ref); assertNull(e.fail); assertEquals(DiagOrigin.WATCH, e.origin)
            assertEquals(DiagLog.MAX_NUMBER, e.n); assertEquals(DiagLog.MAX_MS, e.ms)
        }
    }
}
