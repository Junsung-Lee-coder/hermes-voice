package com.rumi.hermesvoice.core.net

import com.rumi.hermesvoice.core.HermesException
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.UnknownHostException

/**
 * The closed set of speech-recognition failure codes the server's transcription provider emits on purpose, as
 * `[hermes-stt:<code>] <fixed text>`. Only these are ever recognized; a message that merely mentions a code, an unknown code,
 * or any other text is not one (a legacy "400" without the envelope stays an ordinary, non-retryable failure).
 */
enum class SttCode(val wire: String, val retryable: Boolean) {
    UNAVAILABLE("stt_unavailable", true),
    BUSY("stt_busy", true),
    TIMEOUT("stt_timeout", true),
    INPUT_REJECTED("input_rejected", false),
    AUTH_REJECTED("auth_rejected", false),
    TRANSCRIPTION_FAILED("transcription_failed", false),
    CONFIG_INVALID("config_invalid", false),
    CANCELLED("cancelled", false);

    companion object {
        fun fromWire(wire: String?): SttCode? = values().firstOrNull { it.wire == wire }
    }
}

object SttSafeCode {
    private const val PREFIX = "[hermes-stt:"
    private const val MAX_DETAIL = 200

    /**
     * The code of an intentionally emitted envelope, or null. The text must START with the envelope (a code quoted somewhere in
     * the middle of other text does not count), name an allowlisted code, and carry a short single-line fixed sentence after it.
     * Nothing else of the text is returned or kept.
     */
    fun parse(detail: String?): SttCode? {
        if (detail == null || detail.length > MAX_DETAIL || !detail.startsWith(PREFIX)) return null
        val close = detail.indexOf(']', PREFIX.length)
        if (close < 0) return null
        val code = SttCode.fromWire(detail.substring(PREFIX.length, close)) ?: return null
        val tail = detail.substring(close + 1)
        if (tail.isNotEmpty() && (tail[0] != ' ' || tail.any { it.isISOControl() })) return null
        return code
    }

    /** The fixed, user-facing wording of a code: never taken from the server's text. */
    fun message(code: SttCode): String = when (code) {
        SttCode.UNAVAILABLE -> "speech recognition is temporarily unavailable"
        SttCode.BUSY -> "speech recognition is busy"
        SttCode.TIMEOUT -> "speech recognition did not finish in time"
        SttCode.INPUT_REJECTED -> "the recording was rejected by the recognizer"
        SttCode.AUTH_REJECTED -> "the recognizer refused the request"
        SttCode.TRANSCRIPTION_FAILED -> "the recognizer could not transcribe this recording"
        SttCode.CONFIG_INVALID -> "speech recognition is not configured correctly"
        SttCode.CANCELLED -> "transcription was cancelled"
    }
}

/**
 * The closed set of temporary transport failures at the dashboard's speech-to-text HTTP boundary that are positively known to
 * leave nothing accepted: the name could not be resolved, the connection could not be made, an established connection was cut,
 * or a connect/read/write/call time ran out. Anything else (TLS trust, protocol violations, unrecognized I/O) is not in the set.
 */
enum class SttTransport(val code: SttCode) {
    DNS(SttCode.UNAVAILABLE),
    CONNECT(SttCode.UNAVAILABLE),
    RESET(SttCode.UNAVAILABLE),
    TIMEOUT(SttCode.TIMEOUT),
}

/**
 * A request to the speech route failed in transport in a way [SttTransport] names. The wording is fixed; the cause is kept
 * for TYPE-only diagnostics and its message is never read.
 */
class HermesSttTransportException(val transport: SttTransport, cause: IOException) :
    HermesException("speech server unreachable (${transport.name.lowercase()})", cause) {
    /** The code the recovery treats this failure as. It is the app's own conclusion, never a code the server emitted. */
    val sttCode: SttCode get() = transport.code
}

/** Classifies an I/O failure of the speech request by exception TYPE and phase; never by message text. */
object SttTransportClassifier {
    private const val MAX_DEPTH = 6

    /**
     * [callCanceled]: the OkHttp call had been cancelled (a Stop, or `Call.cancel`) before the failure surfaced. A cancelled call
     * can surface as several socket errors, none of which is a transport failure, so only a time-out (which also cancels the call
     * but is reported as [InterruptedIOException]) is still recognized then.
     */
    fun classify(error: IOException, callCanceled: Boolean): SttTransport? {
        if (chain(error).any { it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException }) return null
        if (error is InterruptedIOException) return SttTransport.TIMEOUT
        if (callCanceled) return null
        return when {
            error is UnknownHostException -> SttTransport.DNS
            error is ConnectException || error is NoRouteToHostException || error is PortUnreachableException -> SttTransport.CONNECT
            error is SocketException || error is EOFException -> SttTransport.RESET
            chain(error).any { it is SocketException || it is EOFException || it.javaClass.simpleName in RESET_TYPES } -> SttTransport.RESET
            else -> null
        }
    }

    private val RESET_TYPES = setOf("StreamResetException", "ConnectionShutdownException")

    private fun chain(error: Throwable): Sequence<Throwable> = generateSequence(error) { it.cause?.takeIf { next -> next !== it } }.take(MAX_DEPTH)
}
