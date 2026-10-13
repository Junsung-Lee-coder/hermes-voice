package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.notify.ReplyPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bounded plain-text view of a delivered answer that the Phone's notification shows (the answer itself stays whole in the chat). */
class ReplyPreviewTest {
    @Test
    fun `Markdown becomes readable plain text`() {
        assertEquals("Title\n\nSome bold and it and code text.\n• one\n• two\n\ndocs alt",
            ReplyPreview.plain("# Title\n\nSome **bold** and _it_ and `code` text.\n- one\n- two\n\n[docs](https://a.example/x) ![alt](http://i.example/x.png)"))
        assertEquals("Run:\nls -la\nDone", ReplyPreview.plain("Run:\n```sh\nls -la\n```\nDone"))
        assertEquals("a · b\n1 · 2", ReplyPreview.plain("| a | b |\n|---|---|\n| 1 | 2 |"))
        assertEquals("quoted line", ReplyPreview.plain("> quoted line"))
        assertEquals("one\ntwo", ReplyPreview.plain("one\n\n---\ntwo".replace("\n\n---\n", "\n---\n")))
        assertEquals("2 * 3 * 4 = 24 and snake_case_name", ReplyPreview.plain("2 * 3 * 4 = 24 and snake_case_name"))
    }

    @Test
    fun `HTML is left as inert literal text and control and direction-override characters are dropped`() {
        assertEquals("<b>x</b> <script>alert(1)</script>", ReplyPreview.plain("<b>x</b> <script>alert(1)</script>"))
        assertEquals("abcd e", ReplyPreview.plain("a\u0000b‮c​d\te"))
    }

    @Test
    fun `Korean multiline and linked answers keep their wording`() {
        val preview = ReplyPreview.of("안녕하세요! **오늘** 일정은 `3건`입니다.")
        assertNotNull(preview)
        assertEquals("안녕하세요! 오늘 일정은 3건입니다.", preview!!.summary)
        assertEquals(preview.summary, preview.expanded)
        val multi = ReplyPreview.of("첫째 줄\n둘째 줄\n\n셋째 [링크](https://a.example)")!!
        assertEquals("첫째 줄 둘째 줄 셋째 링크", multi.summary)
        assertEquals("첫째 줄\n둘째 줄\n\n셋째 링크", multi.expanded)
        assertFalse(multi.summary.contains('\n'))
    }

    @Test
    fun `nothing readable gives no preview`() {
        for (text in listOf(null, "", "   \n\t ", "---", "```\n```", "​‮")) assertNull("[$text]", ReplyPreview.of(text))
    }

    @Test
    fun `long answers are bounded and never cut a character in half`() {
        val long = "가".repeat(3000)
        val a = ReplyPreview.of(long)!!
        assertEquals(ReplyPreview.SUMMARY_MAX, a.summary.length)
        assertEquals(ReplyPreview.EXPANDED_MAX, a.expanded.length)
        assertTrue("Android cuts notification text at 1024 characters; the preview ends with its own ellipsis first", ReplyPreview.EXPANDED_MAX < 1024)
        assertTrue(a.summary.endsWith("…") && a.expanded.endsWith("…"))
        val emoji = ReplyPreview.of("😀".repeat(2000))!!
        assertTrue(emoji.summary.length <= ReplyPreview.SUMMARY_MAX && emoji.expanded.length <= ReplyPreview.EXPANDED_MAX)
        assertEquals("😀".repeat(69), emoji.summary.dropLast(1))
        assertEquals(emoji.summary, String(emoji.summary.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        val words = ReplyPreview.of("word ".repeat(400))!!
        assertTrue(words.summary.endsWith("word…") && words.summary.length <= ReplyPreview.SUMMARY_MAX)
        val short = ReplyPreview.of("Done.")!!
        assertEquals("Done.", short.summary)
        assertEquals("Done.", short.expanded)
    }

    @Test
    fun `a preview never shows its text through toString`() {
        val preview = ReplyPreview.of("the private answer")!!
        assertFalse(preview.toString().contains("private"))
    }
}
