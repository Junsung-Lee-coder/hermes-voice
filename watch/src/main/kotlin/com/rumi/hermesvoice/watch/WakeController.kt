package com.rumi.hermesvoice.watch

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
import com.rumi.hermesvoice.core.wake.WakeContract
import com.rumi.hermesvoice.core.wake.WakeDeviceController
import com.rumi.hermesvoice.core.wake.WakeDevicePort
import com.rumi.hermesvoice.core.wake.WakeRecognizerPort
import com.rumi.hermesvoice.core.wake.WakeTimerPort

/**
 * Android adapter for the Watch's foreground-only wake phrase: lifecycle and screen signals drive
 * the shared [WakeDeviceController] (which decides, from the Phone-owned wake location, whether
 * the Watch listens at all), the platform `SpeechRecognizer` is the recognizer port, and a
 * main-thread handler is the deadline timer. The screen broadcast only posts a signal; the
 * microphone is never touched from the receiver. The recognizer is released before any handoff,
 * so the app's own recorder never overlaps it. After a resume the Watch waits for its settings
 * replica to be current before listening ([WakeDeviceController.onSettingsCurrent]).
 */
class WakeController(
    private val activity: ComponentActivity,
    port: WakeDevicePort,
    initial: WatchSettings,
    claims: WakeClaimPort,
) : DefaultLifecycleObserver {
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
        Log.i(TAG, "qa recognizer fixture result gen=${wake.generation} final=$final chars=${text.length} (simulated, not recognition)")
        wake.onResults(wake.generation, listOf(text), final)
    }
    private var receiverRegistered = false

    private val recognizerPort: WakeRecognizerPort = object : WakeRecognizerPort {
        override fun available(): Boolean = qaFixtureRecognizer ||
            runCatching { SpeechRecognizer.isRecognitionAvailable(activity) }.getOrDefault(false) || onDeviceAvailable()

        override fun start(generation: Long): Boolean {
            if (qaFixtureRecognizer) {
                Log.i(TAG, "wake window opened gen=$generation mode=FIXTURE window_ms=${WakeContract.WINDOW_MS} (simulated recognizer)")
                return true
            }
            return startRecognizer(generation, onDevice = onDeviceAvailable())
        }

        override fun release() = destroyRecognizer()
    }

    /** On-device recognition is preferred; [onDevice] false uses the system's default recognition service. */
    private fun startRecognizer(generation: Long, onDevice: Boolean): Boolean = runCatching {
        val created = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(activity) else SpeechRecognizer.createSpeechRecognizer(activity)
        recognizer = created
        created.setRecognitionListener(listenerFor(generation, onDevice, guard.open()))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Best-effort hints only: many recognition services ignore them and apply their own
            // pause detection and session limit (see WakeContract). The app's own VAD setting
            // applies to the recorder after the phrase, not to the recognizer.
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_HINT_MS)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_HINT_MS)
        if (wake.settings.wakePatterns.any { it in '가'..'힣' }) intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        created.startListening(intent)
        Log.i(TAG, "wake window opened gen=$generation mode=${if (onDevice) "ON_DEVICE" else "SYSTEM"} window_ms=${WakeContract.WINDOW_MS}")
    }.onFailure { Log.w(TAG, "wake recognizer start failed ${it.javaClass.simpleName}") }.isSuccess

    /** Cancels and destroys the recognizer; its late callbacks are ignored from here on ([guard]). */
    private fun destroyRecognizer() {
        guard.close()
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
    }

    private val deadlineCheck: Runnable = Runnable { wake.onTimer() }

    private val timerPort: WakeTimerPort = object : WakeTimerPort {
        override fun schedule(delayMs: Long) {
            handler.removeCallbacks(deadlineCheck)
            handler.postDelayed(deadlineCheck, delayMs)
        }

        override fun cancel() = handler.removeCallbacks(deadlineCheck)
    }

    /** The shared wake flow; the activity feeds it settings, busy/idle and handoff timing. */
    val wake: WakeDeviceController = WakeDeviceController(VoiceOrigin.WATCH, recognizerPort, timerPort, port, SystemClock::elapsedRealtime, initial, claims)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> handler.post { wake.onScreenOff() }
                Intent.ACTION_SCREEN_ON -> handler.post { wake.onScreenOn() }
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
    }

    /** The Watch may hold settings older than the Phone's until the activity has read the synced item. */
    override fun onResume(owner: LifecycleOwner) = wake.onResume(settingsPending = true)

    override fun onPause(owner: LifecycleOwner) = wake.onPause()

    override fun onStop(owner: LifecycleOwner) {
        if (receiverRegistered) runCatching { activity.unregisterReceiver(screenReceiver) }
        receiverRegistered = false
        wake.onPause()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        handler.removeCallbacksAndMessages(null)
        wake.onPause()
    }

    private fun onDeviceAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(activity) }.getOrDefault(false)

    private fun listenerFor(gen: Long, onDevice: Boolean, token: Long) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { Log.i(TAG, "wake recognizer ready gen=$gen") }
        override fun onBeginningOfSpeech() { Log.i(TAG, "wake recognizer speech_begin gen=$gen") }
        override fun onEndOfSpeech() { Log.i(TAG, "wake recognizer speech_end gen=$gen") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onError(error: Int) {
            Log.i(TAG, "wake recognizer error gen=$gen code=$error mode=${if (onDevice) "ON_DEVICE" else "SYSTEM"}")
            // A late callback of a recognizer that was already released must not touch the current one.
            if (!guard.isCurrent(token)) return
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
        Log.i(TAG, "wake recognizer result gen=$gen final=$final candidates=${heard.size}")
        wake.onResults(gen, heard, final)
    }

    companion object {
        private const val TAG = "HermesVoiceWake"
        private const val SILENCE_HINT_MS = 2_000L

        /** SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED and ERROR_LANGUAGE_UNAVAILABLE (API 31). */
        private val LANGUAGE_ERRORS = setOf(12, 13)
    }
}
