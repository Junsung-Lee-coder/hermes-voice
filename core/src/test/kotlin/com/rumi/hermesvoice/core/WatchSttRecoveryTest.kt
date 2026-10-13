package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import com.rumi.hermesvoice.core.net.*
import com.rumi.hermesvoice.core.settings.*
import com.rumi.hermesvoice.core.voice.*
import com.rumi.hermesvoice.core.watchlink.*
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Watch recording is transcribed by the Phone only: the Phone waits for the speech server and tries again with the same
 * recording, the Watch is told once that it is waiting, and the turn is delivered exactly once. Real clock, shortened backoff.
 */
class WatchSttRecoveryTest {
    private val timeline: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val transcribeCalls = AtomicInteger()
    private val submitted: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var failures = 0
    private var failWith: () -> Throwable = { HermesHttpException(400, SttSafeCode.message(SttCode.UNAVAILABLE), SttCode.UNAVAILABLE) }
    private val entries = listOf(DestinationEntry("work", "work_session"))

    private val speech = object : HermesSpeechGateway {
        override suspend fun transcribe(audio: ByteArray, mimeType: String): String {
            val n = transcribeCalls.incrementAndGet()
            timeline += "transcribe:$n:${audio.size}"
            if (n <= failures) throw failWith()
            return "move my meeting"
        }

        override suspend fun speak(text: String) = SpokenAudio(text.toByteArray(), "audio/mpeg")
    }

    private class Turn(private val events: List<RecipientEvent>) : SubmittedTurn {
        override val submitStatus = "streaming"
        override val attributable = true
        override suspend fun collect(timeoutMs: Long, onEvent: suspend (RecipientEvent) -> Unit) {
            for (event in events) onEvent(event)
        }

        override fun release() {}
    }

    private val conversations = object : HermesConversationPort {
        override suspend fun create(source: String, title: String, seedInstruction: String, hidden: Boolean) = throw UnsupportedOperationException()
        override suspend fun submit(storedSessionId: String, text: String, attachments: List<OutgoingAttachment>): SubmittedTurn {
            if (storedSessionId == "router_session") return Turn(listOf(RecipientEvent.Complete("""{"destination":"work","ack":"Sending."}""", "complete")))
            submitted += voiceWords(text)
            return Turn(listOf(RecipientEvent.Complete("final", "complete")))
        }
    }

    private val acks = WatchAckRegistry()
    private val policy = SttRecoveryPolicy(budgetMs = 60_000, maxAttempts = 6, backoffMs = listOf(1_000, 1_000, 1_000, 1_000, 1_000))
    private val orchestrator = VoiceTurnOrchestrator(speech, conversations,
        config = { VoiceTurnConfig("router_session", DestinationAllowlist.create(entries, "router_session"), ResponsePlaybackSettings()) },
        inputGate = { _, _ -> AudioInputVerdict.USABLE }, sttPolicy = policy)
    private val intake = WatchTurnIntake(orchestrator, acks)

    private inner class Transport : WatchTransport {
        override val nodeId = "watch-node"
        val states: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun sendMessage(path: String, bytes: ByteArray) {
            if (path == WatchLinkPaths.STATE) TurnStateMessage.decode(bytes)?.let { states += it.stage; timeline += "state:${it.stage}" }
        }

        override suspend fun sendChannel(path: String, bytes: ByteArray) {
            val play = PlayRequest.fromFrame(LinkFrame.decode(bytes))
            acks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, true).encode())
        }
    }

    private fun upload(turnId: String) = WatchTurnUpload(turnId, TurnTrigger.PUSH_TO_TALK, WatchTurnUpload.MIME_WAV, ByteArray(300) { 7 })

    private suspend fun send(turnId: String, transport: Transport) =
        intake.onTurnChannel(WatchLinkPaths.turnPath(turnId), upload(turnId).toFrame().encode(), transport)

    @Test
    fun `the Phone transcribes a Watch recording again with the same bytes and delivers once`() = runBlocking {
        failures = 2
        val transport = Transport()
        val outcome = withTimeout(30_000) { send("watch-stt-001", transport) }
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.Completed)
        assertEquals(3, transcribeCalls.get())
        assertEquals("every attempt used the one retained recording", listOf("transcribe:1:300", "transcribe:2:300", "transcribe:3:300"),
            timeline.filter { it.startsWith("transcribe") })
        assertEquals("delivered exactly once", listOf("move my meeting"), submitted.toList())
        assertTrue(transport.states.toString(), transport.states.contains("stt_recovering"))
        assertEquals("done", transport.states.last())
        assertEquals("the Watch is told it is waiting once, not per attempt", 1, transport.states.count { it == "stt_recovering" })
    }

    @Test
    fun `a Watch recording whose server never recovers ends with a stable transient failure and is never delivered`() = runBlocking {
        failures = Int.MAX_VALUE
        val transport = Transport()
        val outcome = withTimeout(30_000) { send("watch-stt-002", transport) }
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.NotDelivered)
        assertTrue(transcribeCalls.get() in 2..6)
        assertTrue(submitted.isEmpty())
        assertEquals("done", transport.states.last())
    }

    @Test
    fun `a rejected recording from the Watch is not retried`() = runBlocking {
        failures = Int.MAX_VALUE
        failWith = { HermesHttpException(400, SttSafeCode.message(SttCode.INPUT_REJECTED), SttCode.INPUT_REJECTED) }
        val transport = Transport()
        val outcome = withTimeout(30_000) { send("watch-stt-003", transport) }
        assertTrue(outcome is VoiceTurnOutcome.NotDelivered)
        assertEquals(1, transcribeCalls.get())
        assertFalse(transport.states.contains("stt_recovering"))
    }

    @Test
    fun `the Watch Stop during the wait ends exactly that request and nothing is transcribed afterwards`() = runBlocking {
        failures = Int.MAX_VALUE
        val transport = Transport()
        val running = async { send("watch-stt-004", transport) }
        withTimeout(10_000) { while (!transport.states.contains("stt_recovering")) delay(20) }
        assertTrue(intake.cancelTurn("watch-stt-004", "watch-node"))
        val outcome = withTimeout(10_000) { running.await() }
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.Stopped)
        val calls = transcribeCalls.get()
        delay(2_500)
        assertEquals("no retry after Stop", calls, transcribeCalls.get())
        assertTrue(submitted.isEmpty())
    }

    @Test
    fun `a request on the same destination is not held back while another recording waits for the server`() = runBlocking {
        failures = 1
        val first = Transport()
        val a = async { send("watch-stt-005", first) }
        withTimeout(10_000) { while (!first.states.contains("stt_recovering")) delay(20) }
        val second = Transport()
        val b = withTimeout(20_000) { send("watch-stt-006", second) }
        assertTrue(b.toString(), b is VoiceTurnOutcome.Completed)
        assertTrue(withTimeout(20_000) { a.await() } is VoiceTurnOutcome.Completed)
        assertEquals(2, submitted.size)
    }
}
