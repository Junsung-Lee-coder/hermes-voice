package com.rumi.hermesvoice.core.watchlink

enum class WatchPhase { IDLE, RECORDING, SENDING, WAITING, PLAYING }

/**
 * The Watch's own guard for LATER replies and for an own reply that may arrive during another request's recording
 * ([PlayRequest.refusedWhileRecording]): such a reply never plays over a recording.
 * The Phone can't see a Watch recording that hasn't been sent, so the Watch answers for itself.
 */
object LaterPlaybackGuard {
    /** The error to refuse [request] with now ([PlayedAck.BUSY_RECORDING]), or null to play it. */
    fun refusal(request: PlayRequest, recording: Boolean): String? = if (request.refusedWhileRecording && recording) PlayedAck.BUSY_RECORDING else null

    /** A recording starts while [playing] plays: the error to stop it with, or null to let it play (an in-turn reply). */
    fun onRecordingStarted(playing: PlayRequest?): String? = if (playing?.refusedWhileRecording == true) PlayedAck.BUSY_RECORDING else null
}

/**
 * The Watch's talk state, driven by local capture and by the Phone's [TurnStateMessage]s and
 * playback. Pure so it can be tested off-device. [turnId] is the Watch's current request (the one being
 * recorded, sent, or the latest one waiting for its reply). A new recording may start while an earlier request
 * only waits for its reply: that request moves to [earlier] (bounded), where it keeps its own terminal state and
 * its status line; a stage message for a turn that is neither is ignored (stale or replayed messages).
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
    /** Earlier delivered requests still waiting for their reply, oldest first (bounded by [MAX_EARLIER]); nothing is held for them. */
    val earlier: List<String> = emptyList(),
    /** The finished request whose reply audio did not play: the Watch may ask the Phone to play it again (cleared by any new state). */
    val replayTurnId: String? = null,
    /** The finished request whose reply the Phone was asked to play again; its next terminal state is shown even though the request had ended. */
    val replayingTurnId: String? = null,
    /** The "not sent" status of a request that is not the current one (an earlier request, or one this Watch no longer tracks); shown until the next request or Stop. */
    val notice: String = "",
) {
    /** Whether another request may start while this one waits: a waiting request only waits, and the bound is not reached. */
    private val roomToStart: Boolean get() = phase == WatchPhase.IDLE || (phase == WatchPhase.WAITING && earlier.size < MAX_EARLIER)

    /**
     * The wake phrase may listen when nothing else owns the microphone or speaker: waiting for a reply is not owning
     * either, so a new request is accepted while an earlier one awaits its reply.
     */
    val canArmWakePhrase: Boolean get() = roomToStart && speakingTurnId == null

    val canStartPushToTalk: Boolean get() = roomToStart

    /** Requests waiting for a reply, this Watch's current one included. */
    val waitingCount: Int get() = earlier.size + (if (phase == WatchPhase.WAITING || phase == WatchPhase.PLAYING) 1 else 0)

    fun startRecording(turnId: String, trigger: TurnTrigger): WatchTalkState {
        check(if (trigger == TurnTrigger.PUSH_TO_TALK) canStartPushToTalk else canArmWakePhrase) { "busy: $phase" }
        val waiting = if (phase == WatchPhase.WAITING && this.turnId != null) earlier + this.turnId else earlier
        return WatchTalkState(WatchPhase.RECORDING, turnId, trigger, "Listening…", speakingTurnId, waiting)
    }

    /** Updates the prompt of the capture in progress (e.g. "Speak now" once the microphone is calibrated). */
    fun recordingCue(line: String): WatchTalkState = if (phase == WatchPhase.RECORDING) copy(line = line) else this

    fun recordingDiscarded(reason: String): WatchTalkState = WatchTalkState(line = reason, speakingTurnId = speakingTurnId, earlier = earlier)

    fun sending(): WatchTalkState = copy(phase = WatchPhase.SENDING, line = "Sending to phone…")

    fun sendFailed(reason: String): WatchTalkState = WatchTalkState(line = reason, speakingTurnId = speakingTurnId, earlier = earlier)

    /** Stop: every request of this Watch, current and waiting, is dropped here (what the Watch does on its Stop). */
    fun stopped(reason: String): WatchTalkState =
        if (turnId == null && earlier.isEmpty()) this else WatchTalkState(line = reason, speakingTurnId = speakingTurnId)

    /** The Watch asked the Phone to play [replayTurnId]'s reply again: the control goes away until the Phone says it failed again. */
    fun replayRequested(): WatchTalkState =
        if (replayTurnId == null) this else copy(replayTurnId = null, replayingTurnId = replayTurnId, line = REPLAY_REQUESTED)

    fun onPhoneState(message: TurnStateMessage): WatchTalkState {
        val failure = message.terminal && isRetainedFailure(message.detail)
        if (message.turnId in earlier) {
            // An earlier request ended or only changed stage: its own end leaves the waiting list, nothing else touches the current one.
            return if (message.terminal) copy(earlier = earlier - message.turnId, notice = if (failure) message.detail else notice) else this
        }
        if (message.turnId != turnId) {
            if (failure) return copy(notice = message.detail)
            if (message.turnId != replayingTurnId || !message.terminal) return this
            // A newer request of this Watch is in progress: its state is not overwritten by the older reply's replay result.
            if (phase != WatchPhase.IDLE) return copy(replayingTurnId = null)
            return WatchTalkState(line = message.detail, speakingTurnId = speakingTurnId, earlier = earlier,
                replayTurnId = if (message.replay) message.turnId else null)
        }
        if (message.terminal) return WatchTalkState(line = message.detail, speakingTurnId = speakingTurnId, earlier = earlier,
            replayTurnId = if (message.replay) message.turnId else null, notice = notice)
        val line = when (message.stage) {
            "transcribing" -> "Transcribing…"
            "stt_recovering" -> STT_RECOVERING
            "audio_recovering" -> AUDIO_RECOVERING
            "routing" -> "Choosing where to send…"
            "creating" -> "Creating a conversation…"
            "routed" -> "→ ${message.detail}"
            "acknowledging", "delivering" -> line
            "queued" -> QUEUED
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

        /** The start of a terminal status that says the request never left the Phone (see [VoiceOutcomeText.NOT_SENT]). */
        const val NOT_SENT = "Not sent"

        /** The start of a terminal status for a request that was refused at delivery, see [VoiceOutcomeText.describe]. */
        const val NOT_DELIVERED = "Not delivered"

        /** The start of a terminal status for a request that may have reached Hermes; it is never sent again. */
        const val ACCEPTANCE_UNCERTAIN = "Acceptance uncertain"

        /** A terminal status the Phone keeps owing, and the Watch keeps showing until the person has seen it, then acknowledges. */
        fun isRetainedFailure(detail: String): Boolean =
            detail.startsWith(NOT_SENT) || detail.startsWith(NOT_DELIVERED) || detail.startsWith(ACCEPTANCE_UNCERTAIN)

        /** The request waits for the earlier request to the same conversation to be answered; it is sent after that. */
        const val QUEUED = "Queued behind an earlier request…"

        /** The speech server is temporarily unavailable; the phone keeps the recording and tries again (Stop cancels it). */
        const val STT_RECOVERING = "Waiting for the speech server…"

        /** The reply's audio did not play yet; the Phone is trying again (Stop cancels it). */
        const val AUDIO_RECOVERING = "Reply audio did not play yet - trying again…"

        const val REPLAY_REQUESTED = "Asking the phone to play the reply again…"

        const val TOO_MANY = "Too many requests are waiting. Wait for a reply or Stop"

        /** Most earlier requests a Watch keeps waiting for their reply; past it a new request is not started (visibly). */
        const val MAX_EARLIER = 7
    }
}
