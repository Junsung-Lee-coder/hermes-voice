package com.rumi.hermesvoice.core.net

import com.rumi.hermesvoice.core.attachments.AttachmentRef

/** The bytes of a stored file as the dashboard served them; [mimeType] is the server's claim, to be checked by [com.rumi.hermesvoice.core.attachments.FilePreview]. */
class StoredFile(val name: String, val mimeType: String, val bytes: ByteArray) {
    override fun toString() = "StoredFile(bytes=${bytes.size})"
}

/** Where a file named in a message is read from: the authenticated dashboard (see [HermesDashboardClient.fetchStoredFile]). */
interface StoredFileSource {
    suspend fun fetchStoredFile(ref: AttachmentRef): StoredFile

    companion object {
        /** The most a preview or download holds in memory. */
        const val MAX_BYTES = 16 * 1024 * 1024

        /** The JSON document that carries it: base64 inflation plus the envelope. */
        const val MAX_BODY_BYTES: Long = MAX_BYTES.toLong() * 4 / 3 + 64 * 1024
    }
}
