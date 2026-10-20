package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.*
import com.rumi.hermesvoice.core.net.*
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

/** One entry of the modelled server queue. [authored]: another sender's prompt (never merged); [images]: image-bearing (never merged or dropped). */
private class QueueEnvelope(var text: String, val authored: Boolean, val images: Boolean)

/**
 * R06: the installed tui_gateway's busy-submit QUEUE branch is not "one prompt, one FIFO turn". Read from the installed originals
 * (session_auto_continue `_enqueue_prompt` / `_handle_busy_submit`, server `_live_session_payload`): a text-only prompt MERGES into an
 * existing text-only queue head and answers `queued` without adding a turn, a text-only repeat of the RUNNING prompt is DROPPED and still
 * answers `queued`, and the resume projection shows only the queue HEAD (`queued.user`) plus the running prompt (`inflight.user`).
 * The queue branch is reached when the agent is absent or the busy-input policy falls through (the app never sends `queued`); these
 * tests model that branch only. They are NOT evidence of what a live Hermes does, nor of physical Phone/Watch behaviour.
 *
 * Real OkHttp/WebSocket/JSON-RPC against a state-derived model: only the modelled queue decides acknowledgements, the resume projection
 * and the frames (no prompt-index statuses, no sleeps). `beforeAdmission` is the opt-in fixture seam that holds a frame the server has READ
 * on one connection but not yet ADMITTED, so its effect can be applied after a replacement connection already read idle and submitted.
 */
class RealQueueContractTest {
    private val fake = FakeHermesDashboard()
    private val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
    private val dashboard = HermesDashboardClient(HermesDashboardEndpoint.parse(fake.baseUrl), http,
        InMemoryHermesTokenStore(HermesBearerSession("AT-1", "RT-1", null, "basic", "jun")))
    private val connector = HermesGatewayConnector(dashboard, http)
    private val server = ModelledQueue()

    /** Test-only deadline adapter at the real call boundary: only prompt.submit's own await is shortened. */
    @Volatile private var submitDeadlineMs: Long? = null

    /** A fresh wrapper per call (like the other custody tests): connection identity must survive it through the subscription. */
    private val port = GatewayConversationPort {
        val real = connector.connection()
        object : GatewayRpc {
            override val replayEpoch: String get() = real.replayEpoch
            override suspend fun call(method: String, params: JSONObject, timeoutMs: Long): JSONObject =
                real.call(method, params, if (method == "prompt.submit") submitDeadlineMs ?: timeoutMs else timeoutMs)
            override fun subscribe(sessionId: String) = real.subscribe(sessionId)
        }
    }

    private val held = CompletableDeferred<Unit>()
    private val release = CountDownLatch(1)
    private val admittedPrompts = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private fun admitted(prompt: String): CompletableDeferred<Unit> = admittedPrompts.getOrPut(prompt) { CompletableDeferred() }

    init {
        // The server has decided on a prompt (queue state already changed): its answer may still be held.
        fake.beforeAsyncReply = { admitted(fake.rawPrompts.last().second).complete(Unit) }
    }

    @After fun close() {
        release.countDown()
        connector.close()
        fake.close()
    }

    private inner class ModelledQueue {
        val stored = "dest"
        private var running: String? = null
        private var head: QueueEnvelope? = null
        private val rest = ArrayDeque<QueueEnvelope>()
        val completed = mutableListOf<String>()

        init {
            fake.scripts[stored] = { emptyList() }
            fake.turnBehavior = { "async_hold" }
            fake.beforeResumeReply = { snapshot -> synchronized(this) { project(snapshot) } }
            fake.submitStatus = { synchronized(this) { admit(fake.rawPrompts.last().second, fake.consumedImages.last().second.isNotEmpty()) } }
        }

        /** server._live_session_payload: `queued` only for the HEAD, `inflight.user` for the running prompt, both omitted when empty. */
        private fun project(snapshot: JSONObject) {
            val live = running
            snapshot.put("running", live != null).put("status", if (live != null) "working" else "idle")
            snapshot.optJSONObject("info")?.put("running", live != null)
            snapshot.put("messages_omitted", true)
            snapshot.put("turn_started_at", if (live != null) 1_700_000_000.0 else JSONObject.NULL)
            val first = head
            if (first != null) snapshot.put("queued", JSONObject().put("user", first.text.trim())) else snapshot.remove("queued")
            if (live != null) snapshot.put("inflight", JSONObject().put("user", live.trim()).put("assistant", "").put("streaming", true)) else snapshot.remove("inflight")
        }

        /** prompt.submit: idle starts a turn; busy takes the queue branch (session_auto_continue._enqueue_prompt) and ALWAYS answers queued. */
        private fun admit(text: String, images: Boolean): String {
            val live = running ?: run { running = text; start(); return "streaming" }
            val textOnly = !images
            if (textOnly && text.trim() == live.trim()) return "queued" // dropped: a self-copy of the live prompt
            val first = head
            if (first != null && textOnly && !first.authored && !first.images && rest.isEmpty()) {
                first.text = "${first.text}\n\n$text" // merged into the existing head, no new turn
                return "queued"
            }
            val entry = QueueEnvelope(text, authored = false, images = images)
            if (first != null) rest.addLast(entry) else head = entry
            return "queued"
        }

        private fun start() { fake.pushLaterTurn(stored, FakeHermesDashboard.interim("working ${running!!}")) }

        /** Another sender's queued prompt (never merged with the app's text). */
        @Synchronized fun inject(text: String) {
            check(running != null) { "a queue exists only while a turn runs" }
            val entry = QueueEnvelope(text, authored = true, images = false)
            if (head == null) head = entry else rest.addLast(entry)
        }

        /** The server loses its queued entries (its own Stop / queue reset); nothing is announced to clients. */
        @Synchronized fun dropQueue() { head = null; rest.clear() }

        @Synchronized fun finish() {
            val own = checkNotNull(running)
            fake.pushLaterTurn(stored, FakeHermesDashboard.complete(finalOf(own)), withStart = false)
            completed += own
            val next = head
            head = rest.removeFirstOrNull()
            running = next?.text
            if (running != null) start()
        }

        @Synchronized fun drain() { while (running != null) finish() }
    }

    private fun finalOf(turnText: String) = "answer " + turnText.replace("\n\n", "+")

    private suspend fun barrier() {
        connector.connection().call("session.events.since", JSONObject().put("session_id", "rt-${server.stored}").put("last_seen", Long.MAX_VALUE))
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

    private fun prompts() = fake.rawPrompts.map { it.second }

    @Test fun Q00_theFixtureModelsTheInstalledServersQueueMergeAndDuplicateDropRules() = runBlocking<Unit> {
        val p = port.submit(server.stored, "P")
        val r = port.submit(server.stored, "R")
        val s = port.submit(server.stored, "S")
        val repeated = port.submit(server.stored, "P")
        assertEquals(listOf("queued", "queued", "queued"), listOf(r.submitStatus, s.submitStatus, repeated.submitStatus))
        val view = connector.connection().call("session.resume", JSONObject().put("session_id", server.stored).put("omit_messages", true))
        assertEquals("S merged into R's envelope: one head, no extra turn", "R\n\nS", view.getJSONObject("queued").getString("user"))
        assertEquals("P", view.getJSONObject("inflight").getString("user"))
        server.drain(); barrier()
        assertEquals("merge adds no turn and the repeat of the running prompt adds none", listOf("P", "R\n\nS"), server.completed)
        for (turn in listOf(p, r, s, repeated)) turn.release()
    }

    @Test fun Q01_aTextOnlyMergeIntoTheQueuedHeadAddsNoTurnSoNoLaterSendAdoptsAnotherTurnsFinal() = runBlocking<Unit> {
        val p = port.submit(server.stored, "P")
        val r = port.submit(server.stored, "R")
        assertEquals("queued", r.submitStatus)
        val s = port.submit(server.stored, "S")
        assertEquals("the server merged S into R's envelope yet answered queued", "queued", s.submitStatus)
        val pAnswer = async { answer(p) }
        val rAnswer = async { answer(r) }
        val sAnswer = async { answer(s) }
        server.finish() // P
        server.finish() // the ONE merged turn of R and S
        barrier()
        assertEquals(listOf("P", "R\n\nS"), server.completed)
        val u = port.submit(server.stored, "U")
        assertEquals("the server is idle again", "streaming", u.submitStatus)
        server.finish(); barrier()
        val sGot = withTimeout(8_000) { sAnswer.await() }
        assertFalse("NEIGHBOUR U's FINAL MUST NEVER BE DELIVERED AS S's REPLY: $sGot", sGot.first.contains(finalOf("U")))
        assertEquals("S is delivered but never attributed a reply: $sGot", emptyList<String>(), sGot.first)
        assertTrue("typed unattributable outcome for S: $sGot", sGot.second is HermesProtocolException)
        assertFalse("S's acknowledgement is not a FIFO position", s.attributable)
        assertEquals(listOf(finalOf("P")), withTimeout(8_000) { pAnswer.await() }.first)
        val rGot = withTimeout(8_000) { rAnswer.await() }
        assertEquals("HermesQueuedAttributionException", rGot.second?.javaClass?.simpleName)
        assertEquals(emptyList<String>(), rGot.first)
        assertNull(r.acceptance); assertFalse(r.attributable)
        own(u, "U")
        assertEquals("accepted prompts are never resubmitted", listOf("P", "R", "S", "U"), prompts())
    }

    @Test fun Q02_aRepeatOfTheRunningPromptIsDroppedByTheServerSoItIsNeverACountedPosition() = runBlocking<Unit> {
        val p = port.submit(server.stored, "hello")
        val r = port.submit(server.stored, "hello")
        assertEquals("dropped as a self-copy yet answered queued", "queued", r.submitStatus)
        val pAnswer = async { answer(p) }
        val rAnswer = async { answer(r) }
        server.finish(); barrier()
        assertEquals(listOf("hello"), server.completed)
        val u = port.submit(server.stored, "U")
        assertEquals("streaming", u.submitStatus)
        server.finish(); barrier()
        val rGot = withTimeout(8_000) { rAnswer.await() }
        assertFalse("NEIGHBOUR U's FINAL MUST NEVER BE DELIVERED AS THE DROPPED REPEAT's REPLY: $rGot", rGot.first.contains(finalOf("U")))
        assertEquals("the dropped repeat is never attributed a reply: $rGot", emptyList<String>(), rGot.first)
        assertTrue("typed unattributable outcome: $rGot", rGot.second is HermesProtocolException)
        assertEquals(listOf(finalOf("hello")), withTimeout(8_000) { pAnswer.await() }.first)
        own(u, "U")
        assertEquals("accepted prompts are never resubmitted", listOf("hello", "hello", "U"), prompts())
    }

    @Test fun Q03_aVisibleQueueHeadTheKnownPositionsDoNotAccountForIsNeverAdopted() = runBlocking<Unit> {
        val p = port.submit(server.stored, "P")
        server.inject("X") // another sender's queued prompt, ahead of anything this app adds
        val r = port.submit(server.stored, "R")
        assertEquals("queued", r.submitStatus)
        val pAnswer = async { answer(p) }
        val rAnswer = async { answer(r) }
        server.finish() // P
        server.finish() // X
        server.finish() // R
        barrier()
        assertEquals(listOf("P", "X", "R"), server.completed)
        val rGot = withTimeout(8_000) { rAnswer.await() }
        assertFalse("NEIGHBOUR X's FINAL MUST NEVER BE DELIVERED AS R's REPLY: $rGot", rGot.first.contains(finalOf("X")))
        assertEquals("R sat behind an unaccounted head: delivered, never attributed: $rGot", emptyList<String>(), rGot.first)
        assertTrue("typed unattributable outcome: $rGot", rGot.second is HermesProtocolException)
        assertEquals(listOf(finalOf("P")), withTimeout(8_000) { pAnswer.await() }.first)
        val v = port.submit(server.stored, "V")
        assertEquals("streaming", v.submitStatus)
        server.finish(); barrier()
        own(v, "V")
        assertEquals("accepted prompts are never resubmitted", listOf("P", "R", "V"), prompts())
    }

    @Test fun Q04_aUniqueQueuedSendBehindTheRunningTurnAndLaterSequentialSendsKeepTheirOwnFinals() = runBlocking<Unit> {
        val p = port.submit(server.stored, "P")
        val r = port.submit(server.stored, "R")
        assertEquals("queued", r.submitStatus)
        assertFalse("a text echo is never queued ownership proof", r.attributable); assertNull(r.acceptance)
        val pAnswer = async { answer(p) }
        val rAnswer = async { answer(r) }
        server.finish(); server.finish(); barrier()
        assertEquals(listOf(finalOf("P")), withTimeout(8_000) { pAnswer.await() }.first)
        val rGot = withTimeout(8_000) { rAnswer.await() }
        assertEquals("HermesQueuedAttributionException", rGot.second?.javaClass?.simpleName)
        assertEquals(emptyList<String>(), rGot.first)
        val u = port.submit(server.stored, "U"); assertEquals("streaming", u.submitStatus); server.finish(); barrier(); own(u, "U")
        val v = port.submit(server.stored, "V"); assertEquals("streaming", v.submitStatus); server.finish(); barrier(); own(v, "V")
        assertEquals(listOf("P", "R", "U", "V"), prompts())
    }

    @Test fun Q05_aFreshIdleReadRetiresATailTheServerCanNoLongerRun() = runBlocking<Unit> {
        val p = port.submit(server.stored, "P")
        val r = port.submit(server.stored, "R")
        assertEquals("queued", r.submitStatus)
        r.release() // local Stop of R dequeues nothing remotely
        server.dropQueue() // the server itself later loses R's queued entry
        server.finish(); barrier() // P ends; nothing is left to run
        val u = port.submit(server.stored, "U")
        assertEquals("the server is idle", "streaming", u.submitStatus)
        server.finish(); barrier()
        own(u, "U")
        assertEquals(listOf("P", "R", "U"), prompts())
        p.release()
    }

    @Test fun Q06_aLateAdmissionFromTheOldConnectionNeverMakesANewerSendAdoptTheAbandonedPromptsFinal() = runBlocking<Unit> {
        // R's frame is READ by the server on the first connection but not admitted: its effect is held.
        fake.beforeAdmission = { params -> if (params.optString("text") == "R") { held.complete(Unit); check(release.await(10, TimeUnit.SECONDS)) { "the held admission was never released" } } }
        val r = async { runCatching { port.submit(server.stored, "R") } }
        withTimeout(8_000) { held.await() }
        fake.dropGateway() // the client loses that connection; the server has not applied R yet
        val lost = withTimeout(8_000) { r.await() }
        assertTrue("R's answer never came: $lost", lost.exceptionOrNull() is HermesException)
        val s = port.submit(server.stored, "S") // the REPLACEMENT connection reads idle and submits before R is admitted
        assertEquals("streaming", s.submitStatus)
        release.countDown() // NOW the old connection's frame is admitted: the server is busy with S, so R queues behind it
        withTimeout(8_000) { admitted("R").await() }
        val png = byteArrayOf(1, 2, 3)
        val t = port.submit(server.stored, "T", listOf(OutgoingAttachment("t.png", "image/png", png))) // image-bearing: its own envelope, behind R
        assertEquals("queued", t.submitStatus)
        val sAnswer = async { answer(s) }
        val tAnswer = async { answer(t) }
        server.finish() // S
        server.finish() // R, admitted late
        server.finish() // T
        barrier()
        assertEquals(listOf("S", "R", "T"), server.completed)
        assertEquals("unanswered old frame cannot be retired by replacement-connection idle", emptyList<String>(), withTimeout(8_000) { sAnswer.await() }.first)
        assertNull(s.acceptance)
        val tGot = withTimeout(8_000) { tAnswer.await() }
        assertFalse("NEIGHBOUR R's FINAL (the abandoned prompt) MUST NEVER BE DELIVERED AS T's REPLY: $tGot", tGot.first.contains(finalOf("R")))
        assertEquals("T queued behind a late head the app cannot account for: delivered, never attributed: $tGot", emptyList<String>(), tGot.first)
        assertTrue("typed unattributable outcome: $tGot", tGot.second is HermesProtocolException)
        assertEquals("no accepted or possibly accepted prompt is resubmitted", listOf("S", "R", "T"), prompts())
    }

    @Test fun Q07_sameConnectionIdleReadStillRecoversAfterAHeldAdmissionAndADeadline() = runBlocking<Unit> {
        fake.beforeAdmission = { params -> if (params.optString("text") == "R") { held.complete(Unit); check(release.await(10, TimeUnit.SECONDS)) { "the held admission was never released" } } }
        submitDeadlineMs = 500
        val r = async { runCatching { port.submit(server.stored, "R") } }
        withTimeout(8_000) { held.await() }
        val timedOut = withTimeout(8_000) { r.await() }
        submitDeadlineMs = null
        assertTrue("the bounded call timed out while the server still held the frame: $timedOut", timedOut.exceptionOrNull() is HermesProtocolException)
        release.countDown() // the SAME connection's frame is admitted now: the server was idle, so R runs
        withTimeout(8_000) { admitted("R").await() }
        server.drain(); barrier()
        assertEquals(listOf("R"), server.completed)
        val n = port.submit(server.stored, "N")
        assertEquals("the server is idle and the idle read came on the connection that carried R's frame", "streaming", n.submitStatus)
        server.finish(); barrier()
        own(n, "N")
        assertEquals(listOf("R", "N"), prompts())
    }
}
