package com.rumi.hermesvoice.core.wake

import com.rumi.hermesvoice.core.settings.WakePhrasePatterns

/**
 * Wake-phrase contract (foreground only, opt-in, Phone-owned patterns), after the original
 * Recorder Watch "Method A":
 *
 * 1. While the Watch app is visibly in the foreground, one bounded [WINDOW_MS] window is opened
 *    per visibility generation (app shown, or screen back on / out of ambient while shown). The
 *    platform `SpeechRecognizer` is the only microphone user during the window.
 * 2. The decision is made on the recognizer's FINAL result (a partial match extends the window by
 *    [FINAL_GRACE_MS] so the recognizer can finish the utterance):
 *    - wake phrase only → [WakeHandoff.SECOND_UTTERANCE]: the recognizer is released, the app's
 *      own `AudioRecord` starts, calibrates, and a ready haptic tells the user to speak the request.
 *      That capture ends on trailing silence (no duration cap) or a separate no-speech timeout.
 *    - wake phrase followed by more words → [WakeHandoff.RECOGNIZED_REQUEST]: the recognizer
 *      already consumed the request, so its text is sent as the request instead of being dropped;
 *      no second utterance is expected.
 * 3. Wake-recognizer text never leaves the Watch except as the request of rule 2.
 */
object WakeContract {
    const val WINDOW_MS = 5_000L
    const val FINAL_GRACE_MS = 8_000L
    const val MAX_REQUEST_CHARS = 1_000

    /** Quiet period after Watch playback ends, so the reply cannot trigger the wake phrase. */
    const val PLAYBACK_COOLDOWN_MS = 4_000L
}

enum class WakeHandoff { SECOND_UTTERANCE, RECOGNIZED_REQUEST }

sealed class WakeOutcome {
    /** Keep listening (or an event for a stale/closed window: ignore it). */
    object None : WakeOutcome() {
        override fun toString() = "None"
    }

    data class Handoff(val contract: WakeHandoff, val request: String) : WakeOutcome()

    data class Closed(val reason: String) : WakeOutcome()
}

/**
 * One bounded recognizer window. Every event carries the generation it was opened with; events
 * for any other generation, or after the window resolved, return [WakeOutcome.None], so a result
 * can be handed off at most once.
 */
class WakeSession(
    private val windowMs: Long = WakeContract.WINDOW_MS,
    private val finalGraceMs: Long = WakeContract.FINAL_GRACE_MS,
) {
    private var generation: Long? = null
    private var deadlineMs = 0L
    private var matchedRequest: String? = null

    val active: Boolean get() = generation != null

    @Synchronized
    fun open(generation: Long, nowMs: Long) {
        this.generation = generation
        deadlineMs = nowMs + windowMs
        matchedRequest = null
    }

    @Synchronized
    fun onResults(generation: Long, hypotheses: List<String>, final: Boolean, nowMs: Long, patterns: String): WakeOutcome {
        if (generation != this.generation) return WakeOutcome.None
        if (nowMs > deadlineMs) return close(WakeOutcome.Closed("deadline"))
        val request = hypotheses.asSequence().mapNotNull { WakePhrasePatterns.requestAfterWake(patterns, it) }.firstOrNull()
        return when {
            request == null && final -> matchedRequest?.let(::handoff) ?: close(WakeOutcome.Closed("not_matched"))
            request == null -> WakeOutcome.None
            final -> handoff(request)
            else -> {
                if (matchedRequest == null) deadlineMs = maxOf(deadlineMs, nowMs + finalGraceMs)
                matchedRequest = request
                WakeOutcome.None
            }
        }
    }

    /** A recognizer error; a phrase already matched in a partial result is still handed off. */
    @Synchronized
    fun onError(generation: Long, code: Int): WakeOutcome {
        if (generation != this.generation) return WakeOutcome.None
        return matchedRequest?.let(::handoff) ?: close(WakeOutcome.Closed("recognizer_error_$code"))
    }

    @Synchronized
    fun onDeadline(generation: Long, nowMs: Long): WakeOutcome {
        if (generation != this.generation || nowMs < deadlineMs) return WakeOutcome.None
        return matchedRequest?.let(::handoff) ?: close(WakeOutcome.Closed("timeout"))
    }

    /** Closes the window (screen off, pause, busy, opt-out...); a pending match is abandoned. */
    @Synchronized
    fun cancel(reason: String): WakeOutcome = if (generation == null) WakeOutcome.None else close(WakeOutcome.Closed(reason))

    @Synchronized
    fun deadline(): Long = deadlineMs

    private fun handoff(request: String): WakeOutcome {
        val bounded = request.take(WakeContract.MAX_REQUEST_CHARS)
        return close(WakeOutcome.Handoff(if (bounded.isBlank()) WakeHandoff.SECOND_UTTERANCE else WakeHandoff.RECOGNIZED_REQUEST, bounded))
    }

    private fun close(outcome: WakeOutcome): WakeOutcome {
        generation = null
        matchedRequest = null
        return outcome
    }
}

enum class WakeBlock { DISABLED, NOT_FOREGROUND, PERMISSION, MICROPHONE_MUTED, BUSY, PHONE_UNREACHABLE, COOLDOWN, ALREADY_ARMED }

data class WakeArmInputs(
    val enabled: Boolean,
    val resumed: Boolean,
    val interactive: Boolean,
    val ambient: Boolean,
    val permission: Boolean,
    val microphoneMuted: Boolean,
    /** Nothing is recording, uploading, waiting on the Phone, or playing on this Watch. */
    val talkIdle: Boolean,
    val phoneReachable: Boolean?,
    val nowMs: Long,
    val cooldownUntilMs: Long,
    val generation: Long,
    val lastArmedGeneration: Long?,
)

/** Whether a wake window may open now; the first failing gate, or null when it may. */
object WakeArmGate {
    fun block(inputs: WakeArmInputs): WakeBlock? = when {
        !inputs.enabled -> WakeBlock.DISABLED
        !inputs.resumed || !inputs.interactive || inputs.ambient -> WakeBlock.NOT_FOREGROUND
        !inputs.permission -> WakeBlock.PERMISSION
        inputs.microphoneMuted -> WakeBlock.MICROPHONE_MUTED
        !inputs.talkIdle -> WakeBlock.BUSY
        inputs.phoneReachable == false -> WakeBlock.PHONE_UNREACHABLE
        inputs.nowMs < inputs.cooldownUntilMs -> WakeBlock.COOLDOWN
        inputs.lastArmedGeneration == inputs.generation -> WakeBlock.ALREADY_ARMED
        else -> null
    }
}
