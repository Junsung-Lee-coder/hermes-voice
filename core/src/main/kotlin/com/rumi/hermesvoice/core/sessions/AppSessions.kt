package com.rumi.hermesvoice.core.sessions

import com.rumi.hermesvoice.core.HermesAuthRequiredException
import com.rumi.hermesvoice.core.HermesException
import com.rumi.hermesvoice.core.HermesRpcException
import com.rumi.hermesvoice.core.KeyValueStore
import com.rumi.hermesvoice.core.SessionNotOwnedException
import com.rumi.hermesvoice.core.net.ArchivedFilter
import com.rumi.hermesvoice.core.net.HermesConversationPort
import com.rumi.hermesvoice.core.net.HistoryPage
import com.rumi.hermesvoice.core.net.OutgoingAttachment
import com.rumi.hermesvoice.core.net.StoredSession
import com.rumi.hermesvoice.core.net.StoredSessionPage
import com.rumi.hermesvoice.core.net.SubmittedTurn
import com.rumi.hermesvoice.core.voice.CreateIntent
import com.rumi.hermesvoice.core.voice.DestinationAllowlist
import com.rumi.hermesvoice.core.voice.DestinationEntry
import com.rumi.hermesvoice.core.voice.PriorCreate
import com.rumi.hermesvoice.core.voice.RecipientCreator
import com.rumi.hermesvoice.core.voice.RoutingContract
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
class OwnedSessionRegistry(internal val store: KeyValueStore) {
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

/** How far a voice turn that asked for a new conversation got. Persisted, so it survives a restart. */
enum class TurnCreateState {
    /** `session.create` was about to be sent; whether the server created a session is unknown. */
    PENDING,

    /** The server session exists, its id is recorded here; the transcript has not been submitted. */
    CREATED,

    /** The transcript was handed to `prompt.submit` (or that was about to happen): never submit again. */
    SUBMITTED,
}

data class TurnCreateRecord(
    val turnId: String,
    val state: TurnCreateState,
    val title: String,
    val alias: String,
    val description: String,
    val ackText: String,
    val storedSessionId: String = "",
    val createdAtMs: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().put("turn_id", turnId).put("state", state.name).put("title", title)
        .put("alias", alias).put("description", description).put("ack", ackText).put("id", storedSessionId)
        .put("created_at_ms", createdAtMs)

    companion object {
        fun fromJson(json: JSONObject): TurnCreateRecord = TurnCreateRecord(json.getString("turn_id"),
            TurnCreateState.valueOf(json.getString("state")), json.optString("title"), json.optString("alias"),
            json.optString("description"), json.optString("ack"), json.optString("id"), json.optLong("created_at_ms"))
    }
}

/**
 * Durable journal of voice turns that created a conversation, kept next to the registry (same
 * dashboard and profile). It is what makes a replayed turn id create at most one session and
 * submit its transcript at most once, across a process restart. It holds no transcript.
 */
class TurnCreateJournal(private val store: KeyValueStore) {
    @Synchronized
    fun all(): List<TurnCreateRecord> {
        val raw = store.getString(KEY) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { TurnCreateRecord.fromJson(array.getJSONObject(it)) }
        } catch (_: JSONException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    fun find(turnId: String): TurnCreateRecord? = all().firstOrNull { it.turnId == turnId }

    @Synchronized
    fun put(record: TurnCreateRecord) {
        val next = (all().filterNot { it.turnId == record.turnId } + record).takeLast(MAX_RECORDS)
        store.putString(KEY, JSONArray().apply { next.forEach { put(it.toJson()) } }.toString())
    }

    @Synchronized
    fun remove(turnId: String) {
        store.putString(KEY, JSONArray().apply { all().filterNot { it.turnId == turnId }.forEach { put(it.toJson()) } }.toString())
    }

    companion object {
        const val KEY = "turn_create_journal_v1"
        const val MAX_RECORDS = 64
    }
}

/** The router asked for a new conversation and the Phone could not provide one. Nothing was delivered. */
class RecipientCreateException(val reason: String, message: String) : HermesException(message)

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
    private val journal = TurnCreateJournal(registry.store)
    private val createLock = Mutex()

    /** What the journal knows about [turnId], if it ever asked for a new conversation. */
    fun turnCreateRecord(turnId: String): TurnCreateRecord? = journal.find(turnId)

    /**
     * Creates the conversation a voice turn's router asked for, at most once per [turnId]:
     * serialized, journalled before and after the server call, and registered only after the
     * created row is read back with this app's source and not archived. A repeated call for the
     * same turn returns the same conversation. If an earlier attempt may or may not have created a
     * session (the app stopped, or the connection failed, between the server call and the journal
     * entry), it fails closed as `create_ambiguous` rather than create a second one: the server
     * offers no idempotency key or lookup for `session.create`. The alias is the router's
     * suggestion, or that plus a suffix derived from the turn id when it is already in use; an
     * existing conversation is never reused in its place.
     */
    suspend fun createForTurn(turnId: String, intent: CreateIntent, ackText: String): OwnedSession = createLock.withLock {
        journal.find(turnId)?.let { record ->
            if (record.state == TurnCreateState.PENDING) {
                throw RecipientCreateException("create_ambiguous",
                    "create_ambiguous: a new conversation may already have been created for this request; not creating another")
            }
            return@withLock registerCreated(record)
        }
        val active = registry.all().filter { it.role == OwnedRole.CONVERSATION && !it.archived }
        if (active.size >= DestinationAllowlist.MAX_ENTRIES) {
            throw RecipientCreateException("create_limit", "create_limit: there are already ${DestinationAllowlist.MAX_ENTRIES} conversations")
        }
        val alias = uniqueAlias(intent.alias, turnId, active.map { it.alias }.toSet())
        val title = intent.title.trim().take(MAX_TITLE_CHARS)
        val pending = TurnCreateRecord(turnId, TurnCreateState.PENDING, title, alias, intent.description, ackText)
        journal.put(pending)
        val created = try {
            conversations.create(AppSources.CONVERSATION, title, CONVERSATION_SEED, hidden = false)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Only an answer from the server (an RPC error) or a failure before anything was sent
            // (no sign-in) proves nothing was created; otherwise the journal entry stays PENDING.
            if (error is HermesRpcException || error is HermesAuthRequiredException) {
                journal.remove(turnId)
                if (error is HermesAuthRequiredException) throw error
                throw RecipientCreateException("create_failed", "create_failed: the dashboard refused to create a conversation")
            }
            throw RecipientCreateException("create_ambiguous", "create_ambiguous: creating a conversation did not finish; not retrying")
        }
        val record = pending.copy(state = TurnCreateState.CREATED, storedSessionId = created.storedSessionId, createdAtMs = clock())
        journal.put(record)
        registerCreated(record)
    }

    /** The [RecipientCreator] the voice orchestrator uses: creation only ever goes through this repository. */
    fun recipientCreator(): RecipientCreator = object : RecipientCreator {
        override fun previous(turnId: String): PriorCreate? = journal.find(turnId)?.let {
            PriorCreate(CreateIntent(it.title, it.alias, it.description), it.ackText, it.state == TurnCreateState.SUBMITTED)
        }

        override suspend fun create(turnId: String, intent: CreateIntent, ackText: String): DestinationEntry =
            createForTurn(turnId, intent, ackText).let { DestinationEntry(it.alias, it.storedSessionId, it.description.ifBlank { it.title }) }

        override fun markSubmitted(turnId: String): Boolean = markTurnSubmitted(turnId)
    }

    /** Must be called right before the turn's transcript is submitted; false if it already was. */
    fun markTurnSubmitted(turnId: String): Boolean {
        val record = journal.find(turnId) ?: return true
        if (record.state != TurnCreateState.CREATED) return false
        journal.put(record.copy(state = TurnCreateState.SUBMITTED))
        return true
    }

    /** Registers a journalled created session once the server row proves it is ours, visible and active. */
    private suspend fun registerCreated(record: TurnCreateRecord): OwnedSession {
        registry.find(record.storedSessionId)?.let { existing ->
            if (existing.role != OwnedRole.CONVERSATION || existing.archived) {
                throw RecipientCreateException("create_unavailable", "create_unavailable: the conversation created for this request is no longer available")
            }
            return existing
        }
        if (!DestinationAllowlist.isValidSessionId(record.storedSessionId)) {
            throw RecipientCreateException("create_unverified", "create_unverified: the dashboard returned an unusable session id")
        }
        val row = api.getSession(record.storedSessionId)
        if (row == null || row.id != record.storedSessionId || row.source != AppSources.CONVERSATION || row.archived) {
            throw RecipientCreateException("create_unverified", "create_unverified: the new conversation could not be verified on the dashboard")
        }
        synchronized(verifiedSources) { verifiedSources += "${AppSources.CONVERSATION}|${row.id}" }
        return OwnedSession(row.id, OwnedRole.CONVERSATION, record.title, record.alias, record.description,
            archived = false, createdAtMs = record.createdAtMs).also(registry::put)
    }

    private fun uniqueAlias(suggested: String, turnId: String, taken: Set<String>): String {
        if (suggested !in taken) return suggested
        val digest = MessageDigest.getInstance("SHA-256").digest(turnId.toByteArray()).joinToString("") { "%02x".format(it) }
        for (length in listOf(4, 8, 16)) {
            val suffix = "-" + digest.take(length)
            val candidate = suggested.take(32 - suffix.length) + suffix
            if (candidate !in taken) return candidate
        }
        throw RecipientCreateException("create_alias", "create_alias: no free alias for the new conversation")
    }

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

    /** The voice router's allowlist: our unarchived conversations (possibly none: the router may then ask for one). */
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
