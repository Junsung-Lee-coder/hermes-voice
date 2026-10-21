package com.rumi.hermesvoice.core.net

/**
 * The gateway's whole-response silence contract (`gateway/response_filters.py`, `is_intentional_silence_response`): an assistant
 * response that is exactly one marker, after trimming, optional edge punctuation (never `[` or `]`) and case/space folding.
 * Prose that merely mentions a marker is not silence and a blank response is not silence either (that stays the empty-answer path).
 * Only an assistant row or terminal whose whole text is a marker is a control; nothing is ever stripped out of longer text.
 */
object SilentControl {
    private val MARKERS = setOf("[SILENT]", "SILENT", "NO_REPLY", "NO REPLY", "[静默]", "静默", "[沉默]", "沉默")
    private const val LENGTH_CAP = 64

    fun isWholeResponse(text: String?): Boolean {
        if (text == null) return false
        val stripped = trim(text)
        if (stripped.isEmpty() || stripped.codePointCount(0, stripped.length) > LENGTH_CAP) return false
        if (canonical(stripped) in MARKERS) return true
        val bare = trim(stripEdgePunctuation(stripped))
        return bare != stripped && canonical(bare) in MARKERS
    }

    private fun isSpace(ch: Int) = Character.isWhitespace(ch) || Character.isSpaceChar(ch)

    private fun trim(text: String): String {
        var start = 0
        var end = text.length
        while (start < end) {
            val cp = text.codePointAt(start)
            if (!isSpace(cp)) break
            start += Character.charCount(cp)
        }
        while (end > start) {
            val cp = text.codePointBefore(end)
            if (!isSpace(cp)) break
            end -= Character.charCount(cp)
        }
        return text.substring(start, end)
    }

    private fun isEdgePunctuation(cp: Int): Boolean {
        if (cp == '['.code || cp == ']'.code) return false
        return when (Character.getType(cp).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    private fun stripEdgePunctuation(text: String): String {
        var start = 0
        var end = text.length
        while (start < end) {
            val cp = text.codePointAt(start)
            if (!isEdgePunctuation(cp)) break
            start += Character.charCount(cp)
        }
        while (end > start) {
            val cp = text.codePointBefore(end)
            if (!isEdgePunctuation(cp)) break
            end -= Character.charCount(cp)
        }
        return text.substring(start, end)
    }

    private fun canonical(text: String): String {
        val folded = text.uppercase()
        val out = StringBuilder(folded.length)
        var pendingSpace = false
        var i = 0
        while (i < folded.length) {
            val cp = folded.codePointAt(i)
            i += Character.charCount(cp)
            if (isSpace(cp)) {
                pendingSpace = out.isNotEmpty()
            } else {
                if (pendingSpace) out.append(' ')
                pendingSpace = false
                out.appendCodePoint(cp)
            }
        }
        return out.toString()
    }
}
