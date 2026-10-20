package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import com.rumi.hermesvoice.core.net.*
import com.rumi.hermesvoice.core.settings.*
import com.rumi.hermesvoice.core.voice.*
import com.rumi.hermesvoice.core.watchlink.*
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R11: a Watch request that ended after the Phone began handing it to Hermes ("Not delivered", "Acceptance uncertain") is a terminal
 * failure the Watch must still be able to show when its live message is lost, exactly like "Not sent". Real WatchTurnIntake and
 * VoiceTurnOrchestrator; the speech gateway, destination and Watch transport are in-test doubles. Synthetic recording bytes only.
 * Retaining a failure never submits anything again.
 */
class WatchTerminalFailureRetentionR11Test {
    private val submits = AtomicInteger()
    private var submitFails: Throwable? = null
    private val entries = listOf(DestinationEntry("work", "work_session"))

    private val speech = object : HermesSpeechGateway {
        override suspend fun transcribe(audio: ByteArray, mimeType: String) = "move my meeting"
        override suspend fun speak(text: String) = SpokenAudio(text.toByteArray(), "audio/mpeg")
    }

    private inner class Turn(private val events: List<RecipientEvent>) : SubmittedTurn {
        override val submitStatus = "streaming"
        override val attributable = true
        override suspend fun collect(timeoutMs: Long, onEvent: suspend (RecipientEvent) -> Unit) { for (event in events) onEvent(event) }
        override fun release() {}
    }

    private val conversations = object : HermesConversationPort {
        override suspend fun create(source: String, title: String, seedInstruction: String, hidden: Boolean) = throw UnsupportedOperationException()
        override suspend fun submit(storedSessionId: String, text: String, attachments: List<OutgoingAttachment>): SubmittedTurn {
            if (storedSessionId == "router_session") return Turn(listOf(RecipientEvent.Complete("""{"destination":"work","ack":"Sending."}""", "complete")))
            submits.incrementAndGet()
            submitFails?.let { throw it }
            return Turn(listOf(RecipientEvent.Complete("final", "complete")))
        }
    }

    private val acks = WatchAckRegistry()
    private val file = InMemoryKeyValueStore()
    private val outbox = WatchFailureOutbox(file)

    private fun orchestrator() = VoiceTurnOrchestrator(speech, conversations,
        config = { VoiceTurnConfig("router_session", DestinationAllowlist.create(entries, "router_session"), ResponsePlaybackSettings()) },
        inputGate = { _, _ -> AudioInputVerdict.USABLE }, sttPolicy = SttRecoveryPolicy(budgetMs = 60_000, maxAttempts = 2, backoffMs = listOf(1_000)))

    private fun intake() = WatchTurnIntake(orchestrator(), acks, failures = outbox)

    private inner class Transport(override val nodeId: String = "watch-node", private val reachable: () -> Boolean = { true }) : WatchTransport {
        val states: MutableList<TurnStateMessage> = Collections.synchronizedList(mutableListOf())
        override suspend fun sendMessage(path: String, bytes: ByteArray) {
            if (!reachable()) throw java.io.IOException("watch not reachable")
            if (path == WatchLinkPaths.STATE) TurnStateMessage.decode(bytes)?.let { states += it }
        }

        override suspend fun sendChannel(path: String, bytes: ByteArray) {
            val play = PlayRequest.fromFrame(LinkFrame.decode(bytes))
            acks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, true).encode())
        }
    }

    private suspend fun send(turnId: String, transport: Transport) =
        intake().onTurnChannel(WatchLinkPaths.turnPath(turnId),
            WatchTurnUpload(turnId, TurnTrigger.PUSH_TO_TALK, WatchTurnUpload.MIME_WAV, ByteArray(300) { 7 }).toFrame().encode(), transport)

    private fun owedFor(turnId: String) = WatchFailureOutbox(file).owedTo("watch-node").filter { it.turnId == turnId }

    @Test
    fun `T1 a refused hand over to Hermes is owed to the Watch node until it acknowledges, though the Watch is unreachable`() = runBlocking {
        submitFails = HermesHttpException(403, "synthetic refusal")
        val outcome = withTimeout(30_000) { send("watch-tf-001", Transport(reachable = { false })) }
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.NotDelivered)
        val owed = owedFor("watch-tf-001")
        assertEquals("kept for exactly that request and node: ${outbox.owedTo("watch-node")}", 1, owed.size)
        assertTrue(owed.single().detail, owed.single().detail.startsWith("Not delivered"))
        assertEquals("the hand over was tried once and never repeated", 1, submits.get())
        assertFalse("another node is owed nothing", outbox.owedTo("watch-node-b").isNotEmpty())
        assertFalse("another node cannot settle it", WatchFailureOutbox(file).acknowledge("watch-tf-001", "watch-node-b"))
        assertTrue(WatchFailureOutbox(file).acknowledge("watch-tf-001", "watch-node"))
        assertTrue(WatchFailureOutbox(file).owedTo("watch-node").isEmpty())
        assertEquals(1, submits.get())
    }

    @Test
    fun `T2 an uncertain acceptance is owed to the Watch node as uncertain and nothing is submitted again`() = runBlocking {
        submitFails = HermesPromptUncertainException(java.io.IOException("synthetic"))
        val outcome = withTimeout(30_000) { send("watch-tf-002", Transport(reachable = { false })) }
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.NotDelivered && (outcome as VoiceTurnOutcome.NotDelivered).possiblySent)
        val owed = owedFor("watch-tf-002")
        assertEquals(1, owed.size)
        assertTrue(owed.single().detail, owed.single().detail.startsWith("Acceptance uncertain"))
        assertEquals(1, submits.get())
    }

    @Test
    fun `T3 a failure of the hand over reaches a reachable Watch at once and is also kept until acknowledged`() = runBlocking {
        submitFails = HermesHttpException(500, "synthetic fault")
        val watch = Transport()
        withTimeout(30_000) { send("watch-tf-003", watch) }
        val terminal = watch.states.single { it.terminal }
        assertTrue(terminal.detail, terminal.detail.startsWith("Not delivered"))
        assertEquals(terminal.detail, owedFor("watch-tf-003").singleOrNull()?.detail)
        assertEquals(1, submits.get())
    }

    @Test
    fun `T4 a Watch that rebuilds still has the failure and the owed text is the fixed status text only`() = runBlocking {
        submitFails = HermesHttpException(403, "synthetic refusal with details that must not be kept")
        withTimeout(30_000) { send("watch-tf-004", Transport(reachable = { false })) }
        val persisted = file.getString(WatchFailureOutbox.KEY).orEmpty()
        assertTrue(persisted, persisted.contains("watch-tf-004"))
        assertFalse("no transcript is kept: $persisted", persisted.contains("move my meeting"))
        assertEquals(1, WatchFailureOutbox(file).owedTo("watch-node").count { it.turnId == "watch-tf-004" })
    }

    @Test
    fun `T5 a request that completed is not owed as a failure`() = runBlocking {
        val watch = Transport()
        val outcome = withTimeout(30_000) { send("watch-tf-005", watch) }
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.Completed)
        assertTrue(outbox.owedTo("watch-node").isEmpty())
        assertEquals(1, submits.get())
    }
}
