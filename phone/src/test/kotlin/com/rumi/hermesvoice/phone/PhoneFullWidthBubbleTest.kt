package com.rumi.hermesvoice.phone

import androidx.compose.ui.test.click
import androidx.compose.ui.unit.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import com.rumi.hermesvoice.core.activity.ActivityBubble
import com.rumi.hermesvoice.core.chat.DispatchedSend
import com.rumi.hermesvoice.core.chat.PendingSend
import com.rumi.hermesvoice.core.net.HistoryMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). Every chat bubble (the user's, the assistant's, a just-sent one and the receiving bubble) uses
 * the same full content width and alignment; the sender colors, texts and tap actions are unchanged. The production ChatPane is
 * composed directly with synthetic rows; no device.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1280dp", shadows = [PhoneFullWidthBubbleTest.NoMagnifier::class])
class PhoneFullWidthBubbleTest {
    /** Robolectric has no display surface to copy pixels from, so the platform magnifier of a selection is inert in this harness. */
    @Implements(android.widget.Magnifier::class)
    class NoMagnifier {
        @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float) {}
        @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float, magnifierCenterX: Float, magnifierCenterY: Float) {}
        @Implementation fun update() {}
        @Implementation fun dismiss() {}
    }

    val rule = createComposeRule()
    @get:Rule val cleanupThenCompose: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(ComposeDispatcherCleanup()).around(rule)

    @Before fun isolateComposeDispatcher() = ComposeDispatcherIsolation.reset()

    private val history = mutableStateOf(emptyList<HistoryMessage>())
    private val pendings = mutableStateOf(emptyList<PendingSend>())
    private val activity = mutableStateOf(emptyList<ActivityBubble>())
    private var scheme: ColorScheme? = null
    private val links = mutableListOf<String>()
    private val files = mutableListOf<Any>()

    private fun message(id: Long, role: String, text: String) = HistoryMessage(id, role, text, id.toDouble())
    private fun pending(id: Long, text: String) = PendingSend(DispatchedSend(id, "s", text, emptyList()), 10)

    private fun show() {
        rule.setContent {
            MaterialTheme {
                scheme = MaterialTheme.colorScheme
                ChatPane(sessionKey = "s", history = history.value, hasOlder = false, draft = "", attachments = emptyList(),
                    onLoadOlder = {}, onDraft = {}, onSend = {}, onAttach = {}, onRemoveAttachment = {},
                    onOpenLink = { links += it }, onOpenAttachment = { files += it },
                    pendingSends = pendings.value, activity = activity.value)
            }
        }
        rule.waitForIdle()
    }

    private fun mixed() {
        history.value = listOf(
            message(10, "assistant", "Short assistant reply"),
            message(11, "user", "Short user note"),
            message(12, "assistant", "A longer assistant paragraph that wraps over several lines on a narrow phone screen so that its natural width is the whole row. ".repeat(3)),
            message(13, "user", "line one\nline two\nline three"),
        )
        pendings.value = listOf(pending(5, "just sent"))
        activity.value = listOf(ActivityBubble("k1", "s", "🔎 Searching files", false))
        show()
    }

    private val tags = listOf("message_10", "message_11", "message_12", "message_13", "pending_send_5", "activity_k1")

    private fun b(tag: String): DpRect = rule.onNodeWithTag(tag).getBoundsInRoot()

    private fun texts(tag: String): List<String> {
        fun walk(node: SemanticsNode, out: MutableList<String>) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.children.forEach { walk(it, out) }
        }
        return mutableListOf<String>().also { walk(rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode(), it) }
    }

    private fun containerColor(tag: String): Long {
        val node = rule.onNodeWithTag(tag).fetchSemanticsNode()
        val background = node.layoutInfo.getModifierInfo().map { it.modifier }.first { it.javaClass.name.contains("Background") }
        val field = background.javaClass.getDeclaredField("color").also { it.isAccessible = true }
        return field.getLong(background)
    }

    private fun sameFullWidth() {
        val root = rule.onRoot().getBoundsInRoot()
        val first = b(tags.first())
        val margin = first.left.value
        assertTrue("a safe inset on the left: $margin", margin in 8f..24f)
        for (tag in tags) {
            val bounds = b(tag)
            assertEquals("$tag left", first.left.value, bounds.left.value, 0.6f)
            assertEquals("$tag right", first.right.value, bounds.right.value, 0.6f)
        }
        assertEquals("the right inset mirrors the left", margin, root.right.value - first.right.value, 0.6f)
        assertEquals("full content width", root.width.value - 2 * margin, first.width.value, 0.6f)
    }

    @Test fun B01_userAssistantSentAndReceivingBubblesShareOneFullWidth() {
        mixed()
        sameFullWidth()
    }

    @Test @Config(qualifiers = "w320dp-h1280dp") fun B02_theSameHoldsOnANarrowPhone() {
        mixed()
        sameFullWidth()
    }

    @Test fun B03_senderColorsAreUnchanged() {
        mixed()
        val c = scheme!!
        val mine = c.primaryContainer.value.toLong()
        val theirs = c.surfaceContainerHigh.value.toLong()
        assertEquals("user row", mine, containerColor("message_11"))
        assertEquals("user row 2", mine, containerColor("message_13"))
        assertEquals("sent bubble", mine, containerColor("pending_send_5"))
        assertEquals("assistant row", theirs, containerColor("message_10"))
        assertEquals("assistant row 2", theirs, containerColor("message_12"))
        assertEquals("receiving bubble", theirs, containerColor("activity_k1"))
    }

    @Test fun B04_noSenderLabelOrMetadataRowIsAdded() {
        mixed()
        assertEquals(listOf("Short assistant reply"), texts("message_10"))
        assertEquals(listOf("Short user note"), texts("message_11"))
        assertEquals(listOf("line one\nline two\nline three"), texts("message_13"))
        assertEquals(listOf("🔎 Searching files"), texts("activity_k1"))
        assertEquals(listOf("just sent", "Sending…"), texts("pending_send_5"))
    }

    @Test fun B05_wideCodeKeepsTheBubbleWidthAndStaysInsideTheScreen() {
        history.value = listOf(
            message(1, "user", "ask"),
            message(2, "assistant", "```\n" + "0123456789".repeat(40) + "\n```"),
            message(3, "assistant", "| a | b |\n|---|---|\n| " + "x".repeat(120) + " | y |"),
            message(4, "assistant", "plain"),
        )
        show()
        val root = rule.onRoot().getBoundsInRoot()
        val reference = b("message_4")
        for (tag in listOf("message_1", "message_2", "message_3")) {
            val bounds = b(tag)
            assertEquals("$tag left", reference.left.value, bounds.left.value, 0.6f)
            assertEquals("$tag right", reference.right.value, bounds.right.value, 0.6f)
            assertTrue("$tag does not overflow the screen", bounds.right.value <= root.right.value + 0.5f && bounds.left.value >= -0.5f)
        }
    }

    @Test fun B06_aLinkInAFullWidthBubbleOpensOnATapAndNotOnALongPress() {
        history.value = listOf(message(1, "user", "https://example.com/docs/guide 를 참고하세요."))
        show()
        rule.onNodeWithTag("message_text_1").performTouchInput { click(Offset(10f, height / 2f)) }
        rule.waitForIdle()
        assertEquals(listOf("https://example.com/docs/guide"), links)
        links.clear()
        rule.onNodeWithTag("message_text_1").performTouchInput {
            down(Offset(10f, height / 2f)); advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100); up()
        }
        rule.waitForIdle()
        assertTrue("a long press selects instead of opening the link", links.isEmpty())
    }

    @Test fun B06b_anAttachmentButtonInAFullWidthBubbleStillOpensTheFile() {
        history.value = listOf(message(1, "assistant", "만든 파일입니다\nMEDIA:/tmp/out/report.pdf"))
        show()
        rule.onNodeWithTag("message_text_1_file_0").performClick()
        rule.waitForIdle()
        assertEquals(1, files.size)
    }

    @Test fun B07_theBubbleKeepsItsInnerPaddingAroundTheText() {
        history.value = listOf(message(1, "user", "padded"), message(2, "assistant", "padded too"))
        show()
        for ((id, tag) in listOf(1 to "message_1", 2 to "message_2")) {
            val card = b(tag)
            val text = b("message_text_$id")
            assertTrue("inner left padding: ${text.left.value - card.left.value}", text.left.value - card.left.value >= 9.5f)
            assertTrue("inner right padding", card.right.value - text.right.value >= 9.5f)
        }
    }
}
