package com.rumi.hermesvoice.phone

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rumi.hermesvoice.core.ChatSendResult
import com.rumi.hermesvoice.core.HermesAuthRequiredException
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.audio.CaptureEnd
import com.rumi.hermesvoice.core.audio.PcmCaptureLoop
import com.rumi.hermesvoice.core.audio.SilenceEndpoint
import com.rumi.hermesvoice.core.net.AttachmentPolicy
import com.rumi.hermesvoice.core.net.HistoryMessage
import com.rumi.hermesvoice.core.net.OutgoingAttachment
import com.rumi.hermesvoice.core.sessions.AppConversation
import com.rumi.hermesvoice.core.sessions.OwnedSession
import com.rumi.hermesvoice.core.settings.ThemeMode
import com.rumi.hermesvoice.core.settings.VadSilence
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import com.rumi.hermesvoice.core.watchlink.CaptureCoordinator
import com.rumi.hermesvoice.core.watchlink.CapturePort
import com.rumi.hermesvoice.core.watchlink.CaptureStop
import com.rumi.hermesvoice.core.watchlink.HapticEvent
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
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
    /** A Phone voice turn is being sent, answered or played. */
    val voiceBusy: Boolean = false,
    /** The Phone's hands-free state: listening for the wake phrase, or recording the request after it. */
    val handsFree: HandsFree = HandsFree.IDLE,
    /** Bumped for each recording start/end haptic of a hands-free request (the activity plays it). */
    val hapticTick: Int = 0,
)

enum class HandsFree { IDLE, LISTENING, GET_READY, SPEAK_NOW }

class PhoneViewModel(application: Application) : AndroidViewModel(application) {
    private val app = PhoneApp.from(application)
    private val recorder = WavRecorder()
    private val main = Handler(Looper.getMainLooper())

    /** The hands-free recorder of the active wake capture; its lifecycle lives in [captures]. */
    private var handsFreeRecorder: PhoneCapture? = null

    /** The wake claim the active hands-free capture was started under ("Both"), or null. */
    private var handsFreeClaimId: String? = null

    /** Told when a hands-free capture ended: true if it was handed on as a request (with its claim). */
    var onHandsFreeEnded: ((sent: Boolean) -> Unit)? = null

    /** The same capture lifecycle as the Watch's: exactly-once stop, eligibility check, never a partial send. */
    private val captures = CaptureCoordinator(object : CapturePort {
        override fun stopRecorder(captureId: String, reason: CaptureStop): ByteArray? {
            val active = handsFreeRecorder?.takeIf { it.captureId == captureId }
            handsFreeRecorder = null
            val wav = active?.stop()
            active?.stats()?.let { stats ->
                // Aggregates only (no audio): the configured trailing silence and what the VAD measured.
                Log.i(TAG, "phone hands-free captured turn=${captureId.take(12)} end=$reason pcm_bytes=${stats.pcmBytes} " +
                    "peak=${stats.peak} rms=${stats.rms} speech=${stats.speech} vad_silence_ms=${stats.silenceMs} " +
                    "speech_end_ms=${stats.speechEndMs} end_ms=${stats.endMs} " +
                    "trailing_ms=${if (stats.speechEndMs >= 0) stats.endMs - stats.speechEndMs else -1} wav=${wav != null}")
            }
            _state.update { it.copy(handsFree = HandsFree.IDLE) }
            return wav
        }

        override fun haptic(event: HapticEvent) {
            Log.i(TAG, "phone haptic $event")
            _state.update { it.copy(hapticTick = it.hapticTick + 1) }
        }

        override fun cue(line: String) = _state.update { it.copy(handsFree = HandsFree.SPEAK_NOW, voiceStatus = line) }
        override fun upload(captureId: String, trigger: TurnTrigger, wav: ByteArray) {
            val claimId = handsFreeClaimId
            handsFreeClaimId = null
            onHandsFreeEnded?.invoke(true)
            submitVoice(wav, wakeTurn = true, wakeClaimId = claimId)
        }

        override fun uploadRecognized(turnId: String, text: String) = submitRecognized(turnId, text, recognizedClaimId)

        override fun discard(message: String) {
            Log.i(TAG, "phone hands-free discarded: $message")
            handsFreeClaimId = null
            onHandsFreeEnded?.invoke(false)
            _state.update { it.copy(voiceStatus = message) }
        }
    })
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<PhoneUiState> = _state

    init {
        viewModelScope.launch { app.playbackDevice.collect { device -> _state.update { it.copy(playbackDevice = device) } } }
        // A voice turn (from the Phone or the Watch) created a conversation: show it.
        viewModelScope.launch { app.conversationsCreated.collect { count -> if (count > 0 && _state.value.signedIn) refresh() } }
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
        // Tapping during a hands-free request sends it now (it was listening for the user anyway).
        if (captures.tap()) return
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

    private fun submitVoice(wav: ByteArray, wakeTurn: Boolean = false, wakeClaimId: String? = null) =
        runPhoneTurn(UUID.randomUUID().toString()) { turnId ->
            VoiceTurnRequest(turnId, VoiceOrigin.PHONE, wav, "audio/wav", PhoneSpeakerSink(getApplication()),
                wakeTurn = wakeTurn, wakeClaimId = wakeClaimId)
        }

    /** The wake claim of the recognized request being sent; read once by [submitRecognized]. */
    private var recognizedClaimId: String? = null

    /** A request the Phone's recognizer heard in full after the wake phrase: routed like speech, as text. */
    private fun submitRecognized(turnId: String, text: String, claimId: String?) = runPhoneTurn(turnId) { id ->
        VoiceTurnRequest(id, VoiceOrigin.PHONE, ByteArray(0), "text/plain", PhoneSpeakerSink(getApplication()), recognizedText = text,
            wakeTurn = true, wakeClaimId = claimId)
    }

    /** Runs one Phone voice turn; [PhoneUiState.voiceBusy] holds while it is sent, answered and played. */
    private fun runPhoneTurn(turnId: String, request: (String) -> VoiceTurnRequest) = launchGuarded { wiring ->
        _state.update { it.copy(voiceBusy = true, voiceStatus = "Sending…") }
        try {
            val outcome = wiring.core.orchestrator.run(request(turnId))
            Log.i(TAG, "phone turn ${turnId.take(12)} outcome=${outcome.javaClass.simpleName}")
            _state.update { it.copy(voiceStatus = VoiceOutcomeText.describe(outcome)) }
            _state.value.selected?.let { open(it) }
        } finally {
            _state.update { it.copy(voiceBusy = false) }
        }
    }

    // ── phone hands-free (wake phrase) ───────────────────────────────────────────────────────

    /** Nothing on the Phone owns the microphone or speaker: the wake phrase may listen. */
    fun voiceIdle(): Boolean = !recorder.isRecording && captures.activeId == null && !_state.value.voiceBusy

    /** A hands-free request is being recorded (a tap on Talk sends it). */
    fun handsFreeCapturing(): Boolean = captures.activeId != null

    fun setWakeListening(open: Boolean) = _state.update {
        when {
            open -> it.copy(handsFree = HandsFree.LISTENING)
            it.handsFree == HandsFree.LISTENING -> it.copy(handsFree = HandsFree.IDLE)
            else -> it
        }
    }

    /**
     * Starts the hands-free recorder after a phrase-only wake result. It ends after [silenceMs] of
     * trailing silence (the setting now; a later change applies to the next request), on no
     * speech, or on a tap; a pause or opt-out cancels it unsent ([cancelHandsFree]).
     */
    fun startHandsFree(silenceMs: Long, claimId: String? = null): Boolean {
        if (recorder.isRecording || _state.value.voiceBusy) return false
        handsFreeClaimId = claimId
        val id = UUID.randomUUID().toString()
        if (!captures.begin(id, TurnTrigger.WAKE_PHRASE)) return false
        val capture = PhoneCapture(id, SilenceEndpoint(sampleRate = PhoneCapture.SAMPLE_RATE, silenceMs = silenceMs),
            object : PcmCaptureLoop.Listener {
                override fun onLive() = Unit
                override fun onCalibrated() { main.post { captures.onCalibrated(id) } }
                override fun onEnd(reason: CaptureEnd) { main.post { captures.stop(id, CaptureStop.of(reason)) } }
            })
        handsFreeRecorder = capture
        Log.i(TAG, "phone hands-free capture turn=${id.take(12)} vad_silence_ms=$silenceMs")
        if (!capture.start()) {
            captures.stop(id, CaptureStop.START_FAILED)
            return false
        }
        _state.update { it.copy(handsFree = HandsFree.GET_READY, voiceStatus = "Get ready…") }
        return true
    }

    /** Stops a hands-free capture in progress without sending it (pause, screen off, opt-out). */
    fun cancelHandsFree(reason: String) {
        val id = captures.activeId ?: return
        Log.i(TAG, "phone hands-free capture cancelled reason=$reason (not sent)")
        captures.stop(id, CaptureStop.LIFECYCLE)
    }

    /** The recognizer heard the whole request with the wake phrase: one end pulse, then send it as text. */
    fun sendRecognizedRequest(text: String, claimId: String? = null) {
        if (!voiceIdle()) {
            // Something else took the microphone or speaker in the meantime: say so instead of dropping it silently.
            Log.i(TAG, "phone recognized request not sent: busy (retry notice)")
            claimId?.let { id -> runCatching { app.wiring().core.wakeAdmission.release(id, VoiceOrigin.PHONE, "") } }
            return onWakeClosed("unfinished_request")
        }
        recognizedClaimId = claimId
        captures.sendRecognized(UUID.randomUUID().toString(), text)
    }

    fun onWakeClosed(reason: String) {
        val notice = when (reason) {
            "unfinished_request" -> "Didn't catch that. Tap Talk or say it again"
            "request_too_long" -> "That was too long to send as text. Use Talk"
            "unavailable" -> "Wake phrase unavailable on this phone (no speech recognizer)"
            "wake_taken" -> "The Watch answered that wake phrase"
            "wake_claim_timeout", "wake_claim_failed" -> "Couldn't confirm the wake phrase. Say it again"
            "recognizer_error_12", "recognizer_error_13" -> "The speech recognizer lacks the wake phrase language"
            else -> return
        }
        _state.update { it.copy(voiceStatus = notice) }
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

    /** Saves all shared voice settings as one Phone-owned snapshot (new revision) and publishes it to the Watch. */
    fun updateWatch(settings: WatchSettings) {
        val saved = app.settings.saveWatchSettings(settings)
        _state.update { it.copy(watch = saved) }
        viewModelScope.launch {
            val result = runCatching { WatchSettingsSync.publish(getApplication(), saved) }
            Log.i(TAG, "voice settings saved+published revision=${saved.revision} wake_location=${saved.wakeLocation} " +
                "vad_silence_s=${saved.vadSilenceSeconds} ok=${result.isSuccess}")
            _state.update { it.copy(status = if (result.isSuccess) "Settings sent to the Watch" else "Watch not reachable; will apply when it syncs") }
        }
    }

    fun setWakeLocation(location: WakeLocation) = updateWatch(_state.value.watch.copy(wakeLocation = location))

    /** Only the offered 0.5 s steps are accepted; anything else is ignored. */
    fun setVadSilence(seconds: Double) {
        val valid = VadSilence.validOrNull(seconds) ?: return
        if (valid != _state.value.watch.vadSilenceSeconds) updateWatch(_state.value.watch.copy(vadSilenceSeconds = valid))
    }

    override fun onCleared() {
        recorder.stop()
        cancelHandsFree("cleared")
    }

    companion object {
        const val HISTORY_PAGE = 50
        private const val TAG = "HermesVoice"
    }
}
