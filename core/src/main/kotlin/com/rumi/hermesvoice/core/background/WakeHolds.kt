package com.rumi.hermesvoice.core.background

/** Why the CPU is kept awake, and the longest one hold of that kind may last. */
enum class HoldReason(val maxMs: Long) {
    /** One recognizer window of an armed background session. */
    LISTEN(40_000L),

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
 * Acquiring again restarts that reason's timeout; releasing what is not held does nothing.
 */
class WakeHolds(private val port: WakeLockPort, private val clock: () -> Long) {
    private val until = HashMap<HoldReason, Long>()

    @Synchronized
    fun acquire(reason: HoldReason, timeoutMs: Long = reason.maxMs) {
        val bounded = timeoutMs.coerceIn(1L, reason.maxMs)
        until[reason] = clock() + bounded
        port.acquire(reason, bounded)
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
