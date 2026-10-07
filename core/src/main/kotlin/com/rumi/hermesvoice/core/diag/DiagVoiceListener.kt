package com.rumi.hermesvoice.core.diag

import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import com.rumi.hermesvoice.core.voice.PlaybackCue
import com.rumi.hermesvoice.core.voice.ResponseDecision
import com.rumi.hermesvoice.core.SpokenRole
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnStage

/**
 * Records the Phone's typed diagnostic events from the voice pipeline's callbacks, then passes every call on unchanged. It reads
 * no text, transcript, route, alias, id string (the turn id is only a correlation key that never leaves the app unhashed) or
 * exception message: only fixed enums and numbers.
 */
class DiagVoiceListener(private val delegate: VoiceTurnListener, private val log: DiagLog) : VoiceTurnListener {
    private fun origin(origin: VoiceOrigin) = if (origin == VoiceOrigin.WATCH) DiagOrigin.WATCH else DiagOrigin.PHONE

    override fun onInputRejected(turnId: String, origin: VoiceOrigin, verdict: AudioInputVerdict) = delegate.onInputRejected(turnId, origin, verdict)

    override fun onNotAdmitted(turnId: String, origin: VoiceOrigin, reason: String) {
        val full = reason == VoiceTurnOrchestrator.PENDING_LIMIT || reason == VoiceTurnOrchestrator.QUEUE_FULL
        log.record(if (full) DiagCode.REQUEST_REFUSED_FULL else DiagCode.REQUEST_FAILED, origin(origin), turnId,
            fail = if (full) DiagFail.REFUSED else DiagFail.BUSY)
        delegate.onNotAdmitted(turnId, origin, reason)
    }

    override fun onAccepted(turnId: String, origin: VoiceOrigin) {
        log.record(DiagCode.REQUEST_ACCEPTED, origin(origin), turnId)
        delegate.onAccepted(turnId, origin)
    }

    override fun onStage(turnId: String, stage: VoiceTurnStage) {
        when (stage) {
            VoiceTurnStage.QUEUED -> log.record(DiagCode.REQUEST_QUEUED, ref = turnId)
            VoiceTurnStage.RESPONDING -> log.record(DiagCode.RESPONSE_WAITING, ref = turnId)
            else -> Unit
        }
        delegate.onStage(turnId, stage)
    }

    override fun onRouted(route: com.rumi.hermesvoice.core.voice.AssembledRoute) = delegate.onRouted(route)

    override fun onResponse(turnId: String, decision: ResponseDecision) {
        log.record(if (decision.role == SpokenRole.FINAL) DiagCode.RESPONSE_COMPLETE else DiagCode.RESPONSE_PROGRESS, ref = turnId)
        delegate.onResponse(turnId, decision)
    }

    override fun onPlayed(cue: PlaybackCue) {
        log.record(DiagCode.PLAYBACK_DONE, if (cue.device == VoiceOrigin.WATCH) DiagOrigin.WATCH else DiagOrigin.PHONE, cue.turnId,
            n = cue.part.takeIf { it > 0 })
        delegate.onPlayed(cue)
    }

    override fun onDelivered(route: com.rumi.hermesvoice.core.voice.AssembledRoute) = delegate.onDelivered(route)

    override fun onTransmitted(turnId: String) {
        log.record(DiagCode.REQUEST_DELIVERED, ref = turnId)
        delegate.onTransmitted(turnId)
    }

    override fun onLaterReply(turnId: String, played: Boolean, detail: String) {
        log.record(if (played) DiagCode.LATER_PLAYED else DiagCode.LATER_NOT_PLAYED, ref = turnId)
        delegate.onLaterReply(turnId, played, detail)
    }

    override fun onLaterFollowEnded(turnId: String, reason: String) {
        log.record(DiagCode.RESPONSE_ENDED, ref = turnId)
        delegate.onLaterFollowEnded(turnId, reason)
    }

    override fun onDiagnostic(turnId: String, stage: String, detail: String) = delegate.onDiagnostic(turnId, stage, detail)

    override fun onOutcome(turnId: String, outcome: VoiceTurnOutcome) {
        when (outcome) {
            is VoiceTurnOutcome.Completed -> log.record(DiagCode.REQUEST_COMPLETED, ref = turnId)
            VoiceTurnOutcome.Stopped -> log.record(DiagCode.REQUEST_STOPPED, ref = turnId)
            is VoiceTurnOutcome.NotDelivered -> log.record(DiagCode.REQUEST_FAILED, ref = turnId,
                fail = if (outcome.authRequired) DiagFail.AUTH else DiagFail.UNKNOWN)
            is VoiceTurnOutcome.DeliveredResponseFailed -> log.record(DiagCode.RESPONSE_SILENT, ref = turnId, fail = DiagFail.UNKNOWN)
            else -> Unit
        }
        delegate.onOutcome(turnId, outcome)
    }

    override fun onStopRequested(turnId: String) {
        log.record(DiagCode.STOP_ONE, ref = turnId)
        delegate.onStopRequested(turnId)
    }

    override fun onSpeechChunk(turnId: String, index: Int, count: Int, ms: Long, failure: String?) {
        if (failure == null) log.record(DiagCode.SPEECH_CHUNK_DONE, ref = turnId, n = index + 1, ms = ms)
        else log.record(DiagCode.SPEECH_CHUNK_FAILED, ref = turnId, n = index + 1, ms = ms, fail = DiagFail.ofName(failure))
        delegate.onSpeechChunk(turnId, index, count, ms, failure)
    }
}
