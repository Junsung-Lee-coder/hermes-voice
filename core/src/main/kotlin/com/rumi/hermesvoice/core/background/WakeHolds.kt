package com.rumi.hermesvoice.core.background

/** Why the CPU is kept awake, and the longest one hold of that kind may last. */
enum class HoldReason(val maxMs: Long) {
    /**
     * One recognizer window of an armed background session: its deadline plus a margin (a window
     * waiting on a partial result may be extended up to [com.rumi.hermesvoice.core.wake.WakeContract.PENDING_INACTIVITY_MS] at a time).
     */
    LISTEN(60_000L),

    /**
     * From the recognizer's release after the phrase to the app's recorder: the microphone handoff
     * pause and, in Both, the wait for the Phone's answer to the claim (which fails closed sooner).
     */
    HANDOFF(10_000L),

    /**
     * Between two windows of an armed session: the pause, a back-off, a retry while the Phone is
     * unreachable or the playback cooldown, plus the bounded reachability check before the next window.
     */
    REARM(90_000L),

    /** One recording: above the recorder's storage bound (about 13 minutes), which ends it first. */
    CAPTURE(14 * 60_000L),

    /** Handing one recording to the Phone. */
    TRANSFER(10 * 60_000L),

    /** One voice turn on the Phone: above the turn's own timeout, which ends it first. */
    TURN(16 * 60_000L),

    /** Playing one utterance and sending its acknowledgement. */
    PLAYBACK(15 * 60_000L),
}

/** The platform's partial wake lock, one per reason. */
interface WakeLockPort {
    fun acquire(reason: HoldReason, timeoutMs: Long)
    fun release(reason: HoldReason)
}

/**
 * CPU wake locks, each for a stated reason and with a timeout no longer than the reason allows, so
 * none can be held forever: the platform lets go at the timeout even if a release is missed.
 * Acquiring again never shortens a hold that lasts longer already; releasing what is not held
 * does nothing. [reconcile] moves a set of reasons to what is needed now, taking the new holds
 * before it lets go of the old ones.
 */
class WakeHolds(private val port: WakeLockPort, private val clock: () -> Long) {
    private val until = HashMap<HoldReason, Long>()

    @Synchronized
    fun acquire(reason: HoldReason, timeoutMs: Long = reason.maxMs) {
        val now = clock()
        val bounded = timeoutMs.coerceIn(1L, reason.maxMs)
        val current = until[reason]
        if (current != null && current > now && current >= now + bounded) return
        until[reason] = now + bounded
        port.acquire(reason, bounded)
    }

    /**
     * Of [managed] reasons, holds exactly those in [needed] (each for at least its timeout from
     * now): the needed ones are acquired or extended first, then the others are released.
     */
    @Synchronized
    fun reconcile(managed: Set<HoldReason>, needed: Map<HoldReason, Long>) {
        needed.forEach { (reason, timeoutMs) -> if (reason in managed) acquire(reason, timeoutMs) }
        held().filter { it in managed && it !in needed }.forEach(::release)
    }

    @Synchronized
    fun release(reason: HoldReason) {
        if (until.remove(reason) != null) port.release(reason)
    }

    @Synchronized
    fun releaseAll() = until.keys.toList().forEach(::release)

    /** The reasons still held (a hold past its timeout was already dropped by the platform). */
    @Synchronized
    fun held(): Set<HoldReason> {
        val now = clock()
        until.entries.removeAll { it.value <= now }
        return until.keys.toSet()
    }
}
