package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.audio.AudioInputVerdict

import com.rumi.hermesvoice.core.auth.*
import com.rumi.hermesvoice.core.net.*
import com.rumi.hermesvoice.core.sessions.*
import com.rumi.hermesvoice.core.settings.*
import com.rumi.hermesvoice.core.voice.*
import com.rumi.hermesvoice.core.watchlink.*

import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTurnOrchestratorTest {
    private val timeline: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var routingReply = """{"destination":"work","ack":"Sending to work."}"""
    private var transcript = "move my meeting"
    private var failSpeakFor: String? = null
    private var playback = ResponsePlaybackSettings()
    private val destinationScripts = HashMap<String, Channel<RecipientEvent>>()
    private var destinationStatus = "streaming"
    private var destinationAttributable = true
    /** Per spoken words (see [request]): transcription waits until the gate completes. */
    private val transcribeGates = HashMap<String, CompletableDeferred<Unit>>()
    private val heard: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val played: MutableList<PlaybackCue> = Collections.synchronizedList(mutableListOf())
    private val playedListener = object : VoiceTurnListener {
        override fun onPlayed(cue: PlaybackCue) {
            played += cue
        }
    }

    private val allowlist = DestinationAllowlist.create(
        listOf(DestinationEntry("work", "work_session"), DestinationEntry("home", "home_session")), "router_session")

    private val speech = object : HermesSpeechGateway {
        override suspend fun transcribe(audio: ByteArray, mimeType: String): String {
            val said = String(audio).takeIf { it.startsWith(SAY) }?.removePrefix(SAY)
            said?.let { transcribeGates[it]?.await() }
            timeline += "transcribe"
            return (said ?: transcript).also { heard += it }
        }

        override suspend fun speak(text: String): SpokenAudio {
            if (text == failSpeakFor) throw HermesHttpException(500, "Speech synthesis failed")
            timeline += "speak:$text"
            return SpokenAudio("AUDIO:$text".toByteArray(), "audio/mpeg")
        }
    }

    private inner class ScriptedTurn(
        private val events: Channel<RecipientEvent>,
        override val submitStatus: String,
        override val attributable: Boolean,
    ) : SubmittedTurn {
        override suspend fun collect(timeoutMs: Long, onEvent: suspend (RecipientEvent) -> Unit) {
            withTimeout(timeoutMs) {
                for (event in events) {
                    onEvent(event)
                    if (event is RecipientEvent.Complete) return@withTimeout
                }
            }
        }

        override fun release() {}
    }

    private val conversations = object : HermesConversationPort {
        override suspend fun create(source: String, title: String, seedInstruction: String, hidden: Boolean) =
            throw UnsupportedOperationException()

        override suspend fun submit(storedSessionId: String, text: String, attachments: List<OutgoingAttachment>): SubmittedTurn {
            if (storedSessionId == "router_session") {
                timeline += "route"
                assertTrue(heard.any { it.isNotBlank() && text.contains(it) })
                return ScriptedTurn(Channel<RecipientEvent>(Channel.UNLIMITED).apply {
                    trySend(RecipientEvent.Interim("thinking about routing"))
                    trySend(RecipientEvent.Complete(routingReply, "complete"))
                }, "streaming", true)
            }
            // the recipient prompt starts with the voice marker; the fixture keys on the words.
            val words = voiceWords(text)
            timeline += "submit:$storedSessionId:$words"
            val channel = destinationScripts.remove(words) ?: Channel<RecipientEvent>(Channel.UNLIMITED).apply {
                trySend(RecipientEvent.Interim("first"))
                trySend(RecipientEvent.Interim("middle"))
                trySend(RecipientEvent.Complete("final", "complete"))
            }
            return ScriptedTurn(channel, destinationStatus, destinationAttributable)
        }
    }

    /** The speaker of [device]: logs `play:<device>:<role>:<text>` for whatever turn it is handed. */
    private fun sinkFor(device: VoiceOrigin, hold: CompletableDeferred<Unit>? = null, holdRole: SpokenRole? = null) =
        PlaybackSink { audio, cue ->
            assertEquals(device, cue.device)
            timeline += "play:${device.name.lowercase()}:${cue.role}:${String(audio.bytes).removePrefix("AUDIO:")}"
            if (hold != null && cue.role == holdRole) {
                try {
                    hold.await()
                } catch (cancelled: CancellationException) {
                    timeline += "stopped:${cue.role}:${cue.text}"
                    throw cancelled
                }
            }
        }

    /** These tests carry fake transcripts inside the "audio" bytes, so the acoustic gate (AudioInputGateTest) is bypassed. */
    private val passThrough: (ByteArray, String) -> AudioInputVerdict = { _, _ -> AudioInputVerdict.USABLE }

    private fun orchestrator() = VoiceTurnOrchestrator(speech, conversations, inputGate = passThrough,
        config = { VoiceTurnConfig("router_session", allowlist, playback) }, listener = playedListener)

    /** [say] makes the fake transcriber return those words for this request (default: [transcript]). */
    private fun request(turnId: String, origin: VoiceOrigin, sink: PlaybackSink = sinkFor(origin), say: String? = null) =
        VoiceTurnRequest(turnId, origin, say?.let { "$SAY$it".toByteArray() } ?: byteArrayOf(1, 2, 3), "audio/wav", sink)

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(5) }
        assertTrue(what, condition())
    }

    @Test
    fun `full turn plays the ack before delivering the original transcript`() = runBlocking {
        val outcome = orchestrator().run(request("t1", VoiceOrigin.PHONE))
        assertEquals(
            listOf("transcribe", "route", "speak:Sending to work.", "play:phone:ACK:Sending to work.",
                "submit:work_session:move my meeting", "speak:final", "play:phone:FINAL:final"),
            timeline.toList(),
        )
        outcome as VoiceTurnOutcome.Completed
        assertEquals("move my meeting", outcome.route.originalTranscript)
        assertEquals(listOf(SpokenRole.FINAL), outcome.spoken)
    }

    @Test
    fun `settings matrix is identical for phone and watch origins`() = runBlocking {
        val expected = mapOf(
            ResponsePlaybackSettings(false, false) to listOf(SpokenRole.FINAL),
            ResponsePlaybackSettings(true, false) to listOf(SpokenRole.FIRST, SpokenRole.FINAL),
            ResponsePlaybackSettings(false, true) to listOf(SpokenRole.MIDDLE, SpokenRole.FINAL),
            ResponsePlaybackSettings(true, true) to listOf(SpokenRole.FIRST, SpokenRole.MIDDLE, SpokenRole.FINAL),
        )
        var n = 0
        for ((settings, roles) in expected) for (origin in VoiceOrigin.values()) {
            playback = settings
            timeline.clear()
            val outcome = orchestrator().run(request("t${n++}", origin)) as VoiceTurnOutcome.Completed
            assertEquals("$settings/$origin", roles, outcome.spoken)
            val plays = timeline.filter { it.startsWith("play:") }
            assertEquals("play:${origin.name.lowercase()}:ACK:Sending to work.", plays.first())
            assertEquals(roles.size + 1, plays.size)
            assertTrue(plays.all { it.startsWith("play:${origin.name.lowercase()}:") })
        }
    }

    @Test
    fun `non-allowlisted alias fails closed with no ack and no delivery`() = runBlocking {
        routingReply = """{"destination":"finance","ack":"Sending to finance.","session_id":"finance_session"}"""
        val outcome = orchestrator().run(request("t1", VoiceOrigin.WATCH))
        assertEquals(VoiceTurnOutcome.RoutingRejected("move my meeting", "routing_destination_not_allowlisted"), outcome)
        assertEquals(listOf("transcribe", "route"), timeline.toList())
    }

    @Test
    fun `ack that cannot be spoken blocks delivery`() = runBlocking {
        failSpeakFor = "Sending to work."
        val outcome = orchestrator().run(request("t1", VoiceOrigin.PHONE))
        outcome as VoiceTurnOutcome.NotDelivered
        assertEquals(VoiceTurnStage.ACKNOWLEDGING, outcome.stage)
        assertFalse(timeline.any { it.startsWith("submit:") })
    }

    @Test
    fun `ack playback failure on the device blocks delivery`() = runBlocking {
        val sink = PlaybackSink { _, cue -> if (cue.role == SpokenRole.ACK) throw HermesPlaybackException("watch did not confirm playback") }
        val outcome = orchestrator().run(request("t1", VoiceOrigin.WATCH, sink))
        assertEquals(VoiceTurnStage.ACKNOWLEDGING, (outcome as VoiceTurnOutcome.NotDelivered).stage)
        assertFalse(timeline.any { it.startsWith("submit:") })
        assertTrue("an unconfirmed playback is never reported as played", played.isEmpty())
    }

    @Test
    fun `blank transcript never reaches the router`() = runBlocking {
        transcript = "  "
        assertEquals(VoiceTurnOutcome.NoSpeech, orchestrator().run(request("t1", VoiceOrigin.PHONE)))
        assertEquals(listOf("transcribe"), timeline.toList())
    }

    @Test
    fun `replayed turn id is processed once`() = runBlocking {
        val orchestrator = orchestrator()
        orchestrator.run(request("same", VoiceOrigin.WATCH))
        assertEquals(VoiceTurnOutcome.Duplicate("same"), orchestrator.run(request("same", VoiceOrigin.WATCH)))
        assertEquals(1, timeline.count { it.startsWith("submit:") })
    }

    @Test
    fun `unattributable delivery speaks nothing beyond the ack`() = runBlocking {
        destinationStatus = "queued"
        destinationAttributable = false
        val outcome = orchestrator().run(request("t1", VoiceOrigin.PHONE))
        assertEquals("queued", (outcome as VoiceTurnOutcome.DeliveredUnattributed).submitStatus)
        assertEquals(listOf("play:phone:ACK:Sending to work."), timeline.filter { it.startsWith("play:") })
    }

    @Test
    fun `failed middle playback does not suppress the final`() = runBlocking {
        playback = ResponsePlaybackSettings(true, true)
        failSpeakFor = "middle"
        val outcome = orchestrator().run(request("t1", VoiceOrigin.PHONE)) as VoiceTurnOutcome.Completed
        assertEquals(listOf(SpokenRole.FIRST, SpokenRole.FINAL), outcome.spoken)
    }

    @Test
    fun `failed final is reported, not hidden`() = runBlocking {
        failSpeakFor = "final"
        val outcome = orchestrator().run(request("t1", VoiceOrigin.PHONE))
        assertTrue(outcome is VoiceTurnOutcome.DeliveredResponseFailed)
    }

    // v19 migration of "a newer turn's ack interrupts the older turn's response playback": the older reply is an
    // independent request now. It is never interrupted nor superseded: the newer request is accepted at once, its
    // acknowledgement waits for the audio that is playing, goes before the older reply that is still waiting, and
    // both requests complete.
    @Test
    fun `a newer turn's ack waits for the older turn's response playback instead of interrupting it`() = runBlocking {
        playback = ResponsePlaybackSettings(playFirstResponse = true)
        val olderEvents = Channel<RecipientEvent>(Channel.UNLIMITED)
        destinationScripts["older words"] = olderEvents
        val hold = CompletableDeferred<Unit>()
        val orchestrator = orchestrator()

        transcript = "older words"
        val older = async { orchestrator.run(request("t-old", VoiceOrigin.WATCH, sinkFor(VoiceOrigin.WATCH, hold, SpokenRole.FIRST))) }
        olderEvents.send(RecipientEvent.Interim("older first"))
        while (timeline.none { it == "play:watch:FIRST:older first" }) delay(5)

        transcript = "newer words"
        val newer = async { orchestrator.run(request("t-new", VoiceOrigin.PHONE)) }
        olderEvents.send(RecipientEvent.Complete("older final", "complete"))
        delay(300)
        assertFalse("the audio that plays is not stopped", timeline.any { it.startsWith("stopped:") })
        assertFalse("the newer ack waits for it", timeline.contains("play:phone:ACK:Sending to work."))
        hold.complete(Unit)

        val olderOutcome = older.await()
        val newerOutcome = newer.await()
        assertTrue("$olderOutcome", olderOutcome is VoiceTurnOutcome.Completed)
        assertTrue("$newerOutcome", newerOutcome is VoiceTurnOutcome.Completed)
        val order = timeline.toList()
        assertFalse("never silently dropped", order.none { it.endsWith("FINAL:older final") })
        assertTrue(order.indexOf("play:watch:FIRST:older first") < order.indexOf("play:phone:ACK:Sending to work."))
        assertEquals(listOf("submit:work_session:older words", "submit:work_session:newer words"),
            order.filter { it.startsWith("submit:") })
    }

    @Test
    fun `a newer voice request from the other device takes the older turn's unplayed final, both directions`() = runBlocking {
        for ((older, newer) in listOf(VoiceOrigin.WATCH to VoiceOrigin.PHONE, VoiceOrigin.PHONE to VoiceOrigin.WATCH)) {
            timeline.clear()
            played.clear()
            val o = older.name.lowercase()
            val n = newer.name.lowercase()
            val olderEvents = Channel<RecipientEvent>(Channel.UNLIMITED)
            destinationScripts["$o words"] = olderEvents
            val newerGate = CompletableDeferred<Unit>()
            transcribeGates["$n words"] = newerGate
            val orchestrator = orchestrator()

            val olderTurn = async { orchestrator.run(request("t-$o", older, say = "$o words")) }
            waitFor("older delivered") { timeline.contains("submit:work_session:$o words") }
            // The newer request is accepted (it is the latest voice sender) while still transcribing.
            val newerTurn = async { orchestrator.run(request("t-$n", newer, say = "$n words")) }
            waitFor("newer accepted") { orchestrator.playbackRoute.current()?.device == newer }
            olderEvents.send(RecipientEvent.Complete("$o final", "complete"))
            val olderOutcome = olderTurn.await()
            newerGate.complete(Unit)
            val newerOutcome = newerTurn.await()

            assertTrue("$olderOutcome", olderOutcome is VoiceTurnOutcome.Completed)
            assertTrue("$newerOutcome", newerOutcome is VoiceTurnOutcome.Completed)
            assertEquals("$older→$newer", listOf("play:$o:ACK:Sending to work.", "play:$n:FINAL:$o final",
                "play:$n:ACK:Sending to work.", "play:$n:FINAL:final"), timeline.filter { it.startsWith("play:") })
            val retargeted = played.single { it.text == "$o final" }
            assertEquals(older, retargeted.origin)
            assertEquals(newer, retargeted.device)
        }
    }

    @Test
    fun `a voice request accepted before an older turn's ack handoff takes that ack`() = runBlocking {
        val olderGate = CompletableDeferred<Unit>()
        val newerGate = CompletableDeferred<Unit>()
        transcribeGates["watch words"] = olderGate
        transcribeGates["phone words"] = newerGate
        val orchestrator = orchestrator()

        val older = async { orchestrator.run(request("t-watch", VoiceOrigin.WATCH, say = "watch words")) }
        waitFor("watch accepted") { orchestrator.playbackRoute.current()?.device == VoiceOrigin.WATCH }
        val newer = async { orchestrator.run(request("t-phone", VoiceOrigin.PHONE, say = "phone words")) }
        waitFor("phone accepted") { orchestrator.playbackRoute.current()?.device == VoiceOrigin.PHONE }
        olderGate.complete(Unit)
        assertTrue(older.await() is VoiceTurnOutcome.Completed)
        newerGate.complete(Unit)
        assertTrue(newer.await() is VoiceTurnOutcome.Completed)

        assertFalse("nothing plays on the watch once the phone is the latest sender", timeline.any { it.startsWith("play:watch:") })
        assertEquals(listOf("t-watch:ACK", "t-watch:FINAL", "t-phone:ACK", "t-phone:FINAL"),
            played.map { "${it.turnId}:${it.role}" })
        assertTrue(played.all { it.device == VoiceOrigin.PHONE })
        assertEquals(listOf("submit:work_session:watch words", "submit:work_session:phone words"),
            timeline.filter { it.startsWith("submit:") })
    }

    @Test
    fun `a replayed voice upload does not move playback back to its device`() = runBlocking {
        val orchestrator = orchestrator()
        orchestrator.run(request("watch-turn", VoiceOrigin.WATCH))
        orchestrator.run(request("phone-turn", VoiceOrigin.PHONE))
        assertEquals(VoiceTurnOutcome.Duplicate("watch-turn"), orchestrator.run(request("watch-turn", VoiceOrigin.WATCH)))
        assertEquals(VoiceOrigin.PHONE, orchestrator.playbackRoute.current()?.device)
    }

    @Test
    fun `a voice request with no speech still becomes the playback target`() = runBlocking {
        val olderEvents = Channel<RecipientEvent>(Channel.UNLIMITED)
        destinationScripts["watch words"] = olderEvents
        val orchestrator = orchestrator()
        val older = async { orchestrator.run(request("t-watch", VoiceOrigin.WATCH, say = "watch words")) }
        waitFor("watch delivered") { timeline.contains("submit:work_session:watch words") }
        assertEquals(VoiceTurnOutcome.NoSpeech, orchestrator.run(request("t-phone", VoiceOrigin.PHONE, say = " ")))
        olderEvents.send(RecipientEvent.Complete("watch final", "complete"))
        assertTrue(older.await() is VoiceTurnOutcome.Completed)
        assertEquals("play:phone:FINAL:watch final", timeline.last { it.startsWith("play:") })
    }

    @Test
    fun `invalid configuration fails closed before any network call`() = runBlocking {
        val orchestrator = VoiceTurnOrchestrator(speech, conversations, inputGate = passThrough,
            config = { throw IllegalArgumentException("Hermes direct mode is disabled") })
        val outcome = orchestrator.run(request("t1", VoiceOrigin.PHONE))
        assertTrue(outcome is VoiceTurnOutcome.NotDelivered)
        assertTrue(timeline.isEmpty())
    }

    private companion object {
        const val SAY = "SAY:"
    }
}
