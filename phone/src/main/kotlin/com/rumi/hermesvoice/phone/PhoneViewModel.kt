package com.rumi.hermesvoice.phone

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rumi.hermesvoice.core.ChatSendResult
import com.rumi.hermesvoice.core.HermesAuthRequiredException
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.net.AttachmentPolicy
import com.rumi.hermesvoice.core.net.HistoryMessage
import com.rumi.hermesvoice.core.net.OutgoingAttachment
import com.rumi.hermesvoice.core.sessions.AppConversation
import com.rumi.hermesvoice.core.sessions.OwnedSession
import com.rumi.hermesvoice.core.settings.ThemeMode
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import com.rumi.hermesvoice.core.watchlink.VoiceOutcomeText
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PhoneUiState(
    val dashboardUrl: String = "",
    val profile: String = "",
    val signedIn: Boolean = false,
    val status: String = "",
    val showArchived: Boolean = false,
    val conversations: List<AppConversation> = emptyList(),
    val selected: OwnedSession? = null,
    val history: List<HistoryMessage> = emptyList(),
    val hasOlder: Boolean = false,
    /** Raw transcript rows fetched so far (the server's offset unit; includes rows we do not display). */
    val rawLoaded: Int = 0,
    val draft: String = "",
    val attachments: List<OutgoingAttachment> = emptyList(),
    val sending: Boolean = false,
    val recording: Boolean = false,
    val voiceStatus: String = "",
    val playFirst: Boolean = false,
    val playMiddle: Boolean = false,
    val watch: WatchSettings = WatchSettings(),
    val themeMode: ThemeMode = ThemeMode.DARK,
    /** Where spoken acks and replies play: the device of the latest accepted voice request. */
    val playbackDevice: VoiceOrigin? = null,
    /** Whether the Watch app is reachable over the Data Layer; null until checked. */
    val watchReachable: Boolean? = null,
)

class PhoneViewModel(application: Application) : AndroidViewModel(application) {
    private val app = PhoneApp.from(application)
    private val recorder = WavRecorder()
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<PhoneUiState> = _state

    init {
        viewModelScope.launch { app.playbackDevice.collect { device -> _state.update { it.copy(playbackDevice = device) } } }
        refreshWatchStatus()
    }

    private fun initialState() = PhoneUiState(
        dashboardUrl = app.settings.dashboardUrl,
        profile = app.settings.profile,
        signedIn = app.tokens.load() != null,
        playFirst = app.settings.playFirstResponse,
        playMiddle = app.settings.playMiddleResponses,
        watch = app.settings.watchSettings(),
        themeMode = app.settings.themeMode,
    )

    private fun wiringOrStatus(): PhoneApp.Wiring? = try {
        app.wiring()
    } catch (error: IllegalArgumentException) {
        _state.update { it.copy(status = error.message ?: "Dashboard URL is invalid") }
        null
    }

    private fun launchGuarded(block: suspend (PhoneApp.Wiring) -> Unit) {
        val wiring = wiringOrStatus() ?: return
        viewModelScope.launch {
            try {
                block(wiring)
            } catch (error: HermesAuthRequiredException) {
                _state.update { it.copy(signedIn = false, status = "Sign in to Hermes (${error.message})") }
            } catch (error: Exception) {
                Log.w(TAG, "action failed", error)
                _state.update { it.copy(status = error.message ?: error.javaClass.simpleName) }
            }
        }
    }

    // ── connection & auth ────────────────────────────────────────────────────────────────────

    fun saveConnection(url: String, profile: String) {
        val changed = url.trim() != app.settings.dashboardUrl || profile.trim() != app.settings.profile
        app.settings.dashboardUrl = url
        app.settings.profile = profile
        // A bearer pair belongs to the dashboard that minted it; never replay it elsewhere.
        if (changed) app.tokens.clear()
        _state.update { it.copy(dashboardUrl = app.settings.dashboardUrl, profile = app.settings.profile,
            signedIn = app.tokens.load() != null, selected = null, history = emptyList(), conversations = emptyList(),
            status = "Saved") }
    }

    fun signIn() {
        if (wiringOrStatus() == null) return
        viewModelScope.launch {
            _state.update { it.copy(status = "Complete sign-in in the browser…") }
            val message = runCatching { runNativeSignIn(getApplication()) }.getOrElse { "Sign-in failed: ${it.message}" }
            _state.update { it.copy(status = message, signedIn = app.tokens.load() != null) }
            if (app.tokens.load() != null) refresh()
        }
    }

    fun signOut() {
        app.tokens.clear()
        _state.update { it.copy(signedIn = false, status = "Signed out on this phone") }
    }

    // ── sessions ─────────────────────────────────────────────────────────────────────────────

    fun refresh() = launchGuarded { wiring ->
        val list = wiring.core.sessions.listConversations(archived = _state.value.showArchived)
        _state.update { it.copy(conversations = list, status = "") }
    }

    fun setShowArchived(show: Boolean) {
        _state.update { it.copy(showArchived = show) }
        refresh()
    }

    fun createConversation(title: String, alias: String, description: String) = launchGuarded { wiring ->
        val owned = wiring.core.sessions.createConversation(title, alias, description)
        _state.update { it.copy(status = "Created '${owned.title}' (voice alias: ${owned.alias})") }
        refresh()
        open(owned)
    }

    fun setArchived(session: OwnedSession, archived: Boolean) = launchGuarded { wiring ->
        wiring.core.sessions.setArchived(session.storedSessionId, archived)
        if (archived && _state.value.selected?.storedSessionId == session.storedSessionId) {
            _state.update { it.copy(selected = null, history = emptyList()) }
        }
        refresh()
    }

    fun updateAlias(session: OwnedSession, alias: String, description: String) = launchGuarded { wiring ->
        wiring.core.sessions.updateDestination(session.storedSessionId, alias, description)
        refresh()
    }

    fun open(session: OwnedSession) = launchGuarded { wiring ->
        val page = wiring.core.sessions.history(session.storedSessionId, limit = HISTORY_PAGE)
        _state.update { it.copy(selected = session, history = page.messages, hasOlder = page.returned >= HISTORY_PAGE,
            rawLoaded = page.returned, draft = "", attachments = emptyList()) }
    }

    fun loadOlder() = launchGuarded { wiring ->
        val selected = _state.value.selected ?: return@launchGuarded
        val page = wiring.core.sessions.history(selected.storedSessionId, limit = HISTORY_PAGE, offset = _state.value.rawLoaded)
        _state.update { current ->
            // New rows may have landed since the first page; de-duplicate by row id.
            val known = current.history.map { it.rowId }.toSet()
            current.copy(history = page.messages.filter { it.rowId !in known } + current.history,
                hasOlder = page.returned >= HISTORY_PAGE, rawLoaded = current.rawLoaded + page.returned)
        }
    }

    // ── text chat & attachments ──────────────────────────────────────────────────────────────

    fun setDraft(text: String) = _state.update { it.copy(draft = text) }

    fun addAttachment(uri: Uri) {
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val attachment = withContext(Dispatchers.IO) {
                runCatching {
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "attachment"
                    val size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                    require(size in 1..AttachmentPolicy.MAX_BYTES.toLong()) {
                        "$name is larger than ${AttachmentPolicy.MAX_BYTES / (1024 * 1024)} MiB or unreadable"
                    }
                    val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
                    OutgoingAttachment(name, resolver.getType(uri) ?: "application/octet-stream", bytes)
                }
            }
            attachment.onSuccess { item ->
                _state.update { it.copy(attachments = (it.attachments + item).take(AttachmentPolicy.MAX_ATTACHMENTS)) }
            }.onFailure { error -> _state.update { it.copy(status = error.message ?: "Could not read the file") } }
        }
    }

    fun removeAttachment(index: Int) = _state.update { it.copy(attachments = it.attachments.filterIndexed { i, _ -> i != index }) }

    fun send() {
        val current = _state.value
        val selected = current.selected ?: return
        if (current.sending || (current.draft.isBlank() && current.attachments.isEmpty())) return
        launchGuarded { wiring ->
            _state.update { it.copy(sending = true, status = "Sending…") }
            try {
                val result = wiring.core.chat.send(selected.storedSessionId, current.draft, current.attachments)
                val status = when (result) {
                    is ChatSendResult.Replied -> ""
                    is ChatSendResult.Accepted -> "Queued in Hermes; the reply will appear after refresh"
                    is ChatSendResult.Failed -> result.reason.also { if (result.authRequired) _state.update { s -> s.copy(signedIn = false) } }
                }
                val delivered = result !is ChatSendResult.Failed || result.reason.startsWith("sent,")
                _state.update { it.copy(status = status, draft = if (delivered) "" else it.draft,
                    attachments = if (delivered) emptyList() else it.attachments) }
                if (delivered) open(selected)
            } finally {
                _state.update { it.copy(sending = false) }
            }
        }
    }

    // ── phone push-to-talk ───────────────────────────────────────────────────────────────────

    fun toggleRecording() {
        if (!recorder.isRecording) {
            runCatching { recorder.start() }
                .onSuccess { _state.update { it.copy(recording = true, voiceStatus = "Listening… tap to send") } }
                .onFailure { error -> _state.update { it.copy(voiceStatus = error.message ?: "Microphone unavailable") } }
            return
        }
        val wav = recorder.stop()
        _state.update { it.copy(recording = false) }
        if (wav == null) {
            _state.update { it.copy(voiceStatus = "Too short") }
            return
        }
        Log.i(TAG, "phone mic captured bytes=${wav.size} peak=${WavRecorder.peak(wav)}")
        submitVoice(wav)
    }

    /**
     * Debug builds only (see [com.rumi.hermesvoice.core.audio.QaAudio]): runs a WAV from the app's private `files/qa/` through the
     * same Phone voice path as the Talk button, for emulators whose microphone carries no speech.
     */
    fun submitQaWav(wav: ByteArray) {
        if (recorder.isRecording) return
        submitVoice(wav)
    }

    private fun submitVoice(wav: ByteArray) {
        launchGuarded { wiring ->
            _state.update { it.copy(voiceStatus = "Sending…") }
            val turnId = UUID.randomUUID().toString()
            val outcome = wiring.core.orchestrator.run(VoiceTurnRequest(turnId, VoiceOrigin.PHONE, wav, "audio/wav",
                PhoneSpeakerSink(getApplication())))
            Log.i(TAG, "phone turn ${turnId.take(12)} outcome=${outcome.javaClass.simpleName}")
            _state.update { it.copy(voiceStatus = VoiceOutcomeText.describe(outcome)) }
            _state.value.selected?.let { open(it) }
        }
    }

    // ── settings ─────────────────────────────────────────────────────────────────────────────

    fun setPlayFirst(value: Boolean) {
        app.settings.playFirstResponse = value
        _state.update { it.copy(playFirst = value) }
    }

    fun setPlayMiddle(value: Boolean) {
        app.settings.playMiddleResponses = value
        _state.update { it.copy(playMiddle = value) }
    }

    fun setThemeMode(mode: ThemeMode) {
        app.settings.themeMode = mode
        _state.update { it.copy(themeMode = mode) }
    }

    fun refreshWatchStatus() {
        viewModelScope.launch {
            val reachable = runCatching { WatchPresence.reachable(getApplication()) }.getOrDefault(false)
            _state.update { it.copy(watchReachable = reachable) }
        }
    }

    fun updateWatch(settings: WatchSettings) {
        app.settings.watchWakePhraseEnabled = settings.wakePhraseEnabled
        app.settings.watchWakePatterns = settings.wakePatterns
        app.settings.watchMaxTurnSeconds = settings.maxTurnSeconds
        app.settings.watchHapticsEnabled = settings.hapticsEnabled
        val saved = app.settings.watchSettings()
        _state.update { it.copy(watch = saved) }
        viewModelScope.launch {
            val result = runCatching { WatchSettingsSync.publish(getApplication(), saved) }
            _state.update { it.copy(status = if (result.isSuccess) "Watch settings sent" else "Watch not reachable; will apply when it syncs") }
        }
    }

    override fun onCleared() {
        recorder.stop()
    }

    companion object {
        const val HISTORY_PAGE = 50
        private const val TAG = "HermesVoice"
    }
}
