package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.net.AcceptedKind
import com.rumi.hermesvoice.core.net.AcceptedTurn
import com.rumi.hermesvoice.core.net.AcceptedTurnStore
import com.rumi.hermesvoice.core.notify.ReplyAlertLedger
import com.rumi.hermesvoice.core.notify.ReplyAlerts
import com.rumi.hermesvoice.core.sessions.OwnedRole
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F09: an accepted prompt does not confer permanent eligibility. Real guarded port, durable record and startup reconciler. */
class ReattachEligibilityRegressionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun stop() = scope.cancel()

    private fun refusedAfter(revoke: (CoreHarness, String) -> Unit) = runBlocking {
        val shown = Collections.synchronizedList(mutableListOf<String>())
        val alerts = ReplyAlerts(ReplyAlertLedger(InMemoryKeyValueStore()), { shown += it.identity; true }, scope)
        CoreHarness(laterScope = scope, replyAlerts = alerts).use { h ->
            val work = h.core.sessions.createConversation("Work", "work", "work things").storedSessionId
            h.fake.scripts[work] = { listOf(FakeHermesDashboard.complete("Must not be attributed after eligibility was revoked.")) }
            h.fake.turnBehavior = { "hold" }
            val submitted = h.core.sessions.guardedPort().submit(work, "hello")
            val acceptance = submitted.acceptance!!
            submitted.release()
            val saved = AcceptedTurnStore(h.store)
            saved.begin(AcceptedTurn("chat-revoked", AcceptedKind.TEXT, work, acceptance, System.currentTimeMillis()))
            saved.idle("chat-revoked")
            revoke(h, work)
            h.fake.finishHeldTurn(work)
            h.core.reconcileAccepted()
            withTimeout(12_000) { while (shown.isEmpty()) delay(10) }
            delay(500)
            assertEquals("revoked eligibility owes an honest notice, never a reply", listOf("chat-revoked#notice"), shown.toList())
            assertTrue("settled only after the notice", AcceptedTurnStore(h.store).pending().isEmpty())
            assertEquals("recovery never resubmits", 1, h.fake.rpcLog.toList().count { it.optString("method") == "prompt.submit" })
        }
    }

    @Test fun `a conversation reclassified as a nonretired router is refused during recovery`() = refusedAfter { h, work ->
        h.registry.update(work) { it.copy(role = OwnedRole.ROUTER, retired = false) }
    }

    @Test fun `a retired conversation record is refused during recovery`() = refusedAfter { h, work ->
        h.registry.update(work) { it.copy(retired = true) }
    }

    @Test fun `revoked created_source is refused during recovery even while registry membership remains`() = refusedAfter { h, work ->
        h.fake.setCreatedSource(work, "not-recorder")
    }
}
