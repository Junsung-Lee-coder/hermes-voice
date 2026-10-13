package com.rumi.hermesvoice.phone

import android.Manifest
import android.content.pm.PackageManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.background.HoldReason
import com.rumi.hermesvoice.core.headset.*
import com.rumi.hermesvoice.core.voice.MicrophoneClaim
import com.rumi.hermesvoice.core.voice.TurnRouting
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import com.rumi.hermesvoice.core.watchlink.VoiceOutcomeText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

fun interface ManualAdmission {
    /** Requests synchronously from the initiating user/media callback. Positive completion means actual microphone-type FGS. */
    fun request(generation: Long, done: (Long, Boolean) -> Unit): AutoCloseable
}

enum class ManualPhase { IDLE, PENDING, ADMITTED, RECORDING, REFUSED }
data class ManualCaptureState(val phase: ManualPhase = ManualPhase.IDLE, val recording: Boolean = false,
    val voiceStatus: String = "", val micNotice: String? = null)

/** One real manual Phone recorder and target, owned by the Application and leased to a microphone foreground service. */
class PhoneManualCapture(private val app: PhoneApp) : HeadsetRecordingTarget {
    private val main = Handler(Looper.getMainLooper())
    val recorder by lazy { WavRecorder(maxSeconds = MAX_SECONDS, records = app.recordFactory) }
    private val mutable = MutableStateFlow(ManualCaptureState())
    val state: StateFlow<ManualCaptureState> = mutable
    val busy: Boolean get() = opening || recorder.isRecording
    @Volatile private var generation = 0L
    private var opening = false
    private var admitted = false
    private var viaHeadset = false
    private var cued = false
    private var microphone: MicrophoneClaim? = null
    private var micLease: AutoCloseable? = null
    private var serviceLease: AutoCloseable? = null
    private var serviceGeneration = -1L
    private var noisy: BroadcastReceiver? = null
    private var preparing: AutoCloseable? = null
    private var loss: AutoCloseable? = null
    private var cue: AutoCloseable? = null
    private var deadline: Runnable? = null

    fun toggle() {
        if (busy) { stop(); return }
        start(headset = false)
    }

    override fun onHeadsetCommand(command: RecordingCommand): HeadsetOutcome {
        if (!app.headset.enabled || app.headset.output() == null) return HeadsetOutcome.REFUSED
        if (busy) {
            if (command == RecordingCommand.START) return HeadsetOutcome.IGNORED
            stop()
            return HeadsetOutcome.STOPPED
        }
        if (command == RecordingCommand.STOP) return HeadsetOutcome.IGNORED
        return if (start(headset = true)) HeadsetOutcome.STARTED else HeadsetOutcome.REFUSED
    }

    private fun start(headset: Boolean): Boolean {
        if (busy) return false
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mutable.value = ManualCaptureState(ManualPhase.REFUSED, voiceStatus = HeadsetMediaText.PERMISSION, micNotice = HeadsetMediaText.PERMISSION)
            return false
        }
        if (app.tokens.load() == null || (!app.settings.routingEnabled && app.selectedConversationId == null)) {
            mutable.value = ManualCaptureState(ManualPhase.REFUSED, voiceStatus = "Open a conversation and sign in before recording")
            return false
        }
        val owned = app.phoneMic.takeManual() ?: run {
            mutable.value = ManualCaptureState(ManualPhase.REFUSED, voiceStatus = "Phone microphone busy")
            return false
        }
        app.connectionCue.cancel()
        cancelCue()
        micLease = owned
        viaHeadset = headset
        opening = true
        admitted = false
        val token = ++generation
        serviceGeneration = token
        mutable.value = ManualCaptureState(ManualPhase.PENDING, voiceStatus = "Waiting for microphone permission from Android…")
        val expiry = Runnable { if (token == generation && opening) cancel("Microphone start timed out") }
        deadline = expiry
        main.postDelayed(expiry, PREPARE_TIMEOUT_MS)
        // Do not postpone the foreground-service request to a coroutine: external callback allowance is short-lived.
        val lease = runCatching { app.manualAdmission.request(token, ::onAdmission) }.getOrElse {
            onAdmission(token, false)
            AutoCloseable { }
        }
        if (token == generation && busy) {
            serviceLease = lease
            if (viaHeadset) watchNoisy(token)
        } else runCatching { lease.close() }
        return busy
    }

    fun isCurrent(token: Long): Boolean = token == generation && busy

    fun onAdmission(token: Long, accepted: Boolean) {
        if (token != generation || !opening || admitted) return
        if (!accepted) { cancel("Android refused background microphone access", refused = true); return }
        admitted = true
        mutable.update { it.copy(phase = ManualPhase.ADMITTED, voiceStatus = "Preparing microphone…") }
        val claim = app.audio.claimMicrophone(VoiceOrigin.PHONE)
        microphone = claim
        claim.whenSpeakerStopped(app.mainScope) { stopped ->
            if (token != generation || !opening || !admitted) { claim.release(); return@whenSpeakerStopped }
            if (!stopped) { cancel("Phone speaker did not stop"); return@whenSpeakerStopped }
            var settled = false
            val handle = app.headsetMic.prepare { plan ->
                settled = true
                preparing = null
                open(token, plan)
            }
            if (!settled && token == generation) preparing = handle else if (token != generation) runCatching { handle.close() }
        }
    }

    private fun open(token: Long, plan: MicPlan) {
        if (token != generation || !opening || !admitted) { plan.release(); return }
        if (viaHeadset && (!app.headset.enabled || app.headset.output() == null)) { plan.release(); cancel("Headset control lost"); return }
        cued = app.recordingCues.available()
        runCatching {
            recorder.start(plan, admitted = !cued,
                onTerminal = { main.post { if (token == generation) finish(inputLost = false) } },
                onSource = { report -> main.post { if (token == generation && recorder.isRecording) mutable.update { it.copy(micNotice = report.text) } } })
        }.onFailure { cancel("Microphone unavailable", refused = true) }.onSuccess {
            opening = false
            deadline?.let(main::removeCallbacks)
            val safety = Runnable { if (token == generation) finish(inputLost = false) }
            deadline = safety
            main.postDelayed(safety, MAX_SECONDS * 1000L)
            app.holds.acquire(HoldReason.CAPTURE, MAX_SECONDS * 1000L + 5_000L)
            mutable.update { it.copy(phase = ManualPhase.RECORDING, recording = true, voiceStatus = if (cued) "Getting ready…" else "Listening… tap to send") }
            if (plan.preferred != null) loss = app.headsetMic.watchLoss(plan) { main.post { if (token == generation) finish(inputLost = true) } }
            app.headsetMedia.recording(true)
            if (cued) {
                val handle = app.recordingCues.play(CueKind.START) { result -> main.post {
                    if (token != generation || !recorder.isRecording) return@post
                    cue = null
                    if (result in setOf(CueResult.LOST, CueResult.FOCUS_LOST, CueResult.UNROUTED)) { cancel("Headset cue interrupted"); return@post }
                    recorder.admit()
                    mutable.update { it.copy(voiceStatus = "Listening… tap to send", micNotice = if (result == CueResult.PLAYED) it.micNotice else HeadsetMediaText.CUE_SKIPPED) }
                } }
                if (token == generation) cue = handle else runCatching { handle?.close() }
                if (handle == null && token == generation) { recorder.admit(); mutable.update { it.copy(voiceStatus = "Listening… tap to send", micNotice = HeadsetMediaText.CUE_SKIPPED) } }
            }
            PhoneManualService.running?.refresh(token, "Recording · Stop to send")
        }
    }

    /** Exact capture Stop. Pending starts are cancelled; only real accepted PCM is submitted. */
    fun stop(token: Long = generation) {
        if (token != generation) return
        app.connectionCue.cancel()
        if (recorder.isRecording) finish(inputLost = false) else if (opening) cancel("Stopped")
    }

    override fun onHeadsetControlLost() {
        if (viaHeadset && busy) cancel(HEADSET_DISCARDED)
    }

    fun serviceGone(token: Long) {
        if (token != serviceGeneration) return
        if (busy) cancel("Microphone service ended") else cancelCue()
    }

    private fun watchNoisy(token: Long) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && token == generation && busy) {
                    cancel(HEADSET_DISCARDED)
                }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(app, receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            noisy = receiver
        }.onFailure { if (token == generation) cancel("Headset loss monitoring unavailable") }
    }

    fun cancel(message: String = "Stopped", refused: Boolean = false) {
        ++generation
        cancelCue()
        releasePreparation()
        if (recorder.isRecording) recorder.stop()
        opening = false
        admitted = false
        viaHeadset = false
        app.headsetMedia.recording(false)
        releaseLeases()
        microphone?.release()
        microphone = null
        mutable.value = ManualCaptureState(if (refused) ManualPhase.REFUSED else ManualPhase.IDLE, voiceStatus = message,
            micNotice = if (message == HEADSET_DISCARDED) message else mutable.value.micNotice)
    }

    private fun finish(inputLost: Boolean) {
        if (!recorder.isRecording) return
        val playStop = cued && !inputLost
        ++generation
        cancelCue()
        releasePreparation()
        val wav = recorder.stop()
        val claim = microphone
        microphone = null
        opening = false
        admitted = false
        viaHeadset = false
        app.headsetMedia.recording(false)
        // Stop cue follows physical mic release; retain actual foreground admission until bounded cue ends.
        micLease?.close(); micLease = null
        app.holds.release(HoldReason.CAPTURE)
        mutable.update { it.copy(phase = ManualPhase.IDLE, recording = false, voiceStatus = if (wav == null) "Too short" else "Sending…", micNotice = if (inputLost) HeadsetText.LOST else it.micNotice) }
        val token = generation
        if (wav != null && playStop && app.recordingCues.available()) {
            cue = app.recordingCues.play(CueKind.STOP) { if (token == generation) { cue = null; releaseService() } }
            if (cue == null) releaseService()
        } else releaseService()
        if (wav == null) { claim?.release(); return }
        submit(wav, claim)
    }

    private fun submit(wav: ByteArray, claim: MicrophoneClaim?) {
        val routing = TurnRouting.of(app.settings.routingEnabled, app.selectedConversationId)
        val wiring = runCatching { app.wiring() }.getOrNull()
        if (wiring == null || app.tokens.load() == null || (!app.settings.routingEnabled && app.selectedConversationId == null)) {
            claim?.release(); mutable.update { it.copy(voiceStatus = "Not sent: no signed-in conversation") }; return
        }
        val id = UUID.randomUUID().toString()
        val token = generation
        app.launchTurn(id, phoneOrigin = true, claim) {
            try {
                val outcome = wiring.core.orchestrator.run(VoiceTurnRequest(id, VoiceOrigin.PHONE, wav, "audio/wav", PhoneSpeakerSink(app), routing = routing, microphone = claim))
                mutable.update { if (token == generation) it.copy(voiceStatus = VoiceOutcomeText.describe(outcome)) else it }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                mutable.update { if (token == generation) it.copy(voiceStatus = "Couldn't send recording") else it }
            }
        }
    }

    private fun cancelCue() { val old = cue; cue = null; runCatching { old?.close() }; if (!busy) releaseService() }
    private fun releasePreparation() {
        noisy?.let { runCatching { app.unregisterReceiver(it) } }; noisy = null
        deadline?.let(main::removeCallbacks); deadline = null
        runCatching { preparing?.close() }; preparing = null
        runCatching { loss?.close() }; loss = null
    }
    private fun releaseService() {
        val lease = serviceLease
        serviceLease = null
        serviceGeneration = -1L
        runCatching { lease?.close() }
    }
    private fun releaseLeases() { micLease?.close(); micLease = null; releaseService(); app.holds.release(HoldReason.CAPTURE) }

    companion object {
        const val MAX_SECONDS = 13 * 60
        private const val PREPARE_TIMEOUT_MS = 10_000L
        private const val HEADSET_DISCARDED = "Headset control lost: recording discarded, not sent"
    }
}
