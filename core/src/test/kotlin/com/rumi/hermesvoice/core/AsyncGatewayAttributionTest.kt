package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.*
import com.rumi.hermesvoice.core.net.*
import com.rumi.hermesvoice.core.voice.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Real OkHttp/WebSocket/RPC/subscription boundary. Acceptance returns before ANY turn events;
 * explicit deferreds/awaits replace timing sleeps. Contract double, not a live-server proof.
 * Bare pre-start error and error+terminal-complete are both supported witnesses in the legacy
 * fixture (tui_gateway _admit_prompt_turn vs _emit_terminal_turn_error). They are not interchangeable
 * queue delimiters: no wire prompt id binds an error to this queued request. */
class AsyncGatewayAttributionTest {
    private val fake = FakeHermesDashboard()
    private val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
    private val tokens = InMemoryHermesTokenStore(HermesBearerSession("AT-1", "RT-1", null, "basic", "jun"))
    private val dashboard = HermesDashboardClient(HermesDashboardEndpoint.parse(fake.baseUrl), http, tokens)
    private val connector = HermesGatewayConnector(dashboard, http)
    private val port = GatewayConversationPort { connector.connection() }

    @After fun close() { connector.close(); fake.close() }

    private suspend fun result(turn: SubmittedTurn): Pair<List<RecipientEvent.Complete>, Throwable?> {
        val finals = mutableListOf<RecipientEvent.Complete>()
        return try {
            turn.collect(3_000) { if (it is RecipientEvent.Complete) finals += it }
            finals to null
        } catch (e: HermesException) { finals to e }
        finally { turn.release() }
    }

    private fun asynchronous() {
        fake.scripts["dest"] = { emptyList() }
        fake.turnBehavior = { "async_hold" }
        fake.submitStatus = { if (fake.prompts.size == 1) "streaming" else "queued" }
    }

    @Test fun A01_twoAcceptedLocalSubmitsWaitForIndependentEventsAndKeepTheirOwnFinals() = runBlocking {
        asynchronous()
        val first = port.submit("dest", "alpha")
        val second = port.submit("dest", "beta")
        assertEquals(2, fake.prompts.size)
        assertTrue(first.attributable); assertFalse(second.attributable); assertNull(second.acceptance)
        val a = async { result(first) }
        val b = async { result(second) }
        fake.pushLaterTurn("dest", FakeHermesDashboard.complete("alpha answer"))
        fake.pushLaterTurn("dest", FakeHermesDashboard.complete("beta answer"))
        assertEquals(listOf(RecipientEvent.Complete("alpha answer", "complete")), withTimeout(8_000) { a.await() }.first)
        val beta = withTimeout(8_000) { b.await() }
        assertEquals("HermesQueuedAttributionException", beta.second?.javaClass?.simpleName)
        assertEquals(emptyList<RecipientEvent.Complete>(), beta.first)
        assertEquals(2, fake.prompts.size)
    }

    @Test fun A02_queuedPreStartErrorCannotMakeTheNextNeighbourFinalBecomeThisRequestsAnswer() = runBlocking {
        asynchronous()
        val prior = port.submit("dest", "prior")
        val queued = port.submit("dest", "queued")
        val a = async { result(prior) }
        val b = async { result(queued) }
        // The prior accepted turn is refused before start: the documented bare-error variant.
        fake.pushLaterTurn("dest", "error" to JSONObject().put("message", "Turn cancelled before the agent was ready"), withStart = false)
        fake.pushLaterTurn("dest", FakeHermesDashboard.complete("queued own answer"))
        // An independently started neighbour must never be adopted if ordinal custody was lost.
        fake.pushLaterTurn("dest", FakeHermesDashboard.complete("NEIGHBOUR MUST NOT BE ADOPTED"))
        assertTrue(withTimeout(8_000) { a.await() }.second is HermesTurnFailedException)
        val got = withTimeout(8_000) { b.await() }
        assertTrue("ambiguous pre-start error must fail honestly, not adopt neighbour: $got", got.first.isEmpty() && got.second is HermesReplyUnrecoverableException)
        assertEquals("no resubmission", listOf("prior", "queued"), fake.prompts.map { it.second })
    }

    @Test fun A03_externalQueuedHeadAndSteeredOrUnknownAcceptanceRemainUnattributable() = runBlocking {
        asynchronous()
        fake.resumeQueued = true
        fake.submitStatus = { "queued" }
        val external = port.submit("dest", "behind unknown head")
        assertFalse(external.attributable)
        assertTrue(result(external).second is HermesProtocolException)
        for (status in listOf("steered", "redirected")) {
            fake.submitStatus = { status }
            val turn = port.submit("dest", status)
            assertFalse(turn.attributable)
            assertTrue(result(turn).second is HermesProtocolException)
        }
        fake.submitStatus = { "unknown-status" }
        val unknown = runCatching { port.submit("dest", "unknown-status") }
        assertEquals("unknown ACK must create no turn/collector or Retry authority", HermesPromptUncertainException::class.java, unknown.exceptionOrNull()?.javaClass)
        assertEquals(4, fake.prompts.size)
    }

    @Test fun A04_priorRunningTurnCompletesAfterQueuedAcceptanceBeforeOwnStart() = runBlocking {
        asynchronous()
        // External prior turn already emitted start before this client's replay anchor/subscription.
        // Establish only the resume/socket, with an unattributable steer (no invented server id).
        fake.submitStatus = { "steered" }
        port.submit("dest", "steer").release()
        fake.pushLaterTurn("dest", withStart = true)
        fake.submitStatus = { "queued" }
        val accepted = java.util.concurrent.CountDownLatch(1)
        val reply = java.util.concurrent.CountDownLatch(1)
        fake.beforeAsyncReply = { accepted.countDown(); check(reply.await(10, TimeUnit.SECONDS)) }
        val submitting = async(Dispatchers.Default) { port.submit("dest", "queued") }
        try {
            assertTrue("actual RPC status decided, reply still held", accepted.await(10, TimeUnit.SECONDS))
            // Real subscription already exists; complete/start frames race ahead of the RPC reply.
            fake.pushLaterTurn("dest", FakeHermesDashboard.complete("external prior answer"), withStart = false)
            fake.pushLaterTurn("dest", FakeHermesDashboard.complete("queued answer"))
        } finally { reply.countDown() }
        val queued = withTimeout(8_000) { submitting.await() }
        assertNull("no queued ordinal is trusted", queued.acceptance); assertFalse(queued.attributable)
        val got = withTimeout(8_000) { result(queued) }
        assertEquals("HermesQueuedAttributionException", got.second?.javaClass?.simpleName)
        assertEquals(emptyList<RecipientEvent.Complete>(), got.first)
    }
}
