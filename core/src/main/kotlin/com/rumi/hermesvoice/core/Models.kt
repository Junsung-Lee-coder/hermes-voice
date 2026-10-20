package com.rumi.hermesvoice.core

/**
 * Hermes Voice: the Phone talks to the Hermes dashboard's existing authenticated routes
 * (`/api/audio/transcribe`, `/api/audio/speak`, `/api/sessions`, the `/api/ws` JSON-RPC gateway) and the Watch talks only to
 * the Phone. Nothing here holds an API server key, password or administrator secret; the only
 * credential is the per-user bearer pair minted at runtime by the dashboard's RFC 8252 native
 * sign-in (see [com.rumi.hermesvoice.core.auth.HermesNativeSignIn]).
 */
enum class VoiceOrigin { PHONE, WATCH }

/** One spoken utterance of a voice turn. [ACK] is the router's acknowledgement, never a recipient response. */
enum class SpokenRole { ACK, FIRST, MIDDLE, FINAL }

/**
 * Recipient-response playback switches. Both default OFF and are independent. The FINAL response
 * and the routing acknowledgement are not configurable: they always play.
 */
data class ResponsePlaybackSettings(
    val playFirstResponse: Boolean = false,
    val playMiddleResponses: Boolean = false,
)

class SpokenAudio(val bytes: ByteArray, val mimeType: String) {
    override fun toString(): String = "SpokenAudio(mimeType=$mimeType, bytes=${bytes.size})"
}

open class HermesException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** No usable bearer session: the user must run the dashboard sign-in again. Never bypassed. */
class HermesAuthRequiredException(message: String) : HermesException(message)

class HermesHttpException(
    val status: Int,
    message: String,
    val sttCode: com.rumi.hermesvoice.core.net.SttCode? = null,
    /** The server's Retry-After for this answer in milliseconds, when it sent a usable one. */
    val retryAfterMs: Long? = null,
    /** True only for the non-success answer of a GET: the one kind of answer a read refresh may ask for again. */
    val readResponse: Boolean = false,
) : HermesException(message)

open class HermesProtocolException(message: String, cause: Throwable? = null) : HermesException(message, cause)

/** The gateway socket was closed by the server with a WebSocket close code (the code, not the reason text, is what diagnostics keep). */
class HermesGatewayClosedException(val closeCode: Int, message: String) : HermesProtocolException(message)

/** No gateway activity for the whole inactivity bound while waiting for a reply. */
class HermesInactivityTimeoutException(message: String) : HermesProtocolException(message)

/** The gateway event stream ended before the reply completed. */
class HermesStreamEndedException(message: String) : HermesProtocolException(message)

/** Hermes itself reported the turn as failed (an error event or an error completion). */
class HermesTurnFailedException(message: String) : HermesProtocolException(message)

/** The accepted turn's reply can never be matched to its request (the server restarted, dropped the events, or runs the session under another id). */
open class HermesReplyUnrecoverableException(message: String) : HermesProtocolException(message)

/** Queued ACK has no envelope identity; it is accepted but no final is attributable. */
class HermesQueuedAttributionException : HermesReplyUnrecoverableException(
    "Hermes accepted this message while busy, but this protocol cannot identify its queued reply. Open the conversation to check it; do not retry this request.")

/** A prompt frame may have reached Hermes; NEVER retry that same request. */
class HermesPromptUncertainException(cause: Throwable) : HermesProtocolException(
    "Your message may have reached Hermes, but its acceptance could not be confirmed. Open the conversation to check it; do not retry this request.", cause)

/** The WebSocket refused the prompt frame before taking it; a later explicit Retry is safe. */
class HermesPromptNotSentException(cause: Throwable) : HermesProtocolException("The message was not sent; the gateway connection was unavailable.", cause)

enum class SendDelivery { NOT_SENT, POSSIBLY_SENT, ACCEPTED }

/** False when the failure shows the reply is not coming (Hermes failed the turn, or it can't be recovered): then nothing is left to wait for. */
fun HermesException.replyMayStillArrive(): Boolean =
    this is HermesAuthRequiredException || (this is HermesProtocolException && this !is HermesTurnFailedException && this !is HermesReplyUnrecoverableException)

/** The gateway answered a call with a JSON-RPC error. [reason] is its machine-readable `data.reason` (bounded; "" when it sent none). */
open class HermesRpcException(val code: Int, message: String, val reason: String = "") : HermesException(message)

/**
 * `prompt.submit` was refused with code 4090 and reason SESSION_NOT_OWNED: another Hermes process (the TUI, Desktop, another gateway)
 * holds the session. The gateway refused before accepting the prompt, so nothing was sent. The message is written by this app.
 */
class SessionInUseException : HermesRpcException(CODE, MESSAGE, REASON) {
    companion object {
        const val CODE = 4090
        const val REASON = "SESSION_NOT_OWNED"
        const val MESSAGE = "This session is currently in use in the TUI or another Hermes runtime. " +
            "Release or close it there and retry; nothing was sent."
    }
}

/** The originating device could not play (or confirm playing) an utterance. */
open class HermesPlaybackException(message: String) : HermesException(message)

/** A playback failure that happened before any speech was heard and is worth trying again (a player or route that was not ready yet). */
class HermesPlaybackTransientException(message: String) : HermesPlaybackException(message)

/** The device didn't play a later reply because its microphone is recording: not played, to be tried again. */
class HermesPlaybackBusyException(message: String) : HermesPlaybackException(message)

/** A session id the app did not create (or that is not tagged with the app source) was used. Fail closed. */
class SessionNotOwnedException(message: String) : HermesException(message)
