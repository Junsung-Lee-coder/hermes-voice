package com.rumi.hermesvoice.core.wake

/** When to open the next window of an armed background session; [failure] counts toward the back-off. */
data class Rearm(val delayMs: Long, val failure: Boolean)

/**
 * What follows a closed recognizer window while a background session is armed. A quiet window,
 * speech that was not the phrase and an episode that ended are followed by the next window after
 * the recognizer has been released; a recognizer that fails backs off, doubling up to a minute;
 * a device without a recognizer stops. Closes that something else will follow up (busy, opt-out,
 * leaving) schedule nothing here.
 */
object ContinuousWakePolicy {
    /** Between two windows: the recognizer is released before the next one is created. */
    const val REARM_MS = WakeContract.MIC_HANDOFF_MS
    const val FIRST_BACKOFF_MS = 1_000L
    const val MAX_BACKOFF_MS = 60_000L

    /** How often a window is retried while the Phone is unreachable or the microphone is muted. */
    const val BLOCKED_RETRY_MS = 15_000L
    const val COOLDOWN_MARGIN_MS = 100L

    private val next = setOf("timeout", "not_matched", "unfinished_request", "request_too_long", "wake_taken", "wake_claim_failed",
        "wake_claim_timeout", "wake_mode_changed", "recognizer_error_6", "recognizer_error_7")
    private val followedElsewhere = setOf("unavailable", "busy", "opt_out", "pause", "screen_off", "resume", "rearm", "qa_handoff")

    /** [failures]: how many windows in a row already failed. Null: no window is scheduled. */
    fun next(reason: String, failures: Int): Rearm? = when (reason) {
        in next -> Rearm(REARM_MS, failure = false)
        in followedElsewhere -> null
        else -> Rearm((FIRST_BACKOFF_MS shl failures.coerceIn(0, 16)).coerceAtMost(MAX_BACKOFF_MS), failure = true)
    }
}

/** What [WakePresence] needs from the device besides the wake flow itself. */
interface WakePresencePort {
    /** Runs [WakePresence.onRearmDue] after [delayMs], replacing any earlier one. */
    fun scheduleRearm(delayMs: Long)
    fun cancelRearm()

    /** Ends any recording in progress unsent, push-to-talk included: the app left the screen with no background session. */
    fun cancelCapture(reason: String)
}

/**
 * Whether a device's wake flow follows the screen or a background session. Without a session the
 * foreground-only rules hold: leaving the screen or the screen going off closes the window and
 * ends a recording unsent, and one window opens per show. While a session's microphone is
 * [armed] (the user started it from the visible app), hiding the app and the screen going off
 * change nothing, windows of [WakeContract.BACKGROUND_WINDOW_MS] follow one another
 * ([ContinuousWakePolicy]), and the flow listens again after each request. Disarming with the app
 * hidden stops everything at once, unsent; with the app visible it returns to the foreground rules.
 */
class WakePresence(private val wake: WakeDeviceController, private val port: WakePresencePort) {
    var visible = false
        private set

    /** A background session's microphone is armed. */
    var armed = false
        private set

    /** The device may listen and record: its app is on screen, or a session is armed. */
    val present: Boolean get() = visible || armed

    private var failures = 0
    private var unavailable = false

    fun onActivityResumed(settingsPending: Boolean) {
        visible = true
        // A recognizer may have been installed meanwhile: every show tries again.
        unavailable = false
        if (!armed) return wake.onResume(settingsPending)
        if (!settingsPending) rearm("resume")
    }

    fun onSettingsCurrent() {
        wake.onSettingsCurrent()
        if (armed) rearm("settings_current")
    }

    fun onActivityPaused() {
        visible = false
        if (armed) return
        wake.onPause()
        port.cancelCapture("pause")
    }

    fun onScreenOff() {
        if (!armed) wake.onScreenOff()
    }

    fun onScreenOn() {
        if (!armed) wake.onScreenOn()
    }

    /** The background session's microphone was armed (from the visible app) or disarmed (stop, settings, permission). */
    fun onArmed(now: Boolean) {
        if (now == armed) return
        armed = now
        failures = 0
        port.cancelRearm()
        if (now) {
            wake.windowMs = WakeContract.BACKGROUND_WINDOW_MS
            rearm("session")
            return
        }
        wake.windowMs = WakeContract.WINDOW_MS
        if (visible) return
        wake.onPause()
        port.cancelCapture("pause")
    }

    /** The wake flow closed its window (or an episode) for [reason]. */
    fun onWindowClosed(reason: String) {
        if (reason == "unavailable") unavailable = true
        if (!armed) return
        val next = ContinuousWakePolicy.next(reason, failures) ?: return
        failures = if (next.failure) failures + 1 else 0
        port.scheduleRearm(next.delayMs)
    }

    /** A window could not open. [cooldownRemainingMs]: how long the playback cooldown still lasts. */
    fun onArmBlocked(block: WakeBlock, cooldownRemainingMs: Long) {
        if (!armed) return
        when (block) {
            WakeBlock.COOLDOWN -> port.scheduleRearm(cooldownRemainingMs + ContinuousWakePolicy.COOLDOWN_MARGIN_MS)
            WakeBlock.PHONE_UNREACHABLE, WakeBlock.MICROPHONE_MUTED -> port.scheduleRearm(ContinuousWakePolicy.BLOCKED_RETRY_MS)
            else -> Unit
        }
    }

    fun onBusy() = wake.onBusy()

    fun onIdle() {
        if (armed) rearm("idle") else wake.onIdle()
    }

    fun onRearmDue() {
        if (armed) rearm("rearm")
    }

    private fun rearm(source: String) {
        if (!unavailable) wake.rearm(source)
    }
}
