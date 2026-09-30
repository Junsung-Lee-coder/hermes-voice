package com.rumi.hermesvoice.core.sessions

import com.rumi.hermesvoice.core.KeyValueStore
import com.rumi.hermesvoice.core.SessionNotOwnedException
import com.rumi.hermesvoice.core.net.ArchivedFilter
import com.rumi.hermesvoice.core.net.HermesConversationPort
import com.rumi.hermesvoice.core.net.HistoryPage
import com.rumi.hermesvoice.core.net.OutgoingAttachment
import com.rumi.hermesvoice.core.net.StoredSession
import com.rumi.hermesvoice.core.net.StoredSessionPage
import com.rumi.hermesvoice.core.net.SubmittedTurn
import com.rumi.hermesvoice.core.voice.DestinationAllowlist
import com.rumi.hermesvoice.core.voice.DestinationEntry
import com.rumi.hermesvoice.core.voice.RoutingContract
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The dashboard session routes the repository needs; [com.rumi.hermesvoice.core.net.HermesDashboardClient] implements them. */
interface HermesSessionsApi {
    suspend fun listSessions(source: String, archived: ArchivedFilter, limit: Int = 100, offset: Int = 0): StoredSessionPage
    suspend fun getSession(sessionId: String): StoredSession?
    suspend fun getMessages(sessionId: String, limit: Int = 50, offset: Int = 0): HistoryPage
    suspend fun setArchived(sessionId: String, archived: Boolean)
}

object AppSources {
    /** Every conversation this app creates is tagged with this Hermes `source`. */
    const val CONVERSATION = "recorder-phone"

    /** The routing session gets its own source (and is created hidden) so it never shows in conversation lists. */
    const val ROUTER = "recorder-phone-router"
}

enum class OwnedRole { CONVERSATION, ROUTER }

/** One session this app created, by its exact stored id. [alias] is how the voice router may name it. */
data class OwnedSession(
    val storedSessionId: String,
    val role: OwnedRole,
    val title: String,
    val alias: String,
    val description: String,
    val archived: Boolean,
    val createdAtMs: Long,
) {
    fun toJson(): JSONObject = JSONObject().put("id", storedSessionId).put("role", role.name).put("title", title)
        .put("alias", alias).put("description", description).put("archived", archived).put("created_at_ms", createdAtMs)

    companion object {
        fun fromJson(json: JSONObject): OwnedSession = OwnedSession(
            storedSessionId = json.getString("id"),
            role = OwnedRole.valueOf(json.getString("role")),
            title = json.optString("title"),
            alias = json.optString("alias"),
            description = json.optString("description"),
            archived = json.optBoolean("archived"),
            createdAtMs = json.optLong("created_at_ms"),
        )
    }
}

/**
 * The exact stored session ids created by this install, persisted locally. `source` on the server
 * is only a categorization filter; this registry is what makes a session "ours". A session id
 * that is not in here is never listed, read, archived or submitted to.
 */
class OwnedSessionRegistry(private val store: KeyValueStore) {
    @Synchronized
    fun all(): List<OwnedSession> {
        val raw = store.getString(KEY) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { OwnedSession.fromJson(array.getJSONObject(it)) }
        } catch (_: JSONException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    fun find(storedSessionId: String): OwnedSession? = all().firstOrNull { it.storedSessionId == storedSessionId }

    fun router(): OwnedSession? = all().firstOrNull { it.role == OwnedRole.ROUTER }

    @Synchronized
    fun put(session: OwnedSession) {
        val next = all().filterNot { it.storedSessionId == session.storedSessionId } + session
        store.putString(KEY, JSONArray().apply { next.forEach { put(it.toJson()) } }.toString())
    }

    @Synchronized
    fun update(storedSessionId: String, transform: (OwnedSession) -> OwnedSession): OwnedSession {
        val current = find(storedSessionId) ?: throw SessionNotOwnedException("session was not created by this app")
        return transform(current).also { require(it.storedSessionId == storedSessionId); put(it) }
    }

    companion object {
        const val KEY = "owned_sessions_v1"
    }
}

/** A conversation row for the Phone UI: our registry entry joined with the dashboard's row. */
data class AppConversation(val owned: OwnedSession, val stored: StoredSession)

/**
 * App-only session management over the existing dashboard + gateway surface, with no Hermes
 * change. Listing uses the server-side `source` filter AND the local exact-id registry;
 * history/archive/submit verify both (registry membership, then the server row's `source`)
 * before touching a session, and fail closed otherwise. This does not stop the same dashboard
 * identity from reaching its other sessions through other clients; it only keeps this app to its own.
 */
class AppSessionRepository(
    private val api: HermesSessionsApi,
    private val conversations: HermesConversationPort,
    val registry: OwnedSessionRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val verifiedSources = HashSet<String>()

    suspend fun createConversation(title: String, alias: String, description: String): OwnedSession {
        val cleanTitle = title.trim().ifEmpty { "Hermes Voice" }.take(MAX_TITLE_CHARS)
        val cleanAlias = requireNotNull(DestinationAllowlist.normalizeAlias(alias)) {
            "alias must be 1-32 characters: lowercase letters, digits, '-' or '_'"
        }
        requireAliasFree(cleanAlias, exceptSessionId = null)
        val cleanDescription = DestinationAllowlist.cleanDescription(description)
        require(cleanDescription.length <= DestinationAllowlist.MAX_DESCRIPTION_CHARS) { "description is too long" }
        val created = conversations.create(AppSources.CONVERSATION, cleanTitle, CONVERSATION_SEED, hidden = false)
        return OwnedSession(created.storedSessionId, OwnedRole.CONVERSATION, cleanTitle, cleanAlias, cleanDescription,
            archived = false, createdAtMs = clock()).also(registry::put)
    }

    /** The persistent routing session, created (hidden, router source) on first use and then reused. */
    suspend fun ensureRoutingSession(): OwnedSession {
        registry.router()?.let { return it }
        val created = conversations.create(AppSources.ROUTER, "Hermes Voice router", RoutingContract.ROUTER_SEED, hidden = true)
        return OwnedSession(created.storedSessionId, OwnedRole.ROUTER, "Hermes Voice router", "", "",
            archived = false, createdAtMs = clock()).also(registry::put)
    }

    /** Only rows that carry the app source AND whose exact id this install created. The router never appears. */
    suspend fun listConversations(archived: Boolean): List<AppConversation> {
        val owned = registry.all().filter { it.role == OwnedRole.CONVERSATION }.associateBy { it.storedSessionId }
        if (owned.isEmpty()) return emptyList()
        val rows = ArrayList<StoredSession>()
        var offset = 0
        for (page in 0 until MAX_LIST_PAGES) {
            val result = api.listSessions(AppSources.CONVERSATION, if (archived) ArchivedFilter.ONLY else ArchivedFilter.EXCLUDE,
                limit = PAGE_SIZE, offset = offset)
            rows += result.sessions
            offset += result.sessions.size
            if (result.sessions.size < PAGE_SIZE || offset >= result.total) break
        }
        return rows.filter { it.source == AppSources.CONVERSATION }.mapNotNull { row ->
            val mine = owned[row.id] ?: return@mapNotNull null
            val synced = if (mine.archived != row.archived) registry.update(row.id) { it.copy(archived = row.archived) } else mine
            AppConversation(synced, row)
        }
    }

    suspend fun history(storedSessionId: String, limit: Int = 50, offset: Int = 0): HistoryPage {
        verifyConversation(storedSessionId)
        return api.getMessages(storedSessionId, limit, offset)
    }

    suspend fun setArchived(storedSessionId: String, archived: Boolean): OwnedSession {
        verifyConversation(storedSessionId, forceServerCheck = true)
        if (!archived) requireAliasFree(registry.find(storedSessionId)!!.alias, exceptSessionId = storedSessionId)
        api.setArchived(storedSessionId, archived)
        return registry.update(storedSessionId) { it.copy(archived = archived) }
    }

    fun updateDestination(storedSessionId: String, alias: String, description: String): OwnedSession {
        val owned = registry.find(storedSessionId)?.takeIf { it.role == OwnedRole.CONVERSATION }
            ?: throw SessionNotOwnedException("session was not created by this app")
        val cleanAlias = requireNotNull(DestinationAllowlist.normalizeAlias(alias)) { "invalid alias" }
        requireAliasFree(cleanAlias, exceptSessionId = storedSessionId)
        val cleanDescription = DestinationAllowlist.cleanDescription(description)
        require(cleanDescription.length <= DestinationAllowlist.MAX_DESCRIPTION_CHARS) { "description is too long" }
        return registry.update(owned.storedSessionId) { it.copy(alias = cleanAlias, description = cleanDescription) }
    }

    /** Text chat (optionally with attachments) into one of our conversations. */
    suspend fun sendMessage(storedSessionId: String, text: String, attachments: List<OutgoingAttachment> = emptyList()): SubmittedTurn {
        require(text.isNotBlank() || attachments.isNotEmpty()) { "message is empty" }
        verifyConversation(storedSessionId)
        return conversations.submit(storedSessionId, text.trim(), attachments)
    }

    /** The voice router's allowlist: our unarchived conversations. Throws when there is nothing to route to. */
    fun allowlist(routingStoredSessionId: String): DestinationAllowlist = DestinationAllowlist.create(
        registry.all().filter { it.role == OwnedRole.CONVERSATION && !it.archived }
            .map { DestinationEntry(it.alias, it.storedSessionId, it.description.ifBlank { it.title }) },
        routingStoredSessionId,
    )

    /**
     * A conversation port that only ever submits to sessions this app owns (the router included),
     * verified the same way as text chat. The voice orchestrator uses this, never the raw port.
     */
    fun guardedPort(): HermesConversationPort = object : HermesConversationPort {
        override suspend fun create(source: String, title: String, seedInstruction: String, hidden: Boolean) =
            throw UnsupportedOperationException("create sessions through AppSessionRepository")

        override suspend fun submit(storedSessionId: String, text: String, attachments: List<OutgoingAttachment>): SubmittedTurn {
            val owned = registry.find(storedSessionId) ?: throw SessionNotOwnedException("session was not created by this app")
            if (owned.role == OwnedRole.ROUTER) verifySource(storedSessionId, AppSources.ROUTER, false)
            else verifyConversation(storedSessionId)
            return conversations.submit(storedSessionId, text, attachments)
        }
    }

    private suspend fun verifyConversation(storedSessionId: String, forceServerCheck: Boolean = false) {
        val owned = registry.find(storedSessionId)
        if (owned == null || owned.role != OwnedRole.CONVERSATION) {
            throw SessionNotOwnedException("session was not created by this app")
        }
        verifySource(storedSessionId, AppSources.CONVERSATION, forceServerCheck)
    }

    private suspend fun verifySource(storedSessionId: String, source: String, force: Boolean) {
        val key = "$source|$storedSessionId"
        if (!force && synchronized(verifiedSources) { key in verifiedSources }) return
        val row = api.getSession(storedSessionId) ?: throw SessionNotOwnedException("session no longer exists on the dashboard")
        if (row.id != storedSessionId || row.source != source) {
            throw SessionNotOwnedException("session is not tagged with this app's source")
        }
        synchronized(verifiedSources) { verifiedSources += key }
    }

    private fun requireAliasFree(alias: String, exceptSessionId: String?) {
        val clash = registry.all().any {
            it.role == OwnedRole.CONVERSATION && !it.archived && it.alias == alias && it.storedSessionId != exceptSessionId
        }
        require(!clash) { "alias '$alias' is already used by another conversation" }
    }

    companion object {
        const val PAGE_SIZE = 100
        const val MAX_LIST_PAGES = 10
        const val MAX_TITLE_CHARS = 80
        const val CONVERSATION_SEED =
            "This conversation was created by the Hermes Voice phone app. User turns may be voice transcripts " +
                "(speech-to-text, so expect recognition errors) and replies may be read aloud: prefer short, " +
                "plain-spoken answers without markdown tables or code unless asked."
    }
}
