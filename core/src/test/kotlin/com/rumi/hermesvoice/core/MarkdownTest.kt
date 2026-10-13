package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.text.Markdown
import com.rumi.hermesvoice.core.text.MarkdownLines
import com.rumi.hermesvoice.core.text.MdAlign
import com.rumi.hermesvoice.core.text.MdBlock
import com.rumi.hermesvoice.core.text.MdInline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Markdown is presentation only: a pure, bounded, safe reading of a message that never changes the stored text. */
class MarkdownTest {
    private fun blocks(text: String): List<MdBlock> = Markdown.parse(text) ?: error("expected Markdown in: $text")
    private fun runs(text: String) = Markdown.runs((blocks(text).first() as MdBlock.Paragraph).inlines)

    @Test fun `plain text without any Markdown is left to the plain path`() {
        assertNull(Markdown.parse("그냥 평범한 한국어 문장입니다. 2 * 3 = 6 and snake_case_name stays."))
        assertNull(Markdown.parse(""))
        assertNull(Markdown.parse("line one\nline two"))
    }

    @Test fun `bold italic and strikethrough become styled runs without their markers`() {
        val r = runs("a **bold** and *italic* and ~~gone~~ and ***both***")
        assertEquals("a bold and italic and gone and both", r.joinToString("") { it.text })
        assertTrue(r.first { it.text == "bold" }.bold)
        assertTrue(r.first { it.text == "italic" }.italic)
        assertTrue(r.first { it.text == "gone" }.strike)
        val both = r.first { it.text == "both" }
        assertTrue(both.bold && both.italic)
    }

    @Test fun `snake_case words and a lone asterisk are not emphasis`() {
        assertNull(Markdown.parse("call my_function_name with 3 * 4"))
    }

    @Test fun `headings carry their level and text`() {
        val b = blocks("# One\n## Two\n###### Six\ntext")
        assertEquals(listOf(1, 2, 6), b.filterIsInstance<MdBlock.Heading>().map { it.level })
        assertEquals("One", Markdown.plainText((b[0] as MdBlock.Heading).inlines))
    }

    @Test fun `bullet numbered and task lists keep order markers and nesting`() {
        val b = blocks("- a\n- b\n  - nested\n\n3. three\n4. four\n\n- [x] done\n- [ ] todo")
        val lists = b.filterIsInstance<MdBlock.ListBlock>()
        assertEquals(3, lists.size)
        assertFalse(lists[0].ordered)
        assertEquals(2, lists[0].items.size)
        assertTrue(lists[0].items[1].blocks.any { it is MdBlock.ListBlock })
        assertTrue(lists[1].ordered)
        assertEquals(3, lists[1].start)
        assertEquals(listOf<Boolean?>(true, false), lists[2].items.map { it.check })
    }

    @Test fun `block quotes can nest other blocks`() {
        val q = blocks("> quoted **bold**\n> - item").first() as MdBlock.Quote
        assertTrue(q.blocks.any { it is MdBlock.Paragraph })
        assertTrue(q.blocks.any { it is MdBlock.ListBlock })
    }

    @Test fun `inline code and fenced blocks keep their text verbatim`() {
        val code = runs("run `a *b* c` now").first { it.code }
        assertEquals("a *b* c", code.text)
        val fenced = blocks("```kotlin\nval x = **1**\n  indented\n```").first() as MdBlock.CodeBlock
        assertEquals("kotlin", fenced.language)
        assertEquals("val x = **1**\n  indented", fenced.text)
        assertTrue(fenced.closed)
    }

    @Test fun `an unclosed fence from a streaming reply degrades to an open code block`() {
        val open = blocks("intro\n```\npartial **text").filterIsInstance<MdBlock.CodeBlock>().single()
        assertFalse(open.closed)
        assertEquals("partial **text", open.text)
    }

    @Test fun `unclosed emphasis and a dangling link stay visible as plain text`() {
        assertNull(Markdown.parse("**never closed and [a link](the docs"))
        val r = runs("**closed** then **never closed and [a link](the docs")
        val shown = r.joinToString("") { it.text }
        assertEquals("closed then **never closed and [a link](the docs", shown)
        assertTrue(r.none { it.url != null })
    }

    @Test fun `only a safe http or https link is tappable and a script link is plain text`() {
        val good = runs("see [docs](https://example.com/a?b=1) now").first { it.url != null }
        assertEquals("https://example.com/a?b=1", good.url)
        assertNull(Markdown.parse("[x](javascript:alert(1)) [y](file:///etc/passwd) [z](https://user:pw@example.com/)"))
        val bad = runs("**b** [x](javascript:alert(1)) [y](file:///etc/passwd) [z](https://user:pw@example.com/)")
        assertTrue(bad.none { it.url != null })
        assertTrue(bad.joinToString("") { it.text }.contains("[x](javascript:alert(1))"))
    }

    @Test fun `an image is never loaded and appears as a labelled link at most`() {
        val b = blocks("![alt text](https://example.com/p.png) after")
        val r = Markdown.runs((b.first() as MdBlock.Paragraph).inlines)
        assertTrue(r.joinToString("") { it.text }.contains("alt text"))
        assertTrue(b.none { it is MdBlock.Heading })
    }

    @Test fun `html stays literal text and is never interpreted`() {
        val b = blocks("**x** <script>alert(1)</script> <img src=x onerror=alert(1)>")
        val shown = Markdown.blocksText(b)
        assertTrue(shown, shown.contains("<script>alert(1)</script>"))
        assertTrue(shown, shown.contains("<img src=x onerror=alert(1)>"))
    }

    @Test fun `a table keeps every cell and its alignment`() {
        val t = blocks("| 이름 | 값 | 끝 |\n|:--|:-:|--:|\n| a | **b** | c |\n| d | e | f |").first() as MdBlock.Table
        assertEquals(listOf(MdAlign.START, MdAlign.CENTER, MdAlign.END), t.aligns)
        assertEquals(2, t.rows.size)
        assertEquals("이름", Markdown.plainText(t.header[0]))
        assertEquals("b", Markdown.plainText(t.rows[0][1]))
        assertEquals("f", Markdown.plainText(t.rows[1][2]))
    }

    @Test fun `escaped markers are shown literally`() {
        val r = runs("\\*not italic\\* and \\# not heading")
        assertEquals("*not italic* and # not heading", r.joinToString("") { it.text })
    }

    @Test fun `multiline Korean paragraphs keep their line breaks`() {
        val b = blocks("**제목**\n둘째 줄\n셋째 줄")
        val shown = MarkdownLines.plainText(MarkdownLines.lines(b))
        assertEquals("제목\n둘째 줄\n셋째 줄", shown)
    }

    @Test fun `oversized or pathological input is bounded and never throws`() {
        assertNull(Markdown.parse("**a** ".repeat(Markdown.MAX_CHARS)))
        val deepQuote = ">".repeat(500) + " **x**"
        assertNotNull(Markdown.parse(deepQuote))
        val manyDelimiters = "*a ".repeat(20_000)
        Markdown.parse(manyDelimiters)
        val brackets = "[".repeat(30_000) + "](" + "x".repeat(100)
        Markdown.parse(brackets)
        val wide = "|" + " a |".repeat(500) + "\n|" + "---|".repeat(500) + "\n|" + " b |".repeat(500)
        Markdown.parse(wide)
    }

    @Test fun `parsing is a pure function and never alters the input`() {
        val text = "# T\n- **a**\n\n```\nx\n```"
        val copy = String(text.toCharArray())
        assertEquals(Markdown.blocksText(blocks(text)), Markdown.blocksText(blocks(copy)))
        assertEquals(copy, text)
    }

    @Test fun `watch lines are compact and keep all table cell text`() {
        val b = blocks("# Title\n- one\n- two\n\n1. first\n\n> quote\n\n| h1 | h2 |\n|--|--|\n| c1 | c2 |\n\n```\ncode\n```")
        val lines = MarkdownLines.lines(b)
        val shown = MarkdownLines.plainText(lines)
        assertTrue(shown, shown.contains("Title"))
        assertTrue(shown, shown.contains("• one"))
        assertTrue(shown, shown.contains("1. first"))
        assertTrue(shown, shown.contains("│ quote"))
        assertTrue(shown, shown.contains("h1 · h2"))
        assertTrue(shown, shown.contains("c1 · c2"))
        assertTrue(shown, shown.contains("code"))
        assertTrue(lines.first().all { it.bold })
        assertTrue(lines.first { l -> l.joinToString("") { it.text }.startsWith("h1") }.filter { it.text != MarkdownLines.CELL_SEPARATOR }.all { it.bold })
        assertTrue(lines.flatten().none { it.url == "" })
    }

    @Test fun `a markdown link on the watch reads as its label with no tappable url requirement`() {
        val lines = MarkdownLines.lines(blocks("open [the docs](https://example.com) please"))
        assertTrue(MarkdownLines.plainText(lines).contains("the docs"))
    }

    @Test fun `hostOf never shows credentials or a path`() {
        assertEquals("example.com", Markdown.hostOf("https://example.com/a/b?c=d#e"))
        assertEquals("example.com", Markdown.hostOf("https://user:pw@example.com/x"))
    }

    @Test fun `Link inline holds label children and Text holds literal characters`() {
        val inlines = (blocks("[a *b*](https://example.com)").first() as MdBlock.Paragraph).inlines
        val link = inlines.filterIsInstance<MdInline.Link>().single()
        assertEquals("a b (example.com)", Markdown.plainText(link.children))
    }

    // ---- R1 repair: table rows wider than their header, path-like backslashes, bounded worst cases ----

    private fun table(text: String) = blocks(text).filterIsInstance<MdBlock.Table>().single()
    private fun cellsOf(row: List<List<MdInline>>) = row.map { Markdown.plainText(it) }

    @Test fun `a row with more cells than the header keeps every cell text in the last column`() {
        val t = table("| a | b |\n|--|--|\n| 1 | 2 | 3 | 4 |\n| 5 | 6 |")
        assertEquals(2, t.header.size)
        assertEquals(listOf("1", "2 | 3 | 4"), cellsOf(t.rows[0]))
        assertEquals(listOf("5", "6"), cellsOf(t.rows[1]))
        val shown = Markdown.blocksText(listOf(t))
        for (cell in listOf("1", "2", "3", "4", "5", "6")) assertTrue("$cell in $shown", shown.contains(cell))
    }

    @Test fun `a single column table with extra cells keeps them`() {
        val t = table("| a |\n|--|\n| 1 | 2 | 3 |")
        assertEquals(listOf("1 | 2 | 3"), cellsOf(t.rows.single()))
    }

    @Test fun `a short row is padded and loses nothing`() {
        val t = table("| a | b | c |\n|--|--|--|\n| 1 |\n| 2 | 3 |")
        assertEquals(listOf("1", "", ""), cellsOf(t.rows[0]))
        assertEquals(listOf("2", "3", ""), cellsOf(t.rows[1]))
    }

    @Test fun `pipes inside code and escaped pipes keep their text`() {
        val split = table("| a | b |\n|--|--|\n| `x|y` | z |")
        val shown = Markdown.blocksText(listOf(split))
        for (part in listOf("x", "y", "z")) assertTrue("$part in $shown", shown.contains(part))
        val escaped = table("| a | b |\n|--|--|\n| `x\\|y` | z |")
        val first = escaped.rows.single()[0].single()
        assertEquals(MdInline.Code("x|y"), first)
        assertEquals("z", Markdown.plainText(escaped.rows.single()[1]))
    }

    @Test fun `a link in the overflow of a row stays a link and its text stays`() {
        val t = table("| a | b |\n|--|--|\n| 1 | [docs](https://example.com/d) | extra |")
        val last = t.rows.single()[1]
        val link = last.filterIsInstance<MdInline.Link>().single()
        assertEquals("https://example.com/d", link.url)
        assertTrue(Markdown.plainText(last).contains("extra"))
    }

    @Test fun `multiline Korean around a wide table row keeps every character on every projection`() {
        val text = "표 앞의 문단\n둘째 줄\n\n| 이름 | 값 |\n|--|--|\n| 알파 | 1 | 둘째 | 셋째 |\n\n표 뒤의 **문단**\n마지막 줄"
        val b = blocks(text)
        val plain = Markdown.blocksText(b)
        val watch = MarkdownLines.plainText(MarkdownLines.lines(b))
        for (piece in listOf("표 앞의 문단", "둘째 줄", "이름", "알파", "1", "둘째", "셋째", "표 뒤의 문단", "마지막 줄")) {
            assertTrue("$piece in accessible/copy text: $plain", plain.contains(piece))
            assertTrue("$piece in watch lines: $watch", watch.contains(piece))
        }
        assertTrue(watch, watch.contains("알파 · 1 | 둘째 | 셋째"))
    }

    @Test fun `a single backslash Windows path is left exactly as written`() {
        assertNull(Markdown.parse("C:\\Users\\owner\\file.txt"))
        val withBold = runs("**경로** C:\\Users\\owner\\a.txt").joinToString("") { it.text }
        assertEquals("경로 C:\\Users\\owner\\a.txt", withBold)
    }

    @Test fun `a backslash before ASCII punctuation is a CommonMark escape and a code span keeps it`() {
        assertEquals("a *b* C:\\temp.cache", runs("**a** \\*b\\* C:\\temp\\.cache").joinToString("") { it.text })
        val unc = runs("**x** \\\\server\\share").joinToString("") { it.text }
        assertEquals("x \\server\\share", unc)
        val code = (blocks("`\\\\server\\share` and `C:\\Users\\owner`").first() as MdBlock.Paragraph).inlines.filterIsInstance<MdInline.Code>()
        assertEquals(listOf("\\\\server\\share", "C:\\Users\\owner"), code.map { it.text })
    }

    @Test fun `an empty heading and a lone hash never throw`() {
        Markdown.parse("#")
        Markdown.parse("# \n**a**")
        Markdown.parse("##\n\n- x")
    }

    @Test fun `worst case brackets and pipes at the size limit stay bounded`() {
        val limit = Markdown.MAX_CHARS
        val cases = mapOf(
            "open brackets" to "[".repeat(limit),
            "bracket-paren pairs" to "[a](".repeat(limit / 4),
            "nested open links" to "[a](https://example.com/".repeat(limit / 24),
            "pipes only" to "|".repeat(limit),
            "pipe cells" to "|a".repeat(limit / 2),
            "wide header then rows" to ("|" + " a |".repeat(29) + "\n|" + "--|".repeat(29) + "\n" + ("|" + " b |".repeat(40) + "\n").repeat((limit - 400) / 170)),
            "escaped pipes" to "\\|".repeat(limit / 2),
            "emphasis soup" to "*_~`".repeat(limit / 4),
        )
        for ((name, text) in cases) {
            val input = text.take(limit)
            val begun = System.nanoTime()
            Markdown.parse(input)?.let { MarkdownLines.plainText(MarkdownLines.lines(it)); Markdown.blocksText(it) }
            val ms = (System.nanoTime() - begun) / 1_000_000
            println("MARKDOWN_WORST_CASE $name chars=${input.length} ms=$ms")
            assertTrue("$name took $ms ms", ms < 5_000)
        }
    }
}
