package com.rumi.hermesvoice.core.net

import com.rumi.hermesvoice.core.HermesProtocolException
import com.rumi.hermesvoice.core.attachments.AttachmentRef
import java.io.File

/** The bytes of a stored file as the dashboard served them; [mimeType] is the server's claim, to be checked by [com.rumi.hermesvoice.core.attachments.FilePreview]. */
class StoredFile(val name: String, val mimeType: String, val bytes: ByteArray) {
    override fun toString() = "StoredFile(bytes=${bytes.size})"
}

/** A received file streamed to disk by [StoredFileSource.downloadStoredFile]: [size] is the raw byte count written. */
class StoredDownload(val name: String, val mimeType: String, val size: Long)

/** A file above [limitBytes], the limit of the route that refused it (see [StoredFileSource.MAX_BYTES], [StoredFileSource.MAX_DOWNLOAD_BYTES]). */
class StoredFileTooLargeException(message: String, val limitBytes: Long) : HermesProtocolException(message)

/** The dashboard has no `GET /api/files/download` route, so a file above the preview limit cannot be received from it. */
class StoredDownloadUnavailableException(message: String) : HermesProtocolException(message)

/** Where a file named in a message is read from: the authenticated dashboard (see [HermesDashboardClient.fetchStoredFile]). */
interface StoredFileSource {
    suspend fun fetchStoredFile(ref: AttachmentRef): StoredFile

    /** Streams the file to [target] (replacing it); on any failure or cancellation nothing is left at [target]. */
    suspend fun downloadStoredFile(ref: AttachmentRef, target: File): StoredDownload

    companion object {
        /** The most a preview or download holds in memory. */
        const val MAX_BYTES = 16 * 1024 * 1024

        /** The JSON document that carries it: base64 inflation plus the envelope. */
        const val MAX_BODY_BYTES: Long = MAX_BYTES.toLong() * 4 / 3 + 64 * 1024

        /** The most a streamed download writes to disk, the dashboard's own cap for a file it serves. */
        const val MAX_DOWNLOAD_BYTES: Long = 100L * 1024 * 1024
    }
}
