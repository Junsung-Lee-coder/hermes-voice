package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import com.rumi.hermesvoice.core.wake.ClaimVerdict
import com.rumi.hermesvoice.core.wake.WakeArmInputs
import com.rumi.hermesvoice.core.wake.WakeClaim
import com.rumi.hermesvoice.core.wake.WakeClaimMessage
import com.rumi.hermesvoice.core.wake.WakeClaimPort
import com.rumi.hermesvoice.core.wake.WakeClaimService
import com.rumi.hermesvoice.core.wake.WakeClaimTransit
import com.rumi.hermesvoice.core.wake.WakeContract
import com.rumi.hermesvoice.core.wake.WakeDeviceController
import com.rumi.hermesvoice.core.wake.WakeDevicePort
import com.rumi.hermesvoice.core.wake.WakeEpochItem
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ACCEPTED_HOUR = 3_600_000L

private enum class Stage { HANDOFF_PENDING, CAPTURING }

private enum class Shown(val visible: Boolean, val standby: Boolean, val screenOff: Boolean) {
    VISIBLE_STANDBY_OFF(true, false, false),
    VISIBLE_STANDBY_ON(true, true, false),
    HIDDEN_CONTINUOUS(false, true, true),
    HIDDEN_FIVE_SECONDS(false, true, false),
}

/**
 * "Listen on" decides whether a device may START listening for a wake phrase. It never reaches back into a wake request the device
 * already accepted: a positively accepted handoff or recording completes under the settings it was accepted under, is sent once,
 * and nothing new is accepted afterwards. Driven through the real Phone and Watch classes; only the platform is fake.
 */
class AcceptedCaptureSelectionTest {
    private fun transitions(device: VoiceOrigin): List<Pair<WakeLocation, WakeLocation>> =
        WakeLocation.values().filter { it.listensOn(device) }.flatMap { from -> WakeLocation.values().filter { it != from }.map { from to it } }

    /** What a scenario needs from a device in some visibility state: the real controller and the calls the platform makes to it. */
    private class Subject(
        val wake: WakeDeviceController,
        val heard: (String, Boolean) -> Unit,
        val handoffDue: () -> Unit,
        val listenOn: (WakeLocation) -> Unit,
        val captureEnded: () -> Unit,
        val captured: () -> Int,
        val cancelled: () -> Boolean,
        val sent: () -> List<String>,
        val windows: () -> Int,
        val churn: () -> Unit,
        val dark: () -> Boolean,
        val anHourLater: () -> Unit,
    )

    private fun ofDev(d: Dev) = Subject(d.wake, { t, f -> d.heard(t, f) }, { d.handoffDue() }, { d.listenOn(it) }, { d.captureEnded() }, { d.captured() },
        { d.captureCancelled() }, { d.sent() }, { d.listenCount() },
        { d.staleRearm(); d.replaySettings(); d.eligibility(); d.idle() },
        { !d.wake.listening && !d.listenHold() && !d.handoffHold() && d.rearmAt == null }, { d.advanceTo(d.now + ACCEPTED_HOUR) })

    /** The Phone's own wake flow while its app is on screen (PhoneWakeController.wake): a bare foreground controller, resumed by the activity. */
    private fun phoneOnScreen(from: WakeLocation, standby: Boolean): Subject {
        val log = mutableListOf<String>()
        var settings = WatchSettings(from, "루미", revision = 1, phoneBackgroundWakeEnabled = standby)
        val port = object : WakeDevicePort {
            override fun windowChanged(open: Boolean) {}
            override fun scheduleHandoff(delayMs: Long) { log += "handoff_in" }
            override fun cancelHandoff() { log += "cancel_handoff" }
            override fun startRequestCapture(silenceMs: Long): Boolean { log += "capture"; return true }
            override fun cancelRequestCapture(reason: String) { log += "cancel_capture:$reason" }
            override fun sendRecognized(request: String) { log += "send:$request" }
            override fun closed(reason: String) { log += "closed:$reason" }
            override fun armInputs() = WakeArmInputs(true, true, true, false, true, false, true, true, 0, 0, 0, null)
        }
        val recognizer = object : WakeRecognizerPort, WakeTimerPort {
            override fun available() = true
            override fun start(generation: Long): Boolean { log += "listen"; return true }
            override fun release() {}
            override fun schedule(delayMs: Long) {}
            override fun cancel() {}
        }
        val wake = WakeDeviceController(VoiceOrigin.PHONE, recognizer, recognizer, port, { 0L }, settings)
        wake.onResume(settingsPending = false)
        fun replay() { settings = settings.copy(revision = settings.revision + 1); wake.onSettings(settings) }
        return Subject(wake,
            heard = { t, f -> wake.onResults(wake.generation, listOf(t), f) },
            handoffDue = { wake.onHandoffDue(captureIdle = true) },
            listenOn = { settings = settings.copy(wakeLocation = it, revision = settings.revision + 1); wake.onSettings(settings) },
            captureEnded = { wake.onRequestCaptureEnded(sent = true) },
            captured = { log.count { it == "capture" } },
            cancelled = { log.indexOf("capture").let { at -> at >= 0 && log.drop(at).any { it.startsWith("cancel_capture") } } },
            sent = { log.filter { it.startsWith("send:") } },
            windows = { log.count { it == "listen" } },
            churn = { replay(); replay() },
            dark = { !wake.listening },
            anHourLater = { replay() })
    }

    private fun build(device: VoiceOrigin, shown: Shown, from: WakeLocation): Subject {
        if (device == VoiceOrigin.PHONE && shown.visible) return phoneOnScreen(from, shown.standby)
        val d = listenDev(device, from, standby = shown.standby, screenOff = shown.screenOff)
        if (shown.visible) d.bootVisible() else d.boot()
        return ofDev(d)
    }

    private fun scenario(device: VoiceOrigin, shown: Shown, stage: Stage, from: WakeLocation, to: WakeLocation): List<String> {
        val label = "$device $shown $stage $from->$to"
        val problems = mutableListOf<String>()
        fun check(what: String, ok: Boolean) { if (!ok) problems += "$label: $what" }
        val d = build(device, shown, from)
        check("precondition: the device listens before the phrase", d.wake.listening)
        d.heard("루미", true)
        if (stage == Stage.CAPTURING) d.handoffDue()
        check("precondition: the accepted episode is under way", stage == Stage.HANDOFF_PENDING || d.captured() == 1)
        val windows = d.windows()
        val sentBefore = d.sent().size
        d.listenOn(to)
        check("the settings change did not cancel the accepted recording", !d.cancelled())
        if (stage == Stage.HANDOFF_PENDING) {
            d.handoffDue()
            check("the accepted handoff still started its recording", d.captured() == 1)
            check("the recording that followed the accepted handoff was not cancelled", !d.cancelled())
        }
        val selected = to.listensOn(device)
        if (!selected) {
            d.heard("루미 안녕하세요", true); d.heard("루미", false); d.heard("루미", true)
            d.churn()
            check("nothing new was sent after the deselect: ${d.sent()}", d.sent().size == sentBefore)
            check("no second recording started after the deselect", d.captured() == 1)
            check("no window was opened after the deselect", d.windows() == windows)
            check("nothing listens while the accepted recording runs", !d.wake.listening)
        }
        d.captureEnded()
        check("the recording was not cancelled when it ended", !d.cancelled())
        check("exactly one recording", d.captured() == 1)
        if (!selected) {
            d.anHourLater()
            check("dark an hour later", d.dark())
            check("no window an hour later", d.windows() == windows)
            check("nothing sent an hour later", d.sent().size == sentBefore)
        }
        return problems
    }

    private fun sweep(stage: Stage): List<String> {
        val problems = mutableListOf<String>()
        for (device in listOf(VoiceOrigin.PHONE, VoiceOrigin.WATCH)) for (shown in Shown.values()) for ((from, to) in transitions(device)) {
            problems += scenario(device, shown, stage, from, to)
        }
        return problems
    }

    @Test
    fun `a recording accepted before Listen on changed is never cut by the change, on either device, shown or hidden`() {
        assertEquals("every Listen on change while a wake recording runs", emptyList<String>(), sweep(Stage.CAPTURING))
    }

    @Test
    fun `a wake handoff accepted before Listen on changed still starts its recording, and nothing new is accepted after a deselect`() {
        assertEquals("every Listen on change inside the microphone handoff", emptyList<String>(), sweep(Stage.HANDOFF_PENDING))
    }

    @Test
    fun `a phrase heard but not yet accepted is still revoked by a deselect`() {
        for (device in listOf(VoiceOrigin.PHONE, VoiceOrigin.WATCH)) for (shown in Shown.values()) {
            val d = build(device, shown, WakeLocation.BOTH)
            d.heard("루미", false)
            d.listenOn(if (device == VoiceOrigin.PHONE) WakeLocation.WATCH else WakeLocation.PHONE)
            assertTrue("$device $shown: a partial is not an acceptance, so the deselect revokes it", d.dark())
            d.heard("루미 안녕하세요", true); d.handoffDue()
            assertEquals("$device $shown: nothing was recorded", 0, d.captured())
            assertTrue("$device $shown: nothing was sent: ${d.sent()}", d.sent().isEmpty())
        }
    }

    @Test
    fun `a manual recording and a deselect together change nothing`() {
        val phone = listenDev(VoiceOrigin.PHONE, WakeLocation.BOTH, standby = true, screenOff = true) as PhoneDev
        phone.boot()
        phone.rig.recording = true
        phone.listenOn(WakeLocation.WATCH)
        assertTrue("the manual recording goes on", phone.rig.recording)
    }

    // ── "Both": the Phone's admission of what a device accepted under another mode ───────────

    private inner class WatchRig(val h: CoreHarness, val node: String = "watch-a") : WakeRecognizerPort, WakeTimerPort, WakeDevicePort, WakeClaimPort, WatchTransport {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val states: MutableList<TurnStateMessage> = Collections.synchronizedList(mutableListOf())
        val lost = mutableListOf<String>()
        var captureClaim: String? = null
        var epoch = 0L
        private var ids = 0
        val controller = WakeDeviceController(VoiceOrigin.WATCH, this, this, this, { 0L }, h.settings.watchSettings(), claims = this)
        val transit = WakeClaimTransit({ System.currentTimeMillis() + h.clockOffsetMs },
            renew = { wire(WakeClaimMessage(WakeClaimMessage.Op.RENEW, it)) },
            release = { calls += "release_claim"; wire(WakeClaimMessage(WakeClaimMessage.Op.RELEASE, it)) },
            onLost = { turnId, reason -> lost += "$turnId:$reason" })

        private fun wire(message: WakeClaimMessage) {
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
        override fun sendRecognized(request: String, claimId: String?) { calls += "send:$request" }
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

        fun handOver(turnId: String, bytes: Int): WatchTurnUpload {
            val claim = captureClaim
            captureClaim = null
            controller.onRequestCaptureEnded(sent = true)
            claim?.let { transit.begin(it, turnId, WakeContract.transitLimitMs(bytes)) }
            return WatchTurnUpload(turnId, TurnTrigger.WAKE_PHRASE, WatchTurnUpload.MIME_WAV, TestAudio.speechWav(), claim)
        }

        fun arrives(upload: WatchTurnUpload) = runBlocking {
            h.core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath(upload.turnId), upload.toFrame().encode(), this@WatchRig)
        }
    }

    private inner class PhoneRig(val h: CoreHarness) : WakeRecognizerPort, WakeTimerPort, WakeDevicePort, WakeClaimPort {
        val calls = mutableListOf<String>()
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
        override fun sendRecognized(request: String, claimId: String?) { calls += "send:$request" }
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

        /** The recorder ended and its audio goes to the orchestrator with the claim it was started with, as PhoneViewModel does. */
        fun submitRecording(): VoiceTurnOutcome {
            val claim = captureClaim
            captureClaim = null
            controller.onRequestCaptureEnded(sent = true)
            return runBlocking {
                h.core.orchestrator.run(VoiceTurnRequest("turn-phone-${++ids}-${calls.size}", VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav",
                    PlaybackSink { _, _ -> }, wakeTurn = true, wakeClaimId = claim))
            }
        }
        fun hears(text: String, final: Boolean) = controller.onResults(controller.generation, listOf(text), final)
    }

    private class Rig(val h: CoreHarness, val phone: AcceptedCaptureSelectionTest.PhoneRig, val watch: AcceptedCaptureSelectionTest.WatchRig, val workId: String) {
        fun delivered() = h.fake.prompts.count { it.first == workId }
        fun pass(ms: Long, renewing: () -> Unit = {}) {
            var left = ms
            while (left > 0) {
                val step = minOf(left, WakeContract.CLAIM_RENEW_MS)
                h.clockOffsetMs += step
                left -= step
                renewing()
            }
        }

        /** What PhoneViewModel.updateWatch does: the Phone saves the new snapshot, then every wake flow reads it. */
        fun listenOn(location: WakeLocation) {
            h.settings.saveWatchSettings(h.settings.watchSettings().copy(wakeLocation = location))
            val now = h.settings.watchSettings()
            phone.controller.onSettings(now)
            watch.controller.onSettings(now)
        }
    }

    private fun rig(mode: WakeLocation, block: (Rig) -> Unit) {
        CoreHarness().use { h ->
            h.settings.saveWatchSettings(WatchSettings(mode, "루미"))
            h.fake.sourceScripts[AppSources.ROUTER] = { listOf(FakeHermesDashboard.complete("""{"action":"route","destination":"work","ack":"To work."}""")) }
            val work = runBlocking { h.core.sessions.createConversation("Work", "work", "") }.storedSessionId
            val r = Rig(h, PhoneRig(h), WatchRig(h), work)
            h.core.onWakeEpisode = { episode ->
                r.phone.controller.onEpisodeAnswered(episode.epoch, episode.claimId)
                val item = WakeEpochItem.parse(WakeEpochItem(episode.epoch, episode.claimId).toJson())!!
                r.watch.epoch = item.epoch
                r.watch.controller.onEpisodeAnswered(item.epoch, item.claimId)
            }
            r.phone.controller.onResume()
            r.watch.controller.onResume()
            block(r)
        }
    }

    @Test
    fun `a recording accepted under Both with a claim is delivered once, with its claim, whatever Listen on becomes`() {
        val problems = mutableListOf<String>()
        for (stage in Stage.values()) for (to in listOf(WakeLocation.OFF, WakeLocation.PHONE, WakeLocation.WATCH)) rig(WakeLocation.BOTH) { r ->
            val label = "WATCH BOTH->$to $stage"
            r.watch.hears("루미", final = true)
            if (stage == Stage.CAPTURING) r.watch.controller.onHandoffDue(captureIdle = true)
            if (r.watch.controller.episodePending == (stage == Stage.CAPTURING)) problems += "$label: precondition (accepted episode)"
            r.listenOn(to)
            if (stage == Stage.HANDOFF_PENDING) r.watch.controller.onHandoffDue(captureIdle = true)
            if (r.watch.calls.any { it.startsWith("cancel_capture") }) problems += "$label: cancelled: ${r.watch.calls}"
            if (r.watch.captureClaim == null) problems += "$label: the recording lost its claim"
            r.pass(12_000) { r.watch.controller.onClaimTimer() }
            val upload = r.watch.handOver("turn-watch-s${stage.ordinal}-${to.name.lowercase()}", TestAudio.speechWav().size)
            val outcome = r.watch.arrives(upload)
            if (outcome !is VoiceTurnOutcome.Completed) problems += "$label: not delivered: $outcome"
            if (r.delivered() != 1) problems += "$label: delivered ${r.delivered()} times"
            if (r.h.core.wakeAdmission.holder() != null) problems += "$label: the claim was left held"
            val again = r.watch.arrives(upload)
            if (again is VoiceTurnOutcome.Completed || r.delivered() != 1) problems += "$label: delivered twice"
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `a recording accepted by one device alone is admitted once after Listen on becomes Both, and only for that device`() {
        val problems = mutableListOf<String>()
        for (stage in Stage.values()) {
            rig(WakeLocation.WATCH) { r ->
                val label = "WATCH alone->BOTH $stage"
                r.watch.hears("루미", final = true)
                if (stage == Stage.CAPTURING) r.watch.controller.onHandoffDue(captureIdle = true)
                r.listenOn(WakeLocation.BOTH)
                if (stage == Stage.HANDOFF_PENDING) r.watch.controller.onHandoffDue(captureIdle = true)
                if (r.watch.calls.any { it.startsWith("cancel_capture") }) problems += "$label: cancelled: ${r.watch.calls}"
                if (!r.watch.calls.contains("capture")) problems += "$label: the accepted handoff never recorded: ${r.watch.calls}"
                val outcome = r.watch.arrives(r.watch.handOver("turn-watch-lone-s${stage.ordinal}", TestAudio.speechWav().size))
                if (outcome !is VoiceTurnOutcome.Completed) problems += "$label: not delivered: $outcome"
                if (r.delivered() != 1) problems += "$label: delivered ${r.delivered()} times"
                val second = r.watch.arrives(WatchTurnUpload("turn-watch-lone-again-s${stage.ordinal}", TurnTrigger.WAKE_PHRASE, WatchTurnUpload.MIME_WAV, TestAudio.speechWav(), null))
                if (second !is VoiceTurnOutcome.NotAdmitted) problems += "$label: a second claimless wake turn was admitted: $second"
                val other = r.phone.submitRecording()
                if (other !is VoiceTurnOutcome.NotAdmitted) problems += "$label: the Phone, which accepted nothing, was admitted without a claim: $other"
            }
            rig(WakeLocation.PHONE) { r ->
                val label = "PHONE alone->BOTH $stage"
                r.phone.hears("루미", final = true)
                if (stage == Stage.CAPTURING) r.phone.controller.onHandoffDue(captureIdle = true)
                r.listenOn(WakeLocation.BOTH)
                if (stage == Stage.HANDOFF_PENDING) r.phone.controller.onHandoffDue(captureIdle = true)
                if (r.phone.calls.any { it.startsWith("cancel_capture") }) problems += "$label: cancelled: ${r.phone.calls}"
                if (!r.phone.calls.contains("capture")) problems += "$label: the accepted handoff never recorded: ${r.phone.calls}"
                val outcome = r.phone.submitRecording()
                if (outcome !is VoiceTurnOutcome.Completed) problems += "$label: not delivered: $outcome"
                if (r.delivered() != 1) problems += "$label: delivered ${r.delivered()} times"
                val second = r.phone.submitRecording()
                if (second !is VoiceTurnOutcome.NotAdmitted) problems += "$label: a second claimless wake turn was admitted: $second"
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `an accepted claim that ran out is still refused after Listen on stopped selecting its device`() = rig(WakeLocation.BOTH) { r ->
        r.watch.hears("루미", final = true)
        r.watch.controller.onHandoffDue(captureIdle = true)
        r.listenOn(WakeLocation.PHONE)
        assertTrue("the accepted recording goes on: ${r.watch.calls}", r.watch.calls.none { it.startsWith("cancel_capture") })
        val upload = r.watch.handOver("turn-watch-ran-out", 1_000)
        r.pass(WakeContract.CLAIM_TTL_MS + 2_000)
        val outcome = r.watch.arrives(upload)
        assertTrue("an expired claim keeps its protection: $outcome", outcome is VoiceTurnOutcome.NotAdmitted)
        assertEquals(0, r.delivered())
    }

    @Test
    fun `a phrase heard under Both but not accepted gives its claim back at a deselect and records nothing`() {
        for (to in listOf(WakeLocation.OFF, WakeLocation.PHONE)) rig(WakeLocation.BOTH) { r ->
            r.watch.hears("루미", final = false)
            assertEquals("$to: the Watch holds the claim for its partial", VoiceOrigin.WATCH, r.h.core.wakeAdmission.holder())
            r.listenOn(to)
            assertNull("$to: released at once", r.h.core.wakeAdmission.holder())
            r.watch.hears("루미", final = true)
            assertTrue("$to: nothing was recorded: ${r.watch.calls}", r.watch.calls.none { it == "capture" || it == "handoff" })
            assertEquals(false, r.watch.controller.onHandoffDue(captureIdle = true))
            assertEquals(0, r.delivered())
        }
    }
}
