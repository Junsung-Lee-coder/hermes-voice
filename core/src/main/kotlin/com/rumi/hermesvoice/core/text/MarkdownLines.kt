package com.rumi.hermesvoice.core.text

/**
 * The compact, single-column reading of a Markdown message for a small round screen: every block becomes lines of styled runs,
 * lists get a bullet or number and a hanging indent, quotes a bar, code stays monospace, and a table is simplified to one line per
 * row with its cells joined by " · " (all cell text kept; the header row is bold). Presentation only, like [Markdown].
 */
object MarkdownLines {
    const val RULE = "──────"
    const val QUOTE_BAR = "│ "
    const val CELL_SEPARATOR = " · "

    fun lines(blocks: List<MdBlock>): List<List<MdRun>> {
        val out = ArrayList<List<MdRun>>()
        blocks.forEach { out += blockLines(it) }
        return out
    }

    /** The lines as the plain text that is shown (one '\n' between lines). */
    fun plainText(lines: List<List<MdRun>>): String = lines.joinToString("\n") { line -> line.joinToString("") { it.text } }

    private fun blockLines(block: MdBlock): List<List<MdRun>> = when (block) {
        is MdBlock.Paragraph -> split(Markdown.runs(block.inlines))
        is MdBlock.Heading -> split(Markdown.runs(block.inlines).map { it.copy(bold = true) })
        is MdBlock.Quote -> lines(block.blocks).map { listOf(MdRun(QUOTE_BAR, italic = true)) + it }
        is MdBlock.ListBlock -> block.items.flatMapIndexed { index, item ->
            val marker = when {
                item.check == true -> "☑ "
                item.check == false -> "☐ "
                block.ordered -> "${block.start + index}. "
                else -> "• "
            }
            val inner = lines(item.blocks).ifEmpty { listOf(emptyList()) }
            inner.mapIndexed { n, line -> listOf(MdRun(if (n == 0) marker else " ".repeat(marker.length))) + line }
        }
        is MdBlock.CodeBlock -> block.text.split('\n').map { listOf(MdRun(it, code = true)) }
        is MdBlock.Table -> listOf(row(block.header, bold = true)) + block.rows.map { row(it, bold = false) }
        MdBlock.Rule -> listOf(listOf(MdRun(RULE)))
    }

    private fun row(cells: List<List<MdInline>>, bold: Boolean): List<MdRun> {
        val out = ArrayList<MdRun>()
        cells.forEachIndexed { index, cell ->
            if (index > 0) out += MdRun(CELL_SEPARATOR)
            out += Markdown.runs(cell).map { if (bold) it.copy(bold = true) else it }
        }
        return out
    }

    private fun split(runs: List<MdRun>): List<List<MdRun>> {
        val lines = ArrayList<List<MdRun>>()
        var current = ArrayList<MdRun>()
        runs.forEach { run ->
            val parts = run.text.split('\n')
            parts.forEachIndexed { index, part ->
                if (index > 0) {
                    lines += current
                    current = ArrayList()
                }
                if (part.isNotEmpty()) current += run.copy(text = part)
            }
        }
        lines += current
        return lines
    }
}
