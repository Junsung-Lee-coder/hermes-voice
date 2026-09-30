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
import com.rumi.hermesvoice.core.watchlink.HapticEvent
import com.rumi.hermesvoice.core.watchlink.HapticUsage
import com.rumi.hermesvoice.core.watchlink.PlayRequest
import com.rumi.hermesvoice.core.watchlink.PlayedAck
import com.rumi.hermesvoice.core.watchlink.ReaderKind
import com.rumi.hermesvoice.core.watchlink.ReaderMessageRow
import com.rumi.hermesvoice.core.watchlink.ReaderRequest
import com.rumi.hermesvoice.core.watchlink.ReaderResponse
import com.rumi.hermesvoice.core.watchlink.ReaderSessionRow
import com.rumi.hermesvoice.core.watchlink.ReaderSurface
import com.rumi.hermesvoice.core.watchlink.TurnStateMessage
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchHapticPolicy
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchReaderLimits
import com.rumi.hermesvoice.core.watchlink.WatchReaderState
import com.rumi.hermesvoice.core.watchlink.WatchTalkState
import com.rumi.hermesvoice.core.watchlink.WatchTurnUpload
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

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
    private val _talk = MutableStateFlow(WatchTalkState())
    val talk: StateFlow<WatchTalkState> = _talk
    private val _settings = MutableStateFlow(WatchSettings())
    val settings: StateFlow<WatchSettings> = _settings
    private val _reader = MutableStateFlow(WatchReaderState())

    /** Conversation reader: session browser and the selected conversation's cached history. */
    val reader: StateFlow<WatchReaderState> = _reader

    /** The Phone node each pending reader request went to; a response from any other node is ignored. */
    private val readerTargets = ConcurrentHashMap<String, String>()

    private var player: MediaPlayer? = null
    private var playing: PlayRequest? = null
    private var playingNode: String? = null

    /** Elapsed-realtime millis when Watch playback last ended (wake-phrase cooldown). */
    @Volatile var lastPlaybackEndedAtMs: Long = 0L
        private set

    private lateinit var replica: WatchSettingsReplica

    /** Device-local choices of this Watch (not the Phone-owned voice settings): the background opt-in. */
    val localStore: KeyValueStore by lazy { PrefsStore(getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)) }

    /** CPU wake locks, one per reason and each with a timeout (see [WakeHolds]). */
    val holds: WakeHolds by lazy { WakeHolds(AndroidWakeLocks(this), SystemClock::elapsedRealtime) }

    /** The one voice runtime (wake phrase, recorder, background session); created with the process, on the main thread. */
    lateinit var voice: WatchVoiceRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_SETTINGS, null)
        replica = WatchSettingsReplica(stored)
        // Durable migration: a copy from before the wake-location selector (or an unreadable one) is rewritten once.
        if (stored != null && stored != replica.current.toJson()) prefs.edit().putString(KEY_SETTINGS, replica.current.toJson()).apply()
        _settings.value = replica.current
        WatchVoiceService.createChannel(this)
        voice = WatchVoiceRuntime(this)
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
        }
        Log.i(TAG, "settings $result revision=${current.revision} wake_location=${current.wakeLocation} " +
            "watch_listens=${current.watchWakeEnabled} vad_silence_s=${current.vadSilenceSeconds} haptics=${current.hapticsEnabled}")
    }

    private val _phoneReachable = MutableStateFlow<Boolean?>(null)

    /** Whether a nearby Phone advertising the app capability is reachable; null until first checked. */
    val phoneReachable: StateFlow<Boolean?> = _phoneReachable

    suspend fun refreshPhoneReachable() {
        _phoneReachable.value = phoneNode() != null
    }

    private suspend fun phoneNode(): String? = runCatching {
        Wearable.getCapabilityClient(this).getCapability(WatchLinkPaths.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
            .await().nodes.firstOrNull { it.isNearby }?.id
    }.getOrNull()

    fun newTurn(trigger: TurnTrigger): String? {
        val turnId = UUID.randomUUID().toString()
        return runCatching { _talk.update { it.startRecording(turnId, trigger) } }.map { turnId }.getOrNull()
    }

    fun discard(reason: String) = _talk.update { it.recordingDiscarded(reason) }

    /** Updates the prompt of the capture in progress. */
    fun cue(line: String) = _talk.update { it.recordingCue(line) }

    /** Uploads the finished recording to the reachable Phone node that advertises the app capability. */
    fun upload(turnId: String, trigger: TurnTrigger, wav: ByteArray, wakeClaimId: String? = null) {
        val claimId = wakeClaimId?.takeIf { trigger == TurnTrigger.WAKE_PHRASE }
        // Recording ended, but the claim is this Watch's until the Phone has answered the turn.
        claimId?.let { keepClaimInTransit(it, turnId, wav.size) }
        send(turnId, WatchTurnUpload(turnId, trigger, WatchTurnUpload.MIME_WAV, wav, claimId))
    }

    /** Sends a wake-phrase request the recognizer already heard (no second utterance was recorded). */
    fun uploadRecognized(turnId: String, request: String, wakeClaimId: String? = null) {
        wakeClaimId?.let { keepClaimInTransit(it, turnId, request.length) }
        send(turnId, WatchTurnUpload.recognized(turnId, request, wakeClaimId))
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

    /** Turns the user stopped from this Watch: whatever the Phone still sends for them is not played. */
    private val stoppedTurns = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * The background session was stopped with the app hidden: recordings on their way to the link
     * are withdrawn (their wake claims given back), and the turn the Watch was waiting on is
     * dropped here, so nothing of it is played later.
     */
    fun cancelPendingUploads(reason: String) {
        if (stoppedTurns.size >= 16) stoppedTurns.clear()
        val turnId = _talk.value.turnId
        stoppedTurns += uploads.keys + listOfNotNull(turnId)
        uploads.forEach { (id, job) ->
            uploadStops[id] = reason
            job.cancel()
        }
        _talk.update { if (turnId != null && it.turnId == turnId) it.sendFailed(reason) else it }
        if (turnId != null) Log.i(TAG, "turn ${turnId.take(12)} stopped on the watch reason=$reason")
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
                val phone = phoneNode() ?: error("Phone not reachable")
                val channels = Wearable.getChannelClient(this@WatchApp)
                val channel = channels.openChannel(phone, WatchLinkPaths.turnPath(turnId)).await()
                val output = channels.getOutputStream(channel).await()
                val frame = upload.toFrame().encode()
                withContext(Dispatchers.IO) { output.use { it.write(frame); it.flush() } }
            }.exceptionOrNull()
            if (failure == null) {
                Log.i(TAG, "upload sent turn=${turnId.take(12)} trigger=${upload.trigger} kind=" +
                    (if (upload.recognizedText != null) "recognized_request" else "wav") + " bytes=${upload.audio.size}")
            } else {
                val stopped = uploadStops.remove(turnId)
                Log.w(TAG, "upload failed: ${stopped ?: failure.message}")
                transit.onTransferFailed(turnId)
                _talk.update { if (it.turnId == turnId) it.sendFailed(stopped ?: failure.message ?: "Could not reach the phone") else it }
            }
            uploads.remove(turnId)
            if (uploads.isEmpty()) holds.release(HoldReason.TRANSFER)
        }
        uploads[turnId] = job
        job.start()
    }

    fun onPhoneState(message: TurnStateMessage) {
        Log.i(TAG, "phone state turn=${message.turnId.take(12)} stage=${message.stage} terminal=${message.terminal}")
        val ownTurn = message.turnId == _talk.value.turnId
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

    fun loadSessions() {
        val reqId = newRequestId()
        _reader.update { it.requestSessions(reqId) }
        dispatch(ReaderRequest(reqId, ReaderKind.SESSIONS))
    }

    /** Shows [sessionId] in chat. Only what is displayed changes: voice turns still go through the router. */
    fun selectSession(sessionId: String) {
        val reqId = newRequestId()
        _reader.update { it.select(sessionId, reqId) }
        dispatch(ReaderRequest(reqId, ReaderKind.HISTORY, sessionId))
    }

    fun refreshSelected() {
        val reqId = newRequestId()
        var request: ReaderRequest? = null
        _reader.update { state ->
            val next = state.refreshLatest(reqId)
            request = next?.selectedSessionId?.let { ReaderRequest(reqId, ReaderKind.HISTORY, it) }
            next ?: state
        }
        request?.let(::dispatch)
    }

    fun loadOlder() {
        val reqId = newRequestId()
        var request: ReaderRequest? = null
        _reader.update { state ->
            val next = state.requestOlder(reqId)
            val history = next?.selectedHistory
            request = history?.let { ReaderRequest(reqId, ReaderKind.HISTORY, it.sessionId, offset = it.nextOlderOffset) }
            next ?: state
        }
        request?.let(::dispatch)
    }

    fun toggleReaderSurface() {
        _reader.update { it.toggleSurface() }
        if (_reader.value.surface == ReaderSurface.SESSIONS) loadSessions()
    }

    fun onReaderResponse(sourceNodeId: String, bytes: ByteArray) {
        val response = ReaderResponse.decode(bytes) ?: return
        val expectedNode = readerTargets.remove(response.reqId)
        if (expectedNode == null || expectedNode != sourceNodeId) {
            Log.w(TAG, "reader response ignored req=${response.reqId.take(12)} (not pending from this node)")
            return
        }
        _reader.update { it.onResponse(response) }
        // A conversation turned out to be gone: reload the browser so it cannot be tapped again.
        if (_reader.value.sessions.stale) loadSessions()
        Log.i(TAG, "reader ${response.kind.wire} req=${response.reqId.take(12)} ok=${response.ok} error=${response.error} " +
            "sessions=${response.sessions.size} messages=${response.messages.size} has_older=${response.hasOlder}")
    }

    private fun dispatch(request: ReaderRequest) {
        scope.launch {
            val node = phoneNode()
            if (node == null) {
                _phoneReachable.value = false
                _reader.update { it.onSendFailed(request.reqId) }
                return@launch
            }
            readerTargets[request.reqId] = node
            val sent = runCatching {
                Wearable.getMessageClient(this@WatchApp).sendMessage(node, WatchLinkPaths.READER_REQUEST, request.encode()).await()
            }
            Log.i(TAG, "reader request ${request.kind.wire} req=${request.reqId.take(12)} offset=${request.offset} sent=${sent.isSuccess}")
            if (sent.isFailure) {
                readerTargets.remove(request.reqId)
                _reader.update { it.onSendFailed(request.reqId) }
                return@launch
            }
            delay(WatchReaderLimits.REQUEST_TIMEOUT_MS)
            if (readerTargets.remove(request.reqId) != null) _reader.update { it.onTimeout(request.reqId) }
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
        _reader.update { it.requestSessions(sessionsReq).onResponse(ReaderResponse(sessionsReq, ReaderKind.SESSIONS, ok = true, sessions = rows)) }
        val reqId = newRequestId()
        val opening = _reader.value.selectedSessionId != sessionId
        val first = if (opening) newest - count + 1 else newest - 4
        val messages = (first..newest).map { ReaderMessageRow(it, if (it % 2 == 0L) "assistant" else "user", "QA row $it: synthetic reader fixture text", false) }
        _reader.update { state ->
            val requested = if (opening) state.select(sessionId, reqId) else state.refreshLatest(reqId) ?: state
            requested.onResponse(ReaderResponse(reqId, ReaderKind.HISTORY, ok = true, sessionId = sessionId, messages = messages,
                offset = 0, nextOffset = count, hasOlder = false))
        }
        Log.i(TAG, "qa reader fixture rows=${messages.size} newest=$newest opening=$opening")
    }

    // ── playback ─────────────────────────────────────────────────────────────────────────────

    /**
     * One utterance at a time (the Phone waits for our ACK before sending the next). The utterance
     * may belong to a Phone turn: the Phone plays everything on the latest voice sender. A
     * successful `/hv/v1/played` ACK is sent only from this player's completion callback, i.e.
     * after the audio actually played; every other end (error, stop, replacement) ACKs `ok=false`.
     */
    fun play(request: PlayRequest, nodeId: String) {
        stopPlayback("superseded")
        Log.i(TAG, "play received turn=${request.turnId.take(12)} seq=${request.sequence} role=${request.role} " +
            "bytes=${request.audio.size} from=${nodeId.take(8)} own_turn=${request.turnId == _talk.value.turnId}")
        if (request.turnId in stoppedTurns) return refusePlayback(request, nodeId, "stopped on the watch")
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

    private fun finishPlayback(ok: Boolean, error: String) {
        val request = playing ?: return
        val node = playingNode
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        playing = null
        playingNode = null
        abandonFocus()
        lastPlaybackEndedAtMs = SystemClock.elapsedRealtime()
        _talk.update { it.playbackEnded(request.turnId) }
        if (node == null) return holds.release(HoldReason.PLAYBACK)
        sendPlayed(request, node, ok, error)
    }

    /** Not played at all: the Phone is told so (it never waits for a timeout, and never takes it for played). */
    private fun refusePlayback(request: PlayRequest, nodeId: String, error: String) {
        holds.acquire(HoldReason.PLAYBACK, ACK_HOLD_MS)
        sendPlayed(request, nodeId, ok = false, error = error)
    }

    private fun sendPlayed(request: PlayRequest, node: String, ok: Boolean, error: String) {
        scope.launch {
            val sent = runCatching {
                Wearable.getMessageClient(this@WatchApp)
                    .sendMessage(node, WatchLinkPaths.PLAYED, PlayedAck(request.turnId, request.sequence, ok, error).encode()).await()
            }
            Log.i(TAG, "played ack turn=${request.turnId.take(12)} seq=${request.sequence} ok=$ok" +
                (if (ok) "" else " error=$error") + " sent=${sent.isSuccess}")
            // The next utterance takes its own hold; none is playing now.
            if (playing == null) holds.release(HoldReason.PLAYBACK)
        }
    }

    private val playbackAttributes: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    /** Losing the focus for good or for a while (a call, another player) ends the utterance; it is acknowledged as not played. */
    private val focusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(playbackAttributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    scope.launch { stopPlayback("audio focus lost") }
                }
            }.build()
    }

    private fun requestFocus(): Boolean = runCatching {
        getSystemService(AudioManager::class.java).requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }.getOrDefault(false)

    private fun abandonFocus() {
        runCatching { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(focusRequest) }
    }

    companion object {
        private const val TAG = "HermesVoiceWatch"
        private const val PREFS = "hermes_voice_watch"
        private const val KEY_SETTINGS = "settings_json"
        private const val LOCAL_PREFS = "hermes_voice_watch_local"
        private const val ACK_HOLD_MS = 10_000L

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
class AndroidWakeLocks(context: Context) : WakeLockPort {
    private val power = context.applicationContext.getSystemService(PowerManager::class.java)
    private val locks = HashMap<HoldReason, PowerManager.WakeLock>()

    @Synchronized
    override fun acquire(reason: HoldReason, timeoutMs: Long) {
        val lock = locks.getOrPut(reason) {
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HermesVoice:${reason.name.lowercase()}").apply { setReferenceCounted(false) }
        }
        lock.acquire(timeoutMs)
    }

    @Synchronized
    override fun release(reason: HoldReason) {
        locks[reason]?.takeIf { it.isHeld }?.release()
    }
}
