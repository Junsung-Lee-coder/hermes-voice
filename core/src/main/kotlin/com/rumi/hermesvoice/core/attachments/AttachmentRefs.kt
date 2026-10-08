package com.rumi.hermesvoice.core.attachments

enum class RefKind { IMAGE, FILE, MEDIA }

/**
 * A reference to a stored file found in a chat message. [path] is the server-side path exactly as Hermes wrote it;
 * it is only ever sent back to the authenticated dashboard (never opened locally). [name] is a display-safe file name.
 */
class AttachmentRef(val kind: RefKind, val path: String, val name: String) {
    override fun equals(other: Any?) = other is AttachmentRef && other.kind == kind && other.path == path
    override fun hashCode() = 31 * kind.hashCode() + path.hashCode()
    override fun toString() = "AttachmentRef($kind)"
}

/**
 * Finds the file references Hermes leaves in stored messages (see the capability audit):
 * `@image:<path>` and `@file:<path>` in user rows (the value is quoted with `` ` `` `"` or `'` when it holds whitespace),
 * and `MEDIA:<path>` in assistant rows. Bounded and path-strict: at most [MAX_REFS] per message, no control
 * characters, no `..` segments, no URL schemes.
 */
object AttachmentRefs {
    const val MAX_REFS = 12
    const val MAX_PATH = 1024
    private const val MAX_NAME = 120
    private val PREFIX = Regex("""(?:^|(?<=\s))(@image:|@file:|MEDIA:)""")
    private val CONTROL = Regex("[\\p{Cntrl}]")

    fun extract(text: String): List<AttachmentRef> {
        if (text.isEmpty() || text.length > MAX_SCAN) return emptyList()
        val found = LinkedHashSet<AttachmentRef>()
        for (match in PREFIX.findAll(text)) {
            if (found.size >= MAX_REFS) break
            val kind = when (match.groupValues[1]) {
                "@image:" -> RefKind.IMAGE
                "@file:" -> RefKind.FILE
                else -> RefKind.MEDIA
            }
            val raw = value(text, match.range.last + 1, kind) ?: continue
            val path = raw.trim()
            if (!acceptable(path)) continue
            found += AttachmentRef(kind, path, displayName(path))
        }
        return found.toList()
    }

    private fun value(text: String, start: Int, kind: RefKind): String? {
        if (start >= text.length) return null
        val first = text[start]
        if (first == '`' || first == '"' || first == '\'') {
            val end = text.indexOf(first, start + 1)
            return if (end < 0) null else text.substring(start + 1, end)
        }
        val lineEnd = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        return if (kind == RefKind.MEDIA) text.substring(start, lineEnd) else text.substring(start, lineEnd).takeWhile { !it.isWhitespace() }
    }

    private fun acceptable(path: String): Boolean {
        if (path.isEmpty() || path.length > MAX_PATH) return false
        if (CONTROL.containsMatchIn(path)) return false
        if (Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(path)) return false
        if (path.split('/', '\\').any { it == ".." }) return false
        return true
    }

    fun displayName(path: String): String {
        val base = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        val clean = CONTROL.replace(base, "_").trim()
        return clean.ifEmpty { "attachment" }.take(MAX_NAME)
    }

    private const val MAX_SCAN = 200_000
}
