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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.audio.QaAudio
import com.rumi.hermesvoice.core.audio.QaLaunchGuard
import com.rumi.hermesvoice.core.audio.SilenceEndpoint
import com.rumi.hermesvoice.core.wake.WakeHandoff
import com.rumi.hermesvoice.core.wake.WakeOutcome
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
 * push-to-talk, and the optional foreground wake phrase ([WakeController]). The microphone has one
 * owner at a time: the wake recognizer is released before the app's own recorder starts.
 */
class WatchActivity : ComponentActivity() {
    private val app by lazy { WatchApp.from(this) }
    private var capture: WatchCapture? = null
    private val wakeListening = mutableStateOf(false)
    private lateinit var wake: WakeController

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) wake.onPermissionGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wake = WakeController(this, app, onWindowChanged = { open ->
            wakeListening.value = open
            updateKeepScreenOn()
        }, onHandoff = ::onWakeHandoff)
        lifecycle.addObserver(wake)
        setContent {
            val talk by app.talk.collectAsStateWithLifecycle()
            val settings by app.settings.collectAsStateWithLifecycle()
            val phone by app.phoneReachable.collectAsStateWithLifecycle()
            val reader by app.reader.collectAsStateWithLifecycle()
            val listening by wakeListening
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
                        else -> TalkHome(phone, talk, settings.wakePhraseEnabled, listening, ::onTalkPressed)
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
            app.talk.collect { if (it.canArmWakePhrase) wake.requestArm("idle") else wake.onTalkBusy() }
        }
        lifecycleScope.launch {
            app.settings.collect { if (it.wakePhraseEnabled) wake.requestArm("settings") else wake.onOptOut() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleQaIntent(intent, restored = false)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { pullSettings() }
        lifecycleScope.launch { app.refreshPhoneReachable() }
        // Titles for the chat header and the browser; the open conversation is re-read for new messages.
        if (app.reader.value.sessions.status == LoadStatus.IDLE) app.loadSessions()
        if (app.reader.value.selectedSessionId != null) app.refreshSelected()
        if (!hasMic()) micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onPause() {
        capture?.let { finishCapture(send = false, reason = "Cancelled", endReason = "lifecycle") }
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
        if (capture != null) {
            finishCapture(send = true, reason = "", endReason = "tap_send")
            return
        }
        if (!hasMic()) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        wake.onTalkBusy()
        startCapture(TurnTrigger.PUSH_TO_TALK)
    }

    // ── capture ──────────────────────────────────────────────────────────────────────────────

    private fun startCapture(trigger: TurnTrigger) {
        val turnId = app.newTurn(trigger) ?: return
        val wakeRequest = trigger == TurnTrigger.WAKE_PHRASE
        val started = WatchCapture(turnId, trigger, WatchCapture.limitFor(trigger, app.settings.value.maxTurnSeconds),
            if (wakeRequest) SilenceEndpoint() else null, object : WatchCapture.Listener {
                override fun onLive() = runOnUiThread {
                    if (capture?.turnId != turnId) return@runOnUiThread
                    Log.i(TAG, "capture live turn=${turnId.take(12)} trigger=$trigger")
                    // Push-to-talk records from the first real audio; a wake request cues after calibration.
                    if (!wakeRequest) app.onRecordingStarted(turnId)
                }

                override fun onCalibrated() = runOnUiThread {
                    if (capture?.turnId != turnId) return@runOnUiThread
                    Log.i(TAG, "capture calibrated turn=${turnId.take(12)} (speak-now cue)")
                    app.cue("Speak now…")
                    app.onRecordingStarted(turnId)
                }

                override fun onEnd(reason: CaptureEnd) = runOnUiThread {
                    if (capture?.turnId != turnId) return@runOnUiThread
                    when (reason) {
                        CaptureEnd.NO_SPEECH -> finishCapture(send = false, reason = "Didn't hear a request", endReason = "no_speech_timeout")
                        CaptureEnd.MIC_ERROR -> finishCapture(send = false, reason = "Microphone unavailable", endReason = "mic_read_error")
                        CaptureEnd.SILENCE -> finishCapture(send = true, reason = "", endReason = "adaptive_silence")
                        CaptureEnd.LIMIT -> finishCapture(send = true, reason = "", endReason = "size_limit")
                    }
                }
            })
        capture = started
        if (!started.start()) {
            capture = null
            Log.w(TAG, "capture start failed turn=${turnId.take(12)} trigger=$trigger")
            app.discard("Microphone unavailable")
            return
        }
        if (wakeRequest) app.cue("Get ready…")
        updateKeepScreenOn()
    }

    private fun finishCapture(send: Boolean, reason: String, endReason: String) {
        val active = capture ?: return
        capture = null
        val wav = active.stop()
        val stats = active.stats()
        // Aggregates only (no audio): proves whether the microphone delivered real, non-silent PCM.
        Log.i(TAG, "watch mic captured turn=${active.turnId.take(12)} trigger=${active.trigger} end=$endReason " +
            "pcm_bytes=${stats.pcmBytes} peak=${stats.peak} rms=${stats.rms} speech=${stats.speech} send=${send && wav != null}")
        app.onRecordingEnded(active.turnId, endReason)
        if (send && wav != null) app.upload(active.turnId, active.trigger, wav) else app.discard(reason.ifBlank { "Too short" })
        updateKeepScreenOn()
    }

    // ── wake phrase ──────────────────────────────────────────────────────────────────────────

    private fun onWakeHandoff(handoff: WakeOutcome.Handoff) {
        when (handoff.contract) {
            WakeHandoff.RECOGNIZED_REQUEST -> {
                // The recognizer already heard the request with the wake phrase: send it, don't ask again.
                val turnId = app.newTurn(TurnTrigger.WAKE_PHRASE) ?: return
                app.uploadRecognized(turnId, handoff.request)
            }
            WakeHandoff.SECOND_UTTERANCE -> {
                if (!hasMic() || capture != null) return
                // Give the recognizer's microphone a moment to be released before our recorder opens it.
                window.decorView.postDelayed({
                    if (capture == null && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                        startCapture(TurnTrigger.WAKE_PHRASE)
                    }
                }, MIC_HANDOFF_MS)
            }
        }
    }

    private fun updateKeepScreenOn() {
        if (capture != null || wakeListening.value) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 || capture != null) return
        if (seed > 0) {
            app.seedReaderForQa(seed.coerceAtMost(40), intent.getLongExtra(QA_SEED_NEWEST, seed.toLong()))
            return
        }
        if (handoff == "second_utterance") {
            Log.i(TAG, "qa wake handoff fixture contract=SECOND_UTTERANCE")
            onWakeHandoff(WakeOutcome.Handoff(WakeHandoff.SECOND_UTTERANCE, ""))
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
