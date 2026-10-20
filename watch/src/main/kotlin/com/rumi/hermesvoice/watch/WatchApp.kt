package com.rumi.hermesvoice.watch

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.PowerManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.KeyValueStore
import com.rumi.hermesvoice.core.background.DeviceLocalFlags
import com.rumi.hermesvoice.core.background.HoldReason
import com.rumi.hermesvoice.core.background.WakeHolds
import com.rumi.hermesvoice.core.background.WakeLockPort
import com.rumi.hermesvoice.core.settings.ReplicaUpdate
import com.rumi.hermesvoice.core.wake.ClaimVerdict
import com.rumi.hermesvoice.core.wake.WakeClaimMessage
import com.rumi.hermesvoice.core.wake.WakeClaimSender
import com.rumi.hermesvoice.core.wake.WakeClaimTransit
import com.rumi.hermesvoice.core.wake.WakeContract
import com.rumi.hermesvoice.core.wake.WakeEpochItem
import com.rumi.hermesvoice.core.wake.WakeVerdictMessage
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.settings.WatchSettingsReplica
import com.rumi.hermesvoice.core.notify.ReplyAlert
import com.rumi.hermesvoice.core.notify.ConversationPresence
import com.rumi.hermesvoice.core.notify.ReplyAlertLedger
import com.rumi.hermesvoice.core.notify.ReplyAlertResult
import com.rumi.hermesvoice.core.watchlink.HapticEvent
import com.rumi.hermesvoice.core.watchlink.ReplyAlertMessage
import com.rumi.hermesvoice.core.watchlink.ReplayRequest
import com.rumi.hermesvoice.core.watchlink.HapticUsage
import com.rumi.hermesvoice.core.watchlink.LaterPlaybackGuard
import com.rumi.hermesvoice.core.watchlink.PlayProgress
import com.rumi.hermesvoice.core.watchlink.PlayRequest
import com.rumi.hermesvoice.core.watchlink.PlayedAck
import com.rumi.hermesvoice.core.watchlink.PrivateAudioReceipt
import com.rumi.hermesvoice.core.watchlink.ReaderKind
import com.rumi.hermesvoice.core.watchlink.ReaderMessageRow
import com.rumi.hermesvoice.core.watchlink.ReaderRequest
import com.rumi.hermesvoice.core.watchlink.ReaderResponse
import com.rumi.hermesvoice.core.watchlink.ReaderSessionRow
import com.rumi.hermesvoice.core.watchlink.ReaderSurface
import com.rumi.hermesvoice.core.diag.ApkIdentity
import com.rumi.hermesvoice.core.diag.DiagApp
import com.rumi.hermesvoice.core.diag.DiagCause
import com.rumi.hermesvoice.core.diag.DiagClassify
import com.rumi.hermesvoice.core.diag.DiagCode
import com.rumi.hermesvoice.core.diag.DiagFail
import com.rumi.hermesvoice.core.diag.DiagJournal
import com.rumi.hermesvoice.core.diag.DiagLog
import com.rumi.hermesvoice.core.diag.DiagModality
import com.rumi.hermesvoice.core.diag.DiagOrigin
import com.rumi.hermesvoice.core.diag.DiagStage
import com.rumi.hermesvoice.core.diag.DiagWatchResponder
import com.rumi.hermesvoice.core.diag.DiagWatchTransfer
import com.rumi.hermesvoice.core.diag.DiagWireV2
import com.rumi.hermesvoice.core.diag.DiagWire
import com.rumi.hermesvoice.core.diag.FileDiagJournalStorage
import com.rumi.hermesvoice.core.diag.PhoneUnreachableException
import com.rumi.hermesvoice.core.watchlink.TurnStateMessage
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchHapticPolicy
import com.rumi.hermesvoice.core.sessions.ReadRetryBudget
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchNavigation
import com.rumi.hermesvoice.core.watchlink.WatchNavigationGuard
import com.rumi.hermesvoice.core.watchlink.WatchReaderLimits
import com.rumi.hermesvoice.core.watchlink.WatchReaderState
import com.rumi.hermesvoice.core.watchlink.WatchPhase
import com.rumi.hermesvoice.core.watchlink.WatchTalkState
import com.rumi.hermesvoice.core.watchlink.WatchTurnUpload
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import com.rumi.hermesvoice.core.watchlink.ReaderSessionsState
import com.rumi.hermesvoice.core.watchlink.LoadStatus
import com.rumi.hermesvoice.core.watchlink.ReaderError

/**
 * Watch process state. The Watch never talks to Hermes: it uploads captured WAVs to the Phone,
 * shows the Phone's stage updates, plays whatever the Phone sends (acknowledging each playback so
 * the Phone can keep its ordering: ack → delivery → replies), and reads the app's conversations
 * through the Phone ([reader]). Listening and recording belong to [voice]. Everything here is
 * owned by the application, not by an activity, so it goes on with the app hidden for as long as
 * the process runs; [WatchVoiceService] keeps it running for a background session.
 */
class WatchApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Typed diagnostic events (bounded ring buffer, last 24 h also kept in private storage): sent to the Phone only inside one user-started export. */
    val diag = DiagLog(clock = SystemClock::elapsedRealtime)

    private val diagExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "hv-diag-journal").apply { isDaemon = true }
    }
    val diagJournal: DiagJournal by lazy {
        DiagJournal(diag, FileDiagJournalStorage(File(noBackupFilesDir, "diag-journal")), diagExecutor)
    }

    private val diagResponder: DiagWatchResponder by lazy {
        DiagWatchResponder(diag, persistence = diagJournal::status, app = {
            val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
            DiagApp(DiagApp.safeName(info?.versionName), info?.longVersionCode?.toInt() ?: 0,
                ApkIdentity.of(runCatching { File(applicationInfo.sourceDir) }.getOrNull()))
        })
    }

    /** Clears this watch's saved and in-memory diagnostics only; no conversation, setting, media or Phone data. False if the file could not be removed. */
    suspend fun clearLocalLog(): Boolean = withContext(Dispatchers.IO) { diagJournal.clearNow() }
    private val _talk = MutableStateFlow(WatchTalkState())
    val talk: StateFlow<WatchTalkState> = _talk
    private val _settings = MutableStateFlow(WatchSettings())
    val settings: StateFlow<WatchSettings> = _settings
    private val _reader = MutableStateFlow(WatchReaderState())

    /** Conversation reader: session browser and the selected conversation's cached history. */
    val reader: StateFlow<WatchReaderState> = _reader

    /** The Phone node each pending reader request went to; a response from any other node is ignored. */
    private val readerTargets = ConcurrentHashMap<String, String>()

    private val readerTitleWrites = Mutex()
    private val readerTitleEpoch = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var readerTitlePhone: String? = null
    @Volatile private var readerTitleScope: String? = null
    @Volatile private var readerBodyScope: String? = null
    private val bodyGeneration = java.util.concurrent.atomic.AtomicLong()
    private val receivedFinals by lazy {
        WatchFinalIntake(this, {
            val phone = readerTitlePhone; val account = readerBodyScope
            if (phone == null || account == null) null else phone to account
        }, { presence.isVisible(it) }, { note -> _reader.update { it.copy(sessions = it.sessions.copy(alertNotice = note)) } }, { ids ->
            _reader.update { state -> state.copy(sessions = state.sessions.copy(rows = state.sessions.rows.map { if(it.id in ids) it.copy(unread = true) else it })) }
            saveReaderTitles()
            loadSessions() // Retire older list requests before reading the Phone's receive/read knowledge.
        })
    }
    private val READER_TITLES_KEY = "reader_session_titles_v1"
    private val recentBodies by lazy {
        com.rumi.hermesvoice.core.sessions.RecentBodies(localStore, "reader_bodies_v1", {
            val phone = readerTitlePhone
            val account = readerBodyScope
            if (phone == null || account == null) null else "$phone|$account"
        }, scope) {
            _reader.update { it.copy(sessions = it.sessions.copy(notice = "Recent messages not saved for next start")) }
        }
    }

    /** Only last-known display metadata; no prompts, aliases, previews, history or auth material. */
    private fun hydrateReaderTitles() {
        runCatching {
            val saved = JSONObject(localStore.getString(READER_TITLES_KEY) ?: return)
            if (saved.optInt("v") != 1) return
            val phone = saved.getString("phone").takeIf { it.isNotBlank() && it.length <= 256 } ?: return
            val scope = saved.getString("scope").takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: return
            val data = saved.getJSONArray("rows")
            if (data.length() > WatchReaderLimits.MAX_SESSIONS) return
            val rows = (0 until data.length()).map { i ->
                val row = data.getJSONObject(i)
                val id = row.getString("id")
                require(com.rumi.hermesvoice.core.voice.DestinationAllowlist.isValidSessionId(id))
                ReaderSessionRow(id, row.getString("title").take(WatchReaderLimits.MAX_TITLE_CHARS), "", "",
                    row.optDouble("last_active", 0.0).takeIf { it.isFinite() } ?: 0.0, row.optBoolean("unread"))
            }.distinctBy { it.id }
            readerTitlePhone = phone
            readerTitleScope = scope
            readerBodyScope = saved.optString("body_scope").takeIf { it.matches(Regex("[a-f0-9]{64}")) }?:scope
            _reader.value = _reader.value.copy(sessions = ReaderSessionsState(rows, LoadStatus.READY,
                notice = if (rows.isEmpty()) null else "Saved conversations"))
        }
    }

    /** Immediate state/request fence; the small disk clear is serialized off the main thread. */
    private fun invalidateReaderTitles(phone: String? = readerTitlePhone, scope: String? = null, clearState: Boolean = true) {
        bodyGeneration.incrementAndGet()
        receivedFinals.invalidate()
        recentBodies.invalidate()
        readerTitlePhone = phone
        readerTitleScope = scope
        readerBodyScope = scope
        val epoch = readerTitleEpoch.incrementAndGet()
        if (clearState) {
            readerTargets.clear()
            updateReader { WatchReaderState(surface = ReaderSurface.SESSIONS) }
        }
        this.scope.launch(Dispatchers.IO) {
            readerTitleWrites.withLock {
                if (readerTitleEpoch.get() != epoch) return@withLock
                val ok = try { localStore.commitString(READER_TITLES_KEY, "{}") }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                if (!ok) Log.w(TAG, "Reader title invalidation was not saved")
            }
        }
    }

    /** A capability/query result is an observed Phone node, not proof of its Hermes account. */
    private fun observeReaderPhone(node: String) {
        if (readerTitlePhone != null && readerTitlePhone != node) invalidateReaderTitles(phone = node)
        else readerTitlePhone = node
    }

    private fun saveReaderTitles() {
        val phone = readerTitlePhone ?: return
        val namespace = readerTitleScope ?: return // old peer: no invented endpoint/account binding
        val bodyNamespace = readerBodyScope
        val epoch = readerTitleEpoch.incrementAndGet()
        val rows = _reader.value.sessions.rows.take(WatchReaderLimits.MAX_SESSIONS)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                readerTitleWrites.withLock {
                    if (readerTitleEpoch.get() != epoch) return@withLock null
                    val saved = JSONObject().put("v", 1).put("phone", phone).put("scope", namespace)
                        .put("body_scope",bodyNamespace?:JSONObject.NULL)
                        .put("rows", JSONArray().apply { rows.forEach {
                            put(JSONObject().put("id", it.id).put("title", it.title.take(WatchReaderLimits.MAX_TITLE_CHARS))
                                .put("last_active", it.lastActive).put("unread", it.unread))
                        } })
                    try { localStore.commitString(READER_TITLES_KEY, saved.toString()) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                }
            }
            if (ok == false && readerTitleEpoch.get() == epoch) updateReader {
                it.copy(sessions = it.sessions.copy(error = "cache_not_saved", notice = "List not saved for next start"))
            }
        }
    }

    /**
     * Phone-told conversation moves ([onNavigation]) are applied only for a request this Watch started and only while the user has
     * not navigated since. In memory only, like the selection itself.
     */
    internal val navigationGuard = WatchNavigationGuard()

    @Volatile private var player: MediaPlayer? = null
    private var playing: PlayRequest? = null
    private var playingNode: String? = null
    private var progressJob: Job? = null

    // ACK transport owns separate, bounded CPU holds, never the current speaker's hold.
    // Tokens (not turn IDs) keep concurrent/duplicate acknowledgements independently owned.
    private val playedAckHolds by lazy { WakeHolds(AndroidWakeLocks(this, "HermesVoice:ack"), SystemClock::elapsedRealtime) }
    private val pendingPlayedAcks = mutableSetOf<Any>()

    /** A channel already admitted before local Stop loses receive authority, even for Phone turns. */
    @Volatile internal var playbackStopGeneration: Long = 0L
        private set

    /** Elapsed-realtime millis when Watch playback last ended (wake-phrase cooldown). */
    @Volatile var lastPlaybackEndedAtMs: Long = 0L
        private set

    private lateinit var replica: WatchSettingsReplica

    /** Device-local choices of this Watch (not the Phone-owned voice settings): the background opt-in. */
    val localStore: KeyValueStore by lazy { PrefsStore(getSharedPreferences(DeviceLocalFlags.WATCH_PREFERENCES, Context.MODE_PRIVATE)) }

    /** CPU wake locks, one per reason and each with a timeout (see [WakeHolds]). */
    val holds: WakeHolds by lazy { WakeHolds(AndroidWakeLocks(this), SystemClock::elapsedRealtime) }

    /** The one voice runtime (wake phrase, recorder, background session); created with the process, on the main thread. */
    lateinit var voice: WatchVoiceRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        diagJournal.attach()
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_SETTINGS, null)
        replica = WatchSettingsReplica(stored)
        // Durable migration: a copy from before the wake-location selector (or an unreadable one) is rewritten once.
        if (stored != null && stored != replica.current.toJson()) prefs.edit().putString(KEY_SETTINGS, replica.current.toJson()).apply()
        _settings.value = replica.current
        hydrateReaderTitles()
        // A final intake can commit before an unrelated title writer. Recover its own binding,
        // but never cross an explicit title-clear tombstone or replace a known newer scope.
        if (readerTitlePhone == null && localStore.getString(READER_TITLES_KEY) == null) runCatching {
            val saved = JSONObject(localStore.getString(WatchFinalIntake.KEY) ?: "{}")
            val phone = saved.optString("phone").takeIf { it.isNotBlank() && it.length <= 256 }
            val account = saved.optString("scope").takeIf { it.matches(Regex("[a-f0-9]{64}")) }
            if (phone != null && account != null) { readerTitlePhone = phone; readerBodyScope = account }
        }
        WatchVoiceService.createChannel(this)
        WatchReplyAlertNotifier.createChannel(this)
        hydrateOwedFailure()
        voice = WatchVoiceRuntime(this)
        // The Phone's reachability is kept current by the platform's own capability events (no polling before a window).
        runCatching {
            Wearable.getCapabilityClient(this).addListener(
                CapabilityClient.OnCapabilityChangedListener { info -> setPhoneReachable(info.nodes.firstOrNull { it.isNearby }?.id) },
                WatchLinkPaths.CAPABILITY_PHONE,
            )
        }.onFailure { Log.w(TAG, "capability listener unavailable: ${it.javaClass.simpleName}") }
    }

    /**
     * Offers a Phone-owned settings snapshot to the replica: an invalid one (bad type, NaN, out of
     * range, unknown mode) is rejected whole, a stale or equal-revision one is ignored.
     */
    fun applySettings(json: String) {
        val result = replica.offer(json)
        val current = replica.current
        if (result == ReplicaUpdate.APPLIED) {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SETTINGS, current.toJson()).apply()
            _settings.value = current
            // The Phone's headset is the only output now: what plays here stops (the Phone is told it was not played).
            if (current.privateAudio) stopPlayback(PRIVATE_HEADSET)
        }
        // Whatever was offered (applied, stale or invalid), the Phone is told what this Watch really holds: the revision and whether it
        // refuses to play. A send is best effort and bounded; without a receipt the Phone claims nothing about the Watch.
        val receipt = PrivateAudioReceipt(current.revision, current.privateAudio)
        scope.launch {
            val node = phoneNode() ?: return@launch
            try {
                withTimeoutOrNull(PROGRESS_SEND_MS) {
                    Wearable.getMessageClient(this@WatchApp).sendMessage(node, WatchLinkPaths.PRIVATE_AUDIO, receipt.toJson().toByteArray(Charsets.UTF_8)).await()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
        Log.i(TAG, "settings $result revision=${current.revision} wake_location=${current.wakeLocation} " +
            "watch_listens=${current.watchWakeEnabled} phone_standby=${current.phoneBackgroundWakeEnabled} " +
            "watch_standby=${current.watchBackgroundWakeEnabled} phone_screen_off=${current.phoneBackgroundWakeScreenOffEnabled} " +
            "watch_screen_off=${current.watchBackgroundWakeScreenOffEnabled} watch_auto_navigate=${current.watchAutoNavigateToRouted} vad_silence_s=${current.vadSilenceSeconds} haptics=${current.hapticsEnabled}")
    }

    private val _phoneReachable = MutableStateFlow<Boolean?>(null)

    /** Whether a nearby Phone advertising the app capability is reachable; null until first checked. */
    val phoneReachable: StateFlow<Boolean?> = _phoneReachable

    suspend fun refreshPhoneReachable() {
        setPhoneReachable(phoneNode())
    }

    /** Whether the Phone is reachable now; each time it becomes reachable, the "not sent" states it owes this Watch are asked for. */
    private fun setPhoneReachable(node: String?) {
        if (node != null) observeReaderPhone(node)
        val before = _phoneReachable.value
        _phoneReachable.value = node != null
        if (node != null && before != true) scope.launch {
            receivedFinals.recover()
            if (_reader.value.sessions.pendingReqId == null) loadSessions()
            runCatching {
                withTimeoutOrNull(PROGRESS_SEND_MS) {
                    Wearable.getMessageClient(this@WatchApp).sendMessage(node, WatchLinkPaths.STATE_SYNC, ByteArray(0)).await()
                }
            }
        }
    }

    /** The Phone's "not sent" states this Watch owes the person a look at, and those already shown (durable, checked commits). */
    private val failureInbox by lazy { com.rumi.hermesvoice.core.watchlink.WatchFailureInbox(localStore) }

    private val _owedFailure = MutableStateFlow<com.rumi.hermesvoice.core.watchlink.WatchFailureInbox.Owed?>(null)

    /** The oldest "not sent" state not yet shown on screen, with its exact request id; null when nothing is owed. */
    val owedFailure: StateFlow<com.rumi.hermesvoice.core.watchlink.WatchFailureInbox.Owed?> = _owedFailure.asStateFlow()

    /** Reads what the durable inbox still owes the screen (also after the app was recreated). */
    fun hydrateOwedFailure() {
        _owedFailure.value = failureInbox.pending().firstOrNull()
    }

    /**
     * The screen showed the owed failure [turnId] (resumed activity, status text composed). Only now is it settled, durably, and only
     * when that is saved is the Phone acknowledged, to the Phone node it came from.
     */
    fun onFailureDisplayed(turnId: String) {
        val entry = failureInbox.pending().firstOrNull { it.turnId == turnId } ?: return hydrateOwedFailure()
        val saved = failureInbox.displayed(turnId)
        hydrateOwedFailure()
        if (saved) acknowledgeFailure(turnId, entry.source)
    }

    private fun acknowledgeFailure(turnId: String, sourceNodeId: String?) {
        scope.launch {
            val node = sourceNodeId ?: phoneNode() ?: return@launch
            runCatching {
                withTimeoutOrNull(PROGRESS_SEND_MS) {
                    Wearable.getMessageClient(this@WatchApp).sendMessage(node, WatchLinkPaths.STATE_ACK, TurnStateMessage(turnId, "ack", "", true).encode()).await()
                }
            }
        }
    }

    private suspend fun lookUpPhone(): String? =
        Wearable.getCapabilityClient(this).getCapability(WatchLinkPaths.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
            .await().nodes.firstOrNull { it.isNearby }?.id

    private suspend fun phoneNode(): String? = runCatching { lookUpPhone() }.getOrNull()

    /**
     * One Data Layer call as a value. A Task that was itself cancelled throws a CancellationException although this coroutine is still
     * active (a transport failure, not ours to propagate); but when THIS coroutine was cancelled that is thrown at once, even if the Task
     * had already failed, so a cancelled refresh never goes on to publish a failure or send again.
     */
    private suspend fun <T> dataLayerCall(call: suspend () -> T): Result<T> = try {
        Result.success(call()).also { currentCoroutineContext().ensureActive() }
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        Result.failure(error)
    }

    fun newTurn(trigger: TurnTrigger): String? {
        val turnId = UUID.randomUUID().toString()
        val started = runCatching { _talk.update { it.startRecording(turnId, trigger) } }.map { turnId }.getOrNull()
        if (started == null && _talk.value.phase == WatchPhase.WAITING && _talk.value.earlier.size >= WatchTalkState.MAX_EARLIER) {
            // The bound is reached: said on screen, not dropped silently. Nothing already waiting is touched.
            _talk.update { it.copy(line = WatchTalkState.TOO_MANY) }
        }
        return started
            ?.also {
                diag.record(DiagCode.WATCH_RECORDING, DiagOrigin.WATCH, it, n = _talk.value.waitingCount)
                unstoppedLocalTurns += it
                navigationGuard.onTurnStarted(it)
                // A later reply playing now stops ("busy", not played): the Phone plays it again after the recording.
                LaterPlaybackGuard.onRecordingStarted(playing)?.let { busy -> finishPlayback(ok = false, error = busy) }
            }
    }

    /**
     * This Watch is recording a request (push-to-talk or after the wake phrase), about to (a wake
     * phrase was heard: its claim or recorder handoff is pending, and playback would cancel it), or
     * sending one the Phone has not taken yet: a later reply is refused as busy (once the Phone has
     * the request, its orchestrator holds later replies until that request is answered).
     */
    private fun recordingNow(): Boolean = _talk.value.phase == WatchPhase.RECORDING || _talk.value.phase == WatchPhase.SENDING ||
        (::voice.isInitialized && (voice.capturing || voice.wakeEpisodePending))

    fun discard(reason: String) = _talk.update { it.recordingDiscarded(reason) }

    /** Updates the prompt of the capture in progress. */
    fun cue(line: String) = _talk.update { it.recordingCue(line) }

    /** Uploads the finished recording to the reachable Phone node that advertises the app capability. */
    fun upload(turnId: String, trigger: TurnTrigger, wav: ByteArray, wakeClaimId: String? = null) {
        val claimId = wakeClaimId?.takeIf { trigger == TurnTrigger.WAKE_PHRASE }
        // Recording ended, but the claim is this Watch's until the Phone has answered the turn.
        claimId?.let { keepClaimInTransit(it, turnId, wav.size) }
        send(turnId, WatchTurnUpload(turnId, trigger, WatchTurnUpload.MIME_WAV, wav, claimId, target = selectedTarget(),
            selectionGeneration = navigationGuard.generationFor(turnId)))
    }

    /**
     * The conversation selected on this Watch, sent with every turn: the Phone uses it only while
     * its voice routing is off (and only if it is still one of its active conversations).
     */
    private fun selectedTarget(): String? = _reader.value.selectedSessionId

    /** Sends a wake-phrase request the recognizer already heard (no second utterance was recorded). */
    fun uploadRecognized(turnId: String, request: String, wakeClaimId: String? = null) {
        wakeClaimId?.let { keepClaimInTransit(it, turnId, request.length) }
        send(turnId, WatchTurnUpload.recognized(turnId, request, wakeClaimId, target = selectedTarget(),
            selectionGeneration = navigationGuard.generationFor(turnId)))
    }

    // ── wake arbitration ("Both") ────────────────────────────────────────────────────────────

    /** Receives the Phone's verdicts (claim id, verdict); set by the visible activity. */
    @Volatile var wakeVerdictListener: ((String, ClaimVerdict) -> Unit)? = null

    /** Told when the Phone admitted a wake request (its count, that request's claim); set by the visible activity. */
    @Volatile var wakeEpochListener: ((Long, String) -> Unit)? = null

    /** How many wake requests the Phone has answered, as far as this Watch knows; a window carries the value it opened with. */
    @Volatile var wakeEpoch: Long = 0L
        private set

    /** The epoch each granted claim was granted at, until its request is answered. */
    private val grantedEpochs = ConcurrentHashMap<String, Long>()

    /** One ordered sender: a claim's release can never overtake the claim (see [WakeClaimSender]). */
    private val claimSender by lazy {
        WakeClaimSender(scope, ::phoneNode,
            send = { node, bytes -> Wearable.getMessageClient(this@WatchApp).sendMessage(node, WatchLinkPaths.WAKE_CLAIM, bytes).await() },
            log = { Log.i(TAG, it) })
    }

    /**
     * Asks the Phone for, renews or releases the wake claim. The Phone is the only coordinator; if
     * it cannot be reached nothing comes back and the wake flow fails closed on its timer.
     */
    fun sendWakeClaim(message: WakeClaimMessage) = claimSender.offer(message)

    /** Keeps the claim of a request that is on its way to the Phone until the Phone answers that turn. */
    private val transit = WakeClaimTransit(SystemClock::elapsedRealtime,
        renew = { sendWakeClaim(WakeClaimMessage(WakeClaimMessage.Op.RENEW, it)) },
        release = { sendWakeClaim(WakeClaimMessage(WakeClaimMessage.Op.RELEASE, it)) },
        onLost = { turnId, reason ->
            Log.w(TAG, "wake claim lost in transit turn=${turnId.take(12)} reason=$reason")
            _talk.update { if (it.turnId == turnId) it.sendFailed("Couldn't confirm with the phone. Say it again") else it }
        })
    private var transitJob: Job? = null

    private fun keepClaimInTransit(claimId: String, turnId: String, bytes: Int) {
        transit.begin(claimId, turnId, WakeContract.transitLimitMs(bytes))
        Log.i(TAG, "wake claim ${claimId.take(10)} kept for turn=${turnId.take(12)} until the phone answers")
        if (transitJob?.isActive == true) return
        transitJob = scope.launch {
            while (transit.active) {
                delay(WakeContract.CLAIM_RENEW_MS)
                transit.tick()
            }
        }
    }

    fun onWakeVerdict(sourceNodeId: String, bytes: ByteArray) {
        val verdict = WakeVerdictMessage.decode(bytes) ?: return
        if (!claimSender.accepts(verdict.claimId, sourceNodeId)) {
            Log.w(TAG, "wake verdict ignored ${verdict.claimId.take(10)} (not pending from this node)")
            return
        }
        if (verdict.epoch >= 0) {
            wakeEpoch = verdict.epoch
            if (verdict.verdict == ClaimVerdict.GRANTED) {
                if (grantedEpochs.size >= 8) grantedEpochs.clear()
                grantedEpochs[verdict.claimId] = verdict.epoch
            }
        }
        if (verdict.verdict != ClaimVerdict.GRANTED) {
            claimSender.forget(verdict.claimId)
            grantedEpochs.remove(verdict.claimId)
        }
        Log.i(TAG, "wake verdict ${verdict.claimId.take(10)} ${verdict.verdict} epoch=${verdict.epoch}")
        transit.onVerdict(verdict.claimId, verdict.verdict)
        wakeVerdictListener?.invoke(verdict.claimId, verdict.verdict)
    }

    /** The Phone's count of answered wake requests (data item, read on resume and when it changes). */
    fun applyWakeEpoch(json: String) {
        val item = WakeEpochItem.parse(json) ?: return
        wakeEpoch = item.epoch
        Log.i(TAG, "wake epoch ${item.epoch}")
        wakeEpochListener?.invoke(item.epoch, item.claimId)
    }

    /** Debug QA only (set from WatchActivity in debuggable builds): how long every upload waits first, to model a slow link. */
    @Volatile var qaUploadDelayMs = 0L

    /** Uploads that have not reached the link yet, and why one of them was stopped. */
    private val uploads = ConcurrentHashMap<String, Job>()
    private val uploadStops = ConcurrentHashMap<String, String>()

    /**
     * Process-lifetime denial identities: a delayed reply has no finite lifetime in the protocol,
     * so no cap, TTL or LRU may silently reauthorize a stopped turn. Service/reconnect/recreation
     * never clears this set. It grows with stopped identities and is NOT persisted across process death.
     */
    private val stoppedTurns = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** All local turns since Stop, including older WAITING turns no longer in the talk UI. */
    private val unstoppedLocalTurns = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Legitimate received response identities survive completion, including nonlocal Phone turns. */
    private val unstoppedResponseTurns = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * Stop (visible or hidden): recordings on their way to the link
     * are withdrawn (their wake claims given back), and the turn the Watch was waiting on is
     * dropped here, so nothing of it is played later.
     */
    fun cancelPendingUploads(reason: String) {
        // Invalidate in-flight channel reads before any teardown can clear their turn identity.
        playbackStopGeneration++
        val turnId = _talk.value.turnId
        val earlier = _talk.value.earlier
        stoppedTurns += unstoppedLocalTurns + unstoppedResponseTurns + uploads.keys + listOfNotNull(turnId, playing?.turnId) + earlier
        // Safe to clear only after every identity moved to permanent process-lifetime denial.
        unstoppedLocalTurns.clear()
        unstoppedResponseTurns.clear()
        navigationGuard.onStopped()
        transit.cancel()
        transitJob?.cancel()
        transitJob = null
        grantedEpochs.clear()
        uploads.forEach { (id, job) ->
            uploadStops[id] = reason
            job.cancel()
        }
        diag.record(DiagCode.WATCH_STOPPED, DiagOrigin.WATCH, n = earlier.size + (if (turnId != null) 1 else 0))
        // This Watch's Stop is global for its own requests: the current one and every one still waiting for its reply.
        _talk.update { it.stopped(reason) }
        tellPhoneStopped(listOfNotNull(turnId) + earlier)
        if (turnId != null) Log.i(TAG, "turn ${turnId.take(12)} stopped on the watch reason=$reason waiting_stopped=${earlier.size}")
    }

    /**
     * "Play again": asks the Phone to play the finished request's reply again (nothing is sent to Hermes again). The control goes
     * away at once; the Phone's next state for the request says if it failed again.
     */
    fun replayReply() {
        val turnId = _talk.value.replayTurnId ?: return
        _talk.update { it.replayRequested() }
        scope.launch {
            val node = phoneNode()
            val sent = node != null && runCatching {
                Wearable.getMessageClient(this@WatchApp).sendMessage(node, WatchLinkPaths.REPLAY, ReplayRequest(turnId).encode()).await()
            }.isSuccess
            if (!sent) _talk.update { if (it.replayTurnId == null && it.line == WatchTalkState.REPLAY_REQUESTED) it.copy(replayTurnId = turnId, line = "Phone not reachable - try again") else it }
        }
    }

    /** Tells the Phone that these requests of this Watch were stopped (best effort): it stops them too, wherever they are. */
    private fun tellPhoneStopped(turnIds: List<String>) {
        if (turnIds.isEmpty()) return
        scope.launch {
            val node = phoneNode() ?: return@launch
            turnIds.forEach { id ->
                runCatching {
                    Wearable.getMessageClient(this@WatchApp)
                        .sendMessage(node, WatchLinkPaths.CANCEL, TurnStateMessage(id, "cancel", "", false).encode()).await()
                }
            }
        }
    }

    private fun send(turnId: String, upload: WatchTurnUpload) {
        _talk.update { if (it.turnId == turnId) it.sending() else it }
        holds.acquire(HoldReason.TRANSFER, WakeContract.transitLimitMs(upload.audio.size))
        // Registered before it runs, so its own end always finds (and removes) its entry.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val failure = runCatching {
                if (qaUploadDelayMs > 0) {
                    Log.i(TAG, "qa slow transfer: turn=${turnId.take(12)} waits $qaUploadDelayMs ms (debug builds only)")
                    delay(qaUploadDelayMs)
                }
                val phone = phoneNode() ?: throw PhoneUnreachableException()
                navigationGuard.onUploadNode(turnId, phone)
                val channels = Wearable.getChannelClient(this@WatchApp)
                val channel = channels.openChannel(phone, WatchLinkPaths.turnPath(turnId)).await()
                val output = channels.getOutputStream(channel).await()
                val frame = upload.toFrame().encode()
                withContext(Dispatchers.IO) { output.use { it.write(frame); it.flush() } }
            }.exceptionOrNull()
            val stopped = if (failure != null) uploadStops.remove(turnId) else null
            val transfer = DiagWatchTransfer.record(diag, turnId, failure, userStopped = stopped != null)
            if (failure == null) {
                Log.i(TAG, "upload sent turn=${turnId.take(12)} trigger=${upload.trigger} kind=" +
                    (if (upload.recognizedText != null) "recognized_request" else "wav") + " bytes=${upload.audio.size}")
            } else {
                Log.w(TAG, "upload failed: ${stopped ?: transfer?.cause?.wire}")
                transit.onTransferFailed(turnId)
                _talk.update { if (it.turnId == turnId) it.sendFailed(stopped ?: transfer?.status ?: "Couldn't send to the phone") else it }
            }
            uploads.remove(turnId)
            if (uploads.isEmpty()) holds.release(HoldReason.TRANSFER)
        }
        uploads[turnId] = job
        job.start()
    }

    /** One user-started diagnostics export on the Phone asks for this Watch's events: answered to that node only, once, bounded. */
    fun onDiagRequest(sourceNodeId: String, data: ByteArray, pathSchema: Int) {
        if (DiagWire.decodeRequestAny(data)?.first != pathSchema) return
        val response = runCatching { diagResponder.answer(data) }.getOrNull() ?: return
        scope.launch {
            runCatching {
                withTimeoutOrNull(DIAG_SEND_MS) {
                    Wearable.getMessageClient(this@WatchApp).sendMessage(sourceNodeId, WatchLinkPaths.DIAG_RESPONSE, response).await()
                }
            }
        }
    }

    /**
     * The Phone's state for a request. A "not sent" end is kept as owed (durably) until the screen reports it was shown, then it is
     * settled and acknowledged to [sourceNodeId] (the Phone node it came from); a repeat of a shown one is only acknowledged again,
     * a repeat of one not yet shown is shown again. The Phone keeps owing it until it is acknowledged.
     */
    fun onPhoneState(message: TurnStateMessage, sourceNodeId: String? = null) {
        val failure = message.terminal && WatchTalkState.isRetainedFailure(message.detail)
        if (!failure) return applyPhoneState(message)
        when (failureInbox.receive(message.turnId, message.detail, sourceNodeId)) {
            com.rumi.hermesvoice.core.watchlink.WatchFailureInbox.Receipt.SHOWN_AND_SAVED -> acknowledgeFailure(message.turnId, sourceNodeId)
            com.rumi.hermesvoice.core.watchlink.WatchFailureInbox.Receipt.SHOWN_NOT_SAVED -> Unit
            com.rumi.hermesvoice.core.watchlink.WatchFailureInbox.Receipt.OWED -> {
                applyPhoneState(message)
                hydrateOwedFailure()
            }
        }
    }

    private fun applyPhoneState(message: TurnStateMessage) {
        Log.i(TAG, "phone state turn=${message.turnId.take(12)} stage=${message.stage} terminal=${message.terminal}")
        val ownTurn = message.turnId == _talk.value.turnId || message.turnId in _talk.value.earlier
        // The Phone answered this turn: its wake claim was used up (or, if the turn ended there, is given back).
        transit.onPhoneState(message.turnId, message.terminal)?.let { claimId ->
            val granted = grantedEpochs.remove(claimId)
            if (!message.terminal) {
                // Admitted: one more answered wake request, which this Watch's next window must know.
                if (granted != null && wakeEpoch == granted) wakeEpoch = granted + 1
                claimSender.forget(claimId)
            }
        }
        _talk.update { it.onPhoneState(message) }
        // A finished Watch turn may have added messages to the conversation being read, or created
        // a new conversation: re-read both through the Phone.
        if (message.terminal && ownTurn) {
            refreshSelected()
            loadSessions()
        }
    }

    // ── haptics ──────────────────────────────────────────────────────────────────────────────

    /**
     * One haptic from [WatchHapticPolicy], with its exact waveform (the platform "tick" effect played
     * ~100 ms on the emulator, far from the confirmed 10 ms step). On API 33+ it carries the policy's
     * usage: hardware feedback for recording start/end, touch feedback for scroll steps. No flag
     * bypasses Do Not Disturb or the user's vibration settings; older APIs use the default attributes.
     * Recording start/end timing is decided by [com.rumi.hermesvoice.core.watchlink.CaptureCoordinator],
     * never by button intent.
     */
    fun haptic(event: HapticEvent) {
        if (!_settings.value.hapticsEnabled) return
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            } ?: return
            if (!vibrator.hasVibrator()) return
            val effect = VibrationEffect.createWaveform(WatchHapticPolicy.patternFor(event).timings(), -1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val usage = when (WatchHapticPolicy.usageFor(event)) {
                    HapticUsage.HARDWARE_FEEDBACK -> VibrationAttributes.USAGE_HARDWARE_FEEDBACK
                    HapticUsage.TOUCH -> VibrationAttributes.USAGE_TOUCH
                }
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(usage))
            } else {
                vibrator.vibrate(effect)
            }
        }
    }

    /** A short status line shown only while nothing is recording, sending or playing. */
    fun notice(line: String) = _talk.update { if (it.canArmWakePhrase) it.copy(line = line) else it }

    // ── conversation reader ──────────────────────────────────────────────────────────────────

    /** Saved READY (even empty) is not a current Phone read. Keep an existing request's custody. */
    fun refreshSessionTitlesOnResume() {
        if (_reader.value.sessions.pendingReqId == null) loadSessions()
    }

    fun loadSessions() {
        val reqId = newRequestId()
        updateReader { it.requestSessions(reqId) }
        dispatch(ReaderRequest(reqId, ReaderKind.SESSIONS))
    }

    /**
     * Shows [sessionId] in chat. With the Phone's routing on, voice turns still go through the
     * router; with it off, they go to the conversation selected here ([selectedTarget]).
     */
    /** Handled arrival alerts of this Watch (bounded, durable, device-local): one alert per identity, even across a restart. */
    private val replyLedger by lazy { ReplyAlertLedger(localStore) }
    private val replyNotifier by lazy { WatchReplyAlertNotifier(this) }

    /** A conversation is "opened" only when it is the one shown on the chat surface AND the Watch app is in the foreground. */
    val presence: ConversationPresence by lazy {
        ConversationPresence().also {
            // Its stacked alerts go only once the conversation's history was read successfully again.
            it.onOpened = { id -> dismissAfterVisibleRead(id, fresh = true) }
        }
    }

    /**
     * The history of [sessionId] (the selected conversation) was read again and the read succeeded; waits until no read of it is pending
     * (a newer request that replaces the pending one is waited for too), else asks for one. Bounded.
     */
    private val openingReads = java.util.concurrent.atomic.AtomicLong()

    private fun dismissAfterVisibleRead(sessionId: String, fresh: Boolean) {
        val opening = openingReads.incrementAndGet()
        val generation = presence.generation
        if (!presence.isVisible(sessionId)) return
        // A return to CHAT/foreground must not reuse a request of an earlier opening.
        if (fresh) refreshSelected()
        scope.launch {
            if (readVisibleHistory(sessionId) && openingReads.get() == opening) {
                presence.ifVisible(sessionId, generation) {
                    if (openingReads.get() == opening) replyNotifier.dismissConversation(sessionId)
                }
            }
        }
    }

    private suspend fun readVisibleHistory(sessionId: String): Boolean {
        if (_reader.value.selectedSessionId != sessionId || !presence.isVisible(sessionId)) return false
        if (_reader.value.historyFor(sessionId)?.pendingReqId == null) refreshSelected()
        if (_reader.value.historyFor(sessionId)?.pendingReqId != null) {
            withTimeoutOrNull(WatchReaderLimits.REQUEST_TIMEOUT_MS + READ_WAIT_SLACK_MS) {
                _reader.first { it.selectedSessionId != sessionId || it.historyFor(sessionId)?.pendingReqId == null }
            } ?: return false
        }
        if (_reader.value.selectedSessionId != sessionId || !presence.isVisible(sessionId)) return false
        val history = _reader.value.historyFor(sessionId)
        return history != null && history.status == com.rumi.hermesvoice.core.watchlink.LoadStatus.READY && history.error == null
    }

    private fun updateReader(change: (WatchReaderState) -> WatchReaderState) {
        _reader.update(change)
        syncPresence()
    }

    private fun syncPresence() {
        val state = _reader.value
        presence.setShown(state.selectedSessionId?.takeIf { state.surface == ReaderSurface.CHAT })
    }

    fun onForeground(foreground: Boolean) {
        syncPresence()
        presence.setForeground(foreground)
        if (foreground) receivedFinals.recover()
    }

    fun contentDisplayed(id: String, messages: List<ReaderMessageRow>) = receivedFinals.displayed(id, messages)
    fun onSessionFinalReceipt(node: String, bytes: ByteArray) = receivedFinals.acknowledged(node, bytes)

    fun onSessionFinals(node: String, bytes: ByteArray) {
        if (bytes.size > 60_000) return
        val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return
        if (json.optInt("v") != 1) return
        val rows = json.optJSONArray("finals") ?: return
        receivedFinals.receive(node, json.optString("scope"), (0 until minOf(rows.length(),100)).map { rows.getJSONObject(it) })
    }

    /**
     * The Phone says a final answer for this Watch arrived without audio ([ReplyAlertMessage]): this Watch alone shows the alert
     * (local-only; the Phone showed none). A repeat of the same identity does nothing. Returns what was decided (null: not valid).
     */
    suspend fun onReplyAlert(bytes: ByteArray): ReplyAlertResult? {
        val message = ReplyAlertMessage.decode(bytes) ?: return null
        val alert = ReplyAlert(message.identity, message.sessionId)
        if (replyLedger.isHandled(alert.identity)) return ReplyAlertResult.DUPLICATE
        if (!replyLedger.begin(alert.identity)) return ReplyAlertResult.NOT_SHOWN_HERE
        var spent = false
        try {
            // Refresh the real reader only for the conversation already visible; never navigate on arrival.
            // This read is independent of alert delivery: failure/timeout must not spend or suppress its identity.
            if (presence.isVisible(message.sessionId)) refreshSelected()
            // The content-free wire cannot bind this final identity to a history row. An older assistant row is not
            // positive display of the new answer: keep one deduplicated alert, even when this conversation is visible.
            // A later explicit open/read uses presence.onOpened to dismiss it only after a successful history read.
            val shown = if (replica.current.privateAudio) replyNotifier.show(alert, null, true) else replyNotifier.show(alert)
            if (!shown) return ReplyAlertResult.NOT_SHOWN_HERE
            spent = true
            return ReplyAlertResult.SHOWN_HERE
        } finally {
            if (spent) replyLedger.commit(alert.identity) else replyLedger.release(alert.identity)
        }
    }

    /** A tapped arrival alert: the conversation it names is opened like the user's own choice. */
    fun openFromReplyAlert(sessionId: String) = selectSession(sessionId)

    fun selectSession(sessionId: String) {
        navigationGuard.onUserNavigation()
        val alreadyVisible = presence.isVisible(sessionId)
        openConversation(sessionId)
        if (alreadyVisible) dismissAfterVisibleRead(sessionId, fresh = false)
    }

    private fun openConversation(sessionId: String) {
        if (_reader.value.historyFor(sessionId) == null) recentBodies.load(sessionId)?.let { saved ->
            updateReader { state -> state.copy(histories = (state.histories +
                com.rumi.hermesvoice.core.watchlink.ReaderHistory(sessionId,
                    saved.page.messages.map { ReaderMessageRow(it.rowId, it.role, it.text, it.truncated, it.messageUid, it.timestamp, received = it.received) },
                    nextOlderOffset = saved.page.returned, hasOlder = saved.hasOlder))
                .takeLast(WatchReaderLimits.MAX_CACHED_SESSIONS)) }
        }
        val reqId = newRequestId()
        updateReader { it.select(sessionId, reqId) }
        // onOpened may have synchronously replaced this with a fresh opening read.
        if (_reader.value.historyFor(sessionId)?.pendingReqId == reqId) dispatch(ReaderRequest(reqId, ReaderKind.HISTORY, sessionId))
    }

    /**
     * The Phone says a request this Watch spoke was DELIVERED to a conversation ([WatchNavigation]). With the Phone-owned option on
     * and the guard satisfied (own newest request, once, from the node it went to, no user navigation since) the conversation is
     * selected and its history requested, for the next visit: nothing is launched and the screen is not woken. A newly created
     * conversation is also fetched into the list. Returns what was decided (null: not a valid message, or the option is off).
     */
    fun onNavigation(sourceNodeId: String, bytes: ByteArray): WatchNavigationGuard.Verdict? {
        val navigation = WatchNavigation.decode(bytes) ?: return null
        if (!_settings.value.watchAutoNavigateToRouted) return null
        val verdict = navigationGuard.accept(navigation, sourceNodeId)
        Log.i(TAG, "navigation turn=${navigation.turnId.take(12)} verdict=$verdict created=${navigation.created}")
        if (verdict != WatchNavigationGuard.Verdict.APPLY) return verdict
        openConversation(navigation.sessionId)
        if (navigation.created) loadSessions()
        return verdict
    }

    fun refreshSelected() {
        val reqId = newRequestId()
        var request: ReaderRequest? = null
        updateReader { state ->
            val next = state.refreshLatest(reqId)
            request = next?.selectedSessionId?.let { ReaderRequest(reqId, ReaderKind.HISTORY, it) }
            next ?: state
        }
        request?.let(::dispatch)
    }

    fun loadOlder() {
        val reqId = newRequestId()
        var request: ReaderRequest? = null
        updateReader { state ->
            val next = state.requestOlder(reqId)
            val history = next?.selectedHistory
            request = history?.let { ReaderRequest(reqId, ReaderKind.HISTORY, it.sessionId, offset = it.nextOlderOffset) }
            next ?: state
        }
        request?.let(::dispatch)
    }

    fun toggleReaderSurface() {
        navigationGuard.onUserNavigation()
        updateReader { it.toggleSurface() }
        if (_reader.value.surface == ReaderSurface.SESSIONS) loadSessions()
    }

    fun onReaderResponse(sourceNodeId: String, bytes: ByteArray) {
        val response = ReaderResponse.decode(bytes) ?: return
        val expectedNode = readerTargets[response.reqId]
        if (expectedNode == null || expectedNode != sourceNodeId) {
            Log.w(TAG, "reader response ignored req=${response.reqId.take(12)} (not pending from this node)")
            return
        }
        // Do not consume transport custody for a valid node's wrong kind/session/page. The
        // reducer ignores these too, but retaining the target lets the real reply or timeout settle.
        val pending = _reader.value
        when (response.kind) {
            ReaderKind.SESSIONS -> if (pending.sessions.pendingReqId != response.reqId) return
            ReaderKind.HISTORY -> {
                val history = pending.pendingHistoryRequest(response.reqId) ?: return
                if (history.sessionId != response.sessionId || history.sessionId != pending.selectedSessionId) return
                if (response.ok && response.offset != (if (history.loadingOlder) history.nextOlderOffset else 0)) return
            }
        }
        readerTargets.remove(response.reqId, sourceNodeId)
        val bodyNamespace=response.bodyScope?:response.scope
        val changedScope = (response.scope != null && readerTitleScope != null && response.scope != readerTitleScope) ||
            (bodyNamespace!=null && readerBodyScope!=null && bodyNamespace!=readerBodyScope)
        val signedOut = response.error in setOf(ReaderError.SIGN_IN_REQUIRED, ReaderError.NOT_CONFIGURED)
        if (changedScope || signedOut) {
            invalidateReaderTitles(sourceNodeId, response.scope)
            readerBodyScope=if(signedOut) null else bodyNamespace
            if (response.kind == ReaderKind.HISTORY) {
                if (signedOut) updateReader { it.copy(sessions = ReaderSessionsState(status = LoadStatus.ERROR, error = response.error)) }
                else loadSessions()
                return
            }
            if (response.kind == ReaderKind.SESSIONS) updateReader { it.requestSessions(response.reqId) }
        }
        if (response.scope != null) readerTitleScope = response.scope
        if (!signedOut && response.scope != null) readerBodyScope = bodyNamespace
        if (response.kind == ReaderKind.SESSIONS && response.ok && response.scope == null) {
            // A legacy peer can display its current result, but cannot authenticate a durable account namespace.
            invalidateReaderTitles(sourceNodeId, clearState = false)
        }
        updateReader { it.onResponse(response) }
        if (response.kind == ReaderKind.SESSIONS && response.ok) {
            response.removedSessionIds.forEach { recentBodies.evict(it) }
            response.sessions.forEach { recentBodies.eligible(it.id) }
            receivedFinals.remove(response.removedSessionIds.toSet())
            receivedFinals.eligible(response.sessions.map { it.id }.toSet())
        }
        if (response.ok && response.scope != null) receivedFinals.receive(sourceNodeId, bodyNamespace, response.finals)
        if (response.kind == ReaderKind.HISTORY && response.ok && response.scope != null &&
            pending.pendingHistoryRequest(response.reqId)?.loadingOlder == false) {
            val epoch = bodyGeneration.get()
            recentBodies.remember(com.rumi.hermesvoice.core.net.HistoryPage(response.sessionId!!,
                response.messages.map { com.rumi.hermesvoice.core.net.HistoryMessage(it.rowId, it.role, it.text, it.timestamp, it.truncated, it.messageUid, finishReason = if(it.received) null else "nonfinal") },
                response.nextOffset), response.hasOlder) {
                bodyGeneration.get() == epoch && readerTitlePhone == sourceNodeId && readerTitleScope == response.scope && readerBodyScope==bodyNamespace
            }
        }
        if (response.kind == ReaderKind.HISTORY && response.error == ReaderError.NOT_OWNED) {
            recentBodies.invalidate()
            updateReader { it.copy(sessions = it.sessions.copy(rows = it.sessions.rows.filterNot { row -> row.id == response.sessionId })) }
            saveReaderTitles()
        } else if (response.kind == ReaderKind.SESSIONS && response.ok) saveReaderTitles()
        // A conversation turned out to be gone: reload the browser so it cannot be tapped again.
        if (_reader.value.sessions.stale) loadSessions()
        Log.i(TAG, "reader ${response.kind.wire} req=${response.reqId.take(12)} ok=${response.ok} error=${response.error} " +
            "sessions=${response.sessions.size} messages=${response.messages.size} has_older=${response.hasOlder}")
    }

    private fun readerRequestPending(request: ReaderRequest): Boolean {
        val pending = _reader.value
        if (request.kind == ReaderKind.SESSIONS) return pending.sessions.pendingReqId == request.reqId
        val selected = pending.selectedSessionId ?: return false
        return pending.pendingHistoryRequest(request.reqId)?.sessionId == selected
    }

    /**
     * Sends a read request to the Phone. A failed Data Layer call proves nothing about whether the Phone got the message, so a call that
     * failed for a TRANSIENT typed reason, or a missing Phone node, is sent again with the same id after 1 s and then 2 s, and only while
     * it is still the pending request and the ONE original 15 s window (counted from the first try, lookups and sends included) has not
     * closed. A final failure (sign-in, access, security, protocol, size, cancellation, anything unrecognised) is shown at once. The retries
     * already spent travel with the request so the Phone continues the same allowance and the whole refresh makes at most two. A request
     * the Phone received is never sent again here: the Phone owns its dashboard retries and coalesces a repeated id.
     */
    private fun dispatch(request: ReaderRequest) {
        scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            val elapsedMs = { SystemClock.elapsedRealtime() - startedAt }
            var retries = 0
            while (true) {
                val lookup = dataLayerCall { lookUpPhone() }
                val node = lookup.getOrNull()
                var failure = "no_phone"
                if (node == null) {
                    _phoneReachable.value = false
                    if (lookup.isFailure && !ReaderSendAdmission.transient(lookup.exceptionOrNull()!!)) {
                        updateReader { it.onSendFailed(request.reqId) }
                        return@launch
                    }
                } else {
                    if (!readerRequestPending(request)) return@launch
                    if (elapsedMs() >= WatchReaderLimits.REQUEST_TIMEOUT_MS) {
                        updateReader { it.onSendFailed(request.reqId) }
                        return@launch
                    }
                    val changedPhone = readerTitlePhone != null && readerTitlePhone != node
                    observeReaderPhone(node)
                    if (changedPhone) {
                        if (request.kind == ReaderKind.HISTORY) { loadSessions(); return@launch }
                        updateReader { it.requestSessions(request.reqId) }
                    }
                    readerTargets[request.reqId] = node
                    val wire = request.copy(retriesUsed = retries).encode()
                    val sent = dataLayerCall {
                        Wearable.getMessageClient(this@WatchApp).sendMessage(node, WatchLinkPaths.READER_REQUEST, wire).await()
                    }
                    Log.i(TAG, "reader request ${request.kind.wire} req=${request.reqId.take(12)} offset=${request.offset} sent=${sent.isSuccess}")
                    if (sent.isSuccess) {
                        delay((WatchReaderLimits.REQUEST_TIMEOUT_MS - elapsedMs()).coerceAtLeast(0L))
                        if (readerTargets.remove(request.reqId) != null) updateReader { it.onTimeout(request.reqId) }
                        return@launch
                    }
                    readerTargets.remove(request.reqId)
                    if (!ReaderSendAdmission.transient(sent.exceptionOrNull()!!)) {
                        updateReader { it.onSendFailed(request.reqId) }
                        return@launch
                    }
                    failure = "send_refused"
                }
                val wait = ReadRetryBudget.LOCAL_DELAYS_MS.getOrNull(retries)
                if (wait == null || elapsedMs() + wait >= WatchReaderLimits.REQUEST_TIMEOUT_MS) {
                    updateReader { it.onSendFailed(request.reqId) }
                    return@launch
                }
                retries += 1
                Log.i(TAG, "reader request ${request.kind.wire} retry=$retries/${ReadRetryBudget.MAX_RETRIES} cause=$failure wait_ms=$wait")
                delay(wait)
                if (!readerRequestPending(request)) return@launch
                if (elapsedMs() >= WatchReaderLimits.REQUEST_TIMEOUT_MS) {
                    updateReader { it.onSendFailed(request.reqId) }
                    return@launch
                }
            }
        }
    }

    private fun newRequestId(): String = "rd-" + UUID.randomUUID().toString()

    /**
     * Debuggable QA only (see WatchActivity): synthetic, content-free reader rows applied through the
     * same request-id reducer as Phone responses, without the Data Layer. It exercises the reader UI
     * (bubbles, scrolling, bezel, follow-latest) on an unpaired Watch; it proves nothing about transport.
     * The first call opens a fixture conversation with rows up to [newest]; later calls add newer rows.
     */
    fun seedReaderForQa(count: Int, newest: Long) {
        val sessionId = "qa-fixture-1"
        val sessionsReq = newRequestId()
        val rows = (1..3).map { ReaderSessionRow("qa-fixture-$it", "QA fixture $it", "qa$it", "synthetic rows", 0.0) }
        updateReader { it.requestSessions(sessionsReq).onResponse(ReaderResponse(sessionsReq, ReaderKind.SESSIONS, ok = true, sessions = rows)) }
        val reqId = newRequestId()
        val opening = _reader.value.selectedSessionId != sessionId
        val first = if (opening) newest - count + 1 else newest - 4
        val messages = (first..newest).map { ReaderMessageRow(it, if (it % 2 == 0L) "assistant" else "user", "QA row $it: synthetic reader fixture text", false) }
        updateReader { state ->
            val requested = if (opening) state.select(sessionId, reqId) else state.refreshLatest(reqId) ?: state
            requested.onResponse(ReaderResponse(reqId, ReaderKind.HISTORY, ok = true, sessionId = sessionId, messages = messages,
                offset = 0, nextOffset = count, hasOlder = false))
        }
        Log.i(TAG, "qa reader fixture rows=${messages.size} newest=$newest opening=$opening")
    }

    // ── playback ─────────────────────────────────────────────────────────────────────────────

    /** Source-bound channel admission: a Stop while reading cannot admit an old Phone frame later. */
    fun playReceived(request: PlayRequest, nodeId: String, receivedGeneration: Long) {
        if (receivedGeneration != playbackStopGeneration) return refusePlayback(request, nodeId, "stopped on the watch")
        play(request, nodeId)
    }

    /**
     * One utterance at a time (the Phone waits for our ACK before sending the next). The utterance
     * may belong to a Phone turn: the Phone plays everything on the latest voice sender. A
     * successful `/hv/v1/played` ACK is sent only from this player's completion callback, i.e.
     * after the audio actually played; every other end (error, stop, replacement) ACKs `ok=false`.
     */
    fun play(request: PlayRequest, nodeId: String) {
        // Reject cancelled authority before touching an unrelated player, focus, speaker or hold.
        if (request.turnId in stoppedTurns) return refusePlayback(request, nodeId, "stopped on the watch")
        // The Phone's headset is the only output: nothing is played here, whoever asked and whatever the reply is.
        if (replica.current.privateAudio) return refusePlayback(request, nodeId, PRIVATE_HEADSET)
        // A later reply never plays over a recording: refused as busy (not played, not failed), and what plays now goes on.
        LaterPlaybackGuard.refusal(request, recordingNow())?.let { busy -> return refusePlayback(request, nodeId, busy) }
        // Completion must not forget the authority Stop needs to revoke between utterances.
        // Invalid receive generations and cancelled/busy frames never enter this live ledger.
        unstoppedResponseTurns += request.turnId
        stopPlayback("superseded")
        diag.record(DiagCode.WATCH_PLAY_RECEIVED, DiagOrigin.WATCH, request.turnId, n = request.sequence, ms = null)
        Log.i(TAG, "play received turn=${request.turnId.take(12)} seq=${request.sequence} role=${request.role} " +
            "bytes=${request.audio.size} from=${nodeId.take(8)} own_turn=${request.turnId == _talk.value.turnId}")
        // Audio focus, as any player: not granted (a call, or a policy that silences this app) means not played.
        if (!requestFocus()) return refusePlayback(request, nodeId, "audio focus denied")
        holds.acquire(HoldReason.PLAYBACK)
        val file = File(cacheDir, "hv-play-${request.sequence}.${if (request.mimeType.contains("wav")) "wav" else "mp3"}")
        file.writeBytes(request.audio)
        _talk.update { it.playing(request.turnId) }
        val mp = MediaPlayer()
        player = mp
        playing = request
        playingNode = nodeId
        mp.setAudioAttributes(playbackAttributes)
        // The player keeps the CPU awake while it plays, screen on or off.
        mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
        // A callback from a player that was already replaced must not end (or ACK) the current one.
        mp.setOnCompletionListener { if (player === it) finishPlayback(ok = true, error = "") }
        mp.setOnErrorListener { it, what, extra ->
            if (player === it) finishPlayback(ok = false, error = "watch player $what/$extra")
            true
        }
        try {
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener {
                if (player === it) {
                    it.start()
                    Log.i(TAG, "playback started turn=${request.turnId.take(12)} seq=${request.sequence}")
                    reportProgress(it, request, nodeId)
                }
            }
            mp.prepareAsync()
        } catch (error: Exception) {
            finishPlayback(ok = false, error = error.javaClass.simpleName)
        }
    }

    /** Phone asked us to stop this turn (a newer turn took the speaker), or a new utterance replaced it. */
    fun stopPlayback(reason: String, turnId: String? = null) {
        val current = playing ?: return
        if (turnId != null && current.turnId != turnId) return
        finishPlayback(ok = false, error = reason)
    }

    /**
     * Tells the Phone where this player really is (position and duration read from it, never estimated) every few seconds
     * while this exact clip plays, so a long clip is not mistaken for a dead one. A stalled player reports the same
     * position, which the Phone does not count as progress. Ends with the playback.
     */
    private fun reportProgress(mp: MediaPlayer, request: PlayRequest, node: String) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (player === mp && playing === request) {
                val position = runCatching { mp.currentPosition.toLong() }.getOrNull()
                val duration = runCatching { mp.duration.toLong() }.getOrNull()
                if (position != null && duration != null && duration > 0 && position in 0..duration) {
                    try {
                        withTimeoutOrNull(PROGRESS_SEND_MS) {
                            Wearable.getMessageClient(this@WatchApp)
                                .sendMessage(node, WatchLinkPaths.PLAY_PROGRESS, PlayProgress(request.turnId, request.sequence, position, duration).encode()).await()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                    }
                }
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private fun finishPlayback(ok: Boolean, error: String) {
        val request = playing ?: return
        progressJob?.cancel()
        progressJob = null
        val node = playingNode
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        playing = null
        playingNode = null
        abandonFocus()
        lastPlaybackEndedAtMs = SystemClock.elapsedRealtime()
        _talk.update { it.playbackEnded(request.turnId) }
        if (node == null) holds.release(HoldReason.PLAYBACK)
        else sendPlayed(request, node, ok, error, releasePlaybackHold = true)
    }

    /** Not played at all: the Phone is told so (it never waits for a timeout, and never takes it for played). */
    private fun refusePlayback(request: PlayRequest, nodeId: String, error: String) {
        sendPlayed(request, nodeId, ok = false, error = error)
    }

    private fun sendPlayed(request: PlayRequest, node: String, ok: Boolean, error: String, releasePlaybackHold: Boolean = false) {
        val refusal = when {
            ok -> null
            error == PlayedAck.BUSY_RECORDING || error == PRIVATE_HEADSET || error == "audio focus denied" -> DiagCause.LOCAL
            error == "stopped on the watch" -> DiagCause.CANCELLED
            else -> DiagCause.UNKNOWN
        }
        diag.record(if (ok) DiagCode.PLAYBACK_DONE else DiagCode.PLAYBACK_FAILED, DiagOrigin.WATCH, request.turnId, n = request.sequence,
            fail = if (ok) null else if (error == PlayedAck.BUSY_RECORDING) DiagFail.BUSY else refusal?.let { DiagClassify.failOf(it) } ?: DiagFail.UNKNOWN,
            stage = if (ok) null else DiagStage.PLAYBACK, cause = refusal, modality = if (ok) null else DiagModality.VOICE)
        val token = Any()
        pendingPlayedAcks += token
        playedAckHolds.acquire(HoldReason.PLAYBACK, ACK_HOLD_MS)
        // Take successor CPU ownership first, then release the finished player BEFORE transport
        // can complete/reenter. The launched ACK never releases any current player's hold.
        if (releasePlaybackHold) holds.release(HoldReason.PLAYBACK)
        scope.launch {
            try {
                val sent = try {
                    withTimeoutOrNull(ACK_HOLD_MS) {
                        Wearable.getMessageClient(this@WatchApp)
                            .sendMessage(node, WatchLinkPaths.PLAYED, PlayedAck(request.turnId, request.sequence, ok, error).encode()).await()
                        true
                    } == true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                Log.i(TAG, "played ack turn=${request.turnId.take(12)} seq=${request.sequence} ok=$ok" +
                    (if (ok) "" else " error=$error") + " sent=$sent")
            } finally {
                pendingPlayedAcks -= token
                if (pendingPlayedAcks.isEmpty()) playedAckHolds.release(HoldReason.PLAYBACK)
            }
        }
    }

    private val playbackAttributes: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    /** Each acquisition owns its request/listener; abandoned callbacks cannot inherit a replacement. */
    @Volatile private var focusRequest: AudioFocusRequest? = null

    private fun requestFocus(): Boolean {
        abandonFocus()
        val lost = java.util.concurrent.atomic.AtomicBoolean(false)
        lateinit var acquired: AudioFocusRequest
        acquired = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(playbackAttributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    // Mark loss even before requestAudioFocus returns; no player exists yet then.
                    lost.set(true)
                    if (focusRequest === acquired) scope.launch {
                        // Recheck after queueing: Stop, completion or replacement may have revoked it.
                        if (focusRequest === acquired) {
                            if (playing != null) stopPlayback("audio focus lost") else abandonFocus()
                        }
                    }
                }
            }.build()
        focusRequest = acquired
        val granted = runCatching {
            getSystemService(AudioManager::class.java).requestAudioFocus(acquired) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }.getOrDefault(false)
        if (!granted || lost.get() || focusRequest !== acquired) {
            if (focusRequest === acquired) abandonFocus()
            return false
        }
        return true
    }

    private fun abandonFocus() {
        val abandoned = focusRequest ?: return
        // Invalidate before calling the platform, which may synchronously or later dispatch loss.
        focusRequest = null
        runCatching { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(abandoned) }
    }

    companion object {
        private const val TAG = "HermesVoiceWatch"
        private const val PREFS = "hermes_voice_watch"
        private const val KEY_SETTINGS = "settings_json"
        /** The ACK error of a clip refused or stopped because the Phone's headset is the only output (never "played"). */
        const val PRIVATE_HEADSET = "private_headset"
        private const val ACK_HOLD_MS = 10_000L
        private const val PROGRESS_INTERVAL_MS = 5_000L
        private const val PROGRESS_SEND_MS = 4_000L
        private const val READ_WAIT_SLACK_MS = 2_000L
        private const val DIAG_SEND_MS = 5_000L

        fun from(context: Context): WatchApp = context.applicationContext as WatchApp
    }
}

/** A private SharedPreferences file as the core's key-value seam. */
private class PrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun commitString(key: String, value: String): Boolean = prefs.edit().putString(key, value).commit()
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
}

/** One partial wake lock per reason; every acquire carries the timeout [WakeHolds] bounded. */
class AndroidWakeLocks(context: Context, private val tagPrefix: String = "HermesVoice") : WakeLockPort {
    private val power = context.applicationContext.getSystemService(PowerManager::class.java)
    private val locks = HashMap<HoldReason, PowerManager.WakeLock>()

    @Synchronized
    override fun acquire(reason: HoldReason, timeoutMs: Long) {
        val lock = locks.getOrPut(reason) {
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$tagPrefix:${reason.name.lowercase()}").apply { setReferenceCounted(false) }
        }
        lock.acquire(timeoutMs)
    }

    @Synchronized
    override fun release(reason: HoldReason) {
        locks[reason]?.takeIf { it.isHeld }?.release()
    }
}
