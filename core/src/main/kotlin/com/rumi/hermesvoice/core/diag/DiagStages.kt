package com.rumi.hermesvoice.core.diag

import com.rumi.hermesvoice.core.HermesAuthRequiredException
import com.rumi.hermesvoice.core.HermesGatewayClosedException
import com.rumi.hermesvoice.core.HermesHttpException
import com.rumi.hermesvoice.core.HermesInactivityTimeoutException
import com.rumi.hermesvoice.core.HermesException
import com.rumi.hermesvoice.core.HermesRpcException
import com.rumi.hermesvoice.core.HermesStreamEndedException
import com.rumi.hermesvoice.core.HermesTurnFailedException
import com.rumi.hermesvoice.core.SessionNotOwnedException
import com.rumi.hermesvoice.core.net.SttCode
import com.rumi.hermesvoice.core.voice.SttRecoveryExhaustedException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext

/** Where in a request's life an observed event or failure happened. Fixed words only. */
enum class DiagStage(val wire: String) {
    PREPARE("prepare"), VERIFY("verify"), CONNECT("connect"), RESUME("resume"), UPLOAD("upload"), SUBMIT("submit"),
    WAIT("wait"), RECEIVE("receive"), STT("stt"), TTS("tts"), TRANSFER("transfer"), PLAYBACK("playback");

    companion object {
        private val byWire = values().associateBy { it.wire }
        fun fromWire(wire: String?): DiagStage? = byWire[wire]
    }
}

/**
 * The whitelisted failure categories. [TIMEOUT] keeps its phase in the event's [DiagStage]; [NO_NETWORK] is only chosen for a
 * DNS or connect failure while the connectivity snapshot said "no network" (context, never a gate); [LOCAL] is the app's own
 * refusal before anything was sent.
 */
enum class DiagCause(val wire: String) {
    NO_NETWORK("no_network"), DNS("dns"), CONNECT("connect"), TLS("tls"), TIMEOUT("timeout"), DISCONNECT("disconnect_io"),
    AUTH("auth"), REJECTED("rejected"), CANCELLED("cancelled"), LOCAL("local"), UNKNOWN("unknown"),

    /** The speech server reported itself temporarily unavailable, busy or too slow (a recoverable safe code), or kept doing so until recovery ran out. */
    STT_UNAVAILABLE("stt_unavailable");

    companion object {
        private val byWire = values().associateBy { it.wire }
        fun fromWire(wire: String?): DiagCause? = byWire[wire]
    }
}

enum class DiagModality(val wire: String) {
    TEXT("text"), ATTACHMENT("attachment"), VOICE("voice");

    companion object {
        private val byWire = values().associateBy { it.wire }
        fun fromWire(wire: String?): DiagModality? = byWire[wire]
    }
}

/** The connectivity snapshot taken when an event was recorded: context about the device, not a statement about the cause. */
enum class DiagConn(val wire: String) {
    NONE("none"), UNVALIDATED("unvalidated"), VALIDATED("validated"), UNKNOWN("unknown");

    companion object {
        private val byWire = values().associateBy { it.wire }
        fun fromWire(wire: String?): DiagConn? = byWire[wire]
    }
}

/** A failure as the three bounded things the diagnostics keep: the legacy class, the category and an explicit numeric code. */
data class DiagClassified(val fail: DiagFail, val cause: DiagCause, val status: Int?)

/**
 * Classification by exception TYPE (walking the cause chain) and explicit numeric codes of the app's own typed exceptions.
 * No message, cause text or stack is ever read, so nothing a server or library wrote can influence or reach a report.
 */
object DiagClassify {
    private const val MAX_DEPTH = 6
    const val MAX_STATUS = 99_999

    fun of(error: Throwable?, conn: DiagConn? = null): DiagClassified {
        if (error == null) return DiagClassified(DiagFail.UNKNOWN, DiagCause.UNKNOWN, null)
        var node: Throwable? = error
        var depth = 0
        var sawHermes = false
        while (node != null && depth < MAX_DEPTH) {
            decide(node, conn)?.let { return it }
            if (node is HermesException) sawHermes = true
            node = node.cause?.takeIf { it !== node }
            depth += 1
        }
        return if (sawHermes) DiagClassified(DiagFail.PROTOCOL, DiagCause.UNKNOWN, null) else DiagClassified(DiagFail.UNKNOWN, DiagCause.UNKNOWN, null)
    }

    /** The legacy coarse class that goes with a category (used where only the category is known). */
    fun failOf(cause: DiagCause): DiagFail = when (cause) {
        DiagCause.TIMEOUT -> DiagFail.TIMEOUT
        DiagCause.AUTH -> DiagFail.AUTH
        DiagCause.CANCELLED -> DiagFail.CANCELLED
        DiagCause.NO_NETWORK, DiagCause.DNS, DiagCause.CONNECT, DiagCause.DISCONNECT -> DiagFail.DISCONNECTED
        DiagCause.TLS -> DiagFail.IO
        DiagCause.REJECTED, DiagCause.LOCAL -> DiagFail.REFUSED
        DiagCause.UNKNOWN -> DiagFail.UNKNOWN
        DiagCause.STT_UNAVAILABLE -> DiagFail.BUSY
    }

    /** From a bare class simple name (all that the speech listener is given); never from a message. */
    fun causeOfName(simpleName: String?): DiagCause = when (simpleName) {
        "SocketTimeoutException", "TimeoutException", "TimeoutCancellationException", "HermesInactivityTimeoutException" -> DiagCause.TIMEOUT
        "UnknownHostException" -> DiagCause.DNS
        "ConnectException", "NoRouteToHostException" -> DiagCause.CONNECT
        "SSLException", "SSLHandshakeException", "CertificateException" -> DiagCause.TLS
        "SocketException", "IOException", "EOFException", "HermesStreamEndedException" -> DiagCause.DISCONNECT
        "HermesAuthRequiredException", "SecurityException" -> DiagCause.AUTH
        "HermesHttpException", "HermesRpcException" -> DiagCause.REJECTED
        "CancellationException" -> DiagCause.CANCELLED
        else -> DiagCause.UNKNOWN
    }

    private fun clampStatus(code: Int): Int = code.coerceIn(-MAX_STATUS, MAX_STATUS)

    private fun decide(error: Throwable, conn: DiagConn?): DiagClassified? = when (error) {
        is TimeoutCancellationException -> DiagClassified(DiagFail.TIMEOUT, DiagCause.TIMEOUT, null)
        is java.util.concurrent.CancellationException -> DiagClassified(DiagFail.CANCELLED, DiagCause.CANCELLED, null)
        is HermesAuthRequiredException -> DiagClassified(DiagFail.AUTH, DiagCause.AUTH, null)
        is SecurityException -> DiagClassified(DiagFail.AUTH, DiagCause.AUTH, null)
        is SttRecoveryExhaustedException -> DiagClassified(
            when (error.code) { SttCode.TIMEOUT -> DiagFail.TIMEOUT; SttCode.BUSY -> DiagFail.BUSY; else -> DiagFail.DISCONNECTED },
            DiagCause.STT_UNAVAILABLE, null)
        is HermesHttpException ->
            if (error.sttCode?.retryable == true) DiagClassified(
                when (error.sttCode) { SttCode.TIMEOUT -> DiagFail.TIMEOUT; SttCode.BUSY -> DiagFail.BUSY; else -> DiagFail.DISCONNECTED },
                DiagCause.STT_UNAVAILABLE, clampStatus(error.status))
            else if (error.status == 401 || error.status == 403) DiagClassified(DiagFail.AUTH, DiagCause.AUTH, clampStatus(error.status))
            else DiagClassified(DiagFail.REFUSED, DiagCause.REJECTED, clampStatus(error.status))
        is HermesRpcException -> DiagClassified(DiagFail.REFUSED, DiagCause.REJECTED, clampStatus(error.code))
        is HermesGatewayClosedException -> when (error.closeCode) {
            4401 -> DiagClassified(DiagFail.AUTH, DiagCause.AUTH, 4401)
            4403 -> DiagClassified(DiagFail.REFUSED, DiagCause.REJECTED, 4403)
            else -> DiagClassified(DiagFail.DISCONNECTED, DiagCause.DISCONNECT, clampStatus(error.closeCode))
        }
        is HermesInactivityTimeoutException -> DiagClassified(DiagFail.TIMEOUT, DiagCause.TIMEOUT, null)
        is HermesStreamEndedException -> DiagClassified(DiagFail.DISCONNECTED, DiagCause.DISCONNECT, null)
        is HermesTurnFailedException -> DiagClassified(DiagFail.REFUSED, DiagCause.REJECTED, null)
        is SessionNotOwnedException -> DiagClassified(DiagFail.REFUSED, DiagCause.LOCAL, null)
        is java.net.UnknownHostException -> DiagClassified(DiagFail.DISCONNECTED, if (conn == DiagConn.NONE) DiagCause.NO_NETWORK else DiagCause.DNS, null)
        is java.net.ConnectException, is java.net.NoRouteToHostException, is java.net.PortUnreachableException ->
            DiagClassified(DiagFail.DISCONNECTED, if (conn == DiagConn.NONE) DiagCause.NO_NETWORK else DiagCause.CONNECT, null)
        is javax.net.ssl.SSLException, is java.security.cert.CertificateException -> DiagClassified(DiagFail.IO, DiagCause.TLS, null)
        is java.net.SocketTimeoutException, is java.util.concurrent.TimeoutException -> DiagClassified(DiagFail.TIMEOUT, DiagCause.TIMEOUT, null)
        is java.io.IOException -> DiagClassified(if (error is java.net.SocketException) DiagFail.DISCONNECTED else DiagFail.IO, DiagCause.DISCONNECT, null)
        is IllegalArgumentException -> DiagClassified(DiagFail.REFUSED, DiagCause.LOCAL, null)
        else -> null
    }
}

/**
 * The stage a request has reached, carried in the coroutine context so a deep layer can mark where it is without changing any
 * signature or exception. Absent (the common case for voice and tests): marking does nothing.
 */
class DiagTrace(initial: DiagStage = DiagStage.PREPARE) : AbstractCoroutineContextElement(Key) {
    @Volatile var stage: DiagStage = initial
    @Volatile var attachments: Int = 0

    companion object Key : CoroutineContext.Key<DiagTrace>
}

/** Marks the current request's stage; a no-op without a [DiagTrace]. */
suspend fun diagMark(stage: DiagStage) {
    currentCoroutineContext()[DiagTrace]?.stage = stage
}
