package com.rumi.hermesvoice.watch

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.graphics.Color
import com.rumi.hermesvoice.core.watchlink.LoadStatus
import com.rumi.hermesvoice.core.watchlink.ReaderHistory
import com.rumi.hermesvoice.core.watchlink.ReaderMessageRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * The production Watch reader ([ChatSurface] and its bubbles) with Markdown messages under Robolectric: a compact single-column
 * reading with the text of every list item and table cell kept, plain messages unchanged, and the stored text never altered.
 * Real wrist ergonomics and the round display are NOT_RUN.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WatchMarkdownRoboTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(vararg rows: ReaderMessageRow) {
        val history = ReaderHistory("s", rows.toList(), status = LoadStatus.READY)
        rule.setContent {
            ChatSurface("Work", history, FocusRequester(), onOlder = {}, onRetry = {}, onScrollStep = {}, talkStatus = {})
        }
        rule.waitForIdle()
    }

    private fun bubbles(tag: String): List<String> =
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().map { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                ?: node.children.mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { t -> t.text } }.joinToString("")
        }

    @Test
    fun `W01 a received Markdown message reads compactly with bullets headings and no markers`() {
        show(ReaderMessageRow(1, "assistant", "# 오늘 할 일\n\n- **회의** 준비\n- 보고서 *검토*\n\n1. 첫째\n2. 둘째", false))
        val text = bubbles("bubble_received").single()
        assertEquals("오늘 할 일\n• 회의 준비\n• 보고서 검토\n1. 첫째\n2. 둘째", text)
    }

    @Test
    fun `W02 a table is simplified to one line per row and every cell text is kept`() {
        show(ReaderMessageRow(1, "assistant", "| 이름 | 값 |\n|--|--|\n| 알파 | 1 |\n| 베타 | 2 |", false))
        assertEquals("이름 · 값\n알파 · 1\n베타 · 2", bubbles("bubble_received").single())
    }

    @Test
    fun `W02b a table row wider than its header keeps its extra cell text on one line`() {
        show(ReaderMessageRow(1, "assistant", "| 이름 | 값 |\n|--|--|\n| 알파 | 1 | 둘째 | 셋째 |\n| 베타 | 2 |", false))
        assertEquals("이름 · 값\n알파 · 1 | 둘째 | 셋째\n베타 · 2", bubbles("bubble_received").single())
    }

    @Test
    fun `W03 plain messages sent and received are unchanged and a truncated one says more on the phone`() {
        show(ReaderMessageRow(1, "user", "안녕 2 * 3 그냥 문장", false), ReaderMessageRow(2, "assistant", "일반 답변입니다", true))
        assertEquals("안녕 2 * 3 그냥 문장", bubbles("bubble_sent").single())
        assertEquals("일반 답변입니다 (more on phone)", bubbles("bubble_received").single())
    }

    @Test
    fun `W04 a truncated Markdown message ends with more on the phone and an unclosed marker stays visible`() {
        show(ReaderMessageRow(1, "assistant", "**앞부분만 도착한 굵은", true))
        val text = bubbles("bubble_received").single()
        assertTrue(text, text.contains("앞부분만 도착한 굵은") && text.endsWith("(more on phone)"))
    }

    @Test
    fun `W05 HTML and a script link are literal text and a code block keeps its lines`() {
        show(ReaderMessageRow(1, "assistant", "<b>x</b> [a](javascript:alert(1))\n\n```\nline1\nline2\n```", false))
        val text = bubbles("bubble_received").single()
        assertTrue(text, text.contains("<b>x</b>") && text.contains("line1\nline2"))
    }

    @Test
    fun `W06 the pure renderer styles bold runs and leaves plain text as it is`() {
        val styled = watchMessageText("**굵게** 보통", false, Color.Gray)
        assertEquals("굵게 보통", styled.text)
        assertTrue(styled.spanStyles.any { it.item.fontWeight != null && it.start == 0 && it.end == 2 })
        assertEquals("그냥 문장", watchMessageText("그냥 문장", false, Color.Gray).text)
        assertFalse(watchMessageText("그냥 문장", false, Color.Gray).spanStyles.isNotEmpty())
    }
}
