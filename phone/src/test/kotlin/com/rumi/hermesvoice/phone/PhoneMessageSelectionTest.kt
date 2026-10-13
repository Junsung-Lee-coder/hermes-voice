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
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.performScrollToIndex
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
 * SCRATCH HARNESS (never packaged). The production [ChatPane] / [MessageBody] renderer (the exact composables the Phone chat shows,
 * user and assistant rows) driven with injected touch events under Robolectric, with the platform's real [ClipboardManager] and a
 * recording [TextToolbar] standing in for the floating Copy menu. It proves the Compose selection wiring (long press starts a
 * selection, the toolbar offers Copy, Copy puts the canonical text on the clipboard, a link opens on a tap and never on a long
 * press, file buttons and list scrolling still work). It does NOT prove a physical finger, the drag handles' pixels or the native
 * toolbar's look: those are NOT_RUN.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [PhoneMessageSelectionTest.NoMagnifier::class])
class PhoneMessageSelectionTest {
    /** Robolectric has no display surface to copy pixels from, so the platform magnifier (shown while a handle drags) is inert in this harness. */
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
        var selectAll: (() -> Unit)? = null
        override var status: TextToolbarStatus = TextToolbarStatus.Hidden
            private set

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) {
            showCount++
            copy = onCopyRequested
            selectAll = onSelectAllRequested
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
    private fun clipText(): String? = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

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
    private fun textTag(id: Long) = "message_text_$id"

    private fun longPress(tag: String) {
        rule.onNodeWithTag(tag).performTouchInput { longClick() }
        rule.waitForIdle()
    }

    /** Press at the first character, hold past the long-press time, then drag to the last one and release: a selection of the whole text. */
    private fun dragSelectAll(tag: String) {
        rule.onNodeWithTag(tag).performTouchInput {
            down(Offset(2f, 2f))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(Offset(width / 2f, height / 2f), 50)
            moveTo(Offset(width - 1f, height - 1f), 50)
            up()
        }
        rule.waitForIdle()
    }

    private fun copyFromToolbar(): String {
        assertEquals("the floating menu is shown", TextToolbarStatus.Shown, toolbar.status)
        val copy = toolbar.copy
        assertNotNull("the menu offers Copy", copy)
        rule.runOnUiThread { copy!!.invoke() }
        rule.waitForIdle()
        val text = clipText()
        assertNotNull(text)
        assertNotEquals("Copy changed the clipboard", sentinel, text)
        return text!!
    }

    private val korean = "안녕하세요, 오늘 회의는 오후 세 시에 시작합니다.\n준비물: 노트북, 충전기, 그리고 지난주 보고서.\n질문이 있으면 편하게 말씀해 주세요."
    private val long = (1..6).joinToString(" ") { "문장 $it: The quick brown fox jumps over the lazy dog." }
    private val taller = (1..60).joinToString(" ") { "문장 $it: The quick brown fox jumps over the lazy dog." }

    @Test
    fun `S01 a long press on an assistant message selects text and the menu offers Copy, which copies text from that message only`() {
        show(listOf(message(1, "user", "첫 번째 질문입니다"), message(2, "assistant", korean)))
        longPress(textTag(2))
        val copied = copyFromToolbar()
        assertTrue("copied text '$copied' is part of the displayed message", korean.contains(copied))
        assertTrue(copied.isNotBlank())
        println("SELECT_ALL_OFFERED_BY_THIS_COMPOSE=" + (toolbar.selectAll != null))
        assertTrue("a long press never opens anything", links.isEmpty() && files.isEmpty())
    }

    @Test
    fun `S02 a long press on a user message selects too`() {
        show(listOf(message(1, "user", korean), message(2, "assistant", "답변")))
        longPress(textTag(1))
        val copied = copyFromToolbar()
        assertTrue(korean.contains(copied) && copied.isNotBlank())
    }

    @Test
    fun `S03 press-hold-drag selects a range and Copy gives the canonical multiline Korean text exactly`() {
        show(listOf(message(1, "assistant", korean)))
        dragSelectAll(textTag(1))
        assertEquals(korean, copyFromToolbar())
    }

    @Test
    fun `S04 a multi-line answer is copied whole and unaltered`() {
        show(listOf(message(1, "assistant", long)))
        dragSelectAll(textTag(1))
        assertEquals(long, copyFromToolbar())
    }

    @Test
    fun `S04b an answer taller than the screen copies a contiguous, unaltered part of its text (the gesture does not scroll the list)`() {
        show(listOf(message(1, "assistant", taller)))
        dragSelectAll(textTag(1))
        val copied = copyFromToolbar()
        assertTrue("a contiguous part of the stored text: '${copied.take(60)}'", copied.isNotBlank() && taller.contains(copied))
    }

    @Test
    fun `S05 a message with a link and a stored file copies its displayed words, with no workflow label and no attachment-button text`() {
        val text = "https://example.com/docs/guide 를 참고하세요.\n결과 파일: MEDIA:/tmp/out/report.pdf"
        show(listOf(message(1, "assistant", text)))
        dragSelectAll(textTag(1))
        val copied = copyFromToolbar()
        assertEquals(text, copied)
        assertFalse("the file button label is not part of the copied text", copied.contains("📎") || copied.contains("View attachment"))
        assertTrue("nothing was opened", links.isEmpty() && files.isEmpty())
    }

    @Test
    fun `S06 a short tap on a link opens it once, a long press on the same link only selects`() {
        val text = "https://example.com/docs/guide 를 참고하세요."
        show(listOf(message(1, "user", text)))
        rule.onNodeWithTag(textTag(1)).performTouchInput { click(Offset(10f, height / 2f)) }
        rule.waitForIdle()
        assertEquals(listOf("https://example.com/docs/guide"), links)
        assertEquals("a tap is not a selection", TextToolbarStatus.Hidden, toolbar.status)

        links.clear()
        rule.onNodeWithTag(textTag(1)).performTouchInput { down(Offset(10f, height / 2f)); advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100); up() }
        rule.waitForIdle()
        assertTrue("a long press never opens a link: $links", links.isEmpty())
        val copied = copyFromToolbar()
        assertTrue(text.contains(copied) && copied.isNotBlank())
        assertTrue(links.isEmpty())
    }

    @Test
    fun `S07 a stored-file button still opens its file with a tap, and a long press on it selects nothing`() {
        show(listOf(message(1, "assistant", "만든 파일입니다\nMEDIA:/tmp/out/report.pdf")))
        rule.onNodeWithTag("${textTag(1)}_file_0").performClick()
        rule.waitForIdle()
        assertEquals(1, files.size)
        assertEquals("/tmp/out/report.pdf", files.single().path)

        files.clear()
        toolbar.hide()
        val before = toolbar.showCount
        rule.onNodeWithTag("${textTag(1)}_file_0").performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals("no selection menu from a button", before, toolbar.showCount)
    }

    @Test
    fun `S08 the list still scrolls when a swipe starts on message text, and it starts no selection`() {
        val history = (1L..40L).map { message(it, if (it % 2 == 0L) "assistant" else "user", "메시지 $it 번 — line of text for row $it") }
        show(history)
        val tag = textTag(36)
        val before = rule.onNodeWithTag(tag).getBoundsInRoot().top
        rule.onNodeWithTag(tag).performTouchInput { swipeDown(startY = centerY, endY = centerY + 60f, durationMillis = 200) }
        rule.waitForIdle()
        val after = rule.onNodeWithTag(tag).getBoundsInRoot().top
        assertTrue("the list scrolled ($before -> $after)", after > before)
        assertEquals("a swipe is not a selection", TextToolbarStatus.Hidden, toolbar.status)
        assertEquals(0, toolbar.showCount)
    }

    @Test
    fun `S09 selecting in one row, scrolling it out of the list and selecting in another row copies only the second row`() {
        val history = (1L..40L).map { message(it, "assistant", "row${it}end") }
        show(history)
        longPress(textTag(40))
        assertEquals(TextToolbarStatus.Shown, toolbar.status)
        assertEquals("row40end", copyFromToolbar())
        clipboard.setPrimaryClip(ClipData.newPlainText("test", sentinel))
        longPress(textTag(40))
        rule.onNodeWithTag("chat_list").performScrollToIndex(35)
        rule.waitForIdle()
        longPress(textTag(5))
        assertEquals("row5end", copyFromToolbar())
    }
}
