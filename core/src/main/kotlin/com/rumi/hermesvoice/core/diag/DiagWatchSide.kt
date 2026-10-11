package com.rumi.hermesvoice.core.diag

import java.io.IOException

/** The Phone could not be addressed at all (no node was reachable). Says nothing about the Internet. */
class PhoneUnreachableException : IOException("phone link")

/** What the Watch's own screen says about one failed hand-over to the Phone: fixed words only, never an exception's text. */
class WatchTransferFailure(val cause: DiagCause, val userStopped: Boolean, val status: String)

/** The Watch's own events around handing a recording to the Phone. */
object DiagWatchTransfer {
    /**
     * Records the outcome of one hand-over attempt (a retry is another attempt with another correlation), classified by type and
     * stage, and returns the safe description for the screen; null when it succeeded. Cancellation is the user's Stop, not a failure.
     */
    fun record(log: DiagLog?, turnId: String, failure: Throwable?, userStopped: Boolean): WatchTransferFailure? {
        if (failure == null) {
            log?.record(DiagCode.WATCH_SENT, DiagOrigin.WATCH, turnId, stage = DiagStage.TRANSFER, modality = DiagModality.VOICE)
            return null
        }
        val stopped = userStopped || (failure is kotlinx.coroutines.CancellationException && failure !is kotlinx.coroutines.TimeoutCancellationException)
        if (stopped) {
            log?.record(DiagCode.REQUEST_STOPPED, DiagOrigin.WATCH, turnId, fail = DiagFail.CANCELLED, stage = DiagStage.TRANSFER,
                cause = DiagCause.CANCELLED, modality = DiagModality.VOICE)
            return WatchTransferFailure(DiagCause.CANCELLED, true, "Stopped")
        }
        val classified = DiagClassify.of(failure)
        val cause = if (classified.cause == DiagCause.UNKNOWN) DiagClassify.causeOfName(failure.javaClass.simpleName) else classified.cause
        log?.record(DiagCode.REQUEST_FAILED, DiagOrigin.WATCH, turnId, fail = DiagClassify.failOf(cause), stage = DiagStage.TRANSFER,
            cause = cause, modality = DiagModality.VOICE, status = classified.status)
        val status = if (failure is PhoneUnreachableException) "Couldn't reach the phone" else "Couldn't send to the phone: ${DiagLabels.cause(cause)}"
        return WatchTransferFailure(cause, false, status)
    }
}

/** Answers the Phone's user-started diagnostics request for this Watch, in the schema the Phone asked in (an older Phone asks for 1). */
class DiagWatchResponder(
    private val log: DiagLog,
    private val persistence: () -> DiagPersistStatus,
    /** Blocking (reads the installed APK): [answer] runs on the Data Layer listener's background thread, never the main thread. */
    private val app: () -> DiagApp,
) {
    fun answer(request: ByteArray): ByteArray? {
        val (schema, salt) = DiagWire.decodeRequestAny(request) ?: return null
        return if (schema == DiagWireV2.SCHEMA || schema == DiagWireV2.SCHEMA_3) {
            DiagWireV2.encodeResponse(salt, log.snapshot(), log.now(), log.coverage(), persistence(), app(), DiagNetwork.UNKNOWN, schema)
        } else {
            DiagWire.encodeResponse(salt, log.snapshot(), log.dropped(), log.now())
        }
    }
}
