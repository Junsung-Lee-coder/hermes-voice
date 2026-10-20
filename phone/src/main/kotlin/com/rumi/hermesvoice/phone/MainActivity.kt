package com.rumi.hermesvoice.phone

import android.Manifest
import com.rumi.hermesvoice.core.notify.ReplyAlertContent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.ui.text.style.TextOverflow
import com.rumi.hermesvoice.core.attachments.AttachmentRef
import com.rumi.hermesvoice.core.activity.ActivityBubble
import com.rumi.hermesvoice.core.chat.FailedSend
import com.rumi.hermesvoice.core.chat.PendingSend
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.voice.PendingPhase
import com.rumi.hermesvoice.core.voice.PendingTurn
import com.rumi.hermesvoice.core.audio.QaAudio
import com.rumi.hermesvoice.core.audio.QaLaunchGuard
import com.rumi.hermesvoice.core.background.BackgroundText
import com.rumi.hermesvoice.core.net.HistoryMessage
import com.rumi.hermesvoice.core.net.OutgoingAttachment
import com.rumi.hermesvoice.core.sessions.AppConversation
import com.rumi.hermesvoice.core.settings.HelpTopic
import com.rumi.hermesvoice.core.settings.LaterReplyWindow
import com.rumi.hermesvoice.core.settings.SettingsHelp
import com.rumi.hermesvoice.core.settings.ThemeMode
import com.rumi.hermesvoice.core.settings.VadSilence
import com.rumi.hermesvoice.core.settings.WakeGate
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.voice.EpisodeMicrophone
import com.rumi.hermesvoice.core.wake.ClaimVerdict
import com.rumi.hermesvoice.core.wake.WakeArmInputs
import com.rumi.hermesvoice.core.wake.WakeClaim
import com.rumi.hermesvoice.core.wake.WakeClaimPort
import com.rumi.hermesvoice.core.wake.WakeBlock
import com.rumi.hermesvoice.core.wake.WakeContract
import com.rumi.hermesvoice.core.wake.WakeDevicePort

class MainActivity : ComponentActivity() {
    /** Check keys delivered to this Activity before normal dispatch; physical headset delivery is OS-owned. */
    // Public Activity SDK override: activity 1.9.1 inherits it from core 1.13.1's restricted implementation
    // class. Keep the AndroidX superclass fallback; this does not use its internal superDispatchKeyEvent API.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (PhoneApp.from(this).headsetMedia.onForegroundKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private val model: PhoneViewModel by viewModels()
    private lateinit var phoneWake: PhoneWakeController
    private var qaWakeHandoffPending = false
    private var qaHeardPending: Pair<String, Boolean>? = null
    private var qaHeardDelayMs = QA_HEARD_DELAY_MS
    private var recognizerAvailable by mutableStateOf(false)
    private val handoffRunnable: Runnable = Runnable {
        // A later reply does not count here: the accepted phrase owns the microphone (it stopped that reply).
        if (!phoneWake.wake.onHandoffDue(captureIdle = model.captureIdle())) Log.i(TAG, "phone wake handoff dropped gen=${phoneWake.wake.generation}")
    }

    /** The wake episode's microphone hold, from the phrase (before the accepted cue) to its recording or request. */
    private val episodeMicrophone by lazy { EpisodeMicrophone(PhoneApp.from(this).audio, VoiceOrigin.PHONE) }

    /** What the shared wake flow ([PhoneWakeController.wake]) does on this Phone. */
    private val wakePort: WakeDevicePort = object : WakeDevicePort {
        override fun windowChanged(open: Boolean) = model.setWakeListening(open)

        override fun scheduleHandoff(delayMs: Long) {
            // Give the recognizer's microphone a moment to be released before our recorder opens it.
            window.decorView.removeCallbacks(handoffRunnable)
            window.decorView.postDelayed(handoffRunnable, delayMs)
        }

        override fun cancelHandoff() {
            window.decorView.removeCallbacks(handoffRunnable)
        }
        override fun startRequestCapture(silenceMs: Long): Boolean = startRequestCapture(silenceMs, null)
        override fun startRequestCapture(silenceMs: Long, claimId: String?): Boolean =
            hasMic() && model.startHandsFree(silenceMs, claimId, episodeMicrophone.take())
        override fun cancelRequestCapture(reason: String) = model.cancelHandsFree(reason)
        override fun sendRecognized(request: String) = sendRecognized(request, null)
        override fun sendRecognized(request: String, claimId: String?) = model.sendRecognizedRequest(request, claimId, episodeMicrophone.take())

        // From the phrase until the recording or request takes it over: no later reply starts on this Phone.
        override fun holdMicrophone(held: Boolean) = episodeMicrophone.hold(held)

        override fun closed(reason: String) {
            Log.i(TAG, "phone wake window closed reason=$reason")
            model.onWakeClosed(reason)
        }

        override fun armInputs() = WakeArmInputs(
            enabled = true,
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            interactive = getSystemService(PowerManager::class.java)?.isInteractive == true,
            ambient = false,
            permission = hasMic(),
            microphoneMuted = getSystemService(AudioManager::class.java)?.isMicrophoneMute == true,
            talkIdle = model.voiceIdle(),
            // The Phone delivers requests itself: it needs a Hermes sign-in, not a reachable peer.
            phoneReachable = model.state.value.signedIn,
            nowMs = SystemClock.elapsedRealtime(),
            cooldownUntilMs = PhoneApp.from(this@MainActivity).lastPhonePlaybackEndedAtMs.let {
                if (it == 0L) 0L else it + WakeContract.PLAYBACK_COOLDOWN_MS
            },
            generation = 0,
            lastArmedGeneration = null,
        )

        override fun armBlocked(source: String, block: WakeBlock) {
            if (block != WakeBlock.DISABLED) Log.i(TAG, "phone wake window blocked source=$source reason=$block")
        }

        /** A wake phrase was accepted here (see WakeDevicePort.wakeAccepted): one short pulse, screen on or off. */
        override fun wakeAccepted() = PhoneHaptics(this@MainActivity).wakeAccepted()
    }

    private val claimTimer: Runnable = Runnable { phoneWake.wake.onClaimTimer() }

    /**
     * "Both": the Phone is the coordinator, so its own claims are direct calls to the shared
     * [com.rumi.hermesvoice.core.wake.WakeAdmission] the Watch's claims also go to. Answers are
     * posted, like the Watch's arrive, so a claim is never answered re-entrantly.
     */
    private val claimPort: WakeClaimPort = object : WakeClaimPort {
        private fun admission() = runCatching { PhoneApp.from(this@MainActivity).wiring().core.wakeAdmission }.getOrNull()

        override fun newClaimId(): String = "p-" + UUID.randomUUID().toString()

        override fun epoch(): Long = admission()?.epoch ?: 0L

        override fun request(claimId: String, settingsRevision: Long, generation: Long, epoch: Long) {
            val verdict = admission()?.claim(WakeClaim(claimId, VoiceOrigin.PHONE, "", settingsRevision, generation, epoch)) ?: ClaimVerdict.EXPIRED
            Log.i(TAG, "phone wake claim ${claimId.take(10)} gen=$generation epoch=$epoch verdict=$verdict")
            window.decorView.post { phoneWake.wake.onClaimVerdict(claimId, verdict) }
        }

        override fun renew(claimId: String) {
            val verdict = admission()?.renew(claimId, VoiceOrigin.PHONE, "") ?: ClaimVerdict.EXPIRED
            if (verdict != ClaimVerdict.GRANTED) window.decorView.post { phoneWake.wake.onClaimVerdict(claimId, verdict) }
        }

        override fun release(claimId: String) {
            admission()?.release(claimId, VoiceOrigin.PHONE, "")
            Log.i(TAG, "phone wake claim ${claimId.take(10)} released")
        }

        override fun scheduleTimer(delayMs: Long) {
            window.decorView.removeCallbacks(claimTimer)
            window.decorView.postDelayed(claimTimer, delayMs)
        }

        override fun cancelTimer() {
            window.decorView.removeCallbacks(claimTimer)
        }
    }

    /** A Talk tap waiting for the recognizer's microphone to be released. */
    private var talkPending = false
    private val talkAfterRelease: Runnable = Runnable {
        talkPending = false
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) model.toggleRecording()
    }

    /**
     * The Talk button. During a hands-free recording it sends it now. While the wake window is
     * open, the recognizer is released first and push-to-talk starts after the same short pause
     * as the wake handoff, so the two never hold the microphone together.
     */
    private fun onTalk() {
        if (talkPending) return
        if (model.handsFreeCapturing() || model.state.value.recording) return model.toggleRecording()
        val listening = model.state.value.handsFree == HandsFree.LISTENING
        phoneWake.wake.onBusy()
        if (!listening) return model.toggleRecording()
        talkPending = true
        Log.i(TAG, "phone talk tapped in the wake window: recognizer released, recording in ${WakeContract.MIC_HANDOFF_MS} ms")
        window.decorView.postDelayed(talkAfterRelease, WakeContract.MIC_HANDOFF_MS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars(dark = PhoneApp.from(this).settings.themeMode.isDark(systemDark = isSystemNight()))
        phoneWake = PhoneWakeController(this, wakePort, model.state.value.watch, claimPort)
        lifecycle.addObserver(phoneWake)
        model.onHandsFreeEnded = { sent -> phoneWake.wake.onRequestCaptureEnded(sent) }
        recognizerAvailable = phoneWake.recognizerAvailable()
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val dark = state.themeMode.isDark(isSystemInDarkTheme())
            DisposableEffect(dark) {
                applySystemBars(dark)
                onDispose {}
            }
            HermesVoiceTheme(dark) {
                // The phone-local text size multiplies the Android system font scale; dp density is left alone.
                val system = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(system.density, system.fontScale * state.fontScalePercent / 100f)) {
                    PhoneScreen(model, recognizerAvailable, onTalk = ::onTalk)
                }
            }
        }
        // Phone-owned settings: a mode that excludes the Phone stops listening and any hands-free capture at once.
        lifecycleScope.launch { model.state.map { it.watch }.distinctUntilChanged().collect { phoneWake.wake.onSettings(it) } }
        lifecycleScope.launch { model.state.map { it.signedIn }.distinctUntilChanged().collect { phoneWake.onSignInChanged(it) } }
        lifecycleScope.launch {
            combine(PhoneApp.from(this@MainActivity).phoneWake.status, PhoneWakeService.admission) { _, _ -> Unit }
                .collect { phoneWake.onEnvironmentChanged() }
        }
        // Both: a wake request was admitted (from the Watch, or this Phone's own): a window that was already listening closes.
        lifecycleScope.launch {
            PhoneApp.from(this@MainActivity).wakeEpisodes.collect { episode -> episode?.let { phoneWake.wake.onEpisodeAnswered(it.epoch, it.claimId) } }
        }
        // Push-to-talk or a turn in flight closes the window and ends a wake episode; a later reply holding
        // this Phone's speaker closes an open window only (an episode under way owns the microphone). Idle
        // again may re-arm (once per visibility generation).
        lifecycleScope.launch {
            combine(model.state.map { it.recording || it.voiceBusy }, PhoneApp.from(this@MainActivity).speakingLater) { busy, later ->
                if (busy) WakeBusy.VOICE else if (later) WakeBusy.SPEAKER else WakeBusy.IDLE
            }.distinctUntilChanged().collect { busy ->
                when (busy) {
                    WakeBusy.VOICE -> phoneWake.wake.onBusy()
                    WakeBusy.SPEAKER -> phoneWake.wake.onPlaybackBusy()
                    WakeBusy.IDLE -> if (model.voiceIdle()) phoneWake.wake.onIdle()
                }
            }
        }
        if (model.state.value.signedIn) model.refresh()
        // A tapped arrival alert starts the app (cold or from the task): only a fresh launch intent counts, never a restored one.
        if (savedInstanceState == null) handleReplyAlertIntent(intent)
        handleQaIntent(intent, restored = savedInstanceState != null)
    }

    override fun onStart() {
        super.onStart()
        // Visible: a background relay the user left switched on may (re)start now, and only now.
        PhoneApp.from(this).onActivityStarted()
    }

    override fun onStop() {
        PhoneApp.from(this).diagnostics.flush()
        PhoneApp.from(this).onActivityStopped()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        recognizerAvailable = phoneWake.recognizerAvailable()
        qaHeardPending?.let { (text, final) ->
            qaHeardPending = null
            // After the resume's window opened: the simulated recognizer reports this result.
            window.decorView.postDelayed({ phoneWake.qaHeard(text, final) }, qaHeardDelayMs)
        }
        if (qaWakeHandoffPending) {
            qaWakeHandoffPending = false
            // Posted: lifecycle observers (the wake window's new generation) run after onResume returns.
            window.decorView.post {
                Log.i(TAG, "qa wake handoff fixture contract=SECOND_UTTERANCE gen=${phoneWake.wake.generation} " +
                    "phone_listens=${phoneWake.wake.enabledHere} (recorder path only, not recognition)")
                phoneWake.wake.qaSecondUtterance()
            }
        }
    }

    override fun onPause() {
        window.decorView.removeCallbacks(handoffRunnable)
        window.decorView.removeCallbacks(talkAfterRelease)
        talkPending = false
        super.onPause()
    }

    override fun onDestroy() {
        model.onHandsFreeEnded = null
        // Never left held by a screen that is gone (the paused wake flow already gave it back).
        episodeMicrophone.hold(false)
        super.onDestroy()
    }

    private enum class WakeBusy { IDLE, VOICE, SPEAKER }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleReplyAlertIntent(intent)
        handleQaIntent(intent, restored = false)
    }

    /** A tapped arrival alert names one conversation; the screen opens it (see [PhoneApp.pendingOpen]). Not the routed preference. */
    private fun handleReplyAlertIntent(intent: Intent?) {
        val session = ReplyAlertContent.sessionOf(intent?.action, intent?.getStringExtra(ReplyAlertContent.EXTRA_SESSION_ID)) ?: return
        PhoneApp.from(this).requestOpenConversation(session)
        // Consumed: a recreation of this activity must not open it again.
        intent?.action = null
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Debuggable builds only, once per fresh launch intent (see [QaLaunchGuard]):
     * `--es hv_qa_wav <name>.wav` submits files/qa/<name>.wav as a Phone voice request;
     * `--es hv_qa_wake_handoff second_utterance` runs the phrase-only wake handoff exactly as a
     * recognizer match would (fixture: it proves the recorder path, not recognition);
     * `--es hv_qa_wake_location <OFF|WATCH|PHONE|BOTH>` and `--es hv_qa_vad_silence <seconds>` save
     * and publish the settings through the same path as the Settings screen (invalid values are refused);
     * `--es hv_qa_recognizer fixture` replaces the platform recognizer with a simulated one (`real`
     * restores it) and `--es hv_qa_wake_heard "<text>" [--ez hv_qa_wake_final false]` is its result
     * for the window this launch opens (it tests the wake flow and arbitration, never recognition).
     */
    private fun handleQaIntent(intent: Intent?, restored: Boolean) {
        intent ?: return
        val name = intent.getStringExtra(QaAudio.EXTRA)
        val handoff = intent.getStringExtra(QA_WAKE_HANDOFF)
        val location = intent.getStringExtra(QA_WAKE_LOCATION)
        val silence = intent.getStringExtra(QA_VAD_SILENCE)
        val recognizer = intent.getStringExtra(QA_RECOGNIZER)
        val heard = intent.getStringExtra(QA_WAKE_HEARD)
        val heardFinal = intent.getBooleanExtra(QA_WAKE_FINAL, true)
        val heardDelay = intent.getStringExtra(QA_WAKE_DELAY)?.toLongOrNull()?.coerceIn(0L, WakeContract.WINDOW_MS - 300)
        if (name == null && handoff == null && location == null && silence == null && recognizer == null && heard == null) return
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        val handled = intent.getBooleanExtra(QA_HANDLED, false)
        listOf(QaAudio.EXTRA, QA_WAKE_HANDOFF, QA_WAKE_LOCATION, QA_VAD_SILENCE, QA_RECOGNIZER, QA_WAKE_HEARD, QA_WAKE_FINAL, QA_WAKE_DELAY).forEach(intent::removeExtra)
        intent.putExtra(QA_HANDLED, true)
        setIntent(intent)
        if (!QaLaunchGuard.shouldHandle(restored, fromHistory, handled)) return
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (location != null || silence != null) {
            val mode = location?.let { WakeLocation.parse(it) ?: run { Log.w(TAG, "qa settings refused: unknown wake location"); return } }
            val seconds = silence?.let { VadSilence.validOrNull(it.toDoubleOrNull()) ?: run { Log.w(TAG, "qa settings refused: invalid trailing silence"); return } }
            val current = model.state.value.watch
            model.updateWatch(current.copy(wakeLocation = mode ?: current.wakeLocation, vadSilenceSeconds = seconds ?: current.vadSilenceSeconds))
        }
        if (handoff == "second_utterance") qaWakeHandoffPending = true
        if (recognizer != null) phoneWake.qaFixtureRecognizer = recognizer == "fixture"
        if (heard != null) {
            qaHeardPending = heard to heardFinal
            qaHeardDelayMs = heardDelay ?: QA_HEARD_DELAY_MS
        }
        val file = name?.let { QaAudio.resolve(File(filesDir, QaAudio.DIR), it) } ?: return
        Log.i("HermesVoice", "qa audio submitted as a phone voice request bytes=${file.length()}")
        model.submitQaWav(file.readBytes())
    }

    private companion object {
        const val TAG = "HermesVoiceWake"
        const val QA_HANDLED = "hv_qa_handled"
        const val QA_WAKE_HANDOFF = "hv_qa_wake_handoff"
        const val QA_WAKE_LOCATION = "hv_qa_wake_location"
        const val QA_VAD_SILENCE = "hv_qa_vad_silence"
        const val QA_RECOGNIZER = "hv_qa_recognizer"
        const val QA_WAKE_HEARD = "hv_qa_wake_heard"
        const val QA_WAKE_FINAL = "hv_qa_wake_final"
        const val QA_WAKE_DELAY = "hv_qa_wake_delay_ms"
        const val QA_HEARD_DELAY_MS = 600L
    }

    private fun isSystemNight(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** Edge-to-edge with transparent bars; icons are light on the dark theme and dark on the light one. */
    private fun applySystemBars(dark: Boolean) {
        val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}

internal enum class Tab(val label: String) { CONVERSATIONS("Conversations"), CHAT("Chat"), SETTINGS("Settings") }

@Composable
private fun PhoneScreen(model: PhoneViewModel, recognizerAvailable: Boolean, onTalk: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.CONVERSATIONS) }
    var creating by rememberSaveable { mutableStateOf(false) }
    // A routed turn's conversation was opened for the user ("Open the routed conversation"): show it,
    // once per request (not again when the screen is recreated).
    var shownOpenRequest by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(state.chatOpenRequest) {
        if (state.chatOpenRequest != shownOpenRequest) {
            shownOpenRequest = state.chatOpenRequest
            creating = false
            tab = Tab.CHAT
        }
    }
    // A conversation was created but could not be opened: the dialog closes and the list (with the new card and the status) stays.
    var shownCreateClosed by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(state.createClosedRequest) {
        if (state.createClosedRequest != shownCreateClosed) {
            shownCreateClosed = state.createClosedRequest
            creating = false
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    // The conversation is "shown" only on the Chat tab (the app being in the foreground is the activity's start/stop).
    val shownConversation = state.selected?.storedSessionId?.takeIf { tab == Tab.CHAT }
    DisposableEffect(shownConversation) {
        val app = PhoneApp.from(context)
        app.presence.setShown(shownConversation)
        onDispose { app.presence.setShown(null) }
    }
    val view = LocalView.current
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Background listening re-checks the platform's answer (it may now be allowed to listen).
        model.onPermissionsChanged()
        if (granted) onTalk()
    }
    // Hands-free recording start ("speak now") and end: a short system haptic, no sound (a tone would be recorded).
    LaunchedEffect(state.hapticTick) {
        if (state.hapticTick > 0) {
            view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
        }
    }
    val requestBackgroundAnswers = rememberBackgroundRelayRequest(model)
    val goBack: () -> Unit = {
        if (tab != Tab.CONVERSATIONS) {
            model.onUserNavigated()
            tab = Tab.CONVERSATIONS
        }
    }
    // Back leaves Chat and Settings for the list (nothing is cancelled or changed); only from the list does it send the task to the background.
    BackHandler(enabled = tab != Tab.CONVERSATIONS) { goBack() }
    BackHandler(enabled = tab == Tab.CONVERSATIONS) { context.findActivity()?.moveTaskToBack(true) }
    val chatKey = state.selected?.storedSessionId.orEmpty()
    val chatList = rememberSaveable(chatKey, saver = LazyListState.Saver) { LazyListState() }
    val chatFollow = rememberSaveable(chatKey, saver = ChatFollowSaver) { ChatFollow() }
    PhoneChrome(
        title = state.selected?.title?.takeIf { tab == Tab.CHAT } ?: "Hermes Voice",
        tab = tab,
        onBack = goBack,
        statusLines = listOf(state.status, state.voiceStatus, state.micNotice.orEmpty(), state.recoveryNotice.orEmpty(), state.cacheNotice.orEmpty()),
        talkBar = if (!state.signedIn) null else ({
            TalkBar(state, onStopPending = model::stopPending, onPlayAgain = model::playAgain, onBackgroundAnswers = requestBackgroundAnswers) {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) onTalk() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }),
        onSettings = {
            if (tab != Tab.SETTINGS) model.onUserNavigated()
            tab = Tab.SETTINGS
        },
        onNewConversation = if (state.signedIn) ({ creating = true }) else null,
    ) {
        when (tab) {
            Tab.CONVERSATIONS -> ConversationsTab(state, model, onOpen = {
                model.onUserNavigated()
                model.openOrResume(it.owned)
                tab = Tab.CHAT
            })
            Tab.CHAT -> ChatTab(state, model, chatList, chatFollow, goBack)
            Tab.SETTINGS -> SettingsTab(state, model, recognizerAvailable)
        }
    }
    if (creating && tab == Tab.CONVERSATIONS && state.signedIn) NewConversationDialog(state, model) { creating = false }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The Phone's frame: title bar (a back arrow on Chat and Settings; on the list the New conversation plus and the Settings gear at the
 * far right), content, and at the bottom the one Talk bar (when signed in). The keyboard lifts the content (e.g. the chat composer)
 * by exactly what the bottom bar doesn't already cover: the bar's height is consumed as insets, so nothing is padded twice.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PhoneChrome(
    title: String,
    tab: Tab,
    onBack: () -> Unit,
    statusLines: List<String>,
    talkBar: (@Composable () -> Unit)?,
    onSettings: (() -> Unit)? = null,
    onNewConversation: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (tab != Tab.CONVERSATIONS) {
                        IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("back_button")) {
                            Icon(ComposerIcons.Back, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (tab == Tab.CONVERSATIONS) {
                        if (onNewConversation != null) {
                            IconButton(onClick = onNewConversation, modifier = Modifier.size(48.dp).testTag("new_conversation_button")) {
                                Icon(ComposerIcons.Plus, contentDescription = "New conversation")
                            }
                        }
                        if (onSettings != null) {
                            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp).testTag("settings_button")) {
                                Icon(ComposerIcons.Gear, contentDescription = "Settings")
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
        bottomBar = {
            // The one Talk bar sits at the bottom, above the system navigation inset, never over the chat composer.
            if (talkBar != null) Column(Modifier.testTag("bottom_bar")) { talkBar() }
        },
    ) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            statusLines.filter { it.isNotBlank() }.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            content()
        }
    }
}

/** Full-width push-to-talk (a 40dp button inside a 48dp touch target), within the horizontal safe area and above the navigation inset. */
@Composable
internal fun TalkBar(
    state: PhoneUiState,
    onStopPending: (String) -> Unit = {},
    onPlayAgain: (com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator.UnplayedReply) -> Unit = {},
    onBackgroundAnswers: () -> Unit = {},
    onTalk: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PendingTurnsList(state.pending, onStopPending)
            UnplayedRepliesList(state.unplayed, onPlayAgain)
            if (state.unplayed.isNotEmpty()) BackgroundAnswersStatus(state, tag = "unplayed_background", onlyWhenNeeded = true, onTurnOn = onBackgroundAnswers)
            if (!state.routingEnabled) {
                // Routing off: where this phone's voice requests go, or that one has to be chosen first.
                val target = state.selected
                Text(target?.let { "Routing off: voice requests go to ${it.title.ifBlank { it.alias }}" }
                    ?: "Routing off: open a conversation to send voice requests",
                    style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                    color = if (target == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp).testTag("routing_target"))
            }
            val handsFreeLine = when (state.handsFree) {
                HandsFree.LISTENING -> "Listening for the wake phrase…"
                HandsFree.GET_READY -> "Get ready…"
                HandsFree.SPEAK_NOW -> "Speak now. It sends when you stop, or tap to send"
                HandsFree.IDLE -> null
            }
            if (handsFreeLine != null) {
                Text(handsFreeLine, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 6.dp).testTag("hands_free"))
            }
            val capturing = state.handsFree == HandsFree.GET_READY || state.handsFree == HandsFree.SPEAK_NOW
            val urgent = state.recording || capturing
            // The whole 48dp row is the touch target; the visible button inside it is 40dp (taller only when the text size needs it).
            Box(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
                    .clickable(role = Role.Button, onClick = onTalk).testTag("talk"),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    contentColor = if (urgent) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("talk_visual"),
                ) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(if (state.recording) "Stop & send" else if (capturing) "Send now" else "Talk",
                            fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/**
 * The voice requests that are not finished, each with its own Stop. A request waiting for its reply holds no microphone,
 * audio focus or wake lock, and a new one may be started meanwhile; there is no progress to show, so none is invented.
 */
@Composable
internal fun PendingTurnsList(pending: List<PendingTurn>, onStop: (String) -> Unit) {
    if (pending.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("pending_requests")) {
        pending.forEachIndexed { index, turn ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(pendingLabel(turn), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).testTag("pending_$index"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onStop(turn.turnId) }, modifier = Modifier.testTag("pending_stop_$index")) { Text("Stop") }
            }
        }
    }
}

/** Replies that were received and shown but whose audio did not play, each with a real "Play again" (no text here: the reply is in the conversation). */
@Composable
internal fun UnplayedRepliesList(unplayed: List<com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator.UnplayedReply>, onPlayAgain: (com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator.UnplayedReply) -> Unit) {
    if (unplayed.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("unplayed_replies")) {
        unplayed.forEachIndexed { index, reply ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (reply.origin == VoiceOrigin.WATCH) "A Watch request's reply was not played" else "A reply was not played",
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).testTag("unplayed_$index"),
                    color = MaterialTheme.colorScheme.error)
                if (reply.playableHere) {
                    TextButton(onClick = { onPlayAgain(reply) }, modifier = Modifier.testTag("unplayed_play_$index")) { Text("Play again") }
                } else {
                    Text("Play it again from the Watch", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp).testTag("unplayed_unavailable_$index"))
                }
            }
        }
    }
}

/**
 * The opt-in for hearing headset answers with this app closed (Android 15+): its honest state and, when it can help, the one action
 * that turns on the existing background relay. A tap on this visible screen is the only thing that asks for it.
 */
@Composable
internal fun BackgroundAnswersStatus(state: PhoneUiState, tag: String, onlyWhenNeeded: Boolean, onTurnOn: () -> Unit) {
    val setup = com.rumi.hermesvoice.core.headset.HeadsetBackground.state(state.useHeadset, state.androidSdk, state.relay, state.relayForeground)
    val text = compactHeadsetBackgroundLine(setup) ?: return
    if (onlyWhenNeeded && !com.rumi.hermesvoice.core.headset.HeadsetBackground.offersAction(setup)) return
    Column(Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("${tag}_status"),
            color = if (setup == com.rumi.hermesvoice.core.headset.HeadsetBackgroundState.ACTIVE) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
        com.rumi.hermesvoice.core.headset.HeadsetBackground.actionLabel(setup)?.let { label ->
            OutlinedButton(onClick = onTurnOn, modifier = Modifier.testTag("${tag}_action")) { Text(label) }
        }
    }
}

/** Turns on the existing background relay for a tap, asking for the notification permission the first time (the relay starts whatever the answer). */
@Composable
internal fun rememberBackgroundRelayRequest(model: PhoneViewModel): () -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.setBackgroundRelay(true) }
    return {
        val shown = PhoneApp.from(context).relayNotificationCapability().shown
        if (!shown && model.askNotificationsOnce()) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else model.setBackgroundRelay(true)
    }
}

internal fun pendingLabel(turn: PendingTurn): String {
    val to = turn.alias?.let { " to $it" }.orEmpty()
    val from = if (turn.origin == VoiceOrigin.WATCH) "Watch request" else "Request"
    return when (turn.phase) {
        PendingPhase.TRANSMITTING, PendingPhase.QUEUED -> "$from$to: sending…"
        PendingPhase.AWAITING -> "$from$to: waiting for the reply (no limit while it keeps working)"
        PendingPhase.SPEAKING -> "$from$to: speaking the reply"
        PendingPhase.WAITING_STT -> "$from$to: waiting for the speech server, will try again (Stop cancels)"
        PendingPhase.WAITING_AUDIO -> "$from$to: the reply's audio did not play yet, trying again (Stop cancels)"
    }
}

@Composable
private fun ConversationsTab(state: PhoneUiState, model: PhoneViewModel, onOpen: (AppConversation) -> Unit) {
    if (!state.signedIn) {
        SignInScreen(state, model)
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !state.showArchived, onClick = { model.setShowArchived(false) }, label = { Text("Active") })
                FilterChip(selected = state.showArchived, onClick = { model.setShowArchived(true) }, label = { Text("Archived") })
                TextButton(onClick = model::refresh) { Text("Refresh") }
                InfoHelpButton(SettingsHelp.sessionMarker())
            }
        }
        items(state.conversations, key = { it.owned.storedSessionId }) { conversation ->
            val id = conversation.owned.storedSessionId
            ConversationRow(
                id = id,
                title = conversation.stored.title.ifBlank { conversation.owned.title }.ifBlank { "Untitled" },
                archivedView = state.showArchived,
                busy = id in state.archiving,
                unread = conversation.unread,
                onOpen = { onOpen(conversation) },
                onToggleArchive = { model.setArchived(conversation.owned, !state.showArchived) },
            )
        }
        if (state.conversations.isEmpty()) item {
            Text(if (state.showArchived) "No archived conversations." else "No conversations yet. Tap + to start one, or just talk: one is created when nothing fits.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The compact creation form behind the plus: the existing title, alias and description inputs. Cancel or dismiss creates nothing. */
@Composable
private fun NewConversationDialog(state: PhoneUiState, model: PhoneViewModel, onClose: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var alias by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        // The window is sized by the dialog itself (the platform default width cannot lay out the text inputs under Robolectric).
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 16.dp).testTag("create_dialog"),
        title = { Text("New conversation") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("create_title"))
                OutlinedTextField(alias, { alias = it }, label = { Text("Voice alias (e.g. work)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("create_alias"))
                OutlinedTextField(description, { description = it }, label = { Text("What goes here (helps voice routing)") },
                    modifier = Modifier.fillMaxWidth().testTag("create_description"))
                if (failed && state.status.isNotBlank()) {
                    Text(state.status, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("create_error"))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    busy = true
                    failed = false
                    model.createConversation(title, alias, description) { created ->
                        busy = false
                        if (!created) failed = true
                    }
                },
                enabled = alias.isNotBlank() && !busy,
                modifier = Modifier.testTag("create_submit"),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy, modifier = Modifier.testTag("create_cancel")) { Text("Cancel") } },
    )
}

private const val SWIPE_COMMIT_FRACTION = 0.4f

/**
 * One compact title-only conversation row. Active rows open on a tap and archive on a committed left swipe; archived rows only
 * restore, on a committed right swipe. A release short of the commit distance, a cancelled gesture or the wrong direction snaps
 * back and does nothing. The same actions are offered to accessibility services as custom actions, so no button is drawn.
 */
@Composable
private fun ConversationRow(
    id: String, title: String, archivedView: Boolean, busy: Boolean, onOpen: () -> Unit, onToggleArchive: () -> Unit,
    unread: Boolean = false,
) {
    val toggle by rememberUpdatedState(onToggleArchive)
    val label = if (archivedView) "Unarchive" else "Archive"
    val sign = if (archivedView) 1f else -1f
    var widthPx by remember { mutableStateOf(0) }
    var dx by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val shown by animateFloatAsState(dx, if (dragging) snap() else tween(120), label = "conversation_row_offset")
    Box(Modifier.fillMaxWidth().onSizeChanged { widthPx = it.width }.pointerInput(archivedView, busy) {
        if (busy) return@pointerInput
        try {
            detectHorizontalDragGestures(
                onDragStart = { dragging = true },
                onDragEnd = {
                    val travelled = dx * sign
                    dragging = false
                    dx = 0f
                    if (widthPx > 0 && travelled >= widthPx * SWIPE_COMMIT_FRACTION) toggle()
                },
                onDragCancel = { dragging = false; dx = 0f },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    dx = ((dx + amount) * sign).coerceIn(0f, widthPx.toFloat()) * sign
                },
            )
        } finally {
            if (dragging) { dragging = false; dx = 0f }
        }
    }) {
        if (shown != 0f) {
            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.medium)
                .padding(horizontal = 16.dp), contentAlignment = if (archivedView) Alignment.CenterStart else Alignment.CenterEnd) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().offset { IntOffset(shown.roundToInt(), 0) }.alpha(if (busy) 0.5f else 1f)
                .testTag("conversation_row_$id")
                .semantics(mergeDescendants = true) {
                    customActions = if (busy) emptyList() else listOf(CustomAccessibilityAction(label) { onToggleArchive(); true })
                }
                .then(if (archivedView) Modifier else Modifier.clickable(enabled = !busy, onClickLabel = "Open", onClick = onOpen)),
        ) {
            Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.testTag("conversation_title_$id"))
            }
        }
    }
}

/** Signed-out landing screen: the dashboard to use, then the system-browser sign-in. */
@Composable
private fun SignInScreen(state: PhoneUiState, model: PhoneViewModel) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), contentAlignment = Alignment.TopCenter) {
        Card(Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Sign in to Hermes", style = MaterialTheme.typography.headlineSmall)
                Text("Enter your Hermes dashboard address. Sign-in finishes in the browser and returns here.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ConnectionFields(state, model, primaryAction = "Sign in")
            }
        }
    }
}

/** Dashboard URL + profile, with Save and either Sign in (signed out) or Sign out (with a confirmation dialog). */
@Composable
private fun ConnectionFields(state: PhoneUiState, model: PhoneViewModel, primaryAction: String) {
    var url by remember(state.dashboardUrl) { mutableStateOf(state.dashboardUrl) }
    var profile by remember(state.profile) { mutableStateOf(state.profile) }
    var confirmSignOut by remember { mutableStateOf(false) }
    OutlinedTextField(url, { url = it }, label = { Text("Dashboard URL (https, or http to loopback/Tailscale)") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(profile, { profile = it }, label = { Text("Profile (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { model.saveConnection(url, profile) }) { Text("Save") }
        if (state.signedIn) {
            OutlinedButton(onClick = { confirmSignOut = true }) { Text("Sign out") }
        } else {
            Button(onClick = { model.saveConnection(url, profile); model.signIn() }, enabled = url.isNotBlank()) { Text(primaryAction) }
        }
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("This phone forgets its Hermes sign-in. Your conversations stay on the dashboard.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; model.signOut() }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ChatTab(state: PhoneUiState, model: PhoneViewModel, listState: LazyListState, follow: ChatFollow, onBack: () -> Unit) {
    val selected = state.selected
    if (selected == null) {
        Text("Open a conversation first.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::addAttachment) }
    val appContext = androidx.compose.ui.platform.LocalContext.current
    val openMessageAttachment: (HistoryMessage, AttachmentRef) -> Unit = remember(selected.storedSessionId, state.history, state.historyNamespace) {
        if(state.historyNamespace != null && state.historyNamespace == PhoneApp.from(appContext).bodyScope()) model.attachmentOpener(selected.storedSessionId)
        else { _, _ -> }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(selected.storedSessionId, state.history, state.historyNamespace, resumed) {
        if (resumed) {
            androidx.compose.runtime.withFrameNanos { }
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.filter { it.size > 0 && it.offset < listState.layoutInfo.viewportEndOffset && it.offset + it.size > listState.layoutInfo.viewportStartOffset }.map { it.key }.toSet() }
                .collect { keys -> model.contentDisplayed(selected.storedSessionId, state.history.filter { it.rowId in keys }, state.historyNamespace) }
        }
    }
    // The first text send asks, visibly, whether a reply that is not spoken may alert; the message is sent whatever the answer.
    val replyAlertPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.send() }
    ChatPane(
        sessionKey = selected.storedSessionId,
        history = state.history,
        hasOlder = state.hasOlder,
        draft = state.draft,
        attachments = state.attachments,
        failedSends = state.failedSends.filter { it.send.sessionId == selected.storedSessionId },
        pendingSends = state.pendingSends.filter { it.send.sessionId == selected.storedSessionId },
        activity = state.activity.filter { it.sessionId == selected.storedSessionId },
        onLoadOlder = model::loadOlder,
        olderLoading = state.olderLoading,
        olderError = state.olderError,
        onRetryOlder = model::loadOlder,
        onBack = onBack,
        listState = listState,
        follow = follow,
        onDraft = model::setDraft,
        onSend = {
            if (PhoneApp.from(appContext).askReplyAlertsOnce()) replyAlertPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else model.send()
        },
        onAttach = { picker.launch(arrayOf("*/*")) },
        onRemoveAttachment = model::removeAttachment,
        onRetry = model::retryFailedSend,
        onDismissFailed = model::dismissFailedSend,
        onPreviewAttachment = model::previewAttachment,
        onOpenLink = { url -> LinkOpener.open(appContext, url)?.let(model::reportStatus) },
        onOpenMessageAttachment = openMessageAttachment,
    )
    AttachmentViewerHost(state.viewer, model)
}

/** Where a rightward swipe may start, measured from the left edge of the chat list, which is the physical screen edge (the 16dp side margin is content padding). */
internal const val EDGE_SWIPE_REGION_DP = 40

/** How far right the finger must have moved on release for the swipe to go back. */
internal const val EDGE_SWIPE_COMMIT_DP = 72

internal val ChatFollowSaver: Saver<ChatFollow, Boolean> =
    Saver(save = { it.atLatest }, restore = { saved -> ChatFollow().also { it.atLatest = saved } })

/**
 * A rightward swipe that starts at the list's left edge and is released well to the right, mostly horizontally, goes back. It only
 * watches (after the content has had the gesture) and never consumes: a vertical scroll, a drag a child already took (selection,
 * a horizontally scrolled code block), a press held before moving, a second finger or a cancelled gesture never go back.
 */
@Composable
private fun Modifier.edgeSwipeBack(onBack: (() -> Unit)?): Modifier {
    val latest by rememberUpdatedState(onBack)
    if (onBack == null) return this
    return pointerInput(Unit) {
        val region = EDGE_SWIPE_REGION_DP.dp.toPx()
        val commit = EDGE_SWIPE_COMMIT_DP.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.position.x > region) return@awaitEachGesture
            val slop = viewConfiguration.touchSlop
            val patience = viewConfiguration.longPressTimeoutMillis
            var moving = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (event.changes.size > 1 || change.isConsumed) return@awaitEachGesture
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (!moving && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                    if (change.uptimeMillis - down.uptimeMillis > patience) return@awaitEachGesture
                    moving = true
                }
                if (change.changedToUp()) {
                    if (dx >= commit && dx >= 2 * kotlin.math.abs(dy)) latest?.invoke()
                    return@awaitEachGesture
                }
            }
        }
    }
}

/**
 * Whether the conversation follows its newest message. It does until the user scrolls away from
 * it (by touch, fling, accessibility or keyboard: any scroll this pane did not start itself), and
 * again once they scroll back, send, or start typing. Content arriving or the keyboard resizing
 * the list is not a scroll and never changes it.
 */
@Stable
internal class ChatFollow {
    var atLatest by mutableStateOf(true)

    /** Scrolls this pane started ([toLatest]); every other scroll is the user's. */
    private var own = 0

    suspend fun toLatest(list: LazyListState) {
        atLatest = true
        own += 1
        try {
            list.scrollToItem(0)
        } finally {
            own -= 1
        }
    }

    /** True while a scroll this pane did not start is in progress (touch, fling, accessibility or keyboard). */
    var userScrolling by mutableStateOf(false)
        private set

    suspend fun track(list: LazyListState) {
        // Reverse layout: "backward" is toward item 0, the newest message; none left means it is fully shown.
        // (Should one of this pane's own jumps be taken for the user's, it ends at the newest message anyway.)
        snapshotFlow { list.isScrollInProgress to list.canScrollBackward }.collect { (scrolling, canGoNewer) ->
            if (scrolling && own == 0) userScrolling = true
            if (userScrolling) atLatest = !canGoNewer
            if (!scrolling) userScrolling = false
        }
    }
}

/**
 * The open conversation: newest message at the bottom, right above the composer.
 *
 * The list is laid out from the bottom (reverse layout, item 0 = newest), so when the keyboard or
 * anything else shrinks it, the bottom (the newest message) stays put, and a reply that grows
 * keeps its latest line in view. While [ChatFollow.atLatest], a new or grown newest message is
 * scrolled to; while the user reads older messages their place is kept (items are keyed by row,
 * so loading older pages above does not move it either). Sending or focusing the composer
 * returns to the newest message. Each conversation ([sessionKey]) opens at its newest message.
 */
@Composable
internal fun ChatPane(
    sessionKey: String,
    history: List<HistoryMessage>,
    hasOlder: Boolean,
    draft: String,
    attachments: List<OutgoingAttachment>,
    onLoadOlder: () -> Unit,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    failedSends: List<FailedSend> = emptyList(),
    onRetry: (Long) -> Unit = {},
    onDismissFailed: (Long) -> Unit = {},
    onPreviewAttachment: (Int) -> Unit = {},
    onOpenLink: (String) -> Unit = {},
    onOpenAttachment: (AttachmentRef) -> Unit = {},
    onOpenMessageAttachment: ((HistoryMessage, AttachmentRef) -> Unit)? = null,
    pendingSends: List<PendingSend> = emptyList(),
    activity: List<ActivityBubble> = emptyList(),
    olderLoading: Boolean = false,
    olderError: Boolean = false,
    onRetryOlder: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    listState: LazyListState = remember(sessionKey) { LazyListState() },
    follow: ChatFollow = remember(sessionKey) { ChatFollow() },
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(listState) { follow.track(listState) }
    // Older history is asked for only when the reader's own scroll has brought the top of the loaded rows into view: not on open,
    // not on a remeasure, not while a read is running or after one failed.
    val olderAllowed by rememberUpdatedState(hasOlder && !olderLoading && !olderError)
    val askOlder by rememberUpdatedState(onLoadOlder)
    LaunchedEffect(listState, follow) {
        // One chance per touch gesture: a fling that carries on over a page that has just arrived does not pull the next one.
        var spent = false
        snapshotFlow { follow.userScrolling to listState.layoutInfo.visibleItemsInfo.any { it.key == "load_older" } }
            .distinctUntilChanged()
            .collect { (scrolling, atTop) ->
                if (!scrolling) {
                    spent = false
                } else if (atTop && !spent) {
                    spent = true
                    if (olderAllowed) askOlder()
                }
            }
    }
    val newest = history.lastOrNull()
    val anything = history.isNotEmpty() || pendingSends.isNotEmpty() || activity.isNotEmpty()
    LaunchedEffect(listState, history.size, newest?.rowId, newest?.text?.length, pendingSends.size, activity.size) {
        if (follow.atLatest && anything) follow.toLatest(listState)
    }
    val toLatest: () -> Unit = {
        follow.atLatest = true
        if (anything) scope.launch { follow.toLatest(listState) }
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("chat_list").edgeSwipeBack(onBack), state = listState, reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
            // Newest first: the transient receiving bubble, then the just-sent messages, then the conversation's own rows.
            items(activity.asReversed(), key = { "activity_${it.key}" }) { bubble ->
                Card(
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("activity_${bubble.key}"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text(bubble.label, Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(pendingSends.asReversed(), key = { "pending_${it.send.id}" }) { pending ->
                Card(
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("pending_send_${pending.send.id}"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        if (pending.send.text.isNotBlank()) SentMessageText(pending.send.text, onOpenLink)
                        pending.send.attachments.forEach { Text("📎 ${it.name}", style = MaterialTheme.typography.bodySmall) }
                        Text(if (pending.delivered) "Sent" else "Sending…", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            items(history.asReversed(), key = { it.rowId }) { message ->
                val mine = message.role == "user"
                Card(
                    Modifier.fillMaxWidth().testTag("message_${message.rowId}"),
                    colors = CardDefaults.cardColors(
                        containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    MessageBody(message.text, "message_text_${message.rowId}", onOpenLink,
                        { ref -> if (onOpenMessageAttachment != null) onOpenMessageAttachment(message, ref) else onOpenAttachment(ref) }, Modifier.padding(10.dp))
                }
            }
            if (hasOlder) item(key = "load_older") {
                if (olderLoading) {
                    Text("Loading older messages…", Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("load_older_loading"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                } else if (olderError) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Couldn't load older messages", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetryOlder, modifier = Modifier.heightIn(min = 48.dp).testTag("load_older_retry")) { Text("Retry") }
                    }
                } else {
                    TextButton(onClick = onLoadOlder, modifier = Modifier.testTag("load_older")) { Text("Load older") }
                }
            }
        }
        failedSends.forEach { failed ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("failed_send_${failed.send.id}"), verticalAlignment = Alignment.CenterVertically) {
                Text("Not sent: ${failed.reason}", Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onRetry(failed.send.id) }, modifier = Modifier.heightIn(min = 48.dp).testTag("retry_${failed.send.id}")) { Text("Retry") }
                TextButton(onClick = { onDismissFailed(failed.send.id) }, modifier = Modifier.heightIn(min = 48.dp).testTag("dismiss_${failed.send.id}")) { Text("Dismiss") }
            }
        }
        attachments.forEachIndexed { index, attachment ->
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onPreviewAttachment(index) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("attachment_$index")) {
                    Text("📎 ${attachment.name} (${attachment.bytes.size / 1024} KiB)", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { onRemoveAttachment(index) }, modifier = Modifier.heightIn(min = 48.dp).testTag("remove_attachment_$index")) { Text("Remove") }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("composer_row"), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onAttach, modifier = Modifier.size(48.dp).testTag("attach")) {
                Icon(ComposerIcons.Plus, contentDescription = "Attach file")
            }
            OutlinedTextField(draft, onDraft, Modifier.weight(1f).testTag("composer").onFocusChanged { if (it.isFocused) toLatest() },
                placeholder = { Text("Message") }, minLines = 1, maxLines = 6)
            FilledIconButton(onClick = { toLatest(); onSend() }, enabled = draft.isNotBlank() || attachments.isNotEmpty(),
                modifier = Modifier.size(48.dp).testTag("send")) {
                Icon(ComposerIcons.ArrowUp, contentDescription = "Send message")
            }
        }
    }
}

@Composable
internal fun SettingsTab(state: PhoneUiState, model: PhoneViewModel, recognizerAvailable: Boolean) {
    var patterns by remember(state.watch.wakePatterns) { mutableStateOf(state.watch.wakePatterns) }
    var silence by remember(state.watch.vadSilenceSeconds) { mutableStateOf(state.watch.vadSilenceSeconds.toFloat()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Hermes dashboard", style = MaterialTheme.typography.titleSmall)
        ConnectionFields(state, model, primaryAction = "Sign in")

        HorizontalDivider()
        Text("Appearance", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ThemeMode.DARK to "Dark", ThemeMode.LIGHT to "Light", ThemeMode.SYSTEM to "System").forEach { (mode, label) ->
                FilterChip(selected = state.themeMode == mode, onClick = { model.setThemeMode(mode) }, label = { Text(label) })
            }
        }
        Text("Text size", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { model.setFontScalePercent(state.fontScalePercent - PhoneDisplaySettings.STEP) },
                enabled = state.fontScalePercent > PhoneDisplaySettings.MIN,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("font_scale_down").semantics { contentDescription = "Smaller text" },
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) { Text("−") }
            Text("${state.fontScalePercent}%", Modifier.widthIn(min = 56.dp).testTag("font_scale_value"), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = { model.setFontScalePercent(state.fontScalePercent + PhoneDisplaySettings.STEP) },
                enabled = state.fontScalePercent < PhoneDisplaySettings.MAX,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("font_scale_up").semantics { contentDescription = "Larger text" },
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) { Text("+") }
            TextButton(
                onClick = model::resetFontScale,
                enabled = state.fontScalePercent != PhoneDisplaySettings.DEFAULT,
                modifier = Modifier.heightIn(min = 48.dp).testTag("font_scale_reset"),
            ) { Text("Reset") }
        }

        HorizontalDivider()
        SectionTitle("Spoken replies", SettingsHelp.spokenReplies())
        SwitchRow("Play first response", state.playFirst, onChange = model::setPlayFirst)
        SwitchRow("Play middle responses", state.playMiddle, onChange = model::setPlayMiddle)
        SwitchRow("Speak later replies", state.speakLater,
            tag = "speak_later_replies", help = SettingsHelp.laterReplies(state.laterReplyWindowMinutes), onChange = model::setSpeakLaterReplies)
        LaterReplyWindowRow(state, model)
        // Android 12+ needs the Bluetooth permission before a Bluetooth headset can be found or its microphone used; the answer only
        // decides the Bluetooth part (a denied permission is told when a recording falls back to the Phone microphone).
        val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        SwitchRow("Use headset", state.useHeadset, tag = "use_headset", help = SettingsHelp.useHeadset(), onChange = { on ->
            model.setUseHeadset(on)
            if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        })
        state.headsetControl?.let {
            Text(compactHeadsetControlLine(it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("headset_control_status"))
        }
        val requestBackgroundAnswers = rememberBackgroundRelayRequest(model)
        BackgroundAnswersStatus(state, tag = "headset_background", onlyWhenNeeded = false, onTurnOn = requestBackgroundAnswers)

        HorizontalDivider()
        SectionTitle("Voice routing", SettingsHelp.routing())
        SwitchRow("Route voice requests automatically", state.routingEnabled, tag = "routing_enabled", onChange = model::setRoutingEnabled)
        SwitchRow("Open the routed conversation on this phone", state.autoNavigate, enabled = state.routingEnabled,
            tag = "auto_navigate", help = SettingsHelp.phoneNavigation(), onChange = model::setAutoNavigate)
        SwitchRow("Open the routed conversation on Watch", state.watch.watchAutoNavigateToRouted, enabled = state.routingEnabled,
            tag = "watch_auto_navigate", help = SettingsHelp.watchNavigation(), onChange = model::setWatchAutoNavigate)

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Watch", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            InfoHelpButton(SettingsHelp.watch())
            TextButton(onClick = model::refreshWatchStatus) { Text("Check") }
        }
        Text(
            when (state.watchReachable) {
                true -> "Watch app connected"
                false -> "Watch app not reachable"
                null -> "Checking the Watch…"
            },
            color = if (state.watchReachable == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.bodyMedium,
        )
        SwitchRow("Watch haptics", state.watch.hapticsEnabled) { model.updateWatch(state.watch.copy(hapticsEnabled = it)) }

        HorizontalDivider()
        DiagnosticsRow()

        HorizontalDivider()
        SectionTitle("Background", SettingsHelp.backgroundRelay())
        // The first switch-on asks whether the app may show its notification (Android 13+); the relay starts whatever the answer.
        // Whether the relay's notification can really be seen now: the permission, the app's notifications and its channel.
        val notifications = PhoneApp.from(context).relayNotificationCapability().shown
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.setBackgroundRelay(true) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Keep relaying for the Watch when this app is closed", Modifier.weight(1f))
            Switch(checked = state.relay.running, modifier = Modifier.testTag("background_relay"), onCheckedChange = { on ->
                if (on && !notifications && model.askNotificationsOnce()) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    model.setBackgroundRelay(on)
                }
            })
        }
        Text(BackgroundText.phoneStatus(state.relay, notifications), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("background_relay_status"),
            color = if (state.relay.wanted && !state.relay.running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)

        HorizontalDivider()
        SectionTitle("Wake phrase", SettingsHelp.wakePhrase())
        Text("Listen on", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WakeLocation.values().forEach { location ->
                FilterChip(selected = state.watch.wakeLocation == location, onClick = { model.setWakeLocation(location) },
                    label = { Text(location.label) }, modifier = Modifier.testTag("wake_${location.name.lowercase()}"))
            }
        }
        // "Listen on" selects the device in the foreground and the background; a standby switch is an extra condition with the app hidden (see PhoneBackgroundWakeRow).
        val phoneListens = state.watch.listensIn(VoiceOrigin.PHONE, WakeGate.FOREGROUND)
        val watchListens = state.watch.listensIn(VoiceOrigin.WATCH, WakeGate.FOREGROUND)
        Text(
            when {
                !phoneListens -> "Phone: not listening (not selected in Listen on)" + if (state.watch.phoneBackgroundWakeEnabled) "; its background standby below has no effect" else ""
                !recognizerAvailable -> "Phone: unavailable, this phone has no speech recognizer"
                !micGranted -> "Phone: needs the microphone permission (tap Talk once to grant it)"
                !state.signedIn -> "Phone: sign in to Hermes first"
                state.handsFree == HandsFree.LISTENING -> "Phone: listening now"
                else -> "Phone: listens for ${WakeContract.WINDOW_MS / 1000} s each time you open this app, screen on"
            },
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("phone_wake_status"),
            color = if (phoneListens && (!recognizerAvailable || !micGranted)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
        )
        Text(
            when {
                !watchListens -> "Watch: not listening (not selected in Listen on)" + if (state.watch.watchBackgroundWakeEnabled) "; its background standby below has no effect" else ""
                state.watchReachable == false -> "Watch: app not reachable; it applies this when it syncs"
                else -> "Watch: listens for ${WakeContract.WINDOW_MS / 1000} s each time the Watch app opens; the Watch shows if it has no recognizer"
            },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary,
        )
        PhoneBackgroundWakeRow(state, model)
        OutlinedTextField(patterns, { patterns = it }, label = { Text("Wake phrases for both devices (space-separated, * wildcard)") },
            modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { model.updateWatch(state.watch.copy(wakePatterns = patterns)) }) { Text("Save wake phrases") }
            InfoHelpButton(SettingsHelp.wakePatterns())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Send after ${VadSilence.label(silence.toDouble())} of silence", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).testTag("vad_silence_label"))
            InfoHelpButton(SettingsHelp.vad())
        }
        Slider(
            value = silence,
            onValueChange = { silence = ((it / VadSilence.STEP_SECONDS).roundToInt() * VadSilence.STEP_SECONDS).toFloat() },
            onValueChangeFinished = { model.setVadSilence(silence.toDouble()) },
            valueRange = VadSilence.MIN_SECONDS.toFloat()..VadSilence.MAX_SECONDS.toFloat(),
            steps = VadSilence.choices.size - 2,
            modifier = Modifier.fillMaxWidth().testTag("vad_silence"),
        )
    }
}

/**
 * "Share diagnostics": a user-started, private export. The dialog says what is and is not included and whether the Watch can
 * be reached now BEFORE anything is prepared; only Share builds the file, and the Android share sheet picks the recipient.
 */
@Composable
private fun DiagnosticsRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val diagnostics = PhoneApp.from(context).diagnostics
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    var watchReachable by remember { mutableStateOf<Boolean?>(null) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var cleared by remember { mutableStateOf<String?>(null) }
    SectionTitle("Share diagnostics", SettingsHelp.diagnostics())
    Text("Prepares a private file of recent technical events and opens the share sheet. Nothing is sent unless you choose where.",
        style = MaterialTheme.typography.bodyMedium)
    OutlinedButton(
        onClick = { message = null; watchReachable = null; asking = true },
        enabled = !working,
        modifier = Modifier.fillMaxWidth().testTag("share_diagnostics"),
    ) { Text(if (working) "Preparing…" else "Share diagnostics…") }
    TextButton(
        onClick = {
            cleared = null
            scope.launch { cleared = if (diagnostics.clearLocalLog()) "This phone's log was cleared." else "Couldn't clear this phone's log." }
        },
        enabled = !working,
        modifier = Modifier.fillMaxWidth().testTag("clear_phone_diagnostics"),
    ) { Text("Clear this phone's log") }
    cleared?.let { Text(it, modifier = Modifier.testTag("diagnostics_cleared"), style = MaterialTheme.typography.bodyMedium) }
    message?.let { Text(it, modifier = Modifier.testTag("diagnostics_message"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    if (asking) {
        LaunchedEffect(Unit) { watchReachable = diagnostics.watchReachable() }
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Share diagnostics?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).testTag("diagnostics_consent"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Included:", style = MaterialTheme.typography.titleSmall)
                    com.rumi.hermesvoice.core.diag.DiagReport.CONTENT_LINES.forEach { Text("• $it") }
                    Text("Not included:", style = MaterialTheme.typography.titleSmall)
                    com.rumi.hermesvoice.core.diag.DiagReport.EXCLUDED_LINES.forEach { Text("• $it") }
                    Text(
                        when (watchReachable) {
                            true -> "Watch: reachable now, its events will be included."
                            false -> "Watch: not reachable now, only this phone's events will be shared."
                            null -> "Watch: checking…"
                        },
                        modifier = Modifier.testTag("diagnostics_watch_status"),
                    )
                    Text("You choose the recipient in the share sheet. This doesn't interrupt a recording or a reply.")
                }
            },
            confirmButton = {
                TextButton(modifier = Modifier.testTag("diagnostics_confirm"), onClick = {
                    asking = false
                    working = true
                    scope.launch {
                        val result = try { diagnostics.export() } finally { working = false }
                        when (result) {
                            is com.rumi.hermesvoice.core.diag.DiagExportResult.Ready -> {
                                val chooser = diagnostics.shareIntent(context, result.file)
                                message = if (chooser == null) "Couldn't prepare the file to share." else runCatching {
                                    context.startActivity(chooser)
                                    null
                                }.getOrElse { "No app is available to share the file." }
                            }
                            com.rumi.hermesvoice.core.diag.DiagExportResult.Busy -> message = "Diagnostics are already being prepared."
                            is com.rumi.hermesvoice.core.diag.DiagExportResult.Failed -> message = "Couldn't prepare the diagnostics file."
                        }
                    }
                }) { Text("Share") }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
}

/**
 * The Phone's opt-in background listening: off by default, switched on only here (in the visible
 * app), with what it really does now and its limits.
 */
@Composable
private fun PhoneBackgroundWakeRow(state: PhoneUiState, model: PhoneViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val wake = state.phoneWake
    val notifications = PhoneApp.from(context).phoneWake.notificationCapability().shown
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.setPhoneBackgroundWake(true)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Background standby", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        InfoHelpButton(SettingsHelp.standby())
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Phone background wake standby", Modifier.weight(1f))
        Switch(checked = state.watch.phoneBackgroundWakeEnabled, modifier = Modifier.testTag("phone_background_wake"), onCheckedChange = { on ->
            if (on && !notifications && model.askNotificationsOnce()) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                model.setPhoneBackgroundWake(on)
            }
        })
    }
    if (wake != null) {
        Text(BackgroundText.phoneWakeStatus(wake, notifications), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("phone_background_wake_status"),
            color = if (wake.session.wanted && !wake.session.microphone) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
    }
    ScreenOffRow(
        label = "Phone background wake recognition with screen off",
        checked = state.watch.phoneBackgroundWakeScreenOffEnabled,
        masterOn = state.watch.phoneBackgroundWakeEnabled,
        device = "Phone",
        tag = "phone_background_wake_screen_off",
        onChange = { on -> model.setPhoneBackgroundWakeScreenOff(on) },
    )
    ChargingWakeRow("Phone", state.watch.phoneWakeWhileCharging, state.watch.phoneBackgroundWakeEnabled,
        "phone_wake_while_charging", model::setPhoneWakeWhileCharging)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Watch background wake standby", Modifier.weight(1f))
        Switch(checked = state.watch.watchBackgroundWakeEnabled, modifier = Modifier.testTag("watch_background_wake"),
            onCheckedChange = { on -> model.setWatchBackgroundWake(on) })
    }
    Text(
        when {
            !state.watch.watchBackgroundWakeEnabled -> "Watch standby: off"
            state.watchReachable == false -> "Watch standby: requested; the Watch is not reachable, it applies this when it syncs"
            else -> "Watch standby: requested. It starts when the Watch app is next opened (Android lets a microphone start only " +
                "from a visible app); the Watch shows what it is really doing."
        },
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("watch_background_wake_status"),
        color = MaterialTheme.colorScheme.tertiary,
    )
    ScreenOffRow(
        label = "Watch background wake recognition with screen off",
        checked = state.watch.watchBackgroundWakeScreenOffEnabled,
        masterOn = state.watch.watchBackgroundWakeEnabled,
        device = "Watch",
        tag = "watch_background_wake_screen_off",
        onChange = { on -> model.setWatchBackgroundWakeScreenOff(on) },
    )
    ChargingWakeRow("Watch", state.watch.watchWakeWhileCharging, state.watch.watchBackgroundWakeEnabled,
        "watch_wake_while_charging", model::setWatchWakeWhileCharging)
}

/**
 * A device's "background wake recognition with screen off" preference: subordinate to that device's standby switch, off by
 * default, about BACKGROUND recognition only (never the app open on screen). Shown disabled, value kept, while the standby is off.
 */
@Composable
private fun ChargingWakeRow(device: String, checked: Boolean, masterOn: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    SwitchRow("$device · Always listen while charging", checked, tag = tag, help = SettingsHelp.chargingWake(device), onChange = onChange)
    Text(when {
        !checked -> "$device charging listening: off"
        !masterOn -> "$device charging listening: requested; standby is off"
        else -> "$device charging listening: requested; actual listening depends on local charge and system admission"
    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(tag + "_note"))
}

@Composable
private fun ScreenOffRow(label: String, checked: Boolean, masterOn: Boolean, device: String, tag: String, onChange: (Boolean) -> Unit) {
    SwitchRow(label, checked, enabled = masterOn, tag = tag, onChange = onChange)
    Text(
        when {
            !masterOn -> "$device standby is off: no effect now. Your choice (${if (checked) "on" else "off"}) is kept."
            checked -> "$device recognition continues, also with its screen off (requested only)."
            else -> "$device listens for the wake phrase for 5 seconds after its own screen turns on."
        },
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(tag + "_note"),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, tag: String? = null, help: HelpTopic? = null,
                      onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        if (help != null) InfoHelpButton(help)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
    }
}

@Composable
private fun SectionTitle(title: String, help: HelpTopic) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        InfoHelpButton(help)
    }
}

/**
 * A small ⓘ button that opens [topic]'s explanation in a dismissible, scrollable dialog. Opening or closing it changes nothing
 * else: no setting, no state of the app. The button's spoken name names the option it explains.
 */
@Composable
internal fun InfoHelpButton(topic: HelpTopic) {
    var open by rememberSaveable(topic.id) { mutableStateOf(false) }
    IconButton(onClick = { open = true },
        modifier = Modifier.testTag(topic.buttonTag).sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = topic.contentDescription }) {
        Text("\u24D8", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clearAndSetSemantics { })
    }
    if (open) HelpDialog(topic) { open = false }
}

@Composable
private fun HelpDialog(topic: HelpTopic, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(topic.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag(topic.dialogTag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                topic.paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("help_close")) { Text("Close") } },
    )
}

/**
 * How long a delivered voice request keeps being followed for later replies: a whole number in minutes, hours or days (1 minute
 * to 3 days). Typing changes only a draft; the value is checked and saved on Done, when the field loses focus or a
 * unit is chosen, and a refused entry is explained without being saved. The opt-in switch above is separate.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LaterReplyWindowRow(state: PhoneUiState, model: PhoneViewModel) {
    val stored = state.laterReplyWindowMinutes
    var text by remember(stored) { mutableStateOf(LaterReplyWindow.displayValue(stored).toString()) }
    var unit by remember(stored) { mutableStateOf(LaterReplyWindow.displayUnit(stored)) }
    var wasFocused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    fun commit() {
        if (text != LaterReplyWindow.displayValue(stored).toString() || unit != LaterReplyWindow.displayUnit(stored)) {
            model.commitLaterReplyWindow(text, unit)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { value ->
                    text = value.filter { it.isDigit() }.take(7)
                    if (state.laterReplyWindowError != null) model.clearLaterReplyWindowError()
                },
                label = { Text("Follow for") },
                singleLine = true,
                isError = state.laterReplyWindowError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit(); focus.clearFocus() }),
                modifier = Modifier.widthIn(max = 120.dp).testTag("later_reply_window_value")
                    .onFocusChanged { focusState ->
                        if (wasFocused && !focusState.isFocused) commit()
                        wasFocused = focusState.isFocused
                    },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LaterReplyWindow.Unit.values().forEach { choice ->
                    FilterChip(selected = unit == choice, onClick = { unit = choice; model.commitLaterReplyWindow(text, choice) },
                        label = { Text(choice.label) }, modifier = Modifier.testTag("later_reply_unit_${choice.name.lowercase()}"))
                }
            }
        }
        state.laterReplyWindowError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("later_reply_window_error"))
        }
    }
}
