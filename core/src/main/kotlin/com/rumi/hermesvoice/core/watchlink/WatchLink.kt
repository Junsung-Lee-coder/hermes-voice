package com.rumi.hermesvoice.core.watchlink

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
 * - `/hv/v1/state`               message, Phone → Watch: [TurnStateMessage]
 * - `/hv/v1/play/<turnId>/<seq>` channel, Phone → Watch: [LinkFrame] {play header} + audio bytes
 * - `/hv/v1/played`              message, Watch → Phone: [PlayedAck] (sent when playback ends)
 * - `/hv/v1/stop`                message, Phone → Watch: stop playing this turn now (interruption)
 * - `/hv/v1/settings`            data item, Phone → Watch: [com.rumi.hermesvoice.core.settings.WatchSettings] JSON
 * - `/hv/v1/reader/request`      message, Watch → Phone, and `/hv/v1/reader/response`, Phone → Watch:
 *                                the conversation reader ([ReaderRequest], [ReaderResponse])
 */
object WatchLinkPaths {
    const val TURN_PREFIX = "/hv/v1/turn/"
    const val STATE = "/hv/v1/state"
    const val PLAY_PREFIX = "/hv/v1/play/"
    const val PLAYED = "/hv/v1/played"
    const val STOP = "/hv/v1/stop"
    const val SETTINGS = "/hv/v1/settings"
    const val READER_REQUEST = "/hv/v1/reader/request"
    const val READER_RESPONSE = "/hv/v1/reader/response"
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
class WatchTurnUpload(val turnId: String, val trigger: TurnTrigger, val mimeType: String, val audio: ByteArray) {
    val recognizedText: String? get() = if (mimeType == MIME_TEXT) String(audio, StandardCharsets.UTF_8).trim() else null

    fun toFrame(): LinkFrame = LinkFrame(
        JSONObject().put("v", 1).put("turn_id", turnId).put("trigger", trigger.name).put("mime", mimeType).put("bytes", audio.size),
        audio,
    )

    companion object {
        const val MIME_WAV = "audio/wav"
        const val MIME_TEXT = "text/plain; charset=utf-8"
        const val MAX_RECOGNIZED_CHARS = 1_000

        fun recognized(turnId: String, text: String): WatchTurnUpload =
            WatchTurnUpload(turnId, TurnTrigger.WAKE_PHRASE, MIME_TEXT, text.trim().toByteArray(StandardCharsets.UTF_8))

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
            val upload = WatchTurnUpload(pathTurnId, trigger, mime, frame.payload)
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

/** One utterance for the Watch speaker. */
class PlayRequest(val turnId: String, val sequence: Int, val role: String, val mimeType: String, val audio: ByteArray) {
    fun toFrame(): LinkFrame = LinkFrame(
        JSONObject().put("v", 1).put("turn_id", turnId).put("seq", sequence).put("role", role).put("mime", mimeType)
            .put("bytes", audio.size),
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
                header.optString("role"), mime, frame.payload)
        }
    }
}

/** Watch → Phone: playback of (turnId, seq) finished ([ok]) or failed/was stopped. */
data class PlayedAck(val turnId: String, val sequence: Int, val ok: Boolean, val error: String = "") {
    fun encode(): ByteArray = JSONObject().put("turn_id", turnId).put("seq", sequence).put("ok", ok).put("error", error.take(120))
        .toString().toByteArray(StandardCharsets.UTF_8)

    companion object {
        fun decode(bytes: ByteArray): PlayedAck? = runCatching {
            val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
            PlayedAck(json.getString("turn_id"), json.getInt("seq"), json.getBoolean("ok"), json.optString("error"))
        }.getOrNull()
    }
}
