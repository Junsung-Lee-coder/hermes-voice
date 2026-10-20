package com.rumi.hermesvoice.core.watchlink

import com.rumi.hermesvoice.core.KeyValueStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * The "not sent" final states the Phone owes to the Watch node that made the request. A state stays here until THAT node acknowledges
 * it ([WatchLinkPaths.STATE_ACK]); sending it over the Data Layer proves nothing about receipt. Only the turn id, the node and the
 * fixed status text are kept: never a recording or a transcript. Bounded, oldest first out.
 */
class WatchFailureOutbox(private val store: KeyValueStore, private val max: Int = MAX_ENTRIES) {
    init {
        require(max > 0)
    }

    data class Owed(val turnId: String, val nodeId: String, val detail: String)

    private val owed = LinkedHashMap<String, Owed>().also { map ->
        runCatching {
            val array = JSONArray(store.getString(KEY) ?: "[]")
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val entry = Owed(item.getString("turn"), item.getString("node"), item.getString("detail"))
                if (WatchLinkPaths.isValidTurnId(entry.turnId) && entry.nodeId.isNotBlank()) map[entry.turnId] = entry
            }
        }
        while (map.size > max) map.remove(map.keys.first())
    }

    /** True while the last write was refused: what is held in memory is newer than the file. */
    private var unsaved = false

    private fun save() {
        unsaved = !store.commitString(KEY, encode())
    }

    private fun retrySave() {
        if (unsaved) save()
    }

    /**
     * Records that [nodeId] is owed [detail] for [turnId]. True only when the file accepted it. When it did not, the entry is still owed
     * by this process (memory) and the write is retried on the next call here; it is not durable until a later call returns it saved.
     */
    @Synchronized
    fun add(turnId: String, nodeId: String, detail: String): Boolean {
        owed.remove(turnId)
        owed[turnId] = Owed(turnId, nodeId, detail.take(TurnStateMessage.MAX_DETAIL))
        while (owed.size > max) owed.remove(owed.keys.first())
        save()
        return !unsaved
    }

    /** What [nodeId] is still owed, oldest first (a refused earlier write is retried first). */
    @Synchronized
    fun owedTo(nodeId: String): List<Owed> {
        retrySave()
        return owed.values.filter { it.nodeId == nodeId }
    }

    /**
     * [nodeId] confirmed [turnId]; only the node the failure is owed to can settle it. True when something was settled. If the file
     * refuses the write the failure is still settled for this process and the write is retried; a new process may owe it once more.
     */
    @Synchronized
    fun acknowledge(turnId: String, nodeId: String): Boolean {
        if (owed[turnId]?.nodeId != nodeId) return false
        owed.remove(turnId)
        save()
        return true
    }

    private fun encode(): String = JSONArray().also { array ->
        owed.values.forEach { array.put(JSONObject().put("turn", it.turnId).put("node", it.nodeId).put("detail", it.detail)) }
    }.toString()

    companion object {
        const val KEY = "watch_failure_outbox_v1"
        const val MAX_ENTRIES = 32
    }
}

/**
 * This Watch's durable record of the Phone's "not sent" states: which are still to be shown to the person and which were already shown.
 * A state is first kept as owed ([receive]); only after the screen reports it was visible ([displayed]) does it move to shown, and only
 * then may the Phone be acknowledged (a saved shown state). One key, checked commits: when the file refuses a write the in-memory state
 * is kept, the write is retried on the next call, and [Receipt.SHOWN_NOT_SAVED] / a false [displayed] mean "do not acknowledge yet".
 * Legacy ids from `handled_not_sent_v1` are read as shown. Bounded, oldest first out.
 */
class WatchFailureInbox(private val store: KeyValueStore, private val maxOwed: Int = MAX_OWED, private val maxShown: Int = MAX_SHOWN) {
    init {
        require(maxOwed > 0 && maxShown > 0)
    }

    /** [source] is the Phone node that sent it, kept so the acknowledgement still goes to that node after a restart. */
    data class Owed(val turnId: String, val detail: String, val source: String?)

    enum class Receipt { OWED, SHOWN_AND_SAVED, SHOWN_NOT_SAVED }

    private val owed = LinkedHashMap<String, Owed>()
    private val shown = LinkedHashSet<String>()
    private var unsaved = false

    init {
        val raw = store.getString(KEY)
        if (raw != null) {
            runCatching {
                val root = JSONObject(raw)
                val owedArray = root.optJSONArray("owed") ?: JSONArray()
                for (i in 0 until owedArray.length()) {
                    val item = owedArray.getJSONObject(i)
                    val id = item.getString("turn")
                    if (WatchLinkPaths.isValidTurnId(id)) owed[id] = Owed(id, item.getString("detail"), item.optString("node").ifBlank { null })
                }
                val shownArray = root.optJSONArray("shown") ?: JSONArray()
                for (i in 0 until shownArray.length()) shown += shownArray.getString(i)
            }
        } else {
            store.getString(LEGACY_KEY)?.split(' ')?.filter { it.isNotEmpty() }?.let(shown::addAll)
        }
        trim()
    }

    private fun trim() {
        while (owed.size > maxOwed) owed.remove(owed.keys.first())
        while (shown.size > maxShown) shown.remove(shown.first())
    }

    private fun encode(): String = JSONObject()
        .put("owed", JSONArray().also { a -> owed.values.forEach { a.put(JSONObject().put("turn", it.turnId).put("detail", it.detail).put("node", it.source ?: "")) } })
        .put("shown", JSONArray().also { a -> shown.forEach { a.put(it) } })
        .toString()

    private fun save() {
        unsaved = !store.commitString(KEY, encode())
    }

    private fun retrySave() {
        if (unsaved) save()
    }

    /** The Phone says [turnId] was not sent. Keeps it owed (saved, checked) unless it was already shown; the result says whether the Phone may be acknowledged now. */
    @Synchronized
    fun receive(turnId: String, detail: String, source: String?): Receipt {
        retrySave()
        if (turnId in shown) return if (unsaved) Receipt.SHOWN_NOT_SAVED else Receipt.SHOWN_AND_SAVED
        val entry = Owed(turnId, detail, source ?: owed[turnId]?.source)
        if (owed[turnId] != entry) {
            owed[turnId] = entry
            trim()
            save()
        }
        return Receipt.OWED
    }

    /** What is still to be shown, oldest first. */
    @Synchronized
    fun pending(): List<Owed> {
        retrySave()
        return owed.values.toList()
    }

    /** The screen showed [turnId]. True when that is saved, so the Phone may now be acknowledged; false: shown here, not yet saved (or unknown). */
    @Synchronized
    fun displayed(turnId: String): Boolean {
        if (turnId in owed) {
            owed.remove(turnId)
            shown += turnId
            trim()
            save()
        } else retrySave()
        return turnId in shown && !unsaved
    }

    companion object {
        const val KEY = "watch_failure_inbox_v1"
        const val LEGACY_KEY = "handled_not_sent_v1"
        const val MAX_OWED = 16
        const val MAX_SHOWN = 64
    }
}
