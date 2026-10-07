package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.TurnRouting
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import com.rumi.hermesvoice.core.watchlink.LinkFrame
import com.rumi.hermesvoice.core.watchlink.PlayRequest
import com.rumi.hermesvoice.core.watchlink.PlayedAck
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchTransport
import com.rumi.hermesvoice.core.watchlink.WatchTurnUpload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The final-recipient wire payload of a voice request: the microphone marker, one space, and the user's own words,
 * nothing else. Production orchestrator, production gateway port and its `prompt.submit` over a real socket to the
 * fake gateway. Whether a model follows the marker is NOT exercised (NOT_RUN); this proves what is sent.
 */
class VoiceMarkerWireTest {
    private val said = "move my 2pm meeting to 3"

    private fun CoreHarness.work(alias: String = "work"): String {
        val stored = runBlocking { core.sessions.createConversation(alias.replaceFirstChar { it.uppercase() }, alias, "") }.storedSessionId
        fake.scripts[stored] = { listOf(FakeHermesDashboard.complete("Done.")) }
        return stored
    }

    private fun CoreHarness.routeTo(alias: String) {
        fake.sourceScripts[AppSources.ROUTER] = { listOf(FakeHermesDashboard.complete("""{"destination":"$alias","ack":"On it."}""")) }
    }

    private fun CoreHarness.router(): String = registry.router()!!.storedSessionId

    private fun CoreHarness.phone(id: String, words: String = said, routing: TurnRouting = TurnRouting.Model, origin: VoiceOrigin = VoiceOrigin.PHONE) = runBlocking {
        core.orchestrator.run(VoiceTurnRequest(id, origin, TestAudio.speechWav(), "audio/wav", PlaybackSink { _, _ -> },
            recognizedText = words, routing = routing))
    }

    private class Watch(private val h: CoreHarness) : WatchTransport {
        override val nodeId = "watch-node-a"
        override suspend fun sendMessage(path: String, bytes: ByteArray) {}
        override suspend fun sendChannel(path: String, bytes: ByteArray) {
            val play = PlayRequest.fromFrame(LinkFrame.decode(bytes))
            h.core.watchAcks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, true).encode())
        }
    }

    private fun CoreHarness.watch(id: String, target: String? = null) {
        val frame = WatchTurnUpload(id, TurnTrigger.PUSH_TO_TALK, WatchTurnUpload.MIME_WAV, TestAudio.speechWav(), target = target).toFrame().encode()
        runBlocking { core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath(id), frame, Watch(this@watch)) }
    }

    private fun CoreHarness.toRecipient(stored: String) = fake.rawPrompts.filter { it.first == stored }.map { it.second }

    @Test
    fun `the marker is the microphone code point and one space`() {
        assertEquals(0x1F399, VOICE_MARK.codePointAt(0))
        assertEquals(' ', VOICE_MARK[VOICE_MARK.length - 1])
        assertEquals(3, VOICE_MARK.length)
    }

    @Test
    fun `a routed Phone voice request is sent to its recipient as the marker, a space and the original words only`() {
        CoreHarness().use { h ->
            val work = h.work()
            h.routeTo("work")
            val outcome = h.phone("mk-phone-routed") as VoiceTurnOutcome.Completed
            assertEquals("the canonical original is kept in the route", said, outcome.route.originalTranscript)
            assertEquals(listOf(VOICE_MARK + said), h.toRecipient(work))
        }
    }

    @Test
    fun `a routed request leaves the router prompt as it was - no marker, no old instruction`() {
        CoreHarness().use { h ->
            h.work()
            h.routeTo("work")
            h.phone("mk-router")
            val toRouter = h.fake.rawPrompts.filter { it.first == h.router() }
            assertEquals(1, toRouter.size)
            assertTrue("the router still gets the transcript as data", toRouter.single().second.contains(said))
            assertFalse("no marker was added for the router", toRouter.single().second.startsWith(VOICE_MARK.trim()))
            assertFalse("the router is not given the marker anywhere", toRouter.single().second.contains(VOICE_MARK))
            assertFalse(toRouter.single().second.contains(OLD_VOICE_HINT))
        }
    }

    @Test
    fun `a direct Phone voice request carries the marker and has no router turn at all`() {
        CoreHarness().use { h ->
            val work = h.work()
            h.phone("mk-phone-direct", routing = TurnRouting.Direct(work))
            assertEquals(listOf(work to VOICE_MARK + said), h.fake.rawPrompts.toList())
        }
    }

    @Test
    fun `a routed Watch voice request is sent through the production relay with the same marker payload`() {
        CoreHarness().use { h ->
            val work = h.work()
            h.routeTo("work")
            h.watch("mk-watch-0001")
            assertEquals(listOf(VOICE_MARK + h.fake.transcript), h.toRecipient(work))
            val toRouter = h.fake.rawPrompts.single { it.first == h.router() }.second
            assertFalse(toRouter.contains(VOICE_MARK))
        }
    }

    @Test
    fun `a direct Watch voice request through the relay (Phone routing off) carries the marker too`() {
        CoreHarness().use { h ->
            val work = h.work()
            h.settings.routingEnabled = false
            h.watch("mk-watch-0002", target = work)
            assertEquals(listOf(work to VOICE_MARK + h.fake.transcript), h.fake.rawPrompts.toList())
        }
    }

    @Test
    fun `a direct request made on the Watch origin by the orchestrator carries the marker`() {
        CoreHarness().use { h ->
            val work = h.work()
            h.phone("mk-watch-direct", routing = TurnRouting.Direct(work), origin = VoiceOrigin.WATCH)
            assertEquals(listOf(VOICE_MARK + said), h.toRecipient(work))
        }
    }

    @Test
    fun `nothing of the old instruction is sent anywhere`() {
        CoreHarness().use { h ->
            val work = h.work()
            h.routeTo("work")
            h.phone("mk-no-hint-1")
            h.phone("mk-no-hint-2", routing = TurnRouting.Direct(work))
            h.watch("mk-watch-0003")
            assertEquals(3, h.toRecipient(work).size)
            assertTrue(h.fake.rawPrompts.none { it.second.contains("음성으로 보낸 요청입니다") || it.second.contains(OLD_VOICE_HINT) })
            assertTrue("no suffix after the words", h.toRecipient(work).all { it == VOICE_MARK + said || it == VOICE_MARK + h.fake.transcript })
        }
    }

    @Test
    fun `an ordinary text message keeps its exact payload with no marker`() {
        CoreHarness().use { h ->
            val work = h.work()
            val turn = runBlocking { h.core.sessions.sendMessage(work, "  plain typed question  ") }
            turn.release()
            assertEquals(listOf("plain typed question"), h.toRecipient(work))
        }
    }

    @Test
    fun `a typed message after a voice request in the same conversation carries no marker while the voice one does`() {
        CoreHarness().use { h ->
            val work = h.work()
            h.phone("mk-before-text", routing = TurnRouting.Direct(work))
            val turn = runBlocking { h.core.sessions.sendMessage(work, "  plain typed question  ") }
            turn.release()
            assertEquals(listOf(VOICE_MARK + said, "plain typed question"), h.toRecipient(work))
        }
    }

    @Test
    fun `a typed message that itself begins with the marker is sent as typed - the leading marker means voice by convention`() {
        CoreHarness().use { h ->
            val work = h.work()
            val typed = VOICE_MARK + "typed on purpose"
            runBlocking { h.core.sessions.sendMessage(work, typed) }.release()
            assertEquals(listOf(typed), h.toRecipient(work))
        }
    }

    @Test
    fun `a spoken request that already starts with the marker is not given a second one`() {
        CoreHarness().use { h ->
            val work = h.work()
            h.phone("mk-already", words = VOICE_MARK + "already marked", routing = TurnRouting.Direct(work))
            assertEquals(listOf(VOICE_MARK + "already marked"), h.toRecipient(work))
        }
    }

    @Test
    fun `old instruction looking text, multiline text and several languages are preserved after the marker`() {
        CoreHarness().use { h ->
            val work = h.work()
            val cases = listOf(
                "say this: \n\n$OLD_VOICE_HINT",
                "첫째 줄\n둘째 줄\n\n셋째 줄",
                "明日の会議を三時に変更してください",
                "Réponds en français, s'il te plaît — ¿y en español también? 🙂",
                "  words with outer spaces kept as the transcript has them  ",
            )
            cases.forEachIndexed { i, words -> h.phone("mk-lang-$i", words = words, routing = TurnRouting.Direct(work)) }
            val sent = h.toRecipient(work)
            assertEquals(cases.map { VOICE_MARK + it.trim() }, sent)
        }
    }

    @Test
    fun `consecutive voice requests to two conversations each keep their own content`() {
        CoreHarness().use { h ->
            val one = h.work("work")
            val two = h.work("home")
            h.phone("mk-a", words = "alpha request", routing = TurnRouting.Direct(one))
            h.phone("mk-b", words = "베타 요청입니다", routing = TurnRouting.Direct(two), origin = VoiceOrigin.WATCH)
            h.phone("mk-c", words = "gamma request", routing = TurnRouting.Direct(one))
            assertEquals(listOf(VOICE_MARK + "alpha request", VOICE_MARK + "gamma request"), h.toRecipient(one))
            assertEquals(listOf(VOICE_MARK + "베타 요청입니다"), h.toRecipient(two))
        }
    }

    @Test
    fun `a request that never reaches a recipient (no speech, duplicate) sends no marker and no second turn`() {
        CoreHarness().use { h ->
            val work = h.work()
            val silent = TestAudio.wav(TestAudio.noise(9.2, peak = 2))
            val none = runBlocking {
                h.core.orchestrator.run(VoiceTurnRequest("mk-silent", VoiceOrigin.PHONE, silent, "audio/wav", PlaybackSink { _, _ -> }, routing = TurnRouting.Direct(work)))
            }
            assertEquals(VoiceTurnOutcome.NoSpeech, none)
            assertTrue(h.fake.rawPrompts.isEmpty())
            h.phone("mk-dup", routing = TurnRouting.Direct(work))
            val again = h.phone("mk-dup", routing = TurnRouting.Direct(work))
            assertTrue("$again", again is VoiceTurnOutcome.Duplicate)
            assertEquals(1, h.toRecipient(work).size)
        }
    }
}
