package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.chat.DispatchedSend
import com.rumi.hermesvoice.core.chat.PendingSend
import com.rumi.hermesvoice.core.chat.PendingSends
import com.rumi.hermesvoice.core.net.HistoryMessage
import com.rumi.hermesvoice.core.net.OutgoingAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a message shown the instant it was sent is replaced by the server's own row: one for one, never by text alone. */
class PendingSendsTest {
    private fun send(id: Long, text: String, session: String = "sA", attachments: List<OutgoingAttachment> = emptyList()) =
        DispatchedSend(id, session, text, attachments)

    private fun pending(id: Long, text: String, baseline: Long = 10, session: String = "sA", delivered: Boolean = false, attachments: List<OutgoingAttachment> = emptyList()) =
        PendingSend(send(id, text, session, attachments), baseline, delivered)

    private fun row(id: Long, role: String, text: String) = HistoryMessage(id, role, text, id.toDouble())

    private fun ids(list: List<PendingSend>) = list.map { it.send.id }

    @Test fun `a user row beyond the baseline with the same text replaces its pending bubble`() {
        val left = PendingSends.reconcile(listOf(pending(1, "hello there")), "sA", listOf(row(11, "user", "hello there")))
        assertTrue(left.isEmpty())
    }

    @Test fun `the same text already in history before the send does not claim it`() {
        val history = listOf(row(10, "user", "ok"), row(9, "assistant", "ok"))
        assertEquals(listOf(1L), ids(PendingSends.reconcile(listOf(pending(1, "ok", baseline = 10)), "sA", history)))
    }

    @Test fun `only a user row can replace a pending bubble`() {
        assertEquals(listOf(1L), ids(PendingSends.reconcile(listOf(pending(1, "ok")), "sA", listOf(row(11, "assistant", "ok")))))
    }

    @Test fun `identical consecutive sends are matched one to one in order`() {
        val two = listOf(pending(1, "ok"), pending(2, "ok"))
        assertEquals(listOf(2L), ids(PendingSends.reconcile(two, "sA", listOf(row(11, "user", "ok")))))
        assertTrue(PendingSends.reconcile(two, "sA", listOf(row(11, "user", "ok"), row(12, "assistant", "sure"), row(13, "user", "ok"))).isEmpty())
    }

    @Test fun `a different text in the history does not claim a pending bubble`() {
        assertEquals(listOf(1L, 2L), ids(PendingSends.reconcile(listOf(pending(1, "first"), pending(2, "second")), "sA", listOf(row(11, "user", "third")))))
    }

    @Test fun `out of order or partial history claims only what it contains`() {
        val three = listOf(pending(1, "one"), pending(2, "two"), pending(3, "three"))
        assertEquals(listOf(1L, 3L), ids(PendingSends.reconcile(three, "sA", listOf(row(12, "user", "two")))))
        assertEquals(listOf(1L), ids(PendingSends.reconcile(listOf(pending(1, "one"), pending(2, "two"), pending(3, "three")), "sA", listOf(row(14, "user", "three"), row(12, "user", "two")))))
    }

    @Test fun `another conversation's history never claims or removes a pending bubble`() {
        val mixed = listOf(pending(1, "ok", session = "sA"), pending(2, "ok", session = "sB"))
        assertEquals(listOf(1L), ids(PendingSends.reconcile(mixed, "sB", listOf(row(11, "user", "ok")))))
    }

    @Test fun `the server may wrap the typed text with attachment references`() {
        val left = PendingSends.reconcile(listOf(pending(1, "see this")), "sA", listOf(row(11, "user", "@file:attachments/a.txt\nsee this")))
        assertTrue(left.isEmpty())
    }

    @Test fun `whitespace differences do not hide a match`() {
        assertTrue(PendingSends.reconcile(listOf(pending(1, "  a   b\n")), "sA", listOf(row(11, "user", "a b"))).isEmpty())
    }

    @Test fun `an attachment-only send is claimed by one unclaimed user row beyond the baseline`() {
        val file = OutgoingAttachment("a.txt", "text/plain", byteArrayOf(1))
        val one = listOf(pending(1, "", attachments = listOf(file)), pending(2, "", attachments = listOf(file)))
        assertEquals(listOf(2L), ids(PendingSends.reconcile(one, "sA", listOf(row(11, "user", "@file:attachments/a.txt")))))
    }

    @Test fun `text pendings take their own row before an attachment-only pending can`() {
        val file = OutgoingAttachment("a.txt", "text/plain", byteArrayOf(1))
        val both = listOf(pending(1, "", attachments = listOf(file)), pending(2, "words"))
        assertEquals(listOf(1L), ids(PendingSends.reconcile(both, "sA", listOf(row(11, "user", "words")))))
    }

    @Test fun `baseline is the newest row id loaded, zero for an empty conversation`() {
        assertEquals(0L, PendingSends.baseline(emptyList()))
        assertEquals(7L, PendingSends.baseline(listOf(row(3, "user", "a"), row(7, "assistant", "b"), row(5, "user", "c"))))
    }

    @Test fun `add keeps order, delivered marks without losing the content, remove drops one`() {
        var list = PendingSends.add(emptyList(), pending(1, "a"))
        list = PendingSends.add(list, pending(2, "b"))
        list = PendingSends.markDelivered(list, 1)
        assertEquals(listOf(1L, 2L), ids(list))
        assertEquals(listOf(true, false), list.map { it.delivered })
        assertEquals("a", list.first().send.text)
        assertEquals(listOf(2L), ids(PendingSends.remove(list, 1)))
    }

    @Test fun `the list is bounded but a send still in flight is never dropped`() {
        var list = emptyList<PendingSend>()
        repeat(PendingSends.MAX_PER_SESSION + 6) { list = PendingSends.add(list, pending(it.toLong(), "m$it", delivered = it < 10)) }
        assertTrue(list.size <= PendingSends.MAX_PER_SESSION + 6)
        assertTrue("in-flight sends survive the bound", list.filter { !it.delivered }.size == PendingSends.MAX_PER_SESSION + 6 - 10)
        assertTrue("delivered ones are the ones dropped", list.count { it.delivered } <= PendingSends.MAX_PER_SESSION)
    }
}
