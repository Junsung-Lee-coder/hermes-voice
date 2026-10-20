package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.chat.ComposerDispatch
import com.rumi.hermesvoice.core.chat.DispatchedSend
import com.rumi.hermesvoice.core.diag.DiagCause
import com.rumi.hermesvoice.core.diag.DiagClassify
import com.rumi.hermesvoice.core.diag.DiagStage
import com.rumi.hermesvoice.core.net.GatewayConversationPort
import com.rumi.hermesvoice.core.net.GatewayRpc
import com.rumi.hermesvoice.core.net.GatewaySubscription
import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.net.HermesDashboardClient
import com.rumi.hermesvoice.core.net.HermesGatewayConnector
import com.rumi.hermesvoice.core.net.OutgoingAttachment
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A session held by another Hermes process (the TUI, Desktop, another gateway): `prompt.submit` is refused with code 4090 and
 * data.reason SESSION_NOT_OWNED before the prompt is accepted. That is told apart from a session-limit 4090 and from this
 * app's own resume timeout, and nothing is resent, taken over or marked as sent.
 */
class SessionInUseTest {
    private val h = CoreHarness()

    @After fun tearDown() = h.close()

    private fun conversation() = runBlocking { h.core.sessions.createConversation("Work", "work", "") }.storedSessionId
    private fun methods() = h.fake.rpcLog.map { it.getString("method") }

    @Test
    fun `a session held by the TUI is reported as in use, truthfully not sent`() = runBlocking {
        val id = conversation()
        h.fake.turnBehavior = { "not_owned" }
        var accepted = 0
        val result = h.core.chat.send(id, "ship the report", onAccepted = { accepted += 1 }) as ChatSendResult.Failed
        assertTrue(result.reason, result.reason.contains("currently in use in the TUI or another Hermes runtime"))
        assertTrue(result.reason, result.reason.contains("Release or close it there and retry"))
        assertFalse(result.reason, result.reason.contains("timed out", ignoreCase = true))
        assertFalse(result.reason, result.reason.startsWith("sent"))
        assertFalse(result.acceptanceObserved)
        assertFalse(result.authRequired)
        assertEquals(DiagStage.SUBMIT, result.stage)
        assertEquals(DiagCause.REJECTED, result.cause)
        assertEquals(0, accepted)
        assertTrue(h.fake.prompts.isEmpty())
        assertEquals(listOf(id), h.fake.notOwnedRefusals.toList())
        assertEquals(1, methods().count { it == "prompt.submit" })
    }

    @Test
    fun `the unsent text stays retryable and the status line says in use, not a generic rejection`() = runBlocking {
        val id = conversation()
        h.fake.turnBehavior = { "not_owned" }
        val result = h.core.chat.send(id, "ship the report") as ChatSendResult.Failed
        assertFalse(ComposerDispatch.delivered(result))
        val kept = ComposerDispatch.failure(DispatchedSend(7, id, "ship the report", emptyList()), result)
        assertNotNull(kept)
        assertEquals("ship the report", kept!!.send.text)
        val status = ComposerDispatch.statusSafe(result)
        assertTrue(status, status.contains("in use in the TUI or another Hermes runtime"))
        assertFalse(status, status.startsWith("Acceptance not observed"))
    }

    @Test
    fun `nothing is resent, taken over or created elsewhere, and a retry after release is accepted once`() = runBlocking {
        val id = conversation()
        h.fake.turnBehavior = { "not_owned" }
        assertTrue(h.core.chat.send(id, "first") is ChatSendResult.Failed)
        assertTrue(h.core.chat.send(id, "second") is ChatSendResult.Failed)
        assertEquals(2, methods().count { it == "prompt.submit" })
        assertEquals(listOf(id, id), h.fake.notOwnedRefusals.toList())
        assertTrue(h.fake.prompts.isEmpty())
        assertEquals(1, methods().count { it == "session.create" })
        assertTrue(methods().none { it == "config.set" })
        h.fake.turnBehavior = { "start_first" }
        assertTrue(h.core.chat.send(id, "second") is ChatSendResult.Replied)
        assertEquals(listOf(id to "second"), h.fake.prompts.toList())
    }

    @Test
    fun `queued images are detached when the session is in use so they cannot leak into a later turn`() = runBlocking {
        val id = conversation()
        h.fake.turnBehavior = { "not_owned" }
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
        val result = h.core.chat.send(id, "look", listOf(OutgoingAttachment("p.png", "image/png", png)))
        assertTrue(result is ChatSendResult.Failed)
        assertTrue(methods().contains("image.attach_bytes"))
        assertTrue(methods().contains("image.detach"))
        assertTrue(h.fake.queuedImages.values.all { it.isEmpty() })
        assertTrue(h.fake.prompts.isEmpty())
        assertTrue(h.fake.consumedImages.isEmpty())
    }

    @Test
    fun `an active session limit refusal is not reported as in use`() = runBlocking {
        val id = conversation()
        h.fake.turnBehavior = { "session_limit" }
        val result = h.core.chat.send(id, "hi") as ChatSendResult.Failed
        assertFalse(result.reason, result.reason.contains("in use in the TUI"))
        assertEquals(HermesPromptUncertainException(Exception()).message, result.reason)
        assertEquals(SendDelivery.POSSIBLY_SENT, result.delivery)
        assertFalse(result.acceptanceObserved)
        assertNull(ComposerDispatch.failure(DispatchedSend(1, id, "hi", emptyList()), result))
        assertEquals(DiagCause.REJECTED, result.cause)
    }

    @Test
    fun `the gateway refusal keeps its code and carries the in-use reason`() = runBlocking {
        val id = conversation()
        h.fake.turnBehavior = { "not_owned" }
        val dashboard = HermesDashboardClient(HermesDashboardEndpoint.parse(h.fake.baseUrl), h.http, h.tokens)
        val connector = HermesGatewayConnector(dashboard, h.http)
        val port = GatewayConversationPort { connector.connection() }
        try {
            port.submit(id, "hello", emptyList())
            fail("expected the refusal")
        } catch (error: HermesRpcException) {
            assertEquals(4090, error.code)
            assertEquals("SessionInUseException", error.javaClass.simpleName)
        }
        connector.close()
        assertTrue(h.fake.prompts.isEmpty())
    }

    @Test
    fun `a resume timeout is a timeout at the resume stage, tried once, and never an in-use report`() = runBlocking {
        val rpc = ResumeTimeoutRpc()
        val port = GatewayConversationPort { rpc }
        val error = try {
            port.submit("stored-1", "hello", emptyList())
            throw AssertionError("expected the timeout")
        } catch (timeout: HermesProtocolException) {
            timeout
        }
        assertEquals(listOf("session.resume"), rpc.calls)
        assertEquals(DiagCause.TIMEOUT, DiagClassify.of(error).cause)
        val thrown: Throwable = error
        assertFalse(thrown is HermesRpcException)
        assertFalse(error.javaClass.simpleName == "SessionInUseException")
        assertFalse(error.message.orEmpty().contains("in use"))
    }

    private class ResumeTimeoutRpc : GatewayRpc {
        val calls = mutableListOf<String>()

        override suspend fun call(method: String, params: JSONObject, timeoutMs: Long): JSONObject {
            calls += method
            val cause = try {
                withTimeout(1) { delay(5_000) }
                null
            } catch (timeout: TimeoutCancellationException) {
                timeout
            }
            throw HermesProtocolException("gateway call $method timed out", cause)
        }

        override fun subscribe(sessionId: String): GatewaySubscription = throw AssertionError("not subscribed after a failed resume")
    }
}
