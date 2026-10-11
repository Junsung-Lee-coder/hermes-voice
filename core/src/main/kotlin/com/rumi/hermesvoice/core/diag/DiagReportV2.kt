package com.rumi.hermesvoice.core.diag

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The JSON shapes shared by the Watch's answer and the exported report: closed key sets, fixed words, bounded numbers. */
internal object DiagJson {
    fun integral(value: Any?, max: Long): Long? = if (value is Int || value is Long) (value as Number).toLong().takeIf { it in 0..max } else null

    fun coverage(c: WireCoverage): JSONObject = JSONObject()
        .put("retained", c.retained).put("capacity_dropped", c.capacityDropped).put("retention_pruned", c.retentionPruned)
        .put("restored", c.restored).put("capacity", c.capacity).put("retention_hours", c.retentionHours)
        .put("logging_for_ms", c.loggingForMs).put("persistence", c.persistence.wire)
        .put("skipped_on_load", c.skippedOnLoad).put("write_failures", c.writeFailures)

    private val COVERAGE_KEYS = setOf("retained", "capacity_dropped", "retention_pruned", "restored", "capacity", "retention_hours",
        "logging_for_ms", "persistence", "skipped_on_load", "write_failures")

    fun decodeCoverage(json: JSONObject): WireCoverage? {
        if (json.keys().asSequence().toSet() != COVERAGE_KEYS) return null
        val big = DiagLog.MAX_COUNTER
        return WireCoverage(
            retained = integral(json.opt("retained"), DiagLog.MAX_CAPACITY.toLong())?.toInt() ?: return null,
            capacityDropped = integral(json.opt("capacity_dropped"), big) ?: return null,
            retentionPruned = integral(json.opt("retention_pruned"), big) ?: return null,
            restored = integral(json.opt("restored"), DiagLog.MAX_CAPACITY.toLong())?.toInt() ?: return null,
            capacity = integral(json.opt("capacity"), DiagLog.MAX_CAPACITY.toLong())?.toInt() ?: return null,
            retentionHours = integral(json.opt("retention_hours"), 24L * 365)?.toInt() ?: return null,
            loggingForMs = integral(json.opt("logging_for_ms"), DiagLog.MAX_MS * 100) ?: return null,
            persistence = DiagPersistState.fromWire(json.opt("persistence") as? String) ?: return null,
            skippedOnLoad = integral(json.opt("skipped_on_load"), big) ?: return null,
            writeFailures = integral(json.opt("write_failures"), big) ?: return null,
        )
    }

    fun app(a: DiagApp): JSONObject = JSONObject().put("version_name", a.versionName).put("version_code", a.versionCode)
        .put("build_status", a.build.status.wire).apply { a.build.sha256?.let { put("build_sha256", it) } }

    fun decodeApp(json: JSONObject): DiagApp? {
        if (!setOf("version_name", "version_code", "build_status", "build_sha256").containsAll(json.keys().asSequence().toSet())) return null
        val name = (json.opt("version_name") as? String)?.takeIf { DiagApp.NAME_FORMAT.matches(it) } ?: return null
        val code = integral(json.opt("version_code"), 1_000_000_000L)?.toInt() ?: return null
        val status = DiagBuildStatus.fromWire(json.opt("build_status") as? String) ?: return null
        val sha = if (json.has("build_sha256")) (json.opt("build_sha256") as? String)?.takeIf { DiagBuild.HEX64.matches(it) } ?: return null else null
        if ((status == DiagBuildStatus.AVAILABLE) != (sha != null)) return null
        return DiagApp(name, code, DiagBuild(status, sha))
    }

    fun network(n: DiagNetwork): JSONObject = JSONObject().put("state", n.conn.wire)
        .put("transports", JSONArray().apply { n.transports.sortedBy { it.wire }.forEach { put(it.wire) } })

    fun decodeNetwork(json: JSONObject): DiagNetwork? {
        if (json.keys().asSequence().toSet() != setOf("state", "transports")) return null
        val conn = DiagConn.fromWire(json.opt("state") as? String) ?: return null
        val array = json.opt("transports") as? JSONArray ?: return null
        if (array.length() > DiagTransport.values().size) return null
        val transports = LinkedHashSet<DiagTransport>()
        for (i in 0 until array.length()) transports += DiagTransport.fromWire(array.opt(i) as? String) ?: return null
        if (transports.size != array.length()) return null
        return DiagNetwork(conn, transports)
    }
}

/** The closed JSON shape of [DiagStt]: fixed words and bounded numbers; anything else makes the carrying event invalid. */
internal object DiagSttJson {
    private val KEYS = setOf("code", "bytes", "mime", "wait_ms", "backend", "model")

    fun encode(stt: DiagStt): JSONObject = JSONObject().apply {
        stt.code?.let { put("code", it.wire) }
        stt.audioBytes?.let { put("bytes", it) }
        stt.mime?.let { put("mime", it.wire) }
        stt.waitMs?.let { put("wait_ms", it) }
        put("backend", stt.backend.wire)
        put("model", "unknown")
    }

    fun decode(json: JSONObject): DiagStt? {
        if (!KEYS.containsAll(json.keys().asSequence().toSet())) return null
        val code = if (json.has("code")) com.rumi.hermesvoice.core.net.SttCode.fromWire(json.opt("code") as? String) ?: return null else null
        val bytes = if (json.has("bytes")) DiagJson.integral(json.opt("bytes"), DiagStt.MAX_AUDIO_BYTES.toLong())?.toInt() ?: return null else null
        val mime = if (json.has("mime")) DiagMime.fromWire(json.opt("mime") as? String) ?: return null else null
        val wait = if (json.has("wait_ms")) DiagJson.integral(json.opt("wait_ms"), DiagStt.MAX_WAIT_MS) ?: return null else null
        val backend = if (json.has("backend")) DiagBackend.fromWire(json.opt("backend") as? String) ?: return null else DiagBackend.UNKNOWN
        if (json.has("model") && json.opt("model") != "unknown") return null
        return DiagStt(code, bytes, mime, wait, backend)
    }
}

/** Schema 2 of the Watch's answer: schema 1 plus typed stage/cause fields, coverage, app identity and a network category. */
object DiagWireV2 {
    const val SCHEMA = 2
    const val SCHEMA_3 = 3
    private val TOP_KEYS = setOf("schema", "salt", "events", "coverage", "app", "network", "omitted")

    fun encodeResponse(
        salt: String,
        events: List<DiagEvent>,
        now: Long,
        coverage: DiagCoverage,
        persist: DiagPersistStatus,
        app: DiagApp,
        network: DiagNetwork,
        schema: Int = SCHEMA,
    ): ByteArray {
        val shown = if (schema == SCHEMA_3) events else events.filter { !it.code.v3 }
        var keep = shown.takeLast(DiagWire.MAX_EVENTS)
        while (true) {
            val bytes = build(salt, keep, now, WireCoverage.of(coverage, persist), app, network, (shown.size - keep.size).toLong(), schema)
            if (bytes.size <= DiagWire.MAX_BYTES || keep.isEmpty()) return bytes
            keep = keep.drop(maxOf(1, keep.size / 4))
        }
    }

    private fun build(salt: String, events: List<DiagEvent>, now: Long, coverage: WireCoverage, app: DiagApp, network: DiagNetwork, omitted: Long, schema: Int): ByteArray {
        val array = JSONArray()
        events.forEach { array.put(DiagWire.eventJson(wireEvent(salt, it, now, withStt = schema == SCHEMA_3))) }
        return JSONObject().put("schema", schema).put("salt", salt).put("events", array).put("coverage", DiagJson.coverage(coverage))
            .put("app", DiagJson.app(app)).put("network", DiagJson.network(network)).put("omitted", omitted).toString().toByteArray(Charsets.UTF_8)
    }

    fun wireEvent(salt: String, e: DiagEvent, now: Long, rawLive: Boolean = false, withStt: Boolean = false) = WireEvent((now - e.atMs).coerceAtLeast(0), e.code, e.origin,
        DiagPseudonyms.keyOf(e, rawLive)?.let { DiagPseudonyms.of(salt, it) }, e.n, e.ms, e.fail, e.stage, e.cause, e.modality, e.conn, e.status,
        if (withStt) e.stt else null)

    /** Strict, like schema 1: any structural problem makes the whole Watch part [WatchDiagStatus.INVALID]. */
    fun decodeResponse(bytes: ByteArray, salt: String, schema: Int = SCHEMA): WatchDiag {
        if (bytes.size > DiagWire.MAX_BYTES) return WatchDiag(WatchDiagStatus.TOO_LARGE)
        return try {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            if (json.keys().asSequence().toSet() != TOP_KEYS || json.opt("schema") != schema || json.opt("salt") != salt) return WatchDiag(WatchDiagStatus.INVALID)
            val coverage = DiagJson.decodeCoverage(json.opt("coverage") as? JSONObject ?: return WatchDiag(WatchDiagStatus.INVALID)) ?: return WatchDiag(WatchDiagStatus.INVALID)
            val app = DiagJson.decodeApp(json.opt("app") as? JSONObject ?: return WatchDiag(WatchDiagStatus.INVALID)) ?: return WatchDiag(WatchDiagStatus.INVALID)
            val network = DiagJson.decodeNetwork(json.opt("network") as? JSONObject ?: return WatchDiag(WatchDiagStatus.INVALID)) ?: return WatchDiag(WatchDiagStatus.INVALID)
            val omitted = DiagJson.integral(json.opt("omitted"), DiagLog.MAX_COUNTER) ?: return WatchDiag(WatchDiagStatus.INVALID)
            val array = json.opt("events") as? JSONArray ?: return WatchDiag(WatchDiagStatus.INVALID)
            if (array.length() > DiagWire.MAX_EVENTS) return WatchDiag(WatchDiagStatus.INVALID)
            val events = ArrayList<WireEvent>(array.length())
            for (i in 0 until array.length()) {
                events += decodeEvent(array.opt(i) as? JSONObject ?: return WatchDiag(WatchDiagStatus.INVALID), schema) ?: return WatchDiag(WatchDiagStatus.INVALID)
            }
            WatchDiag(WatchDiagStatus.REACHABLE, events, coverage.capacityDropped, WatchDiagV2(coverage, app, network, omitted, schema))
        } catch (_: JSONException) {
            WatchDiag(WatchDiagStatus.INVALID)
        }
    }

    private fun decodeEvent(json: JSONObject, schema: Int): WireEvent? {
        if (!(if (schema == SCHEMA_3) DiagWire.EVENT_KEYS_V3 else DiagWire.EVENT_KEYS_V2).containsAll(json.keys().asSequence().toSet())) return null
        val age = DiagJson.integral(json.opt("age_ms"), DiagLog.MAX_MS) ?: return null
        val code = DiagCode.fromWire(json.opt("code") as? String) ?: return null
        val origin = if (json.has("origin")) DiagOrigin.values().firstOrNull { it.wire == json.opt("origin") } ?: return null else null
        val ref = if (json.has("ref")) (json.opt("ref") as? String)?.takeIf { DiagPseudonyms.FORMAT.matches(it) } ?: return null else null
        val n = if (json.has("n")) DiagJson.integral(json.opt("n"), DiagLog.MAX_NUMBER.toLong())?.toInt() ?: return null else null
        val ms = if (json.has("ms")) DiagJson.integral(json.opt("ms"), DiagLog.MAX_MS) ?: return null else null
        val fail = if (json.has("fail")) DiagFail.fromWire(json.opt("fail") as? String) ?: return null else null
        val stage = if (json.has("stage")) DiagStage.fromWire(json.opt("stage") as? String) ?: return null else null
        val cause = if (json.has("cause")) DiagCause.fromWire(json.opt("cause") as? String) ?: return null else null
        val modality = if (json.has("modality")) DiagModality.fromWire(json.opt("modality") as? String) ?: return null else null
        val conn = if (json.has("conn")) DiagConn.fromWire(json.opt("conn") as? String) ?: return null else null
        val status = if (json.has("status")) {
            val value = json.opt("status")
            if (value is Int && value in -DiagClassify.MAX_STATUS..DiagClassify.MAX_STATUS) value else return null
        } else null
        if (code.v3 && schema != SCHEMA_3) return null
        val stt = if (json.has("stt")) DiagSttJson.decode(json.opt("stt") as? JSONObject ?: return null) ?: return null else null
        return WireEvent(age, code, origin, ref, n, ms, fail, stage, cause, modality, conn, status, stt)
    }
}

/** The exported report in schema 2 (and, with [DiagReportV2.build]'s `schema` = 3, schema 3). Everything is a fixed word, a bounded number or a one-time pseudonym. */
object DiagReportV2 {
    const val SCHEMA = 2

    /** What must be read into a report: fixed statements that keep a short or empty log from being mistaken for a healthy one. */
    val READING = listOf(
        "no_events_is_not_proof_of_delivery",
        "connectivity_is_context_not_the_cause",
        "events_since_the_last_save_are_lost_if_the_app_dies",
    )

    /** Added in schema 3: what the newer event kinds and fields do and do not say. */
    val READING_3 = listOf(
        "request_delivered_means_the_server_accepted_the_submitted_message",
        "request_transmission_ended_is_bookkeeping_and_never_proof_of_delivery",
        "request_delivered_recorded_by_builds_before_schema_3_only_meant_the_transmission_ended_and_may_follow_a_failure",
        "stt_backend_and_model_are_unknown_to_the_app_the_server_log_has_them",
        "a_missing_field_is_unknown_not_zero",
    )

    fun build(
        exportedAtWallMs: Long,
        app: DiagApp,
        phone: List<DiagEvent>,
        now: Long,
        salt: String,
        phoneCoverage: WireCoverage,
        phoneNetwork: DiagNetwork,
        watch: WatchDiag,
        settings: Map<String, Boolean>,
        schema: Int = SCHEMA,
    ): String {
        val events = JSONArray()
        val three = schema == DiagWireV2.SCHEMA_3
        val olderWatch = watch.status == WatchDiagStatus.REACHABLE && watch.v2 == null
        (if (three) phone else phone.filter { !it.code.v3 }).takeLast(DiagLog.MAX_CAPACITY)
            .forEach { events.put(DiagWire.eventJson(DiagWireV2.wireEvent(salt, it, now, rawLive = olderWatch, withStt = three))) }
        val watchEvents = JSONArray()
        watch.events.forEach { watchEvents.put(DiagWire.eventJson(it)) }
        val watchJson = JSONObject().put("status", watch.status.wire).put("schema", if (watch.v2 != null) watch.v2.schema else if (watch.status == WatchDiagStatus.REACHABLE) DiagWire.SCHEMA else JSONObject.NULL)
            .put("events", watchEvents).put("dropped", watch.dropped)
        watch.v2?.let {
            watchJson.put("coverage", DiagJson.coverage(it.coverage)).put("app", DiagJson.app(it.app))
                .put("network", DiagJson.network(it.network)).put("omitted_for_size", it.omittedForSize)
        }
        val flags = JSONObject()
        settings.toSortedMap().forEach { (key, value) -> flags.put(key, value) }
        return JSONObject()
            .put("kind", DiagReport.KIND)
            .put("schema", schema)
            .put("exported_utc", java.time.Instant.ofEpochMilli(exportedAtWallMs).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString())
            .put("app", DiagJson.app(app))
            .put("app_version_code", app.versionCode)
            .put("time_basis", "age_ms_before_export")
            .put("reading", JSONArray().apply { READING.forEach { put(it) }; if (three) READING_3.forEach { put(it) } })
            .put("phone", JSONObject().put("events", events).put("dropped", phoneCoverage.capacityDropped)
                .put("coverage", DiagJson.coverage(phoneCoverage)).put("network", DiagJson.network(phoneNetwork)))
            .put("watch", watchJson)
            .put("settings", flags)
            .toString(2)
    }
}
