package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import com.rumi.hermesvoice.core.net.*
import com.rumi.hermesvoice.core.settings.*
import com.rumi.hermesvoice.core.voice.*
import com.rumi.hermesvoice.core.watchlink.*
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Watch recording whose transcription finally fails must reach THAT Watch, for THAT request, as a terminal state that says it was
 * not sent. Real WatchTurnIntake and VoiceTurnOrchestrator; the speech gateway and the Watch transport are in-test doubles.
 * Synthetic recording bytes only.
 */
class WatchFinalFailureTest {
    private val submitted: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val transcribeCalls = AtomicInteger()
    private var transcript = "move my meeting"
    private var stuck = false
    private var failWith: (Int) -> Throwable? = { null }
    private val entries = listOf(DestinationEntry("work", "work_session"))

    private val speech = object : HermesSpeechGateway {
        override suspend fun transcribe(audio: ByteArray, mimeType: String): String {
            val n = transcribeCalls.incrementAndGet()
            if (stuck) awaitCancellation()
            failWith(n)?.let { throw it }
            return transcript
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
    private val policy = SttRecoveryPolicy(budgetMs = 60_000, maxAttempts = 2, backoffMs = listOf(1_000))
    private val orchestrator = VoiceTurnOrchestrator(speech, conversations,
        config = { VoiceTurnConfig("router_session", DestinationAllowlist.create(entries, "router_session"), ResponsePlaybackSettings()) },
        inputGate = { _, _ -> AudioInputVerdict.USABLE }, sttPolicy = policy)
    private val intake = WatchTurnIntake(orchestrator, acks)

    private inner class Transport(override val nodeId: String = "watch-node", private val reachable: () -> Boolean = { true }) : WatchTransport {
        val states: MutableList<TurnStateMessage> = Collections.synchronizedList(mutableListOf())
        val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun sendMessage(path: String, bytes: ByteArray) {
            paths += path
            if (!reachable()) throw java.io.IOException("watch not reachable")
            if (path == WatchLinkPaths.STATE) TurnStateMessage.decode(bytes)?.let { states += it }
        }

        override suspend fun sendChannel(path: String, bytes: ByteArray) {
            val play = PlayRequest.fromFrame(LinkFrame.decode(bytes))
            acks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, true).encode())
        }

        val terminals get() = states.filter { it.terminal }
    }

    private fun upload(turnId: String) = WatchTurnUpload(turnId, TurnTrigger.PUSH_TO_TALK, WatchTurnUpload.MIME_WAV, ByteArray(300) { 7 })

    private suspend fun send(turnId: String, transport: Transport) =
        intake.onTurnChannel(WatchLinkPaths.turnPath(turnId), upload(turnId).toFrame().encode(), transport)

    private fun assertNotSentTerminal(turnId: String, transport: Transport) {
        val terminal = transport.terminals.singleOrNull()
        assertTrue("exactly one terminal state reached the originating Watch: ${transport.states.map { it.stage to it.detail }}", terminal != null)
        assertEquals("tied to the exact request", turnId, terminal!!.turnId)
        assertEquals("done", terminal.stage)
        assertTrue("says the request was not sent: '${terminal.detail}'", terminal.detail.startsWith("Not sent"))
        assertFalse("does not pretend an answer is coming: '${terminal.detail}'", terminal.detail.contains("Waiting", ignoreCase = true))
        assertTrue("nothing was submitted", submitted.isEmpty())
    }

    @Test
    fun `a final HTTP 400 rejection of the recording ends as not sent for that request on that Watch`() = runBlocking {
        failWith = { HermesHttpException(400, SttSafeCode.message(SttCode.INPUT_REJECTED), SttCode.INPUT_REJECTED) }
        val watch = Transport()
        val outcome = withTimeout(30_000) { send("watch-ff-001", watch) }
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.NotDelivered)
        assertEquals("a rejected recording is not tried again", 1, transcribeCalls.get())
        assertNotSentTerminal("watch-ff-001", watch)
    }

    @Test
    fun `a final provider failure of the transcription ends as not sent for that request on that Watch`() = runBlocking {
        failWith = { HermesHttpException(400, SttSafeCode.message(SttCode.TRANSCRIPTION_FAILED), SttCode.TRANSCRIPTION_FAILED) }
        val watch = Transport()
        val outcome = withTimeout(30_000) { send("watch-ff-002", watch) }
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.NotDelivered)
        assertNotSentTerminal("watch-ff-002", watch)
    }

    @Test
    fun `a transport time-out that outlasts the retries ends as not sent after exactly one waiting notice`() = runBlocking {
        failWith = { HermesSttTransportException(SttTransport.TIMEOUT, SocketTimeoutException("synthetic")) }
        val watch = Transport()
        val outcome = withTimeout(30_000) { send("watch-ff-003", watch) }
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.NotDelivered)
        assertEquals(2, transcribeCalls.get())
        assertEquals("the retry is told once, not per attempt", 1, watch.states.count { it.stage == "stt_recovering" })
        assertTrue("the retry notice is not terminal", watch.states.filter { it.stage == "stt_recovering" }.none { it.terminal })
        assertNotSentTerminal("watch-ff-003", watch)
    }

    @Test
    fun `a transcription that stops with an unexpected error still ends the request as not sent`() = runBlocking {
        failWith = { IllegalStateException("synthetic unexpected failure") }
        val watch = Transport()
        runCatching { withTimeout(30_000) { send("watch-ff-004", watch) } }
        assertNotSentTerminal("watch-ff-004", watch)
    }

    @Test
    fun `a request cancelled on the phone before it was sent says it was not sent`() = runBlocking {
        stuck = true
        val watch = Transport()
        val running = launch(Dispatchers.Default) { runCatching { send("watch-ff-005", watch) } }
        withTimeout(10_000) { while (transcribeCalls.get() == 0) delay(20) }
        running.cancel()
        withTimeout(10_000) { running.join() }
        assertNotSentTerminal("watch-ff-005", watch)
    }

    @Test
    fun `the Watch Stop during transcription ends as stopped and is not reported as a failure`() = runBlocking {
        stuck = true
        val watch = Transport()
        val running = launch(Dispatchers.Default) { send("watch-ff-006", watch) }
        withTimeout(10_000) { while (transcribeCalls.get() == 0) delay(20) }
        assertTrue(intake.cancelTurn("watch-ff-006", "watch-node"))
        withTimeout(10_000) { running.join() }
        val terminal = watch.terminals.single()
        assertEquals("Stopped", terminal.detail)
        assertFalse(terminal.detail.startsWith("Not sent"))
        assertTrue(submitted.isEmpty())
    }

    @Test
    fun `a backend failure that recovers sends no failure state at any point`() = runBlocking {
        failWith = { n -> if (n == 1) HermesHttpException(400, SttSafeCode.message(SttCode.UNAVAILABLE), SttCode.UNAVAILABLE) else null }
        val watch = Transport()
        val outcome = withTimeout(30_000) { send("watch-ff-007", watch) }
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.Completed)
        assertEquals(1, watch.states.count { it.stage == "stt_recovering" })
        assertTrue("no state said the request was not sent: ${watch.states.map { it.detail }}", watch.states.none { it.detail.startsWith("Not sent") || it.detail.startsWith("Not delivered") })
        assertEquals(listOf("move my meeting"), submitted.toList())
    }

    @Test
    fun `a quiet recording that has no speech is reported as no speech and not as a failure`() = runBlocking {
        transcript = "   "
        val watch = Transport()
        val outcome = withTimeout(30_000) { send("watch-ff-008", watch) }
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.NoSpeech)
        val terminal = watch.terminals.single()
        assertEquals("No speech detected", terminal.detail)
        assertTrue(submitted.isEmpty())
    }

    @Test
    fun `a failure reaches only the Watch node that sent the request`() = runBlocking {
        failWith = { HermesHttpException(400, SttSafeCode.message(SttCode.INPUT_REJECTED), SttCode.INPUT_REJECTED) }
        val sender = Transport("watch-node-a")
        val bystander = Transport("watch-node-b")
        withTimeout(30_000) { send("watch-ff-009", sender) }
        assertEquals(1, sender.terminals.size)
        assertTrue("another node was sent nothing", bystander.paths.isEmpty())
    }

    @Test
    fun `a Watch that cannot be reached when the failure ends does not break the turn`() = runBlocking {
        failWith = { HermesHttpException(400, SttSafeCode.message(SttCode.INPUT_REJECTED), SttCode.INPUT_REJECTED) }
        val away = Transport(reachable = { false })
        val outcome = withTimeout(30_000) { send("watch-ff-010", away) }
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.NotDelivered)
        assertTrue(away.terminals.isEmpty())
        assertTrue(submitted.isEmpty())
    }
}
