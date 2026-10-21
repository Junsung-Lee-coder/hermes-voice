package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.attachments.FilePreview
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A file too large to hold in memory is classified from its first bytes only. That is the same decision as for the whole file
 * because classification and the text preview never look past the first [FilePreview.MAX_TEXT_BYTES] bytes plus the one after
 * them (the boundary of a cut multi-byte character).
 */
class FilePreviewHeadTest {
    private val limit = FilePreview.MAX_TEXT_BYTES

    private fun head(whole: ByteArray) = whole.copyOf(minOf(whole.size, limit + 1))

    private fun same(name: String, mime: String, whole: ByteArray) {
        val a = FilePreview.classify(name, mime, whole)
        val b = FilePreview.classify(name, mime, head(whole))
        assertEquals("kind of $name (${whole.size} bytes)", a.kind, b.kind)
        assertEquals("type of $name (${whole.size} bytes)", a.mimeType, b.mimeType)
        assertEquals("preview text of $name (${whole.size} bytes)", FilePreview.textPreview(whole), FilePreview.textPreview(head(whole)))
    }

    @Test
    fun `long ascii text gives the same decision and preview from its head`() {
        same("log.txt", "text/plain", ByteArray(3 * limit) { 'a'.code.toByte() })
    }

    @Test
    fun `a multi-byte character cut at the preview boundary gives the same preview from its head`() {
        for (offset in -4..4) {
            val unit = "é".toByteArray()
            val whole = ByteArray(limit + 64) { 'x'.code.toByte() }
            val at = limit + offset - 1
            whole[at] = unit[0]
            whole[at + 1] = unit[1]
            same("notes.txt", "text/plain", whole)
        }
    }

    @Test
    fun `a NUL byte inside the previewed part is binary from the head as from the whole`() {
        val whole = ByteArray(2 * limit) { 'a'.code.toByte() }.also { it[limit - 1] = 0 }
        same("data.txt", "text/plain", whole)
    }

    @Test
    fun `a NUL byte after the previewed part does not change the decision`() {
        val whole = ByteArray(2 * limit) { 'a'.code.toByte() }.also { it[limit + 10] = 0 }
        same("data.txt", "text/plain", whole)
    }

    @Test
    fun `images, pdf and unknown binaries are decided from their signatures`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(2 * limit)
        same("a.png", "image/png", png)
        same("a.bin", "application/octet-stream", png)
        same("a.pdf", "application/pdf", "%PDF-1.7\n".toByteArray() + ByteArray(2 * limit))
        same("blob.bin", "application/octet-stream", ByteArray(2 * limit) { (it * 7).toByte() })
    }
}
