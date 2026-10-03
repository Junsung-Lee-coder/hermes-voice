package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import com.rumi.hermesvoice.core.net.HermesConversationPort
import com.rumi.hermesvoice.core.net.HermesSpeechGateway
import com.rumi.hermesvoice.core.net.OutgoingAttachment
import com.rumi.hermesvoice.core.net.SubmittedTurn
import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.voice.AssembledRoute
import com.rumi.hermesvoice.core.voice.DestinationAllowlist
import com.rumi.hermesvoice.core.voice.DestinationEntry
import com.rumi.hermesvoice.core.voice.DirectAck
import com.rumi.hermesvoice.core.voice.PlaybackCue
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.RecipientEvent
import com.rumi.hermesvoice.core.voice.RoutedNavigation
import com.rumi.hermesvoice.core.voice.TurnRouting
import com.rumi.hermesvoice.core.voice.VoiceTurnConfig
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import com.rumi.hermesvoice.core.watchlink.LinkFrame
import com.rumi.hermesvoice.core.watchlink.LinkProtocolException
import com.rumi.hermesvoice.core.watchlink.PlayRequest
import com.rumi.hermesvoice.core.watchlink.PlayedAck
import com.rumi.hermesvoice.core.watchlink.TurnStateMessage
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.VoiceOutcomeText
import com.rumi.hermesvoice.core.watchlink.WatchAckRegistry
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchTransport
import com.rumi.hermesvoice.core.watchlink.WatchTurnIntake
import com.rumi.hermesvoice.core.watchlink.WatchTurnUpload
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The two Phone routing preferences, through the real orchestrator (and the real Watch intake):
 * routing on keeps the model-assisted path; routing off delivers to the sending device's selected
 * conversation without the router; auto-navigation fires once, after delivery, for the latest turn.
 */
class RoutingPreferencesTest {
    private val timeline: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val configRoutings: MutableList<TurnRouting> = Collections.synchronizedList(mutableListOf())
    private var routingReply = """{"action":"route","destination":"work","ack":"Sending to work."}"""
    private var transcriptGate: CompletableDeferred<Unit>? = null
    private var verdict = AudioInputVerdict.USABLE

    private val entries = listOf(DestinationEntry("work", "work_session"), DestinationEntry("home", "home_session"))

    private val speech = object : HermesSpeechGateway {
        override suspend fun transcribe(audio: ByteArray, mimeType: String): String {
            transcriptGate?.await()
            timeline += "transcribe"
            return "move my meeting"
        }

        override suspend fun speak(text: String): SpokenAudio {
            timeline += "speak:$text"
            return SpokenAudio(text.toByteArray(), "audio/mpeg")
        }
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
        override suspend fun create(source: String, title: String, seedInstruction: String, hidden: Boolean) =
            throw UnsupportedOperationException()

        override suspend fun submit(storedSessionId: String, text: String, attachments: List<OutgoingAttachment>): SubmittedTurn {
            if (storedSessionId == "router_session") {
                timeline += "route"
                return Turn(listOf(RecipientEvent.Complete(routingReply, "complete")))
            }
            timeline += "submit:$storedSessionId:$text"
            return Turn(listOf(RecipientEvent.Complete("final", "complete")))
        }
    }

    private val sink = PlaybackSink { audio, cue -> timeline += "play:${cue.device}:${cue.role}:${String(audio.bytes)}" }

    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val trace = object : VoiceTurnListener {
        override fun onAccepted(turnId: String, origin: VoiceOrigin) { events += "accepted:$turnId" }
        override fun onNotAdmitted(turnId: String, origin: VoiceOrigin, reason: String) { events += "not_admitted:$turnId:$reason" }
        override fun onDelivered(route: AssembledRoute) { events += "delivered:${route.turnId}:${route.destination.storedSessionId}" }
    }

    /** Like the Phone: the router session exists only for routed turns; a direct turn's allowlist has no router. */
    private fun config(routing: TurnRouting): VoiceTurnConfig {
        configRoutings += routing
        return if (routing is TurnRouting.Direct) VoiceTurnConfig(null, DestinationAllowlist.create(entries, null), ResponsePlaybackSettings())
        else VoiceTurnConfig("router_session", DestinationAllowlist.create(entries, "router_session"), ResponsePlaybackSettings())
    }

    private fun orchestrator(listener: VoiceTurnListener = trace) = VoiceTurnOrchestrator(speech, conversations,
        config = { config(it) }, listener = listener, inputGate = { _, _ -> verdict })

    private fun request(id: String, routing: TurnRouting, origin: VoiceOrigin = VoiceOrigin.PHONE) =
        VoiceTurnRequest(id, origin, byteArrayOf(1, 2, 3), "audio/wav", sink, routing = routing)

    // ── routing on / off through the orchestrator ────────────────────────────────────────────

    @Test
    fun `routing on keeps the router, its spoken ack and the original transcript`() = runBlocking {
        val outcome = orchestrator().run(request("on-1", TurnRouting.Model))
        assertEquals(listOf("transcribe", "route", "speak:Sending to work.", "play:PHONE:ACK:Sending to work.",
            "submit:work_session:move my meeting", "speak:final", "play:PHONE:FINAL:final"), timeline.toList())
        outcome as VoiceTurnOutcome.Completed
        assertFalse(outcome.route.direct)
        assertEquals(listOf<TurnRouting>(TurnRouting.Model), configRoutings.toList())
    }

    @Test
    fun `routing off skips the router and delivers the original transcript to the selected conversation`() = runBlocking {
        val outcome = orchestrator().run(request("off-1", TurnRouting.Direct("home_session")))
        val ack = DirectAck.compose("home", "move my meeting")
        assertEquals(listOf("transcribe", "speak:$ack", "play:PHONE:ACK:$ack", "submit:home_session:move my meeting",
            "speak:final", "play:PHONE:FINAL:final"), timeline.toList())
        assertFalse("the router session is never asked", timeline.contains("route"))
        outcome as VoiceTurnOutcome.Completed
        assertTrue(outcome.route.direct)
        assertEquals("home_session", outcome.route.destination.storedSessionId)
        assertEquals(listOf<TurnRouting>(TurnRouting.Direct("home_session")), configRoutings.toList())
        assertEquals(listOf("accepted:off-1", "delivered:off-1:home_session"), events.toList())
    }

    @Test
    fun `routing off with nothing selected is refused before acceptance and moves nothing`() = runBlocking {
        val orchestrator = orchestrator()
        val outcome = orchestrator.run(request("off-none", TurnRouting.Direct(null)))
        assertEquals(VoiceTurnOutcome.NotAdmitted(TurnRouting.NO_TARGET), outcome)
        assertTrue("not transcribed, routed or played", timeline.isEmpty())
        assertNull("the playback target is unchanged", orchestrator.playbackRoute.current())
        assertEquals(listOf("not_admitted:off-none:${TurnRouting.NO_TARGET}"), events.toList())
        assertTrue(VoiceOutcomeText.describe(outcome).contains("Open a conversation"))
    }

    @Test
    fun `routing off never delivers to a conversation that is not this app's active one`() = runBlocking {
        for ((id, target) in listOf("off-gone" to "deleted_session", "off-router" to "router_session", "off-foreign" to "someone_elses")) {
            timeline.clear()
            val outcome = orchestrator().run(request(id, TurnRouting.Direct(target)))
            outcome as VoiceTurnOutcome.NotDelivered
            assertEquals(TurnRouting.TARGET_UNAVAILABLE, outcome.reason)
            assertFalse(target, timeline.any { it.startsWith("submit:") || it == "route" || it.startsWith("play:") })
            assertTrue(VoiceOutcomeText.describe(outcome).contains("Select another"))
        }
    }

    @Test
    fun `no-speech input is rejected before the routing preference is even looked at`() = runBlocking {
        verdict = AudioInputVerdict.NO_SPEECH_ENERGY
        val outcome = orchestrator().run(request("quiet", TurnRouting.Direct(null)))
        assertEquals(VoiceTurnOutcome.NoSpeech, outcome)
        assertTrue(events.isEmpty() && timeline.isEmpty() && configRoutings.isEmpty())
    }

    @Test
    fun `a direct turn id is accepted once`() = runBlocking {
        val orchestrator = orchestrator()
        orchestrator.run(request("dup-1", TurnRouting.Direct("work_session")))
        val again = orchestrator.run(request("dup-1", TurnRouting.Direct("work_session")))
        assertEquals(VoiceTurnOutcome.Duplicate("dup-1"), again)
        assertEquals(1, timeline.count { it.startsWith("submit:") })
    }

    // ── the Watch: its own selected conversation, frozen when its upload arrives ─────────────

    private val acks = WatchAckRegistry()

    /** A Watch that confirms every utterance it is sent, like the real one does when playback ends. */
    private inner class RecordingTransport : WatchTransport {
        override val nodeId = "watch-node"
        val states: MutableList<TurnStateMessage> = Collections.synchronizedList(mutableListOf())
        override suspend fun sendMessage(path: String, bytes: ByteArray) {
            if (path == WatchLinkPaths.STATE) TurnStateMessage.decode(bytes)?.let { states += it }
        }

        override suspend fun sendChannel(path: String, bytes: ByteArray) {
            val play = PlayRequest.fromFrame(LinkFrame.decode(bytes))
            timeline += "play:WATCH:${play.role}:${String(play.audio)}"
            acks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, true).encode())
        }
    }

    private fun upload(turnId: String, target: String?) =
        WatchTurnUpload(turnId, TurnTrigger.PUSH_TO_TALK, WatchTurnUpload.MIME_WAV, ByteArray(200) { 1 }, target = target)

    @Test
    fun `the watch's selected conversation travels with its upload and old frames still parse`() {
        val path = WatchLinkPaths.turnPath("watch-turn-1")
        val withTarget = WatchTurnUpload.fromFrame(path, LinkFrame.decode(upload("watch-turn-1", "home_session").toFrame().encode()))
        assertEquals("home_session", withTarget.target)
        val legacy = LinkFrame(JSONObject().put("v", 1).put("turn_id", "watch-turn-1").put("trigger", "PUSH_TO_TALK")
            .put("mime", WatchTurnUpload.MIME_WAV).put("bytes", 200), ByteArray(200) { 1 })
        assertNull(WatchTurnUpload.fromFrame(path, legacy).target)
        val bad = LinkFrame(JSONObject(legacy.header.toString()).put("target", "../etc"), ByteArray(200) { 1 })
        try {
            WatchTurnUpload.fromFrame(path, bad)
            fail("an invalid target id must be rejected")
        } catch (_: LinkProtocolException) {
        }
    }

    @Test
    fun `routing off sends a watch turn to the watch's conversation, and a watch with none is told to open one`() = runBlocking {
        val orchestrator = orchestrator()
        val intake = WatchTurnIntake(orchestrator, acks) { up -> TurnRouting.of(false, up.target) }
        val sent = RecordingTransport()
        val delivered = intake.onTurnChannel(WatchLinkPaths.turnPath("watch-home-1"), upload("watch-home-1", "home_session").toFrame().encode(), sent)
        delivered as VoiceTurnOutcome.Completed
        assertTrue(delivered.route.direct)
        assertTrue(timeline.contains("submit:home_session:move my meeting") && !timeline.contains("route"))
        assertTrue("acknowledged and answered on the Watch", timeline.contains("play:WATCH:ACK:${DirectAck.compose("home", "move my meeting")}"))
        assertEquals(VoiceOrigin.WATCH, orchestrator.playbackRoute.current()?.device)

        timeline.clear()
        val fresh = orchestrator()
        val freshIntake = WatchTurnIntake(fresh, acks) { up -> TurnRouting.of(false, up.target) }
        val transport = RecordingTransport()
        freshIntake.onTurnChannel(WatchLinkPaths.turnPath("watch-none-1"), upload("watch-none-1", null).toFrame().encode(), transport)
        val terminal = transport.states.last()
        assertTrue(terminal.terminal)
        assertTrue(terminal.detail, terminal.detail.contains("Open a conversation"))
        assertFalse(timeline.any { it.startsWith("submit:") })
        assertNull(fresh.playbackRoute.current())
    }

    @Test
    fun `a routing change while a turn is in flight does not reroute it`() = runBlocking {
        var routingEnabled = false
        val gate = CompletableDeferred<Unit>().also { transcriptGate = it }
        val orchestrator = orchestrator()
        val intake = WatchTurnIntake(orchestrator, acks) { up -> TurnRouting.of(routingEnabled, up.target) }
        val running = async {
            intake.onTurnChannel(WatchLinkPaths.turnPath("watch-frozen-1"), upload("watch-frozen-1", "home_session").toFrame().encode(),
                RecordingTransport())
        }
        withTimeout(5_000) { while (!events.contains("accepted:watch-frozen-1")) delay(5) }
        routingEnabled = true // toggled on mid-turn
        gate.complete(Unit)
        val outcome = running.await() as VoiceTurnOutcome.Completed
        assertTrue(outcome.route.direct)
        assertFalse(timeline.contains("route"))
        assertTrue(timeline.contains("submit:home_session:move my meeting"))

        // And the other way round: frozen as routed, turned off mid-turn.
        timeline.clear()
        val gate2 = CompletableDeferred<Unit>().also { transcriptGate = it }
        val routed = async { orchestrator.run(request("phone-frozen-2", TurnRouting.of(routingEnabled, "home_session"))) }
        withTimeout(5_000) { while (!events.contains("accepted:phone-frozen-2")) delay(5) }
        routingEnabled = false
        gate2.complete(Unit)
        val second = routed.await() as VoiceTurnOutcome.Completed
        assertFalse(second.route.direct)
        assertTrue(timeline.contains("route") && timeline.contains("submit:work_session:move my meeting"))
    }

    // ── auto-navigation ──────────────────────────────────────────────────────────────────────

    private val navigated: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun navigation(enabled: () -> Boolean) = RoutedNavigation(enabled) { id ->
        navigated += id
        timeline += "navigate:$id"
    }

    private fun both(a: VoiceTurnListener, b: VoiceTurnListener) = object : VoiceTurnListener {
        override fun onAccepted(turnId: String, origin: VoiceOrigin) { a.onAccepted(turnId, origin); b.onAccepted(turnId, origin) }
        override fun onDelivered(route: AssembledRoute) { a.onDelivered(route); b.onDelivered(route) }
        override fun onPlayed(cue: PlaybackCue) { a.onPlayed(cue); b.onPlayed(cue) }
    }

    @Test
    fun `auto-navigation on opens the routed conversation once, only after the destination accepted the transcript`() = runBlocking {
        val nav = navigation { true }
        val orchestrator = orchestrator(both(trace, nav))
        orchestrator.run(request("nav-1", TurnRouting.Model))
        assertEquals(listOf("work_session"), navigated.toList())
        assertTrue("after delivery", timeline.indexOf("navigate:work_session") > timeline.indexOf("submit:work_session:move my meeting"))
        orchestrator.run(request("nav-1", TurnRouting.Model))
        assertEquals("a replayed turn does not navigate again", 1, navigated.size)
    }

    @Test
    fun `auto-navigation off, routing off, or a turn that was not delivered never navigates`() = runBlocking {
        orchestrator(both(trace, navigation { false })).run(request("nav-off", TurnRouting.Model))
        orchestrator(both(trace, navigation { true })).run(request("nav-direct", TurnRouting.Direct("home_session")))
        routingReply = """{"action":"route","destination":"nowhere","ack":"x"}"""
        orchestrator(both(trace, navigation { true })).run(request("nav-rejected", TurnRouting.Model))
        assertTrue(navigated.isEmpty())
    }

    @Test
    fun `the preference is frozen when the turn is accepted`() = runBlocking {
        var enabled = false
        val gate = CompletableDeferred<Unit>().also { transcriptGate = it }
        val orchestrator = orchestrator(both(trace, navigation { enabled }))
        val running = async { orchestrator.run(request("nav-frozen", TurnRouting.Model)) }
        withTimeout(5_000) { while (!events.contains("accepted:nav-frozen")) delay(5) }
        enabled = true
        gate.complete(Unit)
        running.await()
        assertTrue(navigated.isEmpty())
    }

    @Test
    fun `a manual navigation after the turn started, or a newer turn, wins over an older turn's navigation`() = runBlocking {
        val nav = navigation { true }
        val gate = CompletableDeferred<Unit>().also { transcriptGate = it }
        val orchestrator = orchestrator(both(trace, nav))
        val first = async { orchestrator.run(request("nav-manual", TurnRouting.Model)) }
        withTimeout(5_000) { while (!events.contains("accepted:nav-manual")) delay(5) }
        nav.onManualNavigation()
        gate.complete(Unit)
        first.await()
        assertTrue("the user's own navigation is kept", navigated.isEmpty())

        val gate2 = CompletableDeferred<Unit>().also { transcriptGate = it }
        val older = async { orchestrator.run(request("nav-older", TurnRouting.Model)) }
        withTimeout(5_000) { while (!events.contains("accepted:nav-older")) delay(5) }
        val newer = async { orchestrator.run(request("nav-newer", TurnRouting.Model)) }
        withTimeout(5_000) { while (!events.contains("accepted:nav-newer")) delay(5) }
        gate2.complete(Unit)
        older.await()
        newer.await()
        assertEquals("only the newest accepted turn navigates", listOf("work_session"), navigated.toList())
        assertEquals(1, timeline.count { it == "navigate:work_session" })
    }

    // ── the settings themselves ──────────────────────────────────────────────────────────────

    @Test
    fun `defaults keep today's behavior and both preferences survive a new settings instance over the same store`() {
        val store = InMemoryKeyValueStore()
        val settings = AppSettings(store)
        assertTrue("routing on by default", settings.routingEnabled)
        assertFalse("auto-navigation off by default", settings.autoNavigateToRouted)
        settings.autoNavigateToRouted = true
        settings.routingEnabled = false
        val reopened = AppSettings(store)
        assertFalse(reopened.routingEnabled)
        assertTrue("turning routing off keeps the saved navigation choice", reopened.autoNavigateToRouted)
        assertFalse("but it does not apply while routing is off", reopened.autoNavigationApplies)
        reopened.routingEnabled = true
        assertTrue(AppSettings(store).autoNavigationApplies)
    }

    @Test
    fun `turn routing is built from the frozen preference and only a well-formed selection`() {
        assertEquals(TurnRouting.Model, TurnRouting.of(true, "home_session"))
        assertEquals(TurnRouting.Direct("home_session"), TurnRouting.of(false, "home_session"))
        assertEquals(TurnRouting.Direct(null), TurnRouting.of(false, null))
        assertEquals(TurnRouting.Direct(null), TurnRouting.of(false, "bad id/../x"))
    }
}
