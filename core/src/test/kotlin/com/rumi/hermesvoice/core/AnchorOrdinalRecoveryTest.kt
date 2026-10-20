package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.*
import com.rumi.hermesvoice.core.net.*
import com.rumi.hermesvoice.core.notify.FinalReply
import com.rumi.hermesvoice.core.sessions.*
import com.rumi.hermesvoice.core.voice.RecipientEvent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Real OkHttp/WS/RPC with a stateful synthetic FIFO. The transparent seam delays only
 * return/subscription boundaries; it never supplies a status, event, sequence or acceptance.
 * No live server, producer-id extension, sleeps or collector scheduling assumptions. */
class AnchorOrdinalRecoveryTest {
    private val fake = FakeHermesDashboard()
    private val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
    private val dashboard = HermesDashboardClient(HermesDashboardEndpoint.parse(fake.baseUrl), http,
        InMemoryHermesTokenStore(HermesBearerSession("AT-1", "RT-1", null, "basic", "jun")))
    private val connector = HermesGatewayConnector(dashboard, http)
    private val fifo = Fifo()
    private var boundary: String? = null
    private val reached = CompletableDeferred<Unit>()
    private val proceed = CompletableDeferred<Unit>()
    private val port = GatewayConversationPort {
        val real = connector.connection()
        object : GatewayRpc {
            override val replayEpoch: String get() = real.replayEpoch
            override suspend fun call(method: String, params: JSONObject, timeoutMs: Long): JSONObject {
                if (method == "prompt.submit" && boundary == "acceptance") {
                    boundary = null
                    reached.complete(Unit)
                    proceed.await()
                }
                val answer = real.call(method, params, timeoutMs)
                val target = if (method == "session.resume") "resume" else if (method == "session.events.since") "anchor" else ""
                if (boundary == target) {
                    boundary = null
                    if (target == "resume") {
                        assertTrue("R resume really observed running P", answer.optBoolean("running"))
                        assertEquals("R resume really observed queued Q", "Q", answer.optJSONObject("queued")?.optString("user"))
                    }
                    reached.complete(Unit)
                    proceed.await()
                }
                return answer
            }
            override fun subscribe(sessionId: String): GatewaySubscription {
                val subscription = real.subscribe(sessionId)
                if (boundary == "subscription") {
                    boundary = null
                    reached.complete(Unit)
                    runBlocking { proceed.await() }
                }
                return subscription
            }
        }
    }

    @After fun close() { connector.close(); fake.close() }

    /** Only queue state decides statuses. P/Q/R/N are ordinary submitted prompt words, not
     * protocol ids. Completion removes the running head and starts the actual next FIFO head. */
    private inner class Fifo {
        var stored = "dest"
        private var running: String? = null
        private val queued = ArrayDeque<String>()
        val completed = mutableListOf<String>()
        init {
            fake.scripts[stored] = { emptyList() }
            fake.turnBehavior = { "async_hold" }
            fake.beforeResumeReply = { snapshot ->
                synchronized(this) {
                    snapshot.put("running", running != null).put("status", if (running != null) "running" else "idle")
                    snapshot.optJSONObject("info")?.put("running", running != null)
                    snapshot.put("queued", queued.firstOrNull()?.let { JSONObject().put("user", it) } ?: JSONObject.NULL)
                }
            }
            fake.submitStatus = {
                synchronized(this) {
                    val prompt = fake.prompts.last().second
                    val status = if (running == null) { running = prompt; start(); "streaming" }
                        else { queued.addLast(prompt); "queued" }
                    fake.resumeQueued = queued.isNotEmpty()
                    status
                }
            }
        }
        private fun start() { fake.pushLaterTurn(stored, FakeHermesDashboard.interim("working ${running!!}")) }
        @Synchronized fun finish() {
            val own = checkNotNull(running)
            fake.pushLaterTurn(stored, FakeHermesDashboard.complete("answer $own"), withStart = false)
            completed += own
            running = if (queued.isEmpty()) null else queued.removeFirst()
            fake.resumeQueued = queued.isNotEmpty()
            if (running != null) start()
        }
        @Synchronized fun drain() { while (running != null) finish() }
    }

    private suspend fun barrier() {
        // On the SAME real socket, this reply follows all emitted frames. No timing wait.
        connector.connection().call("session.events.since", JSONObject().put("session_id", "rt-${fifo.stored}").put("last_seen", Long.MAX_VALUE))
    }
    private suspend fun answer(turn: SubmittedTurn): Pair<List<String>, HermesException?> {
        val finals = mutableListOf<String>()
        return try {
            turn.collect(3_000) { if (it is RecipientEvent.Complete) finals += it.text }
            finals to null
        } catch (error: HermesException) { finals to error }
        finally { turn.release() }
    }
    private suspend fun own(turn: SubmittedTurn) {
        r7Refused(turn)
    }

    private suspend fun scenario(where: String?, reattach: Boolean = false, finishBoth: Boolean = false) = coroutineScope {
        val p = port.submit(fifo.stored, "P")
        val pObserved = CompletableDeferred<Unit>()
        val pWait = async(start = CoroutineStart.UNDISPATCHED) {
            p.collect(3_000) { if (it is RecipientEvent.Interim) pObserved.complete(Unit) }
        }
        withTimeout(8_000) { pObserved.await() }
        val q = port.submit(fifo.stored, "Q")
        // Q has been accepted; its collector has NOT been called. This models accepted-store IO lag.
        assertEquals("queued", q.submitStatus)
        boundary = where
        val submitting = async(Dispatchers.Default) { port.submit(fifo.stored, "R") }
        if (where != null) {
            try {
                kotlinx.coroutines.selects.select<Unit> { reached.onAwait { }; submitting.onAwait { } }
                fifo.finish() // normal P.complete followed by Q.start
                if (finishBoth) fifo.finish() // normal Q.complete before R acceptance
                barrier()
            } finally { proceed.complete(Unit) }
        }
        val r = withTimeout(8_000) { submitting.await() }
        val backing = InMemoryKeyValueStore()
        if (reattach) {
            assertNull("no new queued/uncertain acceptance", r.acceptance)
            val accepted = AcceptedTurn("chat-R", AcceptedKind.TEXT, fifo.stored, TurnAcceptance("rt-${fifo.stored}", 0, connector.connection().replayEpoch, 1), System.currentTimeMillis())
            assertTrue(AcceptedTurnStore(backing).begin(accepted))
            r.release()
        }
        fifo.drain() // Q and R, or P/Q/R in the stable control
        val n = port.submit(fifo.stored, "N")
        fifo.finish()
        barrier()
        if (reattach) {
            val record = AcceptedTurnStore(backing).pending().single()
            assertEquals("chat-R", record.id)
            r7LegacyRefused(port, record.storedSessionId, record.acceptance)
        } else own(r)
        assertEquals(listOf("P", "Q", "R", "N"), fifo.completed)
        assertEquals("no automatic resubmit", listOf("P", "Q", "R", "N"), fake.prompts.map { it.second })
        withTimeout(8_000) { pWait.await() }
        p.release(); q.release(); n.release()
    }

    @Test fun B01_resumeToAnchorCompletionWithLaggedQCollectorKeepsRFinal() = runBlocking { scenario("resume") }
    @Test fun B02_sameAcceptedRecordReattachesWithoutNeighbourOrResubmit() = runBlocking { scenario("resume", reattach = true) }
    @Test fun B03_anchorToSubscriptionMovementCannotLoseAPredecessorCompletion() = runBlocking { scenario("anchor") }
    @Test fun B04_subscriptionToStreamingAcceptanceKeepsTheAnchoredPredecessorOrdinal() = runBlocking { scenario("subscription", finishBoth = true) }
    @Test fun B05_stableQueuedAndStreamingFIFOControlKeepsIntendedFinal() = runBlocking { scenario(null) }
    @Test fun B08_afterFinalReplayBasisBeforeRemoteAcceptancePreservesStreamingOrdinal() = runBlocking { scenario("acceptance", finishBoth = true) }

    @Test fun B06_expiredOrdinalEvidenceRefusesWithTypedFailureNotNeighbourSuccess() = runBlocking {
        val p = port.submit(fifo.stored, "P")
        val q = port.submit(fifo.stored, "Q")
        fifo.finish()
        fake.evictRing("rt-${fifo.stored}")
        val r = port.submit(fifo.stored, "R")
        fifo.drain()
        val n = port.submit(fifo.stored, "N")
        fifo.finish(); barrier()
        val got = answer(r)
        assertTrue("lost ordinal proof must be an honest typed refusal, never success or silent untracked acceptance: $got",
            got.first.isEmpty() && got.second is HermesReplyUnrecoverableException)
        assertNull("unproved skip must not become durable reattach authority", r.acceptance)
        assertEquals(4, fake.prompts.size)
        p.release(); q.release(); n.release()
    }

    @Test fun B07_realChatCallerDeliversExactNoticeForExpiredOrdinalWithoutAudioOrResubmit() = runBlocking {
        val store = InMemoryKeyValueStore()
        val sessions = AppSessionRepository(dashboard, port, OwnedSessionRegistry(store))
        val work = sessions.createConversation("Work", "work", "synthetic")
        fifo.stored = work.storedSessionId
        val p = port.submit(fifo.stored, "P")
        val q = port.submit(fifo.stored, "Q")
        fifo.finish(); fake.evictRing("rt-${fifo.stored}")
        val replies = mutableListOf<FinalReply>()
        var audio = 0
        val chat = ChatService(sessions, replyTimeoutMs = 3_000, finalReply = { replies += it }, newSendId = { "R" },
            typedFinal = { _, _, _, _ -> audio++; false }, accepted = AcceptedTurnStore(store))
        val sent = async(start = CoroutineStart.UNDISPATCHED) { chat.send(fifo.stored, "R") { fifo.drain() } }
        val result = withTimeout(8_000) { sent.await() }
        assertTrue("accepted but unprovable delivery must be caller-visible failure: $result", result is ChatSendResult.Failed && result.acceptanceObserved)
        assertEquals(listOf("chat-R#notice"), replies.map { it.identity })
        assertEquals(listOf(R7_QUEUE_NOTICE), replies.map { it.text })
        assertTrue(replies.all { !it.heard && !it.cancelled && it.noticeTitle == AcceptedTurnReconciler.NOTICE_TITLE })
        assertEquals(0, audio)
        assertEquals(listOf("P", "Q", "R"), fake.prompts.map { it.second })
        p.release(); q.release()
    }
}
