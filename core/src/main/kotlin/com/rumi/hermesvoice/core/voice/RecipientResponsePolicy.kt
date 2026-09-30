package com.rumi.hermesvoice.core.voice

import com.rumi.hermesvoice.core.ResponsePlaybackSettings
import com.rumi.hermesvoice.core.SpokenRole

/**
 * A distinct recipient response as the Hermes gateway reports it. `message.delta` chunks are NOT
 * responses and never become one of these: [Interim] is a sealed `message.interim` segment (text
 * beside tool calls, or a pre-nudge answer) and [Complete] is the turn's `message.complete`.
 */
sealed class RecipientEvent {
    data class Interim(val text: String) : RecipientEvent()
    data class Complete(val text: String, val status: String?) : RecipientEvent()
}

/** [speakText] is null when the response is classified but must not be spoken (switch off or already heard). */
data class ResponseDecision(val role: SpokenRole, val text: String, val speakText: String?, val reason: String)

/**
 * Classifies one destination turn's recipient responses: FIRST is the first distinct response,
 * MIDDLE every other distinct non-final response, FINAL the terminal one. FINAL always plays, but
 * text the user already heard is never re-spoken: Hermes can seal the pending final answer as the
 * last interim and then complete with the same (or an extended) text, which the desktop also treats
 * as one message.
 */
class RecipientResponseTracker(private val settings: ResponsePlaybackSettings) {
    private val distinctNonFinal = LinkedHashSet<String>()
    private val spokenNonFinal = HashSet<String>()
    private var lastInterim: String? = null
    private var lastInterimSpoken = false
    private var finalSeen = false

    fun onEvent(event: RecipientEvent): ResponseDecision? = when (event) {
        is RecipientEvent.Interim -> onInterim(event.text)
        is RecipientEvent.Complete -> onComplete(event.text)
    }

    private fun onInterim(raw: String): ResponseDecision? {
        val text = raw.trim()
        if (text.isEmpty() || finalSeen) return null
        val key = normalize(text)
        if (!distinctNonFinal.add(key)) return null
        val role = if (distinctNonFinal.size == 1) SpokenRole.FIRST else SpokenRole.MIDDLE
        val speak = if (role == SpokenRole.FIRST) settings.playFirstResponse else settings.playMiddleResponses
        lastInterim = key
        lastInterimSpoken = speak
        if (speak) spokenNonFinal += key
        return ResponseDecision(role, text, if (speak) text else null,
            if (speak) "enabled" else "${role.name.lowercase()}_response_switch_off")
    }

    private fun onComplete(raw: String): ResponseDecision? {
        if (finalSeen) return null
        finalSeen = true
        val text = raw.trim()
        if (text.isEmpty()) return ResponseDecision(SpokenRole.FINAL, text, null, "final_empty")
        val key = normalize(text)
        if (key in spokenNonFinal) return ResponseDecision(SpokenRole.FINAL, text, null, "final_already_spoken")
        val interim = lastInterim
        if (interim != null && lastInterimSpoken) {
            if (interim.startsWith(key)) return ResponseDecision(SpokenRole.FINAL, text, null, "final_already_spoken")
            if (key.startsWith(interim)) {
                val remainder = key.substring(interim.length).trim()
                return ResponseDecision(SpokenRole.FINAL, text, remainder.ifEmpty { null },
                    if (remainder.isEmpty()) "final_already_spoken" else "final_remainder_after_spoken_interim")
            }
        }
        return ResponseDecision(SpokenRole.FINAL, text, text, "final_always_plays")
    }

    private fun normalize(text: String): String = text.replace(Regex("\\s+"), " ").trim()
}
