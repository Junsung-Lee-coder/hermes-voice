package com.rumi.hermesvoice.phone

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rumi.hermesvoice.core.ChatSendResult
import com.rumi.hermesvoice.core.HermesAuthRequiredException
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.attachments.AttachmentRef
import com.rumi.hermesvoice.core.attachments.FilePreview
import com.rumi.hermesvoice.core.attachments.PreviewKind
import com.rumi.hermesvoice.core.HermesVoiceCore
import com.rumi.hermesvoice.core.activity.ActivityBubble
import com.rumi.hermesvoice.core.activity.ActivityEvent
import com.rumi.hermesvoice.core.activity.ActivityReducer
import com.rumi.hermesvoice.core.activity.ActivityState
import com.rumi.hermesvoice.core.audio.CaptureEnd
import com.rumi.hermesvoice.core.chat.PendingSend
import com.rumi.hermesvoice.core.notify.FinalReply
import com.rumi.hermesvoice.core.diag.DiagClassify
import com.rumi.hermesvoice.core.diag.DiagLabels
import com.rumi.hermesvoice.core.chat.PendingSends
import com.rumi.hermesvoice.core.net.HistoryPage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import com.rumi.hermesvoice.core.chat.ComposerDispatch
import com.rumi.hermesvoice.core.chat.ComposerDraft
import com.rumi.hermesvoice.core.chat.DispatchedSend
import com.rumi.hermesvoice.core.chat.FailedSend
import com.rumi.hermesvoice.core.chat.SendAdmission
import kotlinx.coroutines.CancellationException
import com.rumi.hermesvoice.core.audio.PcmCaptureLoop
import com.rumi.hermesvoice.core.audio.SilenceEndpoint
import com.rumi.hermesvoice.core.background.BackgroundNotice
import com.rumi.hermesvoice.core.background.BackgroundStatus
import com.rumi.hermesvoice.core.headset.HeadsetMediaText
import com.rumi.hermesvoice.core.headset.HeadsetText
import com.rumi.hermesvoice.core.headset.MicPlan
import com.rumi.hermesvoice.core.headset.MicReport
import com.rumi.hermesvoice.core.headset.RecordingCommand
import com.rumi.hermesvoice.core.net.AttachmentPolicy
import com.rumi.hermesvoice.core.net.HistoryMessage
import com.rumi.hermesvoice.core.net.HistoryPages
import com.rumi.hermesvoice.core.net.OutgoingAttachment
import com.rumi.hermesvoice.core.sessions.AppConversation
import com.rumi.hermesvoice.core.sessions.OwnedSession
import com.rumi.hermesvoice.core.sessions.withReadRetries
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PhoneUiState(
    val dashboardUrl: String = "",
    val profile: String = "",
    val signedIn: Boolean = false,
    val status: String = "",
    val recoveryNotice: String? = null,
    val cacheNotice: String? = null,
    val showArchived: Boolean = false,
    val conversations: List<AppConversation> = emptyList(),
    /** Stored ids whose archive or unarchive request is in flight; such a row ignores further gestures. */
    val archiving: Set<String> = emptySet(),
    val selected: OwnedSession? = null,
    val history: List<HistoryMessage> = emptyList(),
    /** Authority provenance of the displayed body, including cached rows; never a file grant. */
    val historyNamespace: String? = null,
    val hasOlder: Boolean = false,
    /** Raw transcript rows fetched so far (the server's offset unit; includes rows we do not display). */
    val rawLoaded: Int = 0,
    /** Bumped when the raw cursor restarts from the newest page; an older read made for an earlier cursor is dropped when it lands. */
    val olderEpoch: Int = 0,
    /** The oldest RAW row id (shown or not) the cursor has walked past; a page that reaches below it is moving toward older rows. */
    val olderBound: Long = Long.MAX_VALUE,
    /** An older page is being read (one at a time per open conversation). */
    val olderLoading: Boolean = false,
    /** The last older-page read failed or returned nothing new; shown with a Retry, never retried by itself. */
    val olderError: Boolean = false,
    val draft: String = "",
    val attachments: List<OutgoingAttachment> = emptyList(),
    /** Text requests dispatched and not yet answered or failed; never blocks the composer. */
    val sendsInFlight: Int = 0,
    /** Dispatched requests that did not reach Hermes, with their content, for retry; never restored over the draft. */
    val failedSends: List<FailedSend> = emptyList(),
    /** Messages shown the instant they were sent, until the conversation's own row for each is loaded (see [PendingSends]). */
    val pendingSends: List<PendingSend> = emptyList(),
    /** The transient "Hermes is working" bubbles, one per in-flight request; never stored, replaced by the final reply. */
    val activity: List<ActivityBubble> = emptyList(),
    /** The attachment being viewed (loading, shown or failed); null when no viewer is open. */
    val viewer: AttachmentViewerState? = null,
    val recording: Boolean = false,
    val voiceStatus: String = "",
    val playFirst: Boolean = false,
    val playMiddle: Boolean = false,
    val watch: WatchSettings = WatchSettings(),
    val themeMode: ThemeMode = ThemeMode.DARK,
    /** This phone's text size as a percent of the Android system font scale (80..140, default 100). */
    val fontScalePercent: Int = PhoneDisplaySettings.DEFAULT,
    /** Where spoken acks and replies play: the device of the latest accepted voice request. */
    val playbackDevice: VoiceOrigin? = null,
    /** Whether the Watch app is reachable over the Data Layer; null until checked. */
    val watchReachable: Boolean? = null,
    /** A Phone voice turn is being SENT (recorded audio on its way, its acknowledgement); waiting for a reply is [pending], not busy. */
    val voiceBusy: Boolean = false,
    /** The accepted voice requests (either device) that are not finished: sending, queued, waiting for a reply or speaking it. */
    val pending: List<com.rumi.hermesvoice.core.voice.PendingTurn> = emptyList(),
    /** Replies whose audio did not play and can be played again from here ("Play again"). */
    val unplayed: List<com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator.UnplayedReply> = emptyList(),
    /** The Phone's hands-free state: listening for the wake phrase, or recording the request after it. */
    val handsFree: HandsFree = HandsFree.IDLE,
    /** Bumped for each recording start/end haptic of a hands-free request (the activity plays it). */
    val hapticTick: Int = 0,
    /** The optional background relay as it really is now (see [PhoneApp.relay]). */
    val relay: BackgroundStatus = BackgroundStatus(false, false, false, BackgroundNotice.OFF),
    /** Android admitted the relay's foreground service (a start that was only asked for does not count). */
    val relayForeground: Boolean = false,
    val androidSdk: Int = android.os.Build.VERSION.SDK_INT,
    /** Voice routing (Settings): on, the router picks the conversation; off, voice goes to [selected]. */
    val routingEnabled: Boolean = true,
    /** Open the routed conversation after delivery (Settings); kept but not applied while routing is off. */
    val autoNavigate: Boolean = false,
    /** Bumped when a routed turn's conversation was opened for the user: the screen shows Chat. */
    val chatOpenRequest: Long = 0,
    /** Bumped when a conversation was created but not opened: the creation dialog closes and the list stays. */
    val createClosedRequest: Long = 0,
    /** Speak later replies (Settings; informed opt-in, off by default). */
    val speakLater: Boolean = false,
    /** Use headset (Settings; Phone-only, off by default): a connected headset takes the Phone's spoken answers and recordings. */
    val useHeadset: Boolean = false,
    /** Which microphone the current or last recording really used, when "Use headset" is on and a headset is connected (null: nothing to say). */
    val micNotice: String? = null,
    /** What the headset play/pause control really is now (null: nothing to say, e.g. "Use headset" is off). Never claims Android delivers the buttons. */
    val headsetControl: String? = null,
    /** How long a delivered turn keeps being followed for later replies (Settings; Phone-owned minutes, 1–4320, default 30). */
    val laterReplyWindowMinutes: Int = com.rumi.hermesvoice.core.settings.LaterReplyWindow.DEFAULT_MINUTES,
    /** Why the last typed later-reply duration was refused (null when it was accepted or nothing was typed). */
    val laterReplyWindowError: String? = null,
    /** The opt-in background listening for the wake phrase as it really is now (see [PhoneBackgroundRuntime]). */
    val phoneWake: com.rumi.hermesvoice.core.background.PhoneWakeStatus? = null,
)

enum class HandsFree { IDLE, LISTENING, GET_READY, SPEAK_NOW }

class PhoneViewModel(application: Application) : AndroidViewModel(application), HeadsetRecordingTarget {
    private val app = PhoneApp.from(application)
    private val main = Handler(Looper.getMainLooper())

    /** The hands-free recorder of the active wake capture; its lifecycle lives in [captures]. */
    private var handsFreeRecorder: PhoneCapture? = null

    /** The wake claim the active hands-free capture was started under ("Both"), or null. */
    private var handsFreeClaimId: String? = null

    private var handsFreeMicrophone: MicrophoneClaim? = null
    private val recorder get() = app.manualCapture.recorder
    private val pushToTalkOpening get() = app.manualCapture.busy && !recorder.isRecording
    private var handsFreePrepare: AutoCloseable? = null
    private var handsFreeLoss: AutoCloseable? = null
    private var handsFreeMicLease: AutoCloseable? = null

    /** Told when a hands-free capture ended: true if it was handed on as a request (with its claim). */
    var onHandsFreeEnded: ((sent: Boolean) -> Unit)? = null

    /** The same capture lifecycle as the Watch's: exactly-once stop, eligibility check, never a partial send. */
    private val captures = CaptureCoordinator(object : CapturePort {
        override fun stopRecorder(captureId: String, reason: CaptureStop): ByteArray? {
            val active = handsFreeRecorder?.takeIf { it.captureId == captureId }
            handsFreeRecorder = null
            runCatching { handsFreePrepare?.close() }
            handsFreePrepare = null
            runCatching { handsFreeLoss?.close() }
            handsFreeLoss = null
            val wav = active?.stop()
            handsFreeMicLease?.close(); handsFreeMicLease = null
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

    /** The one older-page read of the open conversation, and a counter that tells a finished read whether it is still the current one. */
    private var olderJob: Job? = null
    private val olderSeq = AtomicLong(0)

    /** A conversation is being created; a second request meanwhile is ignored. */
    private var creating = false

    /** Bumped for every list refresh and every connection change; a scan of an earlier one is cancelled and never shown. */
    private val listing = AtomicLong(0)
    private val readLog: (String) -> Unit = { Log.i(TAG, it) }
    private var listJob: Job? = null
    private var displayedListScope: String? = app.sessionListScope()

    /** Bumped by a changed connection or a sign-out; an archive request of an earlier one never touches the state again. */
    private val connectionEpoch = AtomicLong(0)
    private val archivingIds = HashSet<String>()

    /** Unsent text and attachments of conversations switched away from, so changing conversations never loses them. */
    private val drafts = HashMap<String, Pair<String, List<OutgoingAttachment>>>()

    private val sendScreen = object : TextSends.Screen {
        override fun accepted(send: DispatchedSend, origin: PhoneApp.AsyncActionOrigin) {
            viewModelScope.launch { if (app.asyncActionCurrent(origin)) onSendAccepted(send) }
        }

        override fun finished(send: DispatchedSend, result: ChatSendResult, origin: PhoneApp.AsyncActionOrigin) {
            viewModelScope.launch { onSendFinished(send, result, origin) }
        }
    }

    private val display = object : ConversationDisplay {
        override suspend fun confirmArrival(reply: FinalReply): Boolean {
            val text = reply.text?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            // A pre-existing equal answer is not evidence that this final was displayed. The
            // supported local row cursor proves only a positively new latest row; without one,
            // keep the conservative exact-identity alert (while still refreshing the real view).
            val before = _state.value.takeIf { it.selected?.storedSessionId == reply.storedSessionId }
                ?.history?.maxOfOrNull { it.rowId }
            val page = refreshVisible(reply.storedSessionId) ?: return false
            return before != null && page.messages.any { it.rowId > before && it.role == "assistant" && it.text.trim() == text }
        }

        override suspend fun confirmOpened(storedSessionId: String): Boolean = refreshVisible(storedSessionId) != null
    }

    init {
        // A request still waiting (or failed) when the screen before this one went away is shown again, and its outcome comes here.
        val sends = app.textSends.attach(sendScreen) { app.asyncActionCurrent(it) }
        if (sends.active.isNotEmpty() || sends.failed.isNotEmpty()) {
            _state.update {
                it.copy(pendingSends = sends.active, sendsInFlight = sends.active.size, failedSends = sends.failed,
                    status = if (sends.active.isNotEmpty()) "Sending…" else it.status)
            }
        }
        app.conversationDisplay = display
        ViewedFiles.clear(app)
        viewModelScope.launch { app.finalRecovery.revision.collect {
            _state.update { it.copy(conversations = app.finalRecovery.decorate(it.conversations)) }
        } }
        viewModelScope.launch { app.finalRecovery.notice.collect { note -> _state.update { it.copy(recoveryNotice = note) } } }
        viewModelScope.launch { app.cacheNotice.collect { note -> _state.update { it.copy(cacheNotice = note) } } }
        viewModelScope.launch { app.playbackDevice.collect { device -> _state.update { it.copy(playbackDevice = device) } } }
        // "Open the routed conversation": only requests made while this screen exists (never a replayed old one).
        viewModelScope.launch { app.routedOpen.collect { storedSessionId -> openRouted(storedSessionId) } }
        // A tapped arrival alert: open exactly that conversation once this screen is signed in (kept until then; not the routed preference).
        viewModelScope.launch {
            combine(app.pendingOpen, app.pendingOpenTaps, _state.map { it.signedIn }.distinctUntilChanged()) { id, _, signedIn -> id.takeIf { signedIn } }
                .collect { id -> if (id != null) openFromReplyAlert(id) }
        }
        // A voice turn (from the Phone or the Watch) created a conversation: show it.
        viewModelScope.launch { app.conversationsCreated.collect { count -> if (count > 0 && _state.value.signedIn) refresh() } }
        viewModelScope.launch { app.relayStatus.collect { relay -> _state.update { it.copy(relay = relay) } } }
        viewModelScope.launch { app.relayForeground.collect { admitted -> _state.update { it.copy(relayForeground = admitted) } } }
        viewModelScope.launch { app.phoneWake.status.collect { wake -> _state.update { it.copy(phoneWake = wake, watch = app.settings.watchSettings()) } } }
        // A request heard with the app closed: its result shows here when the app is opened.
        viewModelScope.launch { app.phoneWake.notice.collect { line -> if (line != null) _state.update { it.copy(voiceStatus = line) } } }
        viewModelScope.launch { app.phoneWake.micNotice.collect { line -> if (line != null) _state.update { it.copy(micNotice = line) } } }
        // A later reply of a delivered request (a background completion) was spoken, or couldn't be.
        viewModelScope.launch { app.laterReplies.collect { line -> if (line != null) _state.update { it.copy(voiceStatus = line) } } }
        // Phone voice turns run in the application, so one started before this screen was (re)created still counts as busy.
        viewModelScope.launch { app.phoneTurns.collect { running -> _state.update { it.copy(voiceBusy = running > 0) } } }
        viewModelScope.launch { app.pendingTurns.collect { list -> _state.update { it.copy(pending = list) } } }
        viewModelScope.launch { app.unplayedReplies.collect { list -> _state.update { it.copy(unplayed = list) } } }
        viewModelScope.launch { app.headsetMedia.status.collect { status -> _state.update { it.copy(headsetControl = headsetControlLine(status)) } } }
        app.existingWiring()?.let { attachActivity(it.core) }
        refreshWatchStatus()
        app.headsetMedia.refresh()
        app.connectionCue.refresh()
        viewModelScope.launch {
            app.manualCapture.state.collect { capture ->
                _state.update { it.copy(recording = capture.recording,
                    voiceStatus = capture.voiceStatus.ifEmpty { it.voiceStatus }, micNotice = capture.micNotice) }
            }
        }
    }

    // ── transient activity (the receiving bubble) ────────────────────────────────────────────

    /** Only touched on the main thread. */
    private var activityState = ActivityState()
    private var activityCore: HermesVoiceCore? = null
    private var activityJob: Job? = null

    /** Subscribes to [core]'s activity feed BEFORE any request is made through it, then seeds from what is already in flight. */
    private fun attachActivity(core: HermesVoiceCore) {
        if (activityCore === core) return
        activityJob?.cancel()
        activityCore = core
        activityJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            core.activity.events.collect { onActivityEvent(it) }
        }
        val snapshot = core.activity.snapshot()
        activityState = ActivityState(snapshot.bubbles, snapshot.closed)
        _state.update { it.copy(activity = activityState.bubbles) }
    }

    private fun onActivityEvent(event: ActivityEvent) {
        val before = activityState
        val after = ActivityReducer.apply(before, event)
        if (after == before) return
        activityState = after
        _state.update { it.copy(activity = after.bubbles) }
        if (event is ActivityEvent.End && event.replied) {
            after.bubbles.firstOrNull { it.key == event.key && it.closing }?.let { finishClosing(it.sessionId) }
        }
    }

    private fun releaseClosing(sessionId: String) {
        val released = ActivityReducer.release(activityState, sessionId)
        if (released === activityState) return
        activityState = released
        _state.update { it.copy(activity = released.bubbles) }
    }

    /**
     * The final reply of a request arrived: its bubble stays until that reply is in the history shown (re-read a few times, as
     * the row may land a moment after the event), then is released in the same step the history is replaced; never earlier.
     */
    private fun finishClosing(sessionId: String) {
        if (_state.value.selected?.storedSessionId != sessionId) {
            releaseClosing(sessionId)
            return
        }
        val baseline = PendingSends.baseline(_state.value.history)
        val generation = selection.get()
        val wiring = app.existingWiring() ?: return
        val origin = actionOrigin(wiring)
        viewModelScope.launch {
            for ((attempt, wait) in FINAL_LOOKUP_WAITS_MS.withIndex()) {
                if (wait > 0) delay(wait)
                if (!actionCurrent(origin)) break
                if (selection.get() != generation || _state.value.selected?.storedSessionId != sessionId) break
                val page = try {
                    wiring.core.sessions.history(sessionId, limit = HISTORY_PAGE)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    continue
                }
                val done = page.messages.any { it.role != "user" && it.rowId > baseline } || attempt == FINAL_LOOKUP_WAITS_MS.lastIndex
                if (!mergeLatest(sessionId, generation, page, release = done, origin = origin)) break
                if (done) return@launch
            }
            releaseClosing(sessionId)
        }
    }

    /** Merges a freshly read newest page into the conversation on screen; false when another conversation is on screen now. */
    private suspend fun mergeLatest(sessionId: String, generation: Long, page: HistoryPage, release: Boolean, origin: ActionOrigin): Boolean {
        if (!actionCurrent(origin)) return false
        if (selection.get() != generation || _state.value.selected?.storedSessionId != sessionId) return false
        app.finalRecovery.observedNewest(page, app.bodyScope())
        if (!actionCurrent(origin) || selection.get() != generation) return false
        val scope = app.sessionListScope()
        app.recentBodies.remember(page, page.returned >= HISTORY_PAGE) {
            app.sessionListScope() == scope && selection.get() == generation && actionCurrent(origin)
        }
        val released = if (release) ActivityReducer.release(activityState, sessionId) else activityState
        _state.update { current ->
            if (selection.get() != generation || current.selected?.storedSessionId != sessionId) return@update current
            val prior = current.history.takeIf { current.historyNamespace == origin.namespace }.orEmpty()
            val merged = HistoryPages.refreshLatest(prior, page.messages)
            // The cursor carries on only when the newest page shares a row with what is shown (nothing lies between them); otherwise
            // rows may have arrived in between, so the walk toward older rows starts again from the newest page.
            val shown = prior.mapTo(HashSet()) { it.rowId }
            val joined = merged.size > page.messages.size && page.messages.any { it.rowId in shown }
            current.copy(history = merged, historyNamespace = origin.namespace,
                pendingSends = PendingSends.reconcile(current.pendingSends, sessionId, merged),
                activity = if (release) released.bubbles else current.activity,
                hasOlder = if (joined) current.hasOlder else page.returned >= HISTORY_PAGE,
                rawLoaded = if (joined) maxOf(current.rawLoaded, page.returned) else page.returned,
                olderEpoch = if (joined) current.olderEpoch else current.olderEpoch + 1,
                olderBound = if (joined) minOf(current.olderBound, page.rawFloor() ?: Long.MAX_VALUE) else page.rawFloor() ?: Long.MAX_VALUE)
        }
        if (release) activityState = released
        return true
    }

    private fun headsetControlLine(status: MediaControlStatus): String? = when (status) {
        MediaControlStatus.OFF -> null
        MediaControlStatus.NO_HEADSET -> HeadsetMediaText.WAITING_FOR_HEADSET
        MediaControlStatus.APP_CLOSED -> HeadsetMediaText.APP_CLOSED
        MediaControlStatus.UNAVAILABLE -> HeadsetMediaText.UNAVAILABLE
        MediaControlStatus.REGISTERED -> HeadsetMediaText.REGISTERED
    }

    private fun initialState(): PhoneUiState {
        val cached = app.cachedSessionTitles(archived = false)
        return PhoneUiState(
        conversations = app.finalRecovery.decorate(cached),
        status = if (cached.isEmpty()) "" else "Saved conversations · refreshing…",
        dashboardUrl = app.settings.dashboardUrl,
        profile = app.settings.profile,
        signedIn = app.tokens.load() != null,
        playFirst = app.settings.playFirstResponse,
        playMiddle = app.settings.playMiddleResponses,
        watch = app.settings.watchSettings(),
        themeMode = app.settings.themeMode,
        fontScalePercent = app.display.fontScalePercent,
        routingEnabled = app.settings.routingEnabled,
        autoNavigate = app.settings.autoNavigateToRouted,
        speakLater = app.laterConsent.enabled,
        useHeadset = app.settings.useHeadset,
        laterReplyWindowMinutes = app.settings.laterReplyWindowMinutes,
        )
    }

    private fun wiringOrStatus(): PhoneApp.Wiring? = try {
        app.wiring().also { attachActivity(it.core) }
    } catch (error: IllegalArgumentException) {
        _state.update { it.copy(status = error.message ?: "Dashboard URL is invalid") }
        null
    }

    private data class ActionOrigin(val wiring: PhoneApp.Wiring, val scope: String?, val url: String,
        val profile: String, val connection: Long, val namespace: String?)

    private fun actionOrigin(wiring: PhoneApp.Wiring) = ActionOrigin(wiring, app.sessionListScope(),
        app.settings.dashboardUrl, app.settings.profile, connectionEpoch.get(), wiring.namespace)

    private fun actionCurrent(origin: ActionOrigin, authLost: Boolean = false): Boolean {
        if (app.existingWiring() !== origin.wiring || app.settings.dashboardUrl != origin.url ||
            app.settings.profile != origin.profile || connectionEpoch.get() != origin.connection) return false
        val scope = app.sessionListScope()
        // A current rejected refresh clears its tokens before throwing. The displayed scope is
        // used only for that null transition, never as the provenance of an independent action.
        return (scope == origin.scope && app.bodyScope() == origin.namespace) || (authLost && scope == null && displayedListScope == origin.scope)
    }

    private fun launchGuarded(block: suspend (PhoneApp.Wiring, ActionOrigin) -> Unit) {
        val wiring = wiringOrStatus() ?: return
        val origin = actionOrigin(wiring)
        viewModelScope.launch {
            try {
                block(wiring, origin)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportActionFailure(error, origin)
            }
        }
    }

    private fun reportActionFailure(error: Exception, origin: ActionOrigin) {
        if (!actionCurrent(origin, authLost = error is HermesAuthRequiredException)) return
        if (error is HermesAuthRequiredException) {
            app.invalidateSessionTitles()
            listing.incrementAndGet()
            listJob?.cancel()
            displayedListScope = null
            selection.incrementAndGet()
            cancelOlder()
            app.selectedConversationId = null
            _state.update { it.copy(signedIn = false, conversations = emptyList(), selected = null, history = emptyList(),
                hasOlder = false, rawLoaded = 0, status = "Sign in to Hermes (${error.message})") }
            return
        }
        val classified = DiagClassify.of(error)
        Log.w(TAG, "action failed cause=${classified.cause.wire}")
        _state.update { it.copy(status = "Couldn't do that: ${DiagLabels.cause(classified.cause)}") }
    }

    // ── connection & auth ────────────────────────────────────────────────────────────────────

    private val loginAttempt = AtomicLong(0)

    fun saveConnection(url: String, profile: String) {
        val changed = url.trim() != app.settings.dashboardUrl || profile.trim() != app.settings.profile
        if(changed) loginAttempt.incrementAndGet()
        // The cached answers belong to the identity that heard them: forgotten before the new one is wired.
        val cacheCleared = if (changed) app.revokeUnplayedReplies() else true
        app.settings.dashboardUrl = url
        app.settings.profile = profile
        // A bearer pair belongs to the dashboard that minted it; never replay it elsewhere.
        if (changed) { closeViewer(); app.invalidateSessionTitles(); app.manualCapture.cancel("Connection changed"); app.connectionCue.cancel(); app.tokens.clear() }
        displayedListScope = app.sessionListScope()
        selection.incrementAndGet()
        listing.incrementAndGet()
        listJob?.cancel()
        endArchiving()
        app.selectedConversationId = null
        _state.update { it.copy(dashboardUrl = app.settings.dashboardUrl, profile = app.settings.profile,
            signedIn = app.tokens.load() != null, selected = null, history = emptyList(), conversations = emptyList(), archiving = emptySet(),
            pendingSends = if (changed) emptyList() else it.pendingSends, failedSends = if (changed) emptyList() else it.failedSends,
            sendsInFlight = if (changed) 0 else it.sendsInFlight,
            status = if (cacheCleared) "Saved" else "Saved. $UNPLAYED_NOT_CLEARED") }
    }

    fun signIn() {
        if (wiringOrStatus() == null) return
        val attempt = loginAttempt.incrementAndGet()
        val previousNamespace = app.bodyScope()
        viewModelScope.launch {
            _state.update { it.copy(status = "Complete sign-in in the browser…") }
            val message = try { runNativeSignIn(getApplication()) }
                catch(cancelled: CancellationException) { throw cancelled }
                catch(error: Exception) { "Sign-in failed: ${error.message}" }
            if(loginAttempt.get()!=attempt) return@launch
            if(app.tokens.load()!=null && app.bodyScope()!=previousNamespace) {
                selection.incrementAndGet();cancelOlder();closeViewer();drafts.clear()
                _state.update { it.copy(selected=null,history=emptyList(),historyNamespace=null,conversations=emptyList(),
                    draft="",attachments=emptyList(),pendingSends=emptyList(),failedSends=emptyList()) }
            }
            _state.update { it.copy(status = message, signedIn = app.tokens.load() != null) }
            if (app.tokens.load() != null) refresh()
        }
    }

    fun signOut() {
        loginAttempt.incrementAndGet()
        closeViewer()
        selection.incrementAndGet()
        cancelOlder()
        val cacheCleared = app.revokeUnplayedReplies()
        app.invalidateSessionTitles()
        app.tokens.clear()
        displayedListScope = null
        app.manualCapture.cancel("Signed out")
        app.connectionCue.cancel()
        app.phoneWake.onSignedOut()
        listing.incrementAndGet()
        listJob?.cancel()
        endArchiving()
        _state.update { it.copy(signedIn = false, conversations = emptyList(), archiving = emptySet(), selected = null, history = emptyList(),
            pendingSends = emptyList(), failedSends = emptyList(), sendsInFlight = 0,
            status = if (cacheCleared) "Signed out on this phone" else "Signed out on this phone. $UNPLAYED_NOT_CLEARED") }
    }

    // ── sessions ─────────────────────────────────────────────────────────────────────────────

    /**
     * Reads the dashboard's sessions page by page. Each finished page is shown at once; a newer refresh, a switch between the
     * active and archived list, a changed connection or a sign-out ends the scan before it, so its late pages are never shown.
     */
    fun refresh() {
        val wiring = wiringOrStatus() ?: return
        val origin = actionOrigin(wiring)
        val scope = app.sessionListScope()
        if (scope != displayedListScope) {
            app.invalidateSessionTitles()
            displayedListScope = scope
            _state.update { it.copy(conversations = emptyList()) }
        }
        val cacheVersion = app.sessionTitlesVersion
        val generation = listing.incrementAndGet()
        val archived = _state.value.showArchived
        val cacheRequest = app.beginSessionTitlesRefresh(archived)
        listJob?.cancel()
        val url = app.settings.dashboardUrl
        val profile = app.settings.profile
        fun requestCurrent() = listing.get() == generation && _state.value.showArchived == archived &&
            app.sessionTitlesVersion == cacheVersion && app.isSessionTitlesRefreshCurrent(archived, cacheRequest) &&
            app.settings.dashboardUrl == url && app.settings.profile == profile && app.existingWiring() === wiring &&
            viewModelScope.coroutineContext[Job]?.isActive != false
        fun current() = requestCurrent() && app.sessionListScope() == scope
        _state.update { it.copy(status = "Reading the dashboard's sessions…") }
        listJob = viewModelScope.launch {
            try {
                val list = withReadRetries(lines = readLog, stillCurrent = ::current) { wiring.core.sessions.listConversations(archived = archived) { progress ->
                    if (current()) {
                        revokeDisplayed(progress.removedIds)
                        val rows = mergeConversations(progress.conversations, _state.value.conversations.filterNot { row -> row.owned.storedSessionId in progress.removedIds })
                        if (app.rememberSessionTitles(scope, cacheVersion, archived, rows, ::current) != null && current()) {
                            _state.update { it.copy(conversations = rows,
                                status = "Reading the dashboard's sessions… ${progress.scanned} of ${progress.total}") }
                        }
                    }
                } }
                if (!current()) return@launch
                revokeDisplayed(list.removedIds)
                // Reconciliation is app-owned recovery, not the list/creation status transaction.
                val recoveryScope = app.bodyScope()
                app.appScope.launch { app.finalRecovery.reconcile(list, recoveryScope) }
                if (!current()) return@launch
                val note = listOfNotNull(
                    "Only ${list.scanned} of ${list.total} sessions on the dashboard could be read${list.error?.let { " ($it)" } ?: ""}, so this list may be missing some."
                        .takeIf { !list.complete },
                    "${list.notAdopted} marked session(s) were not added (no free place or alias, or an unusable id).".takeIf { list.notAdopted > 0 },
                ).joinToString(" ")
                _state.update {
                    it.copy(conversations = if (list.complete) mergeConversations(list.conversations, emptyList())
                        else mergeConversations(list.conversations, it.conversations.filterNot { row -> row.owned.storedSessionId in list.removedIds }), status = note)
                }
                val saved = app.saveSessionTitles(scope, cacheVersion, archived, _state.value.conversations, ::current)
                if (saved == false && current()) _state.update { it.copy(status = listOf(note, "List not saved for next start").filter(String::isNotBlank).joinToString(" ")) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: HermesAuthRequiredException) {
                // Rejected refresh clears credentials before throwing. Null is observed auth loss,
                // not permission to discard this current request's sign-in-required result.
                if (requestCurrent() && (app.sessionListScope() == scope || app.sessionListScope() == null)) {
                    reportActionFailure(error, origin)
                }
            } catch (error: Exception) {
                if (current()) {
                    val classified = DiagClassify.of(error)
                    Log.w(TAG, "action failed cause=${classified.cause.wire}")
                    _state.update { it.copy(status = "Couldn't do that: ${DiagLabels.cause(classified.cause)}") }
                }
            }
        }
    }

    private fun mergeConversations(found: List<AppConversation>, previous: List<AppConversation>): List<AppConversation> {
        val ids = HashSet<String>()
        return app.finalRecovery.decorate((found + previous).filter { ids.add(it.owned.storedSessionId) })
    }

    private fun revokeDisplayed(ids: Set<String>) {
        app.finalRecovery.remove(ids)
        if (_state.value.selected?.storedSessionId in ids) {
            selection.incrementAndGet(); cancelOlder(); app.selectedConversationId = null
            _state.update { it.copy(selected = null, history = emptyList()) }
        }
    }

    fun contentDisplayed(sessionId: String, rows: List<HistoryMessage>, namespace: String? = _state.value.historyNamespace) {
        val shown=_state.value
        if(namespace==null || namespace!=app.bodyScope() || namespace!=shown.historyNamespace || shown.selected?.storedSessionId!=sessionId) return
        app.finalRecovery.displayed(sessionId,rows.filter { row -> shown.history.any { it.rowId==row.rowId && it.messageUid==row.messageUid && it.text==row.text } })
    }

    fun setShowArchived(show: Boolean) {
        _state.update { it.copy(showArchived = show, conversations = app.cachedSessionTitles(archived = show)) }
        refresh()
    }

    /** Creates one conversation and opens it; [onDone] says whether it was created (on false the status line holds the reason). */
    fun createConversation(title: String, alias: String, description: String, onDone: (Boolean) -> Unit = {}) {
        if (creating) {
            _state.update { it.copy(status = "A conversation is already being created") }
            onDone(false)
            return
        }
        val wiring = wiringOrStatus()
        if (wiring == null) {
            onDone(false)
            return
        }
        val origin = actionOrigin(wiring)
        creating = true
        viewModelScope.launch {
            var created = false
            var currentFailure = false
            try {
                val owned = wiring.core.sessions.createConversation(title, alias, description)
                created = true
                _state.update { it.copy(status = "Created '${owned.title}' (voice alias: ${owned.alias})") }
                refresh()
                val generation = selection.incrementAndGet()
                cancelOlder()
                val shown = try {
                    showNewest(wiring, origin, owned, generation) {
                        onUserNavigated()
                        _state.update { it.copy(chatOpenRequest = it.chatOpenRequest + 1) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (!actionCurrent(origin, authLost = error is HermesAuthRequiredException)) return@launch
                    currentFailure = true
                    reportCreatedNotOpened(owned, error, origin)
                    false
                }
                if (!shown && (currentFailure || actionCurrent(origin))) _state.update { it.copy(createClosedRequest = it.createClosedRequest + 1) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentFailure = actionCurrent(origin, authLost = error is HermesAuthRequiredException)
                reportActionFailure(error, origin)
            } finally {
                creating = false
            }
            if (currentFailure || actionCurrent(origin)) onDone(created)
        }
    }

    /** The conversation exists but its history could not be read: say so, and leave it in the list to be opened. */
    private fun reportCreatedNotOpened(owned: OwnedSession, error: Exception, origin: ActionOrigin) {
        if (error is HermesAuthRequiredException) {
            reportActionFailure(error, origin)
            return
        }
        if (!actionCurrent(origin)) return
        Log.w(TAG, "open after create failed cause=${DiagClassify.of(error).cause.wire}")
        _state.update { it.copy(status = "Created '${owned.title}' (voice alias: ${owned.alias}), but couldn't open it. Tap it in the list to try again.") }
    }

    private fun endArchiving() {
        connectionEpoch.incrementAndGet()
        synchronized(archivingIds) { archivingIds.clear() }
    }

    private fun publishArchiving() {
        val ids = synchronized(archivingIds) { archivingIds.toSet() }
        _state.update { it.copy(archiving = ids) }
    }

    /** Archives or restores [session] through the dashboard; a second request for a row whose request is still running is ignored. */
    fun setArchived(session: OwnedSession, archived: Boolean) {
        val wiring = wiringOrStatus() ?: return
        val origin = actionOrigin(wiring)
        val id = session.storedSessionId
        val epoch = connectionEpoch.get()
        if (!synchronized(archivingIds) { archivingIds.add(id) }) return
        publishArchiving()
        viewModelScope.launch {
            try {
                wiring.core.sessions.setArchived(id, archived)
                if (connectionEpoch.get() != epoch) return@launch
                if (archived && _state.value.selected?.storedSessionId == id) {
                    selection.incrementAndGet()
                    app.selectedConversationId = null
                    _state.update { it.copy(selected = null, history = emptyList()) }
                }
                _state.update { state ->
                    if (state.showArchived == archived) state
                    else state.copy(conversations = state.conversations.filterNot { it.owned.storedSessionId == id })
                }
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (connectionEpoch.get() == epoch) reportActionFailure(error, origin)
            } finally {
                if (connectionEpoch.get() == epoch) {
                    synchronized(archivingIds) { archivingIds.remove(id) }
                    publishArchiving()
                }
            }
        }
    }

    fun updateAlias(session: OwnedSession, alias: String, description: String) = launchGuarded { wiring, _ ->
        wiring.core.sessions.updateDestination(session.storedSessionId, alias, description)
        refresh()
    }

    /**
     * Shows [session] from its newest page. A read that lands after another conversation was
     * selected is dropped. The unsent draft is kept per conversation (the one switched away from
     * is stashed, the one opened gets its own back).
     */
    fun open(session: OwnedSession, afterShown: () -> Unit = {}) {
        val generation = selection.incrementAndGet()
        cancelOlder()
        // Never replace a richer live reader/pagination cursor with its bounded saved newest page.
        if (_state.value.selected?.storedSessionId != session.storedSessionId || _state.value.history.isEmpty()) app.recentBodies.load(session.storedSessionId)?.let { saved ->
            val current = _state.value
            val (draft, attachments) = if (current.selected?.storedSessionId == session.storedSessionId) current.draft to current.attachments else {
                current.selected?.let { drafts[it.storedSessionId] = current.draft to current.attachments }
                drafts.remove(session.storedSessionId) ?: ("" to emptyList())
            }
            _state.update { it.copy(selected = session, history = saved.page.messages, historyNamespace = app.bodyScope(), hasOlder = saved.hasOlder,
                rawLoaded = saved.page.returned, olderBound = saved.page.rawFloor() ?: Long.MAX_VALUE,
                olderEpoch = it.olderEpoch + 1, draft = draft, attachments = attachments,
                olderLoading = false, olderError = false) }
        }
        launchGuarded { wiring, origin -> showNewest(wiring, origin, session, generation, afterShown) }
    }

    /** Reads and shows [session]'s newest page; false when another conversation was selected meanwhile (nothing is shown then). */
    private suspend fun showNewest(wiring: PhoneApp.Wiring, origin: ActionOrigin, session: OwnedSession, generation: Long, afterShown: () -> Unit): Boolean {
        val page = try {
            withReadRetries(lines = readLog, stillCurrent = { actionCurrent(origin) && selection.get() == generation }) {
                wiring.core.sessions.history(session.storedSessionId, limit = HISTORY_PAGE)
            }
        } catch (error: com.rumi.hermesvoice.core.SessionNotOwnedException) {
            if (actionCurrent(origin) && selection.get() == generation) {
                app.recentBodies.invalidate()
                _state.update { if (it.selected?.storedSessionId == session.storedSessionId) it.copy(selected = null, history = emptyList()) else it }
            }
            throw error
        }
        if (!actionCurrent(origin)) return false
        if (selection.get() != generation) return false
        app.finalRecovery.observedNewest(page, app.bodyScope())
        if (!actionCurrent(origin) || selection.get() != generation) return false
        app.recentBodies.eligible(session.storedSessionId)
        app.recentBodies.remember(page, page.returned >= HISTORY_PAGE) { actionCurrent(origin) && selection.get() == generation }
        // On the main thread, like every draft edit: the stash is never touched inside a retried update.
        val current = _state.value
        val (draft, attachments) = if (current.selected?.storedSessionId == session.storedSessionId) current.draft to current.attachments
        else {
            current.selected?.let { drafts[it.storedSessionId] = current.draft to current.attachments }
            drafts.remove(session.storedSessionId) ?: ("" to emptyList())
        }
        _state.update {
            it.copy(selected = session, historyNamespace = origin.namespace, history = if (it.selected?.storedSessionId == session.storedSessionId && it.historyNamespace == origin.namespace) HistoryPages.refreshLatest(it.history, page.messages) else page.messages, hasOlder = page.returned >= HISTORY_PAGE,
                pendingSends = PendingSends.reconcile(it.pendingSends, session.storedSessionId, page.messages),
                rawLoaded = page.returned, olderEpoch = it.olderEpoch + 1,
                olderBound = page.rawFloor() ?: Long.MAX_VALUE,
                draft = draft, attachments = attachments, olderLoading = false, olderError = false)
        }
        // With routing off, a voice request made with the app closed goes to the conversation open here.
        app.selectedConversationId = session.storedSessionId
        afterShown()
        return true
    }

    /**
     * Re-reads the newest page of the conversation on screen (after a send or a voice turn): new
     * and grown rows replace theirs, older rows already loaded stay, and the draft is untouched.
     * Dropped if another conversation was selected meanwhile.
     */
    private fun reloadSelected() {
        val session = _state.value.selected ?: return
        val generation = selection.get()
        launchGuarded { wiring, origin ->
            val page = withReadRetries(lines = readLog, stillCurrent = { actionCurrent(origin) && selection.get() == generation }) {
                wiring.core.sessions.history(session.storedSessionId, limit = HISTORY_PAGE)
            }
            mergeLatest(session.storedSessionId, generation, page, release = false, origin = origin)
        }
    }

    /** Shows [session]; when it is already the open conversation its loaded history and place stay and only the newest page is refreshed. */
    fun openOrResume(session: OwnedSession) {
        if (_state.value.selected?.storedSessionId == session.storedSessionId) reloadSelected() else open(session)
    }

    private fun cancelOlder() {
        olderJob?.cancel()
        olderJob = null
        olderSeq.incrementAndGet()
        _state.update { if (it.olderLoading || it.olderError) it.copy(olderLoading = false, olderError = false) else it }
    }

    private enum class OlderStep { ADDED, SKIPPED, END, STALLED, RACED, STALE, SUSPECT }

    /** A full page of rows all shown already that did not reach below [PhoneUiState.olderBound]: held, uncommitted, until the next page shows the server moved on. */
    private class HeldOlder(val page: HistoryPage, val base: Int, val epoch: Int)

    private fun HistoryPage.rawRows(): List<Long> = rawIds.takeIf { it.size == returned } ?: messages.map { it.rowId }

    private fun HistoryPage.rawFloor(): Long? = rawRows().minOrNull()

    /**
     * Reads the next older page of the open conversation by the RAW row offset ([PhoneUiState.rawLoaded]: hidden, tool and seed rows
     * count, the number of rows shown does not). One read at a time; a page without a shown message moves on to the next raw page (a
     * few at most), so earlier messages are never stranded behind rows that are not displayed. Rows that arrive between reads push
     * the same raw offset toward newer rows, so a page of rows all shown already is not proof that the server stood still: it is
     * held until the next page differs from it (the server is moving through the rows) and only then passed; a next page that is
     * the same again is an error with a Retry, never a loop. A read for a conversation that is no longer open is dropped.
     */
    fun loadOlder() {
        val start = _state.value
        val sessionId = start.selected?.storedSessionId ?: return
        if (!start.hasOlder || olderJob?.isActive == true) return
        val wiring = wiringOrStatus() ?: return
        val origin = actionOrigin(wiring)
        val generation = selection.get()
        val seq = olderSeq.incrementAndGet()
        _state.update { it.copy(olderLoading = true, olderError = false) }
        olderJob = viewModelScope.launch {
            try {
                var steps = 0
                var held: HeldOlder? = null
                while (steps < MAX_OLDER_STEPS) {
                    steps += 1
                    val cursor = _state.value
                    val base = cursor.rawLoaded
                    val pending = held?.takeIf { it.base == base && it.epoch == cursor.olderEpoch }?.page
                    val offset = base + (pending?.returned ?: 0)
                    val page = wiring.core.sessions.history(sessionId, limit = HISTORY_PAGE, offset = offset)
                    when (applyOlderPage(sessionId, generation, base, cursor.olderEpoch, page, pending)) {
                        OlderStep.SUSPECT -> held = HeldOlder(page, offset, cursor.olderEpoch)
                        OlderStep.SKIPPED, OlderStep.RACED -> held = null
                        else -> return@launch
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (selection.get() == generation && olderSeq.get() == seq) {
                    if (error is HermesAuthRequiredException) reportActionFailure(error, origin)
                    else _state.update { it.copy(olderError = true) }
                }
            } finally {
                if (olderSeq.get() == seq) _state.update { it.copy(olderLoading = false) }
            }
        }
    }

    /** [base] is the cursor the walk started from; [pending] is the page held at [base] (read there, not yet passed), if any. */
    private fun applyOlderPage(sessionId: String, generation: Long, base: Int, epoch: Int, page: HistoryPage, pending: HistoryPage?): OlderStep {
        var step = OlderStep.STALE
        _state.update { current ->
            step = OlderStep.STALE
            if (selection.get() != generation || current.selected?.storedSessionId != sessionId) return@update current
            if (current.rawLoaded != base || current.olderEpoch != epoch) {
                step = OlderStep.RACED
                return@update current
            }
            // New rows may have landed since the first page; rows already shown win, the others join in row-id order.
            val known = current.history.mapTo(HashSet()) { it.rowId }
            val fresh = page.messages.filter { it.rowId !in known }
            val floor = page.rawFloor()
            if (fresh.isEmpty() && floor != null && floor >= current.olderBound && page.returned >= HISTORY_PAGE) {
                // Nothing new and nothing older than what was passed: the rows that arrived since can shift this offset onto rows
                // already read (an honest page), or the server is repeating itself. The next page of this same read tells which; this
                // one waits for it, and nothing from an earlier read decides it.
                // (A short page of such rows is the end of the transcript: nothing older than the oldest row passed is left.)
                if (pending != null && page.rawRows().toSet() == pending.rawRows().toSet()) {
                    step = OlderStep.STALLED
                    return@update current.copy(olderError = true)
                }
                step = OlderStep.SUSPECT
                return@update if (pending == null) current else current.copy(rawLoaded = base + pending.returned)
            }
            val more = page.returned >= HISTORY_PAGE
            step = if (fresh.isNotEmpty()) OlderStep.ADDED else if (more) OlderStep.SKIPPED else OlderStep.END
            val combined = if (fresh.isEmpty()) current.history else (current.history + fresh).sortedBy { it.rowId }
            current.copy(history = combined, pendingSends = PendingSends.reconcile(current.pendingSends, sessionId, combined),
                hasOlder = more, rawLoaded = base + (pending?.returned ?: 0) + page.returned,
                olderBound = minOf(current.olderBound, floor ?: Long.MAX_VALUE))
        }
        return step
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

    /**
     * A tapped arrival alert's conversation: opened if it is one of this app's own (anything else is dropped). The request is
     * forgotten only once the conversation is really shown (its history read), so a failed read leaves it for the next tap.
     */
    private fun openFromReplyAlert(storedSessionId: String) {
        val wiring = wiringOrStatus() ?: return
        val owned = wiring.core.sessions.activeConversation(storedSessionId)
        if (owned == null) {
            app.consumePendingOpen(storedSessionId)
            return
        }
        Log.i(TAG, "reply alert conversation opened alias=${owned.alias}")
        if (_state.value.selected?.storedSessionId != storedSessionId) open(owned) { app.consumePendingOpen(storedSessionId) }
        else {
            viewModelScope.launch {
                if (refreshVisible(storedSessionId) != null) {
                    app.consumePendingOpen(storedSessionId)
                    app.replyAlerts.onConversationOpened(storedSessionId)
                }
            }
        }
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
            }.onFailure { error ->
                ComposerDispatch.attachmentNotRead(app.diagnostics.log, error)
                _state.update { it.copy(status = ComposerDispatch.attachmentNotReadStatus(error)) }
            }
        }
    }

    fun removeAttachment(index: Int) = _state.update { it.copy(attachments = it.attachments.filterIndexed { i, _ -> i != index }) }

    /**
     * Sends what the composer holds. The text, attachments and conversation are captured and the composer is cleared in
     * ONE atomic step before any backend work; later callbacks only touch the status and [PhoneUiState.failedSends],
     * never the draft, so text typed after the tap (or another conversation's draft) is never erased or overwritten.
     * Nothing waits for an earlier request: Hermes decides what to do with a message sent while it is working.
     */
    fun send() {
        val wiring = wiringOrStatus() ?: return
        while (true) {
            val current = _state.value
            if (!current.signedIn) {
                _state.update { it.copy(status = "Sign in to Hermes first") }
                return
            }
            val admission = ComposerDispatch.admit(current.selected?.storedSessionId, ComposerDraft(current.draft, current.attachments), app.textSends.ids.incrementAndGet(), app.diagnostics.log)
            when (admission) {
                is SendAdmission.Refused -> {
                    admission.reason?.let { reason -> _state.update { it.copy(status = reason) } }
                    return
                }
                is SendAdmission.Admitted -> {
                    val shown = PendingSend(admission.send, PendingSends.baseline(current.history))
                    val cleared = current.copy(draft = admission.remaining.text, attachments = admission.remaining.attachments,
                        pendingSends = PendingSends.add(current.pendingSends, shown),
                        sendsInFlight = current.sendsInFlight + 1, status = "Sending…")
                    if (_state.compareAndSet(current, cleared)) {
                        app.textSends.start(wiring.core.chat, shown, app.asyncActionOrigin(wiring))
                        return
                    }
                }
            }
        }
    }

    /**
     * Sends a failed request again from the content kept for it; the composer is not involved. Admitted only while the request's
     * OWN conversation is the selected one, from one state snapshot: `selected` and `history` change together in [open], so the
     * baseline is that conversation's loaded history. A tap on another conversation's request (or a second tap on one already
     * admitted) is refused and the kept request is left as it was.
     */
    fun retryFailedSend(id: Long) {
        val wiring = wiringOrStatus() ?: return
        while (true) {
            val current = _state.value
            val failed = current.failedSends.firstOrNull { it.send.id == id } ?: return
            if (current.selected?.storedSessionId != failed.send.sessionId) return
            val shown = PendingSend(failed.send, PendingSends.baseline(current.history))
            val admitted = current.copy(failedSends = current.failedSends.filterNot { f -> f.send.id == id },
                pendingSends = PendingSends.add(current.pendingSends, shown), sendsInFlight = current.sendsInFlight + 1, status = "Sending…")
            if (_state.compareAndSet(current, admitted)) {
                if (!app.textSends.retry(wiring.core.chat, shown, app.asyncActionOrigin(wiring))) {
                    // A different screen may already have retried/accepted this exact request.
                    // Remove only this stale projection; never create another transport attempt.
                    _state.update { it.copy(pendingSends = PendingSends.remove(it.pendingSends, id),
                        sendsInFlight = maxOf(0, it.sendsInFlight - 1),
                        status = "This request is no longer available for retry. Check the conversation") }
                }
                return
            }
        }
    }

    fun dismissFailedSend(id: Long) {
        app.textSends.dismissFailed(id)
        _state.update { it.copy(failedSends = it.failedSends.filterNot { f -> f.send.id == id }) }
    }

    private fun onSendAccepted(send: DispatchedSend) {
        _state.update { it.copy(pendingSends = PendingSends.markDelivered(it.pendingSends, send.id)) }
        if (_state.value.selected?.storedSessionId == send.sessionId) reloadSelected()
    }

    private fun onSendFinished(send: DispatchedSend, result: ChatSendResult, origin: PhoneApp.AsyncActionOrigin) {
        if (!app.asyncActionCurrent(origin, authLost = result is ChatSendResult.Failed && result.authRequired)) {
            // The app still owns the original outcome/custody. Retire only this screen's old
            // pending projection; do not publish its status, auth loss or history into a new account.
            _state.update { current ->
                if (current.pendingSends.none { it.send.id == send.id }) current else current.copy(
                    pendingSends = PendingSends.remove(current.pendingSends, send.id),
                    sendsInFlight = maxOf(0, current.sendsInFlight - 1))
            }
            return
        }
        val failed = ComposerDispatch.failure(send, result)
        val status = ComposerDispatch.statusSafe(result)
        _state.update {
            val inFlight = maxOf(0, it.sendsInFlight - 1)
            it.copy(
                sendsInFlight = inFlight,
                status = if (status.isEmpty() && inFlight > 0) it.status else status,
                signedIn = if (result is ChatSendResult.Failed && result.authRequired) false else it.signedIn,
                failedSends = if (failed != null) ComposerDispatch.withFailed(it.failedSends.filterNot { f -> f.send.id == send.id }, failed) else it.failedSends,
                pendingSends = if (failed != null || (result is ChatSendResult.Failed && result.delivery == com.rumi.hermesvoice.core.SendDelivery.POSSIBLY_SENT))
                    PendingSends.remove(it.pendingSends, send.id) else PendingSends.markDelivered(it.pendingSends, send.id),
            )
        }
        if (failed == null && _state.value.selected?.storedSessionId == send.sessionId) reloadSelected()
    }

    /**
     * Re-reads the newest page of [sessionId] and merges it into the screen, for the reply alerts: the page when the conversation is
     * the one selected, the read succeeded and it is still in front of the user; null otherwise (nothing is then claimed as shown).
     */
    private suspend fun refreshVisible(sessionId: String): HistoryPage? {
        val generation = selection.get()
        if (_state.value.selected?.storedSessionId != sessionId) return null
        val wiring = try {
            app.wiring()
        } catch (_: IllegalArgumentException) {
            return null
        }
        val origin = actionOrigin(wiring)
        val page = try {
            wiring.core.sessions.history(sessionId, limit = HISTORY_PAGE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (!mergeLatest(sessionId, generation, page, release = false, origin = origin)) return null
        return page.takeIf { app.presence.isVisible(sessionId) }
    }

    // ── viewing attachments ──────────────────────────────────────────────────────────────────

    private var viewerJob: Job? = null
    private val viewerSerial = AtomicLong(0)

    /** Shows an attachment the user has not sent yet, from the bytes already in the composer. */
    fun previewAttachment(index: Int) {
        val item = _state.value.attachments.getOrNull(index) ?: return
        showViewer(item.name) { materialize(item.name, item.mimeType, item.bytes) }
    }

    /** Render-bound source, retained by ChatTab until that exact session/body changes. */
    internal fun attachmentOpener(sessionId: String): (HistoryMessage, AttachmentRef) -> Unit {
        val wiring = app.existingWiring()
        val origin = wiring?.let(app::asyncActionOrigin)
        return { message, ref -> openAttachment(sessionId, message, ref, origin) }
    }

    /** Non-UI callers must also name a real displayed source row, never just a global file path. */
    fun openAttachment(ref: AttachmentRef) {
        val state = _state.value
        val session = state.selected ?: return
        if(state.historyNamespace == null || state.historyNamespace != app.bodyScope()) return
        val message = state.history.firstOrNull { ref in com.rumi.hermesvoice.core.attachments.AttachmentRefs.extract(it.text) } ?: return
        attachmentOpener(session.storedSessionId)(message, ref)
    }

    private fun openAttachment(sessionId: String, message: HistoryMessage, ref: AttachmentRef, origin: PhoneApp.AsyncActionOrigin?) {
        if (origin == null || !app.asyncActionCurrent(origin) || origin.namespace == null ||
            message.rowId < 0 || ref !in com.rumi.hermesvoice.core.attachments.AttachmentRefs.extract(message.text)) return
        val wiring = origin.wiring
        // All source identity is frozen before suspension. A later selection is irrelevant.
        showViewer(ref.name, origin) {
            // Read authority (including eligible archived history), not send/delivery authority.
            wiring.core.sessions.history(sessionId, limit = 1)
            if (!app.asyncActionCurrent(origin)) throw CancellationException("Attachment origin changed")
            val stored = wiring.dashboard.fetchStoredFile(ref)
            if (!app.asyncActionCurrent(origin)) throw CancellationException("Attachment origin changed")
            materialize(ref.name.ifBlank { stored.name }, stored.mimeType, stored.bytes)
        }
    }

    private fun showViewer(title: String, origin: PhoneApp.AsyncActionOrigin? = null, load: suspend () -> ViewedAttachment) {
        val serial = viewerSerial.incrementAndGet()
        viewerJob?.cancel()
        ViewedFiles.clear(app)
        _state.update { it.copy(viewer = AttachmentViewerState(title, ViewerPhase.LOADING)) }
        viewerJob = viewModelScope.launch {
            fun current(authLost: Boolean = false) = viewerSerial.get() == serial &&
                (origin == null || app.asyncActionCurrent(origin, authLost))
            var authLost = false
            val next = try {
                AttachmentViewerState(title, ViewerPhase.READY, load())
            } catch (error: CancellationException) {
                throw error
            } catch (error: HermesAuthRequiredException) {
                if (!current(authLost = true)) return@launch
                authLost = true
                _state.update { it.copy(signedIn = false) }
                AttachmentViewerState(title, ViewerPhase.FAILED, error = "Sign in to Hermes again to view this file")
            } catch (error: Exception) {
                Log.w(TAG, "attachment not shown: ${error.javaClass.simpleName}")
                AttachmentViewerState(title, ViewerPhase.FAILED, error = viewerFailure(error))
            }
            if (viewerSerial.get() == serial) {
                if (current(authLost)) {
                    _state.update { it.copy(viewer = next) }
                    return@launch
                }
            }
            next.file?.file?.delete() // Only this load's unique materialization, never a newer viewer's files.
        }
    }

    private fun viewerFailure(error: Exception): String = when {
        error is com.rumi.hermesvoice.core.HermesHttpException && error.status == 404 -> "Hermes no longer has this file"
        error is com.rumi.hermesvoice.core.HermesHttpException && (error.status == 401 || error.status == 403) -> "Hermes did not allow this file to be shown"
        error is com.rumi.hermesvoice.core.HermesProtocolException -> error.message ?: "This file can't be shown"
        else -> "Could not load this file. Check the connection and try again"
    }

    private suspend fun materialize(name: String, declaredMime: String, bytes: ByteArray): ViewedAttachment {
        // Keep ownership outside the IO return: prompt cancellation can discard its result.
        var owned: java.io.File? = null
        try {
            return withContext(Dispatchers.IO) {
                val decision = FilePreview.classify(name, declaredMime, bytes)
                val text = if (decision.kind == PreviewKind.TEXT) FilePreview.textPreview(bytes) else null
                val kind = if (decision.kind == PreviewKind.TEXT && text == null) PreviewKind.OTHER else decision.kind
                val file = ViewedFiles.write(app, name, bytes).also { owned = it }
                ViewedAttachment(name, decision.mimeType, kind, bytes.size, text, file, bytes)
            }
        } catch (error: Throwable) {
            runCatching { owned?.delete() } // Only this load's file, even if a newer viewer has already opened.
            throw error
        }
    }

    fun closeViewer() {
        viewerSerial.incrementAndGet()
        viewerJob?.cancel()
        viewerJob = null
        ViewedFiles.clear(app)
        _state.update { it.copy(viewer = null) }
    }

    /** Copies the viewed file to a place the user picked (the system "Save as" result) and reports whether that worked. */
    fun saveViewed(target: Uri, done: (Boolean) -> Unit) {
        val viewed = _state.value.viewer?.file ?: return done(false)
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { getApplication<Application>().contentResolver.openOutputStream(target)!!.use { it.write(viewed.bytes) } }.isSuccess
            }
            done(saved)
        }
    }

    fun reportStatus(text: String) = _state.update { it.copy(status = text) }

    // ── phone push-to-talk ───────────────────────────────────────────────────────────────────

    fun toggleRecording() {
        if (captures.tap()) return
        app.manualCapture.toggle()
    }

    override fun onHeadsetCommand(command: RecordingCommand): HeadsetOutcome {
        if (captures.activeId != null) {
            if (command == RecordingCommand.START) return HeadsetOutcome.IGNORED
            captures.tap()
            return HeadsetOutcome.STOPPED
        }
        return app.manualCapture.onHeadsetCommand(command)
    }

    override fun onHeadsetControlLost() = app.manualCapture.onHeadsetControlLost()

    /** What Android's readback said the recording uses (headset, or the Phone microphone with the reason). */
    private fun onMicSource(report: MicReport) {
        val text = report.text ?: return
        _state.update { if (it.recording || it.handsFree != HandsFree.IDLE) it.copy(micNotice = text) else it }
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
                val classified = DiagClassify.of(error)
                Log.w(TAG, "phone turn failed cause=${classified.cause.wire}")
                _state.update { it.copy(status = "Couldn't send: ${DiagLabels.cause(classified.cause)}") }
            }
        }
    }

    // ── phone hands-free (wake phrase) ───────────────────────────────────────────────────────

    /** Plays one unplayed reply again from its saved text; nothing is sent to Hermes again. */
    fun playAgain(reply: com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator.UnplayedReply) {
        val started = app.replayReply(reply.identity, reply.storedSessionId.takeIf { it.isNotEmpty() }) { heard, detail ->
            _state.update { it.copy(voiceStatus = if (heard) "Reply played" else "Reply still not played: ${com.rumi.hermesvoice.core.headset.HeadsetText.fixedNote(detail)?.removePrefix("not played: ") ?: "try again, or read the text"}") }
        }
        if (!started) _state.update { it.copy(voiceStatus = "That reply can't be played again now") }
    }

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
        val micLease = app.phoneMic.acquire() ?: run { held?.release(); return false }
        handsFreeMicLease = micLease
        handsFreeClaimId = claimId
        val id = UUID.randomUUID().toString()
        if (!captures.begin(id, TurnTrigger.WAKE_PHRASE)) {
            handsFreeMicLease?.close(); handsFreeMicLease = null
            held?.release()
            return false
        }
        // The wake episode's claim (taken when the phrase was heard), or a new one: BEFORE the microphone opens.
        val microphone = held ?: app.audio.claimMicrophone(VoiceOrigin.PHONE)
        handsFreeMicrophone = microphone
        _state.update { it.copy(micNotice = null) }
        val capture = PhoneCapture(id, SilenceEndpoint(sampleRate = PhoneCapture.SAMPLE_RATE, silenceMs = silenceMs),
            object : PcmCaptureLoop.Listener {
                override fun onLive() = Unit
                override fun onCalibrated() { main.post { captures.onCalibrated(id) } }
                override fun onEnd(reason: CaptureEnd) { main.post { captures.stop(id, CaptureStop.of(reason)) } }
            }, app.recordFactory)
        handsFreeRecorder = capture
        Log.i(TAG, "phone hands-free capture turn=${id.take(12)} vad_silence_ms=$silenceMs")
        // Opened only once a later reply it stopped has stopped; null: that happens in a moment (or it ends as START_FAILED).
        return microphone.whenSpeakerStopped(viewModelScope) { stopped -> openHandsFree(id, capture, stopped) } ?: true
    }

    private fun openHandsFree(id: String, capture: PhoneCapture, stopped: Boolean): Boolean {
        // Cancelled meanwhile (pause, opt-out, a tap): nothing to open.
        if (captures.activeId != id || handsFreeRecorder !== capture) return false
        if (!stopped) {
            captures.stop(id, CaptureStop.START_FAILED)
            return false
        }
        var settled = false
        var opened = false
        val preparing = app.headsetMic.prepare { plan ->
            settled = true
            handsFreePrepare = null
            opened = startHandsFreeCapture(id, capture, plan)
        }
        if (!settled) {
            // A Bluetooth headset microphone is connecting (bounded); a cancel closes it through the capture's stop.
            handsFreePrepare = preparing
            return true
        }
        return opened
    }

    private fun startHandsFreeCapture(id: String, capture: PhoneCapture, plan: MicPlan): Boolean {
        if (captures.activeId != id || handsFreeRecorder !== capture) {
            plan.release()
            return false
        }
        if (!capture.start(plan) { report -> main.post { onMicSource(report) } }) {
            captures.stop(id, CaptureStop.START_FAILED)
            return false
        }
        if (plan.preferred != null) {
            handsFreeLoss = app.headsetMic.watchLoss(plan) {
                main.post {
                    if (captures.activeId == id) {
                        _state.update { it.copy(micNotice = HeadsetText.LOST) }
                        captures.stop(id, CaptureStop.INPUT_LOST)
                    }
                }
            }
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

    /** Independent charging requests in the existing atomic Phone-owned snapshot; never turn standby on. */
    fun setPhoneWakeWhileCharging(on: Boolean) {
        val current = _state.value.watch
        if (current.phoneWakeWhileCharging != on) updateWatch(current.copy(phoneWakeWhileCharging = on))
    }

    fun setWatchWakeWhileCharging(on: Boolean) {
        val current = _state.value.watch
        if (current.watchWakeWhileCharging != on) updateWatch(current.copy(watchWakeWhileCharging = on))
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
     * "Use headset" (off by default; Phone-only). It governs new spoken answers and new recordings; an accepted
     * recording or a playback already on the headset is never changed by toggling it.
     */
    fun setUseHeadset(on: Boolean) {
        app.settings.useHeadset = on
        app.privateAudio.refresh()
        app.headsetMedia.refresh()
        app.recordingCues.recheck()
        app.connectionCue.refresh()
        _state.update { it.copy(useHeadset = on) }
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

    /** Sets this phone's text size (percent of the system font scale; bounded and put on a step before it is saved). */
    fun setFontScalePercent(percent: Int) {
        val saved = PhoneDisplaySettings.sanitize(percent.toString())
        app.display.fontScalePercent = saved
        _state.update { it.copy(fontScalePercent = saved) }
    }

    fun resetFontScale() = setFontScalePercent(PhoneDisplaySettings.DEFAULT)

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
        app.textSends.detach(sendScreen)
        if (app.conversationDisplay === display) app.conversationDisplay = null
        viewerJob?.cancel()
        ViewedFiles.clear(app)
        // Manual capture and its headset target belong to the Application/service, never this screen.
        app.foregroundWakeListening = false
        cancelHandsFree("cleared")
        recognizedMicrophone?.release()
        recognizedMicrophone = null
    }

    companion object {
        const val HISTORY_PAGE = 50

        /** Raw pages read in one older-history request while they hold no shown message. */
        private const val MAX_OLDER_STEPS = 4

        /** Waits before each re-read of the history for a just-arrived final reply (the last one releases its bubble regardless). */
        private val FINAL_LOOKUP_WAITS_MS = longArrayOf(0, 400, 1_000, 2_500).toList()
        private const val TAG = "HermesVoice"

        /** Fixed text (never the saved answers or an error): the saved-answer copy could not be removed yet; it stays hidden and is removed as soon as the phone allows. */
        internal const val UNPLAYED_NOT_CLEARED = "Saved answers on this phone could not be removed yet. They stay hidden and are not played; removal is tried again."
    }
}
