package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import com.rumi.hermesvoice.core.net.HermesConversationPort
import com.rumi.hermesvoice.core.net.HermesSpeechGateway
import com.rumi.hermesvoice.core.net.OutgoingAttachment
import com.rumi.hermesvoice.core.net.SubmittedTurn
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.voice.AudioOwnership
import com.rumi.hermesvoice.core.voice.DestinationAllowlist
import com.rumi.hermesvoice.core.voice.DestinationEntry
import com.rumi.hermesvoice.core.voice.PendingPhase
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.RecipientEvent
import com.rumi.hermesvoice.core.voice.TtsBatcher
import com.rumi.hermesvoice.core.voice.VoiceTurnConfig
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import com.rumi.hermesvoice.core.wake.WakeArmInputs
import com.rumi.hermesvoice.core.wake.WakeDeviceController
import com.rumi.hermesvoice.core.wake.WakeDevicePort
import com.rumi.hermesvoice.core.wake.WakeRecognizerPort
import com.rumi.hermesvoice.core.wake.WakeTimerPort
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v19 self-check R1, B3: while the next chunk of a long reply is only being SYNTHESIZED, nothing is audible, so no device
 * speaker, speaker slot or wake gating is held: the real speaker-active signal is false, the real Phone wake controller
 * arms and accepts a new request, a recording's microphone claim does not cancel the reply, a new request's acknowledgement
 * plays, and the reply continues (never replaying a confirmed chunk) once its chunk arrived and the speaker and microphone
 * are free. Production [VoiceTurnOrchestrator], real [AudioOwnership] and real [WakeDeviceController]; the gateway,
 * conversation and device sinks are scripted doubles (this is not a live Hermes or a real speaker).
 */
class ChunkedReplyGapTest {
    private val timeline: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val scripts = ConcurrentHashMap<String, Channel<RecipientEvent>>()
    private val rawSubmits = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    private val outcomes = Collections.synchronizedMap(HashMap<String, VoiceTurnOutcome>())
    private val synthStarted = Collections.synchronizedList(mutableListOf<String>())
    private val synthCancelled = Collections.synchronizedList(mutableListOf<String>())
    private val synthGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val playHolds = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val micClaimedAtPlay = Collections.synchronizedList(mutableListOf<String>())
    private val signalAtPlay = Collections.synchronizedList(mutableListOf<Boolean>())

    private val speakerOn = AtomicInteger()
    private val speakerOff = AtomicInteger()
    private val watchOn = AtomicInteger()
    private val workEntered = AtomicInteger()
    private val workExited = AtomicInteger()

    private fun phoneSpeakerActive() = speakerOn.get() - speakerOff.get() > 0

    private val allowlist = DestinationAllowlist.create(
        listOf(DestinationEntry("work", "work_session"), DestinationEntry("home", "home_session")), "router_session")

    private val longReply = (1..60).joinToString("") { "Sentence number $it is long enough to matter here. " }
    private val chunks = TtsBatcher.split(longReply.trim())

    init {
        synthGates[chunks[1]] = CompletableDeferred()
    }

    private val speech = object : HermesSpeechGateway {
        override suspend fun transcribe(audio: ByteArray, mimeType: String) = String(audio).removePrefix("SAY:")
        override suspend fun speak(text: String): SpokenAudio {
            synthStarted += text
            try {
                synthGates[text]?.await()
            } catch (stopped: CancellationException) {
                synthCancelled += text
                throw stopped
            }
            return SpokenAudio("AUDIO:$text".toByteArray(), "audio/mpeg")
        }
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
                    trySend(RecipientEvent.Complete("""{"destination":"$alias","ack":"Sending to $alias."}""", "complete"))
                })
            }
            val original = voiceWords(text)
            rawSubmits += storedSessionId to text
            return Scripted(scripts.getOrPut(original) { Channel(Channel.UNLIMITED) })
        }
    }

    private val listener = object : VoiceTurnListener {
        override fun onOutcome(turnId: String, outcome: VoiceTurnOutcome) { outcomes[turnId] = outcome }
    }

    private val ownership = AudioOwnership()

    private fun sink(device: VoiceOrigin) = PlaybackSink { audio, cue ->
        val text = String(audio.bytes).removePrefix("AUDIO:")
        timeline += "play:${device.name.lowercase()}:${cue.role}:$text"
        if (cue.role == SpokenRole.FINAL) {
            signalAtPlay += phoneSpeakerActive()
            micClaimedAtPlay += "${ownership.microphoneClaimed(VoiceOrigin.PHONE)}"
        }
        playHolds[text]?.await()
    }

    private fun orchestrator(maxPending: Int = VoiceTurnOrchestrator.MAX_PENDING_TURNS) =
        VoiceTurnOrchestrator(speech, conversations, inputGate = { _, _ -> AudioInputVerdict.USABLE },
            config = { VoiceTurnConfig("router_session", allowlist, ResponsePlaybackSettings()) }, listener = listener,
            ownership = ownership, maxPendingTurns = maxPending,
            laterWork = { work ->
                workEntered.incrementAndGet()
                try { work() } finally { workExited.incrementAndGet() }
            },
            laterSpeaker = { device, holding ->
                if (device == VoiceOrigin.PHONE) {
                    if (holding) speakerOn.incrementAndGet() else speakerOff.incrementAndGet()
                } else if (holding) watchOn.incrementAndGet()
            })

    private fun request(id: String, said: String, origin: VoiceOrigin = VoiceOrigin.PHONE) =
        VoiceTurnRequest(id, origin, "SAY:$said".toByteArray(), "audio/wav", sink(origin))

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        try {
            withTimeout(10_000) { while (!condition()) delay(5) }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what (timeline=$timeline on=${speakerOn.get()} off=${speakerOff.get()})")
        }
    }

    private fun script(text: String) = scripts.getOrPut(text) { Channel(Channel.UNLIMITED) }
    private fun phases(o: VoiceTurnOrchestrator) = o.pending.value.associate { it.turnId to it.phase }
    private fun finals() = timeline.filter { it.contains(":FINAL:") }

    private fun expectedFinals(device: String = "phone") = chunks.map { "play:$device:FINAL:$it" }

    /** The Phone's wake flow, fed the way the adapters feed it: the speaker signal is its `talkIdle`. */
    private class PhoneWake(private val busy: () -> Boolean) : WakeRecognizerPort, WakeTimerPort, WakeDevicePort {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val controller = WakeDeviceController(VoiceOrigin.PHONE, this, this, this, { 0L }, WatchSettings(WakeLocation.PHONE, "루미", vadSilenceSeconds = 2.0, revision = 1))
        override fun available() = true
        override fun start(generation: Long): Boolean { calls += "listen:$generation"; return true }
        override fun release() { calls += "release" }
        override fun schedule(delayMs: Long) {}
        override fun cancel() {}
        override fun windowChanged(open: Boolean) { calls += "window:$open" }
        override fun scheduleHandoff(delayMs: Long) { calls += "handoff_in:$delayMs" }
        override fun cancelHandoff() {}
        override fun startRequestCapture(silenceMs: Long): Boolean { calls += "capture:$silenceMs"; return true }
        override fun cancelRequestCapture(reason: String) { calls += "cancel_capture:$reason" }
        override fun sendRecognized(request: String) { calls += "send:$request" }
        override fun closed(reason: String) { calls += "closed:$reason" }
        override fun armInputs() = WakeArmInputs(enabled = true, resumed = true, interactive = true, ambient = false, permission = true,
            microphoneMuted = false, talkIdle = !busy(), phoneReachable = true, nowMs = 0, cooldownUntilMs = 0, generation = 0,
            lastArmedGeneration = null)

        fun listened() = calls.count { it.startsWith("listen:") }

        /** What the armed standby loop does when the speaker signal changes: close on busy, open the next window (a new generation) on idle. */
        fun sync() { if (busy()) controller.onBusy() else controller.rearm("idle") }
    }

    private suspend fun startLongReply(o: VoiceTurnOrchestrator, id: String = "t1", said: String = "work words"): kotlinx.coroutines.Deferred<VoiceTurnOutcome> {
        val run = CoroutineScope(Dispatchers.Default).async { o.run(request(id, said)) }
        waitFor("$id awaiting") { phases(o)[id] == PendingPhase.AWAITING }
        script(said).send(RecipientEvent.Complete(longReply, "complete"))
        return run
    }

    /** Parks the reply in the gap: the first clip was played to the end, the second is still being synthesized. */
    private suspend fun parkInGap(o: VoiceTurnOrchestrator) {
        assertTrue("a long reply has several chunks", chunks.size >= 3)
        waitFor("the first clip played") { timeline.contains("play:phone:FINAL:${chunks[0]}") }
        waitFor("the first clip's speaker released") { speakerOff.get() >= 1 }
        waitFor("the second chunk's synthesis requested") { synthStarted.contains(chunks[1]) }
        delay(300)
    }

    private fun assertGap(o: VoiceTurnOrchestrator, turn: String = "t1") {
        assertFalse("no audio plays, so the real speaker signal is false (on=${speakerOn.get()} off=${speakerOff.get()})", phoneSpeakerActive())
        assertNull("no speaker slot is held", ownership.later)
        assertEquals("no CPU-holding unit runs while only the server generates", 0, workEntered.get() - workExited.get())
        assertEquals("the request is awaiting its reply's audio, not speaking it", PendingPhase.AWAITING, phases(o)[turn])
        assertFalse("chunk 2 is not played before it exists", timeline.contains("play:phone:FINAL:${chunks[1]}"))
    }

    @Test(timeout = 120_000)
    fun `while the second chunk is only synthesized the speaker signal is false and the Phone wake flow arms and accepts a request`() = runBlocking {
        val o = orchestrator()
        val wake = PhoneWake { phoneSpeakerActive() }
        playHolds[chunks[0]] = CompletableDeferred()
        wake.controller.onResume()
        assertEquals("idle: it listens", 1, wake.listened())
        val run = startLongReply(o)
        waitFor("the first clip is audible") { timeline.contains("play:phone:FINAL:${chunks[0]}") }
        assertTrue("while a clip really plays the signal is true", phoneSpeakerActive())
        wake.sync()
        assertTrue("a playing clip closes the wake window", wake.calls.contains("closed:busy"))
        playHolds.getValue(chunks[0]).complete(Unit)
        waitFor("the first clip's speaker released") { speakerOff.get() >= 1 }
        waitFor("the second chunk's synthesis requested") { synthStarted.contains(chunks[1]) }
        delay(300)
        assertGap(o)
        val before = wake.listened()
        wake.sync()
        assertEquals("the wake flow listens again during the synthesis gap", before + 1, wake.listened())
        wake.controller.qaSecondUtterance()
        wake.controller.onResults(wake.controller.generation, listOf("루미 불 꺼"), final = true)
        assertTrue("a new wake request is accepted: ${wake.calls}", wake.calls.any { it.startsWith("handoff_in") || it.startsWith("send:") })
        synthGates.getValue(chunks[1]).complete(Unit)
        val outcome = run.await()
        assertTrue("$outcome", outcome is VoiceTurnOutcome.Completed)
        assertEquals("each chunk exactly once, in order, never replaying a confirmed one", expectedFinals(), finals())
        assertEquals("the speaker was held exactly once per clip", chunks.size, speakerOn.get())
        assertEquals(chunks.size, speakerOff.get())
        assertTrue("the signal was true during every clip: $signalAtPlay", signalAtPlay.all { it })
        assertEquals("every unit of CPU-holding work was released", workEntered.get(), workExited.get())
        assertTrue(o.pending.value.isEmpty())
    }

    @Test(timeout = 120_000)
    fun `a recording started during the gap is not cut, the late chunk waits for it and then plays once`() = runBlocking {
        val o = orchestrator()
        val run = startLongReply(o)
        parkInGap(o)
        assertGap(o)
        val claim = ownership.claimMicrophone(VoiceOrigin.PHONE)
        val opened = CompletableDeferred<Boolean>()
        claim.whenSpeakerStopped(CoroutineScope(Dispatchers.Default)) { opened.complete(it) }
        assertTrue("nothing was audible, so the microphone may open at once", withTimeout(5_000) { opened.await() })
        synthGates.getValue(chunks[1]).complete(Unit)
        delay(500)
        assertFalse("the chunk that arrived during the recording defers instead of overlapping it", timeline.contains("play:phone:FINAL:${chunks[1]}"))
        assertFalse("the reply was not given up", run.isCompleted)
        assertTrue(phases(o)["t1"] == PendingPhase.AWAITING)
        claim.release()
        val outcome = run.await()
        assertTrue("$outcome", outcome is VoiceTurnOutcome.Completed)
        assertEquals(expectedFinals(), finals())
        assertTrue("no clip was ever audible with the microphone claimed: $micClaimedAtPlay", micClaimedAtPlay.all { it == "false" })
        assertEquals(chunks.size, speakerOn.get())
        assertEquals(chunks.size, speakerOff.get())
    }

    @Test(timeout = 120_000)
    fun `a new request's acknowledgement plays during the gap and the earlier reply is neither lost nor reordered`() = runBlocking {
        val o = orchestrator()
        val run = startLongReply(o)
        parkInGap(o)
        assertGap(o)
        val second = CoroutineScope(Dispatchers.Default).async { o.run(request("t2", "ZETA words")) }
        waitFor("the new request's acknowledgement is spoken while the earlier reply's synthesis is still pending") {
            timeline.contains("play:phone:ACK:Sending to home.")
        }
        assertFalse(run.isCompleted)
        assertFalse("the held reply was not stopped by the acknowledgement: $synthCancelled", synthCancelled.isNotEmpty())
        waitFor("second awaiting") { phases(o)["t2"] == PendingPhase.AWAITING }
        assertEquals("the earlier request keeps its status", PendingPhase.AWAITING, phases(o)["t1"])
        script("ZETA words").send(RecipientEvent.Complete("home final", "complete"))
        synthGates.getValue(chunks[1]).complete(Unit)
        assertTrue(run.await() is VoiceTurnOutcome.Completed)
        assertTrue(second.await() is VoiceTurnOutcome.Completed)
        assertEquals("the earlier reply completes first, exactly once, then the newer one",
            expectedFinals() + "play:phone:FINAL:home final", finals())
        assertEquals(chunks.size + 1, speakerOn.get())
        assertEquals(speakerOn.get(), speakerOff.get())
    }

    @Test(timeout = 120_000)
    fun `a newer Watch request during the gap moves only the unplayed chunks to the Watch and never replays a played one`() = runBlocking {
        val o = orchestrator()
        val run = startLongReply(o)
        parkInGap(o)
        val watch = CoroutineScope(Dispatchers.Default).async { o.run(request("t2", "ZETA words", VoiceOrigin.WATCH)) }
        waitFor("the Watch acknowledgement") { timeline.contains("play:watch:ACK:Sending to home.") }
        // The original is submitted (and its script channel created) on the orchestrator's thread after the acknowledgement; the test must not race it.
        waitFor("the Watch request awaiting") { phases(o)["t2"] == PendingPhase.AWAITING }
        script("ZETA words").send(RecipientEvent.Complete("home final", "complete"))
        synthGates.getValue(chunks[1]).complete(Unit)
        assertTrue(run.await() is VoiceTurnOutcome.Completed)
        assertTrue(watch.await() is VoiceTurnOutcome.Completed)
        assertEquals("the first chunk was played once on the Phone", 1, timeline.count { it == "play:phone:FINAL:${chunks[0]}" })
        assertEquals("the rest follow the newest sender's device, each once, in order",
            chunks.drop(1).map { "play:watch:FINAL:$it" } + "play:watch:FINAL:home final", finals().drop(1))
        assertEquals("the Phone's speaker was held for the one Phone clip only", 1, speakerOn.get())
        assertEquals(1, speakerOff.get())
    }

    @Test(timeout = 120_000)
    fun `Stop during the gap cancels the synthesis once, releases everything and leaves another request alone`() = runBlocking {
        val o = orchestrator()
        val run = startLongReply(o)
        parkInGap(o)
        val other = CoroutineScope(Dispatchers.Default).async { o.run(request("t2", "ZETA words")) }
        waitFor("the other request awaiting") { phases(o)["t2"] == PendingPhase.AWAITING }
        assertTrue(o.stopTurn("t1"))
        assertEquals(VoiceTurnOutcome.Stopped, run.await())
        waitFor("the held synthesis cancelled") { synthCancelled.contains(chunks[1]) }
        assertEquals("cancelled exactly once", 1, synthCancelled.count { it == chunks[1] })
        assertEquals(1, speakerOn.get())
        assertEquals(1, speakerOff.get())
        assertEquals("every unit of CPU-holding work was released exactly once", workEntered.get(), workExited.get())
        assertFalse(phases(o).containsKey("t1"))
        assertEquals(PendingPhase.AWAITING, phases(o)["t2"])
        assertFalse(other.isCompleted)
        script("ZETA words").send(RecipientEvent.Complete("home final", "complete"))
        assertTrue(other.await() is VoiceTurnOutcome.Completed)
        assertEquals(listOf("play:phone:FINAL:${chunks[0]}", "play:phone:FINAL:home final"), finals())
    }

    @Test(timeout = 120_000)
    fun `the pending cap still counts a reply that waits for its synthesis, and refuses before submission`() = runBlocking {
        val o = orchestrator(maxPending = 2)
        val run = startLongReply(o)
        parkInGap(o)
        val second = CoroutineScope(Dispatchers.Default).async { o.run(request("t2", "ZETA words")) }
        waitFor("the second request pending") { phases(o)["t2"] == PendingPhase.AWAITING }
        val third = o.run(request("t3", "work three"))
        assertEquals(VoiceTurnOutcome.NotAdmitted(VoiceTurnOrchestrator.PENDING_LIMIT), third)
        assertFalse("refused BEFORE submission", rawSubmits.any { it.second.startsWith("work three") })
        o.stopTurn("t2")
        assertEquals(VoiceTurnOutcome.Stopped, second.await())
        synthGates.getValue(chunks[1]).complete(Unit)
        assertTrue(run.await() is VoiceTurnOutcome.Completed)
        assertEquals(expectedFinals(), finals())
    }

    @Test(timeout = 120_000)
    fun `an original reply is spoken in full with the later-reply option off, the default`() = runBlocking {
        val o = orchestrator()
        val run = startLongReply(o)
        parkInGap(o)
        synthGates.getValue(chunks[1]).complete(Unit)
        assertTrue(run.await() is VoiceTurnOutcome.Completed)
        assertEquals(expectedFinals(), finals())
        assertEquals("one Watch-side speaker signal never fired for a Phone reply", 0, watchOn.get())
    }

    @Test(timeout = 120_000)
    fun `while the first chunk is only synthesized nothing is held - no speaker, slot or CPU unit - and it plays when it arrives`() = runBlocking {
        val o = orchestrator()
        synthGates[chunks[0]] = CompletableDeferred()
        val run = startLongReply(o)
        waitFor("the first chunk's synthesis requested") { synthStarted.contains(chunks[0]) }
        delay(300)
        assertFalse("nothing is audible, so the speaker signal is false", phoneSpeakerActive())
        assertNull("no speaker slot is held", ownership.later)
        assertEquals("no CPU-holding unit runs while only the server generates", 0, workEntered.get() - workExited.get())
        synthGates.getValue(chunks[0]).complete(Unit)
        synthGates.getValue(chunks[1]).complete(Unit)
        val outcome = run.await()
        assertTrue("$outcome", outcome is VoiceTurnOutcome.Completed)
        assertEquals(expectedFinals(), finals())
        assertEquals("every unit of CPU-holding work was released", workEntered.get(), workExited.get())
    }
}
