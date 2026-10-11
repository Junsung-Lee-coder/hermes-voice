package com.rumi.hermesvoice.core.voice

import com.rumi.hermesvoice.core.KeyValueStore
import com.rumi.hermesvoice.core.VoiceOrigin
import org.json.JSONArray
import org.json.JSONObject

/**
 * The replies that ended without audio, kept on this device only so a manual Replay survives a process end: bounded, expiring, never
 * sent anywhere. The text is the answer the user already has in the conversation; a reply is removed when it was heard.
 */
class UnplayedReplyStore(
    private val store: KeyValueStore,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = DEFAULT_MAX,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val key: String = KEY,
) {
    data class Entry(
        val identity: String,
        val turnId: String,
        val origin: VoiceOrigin,
        val originNodeId: String?,
        val storedSessionId: String,
        val private: Boolean,
        val text: String,
        val createdAtMs: Long,
    )

    /** The kept replies. Expired or future-dated ones are removed from the stored string here (a failed commit hides them anyway and is retried at the next access). */
    @Synchronized fun all(): List<Entry> = purge().first

    @Synchronized fun get(identity: String): Entry? = all().firstOrNull { it.identity == identity }

    /** True only when the entry is safely stored. */
    @Synchronized fun put(entry: Entry): Boolean {
        if (entry.text.isBlank()) return false
        val kept = all().filter { it.identity != entry.identity } + entry
        return save(kept.takeLast(maxEntries))
    }

    @Synchronized fun remove(identity: String) {
        val live = all()
        if (live.any { it.identity == identity }) save(live.filter { it.identity != identity })
    }

    /** Forgets every kept reply. True only when the stored string no longer holds any (a failed commit leaves it and reports false). */
    @Synchronized fun clear(): Boolean = store.commitString(key, "[]")

    /** Removes the expired text from the stored string now. True only when no expired text remains there. */
    @Synchronized fun purgeExpired(): Boolean = purge().second

    private fun purge(): Pair<List<Entry>, Boolean> {
        val loaded = load()
        val now = wallClock()
        val live = loaded.filter { now - it.createdAtMs in 0..ttlMs }
        if (live.size == loaded.size) return live to true
        return live to save(live)
    }

    private fun save(entries: List<Entry>): Boolean {
        val array = JSONArray()
        entries.forEach {
            array.put(JSONObject().put("i", it.identity).put("t", it.turnId).put("o", it.origin.name).put("n", it.originNodeId ?: JSONObject.NULL)
                .put("s", it.storedSessionId).put("p", it.private).put("x", it.text).put("c", it.createdAtMs))
        }
        return store.commitString(key, array.toString())
    }

    private fun load(): List<Entry> {
        val raw = store.getString(key) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val o = array.optJSONObject(index) ?: return@mapNotNull null
                val origin = runCatching { VoiceOrigin.valueOf(o.getString("o")) }.getOrNull() ?: return@mapNotNull null
                val text = o.optString("x", "")
                val identity = o.optString("i", "")
                if (text.isBlank() || identity.isEmpty()) return@mapNotNull null
                Entry(identity, o.optString("t", ""), origin, if (o.isNull("n")) null else o.optString("n"), o.optString("s", ""), o.optBoolean("p", true), text, o.optLong("c", 0L))
            }
        } catch (_: org.json.JSONException) {
            emptyList()
        }
    }

    companion object {
        const val PREFERENCES_NAME = "hermes_voice_unplayed"
        const val KEY = "unplayed_replies_v1"
        const val DEFAULT_MAX = 8
        const val DEFAULT_TTL_MS = 3L * 24 * 60 * 60 * 1000
    }
}
