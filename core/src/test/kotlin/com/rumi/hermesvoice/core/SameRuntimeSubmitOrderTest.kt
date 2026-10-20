package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.*
import com.rumi.hermesvoice.core.net.*
import com.rumi.hermesvoice.core.voice.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review finding F07: two submits to the SAME runtime session started together, over the real gateway client and the
 * server-contract fixture. The replay watermark and the subscription are taken before the per-runtime submit order, so a
 * neighbouring turn of the same session must never be taken for the reply of (or a failure before the start of) the other.
 * These cases exercise what the fixture can reach (it answers the RPCs of one gateway connection in order, so it cannot
 * interleave the watermark of one submit between the accept and the events of another); they hold on any tree that keeps
 * attribution by order, and they fail if a submit ever adopts a neighbour's events.
 */
class SameRuntimeSubmitOrderTest {
    private val fake = FakeHermesDashboard()
    private val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
    private val tokens = InMemoryHermesTokenStore(HermesBearerSession("AT-1", "RT-1", null, "basic", "jun"))
    private val dashboard = HermesDashboardClient(HermesDashboardEndpoint.parse(fake.baseUrl), http, tokens)
    private val connector = HermesGatewayConnector(dashboard, http)
    private val port = GatewayConversationPort { connector.connection() }

    @After fun tearDown() {
        connector.close()
        fake.close()
    }

    private suspend fun outcome(turn: SubmittedTurn): Pair<List<RecipientEvent>, Throwable?> {
        val events = mutableListOf<RecipientEvent>()
        val failure = try {
            turn.collect(8_000) { events += it }
            null
        } catch (error: HermesException) {
            error
        }
        return events to failure
    }

    @Test
    fun `two submits to one session started together each get only the reply to their own prompt`() = runBlocking {
        fake.turnBehavior = { "start_first" }
        fake.scripts["dest"] = { prompt -> listOf(FakeHermesDashboard.complete("answer to $prompt")) }
        repeat(6) { round ->
            val first = "first $round"
            val second = "second $round"
            val results = withTimeout(30_000) {
                listOf(first, second).map { prompt ->
                    async(Dispatchers.Default) { port.submit("dest", prompt).let { prompt to outcome(it) } }
                }.awaitAll()
            }
            for ((prompt, result) in results) {
                val (events, failure) = result
                assertEquals("round $round: no failure for $prompt: $failure", null, failure)
                assertEquals("round $round: $prompt got its own reply only: $events",
                    listOf(RecipientEvent.Complete("answer to $prompt", "complete")), events.filterIsInstance<RecipientEvent.Complete>())
            }
        }
    }

    @Test
    fun `a neighbouring turn that fails before it starts is not taken for the failure of the other submit`() = runBlocking {
        // The fixture asks twice per submit (ownership check, then the run); the prompt is recorded in between: only the first prompt's run is refused.
        fake.turnBehavior = { if (fake.prompts.size == 1) "error_before_start" else "start_first" }
        fake.scripts["dest"] = { prompt -> listOf(FakeHermesDashboard.complete("answer to $prompt")) }
        val results = withTimeout(30_000) {
            listOf("alpha", "beta").map { prompt ->
                async(Dispatchers.Default) { port.submit("dest", prompt).let { prompt to outcome(it) } }
            }.awaitAll()
        }
        val failed = results.filter { it.second.second != null }
        val answered = results.filter { it.second.second == null }
        assertEquals("bare error is not a reliable preceding terminal boundary", 2, failed.size)
        assertEquals(1, failed.count { it.second.second is HermesTurnFailedException })
        assertEquals(1, failed.count { it.second.second?.javaClass?.simpleName == "HermesReplyUnrecoverableException" })
        assertTrue("no neighbour or uncertain final is adopted", answered.isEmpty() && results.all { it.second.first.filterIsInstance<RecipientEvent.Complete>().isEmpty() })
        assertEquals(listOf("alpha", "beta"), fake.prompts.map { it.second }.sorted())
    }
}
