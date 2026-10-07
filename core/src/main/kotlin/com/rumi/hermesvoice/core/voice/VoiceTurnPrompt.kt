package com.rumi.hermesvoice.core.voice

/**
 * What the destination conversation sees of ONE voice request: a leading microphone marker, one space, and the user's
 * own words unchanged. The owner's saved memory tells the model that a request starting with the marker is a voice
 * request to answer briefly; nothing else is appended. It is added only here, at the final recipient submit of a request
 * made by voice; the router's prompt and text/picker submissions keep the original words alone. A typed leading
 * marker is voice by convention (no anti-spoofing). The marker stays visible in stored history and previews.
 */
object VoiceTurnPrompt {
    const val MARKER = "🎙"

    fun compose(original: String): String = if (original.startsWith(MARKER)) original else "$MARKER $original"
}
