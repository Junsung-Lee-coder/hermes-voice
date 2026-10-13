package com.rumi.hermesvoice.phone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.rumi.hermesvoice.core.attachments.AttachmentRef
import com.rumi.hermesvoice.core.net.HistoryMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode

/**
 * The production [ChatPane] / [MessageBody] with Markdown messages under Robolectric: the rendering is presentation only, existing
 * messages render the same way, text stays selectable and copyable, a link opens on a short tap only, tables keep every cell,
 * HTML stays literal text and an image is never loaded. Physical finger interaction and the native toolbar are NOT_RUN here.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [PhoneMarkdownRoboTest.NoMagnifier::class])
class PhoneMarkdownRoboTest {
    @Implements(android.widget.Magnifier::class)
    class NoMagnifier {
        @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float) {}
        @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float, magnifierCenterX: Float, magnifierCenterY: Float) {}
        @Implementation fun update() {}
        @Implementation fun dismiss() {}
    }

    @get:Rule
    val rule = createComposeRule()

    private class RecordingToolbar : TextToolbar {
        var showCount = 0
        var copy: (() -> Unit)? = null
        override var status: TextToolbarStatus = TextToolbarStatus.Hidden
            private set

        override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
            showCount++
            copy = onCopyRequested
            status = TextToolbarStatus.Shown
        }

        override fun hide() {
            status = TextToolbarStatus.Hidden
        }
    }

    private val toolbar = RecordingToolbar()
    private val links = mutableListOf<String>()
    private val files = mutableListOf<AttachmentRef>()
    private val sentinel = "SENTINEL-NOT-COPIED"
    private val clipboard get() = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @Before
    fun resetClipboard() {
        clipboard.setPrimaryClip(ClipData.newPlainText("test", sentinel))
    }

    private fun show(history: List<HistoryMessage>) {
        rule.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                MaterialTheme {
                    ChatPane(
                        sessionKey = "s", history = history, hasOlder = false, draft = "", attachments = emptyList(),
                        onLoadOlder = {}, onDraft = {}, onSend = {}, onAttach = {}, onRemoveAttachment = {},
                        onOpenLink = { links += it }, onOpenAttachment = { files += it },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun message(id: Long, role: String, text: String) = HistoryMessage(id, role, text, id.toDouble())
    private fun tag(id: Long) = "message_text_$id"

    private fun shown(tag: String): String =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: error("no text on $tag")

    private fun longPress(tag: String) {
        rule.onNodeWithTag(tag).performTouchInput { down(Offset(10f, height / 2f)); advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100); up() }
        rule.waitForIdle()
    }

    private fun copyFromToolbar(): String {
        assertEquals(TextToolbarStatus.Shown, toolbar.status)
        rule.runOnUiThread { toolbar.copy!!.invoke() }
        rule.waitForIdle()
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        assertNotNull(text)
        assertNotEquals(sentinel, text)
        return text!!
    }

    @Test
    fun `M01 bold and italic show their words without the markers and plain messages are unchanged`() {
        show(listOf(message(1, "assistant", "이것은 **굵은** 글씨와 *기울임* 입니다."), message(2, "user", "그냥 평범한 2 * 3 문장")))
        assertEquals("이것은 굵은 글씨와 기울임 입니다.", shown(tag(1)))
        assertEquals("그냥 평범한 2 * 3 문장", shown(tag(2)))
    }

    @Test
    fun `M02 a heading a list a quote and a code block each render as their own readable block`() {
        val text = "# 제목\n\n- 하나\n- 둘\n\n> 인용문\n\n```\nval x = 1\n```"
        show(listOf(message(1, "assistant", text)))
        assertEquals("제목", shown(tag(1)))
        assertEquals("하나", shown("${tag(1)}_b1_i0"))
        assertEquals("둘", shown("${tag(1)}_b1_i1"))
        assertEquals("인용문", shown("${tag(1)}_b2"))
        assertEquals("val x = 1", shown("${tag(1)}_b3"))
    }

    @Test
    fun `M03 a table keeps every cell and scrolls sideways inside its own box`() {
        val text = "| 이름 | 값 | 설명 |\n|--|--|--|\n| 알파 | 1 | 첫째 |\n| 베타 | 2 | 둘째 |"
        show(listOf(message(1, "assistant", text)))
        rule.onNodeWithTag("${tag(1)}_scroll").assertExists()
        val cells = listOf("이름", "값", "설명", "알파", "1", "첫째", "베타", "2", "둘째")
        val seen = ArrayList<String>()
        for (r in 0..2) for (c in 0..2) seen += shown("${tag(1)}_r${r}c$c")
        assertEquals(cells, seen)
    }

    @Test
    fun `M03b a table row wider than its header keeps its extra cell text`() {
        val text = "| 이름 | 값 |\n|--|--|\n| 알파 | 1 | 둘째 | 셋째 |\n| 베타 | 2 |"
        show(listOf(message(1, "assistant", text)))
        rule.onNodeWithTag("${tag(1)}_scroll").assertExists()
        assertEquals("이름", shown("${tag(1)}_r0c0"))
        assertEquals("값", shown("${tag(1)}_r0c1"))
        assertEquals("알파", shown("${tag(1)}_r1c0"))
        assertEquals("1 | 둘째 | 셋째", shown("${tag(1)}_r1c1"))
        assertEquals("베타", shown("${tag(1)}_r2c0"))
        assertEquals("2", shown("${tag(1)}_r2c1"))
    }

    @Test
    fun `M04 a long press selects Markdown text and Copy gives the words that are shown`() {
        show(listOf(message(1, "assistant", "이것은 **굵은** 글씨 입니다")))
        longPress(tag(1))
        val copied = copyFromToolbar()
        assertTrue(copied, copied.isNotBlank() && "이것은 굵은 글씨 입니다".contains(copied))
        assertFalse(copied.contains("*"))
    }

    @Test
    fun `M05 a short tap on a Markdown link opens it once and a long press only selects`() {
        show(listOf(message(1, "assistant", "[문서](https://example.com/docs) 를 보세요")))
        rule.onNodeWithTag(tag(1)).performTouchInput { click(Offset(8f, height / 2f)) }
        rule.waitForIdle()
        assertEquals(listOf("https://example.com/docs"), links)
        links.clear()
        longPress(tag(1))
        assertTrue("a long press never opens a link: $links", links.isEmpty())
        copyFromToolbar()
        assertTrue(links.isEmpty())
    }

    @Test
    fun `M06 a script link is plain text and nothing opens`() {
        show(listOf(message(1, "assistant", "[클릭](javascript:alert(1)) 하지 마세요")))
        rule.onNodeWithTag(tag(1)).performTouchInput { click(Offset(8f, height / 2f)) }
        rule.waitForIdle()
        assertTrue(links.isEmpty())
        assertTrue(shown(tag(1)).contains("클릭"))
    }

    @Test
    fun `M07 HTML stays literal text and an image is shown as a label and never loaded`() {
        show(listOf(message(1, "assistant", "**x** <script>alert(1)</script> <b>not bold</b>\n\n![그림](https://example.com/p.png)")))
        val first = shown(tag(1))
        assertTrue(first, first.contains("<script>alert(1)</script>") && first.contains("<b>not bold</b>"))
        assertTrue(shown("${tag(1)}_b1").contains("그림"))
        assertTrue(links.isEmpty() && files.isEmpty())
    }

    @Test
    fun `M08 an attachment button in a Markdown message still opens its file`() {
        show(listOf(message(1, "assistant", "**만든 파일입니다**\nMEDIA:/tmp/out/report.pdf")))
        rule.onNodeWithTag("${tag(1)}_file_0").performClick()
        rule.waitForIdle()
        assertEquals("/tmp/out/report.pdf", files.single().path)
    }

    @Test
    fun `M09 an unfinished streaming message degrades to readable text`() {
        show(listOf(message(1, "assistant", "**아직 끝나지 않은 문장 [링크](https://exa")))
        val text = shown(tag(1))
        assertTrue(text, text.contains("아직 끝나지 않은 문장"))
        assertTrue(links.isEmpty())
    }

    @Test
    fun `M10 many Markdown rows still compose and scroll`() {
        val history = (1L..60L).map { message(it, if (it % 2 == 0L) "assistant" else "user", "**행 $it** 번 - `코드` 와 - 항목\n\n- 가\n- 나") }
        show(history)
        rule.onNodeWithTag("chat_list").assertExists()
    }
}
