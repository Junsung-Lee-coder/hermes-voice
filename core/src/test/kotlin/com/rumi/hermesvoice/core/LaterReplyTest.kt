package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.voice.AssembledRoute
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.TurnRouting
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import com.rumi.hermesvoice.core.watchlink.LinkFrame
import com.rumi.hermesvoice.core.watchlink.PlayRequest
import com.rumi.hermesvoice.core.watchlink.PlayedAck
import com.rumi.hermesvoice.core.watchlink.TurnStateMessage
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchTransport
import com.rumi.hermesvoice.core.watchlink.WatchTurnUpload
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two reports "no Watch reply after routing to a new conversation" and "no speech when an
 * asynchronous task finishes": Hermes delivers a session's later output (a background process or
 * async-delegation completion, a goal follow-up) as a NEW turn on that session (tui_gateway
 * session_notifications / prompt_turn: `message.start` … `message.complete` on the same runtime
 * id), after the turn the app submitted has already completed. Through the production wiring
 * (real OkHttp WebSocket, gateway connection, orchestrator, Watch intake, Watch playback sink and
 * ACK registry) against [FakeHermesDashboard].
 */
class LaterReplyTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val later: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val listener = object : VoiceTurnListener {
        override fun onLaterReply(turnId: String, played: Boolean, detail: String) {
            later += "$turnId:${if (played) "played" else "not_played"}:$detail"
        }
    }

    @After
    fun stop() = scope.cancel()

    /** With the user's opt-in on (it is off by default: LaterReplyReviewTest). */
    private fun harness(windowMs: Long = 60_000L) = CoreHarness(laterScope = scope, laterWindowMs = windowMs, voiceListener = listener)
        .also { it.laterConsent.enabled = true }

    /** A paired Watch: plays what it is sent and confirms it, like the real one, from its own node. */
    private inner class Watch(private val h: CoreHarness, @Volatile var reachable: Boolean = true) : WatchTransport {
        override val nodeId = "watch-node-1"
        val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val states: MutableList<TurnStateMessage> = Collections.synchronizedList(mutableListOf())
        override suspend fun sendMessage(path: String, bytes: ByteArray) {
            if (path == WatchLinkPaths.STATE) TurnStateMessage.decode(bytes)?.let { states += it }
        }

        /** Playback of this text holds until completed (a long utterance still playing). */
        val holds = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Unit>>()

        override suspend fun sendChannel(path: String, bytes: ByteArray) {
            if (!reachable) throw IllegalStateException("watch not reachable")
            val play = PlayRequest.fromFrame(LinkFrame.decode(bytes))
            val text = String(play.audio).removePrefix("AUDIO:")
            played += "${play.role}:$text"
            kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
                holds[text]?.await()
                h.core.watchAcks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, true).encode())
            }
        }
    }

    private fun watchTurn(h: CoreHarness, watch: Watch, turnId: String): VoiceTurnOutcome? = runBlocking {
        val frame = WatchTurnUpload(turnId, TurnTrigger.PUSH_TO_TALK, WatchTurnUpload.MIME_WAV, TestAudio.speechWav()).toFrame().encode()
        h.core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath(turnId), frame, watch)
    }

    private fun phoneTurn(h: CoreHarness, turnId: String, played: MutableList<String>, routing: TurnRouting = TurnRouting.Model) = runBlocking {
        h.core.orchestrator.run(VoiceTurnRequest(turnId, VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav",
            PlaybackSink { audio, cue -> played += "${cue.role}:${h.fake.decodeSpoken(audio)}" }, routing = routing))
    }

    /** The router's next answers: for a routing session still to be created and for the existing one. */
    private fun routeTo(h: CoreHarness, alias: String) {
        val reply: (String) -> List<Pair<String, org.json.JSONObject?>> =
            { listOf(FakeHermesDashboard.complete("""{"action":"route","destination":"$alias","ack":"Sending to $alias."}""")) }
        h.fake.sourceScripts[AppSources.ROUTER] = reply
        h.registry.router()?.let { h.fake.scripts[it.storedSessionId] = reply }
    }

    private fun existing(h: CoreHarness, alias: String, firstReply: String): String {
        val owned = runBlocking { h.core.sessions.createConversation(alias.replaceFirstChar { it.uppercase() }, alias, "$alias things") }
        h.fake.scripts[owned.storedSessionId] = { listOf(FakeHermesDashboard.complete(firstReply)) }
        return owned.storedSessionId
    }

    private fun waitFor(what: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(5_000) { while (!condition()) delay(10) }
        } catch (_: Exception) {
            throw AssertionError("timed out waiting for $what")
        }
    }

    /** Lets anything wrongly scheduled happen before asserting it did not. */
    private fun settle() = runBlocking { delay(400) }

    private fun completion(text: String, status: String = "complete") = FakeHermesDashboard.complete(text, status)

    // ── the reports ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `positive control - a reply inside the submitted turn plays on the Watch as before`() {
        harness().use { h ->
            val work = existing(h, "work", "Meeting moved to 3.")
            routeTo(h, "work")
            val watch = Watch(h)
            val outcome = watchTurn(h, watch, "w-turn-0001") as VoiceTurnOutcome.Completed
            assertEquals(work, outcome.route.destination.storedSessionId)
            assertEquals(listOf("ACK:Sending to work.", "FINAL:Meeting moved to 3."), watch.played.toList())
        }
    }

    @Test
    fun `an asynchronous completion arriving after the Watch turn ended is spoken on the Watch`() {
        harness().use { h ->
            val work = existing(h, "work", "Started the report; I'll tell you when it's done.")
            routeTo(h, "work")
            val watch = Watch(h)
            watchTurn(h, watch, "w-turn-0002") as VoiceTurnOutcome.Completed
            assertTrue("the Watch was told the turn ended", watch.states.last().terminal)
            h.fake.pushLaterTurn(work, completion("The report is done: 3 findings."))
            waitFor("the later reply on the Watch") { watch.played.contains("FINAL:The report is done: 3 findings.") }
            assertEquals(listOf("ACK:Sending to work.", "FINAL:Started the report; I'll tell you when it's done.",
                "FINAL:The report is done: 3 findings."), watch.played.toList())
            waitFor("the outcome") { later.isNotEmpty() }
            assertEquals(listOf("w-turn-0002:played:watch"), later.toList())
        }
    }

    @Test
    fun `a newly created conversation's later reply is spoken on the Watch too`() {
        harness().use { h ->
            h.fake.sourceScripts[AppSources.ROUTER] = {
                listOf(FakeHermesDashboard.complete("""{"action":"create","title":"Garden plans","alias":"garden","description":"Plants"}"""))
            }
            h.fake.sourceScripts[AppSources.CONVERSATION] = { listOf(FakeHermesDashboard.complete("On it.")) }
            val watch = Watch(h)
            val outcome = watchTurn(h, watch, "w-turn-0003") as VoiceTurnOutcome.Completed
            assertTrue(outcome.route.created)
            val made = outcome.route.destination.storedSessionId
            h.fake.pushLaterTurn(made, completion("Planting plan ready."))
            waitFor("the created conversation's later reply") { watch.played.contains("FINAL:Planting plan ready.") }
            assertEquals("ACK, first reply, later reply", 3, watch.played.size)
            assertEquals(VoiceOrigin.WATCH, h.core.orchestrator.playbackRoute.device.value)
        }
    }

    @Test
    fun `a Phone request's later reply plays on the Phone, whatever routing or screen it was`() {
        harness().use { h ->
            val work = existing(h, "work", "Working on it.")
            val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
            phoneTurn(h, "p-turn-0001", played, TurnRouting.Direct(work)) as VoiceTurnOutcome.Completed
            h.fake.pushLaterTurn(work, completion("Done."))
            waitFor("the later reply on the Phone") { played.contains("FINAL:Done.") }
            assertEquals(3, played.size)
        }
    }

    // ── what counts as a later reply ─────────────────────────────────────────────────────────

    @Test
    fun `distinct later replies each play once, a replayed frame never plays again, and failed ones never play`() {
        harness().use { h ->
            val work = existing(h, "work", "Queued two jobs.")
            routeTo(h, "work")
            val watch = Watch(h)
            watchTurn(h, watch, "w-turn-0004")
            h.fake.pushLaterTurn(work, completion("Job one done."))
            waitFor("first") { watch.played.contains("FINAL:Job one done.") }
            h.fake.pushLaterTurn(work, completion("Job one done."), withStart = false) // a replayed frame, no new turn
            h.fake.pushLaterTurn(work, completion("", "error"))
            h.fake.pushLaterTurn(work, completion("stopped halfway", "interrupted"))
            h.fake.pushLaterTurn(work, completion("Job two done."))
            waitFor("second") { watch.played.contains("FINAL:Job two done.") }
            settle()
            assertEquals(listOf("ACK:Sending to work.", "FINAL:Queued two jobs.", "FINAL:Job one done.", "FINAL:Job two done."),
                watch.played.toList())
        }
    }

    @Test
    fun `another session's output, or one after the follow window, is never spoken`() {
        harness(windowMs = 1_500).use { h ->
            val work = existing(h, "work", "ok")
            val home = existing(h, "home", "ok")
            routeTo(h, "work")
            val watch = Watch(h)
            watchTurn(h, watch, "w-turn-0005")
            h.fake.pushLaterTurn(home, completion("Not yours."))
            settle()
            runBlocking { delay(1_600) }
            h.fake.pushLaterTurn(work, completion("Too late."))
            settle()
            assertEquals(listOf("ACK:Sending to work.", "FINAL:ok"), watch.played.toList())
        }
    }

    @Test
    fun `the app's own next submit to that conversation ends the follow, so its reply is not spoken twice`() {
        harness().use { h ->
            val work = existing(h, "work", "First.")
            routeTo(h, "work")
            val watch = Watch(h)
            watchTurn(h, watch, "w-turn-0006")
            h.fake.scripts[work] = { listOf(FakeHermesDashboard.complete("Second.")) }
            watchTurn(h, watch, "w-turn-0007")
            // A text message to the same conversation: its reply is shown, never spoken.
            h.fake.scripts[work] = { listOf(FakeHermesDashboard.complete("Typed reply.")) }
            runBlocking { h.core.chat.send(work, "typed") }
            settle()
            assertEquals(listOf("ACK:Sending to work.", "FINAL:First.", "ACK:Sending to work.", "FINAL:Second."), watch.played.toList())
            h.fake.pushLaterTurn(work, completion("Background result."))
            settle()
            assertFalse("after a text message the voice follow is over", watch.played.contains("FINAL:Background result."))
        }
    }

    // ── where it plays ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a later reply plays on whichever device sent the latest accepted request when it is handed off`() {
        harness().use { h ->
            val work = existing(h, "work", "Started.")
            val home = existing(h, "home", "Sure.")
            routeTo(h, "work")
            val watch = Watch(h)
            watchTurn(h, watch, "w-turn-0008")
            val phone: MutableList<String> = Collections.synchronizedList(mutableListOf())
            routeTo(h, "home")
            phoneTurn(h, "p-turn-0002", phone)
            assertEquals(VoiceOrigin.PHONE, h.core.orchestrator.playbackRoute.device.value)
            h.fake.pushLaterTurn(work, completion("Work report done."))
            waitFor("the later reply on the Phone") { phone.contains("FINAL:Work report done.") }
            assertFalse(watch.played.contains("FINAL:Work report done."))
            assertTrue(home.isNotEmpty())
        }
    }

    @Test
    fun `an unreachable Watch is reported as not played and nothing falls back to the Phone`() {
        harness().use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val watch = Watch(h)
            watchTurn(h, watch, "w-turn-0009")
            watch.reachable = false // the Watch went out of range after its turn
            h.fake.pushLaterTurn(work, completion("Finished."))
            waitFor("a not-played report") { later.any { it.startsWith("w-turn-0009:not_played") } }
            assertTrue("never a Phone fallback", later.none { it.contains(":played:") })
            assertFalse(watch.played.contains("FINAL:Finished."))
        }
    }

    @Test
    fun `first and middle switches off never suppress a later reply`() {
        harness().use { h ->
            h.settings.playFirstResponse = false
            h.settings.playMiddleResponses = false
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val watch = Watch(h)
            watchTurn(h, watch, "w-turn-0011")
            h.fake.pushLaterTurn(work, FakeHermesDashboard.interim("thinking"), completion("Final result."))
            waitFor("the later final") { watch.played.contains("FINAL:Final result.") }
            assertFalse("interim text of a later turn is not spoken", watch.played.any { it.contains("thinking") })
        }
    }

    // ── sharing the speaker with newer requests ─────────────────────────────────────────────

    @Test
    fun `a later reply waits while a newer request is speaking, then plays`() {
        harness().use { h ->
            val work = existing(h, "work", "Started.")
            val home = existing(h, "home", "A long answer.")
            routeTo(h, "work")
            val watch = Watch(h)
            watchTurn(h, watch, "w-turn-0012")
            routeTo(h, "home")
            val busy = CompletableDeferred<Unit>().also { watch.holds["A long answer."] = it }
            val newer = scope.async { watchTurn(h, watch, "w-turn-0013") }
            waitFor("the newer reply playing") { watch.played.contains("FINAL:A long answer.") }
            h.fake.pushLaterTurn(work, completion("Work done."))
            settle()
            assertFalse("not over the newer reply", watch.played.contains("FINAL:Work done."))
            busy.complete(Unit)
            runBlocking { newer.await() }
            waitFor("the later reply after it") { watch.played.contains("FINAL:Work done.") }
            assertEquals("FINAL:Work done.", watch.played.last())
            assertTrue(home.isNotEmpty())
        }
    }

    @Test
    fun `a newer request takes the speaker from a later reply, which is reported as not played`() {
        harness().use { h ->
            val work = existing(h, "work", "Started.")
            existing(h, "home", "Home answer.")
            routeTo(h, "work")
            val watch = Watch(h)
            watchTurn(h, watch, "w-turn-0014")
            watch.holds["A very long report."] = CompletableDeferred()
            h.fake.pushLaterTurn(work, completion("A very long report."))
            waitFor("the later reply playing") { watch.played.contains("FINAL:A very long report.") }
            routeTo(h, "home")
            watchTurn(h, watch, "w-turn-0015") as VoiceTurnOutcome.Completed
            waitFor("the stop report") { later.any { it.startsWith("w-turn-0014:not_played") } }
            assertEquals(listOf("ACK:Sending to home.", "FINAL:Home answer."), watch.played.takeLast(2))
        }
    }
}
