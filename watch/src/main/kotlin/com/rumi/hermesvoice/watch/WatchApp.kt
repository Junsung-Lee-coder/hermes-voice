package com.rumi.hermesvoice.watch

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.settings.ReplicaUpdate
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
import kotlinx.coroutines.Dispatchers
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
 * through the Phone ([reader]).
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

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_SETTINGS, null)
        replica = WatchSettingsReplica(stored)
        // Durable migration: a copy from before the wake-location selector (or an unreadable one) is rewritten once.
        if (stored != null && stored != replica.current.toJson()) prefs.edit().putString(KEY_SETTINGS, replica.current.toJson()).apply()
        _settings.value = replica.current
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
    fun upload(turnId: String, trigger: TurnTrigger, wav: ByteArray) =
        send(turnId, WatchTurnUpload(turnId, trigger, WatchTurnUpload.MIME_WAV, wav))

    /** Sends a wake-phrase request the recognizer already heard (no second utterance was recorded). */
    fun uploadRecognized(turnId: String, request: String) = send(turnId, WatchTurnUpload.recognized(turnId, request))

    private fun send(turnId: String, upload: WatchTurnUpload) {
        _talk.update { if (it.turnId == turnId) it.sending() else it }
        scope.launch {
            val failure = runCatching {
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
                Log.w(TAG, "upload failed: ${failure.message}")
                _talk.update { if (it.turnId == turnId) it.sendFailed(failure.message ?: "Could not reach the phone") else it }
            }
        }
    }

    fun onPhoneState(message: TurnStateMessage) {
        Log.i(TAG, "phone state turn=${message.turnId.take(12)} stage=${message.stage} terminal=${message.terminal}")
        val ownTurn = message.turnId == _talk.value.turnId
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
        val file = File(cacheDir, "hv-play-${request.sequence}.${if (request.mimeType.contains("wav")) "wav" else "mp3"}")
        file.writeBytes(request.audio)
        _talk.update { it.playing(request.turnId) }
        val mp = MediaPlayer()
        player = mp
        playing = request
        playingNode = nodeId
        mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
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
        lastPlaybackEndedAtMs = SystemClock.elapsedRealtime()
        _talk.update { it.playbackEnded(request.turnId) }
        if (node != null) {
            scope.launch {
                val sent = runCatching {
                    Wearable.getMessageClient(this@WatchApp)
                        .sendMessage(node, WatchLinkPaths.PLAYED, PlayedAck(request.turnId, request.sequence, ok, error).encode()).await()
                }
                Log.i(TAG, "played ack turn=${request.turnId.take(12)} seq=${request.sequence} ok=$ok" +
                    (if (ok) "" else " error=$error") + " sent=${sent.isSuccess}")
            }
        }
    }

    companion object {
        private const val TAG = "HermesVoiceWatch"
        private const val PREFS = "hermes_voice_watch"
        private const val KEY_SETTINGS = "settings_json"

        fun from(context: Context): WatchApp = context.applicationContext as WatchApp
    }
}
