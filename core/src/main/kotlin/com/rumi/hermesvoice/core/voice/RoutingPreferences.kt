package com.rumi.hermesvoice.core.voice

import com.rumi.hermesvoice.core.VoiceOrigin

/**
 * How one voice turn picks its destination: the Phone's routing switch, frozen into the request
 * when the turn starts, so changing the switch later never reroutes a turn already on its way.
 */
sealed class TurnRouting {
    /** Routing on (the default): the router session chooses an allowlisted alias or asks for a new conversation. */
    object Model : TurnRouting()

    /**
     * Routing off: the router is not asked. The turn goes to the conversation the user selected on
     * the device that sent it ([storedSessionId]; null when nothing was selected), and only if that
     * is one of this app's active conversations.
     */
    data class Direct(val storedSessionId: String?) : TurnRouting()

    companion object {
        /** Routing off and no conversation selected on the sending device: refused before acceptance. */
        const val NO_TARGET = "routing_off_no_target"

        /** Routing off and the selected conversation is not (or no longer) one of this app's active ones. */
        const val TARGET_UNAVAILABLE = "routing_off_target_unavailable"

        /** A malformed selection counts as none: it is never looked up, let alone used. */
        fun of(routingEnabled: Boolean, selectedStoredSessionId: String?): TurnRouting =
            if (routingEnabled) Model else Direct(selectedStoredSessionId?.takeIf(DestinationAllowlist::isValidSessionId))
    }
}

/**
 * The acknowledgement for a turn sent with routing off, worded by the Phone from the conversation
 * the user selected (there is no router sentence). Korean when the request contains Hangul,
 * otherwise English, like [CreateAck].
 */
object DirectAck {
    private val HANGUL = Regex("[\\uAC00-\\uD7A3\\u1100-\\u11FF\\u3130-\\u318F]")

    fun compose(alias: String, transcript: String): String =
        if (HANGUL.containsMatchIn(transcript)) "$alias 대화로 보냅니다." else "Sending to $alias."
}

/**
 * "Open the routed conversation": tells the Phone UI to show the conversation a routed turn went
 * to. The preference is read once, when the turn is accepted. It fires at most once per turn, only
 * after the destination accepted the transcript ([VoiceTurnListener.onDelivered]), and only for a
 * routed (not a routing-off) turn that is still the newest accepted one, with no navigation by the
 * user since it was accepted. Turns that are rejected, fail or are replays never reach it.
 */
class RoutedNavigation(
    private val autoNavigate: () -> Boolean,
    private val navigate: (storedSessionId: String) -> Unit,
) : VoiceTurnListener {
    private class Accepted(val sequence: Long, val enabled: Boolean, val manualEpoch: Long)

    private val lock = Any()
    private val accepted = LinkedHashMap<String, Accepted>()
    private var sequence = 0L
    private var manualEpoch = 0L

    override fun onAccepted(turnId: String, origin: VoiceOrigin) {
        val enabled = autoNavigate()
        synchronized(lock) {
            accepted[turnId] = Accepted(++sequence, enabled, manualEpoch)
            while (accepted.size > MAX_TRACKED) accepted.remove(accepted.keys.first())
        }
    }

    /** The user opened a conversation or changed screens: older turns no longer move the screen. */
    fun onManualNavigation() = synchronized(lock) { manualEpoch += 1 }

    override fun onDelivered(route: AssembledRoute) {
        val go = synchronized(lock) {
            val turn = accepted.remove(route.turnId) ?: return
            turn.enabled && !route.direct && turn.manualEpoch == manualEpoch && turn.sequence == sequence
        }
        if (go) navigate(route.destination.storedSessionId)
    }

    private companion object {
        const val MAX_TRACKED = 32
    }
}
