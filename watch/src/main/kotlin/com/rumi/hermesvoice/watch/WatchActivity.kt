package com.rumi.hermesvoice.watch

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.rumi.hermesvoice.core.audio.QaAudio
import com.rumi.hermesvoice.core.audio.QaLaunchGuard
import com.rumi.hermesvoice.core.wake.WakeContract
import com.rumi.hermesvoice.core.watchlink.HapticEvent
import com.rumi.hermesvoice.core.watchlink.LoadStatus
import com.rumi.hermesvoice.core.watchlink.ReaderAction
import com.rumi.hermesvoice.core.watchlink.ReaderGestureMapping
import com.rumi.hermesvoice.core.watchlink.ReaderSurface
import com.rumi.hermesvoice.core.watchlink.SwipeDirection
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchPhase
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * The Watch UI: a conversation reader (session browser ↔ selected conversation, swipe left to
 * switch, swipe right or Back to send the app to the background, vertical touch or bezel to scroll)
 * with push-to-talk as a one-second hold anywhere on it (start, then a new hold to stop) and the
 * wake phrase state. It owns no voice logic: the wake flow, the recorder and the session live in
 * the application's [WatchVoiceRuntime] (`app.voice`), which this activity tells when it is shown
 * or hidden and whose state it renders.
 *
 * Background operation is on by default: every show of a real open ensures it (once per open; a
 * running session is reused, and after the notification's Stop nothing starts it again until the
 * user opens the app again). There is no background control in the app. Permission prompts are
 * asked here because only an activity can: each at most once per open, never by a swipe.
 */
class WatchActivity : ComponentActivity() {
    private val app by lazy { WatchApp.from(this) }
    private val voice get() = app.voice
    private var qaWakeHandoffPending = false
    private var qaHeardPending: Pair<String, Boolean>? = null
    private var qaHeardDelayMs = QA_HEARD_DELAY_MS

    /** This instance stands for a real user open (a launch, or the task brought back) not yet told to the runtime. */
    private var openPending = false

    /** A permission prompt this activity asked for is up: the stop and restart around it are not a new open. */
    private var promptShowing = false

    /** Asked in this open already (each prompt at most once per open; kept across recreation). */
    private var askedMicrophone = false
    private var askedNotifications = false

    /** The settings read of the current show; cancelled when the app leaves the screen. */
    private var settingsPull: Job? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        promptShowing = false
        voice.onPermissionResult()
    }

    /**
     * Asked once per open while notifications aren't allowed (Android 13+; the system stops asking
     * after repeated refusals). The session runs whatever the answer; the platform's answer, read
     * again then, decides whether it may listen (see WatchVoiceCoordinator.block).
     */
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        promptShowing = false
        voice.onPermissionResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only a fresh launcher entry grants open authority; restored tasks continue their old open.
        openPending = savedInstanceState == null && isUserOpen(intent)
        savedInstanceState?.let {
            promptShowing = it.getBoolean(STATE_PROMPT)
            askedMicrophone = it.getBoolean(STATE_ASKED_MIC)
            askedNotifications = it.getBoolean(STATE_ASKED_NOTIFICATIONS)
        }
        // Back sends the app to the background like the right swipe; it never finishes it.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = toBackground("back")
        })
        setContent {
            val talk by app.talk.collectAsStateWithLifecycle()
            val settings by app.settings.collectAsStateWithLifecycle()
            val phone by app.phoneReachable.collectAsStateWithLifecycle()
            val reader by app.reader.collectAsStateWithLifecycle()
            val listening by voice.wakeListening.collectAsStateWithLifecycle()
            val unavailable by voice.wakeUnavailable.collectAsStateWithLifecycle()
            val chatFocus = remember { FocusRequester() }
            val sessionsFocus = remember { FocusRequester() }
            val onScrollStep = { app.haptic(HapticEvent.SCROLL_STEP) }
            MaterialTheme(colors = WatchColors) {
                val history = reader.selectedHistory
                // The whole main screen takes the one-second hold that starts and stops recording (no Talk button).
                ReaderRoot(
                    modifier = Modifier.readerSwipe(::onSwipe),
                    surfaceKey = reader.surface to history?.sessionId,
                    recording = talk.phase == WatchPhase.RECORDING,
                    enabled = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && app.talk.value.phase != WatchPhase.SENDING },
                    onHoldToggle = ::onTalkPressed,
                ) {
                    when {
                        reader.surface == ReaderSurface.SESSIONS -> SessionsSurface(reader.sessions, reader.selectedSessionId, sessionsFocus,
                            onSelect = app::selectSession, onRefresh = app::loadSessions, onScrollStep = onScrollStep)
                        history != null -> ChatSurface(
                            title = reader.sessions.rows.firstOrNull { it.id == history.sessionId }?.title ?: "Conversation",
                            history = history, focusRequester = chatFocus, onOlder = app::loadOlder, onRetry = app::refreshSelected,
                            onScrollStep = onScrollStep,
                        ) { TalkStatusLine(talk, listening) }
                        else -> TalkHome(phone, talk, settings.watchWakeEnabled, listening, unavailable)
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
        // The screen stays on while recording and while a foreground-only window listens; never for a whole background session.
        lifecycleScope.launch {
            voice.keepScreenOn.collect { on ->
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isUserOpen(intent)) {
            openPending = true
            askedMicrophone = false
            askedNotifications = false
        }
        handleQaIntent(intent, restored = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PROMPT, promptShowing)
        outState.putBoolean(STATE_ASKED_MIC, askedMicrophone)
        outState.putBoolean(STATE_ASKED_NOTIFICATIONS, askedNotifications)
    }

    /** Launcher/notification-open delivery, never a screen transition, dialog or history restoration. */
    private fun isUserOpen(intent: Intent?): Boolean = intent?.action == Intent.ACTION_MAIN &&
        intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
        intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0

    override fun onResume() {
        super.onResume()
        if (openPending) {
            openPending = false
            voice.onUserOpened()
        }
        val visit = voice.onActivityResumed()
        // On by default: started from this visible show (for replies until the settings read below arms its microphone).
        voice.ensureBackground()
        // Reachability and the synced settings item first; only then may the Watch listen. The read
        // belongs to this show: leaving the screen cancels it, and a late one is ignored anyway.
        settingsPull?.cancel()
        settingsPull = lifecycleScope.launch {
            app.refreshPhoneReachable()
            pullSettings()
            voice.onSettingsPulled(visit)
        }
        // Titles for the chat header and the browser; the open conversation is re-read for new messages.
        if (app.reader.value.sessions.status == LoadStatus.IDLE) app.loadSessions()
        if (app.reader.value.selectedSessionId != null) app.refreshSelected()
        askPermissionsOnce()
        qaHeardPending?.let { (text, final) ->
            qaHeardPending = null
            // After this resume's window opened (it waits for the synced settings): the simulated recognizer's result.
            window.decorView.postDelayed({ voice.wake.qaHeard(text, final) }, qaHeardDelayMs)
        }
        if (qaWakeHandoffPending) {
            qaWakeHandoffPending = false
            // Posted: the wake window's new generation is set up after onResume returns.
            window.decorView.post {
                Log.i(TAG, "qa wake handoff fixture contract=SECOND_UTTERANCE gen=${voice.wake.wake.generation} " +
                    "watch_listens=${voice.wake.wake.enabledHere} (recorder path only, not recognition)")
                voice.wake.wake.qaSecondUtterance()
            }
        }
    }

    override fun onPause() {
        settingsPull?.cancel()
        settingsPull = null
        // Without an armed background session this ends listening and any recording, unsent (WakePresence).
        voice.onActivityPaused()
        super.onPause()
    }

    // ── background operation ─────────────────────────────────────────────────────────────────

    /**
     * The microphone (listening and talking) and, on Android 13+, notifications (the session's
     * notification with its Stop; without it the session only plays replies): each asked at most
     * once per open, one at a time. A refusal stands until the user opens the app again.
     */
    private fun askPermissionsOnce() {
        if (promptShowing) return
        when {
            !voice.hasMic() && !askedMicrophone -> {
                askedMicrophone = true
                promptShowing = true
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedNotifications &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> {
                askedNotifications = true
                promptShowing = true
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Right swipe or Back: the app goes to the background, always: never finished, and the session
     * is never stopped here. Background operation is made sure of first (it is normally already
     * running from this open); hiding never waits for the service or a permission.
     */
    private fun toBackground(reason: String) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) voice.ensureBackground()
        val status = voice.backgroundStatus.value
        Log.i(TAG, "task moved to back reason=$reason session_running=${status.running} microphone=${status.microphone}")
        moveTaskToBack(true)
    }

    private fun onSwipe(direction: SwipeDirection) {
        when (ReaderGestureMapping.action(direction)) {
            ReaderAction.TOGGLE_SURFACE -> {
                app.toggleReaderSurface()
                Log.i(TAG, "gesture swipe=$direction action=toggle surface=${app.reader.value.surface}")
            }
            // The task (and its reader state) stays alive; this is not process termination.
            ReaderAction.BACKGROUND_APP -> toBackground("swipe")
        }
    }

    /**
     * The settings data item may predate this install's listener; read it directly on resume, and
     * with it the Phone's count of answered wake requests, which the next wake window must know.
     */
    private suspend fun pullSettings() {
        pullItem(WatchLinkPaths.SETTINGS, app::applySettings)
        pullItem(WatchLinkPaths.WAKE_EPOCH, app::applyWakeEpoch)
    }

    private suspend fun pullItem(path: String, apply: (String) -> Unit) {
        runCatching {
            val items = Wearable.getDataClient(this).getDataItems(
                Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(path).build()).await()
            try {
                items.firstOrNull()?.let { DataMapItem.fromDataItem(it).dataMap.getString("json") }?.let(apply)
            } finally {
                items.release()
            }
        }
    }

    private fun onTalkPressed() {
        if (voice.onTalkPressed()) return
        // The user's own talk gesture asks for the microphone (also after a refusal at the open).
        promptShowing = true
        micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // ── debug QA (debuggable builds only) ────────────────────────────────────────────────────

    /**
     * `am start ... --es hv_qa_wav <name>.wav` uploads files/qa/<name>.wav as a push-to-talk turn;
     * `--es hv_qa_wake_handoff second_utterance` runs the wake handoff exactly as a recognizer match
     * would (fixture: it proves the recorder path, not recognition); `--es hv_qa_upload_delay_ms <ms>`
     * makes every upload wait before it is handed to the link (a slow transfer); `--es hv_qa_wake_delay_ms <ms>`
     * is when the simulated recognizer reports its result; `--ei hv_qa_seed_reader <n>
     * [--el hv_qa_seed_newest <row>]` shows synthetic reader rows ([WatchApp.seedReaderForQa]).
     * Once per fresh launch intent.
     */
    private fun handleQaIntent(intent: Intent?, restored: Boolean) {
        intent ?: return
        val wav = intent.getStringExtra(QaAudio.EXTRA)
        val handoff = intent.getStringExtra(QA_WAKE_HANDOFF)
        val seed = intent.getIntExtra(QA_SEED_READER, 0)
        val recognizer = intent.getStringExtra(QA_RECOGNIZER)
        val heard = intent.getStringExtra(QA_WAKE_HEARD)
        val heardFinal = intent.getBooleanExtra(QA_WAKE_FINAL, true)
        val heardDelay = intent.getStringExtra(QA_WAKE_DELAY)?.toLongOrNull()?.coerceIn(0L, WakeContract.WINDOW_MS - 300)
        val uploadDelay = intent.getStringExtra(QA_UPLOAD_DELAY)?.toLongOrNull()?.coerceIn(0L, 120_000L)
        if (wav == null && handoff == null && seed <= 0 && recognizer == null && heard == null && uploadDelay == null) return
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        val handled = intent.getBooleanExtra(QA_HANDLED, false)
        intent.removeExtra(QaAudio.EXTRA)
        intent.removeExtra(QA_WAKE_HANDOFF)
        intent.removeExtra(QA_SEED_READER)
        listOf(QA_RECOGNIZER, QA_WAKE_HEARD, QA_WAKE_FINAL, QA_WAKE_DELAY, QA_UPLOAD_DELAY).forEach(intent::removeExtra)
        intent.putExtra(QA_HANDLED, true)
        setIntent(intent)
        if (!QaLaunchGuard.shouldHandle(restored, fromHistory, handled)) return
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 || voice.capturing) return
        if (recognizer != null) voice.wake.qaFixtureRecognizer = recognizer == "fixture"
        if (heard != null) {
            qaHeardPending = heard to heardFinal
            qaHeardDelayMs = heardDelay ?: QA_HEARD_DELAY_MS
        }
        if (uploadDelay != null) {
            // A slow link for QA: every upload waits this long before it is handed to the Data Layer.
            app.qaUploadDelayMs = uploadDelay
            Log.i(TAG, "qa slow transfer set to $uploadDelay ms (debug builds only)")
        }
        if (recognizer != null || heard != null || uploadDelay != null) return
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
        private const val STATE_PROMPT = "hv_prompt_showing"
        private const val STATE_ASKED_MIC = "hv_asked_microphone"
        private const val STATE_ASKED_NOTIFICATIONS = "hv_asked_notifications"
        private const val QA_WAKE_HANDOFF = "hv_qa_wake_handoff"
        private const val QA_RECOGNIZER = "hv_qa_recognizer"
        private const val QA_WAKE_HEARD = "hv_qa_wake_heard"
        private const val QA_WAKE_FINAL = "hv_qa_wake_final"
        private const val QA_WAKE_DELAY = "hv_qa_wake_delay_ms"
        private const val QA_UPLOAD_DELAY = "hv_qa_upload_delay_ms"
        private const val QA_HEARD_DELAY_MS = 1_500L
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
