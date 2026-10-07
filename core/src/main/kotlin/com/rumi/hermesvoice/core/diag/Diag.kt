package com.rumi.hermesvoice.core.diag

import java.security.MessageDigest

/** Which device recorded an event. */
enum class DiagSource(val wire: String) { PHONE("phone"), WATCH("watch") }

/** Where a request came from, as a fixed word (never a device or node id). */
enum class DiagOrigin(val wire: String) { PHONE("phone"), WATCH("watch") }

enum class DiagCategory(val wire: String) {
    REQUEST("request"), RESPONSE("response"), SPEECH("speech"), PLAYBACK("playback"), STOP("stop"),
    QUEUE("queue"), WATCH_LINK("watch_link"), LATER("later_reply"), WATCH_DEVICE("watch_device"),
}

/**
 * The whitelist of event kinds. An event is one of these and a few numbers: there is no free-text field anywhere, so
 * nothing a user said, a reply, a name, an address or an exception message can reach a report.
 */
enum class DiagCode(val wire: String, val category: DiagCategory) {
    REQUEST_ACCEPTED("request_accepted", DiagCategory.REQUEST),
    REQUEST_DELIVERED("request_delivered", DiagCategory.REQUEST),
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
 * One recorded event. [ref] is an in-memory correlation key (a turn id, say); it is turned into an export-only pseudonym
 * ([DiagPseudonyms]) and never written as is. [n] and [ms] are small counts and durations; [atMs] is monotonic time.
 */
data class DiagEvent(
    val atMs: Long,
    val code: DiagCode,
    val origin: DiagOrigin? = null,
    val ref: String? = null,
    val n: Int? = null,
    val ms: Long? = null,
    val fail: DiagFail? = null,
)

/** Bounded ring buffer of recent events (oldest dropped). Recording never blocks, never throws and holds no device resource. */
class DiagLog(val capacity: Int = DEFAULT_CAPACITY, private val clock: () -> Long) {
    init { require(capacity in 1..MAX_CAPACITY) { "capacity" } }

    private val ring = ArrayDeque<DiagEvent>()
    private var droppedCount = 0L

    @Synchronized
    fun record(code: DiagCode, origin: DiagOrigin? = null, ref: String? = null, n: Int? = null, ms: Long? = null, fail: DiagFail? = null) {
        if (ring.size >= capacity) {
            ring.removeFirst()
            droppedCount += 1
        }
        ring.addLast(DiagEvent(clock(), code, origin, ref, n?.coerceIn(0, MAX_NUMBER), ms?.coerceIn(0L, MAX_MS), fail))
    }

    fun record(code: DiagCode, error: Throwable, origin: DiagOrigin? = null, ref: String? = null, n: Int? = null, ms: Long? = null) =
        record(code, origin, ref, n, ms, DiagFail.of(error))

    @Synchronized
    fun snapshot(): List<DiagEvent> = ring.toList()

    @Synchronized
    fun dropped(): Long = droppedCount

    fun now(): Long = clock()

    companion object {
        const val DEFAULT_CAPACITY = 256
        const val MAX_CAPACITY = 1_024
        const val MAX_NUMBER = 1_000_000
        const val MAX_MS = 7L * 24 * 3_600_000
    }
}

/** Per-export pseudonyms: `hex8(sha256(salt + ref))`, so both devices correlate one request inside ONE export and nothing outside it. */
object DiagPseudonyms {
    val FORMAT = Regex("^[0-9a-f]{8}$")
    val SALT_FORMAT = Regex("^[0-9a-f]{16}$")

    fun of(salt: String, ref: String): String {
        require(SALT_FORMAT.matches(salt)) { "salt" }
        val digest = MessageDigest.getInstance("SHA-256").digest((salt + "\u0000" + ref).toByteArray(Charsets.UTF_8))
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    fun newSalt(random: java.security.SecureRandom = java.security.SecureRandom()): String {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
