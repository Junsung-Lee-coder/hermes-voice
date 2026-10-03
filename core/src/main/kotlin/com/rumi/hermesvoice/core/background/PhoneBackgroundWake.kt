package com.rumi.hermesvoice.core.background

import com.rumi.hermesvoice.core.KeyValueStore
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.wake.WakeArmInputs
import com.rumi.hermesvoice.core.wake.WakeBlock
import com.rumi.hermesvoice.core.wake.WakeContract
import com.rumi.hermesvoice.core.wake.WakeDeviceController
import com.rumi.hermesvoice.core.wake.WakeDevicePort
import com.rumi.hermesvoice.core.wake.WakeLoop
import com.rumi.hermesvoice.core.wake.WakePresence
import com.rumi.hermesvoice.core.wake.WakePresencePort

/** What the Phone's background listening is doing, as the user is told. */
data class PhoneWakeStatus(val session: BackgroundStatus, val loop: WakeLoop, val listeningNow: Boolean)

/** What [PhoneBackgroundWake] needs from the Phone: live platform facts, a timer and where its status goes. */
interface PhoneBackgroundHost {
    /** The Phone-owned voice settings now. */
    fun settings(): WatchSettings
    fun microphonePermission(): Boolean

    /** An ON-DEVICE recognizer exists (the background never streams the room to a recognition server). */
    fun onDeviceRecognizer(): Boolean

    /** Asked each time: the platform's current answer, never a remembered one. */
    fun notifications(): NotificationCapability

    /** Runs [PhoneBackgroundWake.onRearmDue] after [delayMs], replacing any earlier one. */
    fun scheduleRearm(delayMs: Long)
    fun cancelRearm()

    /** Ends a background hands-free recording in progress unsent. */
    fun cancelCapture(reason: String)

    /** Runs [block] on the same thread after the event being handled now. */
    fun post(block: () -> Unit)

    fun statusChanged(status: PhoneWakeStatus)
    fun log(line: String) {}
}

/**
 * The Phone's opt-in background listening, independent of Android. The foreground wake phrase is
 * untouched: while the app is on screen its own flow owns the microphone, exactly as before. This
 * adds a second, background-only wake flow ([wake], built by the runtime with [devicePort]) that
 * owns the microphone only while the app is hidden (left, or the screen went off), and only when:
 *
 * - the user started it from the visible app ([start]); the choice is device-local and stored, off
 *   by default, and Stop (Settings or the notification's Stop) ends it for good ([stop]); a session
 *   the system ended reads as paused until the user starts it again;
 * - its foreground service was typed for the microphone while the app was visible (Android allows
 *   no other way): nothing arms the microphone from the background; and
 * - nothing blocks it ([block]): the wake location includes the Phone, the microphone is allowed,
 *   an ON-DEVICE recognizer exists, and the notification with Stop can be seen.
 *
 * Showing the app takes the microphone back at once: the background window closes, a pending
 * handoff is cancelled and an unfinished background recording is dropped unsent, before the
 * foreground flow resumes. Hidden, windows follow one another ([WakePresence] with
 * [com.rumi.hermesvoice.core.wake.ContinuousWakePolicy]: a failing recognizer backs off up to a
 * minute, a missing one stops the loop and the session says so). Every CPU hold is finite: the open
 * window up to its deadline, the handoff gap and the gap before the next window, the same as the
 * Watch's background session; the recorder, the request and playback take their own.
 */
class PhoneBackgroundWake(
    store: KeyValueStore,
    key: String,
    service: BackgroundPort,
    private val host: PhoneBackgroundHost,
    val holds: WakeHolds,
    private val clock: () -> Long,
) {
    lateinit var wake: WakeDeviceController
        private set
    private lateinit var presence: WakePresence

    val session: BackgroundSession = BackgroundSession(store, key, service, resumeWhenVisible = false) { onSessionChanged() }

    /** The app is on screen (its own wake flow owns the microphone). */
    var appVisible = false
        private set

    private var syncPosted = false
    private var last: PhoneWakeStatus? = null

    /** The background flow owns the microphone: the app is hidden and the session's microphone is armed. */
    val owning: Boolean get() = ::presence.isInitialized && presence.armed

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

    /** Binds the background wake flow; call once, before any event reaches it. */
    fun attach(controller: WakeDeviceController) {
        wake = controller
        presence = WakePresence(controller, presencePort)
        publish()
    }

    /** The device port the background wake flow must be built with: [inner] does the platform work. */
    fun devicePort(inner: WakeDevicePort): WakeDevicePort = object : WakeDevicePort by inner {
        override fun windowChanged(open: Boolean) {
            inner.windowChanged(open)
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
            // No (on-device) recognizer: the loop has stopped; the session must not claim to listen.
            if (reason == "unavailable" && session.status.microphone) disarm(MicBlock.NO_RECOGNIZER)
            sync()
        }

        /** Only the hidden app's armed session may listen; the screen may be off. */
        override fun armInputs(): WakeArmInputs {
            val platform = inner.armInputs()
            return platform.copy(enabled = platform.enabled && owning, resumed = owning, interactive = true)
        }

        override fun armBlocked(source: String, block: WakeBlock) {
            inner.armBlocked(source, block)
            val inputs = inner.armInputs()
            presence.onArmBlocked(block, (inputs.cooldownUntilMs - inputs.nowMs).coerceAtLeast(0L))
            if (owning && block == WakeBlock.PERMISSION) disarm(MicBlock.PERMISSION)
            sync()
        }
    }

    /** What stands in the way of listening in the background now, from live platform facts; null when nothing does. */
    fun block(): MicBlock? = when {
        !host.settings().wakeLocation.listensOn(VoiceOrigin.PHONE) -> MicBlock.NOT_WANTED
        !host.microphonePermission() -> MicBlock.PERMISSION
        !host.onDeviceRecognizer() -> MicBlock.NO_RECOGNIZER
        !host.notifications().shown -> MicBlock.NOTIFICATIONS
        else -> null
    }

    // ── the user ─────────────────────────────────────────────────────────────────────────────

    /** The user switched it on, from the visible app (refused otherwise). */
    fun start(): BackgroundStatus = session.start(appVisible, block()).also { sync() }

    /**
     * The user switched it off, or pressed Stop in the notification. Final; safe to repeat: the
     * window closes, the recognizer and microphone are released, a pending handoff or next window
     * is cancelled and an unfinished background recording is dropped unsent; holds are let go.
     */
    fun stop(): BackgroundStatus {
        val status = session.stop()
        release("stop")
        return status
    }

    // ── the app ──────────────────────────────────────────────────────────────────────────────

    /** The app is on screen: the background flow gives the microphone back before the app's own resumes. */
    fun onAppShown() {
        appVisible = true
        release("app_shown")
        // Visible: the only moment the session's microphone may be armed (or re-checked).
        session.onVisible(block())
        sync()
    }

    /** The app left the screen (or the screen went off): an armed session's flow takes over the microphone. */
    fun onAppHidden() {
        appVisible = false
        if (!session.status.microphone || block() != null) return sync()
        presence.onActivityResumed(settingsPending = false)
        if (!presence.onArmed(true)) presence.onActivityPaused()
        host.log("phone background listening took the microphone")
        sync()
    }

    /**
     * Something that decides [block] may have changed (the wake settings, a permission answer,
     * notifications). Blocked: disarm now, wherever the app is. Unblocked: arm only while visible.
     */
    fun onEligibilityChanged() {
        if (::wake.isInitialized) wake.onSettings(host.settings())
        val blocked = block()
        if (blocked != null) disarm(blocked) else session.onMicrophoneBlock(null, appVisible)
        sync()
    }

    fun onServiceGone(generation: Long) {
        session.onServiceGone(generation)
        sync()
    }

    fun onMicrophoneRefused(generation: Long) {
        session.onMicrophoneRefused(generation)
        sync()
    }

    // ── the loop ─────────────────────────────────────────────────────────────────────────────

    fun onRearmDue() {
        // Checked at every window: a permission or setting lost meanwhile disarms (it can never arm here).
        block()?.let { disarm(it) }
        if (owning) presence.onRearmDue()
        sync()
    }

    /** A request, playback or push-to-talk owns the microphone or speaker. */
    fun onBusy() {
        if (owning) presence.onBusy()
        sync()
    }

    /** A later reply holds the Phone's speaker: a window stops listening; a wake episode under way (it owns the microphone) goes on. */
    fun onPlaybackBusy() {
        if (owning) presence.onPlaybackBusy()
        sync()
    }

    fun onIdle() {
        if (owning) presence.onIdle()
        sync()
    }

    /** The recognizer reported something: a pending partial may have moved the window's deadline. */
    fun onRecognizerActivity() = sync()

    // ── state ────────────────────────────────────────────────────────────────────────────────

    val status: PhoneWakeStatus
        get() = PhoneWakeStatus(session.status, if (::presence.isInitialized) presence.loop else WakeLoop.OFF,
            owning && ::wake.isInitialized && wake.listening)

    private fun disarm(block: MicBlock) {
        session.onMicrophoneBlock(block, appVisible)
        release("disarmed")
    }

    /** The background flow stops listening and recording now (unsent) and holds nothing. */
    private fun release(reason: String) {
        if (!::presence.isInitialized) return
        val was = presence.armed
        presence.onArmed(false)
        presence.onActivityPaused()
        host.cancelRearm()
        if (was) host.log("phone background listening released the microphone reason=$reason")
        sync()
    }

    private fun onSessionChanged() {
        // The microphone was dropped (stop, refusal, a block): the background flow stops at once.
        if (!session.status.microphone && owning) release("session")
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

    /** Moves the background flow's holds to what is under way now (new ones first), and reports the status. */
    fun sync() {
        val needed = HashMap<HoldReason, Long>()
        if (owning) {
            val now = clock()
            if (wake.listening) needed[HoldReason.LISTEN] = (wake.windowDeadlineMs() - now).coerceAtLeast(0L) + HOLD_MARGIN_MS
            if (wake.episodePending) needed[HoldReason.HANDOFF] = WakeContract.CLAIM_TIMEOUT_MS + WakeContract.MIC_HANDOFF_MS + HOLD_MARGIN_MS
            presence.pendingRearm?.let { needed[HoldReason.REARM] = it.delayMs + HOLD_MARGIN_MS }
        }
        holds.reconcile(WatchVoiceCoordinator.MANAGED, needed)
        publish()
    }

    private fun publish() {
        val now = status
        if (now == last) return
        last = now
        host.statusChanged(now)
    }

    private companion object {
        const val HOLD_MARGIN_MS = 5_000L
    }
}
