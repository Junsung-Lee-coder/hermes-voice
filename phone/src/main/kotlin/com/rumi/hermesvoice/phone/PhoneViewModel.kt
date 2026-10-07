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
import com.rumi.hermesvoice.core.background.BackgroundNotice
import com.rumi.hermesvoice.core.background.BackgroundStatus
import com.rumi.hermesvoice.core.net.AttachmentPolicy
import com.rumi.hermesvoice.core.net.HistoryMessage
import com.rumi.hermesvoice.core.net.HistoryPages
import com.rumi.hermesvoice.core.net.OutgoingAttachment
import com.rumi.hermesvoice.core.sessions.AppConversation
import com.rumi.hermesvoice.core.sessions.OwnedSession
import com.rumi.hermesvoice.core.settings.ThemeMode
import com.rumi.hermesvoice.core.settings.VadSilence
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.voice.MicrophoneClaim
import com.rumi.hermesvoice.core.voice.TurnRouting
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import com.rumi.hermesvoice.core.watchlink.CaptureCoordinator
import com.rumi.hermesvoice.core.watchlink.CapturePort
import com.rumi.hermesvoice.core.watchlink.CaptureStop
import com.rumi.hermesvoice.core.watchlink.HapticEvent
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.VoiceOutcomeText
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
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
    /** A Phone voice turn is being SENT (recorded audio on its way, its acknowledgement); waiting for a reply is [pending], not busy. */
    val voiceBusy: Boolean = false,
    /** The accepted voice requests (either device) that are not finished: sending, queued, waiting for a reply or speaking it. */
    val pending: List<com.rumi.hermesvoice.core.voice.PendingTurn> = emptyList(),
    /** The Phone's hands-free state: listening for the wake phrase, or recording the request after it. */
    val handsFree: HandsFree = HandsFree.IDLE,
    /** Bumped for each recording start/end haptic of a hands-free request (the activity plays it). */
    val hapticTick: Int = 0,
    /** The optional background relay as it really is now (see [PhoneApp.relay]). */
    val relay: BackgroundStatus = BackgroundStatus(false, false, false, BackgroundNotice.OFF),
    /** Voice routing (Settings): on, the router picks the conversation; off, voice goes to [selected]. */
    val routingEnabled: Boolean = true,
    /** Open the routed conversation after delivery (Settings); kept but not applied while routing is off. */
    val autoNavigate: Boolean = false,
    /** Bumped when a routed turn's conversation was opened for the user: the screen shows Chat. */
    val chatOpenRequest: Long = 0,
    /** Speak later replies (Settings; informed opt-in, off by default). */
    val speakLater: Boolean = false,
    /** How long a delivered turn keeps being followed for later replies (Settings; Phone-owned minutes, 1–4320, default 30). */
    val laterReplyWindowMinutes: Int = com.rumi.hermesvoice.core.settings.LaterReplyWindow.DEFAULT_MINUTES,
    /** Why the last typed later-reply duration was refused (null when it was accepted or nothing was typed). */
    val laterReplyWindowError: String? = null,
    /** The opt-in background listening for the wake phrase as it really is now (see [PhoneBackgroundRuntime]). */
    val phoneWake: com.rumi.hermesvoice.core.background.PhoneWakeStatus? = null,
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

    /** The microphone claims of the push-to-talk and hands-free recordings ([PhoneApp.audio]); handed to their request. */
    private var pushToTalkMicrophone: MicrophoneClaim? = null
    private var handsFreeMicrophone: MicrophoneClaim? = null

    /** Push-to-talk claimed the microphone and waits for a later reply it stopped to stop (a moment). */
    private var pushToTalkOpening = false

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
            val microphone = handsFreeMicrophone
            handsFreeMicrophone = null
            onHandsFreeEnded?.invoke(true)
            submitVoice(wav, wakeTurn = true, wakeClaimId = claimId, microphone = microphone)
        }

        override fun uploadRecognized(turnId: String, text: String) {
            val microphone = recognizedMicrophone
            recognizedMicrophone = null
            submitRecognized(turnId, text, recognizedClaimId, microphone)
        }

        override fun discard(message: String) {
            Log.i(TAG, "phone hands-free discarded: $message")
            handsFreeClaimId = null
            handsFreeMicrophone?.release()
            handsFreeMicrophone = null
            onHandsFreeEnded?.invoke(false)
            _state.update { it.copy(voiceStatus = message) }
        }
    })
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<PhoneUiState> = _state

    /** Bumped for every selection; a history read made for an earlier selection is dropped when it lands. */
    private val selection = AtomicLong(0)

    /** Unsent text and attachments of conversations switched away from, so changing conversations never loses them. */
    private val drafts = HashMap<String, Pair<String, List<OutgoingAttachment>>>()

    init {
        viewModelScope.launch { app.playbackDevice.collect { device -> _state.update { it.copy(playbackDevice = device) } } }
        // "Open the routed conversation": only requests made while this screen exists (never a replayed old one).
        viewModelScope.launch { app.routedOpen.collect { storedSessionId -> openRouted(storedSessionId) } }
        // A voice turn (from the Phone or the Watch) created a conversation: show it.
        viewModelScope.launch { app.conversationsCreated.collect { count -> if (count > 0 && _state.value.signedIn) refresh() } }
        viewModelScope.launch { app.relayStatus.collect { relay -> _state.update { it.copy(relay = relay) } } }
        viewModelScope.launch { app.phoneWake.status.collect { wake -> _state.update { it.copy(phoneWake = wake, watch = app.settings.watchSettings()) } } }
        // A request heard with the app closed: its result shows here when the app is opened.
        viewModelScope.launch { app.phoneWake.notice.collect { line -> if (line != null) _state.update { it.copy(voiceStatus = line) } } }
        // A later reply of a delivered request (a background completion) was spoken, or couldn't be.
        viewModelScope.launch { app.laterReplies.collect { line -> if (line != null) _state.update { it.copy(voiceStatus = line) } } }
        // Phone voice turns run in the application, so one started before this screen was (re)created still counts as busy.
        viewModelScope.launch { app.phoneTurns.collect { running -> _state.update { it.copy(voiceBusy = running > 0) } } }
        viewModelScope.launch { app.pendingTurns.collect { list -> _state.update { it.copy(pending = list) } } }
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
        routingEnabled = app.settings.routingEnabled,
        autoNavigate = app.settings.autoNavigateToRouted,
        speakLater = app.laterConsent.enabled,
        laterReplyWindowMinutes = app.settings.laterReplyWindowMinutes,
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
        selection.incrementAndGet()
        app.selectedConversationId = null
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
            selection.incrementAndGet()
            app.selectedConversationId = null
            _state.update { it.copy(selected = null, history = emptyList()) }
        }
        refresh()
    }

    fun updateAlias(session: OwnedSession, alias: String, description: String) = launchGuarded { wiring ->
        wiring.core.sessions.updateDestination(session.storedSessionId, alias, description)
        refresh()
    }

    /**
     * Shows [session] from its newest page. A read that lands after another conversation was
     * selected is dropped. The unsent draft is kept per conversation (the one switched away from
     * is stashed, the one opened gets its own back).
     */
    fun open(session: OwnedSession) {
        val generation = selection.incrementAndGet()
        launchGuarded { wiring ->
            val page = wiring.core.sessions.history(session.storedSessionId, limit = HISTORY_PAGE)
            if (selection.get() != generation) return@launchGuarded
            // On the main thread, like every draft edit: the stash is never touched inside a retried update.
            val current = _state.value
            val (draft, attachments) = if (current.selected?.storedSessionId == session.storedSessionId) current.draft to current.attachments
            else {
                current.selected?.let { drafts[it.storedSessionId] = current.draft to current.attachments }
                drafts.remove(session.storedSessionId) ?: ("" to emptyList())
            }
            _state.update {
                it.copy(selected = session, history = page.messages, hasOlder = page.returned >= HISTORY_PAGE,
                    rawLoaded = page.returned, draft = draft, attachments = attachments)
            }
            // With routing off, a voice request made with the app closed goes to the conversation open here.
            app.selectedConversationId = session.storedSessionId
        }
    }

    /**
     * Re-reads the newest page of the conversation on screen (after a send or a voice turn): new
     * and grown rows replace theirs, older rows already loaded stay, and the draft is untouched.
     * Dropped if another conversation was selected meanwhile.
     */
    private fun reloadSelected() {
        val session = _state.value.selected ?: return
        val generation = selection.get()
        launchGuarded { wiring ->
            val page = wiring.core.sessions.history(session.storedSessionId, limit = HISTORY_PAGE)
            _state.update { current ->
                if (selection.get() != generation || current.selected?.storedSessionId != session.storedSessionId) return@update current
                val merged = HistoryPages.refreshLatest(current.history, page.messages)
                val keptOlder = merged.size > page.messages.size
                current.copy(history = merged,
                    hasOlder = if (keptOlder) current.hasOlder else page.returned >= HISTORY_PAGE,
                    rawLoaded = if (keptOlder) maxOf(current.rawLoaded, page.returned) else page.returned)
            }
        }
    }

    fun loadOlder() {
        val generation = selection.get()
        launchGuarded { wiring ->
            val selected = _state.value.selected ?: return@launchGuarded
            val page = wiring.core.sessions.history(selected.storedSessionId, limit = HISTORY_PAGE, offset = _state.value.rawLoaded)
            _state.update { current ->
                if (selection.get() != generation || current.selected?.storedSessionId != selected.storedSessionId) return@update current
                // New rows may have landed since the first page; de-duplicate by row id.
                val known = current.history.map { it.rowId }.toSet()
                current.copy(history = page.messages.filter { it.rowId !in known } + current.history,
                    hasOlder = page.returned >= HISTORY_PAGE, rawLoaded = current.rawLoaded + page.returned)
            }
        }
    }

    /** The user opened a conversation or changed screens: a routed turn accepted earlier no longer moves the screen. */
    fun onUserNavigated() = app.routedNavigation.onManualNavigation()

    /** A routed turn's conversation, opened because "Open the routed conversation" is on (see [PhoneApp.routedNavigation]). */
    private fun openRouted(storedSessionId: String) {
        if (!_state.value.signedIn) return
        val wiring = wiringOrStatus() ?: return
        val owned = wiring.core.sessions.activeConversation(storedSessionId) ?: return
        Log.i(TAG, "routed conversation opened alias=${owned.alias}")
        if (_state.value.selected?.storedSessionId != storedSessionId) open(owned)
        _state.update { it.copy(chatOpenRequest = it.chatOpenRequest + 1) }
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
                _state.update {
                    // Only the conversation the message went to loses its draft (the user may have switched meanwhile).
                    if (it.selected?.storedSessionId != selected.storedSessionId) it.copy(status = status)
                    else it.copy(status = status, draft = if (delivered) "" else it.draft,
                        attachments = if (delivered) emptyList() else it.attachments)
                }
                if (delivered) reloadSelected()
            } finally {
                _state.update { it.copy(sending = false) }
            }
        }
    }

    // ── phone push-to-talk ───────────────────────────────────────────────────────────────────

    fun toggleRecording() {
        // Tapping during a hands-free request sends it now (it was listening for the user anyway).
        if (captures.tap()) return
        if (pushToTalkOpening) return
        if (!recorder.isRecording) {
            // Claimed BEFORE the microphone opens: no later reply starts on this Phone any more, and one
            // playing now is stopped; the recorder opens only once it has really stopped.
            val microphone = app.audio.claimMicrophone(VoiceOrigin.PHONE)
            pushToTalkOpening = true
            microphone.whenSpeakerStopped(viewModelScope) { stopped ->
                pushToTalkOpening = false
                if (!stopped) {
                    _state.update { it.copy(voiceStatus = "Microphone busy. Tap Talk again") }
                    return@whenSpeakerStopped
                }
                runCatching { recorder.start() }
                    .onSuccess {
                        pushToTalkMicrophone = microphone
                        _state.update { it.copy(recording = true, voiceStatus = "Listening… tap to send") }
                    }
                    .onFailure { error ->
                        microphone.release()
                        _state.update { it.copy(voiceStatus = error.message ?: "Microphone unavailable") }
                    }
            }
            return
        }
        val wav = recorder.stop()
        val microphone = pushToTalkMicrophone
        pushToTalkMicrophone = null
        _state.update { it.copy(recording = false) }
        if (wav == null) {
            microphone?.release()
            _state.update { it.copy(voiceStatus = "Too short") }
            return
        }
        Log.i(TAG, "phone mic captured bytes=${wav.size} peak=${WavRecorder.peak(wav)}")
        // The claim goes with the request: later replies keep waiting until it has been answered.
        submitVoice(wav, microphone = microphone)
    }

    /**
     * Debug builds only (see [com.rumi.hermesvoice.core.audio.QaAudio]): runs a WAV from the app's private `files/qa/` through the
     * same Phone voice path as the Talk button, for emulators whose microphone carries no speech.
     */
    fun submitQaWav(wav: ByteArray) {
        if (recorder.isRecording) return
        submitVoice(wav)
    }

    /** The routing switch and the conversation on this Phone, frozen now for the turn about to start. */
    private fun routingNow(): TurnRouting = TurnRouting.of(app.settings.routingEnabled, _state.value.selected?.storedSessionId)

    private fun submitVoice(wav: ByteArray, wakeTurn: Boolean = false, wakeClaimId: String? = null, microphone: MicrophoneClaim? = null) {
        val routing = routingNow()
        runPhoneTurn(UUID.randomUUID().toString(), microphone) { turnId ->
            VoiceTurnRequest(turnId, VoiceOrigin.PHONE, wav, "audio/wav", PhoneSpeakerSink(getApplication()),
                wakeTurn = wakeTurn, wakeClaimId = wakeClaimId, routing = routing, microphone = microphone)
        }
    }

    /** The wake claim and microphone claim of the recognized request being sent; read once by [submitRecognized]. */
    private var recognizedClaimId: String? = null
    private var recognizedMicrophone: MicrophoneClaim? = null

    /** A request the Phone's recognizer heard in full after the wake phrase: routed like speech, as text. */
    private fun submitRecognized(turnId: String, text: String, claimId: String?, microphone: MicrophoneClaim?) {
        val routing = routingNow()
        runPhoneTurn(turnId, microphone) { id ->
            VoiceTurnRequest(id, VoiceOrigin.PHONE, ByteArray(0), "text/plain", PhoneSpeakerSink(getApplication()), recognizedText = text,
                wakeTurn = true, wakeClaimId = claimId, routing = routing, microphone = microphone)
        }
    }

    /**
     * Runs one Phone voice turn; [PhoneUiState.voiceBusy] holds while it is sent (not while it waits for its reply: the
     * request then shows in [PhoneUiState.pending] and a new one may start). The turn belongs to the application ([PhoneApp.launchTurn]), not to this screen:
     * leaving or recreating the screen does not end it. The orchestrator takes [microphone] over
     * when it starts the turn; a turn that never ran gives it back when its job ends.
     */
    private fun runPhoneTurn(turnId: String, microphone: MicrophoneClaim? = null, request: (String) -> VoiceTurnRequest) {
        val wiring = wiringOrStatus() ?: return run { microphone?.release() }
        _state.update { it.copy(voiceBusy = true, voiceStatus = "Sending…") }
        app.launchTurn(turnId, phoneOrigin = true, microphone) {
            try {
                val outcome = wiring.core.orchestrator.run(request(turnId))
                Log.i(TAG, "phone turn ${turnId.take(12)} outcome=${outcome.javaClass.simpleName}")
                _state.update { it.copy(voiceStatus = VoiceOutcomeText.describe(outcome)) }
                reloadSelected()
            } catch (error: HermesAuthRequiredException) {
                _state.update { it.copy(signedIn = false, status = "Sign in to Hermes (${error.message})") }
            } catch (error: kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(voiceStatus = "Stopped") }
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "phone turn failed: ${error.javaClass.simpleName}")
                _state.update { it.copy(status = error.message ?: error.javaClass.simpleName) }
            }
        }
    }

    // ── phone hands-free (wake phrase) ───────────────────────────────────────────────────────

    /** Stops ONE pending request (the pending list's Stop); the global Stop (relay, later replies) is separate and unchanged. */
    fun stopPending(turnId: String) {
        if (!app.stopPendingTurn(turnId)) _state.update { it.copy(voiceStatus = "That request already ended") }
    }

    /** Nothing on the Phone owns the microphone or speaker: the wake phrase may listen. */
    fun voiceIdle(): Boolean = captureIdle() &&
        // A later reply holding this Phone's speaker: no wake window over it.
        !app.speakingLater.value

    /**
     * Nothing else records or sends on the Phone: an accepted wake phrase may record or send its
     * request. A later reply does not count: the episode owns the microphone, so that reply was stopped.
     */
    fun captureIdle(): Boolean = !recorder.isRecording && !pushToTalkOpening && captures.activeId == null &&
        !_state.value.voiceBusy && app.phoneTurns.value == 0

    /** A hands-free request is being recorded (a tap on Talk sends it). */
    fun handsFreeCapturing(): Boolean = captures.activeId != null

    fun setWakeListening(open: Boolean) {
        app.foregroundWakeListening = open
        _state.update {
            when {
                open -> it.copy(handsFree = HandsFree.LISTENING)
                it.handsFree == HandsFree.LISTENING -> it.copy(handsFree = HandsFree.IDLE)
                else -> it
            }
        }
    }

    /**
     * Starts the hands-free recorder after a phrase-only wake result. It ends after [silenceMs] of
     * trailing silence (the setting now; a later change applies to the next request), on no
     * speech, or on a tap; a pause or opt-out cancels it unsent ([cancelHandsFree]).
     */
    fun startHandsFree(silenceMs: Long, claimId: String? = null, held: MicrophoneClaim? = null): Boolean {
        if (recorder.isRecording || pushToTalkOpening || _state.value.voiceBusy || app.phoneTurns.value > 0) {
            held?.release()
            return false
        }
        handsFreeClaimId = claimId
        val id = UUID.randomUUID().toString()
        if (!captures.begin(id, TurnTrigger.WAKE_PHRASE)) {
            held?.release()
            return false
        }
        // The wake episode's claim (taken when the phrase was heard), or a new one: BEFORE the microphone opens.
        val microphone = held ?: app.audio.claimMicrophone(VoiceOrigin.PHONE)
        handsFreeMicrophone = microphone
        val capture = PhoneCapture(id, SilenceEndpoint(sampleRate = PhoneCapture.SAMPLE_RATE, silenceMs = silenceMs),
            object : PcmCaptureLoop.Listener {
                override fun onLive() = Unit
                override fun onCalibrated() { main.post { captures.onCalibrated(id) } }
                override fun onEnd(reason: CaptureEnd) { main.post { captures.stop(id, CaptureStop.of(reason)) } }
            })
        handsFreeRecorder = capture
        Log.i(TAG, "phone hands-free capture turn=${id.take(12)} vad_silence_ms=$silenceMs")
        // Opened only once a later reply it stopped has stopped; null: that happens in a moment (or it ends as START_FAILED).
        return microphone.whenSpeakerStopped(viewModelScope) { stopped -> openHandsFree(id, capture, stopped) } ?: true
    }

    private fun openHandsFree(id: String, capture: PhoneCapture, stopped: Boolean): Boolean {
        // Cancelled meanwhile (pause, opt-out, a tap): nothing to open.
        if (captures.activeId != id || handsFreeRecorder !== capture) return false
        if (!stopped || !capture.start()) {
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
    fun sendRecognizedRequest(text: String, claimId: String? = null, held: MicrophoneClaim? = null) {
        if (!captureIdle()) {
            // Something else took the microphone in the meantime: say so instead of dropping it silently.
            Log.i(TAG, "phone recognized request not sent: busy (retry notice)")
            held?.release()
            claimId?.let { id -> runCatching { app.wiring().core.wakeAdmission.release(id, VoiceOrigin.PHONE, "") } }
            return onWakeClosed("unfinished_request")
        }
        recognizedClaimId = claimId
        // The episode's microphone claim goes with the request (later replies wait until it is answered).
        recognizedMicrophone = held
        captures.sendRecognized(UUID.randomUUID().toString(), text)
    }

    fun onWakeClosed(reason: String) {
        val notice = when (reason) {
            "unfinished_request" -> "Didn't catch that. Tap Talk or say it again"
            "request_too_long" -> "That was too long to send as text. Use Talk"
            "unavailable" -> "Wake phrase unavailable on this phone (no speech recognizer)"
            "wake_taken" -> "The Watch answered that wake phrase"
            "wake_claim_timeout", "wake_claim_failed" -> "Couldn't confirm the wake phrase. Say it again"
            "wake_mode_changed" -> "Wake settings changed. Say it again"
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

    /** Phone-owned and durable; applies to voice requests started from now on (a turn in flight keeps its own). */
    fun setRoutingEnabled(on: Boolean) {
        app.settings.routingEnabled = on
        _state.update { it.copy(routingEnabled = on) }
    }

    /**
     * The Phone's background wake standby switch. The request is saved first (and sent to the Watch as part of the shared
     * settings), then the session follows from this visible screen. Off ends the standby session and its scheduled work; it
     * does not cut a recording, an upload, a transfer or playback.
     */
    fun setPhoneBackgroundWake(on: Boolean) {
        val current = _state.value.watch
        if (current.phoneBackgroundWakeEnabled != on) updateWatch(current.copy(phoneBackgroundWakeEnabled = on))
        val status = if (on) app.phoneWake.start() else app.phoneWake.standbyOff()
        Log.i(TAG, "phone background wake standby ${if (on) "on" else "off"} requested notice=${status.notice}")
    }

    /** The Watch's background wake standby switch: saved on the Phone and sent to the Watch (which only mirrors it). */
    fun setWatchBackgroundWake(on: Boolean) {
        val current = _state.value.watch
        if (current.watchBackgroundWakeEnabled != on) updateWatch(current.copy(watchBackgroundWakeEnabled = on))
    }

    /**
     * The Phone's "background wake recognition with screen off" preference: saved with the other settings as one snapshot and
     * sent to the Watch (a mirror). It never turns the standby on, and is kept while the standby is off.
     */
    fun setPhoneBackgroundWakeScreenOff(on: Boolean) {
        val current = _state.value.watch
        if (current.phoneBackgroundWakeScreenOffEnabled != on) updateWatch(current.copy(phoneBackgroundWakeScreenOffEnabled = on))
    }

    /** The Watch's "background wake recognition with screen off" preference: saved on the Phone and sent to the Watch (which only mirrors it). */
    fun setWatchBackgroundWakeScreenOff(on: Boolean) {
        val current = _state.value.watch
        if (current.watchBackgroundWakeScreenOffEnabled != on) updateWatch(current.copy(watchBackgroundWakeScreenOffEnabled = on))
    }

    /** A permission answer came back: background listening re-checks the platform's state. */
    fun onPermissionsChanged() = app.phoneWake.onEligibilityChanged()

    /**
     * The informed opt-in to speak later replies (off by default). Off also ends every follow and any
     * later reply waiting or playing now.
     */
    fun setSpeakLaterReplies(on: Boolean) {
        app.laterConsent.enabled = on
        if (!on) app.stopLaterReplies()
        _state.update { it.copy(speakLater = on) }
    }

    /**
     * Commits a typed later-reply duration (on Done or when the field loses focus). A refused entry is not saved and is explained;
     * the stored value is unchanged. Never touches the later-reply opt-in: the duration is not consent.
     */
    fun commitLaterReplyWindow(text: String, unit: com.rumi.hermesvoice.core.settings.LaterReplyWindow.Unit): Boolean =
        when (val entry = com.rumi.hermesvoice.core.settings.LaterReplyWindow.fromEntry(text, unit)) {
            is com.rumi.hermesvoice.core.settings.LaterReplyWindow.Entry.Valid -> {
                setLaterReplyWindowMinutes(entry.minutes)
                true
            }
            is com.rumi.hermesvoice.core.settings.LaterReplyWindow.Entry.Invalid -> {
                _state.update { it.copy(laterReplyWindowError = entry.reason) }
                false
            }
        }

    /** A preset or a committed value; only a valid minute count is stored. It applies to turns delivered afterwards. */
    fun setLaterReplyWindowMinutes(minutes: Int) {
        val valid = com.rumi.hermesvoice.core.settings.LaterReplyWindow.validOrNull(minutes) ?: return
        if (valid != app.settings.laterReplyWindowMinutes) app.settings.laterReplyWindowMinutes = valid
        _state.update { it.copy(laterReplyWindowMinutes = valid, laterReplyWindowError = null) }
    }

    fun clearLaterReplyWindowError() = _state.update { it.copy(laterReplyWindowError = null) }

    /** Whether a delivered Watch-originated routed request moves the Watch's selection: Phone-owned, saved and sent with the other Watch settings. */
    fun setWatchAutoNavigate(on: Boolean) {
        val current = _state.value.watch
        if (current.watchAutoNavigateToRouted != on) updateWatch(current.copy(watchAutoNavigateToRouted = on))
    }

    fun setAutoNavigate(on: Boolean) {
        app.settings.autoNavigateToRouted = on
        _state.update { it.copy(autoNavigate = on) }
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
        // The wake location may now exclude (or include) this Phone: background listening follows at once.
        app.phoneWake.onEligibilityChanged()
        viewModelScope.launch {
            val result = runCatching { WatchSettingsSync.publish(getApplication(), saved) }
            Log.i(TAG, "voice settings saved+published revision=${saved.revision} wake_location=${saved.wakeLocation} " +
                "vad_silence_s=${saved.vadSilenceSeconds} ok=${result.isSuccess}")
            _state.update { it.copy(status = if (result.isSuccess) "Settings sent to the Watch" else "Watch not reachable; will apply when it syncs") }
        }
    }

    /**
     * The background relay switch (this phone only; not a voice setting and not sent to the Watch).
     * On starts it from this visible screen; off stops it for good.
     */
    fun setBackgroundRelay(on: Boolean) {
        if (on) app.startRelay() else {
            Log.i(TAG, "background relay stop requested (app)")
            app.stopRelay()
        }
    }

    /** True the first time only (kept across restarts): the notification permission is asked once, at the first switch-on. */
    fun askNotificationsOnce(): Boolean = app.askNotificationsOnce()

    fun setWakeLocation(location: WakeLocation) = updateWatch(_state.value.watch.copy(wakeLocation = location))

    /** Only the offered 0.5 s steps are accepted; anything else is ignored. */
    fun setVadSilence(seconds: Double) {
        val valid = VadSilence.validOrNull(seconds) ?: return
        if (valid != _state.value.watch.vadSilenceSeconds) updateWatch(_state.value.watch.copy(vadSilenceSeconds = valid))
    }

    override fun onCleared() {
        recorder.stop()
        pushToTalkMicrophone?.release()
        pushToTalkMicrophone = null
        app.foregroundWakeListening = false
        cancelHandsFree("cleared")
        recognizedMicrophone?.release()
        recognizedMicrophone = null
    }

    companion object {
        const val HISTORY_PAGE = 50
        private const val TAG = "HermesVoice"
    }
}
