package com.rumi.hermesvoice.core.watchlink

import com.rumi.hermesvoice.core.voice.DestinationAllowlist
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import org.json.JSONException
import org.json.JSONObject

/**
 * Phone ↔ Watch wire contract (Wear Data Layer). The Watch never talks to Hermes: it sends
 * captured audio to the Phone and plays what the Phone sends back.
 *
 * - `/hv/v1/turn/<turnId>`       channel, Watch → Phone: [LinkFrame] {turn header} + WAV bytes
 *                                (the header may name the Watch's selected conversation, [WatchTurnUpload.target])
 * - `/hv/v1/state`               message, Phone → Watch: [TurnStateMessage]
 * - `/hv/v1/play/<turnId>/<seq>` channel, Phone → Watch: [LinkFrame] {play header} + audio bytes
 * - `/hv/v1/played`              message, Watch → Phone: [PlayedAck] (sent when playback ends)
 * - `/hv/v1/stop`                message, Phone → Watch: stop playing this turn now (interruption)
 * - `/hv/v1/cancel`             message, Watch → Phone: the Watch's Stop: stop this Watch's own request ([TurnStateMessage]
 *                                with the turn id) wherever it is (queued, awaiting its reply, speaking); one message per request
 * - `/hv/v1/diag/request`        message, Phone → Watch, and `/hv/v1/diag/response`, Watch → Phone: ONE user-started diagnostics
 *                                export asks the Watch for its typed event metadata ([com.rumi.hermesvoice.core.diag.DiagWire]);
 *                                bounded, closed schema, answered once per request, never polled
 * - `/hv/v1/navigate`            message, Phone → Watch: a Watch-originated routed request was DELIVERED to this conversation
 *                                ([WatchNavigation]); the Watch may select it, guarded by [WatchNavigationGuard]
 * - `/hv/v1/settings`            data item, Phone → Watch: [com.rumi.hermesvoice.core.settings.WatchSettings] JSON
 * - `/hv/v1/reader/request`      message, Watch → Phone, and `/hv/v1/reader/response`, Phone → Watch:
 *                                the conversation reader ([ReaderRequest], [ReaderResponse])
 * - `/hv/v1/wake/claim`          message, Watch → Phone, and `/hv/v1/wake/verdict`, Phone → Watch:
 *                                wake arbitration when both devices listen
 *                                ([com.rumi.hermesvoice.core.wake.WakeAdmission])
 */
object WatchLinkPaths {
    const val TURN_PREFIX = "/hv/v1/turn/"
    const val STATE = "/hv/v1/state"
    const val PLAY_PREFIX = "/hv/v1/play/"
    const val PLAYED = "/hv/v1/played"

    /** Watch → Phone: the player's real position while one clip plays ([PlayProgress]). Optional: an older Watch never sends it. */
    const val PLAY_PROGRESS = "/hv/v1/play_progress"
    const val STOP = "/hv/v1/stop"
    const val CANCEL = "/hv/v1/cancel"
    const val DIAG_REQUEST = "/hv/v1/diag/request"
    const val DIAG_RESPONSE = "/hv/v1/diag/response"
    const val NAVIGATE = "/hv/v1/navigate"
    const val SETTINGS = "/hv/v1/settings"
    const val READER_REQUEST = "/hv/v1/reader/request"
    const val READER_RESPONSE = "/hv/v1/reader/response"

    /** Watch → Phone: claim, renew or release the wake episode ("Both"); Phone → that Watch: the verdict. */
    const val WAKE_CLAIM = "/hv/v1/wake/claim"
    const val WAKE_VERDICT = "/hv/v1/wake/verdict"

    /** Phone → Watch data item: how many wake requests have been answered (see `WakeEpochItem`). */
    const val WAKE_EPOCH = "/hv/v1/wake/epoch"
    const val CAPABILITY_PHONE = "hermes_voice_phone"
    const val CAPABILITY_WATCH = "hermes_voice_watch"

    private val TURN_ID = Regex("^[A-Za-z0-9-]{8,64}$")

    fun isValidTurnId(turnId: String): Boolean = TURN_ID.matches(turnId)

    fun turnPath(turnId: String): String {
        require(isValidTurnId(turnId)) { "invalid turn id" }
        return TURN_PREFIX + turnId
    }

    fun playPath(turnId: String, sequence: Int): String {
        require(isValidTurnId(turnId) && sequence >= 0) { "invalid play target" }
        return "$PLAY_PREFIX$turnId/$sequence"
    }

    /** The turn id a `/hv/v1/turn/<id>` path names, or null for anything else. */
    fun turnIdFromPath(path: String): String? =
        path.takeIf { it.startsWith(TURN_PREFIX) }?.removePrefix(TURN_PREFIX)?.takeIf(::isValidTurnId)
}

class LinkProtocolException(message: String) : Exception(message)

/** Reads a Data Layer channel without ever buffering more than [limit] bytes. */
object BoundedRead {
    /** The whole stream, or null when it is longer than [limit] (the rest is not read). */
    fun readAtMost(input: InputStream, limit: Int): ByteArray? = input.use { stream ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (out.size() + read > limit) return null
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    }

    /** Upper bound of one encoded [LinkFrame]. */
    const val FRAME_LIMIT = LinkFrame.MAX_PAYLOAD_BYTES + LinkFrame.MAX_HEADER_BYTES + 8
}

/** `HVL1` + u32 header length + UTF-8 JSON header + payload bytes. Bounded on both parts. */
class LinkFrame(val header: JSONObject, val payload: ByteArray) {
    fun encode(): ByteArray {
        val headerBytes = header.toString().toByteArray(StandardCharsets.UTF_8)
        require(headerBytes.size <= MAX_HEADER_BYTES) { "link header too large" }
        require(payload.size <= MAX_PAYLOAD_BYTES) { "link payload too large" }
        val out = ByteArrayOutputStream(8 + headerBytes.size + payload.size)
        DataOutputStream(out).apply {
            write(MAGIC)
            writeInt(headerBytes.size)
            write(headerBytes)
            write(payload)
            flush()
        }
        return out.toByteArray()
    }

    companion object {
        val MAGIC = byteArrayOf('H'.code.toByte(), 'V'.code.toByte(), 'L'.code.toByte(), '1'.code.toByte())
        const val MAX_HEADER_BYTES = 4 * 1024
        const val MAX_PAYLOAD_BYTES = 25 * 1024 * 1024

        fun decode(bytes: ByteArray): LinkFrame {
            if (bytes.size < 8 || !bytes.copyOfRange(0, 4).contentEquals(MAGIC)) throw LinkProtocolException("not a link frame")
            val headerLength = ByteBuffer.wrap(bytes, 4, 4).int
            if (headerLength <= 0 || headerLength > MAX_HEADER_BYTES || 8 + headerLength > bytes.size) {
                throw LinkProtocolException("bad link header length")
            }
            val payloadLength = bytes.size - 8 - headerLength
            if (payloadLength > MAX_PAYLOAD_BYTES) throw LinkProtocolException("link payload too large")
            val header = try {
                JSONObject(String(bytes, 8, headerLength, StandardCharsets.UTF_8))
            } catch (_: JSONException) {
                throw LinkProtocolException("link header is not JSON")
            }
            return LinkFrame(header, bytes.copyOfRange(8 + headerLength, bytes.size))
        }
    }
}

enum class TurnTrigger { PUSH_TO_TALK, WAKE_PHRASE }

/**
 * A Watch-captured turn: the header of a `/hv/v1/turn/<id>` frame plus its payload. The payload
 * is a WAV recording, or, for a wake-phrase request the Watch's speech recognizer already heard
 * in the same breath as the wake phrase, that request as UTF-8 text ([recognizedText]).
 */
class WatchTurnUpload(
    val turnId: String,
    val trigger: TurnTrigger,
    val mimeType: String,
    val audio: ByteArray,
    /** The wake claim this wake-phrase turn was made under when both devices listen; else null. */
    val wakeClaimId: String? = null,
    /**
     * The conversation selected on the Watch when it sent this turn (a stored session id the
     * Phone's reader gave it), or null. Used only while the Phone's routing is off, and only if it
     * is one of the Phone's active conversations; optional, so older Phones simply ignore it.
     */
    val target: String? = null,
    /**
     * The Watch's conversation-selection generation when this request began (see [WatchNavigationGuard]); the Phone echoes it in
     * [WatchNavigation] so the Watch can tell that the user has navigated since. Optional: an older Watch sends none and then
     * never receives a navigation.
     */
    val selectionGeneration: Long? = null,
) {
    val recognizedText: String? get() = if (mimeType == MIME_TEXT) String(audio, StandardCharsets.UTF_8).trim() else null

    fun toFrame(): LinkFrame = LinkFrame(
        JSONObject().put("v", 1).put("turn_id", turnId).put("trigger", trigger.name).put("mime", mimeType).put("bytes", audio.size)
            .apply {
                if (wakeClaimId != null) put("claim", wakeClaimId)
                if (target != null) put("target", target)
                if (selectionGeneration != null) put("sel_gen", selectionGeneration)
            },
        audio,
    )

    companion object {
        const val MIME_WAV = "audio/wav"
        const val MIME_TEXT = "text/plain; charset=utf-8"
        const val MAX_RECOGNIZED_CHARS = com.rumi.hermesvoice.core.wake.WakeContract.MAX_REQUEST_CHARS

        fun recognized(turnId: String, text: String, wakeClaimId: String? = null, target: String? = null,
                       selectionGeneration: Long? = null): WatchTurnUpload =
            WatchTurnUpload(turnId, TurnTrigger.WAKE_PHRASE, MIME_TEXT, text.trim().toByteArray(StandardCharsets.UTF_8), wakeClaimId, target,
                selectionGeneration)

        /** Validates the frame against the channel path it arrived on; anything off is rejected. */
        fun fromFrame(path: String, frame: LinkFrame): WatchTurnUpload {
            val pathTurnId = WatchLinkPaths.turnIdFromPath(path) ?: throw LinkProtocolException("not a turn path")
            val header = frame.header
            if (header.optInt("v") != 1) throw LinkProtocolException("unsupported turn version")
            if (header.optString("turn_id") != pathTurnId) throw LinkProtocolException("turn id does not match path")
            val mime = header.optString("mime")
            if (mime != MIME_WAV && mime != MIME_TEXT) throw LinkProtocolException("turn payload must be $MIME_WAV or text")
            if (header.optInt("bytes", -1) != frame.payload.size) throw LinkProtocolException("turn payload length mismatch")
            val trigger = runCatching { TurnTrigger.valueOf(header.optString("trigger")) }.getOrNull()
                ?: throw LinkProtocolException("unknown turn trigger")
            val claim = when (val raw = header.opt("claim")) {
                null -> null
                is String -> raw.takeIf { trigger == TurnTrigger.WAKE_PHRASE && com.rumi.hermesvoice.core.wake.WakeClaimMessage.isValidClaimId(it) }
                    ?: throw LinkProtocolException("bad wake claim")
                else -> throw LinkProtocolException("bad wake claim")
            }
            val target = when (val raw = header.opt("target")) {
                null -> null
                is String -> raw.takeIf(DestinationAllowlist::isValidSessionId) ?: throw LinkProtocolException("bad target")
                else -> throw LinkProtocolException("bad target")
            }
            val selectionGeneration = when (val raw = header.opt("sel_gen")) {
                null -> null
                is Int, is Long -> (raw as Number).toLong().takeIf { it >= 0 } ?: throw LinkProtocolException("bad selection generation")
                else -> throw LinkProtocolException("bad selection generation")
            }
            val upload = WatchTurnUpload(pathTurnId, trigger, mime, frame.payload, claim, target, selectionGeneration)
            if (mime == MIME_WAV) {
                if (frame.payload.size <= WAV_HEADER_BYTES) throw LinkProtocolException("turn audio is empty")
            } else {
                if (trigger != TurnTrigger.WAKE_PHRASE) throw LinkProtocolException("only wake-phrase requests may be text")
                val text = upload.recognizedText.orEmpty()
                if (text.isEmpty() || text.length > MAX_RECOGNIZED_CHARS || text.contains('\uFFFD')) {
                    throw LinkProtocolException("recognized request is empty, too long or not UTF-8")
                }
            }
            return upload
        }

        private const val WAV_HEADER_BYTES = 44
    }
}

/** Phone → Watch talk-state projection. [terminal] ends the turn on the Watch UI. */
data class TurnStateMessage(val turnId: String, val stage: String, val detail: String, val terminal: Boolean) {
    fun encode(): ByteArray = JSONObject().put("turn_id", turnId).put("stage", stage).put("detail", detail.take(MAX_DETAIL))
        .put("terminal", terminal).toString().toByteArray(StandardCharsets.UTF_8)

    companion object {
        const val MAX_DETAIL = 280

        fun decode(bytes: ByteArray): TurnStateMessage? = runCatching {
            val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
            TurnStateMessage(json.getString("turn_id"), json.getString("stage"), json.optString("detail").take(MAX_DETAIL),
                json.optBoolean("terminal"))
        }.getOrNull()?.takeIf { WatchLinkPaths.isValidTurnId(it.turnId) }
    }
}

/**
 * Phone → Watch, after a Watch-originated routed request was DELIVERED (the destination accepted the transcript; a router
 * decision, an acknowledgement or a failed delivery never sends one): select [sessionId] for the next visit.
 * `{"v":1,"turn_id":…,"session_id":…,"gen":<selection generation the upload carried>,"created":<new conversation>}`.
 * Strict: a wrong type, an invalid turn or session id or a negative generation decodes to null; unknown fields are ignored.
 */
data class WatchNavigation(val turnId: String, val sessionId: String, val generation: Long, val created: Boolean = false) {
    init {
        require(WatchLinkPaths.isValidTurnId(turnId) && DestinationAllowlist.isValidSessionId(sessionId) && generation >= 0) { "invalid navigation" }
    }

    fun encode(): ByteArray = JSONObject().put("v", 1).put("turn_id", turnId).put("session_id", sessionId).put("gen", generation)
        .put("created", created).toString().toByteArray(StandardCharsets.UTF_8)

    companion object {
        fun decode(bytes: ByteArray): WatchNavigation? = runCatching {
            val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
            if (json.opt("v") != 1) return@runCatching null
            val turnId = json.opt("turn_id") as? String ?: return@runCatching null
            val sessionId = json.opt("session_id") as? String ?: return@runCatching null
            val generation = when (val raw = json.opt("gen")) { is Int, is Long -> (raw as Number).toLong(); else -> return@runCatching null }
            val created = when (val raw = json.opt("created")) { null -> false; is Boolean -> raw; else -> return@runCatching null }
            if (!WatchLinkPaths.isValidTurnId(turnId) || !DestinationAllowlist.isValidSessionId(sessionId) || generation < 0) return@runCatching null
            WatchNavigation(turnId, sessionId, generation, created)
        }.getOrNull()
    }
}

/**
 * The Watch's guard for a [WatchNavigation]: it applies only to a turn THIS Watch started (a Phone-origin or unknown turn never
 * moves it), only once, only from the node the request went to, only for the newest request, and only while the user has not
 * navigated (opened a conversation or changed screen) since that request began (the selection generation it carried).
 * A Stop forgets every pending request. In memory only: a Watch process restart forgets pending requests, so a navigation
 * that arrives afterwards is ignored.
 */
class WatchNavigationGuard(private val maxTracked: Int = 8) {
    enum class Verdict { APPLY, UNKNOWN_TURN, DUPLICATE, WRONG_NODE, SUPERSEDED_TURN, STALE_SELECTION }

    private class Pending(val generation: Long, var nodeId: String? = null)

    private val pending = LinkedHashMap<String, Pending>()
    private val decided = LinkedHashSet<String>()
    private var generation = 0L
    private var newest: String? = null

    /** The generation a request that begins now carries; the user's navigation counter. */
    @Synchronized fun generation(): Long = generation

    /** A local request began (recording started); it becomes the newest, and returns the generation it carries. */
    @Synchronized fun onTurnStarted(turnId: String): Long {
        pending.getOrPut(turnId) { Pending(generation) }
        newest = turnId
        while (pending.size > maxTracked) pending.remove(pending.keys.first())
        return pending.getValue(turnId).generation
    }

    /** The generation [turnId] carries (registering it as the newest request now if it was not started through [onTurnStarted]). */
    @Synchronized fun generationFor(turnId: String): Long = pending[turnId]?.generation ?: onTurnStarted(turnId)

    /** The Phone node [turnId] was uploaded to. */
    @Synchronized fun onUploadNode(turnId: String, nodeId: String) { pending[turnId]?.nodeId = nodeId }

    /** The user opened a conversation or changed screens: every older request stops moving the selection. */
    @Synchronized fun onUserNavigation() { generation += 1 }

    /** A Watch Stop: nothing pending may move the selection afterwards. */
    @Synchronized fun onStopped() { pending.clear(); newest = null }

    @Synchronized fun accept(navigation: WatchNavigation, sourceNodeId: String): Verdict {
        if (navigation.turnId in decided) return Verdict.DUPLICATE
        val request = pending[navigation.turnId] ?: return Verdict.UNKNOWN_TURN
        if (request.nodeId != null && request.nodeId != sourceNodeId) return Verdict.WRONG_NODE
        val verdict = when {
            navigation.generation != request.generation -> Verdict.STALE_SELECTION
            newest != navigation.turnId -> Verdict.SUPERSEDED_TURN
            request.generation != generation -> Verdict.STALE_SELECTION
            else -> Verdict.APPLY
        }
        pending.remove(navigation.turnId)
        decided += navigation.turnId
        while (decided.size > MAX_DECIDED) decided.remove(decided.first())
        return verdict
    }

    private companion object {
        const val MAX_DECIDED = 64
    }
}

/** One utterance for the Watch speaker. */
class PlayRequest(
    val turnId: String,
    val sequence: Int,
    val role: String,
    val mimeType: String,
    val audio: ByteArray,
    /**
     * A LATER reply (one that arrived after its turn was answered): a Watch that is recording refuses
     * it with [PlayedAck.BUSY_RECORDING] instead of playing it over the recording. Optional header
     * field; an older Watch ignores it.
     */
    val later: Boolean = false,
    /**
     * The request's OWN reply, which may arrive while the Watch records another request (a new request is accepted while an
     * earlier one waits): it is refused like a later reply while recording, but it is not a later reply. Optional header
     * field "defer"; an older Watch ignores it.
     */
    val deferrable: Boolean = false,
) {
    /** A recording Watch refuses this utterance instead of playing it over the recording. */
    val refusedWhileRecording: Boolean get() = later || deferrable

    fun toFrame(): LinkFrame = LinkFrame(
        JSONObject().put("v", 1).put("turn_id", turnId).put("seq", sequence).put("role", role).put("mime", mimeType)
            .put("bytes", audio.size).apply { if (later) put("later", true); if (deferrable) put("defer", true) },
        audio,
    )

    companion object {
        fun fromFrame(frame: LinkFrame): PlayRequest {
            val header = frame.header
            val turnId = header.optString("turn_id")
            if (header.optInt("v") != 1 || !WatchLinkPaths.isValidTurnId(turnId)) throw LinkProtocolException("bad play header")
            val mime = header.optString("mime")
            if (!mime.startsWith("audio/")) throw LinkProtocolException("play payload must be audio")
            if (header.optInt("bytes", -1) != frame.payload.size || frame.payload.isEmpty()) {
                throw LinkProtocolException("play audio length mismatch")
            }
            return PlayRequest(turnId, header.optInt("seq", -1).also { if (it < 0) throw LinkProtocolException("bad seq") },
                header.optString("role"), mime, frame.payload, later = header.optBoolean("later", false),
                deferrable = header.optBoolean("defer", false))
        }
    }
}

/** Watch → Phone: playback of (turnId, seq) finished ([ok]) or failed/was stopped. */
data class PlayedAck(val turnId: String, val sequence: Int, val ok: Boolean, val error: String = "") {
    fun encode(): ByteArray = JSONObject().put("turn_id", turnId).put("seq", sequence).put("ok", ok).put("error", error.take(120))
        .toString().toByteArray(StandardCharsets.UTF_8)

    companion object {
        /**
         * The [error] of a later reply the Watch did not play because it is recording (refused at
         * once, or stopped when a recording started). Not played and not failed: the Phone tries again.
         */
        const val BUSY_RECORDING = "busy_recording"

        fun decode(bytes: ByteArray): PlayedAck? = runCatching {
            val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
            PlayedAck(json.getString("turn_id"), json.getInt("seq"), json.getBoolean("ok"), json.optString("error"))
        }.getOrNull()
    }
}

/**
 * Watch → Phone: where the Watch's player really is in clip ([turnId], [sequence]): [positionMs] of [durationMs], both
 * read from the player (never estimated). The Phone counts one only when it is for the exact clip it waits for, from the
 * node it sent it to, with a duration that never changes and a position that is strictly further than any it accepted
 * before; anything else (a repeat, an older position, another clip or node, a position beyond the duration) is ignored,
 * so a heartbeat of a stalled or dead player never extends the wait.
 */
data class PlayProgress(val turnId: String, val sequence: Int, val positionMs: Long, val durationMs: Long) {
    fun encode(): ByteArray = JSONObject().put("turn_id", turnId).put("seq", sequence).put("pos", positionMs).put("dur", durationMs)
        .toString().toByteArray(StandardCharsets.UTF_8)

    companion object {
        /** The longest clip duration a Watch may report (six hours); a larger value is not a real clip. */
        const val MAX_DURATION_MS = 6 * 60 * 60_000L

        fun decode(bytes: ByteArray): PlayProgress? = runCatching {
            if (bytes.size > 512) return null
            val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
            val turnId = json.getString("turn_id")
            if (!WatchLinkPaths.isValidTurnId(turnId)) return null
            val progress = PlayProgress(turnId, json.getInt("seq"), json.getLong("pos"), json.getLong("dur"))
            progress.takeIf { it.sequence >= 0 && it.durationMs in 1..MAX_DURATION_MS && it.positionMs in 0..it.durationMs }
        }.getOrNull()
    }
}
