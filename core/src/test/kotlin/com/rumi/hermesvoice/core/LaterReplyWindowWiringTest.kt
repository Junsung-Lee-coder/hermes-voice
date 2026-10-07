package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.TurnRouting
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
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
 * The production wiring (HermesVoiceCore.connect with no fixed window): the Phone's stored minutes decide how long a delivered
 * turn is followed, with the real gateway connection against [FakeHermesDashboard] (a contract double, not a live Hermes).
 */
class LaterReplyWindowWiringTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ended: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val endedAt: MutableMap<String, Long> = Collections.synchronizedMap(mutableMapOf())
    private val listener = object : VoiceTurnListener {
        override fun onLaterFollowEnded(turnId: String, reason: String) {
            ended += "$turnId:$reason"
            endedAt[turnId] = System.nanoTime()
        }
    }

    @After
    fun stop() = scope.cancel()

    private fun harness() = CoreHarness(laterScope = scope, laterWindowMs = null, voiceListener = listener).also { it.laterConsent.enabled = true }

    private fun conversation(h: CoreHarness, alias: String, reply: String): String {
        val owned = runBlocking { h.core.sessions.createConversation(alias.replaceFirstChar { it.uppercase() }, alias, "$alias things") }
        h.fake.scripts[owned.storedSessionId] = { listOf(FakeHermesDashboard.complete(reply)) }
        return owned.storedSessionId
    }

    private fun speak(h: CoreHarness, turnId: String, session: String, played: MutableList<String>) = runBlocking {
        h.core.orchestrator.run(VoiceTurnRequest(turnId, VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav",
            PlaybackSink { audio, cue -> played += "${cue.role}:${h.fake.decodeSpoken(audio)}" }, routing = TurnRouting.Direct(session)))
    }

    private fun waitFor(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(timeoutMs) { while (!condition()) delay(10) }
        } catch (_: Exception) {
            throw AssertionError("timed out waiting for $what")
        }
    }

    @Test
    fun `a three-day setting keeps following and speaks a reply that arrives later`() {
        harness().use { h ->
            val work = conversation(h, "work", "Started.")
            h.settings.laterReplyWindowMinutes = 4320
            val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
            assertTrue(speak(h, "wire-turn-0001", work, played) is VoiceTurnOutcome.Completed)
            runBlocking { delay(1_500) }
            assertTrue("nothing ended the follow", ended.isEmpty())
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Long task done."))
            waitFor("the later reply") { played.contains("FINAL:Long task done.") }
            assertEquals(1, played.count { it == "FINAL:Long task done." })
        }
    }

    @Test
    fun `the opt-in stays off and separate - a long window alone follows nothing`() {
        CoreHarness(laterScope = scope, laterWindowMs = null, voiceListener = listener).use { h ->
            assertFalse(h.laterConsent.enabled)
            h.settings.laterReplyWindowMinutes = 4320
            val work = conversation(h, "work", "Started.")
            val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
            speak(h, "wire-turn-0002", work, played)
            runBlocking { delay(300) }
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Nobody asked."))
            runBlocking { delay(600) }
            assertFalse(played.contains("FINAL:Nobody asked."))
            assertFalse(h.laterConsent.enabled)
        }
    }

    /** About one minute of real time: the shortest window is the only one that can end inside a unit test. */
    @Test
    fun `a one-minute setting ends that follow after about a minute while a follow started under 3 days is untouched`() {
        harness().use { h ->
            val work = conversation(h, "work", "A started.")
            val home = conversation(h, "home", "B started.")
            val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
            h.settings.laterReplyWindowMinutes = 1
            val started = System.nanoTime()
            speak(h, "wire-turn-A-01", work, played)
            h.settings.laterReplyWindowMinutes = 4320
            speak(h, "wire-turn-B-01", home, played)
            waitFor("A's window", timeoutMs = 90_000) { ended.contains("wire-turn-A-01:window") }
            val elapsedMs = (endedAt.getValue("wire-turn-A-01") - started) / 1_000_000
            assertTrue("ended after about a minute, not at the old 30 or at once: $elapsedMs ms", elapsedMs in 55_000..85_000)
            assertFalse("B was started under 3 days and keeps following", ended.any { it.startsWith("wire-turn-B-01") })
            h.fake.pushLaterTurn(home, FakeHermesDashboard.complete("B later."))
            waitFor("B's later reply") { played.contains("FINAL:B later.") }
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("A too late."))
            runBlocking { delay(500) }
            assertFalse(played.contains("FINAL:A too late."))
        }
    }
}
