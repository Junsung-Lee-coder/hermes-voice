package com.rumi.hermesvoice.core.activity

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** One request's transient projection: opened when it is attributable, replaced in place, ended with the real outcome. */
sealed class ActivityEvent {
    data class Begin(val key: String, val sessionId: String) : ActivityEvent()
    data class Update(val key: String, val label: String) : ActivityEvent()
    data class End(val key: String, val replied: Boolean) : ActivityEvent()
}

/** [closing]: the final reply arrived; the slot stays only until that reply is in the history, then it is released. */
data class ActivityBubble(val key: String, val sessionId: String, val label: String, val closing: Boolean = false)

/** [closed]: keys that ended (most recent last), so a late, duplicate or replayed event can never resurrect one. */
data class ActivityState(val bubbles: List<ActivityBubble> = emptyList(), val closed: List<String> = emptyList())

object ActivityReducer {
    const val MAX_BUBBLES = 8
    const val MAX_CLOSED = 64

    fun apply(state: ActivityState, event: ActivityEvent): ActivityState = when (event) {
        is ActivityEvent.Begin -> {
            if (event.key in state.closed || state.bubbles.any { it.key == event.key }) state
            else {
                var bubbles = state.bubbles + ActivityBubble(event.key, event.sessionId, ActivityLabels.WAITING)
                var closed = state.closed
                while (bubbles.size > MAX_BUBBLES) {
                    val victim = bubbles.firstOrNull { it.closing } ?: bubbles.first()
                    bubbles = bubbles - victim
                    closed = bound(closed + victim.key)
                }
                ActivityState(bubbles, closed)
            }
        }
        is ActivityEvent.Update -> {
            val at = state.bubbles.indexOfFirst { it.key == event.key }
            if (at < 0 || state.bubbles[at].closing || state.bubbles[at].label == event.label) state
            else state.copy(bubbles = state.bubbles.toMutableList().also { it[at] = it[at].copy(label = event.label) })
        }
        is ActivityEvent.End -> {
            val at = state.bubbles.indexOfFirst { it.key == event.key }
            if (at < 0) {
                if (event.key in state.closed) state else state.copy(closed = bound(state.closed + event.key))
            } else if (state.bubbles[at].closing) state
            else if (event.replied) {
                ActivityState(state.bubbles.toMutableList().also { it[at] = it[at].copy(closing = true) }, bound(state.closed + event.key))
            } else {
                ActivityState(state.bubbles.filterIndexed { i, _ -> i != at }, bound(state.closed + event.key))
            }
        }
    }

    /** The final reply is now in the conversation's history: drop that conversation's closing slots (active ones stay). */
    fun release(state: ActivityState, sessionId: String): ActivityState {
        val kept = state.bubbles.filterNot { it.closing && it.sessionId == sessionId }
        return if (kept.size == state.bubbles.size) state else state.copy(bubbles = kept)
    }

    private fun bound(closed: List<String>) = if (closed.size > MAX_CLOSED) closed.takeLast(MAX_CLOSED) else closed
}

/**
 * The shared write side the transport and the voice orchestrator publish to and any viewer observes. State changes only through
 * [ActivityReducer]; only state-changing events are emitted, in order. [snapshot] holds the active slots only, so a viewer that
 * starts late is seeded without ever seeing a slot whose final reply has already arrived.
 */
class ActivityFeed {
    private val lock = Any()
    private var state = ActivityState()
    private val bus = MutableSharedFlow<ActivityEvent>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<ActivityEvent> = bus

    fun begin(key: String, sessionId: String) = publish(ActivityEvent.Begin(key, sessionId))
    fun update(key: String, label: String) = publish(ActivityEvent.Update(key, label))
    fun end(key: String, replied: Boolean) = publish(ActivityEvent.End(key, replied))

    fun snapshot(): ActivityState = synchronized(lock) { ActivityState(state.bubbles.filterNot { it.closing }, state.closed) }

    private fun publish(event: ActivityEvent) {
        synchronized(lock) {
            val next = ActivityReducer.apply(state, event)
            if (next == state) return
            state = if (event is ActivityEvent.End) next.copy(bubbles = next.bubbles.filterNot { it.key == event.key }) else next
            bus.tryEmit(event)
        }
    }
}
