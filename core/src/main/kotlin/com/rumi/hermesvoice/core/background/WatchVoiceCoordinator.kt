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

    /**
     * Runs [WatchVoiceCoordinator.onRearmDue] after [delayMs], replacing any earlier one. The gap holds no wake lock, so the
     * host must back the wait with something that survives a sleeping CPU (the Watch: a non-exact alarm), and must run
     * [WatchVoiceCoordinator.onRearmDue] at most once per scheduled gap.
     */
    fun scheduleRearm(delayMs: Long)
    fun cancelRearm()

    /** Ends any recording in progress unsent (the app left the screen with no session armed). */
    fun cancelCapture(reason: String)

    /** Runs [block] on the same thread after the event being handled now. */
    fun post(block: () -> Unit)

    fun statusChanged(status: WatchVoiceStatus)
    fun log(line: String) {}

    /** A recording (push-to-talk or a hands-free request) is under way now. */
    fun recordingActive(): Boolean = false
}

/**
 * The Watch's background session composed with its wake flow, independent of Android: the
 * decisions the runtime makes, in the order it makes them. It owns the session
 * ([BackgroundSession]), whether the wake flow follows the screen or the session ([WakePresence])
 * and the session's CPU holds ([WakeHolds]).
 *
 * - The microphone is armed only while the app is visible, only once the current visit's
 *   settings read has applied (a read that finishes after the app was hidden, or belongs to an
 *   earlier show, is ignored; a Start before it runs the session for replies until it applies),
 *   and only when nothing blocks it ([block]): the Phone's settings include the Watch, the
 *   microphone is allowed, a recognizer exists and the session's notification with its Stop can
 *   be seen. Anything that blocks it later disarms at once, wherever it comes from; nothing arms
 *   it from the background. A session already armed by an earlier show keeps its loop while a
 *   new show's read is pending.
 * - A session never says it listens while its loop can't: a microphone the platform grants to a
 *   hidden app is dropped again, and a loop that stops (no recognizer) narrows the session to replies.
 * - While armed, a finite hold covers each step that is under way with the screen off: the open
 *   window up to its deadline, and the gap from the recognizer's release to the recorder (handoff
 *   pause, claim answer). The idle gap between two windows holds nothing: the host's timer (an
 *   alarm) brings the next window, which is a lower duty cycle than a held CPU. A new hold is
 *   taken before the one it replaces is let go. The recorder, the transfer and playback take their own holds.
 * - Standby is the Phone-owned switch [WatchSettings.watchBackgroundWakeEnabled]: it alone decides whether the
 *   session may arm its microphone. Turned off, the idle listening ends at once (window, timers, holds, a wake
 *   phrase not yet accepted for recording) while the session itself keeps running for replies; a recording
 *   under way (push-to-talk or hands-free) is never cut: the microphone is given back when it ends ([onIdle]).
 *   Turned on while hidden it is only requested: the microphone arms at the next real visit. The standby is background
 *   only ([WakePresence] moves the wake flow between its two gates): with the app shown and the screen on, the Phone's wake
 *   location alone decides whether the Watch listens (an armed session whose location excludes the Watch listens only once the
 *   app is hidden or the screen goes off), and the standby never opens a foreground window the location excludes.
 * - The Phone's reachability is a cached fact the host reports as an event ([onReachabilityChanged]); a window
 *   blocked by an unreachable Phone is retried with a doubling bounded wait and opened at once when it returns.
 * - Background operation is on by default ([ensureDefault]): every real open of the app starts it
 *   once, from the visible app; the user's Stop holds until the next real open ([onUserOpened]).
 *   A start is only a request ([BackgroundPort.confirmsEntry]): the microphone is armed, and the
 *   session says it listens, only once the service entered the foreground typed for it ([onServiceEntered]).
 */
class WatchVoiceCoordinator(
    private val store: KeyValueStore,
    private val key: String,
    private val service: BackgroundPort,
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

    /**
     * The current visit's settings read has applied ([onSettingsPulled] of this visit, while visible).
     * Cleared by every show and hide; nothing else sets it.
     */
    var settingsReady = false
        private set

    private var syncPosted = false

    /** The service's own report that it entered the foreground: (session generation, typed for the microphone). */
    private var admission: Pair<Long, Boolean>? = null

    /** The microphone the session holds is really the running service's (always, for a service whose start is its entry). */
    private val microphoneAdmitted: Boolean get() = !service.confirmsEntry || admission == (session.generation to true)

    /** The user stopped background operation during this open: nothing starts it again before the next real open. */
    private var stoppedThisOpen = store.getBoolean("$key.stopped", false)

    /** A restored process cannot request a default service before an actual launcher entry. */
    private var openedThisProcess = false

    /** This open already started (or tried to start) background operation; a failed or ended one is not retried in it. */
    private var triedThisOpen = false
    private var last: WatchVoiceStatus? = null

    /** Standby was turned off with the app hidden while a recording is under way: the microphone is given back when it ends. */
    private var disarmPending = false

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
        presence = WakePresence(controller, presencePort, followsVisibility = true)
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
            if (reason == "unavailable" && session.status.microphone) applyBlock(MicBlock.NO_RECOGNIZER)
            sync()
        }

        /** Present while the app is visible or a session is armed; the screen state is waived only for an armed session. */
        override fun armInputs(): WakeArmInputs {
            val platform = inner.armInputs()
            // An armed session waives the screen (and ambient/AOD) here; whether the screen is allowed is the preference's decision (WakeDeviceController.enabledHere).
            return platform.copy(resumed = !stoppedThisOpen && presence.present, interactive = presence.armed || platform.interactive,
                ambient = platform.ambient && !presence.armed)
        }

        override fun screenInteractive(): Boolean = inner.screenInteractive()

        override fun armBlocked(source: String, block: WakeBlock) {
            inner.armBlocked(source, block)
            val inputs = inner.armInputs()
            presence.onArmBlocked(block, (inputs.cooldownUntilMs - inputs.nowMs).coerceAtLeast(0L))
            if (presence.armed) when (block) {
                // Nothing can listen: say so instead of claiming to.
                WakeBlock.PERMISSION -> applyBlock(MicBlock.PERMISSION)
                WakeBlock.NOT_FOREGROUND -> session.onMicrophoneStalled(session.generation)
                // Armed, included by the settings, yet the flow can't open a window: a stall, never a silent no-op.
                WakeBlock.DISABLED -> if (wake.enabledHere) session.onMicrophoneStalled(session.generation)
                else -> Unit
            }
            sync()
        }
    }

    /**
     * What stands in the way of arming the microphone now, from live platform facts; null when
     * nothing does. A pending settings read of the visible show is the last precondition, and only
     * for a NEW arming: it never disarms a session that is already armed, and while the app is
     * hidden visibility itself keeps a new arming out.
     */
    fun block(): MicBlock? = when {
        !host.settings().watchBackgroundWakeEnabled -> MicBlock.NOT_WANTED
        !host.microphonePermission() -> MicBlock.PERMISSION
        !host.recognizerAvailable() -> MicBlock.NO_RECOGNIZER
        !host.notifications().shown -> MicBlock.NOTIFICATIONS
        presence.visible && !settingsReady && !session.status.microphone -> MicBlock.SETTINGS_PENDING
        else -> null
    }

    // ── the app ──────────────────────────────────────────────────────────────────────────────

    /** The app is on screen. Returns this visit; the settings read it starts reports back with it. */
    fun onActivityResumed(): Long {
        visit += 1
        settingsReady = false
        presence.onActivityResumed(settingsPending = true)
        // A session running for replies now waits for this show's read (and says so).
        session.onMicrophoneBlock(block(), visible = false)
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
        settingsReady = true
        wake.onSettings(host.settings())
        presence.onSettingsCurrent()
        session.onVisible(block())
        sync()
        return true
    }

    fun onActivityPaused() {
        visit += 1
        settingsReady = false
        presence.onActivityPaused()
        // Hidden: a pending read no longer applies; the session says it replies until opened (it can only disarm here).
        session.onMicrophoneBlock(block(), visible = false)
        sync()
    }

    // ── the session ──────────────────────────────────────────────────────────────────────────

    /** Starts the session from the visible app (see [ensureDefault]). */
    fun start(): BackgroundStatus {
        // Legacy explicit-start API (not called by passive callbacks or app UI). Only a visible
        // caller can authorize it; ensureDefault additionally requires an observed user open.
        if (presence.visible && stoppedThisOpen) onUserOpened()
        return session.start(presence.visible, block()).also { sync() }
    }

    /** The user's Stop (the notification's, or the system's). Final until the next real open; safe to repeat. */
    fun stop(): BackgroundStatus {
        stoppedThisOpen = true
        store.putBoolean("$key.stopped", true)
        val status = session.stop()
        // Disarming an on-screen session alone keeps foreground wake alive. Stop is stronger:
        // invalidate the recognizer generation and cancel its handoff/claim even while visible.
        wake.onPause()
        sync()
        return status
    }

    /** The user really opened the app (launched it, or brought it back to the front): background operation may start again. */
    fun onUserOpened() {
        stoppedThisOpen = false
        store.putBoolean("$key.stopped", false)
        openedThisProcess = true
        triedThisOpen = false
    }

    /**
     * Background operation is on by default: started once per real open from the visible app, unless the user stopped it
     * during this open. A running session is left exactly as it is (never restarted, doubled or stopped), and a start the
     * platform refused or a service it ended is not retried before the next open. True when a session started now.
     */
    fun ensureDefault(): Boolean {
        if (!openedThisProcess || stoppedThisOpen || triedThisOpen || session.status.running || !presence.visible) return false
        triedThisOpen = true
        return start().running
    }

    /**
     * The service of session [generation] entered the foreground, typed for the microphone or not. Only now does a
     * microphone the session asked for count as armed; one asked for meanwhile (the settings read finished before the
     * platform delivered the service) is armed after this event, while the app is still visible.
     */
    fun onServiceEntered(generation: Long, microphone: Boolean) {
        if (!session.isCurrent(generation)) return host.log("service entry of an ended session ignored (generation=$generation)")
        admission = generation to microphone
        host.post {
            if (presence.visible && session.isCurrent(generation)) session.onVisible(block())
            onSessionChanged()
        }
    }

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
        // The device's own screen first, so settings are applied against what the screen really is.
        if (::presence.isInitialized) presence.reconcile()
        val wasEnabled = wake.enabledHere
        wake.onSettings(host.settings())
        if (::presence.isInitialized) presence.settingsApplied(wasEnabled)
        applyBlock(block())
        sync()
    }

    /**
     * Gives the session's microphone back for [blocked] (or re-allows it). Hidden, a standby switched off never cuts a
     * recording under way: the idle listening is already revoked by the wake flow, and the microphone type stays until
     * the recording ends ([onIdle]).
     */
    private fun applyBlock(blocked: MicBlock?) {
        val defer = blocked == MicBlock.NOT_WANTED && presence.armed && !presence.visible && host.recordingActive()
        disarmPending = defer
        if (defer) presence.dropPendingRetry() else session.onMicrophoneBlock(blocked, presence.visible)
    }

    // ── the loop ─────────────────────────────────────────────────────────────────────────────

    /** The gap before the next window is over (the host's timer or alarm fired, once per scheduled gap). */
    fun onRearmDue() {
        // Checked at every window: a permission, notification or standby lost meanwhile disarms (it can never arm here).
        val blocked = block()
        if (blocked != null && session.status.microphone) applyBlock(blocked)
        presence.onRearmDue()
        sync()
    }

    /** The recognizer reported something: a pending partial may have moved the window's deadline. */
    fun onRecognizerActivity() = sync()

    /** The cached Phone reachability changed (a Data Layer event, never a poll): a window waiting for the Phone opens now. */
    fun onReachabilityChanged() {
        presence.onConditionChanged()
        sync()
    }

    fun onBusy() {
        presence.onBusy()
        sync()
    }

    fun onIdle() {
        if (disarmPending && !host.recordingActive()) {
            disarmPending = false
            session.onMicrophoneBlock(block(), presence.visible)
        }
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
        // Asked for but not entered yet: not armed (and not given back: the entry decides).
        val admitted = status.microphone && microphoneAdmitted
        val armed = presence.onArmed(admitted)
        // Granted to an app that isn't visible (or a stale state): never keep a microphone the loop can't use.
        if (admitted && !armed) session.onMicrophoneStalled(session.generation)
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
        val MANAGED: Set<HoldReason> = setOf(HoldReason.LISTEN, HoldReason.HANDOFF)
        const val HOLD_MARGIN_MS = 5_000L
    }
}
