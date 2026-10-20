package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import com.rumi.hermesvoice.core.net.*
import com.rumi.hermesvoice.core.settings.*
import com.rumi.hermesvoice.core.voice.*
import com.rumi.hermesvoice.core.watchlink.*
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
 * Review finding F05 (and F02 for the Phone's outbox): every genuine terminal "not sent" outcome of a Watch request, including a
 * refusal before admission, is kept in the exact-request, exact-node outbox until THAT node acknowledges it, survives a rebuilt
 * Phone process, and is never claimed for an outcome that is not a failure to send. Real WatchTurnIntake and VoiceTurnOrchestrator;
 * the speech gateway, destination and Watch transport are in-test doubles. Synthetic recording bytes only.
 */
class WatchNotSentOutboxTest {
    private val submitted: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val transcribeCalls = AtomicInteger()
    private var transcript = "move my meeting"
    private var stuck = false
    private var failWith: (Int) -> Throwable? = { null }
    private var destinationFails = false
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

    private inner class Turn(private val events: List<RecipientEvent>, private val fails: Boolean = false) : SubmittedTurn {
        override val submitStatus = "streaming"
        override val attributable = true
        override suspend fun collect(timeoutMs: Long, onEvent: suspend (RecipientEvent) -> Unit) {
            if (fails) throw HermesProtocolException("destination went away")
            for (event in events) onEvent(event)
        }

        override fun release() {}
    }

    private val conversations = object : HermesConversationPort {
        override suspend fun create(source: String, title: String, seedInstruction: String, hidden: Boolean) = throw UnsupportedOperationException()
        override suspend fun submit(storedSessionId: String, text: String, attachments: List<OutgoingAttachment>): SubmittedTurn {
            if (storedSessionId == "router_session") return Turn(listOf(RecipientEvent.Complete("""{"destination":"work","ack":"Sending."}""", "complete")))
            submitted += voiceWords(text)
            return Turn(listOf(RecipientEvent.Complete("final", "complete")), fails = destinationFails)
        }
    }

    private val acks = WatchAckRegistry()
    private val file = InMemoryKeyValueStore()
    private val outbox = WatchFailureOutbox(file)

    private fun orchestrator(admission: (VoiceTurnRequest) -> String? = { null }, maxPending: Int = VoiceTurnOrchestrator.MAX_PENDING_TURNS) =
        VoiceTurnOrchestrator(speech, conversations,
            config = { VoiceTurnConfig("router_session", DestinationAllowlist.create(entries, "router_session"), ResponsePlaybackSettings()) },
            inputGate = { _, _ -> AudioInputVerdict.USABLE }, sttPolicy = SttRecoveryPolicy(budgetMs = 60_000, maxAttempts = 2, backoffMs = listOf(1_000)),
            admission = admission, maxPendingTurns = maxPending)

    private fun intake(orchestrator: VoiceTurnOrchestrator, routing: (WatchTurnUpload) -> TurnRouting = { TurnRouting.Model }) =
        WatchTurnIntake(orchestrator, acks, failures = outbox, routing = routing)

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

    private fun upload(turnId: String, trigger: TurnTrigger = TurnTrigger.PUSH_TO_TALK) =
        WatchTurnUpload(turnId, trigger, WatchTurnUpload.MIME_WAV, ByteArray(300) { 7 })

    private suspend fun send(intake: WatchTurnIntake, turnId: String, transport: Transport, trigger: TurnTrigger = TurnTrigger.PUSH_TO_TALK) =
        intake.onTurnChannel(WatchLinkPaths.turnPath(turnId), upload(turnId, trigger).toFrame().encode(), transport)

    private fun owedFor(turnId: String, store: KeyValueStore = file) =
        WatchFailureOutbox(store).owedTo("watch-node").filter { it.turnId == turnId }

    @Test
    fun `N1 a request refused because routing is off and nothing is selected is owed to that Watch node until it acknowledges, though the Watch is unreachable`() = runBlocking {
        val away = Transport(reachable = { false })
        val outcome = withTimeout(30_000) { send(intake(orchestrator(), routing = { TurnRouting.Direct(null) }), "watch-na-001", away) }
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.NotAdmitted)
        val owed = owedFor("watch-na-001")
        assertEquals("kept for exactly that request and node: ${outbox.owedTo("watch-node")}", 1, owed.size)
        assertTrue(owed.single().detail, owed.single().detail.startsWith("Not sent"))
        assertTrue("nothing was submitted", submitted.isEmpty())
        assertFalse("another node is owed nothing", outbox.owedTo("watch-node-b").isNotEmpty())
        val rebuilt = WatchFailureOutbox(file)
        assertFalse("another node cannot settle it", rebuilt.acknowledge("watch-na-001", "watch-node-b"))
        assertEquals("a rebuilt Phone process still owes it", 1, rebuilt.owedTo("watch-node").size)
        assertTrue(rebuilt.acknowledge("watch-na-001", "watch-node"))
        assertTrue(WatchFailureOutbox(file).owedTo("watch-node").isEmpty())
    }

    @Test
    fun `N2 a wake request that was answered elsewhere is owed to that Watch node, and the reachable Watch is also told at once`() = runBlocking {
        val watch = Transport()
        val outcome = withTimeout(30_000) { send(intake(orchestrator(admission = { "wake_claim_expired" })), "watch-na-002", watch, TurnTrigger.WAKE_PHRASE) }
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.NotAdmitted)
        val terminal = watch.states.single { it.terminal }
        assertEquals("watch-na-002", terminal.turnId)
        assertTrue(terminal.detail, terminal.detail.startsWith("Not sent"))
        assertEquals(1, owedFor("watch-na-002").size)
        assertEquals(terminal.detail, owedFor("watch-na-002").single().detail)
    }

    @Test
    fun `N3 a request refused because too many are pending is owed to that Watch node`() = runBlocking {
        val watch = Transport(reachable = { false })
        val outcome = withTimeout(30_000) { send(intake(orchestrator(maxPending = 0)), "watch-na-003", watch) }
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.NotAdmitted)
        assertEquals(1, owedFor("watch-na-003").size)
        assertTrue(owedFor("watch-na-003").single().detail.startsWith("Not sent"))
    }

    @Test
    fun `N4 a not-sent state the Phone could not save stays owed in this process and is saved once storage works again`() = runBlocking {
        val failing = FailingKeyValueStore()
        val owedBox = WatchFailureOutbox(failing)
        failing.failing = true
        val intake = WatchTurnIntake(orchestrator(), acks, failures = owedBox, routing = { TurnRouting.Direct(null) })
        withTimeout(30_000) { send(intake, "watch-na-004", Transport(reachable = { false })) }
        assertTrue(WatchFailureOutbox.KEY in failing.refusedKeys())
        assertEquals("still owed by this process", listOf("watch-na-004"), owedBox.owedTo("watch-node").map { it.turnId })
        failing.failing = false
        assertEquals(listOf("watch-na-004"), owedBox.owedTo("watch-node").map { it.turnId })
        assertTrue("saved once the file accepted writes: ${failing.persisted(WatchFailureOutbox.KEY)}",
            failing.persisted(WatchFailureOutbox.KEY)?.contains("watch-na-004") == true)
    }

    @Test
    fun `N4b a failure the Phone could not save is still owed by this process and is saved once storage works again`() = runBlocking {
        val failing = FailingKeyValueStore()
        val box = WatchFailureOutbox(failing)
        failing.failing = true
        box.add("watch-na-004b", "watch-node", "Not sent: test")
        assertEquals("still owed by this process", listOf("watch-na-004b"), box.owedTo("watch-node").map { it.turnId })
        failing.failing = false
        assertEquals(listOf("watch-na-004b"), box.owedTo("watch-node").map { it.turnId })
        assertTrue("saved once the file accepted writes: ${failing.persisted(WatchFailureOutbox.KEY)}",
            failing.persisted(WatchFailureOutbox.KEY)?.contains("watch-na-004b") == true)
    }

    @Test
    fun `N5 an acknowledgement the Phone could not save does not bring the settled failure back in this process`() = runBlocking {
        val failing = FailingKeyValueStore()
        val box = WatchFailureOutbox(failing)
        box.add("watch-na-005", "watch-node", "Not sent: test")
        failing.failing = true
        assertTrue(box.acknowledge("watch-na-005", "watch-node"))
        assertTrue(box.owedTo("watch-node").isEmpty())
        failing.failing = false
        assertTrue(box.owedTo("watch-node").isEmpty())
        assertFalse("the acknowledgement was saved once storage worked: ${failing.persisted(WatchFailureOutbox.KEY)}",
            failing.persisted(WatchFailureOutbox.KEY)?.contains("watch-na-005") == true)
    }

    @Test
    fun `N6 a quiet recording with no speech is not owed as a failure`() = runBlocking {
        transcript = "   "
        val outcome = withTimeout(30_000) { send(intake(orchestrator()), "watch-na-006", Transport()) }
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.NoSpeech)
        assertTrue(outbox.owedTo("watch-node").isEmpty())
    }

    @Test
    fun `N7 a speech server that recovers on the second attempt is not owed as a failure`() = runBlocking {
        failWith = { n -> if (n == 1) HermesHttpException(400, SttSafeCode.message(SttCode.UNAVAILABLE), SttCode.UNAVAILABLE) else null }
        val watch = Transport()
        val outcome = withTimeout(30_000) { send(intake(orchestrator()), "watch-na-007", watch) }
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.Completed)
        assertTrue(outbox.owedTo("watch-node").isEmpty())
        assertTrue(watch.states.none { it.detail.startsWith("Not sent") })
    }

    @Test
    fun `N8 a request delivered whose reply then failed is not owed as not sent`() = runBlocking {
        destinationFails = true
        val watch = Transport()
        val outcome = withTimeout(30_000) { send(intake(orchestrator()), "watch-na-008", watch) }
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.DeliveredResponseFailed)
        assertTrue("the request left the Phone, so it is never owed as not sent: ${outbox.owedTo("watch-node")}", outbox.owedTo("watch-node").isEmpty())
        assertTrue(watch.states.none { it.detail.startsWith("Not sent") })
    }

    @Test
    fun `N9 the Watch Stop on its own request is not owed as a failure`() = runBlocking {
        stuck = true
        val watch = Transport()
        val intake = intake(orchestrator())
        val running = launch(Dispatchers.Default) { send(intake, "watch-na-009", watch) }
        withTimeout(10_000) { while (transcribeCalls.get() == 0) delay(20) }
        assertTrue(intake.cancelTurn("watch-na-009", "watch-node"))
        withTimeout(10_000) { running.join() }
        assertEquals("Stopped", watch.states.single { it.terminal }.detail)
        assertTrue(outbox.owedTo("watch-node").isEmpty())
    }
}
