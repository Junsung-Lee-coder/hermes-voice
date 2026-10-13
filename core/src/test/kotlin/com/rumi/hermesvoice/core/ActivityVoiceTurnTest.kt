package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.activity.ActivityEvent
import com.rumi.hermesvoice.core.activity.ActivityFeed
import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import com.rumi.hermesvoice.core.net.HermesConversationPort
import com.rumi.hermesvoice.core.net.HermesSpeechGateway
import com.rumi.hermesvoice.core.net.OutgoingAttachment
import com.rumi.hermesvoice.core.net.SubmittedTurn
import com.rumi.hermesvoice.core.voice.DestinationAllowlist
import com.rumi.hermesvoice.core.voice.DestinationEntry
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.RecipientEvent
import com.rumi.hermesvoice.core.voice.VoiceTurnConfig
import com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The voice path (Phone voice, and a Watch-origin turn run by the Phone's orchestrator): the same replace-in-place slot, tied
 * to the DESTINATION conversation and the turn, and never a source of speech. Production [VoiceTurnOrchestrator]; the gateway and
 * speaker are scripted doubles (not a live Hermes or a real speaker).
 */
class ActivityVoiceTurnTest {
    private val scripts = ConcurrentHashMap<String, Channel<RecipientEvent>>()
    private val plays = Collections.synchronizedList(mutableListOf<String>())
    private val seen = CopyOnWriteArrayList<ActivityEvent>()
    private val feed = ActivityFeed()
    private val allowlist = DestinationAllowlist.create(
        listOf(DestinationEntry("work", "work_session"), DestinationEntry("home", "home_session")), "router_session")

    private val speech = object : HermesSpeechGateway {
        override suspend fun transcribe(audio: ByteArray, mimeType: String) = String(audio).removePrefix("SAY:")
        override suspend fun speak(text: String) = SpokenAudio("AUDIO:$text".toByteArray(), "audio/mpeg")
    }

    private inner class Scripted(val events: Channel<RecipientEvent>) : SubmittedTurn {
        override val submitStatus = "streaming"
        override val attributable = true
        override suspend fun collect(timeoutMs: Long, onEvent: suspend (RecipientEvent) -> Unit) {
            for (event in events) {
                onEvent(event)
                if (event is RecipientEvent.Complete) return
            }
        }
        override fun release() {}
    }

    private val conversations = object : HermesConversationPort {
        override suspend fun create(source: String, title: String, seedInstruction: String, hidden: Boolean) = throw UnsupportedOperationException()
        override suspend fun submit(storedSessionId: String, text: String, attachments: List<OutgoingAttachment>): SubmittedTurn {
            if (storedSessionId == "router_session") {
                val alias = if (text.contains("ZETA")) "home" else "work"
                return Scripted(Channel<RecipientEvent>(Channel.UNLIMITED).apply {
                    trySend(RecipientEvent.Activity("🧭 routing must never be shown"))
                    trySend(RecipientEvent.Complete("""{"destination":"$alias","ack":"Sending to $alias."}""", "complete"))
                })
            }
            return Scripted(scripts.getOrPut(voiceWords(text)) { Channel(Channel.UNLIMITED) })
        }
    }

    private fun orchestrator(playback: ResponsePlaybackSettings = ResponsePlaybackSettings()) =
        VoiceTurnOrchestrator(speech, conversations, inputGate = { _, _ -> AudioInputVerdict.USABLE },
            config = { VoiceTurnConfig("router_session", allowlist, playback) }, activity = feed)

    private fun request(id: String, said: String, origin: VoiceOrigin = VoiceOrigin.PHONE) =
        VoiceTurnRequest(id, origin, "SAY:$said".toByteArray(), "audio/wav", PlaybackSink { audio, cue ->
            plays += "${cue.role}:${String(audio.bytes).removePrefix("AUDIO:")}"
        })

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        try {
            withTimeout(10_000) { while (!condition()) delay(5) }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what; seen=$seen plays=$plays")
        }
    }

    private fun script(text: String) = scripts.getOrPut(text) { Channel(Channel.UNLIMITED) }
    private fun updates(key: String) = seen.filterIsInstance<ActivityEvent.Update>().filter { it.key == key }.map { it.label }

    private fun collecting(scope: CoroutineScope) = scope.launch(start = CoroutineStart.UNDISPATCHED) { feed.events.collect { seen += it } }

    @Test(timeout = 120_000)
    fun `a voice turn opens one slot on its destination, tool frames replace it, and the final ends it without any speech from the frames`(): Unit = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val job = collecting(scope)
        val o = orchestrator()
        val run = scope.async { o.run(request("t1", "work words")) }
        waitFor("begin") { seen.any { it is ActivityEvent.Begin } }
        val begin = seen.filterIsInstance<ActivityEvent.Begin>().single()
        assertEquals("t1", begin.key)
        assertEquals("the destination conversation, never the hidden router", "work_session", begin.sessionId)

        script("work words").send(RecipientEvent.Activity("🐍 Running code"))
        waitFor("first") { updates("t1").size == 1 }
        script("work words").send(RecipientEvent.Activity("🔎 Searching files"))
        waitFor("second") { updates("t1").size == 2 }
        assertEquals(listOf("🐍 Running code", "🔎 Searching files"), updates("t1"))
        assertEquals("🔎 Searching files", feed.snapshot().bubbles.single().label)
        assertTrue("progress frames are never speech: $plays", plays.none { it.contains("Running code") || it.contains("Searching files") })

        script("work words").send(RecipientEvent.Complete("The answer.", "complete"))
        assertTrue(run.await() is VoiceTurnOutcome.Completed)
        waitFor("end") { seen.any { it is ActivityEvent.End } }
        assertEquals(ActivityEvent.End("t1", replied = true), seen.last())
        assertTrue(feed.snapshot().bubbles.isEmpty())
        assertEquals("the final is spoken once, and only it (plus the acknowledgement)", 1, plays.count { it.startsWith("FINAL:") })
        assertTrue(plays.none { it.contains("routing must never") })
        assertTrue(seen.none { it is ActivityEvent.Begin && it.sessionId == "router_session" })
        job.cancel()
    }

    @Test(timeout = 120_000)
    fun `with the first and middle toggles on a tool frame is still no speech while a conversational interim is`(): Unit = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val job = collecting(scope)
        val o = orchestrator(ResponsePlaybackSettings(playFirstResponse = true, playMiddleResponses = true))
        val run = scope.async { o.run(request("t2", "work words")) }
        waitFor("begin") { seen.any { it is ActivityEvent.Begin } }
        script("work words").send(RecipientEvent.Activity("🐍 Running code"))
        waitFor("update") { updates("t2").size == 1 }
        script("work words").send(RecipientEvent.Interim("Looking into it."))
        script("work words").send(RecipientEvent.Activity("📖 Reading"))
        script("work words").send(RecipientEvent.Complete("Done.", "complete"))
        run.await()
        assertTrue("an interim stays speech exactly as before: $plays", plays.contains("FIRST:Looking into it."))
        assertTrue("a tool or status frame never is: $plays", plays.none { it.contains("Running code") || it.contains("Reading") })
        assertEquals(1, plays.count { it.startsWith("FINAL:") })
        job.cancel()
    }

    @Test(timeout = 120_000)
    fun `a turn that ends without a final reply ends its slot as not replied`(): Unit = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val job = collecting(scope)
        val o = orchestrator()
        val run = scope.async { o.run(request("t3", "work words")) }
        waitFor("begin") { seen.any { it is ActivityEvent.Begin } }
        script("work words").send(RecipientEvent.Activity("🐍 Running code"))
        waitFor("update") { updates("t3").size == 1 }
        script("work words").close()
        run.await()
        waitFor("end") { seen.any { it is ActivityEvent.End } }
        assertEquals(ActivityEvent.End("t3", replied = false), seen.last())
        assertTrue(feed.snapshot().bubbles.isEmpty())
        assertTrue("no fake final answer was spoken: $plays", plays.none { it.startsWith("FINAL:") })
        job.cancel()
    }

    @Test(timeout = 120_000)
    fun `a cancelled turn ends its slot and a Watch-origin turn gets its own slot on its destination`(): Unit = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val job = collecting(scope)
        val o = orchestrator()
        val phone = scope.async { o.run(request("p1", "work words")) }
        val watch = scope.async { o.run(request("w1", "ZETA words", VoiceOrigin.WATCH)) }
        waitFor("both begin") { seen.filterIsInstance<ActivityEvent.Begin>().size == 2 }
        val byKey = seen.filterIsInstance<ActivityEvent.Begin>().associate { it.key to it.sessionId }
        assertEquals(mapOf("p1" to "work_session", "w1" to "home_session"), byKey)

        script("ZETA words").send(RecipientEvent.Activity("🌐 Searching the web"))
        waitFor("watch update") { updates("w1").size == 1 }
        assertTrue("the other turn is untouched", updates("p1").isEmpty())

        phone.cancel()
        waitFor("phone end") { seen.any { it is ActivityEvent.End && it.key == "p1" } }
        assertEquals(ActivityEvent.End("p1", replied = false), seen.last { it is ActivityEvent.End && it.key == "p1" })
        assertEquals("the watch turn keeps its own slot", listOf("w1"), feed.snapshot().bubbles.map { it.key })
        script("ZETA words").send(RecipientEvent.Complete("Home done.", "complete"))
        watch.await()
        waitFor("watch end") { seen.any { it is ActivityEvent.End && it.key == "w1" } }
        assertFalse(seen.any { it is ActivityEvent.End && it.key == "w1" && !it.replied })
        job.cancel()
    }
}
