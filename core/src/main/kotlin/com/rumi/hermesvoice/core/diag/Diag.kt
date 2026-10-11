package com.rumi.hermesvoice.core.diag

import java.security.MessageDigest

/** Which device recorded an event. */
enum class DiagSource(val wire: String) { PHONE("phone"), WATCH("watch") }

/** Where a request came from, as a fixed word (never a device or node id). */
enum class DiagOrigin(val wire: String) { PHONE("phone"), WATCH("watch") }

enum class DiagCategory(val wire: String) {
    REQUEST("request"), RESPONSE("response"), SPEECH("speech"), PLAYBACK("playback"), STOP("stop"),
    QUEUE("queue"), WATCH_LINK("watch_link"), LATER("later_reply"), WATCH_DEVICE("watch_device"), STT("stt"),
}

/**
 * The whitelist of event kinds. An event is one of these and a few numbers: there is no free-text field anywhere, so
 * nothing a user said, a reply, a name, an address or an exception message can reach a report.
 */
enum class DiagCode(val wire: String, val category: DiagCategory, val legacy: Boolean = true, val v3: Boolean = false) {
    REQUEST_STARTED("request_started", DiagCategory.REQUEST, legacy = false),
    REQUEST_ACCEPTED("request_accepted", DiagCategory.REQUEST),
    /** The server accepted the submitted message (the hand-over succeeded). Recorded from the real accepted-submit signal only. */
    REQUEST_DELIVERED("request_delivered", DiagCategory.REQUEST),

    /** The transmission slot ended on any path (delivered, failed or stopped): bookkeeping only, never evidence that anything was delivered. */
    REQUEST_TRANSMISSION_ENDED("request_transmission_ended", DiagCategory.REQUEST, legacy = false, v3 = true),
    REQUEST_COMPLETED("request_completed", DiagCategory.REQUEST),
    REQUEST_FAILED("request_failed", DiagCategory.REQUEST),
    REQUEST_STOPPED("request_stopped", DiagCategory.REQUEST),
    REQUEST_REFUSED_FULL("request_refused_full", DiagCategory.QUEUE),
    REQUEST_QUEUED("request_queued", DiagCategory.QUEUE),
    RESPONSE_WAITING("response_waiting", DiagCategory.RESPONSE),
    RESPONSE_PROGRESS("response_progress", DiagCategory.RESPONSE),
    RESPONSE_COMPLETE("response_complete", DiagCategory.RESPONSE),
    RESPONSE_SILENT("response_silent", DiagCategory.RESPONSE),
    RESPONSE_ENDED("response_ended", DiagCategory.RESPONSE),
    SPEECH_CHUNK_DONE("speech_chunk_done", DiagCategory.SPEECH),
    SPEECH_CHUNK_FAILED("speech_chunk_failed", DiagCategory.SPEECH),
    STT_RECOGNIZER_FAILED("stt_recognizer_failed", DiagCategory.SPEECH, legacy = false),
    STT_ATTEMPT_STARTED("stt_attempt_started", DiagCategory.STT, legacy = false, v3 = true),
    STT_ATTEMPT_OK("stt_attempt_ok", DiagCategory.STT, legacy = false, v3 = true),
    STT_ATTEMPT_FAILED("stt_attempt_failed", DiagCategory.STT, legacy = false, v3 = true),
    STT_WAIT_STARTED("stt_wait_started", DiagCategory.STT, legacy = false, v3 = true),
    STT_RECOVERED("stt_recovered", DiagCategory.STT, legacy = false, v3 = true),
    STT_EXHAUSTED("stt_exhausted", DiagCategory.STT, legacy = false, v3 = true),
    STT_CANCELLED("stt_cancelled", DiagCategory.STT, legacy = false, v3 = true),
    PLAYBACK_STARTED("playback_started", DiagCategory.PLAYBACK),
    PLAYBACK_DONE("playback_done", DiagCategory.PLAYBACK),
    PLAYBACK_FAILED("playback_failed", DiagCategory.PLAYBACK),
    PLAYBACK_WAITING("playback_waiting", DiagCategory.PLAYBACK),
    ACK_TIMEOUT("ack_timeout", DiagCategory.WATCH_LINK),
    ACK_REFUSED("ack_refused", DiagCategory.WATCH_LINK),
    STOP_ALL("stop_all", DiagCategory.STOP),
    STOP_ONE("stop_one", DiagCategory.STOP),
    LATER_ARRIVED("later_arrived", DiagCategory.LATER),
    LATER_PLAYED("later_played", DiagCategory.LATER),
    LATER_NOT_PLAYED("later_not_played", DiagCategory.LATER),
    WATCH_RECORDING("watch_recording", DiagCategory.WATCH_DEVICE),
    WATCH_SENT("watch_sent", DiagCategory.WATCH_DEVICE),
    WATCH_PLAY_RECEIVED("watch_play_received", DiagCategory.WATCH_DEVICE),
    WATCH_STOPPED("watch_stopped", DiagCategory.WATCH_DEVICE);

    /**
     * Frequent progress records: first to be evicted when the ring is full, and never a reason to write the journal on their own
     * (so nothing is written per audio chunk or per synthesized part).
     */
    val transient: Boolean get() = this == STT_ATTEMPT_STARTED || this == RESPONSE_PROGRESS || this == RESPONSE_WAITING || this == SPEECH_CHUNK_DONE ||
        this == PLAYBACK_DONE || this == PLAYBACK_WAITING ||
        this == STT_RECOGNIZER_FAILED

    companion object {
        private val byWire = values().associateBy { it.wire }
        fun fromWire(wire: String?): DiagCode? = byWire[wire]
    }
}

/** A failure class as a fixed code. Derived from the exception's TYPE only; its message is never read. */
enum class DiagFail(val wire: String) {
    TIMEOUT("timeout"), IO("io"), PROTOCOL("protocol"), AUTH("auth"), CANCELLED("cancelled"),
    DISCONNECTED("disconnected"), REFUSED("refused"), BUSY("busy"), STALE("stale"), UNKNOWN("unknown");

    companion object {
        private val byWire = values().associateBy { it.wire }
        fun fromWire(wire: String?): DiagFail? = byWire[wire]

        /** The same mapping from a class's simple name (all that a listener is given). */
        fun ofName(simpleName: String?): DiagFail = when (simpleName) {
            null -> UNKNOWN
            "SocketTimeoutException", "TimeoutException", "TimeoutCancellationException" -> TIMEOUT
            "ConnectException", "UnknownHostException", "SocketException" -> DISCONNECTED
            "HermesProtocolException" -> PROTOCOL
            "HermesAuthRequiredException", "SecurityException" -> AUTH
            "CancellationException" -> CANCELLED
            "IOException", "EOFException", "SSLException" -> IO
            else -> UNKNOWN
        }

        /** Only the class decides; the message, cause and stack are never looked at. */
        fun of(error: Throwable?): DiagFail = when (error) {
            null -> UNKNOWN
            is java.util.concurrent.CancellationException -> CANCELLED
            is java.net.SocketTimeoutException, is java.util.concurrent.TimeoutException -> TIMEOUT
            is java.net.ConnectException, is java.net.UnknownHostException, is java.net.SocketException -> DISCONNECTED
            is java.io.IOException -> if (error.javaClass.simpleName == "HermesProtocolException") PROTOCOL else IO
            is SecurityException -> AUTH
            else -> ofName(error.javaClass.simpleName)
        }
    }
}

/**
 * One recorded event. [ref] is an in-memory correlation key (a turn id, say) unless [opaque]: it is turned into an export-only
 * pseudonym ([DiagPseudonyms]) and a persisted event only ever carries the stable one-way form of it ([DiagPseudonyms.stable]). [n] and [ms] are small counts and
 * durations; [atMs] is monotonic time and [wallMs] the wall time that lets the persisted journal be aged across a restart.
 * [stage], [cause], [modality], [conn] and [status] are fixed words and one bounded number: nothing else is ever stored.
 */
data class DiagEvent(
    val atMs: Long,
    val code: DiagCode,
    val origin: DiagOrigin? = null,
    val ref: String? = null,
    val n: Int? = null,
    val ms: Long? = null,
    val fail: DiagFail? = null,
    val wallMs: Long? = null,
    val stage: DiagStage? = null,
    val cause: DiagCause? = null,
    val modality: DiagModality? = null,
    val conn: DiagConn? = null,
    val status: Int? = null,
    val opaque: Boolean = false,
    val stt: DiagStt? = null,
)

/** The coarse container of the audio an STT attempt carried: a fixed word, never the raw MIME string. */
enum class DiagMime(val wire: String) {
    WAV("wav"), MP4("mp4"), OGG("ogg"), WEBM("webm"), MP3("mp3"), OTHER("other");

    companion object {
        private val byWire = values().associateBy { it.wire }
        fun fromWire(wire: String?): DiagMime? = byWire[wire]

        fun of(mime: String?): DiagMime {
            val base = mime?.substringBefore(';')?.trim()?.lowercase() ?: return OTHER
            return when (base) {
                "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> WAV
                "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac" -> MP4
                "audio/ogg", "audio/opus" -> OGG
                "audio/webm" -> WEBM
                "audio/mpeg", "audio/mp3" -> MP3
                else -> OTHER
            }
        }
    }
}

/**
 * Which speech backend served an attempt, as a fixed alias. The supported dashboard transport does not reveal it to the app,
 * so the app records [UNKNOWN] (the server's own log carries the real alias); the other words exist for a transport that does.
 */
enum class DiagBackend(val wire: String) {
    UNKNOWN("unknown"), PRIMARY("primary"), STANDBY_1("standby_1"), STANDBY_2("standby_2");

    companion object {
        private val byWire = values().associateBy { it.wire }
        fun fromWire(wire: String?): DiagBackend? = byWire[wire]
    }
}

/**
 * What an STT attempt adds to an event: the recognized safe code of a failure, the audio's size and container word, the backoff
 * just started, and the backend/model aliases (always "unknown" from the app: see [DiagBackend]). Bounded numbers and fixed words only.
 */
data class DiagStt(
    val code: com.rumi.hermesvoice.core.net.SttCode? = null,
    val audioBytes: Int? = null,
    val mime: DiagMime? = null,
    val waitMs: Long? = null,
    val backend: DiagBackend = DiagBackend.UNKNOWN,
) {
    init {
        require(audioBytes == null || audioBytes in 0..MAX_AUDIO_BYTES) { "bytes" }
        require(waitMs == null || waitMs in 0..MAX_WAIT_MS) { "wait" }
    }

    companion object {
        const val MAX_AUDIO_BYTES = 1_000_000_000
        const val MAX_WAIT_MS = 3_600_000L
    }
}

/** What the log holds and has lost, so that "no events" is never mistaken for "nothing happened". */
data class DiagCoverage(
    val retained: Int,
    val capacityDropped: Long,
    val retentionPruned: Long,
    val restored: Int,
    val capacity: Int,
    val retentionMs: Long,
    val loggingForMs: Long,
)

/** Everything a journal write needs, taken at one instant. */
class DiagPersistView(val events: List<DiagEvent>, val capacityDropped: Long, val retentionPruned: Long, val epoch: Long)

/**
 * Bounded ring buffer of recent events. When full, the oldest frequent progress record goes first, otherwise the oldest event;
 * events older than the retention window are pruned. Recording never blocks on IO, never throws and holds no device resource.
 */
class DiagLog(
    val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long,
    private val wallClock: () -> Long = System::currentTimeMillis,
    val retentionMs: Long = RETENTION_MS,
    private val connectivity: () -> DiagConn = { DiagConn.UNKNOWN },
) {
    init { require(capacity in 1..MAX_CAPACITY) { "capacity" } }

    private val ring = ArrayDeque<DiagEvent>()
    private var droppedCount = 0L
    private var prunedCount = 0L
    private var restoredCount = 0
    private var epoch = 0L
    private val startedAtMs = clock()

    /** Told after each recorded event, outside the log's lock (the journal's change signal). Exceptions are swallowed. */
    @Volatile var onChange: ((DiagEvent) -> Unit)? = null

    @Synchronized
    private fun add(event: DiagEvent) {
        pruneLocked(clock())
        if (ring.size >= capacity) {
            val index = ring.indexOfFirst { it.code.transient }
            if (index >= 0) ring.removeAt(index) else ring.removeFirst()
            droppedCount += 1
        }
        ring.addLast(event)
    }

    private fun pruneLocked(now: Long) {
        while (ring.isNotEmpty() && now - ring.first().atMs > retentionMs) {
            ring.removeFirst()
            prunedCount += 1
        }
    }

    /** The connectivity category now: context for an event, never a verdict and never consulted before sending. */
    fun connectivityNow(): DiagConn = try { connectivity() } catch (_: Exception) { DiagConn.UNKNOWN }

    fun record(
        code: DiagCode,
        origin: DiagOrigin? = null,
        ref: String? = null,
        n: Int? = null,
        ms: Long? = null,
        fail: DiagFail? = null,
        stage: DiagStage? = null,
        cause: DiagCause? = null,
        modality: DiagModality? = null,
        status: Int? = null,
        opaqueRef: Boolean = false,
        conn: DiagConn? = null,
        stt: DiagStt? = null,
    ) {
        val sample = conn ?: if (fail != null || code == DiagCode.REQUEST_STARTED) connectivityNow() else null
        val event = DiagEvent(clock(), code, origin, ref, n?.coerceIn(0, MAX_NUMBER), ms?.coerceIn(0L, MAX_MS), fail,
            wallClock(), stage, cause, modality, sample, status?.coerceIn(-DiagClassify.MAX_STATUS, DiagClassify.MAX_STATUS), opaqueRef, stt)
        add(event)
        val listener = onChange
        if (listener != null) try { listener(event) } catch (_: Exception) { }
    }

    fun record(code: DiagCode, error: Throwable, origin: DiagOrigin? = null, ref: String? = null, n: Int? = null, ms: Long? = null) =
        recordFailure(code, error, origin, ref, n = n, ms = ms)

    /** Records a failure classified by the error's TYPE (never its message) with the stage it was observed in. */
    fun recordFailure(
        code: DiagCode,
        error: Throwable?,
        origin: DiagOrigin? = null,
        ref: String? = null,
        stage: DiagStage? = null,
        modality: DiagModality? = null,
        n: Int? = null,
        ms: Long? = null,
        opaqueRef: Boolean = false,
    ): DiagClassified {
        val conn = connectivityNow()
        val classified = DiagClassify.of(error, conn)
        record(code, origin, ref, n, ms, classified.fail, stage, classified.cause, modality, classified.status, opaqueRef, conn)
        return classified
    }

    @Synchronized
    fun snapshot(): List<DiagEvent> {
        pruneLocked(clock())
        return ring.toList()
    }

    @Synchronized
    fun dropped(): Long = droppedCount

    @Synchronized
    fun coverage(): DiagCoverage {
        pruneLocked(clock())
        return DiagCoverage(ring.size, droppedCount, prunedCount, restoredCount, capacity, retentionMs, (clock() - startedAtMs).coerceAtLeast(0))
    }

    @Synchronized
    fun persistView(): DiagPersistView {
        pruneLocked(clock())
        return DiagPersistView(ring.toList(), droppedCount, prunedCount, epoch)
    }

    /** Forgets every retained event and the counters; a write based on an earlier [persistView] is then recognisably stale. */
    @Synchronized
    fun clear() {
        ring.clear()
        droppedCount = 0
        prunedCount = 0
        restoredCount = 0
        epoch += 1
    }

    /**
     * Puts events read from storage (older than everything recorded since) in front of the ring, within capacity and the
     * retention window, and adds the counters they carried. Returns how many were kept.
     */
    @Synchronized
    fun restore(older: List<DiagEvent>, capacityDropped: Long, retentionPruned: Long): Int {
        val now = clock()
        val fresh = older.filter { now - it.atMs <= retentionMs }
        prunedCount = (prunedCount + retentionPruned + (older.size - fresh.size)).coerceAtMost(MAX_COUNTER)
        droppedCount = (droppedCount + capacityDropped).coerceAtMost(MAX_COUNTER)
        val room = (capacity - ring.size).coerceAtLeast(0)
        val kept = fresh.takeLast(room)
        droppedCount = (droppedCount + (fresh.size - kept.size)).coerceAtMost(MAX_COUNTER)
        kept.asReversed().forEach { ring.addFirst(it) }
        restoredCount += kept.size
        return kept.size
    }

    fun now(): Long = clock()

    fun wallNow(): Long = wallClock()

    companion object {
        const val DEFAULT_CAPACITY = 256
        const val MAX_CAPACITY = 1_024
        const val MAX_NUMBER = 1_000_000
        const val MAX_MS = 7L * 24 * 3_600_000
        const val RETENTION_MS = 24L * 3_600_000
        const val MAX_COUNTER = 1_000_000_000L
    }
}

/**
 * Per-export pseudonyms: `hex8(sha256(salt + key))`, so both devices correlate one request inside ONE export and nothing outside it.
 * The key of a live correlation (a turn id, which is a random UUID) is its [stable] form, the very value a journal stores and a
 * restored event carries, so one request keeps one pseudonym whether its events are in memory or were restored, on either device.
 */
object DiagPseudonyms {
    val FORMAT = Regex("^[0-9a-f]{8}$")
    val SALT_FORMAT = Regex("^[0-9a-f]{16}$")

    fun of(salt: String, ref: String): String {
        require(SALT_FORMAT.matches(salt)) { "salt" }
        val digest = MessageDigest.getInstance("SHA-256").digest((salt + "\u0000" + ref).toByteArray(Charsets.UTF_8))
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    /** The one-way, domain-separated form of a high-entropy correlation key; the only form of it that is ever persisted. */
    fun stable(ref: String): String =
        MessageDigest.getInstance("SHA-256").digest(("hv-diag-ref-v1\u0000" + ref).toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }

    /**
     * What [of] is applied to for [event]: an opaque or restored ref as it is, a live ref in its [stable] form. [rawLive] keeps a
     * live ref raw, which is what a Watch that only speaks schema 1 uses, so live events still pair with it.
     */
    fun keyOf(event: DiagEvent, rawLive: Boolean = false): String? = event.ref?.let { if (event.opaque || rawLive) it else stable(it) }

    /** A fresh, unguessable correlation for one request attempt: 64 random bits, never derived from any content or id. */
    fun newRef(random: java.security.SecureRandom = java.security.SecureRandom()): String {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun newSalt(random: java.security.SecureRandom = java.security.SecureRandom()): String {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
