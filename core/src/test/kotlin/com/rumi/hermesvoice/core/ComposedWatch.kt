package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.background.BackgroundNotice
import com.rumi.hermesvoice.core.background.BackgroundPort
import com.rumi.hermesvoice.core.background.BackgroundText
import com.rumi.hermesvoice.core.background.HoldReason
import com.rumi.hermesvoice.core.background.NotificationCapability
import com.rumi.hermesvoice.core.background.WakeHolds
import com.rumi.hermesvoice.core.background.WakeLockPort
import com.rumi.hermesvoice.core.background.WatchVoiceCoordinator
import com.rumi.hermesvoice.core.background.WatchVoiceHost
import com.rumi.hermesvoice.core.background.WatchVoiceStatus
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.wake.ClaimVerdict
import com.rumi.hermesvoice.core.wake.ContinuousWakePolicy
import com.rumi.hermesvoice.core.wake.WakeArmInputs
import com.rumi.hermesvoice.core.wake.WakeBlock
import com.rumi.hermesvoice.core.wake.WakeClaimPort
import com.rumi.hermesvoice.core.wake.WakeContract
import com.rumi.hermesvoice.core.wake.WakeDeviceController
import com.rumi.hermesvoice.core.wake.WakeDevicePort
import com.rumi.hermesvoice.core.wake.WakeLoop
import com.rumi.hermesvoice.core.wake.WakeRecognizerPort
import com.rumi.hermesvoice.core.wake.WakeTimerPort

/**
 * The Watch's background session composed as WatchVoiceRuntime composes it: the real
 * [WatchVoiceCoordinator] with the real [WakeDeviceController] (built on the coordinator's device
 * port), [com.rumi.hermesvoice.core.wake.WakePresence], [com.rumi.hermesvoice.core.background.BackgroundSession]
 * and [WakeHolds]. Only the platform is fake: the foreground service (which may refuse the
 * microphone), the recognizer, timers on a simulated clock, and live platform facts. Tests drive it
 * in the order the Android adapters produce events, including work posted to run after an event.
 */
internal class ComposedWatch(
    mode: WakeLocation = WakeLocation.WATCH,
    arbitrated: Boolean = false,
    /**
     * The foreground service as WatchVoiceService runs it: a start is only a REQUEST, and the service reports its entry
     * into the foreground later ([serviceEnters]); a retype goes through the running service at once. False: the legacy
     * double where a start counts as entered.
     */
    private val asyncService: Boolean = false,
    /** The Watch's background standby switch; null mirrors the legacy single selector (on exactly when [mode] lets the Watch listen). */
    watchStandby: Boolean? = null,
    phoneStandby: Boolean = false,
    /** The Watch's screen-off recognition preference. True by default so the screen-off scenarios of earlier builds keep their meaning. */
    watchScreenOff: Boolean = true,
    phoneScreenOff: Boolean = true,
) {
    var now = 1_000L
    val log = mutableListOf<String>()

    // ── platform facts, asked live ──
    var micPermission = true
    var notifications = NotificationCapability.SHOWN
    var recognizer = true
    var settings = WatchSettings(mode, "루미", revision = 1, phoneBackgroundWakeEnabled = phoneStandby,
        watchBackgroundWakeEnabled = watchStandby ?: mode.listensOn(VoiceOrigin.WATCH)).withScreenOff(phone = phoneScreenOff, watch = watchScreenOff)
    var screenOn = true

    /** The Watch is in ambient / always-on display mode (the platform's live answer). */
    var ambient = false
    var reachable: Boolean? = true
    var busy = false
    var cooldownUntil = 0L
    var serviceAcceptsMic = true
    var failStart = false

    /** An explicit push-to-talk recording is under way (the wake flow's own request capture is [capturing]). */
    var pttRecording = false

    // ── platform state ──
    var serviceType: String? = null

    /** An async start the platform has not delivered yet: the microphone type it asked for. */
    var pendingStart: Boolean? = null
    private var pendingGeneration = -1L
    var windowOpen = false
    var handoffIn: Long? = null
    var rearmIn: Long? = null
    var capturing = false
    val posted = ArrayDeque<() -> Unit>()
    val statuses = mutableListOf<WatchVoiceStatus>()
    val lockLog = mutableListOf<String>()
    val claimsSent = mutableListOf<String>()
    private var claimIds = 0

    val holds = WakeHolds(object : WakeLockPort {
        override fun acquire(reason: HoldReason, timeoutMs: Long) { lockLog += "acquire:$reason:$timeoutMs" }
        override fun release(reason: HoldReason) { lockLog += "release:$reason" }
    }) { now }

    private val service = object : BackgroundPort {
        override val confirmsEntry: Boolean get() = asyncService

        override fun startService(microphone: Boolean): Boolean {
            log += "startService(mic=$microphone)"
            if (failStart) return false
            if (asyncService) {
                // Only asked for: the platform delivers it later (serviceEnters), typed then.
                pendingStart = microphone
                pendingGeneration = coordinator.session.generation + 1
                return true
            }
            if (microphone && !serviceAcceptsMic) return false
            serviceType = if (microphone) "microphone|mediaPlayback" else "mediaPlayback"
            return true
        }

        override fun retypeService(microphone: Boolean): Boolean {
            log += "retype(mic=$microphone)"
            if (asyncService && serviceType == null) return false
            if (microphone && !serviceAcceptsMic) return false
            serviceType = if (microphone) "microphone|mediaPlayback" else "mediaPlayback"
            if (asyncService) coordinator.onServiceEntered(coordinator.session.generation, microphone)
            return true
        }

        override fun stopService() { log += "stopService"; serviceType = null; pendingStart = null }
    }

    /** The platform delivers the requested start to the service, which enters the foreground (as WatchVoiceService does). */
    fun serviceEnters() {
        val microphone = pendingStart ?: return
        pendingStart = null
        val typed = microphone && serviceAcceptsMic
        serviceType = if (typed) "microphone|mediaPlayback" else "mediaPlayback"
        log += "entered(mic=$typed)"
        if (microphone && !typed) coordinator.onMicrophoneRefused(pendingGeneration)
        coordinator.onServiceEntered(pendingGeneration, typed)
        drain()
    }

    private val host = object : WatchVoiceHost {
        override fun microphonePermission() = micPermission
        override fun notifications() = notifications
        override fun recognizerAvailable() = recognizer
        override fun settings() = settings
        override fun scheduleRearm(delayMs: Long) { rearmIn = delayMs }
        override fun cancelRearm() { rearmIn = null }
        override fun cancelCapture(reason: String) { if (capturing) { log += "cancel_capture:$reason"; capturing = false; holds.release(HoldReason.CAPTURE) } }
        override fun post(block: () -> Unit) { posted.addLast(block) }
        override fun statusChanged(status: WatchVoiceStatus) { statuses += status }
        override fun recordingActive() = capturing || pttRecording
    }

    val coordinator: WatchVoiceCoordinator = WatchVoiceCoordinator(InMemoryKeyValueStore(), "background_operation", service, host, holds) { now }

    /** What WatchVoiceRuntime's own port does (the platform side of the wake flow). */
    private val inner = object : WakeDevicePort {
        override fun windowChanged(open: Boolean) { windowOpen = open }
        override fun scheduleHandoff(delayMs: Long) { handoffIn = delayMs }
        override fun cancelHandoff() { handoffIn = null }
        override fun startRequestCapture(silenceMs: Long): Boolean = startRequestCapture(silenceMs, null)
        override fun startRequestCapture(silenceMs: Long, claimId: String?): Boolean {
            if (!micPermission) return false
            capturing = true
            holds.acquire(HoldReason.CAPTURE)
            log += "capture"
            return true
        }
        override fun cancelRequestCapture(reason: String) {
            if (capturing) { log += "cancel_capture:$reason"; capturing = false; holds.release(HoldReason.CAPTURE) }
        }
        override fun sendRecognized(request: String) { log += "send:$request" }
        override fun closed(reason: String) { log += "closed:$reason" }
        override fun armInputs() = WakeArmInputs(enabled = true, resumed = false, interactive = screenOn, ambient = ambient,
            permission = micPermission, microphoneMuted = false, talkIdle = !busy, phoneReachable = reachable, nowMs = now,
            cooldownUntilMs = cooldownUntil, generation = 0, lastArmedGeneration = null)
        override fun armBlocked(source: String, block: WakeBlock) { log += "blocked:$source:$block" }
    }

    private val recognizerPort = object : WakeRecognizerPort, WakeTimerPort {
        override fun available() = recognizer
        override fun start(generation: Long): Boolean { log += "listen:$generation"; return true }
        override fun release() { log += "recognizer_release" }
        override fun schedule(delayMs: Long) {}
        override fun cancel() {}
    }

    private val claims = object : WakeClaimPort {
        override fun newClaimId() = "claim-${++claimIds}"
        override fun epoch() = 0L
        override fun request(claimId: String, settingsRevision: Long, generation: Long, epoch: Long) { claimsSent += "claim:$claimId" }
        override fun renew(claimId: String) { claimsSent += "renew:$claimId" }
        override fun release(claimId: String) { claimsSent += "release:$claimId" }
        override fun scheduleTimer(delayMs: Long) {}
        override fun cancelTimer() {}
    }

    val wake = WakeDeviceController(VoiceOrigin.WATCH, recognizerPort, recognizerPort, coordinator.devicePort(inner), { now }, settings,
        if (arbitrated) claims else null)

    init { coordinator.attach(wake) }

    // ── the Android adapters' calls, in their order ──
    val status get() = coordinator.status
    val notice get() = coordinator.status.session.notice
    fun listens() = log.count { it.startsWith("listen:") }
    fun micRequests() = log.count { it == "retype(mic=true)" || it == "startService(mic=true)" }

    /** Runs what the main thread does after the current event. */
    fun drain() { while (posted.isNotEmpty()) posted.removeFirst()() }

    /** WatchActivity.onResume: the visit, then (later) the settings read of that visit. */
    fun resume(): Long = coordinator.onActivityResumed().also { drain() }
    fun pulled(visit: Long) = coordinator.onSettingsPulled(visit).also { drain() }
    fun show() = pulled(resume())
    fun hide() { coordinator.onActivityPaused(); drain() }
    fun start() = coordinator.start().also { drain() }
    fun stop() { coordinator.stop(); drain() }
    /** A newer Phone snapshot with a new foreground location; the Watch standby follows it like the legacy single selector did. */
    fun settingsChange(mode: WakeLocation) {
        settings = settings.copy(wakeLocation = mode, revision = settings.revision + 1,
            watchBackgroundWakeEnabled = mode.listensOn(VoiceOrigin.WATCH))
        coordinator.onEligibilityChanged(); drain()
    }

    /** A newer Phone snapshot that changes only the standby switches (the foreground location stays as it is). */
    fun standbyChange(watch: Boolean = settings.watchBackgroundWakeEnabled, phone: Boolean = settings.phoneBackgroundWakeEnabled) {
        settings = settings.copy(revision = settings.revision + 1, watchBackgroundWakeEnabled = watch, phoneBackgroundWakeEnabled = phone)
        coordinator.onEligibilityChanged(); drain()
    }

    /** A newer Phone snapshot that changes only the Watch's / Phone's screen-off recognition preference. */
    fun screenOffPreference(watch: Boolean? = null, phone: Boolean? = null) {
        settings = settings.copy(revision = settings.revision + 1).withScreenOff(phone = phone, watch = watch)
        coordinator.onEligibilityChanged(); drain()
    }

    /** The screen goes off / comes on, as WatchVoiceRuntime's screen receiver reports it. */
    fun screenOff() { screenOn = false; coordinator.onScreenOff(); drain() }
    fun screenOnEvent() { screenOn = true; coordinator.onScreenOn(); drain() }

    /** The always-on display takes over: the platform says ambient and the display listener reports a screen change. */
    fun enterAmbient() { ambient = true; screenOn = false; coordinator.onScreenOff(); drain() }
    fun leaveAmbient() { ambient = false; screenOn = true; coordinator.onScreenOn(); drain() }

    /** The cached Phone reachability changed (an event from the Data Layer, not a poll). */
    fun reachabilityChanged(value: Boolean?) {
        reachable = value
        coordinator.onReachabilityChanged(); drain()
    }
    fun heard(text: String, final: Boolean = true) { wake.onResults(wake.generation, listOf(text), final); coordinator.onRecognizerActivity(); drain() }
    fun windowTimeout() { now = maxOf(now, wake.windowDeadlineMs()); wake.onTimer(); drain() }
    fun handoffDue() { now += handoffIn ?: 0; handoffIn = null; wake.onHandoffDue(captureIdle = !capturing); drain() }

    /** The re-arm timer fired (the idle gap holds no wake lock: the timer alone brings the next window). */
    fun rearmDue() {
        val delay = rearmIn ?: return
        rearmIn = null
        now += delay
        coordinator.onRearmDue()
        drain()
    }

    fun held() = holds.held()
}
