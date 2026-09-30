package com.rumi.hermesvoice.core.wake

/** When to open the next window of an armed background session; [failure] counts toward the back-off. */
data class Rearm(val delayMs: Long, val failure: Boolean)

/** What an armed background session's wake loop is doing, for what the user is told. */
enum class WakeLoop {
    /** No background session is armed. */
    OFF,

    /** Listening, between two windows, or busy with a request. */
    ACTIVE,

    /** Waiting to try again: the recognizer failed, the Phone is unreachable or the microphone is muted. */
    RETRYING,

    /** The device has no recognizer: the loop stopped. */
    NO_RECOGNIZER,
}

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

    /** The next window is scheduled (null: none); kept until it is due, so the gap can be held awake. */
    var pendingRearm: Rearm? = null
        private set

    /** The last scheduled wait was a retry (a failure, an unreachable Phone or a muted microphone). */
    private var retrying = false

    /** The device's recognizer reported that it isn't there. */
    val recognizerUnavailable: Boolean get() = unavailable

    val loop: WakeLoop
        get() = when {
            !armed -> WakeLoop.OFF
            unavailable -> WakeLoop.NO_RECOGNIZER
            retrying -> WakeLoop.RETRYING
            else -> WakeLoop.ACTIVE
        }

    fun onActivityResumed(settingsPending: Boolean) {
        visible = true
        // A recognizer may have been installed meanwhile: every show tries again.
        unavailable = false
        if (!armed) return wake.onResume(settingsPending)
        // Armed: the flow may have been paused before the session was armed; it counts as shown again.
        wake.markResumed()
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

    /**
     * The background session's microphone was armed (from the visible app) or disarmed (stop,
     * settings, permission). Arming is refused while the app is not visible; returns whether the
     * session is armed now.
     */
    fun onArmed(now: Boolean): Boolean {
        if (now == armed) return armed
        if (now && !visible) return false
        armed = now
        failures = 0
        retrying = false
        cancelRearm()
        if (now) {
            wake.markResumed()
            wake.windowMs = WakeContract.BACKGROUND_WINDOW_MS
            rearm("session")
            return true
        }
        wake.windowMs = WakeContract.WINDOW_MS
        if (visible) return false
        wake.onPause()
        port.cancelCapture("pause")
        return false
    }

    /** The wake flow closed its window (or an episode) for [reason]. */
    fun onWindowClosed(reason: String) {
        if (reason == "unavailable") unavailable = true
        if (!armed) return
        val next = ContinuousWakePolicy.next(reason, failures) ?: return
        failures = if (next.failure) failures + 1 else 0
        schedule(next)
    }

    /** A window could not open. [cooldownRemainingMs]: how long the playback cooldown still lasts. */
    fun onArmBlocked(block: WakeBlock, cooldownRemainingMs: Long) {
        if (!armed) return
        when (block) {
            WakeBlock.COOLDOWN -> schedule(Rearm(cooldownRemainingMs + ContinuousWakePolicy.COOLDOWN_MARGIN_MS, failure = false))
            WakeBlock.PHONE_UNREACHABLE, WakeBlock.MICROPHONE_MUTED -> schedule(Rearm(ContinuousWakePolicy.BLOCKED_RETRY_MS, failure = true))
            else -> Unit
        }
    }

    fun onBusy() = wake.onBusy()

    fun onIdle() {
        if (armed) rearm("idle") else wake.onIdle()
    }

    fun onRearmDue() {
        pendingRearm = null
        if (armed) rearm("rearm")
    }

    private fun schedule(next: Rearm) {
        pendingRearm = next
        retrying = next.failure
        port.scheduleRearm(next.delayMs)
    }

    private fun cancelRearm() {
        pendingRearm = null
        port.cancelRearm()
    }

    private fun rearm(source: String) {
        if (unavailable) return
        // A window that opens means the loop works again; one that is blocked schedules its own retry.
        if (wake.rearm(source) == null) retrying = false
    }
}
