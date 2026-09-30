package com.rumi.hermesvoice.watch

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.rumi.hermesvoice.core.audio.CaptureEnd
import com.rumi.hermesvoice.core.audio.PcmCaptureLoop
import com.rumi.hermesvoice.core.audio.SilenceEndpoint
import com.rumi.hermesvoice.core.background.BackgroundPort
import com.rumi.hermesvoice.core.background.BackgroundSession
import com.rumi.hermesvoice.core.background.BackgroundStatus
import com.rumi.hermesvoice.core.background.HoldReason
import com.rumi.hermesvoice.core.wake.WakeArmInputs
import com.rumi.hermesvoice.core.wake.WakeBlock
import com.rumi.hermesvoice.core.wake.WakeClaimMessage
import com.rumi.hermesvoice.core.wake.WakeClaimPort
import com.rumi.hermesvoice.core.wake.WakeContract
import com.rumi.hermesvoice.core.wake.WakeDevicePort
import com.rumi.hermesvoice.core.wake.WakePresence
import com.rumi.hermesvoice.core.wake.WakePresencePort
import com.rumi.hermesvoice.core.watchlink.CaptureCoordinator
import com.rumi.hermesvoice.core.watchlink.CapturePort
import com.rumi.hermesvoice.core.watchlink.CaptureStop
import com.rumi.hermesvoice.core.watchlink.HapticEvent
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The Watch's one voice runtime, owned by the application: the wake flow ([WakeController]), the
 * capture lifecycle ([CaptureCoordinator]), the recorder, the wake claim and their timers. The
 * activity only shows its state and forwards taps and its own lifecycle; [WatchVoiceService] only
 * keeps the process in the foreground-service state. Nothing here is created per activity, so
 * showing, hiding or recreating the screen never adds a second listener or recorder.
 *
 * Without a background session the foreground-only rules hold (one wake window per show; leaving
 * the screen ends listening and any recording unsent). While a session the user started from the
 * visible app has its microphone armed ([background], [presence]), listening, recording and
 * sending continue with the app hidden and the screen off. All calls are on the main thread.
 */
class WatchVoiceRuntime(private val app: WatchApp) {
    private val handler = Handler(Looper.getMainLooper())

    /** The Android recorder of the active capture; the lifecycle itself lives in [captures]. */
    private var recorder: WatchCapture? = null
    private val captures: CaptureCoordinator by lazy { CaptureCoordinator(capturePort) }

    private val _wakeListening = MutableStateFlow(false)
    val wakeListening: StateFlow<Boolean> = _wakeListening
    private val _wakeUnavailable = MutableStateFlow(false)
    val wakeUnavailable: StateFlow<Boolean> = _wakeUnavailable
    private val _keepScreenOn = MutableStateFlow(false)

    /** The visible activity keeps the screen on while recording, and while a foreground-only window listens; never for a whole background session. */
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn

    /** The wake claim of the hands-free capture in progress ("Both"), and of a recognized request being sent. */
    private var captureClaimId: String? = null
    private var recognizedClaimId: String? = null
    private var endedTrigger: TurnTrigger? = null
    private var talkPending = false

    private val handoffRunnable: Runnable = Runnable {
        if (!wake.wake.onHandoffDue(captureIdle = captures.activeId == null)) {
            Log.i(TAG, "wake handoff dropped gen=${wake.wake.generation}")
        }
    }
    private val claimTimer: Runnable = Runnable { wake.wake.onClaimTimer() }
    private val talkAfterRelease: Runnable = Runnable {
        talkPending = false
        if (presence.present && captures.activeId == null) startCapture(TurnTrigger.PUSH_TO_TALK)
    }

    /** The next window of an armed session; the Phone's reachability is read again first. */
    private val rearmRunnable: Runnable = Runnable {
        app.scope.launch {
            app.refreshPhoneReachable()
            presence.onRearmDue()
        }
    }

    /**
     * "Both": the Watch asks the Phone, the only coordinator, over the Data Layer before it records
     * or sends a wake request. No answer in time fails closed (see [WakeController.wake]).
     */
    private val claimPort: WakeClaimPort = object : WakeClaimPort {
        override fun newClaimId(): String = "w-" + UUID.randomUUID().toString()
        override fun epoch(): Long = app.wakeEpoch
        override fun request(claimId: String, settingsRevision: Long, generation: Long, epoch: Long) =
            app.sendWakeClaim(WakeClaimMessage(WakeClaimMessage.Op.CLAIM, claimId, settingsRevision, generation, epoch))
        override fun renew(claimId: String) = app.sendWakeClaim(WakeClaimMessage(WakeClaimMessage.Op.RENEW, claimId))
        override fun release(claimId: String) = app.sendWakeClaim(WakeClaimMessage(WakeClaimMessage.Op.RELEASE, claimId))

        override fun scheduleTimer(delayMs: Long) {
            handler.removeCallbacks(claimTimer)
            handler.postDelayed(claimTimer, delayMs)
        }

        override fun cancelTimer() = handler.removeCallbacks(claimTimer)
    }

    /** What the shared wake flow ([WakeController.wake]) does on this Watch. */
    private val wakePort: WakeDevicePort = object : WakeDevicePort {
        override fun windowChanged(open: Boolean) {
            _wakeListening.value = open
            if (open) _wakeUnavailable.value = false
            // A hidden window needs the CPU: one bounded hold per window, let go when it closes.
            if (open && presence.armed) app.holds.acquire(HoldReason.LISTEN, wake.wake.windowMs + LISTEN_HOLD_MARGIN_MS)
            else if (!open) app.holds.release(HoldReason.LISTEN)
            updateKeepScreenOn()
        }

        override fun scheduleHandoff(delayMs: Long) {
            // Give the recognizer's microphone a moment to be released before our recorder opens it.
            handler.removeCallbacks(handoffRunnable)
            handler.postDelayed(handoffRunnable, delayMs)
        }

        override fun cancelHandoff() = handler.removeCallbacks(handoffRunnable)

        override fun startRequestCapture(silenceMs: Long): Boolean = startRequestCapture(silenceMs, null)

        override fun startRequestCapture(silenceMs: Long, claimId: String?): Boolean {
            if (!hasMic()) return false
            captureClaimId = claimId
            return startCapture(TurnTrigger.WAKE_PHRASE, silenceMs).also { if (!it) captureClaimId = null }
        }

        override fun cancelRequestCapture(reason: String) {
            val active = recorder?.takeIf { it.trigger == TurnTrigger.WAKE_PHRASE } ?: return
            Log.i(TAG, "hands-free capture cancelled reason=$reason (not sent)")
            end(active.turnId, CaptureStop.LIFECYCLE)
        }

        override fun sendRecognized(request: String) = sendRecognized(request, null)

        override fun sendRecognized(request: String, claimId: String?) {
            // The recognizer's FINAL result had the request after a leading wake phrase: send it whole.
            val turnId = app.newTurn(TurnTrigger.WAKE_PHRASE)
            if (turnId == null) {
                // Busy meanwhile: say so instead of dropping it silently, and give the claim back.
                claimId?.let { app.sendWakeClaim(WakeClaimMessage(WakeClaimMessage.Op.RELEASE, it)) }
                return onWakeClosed("unfinished_request")
            }
            recognizedClaimId = claimId
            captures.sendRecognized(turnId, request)
        }

        override fun closed(reason: String) {
            Log.i(TAG, "wake window closed reason=$reason")
            onWakeClosed(reason)
            presence.onWindowClosed(reason)
        }

        override fun armInputs() = WakeArmInputs(
            enabled = true,
            // Present: the app is on screen, or a background session the user started has its microphone armed.
            resumed = presence.present,
            interactive = presence.armed || app.getSystemService(PowerManager::class.java)?.isInteractive == true,
            ambient = false,
            permission = hasMic(),
            microphoneMuted = app.getSystemService(AudioManager::class.java)?.isMicrophoneMute == true,
            talkIdle = app.talk.value.canArmWakePhrase,
            phoneReachable = app.phoneReachable.value,
            nowMs = SystemClock.elapsedRealtime(),
            cooldownUntilMs = cooldownUntilMs(),
            generation = 0,
            lastArmedGeneration = null,
        )

        override fun armBlocked(source: String, block: WakeBlock) {
            if (block != WakeBlock.DISABLED) Log.i(TAG, "wake window blocked source=$source reason=$block")
            presence.onArmBlocked(block, (cooldownUntilMs() - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            // The permission was taken away under an armed session: it can no longer listen.
            if (block == WakeBlock.PERMISSION) syncMicrophoneWanted()
        }
    }

    private val presencePort: WakePresencePort = object : WakePresencePort {
        override fun scheduleRearm(delayMs: Long) {
            Log.i(TAG, "wake window re-arms in $delayMs ms (background session)")
            handler.removeCallbacks(rearmRunnable)
            handler.postDelayed(rearmRunnable, delayMs)
        }

        override fun cancelRearm() = handler.removeCallbacks(rearmRunnable)

        override fun cancelCapture(reason: String) {
            captures.activeId?.let { end(it, CaptureStop.LIFECYCLE) }
        }
    }

    private val capturePort: CapturePort = object : CapturePort {
        override fun stopRecorder(captureId: String, reason: CaptureStop): ByteArray? {
            val active = recorder?.takeIf { it.turnId == captureId }
            endedTrigger = active?.trigger
            recorder = null
            val wav = active?.stop()
            app.holds.release(HoldReason.CAPTURE)
            active?.stats()?.let { stats ->
                // Aggregates only (no audio): proves whether the microphone delivered real, non-silent PCM.
                Log.i(TAG, "watch mic captured turn=${captureId.take(12)} trigger=${active.trigger} end=$reason " +
                    "pcm_bytes=${stats.pcmBytes} peak=${stats.peak} rms=${stats.rms} speech=${stats.speech} wav=${wav != null}" +
                    (if (stats.silenceMs >= 0) " vad_silence_ms=${stats.silenceMs} speech_end_ms=${stats.speechEndMs} " +
                        "end_ms=${stats.endMs} trailing_ms=${if (stats.speechEndMs >= 0) stats.endMs - stats.speechEndMs else -1}" else ""))
            }
            updateKeepScreenOn()
            return wav
        }

        override fun haptic(event: HapticEvent) {
            Log.i(TAG, "haptic $event")
            app.haptic(event)
        }

        override fun cue(line: String) = app.cue(line)
        override fun upload(captureId: String, trigger: TurnTrigger, wav: ByteArray) {
            val claimId = captureClaimId.takeIf { trigger == TurnTrigger.WAKE_PHRASE }
            if (trigger == TurnTrigger.WAKE_PHRASE) {
                captureClaimId = null
                wake.wake.onRequestCaptureEnded(sent = true)
            }
            app.upload(captureId, trigger, wav, claimId)
        }

        override fun uploadRecognized(turnId: String, text: String) = app.uploadRecognized(turnId, text, recognizedClaimId)

        override fun discard(message: String) {
            Log.i(TAG, "capture discarded: $message")
            if (endedTrigger == TurnTrigger.WAKE_PHRASE) {
                captureClaimId = null
                wake.wake.onRequestCaptureEnded(sent = false)
            }
            app.discard(message)
        }
    }

    val wake: WakeController = WakeController(app, wakePort, app.settings.value, claimPort) {
        if (presence.armed && _wakeListening.value) app.holds.acquire(HoldReason.LISTEN, WakeContract.PENDING_INACTIVITY_MS + LISTEN_HOLD_MARGIN_MS)
    }

    /** Whether the wake flow follows the screen or an armed background session. */
    val presence: WakePresence = WakePresence(wake.wake, presencePort)

    // ── background session ───────────────────────────────────────────────────────────────────

    private val _background = MutableStateFlow(BackgroundStatus(false, false, false, com.rumi.hermesvoice.core.background.BackgroundNotice.OFF))

    /** The opt-in background session as it really is now (not the saved wish alone). */
    val backgroundStatus: StateFlow<BackgroundStatus> = _background

    private val servicePort: BackgroundPort = object : BackgroundPort {
        override fun startService(microphone: Boolean): Boolean = runCatching {
            ContextCompat.startForegroundService(app, WatchVoiceService.start(app, microphone))
        }.onFailure { Log.w(TAG, "background service start refused: ${it.javaClass.simpleName}") }.isSuccess

        override fun retypeService(microphone: Boolean): Boolean = WatchVoiceService.running?.retype(microphone) ?: false

        // A service whose start is still on its way ends itself when it arrives (it must enter the foreground first).
        override fun stopService() {
            WatchVoiceService.running?.finish()
        }
    }

    /**
     * Device-local opt-in, off unless the user started it here. Its microphone is armed only from
     * the visible app and only while the Phone's wake settings include the Watch.
     */
    val background: BackgroundSession = BackgroundSession(app.localStore, KEY_BACKGROUND, servicePort, resumeWhenVisible = false) { status ->
        Log.i(TAG, "background session wanted=${status.wanted} running=${status.running} microphone=${status.microphone} notice=${status.notice}")
        _background.value = status
        presence.onArmed(status.microphone)
        updateKeepScreenOn()
        WatchVoiceService.running?.refresh(status)
    }

    /** The user's Start, from the visible activity only. */
    fun startBackground(): BackgroundStatus =
        background.start(visible = presence.visible, microphoneWanted = app.settings.value.watchWakeEnabled, microphonePermission = hasMic())
            .also { _background.value = it }

    /**
     * The user's Stop (notification or app), safe to repeat. The session is over for good: nothing
     * listens or records for it any more. With the app hidden that means everything stops now:
     * the wake window, a recording (unsent, its claim given back), an upload that has not reached
     * the link, playback, and every wake lock. With the app on screen, foreground use goes on.
     */
    fun stopBackground() {
        val hidden = !presence.visible
        background.stop()
        _background.value = background.status
        if (!hidden) return
        handler.removeCallbacks(handoffRunnable)
        handler.removeCallbacks(talkAfterRelease)
        talkPending = false
        captures.activeId?.let { end(it, CaptureStop.LIFECYCLE) }
        app.cancelPendingUploads("Stopped")
        app.stopPlayback("stopped")
        app.holds.releaseAll()
    }

    /** The service was started by the platform with the microphone type refused: the session plays replies only. */
    fun onMicrophoneRefused(generation: Long) {
        _background.value = background.onMicrophoneRefused(generation)
    }

    /** The service is gone without the user's stop. */
    fun onServiceGone(generation: Long) {
        _background.value = background.onServiceGone(generation)
    }

    private fun syncMicrophoneWanted() {
        _background.value = background.onMicrophoneWanted(app.settings.value.watchWakeEnabled, visible = presence.visible, permission = hasMic())
    }

    // ── activity ─────────────────────────────────────────────────────────────────────────────

    /** The activity is on screen. It reads the synced settings next and then calls [onSettingsPulled]. */
    fun onActivityResumed() = presence.onActivityResumed(settingsPending = true)

    /** The settings replica is as current as the Watch can know: it may listen, and a running session may arm its microphone. */
    fun onSettingsPulled() {
        wake.wake.onSettings(app.settings.value)
        presence.onSettingsCurrent()
        _background.value = background.onVisible(app.settings.value.watchWakeEnabled, hasMic())
    }

    fun onActivityPaused() {
        if (!presence.armed) {
            handler.removeCallbacks(handoffRunnable)
            handler.removeCallbacks(talkAfterRelease)
            talkPending = false
        }
        presence.onActivityPaused()
    }

    fun onPermissionGranted() {
        wake.wake.onPermissionGranted()
        if (presence.visible) _background.value = background.onVisible(app.settings.value.watchWakeEnabled, hasMic())
    }

    fun hasMic() = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** False when the microphone permission has to be asked for first (the activity does that). */
    fun onTalkPressed(): Boolean {
        captures.activeId?.let {
            end(it, CaptureStop.TAP_SEND)
            return true
        }
        if (!hasMic()) return false
        if (talkPending) return true
        val listening = _wakeListening.value
        presence.onBusy()
        if (!listening) {
            startCapture(TurnTrigger.PUSH_TO_TALK)
            return true
        }
        // The wake window was open: the recognizer was just released; give its microphone the same
        // short pause as the wake handoff before push-to-talk opens it.
        talkPending = true
        Log.i(TAG, "talk tapped in the wake window: recognizer released, recording in ${WakeContract.MIC_HANDOFF_MS} ms")
        handler.postDelayed(talkAfterRelease, WakeContract.MIC_HANDOFF_MS)
        return true
    }

    val capturing: Boolean get() = captures.activeId != null

    // ── capture ──────────────────────────────────────────────────────────────────────────────

    /**
     * Starts the app's recorder. There is no duration limit: push-to-talk ends when the user taps
     * (or on a lifecycle, microphone or storage failure), a wake-phrase request after [silenceMs] of
     * trailing silence (the setting when it started) or when the user taps.
     */
    private fun startCapture(trigger: TurnTrigger, silenceMs: Long = 0): Boolean {
        val turnId = app.newTurn(trigger) ?: return false
        if (!captures.begin(turnId, trigger)) return false
        val wakeRequest = trigger == TurnTrigger.WAKE_PHRASE
        val endpoint = if (wakeRequest) SilenceEndpoint(sampleRate = WatchCapture.SAMPLE_RATE, silenceMs = silenceMs) else null
        if (wakeRequest) Log.i(TAG, "hands-free capture turn=${turnId.take(12)} vad_silence_ms=$silenceMs")
        val started = WatchCapture(turnId, trigger, WatchCapture.FRAME_BOUND_PCM_BYTES, endpoint,
            object : PcmCaptureLoop.Listener {
                override fun onLive() { handler.post {
                    Log.i(TAG, "capture live turn=${turnId.take(12)} trigger=$trigger")
                    captures.onLive(turnId)
                } }

                override fun onCalibrated() { handler.post {
                    Log.i(TAG, "capture calibrated turn=${turnId.take(12)} (speak-now cue)")
                    captures.onCalibrated(turnId)
                } }

                override fun onEnd(reason: CaptureEnd) { handler.post { end(turnId, CaptureStop.of(reason)) } }
            })
        recorder = started
        app.holds.acquire(HoldReason.CAPTURE)
        if (!started.start()) {
            Log.w(TAG, "capture start failed turn=${turnId.take(12)} trigger=$trigger")
            end(turnId, CaptureStop.START_FAILED)
            return false
        }
        if (wakeRequest) app.cue("Get ready…")
        updateKeepScreenOn()
        return true
    }

    /** Ends [captureId] exactly once, whoever asks first (see [CaptureCoordinator]); stale requests do nothing. */
    private fun end(captureId: String, reason: CaptureStop) {
        captures.stop(captureId, reason)
    }

    // ── wake phrase ──────────────────────────────────────────────────────────────────────────

    private fun onWakeClosed(reason: String) {
        if (reason == "unavailable") _wakeUnavailable.value = true
        val notice = when (reason) {
            "unfinished_request" -> "Didn't catch that. Tap or say it again"
            "request_too_long" -> "That was too long for the watch. Use the phone"
            "unavailable" -> "Wake phrase unavailable on this watch"
            "wake_taken" -> "The phone answered that wake phrase"
            "wake_claim_timeout", "wake_claim_failed" -> "Couldn't confirm with the phone. Say it again"
            "wake_mode_changed" -> "Wake settings changed. Say it again"
            "recognizer_error_12", "recognizer_error_13" -> "The speech recognizer lacks the wake phrase language"
            else -> return
        }
        app.notice(notice)
    }

    private fun cooldownUntilMs(): Long = if (app.lastPlaybackEndedAtMs == 0L) 0L else app.lastPlaybackEndedAtMs + WakeContract.PLAYBACK_COOLDOWN_MS

    private fun updateKeepScreenOn() {
        _keepScreenOn.value = recorder != null || (_wakeListening.value && !presence.armed)
    }

    // ── screen, settings, talk state ─────────────────────────────────────────────────────────

    /** Only posts a signal; the microphone is never touched from the receiver. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> handler.post { presence.onScreenOff() }
                Intent.ACTION_SCREEN_ON -> handler.post { presence.onScreenOn() }
            }
        }
    }

    /**
     * Debug builds only, delivered without bringing the activity to the screen:
     * `adb shell am broadcast -a com.rumi.hermesvoice.QA_WATCH --es hv_qa_wake_heard "<text>" [--es hv_qa_wake_delay_ms <ms>]`
     * is a result of the simulated recognizer (see [WakeController.qaFixtureRecognizer]) for the open
     * window (it tests the flow, never recognition); `--es hv_qa_background stop` runs the same Stop
     * as the notification's action, which a test cannot tap while the notification is not allowed.
     */
    private val qaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getStringExtra(QA_BACKGROUND) == "stop") {
                Log.i(TAG, "qa background stop (debug builds only; the notification's Stop path)")
                handler.post { stopBackground() }
                return
            }
            val heard = intent.getStringExtra(QA_WAKE_HEARD) ?: return
            val final = intent.getBooleanExtra(QA_WAKE_FINAL, true)
            val delay = intent.getStringExtra(QA_WAKE_DELAY)?.toLongOrNull()?.coerceIn(0L, WakeContract.BACKGROUND_WINDOW_MS - 300) ?: 0L
            // Heard by the window open now; reported [delay] later, like a slow recognizer.
            val generation = wake.wake.generation
            handler.postDelayed({ wake.qaHeard(heard, final, generation) }, delay)
        }
    }

    init {
        _background.value = background.status
        val screen = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(screenReceiver, screen, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(screenReceiver, screen)
        }
        if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Exported so `adb shell am broadcast` reaches it; never registered in a release build.
            ContextCompat.registerReceiver(app, qaReceiver, IntentFilter(QA_ACTION), ContextCompat.RECEIVER_EXPORTED)
        }
        // The notification says whether the Watch can really listen.
        app.scope.launch { _wakeUnavailable.collect { WatchVoiceService.running?.refresh(background.status) } }
        app.wakeVerdictListener = { claimId, verdict -> wake.wake.onClaimVerdict(claimId, verdict) }
        app.wakeEpochListener = { epoch, claimId -> wake.wake.onEpisodeAnswered(epoch, claimId) }
        app.scope.launch {
            app.talk.collect {
                if (it.canArmWakePhrase) {
                    presence.onIdle()
                } else if (captures.activeId == null) {
                    presence.onBusy()
                }
            }
        }
        // Phone-owned settings: a mode that excludes the Watch stops listening and any hands-free capture at
        // once, and disarms a background session; including it again arms one only while the app is on screen.
        app.scope.launch {
            app.settings.collect {
                wake.wake.onSettings(it)
                syncMicrophoneWanted()
            }
        }
    }

    companion object {
        private const val TAG = "HermesVoiceWatch"
        const val KEY_BACKGROUND = "background_operation"
        private const val LISTEN_HOLD_MARGIN_MS = 10_000L
        const val QA_ACTION = "com.rumi.hermesvoice.QA_WATCH"
        const val QA_WAKE_HEARD = "hv_qa_wake_heard"
        const val QA_WAKE_FINAL = "hv_qa_wake_final"
        const val QA_WAKE_DELAY = "hv_qa_wake_delay_ms"
        const val QA_BACKGROUND = "hv_qa_background"
    }
}
