package com.rumi.hermesvoice.core.voice

import com.rumi.hermesvoice.core.VoiceOrigin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where an accepted voice request is in its life (what the Phone's and the Watch's "pending" views say). */
enum class PendingPhase {
    /** Being transcribed, routed, acknowledged or sent: the microphone/transfer work is still going on. */
    TRANSMITTING,

    /** No longer entered: a request to a busy conversation is sent at once and Hermes decides what to do with it. Kept for the Phone/Watch wire format. */
    QUEUED,

    /** Delivered; waiting for the destination's reply. Nothing is held on the device meanwhile. */
    AWAITING,

    /** Its reply is playing. */
    SPEAKING,
}

/** One pending voice request. [alias] is the destination's display name (null before routing); never text or ids. */
data class PendingTurn(val turnId: String, val origin: VoiceOrigin, val phase: PendingPhase, val alias: String? = null)

/** The accepted voice requests that are not finished, in acceptance order (bounded by the orchestrator). */
class PendingTurns {
    private val items = LinkedHashMap<String, PendingTurn>()
    private val _state = MutableStateFlow<List<PendingTurn>>(emptyList())
    val state: StateFlow<List<PendingTurn>> = _state

    val size: Int @Synchronized get() = items.size

    @Synchronized
    fun add(turn: PendingTurn) {
        items[turn.turnId] = turn
        publish()
    }

    @Synchronized
    fun update(turnId: String, phase: PendingPhase, alias: String? = null) {
        val current = items[turnId] ?: return
        items[turnId] = current.copy(phase = phase, alias = alias ?: current.alias)
        publish()
    }

    @Synchronized
    fun remove(turnId: String) {
        if (items.remove(turnId) != null) publish()
    }

    private fun publish() {
        _state.value = items.values.toList()
    }
}

/**
 * A finite overload guard per destination session: at most [maxPerSession] of this app's requests may be pending for one
 * conversation at once. It never makes a request wait for an earlier one: whether a request sent while Hermes works steers,
 * queues or interrupts is Hermes' own busy-input policy, and the app only sends the content.
 */
internal class SessionGate(private val maxPerSession: Int) {
    class Ticket(val session: String)

    private val lines = HashMap<String, ArrayList<Ticket>>()

    /** A place for the request, or null when the session already has [maxPerSession] pending (refused visibly, not dropped silently). */
    @Synchronized
    fun enter(session: String): Ticket? {
        val line = lines.getOrPut(session) { ArrayList() }
        if (line.size >= maxPerSession) return null
        return Ticket(session).also { line += it }
    }

    /** Leaves (answered, failed, stopped or never delivered). Safe to repeat. */
    @Synchronized
    fun release(ticket: Ticket) {
        val line = lines[ticket.session] ?: return
        if (line.remove(ticket) && line.isEmpty()) lines.remove(ticket.session)
    }

    @Synchronized
    fun pending(session: String): Int = (lines[session]?.size ?: 0)
}
