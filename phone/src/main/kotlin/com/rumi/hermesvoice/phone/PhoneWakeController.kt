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
    port: WakeDevicePort,
    initial: WatchSettings,
) : DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var receiverRegistered = false

    /** Whether this Phone has a speech recognition service at all. */
    fun recognizerAvailable(): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(activity) }.getOrDefault(false) || onDeviceAvailable()

    private val recognizerPort: WakeRecognizerPort = object : WakeRecognizerPort {
        override fun available(): Boolean = recognizerAvailable()

        override fun start(generation: Long): Boolean = startRecognizer(generation, onDevice = onDeviceAvailable())

        override fun release() {
            recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
            recognizer = null
        }
    }

    /** On-device recognition is preferred; [onDevice] false uses the system's default recognition service. */
    private fun startRecognizer(generation: Long, onDevice: Boolean): Boolean = runCatching {
        val created = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(activity) else SpeechRecognizer.createSpeechRecognizer(activity)
        recognizer = created
        created.setRecognitionListener(listenerFor(generation, onDevice))
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

    private val deadlineCheck: Runnable = Runnable { wake.onTimer() }

    private val timerPort: WakeTimerPort = object : WakeTimerPort {
        override fun schedule(delayMs: Long) {
            handler.removeCallbacks(deadlineCheck)
            handler.postDelayed(deadlineCheck, delayMs)
        }

        override fun cancel() = handler.removeCallbacks(deadlineCheck)
    }

    /** The shared wake flow; the activity feeds it settings, busy/idle and handoff timing. */
    val wake: WakeDeviceController = WakeDeviceController(VoiceOrigin.PHONE, recognizerPort, timerPort, port, SystemClock::elapsedRealtime, initial)

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

    /** The Phone owns the settings, so they are always current here. */
    override fun onResume(owner: LifecycleOwner) = wake.onResume(settingsPending = false)

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

    private fun listenerFor(gen: Long, onDevice: Boolean) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { Log.i(TAG, "phone wake recognizer ready gen=$gen") }
        override fun onBeginningOfSpeech() { Log.i(TAG, "phone wake recognizer speech_begin gen=$gen") }
        override fun onEndOfSpeech() { Log.i(TAG, "phone wake recognizer speech_end gen=$gen") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onError(error: Int) {
            Log.i(TAG, "phone wake recognizer error gen=$gen code=$error mode=${if (onDevice) "ON_DEVICE" else "SYSTEM"}")
            // The on-device model may lack the wake phrases' language: try the system recognizer once, in the same window.
            if (onDevice && error in LANGUAGE_ERRORS && recognizer != null) {
                recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
                recognizer = null
                if (startRecognizer(gen, onDevice = false)) return
            }
            wake.onError(gen, error)
        }
        override fun onResults(results: Bundle?) = onRecognized(gen, results, final = true)
        override fun onPartialResults(partialResults: Bundle?) = onRecognized(gen, partialResults, final = false)
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
