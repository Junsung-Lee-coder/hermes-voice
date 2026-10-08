package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.*
import com.rumi.hermesvoice.core.net.*
import com.rumi.hermesvoice.core.voice.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C: a request sent while Hermes is still working on an earlier one in the SAME session reaches the real
 * [GatewayConversationPort] unchanged. The app never selects steer / queue / interrupt (no `queued` flag, no
 * policy or provenance field, no extra RPC): Hermes' own busy-input policy decides, and the app only reports what
 * it was told. A transport fixture proves client dispatch only, not what a real Hermes does with the request.
 */
class BusyInputPassThroughTest {
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

    private val submitCalls get() = fake.rpcLog.filter { it.getString("method") == "prompt.submit" }.map { it.getJSONObject("params") }

    private fun submitStatuses(vararg statuses: String) {
        var n = 0
        fake.submitStatus = { statuses[minOf(n++, statuses.size - 1)] }
    }

    private suspend fun collect(turn: SubmittedTurn): List<RecipientEvent> {
        val events = mutableListOf<RecipientEvent>()
        turn.collect(8_000) { events += it }
        return events
    }

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        withTimeout(10_000) { while (!condition()) delay(5) }
        assertTrue(what, condition())
    }

    private fun complete(text: String, status: String = "complete") = FakeHermesDashboard.complete(text, status)

    @Test
    fun `B submitted while A is pending reaches the backend unchanged with no policy chosen by the app, and each turn gets only its own reply`() = runBlocking {
        submitStatuses("streaming", "queued")
        fake.scripts["dest"] = { emptyList() }
        val a = port.submit("dest", "first request")
        val aEvents = async { collect(a) }
        waitFor("A reached the backend") { submitCalls.size == 1 }
        val b = port.submit("dest", "second request while Hermes works")
        val bEvents = async { collect(b) }
        waitFor("B reached the backend while A is unanswered") { submitCalls.size == 2 }
        assertFalse("A is still pending", aEvents.isCompleted)
        assertEquals(listOf("first request", "second request while Hermes works"), fake.rawPrompts.map { it.second })
        assertTrue("B is attributable behind the in-flight A", b.attributable)
        assertEquals("queued", b.submitStatus)
        for (call in submitCalls) {
            assertEquals("only the session and the unchanged text are sent", setOf("session_id", "text"), call.keys().asSequence().toSet() - "profile")
            assertFalse("no queued flag", call.has("queued"))
        }
        assertFalse("the app sends no stop / interrupt / steer / queue RPC",
            fake.rpcLog.any { it.getString("method") in setOf("session.interrupt", "prompt.interrupt", "prompt.steer", "prompt.queue", "session.stop") })
        fake.pushLaterTurn("dest", complete("answer to A"), withStart = false)
        assertEquals(listOf(RecipientEvent.Complete("answer to A", "complete")), aEvents.await())
        fake.pushLaterTurn("dest", complete("answer to B"))
        assertEquals(listOf(RecipientEvent.Complete("answer to B", "complete")), bEvents.await())
    }

    @Test
    fun `a request the backend steers or redirects into the running turn is delivered, not attributed, and A keeps its own reply`() = runBlocking {
        for (merged in listOf("steered", "redirected")) {
            val session = "dest-$merged"
            submitStatuses("streaming", merged)
            fake.scripts[session] = { emptyList() }
            val a = port.submit(session, "A $merged")
            val aEvents = async { collect(a) }
            val b = port.submit(session, "B $merged")
            assertEquals(merged, b.submitStatus)
            assertFalse("a merged request has no reply of its own to follow", b.attributable)
            assertFalse("A is neither cancelled nor finished", aEvents.isCompleted)
            fake.pushLaterTurn(session, complete("A and B answered together"), withStart = false)
            assertEquals(listOf(RecipientEvent.Complete("A and B answered together", "complete")), aEvents.await())
            b.release()
            a.release()
        }
        assertTrue(submitCalls.none { it.has("queued") })
    }

    @Test
    fun `A interrupted by the backend still completes with the status the backend reported, and nothing is cancelled by the app`() = runBlocking {
        submitStatuses("streaming", "queued")
        fake.scripts["dest"] = { emptyList() }
        val a = port.submit("dest", "A")
        val aEvents = async { collect(a) }
        waitFor("A submitted") { submitCalls.size == 1 }
        val b = port.submit("dest", "B")
        val bEvents = async { collect(b) }
        waitFor("B submitted") { submitCalls.size == 2 }
        fake.pushLaterTurn("dest", complete("stopped early", "interrupted"), withStart = false)
        assertEquals(listOf(RecipientEvent.Complete("stopped early", "interrupted")), aEvents.await())
        fake.pushLaterTurn("dest", complete("B answer"))
        assertEquals(listOf(RecipientEvent.Complete("B answer", "complete")), bEvents.await())
    }

    @Test
    fun `three rapid requests to one session are each positioned behind the earlier ones and each gets exactly its own reply`() = runBlocking {
        submitStatuses("streaming", "queued", "queued")
        fake.scripts["dest"] = { emptyList() }
        val a = port.submit("dest", "A")
        val aEvents = async { collect(a) }
        waitFor("A") { submitCalls.size == 1 }
        val b = port.submit("dest", "B")
        val bEvents = async { collect(b) }
        waitFor("B") { submitCalls.size == 2 }
        fake.resumeQueued = true // Hermes now shows B at the head of its queue in the resume snapshot
        val c = port.submit("dest", "C")
        val cEvents = async { collect(c) }
        waitFor("C") { submitCalls.size == 3 }
        assertTrue(a.attributable && b.attributable && c.attributable)
        fake.pushLaterTurn("dest", complete("a"), withStart = false)
        fake.pushLaterTurn("dest", complete("b"))
        fake.pushLaterTurn("dest", complete("c"))
        assertEquals(listOf(RecipientEvent.Complete("a", "complete")), aEvents.await())
        assertEquals(listOf(RecipientEvent.Complete("b", "complete")), bEvents.await())
        assertEquals(listOf(RecipientEvent.Complete("c", "complete")), cEvents.await())
    }

    @Test
    fun `a queue the app did not create is never guessed at, so the request is delivered but not attributed`() = runBlocking {
        submitStatuses("queued")
        fake.resumeQueued = true
        fake.scripts["dest"] = { emptyList() }
        val turn = port.submit("dest", "hello")
        assertFalse(turn.attributable)
        assertEquals(listOf("hello"), fake.rawPrompts.map { it.second })
    }

    @Test
    fun `typed text and a voice request in one busy session each keep their own words, and only the voice one carries the marker once`() = runBlocking {
        submitStatuses("streaming", "queued")
        fake.scripts["dest"] = { emptyList() }
        val typed = port.submit("dest", "typed words")
        val typedEvents = async { collect(typed) }
        waitFor("typed") { submitCalls.size == 1 }
        val voice = port.submit("dest", VoiceTurnPrompt.compose("spoken words"))
        val voiceEvents = async { collect(voice) }
        waitFor("voice") { submitCalls.size == 2 }
        assertEquals(listOf("typed words", VOICE_MARK + "spoken words"), fake.rawPrompts.map { it.second })
        assertEquals("exactly one marker", 1, fake.rawPrompts.map { it.second }.sumOf { Regex(Regex.escape(VOICE_MARK.trim())).findAll(it).count() })
        fake.pushLaterTurn("dest", complete("t"), withStart = false)
        fake.pushLaterTurn("dest", complete("v"))
        typedEvents.await()
        voiceEvents.await()
        Unit
    }

    @Test
    fun `requests to different sessions never depend on each other`() = runBlocking {
        fake.scripts["x"] = { emptyList() }
        fake.scripts["y"] = { listOf(complete("y done")) }
        val x = port.submit("x", "to x")
        val xEvents = async { collect(x) }
        waitFor("x submitted") { submitCalls.size == 1 }
        val y = port.submit("y", "to y")
        assertEquals(listOf(RecipientEvent.Complete("y done", "complete")), collect(y))
        assertFalse("x is untouched", xEvents.isCompleted)
        fake.pushLaterTurn("x", complete("x done"), withStart = false)
        assertEquals(listOf(RecipientEvent.Complete("x done", "complete")), xEvents.await())
    }

    @Test
    fun `a newer request ends only A's later-reply follow, not A's own reply`() = runBlocking {
        submitStatuses("streaming", "queued")
        fake.scripts["dest"] = { emptyList() }
        val a = port.submit("dest", "A")
        val aEvents = async { collect(a) }
        waitFor("A") { submitCalls.size == 1 }
        val b = port.submit("dest", "B")
        val bEvents = async { collect(b) }
        waitFor("B") { submitCalls.size == 2 }
        fake.pushLaterTurn("dest", complete("a"), withStart = false)
        assertEquals(listOf(RecipientEvent.Complete("a", "complete")), aEvents.await())
        assertEquals(LaterEnd.SUPERSEDED, a.collectLater(500) { })
        fake.pushLaterTurn("dest", complete("b"))
        assertEquals(listOf(RecipientEvent.Complete("b", "complete")), bEvents.await())
    }
}
