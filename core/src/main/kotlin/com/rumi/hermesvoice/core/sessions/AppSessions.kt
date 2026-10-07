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
import com.rumi.hermesvoice.core.net.SessionRuntimeException
import com.rumi.hermesvoice.core.net.SessionRuntimeSpec
import com.rumi.hermesvoice.core.net.StoredSession
import com.rumi.hermesvoice.core.net.StoredSessionPage
import com.rumi.hermesvoice.core.net.SubmittedTurn
import com.rumi.hermesvoice.core.voice.CreateIntent
import com.rumi.hermesvoice.core.voice.CreatedRecipient
import com.rumi.hermesvoice.core.voice.DestinationAllowlist
import com.rumi.hermesvoice.core.voice.DestinationEntry
import com.rumi.hermesvoice.core.voice.PriorCreate
import com.rumi.hermesvoice.core.voice.RecipientCreator
import com.rumi.hermesvoice.core.voice.RoutingContract
import com.rumi.hermesvoice.core.voice.TextSanitizer
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/**
 * The model the hidden routing session runs on: a fast, inexpensive one at low reasoning, for that
 * session ONLY (never a recipient conversation, a conversation made by hand, or the profile's
 * default). `gpt-5.6-luna` on `openai-codex` is the Luna slug the installed Hermes catalog lists
 * (hermes_cli/codex_models.py, models_catalog_static.py); it is applied through tui_gateway's own
 * per-session mechanisms ([SessionRuntimeSpec]) and checked by reading the session back.
 */
object RouterRuntime {
    val LUNA_LOW = SessionRuntimeSpec(model = "gpt-5.6-luna", provider = "openai-codex", reasoningEffort = "low")
}

/** The routing session's model could not be set or proven: nothing was routed or sent (no fallback to another model). */
class RouterModelException(val reason: String, message: String) : HermesException(message)

/**
 * The FIRST routing session could not be created (no earlier one to keep): nothing was registered, routed or sent.
 * [reason] is router_create_refused (the gateway answered with an error code), router_create_unknown (no answer: it may
 * still have been created) or router_create_failed; the message is written by the app, never the gateway's own text.
 */
class RouterCreateException(val reason: String, message: String) : HermesException(message)

/**
 * One session this app created, by its exact stored id. [alias] is how the voice router may name
 * it. For the routing session, [contract] is the routing contract version its hidden seed was
 * written for, and [retired] marks one that was replaced by a newer one (kept: its history stays
 * on the dashboard and it is still this app's, but it is never used again).
 */
data class OwnedSession(
    val storedSessionId: String,
    val role: OwnedRole,
    val title: String,
    val alias: String,
    val description: String,
    val archived: Boolean,
    val createdAtMs: Long,
    val contract: Int = 1,
    val retired: Boolean = false,
    /** For the routing session: the [SessionRuntimeSpec.key] verified on it, "" before that. */
    val modelSpec: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("id", storedSessionId).put("role", role.name).put("title", title)
        .put("alias", alias).put("description", description).put("archived", archived).put("created_at_ms", createdAtMs)
        .put("contract", contract).put("retired", retired).put("model_spec", modelSpec)

    companion object {
        /** Strict on what identifies a session: a missing or blank id, or an unknown role, is a corrupt entry. */
        fun fromJson(json: JSONObject): OwnedSession = OwnedSession(
            storedSessionId = json.getString("id").also { require(it.isNotBlank()) { "blank id" } },
            role = OwnedRole.valueOf(json.getString("role")),
            title = json.optString("title"),
            alias = json.optString("alias"),
            description = json.optString("description"),
            archived = json.optBoolean("archived"),
            createdAtMs = json.optLong("created_at_ms"),
            contract = json.optInt("contract", 1),
            retired = json.optBoolean("retired"),
            modelSpec = json.optString("model_spec"),
        )
    }
}

/**
 * The Phone's saved session data could not be read or written safely. Everything that depends on
 * it fails closed: nothing is created, acknowledged or sent, and the saved bytes are left as
 * they are. [reason] is `store_corrupt` or `store_write_failed`.
 */
class LocalStoreException(val reason: String, message: String) : HermesException(message)

/**
 * A JSON list under one key with an explicit durability boundary:
 * - no value at all is an empty list; a value that is not a JSON array of objects is CORRUPT:
 *   [read] throws and nothing is ever written over it;
 * - [write] uses [KeyValueStore.commitString] and succeeds only when that reports the data is on
 *   storage. After a failed write the in-memory copy may already show the new value, so the list
 *   is poisoned for the rest of the process: every later read and write throws, and only a fresh
 *   process, which reads what really reached storage, uses it again.
 * Writes block; callers run them off the main thread.
 */
internal class DurableList(private val store: KeyValueStore, private val key: String, private val what: String) {
    @Volatile private var writeFailed = false

    @Synchronized
    fun read(): List<JSONObject> {
        if (writeFailed) throw failed()
        val raw = store.getString(key) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.get(it) as? JSONObject ?: throw JSONException("not an object") }
        } catch (_: JSONException) {
            throw corrupt()
        }
    }

    @Synchronized
    fun write(items: List<JSONObject>) {
        if (writeFailed) throw failed()
        if (!store.commitString(key, JSONArray().apply { items.forEach { put(it) } }.toString())) {
            writeFailed = true
            throw failed()
        }
    }

    fun corrupt() = LocalStoreException("store_corrupt",
        "store_corrupt: the saved $what on this phone can't be read; it was left untouched and nothing was sent")

    private fun failed() = LocalStoreException("store_write_failed",
        "store_write_failed: the $what couldn't be saved on this phone; nothing was sent. Restart the app and try again")
}

/**
 * The exact stored session ids created by this install, persisted locally. `source` on the server
 * is only a categorization filter; this registry is what makes a session "ours". A session id
 * that is not in here is never listed, read, archived or submitted to. An unreadable registry
 * throws [LocalStoreException] and is never replaced; writes are durable or throw.
 */
class OwnedSessionRegistry(internal val store: KeyValueStore) {
    private val list = DurableList(store, KEY, "conversation list")

    @Synchronized
    fun all(): List<OwnedSession> = list.read().map {
        try {
            OwnedSession.fromJson(it)
        } catch (_: JSONException) {
            throw list.corrupt()
        } catch (_: IllegalArgumentException) {
            throw list.corrupt()
        }
    }

    fun find(storedSessionId: String): OwnedSession? = all().firstOrNull { it.storedSessionId == storedSessionId }

    /** The routing session in use (never a retired one). */
    fun router(): OwnedSession? = all().firstOrNull { it.role == OwnedRole.ROUTER && !it.retired }

    @Synchronized
    fun put(session: OwnedSession) = replaceAll { all -> all.filterNot { it.storedSessionId == session.storedSessionId } + session }

    @Synchronized
    fun update(storedSessionId: String, transform: (OwnedSession) -> OwnedSession): OwnedSession {
        val current = find(storedSessionId) ?: throw SessionNotOwnedException("session was not created by this app")
        return transform(current).also { require(it.storedSessionId == storedSessionId); put(it) }
    }

    /** One durable write of the whole list: either all of [transform]'s changes are saved or none. */
    @Synchronized
    fun replaceAll(transform: (List<OwnedSession>) -> List<OwnedSession>) = list.write(transform(all()).map { it.toJson() })

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
    val storedSessionId: String = "",
    val createdAtMs: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().put("turn_id", turnId).put("state", state.name).put("title", title)
        .put("alias", alias).put("description", description).put("id", storedSessionId).put("created_at_ms", createdAtMs)

    companion object {
        fun fromJson(json: JSONObject): TurnCreateRecord = TurnCreateRecord(
            json.getString("turn_id").also { require(it.isNotBlank()) { "blank turn id" } },
            TurnCreateState.valueOf(json.getString("state")), json.optString("title"), json.optString("alias"),
            json.optString("description"), json.optString("id"), json.optLong("created_at_ms"))
    }
}

/**
 * Durable journal of voice turns that created a conversation, kept next to the registry (same
 * dashboard and profile). It is what makes a replayed turn id create at most one session and
 * submit its transcript at most once, across a process restart. It holds no transcript. Every
 * write is committed to storage or throws; an unreadable journal throws and is never replaced.
 */
class TurnCreateJournal(store: KeyValueStore) {
    private val list = DurableList(store, KEY, "record of created conversations")

    @Synchronized
    fun all(): List<TurnCreateRecord> = list.read().map {
        try {
            TurnCreateRecord.fromJson(it)
        } catch (_: JSONException) {
            throw list.corrupt()
        } catch (_: IllegalArgumentException) {
            throw list.corrupt()
        }
    }

    fun find(turnId: String): TurnCreateRecord? = all().firstOrNull { it.turnId == turnId }

    @Synchronized
    fun put(record: TurnCreateRecord) =
        list.write((all().filterNot { it.turnId == record.turnId } + record).takeLast(MAX_RECORDS).map { it.toJson() })

    @Synchronized
    fun remove(turnId: String) = list.write(all().filterNot { it.turnId == turnId }.map { it.toJson() })

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
 *
 * Everything that changes the registry or the journal (a conversation the router asked for, one
 * made by hand, a rename, archive and unarchive, the routing session) runs under ONE lock, so an
 * alias is checked and registered without anything else changing in between, and the limit of
 * [DestinationAllowlist.MAX_ENTRIES] active conversations holds. Writes are durable
 * ([KeyValueStore.commitString], on [io]) and their failure is an error, never ignored.
 */
class AppSessionRepository(
    private val api: HermesSessionsApi,
    private val conversations: HermesConversationPort,
    val registry: OwnedSessionRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** The routing session's own model ([RouterRuntime]); null leaves it on the profile's default. */
    private val routerRuntime: SessionRuntimeSpec? = RouterRuntime.LUNA_LOW,
    /** Metadata-only diagnostics (never text or ids): the router model's state. */
    private val diagnostic: (String) -> Unit = {},
) {
    private val verifiedSources = HashSet<String>()
    private val journal = TurnCreateJournal(registry.store)
    private val lock = Mutex()

    /** What the journal knows about [turnId], if it ever asked for a new conversation. */
    fun turnCreateRecord(turnId: String): TurnCreateRecord? = journal.find(turnId)

    private fun active(all: List<OwnedSession> = registry.all()) = all.filter { it.role == OwnedRole.CONVERSATION && !it.archived }

    /** [storedSessionId] if it is one of this app's active conversations (e.g. a routed turn's destination to open), else null. */
    fun activeConversation(storedSessionId: String): OwnedSession? = active().firstOrNull { it.storedSessionId == storedSessionId }

    /**
     * Creates the conversation a voice turn's router asked for, at most once per [turnId], in
     * this order, each step only after the one before it is safely done:
     * 1. the journal entry PENDING is committed to storage (if that fails, nothing is asked of the server);
     * 2. `session.create`;
     * 3. CREATED with the server's id is committed (if that fails, the turn ends; after a restart
     *    the entry still reads PENDING and the turn stays ambiguous);
     * 4. the created row is read back (this app's source, not archived) and the registry entry is
     *    committed (if that fails, the turn ends; after a restart it resumes from CREATED).
     * A repeated call for the same turn returns the same conversation. If an earlier attempt may or
     * may not have created a session, it fails closed as `create_ambiguous` rather than create a
     * second one: the server offers no idempotency key or lookup for `session.create`. The alias
     * is the router's suggestion, or that plus a suffix derived from the turn id when it is
     * already in use; an existing conversation is never reused in its place.
     */
    suspend fun createForTurn(turnId: String, intent: CreateIntent): OwnedSession = lock.withLock {
        journal.find(turnId)?.let { record ->
            if (record.state == TurnCreateState.PENDING) {
                throw RecipientCreateException("create_ambiguous",
                    "create_ambiguous: a new conversation may already have been created for this request; not creating another")
            }
            return@withLock registerCreated(record)
        }
        val current = active()
        if (current.size >= DestinationAllowlist.MAX_ENTRIES) {
            throw RecipientCreateException("create_limit", "create_limit: there are already ${DestinationAllowlist.MAX_ENTRIES} conversations")
        }
        val alias = uniqueAlias(intent.alias, turnId, current.map { it.alias }.toSet())
        val title = TextSanitizer.clean(intent.title).take(MAX_TITLE_CHARS)
        val pending = TurnCreateRecord(turnId, TurnCreateState.PENDING, title, alias, DestinationAllowlist.cleanDescription(intent.description))
        withContext(io) { journal.put(pending) }
        val created = try {
            conversations.create(AppSources.CONVERSATION, title, CONVERSATION_SEED, hidden = false)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Only an answer from the server (an RPC error) or a failure before anything was sent
            // (no sign-in) proves nothing was created; otherwise the journal entry stays PENDING.
            if (error is HermesRpcException || error is HermesAuthRequiredException) {
                withContext(io) { journal.remove(turnId) }
                if (error is HermesAuthRequiredException) throw error
                throw RecipientCreateException("create_failed", "create_failed: the dashboard refused to create a conversation")
            }
            throw RecipientCreateException("create_ambiguous", "create_ambiguous: creating a conversation did not finish; not retrying")
        }
        val record = pending.copy(state = TurnCreateState.CREATED, storedSessionId = created.storedSessionId, createdAtMs = clock())
        withContext(io) { journal.put(record) }
        registerCreated(record)
    }

    /** The [RecipientCreator] the voice orchestrator uses: creation only ever goes through this repository. */
    fun recipientCreator(): RecipientCreator = object : RecipientCreator {
        override fun previous(turnId: String): PriorCreate? = journal.find(turnId)?.let {
            PriorCreate(CreateIntent(it.title, it.alias, it.description), it.state == TurnCreateState.SUBMITTED)
        }

        override suspend fun create(turnId: String, intent: CreateIntent): CreatedRecipient = createForTurn(turnId, intent).let {
            CreatedRecipient(DestinationEntry(it.alias, it.storedSessionId, it.description.ifBlank { it.title }), it.title)
        }

        override suspend fun requireDeliverable(storedSessionId: String) = this@AppSessionRepository.requireDeliverable(storedSessionId)

        override suspend fun markSubmitted(turnId: String): Boolean = markTurnSubmitted(turnId)
    }

    /**
     * Must be called right before the turn's transcript is submitted; false if it already was.
     * SUBMITTED is committed to storage first: if that fails it throws and nothing may be submitted.
     */
    suspend fun markTurnSubmitted(turnId: String): Boolean = lock.withLock {
        val record = journal.find(turnId) ?: return@withLock true
        if (record.state != TurnCreateState.CREATED) return@withLock false
        withContext(io) { journal.put(record.copy(state = TurnCreateState.SUBMITTED)) }
        true
    }

    /**
     * Checked right before a voice transcript is submitted, after the acknowledgement played: the
     * destination must still be this app's conversation, not archived here or on the dashboard.
     */
    suspend fun requireDeliverable(storedSessionId: String) {
        val owned = registry.find(storedSessionId)
        if (owned == null || owned.role != OwnedRole.CONVERSATION) throw SessionNotOwnedException("session was not created by this app")
        if (owned.archived) throw SessionNotOwnedException("the conversation '${owned.alias}' was archived; nothing was sent")
        val row = api.getSession(storedSessionId) ?: throw SessionNotOwnedException("session no longer exists on the dashboard")
        if (row.id != storedSessionId || row.source != AppSources.CONVERSATION) {
            throw SessionNotOwnedException("session is not tagged with this app's source")
        }
        if (row.archived) throw SessionNotOwnedException("the conversation '${owned.alias}' was archived; nothing was sent")
    }

    /**
     * Registers a journalled created session once the server row proves it is ours, visible and
     * active; the alias is checked again at this point. Called with [lock] held.
     */
    private suspend fun registerCreated(record: TurnCreateRecord): OwnedSession {
        val all = registry.all()
        all.firstOrNull { it.storedSessionId == record.storedSessionId }?.let { existing ->
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
        val current = active(all)
        if (current.size >= DestinationAllowlist.MAX_ENTRIES) {
            throw RecipientCreateException("create_limit", "create_limit: there are already ${DestinationAllowlist.MAX_ENTRIES} conversations")
        }
        // After a restart the journalled alias may have been taken meanwhile: never register a duplicate.
        val alias = uniqueAlias(record.alias, record.turnId, current.map { it.alias }.toSet())
        val session = OwnedSession(row.id, OwnedRole.CONVERSATION, record.title, alias, record.description,
            archived = false, createdAtMs = record.createdAtMs)
        withContext(io) { registry.put(session) }
        synchronized(verifiedSources) { verifiedSources += "${AppSources.CONVERSATION}|${row.id}" }
        return session
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

    /**
     * A conversation made by hand. Its alias must be free: a clash is refused (a conversation the
     * router asks for gets a suffix instead). Titles need not be unique; the alias tells two
     * conversations with the same title apart.
     */
    suspend fun createConversation(title: String, alias: String, description: String): OwnedSession = lock.withLock {
        val cleanTitle = TextSanitizer.clean(title).ifEmpty { "Hermes Voice" }.take(MAX_TITLE_CHARS)
        val cleanAlias = requireNotNull(DestinationAllowlist.normalizeAlias(alias)) {
            "alias must be 1-32 characters: lowercase letters, digits, '-' or '_'"
        }
        val cleanDescription = DestinationAllowlist.cleanDescription(description)
        require(cleanDescription.length <= DestinationAllowlist.MAX_DESCRIPTION_CHARS) { "description is too long" }
        requireAliasFree(cleanAlias, exceptSessionId = null)
        requireRoom()
        val created = conversations.create(AppSources.CONVERSATION, cleanTitle, CONVERSATION_SEED, hidden = false)
        val session = OwnedSession(created.storedSessionId, OwnedRole.CONVERSATION, cleanTitle, cleanAlias, cleanDescription,
            archived = false, createdAtMs = clock())
        withContext(io) { registry.put(session) }
        session
    }

    /**
     * The persistent routing session: created hidden with the router source on first use, then
     * reused. One whose seed was written for an older routing contract is replaced ONCE by a new
     * hidden session seeded for the current contract:
     * - a marker is committed before the new session is created; if the app stops before the
     *   result is saved, the marker stays and the replacement is not tried again (whether a session
     *   was created is unknown), so the old routing session keeps being used. Every prompt restates
     *   the current contract, so that still works;
     * - the new row must read back with the router source; then ONE registry write retires the old
     *   entry and adds the new one with its contract version. The old session is not deleted: its
     *   history stays on the dashboard and it remains this app's.
     */
    suspend fun ensureRoutingSession(): OwnedSession = lock.withLock {
        val router = routingSessionLocked()
        val spec = routerRuntime ?: return@withLock router
        if (router.modelSpec == spec.key) return@withLock router
        // Made by an earlier build, or created here without a provable model: switch THIS session only, read back.
        val switched = try {
            conversations.ensureRuntime(router.storedSessionId, spec)
        } catch (error: SessionRuntimeException) {
            // The detail says whether a switch was sent (then its effect may exist); the request itself never was.
            diagnostic("router_model=failed reason=${error.reason} stage=${error.stage} write=${error.write}")
            throw RouterModelException(error.reason, "router model ${spec.model} (${spec.reasoningEffort} reasoning) could not be set on the " +
                "routing session (${error.reason}): ${error.message}; your request was not sent")
        } catch (error: HermesRpcException) {
            diagnostic("router_model=failed reason=rpc_${error.code} write=none")
            throw RouterModelException("rpc_${error.code}", "router model ${spec.model} could not be set on the routing session " +
                "(code ${error.code}); your request was not sent")
        } catch (error: HermesAuthRequiredException) {
            throw error
        } catch (error: HermesException) {
            // A connection that failed some other way: the same visible error, tried again on the next request.
            diagnostic("router_model=failed reason=${error.javaClass.simpleName}")
            throw RouterModelException("transport", "router model ${spec.model} could not be set on the routing session " +
                "(${error.javaClass.simpleName}); your request was not sent")
        }
        diagnostic("router_model=${if (switched.switched) "switched" else "already"} spec=${spec.key} build_polls=${switched.polls} setup_ms=${switched.waitedMs}")
        withContext(io) { registry.update(router.storedSessionId) { it.copy(modelSpec = spec.key) } }
    }

    /** The routing session to use (made or replaced here when needed), before its model is checked. Called with [lock] held. */
    private suspend fun routingSessionLocked(): OwnedSession {
        val current = registry.router()
        if (current != null && (current.contract >= RoutingContract.VERSION || routerMigrationPending())) return current
        if (current != null) {
            val saved = withContext(io) { registry.store.commitString(KEY_ROUTER_MIGRATION, RoutingContract.VERSION.toString()) }
            if (!saved) throw LocalStoreException("store_write_failed",
                "store_write_failed: the routing session update couldn't be saved on this phone; nothing was sent")
        }
        val spec = routerRuntime
        val created = try {
            if (spec != null) conversations.create(AppSources.ROUTER, "Hermes Voice router", RoutingContract.ROUTER_SEED, hidden = true, spec)
            else conversations.create(AppSources.ROUTER, "Hermes Voice router", RoutingContract.ROUTER_SEED, hidden = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Replacing an old routing session is optional: keep the old one (the marker stops retries).
            if (current != null) return current
            if (error is HermesAuthRequiredException) throw error
            throw routerCreateFailure(error)
        }
        if (current != null) {
            // A replacement is adopted only if its row reads back as this app's routing session; otherwise the old one stays.
            val row = runCatching { api.getSession(created.storedSessionId) }.getOrNull()
            if (row == null || row.id != created.storedSessionId || row.source != AppSources.ROUTER) return current
            synchronized(verifiedSources) { verifiedSources += "${AppSources.ROUTER}|${row.id}" }
        }
        // The create reported the pinned model back: recorded. Otherwise it is switched and read back before first use.
        val pinned = spec != null && created.model == spec.model && (created.provider.isEmpty() || created.provider == spec.provider)
        val router = OwnedSession(created.storedSessionId, OwnedRole.ROUTER, "Hermes Voice router", "", "",
            archived = false, createdAtMs = clock(), contract = RoutingContract.VERSION, modelSpec = if (pinned) spec!!.key else "")
        if (pinned) diagnostic("router_model=created spec=${spec!!.key}")
        withContext(io) {
            registry.replaceAll { all ->
                all.map { if (it.role == OwnedRole.ROUTER && !it.retired) it.copy(retired = true) else it } + router
            }
        }
        return router
    }

    /**
     * The first routing session's create failed: named by the error's code or class only (the gateway's text can carry
     * paths or details that must not be shown). An unanswered create may still have happened on the server; nothing is
     * registered or retried here either way.
     */
    private fun routerCreateFailure(error: Exception): RouterCreateException {
        val (reason, detail) = when (error) {
            is HermesRpcException -> "router_create_refused" to "the gateway refused creating it (code ${error.code})"
            is HermesException, is java.io.IOException -> "router_create_unknown" to
                "no answer came (${error.javaClass.simpleName}), so whether the gateway created it is unknown; nothing was registered"
            else -> "router_create_failed" to "creating it failed (${error.javaClass.simpleName}); nothing was registered"
        }
        diagnostic("router_create=failed reason=$reason")
        return RouterCreateException(reason, "the routing session could not be created ($reason): $detail; your request was not sent")
    }

    /** A replacement of the routing session was started for this contract version and never recorded as done. */
    private fun routerMigrationPending(): Boolean = registry.store.getString(KEY_ROUTER_MIGRATION) == RoutingContract.VERSION.toString()

    /** Only rows that carry the app source AND whose exact id this install created. The router never appears. */
    suspend fun listConversations(archived: Boolean): List<AppConversation> {
        if (registry.all().none { it.role == OwnedRole.CONVERSATION }) return emptyList()
        val rows = ArrayList<StoredSession>()
        var offset = 0
        for (page in 0 until MAX_LIST_PAGES) {
            val result = api.listSessions(AppSources.CONVERSATION, if (archived) ArchivedFilter.ONLY else ArchivedFilter.EXCLUDE,
                limit = PAGE_SIZE, offset = offset)
            rows += result.sessions
            offset += result.sessions.size
            if (result.sessions.size < PAGE_SIZE || offset >= result.total) break
        }
        return lock.withLock {
            val owned = registry.all().filter { it.role == OwnedRole.CONVERSATION }.associateBy { it.storedSessionId }
            rows.filter { it.source == AppSources.CONVERSATION }.mapNotNull { row ->
                val mine = owned[row.id] ?: return@mapNotNull null
                // The dashboard's archived flag wins, except that a conversation unarchived elsewhere whose
                // alias is now used by another one stays archived here until it is renamed.
                val synced = when {
                    mine.archived == row.archived -> mine
                    !row.archived && (aliasTaken(mine.alias, mine.storedSessionId) || active().size >= DestinationAllowlist.MAX_ENTRIES) -> mine
                    else -> withContext(io) { registry.update(row.id) { it.copy(archived = row.archived) } }
                }
                AppConversation(synced, row)
            }
        }
    }

    /** The stored rows of one of our conversations exactly as the server holds them (a voice request's row starts with its marker). */
    suspend fun history(storedSessionId: String, limit: Int = 50, offset: Int = 0): HistoryPage {
        verifyConversation(storedSessionId)
        return api.getMessages(storedSessionId, limit, offset)
    }

    /** Archives, or unarchives when the alias is still free and there is room; a clash is refused. */
    suspend fun setArchived(storedSessionId: String, archived: Boolean): OwnedSession = lock.withLock {
        verifyConversation(storedSessionId, forceServerCheck = true)
        if (!archived) {
            requireAliasFree(registry.find(storedSessionId)!!.alias, exceptSessionId = storedSessionId)
            if (registry.find(storedSessionId)!!.archived) requireRoom()
        }
        api.setArchived(storedSessionId, archived)
        withContext(io) { registry.update(storedSessionId) { it.copy(archived = archived) } }
    }

    suspend fun updateDestination(storedSessionId: String, alias: String, description: String): OwnedSession = lock.withLock {
        val owned = registry.find(storedSessionId)?.takeIf { it.role == OwnedRole.CONVERSATION }
            ?: throw SessionNotOwnedException("session was not created by this app")
        val cleanAlias = requireNotNull(DestinationAllowlist.normalizeAlias(alias)) { "invalid alias" }
        requireAliasFree(cleanAlias, exceptSessionId = storedSessionId)
        val cleanDescription = DestinationAllowlist.cleanDescription(description)
        require(cleanDescription.length <= DestinationAllowlist.MAX_DESCRIPTION_CHARS) { "description is too long" }
        withContext(io) { registry.update(owned.storedSessionId) { it.copy(alias = cleanAlias, description = cleanDescription) } }
    }

    /** Text chat (optionally with attachments) into one of our conversations. */
    suspend fun sendMessage(storedSessionId: String, text: String, attachments: List<OutgoingAttachment> = emptyList()): SubmittedTurn {
        require(text.isNotBlank() || attachments.isNotEmpty()) { "message is empty" }
        verifyConversation(storedSessionId)
        return conversations.submit(storedSessionId, text.trim(), attachments)
    }

    /** The voice router's allowlist: our unarchived conversations (possibly none: the router may then ask for one); no router for a routing-off turn. */
    fun allowlist(routingStoredSessionId: String?): DestinationAllowlist = DestinationAllowlist.create(
        active().map { DestinationEntry(it.alias, it.storedSessionId, it.description.ifBlank { TextSanitizer.clean(it.title) }) },
        routingStoredSessionId,
    )

    /**
     * A conversation port that only ever submits to sessions this app owns (the router included),
     * verified the same way as text chat, and never to an archived conversation or a retired
     * routing session. The voice orchestrator uses this, never the raw port.
     */
    fun guardedPort(): HermesConversationPort = object : HermesConversationPort {
        override suspend fun create(source: String, title: String, seedInstruction: String, hidden: Boolean) =
            throw UnsupportedOperationException("create sessions through AppSessionRepository")

        override suspend fun submit(storedSessionId: String, text: String, attachments: List<OutgoingAttachment>): SubmittedTurn {
            val owned = registry.find(storedSessionId) ?: throw SessionNotOwnedException("session was not created by this app")
            if (owned.role == OwnedRole.ROUTER) {
                if (owned.retired) throw SessionNotOwnedException("that routing session is no longer used")
                verifySource(storedSessionId, AppSources.ROUTER, false)
            } else {
                if (owned.archived) throw SessionNotOwnedException("the conversation '${owned.alias}' was archived; nothing was sent")
                verifyConversation(storedSessionId)
            }
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

    private fun aliasTaken(alias: String, exceptSessionId: String?): Boolean =
        active().any { it.alias == alias && it.storedSessionId != exceptSessionId }

    private fun requireAliasFree(alias: String, exceptSessionId: String?) =
        require(!aliasTaken(alias, exceptSessionId)) { "alias '$alias' is already used by another conversation" }

    private fun requireRoom() = require(active().size < DestinationAllowlist.MAX_ENTRIES) {
        "there are already ${DestinationAllowlist.MAX_ENTRIES} conversations; archive one first"
    }

    companion object {
        const val PAGE_SIZE = 100
        const val MAX_LIST_PAGES = 10
        const val MAX_TITLE_CHARS = 80

        /** Set (to the contract version) before an old routing session is replaced; see [ensureRoutingSession]. */
        const val KEY_ROUTER_MIGRATION = "router_migration_started"
        const val CONVERSATION_SEED =
            "This conversation was created by the Hermes Voice phone app. User turns may be voice transcripts " +
                "(speech-to-text, so expect recognition errors) and replies may be read aloud: prefer short, " +
                "plain-spoken answers without markdown tables or code unless asked."
    }
}
