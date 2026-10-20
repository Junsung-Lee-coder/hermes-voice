package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.*
import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import com.rumi.hermesvoice.core.net.*
import com.rumi.hermesvoice.core.notify.FinalReply
import com.rumi.hermesvoice.core.sessions.*
import com.rumi.hermesvoice.core.voice.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** A local Stop is not a remote dequeue. Real OkHttp/WS/RPC, state-derived FIFO and explicit
 * coroutine/socket barriers; no sleeps, scripted prompt-index ownership or live Hermes. */
class ReleasedQueuedPredecessorTest {
    private val fake = FakeHermesDashboard()
    private val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
    private val dashboard = HermesDashboardClient(HermesDashboardEndpoint.parse(fake.baseUrl), http,
        InMemoryHermesTokenStore(HermesBearerSession("AT-1", "RT-1", null, "basic", "jun")))
    private val connector = HermesGatewayConnector(dashboard, http)
    private val fifo = Fifo()
    private var gap = false
    private var omitIdleQueue = false
    private val port = GatewayConversationPort {
        val real = connector.connection()
        object : GatewayRpc {
            override val replayEpoch: String get() = real.replayEpoch
            override suspend fun call(method: String, params: JSONObject, timeoutMs: Long): JSONObject {
                val result = real.call(method, params, timeoutMs)
                // Explicit malformed-replay negative: transport still uses the real server's frames.
                if (gap && method == "session.events.since" && params.optLong("last_seen") != Long.MAX_VALUE) {
                    val frames = result.optJSONArray("events")
                    if (frames != null && frames.length() > 0) frames.remove(0)
                }
                if (omitIdleQueue && method == "session.resume" && result.opt("running") == false) result.remove("queued")
                return result
            }
            override fun subscribe(sessionId: String) = real.subscribe(sessionId)
        }
    }
    private val store = InMemoryKeyValueStore()
    private val notices = mutableListOf<FinalReply>()
    private val played = mutableListOf<String>()
    private val qCollecting = CompletableDeferred<Unit>()
    private var qReleased = 0
    private val observed = object : HermesConversationPort by port {
        override suspend fun submit(storedSessionId: String, text: String, attachments: List<OutgoingAttachment>): SubmittedTurn {
            val actual = port.submit(storedSessionId, text, attachments)
            return object : SubmittedTurn by actual {
                override suspend fun collect(timeoutMs: Long, onEvent: suspend (RecipientEvent) -> Unit) {
                    qCollecting.complete(Unit)
                    actual.collect(timeoutMs, onEvent)
                }
                override fun release() { actual.release(); qReleased++ }
            }
        }
    }
    private val speech = object : HermesSpeechGateway {
        override suspend fun transcribe(audio: ByteArray, mimeType: String) = "Q"
        override suspend fun speak(text: String) = SpokenAudio(text.toByteArray(), "audio/mpeg")
    }
    private fun voice() = VoiceTurnOrchestrator(speech, observed,
        config = { VoiceTurnConfig(null, DestinationAllowlist.create(listOf(DestinationEntry("work", fifo.stored)), "router"), ResponsePlaybackSettings()) },
        inputGate = { _, _ -> AudioInputVerdict.USABLE }, responseTimeoutMs = 3_000,
        acceptedTurns = AcceptedTurnStore(store), finalReply = { notices += it })

    @After fun close() { connector.close(); fake.close() }

    /** Same concrete FIFO as AnchorOrdinalRecoveryTest. Only actual queue state determines
     * acceptance, resume head and frames; finish emits the running head's terminal then starts next. */
    private inner class Fifo {
        var stored = "dest"
        private var running: String? = null
        private val queued = ArrayDeque<String>()
        val completed = mutableListOf<String>()
        init {
            fake.scripts[stored] = { emptyList() }
            fake.turnBehavior = { "async_hold" }
            fake.beforeResumeReply = { snapshot -> synchronized(this) {
                snapshot.put("running", running != null).put("status", if (running != null) "running" else "idle")
                snapshot.optJSONObject("info")?.put("running", running != null)
                snapshot.put("queued", queued.firstOrNull()?.let { JSONObject().put("user", it) } ?: JSONObject.NULL)
            } }
            fake.submitStatus = { synchronized(this) {
                val prompt = fake.prompts.last().second
                val status = if (running == null) { running = prompt; start(); "streaming" }
                    else { queued.addLast(prompt); "queued" }
                fake.resumeQueued = queued.isNotEmpty()
                status
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
    private suspend fun own(turn: SubmittedTurn, word: String = "R") {
        r7Refused(turn)
    }
    private suspend fun refused(turn: SubmittedTurn) {
        val got = answer(turn)
        assertTrue("incomplete tail requires typed refusal, never neighbour/protocol-only/silent acceptance: $got",
            got.first.isEmpty() && got.second is HermesReplyUnrecoverableException)
        assertNull("uncertain FIFO is not durable reattach authority", turn.acceptance)
    }
    private suspend fun stoppedScenario(reattach: Boolean) = coroutineScope {
        val p = port.submit(fifo.stored, "P")
        // P's collector is deliberately lagging. Q is collected through the real orchestrator.
        val ackHeld = CompletableDeferred<Unit>()
        val ackRelease = java.util.concurrent.CountDownLatch(1)
        fake.beforeAsyncReply = { if (fake.prompts.last().second == "Q") { ackHeld.complete(Unit); check(ackRelease.await(10, TimeUnit.SECONDS)) } }
        val o = voice()
        val q = async(start = CoroutineStart.UNDISPATCHED) { o.run(VoiceTurnRequest("Q-stop", VoiceOrigin.PHONE,
            byteArrayOf(1), "audio/wav", PlaybackSink { _, cue -> played += cue.text }, recognizedText = "Q",
            routing = TurnRouting.Direct(fifo.stored))) }
        withTimeout(8_000) { ackHeld.await() }
        assertEquals(listOf("P", "Q"), fake.prompts.map { it.second })
        val beforeStopAudio = played.toList() // A pre-submit acknowledgement is not cancelled reply audio.
        assertTrue(o.stopTurn("Q-stop"))
        assertEquals(VoiceTurnOutcome.Stopped, withTimeout(8_000) { q.await() })
        assertEquals("cancelled before queued ACK creates no collector/SubmittedTurn", 0, qReleased)
        assertFalse(o.stopTurn("Q-stop"))
        ackRelease.countDown(); fake.beforeAsyncReply = null
        assertTrue(AcceptedTurnStore(store).pending().isEmpty())
        val r = port.submit(fifo.stored, "R")
        assertEquals("queued", r.submitStatus)
        assertNull("queued refusal creates no acceptance", r.acceptance)
        val accepted = TurnAcceptance("rt-${fifo.stored}", 0, connector.connection().replayEpoch, 1)
        val backing = InMemoryKeyValueStore()
        if (reattach) {
            assertTrue(AcceptedTurnStore(backing).begin(AcceptedTurn("chat-R", AcceptedKind.TEXT, fifo.stored, accepted, System.currentTimeMillis())))
            r.release()
        }
        fifo.drain(); barrier()
        if (reattach) {
            val record = AcceptedTurnStore(backing).pending().single()
            assertEquals(accepted, record.acceptance)
            r7LegacyRefused(port, record.storedSessionId, record.acceptance)
        } else own(r)
        assertTrue("cancelled Q must never regain reply alerts/notices", notices.isEmpty())
        assertEquals("cancelled Q must never regain audio after Stop", beforeStopAudio, played)
        assertTrue(AcceptedTurnStore(store).pending().isEmpty())
        assertEquals(listOf("P", "Q", "R"), fifo.completed)
        assertEquals("accepted prompts are never resubmitted", listOf("P", "Q", "R"), fake.prompts.map { it.second })
        p.release()
    }

    @Test fun F01_realStopReleasedQueuedTailCannotBecomeRSuccess() = runBlocking { stoppedScenario(false) }
    @Test fun F02_realStopSerializedRReattachesWithoutQAudioAlertOrResubmit() = runBlocking { stoppedScenario(true) }

    @Test fun F03_multipleReleasedQueuedPredecessorsKeepCompleteTailWithLaggedActiveCollector() = runBlocking {
        val p = port.submit(fifo.stored, "P")
        val q = port.submit(fifo.stored, "Q"); q.release()
        val s = port.submit(fifo.stored, "S"); s.release()
        val t = port.submit(fifo.stored, "T"); t.release()
        val r = port.submit(fifo.stored, "R")
        fifo.drain(); barrier(); own(r)
        assertEquals(listOf("P", "Q", "S", "T", "R"), fake.prompts.map { it.second })
        p.release()
    }
    @Test fun F04_releasingAllIncludingLastQueuedPredecessorDoesNotDequeueRemoteWork() = runBlocking {
        val p = port.submit(fifo.stored, "P")
        val q = port.submit(fifo.stored, "Q")
        p.release(); q.release()
        val r = port.submit(fifo.stored, "R")
        fifo.drain(); barrier(); own(r)
        assertEquals(listOf("P", "Q", "R"), fake.prompts.map { it.second })
    }
    @Test fun F05_releasedRunningPredecessorStillOwedUntilRealTerminal() = runBlocking {
        val p = port.submit(fifo.stored, "P"); p.release()
        val r = port.submit(fifo.stored, "R")
        fifo.drain(); barrier(); own(r)
    }
    @Test fun F06_realTerminalAfterReleaseRetiresTailAndHealthySubsequentSendIsNotPoisoned() = runBlocking {
        val p = port.submit(fifo.stored, "P")
        val q = port.submit(fifo.stored, "Q")
        p.release(); q.release(); fifo.drain(); barrier()
        val r = port.submit(fifo.stored, "R"); fifo.finish(); barrier(); own(r)
        fake.evictRing("rt-${fifo.stored}")
        val n = port.submit(fifo.stored, "N"); fifo.finish(); barrier(); own(n, "N")
    }
    @Test fun F07_expiredReleasedTailUsesActualChatGenericNoticeThenHealthyIdleRecovers() = runBlocking {
        val sessions = AppSessionRepository(dashboard, port, OwnedSessionRegistry(store))
        val work = sessions.createConversation("Work", "work", "synthetic")
        fifo.stored = work.storedSessionId
        val p = port.submit(fifo.stored, "P")
        val q = port.submit(fifo.stored, "Q")
        p.release(); q.release(); fifo.finish(); fake.evictRing("rt-${fifo.stored}")
        var audio = 0
        val chat = ChatService(sessions, replyTimeoutMs = 3_000, finalReply = { notices += it }, newSendId = { "R" },
            typedFinal = { _, _, _, _ -> audio++; false }, accepted = AcceptedTurnStore(store))
        val sent = chat.send(fifo.stored, "R") { fifo.drain() }
        assertTrue("accepted but expired tail is a real caller failure: $sent", sent is ChatSendResult.Failed && sent.acceptanceObserved)
        assertEquals(listOf("chat-R#notice"), notices.map { it.identity })
        assertEquals(listOf(R7_QUEUE_NOTICE), notices.map { it.text })
        assertTrue(notices.all { !it.heard && !it.cancelled && it.noticeTitle == AcceptedTurnReconciler.NOTICE_TITLE })
        assertEquals(0, audio)
        val n = port.submit(fifo.stored, "N"); fifo.finish(); barrier(); own(n, "N")
        assertEquals(listOf("P", "Q", "R", "N"), fake.prompts.map { it.second })
    }
    private suspend fun lostTail(kind: String) {
        val p = port.submit(fifo.stored, "P")
        val q = port.submit(fifo.stored, "Q")
        p.release(); q.release()
        // Put a real frame AFTER the latest accepted tail anchor: eviction/removal must actually
        // make the replay needed by that tail incomplete, not just evict an empty suffix.
        fake.pushLaterTurn(fifo.stored, FakeHermesDashboard.interim("working P"), withStart = false)
        when (kind) {
            "expired" -> fake.evictRing("rt-${fifo.stored}")
            "gap" -> gap = true
            "epoch" -> fake.replayEpoch = "e2"
            "bare-error" -> fake.pushLaterTurn(fifo.stored, "error" to JSONObject().put("message", "ambiguous"), withStart = false)
        }
        val r = port.submit(fifo.stored, "R")
        fifo.drain(); barrier(); refused(r)
        assertEquals(listOf("P", "Q", "R"), fake.prompts.map { it.second })
    }
    @Test fun F08_expiredReleasedTailRefusesInsteadOfGuessing() = runBlocking { lostTail("expired") }
    @Test fun F09_gappedReleasedTailRefusesInsteadOfGuessing() = runBlocking { lostTail("gap") }
    @Test fun F10_epochChangedReleasedTailRefusesInsteadOfGuessing() = runBlocking { lostTail("epoch") }
    @Test fun F11_bareErrorIsNotReliableReleasedTailTerminalProof() = runBlocking { lostTail("bare-error") }
    @Test fun F12_erroredCompleteIsReliableFifoBoundaryButNeverAnAnswerForR() = runBlocking {
        val p = port.submit(fifo.stored, "P")
        val q = port.submit(fifo.stored, "Q"); q.release()
        val r = port.submit(fifo.stored, "R")
        fifo.finish(); fifo.finish("error"); fifo.finish(); barrier(); own(r)
        p.release()
    }
    @Test fun F13_lostAcceptedTailStaysUnknownWhenReplayTransportRecoversBeforeCompletion() = runBlocking {
        val p = port.submit(fifo.stored, "P")
        val q = port.submit(fifo.stored, "Q"); p.release(); q.release()
        fake.pushLaterTurn(fifo.stored, FakeHermesDashboard.interim("working P"), withStart = false)
        gap = true
        val r = port.submit(fifo.stored, "R"); refused(r)
        gap = false // The old replay is readable again, but cannot prove R's missing ordinal.
        val s = port.submit(fifo.stored, "S")
        fifo.drain(); barrier(); refused(s)
        val n = port.submit(fifo.stored, "N"); fifo.finish(); barrier(); own(n, "N")
        assertEquals(listOf("P", "Q", "R", "S", "N"), fake.prompts.map { it.second })
    }
    @Test fun F14_missingIdleQueueFieldCannotRetireExpiredCustodyAsCompleteProof() = runBlocking {
        val p = port.submit(fifo.stored, "P")
        val q = port.submit(fifo.stored, "Q"); p.release(); q.release()
        fifo.drain(); fake.evictRing("rt-${fifo.stored}")
        omitIdleQueue = true
        val r = port.submit(fifo.stored, "R"); fifo.finish(); barrier(); refused(r)
        omitIdleQueue = false
        val n = port.submit(fifo.stored, "N"); fifo.finish(); barrier(); own(n, "N")
        assertEquals(listOf("P", "Q", "R", "N"), fake.prompts.map { it.second })
    }

}
