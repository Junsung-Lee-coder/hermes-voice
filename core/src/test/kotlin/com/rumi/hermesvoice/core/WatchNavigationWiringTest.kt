package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.watchlink.LinkFrame
import com.rumi.hermesvoice.core.watchlink.PlayRequest
import com.rumi.hermesvoice.core.watchlink.PlayedAck
import com.rumi.hermesvoice.core.watchlink.TurnStateMessage
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchNavigation
import com.rumi.hermesvoice.core.watchlink.WatchTransport
import com.rumi.hermesvoice.core.watchlink.WatchTurnUpload
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The production wiring (HermesVoiceCore.connect, real gateway against the contract double): the Phone's settings gate the move, a created conversation is verified before it is named. */
class WatchNavigationWiringTest {
    private class Watch(private val h: CoreHarness) : WatchTransport {
        override val nodeId = "watch-node-1"
        val navigations: MutableList<WatchNavigation> = Collections.synchronizedList(mutableListOf())
        val timeline: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun sendMessage(path: String, bytes: ByteArray) {
            when (path) {
                WatchLinkPaths.NAVIGATE -> { WatchNavigation.decode(bytes)?.let { navigations += it }; timeline += "navigate" }
                WatchLinkPaths.STATE -> TurnStateMessage.decode(bytes)?.let { timeline += "state:${it.stage}" }
            }
        }

        override suspend fun sendChannel(path: String, bytes: ByteArray) {
            val play = PlayRequest.fromFrame(LinkFrame.decode(bytes))
            h.core.watchAcks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, true).encode())
        }
    }

    private fun turn(h: CoreHarness, watch: Watch, turnId: String, generation: Long? = 2) = runBlocking {
        val frame = WatchTurnUpload(turnId, TurnTrigger.PUSH_TO_TALK, WatchTurnUpload.MIME_WAV, TestAudio.speechWav(), selectionGeneration = generation)
            .toFrame().encode()
        h.core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath(turnId), frame, watch)
    }

    private fun enable(h: CoreHarness, on: Boolean) {
        h.settings.saveWatchSettings(h.settings.watchSettings().copy(watchAutoNavigateToRouted = on))
    }

    @Test
    fun `an existing destination is named only with the Watch option on, and the router alone is not a delivery`() {
        CoreHarness().use { h ->
            val owned = runBlocking { h.core.sessions.createConversation("Work", "work", "work things") }
            h.fake.scripts[owned.storedSessionId] = { listOf(FakeHermesDashboard.complete("Done.")) }
            val route = { listOf(FakeHermesDashboard.complete("""{"action":"route","destination":"work","ack":"Sending to work."}""")) }
            h.fake.sourceScripts[AppSources.ROUTER] = { route() }
            h.registry.router()?.let { h.fake.scripts[it.storedSessionId] = { route() } }
            val watch = Watch(h)
            assertTrue(turn(h, watch, "wire-nav-0001") is VoiceTurnOutcome.Completed)
            assertTrue("off by default", watch.navigations.isEmpty())
            enable(h, true)
            assertTrue(turn(h, watch, "wire-nav-0002") is VoiceTurnOutcome.Completed)
            assertEquals(listOf(WatchNavigation("wire-nav-0002", owned.storedSessionId, 2, false)), watch.navigations.toList())
            assertTrue(watch.timeline.indexOf("navigate") < watch.timeline.lastIndexOf("state:done"))
            h.settings.routingEnabled = false
            turn(h, watch, "wire-nav-0003")
            assertEquals("routing off: nothing more", 1, watch.navigations.size)
        }
    }

    @Test
    fun `a newly created conversation is named after it was created and verified`() {
        CoreHarness().use { h ->
            enable(h, true)
            h.fake.sourceScripts[AppSources.ROUTER] = {
                listOf(FakeHermesDashboard.complete("""{"action":"create","title":"Garden plans","alias":"garden","description":"Plants"}"""))
            }
            h.fake.sourceScripts[AppSources.CONVERSATION] = { listOf(FakeHermesDashboard.complete("On it.")) }
            val watch = Watch(h)
            val outcome = turn(h, watch, "wire-new-0001", generation = 5) as VoiceTurnOutcome.Completed
            assertTrue(outcome.route.created)
            val made = outcome.route.destination.storedSessionId
            assertEquals(listOf(WatchNavigation("wire-new-0001", made, 5, created = true)), watch.navigations.toList())
            assertTrue("the Phone owns it", h.registry.find(made) != null)
        }
    }
}
