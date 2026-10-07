package com.rumi.hermesvoice.phone

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.audio.CaptureEnd
import com.rumi.hermesvoice.core.audio.PcmCaptureLoop
import com.rumi.hermesvoice.core.audio.SilenceEndpoint
import com.rumi.hermesvoice.core.background.BackgroundPort
import com.rumi.hermesvoice.core.background.BackgroundStatus
import com.rumi.hermesvoice.core.background.DeviceLocalFlags
import com.rumi.hermesvoice.core.background.NotificationCapability
import com.rumi.hermesvoice.core.background.PhoneBackgroundHost
import com.rumi.hermesvoice.core.background.PhoneBackgroundWake
import com.rumi.hermesvoice.core.background.PhoneWakeStatus
import com.rumi.hermesvoice.core.voice.EpisodeMicrophone
import com.rumi.hermesvoice.core.voice.MicrophoneClaim
import com.rumi.hermesvoice.core.voice.TurnRouting
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import com.rumi.hermesvoice.core.wake.ClaimVerdict
import com.rumi.hermesvoice.core.wake.RecognizerGuard
import com.rumi.hermesvoice.core.wake.WakeArmInputs
import com.rumi.hermesvoice.core.wake.WakeClaim
import com.rumi.hermesvoice.core.wake.WakeClaimPort
import com.rumi.hermesvoice.core.wake.WakeContract
import com.rumi.hermesvoice.core.wake.WakeDeviceController
import com.rumi.hermesvoice.core.wake.WakeDevicePort
import com.rumi.hermesvoice.core.wake.WakeRecognizerPort
import com.rumi.hermesvoice.core.wake.WakeTimerPort
import com.rumi.hermesvoice.core.watchlink.CaptureCoordinator
import com.rumi.hermesvoice.core.watchlink.CapturePort
import com.rumi.hermesvoice.core.watchlink.CaptureStop
import com.rumi.hermesvoice.core.watchlink.HapticEvent
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.VoiceOutcomeText
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The Android side of the Phone's opt-in background listening ([PhoneBackgroundWake] decides). It
 * belongs to the application, not to an activity, and works only while the app is hidden; the app
 * on screen keeps its own foreground wake phrase (MainActivity, PhoneWakeController), unchanged.
 *
 * - Recognition is ON-DEVICE only ([SpeechRecognizer.createOnDeviceSpeechRecognizer], Android 12+):
 *   with the app closed the room is never streamed to a recognition server. A device without an
 *   on-device recognizer, or whose model lacks the wake phrases' language, is told so and does not
 *   listen. Android's recognizer is not built for continuous use: windows follow one another
 *   (best effort, with gaps), and a vendor may still stop it in the background.
 * - After the phrase the same recorder and capture rules as in the app ([PhoneCapture],
 *   [CaptureCoordinator]): trailing silence from the settings, no time limit, never a partial send.
 *   The request is a Phone voice request like any other (routing preferences, latest sender); it
 *   never opens the app.
 * - Haptics through the system vibrator ([PhoneHaptics]): one 30 ms pulse when a phrase is accepted
 *   (the shared flow's guards), and the recording start/end cues.
 * All calls on the main thread.
 */
class PhoneBackgroundRuntime(private val app: PhoneApp) {
    private val handler = Handler(Looper.getMainLooper())
    private val haptics = PhoneHaptics(app)

    // ── recognizer (on-device only) ──────────────────────────────────────────────────────────

    private var recognizer: SpeechRecognizer? = null
    private val guard = RecognizerGuard()

    /** The on-device model lacked the wake phrases' language this session: no cloud fallback, the loop stops. */
    @Volatile private var languageMissing = false

    private fun onDeviceAvailable(): Boolean = !languageMissing && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(app) }.getOrDefault(false)

    private val recognizerPort = object : WakeRecognizerPort, WakeTimerPort {
        override fun available(): Boolean = onDeviceAvailable()

        override fun start(generation: Long): Boolean {
            // Background is on-device only: older devices must fail closed, never stream to a system recognizer.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
            return runCatching {
                val created = SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
                recognizer = created
                created.setRecognitionListener(listenerFor(generation, guard.open()))
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                if (background.wake.settings.wakePatterns.any { it in '가'..'힣' }) intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                created.startListening(intent)
                Log.i(TAG, "phone background wake window opened gen=$generation mode=ON_DEVICE window_ms=${background.wake.windowMs}")
            }.onFailure { Log.w(TAG, "phone background wake recognizer start failed ${it.javaClass.simpleName}") }.isSuccess
        }

        override fun release() {
            guard.close()
            recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
            recognizer = null
        }

        override fun schedule(delayMs: Long) {
            handler.removeCallbacks(deadlineCheck)
            handler.postDelayed(deadlineCheck, delayMs)
        }

        override fun cancel() = handler.removeCallbacks(deadlineCheck)
    }

    private val deadlineCheck = Runnable { background.wake.onTimer() }

    private fun listenerFor(gen: Long, token: Long) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { Log.i(TAG, "phone background wake recognizer ready gen=$gen") }
        override fun onBeginningOfSpeech() {}
        override fun onEndOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onError(error: Int) {
            Log.i(TAG, "phone background wake recognizer error gen=$gen code=$error")
            // A late callback of a recognizer that was already released must not touch the current one.
            if (!guard.isCurrent(token)) return
            // The on-device model lacks the language: say so (the next window finds no recognizer); never fall back to a server.
            if (error in LANGUAGE_ERRORS) languageMissing = true
            background.wake.onError(gen, error)
        }
        override fun onResults(results: Bundle?) { if (guard.isCurrent(token)) onRecognized(gen, results, final = true) }
        override fun onPartialResults(partialResults: Bundle?) { if (guard.isCurrent(token)) onRecognized(gen, partialResults, final = false) }
    }

    private fun onRecognized(gen: Long, bundle: Bundle?, final: Boolean) {
        val heard = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        // Privacy: only counts are logged, never recognized words.
        Log.i(TAG, "phone background wake result gen=$gen final=$final candidates=${heard.size}")
        background.wake.onResults(gen, heard, final)
        background.onRecognizerActivity()
    }

    // ── the hands-free recording after the phrase ────────────────────────────────────────────

    private var recorder: PhoneCapture? = null
    private var captureClaimId: String? = null
    private var recognizedClaimId: String? = null

    /** The microphone claims ([PhoneApp.audio]) of the recording and of the recognized request; handed to their request. */
    private var captureMicrophone: MicrophoneClaim? = null
    private var recognizedMicrophone: MicrophoneClaim? = null

    /** The wake episode's microphone hold, from the phrase to its recording or request (WakeDevicePort.holdMicrophone). */
    private val episodeMicrophone = EpisodeMicrophone(app.audio, VoiceOrigin.PHONE)

    private val captures = CaptureCoordinator(object : CapturePort {
        override fun stopRecorder(captureId: String, reason: CaptureStop): ByteArray? {
            val active = recorder?.takeIf { it.captureId == captureId }
            recorder = null
            val wav = active?.stop()
            active?.stats()?.let { stats ->
                Log.i(TAG, "phone background capture ended turn=${captureId.take(12)} end=$reason pcm_bytes=${stats.pcmBytes} " +
                    "speech=${stats.speech} vad_silence_ms=${stats.silenceMs} wav=${wav != null}")
            }
            return wav
        }

        override fun haptic(event: HapticEvent) = haptics.cue(event)
        override fun cue(line: String) = Unit

        override fun upload(captureId: String, trigger: TurnTrigger, wav: ByteArray) {
            val claimId = captureClaimId
            captureClaimId = null
            val microphone = captureMicrophone
            captureMicrophone = null
            background.wake.onRequestCaptureEnded(sent = true)
            submit(UUID.randomUUID().toString(), wav, null, claimId, microphone)
        }

        override fun uploadRecognized(turnId: String, text: String) {
            val claimId = recognizedClaimId
            recognizedClaimId = null
            val microphone = recognizedMicrophone
            recognizedMicrophone = null
            submit(turnId, ByteArray(0), text, claimId, microphone)
        }

        override fun discard(message: String) {
            Log.i(TAG, "phone background capture discarded: $message")
            captureClaimId = null
            captureMicrophone?.release()
            captureMicrophone = null
            background.wake.onRequestCaptureEnded(sent = false)
            notice.value = message
        }
    })

    /** A background recording is in progress (the app's own recorder must not start meanwhile). */
    val capturing: Boolean get() = captures.activeId != null

    /** A background wake window listens now. */
    val listening: Boolean get() = background.owning && background.wake.listening

    private fun startCapture(silenceMs: Long, claimId: String?, held: MicrophoneClaim?): Boolean {
        if (capturing || app.phoneTurns.value > 0) {
            held?.release()
            return false
        }
        captureClaimId = claimId
        val id = UUID.randomUUID().toString()
        if (!captures.begin(id, TurnTrigger.WAKE_PHRASE)) {
            held?.release()
            return false
        }
        // The episode's claim (taken when the phrase was heard), or a new one: BEFORE the microphone opens.
        val microphone = held ?: app.audio.claimMicrophone(VoiceOrigin.PHONE)
        captureMicrophone = microphone
        val capture = PhoneCapture(id, SilenceEndpoint(sampleRate = PhoneCapture.SAMPLE_RATE, silenceMs = silenceMs),
            object : PcmCaptureLoop.Listener {
                override fun onLive() { handler.post { captures.onLive(id) } }
                override fun onCalibrated() { handler.post { captures.onCalibrated(id) } }
                override fun onEnd(reason: CaptureEnd) { handler.post { captures.stop(id, CaptureStop.of(reason)) } }
            })
        recorder = capture
        Log.i(TAG, "phone background capture turn=${id.take(12)} vad_silence_ms=$silenceMs")
        // Opened only once a later reply it stopped has stopped; null: that happens in a moment (or it ends as START_FAILED).
        return microphone.whenSpeakerStopped(app.mainScope) { stopped -> openCapture(id, capture, stopped) } ?: true
    }

    private fun openCapture(id: String, capture: PhoneCapture, stopped: Boolean): Boolean {
        // Cancelled meanwhile (Stop, app shown, settings): nothing to open.
        if (captures.activeId != id || recorder !== capture) return false
        if (!stopped || !capture.start()) {
            captures.stop(id, CaptureStop.START_FAILED)
            return false
        }
        return true
    }

    /**
     * A request heard with the app closed: a Phone voice request like any other, run by the
     * application ([PhoneApp.launchTurn]); the routing preference and the conversation open in the
     * app are frozen now. It never opens or turns on the app.
     */
    private fun submit(turnId: String, wav: ByteArray, recognized: String?, claimId: String?, microphone: MicrophoneClaim?) {
        val wiring = runCatching { app.wiring() }.getOrNull() ?: run {
            microphone?.release()
            notice.value = "Not sent: set up Hermes in the app"
            return
        }
        val routing = TurnRouting.of(app.settings.routingEnabled, app.selectedConversationId)
        // The microphone claim goes with the request: later replies keep waiting until it has been answered.
        app.launchTurn(turnId, phoneOrigin = true, microphone) {
            val request = if (recognized != null) {
                VoiceTurnRequest(turnId, VoiceOrigin.PHONE, ByteArray(0), "text/plain", PhoneSpeakerSink(app), recognizedText = recognized,
                    wakeTurn = true, wakeClaimId = claimId, routing = routing, microphone = microphone)
            } else {
                VoiceTurnRequest(turnId, VoiceOrigin.PHONE, wav, "audio/wav", PhoneSpeakerSink(app), wakeTurn = true, wakeClaimId = claimId,
                    routing = routing, microphone = microphone)
            }
            val outcome = runCatching { wiring.core.orchestrator.run(request) }
            val line = outcome.fold({ VoiceOutcomeText.describe(it) }, { "Not sent: ${it.javaClass.simpleName}" })
            Log.i(TAG, "phone background turn ${turnId.take(12)} outcome=${outcome.getOrNull()?.javaClass?.simpleName ?: "failed"}")
            notice.value = line
        }
    }

    /** The last background request's result, for the app's status line. */
    val notice = MutableStateFlow<String?>(null)

    // ── the shared wake flow, its claims and handoff ─────────────────────────────────────────

    private val handoff = Runnable {
        if (!background.wake.onHandoffDue(captureIdle = !capturing)) Log.i(TAG, "phone background wake handoff dropped")
    }

    private val claimTimer = Runnable { background.wake.onClaimTimer() }

    /** "Both": the Phone's own claims go straight to the shared admission; answers are posted, never re-entrant. */
    private val claimPort = object : WakeClaimPort {
        private fun admission() = runCatching { app.wiring().core.wakeAdmission }.getOrNull()
        override fun newClaimId(): String = "p-" + UUID.randomUUID().toString()
        override fun epoch(): Long = admission()?.epoch ?: 0L

        override fun request(claimId: String, settingsRevision: Long, generation: Long, epoch: Long) {
            val verdict = admission()?.claim(WakeClaim(claimId, VoiceOrigin.PHONE, "", settingsRevision, generation, epoch)) ?: ClaimVerdict.EXPIRED
            handler.post { background.wake.onClaimVerdict(claimId, verdict) }
        }

        override fun renew(claimId: String) {
            val verdict = admission()?.renew(claimId, VoiceOrigin.PHONE, "") ?: ClaimVerdict.EXPIRED
            if (verdict != ClaimVerdict.GRANTED) handler.post { background.wake.onClaimVerdict(claimId, verdict) }
        }

        override fun release(claimId: String) {
            admission()?.release(claimId, VoiceOrigin.PHONE, "")
        }

        override fun scheduleTimer(delayMs: Long) {
            handler.removeCallbacks(claimTimer)
            handler.postDelayed(claimTimer, delayMs)
        }

        override fun cancelTimer() = handler.removeCallbacks(claimTimer)
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private val devicePort = object : WakeDevicePort {
        override fun windowChanged(open: Boolean) = Unit

        override fun scheduleHandoff(delayMs: Long) {
            handler.removeCallbacks(handoff)
            handler.postDelayed(handoff, delayMs)
        }

        override fun cancelHandoff() = handler.removeCallbacks(handoff)
        override fun startRequestCapture(silenceMs: Long): Boolean = startRequestCapture(silenceMs, null)
        override fun startRequestCapture(silenceMs: Long, claimId: String?): Boolean =
            hasMic() && startCapture(silenceMs, claimId, episodeMicrophone.take())

        override fun cancelRequestCapture(reason: String) {
            captures.activeId?.let { captures.stop(it, CaptureStop.LIFECYCLE) }
        }

        override fun sendRecognized(request: String) = sendRecognized(request, null)

        override fun sendRecognized(request: String, claimId: String?) {
            recognizedClaimId = claimId
            recognizedMicrophone = episodeMicrophone.take()
            captures.sendRecognized(UUID.randomUUID().toString(), request)
        }

        // From the phrase (before the accepted cue) until the recording or request takes it over: no later reply here.
        override fun holdMicrophone(held: Boolean) = episodeMicrophone.hold(held)

        override fun closed(reason: String) {
            Log.i(TAG, "phone background wake window closed reason=$reason")
        }

        override fun wakeAccepted() = haptics.wakeAccepted()

        override fun armInputs() = WakeArmInputs(
            enabled = app.settings.watchSettings().phoneBackgroundWakeEnabled,
            // Presence is the background decision's (PhoneBackgroundWake.devicePort); the screen is this Phone's own, read from the platform.
            resumed = false,
            interactive = app.getSystemService(PowerManager::class.java)?.isInteractive == true,
            ambient = false,
            permission = hasMic(),
            microphoneMuted = app.getSystemService(AudioManager::class.java)?.isMicrophoneMute == true,
            talkIdle = !capturing && app.phoneTurns.value == 0 && !app.speakingLater.value,
            // A Phone request needs a Hermes sign-in, not a reachable peer.
            phoneReachable = app.tokens.load() != null,
            nowMs = SystemClock.elapsedRealtime(),
            cooldownUntilMs = app.lastPhonePlaybackEndedAtMs.let { if (it == 0L) 0L else it + WakeContract.PLAYBACK_COOLDOWN_MS },
            generation = 0,
            lastArmedGeneration = null,
        )
    }

    // ── the session and its service ──────────────────────────────────────────────────────────

    private val standbyAlarm = StandbyScheduler(app)

    /** A gap before the next window is waiting (its handler copy and its alarm copy share this: the first to run takes it). */
    private var rearmScheduled = false

    private val rearm = Runnable {
        if (!rearmScheduled) return@Runnable
        rearmScheduled = false
        standbyAlarm.cancel()
        background.onRearmDue()
    }

    /** The platform alarm of a gap fired (possibly while the CPU slept and the handler could not run). */
    fun onRearmAlarm() {
        handler.removeCallbacks(rearm)
        rearm.run()
    }

    private val host = object : PhoneBackgroundHost {
        override fun settings() = app.settings.watchSettings()
        override fun microphonePermission() = hasMic()
        override fun onDeviceRecognizer() = onDeviceAvailable()
        override fun notifications() = notificationCapability()

        override fun scheduleRearm(delayMs: Long) {
            rearmScheduled = true
            handler.removeCallbacks(rearm)
            handler.postDelayed(rearm, delayMs)
            standbyAlarm.schedule(delayMs)
        }

        override fun cancelRearm() {
            rearmScheduled = false
            handler.removeCallbacks(rearm)
            standbyAlarm.cancel()
        }

        override fun recordingActive(): Boolean = capturing

        override fun cancelCapture(reason: String) {
            captures.activeId?.let { captures.stop(it, CaptureStop.LIFECYCLE) }
        }

        override fun post(block: () -> Unit) {
            handler.post(block)
        }

        override fun statusChanged(status: PhoneWakeStatus) {
            Log.i(TAG, "phone background listening wanted=${status.session.wanted} running=${status.session.running} " +
                "microphone=${status.session.microphone} notice=${status.session.notice} loop=${status.loop} listening=${status.listeningNow}")
            _status.value = status
            PhoneWakeService.running?.refresh(status)
        }

        override fun log(line: String) {
            Log.i(TAG, line)
        }
    }

    private val servicePort = object : BackgroundPort {
        override fun startService(microphone: Boolean): Boolean = runCatching {
            ContextCompat.startForegroundService(app, PhoneWakeService.start(app, microphone))
        }.onFailure { Log.w(TAG, "phone background listening service start refused: ${it.javaClass.simpleName}") }.isSuccess

        override fun retypeService(microphone: Boolean): Boolean = PhoneWakeService.running?.retype(microphone) ?: false

        override fun stopService() {
            PhoneWakeService.running?.finish()
        }
    }

    val background: PhoneBackgroundWake = PhoneBackgroundWake(app.localStore, DeviceLocalFlags.KEY_PHONE_WAKE, servicePort, host, app.holds, SystemClock::elapsedRealtime)

    private val _status: MutableStateFlow<PhoneWakeStatus> = MutableStateFlow(background.status)

    /** The background listening as it really is now (not the saved switch alone). */
    val status: StateFlow<PhoneWakeStatus> = _status

    /** This Phone's own screen going on or off: re-decides the standby against the screen-off preference. Events only, never a poll. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON -> handler.post { background.onEligibilityChanged() }
            }
        }
    }

    init {
        // An alarm an earlier process of this app left behind opens nothing.
        standbyAlarm.cancel(force = true)
        val screenFilter = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(screenReceiver, screenFilter)
        background.attach(WakeDeviceController(VoiceOrigin.PHONE, recognizerPort, recognizerPort, background.devicePort(devicePort),
            SystemClock::elapsedRealtime, app.settings.watchSettings(), claimPort))
        // A Phone request in flight owns the microphone and speaker; a later reply holding this Phone's
        // speaker closes an open window only (an episode under way owns the microphone and goes on).
        app.appScope.launch {
            combine(app.phoneTurns, app.speakingLater) { turns, later -> if (turns > 0) BUSY else if (later) SPEAKER else IDLE }
                .distinctUntilChanged().collect { state ->
                    handler.post {
                        when (state) {
                            BUSY -> background.onBusy()
                            SPEAKER -> background.onPlaybackBusy()
                            else -> background.onIdle()
                        }
                    }
                }
        }
    }

    /** Whether the session's notification (and its Stop) can be seen now, from the platform itself. */
    fun notificationCapability(): NotificationCapability {
        if (Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return NotificationCapability.NOT_ALLOWED
        val manager = app.getSystemService(android.app.NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return NotificationCapability.APP_OFF
        val channel = manager.getNotificationChannel(PhoneWakeService.CHANNEL)
        if (channel != null && channel.importance == android.app.NotificationManager.IMPORTANCE_NONE) return NotificationCapability.CHANNEL_OFF
        return NotificationCapability.SHOWN
    }

    /** The user's switch, from the visible app (the session refuses it otherwise). A new start retries the on-device model. */
    fun start(): BackgroundStatus {
        languageMissing = false
        return background.start()
    }

    /**
     * The Phone's background wake standby was switched off in Settings (already saved). The standby session ends and leaves
     * nothing scheduled; a push-to-talk or hands-free recording, an upload, transfer or playback is not cancelled.
     */
    fun standbyOff(): BackgroundStatus {
        handler.removeCallbacks(handoff)
        return background.onStandbyOff()
    }

    /**
     * The notification's Stop (a real Stop): everything of the session ends now, and the standby switch is saved OFF so the
     * next time the app is opened does not start it again.
     */
    fun stop(): BackgroundStatus {
        saveStandbyOff()
        handler.removeCallbacks(handoff)
        val status = background.stop()
        captures.activeId?.let { captures.stop(it, CaptureStop.LIFECYCLE) }
        // A Stop also ends following later replies.
        app.stopLaterReplies()
        return status
    }

    private fun saveStandbyOff() {
        val current = app.settings.watchSettings()
        if (!current.phoneBackgroundWakeEnabled) return
        val saved = app.settings.saveWatchSettings(current.copy(phoneBackgroundWakeEnabled = false))
        app.appScope.launch { runCatching { WatchSettingsSync.publish(app, saved) } }
    }

    /**
     * The app is on screen: the microphone goes back to the app's own wake flow (before it resumes). A saved ON whose
     * session is not running (the system ended it, the phone restarted) is started again here, from the visible app: the
     * switch is a request that is honored at the next real visit. A saved OFF is never turned on.
     */
    fun onAppShown() {
        handler.removeCallbacks(hide)
        handler.removeCallbacks(handoff)
        background.onAppShown()
        if (app.settings.watchSettings().phoneBackgroundWakeEnabled && !background.status.session.running) {
            start()
        }
    }

    private val hide = Runnable { background.onAppHidden() }

    /**
     * The app left the screen, or the screen went off with it open. Taken over after a short grace,
     * so a rotation (stop and start again at once) does not start and kill a recognizer for nothing.
     */
    fun onAppHidden() {
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, HIDE_GRACE_MS)
    }

    /** The wake settings, the microphone or the notification permission may have changed. */
    fun onEligibilityChanged() = background.onEligibilityChanged()

    companion object {
        private const val TAG = "HermesVoiceWake"
        private const val HIDE_GRACE_MS = 700L
        private const val IDLE = 0
        private const val BUSY = 1
        private const val SPEAKER = 2

        /** SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED and ERROR_LANGUAGE_UNAVAILABLE (API 31). */
        private val LANGUAGE_ERRORS = setOf(12, 13)
    }
}
