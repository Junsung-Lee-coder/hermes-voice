package com.rumi.hermesvoice.core.attachments

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

enum class PreviewKind { IMAGE, PDF, TEXT, OTHER }

/** What a file really is, decided from its bytes first (a server's or sender's claim never makes a non-image decodable). */
class PreviewDecision(val kind: PreviewKind, val mimeType: String)

/** Pure checks that keep previews safe: sniffing, bounded text, and a storage name that cannot escape its folder. */
object FilePreview {
    /** Characters of a text file shown; the rest is reachable through Download / Open with. */
    const val MAX_TEXT_CHARS = 20_000

    /** A text preview never decodes more than this many bytes. */
    const val MAX_TEXT_BYTES = 128 * 1024
    private val TEXT_EXTENSIONS = setOf("txt", "md", "log", "json", "csv", "tsv", "xml", "yaml", "yml", "ini", "toml", "kt", "py", "js", "ts", "java", "c", "h", "cpp", "sh", "sql", "html", "css", "diff", "patch")
    private val SAFE_NAME = Regex("[^A-Za-z0-9._-]+")

    fun classify(name: String, declaredMime: String, bytes: ByteArray): PreviewDecision {
        imageMime(bytes)?.let { return PreviewDecision(PreviewKind.IMAGE, it) }
        if (isPdf(bytes)) return PreviewDecision(PreviewKind.PDF, "application/pdf")
        val declared = declaredMime.substringBefore(';').trim().lowercase(Locale.ROOT)
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val textual = declared.startsWith("text/") || declared in setOf("application/json", "application/xml", "application/x-yaml") || extension in TEXT_EXTENSIONS
        if (textual && !declared.startsWith("image/") && textPreview(bytes) != null) return PreviewDecision(PreviewKind.TEXT, declared.ifEmpty { "text/plain" })
        return PreviewDecision(PreviewKind.OTHER, declared.ifEmpty { "application/octet-stream" })
    }

    fun imageMime(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
        bytes.size >= 3 && bytes.startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
        bytes.size >= 6 && (String(bytes, 0, 6, Charsets.ISO_8859_1) == "GIF87a" || String(bytes, 0, 6, Charsets.ISO_8859_1) == "GIF89a") -> "image/gif"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
        else -> null
    }

    fun isPdf(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
        return head.contains("%PDF-")
    }

    /** The start of [bytes] as text, or null when it is not valid UTF-8 text (a NUL byte or a bad sequence anywhere in the decoded part). */
    fun textPreview(bytes: ByteArray): String? {
        val limit = minOf(bytes.size, MAX_TEXT_BYTES)
        if ((0 until limit).any { bytes[it] == 0.toByte() }) return null
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        // A cut multi-byte character at the bound is not an error: back up to a boundary.
        var end = limit
        if (limit < bytes.size) while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, 0, end)).toString().take(MAX_TEXT_CHARS)
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /** A file name for the app's own cache: ASCII letters, digits, dot, dash, underscore; never a path, never hidden, bounded. */
    fun safeStorageName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = SAFE_NAME.replace(base, "_").trim('.', '_').take(64)
        return cleaned.ifEmpty { "file" }
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean = prefix.indices.all { this[it] == prefix[it].toByte() }
}
