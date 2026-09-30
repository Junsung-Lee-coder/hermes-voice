package com.rumi.hermesvoice.core.watchlink

enum class WatchPhase { IDLE, RECORDING, SENDING, WAITING, PLAYING }

/**
 * The Watch's talk state, driven by local capture and by the Phone's [TurnStateMessage]s and
 * playback. Pure so it can be tested off-device. Only one turn is in flight from the Watch at a
 * time; stage messages for any other turn id are ignored (stale or replayed messages).
 *
 * Playback is different: the Phone plays every utterance on the device that most recently sent
 * an accepted voice request, so the Watch may also speak a Phone turn's (or an earlier Watch
 * turn's) audio. [speakingTurnId] tracks whatever is on the speaker, whichever turn it belongs to,
 * so the wake phrase never listens to the Watch's own playback.
 */
data class WatchTalkState(
    val phase: WatchPhase = WatchPhase.IDLE,
    val turnId: String? = null,
    val trigger: TurnTrigger? = null,
    val line: String = "",
    val speakingTurnId: String? = null,
) {
    /** The wake phrase may listen only when nothing else owns the microphone or speaker. */
    val canArmWakePhrase: Boolean get() = phase == WatchPhase.IDLE && speakingTurnId == null

    val canStartPushToTalk: Boolean get() = phase == WatchPhase.IDLE || phase == WatchPhase.WAITING

    fun startRecording(turnId: String, trigger: TurnTrigger): WatchTalkState {
        check(if (trigger == TurnTrigger.PUSH_TO_TALK) canStartPushToTalk else canArmWakePhrase) { "busy: $phase" }
        return WatchTalkState(WatchPhase.RECORDING, turnId, trigger, "Listening…", speakingTurnId)
    }

    /** Updates the prompt of the capture in progress (e.g. "Speak now" once the microphone is calibrated). */
    fun recordingCue(line: String): WatchTalkState = if (phase == WatchPhase.RECORDING) copy(line = line) else this

    fun recordingDiscarded(reason: String): WatchTalkState = WatchTalkState(line = reason, speakingTurnId = speakingTurnId)

    fun sending(): WatchTalkState = copy(phase = WatchPhase.SENDING, line = "Sending to phone…")

    fun sendFailed(reason: String): WatchTalkState = WatchTalkState(line = reason, speakingTurnId = speakingTurnId)

    fun onPhoneState(message: TurnStateMessage): WatchTalkState {
        if (message.turnId != turnId) return this
        if (message.terminal) return WatchTalkState(line = message.detail, speakingTurnId = speakingTurnId)
        val line = when (message.stage) {
            "transcribing" -> "Transcribing…"
            "routing" -> "Choosing where to send…"
            "routed" -> "→ ${message.detail}"
            "acknowledging", "delivering" -> line
            "responding" -> "Waiting for reply…"
            else -> line
        }
        return copy(phase = if (phase == WatchPhase.PLAYING) phase else WatchPhase.WAITING, line = line)
    }

    /** Audio for [turnId] started on this Watch's speaker. */
    fun playing(turnId: String): WatchTalkState = when {
        turnId == this.turnId && phase != WatchPhase.IDLE -> copy(phase = WatchPhase.PLAYING, speakingTurnId = turnId)
        phase == WatchPhase.IDLE -> copy(speakingTurnId = turnId, line = PLAYING_OTHER)
        else -> copy(speakingTurnId = turnId)
    }

    /** Audio for [turnId] stopped (finished, failed or was stopped). */
    fun playbackEnded(turnId: String): WatchTalkState {
        if (speakingTurnId != turnId) return this
        return when {
            turnId == this.turnId && phase == WatchPhase.PLAYING -> copy(phase = WatchPhase.WAITING, speakingTurnId = null)
            line == PLAYING_OTHER -> copy(speakingTurnId = null, line = "")
            else -> copy(speakingTurnId = null)
        }
    }

    companion object {
        const val PLAYING_OTHER = "Playing reply…"
    }
}
