package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import com.rumi.hermesvoice.core.auth.*
import com.rumi.hermesvoice.core.diag.*
import com.rumi.hermesvoice.core.net.*
import com.rumi.hermesvoice.core.settings.*
import com.rumi.hermesvoice.core.sessions.*
import com.rumi.hermesvoice.core.voice.*
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uses only interfaces that already existed before the speech-recovery work, through the real dashboard client and the dashboard
 * test double: a temporarily unavailable speech server (the plugin's safe 400 envelope) is waited out and the same recording is
 * transcribed again, an unprefixed legacy 400 is still final, and a request that failed is never reported delivered just because its
 * transmission ended.
 */
class SttRecoveryBehaviorTest {
    private val fake = FakeHermesDashboard()
    private val tokens = InMemoryHermesTokenStore()
    private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    private val client = HermesDashboardClient(HermesDashboardEndpoint.parse(fake.baseUrl), http, tokens)
    private val transcribeCalls = AtomicInteger()
    private val submitted: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val allowlist = DestinationAllowlist.create(listOf(DestinationEntry("work", "work_session")), "router_session")

    @After fun tearDown() = fake.close()

    private class Scripted(private val events: Channel<RecipientEvent>) : SubmittedTurn {
        override val submitStatus = "streaming"
        override val attributable = true
        override suspend fun collect(timeoutMs: Long, onEvent: suspend (RecipientEvent) -> Unit) {
            for (event in events) {
                onEvent(event)
                if (event is RecipientEvent.Complete) return
            }
        }
        override fun release() {}
    }

    private val conversations = object : HermesConversationPort {
        override suspend fun create(source: String, title: String, seedInstruction: String, hidden: Boolean) =
            throw UnsupportedOperationException()

        override suspend fun submit(storedSessionId: String, text: String, attachments: List<OutgoingAttachment>): SubmittedTurn {
            val reply = if (storedSessionId == "router_session") """{"destination":"work","ack":"Sending to work."}""" else "done"
            if (storedSessionId != "router_session") submitted += voiceWords(text)
            return Scripted(Channel<RecipientEvent>(Channel.UNLIMITED).apply { trySend(RecipientEvent.Complete(reply, "complete")) })
        }
    }

    private fun failFirst(count: Int, body: String) {
        val original = fake.server.dispatcher
        fake.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/audio/transcribe" && transcribeCalls.incrementAndGet() <= count) {
                    return MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json").setBody(body)
                }
                return original.dispatch(request)
            }
        }
    }

    private val diag = DiagLog(clock = { System.nanoTime() / 1_000_000 })

    private fun orchestrator() = VoiceTurnOrchestrator(client, conversations,
        inputGate = { _, _ -> AudioInputVerdict.USABLE },
        config = { VoiceTurnConfig("router_session", allowlist, ResponsePlaybackSettings()) },
        listener = DiagVoiceListener(object : VoiceTurnListener {}, diag))

    private fun request() = VoiceTurnRequest("turn-1", VoiceOrigin.PHONE, byteArrayOf(1, 2, 3), "audio/wav", PlaybackSink { _, _ -> })

    private fun signIn() = tokens.save(HermesBearerSession(fake.accessToken, fake.refreshToken, null, "basic", "jun"))

    @Test
    fun `a temporarily unavailable speech server is waited out and the recording is delivered once`() = runBlocking {
        signIn()
        failFirst(1, """{"detail":"[hermes-stt:stt_unavailable] speech recognition is temporarily unavailable"}""")
        val outcome = orchestrator().run(request())
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.Completed)
        assertEquals(2, transcribeCalls.get())
        assertEquals(listOf(fake.transcript), submitted.toList())
    }

    @Test
    fun `an unprefixed legacy 400 is final and never retried`() = runBlocking {
        signIn()
        failFirst(5, """{"detail":"Transcription failed"}""")
        val outcome = orchestrator().run(request())
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.NotDelivered)
        assertEquals(1, transcribeCalls.get())
        assertTrue(submitted.isEmpty())
        assertFalse("a request that never reached the server is not reported delivered: ${diag.snapshot().map { it.code }}",
            DiagCode.REQUEST_DELIVERED in diag.snapshot().map { it.code })
    }

    @Test
    fun `a failed request is not reported delivered when its transmission ends`() {
        val log = DiagLog(clock = { 0L })
        val listener = DiagVoiceListener(object : VoiceTurnListener {}, log)
        listener.onAccepted("t", VoiceOrigin.PHONE)
        listener.onOutcome("t", VoiceTurnOutcome.NotDelivered(VoiceTurnStage.TRANSCRIBING, "failed"))
        listener.onTransmitted("t")
        val codes = log.snapshot().map { it.code }
        assertFalse("a failed request is never reported delivered: $codes", DiagCode.REQUEST_DELIVERED in codes)
    }
}
