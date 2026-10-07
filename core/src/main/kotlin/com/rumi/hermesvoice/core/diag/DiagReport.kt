package com.rumi.hermesvoice.core.diag

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Whether the Watch's part of a report could be read, and why not. */
enum class WatchDiagStatus(val wire: String) {
    REACHABLE("reachable"), OFFLINE("offline"), TIMEOUT("timeout"), INVALID("invalid"), TOO_LARGE("too_large"), STALE("stale"),
}

/** The Watch's part: events whose times are ages in ms before the Watch answered. Never more than [DiagWire.MAX_EVENTS]. */
data class WatchDiag(val status: WatchDiagStatus, val events: List<WireEvent> = emptyList(), val dropped: Long = 0) {
    init { require(status == WatchDiagStatus.REACHABLE || events.isEmpty()) { "only a readable Watch carries events" } }
}

/** An event as it travels and is exported: age before the report, pseudonymous ref, fixed words and small numbers. */
data class WireEvent(
    val ageMs: Long,
    val code: DiagCode,
    val origin: DiagOrigin?,
    val ref: String?,
    val n: Int?,
    val ms: Long?,
    val fail: DiagFail?,
)

/** The transport of one Watch part: a bounded JSON document with a closed schema, checked strictly on arrival. */
object DiagWire {
    const val SCHEMA = 1
    const val MAX_BYTES = 32 * 1024
    const val MAX_EVENTS = 160
    private val EVENT_KEYS = setOf("age_ms", "code", "origin", "ref", "n", "ms", "fail")
    private val TOP_KEYS = setOf("schema", "salt", "dropped", "events")

    fun encodeRequest(salt: String): ByteArray = JSONObject().put("schema", SCHEMA).put("salt", salt).toString().toByteArray(Charsets.UTF_8)

    /** The salt a request carries, or null for anything that is not exactly a request. */
    fun decodeRequest(bytes: ByteArray): String? {
        if (bytes.size > 256) return null
        return try {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            if (json.keys().asSequence().toSet() != setOf("schema", "salt") || json.opt("schema") != SCHEMA) return null
            (json.opt("salt") as? String)?.takeIf { DiagPseudonyms.SALT_FORMAT.matches(it) }
        } catch (_: JSONException) {
            null
        }
    }

    /** The Watch's answer: its events as pseudonymous, bounded wire events, newest last, trimmed to the size bound. */
    fun encodeResponse(salt: String, events: List<DiagEvent>, dropped: Long, now: Long): ByteArray {
        var keep = events.takeLast(MAX_EVENTS)
        while (true) {
            val bytes = build(salt, keep, dropped + (events.size - keep.size), now)
            if (bytes.size <= MAX_BYTES || keep.isEmpty()) return bytes
            keep = keep.drop(maxOf(1, keep.size / 4))
        }
    }

    private fun build(salt: String, events: List<DiagEvent>, dropped: Long, now: Long): ByteArray {
        val array = JSONArray()
        events.forEach { array.put(eventJson(WireEvent((now - it.atMs).coerceAtLeast(0), it.code, it.origin,
            it.ref?.let { ref -> DiagPseudonyms.of(salt, ref) }, it.n, it.ms, it.fail))) }
        return JSONObject().put("schema", SCHEMA).put("salt", salt).put("dropped", dropped).put("events", array).toString()
            .toByteArray(Charsets.UTF_8)
    }

    fun eventJson(e: WireEvent): JSONObject = JSONObject().put("age_ms", e.ageMs).put("code", e.code.wire).apply {
        e.origin?.let { put("origin", it.wire) }
        e.ref?.let { put("ref", it) }
        e.n?.let { put("n", it) }
        e.ms?.let { put("ms", it) }
        e.fail?.let { put("fail", it.wire) }
    }

    /**
     * Strict decoding of the Watch's answer: any byte or schema problem makes the whole part [WatchDiagStatus.INVALID] (or
     * [WatchDiagStatus.TOO_LARGE]); unknown keys, codes, wrong types, out-of-range numbers, a foreign salt or too many events
     * are never half-accepted. Nothing from the payload is copied except fixed words, numbers and an 8-hex pseudonym.
     */
    fun decodeResponse(bytes: ByteArray, salt: String): WatchDiag {
        if (bytes.size > MAX_BYTES) return WatchDiag(WatchDiagStatus.TOO_LARGE)
        return try {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            if (!TOP_KEYS.containsAll(json.keys().asSequence().toSet()) || json.opt("schema") != SCHEMA || json.opt("salt") != salt) {
                return WatchDiag(WatchDiagStatus.INVALID)
            }
            val dropped = integral(json.opt("dropped"), 1_000_000_000L) ?: return WatchDiag(WatchDiagStatus.INVALID)
            val array = json.opt("events") as? JSONArray ?: return WatchDiag(WatchDiagStatus.INVALID)
            if (array.length() > MAX_EVENTS) return WatchDiag(WatchDiagStatus.INVALID)
            val events = ArrayList<WireEvent>(array.length())
            for (i in 0 until array.length()) events += decodeEvent(array.opt(i) as? JSONObject ?: return WatchDiag(WatchDiagStatus.INVALID))
                ?: return WatchDiag(WatchDiagStatus.INVALID)
            WatchDiag(WatchDiagStatus.REACHABLE, events, dropped)
        } catch (_: JSONException) {
            WatchDiag(WatchDiagStatus.INVALID)
        }
    }

    private fun integral(value: Any?, max: Long): Long? = if (value is Int || value is Long) (value as Number).toLong().takeIf { it in 0..max } else null

    private fun decodeEvent(json: JSONObject): WireEvent? {
        if (!EVENT_KEYS.containsAll(json.keys().asSequence().toSet())) return null
        val age = integral(json.opt("age_ms"), DiagLog.MAX_MS) ?: return null
        val code = DiagCode.fromWire(json.opt("code") as? String) ?: return null
        val origin = if (json.has("origin")) DiagOrigin.values().firstOrNull { it.wire == json.opt("origin") } ?: return null else null
        val ref = if (json.has("ref")) (json.opt("ref") as? String)?.takeIf { DiagPseudonyms.FORMAT.matches(it) } ?: return null else null
        val n = if (json.has("n")) integral(json.opt("n"), DiagLog.MAX_NUMBER.toLong())?.toInt() ?: return null else null
        val ms = if (json.has("ms")) integral(json.opt("ms"), DiagLog.MAX_MS) ?: return null else null
        val fail = if (json.has("fail")) DiagFail.fromWire(json.opt("fail") as? String) ?: return null else null
        return WireEvent(age, code, origin, ref, n, ms, fail)
    }
}

/** The whole report: Phone data always, the Watch part when it could be read, plus coarse yes/no settings. Pure data → JSON. */
object DiagReport {
    const val SCHEMA = 1
    const val KIND = "hermes-voice-diagnostics"

    /** What a report is made of; the consent dialog lists exactly these. */
    val CONTENT_LINES = listOf(
        "Recent events of this phone: request stages, waiting and queue states, reply and speech-synthesis progress, playback and Stop results, with how long ago each happened.",
        "The same kind of events from the Watch, if it can be reached now (otherwise only a note that it is offline).",
        "Failure types as fixed words (for example timeout or disconnected), counts, durations, and which switches in Settings are on.",
        "Request references are one-time pseudonyms that are only valid inside this file.",
    )
    val EXCLUDED_LINES = listOf(
        "No audio, what you said, replies, conversation names, addresses, accounts, passwords or tokens, device names or ids, and no error messages.",
    )

    fun build(
        appVersionCode: Int,
        phone: List<DiagEvent>,
        phoneDropped: Long,
        now: Long,
        salt: String,
        watch: WatchDiag,
        settings: Map<String, Boolean>,
    ): String {
        val events = JSONArray()
        phone.takeLast(DiagLog.MAX_CAPACITY).forEach {
            events.put(DiagWire.eventJson(WireEvent((now - it.atMs).coerceAtLeast(0), it.code, it.origin,
                it.ref?.let { ref -> DiagPseudonyms.of(salt, ref) }, it.n, it.ms, it.fail)))
        }
        val watchEvents = JSONArray()
        watch.events.forEach { watchEvents.put(DiagWire.eventJson(it)) }
        val flags = JSONObject()
        settings.toSortedMap().forEach { (key, value) -> flags.put(key, value) }
        return JSONObject()
            .put("kind", KIND)
            .put("schema", SCHEMA)
            .put("app_version_code", appVersionCode)
            .put("time_basis", "age_ms_before_export")
            .put("phone", JSONObject().put("events", events).put("dropped", phoneDropped))
            .put("watch", JSONObject().put("status", watch.status.wire).put("events", watchEvents).put("dropped", watch.dropped))
            .put("settings", flags)
            .toString(2)
    }
}

/**
 * The Phone's side of the scoped Watch request: one request in flight, answered by a Watch message that carries the request's
 * salt back. An answer for another salt (an earlier export) is ignored; no timer runs and nothing polls: the caller's own
 * bound ([DiagExporter]) ends the wait.
 */
class DiagWatchClient(private val send: suspend (ByteArray) -> Boolean) {
    private class Waiting(val salt: String, val answer: kotlinx.coroutines.CompletableDeferred<WatchDiag>)

    @Volatile private var waiting: Waiting? = null

    /** The Watch's part: [WatchDiagStatus.OFFLINE] when it can't be asked, otherwise its decoded answer (or the caller's timeout). */
    suspend fun request(salt: String): WatchDiag {
        val entry = Waiting(salt, kotlinx.coroutines.CompletableDeferred())
        waiting = entry
        try {
            val sent = try {
                send(DiagWire.encodeRequest(salt))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                false
            }
            if (!sent) return WatchDiag(WatchDiagStatus.OFFLINE)
            return entry.answer.await()
        } finally {
            if (waiting === entry) waiting = null
        }
    }

    fun onResponse(bytes: ByteArray) {
        val entry = waiting ?: return
        if (bytes.size > DiagWire.MAX_BYTES) {
            entry.answer.complete(WatchDiag(WatchDiagStatus.TOO_LARGE))
            return
        }
        val salt = runCatching { JSONObject(String(bytes, Charsets.UTF_8)).opt("salt") }.getOrNull()
        if (salt != null && salt != entry.salt) return
        entry.answer.complete(DiagWire.decodeResponse(bytes, entry.salt))
    }
}
