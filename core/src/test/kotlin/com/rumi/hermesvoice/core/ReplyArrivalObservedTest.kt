package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.TurnRouting
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import com.rumi.hermesvoice.core.watchlink.LinkFrame
import com.rumi.hermesvoice.core.watchlink.PlayRequest
import com.rumi.hermesvoice.core.watchlink.PlayedAck
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchTransport
import com.rumi.hermesvoice.core.watchlink.WatchTurnUpload
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arrival alert for a final assistant answer that is not spoken, observed at the executable boundaries through the
 * production wiring (real OkHttp + gateway socket, orchestrator, Watch intake and sink, chat service) against
 * [FakeHermesDashboard]: what alerts, what never does, who alerts for a Watch-bound answer, and that one answer alerts exactly
 * once. It uses only what exists before the alert feature (the Phone's surface is [AlertRig]'s recording port, the Watch's is the
 * literal `/hv/v1/reply` message), so the same file runs on the immutable repair baseline, where the positive cases fail by assertion.
 */
class ReplyArrivalObservedTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rig = AlertRig(scope)
    private val shown: MutableList<String> get() = rig.shown

    @After
    fun stop() = scope.cancel()

    private fun harness(): CoreHarness = rig.harness()

    private inner class Watch(private val h: CoreHarness, @Volatile var reachable: Boolean = true) : WatchTransport {
        override val nodeId = "watch-node-1"
        val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val replies: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
        @Volatile var replyFails = false
        val holds = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        override suspend fun sendMessage(path: String, bytes: ByteArray) {
            if (path == "/hv/v1/reply") {
                if (replyFails) throw IllegalStateException("watch not reachable")
                val json = JSONObject(String(bytes))
                replies += json.getString("identity") to json.getString("session_id")
            }
        }

        override suspend fun sendChannel(path: String, bytes: ByteArray) {
            if (!reachable) throw IllegalStateException("watch not reachable")
            val play = PlayRequest.fromFrame(LinkFrame.decode(bytes))
            val text = String(play.audio).removePrefix("AUDIO:")
            played += "${play.role}:$text"
            CoroutineScope(Dispatchers.Default).launch {
                holds[text]?.await()
                h.core.watchAcks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, true).encode())
            }
        }
    }

    private fun watchTurn(h: CoreHarness, watch: Watch, turnId: String): VoiceTurnOutcome? = runBlocking {
        val frame = WatchTurnUpload(turnId, TurnTrigger.PUSH_TO_TALK, WatchTurnUpload.MIME_WAV, TestAudio.speechWav()).toFrame().encode()
        h.core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath(turnId), frame, watch)
    }

    private fun phoneTurn(h: CoreHarness, turnId: String, routing: TurnRouting, sink: PlaybackSink) = runBlocking {
        h.core.orchestrator.run(VoiceTurnRequest(turnId, VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav", sink, routing = routing))
    }

    private fun existing(h: CoreHarness, alias: String, firstReply: String): String {
        val owned = runBlocking { h.core.sessions.createConversation(alias.replaceFirstChar { it.uppercase() }, alias, "$alias things") }
        h.fake.scripts[owned.storedSessionId] = { listOf(FakeHermesDashboard.complete(firstReply)) }
        return owned.storedSessionId
    }

    private fun routeTo(h: CoreHarness, alias: String) {
        val reply: (String) -> List<Pair<String, org.json.JSONObject?>> =
            { listOf(FakeHermesDashboard.complete("""{"action":"route","destination":"$alias","ack":"Sending to $alias."}""")) }
        h.fake.sourceScripts[AppSources.ROUTER] = reply
        h.registry.router()?.let { h.fake.scripts[it.storedSessionId] = reply }
    }

    private fun waitFor(what: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(5_000) { while (!condition()) delay(10) }
        } catch (_: Exception) {
            throw AssertionError("timed out waiting for $what")
        }
    }

    private fun settle() = runBlocking { delay(500) }

    private val speaks = PlaybackSink { _, _ -> }
    private val finalFails = PlaybackSink { _, cue -> if (cue.role == SpokenRole.FINAL) throw IOException("no audio output") }

    // ── an own reply, on the Phone ───────────────────────────────────────────────────────────

    @Test
    fun `a spoken Phone reply never alerts`() {
        harness().use { h ->
            val work = existing(h, "work", "Done.")
            assertTrue(phoneTurn(h, "a-spoken-0001", TurnRouting.Direct(work), speaks) is VoiceTurnOutcome.Completed)
            settle()
            assertTrue("$shown", shown.isEmpty())
        }
    }

    @Test
    fun `a direct Phone reply whose audio could not play alerts once, for its own conversation`() {
        harness().use { h ->
            val work = existing(h, "work", "Done.")
            assertTrue(phoneTurn(h, "a-direct-0001", TurnRouting.Direct(work), finalFails) is VoiceTurnOutcome.DeliveredResponseFailed)
            waitFor("the alert") { shown.isNotEmpty() }
            settle()
            assertEquals(listOf("a-direct-0001#final@$work"), shown.toList())
        }
    }

    @Test
    fun `a routed Phone reply whose audio could not play alerts for the routed conversation, not the routing session`() {
        harness().use { h ->
            val work = existing(h, "work", "Done.")
            routeTo(h, "work")
            assertTrue(phoneTurn(h, "a-routed-0001", TurnRouting.Model, finalFails) is VoiceTurnOutcome.DeliveredResponseFailed)
            waitFor("the alert") { shown.isNotEmpty() }
            settle()
            assertEquals(listOf("a-routed-0001#final@$work"), shown.toList())
            assertFalse(shown.single().endsWith("@" + h.registry.router()?.storedSessionId))
        }
    }

    @Test
    fun `an interrupted, errored or empty completion is not a final answer`() {
        for ((index, completion) in listOf(
            FakeHermesDashboard.complete("stopped halfway", "interrupted"),
            FakeHermesDashboard.complete("", "error"),
            FakeHermesDashboard.complete(""),
        ).withIndex()) {
            harness().use { h ->
                val work = existing(h, "work", "x")
                h.fake.scripts[work] = { listOf(FakeHermesDashboard.interim("Checking."), completion) }
                // R01 policy migration: a remote interrupted/error/empty completion is not
                // genuine client Stop. Preserve no-answer-audio and strengthen silence to one
                // exact notice. The old assertion and real h12g FAIL remain in custody.
                val played = mutableListOf<SpokenRole>()
                phoneTurn(h, "a-nofinal-$index", TurnRouting.Direct(work), PlaybackSink { _, cue -> played += cue.role })
                assertTrue("a non-complete answer must not play FINAL audio: $played", SpokenRole.FINAL !in played)
                waitFor("exact non-answer notice $index") { "a-nofinal-$index#notice@$work" in shown }
                settle()
                assertEquals((0..index).map { "a-nofinal-$it#notice@$work" }, shown.toList())
                assertTrue("never a successful-answer alert", shown.none { "#final@" in it || "#reply@" in it })
                assertEquals("never resubmit a delivered request", 1, h.fake.rpcLog.count { it.optString("method") == "prompt.submit" })
            }
        }
    }

    @Test
    fun `a Stop during the reply never alerts`() {
        harness().use { h ->
            val work = existing(h, "work", "Done.")
            val gate = CompletableDeferred<Unit>()
            val reached = CompletableDeferred<Unit>()
            val job = scope.launch {
                h.core.orchestrator.run(VoiceTurnRequest("a-stop-0001", VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav",
                    PlaybackSink { _, cue -> if (cue.role == SpokenRole.FINAL) { reached.complete(Unit); gate.await() } },
                    routing = TurnRouting.Direct(work)))
            }
            runBlocking { withTimeout(5_000) { reached.await() } }
            job.cancel()
            settle()
            assertTrue("$shown", shown.isEmpty())
        }
    }

    // ── a Watch-bound reply ──────────────────────────────────────────────────────────────────

    @Test
    fun `a later reply that played on the Watch alerts nobody`() {
        harness().use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val watch = Watch(h)
            watchTurn(h, watch, "a-watch-0001")
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Finished."))
            waitFor("played") { watch.played.contains("FINAL:Finished.") }
            settle()
            assertTrue("$shown", shown.isEmpty())
            assertTrue(watch.replies.isEmpty())
        }
    }

    @Test
    fun `a later reply still being played on the Watch is not yet an alert, and is none once played`() {
        harness().use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val watch = Watch(h)
            watchTurn(h, watch, "a-watch-0002")
            val hold = CompletableDeferred<Unit>().also { watch.holds["Long result."] = it }
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Long result."))
            waitFor("playing") { watch.played.contains("FINAL:Long result.") }
            settle()
            assertTrue("pending speech is not non-voice yet: $shown", shown.isEmpty() && watch.replies.isEmpty())
            hold.complete(Unit)
            settle()
            assertTrue("$shown", shown.isEmpty() && watch.replies.isEmpty())
        }
    }

    @Test
    fun `a later reply the Watch could not play is alerted once, by the Watch alone`() {
        harness().use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val watch = Watch(h)
            watchTurn(h, watch, "a-watch-0003")
            watch.reachable = false
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Finished."))
            waitFor("the Watch alert") { watch.replies.isNotEmpty() }
            settle()
            val sent = watch.replies.single()
            assertTrue(sent.first.startsWith("a-watch-0003#later"))
            assertEquals(work, sent.second)
            assertTrue("the Phone shows none of its own: $shown", shown.isEmpty())
        }
    }

    @Test
    fun `when the Watch cannot be told, the Phone alerts instead, once`() {
        harness().use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val watch = Watch(h)
            watchTurn(h, watch, "a-watch-0004")
            watch.reachable = false
            watch.replyFails = true
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Finished."))
            waitFor("the Phone alert") { shown.isNotEmpty() }
            settle()
            assertEquals(1, shown.size)
            assertTrue(shown.single().endsWith("@$work"))
            assertTrue(watch.replies.isEmpty())
        }
    }

    @Test
    fun `a replayed frame, and the same turn reloaded, alert once`() {
        harness().use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val watch = Watch(h)
            watchTurn(h, watch, "a-watch-0005")
            watch.reachable = false
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Finished."))
            waitFor("first alert") { watch.replies.isNotEmpty() }
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Finished."), withStart = false)
            runBlocking { h.core.sessions.history(work) }
            settle()
            assertEquals(1, watch.replies.size)
        }
    }

    @Test
    fun `two distinct later replies with identical text each alert`() {
        harness().use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val watch = Watch(h)
            watchTurn(h, watch, "a-watch-0006")
            watch.reachable = false
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Same words."))
            waitFor("first") { watch.replies.size == 1 }
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Same words."))
            waitFor("second") { watch.replies.size == 2 }
            assertEquals(2, watch.replies.map { it.first }.toSet().size)
        }
    }

    @Test
    fun `a Stop or switching later replies off never makes a phantom alert`() {
        harness().use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val watch = Watch(h)
            watchTurn(h, watch, "a-watch-0007")
            val hold = CompletableDeferred<Unit>().also { watch.holds["Held."] = it }
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Held."))
            waitFor("playing") { watch.played.contains("FINAL:Held.") }
            h.core.orchestrator.stopFollowing()
            hold.complete(Unit)
            settle()
            assertTrue("$shown", shown.isEmpty() && watch.replies.isEmpty())
        }
    }

    @Test
    fun `later replies switched off are not followed, so nothing arrives and nothing alerts`() {
        rig.harness(consent = false).use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val watch = Watch(h)
            watchTurn(h, watch, "a-watch-0008")
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Finished."))
            settle()
            assertTrue("$shown", shown.isEmpty() && watch.replies.isEmpty())
        }
    }

    // ── a text reply, and old history ────────────────────────────────────────────────────────

    @Test
    fun `a text chat reply alerts once per send, even with the identical text, and loading history never alerts`() {
        harness().use { h ->
            val work = existing(h, "work", "Same text.")
            runBlocking { h.core.sessions.history(work) }
            settle()
            assertTrue("cold history is not an arrival: $shown", shown.isEmpty())
            val first = runBlocking { h.core.chat.send(work, "hello") } as ChatSendResult.Replied
            val second = runBlocking { h.core.chat.send(work, "hello again") } as ChatSendResult.Replied
            assertEquals(first.text, second.text)
            waitFor("two alerts") { shown.size == 2 }
            settle()
            assertEquals(2, shown.map { it.substringBefore("@") }.toSet().size)
            assertTrue(shown.all { it.endsWith("@$work") })
        }
    }

    // F04 policy migration: remote terminal failures owe an honest notice; true client cancellation stays silent.
    // The exact rejected no-alert assertion and native g5 FAIL are preserved outside the product in custody.
    @Test
    fun `terminal interrupted and error text completions show exact failure notices never answer alerts`() {
        harness().use { h ->
            val work = existing(h, "work", "x")
            val records = Collections.synchronizedList(mutableListOf<com.rumi.hermesvoice.core.notify.FinalReply>())
            val alerts = com.rumi.hermesvoice.core.notify.ReplyAlerts(
                com.rumi.hermesvoice.core.notify.ReplyAlertLedger(InMemoryKeyValueStore()),
                { shown += "${it.identity}@${it.storedSessionId}"; true }, scope)
            var next = 0
            var spoken = 0
            val chat = ChatService(h.core.sessions, newSendId = { "terminal-${++next}" }, finalReply = {
                records += it
                alerts.onFinalReply(it)
            }, typedFinal = { _, _, _, _ -> spoken++; false })
            for ((index, status) in listOf("interrupted", "error").withIndex()) {
                h.fake.scripts[work] = { listOf(FakeHermesDashboard.complete(if (index == 0) "partial" else "", status)) }
                val result = runBlocking { chat.send(work, "hello") }
                // This legacy result carries the terminal status, not evidence of a successful final answer.
                assertTrue("$result", result is ChatSendResult.Replied && result.status == status)
            }
            val expected = listOf("chat-terminal-1#notice", "chat-terminal-2#notice")
            assertEquals("one honest failure, tied to each exact send, never the partial answer", expected, records.map { it.identity })
            for (notice in records) {
                assertEquals(work, notice.storedSessionId)
                assertEquals(VoiceOrigin.PHONE, notice.target)
                assertEquals(com.rumi.hermesvoice.core.notify.FinalReplySource.TEXT, notice.source)
                assertFalse(notice.heard)
                assertFalse(notice.cancelled) // A remote interrupted status is NOT cancellation of this client coroutine.
                assertEquals("Your message reached Hermes, but the turn ended without a complete answer.", notice.text)
                // Read the real public property when it exists; this same test must also compile on e52e52d7.
                assertEquals("Reply not received", notice.javaClass.methods.singleOrNull { it.name == "getNoticeTitle" }?.invoke(notice))
            }
            waitFor("both honest notices") { shown.size == 2 }
            // Replay the same actual delivery inputs: identity-based dedup must not show either a second time.
            records.toList().forEach { alerts.onFinalReply(it) }
            settle()
            assertEquals(expected.map { "$it@$work" }, shown.toList())
            assertEquals("no failure text is spoken as an answer", 0, spoken)
            assertEquals("one submission per request, never a recovery resubmit", 2,
                h.fake.rpcLog.toList().count { it.optString("method") == "prompt.submit" })
        }
    }

    @Test
    fun `true text collector cancellation is silent even when the server later completes`() {
        harness().use { h ->
            val work = existing(h, "work", "Done after cancellation.")
            h.fake.turnBehavior = { "hold" }
            val accepted = CompletableDeferred<Unit>()
            val records = Collections.synchronizedList(mutableListOf<com.rumi.hermesvoice.core.notify.FinalReply>())
            var spoken = 0
            val chat = ChatService(h.core.sessions, newSendId = { "true-cancel" }, finalReply = { records += it },
                typedFinal = { _, _, _, _ -> spoken++; false })
            val job = scope.launch { chat.send(work, "hello", onAccepted = { accepted.complete(Unit) }) }
            runBlocking { withTimeout(5_000) { accepted.await() } }
            waitFor("the accepted held turn") { h.fake.heldTurns.containsKey(work) }
            job.cancel()
            runBlocking { withTimeout(5_000) { job.join() } }
            h.fake.finishHeldTurn(work)
            settle()
            assertTrue("the collecting coroutine was genuinely cancelled", job.isCancelled)
            assertTrue("neither a notice nor a successful answer after cancellation", records.isEmpty())
            assertTrue(shown.isEmpty())
            assertEquals(0, spoken)
            assertEquals("cancellation never resubmits", 1,
                h.fake.rpcLog.toList().count { it.optString("method") == "prompt.submit" })
        }
    }
}
