package com.rumi.hermesvoice.watch

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
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.rumi.hermesvoice.core.wake.WakeArmInputs
import com.rumi.hermesvoice.core.wake.WakeContract
import com.rumi.hermesvoice.core.wake.WakeHostPort
import com.rumi.hermesvoice.core.wake.WakeOutcome
import com.rumi.hermesvoice.core.wake.WakeRecognizerPort
import com.rumi.hermesvoice.core.wake.WakeTimerPort
import com.rumi.hermesvoice.core.wake.WakeWindowCoordinator

/**
 * Android adapter for the foreground-only wake phrase ([WakeContract], [WakeWindowCoordinator]):
 * lifecycle and screen signals start visibility generations, the platform `SpeechRecognizer` is
 * the recognizer port, and a main-thread handler is the deadline timer. The screen broadcast only
 * posts a signal; the microphone is never touched from the receiver. The coordinator releases the
 * recognizer before [onHandoff] runs, so the app's own recorder never overlaps it.
 */
class WakeController(
    private val activity: ComponentActivity,
    private val app: WatchApp,
    private val onWindowChanged: (Boolean) -> Unit,
    private val onHandoff: (Long, WakeOutcome.Handoff) -> Unit,
    private val onClosed: (String) -> Unit,
) : DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private val power = activity.getSystemService(PowerManager::class.java)
    private val audio = activity.getSystemService(AudioManager::class.java)
    private var recognizer: SpeechRecognizer? = null
    private var resumed = false
    private var receiverRegistered = false

    private val recognizerPort: WakeRecognizerPort = object : WakeRecognizerPort {
        override fun available(): Boolean =
            runCatching { SpeechRecognizer.isRecognitionAvailable(activity) }.getOrDefault(false) || onDeviceAvailable()

        override fun start(generation: Long): Boolean = runCatching {
            val onDevice = onDeviceAvailable()
            val created = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(activity) else SpeechRecognizer.createSpeechRecognizer(activity)
            recognizer = created
            created.setRecognitionListener(listenerFor(generation))
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                // Best-effort hints only: many recognition services ignore them and apply their own
                // pause detection and session limit (see WakeContract).
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_HINT_MS)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_HINT_MS)
            if (app.settings.value.wakePatterns.any { it in '가'..'힣' }) intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            created.startListening(intent)
            Log.i(TAG, "wake window opened gen=$generation mode=${if (onDevice) "ON_DEVICE" else "SYSTEM"} window_ms=${WakeContract.WINDOW_MS}")
        }.onFailure { Log.w(TAG, "wake recognizer start failed ${it.javaClass.simpleName}") }.isSuccess

        override fun release() {
            recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
            recognizer = null
        }
    }

    private val deadlineCheck: Runnable = Runnable { wake.onTimer() }

    private val timerPort: WakeTimerPort = object : WakeTimerPort {
        override fun schedule(delayMs: Long) {
            handler.removeCallbacks(deadlineCheck)
            handler.postDelayed(deadlineCheck, delayMs)
        }

        override fun cancel() = handler.removeCallbacks(deadlineCheck)
    }

    private val hostPort: WakeHostPort = object : WakeHostPort {
        override fun windowChanged(open: Boolean) = onWindowChanged(open)

        override fun handoff(generation: Long, handoff: WakeOutcome.Handoff) {
            Log.i(TAG, "wake matched handoff contract=${handoff.contract} request_chars=${handoff.request.length}")
            onHandoff(generation, handoff)
        }

        override fun closed(reason: String) {
            Log.i(TAG, "wake window closed reason=$reason")
            onClosed(reason)
        }
    }

    private val wake: WakeWindowCoordinator = WakeWindowCoordinator(recognizerPort, timerPort, hostPort, SystemClock::elapsedRealtime)

    /** The current visibility generation; a microphone handoff is valid only within it. */
    val generation: Long get() = wake.generation

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> handler.post { wake.newGeneration("screen_off") }
                Intent.ACTION_SCREEN_ON -> handler.post { requestArm("screen_on") }
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

    override fun onResume(owner: LifecycleOwner) {
        resumed = true
        wake.newGeneration("resume")
        requestArm("resume")
    }

    override fun onPause(owner: LifecycleOwner) {
        resumed = false
        wake.close("pause")
    }

    override fun onStop(owner: LifecycleOwner) {
        if (receiverRegistered) runCatching { activity.unregisterReceiver(screenReceiver) }
        receiverRegistered = false
        wake.close("stop")
    }

    override fun onDestroy(owner: LifecycleOwner) {
        handler.removeCallbacksAndMessages(null)
        wake.close("destroy")
    }

    /** Busy (recording, sending, waiting, playing) closes an open window. */
    fun onTalkBusy() = wake.close("busy")

    fun onOptOut() = wake.close("opt_out")

    /** Permission was just granted: this generation may still arm. */
    fun onPermissionGranted() = requestArm("permission")

    fun requestArm(source: String) {
        val settings = app.settings.value
        val block = wake.requestArm(WakeArmInputs(
            enabled = settings.wakePhraseEnabled,
            resumed = resumed,
            interactive = power?.isInteractive == true,
            ambient = false,
            permission = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            microphoneMuted = audio?.isMicrophoneMute == true,
            talkIdle = app.talk.value.canArmWakePhrase,
            phoneReachable = app.phoneReachable.value,
            nowMs = SystemClock.elapsedRealtime(),
            cooldownUntilMs = if (app.lastPlaybackEndedAtMs == 0L) 0L else app.lastPlaybackEndedAtMs + WakeContract.PLAYBACK_COOLDOWN_MS,
            generation = 0,
            lastArmedGeneration = null,
        ))
        if (block != null && settings.wakePhraseEnabled) Log.i(TAG, "wake window blocked source=$source reason=$block")
    }

    private fun onDeviceAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(activity) }.getOrDefault(false)

    private fun listenerFor(gen: Long) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { Log.i(TAG, "wake recognizer ready gen=$gen") }
        override fun onBeginningOfSpeech() { Log.i(TAG, "wake recognizer speech_begin gen=$gen") }
        override fun onEndOfSpeech() { Log.i(TAG, "wake recognizer speech_end gen=$gen") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onError(error: Int) = wake.onError(gen, error)
        override fun onResults(results: Bundle?) = onRecognized(gen, results, final = true)
        override fun onPartialResults(partialResults: Bundle?) = onRecognized(gen, partialResults, final = false)
    }

    private fun onRecognized(gen: Long, bundle: Bundle?, final: Boolean) {
        val heard = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        // Privacy: only counts are logged, never recognized words.
        Log.i(TAG, "wake recognizer result gen=$gen final=$final candidates=${heard.size}")
        wake.onResults(gen, heard, final, app.settings.value.wakePatterns)
    }

    companion object {
        private const val TAG = "HermesVoiceWake"
        private const val SILENCE_HINT_MS = 2_000L
    }
}
