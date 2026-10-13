package com.rumi.hermesvoice.phone

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rumi.hermesvoice.core.attachments.PreviewKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Decodes pictures at a bounded size, so a huge image can never be inflated into memory at full resolution. */
internal object BoundedImage {
    const val MAX_DIMENSION = 2048

    fun sampleSize(width: Int, height: Int, maxDimension: Int = MAX_DIMENSION): Int {
        var sample = 1
        while (maxOf(width, height) / sample > maxDimension) sample *= 2
        return sample
    }

    fun decode(bytes: ByteArray): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) })
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

internal fun readableSize(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
    else -> "%.1f MiB".format(bytes / 1048576.0)
}

private const val MAX_PDF_PAGES = 100
private const val PDF_PAGE_WIDTH = 1080

/** Hosts the viewer for [PhoneUiState.viewer]: a full-screen dialog that Back, or the Close button, dismisses. */
@Composable
internal fun AttachmentViewerHost(state: AttachmentViewerState?, model: PhoneViewModel) {
    if (state == null) return
    val context = LocalContext.current
    var note by remember(state) { mutableStateOf<String?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) model.saveViewed(uri) { ok -> note = if (ok) "Saved" else "Could not save the file" }
    }
    Dialog(onDismissRequest = model::closeViewer, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("attachment_viewer"), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = model::closeViewer, modifier = Modifier.heightIn(min = 48.dp).testTag("viewer_close")) { Text("Close") }
                    Text(state.title, Modifier.weight(1f).padding(horizontal = 8.dp).testTag("viewer_title"), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall)
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when (state.phase) {
                        ViewerPhase.LOADING -> CircularProgressIndicator(Modifier.testTag("viewer_loading"))
                        ViewerPhase.FAILED -> Text(state.error ?: "This file can't be shown", Modifier.padding(24.dp).testTag("viewer_error"),
                            color = MaterialTheme.colorScheme.error)
                        ViewerPhase.READY -> state.file?.let { ViewerBody(it) }
                    }
                }
                state.file?.takeIf { state.phase == ViewerPhase.READY }?.let { viewed ->
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        Text("${viewed.name} · ${viewed.mimeType} · ${readableSize(viewed.size)}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("viewer_meta"))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val intent = ViewedFiles.openWithIntent(context, viewed)
                                note = if (intent == null) "This file can't be opened elsewhere" else try {
                                    context.startActivity(intent); null
                                } catch (_: ActivityNotFoundException) {
                                    "No app can open this file"
                                } catch (_: SecurityException) {
                                    "No app can open this file"
                                }
                            }, modifier = Modifier.heightIn(min = 48.dp).testTag("viewer_open_with")) { Text("Open with") }
                            OutlinedButton(onClick = { saver.launch(viewed.name) }, modifier = Modifier.heightIn(min = 48.dp).testTag("viewer_save")) { Text("Save") }
                        }
                        note?.let { Text(it, Modifier.testTag("viewer_note"), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerBody(viewed: ViewedAttachment) {
    when (viewed.kind) {
        PreviewKind.IMAGE -> ImageBody(viewed)
        PreviewKind.PDF -> PdfBody(viewed)
        PreviewKind.TEXT -> Text(viewed.text.orEmpty(), Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("viewer_text"),
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        PreviewKind.OTHER -> FileCard(viewed)
    }
}

@Composable
private fun FileCard(viewed: ViewedAttachment, reason: String = "This kind of file can't be previewed here") {
    Column(Modifier.padding(24.dp).testTag("viewer_file_card"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("📄 ${viewed.name}", style = MaterialTheme.typography.titleMedium)
        Text("${viewed.mimeType} · ${readableSize(viewed.size)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ImageBody(viewed: ViewedAttachment) {
    val bitmap by produceState<Bitmap?>(null, viewed) { value = withContext(Dispatchers.Default) { BoundedImage.decode(viewed.bytes) } }
    val decoded = bitmap
    if (decoded == null) {
        FileCard(viewed, "This picture can't be previewed here")
        return
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Image(
        decoded.asImageBitmap(), contentDescription = "Picture ${viewed.name}", contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize().testTag("viewer_image")
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
    )
}

@Composable
private fun PdfBody(viewed: ViewedAttachment) {
    val opened = remember(viewed) {
        try {
            val descriptor = ParcelFileDescriptor.open(viewed.file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                PdfDocument(descriptor, PdfRenderer(descriptor))
            } catch (error: Exception) {
                descriptor.close()
                null
            }
        } catch (_: Exception) {
            null
        }
    }
    DisposableEffect(opened) { onDispose { opened?.close() } }
    if (opened == null || opened.renderer.pageCount <= 0) {
        FileCard(viewed, "This PDF can't be previewed here")
        return
    }
    val pages = minOf(opened.renderer.pageCount, MAX_PDF_PAGES)
    LazyColumn(Modifier.fillMaxSize().testTag("viewer_pdf"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(pages) { index ->
            val page by produceState<Bitmap?>(null, opened, index) { value = withContext(Dispatchers.Default) { opened.render(index) } }
            val bitmap = page
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), contentDescription = "Page ${index + 1} of ${opened.renderer.pageCount}", contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth())
            } else {
                Box(Modifier.fillMaxWidth().heightIn(min = 200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
        if (opened.renderer.pageCount > pages) item { Text("Showing the first $pages of ${opened.renderer.pageCount} pages. Use Open with for the rest.", Modifier.padding(16.dp)) }
    }
}

/** A PDF open for viewing: the platform renderer allows one page open at a time, so pages are drawn one after another. */
private class PdfDocument(val descriptor: ParcelFileDescriptor, val renderer: PdfRenderer) {
    private val lock = Mutex()
    @Volatile private var closed = false

    suspend fun render(index: Int): Bitmap? = lock.withLock {
        if (closed) return null
        try {
            renderer.openPage(index).use { page ->
                val width = PDF_PAGE_WIDTH
                val height = (width.toLong() * page.height / maxOf(1, page.width)).coerceIn(1, 4096).toInt()
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } finally {
            if (closed) release()
        }
    }

    /** Closes once no page is being drawn; a page in progress finishes first and then closes it. */
    fun close() {
        closed = true
        if (lock.tryLock()) {
            try {
                release()
            } finally {
                lock.unlock()
            }
        }
    }

    private fun release() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }
}
