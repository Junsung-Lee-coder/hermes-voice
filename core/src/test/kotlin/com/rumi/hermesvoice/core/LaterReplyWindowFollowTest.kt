package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import com.rumi.hermesvoice.core.net.HermesConversationPort
import com.rumi.hermesvoice.core.net.HermesSpeechGateway
import com.rumi.hermesvoice.core.net.LaterEnd
import com.rumi.hermesvoice.core.net.OutgoingAttachment
import com.rumi.hermesvoice.core.net.SubmittedTurn
import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.voice.DestinationAllowlist
import com.rumi.hermesvoice.core.voice.DestinationEntry
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.RecipientEvent
import com.rumi.hermesvoice.core.voice.TurnRouting
import com.rumi.hermesvoice.core.voice.VoiceTurnConfig
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The later-reply window through the real orchestrator's follow: the value a delivered turn is followed with is a SNAPSHOT of the
 * Phone's setting read when its follow starts; the opt-in, Stop, supersession and disconnect keep ending follows as before.
 * The destination is a double that records the window it is asked to follow for.
 */
class LaterReplyWindowFollowTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ended: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val windows: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    private val released = AtomicInteger()
    private val providerReads = AtomicInteger()
    private val ends = Collections.synchronizedMap(mutableMapOf<String, CompletableDeferred<LaterEnd>>())

    @After
    fun stop() = scope.cancel()

    private val listener = object : VoiceTurnListener {
        override fun onLaterFollowEnded(turnId: String, reason: String) { ended += "$turnId:$reason" }
    }

    private val speech = object : HermesSpeechGateway {
        override suspend fun transcribe(audio: ByteArray, mimeType: String) = "hello"
        override suspend fun speak(text: String) = SpokenAudio(text.toByteArray(), "audio/mpeg")
    }

    private inner class FollowedTurn(private val turnId: String) : SubmittedTurn {
        override val submitStatus = "streaming"
        override val attributable = true
        override suspend fun collect(timeoutMs: Long, onEvent: suspend (RecipientEvent) -> Unit) {
            onEvent(RecipientEvent.Complete("answer", "complete"))
        }

        override suspend fun collectLater(windowMs: Long, onLater: suspend (RecipientEvent.Complete) -> Unit): LaterEnd {
            windows += windowMs
            val end = CompletableDeferred<LaterEnd>().also { ends[turnId] = it }
            return try {
                end.await()
            } finally {
                release()
            }
        }

        override fun release() { released.incrementAndGet() }
    }

    private val conversations = object : HermesConversationPort {
        var currentTurnId = ""
        override suspend fun create(source: String, title: String, seedInstruction: String, hidden: Boolean) = throw UnsupportedOperationException()
        override suspend fun submit(storedSessionId: String, text: String, attachments: List<OutgoingAttachment>): SubmittedTurn =
            FollowedTurn(currentTurnId)
    }

    private val entries = listOf(DestinationEntry("work", "work_session"))

    private fun orchestrator(
        enabled: () -> Boolean = { true },
        provider: (() -> Long)? = null,
        fixed: Long = VoiceTurnOrchestrator.LATER_WINDOW_MS,
    ) = VoiceTurnOrchestrator(speech, conversations,
        config = { VoiceTurnConfig(null, DestinationAllowlist.create(entries, null), ResponsePlaybackSettings()) },
        listener = listener, inputGate = { _, _ -> AudioInputVerdict.USABLE }, laterScope = scope, laterWindowMs = fixed,
        laterWindowProvider = provider?.let { read -> { providerReads.incrementAndGet(); read() } }, laterEnabled = enabled)

    private fun deliver(orchestrator: VoiceTurnOrchestrator, turnId: String): VoiceTurnOutcome {
        conversations.currentTurnId = turnId
        return runBlocking {
            orchestrator.run(VoiceTurnRequest(turnId, VoiceOrigin.PHONE, byteArrayOf(1, 2, 3), "audio/wav", PlaybackSink { _, _ -> },
                routing = TurnRouting.Direct("work_session")))
        }
    }

    private fun waitFor(what: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(5_000) { while (!condition()) delay(5) }
        } catch (_: Exception) {
            throw AssertionError("timed out waiting for $what")
        }
    }

    @Test
    fun `a follow uses the stored minutes as exact milliseconds - 1, 30 and 4320 minutes`() {
        val settings = AppSettings(InMemoryKeyValueStore())
        val orchestrator = orchestrator(provider = { settings.laterReplyWindowMillis })
        for ((index, pair) in listOf(1 to 60_000L, 30 to 1_800_000L, 4320 to 259_200_000L).withIndex()) {
            settings.laterReplyWindowMinutes = pair.first
            assertTrue(deliver(orchestrator, "bound-turn-$index") is VoiceTurnOutcome.Completed)
            waitFor("follow ${pair.first}") { windows.size == index + 1 }
            assertEquals(pair.second, windows[index])
        }
    }

    @Test
    fun `the window is a snapshot taken when a delivered turn's follow starts - a change never touches a follow already running`() {
        val settings = AppSettings(InMemoryKeyValueStore())
        val orchestrator = orchestrator(provider = { settings.laterReplyWindowMillis })
        settings.laterReplyWindowMinutes = 45
        deliver(orchestrator, "snap-turn-A")
        waitFor("A follows") { windows.size == 1 }
        settings.laterReplyWindowMinutes = 4320
        assertEquals("A is not restarted or extended", 1, windows.size)
        assertEquals(45 * 60_000L, windows[0])
        assertTrue("A is still being followed", ended.isEmpty())
        settings.laterReplyWindowMinutes = 2
        deliver(orchestrator, "snap-turn-B")
        waitFor("B follows") { windows.size == 2 }
        assertEquals("a NEW delivered turn uses the value as of its own follow start", 2 * 60_000L, windows[1])
        assertEquals("the setting was read once per follow", 2, providerReads.get())
        assertTrue(ended.isEmpty())
    }

    @Test
    fun `a turn that is not delivered reads no window and starts no follow`() {
        val orchestrator = orchestrator(provider = { 4320 * 60_000L })
        val outcome = deliver(orchestrator, "no-deliver-1")
        assertTrue(outcome is VoiceTurnOutcome.Completed)
        // refused before delivery: nothing was selected, so the router-less direct turn has no target
        val refused = runBlocking {
            orchestrator.run(VoiceTurnRequest("no-deliver-2", VoiceOrigin.PHONE, byteArrayOf(1, 2, 3), "audio/wav", PlaybackSink { _, _ -> },
                routing = TurnRouting.Direct(null)))
        }
        assertTrue(refused is VoiceTurnOutcome.NotAdmitted)
        waitFor("only the delivered turn follows") { windows.size == 1 }
        assertEquals(1, providerReads.get())
    }

    @Test
    fun `with the opt-in off nothing is followed whatever the duration, and the duration is not even read`() {
        var enabled = false
        val orchestrator = orchestrator(enabled = { enabled }, provider = { 4320 * 60_000L })
        assertTrue(deliver(orchestrator, "off-turn-001") is VoiceTurnOutcome.Completed)
        waitFor("the turn is released") { released.get() >= 1 }
        assertTrue(windows.isEmpty())
        assertEquals(0, providerReads.get())
        enabled = true
        deliver(orchestrator, "off-turn-002")
        waitFor("followed once on") { windows.size == 1 }
        assertEquals(259_200_000L, windows[0])
    }

    @Test
    fun `an out-of-range or missing answer falls back to 30 minutes and the limits themselves are accepted`() {
        val answers = listOf(0L, -1L, 59_999L, 259_200_001L, Long.MAX_VALUE, Long.MIN_VALUE, 60_000L, 259_200_000L)
        var next = 0
        val orchestrator = orchestrator(provider = { answers[next++] })
        for ((index, answer) in answers.withIndex()) {
            deliver(orchestrator, "fallback-t-$index")
            waitFor("follow $index") { windows.size == index + 1 }
            val expected = if (answer in 60_000L..259_200_000L) answer else 1_800_000L
            assertEquals("answer $answer", expected, windows[index])
        }
    }

    @Test
    fun `without a provider every follow uses the fixed window, as the older tests do`() {
        val orchestrator = orchestrator(fixed = 1_500)
        deliver(orchestrator, "fixed-turn-1")
        waitFor("follow") { windows.size == 1 }
        assertEquals(1_500L, windows[0])
    }

    @Test
    fun `Stop, supersession and a dropped connection each still end a long follow and say why`() {
        val orchestrator = orchestrator(provider = { 259_200_000L })
        deliver(orchestrator, "end-stopped-1")
        deliver(orchestrator, "end-super-0001")
        deliver(orchestrator, "end-drop-00001")
        waitFor("three follows") { windows.size == 3 && ends.size == 3 }
        assertTrue("three days is long, none ended by itself", ended.isEmpty())
        ends.getValue("end-super-0001").complete(LaterEnd.SUPERSEDED)
        ends.getValue("end-drop-00001").complete(LaterEnd.DISCONNECTED)
        waitFor("those two ended") { ended.size == 2 }
        assertTrue(ended.contains("end-super-0001:superseded"))
        assertTrue(ended.contains("end-drop-00001:disconnected"))
        orchestrator.stopFollowing()
        waitFor("stopped") { ended.contains("end-stopped-1:stopped") }
        assertEquals(3, ended.size)
        assertEquals(3, windows.size)
    }

    @Test
    fun `switching the opt-in off ends a follow that has a long window`() {
        var enabled = true
        val orchestrator = orchestrator(enabled = { enabled }, provider = { 259_200_000L })
        deliver(orchestrator, "off-live-0001")
        waitFor("following") { windows.size == 1 }
        enabled = false
        orchestrator.stopFollowing()
        waitFor("ended off") { ended.contains("off-live-0001:off") }
    }
}
