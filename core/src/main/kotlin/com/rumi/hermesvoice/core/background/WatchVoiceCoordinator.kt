package com.rumi.hermesvoice.core.background

import com.rumi.hermesvoice.core.KeyValueStore
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.wake.WakeArmInputs
import com.rumi.hermesvoice.core.wake.WakeBlock
import com.rumi.hermesvoice.core.wake.WakeContract
import com.rumi.hermesvoice.core.wake.WakeDeviceController
import com.rumi.hermesvoice.core.wake.WakeDevicePort
import com.rumi.hermesvoice.core.wake.WakeLoop
import com.rumi.hermesvoice.core.wake.WakePresence
import com.rumi.hermesvoice.core.wake.WakePresencePort

/** Whether the background session's notification (and its Stop) can really be seen now. */
enum class NotificationCapability {
    SHOWN,

    /** Android 13+: the notification permission isn't granted (asked and refused, or never answered). */
    NOT_ALLOWED,

    /** The app's notifications are switched off. */
    APP_OFF,

    /** The session's own channel is switched off. */
    CHANNEL_OFF;

    val shown: Boolean get() = this == SHOWN
}

/** What the background session and its wake loop are doing, as the user is told. */
data class WatchVoiceStatus(val session: BackgroundStatus, val loop: WakeLoop, val notification: NotificationCapability)

/** What [WatchVoiceCoordinator] needs from the Watch: live platform facts, timers and where its status goes. */
interface WatchVoiceHost {
    fun microphonePermission(): Boolean

    /** Asked each time: the platform's current answer, never a remembered one. */
    fun notifications(): NotificationCapability

    fun recognizerAvailable(): Boolean

    /** The Phone-owned settings as the Watch holds them now. */
    fun settings(): WatchSettings

    /** Runs [WatchVoiceCoordinator.onRearmTimer] after [delayMs], replacing any earlier one. */
    fun scheduleRearm(delayMs: Long)
    fun cancelRearm()

    /** Ends any recording in progress unsent (the app left the screen with no session armed). */
    fun cancelCapture(reason: String)

    /** Runs [block] on the same thread after the event being handled now. */
    fun post(block: () -> Unit)

    fun statusChanged(status: WatchVoiceStatus)
    fun log(line: String) {}
}

/**
 * The Watch's background session composed with its wake flow, independent of Android: the
 * decisions the runtime makes, in the order it makes them. It owns the session
 * ([BackgroundSession]), whether the wake flow follows the screen or the session ([WakePresence])
 * and the session's CPU holds ([WakeHolds]).
 *
 * - The microphone is armed only while the app is visible, only from the current visit (a
 *   settings read that finishes after the app was hidden is ignored), and only when nothing blocks
 *   it ([block]): the Phone's settings include the Watch, the microphone is allowed, a recognizer
 *   exists and the session's notification with its Stop can be seen. Anything that blocks it
 *   later disarms at once, wherever it comes from; nothing arms it from the background.
 * - A session never says it listens while its loop can't: a microphone the platform grants to a
 *   hidden app is dropped again, and a loop that stops (no recognizer) narrows the session to replies.
 * - While armed, some finite hold covers every step that can be under way with the screen off:
 *   the open window up to its deadline, the gap from the recognizer's release to the recorder
 *   (handoff pause, claim answer), and the gap between windows including the reachability check.
 *   A new hold is taken before the one it replaces is let go. The recorder, the transfer and
 *   playback take their own holds.
 */
class WatchVoiceCoordinator(
    store: KeyValueStore,
    key: String,
    service: BackgroundPort,
    private val host: WatchVoiceHost,
    val holds: WakeHolds,
    private val clock: () -> Long,
) {
    lateinit var wake: WakeDeviceController
        private set
    lateinit var presence: WakePresence
        private set

    val session: BackgroundSession = BackgroundSession(store, key, service, resumeWhenVisible = false) { onSessionChanged() }

    /** Counts the app's shows and hides; a settings read belongs to the show that started it. */
    var visit = 0L
        private set

    private var syncPosted = false
    private var last: WatchVoiceStatus? = null

    private val presencePort = object : WakePresencePort {
        override fun scheduleRearm(delayMs: Long) {
            host.scheduleRearm(delayMs)
            sync()
        }

        override fun cancelRearm() {
            host.cancelRearm()
            postSync()
        }

        override fun cancelCapture(reason: String) = host.cancelCapture(reason)
    }

    /** Binds the wake flow; call once, before any event reaches it. */
    fun attach(controller: WakeDeviceController) {
        wake = controller
        presence = WakePresence(controller, presencePort)
        publish()
    }

    /**
     * The device port the wake flow must be built with: [inner] does the platform work; this adds
     * the session's decisions (presence, the loop, holds) around it.
     */
    fun devicePort(inner: WakeDevicePort): WakeDevicePort = object : WakeDevicePort by inner {
        override fun windowChanged(open: Boolean) {
            inner.windowChanged(open)
            // A window that opens is held at once; one that closes is let go only after what follows it holds.
            if (open) sync() else postSync()
        }

        override fun scheduleHandoff(delayMs: Long) {
            inner.scheduleHandoff(delayMs)
            sync()
        }

        override fun cancelHandoff() {
            inner.cancelHandoff()
            postSync()
        }

        override fun startRequestCapture(silenceMs: Long): Boolean = inner.startRequestCapture(silenceMs).also { postSync() }

        override fun startRequestCapture(silenceMs: Long, claimId: String?): Boolean =
            inner.startRequestCapture(silenceMs, claimId).also { postSync() }

        override fun closed(reason: String) {
            inner.closed(reason)
            presence.onWindowClosed(reason)
            // No recognizer: the loop has stopped, so the session keeps only playback (no microphone kept for status).
            if (reason == "unavailable" && session.status.microphone) session.onMicrophoneBlock(MicBlock.NO_RECOGNIZER, presence.visible)
            sync()
        }

        /** Present while the app is visible or a session is armed; the screen state is waived only for an armed session. */
        override fun armInputs(): WakeArmInputs {
            val platform = inner.armInputs()
            return platform.copy(resumed = presence.present, interactive = presence.armed || platform.interactive)
        }

        override fun armBlocked(source: String, block: WakeBlock) {
            inner.armBlocked(source, block)
            val inputs = inner.armInputs()
            presence.onArmBlocked(block, (inputs.cooldownUntilMs - inputs.nowMs).coerceAtLeast(0L))
            if (presence.armed) when (block) {
                // Nothing can listen: say so instead of claiming to.
                WakeBlock.PERMISSION -> session.onMicrophoneBlock(MicBlock.PERMISSION, presence.visible)
                WakeBlock.NOT_FOREGROUND -> session.onMicrophoneStalled(session.generation)
                else -> Unit
            }
            sync()
        }
    }

    /** What stands in the way of arming the microphone now, from live platform facts; null when nothing does. */
    fun block(): MicBlock? = when {
        !host.settings().watchWakeEnabled -> MicBlock.NOT_WANTED
        !host.microphonePermission() -> MicBlock.PERMISSION
        !host.recognizerAvailable() -> MicBlock.NO_RECOGNIZER
        !host.notifications().shown -> MicBlock.NOTIFICATIONS
        else -> null
    }

    // ── the app ──────────────────────────────────────────────────────────────────────────────

    /** The app is on screen. Returns this visit; the settings read it starts reports back with it. */
    fun onActivityResumed(): Long {
        visit += 1
        presence.onActivityResumed(settingsPending = true)
        sync()
        return visit
    }

    /**
     * The settings read started by [visit] finished. Only if that visit is still the current one
     * (the app is on screen): the wake flow may listen and a running session may arm its
     * microphone. A read from an earlier visit changes nothing. Returns whether it was applied.
     */
    fun onSettingsPulled(visit: Long): Boolean {
        if (visit != this.visit || !presence.visible) {
            host.log("settings read of an earlier visit ignored (visit=$visit current=${this.visit} visible=${presence.visible})")
            return false
        }
        wake.onSettings(host.settings())
        presence.onSettingsCurrent()
        session.onVisible(block())
        sync()
        return true
    }

    fun onActivityPaused() {
        visit += 1
        presence.onActivityPaused()
        sync()
    }

    // ── the session ──────────────────────────────────────────────────────────────────────────

    /** The user's Start, from the visible app. */
    fun start(): BackgroundStatus = session.start(presence.visible, block()).also { sync() }

    /** The user's Stop (app or notification). Final; safe to repeat. */
    fun stop(): BackgroundStatus = session.stop().also { sync() }

    fun onServiceGone(generation: Long) {
        session.onServiceGone(generation)
        sync()
    }

    fun onMicrophoneRefused(generation: Long) {
        session.onMicrophoneRefused(generation)
        sync()
    }

    /**
     * Something that decides [block] may have changed (the Phone's settings, a permission answer,
     * notifications switched on or off). Blocked: disarm now. Unblocked: arm only while visible.
     */
    fun onEligibilityChanged() {
        wake.onSettings(host.settings())
        session.onMicrophoneBlock(block(), presence.visible)
        sync()
    }

    // ── the loop ─────────────────────────────────────────────────────────────────────────────

    /**
     * The gap before the next window is over. Returns whether to go on (the runtime then checks
     * reachability, bounded by [REACHABILITY_TIMEOUT_MS], and calls [onRearmDue]); the gap stays
     * held until then.
     */
    fun onRearmTimer(): Boolean = presence.armed

    fun onRearmDue() {
        // Checked at every window: a permission or notification lost meanwhile disarms (it can never arm here).
        val blocked = block()
        if (blocked != null && session.status.microphone) session.onMicrophoneBlock(blocked, presence.visible)
        presence.onRearmDue()
        sync()
    }

    /** The recognizer reported something: a pending partial may have moved the window's deadline. */
    fun onRecognizerActivity() = sync()

    fun onBusy() {
        presence.onBusy()
        sync()
    }

    fun onIdle() {
        presence.onIdle()
        sync()
    }

    fun onScreenOff() {
        presence.onScreenOff()
        sync()
    }

    fun onScreenOn() {
        presence.onScreenOn()
        sync()
    }

    // ── state ────────────────────────────────────────────────────────────────────────────────

    val status: WatchVoiceStatus
        get() = WatchVoiceStatus(session.status, if (::presence.isInitialized) presence.loop else WakeLoop.OFF, host.notifications())

    private fun onSessionChanged() {
        val status = session.status
        val armed = presence.onArmed(status.microphone)
        // Granted to an app that isn't visible (or a stale state): never keep a microphone the loop can't use.
        if (status.microphone && !armed) session.onMicrophoneStalled(session.generation)
        sync()
    }

    private fun postSync() {
        if (syncPosted) return
        syncPosted = true
        host.post {
            syncPosted = false
            sync()
        }
    }

    /** Moves the session's holds to what is under way now (new ones first), and reports the status. */
    fun sync() {
        val needed = HashMap<HoldReason, Long>()
        if (::presence.isInitialized && presence.armed) {
            val now = clock()
            if (wake.listening) needed[HoldReason.LISTEN] = (wake.windowDeadlineMs() - now).coerceAtLeast(0L) + HOLD_MARGIN_MS
            if (wake.episodePending) needed[HoldReason.HANDOFF] = WakeContract.CLAIM_TIMEOUT_MS + WakeContract.MIC_HANDOFF_MS + HOLD_MARGIN_MS
            presence.pendingRearm?.let { needed[HoldReason.REARM] = it.delayMs + REACHABILITY_TIMEOUT_MS + HOLD_MARGIN_MS }
        }
        holds.reconcile(MANAGED, needed)
        publish()
    }

    private fun publish() {
        val now = status
        if (now == last) return
        last = now
        host.statusChanged(now)
    }

    companion object {
        /** The holds this coordinator decides; the recorder, transfer and playback manage their own. */
        val MANAGED: Set<HoldReason> = setOf(HoldReason.LISTEN, HoldReason.HANDOFF, HoldReason.REARM)
        const val HOLD_MARGIN_MS = 5_000L

        /** The longest the reachability check before a window may take; the window follows either way. */
        const val REACHABILITY_TIMEOUT_MS = 5_000L
    }
}
