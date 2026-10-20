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
private class R7Envelope(var text: String, val authored: Boolean, val images: Boolean, val id: Int)

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
class QueuedFailClosedR7Test {
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
            override suspend fun call(method: String, params: JSONObject, timeoutMs: Long): JSONObject {
                if (method == "session.resume" && postAck) { postAckRead.complete(Unit); postAckRelease.await() }
                return real.call(method, params, if (method == "prompt.submit") submitDeadlineMs ?: timeoutMs else timeoutMs).also {
                    if (method == "prompt.submit" && params.optString("text") == "S" && holdPostAck) postAck = true
                }
            }
            override fun subscribe(sessionId: String) = real.subscribe(sessionId)
        }
    }

    @Volatile private var holdPostAck = false
    @Volatile private var postAck = false
    private val postAckRead = CompletableDeferred<Unit>()
    private val postAckRelease = CompletableDeferred<Unit>()
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
        private var runningId = 0
        private var nextId = 0
        private var head: R7Envelope? = null
        private val rest = ArrayDeque<R7Envelope>()
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
            if (first != null && first.text.isNotBlank()) snapshot.put("queued", JSONObject().put("user", first.text.trim())) else snapshot.remove("queued")
            if (live != null) snapshot.put("inflight", JSONObject().put("user", live.trim()).put("assistant", "").put("streaming", true)) else snapshot.remove("inflight")
        }

        /** prompt.submit: idle starts a turn; busy takes the queue branch (session_auto_continue._enqueue_prompt) and ALWAYS answers queued. */
        private fun admit(text: String, images: Boolean): String {
            val live = running ?: run { running = text; runningId = ++nextId; start(); return "streaming" }
            val textOnly = !images
            if (textOnly && text.trim() == live.trim()) return "queued" // dropped: a self-copy of the live prompt
            val first = head
            if (first != null && textOnly && !first.authored && !first.images && rest.isEmpty()) {
                first.text = "${first.text}\n\n$text" // merged into the existing head, no new turn
                return "queued"
            }
            val entry = R7Envelope(text, authored = false, images = images, id = ++nextId)
            if (first != null) rest.addLast(entry) else head = entry
            return "queued"
        }

        private fun start() { fake.pushLaterTurn(stored, FakeHermesDashboard.interim("working ${running!!}")) }

        /** Another sender's queued prompt (never merged with the app's text). */
        @Synchronized fun inject(text: String) {
            check(running != null) { "a queue exists only while a turn runs" }
            val entry = R7Envelope(text, authored = true, images = false, id = ++nextId)
            if (head == null) head = entry else rest.addLast(entry)
        }

        /** The server loses its queued entries (its own Stop / queue reset); nothing is announced to clients. */
        @Synchronized fun dropQueue() { head = null; rest.clear() }

        @Synchronized fun finish() {
            val own = checkNotNull(running)
            fake.pushLaterTurn(stored, FakeHermesDashboard.complete("envelope-$runningId:" + finalOf(own)), withStart = false)
            completed += own
            val next = head
            head = rest.removeFirstOrNull()
            running = next?.text
            if (next != null) runningId = next.id
            if (running != null) start()
        }

        @Synchronized fun finishWithoutDrain() {
            val own = checkNotNull(running)
            fake.pushLaterTurn(stored, FakeHermesDashboard.complete("envelope-$runningId:" + finalOf(own)), withStart = false)
            completed += own
            running = null
        }
        @Synchronized fun startQueued() {
            check(running == null)
            val next = checkNotNull(head)
            head = rest.removeFirstOrNull(); running = next.text; runningId = next.id; start()
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

    private fun prompts() = fake.rawPrompts.map { it.second }


    private val image = listOf(OutgoingAttachment("valid.png", "image/png", java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGNgAAIAAAUAAXpeqz8AAAAASUVORK5CYII=")))
    private suspend fun foreign(text: String, images: Boolean = true) {
        val rpc = connector.connection()
        if (images) rpc.call("image.attach_bytes", JSONObject().put("session_id", "rt-${server.stored}")
            .put("filename", "foreign.png").put("content_base64", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGNgAAIAAAUAAXpeqz8AAAAASUVORK5CYII="))
        rpc.call("prompt.submit", JSONObject().put("session_id", "rt-${server.stored}").put("text", text))
    }
    private suspend fun refused(turn: SubmittedTurn) {
        val got = answer(turn)
        assertEquals("NEIGHBOUR must never be delivered on an unprovable queue request", emptyList<String>(), got.first)
        assertEquals("R07 exact typed queued refusal", "HermesQueuedAttributionException", got.second?.javaClass?.simpleName)
        assertFalse("no armed queued collector", turn.attributable)
        assertNull("no durable queued acceptance", turn.acceptance)
    }
    @Test fun R00_ordinarySequentialStreamingAndSafeReattach() = runBlocking<Unit> {
        val p = port.submit(server.stored, "P")
        val saved = checkNotNull(p.acceptance)
        server.finish(); barrier()
        assertEquals(listOf("envelope-1:answer P"), answer(p).first)
        val reattached = port.reattach(server.stored, saved)
        assertEquals(listOf("envelope-1:answer P"), answer(reattached).first)
        val u = port.submit(server.stored, "U"); server.finish(); barrier()
        assertEquals(listOf("envelope-2:answer U"), answer(u).first)
        assertEquals(listOf("P", "U"), prompts())
    }
    @Test fun R01_postAckReadAfterMergedHeadFinishedNeverArmsSOrStealsU() = runBlocking<Unit> {
        val p = port.submit(server.stored, "P")
        val r = port.submit(server.stored, "R")
        holdPostAck = true
        val pending = async { port.submit(server.stored, "S") }
        kotlinx.coroutines.selects.select<Unit> {
            pending.onAwait { }
            postAckRead.onAwait { }
        }
        server.finish(); server.finish() // P and merged M finish BEFORE pooled resume snapshots
        postAckRelease.complete(Unit); postAck = false
        val s = withTimeout(8_000) { pending.await() }
        val collecting = async { answer(s) }
        val u = port.submit(server.stored, "U"); server.finish(); barrier()
        val got = collecting.await()
        assertEquals("NEIGHBOUR U must never be S's final", emptyList<String>(), got.first)
        assertEquals("R07 exact typed queued refusal", "HermesQueuedAttributionException", got.second?.javaClass?.simpleName)
        assertFalse(s.attributable); assertNull(s.acceptance)
        p.release(); r.release(); u.release()
        assertEquals(listOf("P", "R", "S", "U"), prompts())
    }
    private suspend fun sameText(foreignText: String, localText: String) {
        val p = port.submit(server.stored, "P")
        foreign(foreignText)
        val r = port.submit(server.stored, localText)
        server.drain(); barrier()
        assertEquals("the foreign image is really consumed by its own supported RPC submit", 1, fake.consumedImages[1].second.size)
        refused(r); p.release()
        assertEquals(listOf("P", foreignText, localText), prompts())
    }
    @Test fun R02_equalRawTextForeignImageHeadIsNotEnvelopeIdentity() = runBlocking<Unit> { sameText("R", "R") }
    @Test fun R03_voiceMarkerStrippingIsNotEnvelopeIdentity() = runBlocking<Unit> { sameText("🎙 R", "R") }
    @Test fun R04_knownImageHeadDoesNotCountInvisibleForeignTail() = runBlocking<Unit> {
        val p = port.submit(server.stored, "P")
        val r = port.submit(server.stored, "R", image)
        foreign("X")
        val s = port.submit(server.stored, "S", image)
        server.drain(); barrier()
        assertEquals(listOf("P", "R", "X", "S"), server.completed)
        refused(s); p.release(); r.release()
        assertEquals(listOf("P", "R", "X", "S"), prompts())
    }
    @Test fun R05_blankImageHeadIdleProjectionCannotRetireUnknownQueue() = runBlocking<Unit> {
        val p = port.submit(server.stored, "P")
        val blank = port.submit(server.stored, "", image)
        server.finishWithoutDrain()
        val snap = connector.connection().call("session.resume", JSONObject().put("session_id", server.stored))
        assertFalse(snap.getBoolean("running")); assertFalse(snap.has("queued")); assertEquals("idle", snap.getString("status"))
        val s = port.submit(server.stored, "S")
        // Real finished -> drain window: blank image envelope is invisible, not nonexistent.
        server.finishWithoutDrain(); server.startQueued(); server.finish(); barrier()
        val blankGot = answer(blank)
        assertEquals("NEIGHBOUR S must never be the hidden blank image request's final", emptyList<String>(), blankGot.first)
        assertEquals("HermesQueuedAttributionException", blankGot.second?.javaClass?.simpleName)
        val got = answer(s)
        assertEquals("unresolved blank image custody is not retired by omitted queued", emptyList<String>(), got.first)
        assertEquals("R07 uncertainty must not be upgraded by idle-shaped projection", "HermesReplyUnrecoverableException", got.second?.javaClass?.simpleName)
        refused(blank); p.release()
        assertEquals(listOf("P", "", "S"), prompts())
    }
    @Test fun R06_legacyQueuedAcceptanceCannotReattachOrReadNeighbour() = runBlocking<Unit> {
        val p = port.submit(server.stored, "P")
        val r = port.submit(server.stored, "R")
        val legacy = TurnAcceptance("rt-${server.stored}", 0, connector.connection().replayEpoch, 1)
        server.drain(); barrier()
        val e = try { val t = port.reattach(server.stored, legacy); answer(t).second } catch (e: HermesException) { e }
        assertEquals("NEIGHBOUR old serialized ordinals are not authority", "HermesReplyUnrecoverableException", e?.javaClass?.simpleName)
        p.release(); r.release()
    }
    @Test fun R07_cancellationAtPostAckReadRemainsSilentAndNeverResubmits() = runBlocking<Unit> {
        val p = port.submit(server.stored, "P")
        val r = port.submit(server.stored, "R")
        holdPostAck = true
        val pending = async { port.submit(server.stored, "S") }
        kotlinx.coroutines.selects.select<Unit> { pending.onAwait { }; postAckRead.onAwait { } }
        if (!pending.isCompleted) pending.cancelAndJoin() else pending.await().release()
        postAckRelease.complete(Unit); postAck = false
        server.drain(); barrier()
        p.release(); r.release()
        assertEquals(listOf("P", "R", "S"), prompts())
    }
    private fun voice(notices: MutableList<com.rumi.hermesvoice.core.notify.FinalReply>, audio: MutableList<String>) = VoiceTurnOrchestrator(
        object : HermesSpeechGateway {
            override suspend fun transcribe(audio: ByteArray, mimeType: String) = "R"
            override suspend fun speak(text: String) = SpokenAudio(text.toByteArray(), "audio/mpeg")
        }, port,
        config = { VoiceTurnConfig(null, DestinationAllowlist.create(listOf(DestinationEntry("work", server.stored)), "router"), ResponsePlaybackSettings()) },
        responseTimeoutMs = 3_000, finalReply = { notices += it })
    @Test fun R10_queuedVoiceHasExactNoticeNoNeighbourAudioAndNoReplay() = runBlocking<Unit> {
        val notices = mutableListOf<com.rumi.hermesvoice.core.notify.FinalReply>()
        val audio = mutableListOf<String>()
        val p = port.submit(server.stored, "P")
        val o = voice(notices, audio)
        val send = async { o.run(VoiceTurnRequest("voice-r7-q", VoiceOrigin.PHONE, byteArrayOf(), "audio/wav",
            PlaybackSink { _, cue -> if (cue.role == SpokenRole.FINAL) audio += cue.text }, recognizedText = "R", routing = TurnRouting.Direct(server.stored))) }
        admitted("🎙 R").await(); server.drain(); barrier()
        val result = send.await()
        assertTrue("queued voice must close with no guessed reply: $result", result is VoiceTurnOutcome.DeliveredUnattributed)
        assertEquals(emptyList<String>(), audio)
        assertEquals(listOf("voice-r7-q#notice"), notices.map { it.identity })
        assertEquals(listOf(R7_QUEUE_NOTICE), notices.map { it.text })
        assertEquals(listOf("P", "🎙 R"), prompts()); p.release()
    }
    @Test fun R11_voiceLostSubmitReplyIsPossiblySentNeverNotSentOrReplayed() = runBlocking<Unit> {
        val notices = mutableListOf<com.rumi.hermesvoice.core.notify.FinalReply>()
        val audio = mutableListOf<String>()
        fake.beforeAsyncReply = { fake.dropGateway() }
        val o = voice(notices, audio)
        val request = VoiceTurnRequest("voice-r7-lost", VoiceOrigin.PHONE, byteArrayOf(), "audio/wav",
            PlaybackSink { _, cue -> if (cue.role == SpokenRole.FINAL) audio += cue.text }, recognizedText = "R", routing = TurnRouting.Direct(server.stored))
        val result = o.run(request)
        assertEquals("Acceptance uncertain: check the conversation. Do not retry this request", com.rumi.hermesvoice.core.watchlink.VoiceOutcomeText.describe(result))
        assertEquals(emptyList<String>(), audio)
        assertEquals(listOf("voice-r7-lost#notice"), notices.map { it.identity })
        assertEquals(VoiceTurnOutcome.Duplicate("voice-r7-lost"), o.run(request))
        assertEquals(listOf("🎙 R"), prompts())
    }

}
