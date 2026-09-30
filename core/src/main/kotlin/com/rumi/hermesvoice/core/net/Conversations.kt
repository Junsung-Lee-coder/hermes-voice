package com.rumi.hermesvoice.core.net

import com.rumi.hermesvoice.core.HermesProtocolException
import com.rumi.hermesvoice.core.voice.RecipientEvent
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** A file the user picked on the Phone, sent through Hermes's own attach RPCs (never a gateway-visible path). */
class OutgoingAttachment(val name: String, val mimeType: String, val bytes: ByteArray) {
    override fun toString(): String = "OutgoingAttachment(name=$name, mimeType=$mimeType, bytes=${bytes.size})"
}

enum class AttachmentKind { IMAGE, FILE }

/**
 * Maps a picked file onto the verified Hermes attachment mechanisms:
 * - images → `image.attach_bytes` (queued on the live session; consumed by the next `prompt.submit`,
 *   snapshotted with the prompt if the session is busy and the prompt is queued),
 * - everything else → `file.attach` with a base64 `data_url` (staged into the session's
 *   `attachments/` dir; the returned `@file:` ref is prepended to the prompt text).
 * `POST /api/files/upload` is deliberately NOT used: it stores a file but does not attach it to a turn.
 */
object AttachmentPolicy {
    /** Per file. Kept under the pre-v5 gateway's 16 MiB WebSocket frame after base64 inflation. */
    const val MAX_BYTES = 10 * 1024 * 1024
    const val MAX_ATTACHMENTS = 6
    private val IMAGE_MIMES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
    private val CONTROL = Regex("[\\p{Cntrl}]+")

    fun kind(attachment: OutgoingAttachment): AttachmentKind =
        if (attachment.mimeType.substringBefore(';').trim().lowercase(Locale.ROOT) in IMAGE_MIMES) AttachmentKind.IMAGE else AttachmentKind.FILE

    /** Basename only, no control characters; Hermes sanitizes again server-side. */
    fun safeName(raw: String): String =
        raw.replace('\\', '/').substringAfterLast('/').replace(CONTROL, "_").trim().trim('.').take(120).ifEmpty { "attachment" }

    fun validate(attachments: List<OutgoingAttachment>) {
        require(attachments.size <= MAX_ATTACHMENTS) { "at most $MAX_ATTACHMENTS attachments per message" }
        attachments.forEach { attachment ->
            require(attachment.bytes.isNotEmpty()) { "${safeName(attachment.name)} is empty" }
            require(attachment.bytes.size <= MAX_BYTES) { "${safeName(attachment.name)} exceeds ${MAX_BYTES / (1024 * 1024)} MiB" }
        }
    }
}

data class CreatedSession(val runtimeId: String, val storedSessionId: String)

/** A prompt accepted by a Hermes session; [collect] streams that turn's recipient events until terminal. */
interface SubmittedTurn {
    /** Hermes's `prompt.submit` status (`streaming`, `queued`, ...). */
    val submitStatus: String

    /**
     * False when Hermes accepted the prompt but its events cannot be attributed to it with
     * certainty; [collect] then refuses (fail closed) rather than speak another turn's reply.
     */
    val attributable: Boolean

    suspend fun collect(timeoutMs: Long, onEvent: suspend (RecipientEvent) -> Unit)
    fun release()
}

interface HermesConversationPort {
    /**
     * `session.create` with the app's [source] tag and a hidden seed row, so the DB row (with
     * source and title) exists immediately instead of on the first prompt.
     */
    suspend fun create(source: String, title: String, seedInstruction: String, hidden: Boolean): CreatedSession

    suspend fun submit(storedSessionId: String, text: String, attachments: List<OutgoingAttachment> = emptyList()): SubmittedTurn
}

/**
 * `session.resume` (stored id → runtime id), optional attach RPCs, then `prompt.submit` with
 * `queued: true`, so a busy destination queues the turn instead of redirecting/steering its live
 * turn. Events carry no per-prompt id, so attribution is by order: a `streaming` submit owns the
 * next `message.start`; a `queued` submit owns the turn after the in-flight one, and only when the
 * resume snapshot shows no other queued prompt. Anything else is returned as delivered but not
 * [SubmittedTurn.attributable].
 */
class GatewayConversationPort(
    private val profile: String? = null,
    private val connections: suspend () -> GatewayRpc,
) : HermesConversationPort {
    /** Every RPC is stamped with the same `profile` the REST calls use, so both hit one session store. */
    private fun params(): JSONObject = JSONObject().apply { if (!profile.isNullOrBlank()) put("profile", profile) }

    override suspend fun create(source: String, title: String, seedInstruction: String, hidden: Boolean): CreatedSession {
        val params = params().put("source", source).put("title", title).put("hidden", hidden)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", seedInstruction)
                .put("display_kind", "hidden")))
        val result = connections().call("session.create", params)
        val runtimeId = result.optString("session_id")
        val storedId = result.optString("stored_session_id")
        if (runtimeId.isBlank() || storedId.isBlank()) throw HermesProtocolException("session.create returned no session ids")
        return CreatedSession(runtimeId, storedId)
    }

    override suspend fun submit(storedSessionId: String, text: String, attachments: List<OutgoingAttachment>): SubmittedTurn {
        AttachmentPolicy.validate(attachments)
        val connection = connections()
        val resumed = connection.call("session.resume",
            params().put("session_id", storedSessionId).put("omit_messages", true))
        val runtimeId = resumed.optString("session_id").ifBlank {
            throw HermesProtocolException("session.resume returned no runtime session id")
        }
        val alreadyQueued = resumed.has("queued") && !resumed.isNull("queued")
        val prompt = attach(connection, runtimeId, text, attachments)
        val subscription = connection.subscribe(runtimeId)
        try {
            val result = connection.call("prompt.submit",
                params().put("session_id", runtimeId).put("text", prompt).put("queued", true))
            val status = result.optString("status")
            val turnsToSkip = when {
                status == "streaming" -> 0
                status == "queued" && !alreadyQueued -> 1
                else -> -1
            }
            if (turnsToSkip < 0) subscription.close()
            return GatewaySubmittedTurn(subscription, status, turnsToSkip)
        } catch (error: Throwable) {
            subscription.close()
            throw error
        }
    }

    /** Attaches in order; on any failure, queued images are detached so they cannot leak into a later turn. */
    private suspend fun attach(connection: GatewayRpc, runtimeId: String, text: String, attachments: List<OutgoingAttachment>): String {
        if (attachments.isEmpty()) return text
        val queuedImages = mutableListOf<String>()
        val refs = mutableListOf<String>()
        try {
            for (attachment in attachments) {
                val name = AttachmentPolicy.safeName(attachment.name)
                val encoded = Base64.getEncoder().encodeToString(attachment.bytes)
                when (AttachmentPolicy.kind(attachment)) {
                    AttachmentKind.IMAGE -> {
                        val result = connection.call("image.attach_bytes", params().put("session_id", runtimeId)
                            .put("content_base64", encoded).put("filename", name), ATTACH_TIMEOUT_MS)
                        if (!result.optBoolean("attached")) throw HermesProtocolException("image $name was not attached")
                        result.optString("path").takeIf { it.isNotBlank() }?.let(queuedImages::add)
                    }
                    AttachmentKind.FILE -> {
                        val mime = attachment.mimeType.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
                        val result = connection.call("file.attach", params().put("session_id", runtimeId)
                            .put("data_url", "data:$mime;base64,$encoded").put("name", name), ATTACH_TIMEOUT_MS)
                        val ref = result.optString("ref_text")
                        if (!result.optBoolean("attached") || !ref.startsWith("@file:")) {
                            throw HermesProtocolException("file $name was not attached")
                        }
                        refs += ref
                    }
                }
            }
        } catch (error: Throwable) {
            for (path in queuedImages) {
                runCatching { connection.call("image.detach", params().put("session_id", runtimeId).put("path", path)) }
            }
            throw error
        }
        if (refs.isEmpty()) return text
        return (refs + text.ifBlank { null }).filterNotNull().joinToString("\n")
    }

    companion object {
        const val ATTACH_TIMEOUT_MS = 120_000L
    }
}

private class GatewaySubmittedTurn(
    private val subscription: GatewaySubscription,
    override val submitStatus: String,
    private var turnsToSkip: Int,
) : SubmittedTurn {
    override val attributable: Boolean = turnsToSkip >= 0

    override suspend fun collect(timeoutMs: Long, onEvent: suspend (RecipientEvent) -> Unit) {
        if (!attributable) throw HermesProtocolException("prompt.submit status '$submitStatus' cannot be attributed to this turn")
        try {
            withTimeout(timeoutMs) {
                var owned = false
                for (event in subscription.events) {
                    when (event.type) {
                        "message.start" -> if (turnsToSkip == 0) owned = true
                        "message.complete" -> if (!owned) {
                            if (turnsToSkip > 0) turnsToSkip--
                        } else {
                            val payload = event.payload ?: JSONObject()
                            onEvent(RecipientEvent.Complete(payloadText(payload),
                                payload.optString("status").ifBlank { null }))
                            return@withTimeout
                        }
                        "message.interim" -> if (owned) onEvent(RecipientEvent.Interim(payloadText(event.payload ?: JSONObject())))
                        "error" -> if (owned) {
                            throw HermesProtocolException("destination turn failed: ${event.payload?.optString("message").orEmpty().take(200)}")
                        }
                    }
                }
                throw HermesProtocolException("gateway event stream ended before message.complete")
            }
        } catch (timeout: TimeoutCancellationException) {
            throw HermesProtocolException("no message.complete within ${timeoutMs}ms", timeout)
        } finally {
            release()
        }
    }

    override fun release() = subscription.close()

    private fun payloadText(payload: JSONObject): String = when (val text = payload.opt("text")) {
        null, JSONObject.NULL -> ""
        is String -> text
        else -> text.toString()
    }
}
