package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.*
import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import com.rumi.hermesvoice.core.net.*
import com.rumi.hermesvoice.core.notify.FinalReply
import com.rumi.hermesvoice.core.sessions.*
import com.rumi.hermesvoice.core.voice.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * R05: a prompt.submit whose frame LEFT but whose JSON-RPC answer never came back (real Stop while transmitting,
 * a bounded deadline, a lost socket, a reply this client does not recognise) may still be queued by the server.
 * That possibility is custody, exactly like an accepted position. Real OkHttp/WebSocket/JSON-RPC, a state-derived
 * FIFO, the fixture's existing `async_hold` + `beforeAsyncReply` seam (the server HAS queued the prompt and holds its
 * answer) and the real VoiceTurnOrchestrator.stopTurn. No prompt-index ownership, no sleep scheduling, no live Hermes.
 *
 * The resume projection is the installed tui_gateway's live payload (server.py `_live_session_payload`): `queued`
 * only while a prompt is queued, `status` working/idle, `turn_started_at` null while idle.
 */
class AmbiguousSubmitCustodyTest {
    private val fake = FakeHermesDashboard()
    private val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
    private val dashboard = HermesDashboardClient(HermesDashboardEndpoint.parse(fake.baseUrl), http,
        InMemoryHermesTokenStore(HermesBearerSession("AT-1", "RT-1", null, "basic", "jun")))
    private val connector = HermesGatewayConnector(dashboard, http)
    private val fifo = Fifo()

    /** Test-only deadline adapter at the real call boundary: only prompt.submit's own await is shortened; the server keeps holding its answer. */
    @Volatile private var submitDeadlineMs: Long? = null

    /** The server refuses prompt.submit definitively (4090 SESSION_NOT_OWNED) before it accepts anything. */
    @Volatile private var refusing = false

    private val port = GatewayConversationPort {
        val real = connector.connection()
        object : GatewayRpc {
            override val replayEpoch: String get() = real.replayEpoch
            override suspend fun call(method: String, params: JSONObject, timeoutMs: Long): JSONObject =
                real.call(method, params, if (method == "prompt.submit") submitDeadlineMs ?: timeoutMs else timeoutMs)
            override fun subscribe(sessionId: String) = real.subscribe(sessionId)
        }
    }
    private val store = InMemoryKeyValueStore()
    private val notices = mutableListOf<FinalReply>()
    private val played = mutableListOf<String>()

    /** Every prompt the server has decided on (queue state already changed), by its text. */
    private val enqueuedPrompts = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private fun enqueued(prompt: String): CompletableDeferred<Unit> = enqueuedPrompts.getOrPut(prompt) { CompletableDeferred() }
    private val release = CountDownLatch(1)
    @Volatile private var holdPrompt: String? = null

    private val speech = object : HermesSpeechGateway {
        override suspend fun transcribe(audio: ByteArray, mimeType: String) = "R"
        override suspend fun speak(text: String) = SpokenAudio(text.toByteArray(), "audio/mpeg")
    }
    private fun voice() = VoiceTurnOrchestrator(speech, port,
        config = { VoiceTurnConfig(null, DestinationAllowlist.create(listOf(DestinationEntry("work", fifo.stored)), "router"), ResponsePlaybackSettings()) },
        inputGate = { _, _ -> AudioInputVerdict.USABLE }, responseTimeoutMs = 3_000,
        acceptedTurns = AcceptedTurnStore(store), finalReply = { notices += it })
    private fun voiceRequest(turnId: String, text: String) = VoiceTurnRequest(turnId, VoiceOrigin.PHONE,
        byteArrayOf(1), "audio/wav", PlaybackSink { _, cue -> played += cue.text }, recognizedText = text,
        routing = TurnRouting.Direct(fifo.stored))

    init {
        fake.beforeAsyncReply = {
            val prompt = fake.prompts.last().second
            enqueued(prompt).complete(Unit)
            if (prompt == holdPrompt) check(release.await(10, TimeUnit.SECONDS)) { "the held prompt.submit answer was never released" }
        }
        fake.turnBehavior = { if (refusing) "not_owned" else "async_hold" }
    }

    @After fun close() {
        release.countDown()
        connector.close()
        fake.close()
    }

    /** One concrete FIFO. Only the actual queue state determines acceptance, the resume projection and the frames. */
    private inner class Fifo {
        var stored = "dest"
        private var running: String? = null
        private val queued = ArrayDeque<String>()
        val completed = mutableListOf<String>()

        /** prompt -> (does the server enqueue it?, the status it reports): a server answering with something else than the real queue effect. */
        val outcomes = ConcurrentHashMap<String, Pair<Boolean, String>>()

        init {
            fake.scripts[stored] = { emptyList() }
            fake.beforeResumeReply = { snapshot -> synchronized(this) {
                snapshot.put("running", running != null).put("status", if (running != null) "working" else "idle")
                snapshot.optJSONObject("info")?.put("running", running != null)
                snapshot.put("messages_omitted", true)
                snapshot.put("turn_started_at", if (running != null) 1_700_000_000.0 else JSONObject.NULL)
                val head = queued.firstOrNull()
                if (head != null) snapshot.put("queued", JSONObject().put("user", head)) else snapshot.remove("queued")
            } }
            fake.submitStatus = { synchronized(this) {
                val prompt = fake.prompts.last().second
                val custom = outcomes[prompt]
                val real = if (custom != null && !custom.first) null
                    else if (running == null) { running = prompt; start(); "streaming" }
                    else { queued.addLast(prompt); "queued" }
                fake.resumeQueued = queued.isNotEmpty()
                custom?.second ?: real!!
            } }
        }
        private fun start() { fake.pushLaterTurn(stored, FakeHermesDashboard.interim("working ${running!!}")) }
        @Synchronized fun finish(status: String = "complete") {
            val own = checkNotNull(running)
            fake.pushLaterTurn(stored, FakeHermesDashboard.complete("answer $own", status), withStart = false)
            completed += own
            running = if (queued.isEmpty()) null else queued.removeFirst()
            fake.resumeQueued = queued.isNotEmpty()
            if (running != null) start()
        }
        @Synchronized fun drain() { while (running != null) finish() }
    }

    private suspend fun barrier() {
        connector.connection().call("session.events.since", JSONObject().put("session_id", "rt-${fifo.stored}").put("last_seen", Long.MAX_VALUE))
    }
    private suspend fun answer(turn: SubmittedTurn): Pair<List<String>, HermesException?> {
        val finals = mutableListOf<String>()
        return try {
            withTimeout(8_000) { turn.collect(3_000) { if (it is RecipientEvent.Complete) finals += it.text } }
            finals to null
        } catch (error: HermesException) { finals to error }
        finally { turn.release() }
    }
    private suspend fun own(turn: SubmittedTurn, word: String) {
        r7Refused(turn)
    }

    /** The neighbour's reply is never adopted: R's possible position makes the tail unprovable, a typed refusal and no durable authority. */
    private suspend fun refusedNeverNeighbour(turn: SubmittedTurn, word: String) {
        val got = answer(turn)
        assertFalse("RELEASED NEIGHBOUR R's FINAL MUST NEVER BE $word's FINAL: $got", got.first.contains("answer R"))
        assertTrue("an ambiguous earlier submit leaves the tail unprovable: typed refusal, never silent/protocol-only success: $got",
            got.first.isEmpty() && got.second is HermesReplyUnrecoverableException)
        assertNull("uncertain FIFO is not durable reattach authority", turn.acceptance)
    }

    private fun prompts() = fake.prompts.map { it.second }

    private class HeldStop(val orchestrator: VoiceTurnOrchestrator, val p: SubmittedTurn, val audioBefore: List<String>)

    /** P runs; R's frame is queued behind it and its answer is held; the REAL orchestrator Stops R while it is transmitting. */
    private suspend fun stopRWhileItsSubmitAnswerIsHeld(): HeldStop = coroutineScope {
        val p = port.submit(fifo.stored, "P")
        holdPrompt = "R"
        val o = voice()
        val r = async(start = CoroutineStart.UNDISPATCHED) { o.run(voiceRequest("R-stop", "R")) }
        withTimeout(8_000) { enqueued("R").await() }
        assertEquals(listOf("P", "R"), prompts())
        val audioBefore = played.toList()
        assertTrue(o.stopTurn("R-stop"))
        assertEquals(VoiceTurnOutcome.Stopped, withTimeout(8_000) { r.await() })
        assertFalse(o.stopTurn("R-stop"))
        release.countDown()
        assertTrue(AcceptedTurnStore(store).pending().isEmpty())
        HeldStop(o, p, audioBefore)
    }

    @Test fun S01_realStopWhileItsSubmitAnswerIsHeldCannotLetTheNextSendAdoptRsFinal() = runBlocking<Unit> {
        val stopped = stopRWhileItsSubmitAnswerIsHeld()
        val s = port.submit(fifo.stored, "S")
        fifo.drain(); barrier()
        refusedNeverNeighbour(s, "S")
        assertEquals("possibly accepted input is never resubmitted", listOf("P", "R", "S"), prompts())
        assertTrue("stopped R never regains an alert or notice", notices.isEmpty())
        assertEquals("stopped R never regains audio", stopped.audioBefore, played)
        // Healthy recovery: the fresh, actual idle projection retires the unprovable tail.
        val n = port.submit(fifo.stored, "N"); fifo.finish(); barrier(); own(n, "N")
        assertEquals(listOf("P", "R", "S", "N"), prompts())
        stopped.p.release()
    }

    @Test fun S02_realStopThenVoiceSendGetsTypedGenericNoticeAndNeverSpeaksOrAlertsRsAnswer() = runBlocking<Unit> {
        val stopped = stopRWhileItsSubmitAnswerIsHeld()
        val s = async(start = CoroutineStart.UNDISPATCHED) { stopped.orchestrator.run(voiceRequest("S-voice", "S")) }
        withTimeout(8_000) { enqueued("S").await() }
        fifo.drain()
        val outcome = withTimeout(8_000) { s.await() }
        barrier()
        assertFalse("RELEASED NEIGHBOUR R's FINAL MUST NEVER BE SPOKEN FOR S: $played", played.any { it.contains("answer R") })
        assertTrue("typed refusal reaches the real voice caller: $outcome", outcome is VoiceTurnOutcome.DeliveredUnattributed && outcome.submitStatus == "queued")
        assertTrue("no reply audio at all: $played", played.none { it.startsWith("answer") })
        assertEquals(listOf("S-voice#notice"), notices.map { it.identity })
        assertEquals(listOf(R7_QUEUE_NOTICE), notices.map { it.text })
        assertTrue(notices.all { !it.heard && !it.cancelled && it.noticeTitle == AcceptedTurnReconciler.NOTICE_TITLE })
        assertTrue("stopped R is silent; S has no durable authority", AcceptedTurnStore(store).pending().isEmpty())
        assertEquals(listOf("P", "R", "S"), prompts())
        stopped.p.release()
    }

    @Test fun S03_realStopThenTextSendGetsTypedGenericNoticeAndNeverShowsRsAnswer() = runBlocking<Unit> {
        val sessions = AppSessionRepository(dashboard, port, OwnedSessionRegistry(store))
        val work = sessions.createConversation("Work", "work", "synthetic")
        fifo.stored = work.storedSessionId
        val stopped = stopRWhileItsSubmitAnswerIsHeld()
        var audio = 0
        val chat = ChatService(sessions, replyTimeoutMs = 3_000, finalReply = { notices += it }, newSendId = { "S" },
            typedFinal = { _, _, _, _ -> audio++; false }, accepted = AcceptedTurnStore(store))
        val sent = chat.send(fifo.stored, "S") { fifo.drain() }
        barrier()
        assertFalse("RELEASED NEIGHBOUR R's FINAL MUST NEVER BE SHOWN AS S's REPLY: $sent", sent is ChatSendResult.Replied)
        assertTrue("accepted but unprovable tail is a real caller failure: $sent", sent is ChatSendResult.Failed && sent.acceptanceObserved)
        assertEquals(listOf("chat-S#notice"), notices.map { it.identity })
        assertEquals(listOf(R7_QUEUE_NOTICE), notices.map { it.text })
        assertTrue(notices.all { !it.heard && !it.cancelled && it.noticeTitle == AcceptedTurnReconciler.NOTICE_TITLE })
        assertEquals(0, audio)
        assertEquals(listOf("P", "R", "S"), prompts())
        stopped.p.release()
    }

    @Test fun S04_socketLossAfterTheSubmitFrameLeftKeepsMaybeAcceptedCustody() = runBlocking<Unit> {
        val p = port.submit(fifo.stored, "P")
        holdPrompt = "R"
        val r = async { runCatching { port.submit(fifo.stored, "R") } }
        withTimeout(8_000) { enqueued("R").await() }
        assertEquals(listOf("P", "R"), prompts())
        fake.dropGateway()
        val lost = withTimeout(8_000) { r.await() }
        release.countDown()
        assertTrue("the answer never came back: $lost", lost.exceptionOrNull() is HermesException)
        val s = port.submit(fifo.stored, "S")
        fifo.drain(); barrier()
        refusedNeverNeighbour(s, "S")
        assertEquals(listOf("P", "R", "S"), prompts())
        val n = port.submit(fifo.stored, "N"); fifo.finish(); barrier(); own(n, "N")
        assertEquals(listOf("P", "R", "S", "N"), prompts())
        p.release()
    }

    @Test fun S05_boundedSubmitDeadlineKeepsMaybeAcceptedCustody() = runBlocking<Unit> {
        val p = port.submit(fifo.stored, "P")
        holdPrompt = "R"
        submitDeadlineMs = 500
        val r = async { runCatching { port.submit(fifo.stored, "R") } }
        val timedOut = withTimeout(8_000) { r.await() }
        submitDeadlineMs = null
        withTimeout(8_000) { enqueued("R").await() }
        release.countDown()
        assertTrue("the bounded call timed out after the frame was sent: $timedOut", timedOut.exceptionOrNull() is HermesProtocolException)
        assertEquals("HermesPromptUncertainException", timedOut.exceptionOrNull()?.javaClass?.simpleName)
        assertEquals(listOf("P", "R"), prompts())
        val s = port.submit(fifo.stored, "S")
        fifo.drain(); barrier()
        refusedNeverNeighbour(s, "S")
        assertEquals(listOf("P", "R", "S"), prompts())
        val n = port.submit(fifo.stored, "N"); fifo.finish(); barrier(); own(n, "N")
        p.release()
    }

    @Test fun S06_definitiveRefusalAfterAPriorUnknownRestoresTheUnknownTailInsteadOfClearingIt() = runBlocking<Unit> {
        val p = port.submit(fifo.stored, "P")
        holdPrompt = "R"
        submitDeadlineMs = 500
        val r = async { runCatching { port.submit(fifo.stored, "R") } }
        withTimeout(8_000) { r.await() }
        submitDeadlineMs = null
        withTimeout(8_000) { enqueued("R").await() }
        release.countDown()
        // A later submit is refused definitively BEFORE the server accepts anything: R's unknown position must survive it.
        refusing = true
        val refused = runCatching { port.submit(fifo.stored, "T") }
        refusing = false
        assertTrue("definitive typed refusal: $refused", refused.exceptionOrNull() is SessionInUseException)
        assertEquals("a refused prompt was never accepted", listOf("P", "R"), prompts())
        val s = port.submit(fifo.stored, "S")
        fifo.drain(); barrier()
        refusedNeverNeighbour(s, "S")
        assertEquals(listOf("P", "R", "S"), prompts())
        p.release()
    }

    @Test fun S07_installedServerIdleProjectionRetiresLostCustodySoAHealthySendIsOwnedNotRefused() = runBlocking<Unit> {
        val p = port.submit(fifo.stored, "P")
        val q = port.submit(fifo.stored, "Q"); p.release(); q.release()
        fifo.drain(); fake.evictRing("rt-${fifo.stored}")
        // The replay proof for the old tail is gone. The server is actually idle: running false, status idle, `queued` omitted
        // (installed tui_gateway projection) and a null turn_started_at.
        val r = port.submit(fifo.stored, "R"); fifo.finish(); barrier(); own(r, "R")
        val n = port.submit(fifo.stored, "N"); fifo.finish(); barrier(); own(n, "N")
        assertEquals(listOf("P", "Q", "R", "N"), prompts())
    }

    @Test fun S08_healthyIdleRecoveryAfterAnAmbiguousStopReattachesTheSameAcceptance() = runBlocking<Unit> {
        val stopped = stopRWhileItsSubmitAnswerIsHeld()
        fifo.drain(); barrier()
        val n = port.submit(fifo.stored, "N")
        assertEquals("streaming", n.submitStatus)
        assertNull("idle projection cannot retire ambiguity", n.acceptance)
        val accepted = TurnAcceptance("rt-${fifo.stored}", 0, connector.connection().replayEpoch, 1)
        val backing = InMemoryKeyValueStore()
        assertTrue(AcceptedTurnStore(backing).begin(AcceptedTurn("chat-N", AcceptedKind.TEXT, fifo.stored, accepted, System.currentTimeMillis())))
        n.release()
        fifo.finish(); barrier()
        val record = AcceptedTurnStore(backing).pending().single()
        assertEquals(accepted, record.acceptance)
        r7LegacyRefused(port, record.storedSessionId, record.acceptance)
        assertEquals(listOf("P", "R", "N"), prompts())
        assertTrue(notices.isEmpty())
        assertEquals(stopped.audioBefore, played)
        stopped.p.release()
    }

    @Test fun S09_definitiveRefusalWithNothingUnknownLeavesCustodyHealthy() = runBlocking<Unit> {
        val p = port.submit(fifo.stored, "P")
        refusing = true
        val refused = runCatching { port.submit(fifo.stored, "T") }
        refusing = false
        assertTrue("definitive typed refusal: $refused", refused.exceptionOrNull() is SessionInUseException)
        val s = port.submit(fifo.stored, "S")
        assertEquals("queued", s.submitStatus)
        assertNull("queued ACK cannot grant authority even after definitive refusal", s.acceptance)
        fifo.drain(); barrier(); own(s, "S")
        assertEquals(listOf("P", "S"), prompts())
        p.release()
    }

    @Test fun S10_unrecognisedSubmitAnswerKeepsMaybeAcceptedCustody() = runBlocking<Unit> {
        val p = port.submit(fifo.stored, "P")
        fifo.outcomes["R"] = true to "weird" // queued by the server, answered with a status this client does not know
        val r = runCatching { port.submit(fifo.stored, "R") }
        assertEquals("unknown ACK has no returned/armed collector", HermesPromptUncertainException::class.java, r.exceptionOrNull()?.javaClass)
        val s = port.submit(fifo.stored, "S")
        fifo.drain(); barrier()
        refusedNeverNeighbour(s, "S")
        assertEquals(listOf("P", "R", "S"), prompts())
        p.release()
    }

    @Test fun S11_steeredOrRedirectedAnswersAddNoFifoPositionAndDoNotPoisonCustody() = runBlocking<Unit> {
        val p = port.submit(fifo.stored, "P")
        fifo.outcomes["R"] = false to "steered"
        fifo.outcomes["R2"] = false to "redirected"
        val r = port.submit(fifo.stored, "R"); assertFalse(r.attributable); r.release()
        val r2 = port.submit(fifo.stored, "R2"); assertFalse(r2.attributable); r2.release()
        val s = port.submit(fifo.stored, "S")
        assertEquals("queued", s.submitStatus)
        assertNull("steered/redirected add no position, but later queued ACK is still unprovable", s.acceptance)
        fifo.drain(); barrier(); own(s, "S")
        assertEquals(listOf("P", "R", "R2", "S"), prompts())
        p.release()
    }
}
