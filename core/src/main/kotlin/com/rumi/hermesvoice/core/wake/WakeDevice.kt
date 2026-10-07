package com.rumi.hermesvoice.core.wake

import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.settings.VadSilence
import com.rumi.hermesvoice.core.settings.WakeGate
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

    /**
     * Whether THIS device's own screen is interactive right now (ambient/AOD counts as not interactive), as the platform
     * says it, ignoring any waiver a wrapper applies for an armed session. Decides a standby's screen-off eligibility.
     */
    fun screenInteractive(): Boolean = armInputs().let { it.interactive && !it.ambient }

    /** A request to listen was refused; for logs and availability status. */
    fun armBlocked(source: String, block: WakeBlock) {}

    /** As [startRequestCapture]; [claimId] is the wake claim the recording must be sent with ("Both"), or null. */
    fun startRequestCapture(silenceMs: Long, claimId: String?): Boolean = startRequestCapture(silenceMs)

    /** As [sendRecognized]; [claimId] is the wake claim the request must be sent with ("Both"), or null. */
    fun sendRecognized(request: String, claimId: String?) = sendRecognized(request)

    /**
     * A wake phrase was accepted: a final match of the current window that passed every guard and,
     * in Both, the claim the Phone granted this device. Once per wake episode, before the recorder
     * handoff or the recognized request (the Phone gives a short pulse for it).
     */
    fun wakeAccepted() {}

    /**
     * The wake episode's hold on this device's microphone: true as soon as a wake phrase is heard
     * (a claim asked on a partial result, or a phrase accepted), before the accepted cue; false
     * once nothing of the episode is pending: its recording ([startRequestCapture]) or its request
     * ([sendRecognized]) took the hold over, or the episode ended without one. Nothing else (such
     * as a later reply) may start using this device's microphone or speaker meanwhile.
     */
    fun holdMicrophone(held: Boolean) {}
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
 * it applies the Phone-owned [WatchSettings] (the device listens only when its [gate] includes it: the
 * [com.rumi.hermesvoice.core.settings.WakeLocation] while its app is on screen, its own background standby switch
 * while it is hidden in an armed session, never one for the other), opens one recognizer window per
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

    /** [WakeDevicePort.holdMicrophone] was told true and not false yet. */
    private var microphoneHeld = false

    /** The episode owns the microphone from the phrase on (before the accepted cue). */
    private fun holdMicrophone() {
        if (microphoneHeld) return
        microphoneHeld = true
        port.holdMicrophone(true)
    }

    /** After every step: the hold goes back as soon as nothing of the episode is pending any more. */
    private fun <T> step(block: () -> T): T = try {
        block()
    } finally {
        if (microphoneHeld && !episodePending) {
            microphoneHeld = false
            port.holdMicrophone(false)
        }
    }

    private var claim: Claim? = null

    /** A handoff waiting for the Phone's answer to the claim. */
    private var heldHandoff: Pair<Long, WakeOutcome.Handoff>? = null
    private var capturing = false

    /** Both devices may listen, so one wake episode must be admitted from one of them. */
    private val arbitrated: Boolean get() = claims != null && settings.arbitrationRequired
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

    /** How long the next window stays open without hearing the phrase ([WakePresence] lengthens it for a background session). */
    var windowMs: Long = WakeContract.WINDOW_MS

    /** Which switch decides whether this device may listen now: the foreground location, or its own background standby. */
    var gate: WakeGate = WakeGate.FOREGROUND
        private set

    /**
     * Whether this device may listen under the current settings and [gate]: in the foreground the wake location alone
     * decides, in the standby the device's own standby switch alone (whatever the location). Whether a window really
     * opens is the presence's decision ([WakePresence]).
     */
    val enabledHere: Boolean get() = when (gate) {
        WakeGate.FOREGROUND -> settings.listensIn(device, WakeGate.FOREGROUND)
        WakeGate.STANDBY -> settings.standbyListens(device, screenInteractive)
    }

    /** This device's own screen was interactive when the presence last reconciled it (see [WakePresence]); only the standby gate reads it. */
    var screenInteractive: Boolean = true
        private set

    /** The standby is on but this device's own screen is off and its screen-off preference is off: it waits for the screen. */
    val screenDenied: Boolean
        get() = gate == WakeGate.STANDBY && settings.backgroundWakeEnabled(device) && !enabledHere

    /** The platform's own answer for this device's screen now (see [WakeDevicePort.screenInteractive]). */
    fun platformScreenInteractive(): Boolean = port.screenInteractive()

    private fun idleOffReason(): String = when {
        gate == WakeGate.FOREGROUND -> "foreground_excluded"
        !settings.backgroundWakeEnabled(device) -> "standby_off"
        else -> "screen_off_disallowed"
    }

    /**
     * The app moved between the foreground (on screen) and the standby (hidden, or the screen off, in an armed session), or
     * this device's own screen changed under the standby. Like a settings change: a device the new gate or screen excludes
     * stops idle listening now, a recording already accepted is not cut; one the gate includes is armed by the caller.
     */
    fun setGate(next: WakeGate, interactive: Boolean = screenInteractive): Unit = step {
        if (next == gate && interactive == screenInteractive) return@step
        gate = next
        screenInteractive = interactive
        if (enabledHere) return@step
        val reason = idleOffReason()
        if (capturing) revokeIdleListening(reason) else disable(reason)
    }

    /** The current visibility generation (app shown, or screen back on while shown). */
    val generation: Long get() = window.generation

    /** New settings: listen if this device is (still) included, otherwise stop everything wake-related now. */
    fun onSettings(next: WatchSettings): Unit = step {
        val revised = next.revision != settings.revision
        val modeChanged = next.wakeLocation != settings.wakeLocation
        settings = next
        // Only this device's standby switch went off: the idle listening is revoked, a recording under way is not (it ends and is sent as usual).
        if (!enabledHere && capturing && !modeChanged) return@step revokeIdleListening(idleOffReason())
        if (!enabledHere) return@step disable("opt_out")
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
    fun onEpisodeAnswered(epoch: Long, claimId: String?): Unit = step {
        if (epoch == windowEpoch || capturing) return@step
        if (claimId != null && claimId == claim?.id) return@step
        if (window.listening || heldHandoff != null || (claim != null && claim?.granted != true)) failClaim("wake_taken", release = claim?.granted == true)
    }

    /** The app became visible. [settingsPending]: wait for [onSettingsCurrent] before listening. */
    fun onResume(settingsPending: Boolean = false): Unit = step {
        resumed = true
        readyCuePending = true
        settingsCurrent = !settingsPending
        window.newGeneration("resume")
        requestArm("resume")
    }

    /** The device's settings are now as current as it can know (e.g. the Watch read the synced item). */
    fun onSettingsCurrent() {
        settingsCurrent = true
        requestArm("settings_current")
    }

    fun onPause(): Unit = step {
        resumed = false
        disable("pause")
    }

    /**
     * The app is on screen again while a background session keeps this device listening: the flow
     * counts as shown again without starting a new generation, so a window, handoff or recording
     * under way goes on (compare [onResume], which starts over).
     */
    fun markResumed() {
        resumed = true
    }

    /** A recognizer window is open. */
    val listening: Boolean get() = window.listening

    /** When the open window gives up without the phrase (it moves later while partial results keep changing). */
    fun windowDeadlineMs(): Long = window.deadline()

    /**
     * Between the phrase and the recorder: a claim asked or held without a recording, a handoff
     * waiting for the Phone's answer, or the microphone handoff pause.
     */
    val episodePending: Boolean get() = !capturing && (claim != null || heldHandoff != null || handoffGate.pending)

    fun onScreenOff(): Unit = step {
        readyCuePending = true
        cancelHandoff()
        heldHandoff = null
        window.newGeneration("screen_off")
        port.cancelRequestCapture("screen_off")
        capturing = false
        releaseClaim()
    }

    fun onScreenOn() = requestArm("screen_on")

    /**
     * A later reply was admitted to this device's speaker: an open window stops listening, so the
     * reply is never spoken into it. Unlike [onBusy], a wake episode under way is left alone: it owns
     * the microphone ([WakeDevicePort.holdMicrophone]), so that reply is stopped instead.
     */
    fun onPlaybackBusy(): Unit = step {
        if (!episodePending && !capturing) window.close("busy")
    }

    /** Something else owns the microphone or speaker (push-to-talk, a turn, playback). */
    fun onBusy(): Unit = step {
        cancelHandoff()
        heldHandoff = null
        window.close("busy")
        if (!capturing) releaseClaim()
    }

    fun onIdle() = requestArm("idle")

    fun onPermissionGranted() = requestArm("permission")

    /** Opens a window unless a gate blocks it; the blocking reason (also reported to the port), or null. */
    fun requestArm(source: String): WakeBlock? = step {
        val platform = port.armInputs()
        // A pending handoff means the app's recorder is about to take the microphone.
        val inputs = platform.copy(enabled = enabledHere && settingsCurrent, resumed = resumed && platform.resumed,
            talkIdle = platform.talkIdle && !handoffGate.pending)
        // Read before the recognizer starts: what this window hears is newer than every request answered so far.
        val epoch = claims?.epoch() ?: 0L
        val wasListening = window.listening
        val block = window.requestArm(inputs, windowMs)
        if (block == null && !wasListening) windowEpoch = epoch
        if (block != null) port.armBlocked(source, block)
        block
    }

    /**
     * Opens the next window of an armed background session: a new generation, so nothing of the
     * window before it can act on this one. Does nothing while a window is open or a wake episode
     * is under way (a claim asked, a handoff pending, a request being recorded).
     */
    fun rearm(source: String): WakeBlock? = step {
        if (window.listening) return@step WakeBlock.ALREADY_ARMED
        if (claim != null || heldHandoff != null || handoffGate.pending || capturing) return@step WakeBlock.BUSY
        window.newGeneration("rearm")
        requestArm(source)
    }

    fun onResults(generation: Long, hypotheses: List<String>, final: Boolean): Unit = step {
        if (!enabledHere) return@step
        // Claim the episode at the first leading wake phrase, before anything is recorded or sent.
        if (arbitrated && claim == null && generation == this.generation && window.listening &&
            hypotheses.any { WakePhrasePatterns.leadingRequest(settings.wakePatterns, it) != null }) beginClaim()
        window.onResults(generation, hypotheses, final, settings.wakePatterns)
    }

    /** The Phone's answer to a claim or renewal. Answers about any other claim are ignored. */
    fun onClaimVerdict(claimId: String, verdict: ClaimVerdict): Unit = step {
        val current = claim?.takeIf { it.id == claimId } ?: return@step
        if (verdict == ClaimVerdict.USED) return@step forgetClaim()
        if (verdict != ClaimVerdict.GRANTED) {
            return@step failClaim(if (verdict == ClaimVerdict.HELD_BY_OTHER) "wake_taken" else "wake_claim_failed", release = false)
        }
        if (current.granted) return@step
        current.granted = true
        claims?.scheduleTimer(WakeContract.CLAIM_RENEW_MS)
        heldHandoff?.let { (generation, handoff) ->
            heldHandoff = null
            proceed(generation, handoff)
        }
    }

    /** The claim timer fired: no answer in time fails closed; a held claim is renewed (without limit). */
    fun onClaimTimer(): Unit = step {
        val current = claim ?: return@step
        if (!current.granted) return@step failClaim("wake_claim_timeout")
        claims?.renew(current.id)
        claims?.scheduleTimer(WakeContract.CLAIM_RENEW_MS)
    }

    /** The hands-free recording ended. [sent]: it was handed to the Phone with its claim. */
    fun onRequestCaptureEnded(sent: Boolean): Unit = step {
        capturing = false
        if (sent) forgetClaim() else releaseClaim()
    }

    fun onError(generation: Long, code: Int): Unit = step { window.onError(generation, code) }

    fun onTimer(): Unit = step { window.onTimer() }

    /**
     * The recognizer of window [generation] is really ready for speech (the platform's
     * onReadyForSpeech). True, for one short "listening" pulse, only for the current listening
     * window and only once per armed session: a new show, or listening again after it was turned
     * off, paused or the screen went off. A background session's next windows ([rearm]) and a
     * window reopened in the same session are the same armed session and stay silent.
     */
    fun onRecognizerReady(generation: Long): Boolean {
        if (!readyCuePending || !enabledHere || generation != this.generation || !window.listening) return false
        readyCuePending = false
        return true
    }

    /** No ready pulse given yet in the current armed session. */
    private var readyCuePending = true

    /**
     * The mic-handoff pause is over: start the recorder if this handoff is still for the current
     * generation, the app is resumed, nothing is capturing, and this device still listens.
     */
    fun onHandoffDue(captureIdle: Boolean): Boolean = step {
        if (!handoffGate.claim(handoffGeneration, generation, resumed, captureIdle && enabledHere)) {
            releaseClaim()
            return@step false
        }
        if (arbitrated && claim?.granted != true) return@step false
        // Marked before the recorder starts: starting it reports "busy", which must not give the claim back.
        // The recording takes the episode's microphone hold over (WakeDevicePort.holdMicrophone).
        capturing = true
        capturing = port.startRequestCapture(VadSilence.millis(settings.vadSilenceSeconds), claim?.id)
        if (!capturing) releaseClaim()
        capturing
    }

    /**
     * Debug QA only: the phrase-only handoff exactly as a recognizer match would produce it (no
     * recognition). Like a match, it first closes an open window, releasing the recognizer.
     */
    fun qaSecondUtterance(): Unit = step {
        if (!enabledHere || !resumed) return@step
        window.close("qa_handoff")
        windowEpoch = claims?.epoch() ?: 0L
        onHandoff(generation, WakeOutcome.Handoff(WakeHandoff.SECOND_UTTERANCE, ""))
    }

    private fun onHandoff(generation: Long, handoff: WakeOutcome.Handoff) {
        if (!enabledHere || !resumed) return releaseClaim()
        // The phrase is heard: the microphone is this episode's before anything is held, cued or recorded.
        holdMicrophone()
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
        port.wakeAccepted()
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
        holdMicrophone()
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

    /** [disable] without ending the hands-free recording under way: the window, a pending handoff and an unaccepted claim go. */
    private fun revokeIdleListening(reason: String) {
        readyCuePending = true
        heldHandoff = null
        cancelHandoff()
        window.close(reason)
    }

    private fun disable(reason: String) {
        readyCuePending = true
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
