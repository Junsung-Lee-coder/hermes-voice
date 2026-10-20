package com.rumi.hermesvoice.core.sessions

import com.rumi.hermesvoice.core.HermesException
import com.rumi.hermesvoice.core.HermesHttpException
import com.rumi.hermesvoice.core.HermesProtocolException
import com.rumi.hermesvoice.core.HermesAuthRequiredException
import com.rumi.hermesvoice.core.SessionNotOwnedException
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Which dashboard READ a refresh is making; the only reads that may be asked for again. Fixed vocabulary for log lines. */
enum class ReadOp(val wire: String) { LIST("list"), DETAIL("detail"), HISTORY("history"), RECONCILIATION("reconciliation") }

/** Why a read may be asked for again. Typed from the failure, never from its wording. */
enum class ReadRetryCause(val wire: String) {
    TIMEOUT("timeout"), CONNECT("connect"), DNS("dns"), DISCONNECT("disconnect"),
    RATE_LIMITED("rate_limited"), SERVER_BUSY("server_busy"), SERVER_ERROR("server_error"),
}

/**
 * Decides, by TYPE only, whether a failed dashboard READ is worth asking for again. Only a transport/timeout failure, or an
 * answer with a retry-eligible status to a GET, is transient. Everything else (sign-in, access, ownership, protocol, invalid
 * input, TLS or security rejection, cancellation, any mutation's failure) is final. The whole cause chain is read and any final
 * link wins, so a wrapped terminal failure is never retried because of a transient-looking cause beneath it.
 */
object ReadRetryClassifier {
    private const val MAX_DEPTH = 6
    private val ELIGIBLE_STATUSES = setOf(408, 429, 500, 502, 503, 504)

    fun transientCause(error: Throwable): ReadRetryCause? {
        var found: ReadRetryCause? = null
        var node: Throwable? = error
        var depth = 0
        while (node != null && depth < MAX_DEPTH) {
            if (node is HermesHttpException) return if (node.readResponse && node.status in ELIGIBLE_STATUSES) statusCause(node.status) else null
            if (isFinal(node)) return null
            if (found == null) found = transportCause(node)
            val next = node.cause
            if (next === node) break
            node = next
            depth += 1
        }
        return found
    }

    private fun isFinal(error: Throwable): Boolean = error is CancellationException ||
        error is HermesAuthRequiredException || error is SessionNotOwnedException || error is HermesProtocolException ||
        error is SecurityException || error is IllegalArgumentException || error is IllegalStateException ||
        error is SSLException || error is CertificateException || error is java.net.ProtocolException ||
        error is java.net.MalformedURLException || error is java.net.UnknownServiceException ||
        (error is HermesException && error !is HermesHttpException)

    private fun transportCause(error: Throwable): ReadRetryCause? = when (error) {
        is SocketTimeoutException, is InterruptedIOException -> ReadRetryCause.TIMEOUT
        is ConnectException, is NoRouteToHostException -> ReadRetryCause.CONNECT
        is UnknownHostException -> ReadRetryCause.DNS
        is SocketException, is EOFException -> ReadRetryCause.DISCONNECT
        is IOException -> ReadRetryCause.DISCONNECT
        else -> null
    }

    private fun statusCause(status: Int) = when (status) {
        408 -> ReadRetryCause.TIMEOUT
        429 -> ReadRetryCause.RATE_LIMITED
        503 -> ReadRetryCause.SERVER_BUSY
        else -> ReadRetryCause.SERVER_ERROR
    }

    fun retryAfterMs(error: Throwable): Long? {
        var node: Throwable? = error
        var depth = 0
        while (node != null && depth < MAX_DEPTH) {
            if (node is HermesHttpException) return node.retryAfterMs
            node = node.cause.takeIf { it !== node }
            depth += 1
        }
        return null
    }
}

/**
 * One logical refresh's retry allowance: the first try plus at most [MAX_RETRIES] retries, after [LOCAL_DELAYS_MS] (or the server's
 * Retry-After when that is longer). Carried in the coroutine context so every read of the refresh, however deep, draws on the SAME
 * allowance: nothing multiplies and a scan that is partly read continues where it was instead of starting over. A refresh that
 * installs none makes a single try. [lines] receives fixed-vocabulary log lines only (operation, attempt, cause, delay).
 */
class ReadRetryBudget internal constructor(
    private val deadlineAtMs: Long?,
    private val lines: ((String) -> Unit)?,
    private val clock: () -> Long,
    private val pause: suspend (Long) -> Unit,
    private val stillCurrent: (() -> Boolean)?,
    carriedRetries: Int = 0,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ReadRetryBudget> {
        const val MAX_RETRIES = 2
        val LOCAL_DELAYS_MS = longArrayOf(1_000, 2_000)
        const val MAX_RETRY_AFTER_MS = 10_000L
        fun monotonicMs(): Long = System.nanoTime() / 1_000_000
    }

    private val used = AtomicInteger(carriedRetries.coerceIn(0, MAX_RETRIES))

    val retriesUsed: Int get() = used.get()

    /** The wait before the next retry, with the retry now spent; null when no retry is allowed (nothing is spent then). */
    internal fun reserve(op: ReadOp, cause: ReadRetryCause, retryAfterMs: Long?): Long? {
        while (true) {
            val n = used.get()
            if (n >= MAX_RETRIES) { lines?.invoke("read_retry op=${op.wire} exhausted retries=$n cause=${cause.wire}"); return null }
            if (retryAfterMs != null && retryAfterMs > MAX_RETRY_AFTER_MS) {
                lines?.invoke("read_retry op=${op.wire} retry_after_too_long retries=$n cause=${cause.wire}"); return null
            }
            val wait = maxOf(LOCAL_DELAYS_MS[n], retryAfterMs ?: 0L)
            if (deadlineAtMs != null && clock() + wait > deadlineAtMs) {
                lines?.invoke("read_retry op=${op.wire} deadline retries=$n cause=${cause.wire}"); return null
            }
            if (used.compareAndSet(n, n + 1)) {
                lines?.invoke("read_retry op=${op.wire} retry=${n + 1}/$MAX_RETRIES cause=${cause.wire} wait_ms=$wait")
                return wait
            }
        }
    }

    internal fun terminal(op: ReadOp) { lines?.invoke("read_retry op=${op.wire} final") }

    /** False once the account, connection, selection or screen the refresh was made for has changed: nothing is asked for again then. */
    internal fun current(op: ReadOp): Boolean {
        val ok = stillCurrent?.invoke() != false
        if (!ok) lines?.invoke("read_retry op=${op.wire} stale")
        return ok
    }

    /** True once the refresh's start window has closed (a pause that overslept). Ending exactly on the window is still inside it. */
    internal fun pastDeadline(op: ReadOp, cause: ReadRetryCause): Boolean {
        val late = deadlineAtMs != null && clock() > deadlineAtMs
        if (late) lines?.invoke("read_retry op=${op.wire} deadline retries=${used.get()} cause=${cause.wire}")
        return late
    }

    internal suspend fun waitFor(ms: Long) = pause(ms)
}

/**
 * Runs [block] with one retry allowance for every dashboard read inside it. An allowance already in the context is reused (never a
 * second one). [deadlineAfterMs] bounds when a retry may START, measured from now; it is checked again after each pause, so a pause that
 * overslept the window is not followed by a read. [stillCurrent] is asked before every retry and again after each pause; once it is
 * false the failure is thrown as it is and nothing more is read for this refresh. [carriedRetries] is how many of the refresh's
 * retries a caller upstream (the Watch) has already spent, so the allowance is shared across devices instead of restarting.
 */
suspend fun <T> withReadRetries(
    deadlineAfterMs: Long? = null,
    lines: ((String) -> Unit)? = null,
    clock: () -> Long = { ReadRetryBudget.monotonicMs() },
    pause: suspend (Long) -> Unit = { delay(it) },
    stillCurrent: (() -> Boolean)? = null,
    carriedRetries: Int = 0,
    block: suspend () -> T,
): T {
    if (coroutineContext[ReadRetryBudget] != null) return block()
    val budget = ReadRetryBudget(deadlineAfterMs?.let { clock() + it }, lines, clock, pause, stillCurrent, carriedRetries)
    return withContext(budget) { block() }
}

/** One dashboard READ: made once, and asked for again only while the refresh's [ReadRetryBudget] allows and the failure is transient. */
suspend fun <T> readWithRetries(op: ReadOp, call: suspend () -> T): T {
    val budget = coroutineContext[ReadRetryBudget] ?: return call()
    while (true) {
        try {
            return call()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val cause = ReadRetryClassifier.transientCause(error)
            if (cause == null) { budget.terminal(op); throw error }
            if (!budget.current(op)) throw error
            val wait = budget.reserve(op, cause, ReadRetryClassifier.retryAfterMs(error)) ?: throw error
            budget.waitFor(wait)
            if (!budget.current(op) || budget.pastDeadline(op, cause)) throw error
        }
    }
}
