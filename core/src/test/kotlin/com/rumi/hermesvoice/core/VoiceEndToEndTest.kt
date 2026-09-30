package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production wiring (dashboard REST + `/api/ws` gateway + app session repository + voice
 * orchestrator) over real sockets to [FakeHermesDashboard]. Not an authenticated Hermes E2E.
 */
class VoiceEndToEndTest {
    private val h = CoreHarness()
    private val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private lateinit var workId: String

    init {
        h.fake.sourceScripts[AppSources.ROUTER] = {
            listOf(FakeHermesDashboard.delta("{\"dest"),
                FakeHermesDashboard.complete("""{"destination":"work","ack":"Sending that to work.","session_id":"desktop-chat"}"""))
        }
        runBlocking {
            workId = h.core.sessions.createConversation("Work", "work", "Calendar").storedSessionId
            h.core.sessions.createConversation("Home", "home", "")
        }
        h.fake.scripts[workId] = {
            listOf(FakeHermesDashboard.delta("Check"), FakeHermesDashboard.interim("Checking your calendar."),
                FakeHermesDashboard.interim("Found a free slot at 3."), FakeHermesDashboard.delta("Moved"),
                FakeHermesDashboard.complete("Moved it to 3pm."))
        }
    }

    @After fun tearDown() = h.close()

    private fun run(turnId: String, origin: VoiceOrigin) = runBlocking {
        h.core.orchestrator.run(VoiceTurnRequest(turnId, origin, TestAudio.speechWav(), "audio/wav", PlaybackSink { audio, cue ->
            played += "${origin.name.lowercase()}:${cue.role}:${cue.sequence}:${h.fake.decodeSpoken(audio)}"
        }))
    }

    @Test
    fun `defaults speak ack then final only and deliver the original transcript`() {
        val outcome = run("w1", VoiceOrigin.WATCH) as VoiceTurnOutcome.Completed
        assertEquals(listOf("watch:ACK:0:Sending that to work.", "watch:FINAL:1:Moved it to 3pm."), played.toList())
        val router = h.registry.router()!!.storedSessionId
        assertEquals(listOf(router, workId), h.fake.prompts.map { it.first })
        assertTrue(h.fake.prompts[0].second.contains("- work: Calendar"))
        assertTrue(h.fake.prompts[0].second.contains("- home: Home"))
        assertEquals("move my 2pm meeting to 3", h.fake.prompts[1].second)
        assertEquals("move my 2pm meeting to 3", outcome.route.originalTranscript)
        val timeline = h.fake.timeline.toList()
        assertTrue(timeline.indexOf("speak:Sending that to work.") < timeline.indexOf("submit:$workId"))
        // The model's extra "session_id" key is ignored: only the allowlisted alias maps to a session.
        assertTrue(h.fake.prompts.none { it.first == "desktop-chat" })
    }

    @Test
    fun `both switches speak each distinct response once, same policy for phone origin`() {
        h.settings.playFirstResponse = true
        h.settings.playMiddleResponses = true
        run("p1", VoiceOrigin.PHONE)
        assertEquals(listOf("phone:ACK:0:Sending that to work.", "phone:FIRST:1:Checking your calendar.",
            "phone:MIDDLE:2:Found a free slot at 3.", "phone:FINAL:3:Moved it to 3pm."), played.toList())
    }

    @Test
    fun `middle only skips the first response`() {
        h.settings.playMiddleResponses = true
        run("p2", VoiceOrigin.PHONE)
        assertEquals(listOf("phone:ACK:0:Sending that to work.", "phone:MIDDLE:1:Found a free slot at 3.",
            "phone:FINAL:2:Moved it to 3pm."), played.toList())
    }

    @Test
    fun `archived destination is not routable and the turn fails closed`() {
        runBlocking { h.core.sessions.setArchived(workId, true) }
        val outcome = run("w2", VoiceOrigin.WATCH) as VoiceTurnOutcome.RoutingRejected
        assertEquals("routing_destination_not_allowlisted", outcome.reason)
        assertTrue(played.isEmpty())
        assertTrue(h.fake.prompts.none { it.first == workId })
    }

    @Test
    fun `no conversations means no router is created and nothing is transcribed`() {
        val fresh = CoreHarness()
        try {
            val outcome = runBlocking {
                fresh.core.orchestrator.run(VoiceTurnRequest("x1", VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav", PlaybackSink { _, _ -> }))
            } as VoiceTurnOutcome.NotDelivered
            assertTrue(outcome.reason, outcome.reason.startsWith("config_invalid"))
            assertEquals(0, fresh.fake.server.requestCount)
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `expired access token is refreshed transparently mid-turn`() {
        h.fake.rejectAccessTokens = setOf("AT-1")
        run("w3", VoiceOrigin.WATCH) as VoiceTurnOutcome.Completed
        assertEquals(1, h.fake.refreshes)
        assertEquals("AT-2", h.tokens.load()!!.accessToken)
    }

    @Test
    fun `signed-out phone fails closed as auth required`() {
        h.tokens.clear()
        val before = h.fake.server.requestCount
        val outcome = run("w4", VoiceOrigin.WATCH) as VoiceTurnOutcome.NotDelivered
        assertTrue(outcome.authRequired)
        assertEquals(before, h.fake.server.requestCount)
    }
}
