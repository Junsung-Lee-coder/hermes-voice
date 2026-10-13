package com.rumi.hermesvoice.core.diag

import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import com.rumi.hermesvoice.core.voice.PlaybackCue
import com.rumi.hermesvoice.core.voice.ResponseDecision
import com.rumi.hermesvoice.core.SpokenRole
import com.rumi.hermesvoice.core.net.SttCode
import com.rumi.hermesvoice.core.voice.SttPhase
import com.rumi.hermesvoice.core.voice.SttRecoveryEvent
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
            fail = if (full) DiagFail.REFUSED else DiagFail.BUSY, stage = DiagStage.PREPARE, cause = DiagCause.LOCAL, modality = DiagModality.VOICE)
        delegate.onNotAdmitted(turnId, origin, reason)
    }

    override fun onAccepted(turnId: String, origin: VoiceOrigin) {
        log.record(DiagCode.REQUEST_ACCEPTED, origin(origin), turnId, modality = DiagModality.VOICE)
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

    override fun onDelivered(route: com.rumi.hermesvoice.core.voice.AssembledRoute) {
        log.record(DiagCode.REQUEST_DELIVERED, ref = route.turnId, modality = DiagModality.VOICE)
        delegate.onDelivered(route)
    }

    override fun onTransmitted(turnId: String) {
        log.record(DiagCode.REQUEST_TRANSMISSION_ENDED, ref = turnId)
        delegate.onTransmitted(turnId)
    }

    override fun onSttRecovery(turnId: String, event: SttRecoveryEvent) {
        recordStt(turnId, event)
        delegate.onSttRecovery(turnId, event)
    }

    private fun recordStt(turnId: String, event: SttRecoveryEvent) {
        val mime = DiagMime.of(event.mimeType)
        val attemptStt = DiagStt(event.code, event.audioBytes.coerceIn(0, DiagStt.MAX_AUDIO_BYTES), mime)
        when (event.phase) {
            SttPhase.ATTEMPT_STARTED -> log.record(DiagCode.STT_ATTEMPT_STARTED, ref = turnId, n = event.attempt, stage = DiagStage.STT,
                modality = DiagModality.VOICE, stt = attemptStt)
            SttPhase.ATTEMPT_SUCCEEDED -> log.record(DiagCode.STT_ATTEMPT_OK, ref = turnId, n = event.attempt, ms = event.elapsedMs,
                stage = DiagStage.STT, modality = DiagModality.VOICE, stt = attemptStt)
            SttPhase.ATTEMPT_FAILED -> {
                val cause = causeOf(event)
                log.record(DiagCode.STT_ATTEMPT_FAILED, ref = turnId, n = event.attempt, ms = event.elapsedMs, fail = DiagClassify.failOf(cause),
                    stage = DiagStage.STT, cause = cause, modality = DiagModality.VOICE, status = event.status, stt = attemptStt)
            }
            SttPhase.WAIT_STARTED -> log.record(DiagCode.STT_WAIT_STARTED, ref = turnId, n = event.attempt, ms = event.elapsedMs, stage = DiagStage.STT,
                modality = DiagModality.VOICE, status = event.status, stt = DiagStt(event.code, event.audioBytes.coerceIn(0, DiagStt.MAX_AUDIO_BYTES), mime,
                    waitMs = event.waitMs?.coerceIn(0, DiagStt.MAX_WAIT_MS)))
            SttPhase.RECOVERED -> log.record(DiagCode.STT_RECOVERED, ref = turnId, n = event.attempt, ms = event.elapsedMs, stage = DiagStage.STT,
                modality = DiagModality.VOICE, stt = attemptStt)
            SttPhase.EXHAUSTED -> log.record(DiagCode.STT_EXHAUSTED, ref = turnId, n = event.attempt, ms = event.elapsedMs, fail = DiagFail.DISCONNECTED,
                stage = DiagStage.STT, cause = DiagCause.STT_UNAVAILABLE, modality = DiagModality.VOICE, status = event.status, stt = attemptStt)
            SttPhase.CANCELLED -> log.record(DiagCode.STT_CANCELLED, ref = turnId, n = event.attempt, ms = event.elapsedMs, fail = DiagFail.CANCELLED,
                stage = DiagStage.STT, cause = DiagCause.CANCELLED, modality = DiagModality.VOICE, stt = attemptStt)
        }
    }

    private fun causeOf(event: SttRecoveryEvent): DiagCause = when {
        event.code?.retryable == true -> DiagCause.STT_UNAVAILABLE
        event.code == SttCode.AUTH_REJECTED -> DiagCause.AUTH
        event.code != null -> DiagCause.REJECTED
        event.status == 401 || event.status == 403 -> DiagCause.AUTH
        else -> DiagClassify.causeOfName(event.failure)
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
            is VoiceTurnOutcome.NotDelivered -> {
                val cause = outcome.diagCause ?: if (outcome.authRequired) DiagCause.AUTH else DiagCause.UNKNOWN
                log.record(DiagCode.REQUEST_FAILED, ref = turnId,
                    fail = if (outcome.authRequired || cause == DiagCause.AUTH) DiagFail.AUTH else DiagClassify.failOf(cause),
                    stage = outcome.diagStage, cause = cause, modality = DiagModality.VOICE, status = outcome.diagStatus)
            }
            is VoiceTurnOutcome.DeliveredResponseFailed -> {
                val cause = outcome.diagCause ?: DiagCause.UNKNOWN
                log.record(DiagCode.RESPONSE_SILENT, ref = turnId, fail = DiagClassify.failOf(cause),
                    stage = outcome.diagStage, cause = cause, modality = DiagModality.VOICE, status = outcome.diagStatus)
            }
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
        else log.record(DiagCode.SPEECH_CHUNK_FAILED, ref = turnId, n = index + 1, ms = ms, fail = DiagFail.ofName(failure),
            stage = DiagStage.TTS, cause = DiagClassify.causeOfName(failure), modality = DiagModality.VOICE)
        delegate.onSpeechChunk(turnId, index, count, ms, failure)
    }
}
