package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import com.rumi.hermesvoice.core.net.HermesConversationPort
import com.rumi.hermesvoice.core.net.HermesSpeechGateway
import com.rumi.hermesvoice.core.net.OutgoingAttachment
import com.rumi.hermesvoice.core.net.SubmittedTurn
import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.voice.DestinationAllowlist
import com.rumi.hermesvoice.core.voice.DestinationEntry
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.RecipientEvent
import com.rumi.hermesvoice.core.voice.TurnRouting
import com.rumi.hermesvoice.core.voice.VoiceTurnConfig
import com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import com.rumi.hermesvoice.core.watchlink.LinkFrame
import com.rumi.hermesvoice.core.watchlink.PlayRequest
import com.rumi.hermesvoice.core.watchlink.PlayedAck
import com.rumi.hermesvoice.core.watchlink.TurnStateMessage
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchAckRegistry
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchNavigation
import com.rumi.hermesvoice.core.watchlink.WatchTransport
import com.rumi.hermesvoice.core.watchlink.WatchTurnIntake
import com.rumi.hermesvoice.core.watchlink.WatchTurnUpload
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Phone side of "open the routed conversation on Watch": through the real [WatchTurnIntake] and orchestrator, which message the
 * Watch gets and when. Only a request the Watch itself sent, that the destination accepted, while the Phone's routing and the Watch
 * option are both on, tells the Watch anything; the router's decision, the acknowledgement or a failed send do not.
 */
class WatchNavigationIntakeTest {
    private val timeline: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var routingReply = """{"action":"route","destination":"work","ack":"Sending to work."}"""
    private var failDelivery = false
    private var transcriptGate: CompletableDeferred<Unit>? = null
    private val entries = listOf(DestinationEntry("work", "work_session"), DestinationEntry("home", "home_session"))

    private val speech = object : HermesSpeechGateway {
        override suspend fun transcribe(audio: ByteArray, mimeType: String): String {
            transcriptGate?.await()
            return "move my meeting"
        }

        override suspend fun speak(text: String) = SpokenAudio(text.toByteArray(), "audio/mpeg")
    }

    private class Turn(private val events: List<RecipientEvent>) : SubmittedTurn {
        override val submitStatus = "streaming"
        override val attributable = true
        override suspend fun collect(timeoutMs: Long, onEvent: suspend (RecipientEvent) -> Unit) {
            for (event in events) onEvent(event)
        }

        override fun release() {}
    }

    private val conversations = object : HermesConversationPort {
        override suspend fun create(source: String, title: String, seedInstruction: String, hidden: Boolean) = throw UnsupportedOperationException()
        override suspend fun submit(storedSessionId: String, text: String, attachments: List<OutgoingAttachment>): SubmittedTurn {
            if (storedSessionId == "router_session") return Turn(listOf(RecipientEvent.Complete(routingReply, "complete")))
            timeline += "submit:$storedSessionId"
            if (failDelivery) throw java.io.IOException("the destination refused")
            return Turn(listOf(RecipientEvent.Complete("final", "complete")))
        }
    }

    private val settings = AppSettings(InMemoryKeyValueStore())
    private val acks = WatchAckRegistry()

    private fun orchestrator() = VoiceTurnOrchestrator(speech, conversations,
        config = { routing ->
            if (routing is TurnRouting.Direct) VoiceTurnConfig(null, DestinationAllowlist.create(entries, null), ResponsePlaybackSettings())
            else VoiceTurnConfig("router_session", DestinationAllowlist.create(entries, "router_session"), ResponsePlaybackSettings())
        }, inputGate = { _, _ -> AudioInputVerdict.USABLE })

    private val orchestrator = orchestrator()

    private fun intake() = WatchTurnIntake(orchestrator, acks,
        navigation = { settings.watchAutoNavigationApplies },
        routing = { upload -> TurnRouting.of(settings.routingEnabled, upload.target) })

    private inner class Transport : WatchTransport {
        override val nodeId = "watch-node"
        val navigations: MutableList<WatchNavigation> = Collections.synchronizedList(mutableListOf())
        override suspend fun sendMessage(path: String, bytes: ByteArray) {
            when (path) {
                WatchLinkPaths.NAVIGATE -> {
                    timeline += "navigate"
                    navigations += requireNotNull(WatchNavigation.decode(bytes)) { "the Phone sent an undecodable navigation" }
                }
                WatchLinkPaths.STATE -> TurnStateMessage.decode(bytes)?.let { timeline += "state:${it.stage}" }
            }
        }

        override suspend fun sendChannel(path: String, bytes: ByteArray) {
            val play = PlayRequest.fromFrame(LinkFrame.decode(bytes))
            timeline += "play:${play.role}"
            acks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, true).encode())
        }
    }

    private fun optionOn(on: Boolean = true) {
        settings.saveWatchSettings(settings.watchSettings().copy(watchAutoNavigateToRouted = on))
    }

    private fun upload(turnId: String, target: String? = null, generation: Long? = 0) = WatchTurnUpload(turnId, TurnTrigger.PUSH_TO_TALK,
        WatchTurnUpload.MIME_WAV, ByteArray(200) { 1 }, target = target, selectionGeneration = generation)

    private suspend fun sendNow(turnId: String, transport: Transport, target: String? = null, generation: Long? = 0): VoiceTurnOutcome? =
        intake().onTurnChannel(WatchLinkPaths.turnPath(turnId), upload(turnId, target, generation).toFrame().encode(), transport)

    private fun send(turnId: String, transport: Transport, target: String? = null, generation: Long? = 0): VoiceTurnOutcome? =
        runBlocking { sendNow(turnId, transport, target, generation) }

    @Test
    fun `a delivered routed Watch request tells the Watch its destination and generation once, before the turn is done`() {
        optionOn()
        val transport = Transport()
        val outcome = send("watch-nav-001", transport, generation = 4) as VoiceTurnOutcome.Completed
        assertFalse(outcome.route.direct)
        assertEquals(listOf(WatchNavigation("watch-nav-001", "work_session", 4, created = false)), transport.navigations.toList())
        assertTrue("after the destination accepted the transcript", timeline.indexOf("navigate") > timeline.indexOf("submit:work_session"))
        assertTrue("before the terminal state", timeline.indexOf("navigate") < timeline.indexOf("state:done"))
        assertEquals(1, timeline.count { it == "navigate" })
    }

    @Test
    fun `the Watch option is off by default and an off option never navigates`() {
        assertFalse(settings.watchAutoNavigateToRouted)
        val transport = Transport()
        send("watch-off-001", transport)
        assertTrue(transport.navigations.isEmpty())
        optionOn()
        optionOn(false)
        send("watch-off-002", transport)
        assertTrue(transport.navigations.isEmpty())
    }

    @Test
    fun `routing off never navigates even with the option on, and the option is kept`() {
        optionOn()
        settings.routingEnabled = false
        val transport = Transport()
        val outcome = send("watch-direct-01", transport, target = "home_session") as VoiceTurnOutcome.Completed
        assertTrue(outcome.route.direct)
        assertTrue(transport.navigations.isEmpty())
        assertTrue("the choice is retained while routing is off", settings.watchAutoNavigateToRouted)
        assertFalse(settings.watchAutoNavigationApplies)
        settings.routingEnabled = true
        assertTrue(settings.watchAutoNavigationApplies)
        send("watch-direct-02", transport)
        assertEquals(listOf("watch-direct-02"), transport.navigations.map { it.turnId })
    }

    @Test
    fun `a request without a selection generation, from an older Watch, is not told anything`() {
        optionOn()
        val transport = Transport()
        assertTrue(send("watch-old-0001", transport, generation = null) is VoiceTurnOutcome.Completed)
        assertTrue(transport.navigations.isEmpty())
    }

    @Test
    fun `the router's decision, an acknowledgement or a refused delivery never moves the Watch`() {
        optionOn()
        val transport = Transport()
        failDelivery = true
        val failed = send("watch-fail-001", transport)
        assertTrue("the ack was spoken first: $failed", timeline.contains("play:ACK"))
        assertTrue(failed is VoiceTurnOutcome.NotDelivered)
        assertTrue(transport.navigations.isEmpty())
        failDelivery = false
        routingReply = """{"action":"route","destination":"nowhere","ack":"x"}"""
        val rejected = send("watch-fail-002", transport)
        assertFalse(rejected is VoiceTurnOutcome.Completed)
        assertTrue(transport.navigations.isEmpty())
        routingReply = """{"action":"route","destination":"work","ack":"Sending to work."}"""
        assertTrue(send("watch-fail-003", transport) is VoiceTurnOutcome.Completed)
        assertEquals(listOf("watch-fail-003"), transport.navigations.map { it.turnId })
    }

    @Test
    fun `the choice is frozen when the request is accepted and must still hold at delivery`() = runBlocking {
        val transport = Transport()
        // Off when accepted, switched on while it runs: no move.
        var gate = CompletableDeferred<Unit>().also { transcriptGate = it }
        var running = async(Dispatchers.Default) { sendNow("watch-frz-0001", transport) }
        withTimeout(5_000) { while (!timeline.contains("state:accepted")) delay(5) }
        optionOn()
        gate.complete(Unit)
        running.await()
        assertTrue(transport.navigations.isEmpty())
        // On when accepted, switched off before delivery: no move.
        timeline.clear()
        gate = CompletableDeferred<Unit>().also { transcriptGate = it }
        running = async(Dispatchers.Default) { sendNow("watch-frz-0002", transport) }
        withTimeout(5_000) { while (!timeline.contains("state:accepted")) delay(5) }
        optionOn(false)
        gate.complete(Unit)
        running.await()
        assertTrue(transport.navigations.isEmpty())
        transcriptGate = null
    }

    @Test
    fun `a Phone-origin routed request never moves the Watch`() {
        optionOn()
        val transport = Transport()
        val outcome = runBlocking {
            orchestrator.run(VoiceTurnRequest("phone-nav-0001", VoiceOrigin.PHONE, byteArrayOf(1, 2, 3), "audio/wav", PlaybackSink { _, _ -> },
                routing = TurnRouting.Model))
        }
        assertTrue(outcome is VoiceTurnOutcome.Completed)
        assertTrue(transport.navigations.isEmpty())
        assertFalse(timeline.contains("navigate"))
    }

    @Test
    fun `the generation and the turn id on the wire round trip, and anything malformed is refused`() {
        val navigation = WatchNavigation("watch-wire-001", "work_session", 7, created = true)
        assertEquals(navigation, WatchNavigation.decode(navigation.encode()))
        assertEquals(WatchNavigation("watch-wire-001", "work_session", 0, false),
            WatchNavigation.decode("""{"v":1,"turn_id":"watch-wire-001","session_id":"work_session","gen":0}""".toByteArray()))
        val bad = listOf(
            """{"v":2,"turn_id":"watch-wire-001","session_id":"work_session","gen":0}""",
            """{"turn_id":"watch-wire-001","session_id":"work_session","gen":0}""",
            """{"v":1,"turn_id":"short","session_id":"work_session","gen":0}""",
            """{"v":1,"turn_id":"watch-wire-001","session_id":"../etc","gen":0}""",
            """{"v":1,"turn_id":"watch-wire-001","session_id":"","gen":0}""",
            """{"v":1,"turn_id":"watch-wire-001","session_id":"work_session","gen":-1}""",
            """{"v":1,"turn_id":"watch-wire-001","session_id":"work_session","gen":"1"}""",
            """{"v":1,"turn_id":"watch-wire-001","session_id":"work_session","gen":1.5}""",
            """{"v":1,"turn_id":"watch-wire-001","session_id":"work_session"}""",
            """{"v":1,"turn_id":"watch-wire-001","session_id":"work_session","gen":0,"created":"yes"}""",
            """{"v":1,"turn_id":7,"session_id":"work_session","gen":0}""",
            "not json", "", "[]",
        )
        for (raw in bad) assertNull(raw, WatchNavigation.decode(raw.toByteArray()))
        assertNotNull(WatchNavigation.decode("""{"v":1,"turn_id":"watch-wire-001","session_id":"work_session","gen":0,"extra":1}""".toByteArray()))
    }

    @Test
    fun `an upload's selection generation round trips and an older frame without one still parses`() {
        val path = WatchLinkPaths.turnPath("watch-gen-0001")
        assertEquals(9L, WatchTurnUpload.fromFrame(path, LinkFrame.decode(upload("watch-gen-0001", generation = 9).toFrame().encode())).selectionGeneration)
        assertNull(WatchTurnUpload.fromFrame(path, LinkFrame.decode(upload("watch-gen-0001", generation = null).toFrame().encode())).selectionGeneration)
        for (bad in listOf<Any>(-1, "3", 1.5, true)) {
            val frame = upload("watch-gen-0001", generation = 1).toFrame()
            val tampered = LinkFrame(org.json.JSONObject(frame.header.toString()).put("sel_gen", bad), frame.payload)
            try {
                WatchTurnUpload.fromFrame(path, tampered)
                throw AssertionError("a selection generation of $bad must be refused")
            } catch (_: com.rumi.hermesvoice.core.watchlink.LinkProtocolException) {
            }
        }
    }

    @Test
    fun `the Phone's snapshot carries the option, defaults it off for an old snapshot and keeps it with routing off`() {
        assertFalse(WatchSettings().watchAutoNavigateToRouted)
        assertFalse(WatchSettings.parse("""{"wake_location":"OFF","revision":5}""")!!.watchAutoNavigateToRouted)
        assertTrue(WatchSettings.parse(WatchSettings(watchAutoNavigateToRouted = true).toJson())!!.watchAutoNavigateToRouted)
        assertTrue(WatchSettings(watchAutoNavigateToRouted = true).toJson().contains("\"watch_auto_navigate_to_routed\":true"))
        assertNull(WatchSettings.parse("""{"watch_auto_navigate_to_routed":"true"}"""))
        assertNull(WatchSettings.parse("""{"watch_auto_navigate_to_routed":1}"""))
        assertNull(WatchSettings.parse("""{"watch_auto_navigate_to_routed":null}"""))
        optionOn()
        settings.routingEnabled = false
        assertTrue(AppSettings(InMemoryKeyValueStore()).watchSettings().watchAutoNavigateToRouted.not())
        assertTrue(settings.watchSettings().watchAutoNavigateToRouted)
        assertFalse("independent of the Phone's own navigation", settings.autoNavigateToRouted)
        settings.autoNavigateToRouted = true
        assertTrue(settings.watchSettings().watchAutoNavigateToRouted)
        optionOn(false)
        assertTrue("the Phone's own switch is untouched by the Watch's", settings.autoNavigateToRouted)
    }
}
