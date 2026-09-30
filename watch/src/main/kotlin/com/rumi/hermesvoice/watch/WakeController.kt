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
import com.rumi.hermesvoice.core.wake.WakeArmGate
import com.rumi.hermesvoice.core.wake.WakeArmInputs
import com.rumi.hermesvoice.core.wake.WakeContract
import com.rumi.hermesvoice.core.wake.WakeOutcome
import com.rumi.hermesvoice.core.wake.WakeSession

/**
 * Foreground-only wake phrase ([WakeContract]): one bounded platform `SpeechRecognizer` window
 * per visibility generation of this Activity (shown, or screen back on while shown). The screen
 * broadcast only posts a signal; the microphone is never touched from the receiver. The
 * recognizer is released before [onHandoff] runs, so the app's own recorder never overlaps it.
 */
class WakeController(
    private val activity: ComponentActivity,
    private val app: WatchApp,
    private val onWindowChanged: (Boolean) -> Unit,
    private val onHandoff: (WakeOutcome.Handoff) -> Unit,
) : DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private val session = WakeSession()
    private val power = activity.getSystemService(PowerManager::class.java)
    private val audio = activity.getSystemService(AudioManager::class.java)
    private var generation = 0L
    private var lastArmedGeneration: Long? = null
    private var windowGeneration = -1L
    private var recognizer: SpeechRecognizer? = null
    private var resumed = false
    private var receiverRegistered = false
    private val deadlineCheck = Runnable { resolve(session.onDeadline(windowGeneration, SystemClock.elapsedRealtime())) }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> handler.post { generation += 1; close("screen_off") }
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
        generation += 1
        requestArm("resume")
    }

    override fun onPause(owner: LifecycleOwner) {
        resumed = false
        close("pause")
    }

    override fun onStop(owner: LifecycleOwner) {
        if (receiverRegistered) runCatching { activity.unregisterReceiver(screenReceiver) }
        receiverRegistered = false
        close("stop")
    }

    override fun onDestroy(owner: LifecycleOwner) {
        handler.removeCallbacksAndMessages(null)
        close("destroy")
    }

    /** Busy (recording, sending, waiting, playing) closes an open window. */
    fun onTalkBusy() = close("busy")

    fun onOptOut() = close("opt_out")

    /** Permission was just granted: this generation may still arm. */
    fun onPermissionGranted() = requestArm("permission")

    fun requestArm(source: String) {
        if (session.active) return
        val settings = app.settings.value
        val inputs = WakeArmInputs(
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
            generation = generation,
            lastArmedGeneration = lastArmedGeneration,
        )
        WakeArmGate.block(inputs)?.let { block ->
            if (settings.wakePhraseEnabled) Log.i(TAG, "wake window blocked source=$source reason=$block")
            return
        }
        open(source, settings.wakePatterns)
    }

    private fun open(source: String, patterns: String) {
        val available = runCatching { SpeechRecognizer.isRecognitionAvailable(activity) }.getOrDefault(false)
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(activity) }.getOrDefault(false)
        lastArmedGeneration = generation
        if (!available && !onDevice) {
            Log.i(TAG, "wake window unavailable source=$source (no speech recognition service)")
            app.discard("Wake phrase unavailable on this watch")
            return
        }
        val created = runCatching {
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(activity) else SpeechRecognizer.createSpeechRecognizer(activity)
        }.getOrElse {
            Log.w(TAG, "wake recognizer create failed ${it.javaClass.simpleName}")
            return
        }
        val gen = generation
        windowGeneration = gen
        recognizer = created
        session.open(gen, SystemClock.elapsedRealtime())
        created.setRecognitionListener(listenerFor(gen))
        onWindowChanged(true)
        scheduleDeadline()
        Log.i(TAG, "wake window opened gen=$gen source=$source mode=${if (onDevice) "ON_DEVICE" else "SYSTEM"} " +
            "window_ms=${WakeContract.WINDOW_MS}")
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        if (patterns.any { it in '가'..'힣' }) intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        runCatching { created.startListening(intent) }.onFailure {
            Log.w(TAG, "wake recognizer start failed ${it.javaClass.simpleName}")
            resolve(session.cancel("start_failed"))
        }
    }

    private fun listenerFor(gen: Long) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { Log.i(TAG, "wake recognizer ready gen=$gen") }
        override fun onBeginningOfSpeech() { Log.i(TAG, "wake recognizer speech_begin gen=$gen") }
        override fun onEndOfSpeech() { Log.i(TAG, "wake recognizer speech_end gen=$gen") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onError(error: Int) = resolve(session.onError(gen, error))
        override fun onResults(results: Bundle?) = onRecognized(gen, results, final = true)
        override fun onPartialResults(partialResults: Bundle?) = onRecognized(gen, partialResults, final = false)
    }

    private fun onRecognized(gen: Long, bundle: Bundle?, final: Boolean) {
        val heard = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val outcome = session.onResults(gen, heard, final, SystemClock.elapsedRealtime(), app.settings.value.wakePatterns)
        // Privacy: only counts are logged, never recognized words.
        Log.i(TAG, "wake recognizer result gen=$gen final=$final candidates=${heard.size} outcome=${outcome.javaClass.simpleName}")
        resolve(outcome)
    }

    private fun resolve(outcome: WakeOutcome) {
        when (outcome) {
            WakeOutcome.None -> if (session.active) scheduleDeadline()
            is WakeOutcome.Closed -> {
                release()
                Log.i(TAG, "wake window closed reason=${outcome.reason}")
            }
            is WakeOutcome.Handoff -> {
                release()
                Log.i(TAG, "wake matched handoff contract=${outcome.contract} request_chars=${outcome.request.length}")
                onHandoff(outcome)
            }
        }
    }

    private fun close(reason: String) = resolve(session.cancel(reason))

    private fun scheduleDeadline() {
        handler.removeCallbacks(deadlineCheck)
        val delay = (session.deadline() - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        handler.postDelayed(deadlineCheck, delay)
    }

    private fun release() {
        handler.removeCallbacks(deadlineCheck)
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        val had = recognizer != null
        recognizer = null
        if (had) onWindowChanged(false)
    }

    companion object {
        private const val TAG = "HermesVoiceWake"
    }
}
