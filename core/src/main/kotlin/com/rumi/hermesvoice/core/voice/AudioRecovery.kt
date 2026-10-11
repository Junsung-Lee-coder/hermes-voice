package com.rumi.hermesvoice.core.voice

import com.rumi.hermesvoice.core.HermesHttpException
import com.rumi.hermesvoice.core.HermesPlaybackTransientException
import java.io.IOException

/**
 * How often, and for how long, the AUDIO of one reply that is already final is tried again: [maxAttempts] attempts in all (the first
 * included), [backoffMs] (n-1) after attempt n fails, and no further retry is scheduled once [windowMs] have passed since the FIRST
 * classified failure. The window only forbids scheduling another retry: it never cuts a synthesis or a playback that is progressing.
 * The budget is per reply (not per chunk), whole completed chunks are not replayed, and the chunk that was playing when a player error
 * came in may be heard again from its start (a repeated fragment); that is accepted rather than dropping the retry.
 */
data class AudioRecoveryPolicy(
    val maxAttempts: Int = MAX_ATTEMPTS,
    val backoffMs: List<Long> = BACKOFF_MS,
    val windowMs: Long = WINDOW_MS,
) {
    init {
        require(maxAttempts >= 1 && windowMs > 0) { "policy" }
    }

    fun backoffAfter(attempt: Int): Long = backoffMs.getOrElse(attempt - 1) { backoffMs.lastOrNull() ?: 0L }

    companion object {
        const val MAX_ATTEMPTS = 3
        val BACKOFF_MS = listOf(1_000L, 3_000L)
        const val WINDOW_MS = 60_000L

        /** One attempt, no waiting. */
        val NONE = AudioRecoveryPolicy(maxAttempts = 1, backoffMs = emptyList())
    }
}

/** Why a reply's audio failed, as a fixed code (never a message, URL, text or device identifier). */
enum class AudioFailureCode(val transient: Boolean) {
    SYNTH_NETWORK(true),
    SYNTH_HTTP_TRANSIENT(true),
    SYNTH_HTTP_REJECTED(false),
    PLAYER_TRANSIENT(true),
    OTHER(false),
}

internal object AudioFailureClassifier {
    /** The code of [error]; only a positively recognized transient failure is retried. */
    fun classify(error: Throwable): AudioFailureCode = when (error) {
        is IOException -> AudioFailureCode.SYNTH_NETWORK
        is HermesPlaybackTransientException -> AudioFailureCode.PLAYER_TRANSIENT
        is HermesHttpException -> if (error.status == 408 || error.status == 425 || error.status == 429 || (error.status in 500..599 && error.status != 501 && error.status != 505)) {
            AudioFailureCode.SYNTH_HTTP_TRANSIENT
        } else {
            AudioFailureCode.SYNTH_HTTP_REJECTED
        }
        else -> AudioFailureCode.OTHER
    }
}

/** The recovery state of ONE reply's audio: counts classified failures from the first one and says whether, and after how long, to try again. */
internal class AudioRecoveryRun(private val policy: AudioRecoveryPolicy, private val clock: () -> Long) {
    private var failures = 0
    private var firstFailureAt = 0L

    val attempts: Int get() = failures

    /** The code of the latest classified failure (null before the first). */
    var lastCode: AudioFailureCode? = null
        private set

    /** Milliseconds since the first classified failure (0 before it). */
    fun elapsedMs(): Long = if (failures == 0) 0L else clock() - firstFailureAt

    /** The wait before the next attempt after [error], or null when [error] ends the reply's audio (not transient, attempts or window used up). */
    fun nextWaitMs(error: Throwable): Long? {
        val code = AudioFailureClassifier.classify(error)
        if (failures > 0 || code.transient) lastCode = code
        if (!code.transient) return null
        val now = clock()
        if (failures == 0) firstFailureAt = now
        failures += 1
        if (failures >= policy.maxAttempts) return null
        if (now - firstFailureAt >= policy.windowMs) return null
        return policy.backoffAfter(failures)
    }
}

enum class AudioPhase { WAIT_STARTED, RECOVERED, EXHAUSTED, CANCELLED }

/** What happened to a reply's audio, as fixed values only: never text, a URL, an exception message or a device identifier. */
data class AudioRecoveryEvent(
    val phase: AudioPhase,
    val attempt: Int,
    val elapsedMs: Long,
    val code: AudioFailureCode?,
    val waitMs: Long? = null,
)
