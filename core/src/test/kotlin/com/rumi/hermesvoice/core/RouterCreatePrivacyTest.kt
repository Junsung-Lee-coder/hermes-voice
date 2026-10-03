package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.net.ArchivedFilter
import com.rumi.hermesvoice.core.net.GatewayConversationPort
import com.rumi.hermesvoice.core.net.GatewayRpc
import com.rumi.hermesvoice.core.net.GatewaySubscription
import com.rumi.hermesvoice.core.net.HermesSpeechGateway
import com.rumi.hermesvoice.core.net.HistoryPage
import com.rumi.hermesvoice.core.net.RuntimeSetupLimits
import com.rumi.hermesvoice.core.net.StoredSession
import com.rumi.hermesvoice.core.net.StoredSessionPage
import com.rumi.hermesvoice.core.sessions.AppSessionRepository
import com.rumi.hermesvoice.core.sessions.HermesSessionsApi
import com.rumi.hermesvoice.core.sessions.OwnedRole
import com.rumi.hermesvoice.core.sessions.OwnedSession
import com.rumi.hermesvoice.core.sessions.OwnedSessionRegistry
import com.rumi.hermesvoice.core.sessions.RouterRuntime
import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.RoutingContract
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B37-O2: the FIRST hidden-router `session.create` failing (no router registered yet). The gateway's own error text must
 * never reach the user-visible outcome or any diagnostic; the ending names router creation, says an unanswered create's
 * result is unknown, registers nothing and transcribes/sends nothing. Auth and cancellation keep their own handling; an
 * existing (older-contract) router is kept when its replacement can't be created. FAKE sentinel only.
 * Runs at the real seam: HermesVoiceCore -> VoiceTurnOrchestrator -> AppSessionRepository -> GatewayConversationPort.
 */
class RouterCreatePrivacyTest {
    private val sentinel = "/home/rv-sentinel/.hermes/profiles/zz/RVSENTINEL-FAKE-0000"

    private class Api : HermesSessionsApi {
        override suspend fun listSessions(source: String, archived: ArchivedFilter, limit: Int, offset: Int): StoredSessionPage = error("unused")
        override suspend fun getSession(sessionId: String): StoredSession? = error("unused")
        override suspend fun getMessages(sessionId: String, limit: Int, offset: Int): HistoryPage = error("unused")
        override suspend fun setArchived(sessionId: String, archived: Boolean) = error("unused")
    }

    private class Speech : HermesSpeechGateway {
        val transcribed = AtomicInteger()
        override suspend fun transcribe(audio: ByteArray, mimeType: String): String { transcribed.incrementAndGet(); return "hello" }
        override suspend fun speak(text: String): SpokenAudio = error("nothing is spoken")
    }

    /** A gateway whose session.create fails with [failure]; every other method is recorded and refused. */
    private class Gateway(val failure: suspend () -> Nothing) : GatewayRpc {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun call(method: String, params: JSONObject, timeoutMs: Long): JSONObject {
            calls += method
            if (method == "session.create") failure()
            error("unexpected $method")
        }
        override fun subscribe(sessionId: String): GatewaySubscription = error("unused")
    }

    private class Run(val outcome: VoiceTurnOutcome?, val lines: List<String>, val speech: Speech, val registry: OwnedSessionRegistry, val gateway: Gateway)

    private fun firstUse(failure: suspend () -> Nothing): Run {
        val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val listener = object : VoiceTurnListener {
            override fun onDiagnostic(turnId: String, stage: String, detail: String) { lines += "$stage $detail" }
        }
        val speech = Speech()
        val gateway = Gateway(failure)
        val registry = OwnedSessionRegistry(InMemoryKeyValueStore())
        val core = HermesVoiceCore(speech, Api(), GatewayConversationPort(null, RuntimeSetupLimits()) { gateway }, registry,
            AppSettings(InMemoryKeyValueStore()).apply { routingEnabled = true }, listener)
        val outcome = runBlocking { withTimeoutOrNull(15_000) {
            core.orchestrator.run(VoiceTurnRequest("o2-${System.nanoTime()}", VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav", PlaybackSink { _, _ -> }))
        } }
        return Run(outcome, lines.toList(), speech, registry, gateway)
    }

    private fun assertSafe(name: String, run: Run) {
        val text = "${run.outcome}"
        assertFalse("$name leaked into the outcome: $text", text.contains("RVSENTINEL") || text.contains("/home/") || text.contains("rv-sentinel"))
        assertTrue("$name leaked into a diagnostic: ${run.lines}", run.lines.none { it.contains("RVSENTINEL") || it.contains("/home/") })
        assertEquals("$name: nothing transcribed", 0, run.speech.transcribed.get())
        assertTrue("$name: nothing registered", run.registry.all().isEmpty())
        assertEquals("$name: only the one create, nothing submitted", listOf("session.create"), run.gateway.calls.toList())
    }

    @Test
    fun `a create refused with a server error shows its code only and names router creation`() {
        val run = firstUse { throw HermesRpcException(5000, "create failed: $sentinel") }
        val outcome = run.outcome
        assertTrue("$outcome", outcome is VoiceTurnOutcome.NotDelivered && !outcome.authRequired)
        val reason = (outcome as VoiceTurnOutcome.NotDelivered).reason
        assertSafe("rpc", run)
        assertTrue(reason, reason.contains("router_create_refused") && reason.contains("code 5000") && reason.contains("your request was not sent"))
        assertFalse("no claim about the model switch", reason.contains("router model"))
        assertTrue(run.lines.toString(), run.lines.any { it.contains("router_create=failed reason=router_create_refused") })
    }

    @Test
    fun `a create whose answer is lost is reported as unknown, never as not created, and nothing is adopted`() {
        for ((name, failure) in listOf<Pair<String, suspend () -> Nothing>>(
                "protocol" to { throw HermesProtocolException("gateway connection failed: $sentinel") },
                "io" to { throw IOException("socket closed at $sentinel") })) {
            val run = firstUse(failure)
            val reason = (run.outcome as? VoiceTurnOutcome.NotDelivered)?.reason ?: "${run.outcome}"
            assertSafe(name, run)
            assertTrue("$name: $reason", reason.contains("router_create_unknown") && reason.contains("unknown"))
            assertFalse("$name: $reason", reason.contains("not created") || reason.contains("nothing was created"))
        }
    }

    @Test
    fun `an unexpected failure while creating is a safe failure with its class name only`() {
        val run = firstUse { throw IllegalStateException("bad payload $sentinel") }
        val reason = (run.outcome as? VoiceTurnOutcome.NotDelivered)?.reason ?: "${run.outcome}"
        assertSafe("generic", run)
        assertTrue(reason, reason.contains("router_create_failed") && reason.contains("IllegalStateException"))
    }

    @Test
    fun `CTRL an expired sign-in while creating stays a sign-in failure`() {
        val run = firstUse { throw HermesAuthRequiredException("Sign in to the dashboard again") }
        val outcome = run.outcome
        assertTrue("$outcome", outcome is VoiceTurnOutcome.NotDelivered && outcome.authRequired)
        assertEquals(0, run.speech.transcribed.get())
        assertTrue(run.registry.all().isEmpty())
    }

    @Test
    fun `CTRL a caller cancelling while the router is being created is cancelled, not a failure`() {
        val gateway = Gateway { awaitCancellation() }
        val registry = OwnedSessionRegistry(InMemoryKeyValueStore())
        val repo = AppSessionRepository(Api(), GatewayConversationPort(null, RuntimeSetupLimits()) { gateway }, registry, routerRuntime = RouterRuntime.LUNA_LOW)
        val job = CoroutineScope(Dispatchers.Default).async { repo.ensureRoutingSession() }
        runBlocking { delay(300) }
        job.cancel()
        val error = runBlocking { runCatching { job.await() }.exceptionOrNull() }
        assertTrue("$error", error is CancellationException)
        assertTrue(registry.all().isEmpty())
    }

    @Test
    fun `CTRL an older routing session is kept when its replacement can't be created, with the migration marker set`() {
        val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val rpc = object : GatewayRpc {
            override suspend fun call(method: String, params: JSONObject, timeoutMs: Long): JSONObject {
                calls += method
                return when (method) {
                    "session.create" -> throw HermesRpcException(5000, "create failed: $sentinel")
                    "session.resume" -> JSONObject().put("session_id", "rt-old").put("session_key", "s-old").put("running", false).put("status", "idle")
                        .put("info", JSONObject().put("model", "gpt-5.6-luna").put("provider", "openai-codex").put("reasoning_effort", "low"))
                    else -> error(method)
                }
            }
            override fun subscribe(sessionId: String): GatewaySubscription = error("unused")
        }
        val store = InMemoryKeyValueStore()
        val registry = OwnedSessionRegistry(store).apply {
            put(OwnedSession("s-old", OwnedRole.ROUTER, "Hermes Voice router", "", "", archived = false, createdAtMs = 1, contract = RoutingContract.VERSION - 1))
        }
        val repo = AppSessionRepository(Api(), GatewayConversationPort(null, RuntimeSetupLimits()) { rpc }, registry,
            routerRuntime = RouterRuntime.LUNA_LOW, diagnostic = { lines += it })
        val router = runBlocking { repo.ensureRoutingSession() }
        assertEquals("s-old", router.storedSessionId)
        assertFalse(registry.router()!!.retired)
        assertEquals(RoutingContract.VERSION.toString(), store.getString(AppSessionRepository.KEY_ROUTER_MIGRATION))
        assertEquals(listOf("session.create", "session.resume"), calls.toList())
        assertTrue(lines.none { it.contains("RVSENTINEL") })
    }
}
