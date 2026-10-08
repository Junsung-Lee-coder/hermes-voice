package com.rumi.hermesvoice.core.notify

/**
 * A bounded plain-text view of one delivered final answer for the Phone's arrival notification: a one-line [summary] for the
 * collapsed card and a longer [expanded] body for the expanded one. It is a copy for display only; the complete answer stays in
 * the conversation. Its [toString] never shows the text, so it cannot reach a log through string templates.
 */
class ReplyPreview private constructor(val summary: String, val expanded: String) {
    override fun toString(): String = "ReplyPreview(summary=${summary.length} chars, expanded=${expanded.length} chars)"

    companion object {
        const val SUMMARY_MAX = 140
        /** Below Android's own 1024-character cut of notification text, so the preview ends with its own ellipsis. */
        const val EXPANDED_MAX = 1000
        private const val ELLIPSIS = "…"

        /** Null when there is nothing readable to show (the notifier then keeps its generic text). */
        fun of(text: String?): ReplyPreview? {
            if (text == null) return null
            val plain = plain(text)
            if (plain.isEmpty()) return null
            val oneLine = plain.replace(Regex("\\s*\n\\s*"), " ")
            return ReplyPreview(bounded(oneLine, SUMMARY_MAX), bounded(plain, EXPANDED_MAX))
        }

        /**
         * Readable plain text from an answer written in Markdown: markers removed, link and image text kept (the address is
         * not repeated), code kept without its fences, list items bulleted, control and direction-override characters dropped.
         * Nothing here is ever interpreted as markup by the notification, which receives a plain string.
         */
        fun plain(markdown: String): String {
            var inFence = false
            val out = ArrayList<String>()
            for (raw in markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
                val line = raw.trim()
                if (line.startsWith("```") || line.startsWith("~~~")) {
                    inFence = !inFence
                    continue
                }
                out += if (inFence) raw.trimEnd() else inline(block(line) ?: continue)
            }
            val joined = out.joinToString("\n") { it.trimEnd() }
            return clean(joined).replace(Regex("\n{3,}"), "\n\n").trim()
        }

        private fun block(line: String): String? {
            if (HORIZONTAL_RULE.matches(line)) return null
            var s = line
            s = s.replace(Regex("^#{1,6}\\s+"), "")
            s = s.replace(Regex("^(>\\s?)+"), "")
            s = s.replace(Regex("^[-*+]\\s+(\\[[ xX]]\\s+)?"), "• ")
            if (s.startsWith("|") && TABLE_RULE.matches(s)) return null
            if (s.startsWith("|")) s = s.trim('|').split('|').joinToString(" · ") { it.trim() }
            return s
        }

        private fun inline(line: String): String {
            var s = line
            s = s.replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            s = s.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            s = s.replace(Regex("\\[([^\\]]*)]\\[[^\\]]*]"), "$1")
            s = s.replace(Regex("<(https?://[^>\\s]+)>"), "$1")
            s = s.replace(Regex("`+([^`]*)`+"), "$1")
            s = s.replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
            s = s.replace(Regex("~~(.+?)~~"), "$1")
            s = s.replace(Regex("(?<![\\w*])\\*(\\S(?:[^*\\n]*\\S)?)\\*(?![\\w*])"), "$1")
            s = s.replace(Regex("(?<![\\w_])_(\\S(?:[^_\\n]*\\S)?)_(?![\\w_])"), "$1")
            return s
        }

        private fun clean(text: String): String {
            val sb = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                i += Character.charCount(cp)
                val drop = when {
                    cp == '\n'.code -> false
                    cp == '\t'.code -> { sb.append(' '); true }
                    Character.isISOControl(cp) -> true
                    cp in 0x200B..0x200F || cp in 0x202A..0x202E || cp in 0x2066..0x2069 || cp == 0xFEFF -> true
                    else -> false
                }
                if (!drop) sb.appendCodePoint(cp)
            }
            return sb.toString().replace(Regex("[ ]{2,}"), " ")
        }

        /** At most [max] UTF-16 units including the ellipsis, never splitting a surrogate pair, preferring a word boundary. */
        private fun bounded(text: String, max: Int): String {
            if (text.length <= max) return text
            var end = max - ELLIPSIS.length
            if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
            val head = text.substring(0, end)
            val space = head.lastIndexOfAny(charArrayOf(' ', '\n'))
            val cut = if (space >= end / 2) head.substring(0, space) else head
            return cut.trimEnd() + ELLIPSIS
        }

        private val HORIZONTAL_RULE = Regex("^([-*_])(\\s*\\1){2,}$")
        private val TABLE_RULE = Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$")
    }
}
