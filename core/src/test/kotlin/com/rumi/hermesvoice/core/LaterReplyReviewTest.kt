package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import com.rumi.hermesvoice.core.watchlink.LinkFrame
import com.rumi.hermesvoice.core.watchlink.PlayRequest
import com.rumi.hermesvoice.core.watchlink.PlayedAck
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchTransport
import com.rumi.hermesvoice.core.watchlink.WatchTurnUpload
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Independent review of b30 (B30-F1, B30-F2): later replies are an informed opt-in (off by
 * default), and they never play over a recording: a Watch that records refuses them ("busy"), and
 * the Phone defers and delivers them afterwards on the latest sender, once. (Its b30 RED run set the
 * option through the then backed-up settings key; since b32 the consent is device-local, B31-N6.)
 */
class LaterReplyReviewTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val later: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val listener = object : VoiceTurnListener {
        override fun onLaterReply(turnId: String, played: Boolean, detail: String) {
            later += "$turnId:${if (played) "played" else "not_played"}:$detail"
        }
    }

    @After
    fun stop() = scope.cancel()

    private fun harness(speakLater: Boolean) = CoreHarness(laterScope = scope, laterWindowMs = 60_000L, voiceListener = listener).also {
        if (speakLater) it.laterConsent.enabled = true
    }

    /** A paired Watch: confirms what it plays; while [recording] it refuses later replies as busy, like WatchApp. */
    private inner class Watch(private val h: CoreHarness) : WatchTransport {
        override val nodeId = "watch-node-1"
        @Volatile var recording = false
        val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val refused: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun sendMessage(path: String, bytes: ByteArray) {}

        override suspend fun sendChannel(path: String, bytes: ByteArray) {
            val play = PlayRequest.fromFrame(LinkFrame.decode(bytes))
            val text = String(play.audio).removePrefix("AUDIO:")
            if (recording) {
                refused += text
                h.core.watchAcks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, false, BUSY).encode())
                return
            }
            played += "${play.role}:$text"
            h.core.watchAcks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, true).encode())
        }
    }

    private fun watchTurn(h: CoreHarness, watch: Watch, turnId: String) = runBlocking {
        val frame = WatchTurnUpload(turnId, TurnTrigger.PUSH_TO_TALK, WatchTurnUpload.MIME_WAV, TestAudio.speechWav()).toFrame().encode()
        h.core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath(turnId), frame, watch)
    }

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
            withTimeout(15_000) { while (!condition()) delay(10) }
        } catch (_: Exception) {
            throw AssertionError("timed out waiting for $what (later=$later)")
        }
    }

    private fun settle(ms: Long = 600) = runBlocking { delay(ms) }

    @Test
    fun `later replies are off by default - nothing is followed, synthesized or played`() {
        CoreHarness(laterScope = scope, laterWindowMs = 60_000L, voiceListener = listener).use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val watch = Watch(h)
            watchTurn(h, watch, "r-turn-0001") as VoiceTurnOutcome.Completed
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Unrelated later text."))
            settle()
            assertEquals(listOf("ACK:Sending to work.", "FINAL:Started."), watch.played.toList())
            assertFalse("never synthesized", h.fake.timeline.any { it.contains("Unrelated later text.") && it.startsWith("speak") })
            assertTrue(later.isEmpty())
        }
    }

    @Test
    fun `a recording Watch refuses a later reply as busy and gets it after the recording, once`() {
        harness(speakLater = true).use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val watch = Watch(h)
            watchTurn(h, watch, "r-turn-0002")
            watch.recording = true
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Report done."))
            waitFor("a busy refusal") { watch.refused.isNotEmpty() }
            settle()
            assertFalse("not played while recording", watch.played.contains("FINAL:Report done."))
            assertTrue("a refusal is not a played or failed outcome", later.none { it.startsWith("r-turn-0002:") })
            watch.recording = false
            waitFor("delivered after the recording") { watch.played.contains("FINAL:Report done.") }
            settle()
            assertEquals(1, watch.played.count { it == "FINAL:Report done." })
            assertEquals(listOf("r-turn-0002:played:watch"), later.toList())
        }
    }

    @Test
    fun `a reply deferred by a recording plays on the latest sender at its handoff`() {
        harness(speakLater = true).use { h ->
            val work = existing(h, "work", "Started.")
            existing(h, "home", "Sure.")
            routeTo(h, "work")
            val watch = Watch(h)
            watchTurn(h, watch, "r-turn-0003")
            watch.recording = true
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Work report done."))
            waitFor("a busy refusal") { watch.refused.isNotEmpty() }
            // The Watch's recording becomes a request... from the Phone instead: the Phone is the latest sender now.
            val phone: MutableList<String> = Collections.synchronizedList(mutableListOf())
            routeTo(h, "home")
            runBlocking {
                h.core.orchestrator.run(VoiceTurnRequest("r-turn-0004", VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav",
                    PlaybackSink { audio, cue -> phone += "${cue.role}:${h.fake.decodeSpoken(audio)}" }))
            }
            waitFor("the deferred reply on the Phone") { phone.contains("FINAL:Work report done.") }
            settle()
            assertFalse(watch.played.contains("FINAL:Work report done."))
            assertEquals(1, phone.count { it == "FINAL:Work report done." })
        }
    }

    @Test
    fun `switching later replies off ends a follow - a reply arriving afterwards is not spoken`() {
        harness(speakLater = true).use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val watch = Watch(h)
            watchTurn(h, watch, "r-turn-0005")
            h.laterConsent.enabled = false
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("After switching off."))
            settle()
            assertFalse(watch.played.contains("FINAL:After switching off."))
            assertFalse("never synthesized", h.fake.timeline.any { it.contains("After switching off.") && it.startsWith("speak") })
        }
    }

    private companion object {
        /** PlayedAck.BUSY_RECORDING (a literal, as in its b30 RED run). */
        const val BUSY = "busy_recording"
    }
}
