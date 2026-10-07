package com.rumi.hermesvoice.phone

import android.Manifest
import com.rumi.hermesvoice.core.notify.ReplyAlertContent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.media.AudioManager
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
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
                PhoneScreen(model, recognizerAvailable, onTalk = ::onTalk)
            }
        }
        // Phone-owned settings: a mode that excludes the Phone stops listening and any hands-free capture at once.
        lifecycleScope.launch { model.state.map { it.watch }.distinctUntilChanged().collect { phoneWake.wake.onSettings(it) } }
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
    // A routed turn's conversation was opened for the user ("Open the routed conversation"): show it,
    // once per request (not again when the screen is recreated).
    var shownOpenRequest by rememberSaveable { mutableStateOf(state.chatOpenRequest) }
    LaunchedEffect(state.chatOpenRequest) {
        if (state.chatOpenRequest != shownOpenRequest) {
            shownOpenRequest = state.chatOpenRequest
            tab = Tab.CHAT
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
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
    PhoneChrome(
        title = state.selected?.title?.takeIf { tab == Tab.CHAT } ?: "Hermes Voice",
        tab = tab,
        onTab = { item ->
            if (item != tab) model.onUserNavigated()
            tab = item
        },
        statusLines = listOf(state.status, state.voiceStatus),
        talkBar = if (!state.signedIn) null else ({
            TalkBar(state, onStopPending = model::stopPending) {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) onTalk() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }),
    ) {
        when (tab) {
            Tab.CONVERSATIONS -> ConversationsTab(state, model, onOpen = {
                model.onUserNavigated()
                model.open(it.owned)
                tab = Tab.CHAT
            })
            Tab.CHAT -> ChatTab(state, model)
            Tab.SETTINGS -> SettingsTab(state, model, recognizerAvailable)
        }
    }
}

/**
 * The Phone's frame: title bar, content, and at the bottom the Talk bar (when signed in) above the
 * tabs. The keyboard lifts the content (e.g. the chat composer) by exactly what the bottom bar
 * doesn't already cover: the bar's height is consumed as insets, so nothing is padded twice.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PhoneChrome(
    title: String,
    tab: Tab,
    onTab: (Tab) -> Unit,
    statusLines: List<String>,
    talkBar: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
        bottomBar = {
            // The Talk bar sits above the navigation bar, never over the chat composer or the tabs.
            Column(Modifier.testTag("bottom_bar")) {
                talkBar?.invoke()
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Tab.values().forEach { item ->
                        NavigationBarItem(selected = tab == item, onClick = { onTab(item) }, icon = {},
                            label = { Text(item.label, fontWeight = if (tab == item) FontWeight.SemiBold else FontWeight.Normal) },
                            modifier = Modifier.testTag("tab_${item.name.lowercase()}"))
                    }
                }
            }
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

/** Full-width push-to-talk, centered within the horizontal safe area, with where replies will play. */
@Composable
internal fun TalkBar(state: PhoneUiState, onStopPending: (String) -> Unit = {}, onTalk: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                when (state.playbackDevice) {
                    VoiceOrigin.WATCH -> "Voice replies play on the Watch (last voice request)"
                    VoiceOrigin.PHONE -> "Voice replies play on this phone (last voice request)"
                    null -> "Voice replies play on the device you last talked to"
                },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 6.dp),
            )
            PendingTurnsList(state.pending, onStopPending)
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
            val colors = if (state.recording || capturing) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError)
            } else {
                ButtonDefaults.buttonColors()
            }
            Button(onClick = onTalk, colors = colors, shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().height(60.dp).testTag("talk")) {
                Text(if (state.recording) "Stop & send" else if (capturing) "Send now" else "Talk", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
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

internal fun pendingLabel(turn: PendingTurn): String {
    val to = turn.alias?.let { " to $it" }.orEmpty()
    val from = if (turn.origin == VoiceOrigin.WATCH) "Watch request" else "Request"
    return when (turn.phase) {
        PendingPhase.TRANSMITTING -> "$from$to: sending…"
        PendingPhase.QUEUED -> "$from$to: queued behind an earlier request to the same conversation"
        PendingPhase.AWAITING -> "$from$to: waiting for the reply (no limit while it keeps working)"
        PendingPhase.SPEAKING -> "$from$to: speaking the reply"
    }
}

@Composable
private fun ConversationsTab(state: PhoneUiState, model: PhoneViewModel, onOpen: (AppConversation) -> Unit) {
    if (!state.signedIn) {
        SignInScreen(state, model)
        return
    }
    var title by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !state.showArchived, onClick = { model.setShowArchived(false) }, label = { Text("Active") })
                FilterChip(selected = state.showArchived, onClick = { model.setShowArchived(true) }, label = { Text("Archived") })
                TextButton(onClick = model::refresh) { Text("Refresh") }
            }
        }
        items(state.conversations, key = { it.owned.storedSessionId }) { conversation ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(12.dp)) {
                    Text(conversation.stored.title.ifBlank { conversation.owned.title }, style = MaterialTheme.typography.titleMedium)
                    Text("Voice alias: ${conversation.owned.alias}" +
                        conversation.owned.description.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (conversation.stored.preview.isNotBlank()) Text(conversation.stored.preview.take(120), maxLines = 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!state.showArchived) TextButton(onClick = { onOpen(conversation) }) { Text("Open") }
                        TextButton(onClick = { model.setArchived(conversation.owned, !state.showArchived) }) {
                            Text(if (state.showArchived) "Unarchive" else "Archive")
                        }
                    }
                }
            }
        }
        if (state.conversations.isEmpty()) item {
            Text(if (state.showArchived) "No archived conversations." else "No conversations yet. Create one below, or just talk: one is created when nothing fits.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!state.showArchived) item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HorizontalDivider()
                Text("New conversation", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(alias, { alias = it }, label = { Text("Voice alias (e.g. work)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("What goes here (helps voice routing)") },
                    modifier = Modifier.fillMaxWidth())
                Button(onClick = { model.createConversation(title, alias, description); title = ""; alias = ""; description = "" },
                    enabled = alias.isNotBlank()) { Text("Create") }
                Spacer(Modifier.height(12.dp))
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
private fun ChatTab(state: PhoneUiState, model: PhoneViewModel) {
    val selected = state.selected
    if (selected == null) {
        Text("Open a conversation first.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::addAttachment) }
    val appContext = androidx.compose.ui.platform.LocalContext.current
    // The first text send asks, visibly, whether a reply that is not spoken may alert; the message is sent whatever the answer.
    val replyAlertPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.send() }
    ChatPane(
        sessionKey = selected.storedSessionId,
        history = state.history,
        hasOlder = state.hasOlder,
        draft = state.draft,
        attachments = state.attachments,
        sending = state.sending,
        onLoadOlder = model::loadOlder,
        onDraft = model::setDraft,
        onSend = {
            if (PhoneApp.from(appContext).askReplyAlertsOnce()) replyAlertPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else model.send()
        },
        onAttach = { picker.launch(arrayOf("*/*")) },
        onRemoveAttachment = model::removeAttachment,
    )
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

    suspend fun track(list: LazyListState) {
        var userScrolling = false
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
    sending: Boolean,
    onLoadOlder: () -> Unit,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
) {
    val listState = remember(sessionKey) { LazyListState() }
    val follow = remember(sessionKey) { ChatFollow() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(listState) { follow.track(listState) }
    val newest = history.lastOrNull()
    LaunchedEffect(listState, history.size, newest?.rowId, newest?.text?.length) {
        if (follow.atLatest && history.isNotEmpty()) follow.toLatest(listState)
    }
    val toLatest: () -> Unit = {
        follow.atLatest = true
        if (history.isNotEmpty()) scope.launch { follow.toLatest(listState) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("chat_list"), state = listState, reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
            items(history.asReversed(), key = { it.rowId }) { message ->
                val mine = message.role == "user"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    Card(
                        Modifier.fillMaxWidth(0.85f).testTag("message_${message.rowId}"),
                        colors = CardDefaults.cardColors(
                            containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Text(message.text, Modifier.padding(10.dp))
                    }
                }
            }
            if (hasOlder) item(key = "load_older") {
                TextButton(onClick = onLoadOlder, modifier = Modifier.testTag("load_older")) { Text("Load older") }
            }
        }
        attachments.forEachIndexed { index, attachment ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📎 ${attachment.name} (${attachment.bytes.size / 1024} KiB)", Modifier.weight(1f))
                TextButton(onClick = { onRemoveAttachment(index) }) { Text("Remove") }
            }
        }
        Row(Modifier.padding(vertical = 8.dp).testTag("composer_row"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAttach, enabled = !sending) { Text("Attach") }
            OutlinedTextField(draft, onDraft, Modifier.weight(1f).testTag("composer").onFocusChanged { if (it.isFocused) toLatest() },
                placeholder = { Text("Message") })
            Button(onClick = { toLatest(); onSend() }, enabled = !sending && (draft.isNotBlank() || attachments.isNotEmpty()),
                modifier = Modifier.testTag("send")) {
                Text(if (sending) "…" else "Send")
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

        HorizontalDivider()
        SectionTitle("Spoken replies", SettingsHelp.spokenReplies())
        SwitchRow("Play first response", state.playFirst, onChange = model::setPlayFirst)
        SwitchRow("Play middle responses", state.playMiddle, onChange = model::setPlayMiddle)
        SwitchRow("Speak later replies (${LaterReplyWindow.describe(state.laterReplyWindowMinutes)})", state.speakLater,
            tag = "speak_later_replies", help = SettingsHelp.laterReplies(state.laterReplyWindowMinutes), onChange = model::setSpeakLaterReplies)
        LaterReplyWindowRow(state, model)

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
    SectionTitle("Share diagnostics", SettingsHelp.diagnostics())
    Text("Prepares a private file of recent technical events and opens the share sheet. Nothing is sent unless you choose where.",
        style = MaterialTheme.typography.bodyMedium)
    OutlinedButton(
        onClick = { message = null; watchReachable = null; asking = true },
        enabled = !working,
        modifier = Modifier.fillMaxWidth().testTag("share_diagnostics"),
    ) { Text(if (working) "Preparing…" else "Share diagnostics…") }
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
}

/**
 * A device's "background wake recognition with screen off" preference: subordinate to that device's standby switch, off by
 * default, about BACKGROUND recognition only (never the app open on screen). Shown disabled, value kept, while the standby is off.
 */
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
        modifier = Modifier.testTag(topic.buttonTag).semantics { contentDescription = topic.contentDescription }) {
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
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("help_close")) { Text("Close") } },
    )
}

/**
 * How long a delivered voice request keeps being followed for later replies: a whole number in minutes, hours or days (1 minute
 * to 3 days) or a preset. Typing changes only a draft; the value is checked and saved on Done, when the field loses focus or a
 * unit/preset is chosen, and a refused entry is explained without being saved. The opt-in switch above is separate.
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LaterReplyWindow.presets.forEach { minutes ->
                FilterChip(selected = stored == minutes, onClick = {
                    text = LaterReplyWindow.displayValue(minutes).toString()
                    unit = LaterReplyWindow.displayUnit(minutes)
                    model.setLaterReplyWindowMinutes(minutes)
                }, label = { Text(LaterReplyWindow.describe(minutes)) }, modifier = Modifier.testTag("later_reply_preset_$minutes"))
            }
        }
        state.laterReplyWindowError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("later_reply_window_error"))
        }
    }
}
