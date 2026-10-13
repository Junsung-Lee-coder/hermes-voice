package com.rumi.hermesvoice.core.text

enum class MdStyle { BOLD, ITALIC, STRIKE }

enum class MdAlign { START, CENTER, END }

/** Inline Markdown. Only presentation: nothing here is executed, loaded or sent anywhere. */
sealed class MdInline {
    data class Text(val text: String) : MdInline()

    data class Code(val text: String) : MdInline()

    data class Styled(val style: MdStyle, val children: List<MdInline>) : MdInline()

    /** [url] has passed [MessageLinks.safeUrl] (absolute http/https, no credentials); it is only ever opened by an explicit tap. */
    data class Link(val url: String, val children: List<MdInline>) : MdInline()
}

class MdListItem(val check: Boolean?, val blocks: List<MdBlock>)

sealed class MdBlock {
    data class Paragraph(val inlines: List<MdInline>) : MdBlock()

    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock()

    data class Quote(val blocks: List<MdBlock>) : MdBlock()

    class ListBlock(val ordered: Boolean, val start: Int, val items: List<MdListItem>) : MdBlock()

    /** [closed] is false while the closing fence has not (yet) arrived, e.g. a reply that is still streaming. */
    data class CodeBlock(val language: String?, val text: String, val closed: Boolean) : MdBlock()

    class Table(val aligns: List<MdAlign>, val header: List<List<MdInline>>, val rows: List<List<List<MdInline>>>) : MdBlock()

    object Rule : MdBlock()
}

/** One uniformly styled stretch of text; [url] is set for a tappable link (a validated http/https address). */
data class MdRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val url: String? = null,
)

/**
 * A small, safe Markdown subset for chat messages: bold, italic, strikethrough, headings, bullet / numbered / task lists,
 * block quotes, inline code and fenced code blocks, links, images (shown as a labelled link, never loaded), horizontal rules
 * and pipe tables. It is a pure function of the text: HTML is never interpreted (it stays literal text), nothing is fetched, and
 * the stored message is never modified: [parse] returns null when the text has no Markdown in it, and the caller then shows
 * the text exactly as before. Markers that are not closed (a reply that is still arriving) stay visible as ordinary text.
 */
object Markdown {
    const val MAX_CHARS = 60_000
    const val MAX_DEPTH = 8
    private const val MAX_BLOCKS = 3_000
    private const val MAX_TABLE_COLUMNS = 30
    private const val MAX_TABLE_ROWS = 500
    private const val MAX_DELIMITERS = 600
    private const val MAX_BRACKET_SCAN = 4_000

    /** The structured form of [text], or null when it contains no Markdown (or is too large to be worth structuring). */
    fun parse(text: String): List<MdBlock>? {
        if (text.isEmpty() || text.length > MAX_CHARS) return null
        val parser = Parser()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { expandLeadingTabs(it) }
        val blocks = parser.blocks(lines, 0)
        return if (parser.structured) blocks else null
    }

    fun runs(inlines: List<MdInline>): List<MdRun> {
        val out = ArrayList<MdRun>()
        collectRuns(inlines, MdRun(""), out)
        return mergeRuns(out)
    }

    fun plainText(inlines: List<MdInline>): String = buildString {
        inlines.forEach { inline ->
            when (inline) {
                is MdInline.Text -> append(inline.text)
                is MdInline.Code -> append(inline.text)
                is MdInline.Styled -> append(plainText(inline.children))
                is MdInline.Link -> append(plainText(inline.children))
            }
        }
    }

    /** The readable text of whole blocks (what is shown, without markers), one block per line group. For tests and accessibility. */
    fun blocksText(blocks: List<MdBlock>): String = blocks.joinToString("\n") { block ->
        when (block) {
            is MdBlock.Paragraph -> plainText(block.inlines)
            is MdBlock.Heading -> plainText(block.inlines)
            is MdBlock.Quote -> blocksText(block.blocks)
            is MdBlock.ListBlock -> block.items.joinToString("\n") { blocksText(it.blocks) }
            is MdBlock.CodeBlock -> block.text
            is MdBlock.Table -> (listOf(block.header) + block.rows).joinToString("\n") { row -> row.joinToString("\t") { plainText(it) } }
            MdBlock.Rule -> ""
        }
    }

    fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').take(80)

    private fun collectRuns(inlines: List<MdInline>, base: MdRun, out: MutableList<MdRun>) {
        inlines.forEach { inline ->
            when (inline) {
                is MdInline.Text -> splitLinks(inline.text, base, out)
                is MdInline.Code -> out += base.copy(text = inline.text, code = true)
                is MdInline.Styled -> collectRuns(
                    inline.children,
                    when (inline.style) {
                        MdStyle.BOLD -> base.copy(bold = true)
                        MdStyle.ITALIC -> base.copy(italic = true)
                        MdStyle.STRIKE -> base.copy(strike = true)
                    },
                    out,
                )
                is MdInline.Link -> {
                    val inner = ArrayList<MdRun>()
                    collectRuns(inline.children, base.copy(url = inline.url), inner)
                    out += inner.map { it.copy(url = inline.url) }
                }
            }
        }
    }

    private fun splitLinks(text: String, base: MdRun, out: MutableList<MdRun>) {
        if (base.url != null) {
            out += base.copy(text = text)
            return
        }
        MessageLinks.segments(text).forEach { segment ->
            out += when (segment) {
                is MessageSegment.Link -> base.copy(text = segment.text, url = segment.url)
                else -> base.copy(text = segment.text)
            }
        }
    }

    private fun mergeRuns(runs: List<MdRun>): List<MdRun> {
        val out = ArrayList<MdRun>()
        runs.forEach { run ->
            if (run.text.isEmpty()) return@forEach
            val last = out.lastOrNull()
            if (last != null && last.copy(text = "") == run.copy(text = "")) out[out.size - 1] = last.copy(text = last.text + run.text) else out += run
        }
        return out
    }

    private fun expandLeadingTabs(line: String): String {
        var i = 0
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        val lead = line.substring(0, i)
        return if (lead.indexOf('\t') < 0) line else lead.replace("\t", "    ") + line.substring(i)
    }

    private val FENCE = Regex("""^ {0,3}(`{3,}|~{3,})[ \t]*([^`\s]*)[^`]*$""")
    private val HEADING = Regex("""^ {0,3}(#{1,6})(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$""")
    private val RULE = Regex("""^ {0,3}([-*_])(?:[ \t]*\1){2,}[ \t]*$""")
    private val QUOTE = Regex("""^ {0,3}>[ ]?(.*)$""")
    private val LIST = Regex("""^( {0,8})([-+*]|\d{1,9}[.)])([ ]{1,4})(\S.*)$|^( {0,8})([-+*]|\d{1,9}[.)])[ \t]*$""")
    private val TABLE_DELIMITER = Regex("""^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")
    private val TASK = Regex("""^\[([ xX])]\s+(.*)$""", RegexOption.DOT_MATCHES_ALL)

    private class Parser {
        var structured = false
        private var blockCount = 0
        private var inlineBudget = 400_000

        fun blocks(lines: List<String>, depth: Int): List<MdBlock> {
            val out = ArrayList<MdBlock>()
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (line.isBlank()) {
                    i++
                    continue
                }
                if (++blockCount > MAX_BLOCKS || depth > MAX_DEPTH) {
                    out += MdBlock.Paragraph(listOf(MdInline.Text(lines.subList(i, lines.size).joinToString("\n"))))
                    return out
                }
                val fence = FENCE.matchEntire(line)
                if (fence != null) {
                    structured = true
                    val marker = fence.groupValues[1]
                    val body = ArrayList<String>()
                    var j = i + 1
                    var closed = false
                    while (j < lines.size) {
                        val t = lines[j].trim()
                        if (t.length >= marker.length && t.all { it == marker[0] }) {
                            closed = true
                            j++
                            break
                        }
                        body += lines[j]
                        j++
                    }
                    val lang = fence.groupValues[2].takeIf { it.isNotEmpty() && it.length <= 20 && it.all { c -> c.isLetterOrDigit() || c in "+-_#." } }
                    out += MdBlock.CodeBlock(lang, body.joinToString("\n"), closed)
                    i = j
                    continue
                }
                val heading = HEADING.matchEntire(line)
                if (heading != null) {
                    structured = true
                    out += MdBlock.Heading(heading.groupValues[1].length, inline(heading.groupValues[2], depth))
                    i++
                    continue
                }
                if (RULE.matches(line)) {
                    structured = true
                    out += MdBlock.Rule
                    i++
                    continue
                }
                if (QUOTE.matches(line)) {
                    structured = true
                    val inner = ArrayList<String>()
                    var j = i
                    while (j < lines.size) {
                        val q = QUOTE.matchEntire(lines[j])
                        if (q != null) inner += q.groupValues[1]
                        else if (lines[j].isBlank() || startsBlock(lines, j)) break
                        else inner += lines[j]
                        j++
                    }
                    out += MdBlock.Quote(blocks(inner, depth + 1))
                    i = j
                    continue
                }
                val list = LIST.matchEntire(line)
                if (list != null) {
                    val (block, next) = listAt(lines, i, depth)
                    structured = true
                    out += block
                    i = next
                    continue
                }
                val table = tableAt(lines, i, depth)
                if (table != null) {
                    structured = true
                    out += table.first
                    i = table.second
                    continue
                }
                var j = i + 1
                while (j < lines.size && lines[j].isNotBlank() && !startsBlock(lines, j)) j++
                out += MdBlock.Paragraph(inline(lines.subList(i, j).joinToString("\n"), depth))
                i = j
            }
            return out
        }

        private fun startsBlock(lines: List<String>, at: Int): Boolean {
            val line = lines[at]
            return FENCE.matches(line) || HEADING.matches(line) || RULE.matches(line) || QUOTE.matches(line) || LIST.matches(line) ||
                (line.contains('|') && at + 1 < lines.size && isTableHeader(line, lines[at + 1]))
        }

        private fun markerOf(match: MatchResult): Triple<Int, String, Int> {
            val g = match.groupValues
            return if (g[2].isNotEmpty()) Triple(g[1].length, g[2], g[1].length + g[2].length + g[3].length)
            else Triple(g[5].length, g[6], g[5].length + g[6].length + 1)
        }

        private fun sameKind(a: String, b: String): Boolean =
            if (a[0].isDigit() || b[0].isDigit()) a[0].isDigit() && b[0].isDigit() && a.last() == b.last() else a == b

        private fun listAt(lines: List<String>, from: Int, depth: Int): Pair<MdBlock, Int> {
            val first = LIST.matchEntire(lines[from])!!
            val (indent0, marker0, _) = markerOf(first)
            val ordered = marker0[0].isDigit()
            val start = if (ordered) marker0.dropLast(1).toIntOrNull() ?: 1 else 1
            val items = ArrayList<MdListItem>()
            var i = from
            while (i < lines.size) {
                val m = LIST.matchEntire(lines[i]) ?: break
                val (indent, marker, contentIndent) = markerOf(m)
                if (!sameKind(marker0, marker) || indent > indent0 + 3) break
                val body = ArrayList<String>()
                body += (if (m.groupValues[4].isNotEmpty()) m.groupValues[4] else "")
                var j = i + 1
                while (j < lines.size) {
                    val next = lines[j]
                    if (next.isBlank()) {
                        var k = j
                        while (k < lines.size && lines[k].isBlank()) k++
                        if (k < lines.size && leadingSpaces(lines[k]) >= contentIndent) {
                            for (n in j until k) body += ""
                            j = k
                            continue
                        }
                        break
                    }
                    val lead = leadingSpaces(next)
                    val nested = LIST.matchEntire(next)
                    when {
                        lead >= contentIndent -> body += next.substring(minOf(lead, contentIndent))
                        nested != null && markerOf(nested).first >= indent + 2 -> body += next.substring(minOf(lead, indent))
                        nested != null || startsBlock(lines, j) -> break
                        else -> body += next.trim()
                    }
                    j++
                }
                var check: Boolean? = null
                val task = TASK.matchEntire(body[0])
                if (task != null) {
                    check = task.groupValues[1] != " "
                    body[0] = task.groupValues[2]
                }
                items += MdListItem(check, blocks(body, depth + 1))
                i = j
                while (i < lines.size && lines[i].isBlank()) {
                    val k = i
                    var n = k
                    while (n < lines.size && lines[n].isBlank()) n++
                    val upcoming = if (n < lines.size) LIST.matchEntire(lines[n]) else null
                    if (upcoming != null && sameKind(marker0, markerOf(upcoming).second) && markerOf(upcoming).first <= indent0 + 3) i = n else break
                }
            }
            return MdBlock.ListBlock(ordered, start, items) to i
        }

        private fun leadingSpaces(line: String): Int = line.length - line.trimStart(' ').length

        private fun isTableHeader(header: String, delimiter: String): Boolean {
            if (!header.contains('|') || !TABLE_DELIMITER.matches(delimiter) || !delimiter.contains('-')) return false
            val h = cells(header)
            return h.size >= 1 && h.size == cells(delimiter).size && h.size <= MAX_TABLE_COLUMNS && (delimiter.contains('|') || h.size > 1)
        }

        private fun cells(row: String): List<String> {
            var t = row.trim()
            if (t.startsWith("|")) t = t.substring(1)
            if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
            val out = ArrayList<String>()
            val cell = StringBuilder()
            var i = 0
            while (i < t.length) {
                val c = t[i]
                if (c == '\\' && i + 1 < t.length && t[i + 1] == '|') {
                    cell.append('|')
                    i += 2
                    continue
                }
                if (c == '|') {
                    out += cell.toString().trim()
                    cell.setLength(0)
                } else {
                    cell.append(c)
                }
                i++
            }
            out += cell.toString().trim()
            return out
        }

        private fun tableAt(lines: List<String>, at: Int, depth: Int): Pair<MdBlock, Int>? {
            if (at + 1 >= lines.size || !isTableHeader(lines[at], lines[at + 1])) return null
            val width = cells(lines[at]).size
            val aligns = cells(lines[at + 1]).map { c ->
                val left = c.startsWith(":")
                val right = c.endsWith(":")
                if (left && right) MdAlign.CENTER else if (right) MdAlign.END else MdAlign.START
            }
            val rows = ArrayList<List<List<MdInline>>>()
            var j = at + 2
            while (j < lines.size && lines[j].isNotBlank() && lines[j].contains('|') && !FENCE.matches(lines[j]) && !QUOTE.matches(lines[j])) {
                if (rows.size >= MAX_TABLE_ROWS) return null
                val raw = cells(lines[j])
                // A row wider than the header keeps every cell: the overflow is folded into the last column as literal text
                // (never dropped), a shorter row is padded with empty cells.
                val fitted = if (raw.size > width) raw.take(width - 1) + raw.drop(width - 1).joinToString(" | ") else raw
                rows += List(width) { index -> inline(fitted.getOrElse(index) { "" }, depth) }
                j++
            }
            return MdBlock.Table(aligns, cells(lines[at]).map { inline(it, depth) }, rows) to j
        }

        // ---- inline ----

        private sealed class Node {
            class Chunk(val text: String) : Node()
            class Done(val inline: MdInline) : Node()
            class Delim(val ch: Char, var count: Int, val original: Int, val canOpen: Boolean, val canClose: Boolean) : Node()
        }

        fun inline(text: String, depth: Int): List<MdInline> {
            if (text.isEmpty()) return emptyList()
            val nodes = tokenize(text, depth)
            resolve(nodes)
            return toInlines(nodes)
        }

        private fun isPunct(c: Char): Boolean {
            val t = Character.getType(c)
            return t == Character.CONNECTOR_PUNCTUATION.toInt() || t == Character.DASH_PUNCTUATION.toInt() ||
                t == Character.START_PUNCTUATION.toInt() || t == Character.END_PUNCTUATION.toInt() ||
                t == Character.INITIAL_QUOTE_PUNCTUATION.toInt() || t == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
                t == Character.OTHER_PUNCTUATION.toInt() || t == Character.MATH_SYMBOL.toInt() ||
                t == Character.CURRENCY_SYMBOL.toInt() || t == Character.MODIFIER_SYMBOL.toInt() || t == Character.OTHER_SYMBOL.toInt()
        }

        private fun tokenize(s: String, depth: Int): MutableList<Node> {
            val nodes = ArrayList<Node>()
            val buf = StringBuilder()
            var delimiters = 0
            fun flush() {
                if (buf.isNotEmpty()) {
                    nodes += Node.Chunk(buf.toString())
                    buf.setLength(0)
                }
            }
            var i = 0
            val n = s.length
            while (i < n) {
                val c = s[i]
                when {
                    c == '\\' && i + 1 < n && s[i + 1].code < 128 && isPunct(s[i + 1]) -> {
                        structured = true
                        buf.append(s[i + 1])
                        i += 2
                    }
                    c == '`' -> {
                        var k = i
                        while (k < n && s[k] == '`') k++
                        val len = k - i
                        val close = findBacktickRun(s, k, len)
                        if (close < 0) {
                            buf.append(s, i, k)
                            i = k
                        } else {
                            var code = s.substring(k, close).replace('\n', ' ')
                            if (code.length >= 2 && code.startsWith(" ") && code.endsWith(" ") && code.isNotBlank()) code = code.substring(1, code.length - 1)
                            flush()
                            nodes += Node.Done(MdInline.Code(code))
                            structured = true
                            i = close + len
                        }
                    }
                    (c == '[' || (c == '!' && i + 1 < n && s[i + 1] == '[')) && depth < MAX_DEPTH -> {
                        val image = c == '!'
                        val link = linkAt(s, if (image) i + 1 else i, image, depth)
                        if (link == null) {
                            buf.append(c)
                            i++
                        } else {
                            flush()
                            nodes += Node.Done(link.first)
                            structured = true
                            i = link.second
                        }
                    }
                    c == '<' -> {
                        val end = s.indexOf('>', i + 1)
                        val inner = if (end > i + 1 && end - i < 2050) s.substring(i + 1, end) else null
                        val safe = inner?.let { if (it.none { ch -> ch.isWhitespace() }) MessageLinks.safeUrl(it) else null }
                        if (safe != null) {
                            flush()
                            nodes += Node.Done(MdInline.Link(safe, listOf(MdInline.Text(safe))))
                            structured = true
                            i = end + 1
                        } else {
                            buf.append(c)
                            i++
                        }
                    }
                    (c == '*' || c == '_' || c == '~') && delimiters < MAX_DELIMITERS -> {
                        var k = i
                        while (k < n && s[k] == c) k++
                        val run = k - i
                        val prev = if (i == 0) ' ' else s[i - 1]
                        val next = if (k >= n) ' ' else s[k]
                        val leftFlank = !next.isWhitespace() && (!isPunct(next) || prev.isWhitespace() || isPunct(prev))
                        val rightFlank = !prev.isWhitespace() && (!isPunct(prev) || next.isWhitespace() || isPunct(next))
                        val open: Boolean
                        val close: Boolean
                        if (c == '_') {
                            open = leftFlank && (!rightFlank || isPunct(prev))
                            close = rightFlank && (!leftFlank || isPunct(next))
                        } else {
                            open = leftFlank
                            close = rightFlank
                        }
                        if (c == '~' && run != 2 || (!open && !close)) {
                            buf.append(s, i, k)
                        } else {
                            flush()
                            nodes += Node.Delim(c, run, run, open, close)
                            delimiters++
                        }
                        i = k
                    }
                    else -> {
                        buf.append(c)
                        i++
                    }
                }
            }
            flush()
            return nodes
        }

        private fun findBacktickRun(s: String, from: Int, len: Int): Int {
            var i = from
            while (i < s.length) {
                if (s[i] == '`') {
                    var k = i
                    while (k < s.length && s[k] == '`') k++
                    if (k - i == len) return i
                    i = k
                } else {
                    i++
                }
            }
            return -1
        }

        /** `[label](destination "title")` starting at the '[' at [open]; the label is parsed in its own scope. */
        private fun linkAt(s: String, open: Int, image: Boolean, depth: Int): Pair<MdInline, Int>? {
            var level = 0
            var i = open
            val limit = minOf(s.length, open + MAX_BRACKET_SCAN)
            var close = -1
            while (i < limit) {
                val c = s[i]
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '`') {
                    var k = i
                    while (k < s.length && s[k] == '`') k++
                    val end = findBacktickRun(s, k, k - i)
                    i = if (end < 0) k else end + (k - i)
                    continue
                }
                if (c == '[') level++
                if (c == ']') {
                    level--
                    if (level == 0) {
                        close = i
                        break
                    }
                }
                i++
            }
            if (close < 0 || close + 1 >= s.length || s[close + 1] != '(') return null
            val dest = destinationAt(s, close + 2) ?: return null
            val label = s.substring(open + 1, close)
            val url = MessageLinks.safeUrl(dest.first)
            val children = inline(label, depth + 1)
            val host = url?.let { hostOf(it) }
            val inline: MdInline = if (image) {
                val alt = plainText(children).ifBlank { "image" }
                if (url == null) MdInline.Text("[image: $alt]")
                else MdInline.Link(url, listOf(MdInline.Text("[image: $alt] ($host)")))
            } else {
                if (url == null) return null
                val shown = if (children.isEmpty()) listOf<MdInline>(MdInline.Text(url)) else children
                val visible = plainText(shown)
                MdInline.Link(url, if (host != null && host.isNotEmpty() && !visible.contains(host, ignoreCase = true)) shown + MdInline.Text(" ($host)") else shown)
            }
            return inline to dest.second
        }

        private fun destinationAt(s: String, from: Int): Pair<String, Int>? {
            var i = from
            while (i < s.length && s[i] == ' ') i++
            val dest: String
            if (i < s.length && s[i] == '<') {
                val end = s.indexOf('>', i + 1)
                if (end < 0 || s.substring(i + 1, end).any { it == '\n' || it == '<' }) return null
                dest = s.substring(i + 1, end)
                i = end + 1
            } else {
                val start = i
                var parens = 0
                while (i < s.length) {
                    val c = s[i]
                    if (c.isWhitespace() || c.isISOControl()) break
                    if (c == '(') parens++
                    if (c == ')') {
                        if (parens == 0) break
                        parens--
                    }
                    i++
                }
                dest = s.substring(start, i)
            }
            while (i < s.length && s[i] == ' ') i++
            if (i < s.length && (s[i] == '"' || s[i] == '\'')) {
                val quote = s[i]
                val end = s.indexOf(quote, i + 1)
                if (end < 0) return null
                i = end + 1
                while (i < s.length && s[i] == ' ') i++
            }
            if (i >= s.length || s[i] != ')') return null
            return dest to i + 1
        }

        private fun resolve(nodes: MutableList<Node>) {
            var ci = 0
            while (ci < nodes.size) {
                val closer = nodes[ci] as? Node.Delim
                if (closer == null || !closer.canClose || closer.count == 0) {
                    ci++
                    continue
                }
                if (--inlineBudget < 0) return
                var oi = ci - 1
                var found = -1
                while (oi >= 0) {
                    val o = nodes[oi]
                    if (o is Node.Delim && o.ch == closer.ch && o.canOpen && o.count > 0) {
                        val odd = (o.canClose || closer.canOpen) && (o.original + closer.original) % 3 == 0 && !(o.original % 3 == 0 && closer.original % 3 == 0)
                        val usable = if (closer.ch == '~') o.count >= 2 && closer.count >= 2 else true
                        if (!odd && usable) {
                            found = oi
                            break
                        }
                    }
                    oi--
                }
                if (found < 0) {
                    ci++
                    continue
                }
                val opener = nodes[found] as Node.Delim
                val use = if (closer.ch == '~') 2 else if (opener.count >= 2 && closer.count >= 2) 2 else 1
                val inner = ArrayList<Node>(nodes.subList(found + 1, ci))
                val style = if (closer.ch == '~') MdStyle.STRIKE else if (use == 2) MdStyle.BOLD else MdStyle.ITALIC
                val children = toInlines(inner)
                opener.count -= use
                closer.count -= use
                structured = true
                for (k in ci - 1 downTo found + 1) nodes.removeAt(k)
                nodes.add(found + 1, Node.Done(MdInline.Styled(style, children)))
                ci = found + 2
            }
        }

        private fun toInlines(nodes: List<Node>): List<MdInline> {
            val out = ArrayList<MdInline>()
            val text = StringBuilder()
            fun flush() {
                if (text.isNotEmpty()) {
                    out += MdInline.Text(text.toString())
                    text.setLength(0)
                }
            }
            nodes.forEach { node ->
                when (node) {
                    is Node.Chunk -> text.append(node.text)
                    is Node.Delim -> repeat(node.count) { text.append(node.ch) }
                    is Node.Done -> {
                        flush()
                        out += node.inline
                    }
                }
            }
            flush()
            return out
        }
    }
}
