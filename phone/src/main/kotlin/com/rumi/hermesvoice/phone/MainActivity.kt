package com.rumi.hermesvoice.phone

import android.Manifest
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.audio.QaAudio
import com.rumi.hermesvoice.core.audio.QaLaunchGuard
import com.rumi.hermesvoice.core.sessions.AppConversation
import com.rumi.hermesvoice.core.settings.ThemeMode
import com.rumi.hermesvoice.core.settings.VadSilence
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.wake.WakeArmInputs
import com.rumi.hermesvoice.core.wake.WakeBlock
import com.rumi.hermesvoice.core.wake.WakeContract
import com.rumi.hermesvoice.core.wake.WakeDevicePort

class MainActivity : ComponentActivity() {
    private val model: PhoneViewModel by viewModels()
    private lateinit var phoneWake: PhoneWakeController
    private var qaWakeHandoffPending = false
    private var recognizerAvailable by mutableStateOf(false)
    private val handoffRunnable: Runnable = Runnable {
        if (!phoneWake.wake.onHandoffDue(captureIdle = model.voiceIdle())) Log.i(TAG, "phone wake handoff dropped gen=${phoneWake.wake.generation}")
    }

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
        override fun startRequestCapture(silenceMs: Long): Boolean = hasMic() && model.startHandsFree(silenceMs)
        override fun cancelRequestCapture(reason: String) = model.cancelHandsFree(reason)
        override fun sendRecognized(request: String) = model.sendRecognizedRequest(request)

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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars(dark = PhoneApp.from(this).settings.themeMode.isDark(systemDark = isSystemNight()))
        phoneWake = PhoneWakeController(this, wakePort, model.state.value.watch)
        lifecycle.addObserver(phoneWake)
        recognizerAvailable = phoneWake.recognizerAvailable()
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val dark = state.themeMode.isDark(isSystemInDarkTheme())
            DisposableEffect(dark) {
                applySystemBars(dark)
                onDispose {}
            }
            HermesVoiceTheme(dark) {
                // The wake window releases the microphone before push-to-talk opens it.
                PhoneScreen(model, recognizerAvailable, beforeTalk = { if (!model.handsFreeCapturing()) phoneWake.wake.onBusy() })
            }
        }
        // Phone-owned settings: a mode that excludes the Phone stops listening and any hands-free capture at once.
        lifecycleScope.launch { model.state.map { it.watch }.distinctUntilChanged().collect { phoneWake.wake.onSettings(it) } }
        // Push-to-talk or a turn in flight closes the window; idle again may re-arm (once per visibility generation).
        lifecycleScope.launch {
            model.state.map { it.recording || it.voiceBusy }.distinctUntilChanged().collect { busy ->
                if (busy) phoneWake.wake.onBusy() else if (model.voiceIdle()) phoneWake.wake.onIdle()
            }
        }
        if (model.state.value.signedIn) model.refresh()
        handleQaIntent(intent, restored = savedInstanceState != null)
    }

    override fun onResume() {
        super.onResume()
        recognizerAvailable = phoneWake.recognizerAvailable()
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
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleQaIntent(intent, restored = false)
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Debuggable builds only, once per fresh launch intent (see [QaLaunchGuard]):
     * `--es hv_qa_wav <name>.wav` submits files/qa/<name>.wav as a Phone voice request;
     * `--es hv_qa_wake_handoff second_utterance` runs the phrase-only wake handoff exactly as a
     * recognizer match would (fixture: it proves the recorder path, not recognition);
     * `--es hv_qa_wake_location <OFF|WATCH|PHONE|BOTH>` and `--es hv_qa_vad_silence <seconds>` save
     * and publish the settings through the same path as the Settings screen (invalid values are refused).
     */
    private fun handleQaIntent(intent: Intent?, restored: Boolean) {
        intent ?: return
        val name = intent.getStringExtra(QaAudio.EXTRA)
        val handoff = intent.getStringExtra(QA_WAKE_HANDOFF)
        val location = intent.getStringExtra(QA_WAKE_LOCATION)
        val silence = intent.getStringExtra(QA_VAD_SILENCE)
        if (name == null && handoff == null && location == null && silence == null) return
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        val handled = intent.getBooleanExtra(QA_HANDLED, false)
        listOf(QaAudio.EXTRA, QA_WAKE_HANDOFF, QA_WAKE_LOCATION, QA_VAD_SILENCE).forEach(intent::removeExtra)
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

private enum class Tab(val label: String) { CONVERSATIONS("Conversations"), CHAT("Chat"), SETTINGS("Settings") }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PhoneScreen(model: PhoneViewModel, recognizerAvailable: Boolean, beforeTalk: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.CONVERSATIONS) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = LocalView.current
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.toggleRecording()
    }
    // Hands-free recording start ("speak now") and end: a short system haptic, no sound (a tone would be recorded).
    LaunchedEffect(state.hapticTick) {
        if (state.hapticTick > 0) {
            view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(state.selected?.title?.takeIf { tab == Tab.CHAT } ?: "Hermes Voice", maxLines = 1) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
        bottomBar = {
            // The Talk bar sits above the navigation bar, never over the chat composer or the tabs.
            Column {
                if (state.signedIn) {
                    TalkBar(state) {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        beforeTalk()
                        if (granted) model.toggleRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Tab.values().forEach { item ->
                        NavigationBarItem(selected = tab == item, onClick = { tab = item }, icon = {},
                            label = { Text(item.label, fontWeight = if (tab == item) FontWeight.SemiBold else FontWeight.Normal) })
                    }
                }
            }
        },
    ) { padding ->
        // The keyboard lifts the content (e.g. the chat composer) by whatever the bottom bar doesn't already cover.
        Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            listOf(state.status, state.voiceStatus).filter { it.isNotBlank() }.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            when (tab) {
                Tab.CONVERSATIONS -> ConversationsTab(state, model, onOpen = { model.open(it.owned); tab = Tab.CHAT })
                Tab.CHAT -> ChatTab(state, model)
                Tab.SETTINGS -> SettingsTab(state, model, recognizerAvailable)
            }
        }
    }
}

/** Full-width push-to-talk, centered within the horizontal safe area, with where replies will play. */
@Composable
private fun TalkBar(state: PhoneUiState, onTalk: () -> Unit) {
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
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.hasOlder) item { TextButton(onClick = model::loadOlder) { Text("Load older") } }
            items(state.history, key = { it.rowId }) { message ->
                val mine = message.role == "user"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    Card(
                        Modifier.fillMaxWidth(0.85f),
                        colors = CardDefaults.cardColors(
                            containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Text(message.text, Modifier.padding(10.dp))
                    }
                }
            }
        }
        state.attachments.forEachIndexed { index, attachment ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📎 ${attachment.name} (${attachment.bytes.size / 1024} KiB)", Modifier.weight(1f))
                TextButton(onClick = { model.removeAttachment(index) }) { Text("Remove") }
            }
        }
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.sending) { Text("Attach") }
            OutlinedTextField(state.draft, model::setDraft, Modifier.weight(1f), placeholder = { Text("Message") })
            Button(onClick = model::send, enabled = !state.sending && (state.draft.isNotBlank() || state.attachments.isNotEmpty())) {
                Text(if (state.sending) "…" else "Send")
            }
        }
    }
}

@Composable
private fun SettingsTab(state: PhoneUiState, model: PhoneViewModel, recognizerAvailable: Boolean) {
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
        Text("Spoken replies", style = MaterialTheme.typography.titleSmall)
        Text("The routing acknowledgement and the final reply always play. They play on whichever device, " +
            "this phone or the Watch, sent the most recent voice request; text messages don't change that.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SwitchRow("Play first response", state.playFirst, model::setPlayFirst)
        SwitchRow("Play middle responses", state.playMiddle, model::setPlayMiddle)

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Watch", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
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
        Text("Push-to-talk is always available on the Watch and this phone and records until you tap Send (no time limit).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SwitchRow("Watch haptics", state.watch.hapticsEnabled) { model.updateWatch(state.watch.copy(hapticsEnabled = it)) }

        HorizontalDivider()
        Text("Wake phrase", style = MaterialTheme.typography.titleSmall)
        Text("Listen on", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WakeLocation.values().forEach { location ->
                FilterChip(selected = state.watch.wakeLocation == location, onClick = { model.setWakeLocation(location) },
                    label = { Text(location.label) }, modifier = Modifier.testTag("wake_${location.name.lowercase()}"))
            }
        }
        val phoneListens = state.watch.wakeLocation.listensOn(VoiceOrigin.PHONE)
        val watchListens = state.watch.wakeLocation.listensOn(VoiceOrigin.WATCH)
        Text(
            when {
                !phoneListens -> "Phone: off"
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
                !watchListens -> "Watch: off"
                state.watchReachable == false -> "Watch: app not reachable; it applies this when it syncs"
                else -> "Watch: listens for ${WakeContract.WINDOW_MS / 1000} s each time the Watch app opens; the Watch shows if it has no recognizer"
            },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary,
        )
        Text("Only while the app is open on screen, never in the background. Say the wake phrase, pause for the buzz, " +
            "then speak: the request is sent when you stop talking (no time limit). Or say the request right after " +
            "the phrase: it's sent only once the speech recognizer has finished hearing it; if it can't, nothing is " +
            "sent and you're asked to repeat.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(patterns, { patterns = it }, label = { Text("Wake phrases for both devices (space-separated, * wildcard)") },
            modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { model.updateWatch(state.watch.copy(wakePatterns = patterns)) }) { Text("Save wake phrases") }
        Text("Send after ${VadSilence.label(silence.toDouble())} of silence", style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("vad_silence_label"))
        Slider(
            value = silence,
            onValueChange = { silence = ((it / VadSilence.STEP_SECONDS).roundToInt() * VadSilence.STEP_SECONDS).toFloat() },
            onValueChangeFinished = { model.setVadSilence(silence.toDouble()) },
            valueRange = VadSilence.MIN_SECONDS.toFloat()..VadSilence.MAX_SECONDS.toFloat(),
            steps = VadSilence.choices.size - 2,
            modifier = Modifier.fillMaxWidth().testTag("vad_silence"),
        )
        Text("Applies to hands-free requests on both devices, from the next request on. Silence is judged by loudness " +
            "against the room's background, not by understanding speech: steady noise such as a fan fades into the " +
            "background, but loud changing sound (music, TV, other voices) can keep a request open until you tap, and " +
            "very soft speech may count as silence.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
