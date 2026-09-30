package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.*
import com.rumi.hermesvoice.core.net.*
import com.rumi.hermesvoice.core.sessions.*
import com.rumi.hermesvoice.core.settings.*
import com.rumi.hermesvoice.core.voice.*
import com.rumi.hermesvoice.core.watchlink.*

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HermesGatewayClientTest {
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

    private suspend fun collect(turn: SubmittedTurn): List<RecipientEvent> {
        val events = mutableListOf<RecipientEvent>()
        turn.collect(5_000) { events += it }
        return events
    }

    @Test
    fun `ticket rides in the subprotocol, never the url`() = runBlocking {
        fake.scripts["dest"] = { listOf(FakeHermesDashboard.complete("ok")) }
        collect(port.submit("dest", "hello"))
        assertEquals(1, fake.wsProtocolHeaders.size)
        assertTrue(fake.wsProtocolHeaders[0].matches(Regex("hermes-gateway-v1, hermes-gateway-ticket\\.ticket-\\d+")))
        assertFalse(fake.wsPaths[0].contains("ticket"))
    }

    @Test
    fun `deltas are not responses, interims and complete are`() = runBlocking {
        fake.scripts["dest"] = {
            listOf(FakeHermesDashboard.delta("Look"), FakeHermesDashboard.delta("ing…"),
                FakeHermesDashboard.interim("Looking…"), FakeHermesDashboard.delta("Done"),
                FakeHermesDashboard.complete("Done."), FakeHermesDashboard.interim("late"))
        }
        val turn = port.submit("dest", "hello")
        assertEquals("streaming", turn.submitStatus)
        assertEquals(listOf(RecipientEvent.Interim("Looking…"), RecipientEvent.Complete("Done.", "complete")), collect(turn))
        assertEquals(listOf("dest" to "hello"), fake.prompts.toList())
    }

    @Test
    fun `queued submit skips the in-flight turn it waited behind`() = runBlocking {
        fake.submitStatus = { "queued" }
        fake.scripts["dest"] = {
            listOf(FakeHermesDashboard.interim("someone else's interim"), FakeHermesDashboard.complete("someone else's final"),
                "message.start" to null, FakeHermesDashboard.interim("mine first"), FakeHermesDashboard.complete("mine final"))
        }
        val turn = port.submit("dest", "hello")
        assertTrue(turn.attributable)
        assertEquals(listOf(RecipientEvent.Interim("mine first"), RecipientEvent.Complete("mine final", "complete")), collect(turn))
    }

    @Test
    fun `ambiguous queue position is delivered but not attributable`() = runBlocking {
        fake.submitStatus = { "queued" }
        fake.resumeQueued = true
        fake.scripts["dest"] = { listOf(FakeHermesDashboard.complete("someone else's final")) }
        val turn = port.submit("dest", "hello")
        assertFalse(turn.attributable)
        assertEquals(listOf("dest" to "hello"), fake.prompts.toList())
        try {
            collect(turn)
            fail("unattributable turn must not be collected")
        } catch (_: HermesProtocolException) {
        }
    }

    @Test
    fun `turn-level error event fails the collection`() = runBlocking {
        fake.scripts["dest"] = { listOf("error" to JSONObject().put("message", "Context injection refused.")) }
        try {
            collect(port.submit("dest", "hello"))
            fail("expected failure")
        } catch (error: HermesProtocolException) {
            assertTrue(error.message!!.contains("Context injection refused."))
        }
    }

    @Test
    fun `unknown stored session surfaces the rpc error`() = runBlocking {
        try {
            port.submit("missing", "hello")
            fail("expected rpc error")
        } catch (error: HermesRpcException) {
            assertEquals(4007, error.code)
        }
    }

    @Test
    fun `a replayed ticket is refused by the upgrade`() = runBlocking {
        try {
            HermesGatewayConnection.open(http, dashboard.endpoint.route("api/ws"), "ticket-never-minted", 5_000)
            fail("expected refusal")
        } catch (error: HermesHttpException) {
            assertEquals(403, error.status)
        }
    }
}
