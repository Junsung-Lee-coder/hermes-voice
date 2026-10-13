package com.rumi.hermesvoice.core.text

/** A piece of a chat message: ordinary text, or an http(s) link whose [text] is exactly what the message says. */
sealed class MessageSegment {
    abstract val text: String

    class Plain(override val text: String) : MessageSegment()

    class Link(override val text: String, val url: String) : MessageSegment()
}

/**
 * Which parts of a chat message are openable links. The message text is never rewritten: every segment's [MessageSegment.text]
 * concatenates back to the input. Only absolute http/https URLs without embedded credentials qualify; a Markdown link
 * `[label](https://…)` stays visible as written and its URL is the tappable part.
 */
object MessageLinks {
    const val MAX_URL = 2048
    const val MAX_LINKS = 20
    private const val MAX_SCAN = 100_000
    // scheme://host[:port] then an optional path/query/fragment; an '@' before the first '/', '?' or '#' means credentials.
    private val URL_SHAPE = Regex("""(?i)https?://((?:\[[0-9a-f:.]+]|[\p{L}\p{N}](?:[\p{L}\p{N}._~%-]*[\p{L}\p{N}])?))(:\d{1,5})?(?:[/?#]\S*)?""")
    private val CANDIDATE = Regex("""(?i)(?<![A-Za-z0-9+.-])https?://[^\s<>"`]+""")
    private val TRAILING = charArrayOf('.', ',', ';', ':', '!', '?', '\'', '*', '_', '~')

    /** The URL to open for [raw], or null when it is not a safe http/https link. */
    fun safeUrl(raw: String): String? {
        val candidate = raw.trim()
        if (candidate.isEmpty() || candidate.length > MAX_URL) return null
        if (candidate.any { it.isISOControl() || it.isWhitespace() || it == '\\' }) return null
        val match = URL_SHAPE.matchEntire(candidate) ?: return null
        val port = match.groupValues[2].removePrefix(":")
        if (port.isNotEmpty() && port.toInt() !in 1..65535) return null
        return candidate
    }

    fun segments(text: String): List<MessageSegment> {
        if (text.isEmpty()) return emptyList()
        if (text.length > MAX_SCAN) return listOf(MessageSegment.Plain(text))
        val out = ArrayList<MessageSegment>()
        var cursor = 0
        var links = 0
        for (match in CANDIDATE.findAll(text)) {
            if (links >= MAX_LINKS) break
            val end = match.range.first + trimmedLength(match.value)
            val url = safeUrl(text.substring(match.range.first, end)) ?: continue
            if (match.range.first > cursor) out += MessageSegment.Plain(text.substring(cursor, match.range.first))
            out += MessageSegment.Link(text.substring(match.range.first, end), url)
            cursor = end
            links++
        }
        if (cursor < text.length) out += MessageSegment.Plain(text.substring(cursor))
        return out
    }

    /** The length of [candidate] without sentence punctuation and without closing brackets that have no opener inside it. */
    private fun trimmedLength(candidate: String): Int {
        var end = candidate.length
        while (end > 0) {
            val last = candidate[end - 1]
            val drop = when {
                last in TRAILING -> true
                last == ')' -> candidate.substring(0, end).count { it == ')' } > candidate.substring(0, end).count { it == '(' }
                last == ']' -> candidate.substring(0, end).count { it == ']' } > candidate.substring(0, end).count { it == '[' }
                last == '}' -> candidate.substring(0, end).count { it == '}' } > candidate.substring(0, end).count { it == '{' }
                else -> false
            }
            if (!drop) break
            end--
        }
        return end
    }
}
