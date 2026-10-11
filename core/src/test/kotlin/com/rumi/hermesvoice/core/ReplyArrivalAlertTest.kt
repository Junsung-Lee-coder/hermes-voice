package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.notify.FinalReply
import com.rumi.hermesvoice.core.notify.FinalReplySource
import com.rumi.hermesvoice.core.notify.ReplyAlert
import com.rumi.hermesvoice.core.notify.ReplyAlertContent
import com.rumi.hermesvoice.core.notify.ReplyAlertLedger
import com.rumi.hermesvoice.core.notify.ReplyAlertResult
import com.rumi.hermesvoice.core.notify.ReplyAlerts
import com.rumi.hermesvoice.core.watchlink.ReplyAlertMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The arrival-alert decision itself: suppression, validation, one claim per identity across a restart, the bounded ledger, the wire message. */
class ReplyArrivalAlertTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ledgerStore = InMemoryKeyValueStore()

    @After
    fun stop() = scope.cancel()

    // ── the decision itself ──────────────────────────────────────────────────────────────────

    private fun reply(identity: String = "t-1#final", heard: Boolean = false, cancelled: Boolean = false, session: String = "s-1") =
        FinalReply(identity, session, VoiceOrigin.PHONE, heard, cancelled, FinalReplySource.OWN)

    @Test
    fun `heard, cancelled and invalid answers are suppressed, and one identity is claimed once, across a restart`() = runBlocking {
        val port = ArrayList<ReplyAlert>()
        val first = ReplyAlerts(ReplyAlertLedger(ledgerStore), { port += it; true }, scope)
        assertEquals(ReplyAlertResult.SUPPRESSED_HEARD, first.dispatch(reply(heard = true)))
        assertEquals(ReplyAlertResult.SUPPRESSED_CANCELLED, first.dispatch(reply(cancelled = true)))
        assertEquals(ReplyAlertResult.INVALID, first.dispatch(reply(identity = "bad identity!")))
        assertEquals(ReplyAlertResult.INVALID, first.dispatch(reply(session = "../x")))
        assertEquals(ReplyAlertResult.SHOWN_HERE, first.dispatch(reply()))
        assertEquals(ReplyAlertResult.DUPLICATE, first.dispatch(reply()))
        val restarted = ReplyAlerts(ReplyAlertLedger(ledgerStore), { port += it; true }, scope)
        assertEquals(ReplyAlertResult.DUPLICATE, restarted.dispatch(reply()))
        assertEquals(ReplyAlertResult.SHOWN_HERE, restarted.dispatch(reply("t-2#final")))
        assertEquals(listOf("t-1#final", "t-2#final"), port.map { it.identity })
    }

    @Test
    fun `a refused or throwing notification surface leaves the answer handled once, and never throws`() = runBlocking {
        val refused = ReplyAlerts(ReplyAlertLedger(InMemoryKeyValueStore()), { false }, scope)
        assertEquals(ReplyAlertResult.NOT_SHOWN_HERE, refused.dispatch(reply()))
        assertEquals(ReplyAlertResult.DUPLICATE, refused.dispatch(reply()))
        val throwing = ReplyAlerts(ReplyAlertLedger(InMemoryKeyValueStore()), { error("boom") }, scope)
        assertEquals(ReplyAlertResult.NOT_SHOWN_HERE, throwing.dispatch(reply()))
    }

    @Test
    fun `the ledger is bounded and keeps the newest`() {
        val store = InMemoryKeyValueStore()
        val ledger = ReplyAlertLedger(store, max = 3)
        (1..5).forEach { assertTrue(ledger.claim("id$it")) }
        assertEquals("id3 id4 id5", store.getString(ReplyAlertLedger.KEY))
        assertFalse(ledger.claim("id5"))
        assertTrue("the oldest was evicted", ledger.claim("id1"))
        assertFalse(ReplyAlertLedger(store, max = 3).claim("id1"))
    }

    @Test
    fun `identity, wire message and intent validation`() {
        assertEquals("turn-1#final", ReplyAlert.identityOf("turn-1", "final"))
        val odd = ReplyAlert.identityOf("turn with space/andé", "final")
        assertTrue(ReplyAlert.isValidIdentity(odd) && odd.endsWith("#final"))
        assertEquals(odd, ReplyAlert.identityOf("turn with space/andé", "final"))
        val message = ReplyAlertMessage("turn-1#final", "s-1")
        assertEquals(message, ReplyAlertMessage.decode(message.encode()))
        assertNull(ReplyAlertMessage.decode("""{"v":2,"identity":"a#b","session_id":"s-1"}""".toByteArray()))
        assertNull(ReplyAlertMessage.decode("""{"v":1,"identity":"a b","session_id":"s-1"}""".toByteArray()))
        assertNull(ReplyAlertMessage.decode("""{"v":1,"identity":"a#b","session_id":"../x"}""".toByteArray()))
        assertNull(ReplyAlertMessage.decode("not json".toByteArray()))
        assertFalse(String(message.encode()).contains("text"))
        assertEquals("s-1", ReplyAlertContent.sessionOf(ReplyAlertContent.ACTION_OPEN_REPLY, "s-1"))
        assertNull(ReplyAlertContent.sessionOf("android.intent.action.MAIN", "s-1"))
        assertNull(ReplyAlertContent.sessionOf(ReplyAlertContent.ACTION_OPEN_REPLY, "../x"))
        assertNull(ReplyAlertContent.sessionOf(ReplyAlertContent.ACTION_OPEN_REPLY, null))
    }
}
