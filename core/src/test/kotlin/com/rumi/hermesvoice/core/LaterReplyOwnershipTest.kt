package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.voice.AudioOwnership
import com.rumi.hermesvoice.core.voice.EpisodeMicrophone
import com.rumi.hermesvoice.core.voice.MicrophoneClaim
import com.rumi.hermesvoice.core.voice.PlaybackCue
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import com.rumi.hermesvoice.core.watchlink.LinkFrame
import com.rumi.hermesvoice.core.watchlink.PlayRequest
import com.rumi.hermesvoice.core.watchlink.PlayedAck
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchTransport
import com.rumi.hermesvoice.core.watchlink.WatchTurnUpload
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review b31 (B31-N1..N5): one ownership seam for the Phone's microphone and the later-reply speaker
 * ([AudioOwnership]), exercised through the production core wiring with the other thread's action
 * injected at each race point (the reviewer's probes P1, P2, P2b and P3, inverted to the required
 * behaviour, plus their variants).
 */
class LaterReplyOwnershipTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val later: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val ended: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile private var onPlayedHook: ((PlaybackCue) -> Unit)? = null
    @Volatile private var inWork: (() -> Unit)? = null
    @Volatile private var windowHook: (() -> Unit)? = null
    private val phoneSpeakerOn = AtomicInteger()
    private val phoneSpeakerOff = AtomicInteger()
    private val watchSpeaker = AtomicInteger()

    private val listener = object : VoiceTurnListener {
        override fun onLaterReply(turnId: String, played: Boolean, detail: String) {
            later += "$turnId:${if (played) "played" else "not_played"}:$detail"
        }

        override fun onLaterFollowEnded(turnId: String, reason: String) {
            ended += "$turnId:$reason"
        }

        override fun onPlayed(cue: PlaybackCue) {
            onPlayedHook?.invoke(cue)
        }
    }

    @After
    fun stop() = scope.cancel()

    private fun harness(windowMs: Long = 60_000L, deferMaxMs: Long = 60_000L) = CoreHarness(laterScope = scope, laterWindowMs = windowMs,
        voiceListener = listener, laterDeferMaxMs = deferMaxMs,
        laterWork = { work ->
            inWork?.invoke()
            work()
        },
        wakeListening = { windowHook?.invoke(); false },
        laterSpeaker = { device, on ->
            when {
                device == VoiceOrigin.PHONE && on -> phoneSpeakerOn.incrementAndGet()
                device == VoiceOrigin.PHONE -> phoneSpeakerOff.incrementAndGet()
                else -> watchSpeaker.incrementAndGet()
            }
        }).also { it.laterConsent.enabled = true }

    private fun routeTo(h: CoreHarness, alias: String) {
        val reply: (String) -> List<Pair<String, JSONObject?>> =
            { listOf(FakeHermesDashboard.complete("""{"action":"route","destination":"$alias","ack":"Sending to $alias."}""")) }
        h.fake.sourceScripts[AppSources.ROUTER] = reply
        h.registry.router()?.let { h.fake.scripts[it.storedSessionId] = reply }
    }

    private fun existing(h: CoreHarness, alias: String, firstReply: String): String {
        val owned = runBlocking { h.core.sessions.createConversation(alias.replaceFirstChar { it.uppercase() }, alias, "$alias things") }
        h.fake.scripts[owned.storedSessionId] = { listOf(FakeHermesDashboard.complete(firstReply)) }
        return owned.storedSessionId
    }

    private fun waitFor(what: String, ms: Long = 15_000, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(ms) { while (!condition()) delay(10) }
        } catch (_: Exception) {
            throw AssertionError("timed out waiting for $what (later=$later ended=$ended)")
        }
    }

    private fun settle(ms: Long) = runBlocking { delay(ms) }

    /**
     * The Phone speaker. [overlaps]: a later reply was audible while the microphone was OPEN (a
     * recorder opened through [openRecorder]); [cut]: an utterance was stopped mid-way.
     */
    private inner class Speaker(val h: CoreHarness, private val utteranceMs: Long = 0) {
        val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val overlaps: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val cut: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val holds = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        @Volatile var audible: String? = null
        @Volatile var microphoneOpen = false
        @Volatile var atStart: (() -> Unit)? = null
        @Volatile var atEnd: (() -> Unit)? = null
        val sink = PlaybackSink { audio, cue ->
            val text = h.fake.decodeSpoken(audio)
            played += "${cue.role}:$text"
            atStart?.invoke()
            audible = text
            try {
                if (microphoneOpen && cue.later) overlaps += text
                holds[text]?.await()
                if (utteranceMs > 0 && cue.later) delay(utteranceMs)
                if (microphoneOpen && cue.later) overlaps += "$text@end"
                atEnd?.invoke()
            } catch (stopped: CancellationException) {
                cut += text
                throw stopped
            } finally {
                audible = null
            }
        }

        /** What every Phone capture path does: claim, then open the microphone only once a later reply stopped. */
        fun openRecorder(claim: MicrophoneClaim, opened: CompletableDeferred<Boolean>? = null) {
            claim.whenSpeakerStopped(scope) { stopped ->
                if (stopped) {
                    if (audible != null) overlaps += "$audible@open"
                    microphoneOpen = true
                }
                opened?.complete(stopped)
            }
        }

        fun closeRecorder(claim: MicrophoneClaim) {
            microphoneOpen = false
            claim.release()
        }
    }

    private fun phoneTurn(h: CoreHarness, s: Speaker, id: String, microphone: MicrophoneClaim? = null,
                          audio: ByteArray = TestAudio.speechWav()) = runBlocking {
        h.core.orchestrator.run(VoiceTurnRequest(id, VoiceOrigin.PHONE, audio, "audio/wav", s.sink, microphone = microphone))
    }

    // ── N1: a recording and a later reply never overlap ───────────────────────────────────────

    @Test
    fun `P1 inverted - a recording starting at any point of a later reply's admission or playback never records it`() {
        // Each race point a recording can start at (on another thread): synthesis, the wake-window check right after
        // admission, the instant playback starts, mid-utterance.
        for (point in listOf("synthesis", "admitted", "start", "middle")) {
            later.clear()
            harness().use { h ->
                val work = existing(h, "work", "Started.")
                routeTo(h, "work")
                val s = Speaker(h, utteranceMs = 300)
                phoneTurn(h, s, "o-n1-$point")
                val claims: MutableList<MicrophoneClaim> = Collections.synchronizedList(mutableListOf())
                val once = AtomicBoolean(false)
                val start = { if (once.compareAndSet(false, true)) h.ownership.claimMicrophone(VoiceOrigin.PHONE).also { claims += it; s.openRecorder(it) } }
                when (point) {
                    "synthesis" -> inWork = { start() }
                    "admitted" -> windowHook = { start() }
                    "start" -> s.atStart = { start() }
                    "middle" -> scope.launch { while (s.audible == null) delay(5); delay(100); start() }
                }
                h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Never recorded."))
                waitFor("the recording started ($point)") { claims.isNotEmpty() }
                settle(1_200)
                assertTrue("$point: nothing reported while it records", later.isEmpty())
                inWork = null
                windowHook = null
                s.atStart = null
                s.closeRecorder(claims.single())
                waitFor("played once after the recording ($point)") { later.isNotEmpty() }
                assertTrue("$point: never audible with the microphone open: ${s.overlaps}", s.overlaps.isEmpty())
                assertEquals(listOf("o-n1-$point:played:phone"), later.toList())
                assertEquals("$point: one complete playback", 1, s.played.count { it == "FINAL:Never recorded." } - s.cut.count { it == "Never recorded." })
                assertEquals("$point: the Phone speaker signal is balanced", phoneSpeakerOn.get(), phoneSpeakerOff.get())
            }
        }
    }

    @Test
    fun `a claim made while a reply plays opens the microphone only after its playback stopped (cancel and join)`() {
        harness().use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            // A slow stop: the player takes 400 ms to let go after it was told to stop.
            val stopped = AtomicBoolean(false)
            val s = Speaker(h)
            val sink = PlaybackSink { audio, cue ->
                try {
                    s.sink.play(audio, cue)
                } finally {
                    if (cue.later) withContext(NonCancellable) { delay(400); stopped.set(true) }
                }
            }
            runBlocking { h.core.orchestrator.run(VoiceTurnRequest("o-n1-join", VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav", sink)) }
            s.holds["Long."] = CompletableDeferred()
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Long."))
            waitFor("playing") { s.audible == "Long." }
            val claim = h.ownership.claimMicrophone(VoiceOrigin.PHONE)
            val opened = CompletableDeferred<Boolean>()
            assertNull("deferred: the reply is still stopping", claim.whenSpeakerStopped(scope) { ok -> opened.complete(ok && stopped.get()) })
            assertTrue("opened after the player let go", runBlocking { withTimeout(5_000) { opened.await() } })
            claim.release()
        }
    }

    @Test
    fun `a recording cancelled while it waits for a reply to stop never opens, and a speaker that never stops is bounded`() {
        harness().use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val s = Speaker(h)
            val release = CompletableDeferred<Unit>()
            val sink = PlaybackSink { audio, cue ->
                try {
                    s.sink.play(audio, cue)
                } finally {
                    if (cue.later) withContext(NonCancellable) { withTimeout(10_000) { release.await() } }
                }
            }
            runBlocking { h.core.orchestrator.run(VoiceTurnRequest("o-n1-cancel", VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav", sink)) }
            s.holds["Stuck."] = CompletableDeferred()
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Stuck."))
            waitFor("playing") { s.audible == "Stuck." }
            // 1) The recording is cancelled (pause, opt-out) while it waits: open(false), claim given back.
            val first = h.ownership.claimMicrophone(VoiceOrigin.PHONE)
            val firstOpened = CompletableDeferred<Boolean>()
            first.whenSpeakerStopped(scope) { ok -> firstOpened.complete(ok) }
            first.release()
            // 2) A second one gives up after its bound: open(false), claim given back.
            val second = h.ownership.claimMicrophone(VoiceOrigin.PHONE)
            val secondOpened = CompletableDeferred<Boolean>()
            second.whenSpeakerStopped(scope, timeoutMs = 300) { ok -> secondOpened.complete(ok) }
            assertFalse(runBlocking { withTimeout(5_000) { secondOpened.await() } })
            assertFalse(second.held)
            // 3) A third whose scope goes away (the screen is gone) never opens and gives the claim back.
            val gone = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val third = h.ownership.claimMicrophone(VoiceOrigin.PHONE)
            val thirdOpened = AtomicBoolean(false)
            third.whenSpeakerStopped(gone) { thirdOpened.set(true) }
            gone.cancel()
            release.complete(Unit)
            assertFalse(runBlocking { withTimeout(5_000) { firstOpened.await() } })
            waitFor("the third claim given back") { !third.held }
            assertFalse(thirdOpened.get())
            assertFalse(h.ownership.microphoneClaimed(VoiceOrigin.PHONE))
        }
    }

    // ── N2: the window bounds arrivals only ───────────────────────────────────────────────────

    @Test
    fun `P2 inverted - a reply that arrived in the window and waits for a recording plays after the window ended`() {
        harness(windowMs = 3_000).use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val s = Speaker(h)
            phoneTurn(h, s, "o-n2-0001")
            val recording = h.ownership.claimMicrophone(VoiceOrigin.PHONE)
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Arrived in the window."))
            waitFor("the window ended") { ended.contains("o-n2-0001:window") }
            settle(500)
            assertTrue(later.isEmpty())
            recording.release()
            waitFor("played after the window") { later.isNotEmpty() }
            assertEquals(listOf("o-n2-0001:played:phone"), later.toList())
            assertEquals(1, s.played.count { it == "FINAL:Arrived in the window." })
        }
    }

    @Test
    fun `P2b inverted - a reply playing when the window ends is not cut and is reported played`() {
        harness(windowMs = 3_000).use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val s = Speaker(h)
            phoneTurn(h, s, "o-n2-0002")
            val long = CompletableDeferred<Unit>().also { s.holds["A long report."] = it }
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("A long report."))
            waitFor("playing") { s.audible == "A long report." }
            waitFor("the window ended") { ended.contains("o-n2-0002:window") }
            settle(300)
            long.complete(Unit)
            waitFor("reported") { later.isNotEmpty() }
            assertTrue("not cut: ${s.cut}", s.cut.isEmpty())
            assertEquals(listOf("o-n2-0002:played:phone"), later.toList())
        }
    }

    @Test
    fun `a reply first arriving after the window is never admitted, and an admitted one is bounded from its own arrival`() {
        harness(windowMs = 2_000, deferMaxMs = 1_500).use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val s = Speaker(h)
            phoneTurn(h, s, "o-n2-0003")
            val recording = h.ownership.claimMicrophone(VoiceOrigin.PHONE)
            settle(1_500)
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Just in time."))
            waitFor("the window ended") { ended.contains("o-n2-0003:window") }
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Too late."))
            // Busy past its own 1.5 s from arrival (well after the window ended): reported, not silently dropped.
            waitFor("bounded report") { later.isNotEmpty() }
            assertEquals(listOf("o-n2-0003:not_played:not played: the speaker or microphone stayed busy"), later.toList())
            recording.release()
            settle(1_200)
            assertTrue(s.played.none { it.contains("Too late.") || it.contains("Just in time.") })
            assertEquals(1, later.size)
        }
    }

    @Test
    fun `arrivals queue serially and boundedly - extra ones are reported at once, a Stop reports every waiting one`() {
        harness().use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val s = Speaker(h)
            phoneTurn(h, s, "o-n2-0004")
            val recording = h.ownership.claimMicrophone(VoiceOrigin.PHONE)
            for (i in 1..7) h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Reply $i."))
            // One speaking (waiting) + four queued; the 6th and 7th can't wait.
            waitFor("overflow reported") { later.size == 2 }
            assertEquals(List(2) { "o-n2-0004:not_played:not played: too many later replies waiting" }, later.toList())
            h.core.orchestrator.stopFollowing()
            waitFor("every waiting reply reported") { later.size == 7 }
            assertEquals(5, later.count { it == "o-n2-0004:not_played:not played: stopped" })
            recording.release()
            settle(800)
            assertTrue(s.played.none { it.startsWith("FINAL:Reply") })
        }
    }

    @Test
    fun `queued replies play one at a time in arrival order, each once`() {
        harness().use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val s = Speaker(h)
            phoneTurn(h, s, "o-n2-0005")
            val recording = h.ownership.claimMicrophone(VoiceOrigin.PHONE)
            for (i in 1..3) h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Reply $i."))
            settle(600)
            recording.release()
            waitFor("all three played") { later.size == 3 }
            assertEquals(listOf("FINAL:Reply 1.", "FINAL:Reply 2.", "FINAL:Reply 3."), s.played.filter { it.startsWith("FINAL:Reply") })
            assertEquals(List(3) { "o-n2-0005:played:phone" }, later.toList())
        }
    }

    // ── N3: completion wins ───────────────────────────────────────────────────────────────────

    @Test
    fun `P3 inverted - a recording starting as a later reply finishes never replays it`() {
        for (point in listOf("end-of-audio", "onPlayed")) {
            later.clear()
            harness().use { h ->
                val work = existing(h, "work", "Started.")
                routeTo(h, "work")
                val s = Speaker(h)
                phoneTurn(h, s, "o-n3-$point")
                val claims: MutableList<MicrophoneClaim> = Collections.synchronizedList(mutableListOf())
                val once = AtomicBoolean(false)
                val start = { if (once.compareAndSet(false, true)) claims += h.ownership.claimMicrophone(VoiceOrigin.PHONE) }
                if (point == "end-of-audio") s.atEnd = { start() } else onPlayedHook = { cue -> if (cue.later) start() }
                h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Played once."))
                waitFor("reported ($point)") { later.isNotEmpty() }
                claims.forEach { it.release() }
                onPlayedHook = null
                settle(1_500)
                assertEquals("$point", 1, s.played.count { it == "FINAL:Played once." })
                assertEquals(listOf("o-n3-$point:played:phone"), later.toList())
            }
        }
    }

    @Test
    fun `a duplicate or stale Watch ACK never plays or reports twice, and a busy ACK before completion retries`() {
        harness().use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val sends = AtomicInteger()
            val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
            val watch = object : WatchTransport {
                override val nodeId = "watch-node-1"
                override suspend fun sendMessage(path: String, bytes: ByteArray) {}
                override suspend fun sendChannel(path: String, bytes: ByteArray) {
                    val play = PlayRequest.fromFrame(LinkFrame.decode(bytes))
                    val text = String(play.audio).removePrefix("AUDIO:")
                    if (play.later && sends.incrementAndGet() == 1) {
                        // Busy first (it records), then a stale "played" for the refused attempt arrives late.
                        h.core.watchAcks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, false, PlayedAck.BUSY_RECORDING).encode())
                        h.core.watchAcks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, true).encode())
                        return
                    }
                    played += "${play.role}:$text"
                    // Played: confirmed twice (a repeated message).
                    h.core.watchAcks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, true).encode())
                    h.core.watchAcks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, true).encode())
                }
            }
            runBlocking {
                val frame = WatchTurnUpload("o-n3-ack", TurnTrigger.PUSH_TO_TALK, WatchTurnUpload.MIME_WAV, TestAudio.speechWav()).toFrame().encode()
                h.core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath("o-n3-ack"), frame, watch)
            }
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("To the Watch."))
            waitFor("played") { later.isNotEmpty() }
            settle(1_500)
            assertEquals(listOf("o-n3-ack:played:watch"), later.toList())
            assertEquals(1, played.count { it == "FINAL:To the Watch." })
            assertEquals(2, sends.get())
        }
    }

    // ── N4: the Phone's speaker signal is only real Phone playback ───────────────────────────

    @Test
    fun `a later reply to the Watch, its synthesis and busy retries never signal the Phone speaker`() {
        harness().use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val refusals = AtomicInteger()
            val watch = object : WatchTransport {
                override val nodeId = "watch-node-1"
                override suspend fun sendMessage(path: String, bytes: ByteArray) {}
                override suspend fun sendChannel(path: String, bytes: ByteArray) {
                    val play = PlayRequest.fromFrame(LinkFrame.decode(bytes))
                    val ok = !(play.later && refusals.getAndIncrement() < 2)
                    h.core.watchAcks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, ok, if (ok) "" else PlayedAck.BUSY_RECORDING).encode())
                }
            }
            runBlocking {
                val frame = WatchTurnUpload("o-n4-0001", TurnTrigger.PUSH_TO_TALK, WatchTurnUpload.MIME_WAV, TestAudio.speechWav()).toFrame().encode()
                h.core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath("o-n4-0001"), frame, watch)
            }
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("To the Watch."))
            waitFor("played on the Watch") { later.isNotEmpty() }
            assertEquals(listOf("o-n4-0001:played:watch"), later.toList())
            assertEquals(0, phoneSpeakerOn.get())
            assertEquals("three admitted attempts on the Watch, each let go", 6, watchSpeaker.get())
        }
    }

    @Test
    fun `an accepted wake episode holds the microphone through the handoff gap - a later reply waits, then follows its request`() {
        harness().use { h ->
            val work = existing(h, "work", "Started.")
            existing(h, "home", "Sure.")
            routeTo(h, "work")
            val s = Speaker(h)
            phoneTurn(h, s, "o-n4-0002")
            // The phrase is heard: the episode holds the microphone (before the accepted cue), across the 300 ms handoff.
            val episode = EpisodeMicrophone(h.ownership, VoiceOrigin.PHONE)
            episode.hold(true)
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Waited for the request."))
            settle(800)
            assertEquals("never admitted to the Phone during the handoff", 0, phoneSpeakerOn.get())
            // The recording takes the hold over, then the request; giving the episode's hold back is a no-op.
            val recording = episode.take()!!
            episode.hold(false)
            assertTrue(h.ownership.microphoneClaimed(VoiceOrigin.PHONE))
            routeTo(h, "home")
            phoneTurn(h, s, "o-n4-0003", microphone = recording)
            waitFor("the later reply after the request") { later.isNotEmpty() }
            assertEquals(listOf("o-n4-0002:played:phone"), later.toList())
            assertTrue(s.played.indexOf("FINAL:Waited for the request.") > s.played.indexOf("FINAL:Sure."))
        }
    }

    // ── N5: the deferring request's own gap ───────────────────────────────────────────────────

    @Test
    fun `a reply deferred by a recording waits through that request's transcription, routing and answer, then plays once`() {
        harness().use { h ->
            val work = existing(h, "work", "Started.")
            existing(h, "home", "Sure.")
            routeTo(h, "work")
            val s = Speaker(h)
            phoneTurn(h, s, "o-n5-0001")
            val recording = h.ownership.claimMicrophone(VoiceOrigin.PHONE)
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Work report."))
            settle(800)
            routeTo(h, "home")
            h.fake.transcribeDelayMs = 1_500
            // The recording ends and becomes the request (its claim goes with it); transcription takes 1.5 s.
            val turn = scope.async { phoneTurn(h, s, "o-n5-0002", microphone = recording) }
            settle(700)
            assertFalse("not in the transcription gap", s.played.contains("FINAL:Work report."))
            runBlocking { turn.await() }
            waitFor("played after the answer") { later.isNotEmpty() }
            val final = s.played.indexOf("FINAL:Sure.")
            assertTrue("after the request's own answer: ${s.played}", final >= 0 && s.played.indexOf("FINAL:Work report.") > final)
            assertEquals(listOf("o-n5-0001:played:phone"), later.toList())
            assertFalse(h.ownership.microphoneClaimed(VoiceOrigin.PHONE))
        }
    }

    @Test
    fun `a request with no speech, or one that never ran, gives the microphone back - the reply is not wedged`() {
        harness().use { h ->
            val work = existing(h, "work", "Started.")
            routeTo(h, "work")
            val s = Speaker(h)
            phoneTurn(h, s, "o-n5-0003")
            val target = h.core.orchestrator.playbackRoute.device.value
            val noSpeech = h.ownership.claimMicrophone(VoiceOrigin.PHONE)
            h.fake.pushLaterTurn(work, FakeHermesDashboard.complete("Not wedged."))
            settle(600)
            // Silence: refused before acceptance, the latest sender unchanged, and the claim is let go.
            phoneTurn(h, s, "o-n5-0004", microphone = noSpeech, audio = TestAudio.wav(TestAudio.silence(2.0)))
            assertEquals(target, h.core.orchestrator.playbackRoute.device.value)
            assertFalse(noSpeech.held)
            // A recording whose request job was cancelled before it ran: its owner gives the claim back (PhoneApp.launchTurn).
            val neverRan = h.ownership.claimMicrophone(VoiceOrigin.PHONE)
            settle(400)
            assertTrue(later.isEmpty())
            neverRan.release()
            waitFor("played") { later.isNotEmpty() }
            assertEquals(listOf("o-n5-0003:played:phone"), later.toList())
        }
    }

    @Test
    fun `the ownership seam itself - claims stack, release is idempotent, a finished reply is never stopped`() {
        val ownership = AudioOwnership()
        val a = ownership.claimMicrophone(VoiceOrigin.PHONE)
        val b = ownership.claimMicrophone(VoiceOrigin.PHONE)
        assertFalse(ownership.microphoneClaimed(VoiceOrigin.WATCH))
        a.release()
        a.release()
        assertTrue("another recording still holds it", ownership.microphoneClaimed(VoiceOrigin.PHONE))
        b.release()
        assertFalse(ownership.microphoneClaimed(VoiceOrigin.PHONE))
        // Nothing was playing: the microphone opens right away, in the caller.
        val c = ownership.claimMicrophone(VoiceOrigin.PHONE)
        assertEquals(true, c.whenSpeakerStopped(scope) { it })
        c.release()
        assertEquals("a released claim never opens", false, c.whenSpeakerStopped(scope) { it })
    }
}
