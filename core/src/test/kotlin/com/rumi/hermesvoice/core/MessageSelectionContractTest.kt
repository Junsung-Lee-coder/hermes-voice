package com.rumi.hermesvoice.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level contract of the Phone's selectable chat text. Evidence level: source contract only; the rendered interaction is
 * covered by the Phone Robolectric Compose test, and a physical long press is NOT_RUN.
 */
class MessageSelectionContractTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val phone = "phone/src/main/kotlin/com/rumi/hermesvoice/phone"
    private fun source(path: String) = File(root, path).takeIf { it.isFile }?.readText() ?: ""
    private val content = source("$phone/ChatContent.kt")
    private val main = source("$phone/MainActivity.kt")

    private fun between(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("found: $start", from >= 0)
        val to = text.indexOf(end, from + start.length)
        assertTrue("found end: $end", to > from)
        return text.substring(from, to)
    }

    @Test
    fun `chat message text is wrapped in the platform SelectionContainer and the stored-file buttons sit outside it`() {
        val body = between(content, "internal fun MessageBody(", "/** Selectable text whose links")
        assertTrue(body.contains("SelectionContainer {"))
        assertTrue(body.contains("DisableSelection {"))
        assertTrue("buttons are outside the selectable region", body.indexOf("SelectionContainer {") < body.indexOf("DisableSelection {"))
        assertTrue(body.indexOf("DisableSelection {") < body.indexOf("OutlinedButton("))
        assertTrue("the shown text is the stored text, not a rewritten copy", body.contains("Text(text, Modifier.testTag(tag))"))
    }

    @Test
    fun `link text is not a ClickableText and a long press never counts as a tap`() {
        assertFalse(content.contains("ClickableText"))
        assertTrue(content.contains("onLongPress = { }"))
        assertTrue(content.contains("detectTapGestures("))
    }

    @Test
    fun `both roles render through the one MessageBody call and its card has no click or long-click handler of its own`() {
        assertEquals("one renderer for user and assistant rows", 1, Regex("MessageBody\\(").findAll(main).count())
        val row = between(main, "items(history.asReversed(), key = { it.rowId }) { message ->", "if (hasOlder) item(key = \"load_older\")")
        assertTrue(row.contains("MessageBody(message.text,"))
        assertFalse(row.contains("clickable") || row.contains("combinedClickable") || row.contains("onLongClick") || row.contains("pointerInput"))
        assertFalse("the history text is shown unmodified", row.contains("message.text.") || row.contains("replace("))
    }

    @Test
    fun `no whole-message copy button stands in for selection`() {
        val all = content + main
        assertFalse(all.contains("setPrimaryClip") || all.contains("ClipData") || all.contains("setText(") || all.contains("LocalClipboardManager"))
    }

    @Test
    fun `the selection feature adds no WebView and no new permission`() {
        assertFalse(content.contains("WebView"))
        val manifest = source("phone/src/main/AndroidManifest.xml")
        assertFalse(manifest.contains("android.permission.READ_CLIPBOARD") || manifest.contains("WRITE_SECURE"))
    }
}
