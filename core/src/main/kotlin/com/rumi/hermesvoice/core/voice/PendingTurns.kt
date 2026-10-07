package com.rumi.hermesvoice.core.voice

import com.rumi.hermesvoice.core.VoiceOrigin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where an accepted voice request is in its life (what the Phone's and the Watch's "pending" views say). */
enum class PendingPhase {
    /** Being transcribed, routed, acknowledged or sent: the microphone/transfer work is still going on. */
    TRANSMITTING,

    /** Waiting for the earlier request to the same conversation to be answered; nothing was sent yet. */
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
 * One original reply outstanding per destination session. Events of a conversation carry no
 * per-prompt id, so a second request to a conversation whose earlier request is not answered yet
 * could take that one's reply. Requests to the same session therefore wait in a bounded FIFO
 * ([Ticket.ready]); requests to different sessions never wait for each other.
 */
internal class SessionGate(private val maxPerSession: Int) {
    class Ticket(val session: String) {
        /** Completed when every earlier ticket of the session was released. */
        val ready = CompletableDeferred<Unit>()
    }

    private val queues = HashMap<String, ArrayList<Ticket>>()

    /** The place in line, or null when the session's line is full (the request is refused visibly, not dropped silently). */
    @Synchronized
    fun enter(session: String): Ticket? {
        val line = queues.getOrPut(session) { ArrayList() }
        if (line.size >= maxPerSession) return null
        val ticket = Ticket(session)
        line += ticket
        if (line.size == 1) ticket.ready.complete(Unit)
        return ticket
    }

    /** Leaves the line (answered, failed, stopped or never delivered); the next ticket of the session may go. Safe to repeat. */
    @Synchronized
    fun release(ticket: Ticket) {
        val line = queues[ticket.session] ?: return
        val wasHead = line.firstOrNull() === ticket
        if (!line.remove(ticket)) return
        if (line.isEmpty()) queues.remove(ticket.session) else if (wasHead) line.first().ready.complete(Unit)
    }

    @Synchronized
    fun waiting(session: String): Int = (queues[session]?.size ?: 0)
}
