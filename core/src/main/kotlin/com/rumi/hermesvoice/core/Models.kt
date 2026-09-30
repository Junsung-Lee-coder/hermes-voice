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

class HermesHttpException(val status: Int, message: String) : HermesException(message)

class HermesProtocolException(message: String, cause: Throwable? = null) : HermesException(message, cause)

class HermesRpcException(val code: Int, message: String) : HermesException(message)

/** The originating device could not play (or confirm playing) an utterance. */
class HermesPlaybackException(message: String) : HermesException(message)

/** A session id the app did not create (or that is not tagged with the app source) was used. Fail closed. */
class SessionNotOwnedException(message: String) : HermesException(message)
