package com.rumi.hermesvoice.core.wake

import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.settings.VadSilence
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WakePhrasePatterns
import com.rumi.hermesvoice.core.settings.WatchSettings

/** What a device (Phone or Watch) does for its wake flow besides the recognizer and the timer. */
interface WakeDevicePort {
    fun windowChanged(open: Boolean)

    /** Runs [WakeDeviceController.onHandoffDue] after [delayMs] (replacing any earlier one). */
    fun scheduleHandoff(delayMs: Long)
    fun cancelHandoff()

    /**
     * Starts the app's own recorder for the spoken request; it ends after [silenceMs] of trailing
     * silence (the setting when it started), on no speech, or when the user taps. False if it could not start.
     */
    fun startRequestCapture(silenceMs: Long): Boolean

    /** Stops a hands-free request capture in progress, if any, WITHOUT sending it. Push-to-talk is not touched. */
    fun cancelRequestCapture(reason: String)

    /** Sends a request the recognizer heard in full after the wake phrase. */
    fun sendRecognized(request: String)

    fun closed(reason: String)

    /** Platform facts for arming (foreground, screen, permission, microphone, idle, reachability, cooldown). */
    fun armInputs(): WakeArmInputs

    /** A request to listen was refused; for logs and availability status. */
    fun armBlocked(source: String, block: WakeBlock) {}

    /** As [startRequestCapture]; [claimId] is the wake claim the recording must be sent with ("Both"), or null. */
    fun startRequestCapture(silenceMs: Long, claimId: String?): Boolean = startRequestCapture(silenceMs)

    /** As [sendRecognized]; [claimId] is the wake claim the request must be sent with ("Both"), or null. */
    fun sendRecognized(request: String, claimId: String?) = sendRecognized(request)
}

/**
 * How a device asks the Phone for the wake claim when both devices listen (see [WakeAdmission]).
 * On the Phone these are direct calls; on the Watch, Data Layer messages. Answers come back through
 * [WakeDeviceController.onClaimVerdict], the timer through [WakeDeviceController.onClaimTimer].
 */
interface WakeClaimPort {
    fun newClaimId(): String

    /** The number of answered wake requests this device knows of now ([WakeAdmission.epoch]); read before a window starts listening. */
    fun epoch(): Long

    /** Asks for the claim; [epoch] is what [epoch] returned when the window that heard the phrase opened. */
    fun request(claimId: String, settingsRevision: Long, generation: Long, epoch: Long)
    fun renew(claimId: String)
    fun release(claimId: String)

    /** (Re)schedules the single claim timer, replacing any earlier one. */
    fun scheduleTimer(delayMs: Long)
    fun cancelTimer()
}

/**
 * One device's foreground wake flow, independent of Android, shared by the Phone and the Watch:
 * it applies the Phone-owned [WatchSettings] (the device listens only when
 * [com.rumi.hermesvoice.core.settings.WakeLocation.listensOn] it), opens one recognizer window per
 * visibility generation ([WakeWindowCoordinator]), hands a phrase-only result to the app's recorder
 * through a cancellable, generation-bound gate ([WakeHandoffGate]) and sends a same-breath FINAL
 * request as text.
 *
 * Disabling the device, pausing, or the screen going off closes the window, cancels a pending
 * handoff and stops a hands-free capture without sending it; later callbacks of that window are
 * ignored. A device the mode excludes never listens, hands off or sends. A capture keeps the
 * trailing silence it started with; a changed setting applies to the next capture.
 *
 * When both devices listen ("Both") and a [WakeClaimPort] is given, the device asks the Phone for
 * the wake claim as soon as its recognizer reports a leading wake phrase (partial or final) and
 * records or sends only once the claim is granted; it renews the claim until its request is
 * handed over (the Watch then keeps renewing it until the Phone answers, see [WakeClaimTransit]),
 * and releases it when the episode ends without one. A refused, unanswered or lost claim fails
 * closed: the window closes with a notice, a recording in progress is stopped unsent. Verdicts
 * and timers for an older claim are ignored. A claim carries how many wake requests had been
 * answered when its window opened; a window that was already listening when another device's
 * request was admitted closes ([onEpisodeAnswered]) and could not claim anyway.
 *
 * Changing the wake location while a wake episode is under way (a claim asked, a handoff pending
 * or a request being recorded) cancels it with a notice, unsent: a request recorded under one
 * mode is never sent under another. Push-to-talk is not touched.
 */
class WakeDeviceController(
    val device: VoiceOrigin,
    recognizer: WakeRecognizerPort,
    timer: WakeTimerPort,
    private val port: WakeDevicePort,
    clock: () -> Long,
    initial: WatchSettings = WatchSettings(),
    private val claims: WakeClaimPort? = null,
) {
    private val host = object : WakeHostPort {
        override fun windowChanged(open: Boolean) = port.windowChanged(open)
        override fun handoff(generation: Long, handoff: WakeOutcome.Handoff) = onHandoff(generation, handoff)
        override fun closed(reason: String) {
            // The window ended without a request: an episode claimed on a partial result is over.
            if (heldHandoff == null && !capturing) releaseClaim()
            port.closed(reason)
        }
    }

    private class Claim(val id: String, var granted: Boolean = false)

    private var claim: Claim? = null

    /** A handoff waiting for the Phone's answer to the claim. */
    private var heldHandoff: Pair<Long, WakeOutcome.Handoff>? = null
    private var capturing = false

    /** Both devices may listen, so one wake episode must be admitted from one of them. */
    private val arbitrated: Boolean get() = claims != null && settings.wakeLocation == WakeLocation.BOTH
    private val window = WakeWindowCoordinator(recognizer, timer, host, clock)
    private val handoffGate = WakeHandoffGate()
    private var handoffGeneration = -1L
    private var resumed = false

    /** [WakeClaimPort.epoch] when the current window started listening. */
    private var windowEpoch = 0L

    /** False while the device may hold settings older than the Phone's (Watch, just resumed). */
    private var settingsCurrent = true

    var settings: WatchSettings = initial
        private set

    /** Whether this device listens under the current mode. */
    val enabledHere: Boolean get() = settings.wakeLocation.listensOn(device)

    /** The current visibility generation (app shown, or screen back on while shown). */
    val generation: Long get() = window.generation

    /** New settings: listen if this device is (still) included, otherwise stop everything wake-related now. */
    fun onSettings(next: WatchSettings) {
        val revised = next.revision != settings.revision
        val modeChanged = next.wakeLocation != settings.wakeLocation
        settings = next
        if (!enabledHere) return disable("opt_out")
        if (modeChanged && (claim != null || heldHandoff != null || handoffGate.pending || capturing)) {
            // Heard or being recorded under another mode: never sent under this one, and never recorded on in silence.
            endEpisode("wake_mode_changed")
        } else if (revised && claim != null && !capturing) {
            // A claim was made under the old settings: the Phone would refuse its request anyway.
            failClaim("wake_claim_failed")
        }
        requestArm("settings")
    }

    /**
     * A wake request was admitted by the Phone (count [epoch], claim [claimId]). A window of this
     * device that was already listening can no longer answer that phrase: it closes, unless the
     * admitted request is this device's own.
     */
    fun onEpisodeAnswered(epoch: Long, claimId: String?) {
        if (epoch == windowEpoch || capturing) return
        if (claimId != null && claimId == claim?.id) return
        if (window.listening || heldHandoff != null || (claim != null && claim?.granted != true)) failClaim("wake_taken", release = claim?.granted == true)
    }

    /** The app became visible. [settingsPending]: wait for [onSettingsCurrent] before listening. */
    fun onResume(settingsPending: Boolean = false) {
        resumed = true
        settingsCurrent = !settingsPending
        window.newGeneration("resume")
        requestArm("resume")
    }

    /** The device's settings are now as current as it can know (e.g. the Watch read the synced item). */
    fun onSettingsCurrent() {
        settingsCurrent = true
        requestArm("settings_current")
    }

    fun onPause() {
        resumed = false
        disable("pause")
    }

    fun onScreenOff() {
        cancelHandoff()
        heldHandoff = null
        window.newGeneration("screen_off")
        port.cancelRequestCapture("screen_off")
        capturing = false
        releaseClaim()
    }

    fun onScreenOn() = requestArm("screen_on")

    /** Something else owns the microphone or speaker (push-to-talk, a turn, playback). */
    fun onBusy() {
        cancelHandoff()
        heldHandoff = null
        window.close("busy")
        if (!capturing) releaseClaim()
    }

    fun onIdle() = requestArm("idle")

    fun onPermissionGranted() = requestArm("permission")

    /** Opens a window unless a gate blocks it; the blocking reason (also reported to the port), or null. */
    fun requestArm(source: String): WakeBlock? {
        val platform = port.armInputs()
        // A pending handoff means the app's recorder is about to take the microphone.
        val inputs = platform.copy(enabled = enabledHere && settingsCurrent, resumed = resumed && platform.resumed,
            talkIdle = platform.talkIdle && !handoffGate.pending)
        // Read before the recognizer starts: what this window hears is newer than every request answered so far.
        val epoch = claims?.epoch() ?: 0L
        val wasListening = window.listening
        val block = window.requestArm(inputs)
        if (block == null && !wasListening) windowEpoch = epoch
        if (block != null) port.armBlocked(source, block)
        return block
    }

    fun onResults(generation: Long, hypotheses: List<String>, final: Boolean) {
        if (!enabledHere) return
        // Claim the episode at the first leading wake phrase, before anything is recorded or sent.
        if (arbitrated && claim == null && generation == this.generation && window.listening &&
            hypotheses.any { WakePhrasePatterns.leadingRequest(settings.wakePatterns, it) != null }) beginClaim()
        window.onResults(generation, hypotheses, final, settings.wakePatterns)
    }

    /** The Phone's answer to a claim or renewal. Answers about any other claim are ignored. */
    fun onClaimVerdict(claimId: String, verdict: ClaimVerdict) {
        val current = claim?.takeIf { it.id == claimId } ?: return
        if (verdict == ClaimVerdict.USED) return forgetClaim()
        if (verdict != ClaimVerdict.GRANTED) {
            return failClaim(if (verdict == ClaimVerdict.HELD_BY_OTHER) "wake_taken" else "wake_claim_failed", release = false)
        }
        if (current.granted) return
        current.granted = true
        claims?.scheduleTimer(WakeContract.CLAIM_RENEW_MS)
        heldHandoff?.let { (generation, handoff) ->
            heldHandoff = null
            proceed(generation, handoff)
        }
    }

    /** The claim timer fired: no answer in time fails closed; a held claim is renewed (without limit). */
    fun onClaimTimer() {
        val current = claim ?: return
        if (!current.granted) return failClaim("wake_claim_timeout")
        claims?.renew(current.id)
        claims?.scheduleTimer(WakeContract.CLAIM_RENEW_MS)
    }

    /** The hands-free recording ended. [sent]: it was handed to the Phone with its claim. */
    fun onRequestCaptureEnded(sent: Boolean) {
        capturing = false
        if (sent) forgetClaim() else releaseClaim()
    }

    fun onError(generation: Long, code: Int) = window.onError(generation, code)

    fun onTimer() = window.onTimer()

    /**
     * The mic-handoff pause is over: start the recorder if this handoff is still for the current
     * generation, the app is resumed, nothing is capturing, and this device still listens.
     */
    fun onHandoffDue(captureIdle: Boolean): Boolean {
        if (!handoffGate.claim(handoffGeneration, generation, resumed, captureIdle && enabledHere)) {
            releaseClaim()
            return false
        }
        if (arbitrated && claim?.granted != true) return false
        // Marked before the recorder starts: starting it reports "busy", which must not give the claim back.
        capturing = true
        capturing = port.startRequestCapture(VadSilence.millis(settings.vadSilenceSeconds), claim?.id)
        if (!capturing) releaseClaim()
        return capturing
    }

    /**
     * Debug QA only: the phrase-only handoff exactly as a recognizer match would produce it (no
     * recognition). Like a match, it first closes an open window, releasing the recognizer.
     */
    fun qaSecondUtterance() {
        if (!enabledHere || !resumed) return
        window.close("qa_handoff")
        windowEpoch = claims?.epoch() ?: 0L
        onHandoff(generation, WakeOutcome.Handoff(WakeHandoff.SECOND_UTTERANCE, ""))
    }

    private fun onHandoff(generation: Long, handoff: WakeOutcome.Handoff) {
        if (!enabledHere || !resumed) return releaseClaim()
        if (arbitrated) {
            if (claim == null) beginClaim()
            if (claim?.granted != true) {
                // Wait for the Phone: nothing is recorded or sent until this device owns the episode.
                heldHandoff = generation to handoff
                return
            }
        }
        proceed(generation, handoff)
    }

    private fun proceed(generation: Long, handoff: WakeOutcome.Handoff) {
        if (!enabledHere || !resumed || generation != this.generation) return releaseClaim()
        when (handoff.contract) {
            WakeHandoff.RECOGNIZED_REQUEST -> {
                val claimId = claim?.id
                forgetClaim()
                port.sendRecognized(handoff.request, claimId)
            }
            WakeHandoff.SECOND_UTTERANCE -> {
                handoffGeneration = generation
                handoffGate.schedule(generation)
                port.scheduleHandoff(WakeContract.MIC_HANDOFF_MS)
            }
        }
    }

    private fun beginClaim() {
        val port = claims ?: return
        val started = Claim(port.newClaimId())
        claim = started
        port.scheduleTimer(WakeContract.CLAIM_TIMEOUT_MS)
        port.request(started.id, settings.revision, generation, windowEpoch)
    }

    /** The claim was refused, unanswered or lost: stop everything of this episode, unsent, and say so. */
    private fun failClaim(reason: String, release: Boolean = true) {
        heldHandoff = null
        cancelHandoff()
        if (release) releaseClaim() else forgetClaim()
        capturing = false
        port.cancelRequestCapture(reason)
        if (window.listening) window.close(reason) else port.closed(reason)
    }

    /** Ends the wake episode under way, whatever stage it reached, unsent and with a notice. */
    private fun endEpisode(reason: String) = failClaim(reason)

    /** Gives the claim back to the Phone (the episode ended without a request). */
    private fun releaseClaim() {
        val current = claim ?: return
        forgetClaim()
        claims?.release(current.id)
    }

    /** Stops tracking the claim without releasing it (the Phone uses it up when it admits the request). */
    private fun forgetClaim() {
        if (claim == null) return
        claim = null
        claims?.cancelTimer()
    }

    private fun disable(reason: String) {
        heldHandoff = null
        capturing = false
        releaseClaim()
        cancelHandoff()
        window.close(reason)
        port.cancelRequestCapture(reason)
    }

    private fun cancelHandoff() {
        handoffGate.cancel()
        port.cancelHandoff()
    }
}
