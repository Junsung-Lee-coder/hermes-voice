package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import com.rumi.hermesvoice.core.wake.ClaimVerdict
import com.rumi.hermesvoice.core.wake.RecognizerGuard
import com.rumi.hermesvoice.core.wake.WakeAdmission
import com.rumi.hermesvoice.core.wake.WakeArmInputs
import com.rumi.hermesvoice.core.wake.WakeClaim
import com.rumi.hermesvoice.core.wake.WakeClaimMessage
import com.rumi.hermesvoice.core.wake.WakeClaimPort
import com.rumi.hermesvoice.core.wake.WakeClaimService
import com.rumi.hermesvoice.core.wake.WakeContract
import com.rumi.hermesvoice.core.wake.WakeDeviceController
import com.rumi.hermesvoice.core.wake.WakeDevicePort
import com.rumi.hermesvoice.core.wake.WakeRecognizerPort
import com.rumi.hermesvoice.core.wake.WakeTimerPort
import com.rumi.hermesvoice.core.wake.WakeVerdictMessage
import com.rumi.hermesvoice.core.watchlink.LinkFrame
import com.rumi.hermesvoice.core.watchlink.LinkProtocolException
import com.rumi.hermesvoice.core.watchlink.PlayRequest
import com.rumi.hermesvoice.core.watchlink.PlayedAck
import com.rumi.hermesvoice.core.watchlink.TurnStateMessage
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchTransport
import com.rumi.hermesvoice.core.watchlink.WatchTurnUpload
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * "Both": two devices may hear the wake phrase, one spoken wake episode is admitted from one of
 * them. The claim lease itself, and two controllers with working (fake) recognizers against the
 * real admission, orchestrator and session wiring. These are simulated recognizers, not hardware.
 */
class WakeArbitrationTest {
    private val both = WatchSettings(WakeLocation.BOTH, "루미", revision = 7)

    // ── the claim lease ──────────────────────────────────────────────────────────────────────

    private class Clock(var now: Long = 1_000_000L)

    private fun phoneClaim(id: String, revision: Long = 7) = WakeClaim(id, VoiceOrigin.PHONE, "", revision, 1)
    private fun watchClaim(id: String, revision: Long = 7, node: String = "watch-a") = WakeClaim(id, VoiceOrigin.WATCH, node, revision, 1)

    @Test
    fun `the first claim wins and the other device is refused until the episode is over`() {
        val clock = Clock()
        val admission = WakeAdmission({ clock.now }, { both })
        assertEquals(ClaimVerdict.GRANTED, admission.claim(watchClaim("claim-watch-1")))
        assertEquals(ClaimVerdict.HELD_BY_OTHER, admission.claim(phoneClaim("claim-phone-1")))
        assertEquals("asking again is idempotent", ClaimVerdict.GRANTED, admission.claim(watchClaim("claim-watch-1")))
        assertEquals("another watch is another device", ClaimVerdict.HELD_BY_OTHER, admission.claim(watchClaim("claim-watch-x", node = "watch-b")))
        assertEquals(VoiceOrigin.WATCH, admission.holder())
        // The winner's request is admitted once; the loser's finishing recognizer is still refused for the settle time.
        assertNull(admission.admitTurn(true, VoiceOrigin.WATCH, "watch-a", "claim-watch-1"))
        assertEquals("wake_claim_invalid", admission.admitTurn(true, VoiceOrigin.WATCH, "watch-a", "claim-watch-1"))
        clock.now += WakeContract.CLAIM_SETTLE_MS - 1
        assertEquals(ClaimVerdict.HELD_BY_OTHER, admission.claim(phoneClaim("claim-phone-2")))
        assertEquals("the same device may start its next request at once", ClaimVerdict.GRANTED, admission.claim(watchClaim("claim-watch-2")))
        admission.release("claim-watch-2", VoiceOrigin.WATCH, "watch-a")
        assertEquals("still the settle time of the admitted request", ClaimVerdict.HELD_BY_OTHER, admission.claim(phoneClaim("claim-phone-3")))
        clock.now += 1
        assertEquals(ClaimVerdict.GRANTED, admission.claim(phoneClaim("claim-phone-3")))
        admission.release("claim-phone-3", VoiceOrigin.PHONE, "")
        assertEquals("a claim released without a request frees the episode at once", ClaimVerdict.GRANTED, admission.claim(watchClaim("claim-watch-3")))
    }

    @Test
    fun `renewal keeps a claim for as long as it is renewed and an abandoned one expires`() {
        val clock = Clock()
        val admission = WakeAdmission({ clock.now }, { both })
        assertEquals(ClaimVerdict.GRANTED, admission.claim(watchClaim("claim-long-1")))
        repeat(400) {   // 20 minutes of recording: renewal is not a duration cap
            clock.now += WakeContract.CLAIM_RENEW_MS
            assertEquals(ClaimVerdict.GRANTED, admission.renew("claim-long-1", VoiceOrigin.WATCH, "watch-a"))
            assertEquals(ClaimVerdict.HELD_BY_OTHER, admission.claim(phoneClaim("claim-phone-$it-x")))
        }
        clock.now += WakeContract.CLAIM_TTL_MS
        assertEquals("not renewed: gone", ClaimVerdict.EXPIRED, admission.renew("claim-long-1", VoiceOrigin.WATCH, "watch-a"))
        assertEquals("wake_claim_invalid", admission.admitTurn(true, VoiceOrigin.WATCH, "watch-a", "claim-long-1"))
        assertEquals(ClaimVerdict.GRANTED, admission.claim(phoneClaim("claim-phone-new")))
        // Late messages about the old claim cannot touch the new one.
        admission.release("claim-long-1", VoiceOrigin.WATCH, "watch-a")
        admission.release("claim-phone-new", VoiceOrigin.WATCH, "watch-a")
        assertEquals(VoiceOrigin.PHONE, admission.holder())
        assertEquals("wake_claim_invalid", admission.admitTurn(true, VoiceOrigin.WATCH, "watch-a", "claim-phone-new"))
        assertNull(admission.admitTurn(true, VoiceOrigin.PHONE, "", "claim-phone-new"))
    }

    @Test
    fun `claims follow the phone's settings, and only wake turns in Both need one`() {
        val clock = Clock()
        var settings = both
        val admission = WakeAdmission({ clock.now }, { settings })
        assertEquals(ClaimVerdict.STALE_SETTINGS, admission.claim(watchClaim("claim-stale-1", revision = 6)))
        assertEquals("wake_claim_missing", admission.admitTurn(true, VoiceOrigin.WATCH, "watch-a", null))
        assertEquals("wake_claim_invalid", admission.admitTurn(true, VoiceOrigin.WATCH, "watch-a", "claim-never-1"))
        assertNull("push-to-talk is never arbitrated", admission.admitTurn(false, VoiceOrigin.WATCH, "watch-a", null))
        assertEquals(ClaimVerdict.GRANTED, admission.claim(phoneClaim("claim-phone-1")))
        assertNull("not even while the other device holds the claim", admission.admitTurn(false, VoiceOrigin.WATCH, "watch-a", null))
        for (mode in listOf(WakeLocation.WATCH, WakeLocation.PHONE, WakeLocation.OFF)) {
            settings = WatchSettings(mode, "루미", revision = 8)
            assertFalse(admission.required())
            assertNull("$mode: one listener, nothing to arbitrate", admission.admitTurn(true, VoiceOrigin.WATCH, "watch-a", null))
            val expected = if (mode.listensOn(VoiceOrigin.WATCH)) ClaimVerdict.GRANTED else ClaimVerdict.NOT_LISTENING
            assertEquals("$mode", expected, WakeAdmission({ clock.now }, { settings }).claim(watchClaim("claim-mode-1", revision = 8)))
        }
        // A phone restart forgets every claim: a request made under one is refused, not guessed.
        assertEquals("wake_claim_invalid", WakeAdmission({ clock.now }, { both }).admitTurn(true, VoiceOrigin.PHONE, "", "claim-phone-1"))
    }

    @Test
    fun `claim messages and turn frames carry the claim and reject malformed ones`() {
        val clock = Clock()
        val admission = WakeAdmission({ clock.now }, { both })
        val verdict = WakeClaimService.handle(admission, "watch-a", WakeClaimMessage(WakeClaimMessage.Op.CLAIM, "claim-wire-01", 7, 3).encode())!!
        assertEquals(WakeVerdictMessage("claim-wire-01", ClaimVerdict.GRANTED), WakeVerdictMessage.decode(verdict.encode()))
        assertEquals("the node comes from the transport, not the message", ClaimVerdict.EXPIRED,
            WakeClaimService.handle(admission, "watch-b", WakeClaimMessage(WakeClaimMessage.Op.RENEW, "claim-wire-01").encode())!!.verdict)
        assertEquals(ClaimVerdict.GRANTED,
            WakeClaimService.handle(admission, "watch-a", WakeClaimMessage(WakeClaimMessage.Op.RENEW, "claim-wire-01").encode())!!.verdict)
        assertNull(WakeClaimService.handle(admission, "watch-a", WakeClaimMessage(WakeClaimMessage.Op.RELEASE, "claim-wire-01").encode()))
        assertNull(admission.holder())
        for (bad in listOf("nope", """{"v":2,"op":"CLAIM","claim_id":"claim-wire-02"}""", """{"v":1,"op":"STEAL","claim_id":"claim-wire-02"}""",
            """{"v":1,"op":"CLAIM","claim_id":"x"}""", """{"v":1,"op":"CLAIM"}""")) {
            assertNull(bad, WakeClaimService.handle(admission, "watch-a", bad.toByteArray()))
        }
        val upload = WatchTurnUpload.recognized("turn-claim-01", "불 꺼", "claim-wire-03")
        val decoded = WatchTurnUpload.fromFrame(WatchLinkPaths.turnPath("turn-claim-01"), LinkFrame.decode(upload.toFrame().encode()))
        assertEquals("claim-wire-03", decoded.wakeClaimId)
        assertNull(WatchTurnUpload.fromFrame(WatchLinkPaths.turnPath("turn-claim-02"),
            LinkFrame.decode(WatchTurnUpload("turn-claim-02", TurnTrigger.PUSH_TO_TALK, WatchTurnUpload.MIME_WAV, TestAudio.speechWav()).toFrame().encode())).wakeClaimId)
        try {
            WatchTurnUpload.fromFrame(WatchLinkPaths.turnPath("turn-claim-03"), LinkFrame.decode(
                WatchTurnUpload("turn-claim-03", TurnTrigger.PUSH_TO_TALK, WatchTurnUpload.MIME_WAV, TestAudio.speechWav(), "claim-wire-04").toFrame().encode()))
            fail("a push-to-talk turn cannot carry a wake claim")
        } catch (_: LinkProtocolException) {
        }
    }

    @Test
    fun `callbacks of a released recognizer are told apart from the current one's`() {
        val guard = RecognizerGuard()
        assertFalse(guard.isCurrent(0))
        val first = guard.open()
        assertTrue(guard.isCurrent(first))
        guard.close()
        assertFalse("after release", guard.isCurrent(first))
        val second = guard.open()
        assertFalse("a late error from the destroyed recognizer cannot act on the next window's", guard.isCurrent(first))
        assertTrue(guard.isCurrent(second))
    }

    // ── two functioning recognizers ──────────────────────────────────────────────────────────

    /** One device: a real controller; its recognizer, timers and claim transport are in-memory, its requests go to the real orchestrator. */
    private inner class Device(val h: CoreHarness, val origin: VoiceOrigin, val node: String, var holdVerdicts: Boolean = false) :
        WakeRecognizerPort, WakeTimerPort, WakeDevicePort, WakeClaimPort {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val outcomes = mutableListOf<VoiceTurnOutcome>()
        val played = mutableListOf<String>()
        val heldVerdicts = ArrayDeque<() -> Unit>()
        var captureClaim: String? = null
        private var ids = 0
        val controller = WakeDeviceController(origin, this, this, this, { 0L }, h.settings.watchSettings(), claims = this)

        override fun available() = true
        override fun start(generation: Long): Boolean { calls += "listen"; return true }
        override fun release() { calls += "release" }
        override fun schedule(delayMs: Long) {}
        override fun cancel() {}
        override fun windowChanged(open: Boolean) {}
        override fun scheduleHandoff(delayMs: Long) { calls += "handoff" }
        override fun cancelHandoff() {}
        override fun startRequestCapture(silenceMs: Long) = error("unused")
        override fun startRequestCapture(silenceMs: Long, claimId: String?): Boolean { calls += "capture"; captureClaim = claimId; return true }
        override fun cancelRequestCapture(reason: String) { calls += "cancel_capture:$reason" }
        override fun sendRecognized(request: String) = error("unused")
        override fun sendRecognized(request: String, claimId: String?) { calls += "send:$request"; outcomes += submit(request, claimId) }
        override fun closed(reason: String) { calls += "closed:$reason" }
        override fun armInputs() = WakeArmInputs(true, true, true, false, true, false, true, true, 0, 0, 0, null)

        override fun newClaimId() = "claim-${origin.name.lowercase()}-${++ids}"
        override fun request(claimId: String, settingsRevision: Long, generation: Long) {
            calls += "claim"
            val verdict = h.core.wakeAdmission.claim(WakeClaim(claimId, origin, node, settingsRevision, generation))
            val deliver = { controller.onClaimVerdict(claimId, verdict) }
            if (holdVerdicts) heldVerdicts += deliver else deliver()
        }
        override fun renew(claimId: String) {
            val verdict = h.core.wakeAdmission.renew(claimId, origin, node)
            if (verdict != ClaimVerdict.GRANTED) controller.onClaimVerdict(claimId, verdict)
        }
        override fun release(claimId: String) { calls += "release_claim"; h.core.wakeAdmission.release(claimId, origin, node) }
        override fun scheduleTimer(delayMs: Long) {}
        override fun cancelTimer() {}

        fun submit(text: String?, claimId: String?, wake: Boolean = true, turnId: String = "turn-${origin.name.lowercase()}-${++ids}-${calls.size}") = runBlocking {
            h.core.orchestrator.run(VoiceTurnRequest(turnId, origin, if (text == null) TestAudio.speechWav() else ByteArray(0),
                if (text == null) "audio/wav" else "text/plain", PlaybackSink { audio, cue -> played += "${cue.role}:${h.fake.decodeSpoken(audio)}" },
                recognizedText = text, wakeTurn = wake, wakeClaimId = claimId, originNodeId = node))
        }

        fun hears(vararg text: String, final: Boolean) = controller.onResults(controller.generation, text.toList(), final)
    }

    private class Rig(val h: CoreHarness, val phone: WakeArbitrationTest.Device, val watch: WakeArbitrationTest.Device, val workId: String)

    private fun rig(mode: WakeLocation = WakeLocation.BOTH, block: (Rig) -> Unit) {
        CoreHarness().use { h ->
            h.settings.saveWatchSettings(WatchSettings(mode, "루미"))
            h.fake.sourceScripts[AppSources.ROUTER] = { listOf(FakeHermesDashboard.complete("""{"action":"route","destination":"work","ack":"To work."}""")) }
            val work = runBlocking { h.core.sessions.createConversation("Work", "work", "") }.storedSessionId
            val r = Rig(h, Device(h, VoiceOrigin.PHONE, ""), Device(h, VoiceOrigin.WATCH, "watch-a"), work)
            r.phone.controller.onResume()
            r.watch.controller.onResume()
            block(r)
        }
    }

    private fun delivered(r: Rig) = r.h.fake.prompts.count { it.first == r.workId }

    @Test
    fun `both recognizers hear the same request in one breath - one device delivers it, the other says so and sends nothing`() {
        for (first in VoiceOrigin.values()) rig { r ->
            val (winner, loser) = if (first == VoiceOrigin.PHONE) r.phone to r.watch else r.watch to r.phone
            assertEquals(listOf("listen"), winner.calls.toList())
            assertEquals(listOf("listen"), loser.calls.toList())
            winner.hears("루미 불 꺼", final = true)
            loser.hears("루미 불 꺼", final = true)
            assertTrue("$first: ${winner.outcomes}", winner.outcomes.single() is VoiceTurnOutcome.Completed)
            assertTrue("$first loser: ${loser.calls}", loser.outcomes.isEmpty() && loser.calls.none { it.startsWith("send:") || it == "capture" })
            assertTrue("the loser is told", loser.calls.contains("closed:wake_taken"))
            assertTrue("and its recognizer is released", loser.calls.contains("release"))
            assertEquals("delivered once", 1, delivered(r))
            assertEquals("replies play on the winner", first, r.h.core.orchestrator.playbackRoute.device.value)
            assertEquals(listOf("ACK:To work.", "FINAL:reply from ${r.workId}"), winner.played)
        }
    }

    @Test
    fun `the claim is taken at the first partial wake phrase, before anything is sent`() = rig { r ->
        r.watch.hears("루미", final = false)
        assertEquals(VoiceOrigin.WATCH, r.h.core.wakeAdmission.holder())
        r.phone.hears("루미 불", final = false)
        assertTrue(r.phone.calls.contains("closed:wake_taken"))
        r.phone.hears("루미 불 꺼", final = true)
        assertTrue("a final after losing is ignored", r.phone.outcomes.isEmpty())
        r.watch.hears("루미 불 꺼", final = true)
        assertTrue(r.watch.outcomes.single() is VoiceTurnOutcome.Completed)
        assertEquals(1, delivered(r))
        assertEquals(VoiceOrigin.WATCH, r.h.core.orchestrator.playbackRoute.device.value)
    }

    @Test
    fun `phrase only - only the winner records, and a recording without the claim is refused`() = rig { r ->
        r.phone.hears("루미", final = true)
        r.watch.hears("루미", final = true)
        assertTrue(r.phone.calls.contains("handoff"))
        assertFalse(r.watch.calls.contains("handoff"))
        assertTrue(r.phone.controller.onHandoffDue(captureIdle = true))
        assertFalse("the loser never starts its recorder", r.watch.controller.onHandoffDue(captureIdle = true))
        assertFalse(r.watch.calls.contains("capture"))
        // A long request: the winner keeps renewing; the other device stays refused meanwhile.
        repeat(50) { r.phone.controller.onClaimTimer() }
        assertEquals(VoiceOrigin.PHONE, r.h.core.wakeAdmission.holder())
        // A Watch recording that somehow arrives anyway (no claim, or a made-up one) is not accepted.
        val before = r.h.fake.server.requestCount
        for (claim in listOf(null, "claim-made-up-1", r.phone.captureClaim)) {
            val stray = r.watch.submit(null, claim)
            assertTrue("$claim: $stray", stray is VoiceTurnOutcome.NotAdmitted)
        }
        assertEquals("nothing transcribed", before, r.h.fake.server.requestCount)
        assertNull("and it did not become the playback target", r.h.core.orchestrator.playbackRoute.device.value)
        val sent = r.phone.submit(null, r.phone.captureClaim)
        r.phone.controller.onRequestCaptureEnded(sent = true)
        assertTrue("$sent", sent is VoiceTurnOutcome.Completed)
        assertEquals(1, delivered(r))
        assertTrue("one claim, one request", r.phone.submit(null, r.phone.captureClaim) is VoiceTurnOutcome.NotAdmitted)
    }

    @Test
    fun `an unanswered claim fails closed, and its late answer changes nothing`() = rig { r ->
        r.watch.holdVerdicts = true
        r.watch.hears("루미 불 꺼", final = true)
        assertTrue("held until the phone answers", r.watch.outcomes.isEmpty())
        r.watch.controller.onClaimTimer()
        assertTrue(r.watch.calls.toString(), r.watch.calls.containsAll(listOf("closed:wake_claim_timeout", "release_claim")))
        r.watch.heldVerdicts.removeFirst()()
        assertTrue("no late send", r.watch.outcomes.isEmpty() && r.watch.calls.none { it.startsWith("send:") })
        assertEquals(0, delivered(r))
        assertNull("the phone is free again", r.h.core.wakeAdmission.holder())
        // The next episode on that Watch is unaffected by the old claim's messages.
        r.watch.holdVerdicts = false
        r.watch.controller.onPause()
        r.watch.controller.onResume()
        r.watch.hears("루미 불 꺼", final = true)
        assertTrue(r.watch.outcomes.single() is VoiceTurnOutcome.Completed)
    }

    @Test
    fun `losing the claim while recording stops the recording unsent`() = rig { r ->
        r.watch.hears("루미", final = true)
        assertTrue(r.watch.controller.onHandoffDue(captureIdle = true))
        // The phone restarted (or the lease ran out): the renewal comes back EXPIRED.
        r.watch.controller.onClaimVerdict(r.watch.captureClaim!!, ClaimVerdict.EXPIRED)
        assertTrue(r.watch.calls.toString(), r.watch.calls.containsAll(listOf("cancel_capture:wake_claim_failed", "closed:wake_claim_failed")))
        r.watch.controller.onClaimVerdict("claim-watch-99", ClaimVerdict.GRANTED)
        assertTrue(r.watch.outcomes.isEmpty())
    }

    @Test
    fun `leaving the app or a request that never came gives the claim back`() = rig { r ->
        r.phone.hears("루미 불", final = false)
        assertEquals(VoiceOrigin.PHONE, r.h.core.wakeAdmission.holder())
        r.phone.controller.onPause()
        assertNull(r.h.core.wakeAdmission.holder())
        r.phone.controller.onResume()
        r.phone.hears("루미 불", final = false)
        r.phone.hears("아무 말", final = true)   // the final no longer starts with the wake phrase
        assertTrue(r.phone.calls.contains("closed:unfinished_request"))
        assertNull(r.h.core.wakeAdmission.holder())
        r.watch.hears("루미 불 꺼", final = true)
        assertTrue("the other device may then answer its own episode", r.watch.outcomes.single() is VoiceTurnOutcome.Completed)
    }

    @Test
    fun `push-to-talk and deliberate repeated requests are not arbitrated away`() = rig { r ->
        r.phone.hears("루미", final = false)
        assertTrue("watch push-to-talk while the phone holds the claim", r.watch.submit(null, null, wake = false) is VoiceTurnOutcome.Completed)
        r.phone.hears("루미 불 꺼", final = true)
        assertTrue(r.phone.outcomes.single() is VoiceTurnOutcome.Completed)
        // The same words again from the same device, as a new episode: a new claim, delivered again.
        r.phone.controller.onPause()
        r.phone.controller.onResume()
        r.phone.hears("루미 불 꺼", final = true)
        assertEquals(2, r.phone.outcomes.count { it is VoiceTurnOutcome.Completed })
        assertEquals("1 push-to-talk + 2 deliberate requests", 3, delivered(r))
    }

    @Test
    fun `a claim under settings the phone has since changed is refused with a notice`() = rig { r ->
        r.h.settings.saveWatchSettings(r.h.settings.watchSettings().copy(vadSilenceSeconds = 5.0))   // new revision on the Phone
        r.watch.hears("루미 불 꺼", final = true)
        assertTrue(r.watch.calls.toString(), r.watch.calls.contains("closed:wake_claim_failed"))
        assertTrue(r.watch.outcomes.isEmpty())
        assertEquals(0, delivered(r))
    }

    @Test
    fun `with one listening device there is no claim and nothing changes`() {
        for (mode in listOf(WakeLocation.WATCH, WakeLocation.PHONE)) rig(mode) { r ->
            val device = if (mode == WakeLocation.WATCH) r.watch else r.phone
            val other = if (mode == WakeLocation.WATCH) r.phone else r.watch
            device.hears("루미 불 꺼", final = true)
            other.hears("루미 불 꺼", final = true)
            assertTrue("$mode", device.outcomes.single() is VoiceTurnOutcome.Completed)
            assertFalse("$mode: no claim traffic", device.calls.contains("claim") || other.calls.contains("claim"))
            assertTrue(other.calls.isEmpty())
            assertEquals(1, delivered(r))
        }
    }

    // ── through the Watch link ───────────────────────────────────────────────────────────────

    private class Link(private val h: CoreHarness, override val nodeId: String = "watch-a") : WatchTransport {
        val states: MutableList<TurnStateMessage> = Collections.synchronizedList(mutableListOf())
        override suspend fun sendMessage(path: String, bytes: ByteArray) {
            if (path == WatchLinkPaths.STATE) states += TurnStateMessage.decode(bytes)!!
        }
        override suspend fun sendChannel(path: String, bytes: ByteArray) {
            val play = PlayRequest.fromFrame(LinkFrame.decode(bytes))
            h.core.watchAcks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, true).encode())
        }
    }

    @Test
    fun `a watch wake upload is accepted only with the claim the phone gave that watch`() = rig { r ->
        val link = Link(r.h)
        fun upload(turnId: String, claim: String?, node: Link = link) = runBlocking {
            r.h.core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath(turnId),
                WatchTurnUpload(turnId, TurnTrigger.WAKE_PHRASE, WatchTurnUpload.MIME_WAV, TestAudio.speechWav(), claim).toFrame().encode(), node)
        }
        assertTrue(upload("turn-link-01", null) is VoiceTurnOutcome.NotAdmitted)
        assertTrue(link.states.last().terminal && link.states.last().detail.startsWith("Not sent"))
        assertNull(r.h.core.orchestrator.playbackRoute.device.value)
        val granted = WakeClaimService.handle(r.h.core.wakeAdmission, "watch-a",
            WakeClaimMessage(WakeClaimMessage.Op.CLAIM, "claim-link-01", r.h.settings.watchSettingsRevision, 1).encode())!!
        assertEquals(ClaimVerdict.GRANTED, granted.verdict)
        assertTrue("another watch cannot use it", upload("turn-link-02", "claim-link-01", Link(r.h, "watch-b")) is VoiceTurnOutcome.NotAdmitted)
        assertTrue(upload("turn-link-03", "claim-link-01") is VoiceTurnOutcome.Completed)
        assertEquals(VoiceOrigin.WATCH, r.h.core.orchestrator.playbackRoute.device.value)
        assertEquals(1, delivered(r))
        val ptt = runBlocking {
            r.h.core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath("turn-link-04"),
                WatchTurnUpload("turn-link-04", TurnTrigger.PUSH_TO_TALK, WatchTurnUpload.MIME_WAV, TestAudio.speechWav()).toFrame().encode(), link)
        }
        assertTrue("push-to-talk needs no claim", ptt is VoiceTurnOutcome.Completed)
    }
}
