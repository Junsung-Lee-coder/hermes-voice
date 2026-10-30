package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.live.*
import com.rumi.hermesvoice.core.net.HistoryMessage
import com.rumi.hermesvoice.core.net.HistoryPage
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

/** PHONE-R2-01/R2-03 pure core contracts: a clarification turn is a new request, and new arrivals are not cut to the initial-context bound. */
class LivePhoneIntegrationR2CoreTest {
    private fun input(text: String, start: Int, end: Int) = JSONObject().put("type", "session.input_transcript.delta").put("delta", text).put("start_ms", start).put("end_ms", end).toString()
    private fun delegation(id: String, offset: Int) = JSONObject().put("type", "session.delegation.created").put("offset_ms", offset)
        .put("delegation", JSONObject().put("id", id).put("type", "delegation").put("target", "client")).toString()

    @Test fun aLaterDistinctDelegationAfterAnUnresolvedRequestIsANewRequestNotDropped() {
        val d = LiveDialogue("call", "scope")
        d.receive(input("notes 정리", 0, 100))
        assertTrue(d.receive(delegation("d1", 150)) is LiveEvent.Confirmation)
        d.receive(input("notes work", 200, 300))
        val second = d.receive(delegation("d2", 350))
        assertTrue("a clarification turn creates a new request instead of being dropped", second is LiveEvent.Confirmation)
        second as LiveEvent.Confirmation
        assertEquals("d2", second.request.delegationId)
        assertTrue(second.request.text.contains("notes 정리") && second.request.text.contains("notes work"))
        assertNull("a superseded delegation can never be confirmed", d.confirm("d1", "scope", "s", "x"))
        assertNotNull(d.confirm("d2", "scope", "s", second.request.text))
        assertNull(d.receive(delegation("d2", 400)))
        assertNull("no new speech after a confirmed request", d.receive(delegation("d3", 450)))
    }

    @Test fun newArrivalsAreNotTruncatedToTheBoundedInitialContext() {
        val c = LiveSessionContext("a")
        val initial = c.page(HistoryPage("a", (1L..30L).map { HistoryMessage(it, "user", "old-$it", 1.0) }, 30, (1L..30L).toList()), true)
        assertEquals("initial hydration stays bounded", 20, initial.size)
        val arrivals = (31L..55L).map { HistoryMessage(it, "user", "new-$it", 1.0) }
        val out = c.page(HistoryPage("a", arrivals, 25, (31L..55L).toList()), false)
        assertEquals("every new arrival is offered, in order", arrivals.map { "new-${it.rowId}" }, out.map { it.content.substringAfter(": ") })
        assertTrue(c.page(HistoryPage("a", arrivals, 25, (31L..55L).toList()), false).isEmpty())
    }
}
