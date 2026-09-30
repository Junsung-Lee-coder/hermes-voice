package com.rumi.hermesvoice.core.voice

import java.util.Locale

/** One app-maintained destination: the router may only ever name [alias]; [storedSessionId] never comes from a model. */
data class DestinationEntry(
    val alias: String,
    val storedSessionId: String,
    val description: String = "",
)

/**
 * The only mapping from a model-chosen alias to a Hermes session id. It is built from the
 * app's own (unarchived) session registry (it may be empty: the router can then only ask for a
 * new conversation); construction validates every entry and rejects the routing session itself
 * as a destination. Lookups are exact on the normalized alias, so
 * anything the router invents resolves to null (fail closed).
 */
class DestinationAllowlist private constructor(val entries: List<DestinationEntry>) {
    private val byAlias: Map<String, DestinationEntry> = entries.associateBy { it.alias }

    fun resolve(alias: String): DestinationEntry? = normalizeAlias(alias)?.let(byAlias::get)

    companion object {
        const val MAX_ENTRIES = 32
        const val MAX_DESCRIPTION_CHARS = 160
        private val ALIAS = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")
        private val SESSION_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

        fun normalizeAlias(raw: String): String? = raw.trim().lowercase(Locale.ROOT).takeIf(ALIAS::matches)

        fun isValidSessionId(raw: String): Boolean = SESSION_ID.matches(raw)

        fun cleanDescription(raw: String): String = TextSanitizer.clean(raw)

        /** Throws [IllegalArgumentException] naming the first invalid entry; never silently drops one. */
        fun create(entries: List<DestinationEntry>, routingStoredSessionId: String): DestinationAllowlist {
            require(isValidSessionId(routingStoredSessionId)) { "routing session id is invalid" }
            require(entries.size <= MAX_ENTRIES) { "at most $MAX_ENTRIES destinations are allowed" }
            val seen = HashSet<String>()
            val clean = entries.map { entry ->
                val alias = requireNotNull(normalizeAlias(entry.alias)) {
                    "destination alias '${entry.alias.take(40)}' must match ${ALIAS.pattern}"
                }
                require(seen.add(alias)) { "duplicate destination alias '$alias'" }
                val sessionId = entry.storedSessionId.trim()
                require(isValidSessionId(sessionId)) { "destination '$alias' has an invalid session id" }
                require(sessionId != routingStoredSessionId) { "destination '$alias' must not be the routing session" }
                val description = cleanDescription(entry.description)
                require(description.length <= MAX_DESCRIPTION_CHARS) {
                    "destination '$alias' description exceeds $MAX_DESCRIPTION_CHARS characters"
                }
                DestinationEntry(alias, sessionId, description)
            }
            return DestinationAllowlist(clean)
        }
    }
}
