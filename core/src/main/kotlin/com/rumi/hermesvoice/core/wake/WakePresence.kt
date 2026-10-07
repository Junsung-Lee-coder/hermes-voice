package com.rumi.hermesvoice.core.wake

import com.rumi.hermesvoice.core.settings.WakeGate

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

    /** Armed with the standby on, but this device's own screen is off and its screen-off preference is off: nothing listens until the screen is back. */
    WAITING_FOR_SCREEN,
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

    /** The first wait before a window is retried while the Phone is unreachable or the microphone is muted. */
    const val BLOCKED_RETRY_MS = 15_000L
    const val COOLDOWN_MARGIN_MS = 100L

    /** The longest wait between retries while the Phone is unreachable or the microphone is muted (the wait doubles up to it). */
    const val MAX_BLOCKED_RETRY_MS = 300_000L

    /** The wait before the [retries]-th (0-based) consecutive blocked retry: doubles from [BLOCKED_RETRY_MS] up to [MAX_BLOCKED_RETRY_MS]. */
    fun blockedRetryDelay(retries: Int): Long = (BLOCKED_RETRY_MS shl retries.coerceIn(0, 16)).coerceAtMost(MAX_BLOCKED_RETRY_MS)

    private val next = setOf("timeout", "not_matched", "unfinished_request", "request_too_long", "wake_taken", "wake_claim_failed",
        "wake_claim_timeout", "wake_mode_changed", "recognizer_error_6", "recognizer_error_7")
    private val followedElsewhere = setOf("unavailable", "busy", "opt_out", "pause", "screen_off", "resume", "rearm", "qa_handoff", "foreground_excluded", "standby_off", "screen_off_disallowed")

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
class WakePresence(
    private val wake: WakeDeviceController,
    private val port: WakePresencePort,
    /**
     * The device's one wake flow serves both the app on screen and the hidden session (the Watch): it listens under
     * the foreground location while the app is shown with the screen on, and under its own standby switch otherwise.
     * False: the flow is fixed to the gate it was built with (the Phone's background-only flow).
     */
    private val followsVisibility: Boolean = false,
) {
    var visible = false
        private set

    /** The screen is on (a screen off over the visible app counts as hidden for an armed session). */
    private var screenOn = true

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

    /** Consecutive windows that could not open because the Phone is unreachable or the microphone is muted (sets the doubling wait). */
    private var blockedRetries = 0

    /** The scheduled wait is a blocked retry that a changed condition (the Phone back, the microphone unmuted) may cut short. */
    private var waitingForCondition = false

    /** The device's recognizer reported that it isn't there. */
    val recognizerUnavailable: Boolean get() = unavailable

    val loop: WakeLoop
        get() = when {
            !armed -> WakeLoop.OFF
            wake.screenDenied -> WakeLoop.WAITING_FOR_SCREEN
            unavailable -> WakeLoop.NO_RECOGNIZER
            retrying -> WakeLoop.RETRYING
            else -> WakeLoop.ACTIVE
        }

    /**
     * Moves the wake flow to the gate that applies now: the standby while a session is armed and the app is hidden or the
     * screen is off, the foreground location otherwise. Entering the standby with the foreground excluding the device
     * opens the next window; entering the foreground with the location excluding it closes the idle window and the
     * gap's alarm (an accepted recording goes on). Called after every change of visibility, screen or arming.
     */
    private fun syncGate(opensWindow: Boolean = true) {
        // The screen is the device's own, from the platform now as well as from events: a missed event or a stale flag cannot make a dark screen look on.
        val screen = screenOn && wake.platformScreenInteractive()
        val next = when {
            !followsVisibility -> wake.gate
            armed && !(visible && screen) -> WakeGate.STANDBY
            else -> WakeGate.FOREGROUND
        }
        if (next == wake.gate && screen == wake.screenInteractive) return
        val wasEnabled = wake.enabledHere
        wake.setGate(next, screen)
        if (!armed) return
        if (!wake.enabledHere) {
            // Nothing may listen now: no gap, retry or counter of the idle loop outlives that.
            if (next == WakeGate.FOREGROUND || wasEnabled || pendingRearm != null) {
                cancelRearm()
                failures = 0
                blockedRetries = 0
                retrying = false
            }
        } else if (opensWindow && !wasEnabled) {
            rearm("standby")
        }
    }

    /**
     * The device's own screen or the standby settings may have changed without an event reaching the presence (the
     * Phone has no visibility events; the Watch's display listener and screen receiver may race the settings): bring the
     * gate and the screen fact in line with the platform now, tearing the idle loop down or reopening it exactly once.
     */
    fun reconcile() = syncGate()

    /**
     * New settings were applied to the wake flow: a device they leave without permission to listen keeps no gap, retry or
     * counter; one they newly allow opens exactly one window (a repeat of the same settings never does).
     */
    fun settingsApplied(wasEnabled: Boolean) {
        if (!armed) return
        if (!wake.enabledHere) {
            if (wasEnabled || pendingRearm != null) dropPendingRetry()
        } else if (!wasEnabled && wake.gate == WakeGate.STANDBY) {
            rearm("settings")
        }
    }

    fun onActivityResumed(settingsPending: Boolean) {
        visible = true
        screenOn = true
        syncGate()
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
        if (armed) return syncGate()
        wake.onPause()
        port.cancelCapture("pause")
    }

    fun onScreenOff() {
        screenOn = false
        if (!armed) wake.onScreenOff() else syncGate()
    }

    fun onScreenOn() {
        screenOn = true
        if (!armed) wake.onScreenOn() else syncGate()
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
        syncGate(opensWindow = false)
        failures = 0
        blockedRetries = 0
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
        // A window the screen closed while this device is still eligible (the screen-off preference is on) is followed like any quiet window.
        if (reason == "screen_off" && wake.enabledHere) return schedule(Rearm(ContinuousWakePolicy.REARM_MS, failure = false))
        val next = ContinuousWakePolicy.next(reason, failures) ?: return
        failures = if (next.failure) failures + 1 else 0
        schedule(next)
    }

    /** A window could not open. [cooldownRemainingMs]: how long the playback cooldown still lasts. */
    fun onArmBlocked(block: WakeBlock, cooldownRemainingMs: Long) {
        if (!armed) return
        when (block) {
            WakeBlock.COOLDOWN -> schedule(Rearm(cooldownRemainingMs + ContinuousWakePolicy.COOLDOWN_MARGIN_MS, failure = false))
            WakeBlock.PHONE_UNREACHABLE, WakeBlock.MICROPHONE_MUTED -> {
                schedule(Rearm(ContinuousWakePolicy.blockedRetryDelay(blockedRetries), failure = true))
                blockedRetries += 1
                waitingForCondition = true
            }
            else -> Unit
        }
    }

    /**
     * The thing a blocked window waited for may have changed (the Phone became reachable, the microphone was unmuted): the
     * next window opens now instead of at the end of the doubling wait. Nothing else is rescheduled, so an event while no
     * blocked retry waits changes nothing.
     */
    fun onConditionChanged() {
        if (!armed || !waitingForCondition) return
        cancelRearm()
        rearm("condition")
    }

    /** The standby was switched off while a recording defers the disarm: the idle loop gives up its pending gap, retry and counters now. */
    fun dropPendingRetry() {
        failures = 0
        blockedRetries = 0
        retrying = false
        cancelRearm()
    }

    fun onBusy() = wake.onBusy()

    /** A later reply holds this device's speaker: the open window stops listening; an episode under way goes on. */
    fun onPlaybackBusy() = wake.onPlaybackBusy()

    fun onIdle() {
        if (armed) rearm("idle") else wake.onIdle()
    }

    fun onRearmDue() {
        pendingRearm = null
        waitingForCondition = false
        if (!armed) return
        // A due gap re-checks the screen first: a late alarm never resurrects listening the screen no longer allows.
        syncGate(opensWindow = false)
        rearm("rearm")
    }

    private fun schedule(next: Rearm) {
        waitingForCondition = false
        pendingRearm = next
        retrying = next.failure
        port.scheduleRearm(next.delayMs)
    }

    private fun cancelRearm() {
        pendingRearm = null
        waitingForCondition = false
        port.cancelRearm()
    }

    private fun rearm(source: String) {
        if (unavailable) return
        // A window that opens means the loop works again; one that is blocked schedules its own retry.
        if (wake.rearm(source) == null) {
            retrying = false
            blockedRetries = 0
        }
    }
}
