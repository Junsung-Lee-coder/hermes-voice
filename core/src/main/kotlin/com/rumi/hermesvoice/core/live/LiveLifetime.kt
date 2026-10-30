package com.rumi.hermesvoice.core.live

/** Monotonic, finite cost policy. Only the caller's genuine dialogue activity can extend idle, never the absolute cap. */
class LiveLifetime(private val started: Long, private var idleMs: Long = 10_000, private val capMs: Long = 300_000, waitingForConnection: Boolean = false) {
    private var lastDialogue = started
    private var ended: String? = null
    private var connected = !waitingForConnection
    @Synchronized fun connected(now: Long) {
        if (!connected && ended == null) { connected = true; lastDialogue = maxOf(started, now) }
    }
    init { require(idleMs > 0 && capMs >= idleMs) }
    @Synchronized fun grace(seconds: Int) { require(seconds in 1..60); idleMs = seconds * 1000L }
    @Synchronized fun dialogue(now: Long) { if (expired(now) == null && now >= lastDialogue) lastDialogue = now }
    @Synchronized fun expired(now: Long): String? {
        if (ended == null) ended = when {
            now - started >= capMs -> "cap"
            connected && now - lastDialogue >= idleMs -> "idle"
            else -> null
        }
        return ended
    }
}
