package com.rumi.hermesvoice.core.voice

import com.rumi.hermesvoice.core.HermesException
import com.rumi.hermesvoice.core.net.SttCode
import com.rumi.hermesvoice.core.net.SttTransport

/**
 * How long, and how often, one recording is transcribed again when the speech server is temporarily unavailable. One budget
 * covers the whole recovery of one recording (real call time included, never reset by a nested layer); the first attempt is
 * attempt 1. After attempt n fails with a recoverable code the next one starts after [backoffMs] (n-1), shortened so that the
 * last attempt still has [MIN_ATTEMPT_MS] before the deadline.
 */
data class SttRecoveryPolicy(
    val budgetMs: Long = BUDGET_MS,
    val maxAttempts: Int = MAX_ATTEMPTS,
    val backoffMs: List<Long> = BACKOFF_MS,
) {
    init {
        require(budgetMs > 0 && maxAttempts >= 1) { "policy" }
    }

    fun backoffAfter(attempt: Int): Long = backoffMs.getOrElse(attempt - 1) { backoffMs.lastOrNull() ?: 0L }

    companion object {
        const val BUDGET_MS = 180_000L
        const val MAX_ATTEMPTS = 6
        val BACKOFF_MS = listOf(5_000L, 15_000L, 30_000L, 60_000L, 60_000L)
        const val MIN_ATTEMPT_MS = 3_000L
        const val MIN_WAIT_MS = 1_000L

        /** One attempt, no waiting: exactly the behavior before recovery existed. */
        val NONE = SttRecoveryPolicy(budgetMs = Long.MAX_VALUE / 4, maxAttempts = 1, backoffMs = emptyList())
    }
}

enum class SttPhase { ATTEMPT_STARTED, ATTEMPT_SUCCEEDED, ATTEMPT_FAILED, WAIT_STARTED, RECOVERED, EXHAUSTED, CANCELLED }

/**
 * One observation of the transcription of turn: a fixed phase and bounded numbers, never text or audio. [attempt] is the attempt
 * the event is about (for [SttPhase.WAIT_STARTED] the NEXT attempt); [elapsedMs] is the call time of that attempt, or for
 * [SttPhase.RECOVERED], [SttPhase.EXHAUSTED] and [SttPhase.CANCELLED] the time since the first attempt began; [waitMs] is the
 * backoff just started; [code] is the recognized safe code of the failure, [status] the HTTP status when there was one,
 * [failure] the failing exception's class name (never its message), [waiting] whether a cancel found the turn in a backoff wait, [transport] the typed transport failure (DNS, connect, reset, time-out)
 * when the attempt failed before any HTTP answer (then [code] and [status] are null and [failure] is the cause's class name).
 */
data class SttRecoveryEvent(
    val phase: SttPhase,
    val attempt: Int,
    val elapsedMs: Long,
    val audioBytes: Int,
    val mimeType: String,
    val code: SttCode? = null,
    val status: Int? = null,
    val failure: String? = null,
    val waitMs: Long? = null,
    val remainingMs: Long? = null,
    val waiting: Boolean = false,
    val transport: SttTransport? = null,
)

/** Transcription kept failing with a recoverable code until the attempts or the time ran out. */
class SttRecoveryExhaustedException(val code: SttCode, val attempts: Int, val timedOut: Boolean) :
    HermesException("${code.wire}: ${com.rumi.hermesvoice.core.net.SttSafeCode.message(code)} ($attempts attempt${if (attempts == 1) "" else "s"})")
