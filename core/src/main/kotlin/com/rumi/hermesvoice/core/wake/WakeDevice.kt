package com.rumi.hermesvoice.core.wake

import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.settings.VadSilence
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
 */
class WakeDeviceController(
    val device: VoiceOrigin,
    recognizer: WakeRecognizerPort,
    timer: WakeTimerPort,
    private val port: WakeDevicePort,
    clock: () -> Long,
    initial: WatchSettings = WatchSettings(),
) {
    private val host = object : WakeHostPort {
        override fun windowChanged(open: Boolean) = port.windowChanged(open)
        override fun handoff(generation: Long, handoff: WakeOutcome.Handoff) = onHandoff(generation, handoff)
        override fun closed(reason: String) = port.closed(reason)
    }
    private val window = WakeWindowCoordinator(recognizer, timer, host, clock)
    private val handoffGate = WakeHandoffGate()
    private var handoffGeneration = -1L
    private var resumed = false

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
        settings = next
        if (enabledHere) requestArm("settings") else disable("opt_out")
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
        window.newGeneration("screen_off")
        port.cancelRequestCapture("screen_off")
    }

    fun onScreenOn() = requestArm("screen_on")

    /** Something else owns the microphone or speaker (push-to-talk, a turn, playback). */
    fun onBusy() {
        cancelHandoff()
        window.close("busy")
    }

    fun onIdle() = requestArm("idle")

    fun onPermissionGranted() = requestArm("permission")

    /** Opens a window unless a gate blocks it; the blocking reason (also reported to the port), or null. */
    fun requestArm(source: String): WakeBlock? {
        val platform = port.armInputs()
        // A pending handoff means the app's recorder is about to take the microphone.
        val inputs = platform.copy(enabled = enabledHere && settingsCurrent, resumed = resumed && platform.resumed,
            talkIdle = platform.talkIdle && !handoffGate.pending)
        val block = window.requestArm(inputs)
        if (block != null) port.armBlocked(source, block)
        return block
    }

    fun onResults(generation: Long, hypotheses: List<String>, final: Boolean) {
        if (!enabledHere) return
        window.onResults(generation, hypotheses, final, settings.wakePatterns)
    }

    fun onError(generation: Long, code: Int) = window.onError(generation, code)

    fun onTimer() = window.onTimer()

    /**
     * The mic-handoff pause is over: start the recorder if this handoff is still for the current
     * generation, the app is resumed, nothing is capturing, and this device still listens.
     */
    fun onHandoffDue(captureIdle: Boolean): Boolean {
        if (!handoffGate.claim(handoffGeneration, generation, resumed, captureIdle && enabledHere)) return false
        return port.startRequestCapture(VadSilence.millis(settings.vadSilenceSeconds))
    }

    /**
     * Debug QA only: the phrase-only handoff exactly as a recognizer match would produce it (no
     * recognition). Like a match, it first closes an open window, releasing the recognizer.
     */
    fun qaSecondUtterance() {
        if (!enabledHere || !resumed) return
        window.close("qa_handoff")
        onHandoff(generation, WakeOutcome.Handoff(WakeHandoff.SECOND_UTTERANCE, ""))
    }

    private fun onHandoff(generation: Long, handoff: WakeOutcome.Handoff) {
        if (!enabledHere || !resumed) return
        when (handoff.contract) {
            WakeHandoff.RECOGNIZED_REQUEST -> port.sendRecognized(handoff.request)
            WakeHandoff.SECOND_UTTERANCE -> {
                handoffGeneration = generation
                handoffGate.schedule(generation)
                port.scheduleHandoff(WakeContract.MIC_HANDOFF_MS)
            }
        }
    }

    private fun disable(reason: String) {
        cancelHandoff()
        window.close(reason)
        port.cancelRequestCapture(reason)
    }

    private fun cancelHandoff() {
        handoffGate.cancel()
        port.cancelHandoff()
    }
}
