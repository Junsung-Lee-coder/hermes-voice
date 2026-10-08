package com.rumi.hermesvoice.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.rumi.hermesvoice.core.attachments.FilePreview
import com.rumi.hermesvoice.core.attachments.PreviewKind
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** What the viewer shows: the file's own bytes (bounded by the preview limit), its decided kind, and its cache copy. */
class ViewedAttachment(
    val name: String,
    val mimeType: String,
    val kind: PreviewKind,
    val size: Int,
    /** The bounded text of a [PreviewKind.TEXT] file; null for the others. */
    val text: String?,
    /** The app-cache copy used for PDF rendering and "Open with". */
    val file: File,
    internal val bytes: ByteArray,
)

enum class ViewerPhase { LOADING, READY, FAILED }

class AttachmentViewerState(val title: String, val phase: ViewerPhase, val file: ViewedAttachment? = null, val error: String? = null)

/**
 * The folder of files being viewed: inside the app's cache ("viewed/"), under names that cannot leave it, emptied when the
 * viewer closes and whenever the app starts. It is the only place the app's FileProvider shares besides the diagnostics.
 */
internal object ViewedFiles {
    const val SUBDIR = "viewed"
    private val counter = AtomicLong(0)

    fun dir(context: Context): File = File(context.cacheDir, SUBDIR)

    fun write(context: Context, name: String, bytes: ByteArray): File {
        val dir = dir(context).also { it.mkdirs() }
        val file = File(dir, "${counter.incrementAndGet()}-${FilePreview.safeStorageName(name)}")
        check(file.canonicalFile.parentFile == dir.canonicalFile) { "unsafe cache name" }
        file.writeBytes(bytes)
        return file
    }

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { runCatching { it.delete() } }
    }

    /** True when [file] is a direct child of the viewed folder (the only files the provider may hand out for viewing). */
    fun shareable(context: Context, file: File): Boolean =
        runCatching { file.canonicalFile.parentFile == dir(context).canonicalFile && file.isFile }.getOrDefault(false)

    /** The "Open with" chooser for [viewed]: its cache copy as a scoped read-only content:// grant, never a file path or URL. */
    fun openWithIntent(
        context: Context,
        viewed: ViewedAttachment,
        uriFor: (File) -> Uri = { FileProvider.getUriForFile(context, "${context.packageName}.diagshare", it) },
    ): Intent? {
        val file = viewed.file.takeIf { shareable(context, it) } ?: return null
        val uri: Uri = uriFor(file)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, viewed.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).also {
            it.clipData = android.content.ClipData.newRawUri("attachment", uri)
        }
    }
}
