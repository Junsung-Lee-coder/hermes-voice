package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import com.rumi.hermesvoice.core.wake.ClaimVerdict
import com.rumi.hermesvoice.core.wake.WakeAdmission
import com.rumi.hermesvoice.core.wake.WakeArmInputs
import com.rumi.hermesvoice.core.wake.WakeClaim
import com.rumi.hermesvoice.core.wake.WakeClaimMessage
import com.rumi.hermesvoice.core.wake.WakeClaimPort
import com.rumi.hermesvoice.core.wake.WakeClaimSender
import com.rumi.hermesvoice.core.wake.WakeClaimService
import com.rumi.hermesvoice.core.wake.WakeClaimTransit
import com.rumi.hermesvoice.core.wake.WakeContract
import com.rumi.hermesvoice.core.wake.WakeDeviceController
import com.rumi.hermesvoice.core.wake.WakeDevicePort
import com.rumi.hermesvoice.core.wake.WakeEpochItem
import com.rumi.hermesvoice.core.wake.WakeEpochStore
import com.rumi.hermesvoice.core.wake.WakeRecognizerPort
import com.rumi.hermesvoice.core.wake.WakeTimerPort
import com.rumi.hermesvoice.core.wake.WakeVerdictMessage
import com.rumi.hermesvoice.core.watchlink.LinkFrame
import com.rumi.hermesvoice.core.watchlink.PlayRequest
import com.rumi.hermesvoice.core.watchlink.PlayedAck
import com.rumi.hermesvoice.core.watchlink.TurnStateMessage
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchTransport
import com.rumi.hermesvoice.core.watchlink.WatchTurnUpload
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Both" beyond the claim itself: the Watch keeps its claim until the Phone has answered the
 * request it sent; a recognizer that finishes late can never deliver a phrase that was already
 * answered; claim messages reach the Phone in order; and changing the wake location while a
 * request is being recorded cancels it instead of recording it and refusing it afterwards.
 * The Watch here is the real controller, the claim messages as they go over the link, the transit
 * keeper and the Watch link against the real admission and orchestrator; recognizers are simulated.
 */
class WakeClaimTransferTest {
    /** A Watch as its app wires it: claims go to the Phone as encoded messages from node [node], verdicts come back decoded. */
    private inner class Watch(val h: CoreHarness, val node: String = "watch-a") : WakeRecognizerPort, WakeTimerPort, WakeDevicePort, WakeClaimPort, WatchTransport {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val states: MutableList<TurnStateMessage> = Collections.synchronizedList(mutableListOf())
        val lost = mutableListOf<String>()
        var captureClaim: String? = null
        var recognized: Pair<String, String?>? = null

        /** What this Watch knows of the Phone's count: from the data item (when [told]) and from verdicts. */
        var epoch = 0L
        var told = true
        var linkUp = true
        private var ids = 0
        val controller = WakeDeviceController(VoiceOrigin.WATCH, this, this, this, { 0L }, h.settings.watchSettings(), claims = this)
        val transit = WakeClaimTransit({ h.core.wakeAdmission.let { System.currentTimeMillis() + h.clockOffsetMs } },
            renew = { wire(WakeClaimMessage(WakeClaimMessage.Op.RENEW, it)) },
            release = { calls += "release_claim"; wire(WakeClaimMessage(WakeClaimMessage.Op.RELEASE, it)) },
            onLost = { turnId, reason -> lost += "$turnId:$reason" })

        private fun wire(message: WakeClaimMessage) {
            if (!linkUp) return
            val verdict = WakeClaimService.handle(h.core.wakeAdmission, node, message.encode())?.let { WakeVerdictMessage.decode(it.encode())!! } ?: return
            if (verdict.epoch >= 0) epoch = verdict.epoch
            transit.onVerdict(verdict.claimId, verdict.verdict)
            controller.onClaimVerdict(verdict.claimId, verdict.verdict)
        }

        override val nodeId get() = node
        override suspend fun sendMessage(path: String, bytes: ByteArray) {
            if (path != WatchLinkPaths.STATE) return
            val state = TurnStateMessage.decode(bytes)!!
            states += state
            transit.onPhoneState(state.turnId, state.terminal)
        }
        override suspend fun sendChannel(path: String, bytes: ByteArray) {
            val play = PlayRequest.fromFrame(LinkFrame.decode(bytes))
            h.core.watchAcks.onPlayedMessage(node, PlayedAck(play.turnId, play.sequence, true).encode())
        }

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
        override fun sendRecognized(request: String, claimId: String?) { calls += "send:$request"; recognized = request to claimId }
        override fun closed(reason: String) { calls += "closed:$reason" }
        override fun armInputs() = WakeArmInputs(true, true, true, false, true, false, true, true, 0, 0, 0, null)
        override fun newClaimId() = "claim-watch-${++ids}"
        override fun epoch(): Long = epoch
        override fun request(claimId: String, settingsRevision: Long, generation: Long, epoch: Long) {
            calls += "claim"
            wire(WakeClaimMessage(WakeClaimMessage.Op.CLAIM, claimId, settingsRevision, generation, epoch))
        }
        override fun renew(claimId: String) = wire(WakeClaimMessage(WakeClaimMessage.Op.RENEW, claimId))
        override fun release(claimId: String) { calls += "release_claim"; wire(WakeClaimMessage(WakeClaimMessage.Op.RELEASE, claimId)) }
        override fun scheduleTimer(delayMs: Long) {}
        override fun cancelTimer() {}

        fun hears(text: String, final: Boolean) = controller.onResults(controller.generation, listOf(text), final)

        /** Shows the app again: a new window, after reading the Phone's data item if the link delivers it. */
        fun reopen() {
            controller.onPause()
            if (told) epoch = h.core.wakeAdmission.epoch
            controller.onResume()
        }

        /** The recorder ended and the recording was handed to the link with its claim, as WatchActivity and WatchApp do. */
        fun handOver(turnId: String, bytes: Int): WatchTurnUpload {
            val claim = captureClaim
            captureClaim = null
            controller.onRequestCaptureEnded(sent = true)
            claim?.let { transit.begin(it, turnId, WakeContract.transitLimitMs(bytes)) }
            return WatchTurnUpload(turnId, TurnTrigger.WAKE_PHRASE, WatchTurnUpload.MIME_WAV, TestAudio.speechWav(), claim)
        }

        fun arrives(upload: WatchTurnUpload) = runBlocking {
            h.core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath(upload.turnId), upload.toFrame().encode(), this@Watch)
        }
    }

    /** The Phone's own wake flow: direct calls to the admission, as MainActivity makes them. */
    private inner class Phone(val h: CoreHarness) : WakeRecognizerPort, WakeTimerPort, WakeDevicePort, WakeClaimPort {
        val calls = mutableListOf<String>()
        val outcomes = mutableListOf<VoiceTurnOutcome>()
        var captureClaim: String? = null
        private var ids = 0
        val controller = WakeDeviceController(VoiceOrigin.PHONE, this, this, this, { 0L }, h.settings.watchSettings(), claims = this)

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
        override fun newClaimId() = "claim-phone-${++ids}"
        override fun epoch(): Long = h.core.wakeAdmission.epoch
        override fun request(claimId: String, settingsRevision: Long, generation: Long, epoch: Long) {
            calls += "claim"
            controller.onClaimVerdict(claimId, h.core.wakeAdmission.claim(WakeClaim(claimId, VoiceOrigin.PHONE, "", settingsRevision, generation, epoch)))
        }
        override fun renew(claimId: String) {
            val verdict = h.core.wakeAdmission.renew(claimId, VoiceOrigin.PHONE, "")
            if (verdict != ClaimVerdict.GRANTED) controller.onClaimVerdict(claimId, verdict)
        }
        override fun release(claimId: String) { calls += "release_claim"; h.core.wakeAdmission.release(claimId, VoiceOrigin.PHONE, "") }
        override fun scheduleTimer(delayMs: Long) {}
        override fun cancelTimer() {}

        fun submit(text: String?, claimId: String?) = runBlocking {
            h.core.orchestrator.run(VoiceTurnRequest("turn-phone-${++ids}-${calls.size}", VoiceOrigin.PHONE, if (text == null) TestAudio.speechWav() else ByteArray(0),
                if (text == null) "audio/wav" else "text/plain", PlaybackSink { _, _ -> }, recognizedText = text, wakeTurn = true, wakeClaimId = claimId))
        }
        fun hears(text: String, final: Boolean) = controller.onResults(controller.generation, listOf(text), final)
        fun reopen() { controller.onPause(); controller.onResume() }
    }

    private class Rig(val h: CoreHarness, val phone: WakeClaimTransferTest.Phone, val watch: WakeClaimTransferTest.Watch, val workId: String) {
        fun delivered() = h.fake.prompts.count { it.first == workId }

        /** Lets [ms] pass on the Phone's lease clock; the Watch renews every 3 s while [renewing] (its recorder, then its transit keeper). */
        fun pass(ms: Long, renewing: () -> Unit = {}) {
            var left = ms
            while (left > 0) {
                val step = minOf(left, WakeContract.CLAIM_RENEW_MS)
                h.clockOffsetMs += step
                left -= step
                renewing()
            }
        }
    }

    private fun rig(mode: WakeLocation = WakeLocation.BOTH, block: (Rig) -> Unit) {
        CoreHarness().use { h ->
            h.settings.saveWatchSettings(WatchSettings(mode, "루미"))
            h.fake.sourceScripts[AppSources.ROUTER] = { listOf(FakeHermesDashboard.complete("""{"action":"route","destination":"work","ack":"To work."}""")) }
            val work = runBlocking { h.core.sessions.createConversation("Work", "work", "") }.storedSessionId
            val r = Rig(h, Phone(h), Watch(h), work)
            // As PhoneApp does: every admitted wake request is told to the Phone's own flow and (when the link delivers it) to the Watch.
            h.core.onWakeEpisode = { episode ->
                r.phone.controller.onEpisodeAnswered(episode.epoch, episode.claimId)
                if (r.watch.told) {
                    val item = WakeEpochItem.parse(WakeEpochItem(episode.epoch, episode.claimId).toJson())!!
                    r.watch.epoch = item.epoch
                    r.watch.controller.onEpisodeAnswered(item.epoch, item.claimId)
                }
            }
            r.phone.controller.onResume()
            r.watch.controller.onResume()
            block(r)
        }
    }

    // ── P2-B: the claim is the Watch's until the Phone has answered the request ──────────────

    @Test
    fun `a watch request is admitted however long its recording takes to reach the phone`() {
        // A claim that stops being renewed when the upload starts is gone after 10 s: 12 s and 25 s would be refused.
        for (transferMs in listOf(3_000L, 8_000L, 12_000L, 25_000L, 90_000L)) rig { r ->
            r.watch.hears("루미", final = true)
            assertTrue(r.watch.controller.onHandoffDue(captureIdle = true))
            r.pass(30_000) { r.watch.controller.onClaimTimer() }   // 30 s of recording, renewed by the controller
            val upload = r.watch.handOver("turn-watch-$transferMs", TestAudio.speechWav().size)
            assertTrue(r.watch.transit.active)
            // The Phone hears the phrase too while the recording is on its way: still the Watch's episode.
            r.pass(transferMs / 2) { r.watch.transit.tick() }
            r.phone.hears("루미", final = true)
            assertTrue("transfer $transferMs ms: ${r.phone.calls}", r.phone.calls.contains("closed:wake_taken"))
            r.phone.reopen()
            r.pass(transferMs - transferMs / 2) { r.watch.transit.tick() }
            assertEquals("transfer $transferMs ms", VoiceOrigin.WATCH, r.h.core.wakeAdmission.holder())
            val outcome = r.watch.arrives(upload)
            assertTrue("transfer $transferMs ms: $outcome", outcome is VoiceTurnOutcome.Completed)
            assertEquals("accepted", r.watch.states.first().stage)
            assertFalse("the phone's answer ends the keeping", r.watch.transit.active)
            assertFalse("the claim was used up, not given back", r.watch.calls.contains("release_claim"))
            assertTrue(r.watch.lost.isEmpty())
            assertEquals(1, r.delivered())
            assertEquals(VoiceOrigin.WATCH, r.h.core.orchestrator.playbackRoute.device.value)
            assertNull(r.h.core.wakeAdmission.holder())
        }
    }

    @Test
    fun `without the transit keeper the same slow transfer is refused - the renewals are what keep it`() = rig { r ->
        r.watch.hears("루미", final = true)
        assertTrue(r.watch.controller.onHandoffDue(captureIdle = true))
        val upload = r.watch.handOver("turn-watch-unkept", 1_000)
        r.pass(WakeContract.CLAIM_TTL_MS + 2_000)   // nobody renews
        val outcome = r.watch.arrives(upload)
        assertTrue("$outcome", outcome is VoiceTurnOutcome.NotAdmitted)
        assertTrue(r.watch.states.single().let { it.terminal && it.detail.startsWith("Not sent") })
        assertEquals(0, r.delivered())
        assertNull("and it did not become the playback target", r.h.core.orchestrator.playbackRoute.device.value)
    }

    @Test
    fun `a claim in transit ends with the transfer - failure, refusal, a phone restart or the limit - and says so`() {
        // The transfer fails (link lost): the claim is given back at once and the Phone may answer.
        rig { r ->
            r.watch.hears("루미", final = true)
            assertTrue(r.watch.controller.onHandoffDue(captureIdle = true))
            r.watch.handOver("turn-watch-fail", 1_000)
            r.watch.transit.onTransferFailed("turn-other")
            assertTrue("a failure of another turn changes nothing", r.watch.transit.active)
            r.watch.transit.onTransferFailed("turn-watch-fail")
            assertFalse(r.watch.transit.active)
            assertNull(r.h.core.wakeAdmission.holder())
            r.phone.hears("루미 불 꺼", final = true)
            assertTrue(r.phone.outcomes.single() is VoiceTurnOutcome.Completed)
        }
        // The Phone refuses the recording before admitting it (no usable audio): the claim it still holds is released.
        rig { r ->
            r.watch.hears("루미", final = true)
            assertTrue(r.watch.controller.onHandoffDue(captureIdle = true))
            val claim = r.watch.captureClaim
            r.watch.handOver("turn-watch-empty", 1_000)
            val silent = WatchTurnUpload("turn-watch-empty", TurnTrigger.WAKE_PHRASE, WatchTurnUpload.MIME_WAV, TestAudio.wav(TestAudio.silence(3.0)), claim)
            assertEquals(VoiceTurnOutcome.NoSpeech, r.watch.arrives(silent))
            assertTrue(r.watch.states.single().terminal)
            assertFalse(r.watch.transit.active)
            assertNull("released, not left to run out", r.h.core.wakeAdmission.holder())
            assertEquals(0L, r.h.core.wakeAdmission.epoch)
        }
        // The Phone restarted while the recording was on its way: the renewal says so, the recording is refused, the user is told.
        rig { r ->
            r.watch.hears("루미", final = true)
            assertTrue(r.watch.controller.onHandoffDue(captureIdle = true))
            val upload = r.watch.handOver("turn-watch-restart", 1_000)
            r.watch.transit.onVerdict("claim-watch-99", ClaimVerdict.EXPIRED)
            assertTrue("a verdict about another claim changes nothing", r.watch.transit.active)
            r.watch.transit.onVerdict(upload.wakeClaimId!!, WakeAdmission({ 0L }, { r.h.settings.watchSettings() }).renew(upload.wakeClaimId!!, VoiceOrigin.WATCH, "watch-a"))
            assertFalse(r.watch.transit.active)
            assertEquals(listOf("turn-watch-restart:wake_claim_failed"), r.watch.lost)
        }
        // Nothing ever comes back: the claim is kept for the limit, not forever, then given back with a notice.
        rig { r ->
            r.watch.linkUp = true
            r.watch.hears("루미", final = true)
            assertTrue(r.watch.controller.onHandoffDue(captureIdle = true))
            val upload = r.watch.handOver("turn-watch-hang", 64_000)
            val limit = WakeContract.transitLimitMs(64_000)
            assertEquals(92_000L, limit)
            r.pass(limit - 3_000) { r.watch.transit.tick() }
            assertEquals(VoiceOrigin.WATCH, r.h.core.wakeAdmission.holder())
            r.pass(6_000) { r.watch.transit.tick() }
            assertFalse(r.watch.transit.active)
            assertEquals(listOf("turn-watch-hang:wake_transfer_timeout"), r.watch.lost)
            assertNull(r.h.core.wakeAdmission.holder())
            assertTrue("a recording that arrives after that is refused", r.watch.arrives(upload) is VoiceTurnOutcome.NotAdmitted)
        }
    }

    @Test
    fun `a renewal that crosses the admission, or a state for another turn, does not report the claim lost`() = rig { r ->
        r.watch.hears("루미", final = true)
        assertTrue(r.watch.controller.onHandoffDue(captureIdle = true))
        val upload = r.watch.handOver("turn-watch-cross", 1_000)
        r.watch.transit.onPhoneState("turn-some-other", terminal = true)
        assertTrue(r.watch.transit.active)
        // The Phone admits the request; a renewal sent just before arrives just after.
        assertNull(r.h.core.wakeAdmission.admitTurn(true, VoiceOrigin.WATCH, "watch-a", upload.wakeClaimId))
        r.watch.transit.tick()
        assertFalse(r.watch.transit.active)
        assertTrue("told USED, not lost: ${r.watch.lost}", r.watch.lost.isEmpty())
        assertFalse(r.watch.calls.contains("release_claim"))
        assertEquals("and it learnt the new count from that answer", 1L, r.watch.epoch)
    }

    @Test
    fun `a same-breath watch request keeps its claim through the transfer too`() = rig { r ->
        r.watch.hears("루미 불 꺼", final = true)
        val (request, claim) = r.watch.recognized!!
        r.watch.transit.begin(claim!!, "turn-watch-text", WakeContract.transitLimitMs(request.length))
        r.pass(25_000) { r.watch.transit.tick() }
        val outcome = r.watch.arrives(WatchTurnUpload.recognized("turn-watch-text", request, claim))
        assertTrue("$outcome", outcome is VoiceTurnOutcome.Completed)
        assertEquals(1, r.delivered())
    }

    // ── P3-3: a phrase that was answered cannot be answered again, however late ──────────────

    @Test
    fun `a recognizer that finishes late can never deliver a phrase that was already answered`() {
        // No time window is involved: a fixed settle time would let a result through that arrives after it.
        for (told in listOf(true, false)) for (lateMs in listOf(500L, 3_100L, 8_000L, 60_000L, 3_600_000L)) rig { r ->
            r.watch.told = told
            r.phone.hears("루미 불 꺼", final = true)
            assertTrue(r.phone.outcomes.single() is VoiceTurnOutcome.Completed)
            r.pass(lateMs)
            // The Watch's recognizer, which was listening all along, only now reports the same words (no partial before).
            r.watch.hears("루미 불 꺼", final = true)
            val label = "told=$told, $lateMs ms late: ${r.watch.calls}"
            assertNull(label, r.watch.recognized)
            assertFalse(label, r.watch.calls.contains("capture") || r.watch.calls.any { it.startsWith("send:") })
            assertTrue(label, r.watch.calls.contains("closed:wake_taken"))
            // Told by the Phone, its window was closed before the result came; not told, the Phone refuses the claim.
            assertEquals(label, !told, r.watch.calls.contains("claim"))
            assertEquals("delivered once", 1, r.delivered())
            assertNull(r.h.core.wakeAdmission.holder())
            // The next deliberate wake on the Watch is a new episode, without waiting for anything.
            r.watch.reopen()
            r.watch.hears("루미 불 켜", final = true)
            val (request, claim) = r.watch.recognized!!
            assertTrue(r.watch.arrives(WatchTurnUpload.recognized("turn-watch-next-$lateMs", request, claim)) is VoiceTurnOutcome.Completed)
            assertEquals("two deliberate requests, two deliveries", 2, r.delivered())
        }
    }

    @Test
    fun `the same in the other direction and after a recorded request`() = rig { r ->
        // The Watch answers a phrase-only wake with a recording that reaches the Phone 20 s later.
        r.watch.hears("루미", final = true)
        assertTrue(r.watch.controller.onHandoffDue(captureIdle = true))
        r.pass(15_000) { r.watch.controller.onClaimTimer() }
        val upload = r.watch.handOver("turn-watch-rec", 1_000)
        r.pass(6_000) { r.watch.transit.tick() }
        assertTrue(r.watch.arrives(upload) is VoiceTurnOutcome.Completed)
        // The Phone's recognizer, open since before the phrase, reports it a minute later.
        r.pass(60_000)
        r.phone.hears("루미", final = true)
        assertTrue(r.phone.calls.toString(), r.phone.calls.contains("closed:wake_taken"))
        assertFalse(r.phone.calls.contains("handoff") || r.phone.calls.contains("capture"))
        assertEquals(1, r.delivered())
        // A Phone window opened now is a new episode.
        r.phone.reopen()
        r.phone.hears("루미 불 꺼", final = true)
        assertTrue(r.phone.outcomes.single() is VoiceTurnOutcome.Completed)
        assertEquals(2, r.delivered())
    }

    @Test
    fun `a watch that was never told is refused once, learns the count from the refusal and then works`() = rig { r ->
        r.watch.told = false
        r.phone.hears("루미 불 꺼", final = true)
        r.phone.reopen()
        r.phone.hears("루미 불 꺼", final = true)
        assertEquals(2L, r.h.core.wakeAdmission.epoch)
        // The Watch was shown again meanwhile, but the data item never reached it: it still believes 0.
        r.watch.reopen()
        r.watch.hears("루미 불 켜", final = true)
        assertTrue("refused, with a notice: ${r.watch.calls}", r.watch.calls.contains("closed:wake_claim_failed") && r.watch.recognized == null)
        assertEquals("the refusal carried the count", 2L, r.watch.epoch)
        r.watch.reopen()
        r.watch.hears("루미 불 켜", final = true)
        assertTrue(r.watch.recognized != null)
    }

    @Test
    fun `a claim given back without a request still lets the other listening device answer`() = rig { r ->
        // The Phone heard the phrase in a partial but its final result disagreed: nothing was admitted, so the Watch's
        // window, open all along, may still answer the same phrase.
        r.phone.hears("루미 불", final = false)
        assertEquals(VoiceOrigin.PHONE, r.h.core.wakeAdmission.holder())
        r.phone.hears("아무 말", final = true)
        assertNull(r.h.core.wakeAdmission.holder())
        assertEquals(0L, r.h.core.wakeAdmission.epoch)
        r.watch.hears("루미 불 꺼", final = true)
        assertTrue(r.watch.recognized != null)
    }

    @Test
    fun `the count of answered requests survives a phone restart and a claim without it is refused`() {
        val store = object : WakeEpochStore {
            var saved = 0L
            override fun load() = saved
            override fun save(epoch: Long) { saved = epoch }
        }
        val both = WatchSettings(WakeLocation.BOTH, "루미", revision = 7)
        val first = WakeAdmission({ 0L }, { both }, epochs = store)
        assertEquals(ClaimVerdict.GRANTED, first.claim(WakeClaim("claim-phone-01", VoiceOrigin.PHONE, "", 7, 1, 0)))
        assertNull(first.admitTurn(true, VoiceOrigin.PHONE, "", "claim-phone-01"))
        val restarted = WakeAdmission({ 0L }, { both }, epochs = store)
        assertEquals(1L, restarted.epoch)
        assertEquals("a window from before the answered request", ClaimVerdict.STALE_WINDOW, restarted.claim(WakeClaim("claim-watch-01", VoiceOrigin.WATCH, "watch-a", 7, 1, 0)))
        assertEquals(ClaimVerdict.GRANTED, restarted.claim(WakeClaim("claim-watch-02", VoiceOrigin.WATCH, "watch-a", 7, 1, 1)))
        // On the wire: the claim carries the count, the verdict returns it, and a claim without one never matches.
        val message = WakeClaimMessage.decode(WakeClaimMessage(WakeClaimMessage.Op.CLAIM, "claim-wire-10", 7, 3, 5).encode())!!
        assertEquals(5L, message.epoch)
        assertEquals(-1L, WakeClaimMessage.decode("""{"v":1,"op":"CLAIM","claim_id":"claim-wire-11","revision":7}""".toByteArray())!!.epoch)
        restarted.release("claim-watch-02", VoiceOrigin.WATCH, "watch-a")
        val old = WakeClaimService.handle(restarted, "watch-a", """{"v":1,"op":"CLAIM","claim_id":"claim-wire-11","revision":7}""".toByteArray())!!
        assertEquals(WakeVerdictMessage("claim-wire-11", ClaimVerdict.STALE_WINDOW, 1), WakeVerdictMessage.decode(old.encode()))
        assertEquals(WakeEpochItem(4, "claim-wire-12"), WakeEpochItem.parse(WakeEpochItem(4, "claim-wire-12").toJson()))
        for (bad in listOf("nope", """{"v":2,"epoch":1}""", """{"v":1}""", """{"v":1,"epoch":-3}""")) assertNull(bad, WakeEpochItem.parse(bad))
    }

    // ── P3-1: the wake location changes while a request is being recorded ────────────────────

    @Test
    fun `changing the wake location during a wake recording cancels it with a notice and nothing is sent`() {
        // Recording on and refusing the finished recording (it has no claim) would waste what the user said.
        rig(WakeLocation.WATCH) { r ->
            r.watch.hears("루미", final = true)
            assertTrue(r.watch.controller.onHandoffDue(captureIdle = true))
            assertNull("one listener: no claim", r.watch.captureClaim)
            r.h.settings.saveWatchSettings(r.h.settings.watchSettings().copy(wakeLocation = WakeLocation.BOTH))
            r.watch.controller.onSettings(r.h.settings.watchSettings())
            assertTrue(r.watch.calls.toString(), r.watch.calls.containsAll(listOf("cancel_capture:wake_mode_changed", "closed:wake_mode_changed")))
            assertEquals(0, r.delivered())
            // The next wake is a new episode under the new mode, with a claim.
            r.watch.reopen()
            r.watch.hears("루미 불 꺼", final = true)
            assertTrue(r.watch.recognized?.second != null)
        }
        // Both → Watch while the Watch records with a claim: cancelled the same way, and the claim is given back.
        rig { r ->
            r.watch.hears("루미", final = true)
            assertTrue(r.watch.controller.onHandoffDue(captureIdle = true))
            r.h.settings.saveWatchSettings(r.h.settings.watchSettings().copy(wakeLocation = WakeLocation.WATCH))
            r.watch.controller.onSettings(r.h.settings.watchSettings())
            assertTrue(r.watch.calls.toString(), r.watch.calls.containsAll(listOf("cancel_capture:wake_mode_changed", "closed:wake_mode_changed", "release_claim")))
            assertNull(r.h.core.wakeAdmission.holder())
        }
        // In the pause before the recorder starts: the handoff is dropped and the recorder never starts.
        rig(WakeLocation.WATCH) { r ->
            r.watch.hears("루미", final = true)
            r.h.settings.saveWatchSettings(r.h.settings.watchSettings().copy(wakeLocation = WakeLocation.BOTH))
            r.watch.controller.onSettings(r.h.settings.watchSettings())
            assertTrue(r.watch.calls.contains("closed:wake_mode_changed"))
            assertFalse(r.watch.controller.onHandoffDue(captureIdle = true))
            assertFalse(r.watch.calls.contains("capture"))
        }
        // The same on the Phone.
        rig(WakeLocation.PHONE) { r ->
            r.phone.hears("루미", final = true)
            assertTrue(r.phone.controller.onHandoffDue(captureIdle = true))
            r.h.settings.saveWatchSettings(r.h.settings.watchSettings().copy(wakeLocation = WakeLocation.BOTH))
            r.phone.controller.onSettings(r.h.settings.watchSettings())
            assertTrue(r.phone.calls.toString(), r.phone.calls.containsAll(listOf("cancel_capture:wake_mode_changed", "closed:wake_mode_changed")))
        }
    }

    @Test
    fun `another setting changing during a recording keeps it, and its request is admitted`() = rig { r ->
        r.watch.hears("루미", final = true)
        assertTrue(r.watch.controller.onHandoffDue(captureIdle = true))
        // The trailing silence changes on the Phone: the recording keeps the value and the claim it started with.
        r.h.settings.saveWatchSettings(r.h.settings.watchSettings().copy(vadSilenceSeconds = 5.0))
        r.watch.controller.onSettings(r.h.settings.watchSettings())
        assertFalse(r.watch.calls.toString(), r.watch.calls.any { it.startsWith("cancel_capture") || it.startsWith("closed") })
        val upload = r.watch.handOver("turn-watch-kept", 1_000)
        assertTrue(r.watch.arrives(upload) is VoiceTurnOutcome.Completed)
        assertEquals(1, r.delivered())
    }

    // ── P3-2: claim messages reach the Phone in the order they were sent ─────────────────────

    @Test
    fun `a release never reaches the phone before its claim, however slow the lookup or the first send`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val delivered = Collections.synchronizedList(mutableListOf<String>())
            var lookups = 0
            val sender = WakeClaimSender(scope,
                resolveNode = { if (lookups++ == 0) delay(300); "phone-1" },
                send = { node, bytes ->
                    val message = WakeClaimMessage.decode(bytes)!!
                    if (message.claimId == "claim-order-a" && message.op == WakeClaimMessage.Op.CLAIM) delay(400)
                    delivered += "${message.op}:${message.claimId}@$node"
                }, timeoutMs = 2_000)
            // As the Watch does when the wake window closes right after a partial: claim, then release, at once; then a new episode.
            sender.offer(WakeClaimMessage(WakeClaimMessage.Op.CLAIM, "claim-order-a", 7, 1, 0))
            sender.offer(WakeClaimMessage(WakeClaimMessage.Op.RENEW, "claim-order-a"))
            sender.offer(WakeClaimMessage(WakeClaimMessage.Op.RELEASE, "claim-order-a"))
            sender.offer(WakeClaimMessage(WakeClaimMessage.Op.CLAIM, "claim-order-b", 7, 2, 0))
            sender.offer(WakeClaimMessage(WakeClaimMessage.Op.RENEW, "claim-never-sent"))
            runBlocking { repeat(100) { if (delivered.size < 4) delay(30) } }
            assertEquals(listOf("CLAIM:claim-order-a@phone-1", "RENEW:claim-order-a@phone-1", "RELEASE:claim-order-a@phone-1", "CLAIM:claim-order-b@phone-1"),
                delivered.toList())
            assertTrue("a verdict counts only from the node the claim went to", sender.accepts("claim-order-b", "phone-1"))
            assertFalse(sender.accepts("claim-order-b", "phone-2"))
            assertFalse("a released claim accepts no more verdicts", sender.accepts("claim-order-a", "phone-1"))
            assertFalse("a renewal for a claim that was never sent goes nowhere", sender.accepts("claim-never-sent", "phone-1"))
            // Through the real admission: in this order the episode is free again, and the second claim is granted.
            val both = WatchSettings(WakeLocation.BOTH, "루미", revision = 7)
            val admission = WakeAdmission({ 0L }, { both })
            val verdicts = delivered.map { line ->
                val (op, id) = line.substringBefore('@').split(':')
                WakeClaimService.handle(admission, "watch-a", WakeClaimMessage(WakeClaimMessage.Op.valueOf(op), id, 7, 1, 0).encode())?.verdict
            }
            assertEquals(listOf(ClaimVerdict.GRANTED, ClaimVerdict.GRANTED, null, ClaimVerdict.GRANTED), verdicts)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a claim message that cannot be handed over in time gives that claim up and sends nothing more for it`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val delivered = Collections.synchronizedList(mutableListOf<String>())
            val sender = WakeClaimSender(scope, resolveNode = { "phone-1" }, send = { _, bytes ->
                val message = WakeClaimMessage.decode(bytes)!!
                if (message.claimId == "claim-hang-a") delay(5_000)   // the transport hangs on this one
                delivered += "${message.op}:${message.claimId}"
            }, timeoutMs = 200)
            sender.offer(WakeClaimMessage(WakeClaimMessage.Op.CLAIM, "claim-hang-a", 7, 1, 0))
            sender.offer(WakeClaimMessage(WakeClaimMessage.Op.RELEASE, "claim-hang-a"))
            sender.offer(WakeClaimMessage(WakeClaimMessage.Op.CLAIM, "claim-hang-b", 7, 2, 0))
            runBlocking { repeat(100) { if (delivered.isEmpty()) delay(30) } }
            assertEquals("the late claim is not followed by its release out of order, and the next episode goes through", listOf("CLAIM:claim-hang-b"), delivered.toList())
            assertFalse(sender.accepts("claim-hang-a", "phone-1"))
            // The Phone never reachable: nothing is sent, the device's claim timer fails the episode closed.
            val none = WakeClaimSender(scope, resolveNode = { null }, send = { _, _ -> delivered += "sent" }, timeoutMs = 200)
            none.offer(WakeClaimMessage(WakeClaimMessage.Op.CLAIM, "claim-hang-c", 7, 1, 0))
            runBlocking { delay(200) }
            assertEquals(listOf("CLAIM:claim-hang-b"), delivered.toList())
        } finally {
            scope.cancel()
        }
    }
}
