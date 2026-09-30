package com.rumi.hermesvoice.core.wake

import com.rumi.hermesvoice.core.settings.WakePhrasePatterns

/**
 * Wake-phrase contract (foreground only, opt-in, Phone-owned patterns), after the original
 * Recorder Watch "Method A":
 *
 * 1. While the Watch app is visibly in the foreground, one bounded [WINDOW_MS] window is opened
 *    per visibility generation (app shown, or screen back on while shown). The platform
 *    `SpeechRecognizer` is the only microphone user during the window.
 * 2. A wake phrase counts only at the start of what was said (after at most one short greeting,
 *    see [WakePhrasePatterns.leadingRequest]); a mention in the middle of a sentence is ignored.
 * 3. Only the recognizer's FINAL result is ever accepted as the user's words:
 *    - wake phrase only → [WakeHandoff.SECOND_UTTERANCE]: the recognizer is released, the app's
 *      own `AudioRecord` starts, calibrates, and a ready haptic tells the user to speak the
 *      request, which ends on trailing silence (no duration cap) or a separate no-speech timeout;
 *    - wake phrase followed by more words → [WakeHandoff.RECOGNIZED_REQUEST]: the recognizer
 *      already heard the whole request, so its final text is sent, complete, as the request.
 *    Partial results only keep the window open: after a leading wake phrase, every change in the
 *    partial text extends it by [PENDING_INACTIVITY_MS], so a long request is never cut by a total
 *    time limit. If the recognizer errors or goes quiet before a final result, a phrase-only
 *    partial still cues a second utterance (nothing was said yet), but an unfinished request is
 *    never sent: the window closes with "unfinished_request" and the Watch asks the user to repeat.
 *    The same happens when a leading wake phrase was heard in a partial but the final result
 *    disagrees or is empty; only speech that never led with the wake phrase closes silently.
 *    The system recognizer applies its own pause detection (and may cap a session), so a long
 *    request is best said after the buzz, into the app's own recorder, which has no limit.
 * 4. Recognized text never leaves the Watch except as the request of rule 3 and is never logged.
 */
object WakeContract {
    const val WINDOW_MS = 5_000L

    /** Quiet time after the last change of a pending partial before the window gives up. */
    const val PENDING_INACTIVITY_MS = 8_000L

    /** Longest recognized request carried as text; a longer one is refused, never truncated. */
    const val MAX_REQUEST_CHARS = 4_000

    /** Quiet period after playback on the device ends, so the reply cannot trigger the wake phrase. */
    const val PLAYBACK_COOLDOWN_MS = 4_000L

    /** Pause between releasing the recognizer and opening the app's recorder. */
    const val MIC_HANDOFF_MS = 300L

    /** How long a wake claim lasts without renewal (see [WakeAdmission]). */
    const val CLAIM_TTL_MS = 10_000L

    /** How often the holder renews its claim while it listens, hands off and records. */
    const val CLAIM_RENEW_MS = 3_000L

    /** How long a device waits for the Phone's answer to a claim before failing closed. */
    const val CLAIM_TIMEOUT_MS = 2_500L

    /** How long after a wake episode ends the other device's claim for it is still refused. */
    const val CLAIM_SETTLE_MS = 3_000L
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
    private val pendingInactivityMs: Long = WakeContract.PENDING_INACTIVITY_MS,
) {
    private var generation: Long? = null
    private var deadlineMs = 0L

    /** The request text of the latest leading-wake partial, or null before one was heard. */
    private var pending: String? = null

    val active: Boolean get() = generation != null

    @Synchronized
    fun open(generation: Long, nowMs: Long) {
        this.generation = generation
        deadlineMs = nowMs + windowMs
        pending = null
    }

    @Synchronized
    fun onResults(generation: Long, hypotheses: List<String>, final: Boolean, nowMs: Long, patterns: String): WakeOutcome {
        if (generation != this.generation) return WakeOutcome.None
        if (nowMs > deadlineMs) return expire("deadline")
        val request = hypotheses.asSequence().mapNotNull { WakePhrasePatterns.leadingRequest(patterns, it) }.firstOrNull()
        if (final) {
            return when {
                // A leading wake phrase was heard but the final disagrees or is empty: the user's words
                // were consumed, so say so ("unfinished_request" → retry notice); only ambient speech is silent.
                request == null -> close(WakeOutcome.Closed(if (pending != null) "unfinished_request" else "not_matched"))
                request.length > WakeContract.MAX_REQUEST_CHARS -> close(WakeOutcome.Closed("request_too_long"))
                request.isBlank() -> close(WakeOutcome.Handoff(WakeHandoff.SECOND_UTTERANCE, ""))
                else -> close(WakeOutcome.Handoff(WakeHandoff.RECOGNIZED_REQUEST, request))
            }
        }
        if (request != null && request != pending) {
            pending = request
            deadlineMs = maxOf(deadlineMs, nowMs + pendingInactivityMs)
        }
        return WakeOutcome.None
    }

    @Synchronized
    fun onError(generation: Long, code: Int): WakeOutcome {
        if (generation != this.generation) return WakeOutcome.None
        return expire("recognizer_error_$code")
    }

    @Synchronized
    fun onDeadline(generation: Long, nowMs: Long): WakeOutcome {
        if (generation != this.generation || nowMs < deadlineMs) return WakeOutcome.None
        return expire("timeout")
    }

    /** Closes the window (screen off, pause, busy, opt-out...); a pending partial is abandoned. */
    @Synchronized
    fun cancel(reason: String): WakeOutcome = if (generation == null) WakeOutcome.None else close(WakeOutcome.Closed(reason))

    @Synchronized
    fun deadline(): Long = deadlineMs

    /** No final result: a phrase-only partial may still cue a second utterance; words are never sent. */
    private fun expire(reason: String): WakeOutcome = when {
        pending == null -> close(WakeOutcome.Closed(reason))
        pending!!.isBlank() -> close(WakeOutcome.Handoff(WakeHandoff.SECOND_UTTERANCE, ""))
        else -> close(WakeOutcome.Closed("unfinished_request"))
    }

    private fun close(outcome: WakeOutcome): WakeOutcome {
        generation = null
        pending = null
        return outcome
    }
}

enum class WakeBlock { DISABLED, NOT_FOREGROUND, PERMISSION, MICROPHONE_MUTED, BUSY, PHONE_UNREACHABLE, COOLDOWN, ALREADY_ARMED, UNAVAILABLE }

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

/** The platform recognizer, as the wake window needs it. */
interface WakeRecognizerPort {
    fun available(): Boolean

    /** Creates and starts a recognizer whose callbacks carry [generation]; false if it could not start. */
    fun start(generation: Long): Boolean

    /** Cancels and destroys the recognizer, releasing the microphone. */
    fun release()
}

interface WakeTimerPort {
    /** (Re)schedules the single deadline check, replacing any earlier one. */
    fun schedule(delayMs: Long)
    fun cancel()
}

interface WakeHostPort {
    fun windowChanged(open: Boolean)

    /** Called only after the recognizer was released. */
    fun handoff(generation: Long, handoff: WakeOutcome.Handoff)
    fun closed(reason: String)
}

/**
 * The wake window's lifecycle, independent of Android: arming per visibility generation, the
 * deadline timer, and resolution. On any resolution the timer is cancelled and the recognizer is
 * released BEFORE the host hears of a handoff, so the recognizer and the app's recorder never
 * hold the microphone together.
 */
class WakeWindowCoordinator(
    private val recognizer: WakeRecognizerPort,
    private val timer: WakeTimerPort,
    private val host: WakeHostPort,
    private val clock: () -> Long,
) {
    private val session = WakeSession()
    private var open = false
    private var windowGeneration = -1L
    private var lastArmedGeneration: Long? = null

    /** The current visibility generation (app shown, screen back on while shown). */
    var generation = 0L
        private set

    /** A recognizer window is open. */
    val listening: Boolean get() = session.active

    /** Starts a new visibility generation (resume, or screen off); an open window closes with [reason]. */
    fun newGeneration(reason: String) {
        close(reason)
        generation += 1
    }

    /** Opens a window unless a gate blocks it; returns the blocking reason, or null when it opened. */
    fun requestArm(inputs: WakeArmInputs): WakeBlock? {
        if (session.active) return WakeBlock.ALREADY_ARMED
        WakeArmGate.block(inputs.copy(generation = generation, lastArmedGeneration = lastArmedGeneration))?.let { return it }
        lastArmedGeneration = generation
        if (!recognizer.available()) {
            host.closed("unavailable")
            return WakeBlock.UNAVAILABLE
        }
        windowGeneration = generation
        session.open(generation, clock())
        open = true
        host.windowChanged(true)
        timer.schedule(session.deadline() - clock())
        if (!recognizer.start(generation)) resolve(session.cancel("start_failed"))
        return null
    }

    fun onResults(generation: Long, hypotheses: List<String>, final: Boolean, patterns: String) =
        resolve(session.onResults(generation, hypotheses, final, clock(), patterns))

    fun onError(generation: Long, code: Int) = resolve(session.onError(generation, code))

    fun onTimer() = resolve(session.onDeadline(windowGeneration, clock()))

    fun close(reason: String) = resolve(session.cancel(reason))

    private fun resolve(outcome: WakeOutcome) {
        when (outcome) {
            WakeOutcome.None -> if (session.active) timer.schedule((session.deadline() - clock()).coerceAtLeast(0L))
            is WakeOutcome.Closed -> {
                release()
                host.closed(outcome.reason)
            }
            is WakeOutcome.Handoff -> {
                release()
                host.handoff(windowGeneration, outcome)
            }
        }
    }

    private fun release() {
        timer.cancel()
        if (!open) return
        open = false
        recognizer.release()
        host.windowChanged(false)
    }
}

/**
 * The short pause between releasing the recognizer and opening the app's recorder, as a
 * cancellable, generation-bound token: a pause, opt-out or busy state cancels it, and a handoff
 * from an older generation never starts the recorder.
 */
class WakeHandoffGate {
    private var pendingGeneration: Long? = null

    /** A handoff is scheduled and not yet claimed or cancelled: the recorder is about to own the microphone. */
    val pending: Boolean
        @Synchronized get() = pendingGeneration != null

    @Synchronized
    fun schedule(generation: Long) {
        pendingGeneration = generation
    }

    @Synchronized
    fun cancel() {
        pendingGeneration = null
    }

    /** True at most once, and only for the still-current generation while resumed and idle. */
    @Synchronized
    fun claim(generation: Long, currentGeneration: Long, resumed: Boolean, captureIdle: Boolean): Boolean {
        if (pendingGeneration != generation) return false
        pendingGeneration = null
        return generation == currentGeneration && resumed && captureIdle
    }
}

/**
 * Tells a platform recognizer's callbacks apart from those of one that was already released:
 * each recognizer gets a token from [open]; a callback acts only while its token [isCurrent]. A
 * late error or result from a destroyed recognizer can then never touch the next window's.
 */
class RecognizerGuard {
    private var current = 0L
    private var open = false

    @Synchronized
    fun open(): Long {
        current += 1
        open = true
        return current
    }

    @Synchronized
    fun close() {
        open = false
    }

    @Synchronized
    fun isCurrent(token: Long): Boolean = open && token == current
}
