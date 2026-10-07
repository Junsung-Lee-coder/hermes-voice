package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import com.rumi.hermesvoice.core.net.HermesConversationPort
import com.rumi.hermesvoice.core.net.HermesSpeechGateway
import com.rumi.hermesvoice.core.net.OutgoingAttachment
import com.rumi.hermesvoice.core.voice.RecipientEvent
import com.rumi.hermesvoice.core.net.SubmittedTurn
import com.rumi.hermesvoice.core.voice.AudioOwnership
import com.rumi.hermesvoice.core.voice.DestinationAllowlist
import com.rumi.hermesvoice.core.voice.DestinationEntry
import com.rumi.hermesvoice.core.voice.PendingPhase
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.ResponsePlaybackSettings
import com.rumi.hermesvoice.core.SpokenRole
import com.rumi.hermesvoice.core.voice.VoiceTurnConfig
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import com.rumi.hermesvoice.core.voice.VoiceTurnStage
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
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

/**
 * v19: a new voice request is accepted while earlier, independent requests still wait for their replies; each request keeps its
 * own destination, status and reply; requests to the SAME conversation queue (visibly, cancellably, bounded) behind the
 * earlier one; Stop ends exactly the request it targets; a reply never interrupts a recording nor is dropped.
 * Driven through the production [VoiceTurnOrchestrator] with a scripted gateway (a contract double, not a live Hermes).
 */
class WaitWhilePendingTest {
    private val timeline: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val scripts = HashMap<String, Channel<RecipientEvent>>()
    private val released = AtomicInteger()
    private val rawSubmits = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    private val outcomes = Collections.synchronizedMap(HashMap<String, VoiceTurnOutcome>())
    private val stages = Collections.synchronizedList(mutableListOf<String>())
    private val notAdmitted = Collections.synchronizedList(mutableListOf<String>())

    private val allowlist = DestinationAllowlist.create(
        listOf(DestinationEntry("work", "work_session"), DestinationEntry("home", "home_session")), "router_session")

    private val speech = object : HermesSpeechGateway {
        override suspend fun transcribe(audio: ByteArray, mimeType: String) = String(audio).removePrefix("SAY:")
        override suspend fun speak(text: String): SpokenAudio = SpokenAudio("AUDIO:$text".toByteArray(), "audio/mpeg")
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
        override fun release() { released.incrementAndGet() }
    }

    private val conversations = object : HermesConversationPort {
        override suspend fun create(source: String, title: String, seedInstruction: String, hidden: Boolean) = throw UnsupportedOperationException()
        override suspend fun submit(storedSessionId: String, text: String, attachments: List<OutgoingAttachment>): SubmittedTurn {
            if (storedSessionId == "router_session") {
                val alias = if (text.contains("ZETA")) "home" else "work"
                return Scripted(Channel<RecipientEvent>(Channel.UNLIMITED).apply {
                    trySend(RecipientEvent.Complete("""{"destination":"$alias","ack":"Sending to $alias."}""", "complete"))
                })
            }
            val original = voiceWords(text)
            rawSubmits += storedSessionId to text
            timeline += "submit:$storedSessionId:$original"
            return Scripted(scripts.getOrPut(original) { Channel(Channel.UNLIMITED) })
        }
    }

    private val listener = object : VoiceTurnListener {
        override fun onStage(turnId: String, stage: VoiceTurnStage) { stages += "$turnId:$stage" }
        override fun onNotAdmitted(turnId: String, origin: VoiceOrigin, reason: String) { notAdmitted += "$turnId:$reason" }
        override fun onOutcome(turnId: String, outcome: VoiceTurnOutcome) { outcomes[turnId] = outcome }
    }

    private fun sink(device: VoiceOrigin, hold: CompletableDeferred<Unit>? = null, holdRole: SpokenRole? = null) = PlaybackSink { audio, cue ->
        timeline += "play:${device.name.lowercase()}:${cue.role}:${String(audio.bytes).removePrefix("AUDIO:")}"
        if (hold != null && cue.role == holdRole) {
            try { hold.await() } catch (c: CancellationException) { timeline += "stopped:${cue.text}"; throw c }
        }
    }

    private val ownership = AudioOwnership()

    private fun orchestrator(maxPending: Int = VoiceTurnOrchestrator.MAX_PENDING_TURNS, perSession: Int = VoiceTurnOrchestrator.SESSION_QUEUE_MAX) =
        VoiceTurnOrchestrator(speech, conversations, inputGate = { _, _ -> AudioInputVerdict.USABLE },
            config = { VoiceTurnConfig("router_session", allowlist, ResponsePlaybackSettings()) }, listener = listener,
            ownership = ownership, maxPendingTurns = maxPending, maxPerSession = perSession)

    private fun request(id: String, said: String, origin: VoiceOrigin = VoiceOrigin.PHONE, sink: PlaybackSink = sink(origin)) =
        VoiceTurnRequest(id, origin, "SAY:$said".toByteArray(), "audio/wav", sink)

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        withTimeout(10_000) { while (!condition()) delay(5) }
        assertTrue(what, condition())
    }

    private fun script(text: String) = scripts.getOrPut(text) { Channel(Channel.UNLIMITED) }

    private fun phases(o: VoiceTurnOrchestrator) = o.pending.value.associate { it.turnId to it.phase }

    @Test(timeout = 120_000)
    fun `a new request to another conversation is accepted and completes while the first still awaits its reply`() = runBlocking {
        val o = orchestrator()
        val first = async { o.run(request("t1", "work words")) }
        waitFor("first awaiting") { phases(o)["t1"] == PendingPhase.AWAITING }
        val second = async { o.run(request("t2", "ZETA words", VoiceOrigin.WATCH)) }
        waitFor("second awaiting while the first still waits") { phases(o)["t2"] == PendingPhase.AWAITING }
        assertEquals("both are pending, the first untouched", mapOf("t1" to PendingPhase.AWAITING, "t2" to PendingPhase.AWAITING), phases(o))
        script("ZETA words").send(RecipientEvent.Complete("home final", "complete"))
        assertTrue(second.await() is VoiceTurnOutcome.Completed)
        assertFalse("the older request is neither cancelled nor finished", first.isCompleted)
        script("work words").send(RecipientEvent.Complete("work final", "complete"))
        val firstOutcome = first.await()
        assertTrue("$firstOutcome", firstOutcome is VoiceTurnOutcome.Completed)
        assertEquals(1, timeline.count { it == "submit:work_session:work words" })
        // Where audio plays is the latest accepted voice sender's device (the Watch request was accepted second), for the
        // older Phone request's reply too: the route is decided by acceptance, not by who asked first. Each ACK played on
        // the device that was the target when it was spoken.
        assertEquals(listOf("play:phone:ACK:Sending to work.", "play:watch:ACK:Sending to home."), timeline.filter { it.contains(":ACK:") })
        assertEquals("the older reply is spoken, not silently dropped, and nothing final plays on the Phone",
            listOf("play:watch:FINAL:home final", "play:watch:FINAL:work final"), timeline.filter { it.contains(":FINAL:") })
        assertTrue(o.pending.value.isEmpty())
    }

    @Test(timeout = 120_000)
    fun `a second request to the same conversation is queued visibly, sent only after the first is answered, in order`() = runBlocking {
        val o = orchestrator()
        val first = async { o.run(request("t1", "work one")) }
        waitFor("first awaiting") { phases(o)["t1"] == PendingPhase.AWAITING }
        val second = async { o.run(request("t2", "work two")) }
        waitFor("second queued") { phases(o)["t2"] == PendingPhase.QUEUED }
        assertTrue("queued status is reported", stages.contains("t2:${VoiceTurnStage.QUEUED}"))
        delay(300)
        assertFalse("nothing is sent for it while the earlier request is unanswered", timeline.any { it == "submit:work_session:work two" })
        script("work one").send(RecipientEvent.Complete("one done", "complete"))
        assertTrue(first.await() is VoiceTurnOutcome.Completed)
        waitFor("second sent after the first was answered") { timeline.contains("submit:work_session:work two") }
        script("work two").send(RecipientEvent.Complete("two done", "complete"))
        assertTrue(second.await() is VoiceTurnOutcome.Completed)
        assertEquals("both replies spoken once, in request order", listOf("play:phone:FINAL:one done", "play:phone:FINAL:two done"), timeline.filter { it.contains(":FINAL:") })
        assertEquals(listOf("submit:work_session:work one", "submit:work_session:work two"), timeline.filter { it.startsWith("submit:") })
    }

    @Test(timeout = 120_000)
    fun `cancelling a queued request sends nothing for it and leaves the earlier request and a later queued one alone`() = runBlocking {
        val o = orchestrator(perSession = 3)
        val first = async { o.run(request("t1", "work one")) }
        waitFor("first awaiting") { phases(o)["t1"] == PendingPhase.AWAITING }
        val second = async { o.run(request("t2", "work two")) }
        waitFor("second queued") { phases(o)["t2"] == PendingPhase.QUEUED }
        val third = async { o.run(request("t3", "work three")) }
        waitFor("third queued") { phases(o)["t3"] == PendingPhase.QUEUED }
        assertTrue(o.stopTurn("t2"))
        assertEquals(VoiceTurnOutcome.Stopped, second.await())
        assertFalse(o.stopTurn("t2"))
        assertFalse(first.isCompleted)
        script("work one").send(RecipientEvent.Complete("one done", "complete"))
        assertTrue(first.await() is VoiceTurnOutcome.Completed)
        waitFor("third sent next") { timeline.contains("submit:work_session:work three") }
        assertFalse(timeline.contains("submit:work_session:work two"))
        script("work three").send(RecipientEvent.Complete("three done", "complete"))
        assertTrue(third.await() is VoiceTurnOutcome.Completed)
    }

    @Test(timeout = 120_000)
    fun `a full conversation line refuses visibly and loses none of the queued work`() = runBlocking {
        assertEquals("by default one request is active and ONE waits behind it", 2, VoiceTurnOrchestrator.SESSION_QUEUE_MAX)
        val o = orchestrator()
        val first = async { o.run(request("t1", "work one")) }
        waitFor("first awaiting") { phases(o)["t1"] == PendingPhase.AWAITING }
        val second = async { o.run(request("t2", "work two")) }
        waitFor("second queued") { phases(o)["t2"] == PendingPhase.QUEUED }
        val third = o.run(request("t3", "work three"))
        third as VoiceTurnOutcome.NotDelivered
        assertEquals(VoiceTurnOrchestrator.QUEUE_FULL, third.reason)
        assertEquals(VoiceTurnStage.QUEUED, third.stage)
        assertFalse("the refused request was refused BEFORE submission", rawSubmits.any { it.second.startsWith("work three") })
        script("work one").send(RecipientEvent.Complete("one", "complete"))
        assertTrue(first.await() is VoiceTurnOutcome.Completed)
        waitFor("second sent") { timeline.contains("submit:work_session:work two") }
        script("work two").send(RecipientEvent.Complete("two", "complete"))
        assertTrue(second.await() is VoiceTurnOutcome.Completed)
        assertTrue("the refused request left no pending entry", o.pending.value.isEmpty())
    }

    @Test(timeout = 120_000)
    fun `the pending bound refuses a new request before acceptance and it never becomes the playback target`() = runBlocking {
        val o = orchestrator(maxPending = 2)
        val a = async { o.run(request("t1", "work one")) }
        val b = async { o.run(request("t2", "ZETA one", VoiceOrigin.WATCH)) }
        waitFor("two awaiting") { phases(o).values.count { it == PendingPhase.AWAITING } == 2 }
        val refused = o.run(request("t3", "work two", VoiceOrigin.PHONE))
        assertEquals(VoiceTurnOutcome.NotAdmitted(VoiceTurnOrchestrator.PENDING_LIMIT), refused)
        assertTrue(notAdmitted.contains("t3:${VoiceTurnOrchestrator.PENDING_LIMIT}"))
        assertEquals("playback stays with the device of the latest accepted request", VoiceOrigin.WATCH, o.playbackRoute.current()?.device)
        script("work one").send(RecipientEvent.Complete("x", "complete"))
        script("ZETA one").send(RecipientEvent.Complete("y", "complete"))
        assertTrue(a.await() is VoiceTurnOutcome.Completed)
        assertTrue(b.await() is VoiceTurnOutcome.Completed)
        val again = async { o.run(request("t4", "work three")) }
        waitFor("t4 awaiting") { phases(o)["t4"] == PendingPhase.AWAITING }
        script("work three").send(RecipientEvent.Complete("z", "complete"))
        assertTrue("capacity returns when requests finish", again.await() is VoiceTurnOutcome.Completed)
    }

    @Test(timeout = 120_000)
    fun `Stop on one waiting request ends only that request, releases it once, and the other still completes`() = runBlocking {
        val o = orchestrator()
        val a = async { o.run(request("t1", "work one")) }
        val b = async { o.run(request("t2", "ZETA one", VoiceOrigin.WATCH)) }
        waitFor("both awaiting") { phases(o).values.count { it == PendingPhase.AWAITING } == 2 }
        val before = released.get()
        assertTrue(o.stopTurn("t1"))
        assertEquals(VoiceTurnOutcome.Stopped, a.await())
        assertEquals("its subscription was released exactly once", 1, released.get() - before)
        assertFalse(b.isCompleted)
        assertEquals(listOf("t2"), o.pending.value.map { it.turnId })
        script("ZETA one").send(RecipientEvent.Complete("home final", "complete"))
        assertTrue(b.await() is VoiceTurnOutcome.Completed)
        assertFalse("nothing is spoken for the stopped request", timeline.any { it.contains("work one") && it.startsWith("play:") })
        assertEquals(VoiceTurnOutcome.Stopped, outcomes["t1"])
        assertFalse("an unknown or finished request is not stoppable", o.stopTurn("t1"))
    }

    @Test(timeout = 120_000)
    fun `an older reply arriving while a recording holds the microphone waits, never plays over it, and is not dropped`() = runBlocking {
        val o = orchestrator()
        val a = async { o.run(request("t1", "work one")) }
        waitFor("awaiting") { phases(o)["t1"] == PendingPhase.AWAITING }
        val recording = ownership.claimMicrophone(VoiceOrigin.PHONE)
        script("work one").send(RecipientEvent.Complete("older final", "complete"))
        delay(500)
        assertFalse("not spoken over the recording", timeline.contains("play:phone:FINAL:older final"))
        assertFalse(a.isCompleted)
        recording.release()
        assertTrue(a.await() is VoiceTurnOutcome.Completed)
        assertTrue(timeline.contains("play:phone:FINAL:older final"))
    }

    @Test(timeout = 120_000)
    fun `a reply that arrives while another audio plays waits for it instead of interrupting`() = runBlocking {
        val o = orchestrator()
        val hold = CompletableDeferred<Unit>()
        val a = async { o.run(request("t1", "work one")) }
        waitFor("a awaiting") { phases(o)["t1"] == PendingPhase.AWAITING }
        val b = async { o.run(request("t2", "ZETA one", VoiceOrigin.PHONE, sink(VoiceOrigin.PHONE, hold, SpokenRole.FINAL))) }
        waitFor("b awaiting") { phases(o)["t2"] == PendingPhase.AWAITING }
        script("ZETA one").send(RecipientEvent.Complete("b final", "complete"))
        waitFor("b's final is playing") { timeline.contains("play:phone:FINAL:b final") }
        script("work one").send(RecipientEvent.Complete("a final", "complete"))
        delay(400)
        assertFalse("not interrupted", timeline.any { it.startsWith("stopped:") })
        assertFalse("not spoken over the playing reply", timeline.contains("play:phone:FINAL:a final"))
        hold.complete(Unit)
        assertTrue(b.await() is VoiceTurnOutcome.Completed)
        assertTrue(a.await() is VoiceTurnOutcome.Completed)
        assertTrue(timeline.indexOf("play:phone:FINAL:b final") < timeline.indexOf("play:phone:FINAL:a final"))
    }

    @Test(timeout = 120_000)
    fun `a long reply is spoken in ordered pieces with nothing lost or repeated`() = runBlocking {
        val o = orchestrator()
        val long = ("Here is a fairly long sentence for the long reply test. ").repeat(100)
        val a = async { o.run(request("t1", "work one")) }
        waitFor("awaiting") { phases(o)["t1"] == PendingPhase.AWAITING }
        script("work one").send(RecipientEvent.Complete(long, "complete"))
        assertTrue(a.await() is VoiceTurnOutcome.Completed)
        val spoken = timeline.filter { it.startsWith("play:phone:FINAL:") }.joinToString("") { it.removePrefix("play:phone:FINAL:") }
        assertEquals("exact text and order, pieces joined with nothing added (only the reply's outer whitespace is trimmed, as before)", long.trim(), spoken)
        assertTrue("it was split into several requests", timeline.count { it.startsWith("play:phone:FINAL:") } > 1)
    }

    @Test(timeout = 120_000)
    fun `a voice request reaches its final recipient as the voice marker plus the original words only, and the router never sees it`() = runBlocking {
        val o = orchestrator()
        val a = async { o.run(request("t1", "work one")) }
        waitFor("awaiting") { phases(o)["t1"] == PendingPhase.AWAITING }
        script("work one").send(RecipientEvent.Complete("done", "complete"))
        val outcome = a.await() as VoiceTurnOutcome.Completed
        assertEquals("the canonical original is kept in the route", "work one", outcome.route.originalTranscript)
        val toWork = rawSubmits.filter { it.first == "work_session" }
        assertEquals("exactly one recipient submit, no second turn", 1, toWork.size)
        assertEquals(VOICE_MARK + "work one", toWork.single().second)
        assertEquals("the router's prompt carries no marker", emptyList<String>(), rawSubmits.filter { it.first == "router_session" }.map { it.second })
        assertFalse("no scaffold text follows the words", toWork.single().second.contains("\n\n"))
    }

    @Test(timeout = 120_000)
    fun `the voice marker is per request and never sticks - a request not sent by voice carries the words alone`() = runBlocking {
        val o = orchestrator()
        val voice = async { o.run(request("t1", "work one")) }
        waitFor("awaiting") { phases(o)["t1"] == PendingPhase.AWAITING }
        val typed = async {
            o.run(VoiceTurnRequest("t2", VoiceOrigin.WATCH, "SAY:ZETA typed".toByteArray(), "audio/wav", sink(VoiceOrigin.WATCH), sentByVoice = false))
        }
        waitFor("both awaiting") { phases(o)["t2"] == PendingPhase.AWAITING }
        assertEquals(VOICE_MARK + "work one", rawSubmits.single { it.first == "work_session" }.second)
        assertEquals("ZETA typed", rawSubmits.single { it.first == "home_session" }.second)
        script("work one").send(RecipientEvent.Complete("a", "complete"))
        script("ZETA typed").send(RecipientEvent.Complete("b", "complete"))
        assertTrue(voice.await() is VoiceTurnOutcome.Completed)
        assertTrue(typed.await() is VoiceTurnOutcome.Completed)
    }

    @Test(timeout = 120_000)
    fun `queued same-conversation requests each carry their own marker and words when finally sent, and a detail request is untouched`() = runBlocking {
        val o = orchestrator()
        val first = async { o.run(request("t1", "work one")) }
        waitFor("first awaiting") { phases(o)["t1"] == PendingPhase.AWAITING }
        val second = async { o.run(request("t2", "자세히 설명해 줘")) }
        waitFor("second queued") { phases(o)["t2"] == PendingPhase.QUEUED }
        assertEquals("nothing for the queued request was sent yet", 1, rawSubmits.count { it.first == "work_session" })
        script("work one").send(RecipientEvent.Complete("one", "complete"))
        assertTrue(first.await() is VoiceTurnOutcome.Completed)
        waitFor("second sent") { rawSubmits.count { it.first == "work_session" } == 2 }
        assertEquals(VOICE_MARK + "자세히 설명해 줘", rawSubmits.last { it.first == "work_session" }.second)
        script("자세히 설명해 줘").send(RecipientEvent.Complete("a very long and detailed explanation", "complete"))
        assertTrue(second.await() is VoiceTurnOutcome.Completed)
        assertTrue("the reply is spoken whole, not shortened by the client", timeline.contains("play:phone:FINAL:a very long and detailed explanation"))
    }
}
