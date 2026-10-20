package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.watchlink.WatchFailureInbox
import com.rumi.hermesvoice.core.watchlink.WatchFailureInbox.Receipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review finding F01 at the Watch's durable record: a Phone "not sent" state is saved (checked commit) before anything is claimed,
 * is hydrated by a rebuilt process, and is acknowledged only after the screen reports it was shown AND that is saved.
 */
class WatchFailureInboxTest {
    private val detail = "Not sent: test"

    @Test
    fun `I1 a received failure is owed and saved before any claim, and a rebuilt process still owes it with its Phone node`() {
        val file = InMemoryKeyValueStore()
        assertEquals(Receipt.OWED, WatchFailureInbox(file).receive("watch-inbox-1", detail, "phone-node"))
        val rebuilt = WatchFailureInbox(file)
        val owed = rebuilt.pending().single()
        assertEquals(Triple("watch-inbox-1", detail, "phone-node"), Triple(owed.turnId, owed.detail, owed.source))
    }

    @Test
    fun `I2 a failure whose save is refused is not acknowledgeable, stays owed in this process, and a new process owes nothing yet`() {
        val failing = FailingKeyValueStore()
        failing.failing = true
        val inbox = WatchFailureInbox(failing)
        assertEquals(Receipt.OWED, inbox.receive("watch-inbox-2", detail, "phone-node"))
        assertTrue(WatchFailureInbox.KEY in failing.refusedKeys())
        assertFalse("shown here but nothing saved: the Phone must not be acknowledged", inbox.displayed("watch-inbox-2"))
        assertTrue("a rebuilt process knows nothing", WatchFailureInbox(failing).pending().isEmpty())
        failing.failing = false
        assertTrue("the next call saves it", inbox.displayed("watch-inbox-2"))
        assertEquals(Receipt.SHOWN_AND_SAVED, WatchFailureInbox(failing).receive("watch-inbox-2", detail, "phone-node"))
    }

    @Test
    fun `I3 a replay of a failure that was received but not yet displayed is owed again, not acknowledged`() {
        val file = InMemoryKeyValueStore()
        val inbox = WatchFailureInbox(file)
        assertEquals(Receipt.OWED, inbox.receive("watch-inbox-3", detail, "phone-node"))
        assertEquals(Receipt.OWED, inbox.receive("watch-inbox-3", detail, "phone-node"))
        assertEquals(Receipt.OWED, WatchFailureInbox(file).receive("watch-inbox-3", detail, "phone-node"))
        assertEquals(1, inbox.pending().size)
    }

    @Test
    fun `I4 after display a replay is acknowledgeable at once and is never owed again, also after a rebuilt process`() {
        val file = InMemoryKeyValueStore()
        val inbox = WatchFailureInbox(file)
        inbox.receive("watch-inbox-4", detail, "phone-node")
        assertTrue(inbox.displayed("watch-inbox-4"))
        assertTrue(inbox.pending().isEmpty())
        assertEquals(Receipt.SHOWN_AND_SAVED, inbox.receive("watch-inbox-4", detail, "phone-node"))
        val rebuilt = WatchFailureInbox(file)
        assertEquals(Receipt.SHOWN_AND_SAVED, rebuilt.receive("watch-inbox-4", detail, "phone-node"))
        assertTrue(rebuilt.pending().isEmpty())
    }

    @Test
    fun `I5 a shown failure whose save was refused answers SHOWN_NOT_SAVED to a replay until storage works`() {
        val failing = FailingKeyValueStore()
        val inbox = WatchFailureInbox(failing)
        inbox.receive("watch-inbox-5", detail, "phone-node")
        failing.failing = true
        assertFalse(inbox.displayed("watch-inbox-5"))
        assertEquals(Receipt.SHOWN_NOT_SAVED, inbox.receive("watch-inbox-5", detail, "phone-node"))
        failing.failing = false
        assertEquals(Receipt.SHOWN_AND_SAVED, inbox.receive("watch-inbox-5", detail, "phone-node"))
    }

    @Test
    fun `I6 a failure id handled by the previous build stays shown, so it is acknowledged and not shown again`() {
        val file = InMemoryKeyValueStore()
        file.putString(WatchFailureInbox.LEGACY_KEY, "turn-old-1 turn-old-2")
        val inbox = WatchFailureInbox(file)
        assertEquals(Receipt.SHOWN_AND_SAVED, inbox.receive("turn-old-2", detail, "phone-node"))
        assertTrue(inbox.pending().isEmpty())
    }

    @Test
    fun `I7 the owed list is bounded oldest first and displaying an unknown id claims nothing`() {
        val inbox = WatchFailureInbox(InMemoryKeyValueStore(), maxOwed = 2)
        for (n in 1..3) inbox.receive("watch-inbox-7-$n", detail, "phone-node")
        assertEquals(listOf("watch-inbox-7-2", "watch-inbox-7-3"), inbox.pending().map { it.turnId })
        assertFalse(inbox.displayed("watch-inbox-7-never"))
        assertNull(inbox.pending().firstOrNull { it.turnId == "watch-inbox-7-never" })
    }
}
