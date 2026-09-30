package com.rumi.hermesvoice.watch

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import android.media.AudioManager
import android.os.PowerManager
import android.os.SystemClock
import com.rumi.hermesvoice.core.audio.CaptureEnd
import com.rumi.hermesvoice.core.audio.PcmCaptureLoop
import com.rumi.hermesvoice.core.audio.QaAudio
import com.rumi.hermesvoice.core.audio.QaLaunchGuard
import com.rumi.hermesvoice.core.audio.SilenceEndpoint
import com.rumi.hermesvoice.core.wake.WakeArmInputs
import com.rumi.hermesvoice.core.wake.WakeBlock
import com.rumi.hermesvoice.core.wake.WakeContract
import com.rumi.hermesvoice.core.wake.WakeDevicePort
import com.rumi.hermesvoice.core.watchlink.CaptureCoordinator
import com.rumi.hermesvoice.core.watchlink.CapturePort
import com.rumi.hermesvoice.core.watchlink.CaptureStop
import com.rumi.hermesvoice.core.watchlink.HapticEvent
import com.rumi.hermesvoice.core.watchlink.LoadStatus
import com.rumi.hermesvoice.core.watchlink.ReaderAction
import com.rumi.hermesvoice.core.watchlink.ReaderGestureMapping
import com.rumi.hermesvoice.core.watchlink.ReaderSurface
import com.rumi.hermesvoice.core.watchlink.SwipeDirection
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * The Watch UI: a conversation reader (session browser ↔ selected conversation, swipe left to
 * switch, swipe right to send the app to the background, vertical touch or bezel to scroll) with
 * push-to-talk, and the optional foreground wake phrase ([WakeController], when the Phone-owned
 * wake location includes the Watch). The microphone has one owner at a time: the wake recognizer
 * is released before the app's own recorder starts. A hands-free request ends after the shared
 * trailing-silence setting, snapshotted when its recorder starts.
 */
class WatchActivity : ComponentActivity() {
    private val app by lazy { WatchApp.from(this) }

    /** The Android recorder of the active capture; the lifecycle itself lives in [captures]. */
    private var recorder: WatchCapture? = null
    private val captures: CaptureCoordinator by lazy { CaptureCoordinator(capturePort) }
    private val wakeListening = mutableStateOf(false)
    private val wakeUnavailable = mutableStateOf(false)
    private var qaWakeHandoffPending = false
    private lateinit var wake: WakeController
    private val handoffRunnable: Runnable = Runnable {
        if (!wake.wake.onHandoffDue(captureIdle = captures.activeId == null)) {
            Log.i(TAG, "wake handoff dropped gen=${wake.wake.generation}")
        }
    }

    /** What the shared wake flow ([WakeController.wake]) does on this Watch. */
    private val wakePort: WakeDevicePort = object : WakeDevicePort {
        override fun windowChanged(open: Boolean) {
            wakeListening.value = open
            if (open) wakeUnavailable.value = false
            updateKeepScreenOn()
        }

        override fun scheduleHandoff(delayMs: Long) {
            // Give the recognizer's microphone a moment to be released before our recorder opens it.
            window.decorView.removeCallbacks(handoffRunnable)
            window.decorView.postDelayed(handoffRunnable, delayMs)
        }

        override fun cancelHandoff() {
            window.decorView.removeCallbacks(handoffRunnable)
        }

        override fun startRequestCapture(silenceMs: Long): Boolean = hasMic() && startCapture(TurnTrigger.WAKE_PHRASE, silenceMs)

        override fun cancelRequestCapture(reason: String) {
            val active = recorder?.takeIf { it.trigger == TurnTrigger.WAKE_PHRASE } ?: return
            Log.i(TAG, "hands-free capture cancelled reason=$reason (not sent)")
            end(active.turnId, CaptureStop.LIFECYCLE)
        }

        override fun sendRecognized(request: String) {
            // The recognizer's FINAL result had the request after a leading wake phrase: send it whole.
            val turnId = app.newTurn(TurnTrigger.WAKE_PHRASE) ?: return
            captures.sendRecognized(turnId, request)
        }

        override fun closed(reason: String) {
            Log.i(TAG, "wake window closed reason=$reason")
            onWakeClosed(reason)
        }

        override fun armInputs() = WakeArmInputs(
            enabled = true,
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            interactive = getSystemService(PowerManager::class.java)?.isInteractive == true,
            ambient = false,
            permission = hasMic(),
            microphoneMuted = getSystemService(AudioManager::class.java)?.isMicrophoneMute == true,
            talkIdle = app.talk.value.canArmWakePhrase,
            phoneReachable = app.phoneReachable.value,
            nowMs = SystemClock.elapsedRealtime(),
            cooldownUntilMs = if (app.lastPlaybackEndedAtMs == 0L) 0L else app.lastPlaybackEndedAtMs + WakeContract.PLAYBACK_COOLDOWN_MS,
            generation = 0,
            lastArmedGeneration = null,
        )

        override fun armBlocked(source: String, block: WakeBlock) {
            if (block != WakeBlock.DISABLED) Log.i(TAG, "wake window blocked source=$source reason=$block")
        }
    }

    private val capturePort: CapturePort = object : CapturePort {
        override fun stopRecorder(captureId: String, reason: CaptureStop): ByteArray? {
            val active = recorder?.takeIf { it.turnId == captureId }
            recorder = null
            val wav = active?.stop()
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
        override fun upload(captureId: String, trigger: TurnTrigger, wav: ByteArray) = app.upload(captureId, trigger, wav)
        override fun uploadRecognized(turnId: String, text: String) = app.uploadRecognized(turnId, text)
        override fun discard(message: String) {
            Log.i(TAG, "capture discarded: $message")
            app.discard(message)
        }
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) wake.wake.onPermissionGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wake = WakeController(this, wakePort, app.settings.value)
        lifecycle.addObserver(wake)
        setContent {
            val talk by app.talk.collectAsStateWithLifecycle()
            val settings by app.settings.collectAsStateWithLifecycle()
            val phone by app.phoneReachable.collectAsStateWithLifecycle()
            val reader by app.reader.collectAsStateWithLifecycle()
            val listening by wakeListening
            val unavailable by wakeUnavailable
            val chatFocus = remember { FocusRequester() }
            val sessionsFocus = remember { FocusRequester() }
            val onScrollStep = { app.haptic(HapticEvent.SCROLL_STEP) }
            MaterialTheme(colors = WatchColors) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colors.background).readerSwipe(::onSwipe)) {
                    val history = reader.selectedHistory
                    when {
                        reader.surface == ReaderSurface.SESSIONS -> SessionsSurface(reader.sessions, reader.selectedSessionId, sessionsFocus,
                            onSelect = app::selectSession, onRefresh = app::loadSessions, onScrollStep = onScrollStep)
                        history != null -> ChatSurface(
                            title = reader.sessions.rows.firstOrNull { it.id == history.sessionId }?.title ?: "Conversation",
                            history = history, focusRequester = chatFocus, onOlder = app::loadOlder, onRetry = app::refreshSelected,
                            onScrollStep = onScrollStep,
                        ) { CompactTalk(talk, listening, ::onTalkPressed) }
                        else -> TalkHome(phone, talk, settings.watchWakeEnabled, listening, unavailable, ::onTalkPressed)
                    }
                    // Bezel input goes to the list on screen; focus follows the surface.
                    LaunchedEffect(reader.surface, history != null) {
                        runCatching {
                            when {
                                reader.surface == ReaderSurface.SESSIONS -> sessionsFocus.requestFocus()
                                history != null -> chatFocus.requestFocus()
                            }
                        }
                    }
                }
            }
        }
        handleQaIntent(intent, restored = savedInstanceState != null)
        lifecycleScope.launch {
            app.talk.collect {
                if (it.canArmWakePhrase) {
                    wake.wake.onIdle()
                } else if (captures.activeId == null) {
                    wake.wake.onBusy()
                }
            }
        }
        // Phone-owned settings: a mode that excludes the Watch stops listening and any hands-free capture at once.
        lifecycleScope.launch { app.settings.collect { wake.wake.onSettings(it) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleQaIntent(intent, restored = false)
    }

    override fun onResume() {
        super.onResume()
        // Reachability and the synced settings item first; only then may the Watch listen.
        lifecycleScope.launch {
            app.refreshPhoneReachable()
            pullSettings()
            wake.wake.onSettings(app.settings.value)
            wake.wake.onSettingsCurrent()
        }
        // Titles for the chat header and the browser; the open conversation is re-read for new messages.
        if (app.reader.value.sessions.status == LoadStatus.IDLE) app.loadSessions()
        if (app.reader.value.selectedSessionId != null) app.refreshSelected()
        if (!hasMic()) micPermission.launch(Manifest.permission.RECORD_AUDIO)
        if (qaWakeHandoffPending) {
            qaWakeHandoffPending = false
            // Posted: lifecycle observers (the wake window's new generation) run after onResume returns.
            window.decorView.post {
                Log.i(TAG, "qa wake handoff fixture contract=SECOND_UTTERANCE gen=${wake.wake.generation} " +
                    "watch_listens=${wake.wake.enabledHere} (recorder path only, not recognition)")
                wake.wake.qaSecondUtterance()
            }
        }
    }

    override fun onPause() {
        window.decorView.removeCallbacks(handoffRunnable)
        captures.activeId?.let { end(it, CaptureStop.LIFECYCLE) }
        super.onPause()
    }

    private fun onSwipe(direction: SwipeDirection) {
        when (ReaderGestureMapping.action(direction)) {
            ReaderAction.TOGGLE_SURFACE -> {
                app.toggleReaderSurface()
                Log.i(TAG, "gesture swipe=$direction action=toggle surface=${app.reader.value.surface}")
            }
            ReaderAction.BACKGROUND_APP -> {
                Log.i(TAG, "gesture swipe=$direction action=background")
                // The task (and its reader state) stays alive; this is not process termination.
                moveTaskToBack(true)
            }
        }
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** The settings data item may predate this install's listener; read it directly on resume. */
    private suspend fun pullSettings() {
        runCatching {
            val items = Wearable.getDataClient(this).getDataItems(
                Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(WatchLinkPaths.SETTINGS).build()).await()
            try {
                items.firstOrNull()?.let { DataMapItem.fromDataItem(it).dataMap.getString("json") }?.let(app::applySettings)
            } finally {
                items.release()
            }
        }
    }

    private fun onTalkPressed() {
        captures.activeId?.let {
            end(it, CaptureStop.TAP_SEND)
            return
        }
        if (!hasMic()) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        wake.wake.onBusy()
        startCapture(TurnTrigger.PUSH_TO_TALK)
    }

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
                override fun onLive() = runOnUiThread {
                    Log.i(TAG, "capture live turn=${turnId.take(12)} trigger=$trigger")
                    captures.onLive(turnId)
                }

                override fun onCalibrated() = runOnUiThread {
                    Log.i(TAG, "capture calibrated turn=${turnId.take(12)} (speak-now cue)")
                    captures.onCalibrated(turnId)
                }

                override fun onEnd(reason: CaptureEnd) = runOnUiThread { end(turnId, CaptureStop.of(reason)) }
            })
        recorder = started
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
        if (reason == "unavailable") wakeUnavailable.value = true
        val notice = when (reason) {
            "unfinished_request" -> "Didn't catch that. Tap or say it again"
            "request_too_long" -> "That was too long for the watch. Use the phone"
            "unavailable" -> "Wake phrase unavailable on this watch"
            "recognizer_error_12", "recognizer_error_13" -> "The speech recognizer lacks the wake phrase language"
            else -> return
        }
        app.notice(notice)
    }

    private fun updateKeepScreenOn() {
        if (recorder != null || wakeListening.value) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ── debug QA (debuggable builds only) ────────────────────────────────────────────────────

    /**
     * `am start ... --es hv_qa_wav <name>.wav` uploads files/qa/<name>.wav as a push-to-talk turn;
     * `--es hv_qa_wake_handoff second_utterance` runs the wake handoff exactly as a recognizer match
     * would (fixture: it proves the recorder path, not recognition); `--ei hv_qa_seed_reader <n>
     * [--el hv_qa_seed_newest <row>]` shows synthetic reader rows ([WatchApp.seedReaderForQa]).
     * Once per fresh launch intent.
     */
    private fun handleQaIntent(intent: Intent?, restored: Boolean) {
        intent ?: return
        val wav = intent.getStringExtra(QaAudio.EXTRA)
        val handoff = intent.getStringExtra(QA_WAKE_HANDOFF)
        val seed = intent.getIntExtra(QA_SEED_READER, 0)
        if (wav == null && handoff == null && seed <= 0) return
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        val handled = intent.getBooleanExtra(QA_HANDLED, false)
        intent.removeExtra(QaAudio.EXTRA)
        intent.removeExtra(QA_WAKE_HANDOFF)
        intent.removeExtra(QA_SEED_READER)
        intent.putExtra(QA_HANDLED, true)
        setIntent(intent)
        if (!QaLaunchGuard.shouldHandle(restored, fromHistory, handled)) return
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 || captures.activeId != null) return
        if (seed > 0) {
            app.seedReaderForQa(seed.coerceAtMost(40), intent.getLongExtra(QA_SEED_NEWEST, seed.toLong()))
            return
        }
        if (handoff == "second_utterance") {
            // Runs from onResume, in the visible generation, like a recognizer match would.
            qaWakeHandoffPending = true
            return
        }
        val file = QaAudio.resolve(File(filesDir, QaAudio.DIR), wav) ?: return
        val turnId = app.newTurn(TurnTrigger.PUSH_TO_TALK) ?: return
        Log.i(TAG, "qa audio submitted as a watch voice request turn=${turnId.take(12)}")
        app.upload(turnId, TurnTrigger.PUSH_TO_TALK, file.readBytes())
    }

    companion object {
        private const val TAG = "HermesVoiceWatch"
        private const val MIC_HANDOFF_MS = 300L
        private const val QA_WAKE_HANDOFF = "hv_qa_wake_handoff"
        private const val QA_HANDLED = "hv_qa_handled"
        private const val QA_SEED_READER = "hv_qa_seed_reader"
        private const val QA_SEED_NEWEST = "hv_qa_seed_newest"
    }
}

/** Always dark: black background (OLED), high-contrast text, blue Talk and red Send. */
private val WatchColors = Colors(
    primary = Color(0xFF8AB4F8),
    primaryVariant = Color(0xFF669DF6),
    secondary = Color(0xFF81C995),
    background = Color.Black,
    surface = Color(0xFF202124),
    error = Color(0xFFF28B82),
    onPrimary = Color(0xFF0B1D3A),
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color(0xFFE8EAED),
    onSurfaceVariant = Color(0xFFBDC1C6),
    onError = Color(0xFF3B0A08),
)
