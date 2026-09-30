package com.rumi.hermesvoice.watch

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.watchlink.PlayRequest
import com.rumi.hermesvoice.core.watchlink.PlayedAck
import com.rumi.hermesvoice.core.watchlink.TurnStateMessage
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchTalkState
import com.rumi.hermesvoice.core.watchlink.WatchTurnUpload
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Watch process state. The Watch never talks to Hermes: it uploads captured WAVs to the Phone,
 * shows the Phone's stage updates, and plays whatever the Phone sends for the current turn,
 * acknowledging each playback so the Phone can keep its ordering (ack → delivery → replies).
 */
class WatchApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _talk = MutableStateFlow(WatchTalkState())
    val talk: StateFlow<WatchTalkState> = _talk
    private val _settings = MutableStateFlow(WatchSettings())
    val settings: StateFlow<WatchSettings> = _settings

    private var player: MediaPlayer? = null
    private var playing: PlayRequest? = null
    private var playingNode: String? = null

    override fun onCreate() {
        super.onCreate()
        _settings.value = WatchSettings.fromJson(getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SETTINGS, null))
    }

    fun applySettings(json: String) {
        val parsed = WatchSettings.fromJson(json)
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SETTINGS, parsed.toJson()).apply()
        _settings.value = parsed
    }

    private val _phoneReachable = MutableStateFlow<Boolean?>(null)

    /** Whether a nearby Phone advertising the app capability is reachable; null until first checked. */
    val phoneReachable: StateFlow<Boolean?> = _phoneReachable

    suspend fun refreshPhoneReachable() {
        _phoneReachable.value = runCatching {
            Wearable.getCapabilityClient(this).getCapability(WatchLinkPaths.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
                .await().nodes.any { it.isNearby }
        }.getOrDefault(false)
    }

    fun newTurn(trigger: TurnTrigger): String? {
        val turnId = UUID.randomUUID().toString()
        return runCatching { _talk.update { it.startRecording(turnId, trigger) } }.map { turnId }.getOrNull()
    }

    fun discard(reason: String) = _talk.update { it.recordingDiscarded(reason) }

    /** Uploads the finished recording to the reachable Phone node that advertises the app capability. */
    fun upload(turnId: String, trigger: TurnTrigger, wav: ByteArray) {
        _talk.update { if (it.turnId == turnId) it.sending() else it }
        scope.launch {
            val failure = runCatching {
                val phone = Wearable.getCapabilityClient(this@WatchApp)
                    .getCapability(WatchLinkPaths.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE).await()
                    .nodes.firstOrNull { it.isNearby } ?: error("Phone not reachable")
                val channels = Wearable.getChannelClient(this@WatchApp)
                val channel = channels.openChannel(phone.id, WatchLinkPaths.turnPath(turnId)).await()
                val output = channels.getOutputStream(channel).await()
                val frame = WatchTurnUpload(turnId, trigger, WatchTurnUpload.MIME_WAV, wav).toFrame().encode()
                withContext(Dispatchers.IO) { output.use { it.write(frame); it.flush() } }
            }.exceptionOrNull()
            if (failure == null) Log.i(TAG, "upload sent turn=${turnId.take(12)} trigger=$trigger bytes=${wav.size}")
            if (failure != null) {
                Log.w(TAG, "upload failed: ${failure.message}")
                _talk.update { if (it.turnId == turnId) it.sendFailed(failure.message ?: "Could not reach the phone") else it }
            }
        }
    }

    fun onPhoneState(message: TurnStateMessage) {
        Log.i(TAG, "phone state turn=${message.turnId.take(12)} stage=${message.stage} terminal=${message.terminal}")
        _talk.update { it.onPhoneState(message) }
        if (message.terminal && message.turnId == _talk.value.turnId) buzz()
    }

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

    fun buzz() {
        if (!_settings.value.hapticsEnabled) return
        runCatching {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    companion object {
        private const val TAG = "HermesVoiceWatch"
        private const val PREFS = "hermes_voice_watch"
        private const val KEY_SETTINGS = "settings_json"

        fun from(context: Context): WatchApp = context.applicationContext as WatchApp
    }
}
