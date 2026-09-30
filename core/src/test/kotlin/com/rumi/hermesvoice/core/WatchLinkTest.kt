package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.voice.PlaybackCue
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import com.rumi.hermesvoice.core.watchlink.LinkFrame
import com.rumi.hermesvoice.core.watchlink.LinkProtocolException
import com.rumi.hermesvoice.core.watchlink.PlayRequest
import com.rumi.hermesvoice.core.watchlink.PlayedAck
import com.rumi.hermesvoice.core.watchlink.TurnStateMessage
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchAckRegistry
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchPlaybackSink
import com.rumi.hermesvoice.core.watchlink.WatchTransport
import com.rumi.hermesvoice.core.watchlink.WatchTurnUpload
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WatchLinkTest {
    private val wav = TestAudio.speechWav()

    /**
     * Plays instantly (or not at all) and acks through the registry from its own node, as the Watch
     * app does. With [holdAck], the ack (seq 0) plays until that gate completes.
     */
    private class FakeWatch(
        private val acks: WatchAckRegistry,
        var ackOk: Boolean = true,
        var silent: Boolean = false,
        override val nodeId: String = "watch-node-a",
        val holdAck: CompletableDeferred<Unit>? = null,
    ) : WatchTransport {
        val playing = CompletableDeferred<Unit>()
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val states: MutableList<TurnStateMessage> = Collections.synchronizedList(mutableListOf())
        val stops: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun sendMessage(path: String, bytes: ByteArray) {
            when (path) {
                WatchLinkPaths.STATE -> states += TurnStateMessage.decode(bytes)!!
                WatchLinkPaths.STOP -> stops += TurnStateMessage.decode(bytes)!!.turnId
                else -> fail("unexpected message path $path")
            }
        }

        override suspend fun sendChannel(path: String, bytes: ByteArray) {
            val play = PlayRequest.fromFrame(LinkFrame.decode(bytes))
            assertEquals(WatchLinkPaths.playPath(play.turnId, play.sequence), path)
            log += "${play.role}:${play.sequence}:${String(play.audio).removePrefix("AUDIO:")}"
            playing.complete(Unit)
            if (play.sequence == 0) holdAck?.await()
            if (!silent) acks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, ackOk, if (ackOk) "" else "speaker busy").encode())
        }
    }

    @Test
    fun `turn frames round trip and are validated against the channel path`() {
        val upload = WatchTurnUpload("turn-0001", TurnTrigger.WAKE_PHRASE, WatchTurnUpload.MIME_WAV, wav)
        val bytes = upload.toFrame().encode()
        val decoded = WatchTurnUpload.fromFrame(WatchLinkPaths.turnPath("turn-0001"), LinkFrame.decode(bytes))
        assertEquals(TurnTrigger.WAKE_PHRASE, decoded.trigger)
        assertArrayEquals(wav, decoded.audio)
        val rejects = listOf(
            { WatchTurnUpload.fromFrame(WatchLinkPaths.turnPath("turn-0002"), LinkFrame.decode(bytes)) },
            { WatchTurnUpload.fromFrame("/hv/v1/turn/../x", LinkFrame.decode(bytes)) },
            { LinkFrame.decode(bytes.copyOf(6)) },
            { LinkFrame.decode("garbage!".toByteArray()) },
            { WatchTurnUpload.fromFrame(WatchLinkPaths.turnPath("turn-0003"),
                WatchTurnUpload("turn-0003", TurnTrigger.PUSH_TO_TALK, "audio/wav", ByteArray(10)).toFrame()) },
        )
        for (reject in rejects) {
            try {
                reject()
                fail("frame should have been rejected")
            } catch (_: LinkProtocolException) {
            }
        }
        assertNull(WatchLinkPaths.turnIdFromPath("/hv/v1/turn/short"))
    }

    @Test
    fun `watch sink returns only after the matching ack and surfaces failures`() = runBlocking {
        val acks = WatchAckRegistry()
        val watch = FakeWatch(acks)
        val sink = WatchPlaybackSink(watch, acks)
        sink.play(SpokenAudio("AUDIO:hi".toByteArray(), "audio/mpeg"), PlaybackCue("turn-0001", VoiceOrigin.WATCH, SpokenRole.ACK, 0, "hi"))
        assertEquals(listOf("ACK:0:hi"), watch.log.toList())
        watch.ackOk = false
        try {
            sink.play(SpokenAudio("AUDIO:x".toByteArray(), "audio/mpeg"), PlaybackCue("turn-0001", VoiceOrigin.WATCH, SpokenRole.FINAL, 1, "x"))
            fail("a failed watch playback must throw")
        } catch (error: HermesPlaybackException) {
            assertTrue(error.message!!.contains("speaker busy"))
        }
        assertFalse("forged/late acks are ignored", acks.onPlayedMessage("watch-node-a", PlayedAck("turn-0001", 9, true).encode()))
    }

    @Test
    fun `an unreachable watch surfaces as a playback failure and the turn is not delivered`() {
        CoreHarness().use { h ->
            h.fake.sourceScripts[AppSources.ROUTER] = { listOf(FakeHermesDashboard.complete("""{"destination":"work","ack":"On it."}""")) }
            runBlocking { h.core.sessions.createConversation("Work", "work", "") }
            val broken = object : WatchTransport {
                override val nodeId = "watch-node-a"
                override suspend fun sendMessage(path: String, bytes: ByteArray) {}
                override suspend fun sendChannel(path: String, bytes: ByteArray) = throw IllegalStateException("ApiException: 4000")
            }
            val frame = WatchTurnUpload("turn-watch-9", TurnTrigger.PUSH_TO_TALK, WatchTurnUpload.MIME_WAV, wav).toFrame().encode()
            val outcome = runBlocking { h.core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath("turn-watch-9"), frame, broken) }
            assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.NotDelivered)
            assertEquals(1, h.fake.prompts.size) // only the routing prompt; the destination never received it
        }
    }

    @Test
    fun `missing ack times out and cancellation tells the watch to stop`() = runBlocking {
        val acks = WatchAckRegistry()
        val watch = FakeWatch(acks, silent = true)
        val sink = WatchPlaybackSink(watch, acks, ackTimeoutMs = { 50 })
        try {
            sink.play(SpokenAudio("AUDIO:a".toByteArray(), "audio/mpeg"), PlaybackCue("turn-0001", VoiceOrigin.WATCH, SpokenRole.ACK, 0, "a"))
            fail("timeout expected")
        } catch (_: HermesPlaybackException) {
        }
        val slow = WatchPlaybackSink(watch, acks, ackTimeoutMs = { 60_000 })
        val job = async {
            slow.play(SpokenAudio("AUDIO:b".toByteArray(), "audio/mpeg"), PlaybackCue("turn-0002", VoiceOrigin.WATCH, SpokenRole.FINAL, 1, "b"))
        }
        withTimeout(5_000) { while (watch.log.size < 2) kotlinx.coroutines.delay(5) }
        job.cancel()
        job.join()
        assertEquals(listOf("turn-0002"), watch.stops.toList())
    }

    @Test
    fun `a watch turn runs the shared orchestrator with playback on the watch and ordered states`() {
        CoreHarness().use { h ->
            h.fake.sourceScripts[AppSources.ROUTER] = {
                listOf(FakeHermesDashboard.complete("""{"destination":"work","ack":"On it."}"""))
            }
            val work = runBlocking { h.core.sessions.createConversation("Work", "work", "") }.storedSessionId
            h.fake.scripts[work] = { listOf(FakeHermesDashboard.interim("Looking."), FakeHermesDashboard.complete("Done.")) }
            h.settings.playFirstResponse = true
            val watch = FakeWatch(h.core.watchAcks)
            val frame = WatchTurnUpload("turn-watch-1", TurnTrigger.PUSH_TO_TALK, WatchTurnUpload.MIME_WAV, wav).toFrame().encode()
            val outcome = runBlocking { h.core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath("turn-watch-1"), frame, watch) }
            assertTrue(outcome is VoiceTurnOutcome.Completed)
            assertEquals(listOf("ACK:0:On it.", "FIRST:1:Looking.", "FINAL:2:Done."), watch.log.toList())
            assertEquals(listOf("transcribing", "routing", "routed", "acknowledging", "delivering", "responding", "done"),
                watch.states.map { it.stage })
            assertTrue(watch.states.last().terminal)
            assertEquals("Delivered to work", watch.states.last().detail)
            // Replayed upload (Data Layer retry) is deduplicated, not re-delivered.
            val again = runBlocking { h.core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath("turn-watch-1"), frame, watch) }
            assertTrue(again is VoiceTurnOutcome.Duplicate)
            assertEquals(2, h.fake.prompts.size)
        }
    }

    @Test
    fun `relay to the watch is not playback and only the target node's ack confirms it`() = runBlocking {
        val acks = WatchAckRegistry()
        val watch = FakeWatch(acks, silent = true)
        val sink = WatchPlaybackSink(watch, acks, ackTimeoutMs = { 300 })
        val play = async {
            runCatching { sink.play(SpokenAudio("AUDIO:a".toByteArray(), "audio/mpeg"), PlaybackCue("turn-0001", VoiceOrigin.PHONE, SpokenRole.FINAL, 1, "a", VoiceOrigin.WATCH)) }
        }
        withTimeout(5_000) { watch.playing.await() }
        // The channel send succeeded, but that is only the Phone's relay; an ack from another node is ignored.
        assertFalse(acks.onPlayedMessage("watch-node-b", PlayedAck("turn-0001", 1, true).encode()))
        val failure = play.await().exceptionOrNull()
        assertTrue("$failure", failure is HermesPlaybackException)

        val confirmed = async { sink.play(SpokenAudio("AUDIO:b".toByteArray(), "audio/mpeg"), PlaybackCue("turn-0002", VoiceOrigin.PHONE, SpokenRole.FINAL, 1, "b", VoiceOrigin.WATCH)) }
        withTimeout(5_000) { while (watch.log.size < 2) kotlinx.coroutines.delay(5) }
        assertTrue(acks.onPlayedMessage("watch-node-a", PlayedAck("turn-0002", 1, true).encode()))
        confirmed.await()
        assertFalse("a duplicate ack is ignored", acks.onPlayedMessage("watch-node-a", PlayedAck("turn-0002", 1, true).encode()))
    }

    @Test
    fun `a text chat between a watch voice request and its reply does not move playback off the watch`() {
        CoreHarness().use { h ->
            h.fake.sourceScripts[AppSources.ROUTER] = { listOf(FakeHermesDashboard.complete("""{"destination":"work","ack":"On it."}""")) }
            val work = runBlocking { h.core.sessions.createConversation("Work", "work", "") }.storedSessionId
            val home = runBlocking { h.core.sessions.createConversation("Home", "home", "") }.storedSessionId
            h.fake.scripts[work] = { listOf(FakeHermesDashboard.complete("Done.")) }
            val hold = CompletableDeferred<Unit>()
            val watch = FakeWatch(h.core.watchAcks, holdAck = hold)
            val frame = WatchTurnUpload("turn-watch-2", TurnTrigger.PUSH_TO_TALK, WatchTurnUpload.MIME_WAV, wav).toFrame().encode()
            runBlocking {
                val turn = async { h.core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath("turn-watch-2"), frame, watch) }
                withTimeout(5_000) { watch.playing.await() }
                val chat = h.core.chat.send(home, "buy milk")
                assertTrue("$chat", chat is ChatSendResult.Replied)
                hold.complete(Unit)
                assertTrue(turn.await() is VoiceTurnOutcome.Completed)
            }
            assertEquals(listOf("ACK:0:On it.", "FINAL:1:Done."), watch.log.toList())
            assertEquals(VoiceOrigin.WATCH, h.core.orchestrator.playbackRoute.current()?.device)
        }
    }

    @Test
    fun `a phone voice request accepted during the watch ack takes the watch turn's final`() {
        CoreHarness().use { h ->
            h.fake.sourceScripts[AppSources.ROUTER] = { listOf(FakeHermesDashboard.complete("""{"destination":"work","ack":"On it."}""")) }
            val work = runBlocking { h.core.sessions.createConversation("Work", "work", "") }.storedSessionId
            h.fake.scripts[work] = { listOf(FakeHermesDashboard.complete("Done.")) }
            val hold = CompletableDeferred<Unit>()
            val watch = FakeWatch(h.core.watchAcks, holdAck = hold)
            val phone: MutableList<PlaybackCue> = Collections.synchronizedList(mutableListOf())
            val phoneSpeaker = PlaybackSink { audio, cue ->
                assertEquals(VoiceOrigin.PHONE, cue.device)
                phone += cue.copy(text = h.fake.decodeSpoken(audio))
            }
            val frame = WatchTurnUpload("turn-watch-3", TurnTrigger.PUSH_TO_TALK, WatchTurnUpload.MIME_WAV, wav).toFrame().encode()
            val (watchOutcome, phoneOutcome) = runBlocking {
                val turn = async { h.core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath("turn-watch-3"), frame, watch) }
                withTimeout(5_000) { watch.playing.await() }
                // The Phone's request is silence, so it never takes the floor; it still becomes the latest voice sender.
                h.fake.transcript = " "
                val phoneTurn = async { h.core.orchestrator.run(VoiceTurnRequest("turn-phone-3", VoiceOrigin.PHONE, wav, "audio/wav", phoneSpeaker)) }
                withTimeout(5_000) { while (h.core.orchestrator.playbackRoute.current()?.device != VoiceOrigin.PHONE) kotlinx.coroutines.delay(5) }
                hold.complete(Unit)
                turn.await() to phoneTurn.await()
            }
            assertTrue("$watchOutcome", watchOutcome is VoiceTurnOutcome.Completed)
            assertEquals(VoiceTurnOutcome.NoSpeech, phoneOutcome)
            assertEquals("the ack was handed to the watch before the phone request", listOf("ACK:0:On it."), watch.log.toList())
            val final = phone.single()
            assertEquals(listOf("turn-watch-3", "FINAL", "WATCH", "Done."), listOf(final.turnId, final.role.name, final.origin.name, final.text))
            assertEquals("Delivered to work", watch.states.last().detail)
        }
    }

    @Test
    fun `a malformed watch upload is rejected with a terminal state and never transcribed`() = runBlocking {
        CoreHarness().use { h ->
            val watch = FakeWatch(h.core.watchAcks)
            val outcome = h.core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath("turn-bad-01"), "nope".toByteArray(), watch)
            assertNull(outcome)
            assertEquals("rejected", watch.states.single().stage)
            assertEquals(0, h.fake.server.requestCount)
        }
    }
}
