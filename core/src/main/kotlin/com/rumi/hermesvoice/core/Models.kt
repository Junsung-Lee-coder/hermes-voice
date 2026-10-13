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

class HermesHttpException(val status: Int, message: String, val sttCode: com.rumi.hermesvoice.core.net.SttCode? = null) : HermesException(message)

open class HermesProtocolException(message: String, cause: Throwable? = null) : HermesException(message, cause)

/** The gateway socket was closed by the server with a WebSocket close code (the code, not the reason text, is what diagnostics keep). */
class HermesGatewayClosedException(val closeCode: Int, message: String) : HermesProtocolException(message)

/** No gateway activity for the whole inactivity bound while waiting for a reply. */
class HermesInactivityTimeoutException(message: String) : HermesProtocolException(message)

/** The gateway event stream ended before the reply completed. */
class HermesStreamEndedException(message: String) : HermesProtocolException(message)

/** Hermes itself reported the turn as failed (an error event or an error completion). */
class HermesTurnFailedException(message: String) : HermesProtocolException(message)

class HermesRpcException(val code: Int, message: String) : HermesException(message)

/** The originating device could not play (or confirm playing) an utterance. */
open class HermesPlaybackException(message: String) : HermesException(message)

/** A playback failure that happened before any speech was heard and is worth trying again (a player or route that was not ready yet). */
class HermesPlaybackTransientException(message: String) : HermesPlaybackException(message)

/** The device didn't play a later reply because its microphone is recording: not played, to be tried again. */
class HermesPlaybackBusyException(message: String) : HermesPlaybackException(message)

/** A session id the app did not create (or that is not tagged with the app source) was used. Fail closed. */
class SessionNotOwnedException(message: String) : HermesException(message)
