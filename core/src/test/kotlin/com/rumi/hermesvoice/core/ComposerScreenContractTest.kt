package com.rumi.hermesvoice.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level contract of the Phone composer, message renderer and attachment viewer (the Compose UI is not run on a device
 * or emulator here). Evidence level: source contract, below a rendered-UI or interaction test.
 */
class ComposerScreenContractTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val phone = "phone/src/main/kotlin/com/rumi/hermesvoice/phone"
    private fun source(path: String) = File(root, path).takeIf { it.isFile }?.readText() ?: ""
    private val main = source("$phone/MainActivity.kt")
    private val model = source("$phone/PhoneViewModel.kt")
    private val content = source("$phone/ChatContent.kt")
    private val viewer = source("$phone/AttachmentViewer.kt")
    private val files = source("$phone/ViewedFiles.kt")

    private fun block(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("found: $start", from >= 0)
        val to = text.indexOf(end, from + start.length)
        assertTrue("found end: $end", to > from)
        return text.substring(from, to)
    }

    private val composer = block(main, "        failedSends.forEach { failed ->", "@Composable\ninternal fun SettingsTab")

    @Test
    fun `attach and send are icon-only 48dp controls with descriptions, and send is never disabled by a request in flight`() {
        assertTrue(composer.contains("IconButton(onClick = onAttach, modifier = Modifier.size(48.dp).testTag(\"attach\"))"))
        assertTrue(composer.contains("Icon(ComposerIcons.Plus, contentDescription = \"Attach file\")"))
        assertTrue(composer.contains("FilledIconButton(onClick = { toLatest(); onSend() }, enabled = draft.isNotBlank() || attachments.isNotEmpty(),"))
        assertTrue(composer.contains("Modifier.size(48.dp).testTag(\"send\")"))
        assertTrue(composer.contains("Icon(ComposerIcons.ArrowUp, contentDescription = \"Send message\")"))
        assertFalse("no text buttons remain", composer.contains("Text(\"Attach\")") || composer.contains("Text(\"Send\")"))
        assertFalse("a request in flight does not disable the composer", composer.contains("sending") || composer.contains("sendsInFlight"))
    }

    @Test
    fun `the field takes the remaining width and grows up to six lines`() {
        assertTrue(composer.contains("OutlinedTextField(draft, onDraft, Modifier.weight(1f).testTag(\"composer\")"))
        assertTrue(composer.contains("minLines = 1, maxLines = 6"))
        assertTrue(composer.contains("verticalAlignment = Alignment.Bottom"))
    }

    @Test
    fun `a failed send is visible with retry and dismiss, and nothing restores it over the draft`() {
        assertTrue(composer.contains("testTag(\"failed_send_\${failed.send.id}\")"))
        assertTrue(composer.contains("onRetry(failed.send.id)"))
        assertTrue(composer.contains("onDismissFailed(failed.send.id)"))
        val dispatch = block(model, "    private fun dispatch(", "    // ── viewing attachments")
        assertFalse("late callbacks never touch the draft or attachments", dispatch.contains("draft =") || dispatch.contains("attachments ="))
        assertTrue(model.contains("_state.compareAndSet(current, cleared)"))
    }

    @Test
    fun `messages render through the link renderer and the file buttons, and links open only on a tap through a plain view intent`() {
        assertTrue(main.contains("MessageBody(message.text, \"message_text_\${message.rowId}\", onOpenLink, onOpenAttachment"))
        assertTrue("selectable text whose links open on a short tap", content.contains("SelectionContainer {") && content.contains("detectTapGestures("))
        assertTrue(content.contains("MessageLinks.safeUrl(url)"))
        assertTrue(content.contains("Intent(Intent.ACTION_VIEW, Uri.parse(safe)).addCategory(Intent.CATEGORY_BROWSABLE)"))
        assertTrue(content.contains("ActivityNotFoundException"))
        assertTrue("an accessibility action per link", content.contains("CustomAccessibilityAction(\"Open link to"))
        assertFalse("no web view, no script bridge, no credentials", Regex("WebView|addJavascriptInterface|putExtra|Authorization|Bearer").containsMatchIn(content))
        assertTrue(main.contains("onOpenLink = { url -> LinkOpener.open(appContext, url)?.let(model::reportStatus) }"))
    }

    @Test
    fun `the viewer is a dismissible full-screen dialog that decodes pictures at a bounded size and renders no web content`() {
        assertTrue(viewer.contains("Dialog(onDismissRequest = model::closeViewer, properties = DialogProperties(usePlatformDefaultWidth = false))"))
        assertTrue(viewer.contains("testTag(\"viewer_close\")"))
        assertTrue(viewer.contains("inJustDecodeBounds = true"))
        assertTrue(viewer.contains("inSampleSize = sampleSize("))
        assertTrue(viewer.contains("PdfRenderer("))
        assertTrue(viewer.contains("MAX_PDF_PAGES"))
        assertTrue(viewer.contains("testTag(\"viewer_open_with\")") && viewer.contains("testTag(\"viewer_save\")"))
        assertFalse(Regex("WebView|loadUrl|loadData|addJavascriptInterface|Authorization").containsMatchIn(viewer + files))
        assertTrue(main.contains("AttachmentViewerHost(state.viewer, model)"))
    }

    @Test
    fun `open-with hands over only a scoped read-only content uri from the viewed cache folder`() {
        assertTrue(files.contains("FileProvider.getUriForFile(context, \"\${context.packageName}.diagshare\", it)"))
        assertTrue(files.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"))
        assertFalse(files.contains("Uri.fromFile") || files.contains("file://"))
        assertTrue(files.contains("canonicalFile.parentFile == dir(context).canonicalFile"))
        val paths = source("phone/src/main/res/xml/diag_share_paths.xml")
        assertTrue(paths.contains("<cache-path name=\"viewed\" path=\"viewed/\" />"))
        assertFalse("no broad root is shared", paths.contains("root-path") || paths.contains("files-path") || paths.contains("external"))
        val manifest = source("phone/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android:exported=\"false\"") && manifest.contains("android:grantUriPermissions=\"true\""))
    }

    @Test
    fun `the viewed folder is emptied when the viewer closes, a new one opens, the screen is cleared and the app starts`() {
        assertTrue(block(model, "    fun closeViewer()", "    /** Copies the viewed").contains("ViewedFiles.clear(app)"))
        assertTrue(block(model, "    private fun showViewer(", "    private fun viewerFailure").contains("ViewedFiles.clear(app)"))
        assertTrue(block(model, "    override fun onCleared()", "    companion object").contains("ViewedFiles.clear(app)"))
        assertTrue(block(model, "    init {", "viewModelScope.launch").contains("ViewedFiles.clear(app)"))
        assertTrue("a stale result is dropped", model.contains("if (viewerSerial.get() == serial)"))
    }
}
