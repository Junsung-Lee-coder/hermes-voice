package com.rumi.hermesvoice.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.wake.RecognizerGuard
import com.rumi.hermesvoice.core.wake.WakeClaimPort
import com.rumi.hermesvoice.core.wake.ContinuousWakePolicy
import com.rumi.hermesvoice.core.wake.WakeBlock
import com.rumi.hermesvoice.core.wake.WakeContract
import com.rumi.hermesvoice.core.wake.WakeDeviceController
import com.rumi.hermesvoice.core.wake.WakeDevicePort
import com.rumi.hermesvoice.core.wake.WakeRecognizerPort
import com.rumi.hermesvoice.core.wake.WakeTimerPort

/**
 * Android adapter for the Phone's foreground-only wake phrase, the Phone twin of the Watch's
 * `WakeController`: lifecycle and screen signals drive the shared [WakeDeviceController] (which
 * listens only when the wake location includes the Phone), the platform `SpeechRecognizer` is the
 * recognizer port and a main-thread handler the deadline timer. It never runs in the background:
 * the window opens only while [activity] is resumed with the screen on, and pausing closes it and
 * stops a hands-free capture unsent. The recognizer's own pause detection is separate from the
 * app's trailing-silence setting, which applies to the recorder after the phrase.
 */
class PhoneWakeController(
    private val activity: ComponentActivity,
    inner: WakeDevicePort,
    initial: WatchSettings,
    claims: WakeClaimPort,
) : DefaultLifecycleObserver {
    private val app = PhoneApp.from(activity)
    private var micLease: AutoCloseable? = null
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val guard = RecognizerGuard()

    /**
     * Debug QA only: a simulated recognizer. The window opens without the platform recognizer and
     * [qaHeard] supplies its results. It exercises the wake flow and arbitration, not recognition.
     */
    var qaFixtureRecognizer = false

    /** Debug QA only: a result of the simulated recognizer for the open window. */
    fun qaHeard(text: String, final: Boolean) {
        if (!qaFixtureRecognizer) return
        Log.i(TAG, "phone qa recognizer fixture result gen=${wake.generation} final=$final chars=${text.length} (simulated, not recognition)")
        wake.onResults(wake.generation, listOf(text), final)
    }
    private var receiverRegistered = false

    /** Whether this Phone has a speech recognition service at all. */
    fun recognizerAvailable(): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(activity) }.getOrDefault(false) || onDeviceAvailable()

    private val recognizerPort: WakeRecognizerPort = object : WakeRecognizerPort {
        override fun available(): Boolean = qaFixtureRecognizer || recognizerAvailable()

        override fun start(generation: Long): Boolean {
            if (qaFixtureRecognizer) {
                Log.i(TAG, "phone wake window opened gen=$generation mode=FIXTURE window_ms=${WakeContract.WINDOW_MS} (simulated recognizer)")
                return true
            }
            return startRecognizer(generation, onDevice = onDeviceAvailable())
        }

        override fun release() { destroyRecognizer() }
    }

    /** On-device recognition is preferred; [onDevice] false uses the system's default recognition service. */
    private fun startRecognizer(generation: Long, onDevice: Boolean): Boolean = runCatching {
        micLease = app.phoneMic.acquire(recognizer = true) { wake.onBusy(); recognizer == null }
            ?: return false
        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(activity) else SpeechRecognizer.createSpeechRecognizer(activity)
        recognizer = created
        created.setRecognitionListener(listenerFor(generation, onDevice, guard.open()))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Best-effort hints only (see WakeContract); the app's VAD setting is not the recognizer's.
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_HINT_MS)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_HINT_MS)
        if (wake.settings.wakePatterns.any { it in '가'..'힣' }) intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        created.startListening(intent)
        Log.i(TAG, "phone wake window opened gen=$generation mode=${if (onDevice) "ON_DEVICE" else "SYSTEM"} window_ms=${WakeContract.WINDOW_MS}")
    }.onFailure { Log.w(TAG, "phone wake recognizer start failed ${it.javaClass.simpleName}") }.isSuccess

    /** Cancels and destroys the recognizer; its late callbacks are ignored from here on ([guard]). */
    private fun destroyRecognizer(): Boolean {
        guard.close()
        val released = runCatching { recognizer?.cancel(); recognizer?.destroy() }.isSuccess
        if (released) {
            recognizer = null
            micLease?.close(); micLease = null
        }
        return released
    }

    private val deadlineCheck: Runnable = Runnable { wake.onTimer() }

    private val timerPort: WakeTimerPort = object : WakeTimerPort {
        override fun schedule(delayMs: Long) {
            handler.removeCallbacks(deadlineCheck)
            handler.postDelayed(deadlineCheck, delayMs)
        }

        override fun cancel() = handler.removeCallbacks(deadlineCheck)
    }

    private var resumed = false
    private var locallyCharging = false
    private var ordinaryEndMs = 0L
    private var chargingWindow = false
    private var screenWasInteractive = activity.getSystemService(android.os.PowerManager::class.java)?.isInteractive == true
    private var failures = 0
    private var blockedRetries = 0

    /** Foreground charging uses the same controller; its background twin remains dormant while the Activity owns the mic. */
    private fun continuouslyEligible(): Boolean {
        val app = PhoneApp.from(activity)
        return resumed && locallyCharging && wake.settings.phoneWakeWhileCharging && wake.settings.phoneBackgroundWakeEnabled &&
            wake.selected && app.tokens.load() != null && app.phoneWake.status.value.session.microphone &&
            PhoneWakeService.foregroundActive && activity.getSystemService(android.os.PowerManager::class.java)?.isInteractive == true
    }

    private fun openChargingWindow() {
        if (!continuouslyEligible()) return
        val wasListening = wake.listening
        wake.rearm("charging_foreground")
        if (!wasListening && wake.listening) chargingWindow = true
    }

    private val rearm = Runnable { openChargingWindow() }

    private fun cancelRearm() {
        handler.removeCallbacks(rearm)
        failures = 0
        blockedRetries = 0
    }

    private fun scheduleRearm(delayMs: Long) {
        if (!continuouslyEligible()) return cancelRearm()
        handler.removeCallbacks(rearm)
        handler.postDelayed(rearm, delayMs)
    }

    private val port = object : WakeDevicePort by inner {
        override fun settingsApplied() {
            inner.settingsApplied()
            reconcileCharging()
        }

        override fun cancelRequestCapture(reason: String) {
            if (reason == "pause") resumed = false
            if (reason == "pause" || reason == "screen_off") cancelRearm()
            inner.cancelRequestCapture(reason)
        }

        override fun closed(reason: String) {
            inner.closed(reason)
            if (!continuouslyEligible()) return cancelRearm()
            val next = ContinuousWakePolicy.next(reason, failures) ?: return
            failures = if (next.failure) failures + 1 else 0
            scheduleRearm(next.delayMs)
        }

        override fun armBlocked(source: String, block: WakeBlock) {
            inner.armBlocked(source, block)
            if (!continuouslyEligible()) return cancelRearm()
            when (block) {
                WakeBlock.MICROPHONE_MUTED, WakeBlock.PHONE_UNREACHABLE -> scheduleRearm(ContinuousWakePolicy.blockedRetryDelay(blockedRetries++))
                WakeBlock.COOLDOWN -> inner.armInputs().let { scheduleRearm((it.cooldownUntilMs - it.nowMs).coerceAtLeast(0L) + ContinuousWakePolicy.COOLDOWN_MARGIN_MS) }
                else -> Unit
            }
        }
    }

    /** The shared wake flow; the activity feeds it settings, busy/idle and handoff timing. */
    val wake: WakeDeviceController = WakeDeviceController(VoiceOrigin.PHONE, recognizerPort, timerPort, port, SystemClock::elapsedRealtime, initial, claims)

    private val chargingObserver = LocalChargingObserver(activity, handler) { charging ->
        locallyCharging = charging
        wake.onChargingChanged(charging)
        reconcileCharging()
    }

    private fun reconcileCharging() {
        if (!continuouslyEligible()) {
            cancelRearm()
            if (chargingWindow && SystemClock.elapsedRealtime() >= ordinaryEndMs) wake.endChargingForegroundIdle()
        } else if (!wake.listening && !wake.episodePending) openChargingWindow()
    }

    fun onEnvironmentChanged() = reconcileCharging()

    fun onSignInChanged(signedIn: Boolean) {
        if (!signedIn) {
            cancelRearm()
            wake.onBusy()
        } else reconcileCharging()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> handler.post { screenWasInteractive = false; wake.onScreenOff() }
                Intent.ACTION_SCREEN_ON -> handler.post {
                    if (!screenWasInteractive) {
                        ordinaryEndMs = SystemClock.elapsedRealtime() + WakeContract.WINDOW_MS
                        chargingWindow = false
                    }
                    screenWasInteractive = true
                    wake.onScreenOn()
                }
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            activity.registerReceiver(screenReceiver, filter)
        }
        receiverRegistered = true
        chargingObserver.start()
    }

    /** The Phone owns the settings, so they are always current here. */
    override fun onResume(owner: LifecycleOwner) {
        resumed = true
        ordinaryEndMs = SystemClock.elapsedRealtime() + WakeContract.WINDOW_MS
        chargingWindow = false
        screenWasInteractive = activity.getSystemService(android.os.PowerManager::class.java)?.isInteractive == true
        wake.onResume(settingsPending = false)
    }

    override fun onPause(owner: LifecycleOwner) = wake.onPause()

    override fun onStop(owner: LifecycleOwner) {
        resumed = false
        cancelRearm()
        chargingObserver.stop()
        locallyCharging = false
        wake.onChargingChanged(false)
        if (receiverRegistered) runCatching { activity.unregisterReceiver(screenReceiver) }
        receiverRegistered = false
        wake.onPause()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        resumed = false
        chargingObserver.stop()
        handler.removeCallbacksAndMessages(null)
        wake.onPause()
    }

    private fun onDeviceAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(activity) }.getOrDefault(false)

    private fun listenerFor(gen: Long, onDevice: Boolean, token: Long) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { Log.i(TAG, "phone wake recognizer ready gen=$gen") }
        override fun onBeginningOfSpeech() { Log.i(TAG, "phone wake recognizer speech_begin gen=$gen") }
        override fun onEndOfSpeech() { Log.i(TAG, "phone wake recognizer speech_end gen=$gen") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onError(error: Int) {
            Log.i(TAG, "phone wake recognizer error gen=$gen code=$error mode=${if (onDevice) "ON_DEVICE" else "SYSTEM"}")
            // A late callback of a recognizer that was already released must not touch the current one.
            if (!guard.isCurrent(token)) return
            com.rumi.hermesvoice.core.diag.DiagSpeechError.record(PhoneApp.from(activity).diagnostics.log, com.rumi.hermesvoice.core.diag.DiagOrigin.PHONE, error)
            // The on-device model may lack the wake phrases' language: try the system's default recognition
            // service once, in the same window (that service may use the network).
            if (onDevice && error in LANGUAGE_ERRORS) {
                destroyRecognizer()
                if (startRecognizer(gen, onDevice = false)) return
            }
            wake.onError(gen, error)
        }
        override fun onResults(results: Bundle?) { if (guard.isCurrent(token)) onRecognized(gen, results, final = true) }
        override fun onPartialResults(partialResults: Bundle?) { if (guard.isCurrent(token)) onRecognized(gen, partialResults, final = false) }
    }

    private fun onRecognized(gen: Long, bundle: Bundle?, final: Boolean) {
        val heard = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        // Privacy: only counts are logged, never recognized words.
        Log.i(TAG, "phone wake recognizer result gen=$gen final=$final candidates=${heard.size}")
        wake.onResults(gen, heard, final)
    }

    companion object {
        private const val TAG = "HermesVoiceWake"
        private const val SILENCE_HINT_MS = 2_000L

        /** SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED and ERROR_LANGUAGE_UNAVAILABLE (API 31). */
        private val LANGUAGE_ERRORS = setOf(12, 13)
    }
}
