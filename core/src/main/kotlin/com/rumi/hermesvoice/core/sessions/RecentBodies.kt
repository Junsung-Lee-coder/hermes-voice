package com.rumi.hermesvoice.core.sessions

import com.rumi.hermesvoice.core.KeyValueStore
import com.rumi.hermesvoice.core.net.HistoryMessage
import com.rumi.hermesvoice.core.net.HistoryPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Display only. No registry, token, alias, acceptance or notification acknowledgement lives here.
 * A page is kept whole or not kept: clipping a body or discarding a hidden raw row would corrupt
 * canonical text or the older-page cursor. All disk writes are serialized away from first paint.
 */
class RecentBodies(
    private val store: KeyValueStore,
    private val key: String,
    private val namespace: () -> String?,
    private val scope: CoroutineScope,
    private val onWriteFailure: () -> Unit = {},
) {
    data class Saved(val page: HistoryPage, val hasOlder: Boolean)
    private data class Snapshot(val namespace: String, val epoch: Long, val rows: List<Saved>)
    private val lock = Any()
    private val writes = Mutex()
    private var epoch = 0L
    private var serial = 0L
    @Volatile private var hint: Snapshot? = null
    @Volatile private var blocked = false
    private val revoked = HashSet<String>()
    private var revokedNamespace: String? = null
    private var revocationSerial = 0L

    private fun readRevocations(ns: String) = synchronized(lock) {
        if (revokedNamespace == ns) return@synchronized
        revoked.clear()
        runCatching {
            val root = JSONObject(store.getString(key + "_revoked") ?: "{}")
            if (root.optString("scope") == ns) root.optJSONArray("ids")?.let { ids ->
                for (i in 0 until minOf(ids.length(), 1000)) revoked += ids.getString(i)
            }
        }.onFailure { blocked = true; hint = null; onWriteFailure() }
        revokedNamespace = ns
    }

    private fun saveRevocations(ns: String) {
        val version: Long
        val ids: List<String>
        synchronized(lock) { version = ++revocationSerial; ids = revoked.toList() }
        scope.launch(Dispatchers.IO) { writes.withLock {
            if (namespace() != ns || synchronized(lock) { revocationSerial != version }) return@withLock
            val ok = try { store.commitString(key + "_revoked", JSONObject().put("scope", ns).put("ids", JSONArray(ids)).toString()) } catch (_: Exception) { false }
            if (!ok) onWriteFailure()
        } }
    }

    fun load(sessionId: String): Saved? {
        val ns = namespace() ?: return null
        readRevocations(ns)
        if (synchronized(lock) { sessionId in revoked }) return null
        val version = synchronized(lock) { epoch }
        val state = hint?.takeIf { it.namespace == ns && it.epoch == version }
            ?: if (blocked) return null else decode(ns, version)
        return state?.rows?.firstOrNull { it.page.sessionId == sessionId }
            ?.takeIf { namespace() == ns && synchronized(lock) { epoch == version } }
    }

    /** A successful authoritative page, already checked by its caller's original scope guard. */
    fun remember(page: HistoryPage, hasOlder: Boolean, current: () -> Boolean = { true }) {
        val ns = namespace() ?: return
        readRevocations(ns)
        if (!current() || page.messages.size > MAX_MESSAGES || page.returned !in 0..MAX_RAW_ROWS) return
        if (page.messages.any { it.rowId < 0 || it.role !in setOf("user", "assistant") }) return
        val immutable = Saved(page.copy(messages = page.messages.toList(), rawIds = page.rawIds.toList()), hasOlder)
        if (encode(ns, listOf(immutable)).toByteArray(Charsets.UTF_8).size > MAX_BYTES) { onWriteFailure(); return }
        val snapshot: Snapshot
        val request: Long
        synchronized(lock) {
            if (!current() || namespace() != ns || page.sessionId in revoked) return
            val old = hint?.takeIf { it.namespace == ns && it.epoch == epoch }
                ?: if (blocked) null else decode(ns, epoch)
            var rows = old?.rows.orEmpty().filterNot { it.page.sessionId == page.sessionId } + immutable
            rows = rows.takeLast(MAX_CONVERSATIONS)
            while (rows.isNotEmpty() && encode(ns, rows).toByteArray(Charsets.UTF_8).size > MAX_BYTES) rows = rows.drop(1)
            snapshot = Snapshot(ns, epoch, rows)
            hint = snapshot
            request = ++serial
        }
        scope.launch(Dispatchers.IO) {
            writes.withLock {
                if (!valid(snapshot, request) || !current()) return@withLock
                val ok = try { store.commitString(key, encode(ns, snapshot.rows)) } catch (_: Exception) { false }
                if (!ok && valid(snapshot, request)) onWriteFailure()
            }
        }
    }

    /** In-memory tombstone is immediate; the queued clear follows any already-entered disk write. */
    fun invalidate() {
        val fence = synchronized(lock) { blocked = true; hint = null; ++epoch to ++serial }
        scope.launch(Dispatchers.IO) {
            writes.withLock {
                if (synchronized(lock) { epoch != fence.first || serial != fence.second }) return@withLock
                val ok = try { store.commitString(key, "{}") } catch (_: Exception) { false }
                if (!ok) onWriteFailure()
            }
        }
    }

    /** Explicit server eligibility/removal, not absence in an unfinished scan. */
    fun evict(sessionId: String) {
        val ns = namespace() ?: return
        readRevocations(ns)
        val snapshot: Snapshot
        val request: Long
        synchronized(lock) {
            revoked += sessionId
            val old = hint?.takeIf { it.namespace == ns && it.epoch == epoch } ?: if (blocked) null else decode(ns, epoch)
            snapshot = Snapshot(ns, epoch, old?.rows.orEmpty().filterNot { it.page.sessionId == sessionId })
            hint = snapshot
            request = ++serial
        }
        saveRevocations(ns)
        scope.launch(Dispatchers.IO) { writes.withLock {
            if (!valid(snapshot, request)) return@withLock
            val ok = try { store.commitString(key, encode(ns, snapshot.rows)) } catch (_: Exception) { false }
            if (!ok) onWriteFailure()
        } }
    }

    /** Only a newly checked eligible caller can re-admit an evicted entry. */
    fun eligible(sessionId: String) {
        val ns = namespace() ?: return
        readRevocations(ns)
        if (synchronized(lock) { revoked.remove(sessionId) }) saveRevocations(ns)
    }

    private fun valid(state: Snapshot, request: Long): Boolean =
        namespace() == state.namespace && synchronized(lock) { epoch == state.epoch && serial == request }

    private fun decode(ns: String, version: Long): Snapshot? = runCatching {
        val raw = store.getString(key) ?: return null
        if (raw.length > MAX_BYTES || raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
        val root = JSONObject(raw)
        if (root.optInt("v") != 1 || root.optString("scope") != ns) return null
        val rows = root.getJSONArray("pages")
        if (rows.length() > MAX_CONVERSATIONS) return null
        val seen = HashSet<String>()
        val saved = (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val id = row.getString("id")
            require(com.rumi.hermesvoice.core.voice.DestinationAllowlist.isValidSessionId(id) && seen.add(id))
            val messages = row.getJSONArray("messages");require(messages.length() <= MAX_MESSAGES)
            val ids = HashSet<Long>()
            val body = (0 until messages.length()).map { j ->
                val m = messages.getJSONObject(j);val rid = m.getLong("id");val role = m.getString("role")
                require(rid >= 0 && ids.add(rid) && role in setOf("user", "assistant"))
                HistoryMessage(rid, role, m.getString("text"), m.optDouble("timestamp", 0.0).takeIf { it.isFinite() } ?: 0.0, m.optBoolean("truncated"),
                    m.optString("uid"), m.optString("finish").takeIf { it.isNotBlank() }, m.optBoolean("tools"))
            }
            val returned = row.getInt("returned");require(returned in 0..MAX_RAW_ROWS)
            val rawIds = row.getJSONArray("raw_ids");require(rawIds.length() <= MAX_RAW_ROWS)
            Saved(HistoryPage(id, body.filterNot { it.silentControl }, returned, (0 until rawIds.length()).map { rawIds.getLong(it) }), row.getBoolean("has_older"))
        }
        Snapshot(ns, version, saved)
    }.getOrNull()

    private fun encode(ns: String, rows: List<Saved>): String = JSONObject().put("v", 1).put("scope", ns)
        .put("pages", JSONArray().apply { rows.forEach { saved ->
            val page = saved.page
            put(JSONObject().put("id", page.sessionId).put("returned", page.returned).put("raw_ids", JSONArray(page.rawIds))
                .put("has_older", saved.hasOlder).put("messages", JSONArray().apply { page.messages.forEach { m ->
                    put(JSONObject().put("id", m.rowId).put("role", m.role).put("text", m.text).put("truncated", m.truncated).put("timestamp", m.timestamp.takeIf { it.isFinite() } ?: 0.0)
                        .put("uid", m.messageUid).put("finish", m.finishReason ?: "").put("tools", m.hasToolCalls))
                } }))
        } }).toString()

    companion object {
        const val MAX_CONVERSATIONS = 4
        const val MAX_MESSAGES = 50
        const val MAX_RAW_ROWS = 500
        const val MAX_BYTES = 1_048_576
    }
}
