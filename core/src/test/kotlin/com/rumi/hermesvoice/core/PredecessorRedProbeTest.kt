package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.TurnRouting
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour probes written only against API that exists in BOTH the history-R3 predecessor and the successor, so the same file runs on
 * the predecessor (expected: assertion failures) and on the successor (expected: passes). Each asserts an observable of the new behaviour.
 */
class PredecessorRedProbeTest {
    private fun CoreHarness.work(reply: String): String {
        val work = runBlocking { core.sessions.createConversation("Work", "work", "") }.storedSessionId
        fake.scripts[work] = { listOf(FakeHermesDashboard.complete(reply)) }
        return work
    }

    private fun phone(h: CoreHarness, id: String, work: String) = runBlocking {
        h.core.orchestrator.run(VoiceTurnRequest(id, VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav", PlaybackSink { _, _ -> }, routing = TurnRouting.Direct(work)))
    }

    @Test
    fun `the destination receives the voice marker and the original words only`() {
        CoreHarness().use { h ->
            val work = h.work("Done.")
            assertTrue(phone(h, "probe-voice", work) is VoiceTurnOutcome.Completed)
            val submitted = h.fake.rawPrompts.filter { it.first == work }.map { it.second }
            assertEquals(1, submitted.size)
            assertEquals(VOICE_MARK + h.fake.transcript, submitted.single())
            val users = runBlocking { h.core.sessions.history(work) }.messages.filter { it.role == "user" }
            assertEquals(1, users.size)
            assertEquals("the stored row keeps the visible marker", VOICE_MARK + h.fake.transcript, users.single().text)
        }
    }

    @Test
    fun `a long reply is spoken as several ordered pieces with no text lost`() {
        CoreHarness().use { h ->
            val reply = (1..80).joinToString(" ") { "Sentence number $it is here to be spoken in order." }
            val work = h.work(reply)
            assertTrue(phone(h, "probe-long", work) is VoiceTurnOutcome.Completed)
            val spoken = h.fake.timeline.filter { it.startsWith("speak:Sentence") }.map { it.removePrefix("speak:") }
            assertTrue("spoken in more than one piece, was ${spoken.size}", spoken.size >= 2)
            // The dashboard client trims each request's text, so the single spaces between pieces are the only difference.
            assertEquals(reply, spoken.joinToString(" "))
        }
    }
}
