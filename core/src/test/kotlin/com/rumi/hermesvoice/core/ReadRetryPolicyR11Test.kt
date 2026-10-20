package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.sessions.ReadOp
import com.rumi.hermesvoice.core.sessions.ReadRetryClassifier
import com.rumi.hermesvoice.core.sessions.ReadRetryCause
import com.rumi.hermesvoice.core.sessions.readWithRetries
import com.rumi.hermesvoice.core.sessions.withReadRetries
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R11: the read-retry policy by TYPE only (no network): admission, the shared allowance, the deadline and the log vocabulary. Synthetic. */
class ReadRetryPolicyR11Test {
    private fun http(status: Int, read: Boolean = true, retryAfterMs: Long? = null) =
        HermesHttpException(status, "synthetic $status", retryAfterMs = retryAfterMs, readResponse = read)

    /** The coroutine machinery may rethrow a recovered copy of the failure; it must be the same type with the original as its cause (or the original itself). */
    private fun assertRethrown(actual: Throwable?, original: Throwable) {
        assertTrue("$actual", actual === original || (actual != null && actual.javaClass == original.javaClass &&
            generateSequence(actual) { it.cause }.take(8).any { it === original }))
    }

    private class Run(val tries: Int, val waits: List<Long>, val lines: List<String>, val failure: Throwable?)

    private fun attempt(failures: List<Throwable>, deadlineAfterMs: Long? = null, clockAt: () -> Long = { 0L }): Run {
        val waits = mutableListOf<Long>()
        val lines = mutableListOf<String>()
        var tries = 0
        val failure = try {
            runBlocking {
                withReadRetries(deadlineAfterMs, { lines += it }, clockAt, { waits += it }) {
                    readWithRetries(ReadOp.HISTORY) {
                        val i = tries++
                        if (i < failures.size) throw failures[i]
                        "ok"
                    }
                }
            }
            null
        } catch (e: Throwable) { e }
        return Run(tries, waits, lines, failure)
    }

    @Test fun `transport failures are transient`() {
        for (error in listOf(SocketTimeoutException("x"), ConnectException("x"), UnknownHostException("x"), SocketException("x"),
            EOFException("x"), IOException("x"))) {
            val run = attempt(listOf(error))
            assertEquals(error.javaClass.simpleName, 2, run.tries)
            assertNull(run.failure)
            assertEquals(listOf(1000L), run.waits)
        }
    }

    @Test fun `a transient failure wrapped in a typed failure is still transient but a terminal one wins over a transient cause`() {
        assertNull(attempt(listOf(RuntimeException("w", SocketTimeoutException("x")))).failure)
        val wrapped = HermesProtocolException("protocol", SocketTimeoutException("x"))
        val run = attempt(listOf(wrapped))
        assertEquals(1, run.tries)
        assertRethrown(run.failure, wrapped)
    }

    @Test fun `every terminal type is tried once and rethrown unchanged`() {
        val terminals = listOf<Throwable>(
            HermesAuthRequiredException("x"), SessionNotOwnedException("x"), HermesProtocolException("x"),
            HermesException("x"), SSLHandshakeException("x"), CertificateException("x"), SecurityException("x"),
            IllegalArgumentException("x"), IllegalStateException("x"), java.net.ProtocolException("x"),
            java.net.MalformedURLException("x"), http(400), http(401), http(403), http(404), http(409), http(410), http(422),
            http(503, read = false),
        )
        for (error in terminals) {
            val run = attempt(listOf(error, error, error))
            assertEquals(error.javaClass.simpleName, 1, run.tries)
            assertRethrown(run.failure, error)
            assertTrue(run.waits.isEmpty())
        }
    }

    @Test fun `cancellation is never retried`() {
        val cancelled = CancellationException("x")
        val run = attempt(listOf(cancelled))
        assertEquals(1, run.tries)
        assertRethrown(run.failure, cancelled)
    }

    @Test fun `the retry eligible statuses are exactly the six and carry typed causes`() {
        val expected = mapOf(408 to ReadRetryCause.TIMEOUT, 429 to ReadRetryCause.RATE_LIMITED, 500 to ReadRetryCause.SERVER_ERROR,
            502 to ReadRetryCause.SERVER_ERROR, 503 to ReadRetryCause.SERVER_BUSY, 504 to ReadRetryCause.SERVER_ERROR)
        for (status in 400..599) assertEquals("status $status", expected[status], ReadRetryClassifier.transientCause(http(status)))
        assertNull("a mutation's answer is never read-retried", ReadRetryClassifier.transientCause(http(503, read = false)))
    }

    @Test fun `two retries at one and two seconds then the last failure is thrown`() {
        val error = http(503)
        val run = attempt(listOf(error, error, error, error))
        assertEquals(3, run.tries)
        assertEquals(listOf(1000L, 2000L), run.waits)
        assertRethrown(run.failure, error)
    }

    @Test fun `a retry after longer than the local pause is waited for and never shortened`() {
        val run = attempt(listOf(http(429, retryAfterMs = 4000)))
        assertEquals(listOf(4000L), run.waits)
        assertNull(run.failure)
    }

    @Test fun `a retry after over the cap ends the refresh without waiting`() {
        val error = http(429, retryAfterMs = 600_000)
        val run = attempt(listOf(error))
        assertEquals(1, run.tries)
        assertTrue(run.waits.isEmpty())
        assertRethrown(run.failure, error)
    }

    @Test fun `a retry that would start after the operation deadline is not made`() {
        var now = 0L
        val waits = mutableListOf<Long>()
        var tries = 0
        val error = http(503)
        val failure = runCatching {
            runBlocking {
                withReadRetries(2500, null, { now }, { waits += it; now += it }) {
                    readWithRetries(ReadOp.HISTORY) { tries++; throw error }
                }
            }
        }.exceptionOrNull()
        assertEquals(2, tries)
        assertEquals("the 1 s retry fits; the next one would start at 3 s, after the 2.5 s deadline", listOf(1000L), waits)
        assertRethrown(failure, error)
    }

    @Test fun `a refresh that is no longer current is not asked for again before or after the pause`() {
        for (staleAfterPause in listOf(false, true)) {
            var current = true
            val waits = mutableListOf<Long>()
            val lines = mutableListOf<String>()
            var tries = 0
            val error = http(503)
            val failure = runCatching {
                runBlocking {
                    withReadRetries(null, { lines += it }, { 0L }, { waits += it; if (staleAfterPause) current = false }, { current }) {
                        readWithRetries(ReadOp.LIST) { tries++; if (!staleAfterPause) current = false; throw error }
                    }
                }
            }.exceptionOrNull()
            assertRethrown(failure, error)
            assertEquals("stale after pause=$staleAfterPause", 1, tries)
            assertEquals(if (staleAfterPause) listOf(1000L) else emptyList<Long>(), waits)
            assertTrue(lines.toString(), lines.any { it.endsWith("stale") })
        }
    }

    @Test fun `a nested scope shares the outer allowance instead of adding one`() {
        var tries = 0
        val waits = mutableListOf<Long>()
        val result = runCatching {
            runBlocking {
                withReadRetries(null, null, { 0L }, { waits += it }) {
                    withReadRetries(null, null, { 0L }, { waits += it }) {
                        readWithRetries(ReadOp.LIST) { tries++; throw http(503) }
                    }
                }
            }
        }
        assertTrue(result.isFailure)
        assertEquals(3, tries)
        assertEquals(listOf(1000L, 2000L), waits)
    }

    @Test fun `separate reads of one refresh draw on the same allowance`() {
        var detail = 0
        var page = 0
        val waits = mutableListOf<Long>()
        val failure = runCatching {
            runBlocking {
                withReadRetries(null, null, { 0L }, { waits += it }) {
                    readWithRetries(ReadOp.DETAIL) { if (detail++ == 0) throw http(503) else "d" }
                    readWithRetries(ReadOp.HISTORY) { page++; throw http(503) }
                }
            }
        }.exceptionOrNull()
        assertTrue(failure is HermesHttpException)
        assertEquals(2, detail)
        assertEquals("one retry was already spent by the detail read", 2, page)
        assertEquals(listOf(1000L, 2000L), waits)
    }

    @Test fun `a read outside any allowance is made once`() {
        var tries = 0
        val failure = runCatching { runBlocking { readWithRetries(ReadOp.LIST) { tries++; throw http(503) } } }.exceptionOrNull()
        assertTrue(failure is HermesHttpException)
        assertEquals(1, tries)
    }

    @Test fun `log lines carry only the fixed vocabulary and never the failure text`() {
        val secret = "synthetic-secret-token-123 /private/path bearer"
        val run = attempt(listOf(SocketTimeoutException(secret), SocketTimeoutException(secret), SocketTimeoutException(secret)))
        assertTrue(run.lines.isNotEmpty())
        val allowed = Regex("""read_retry op=(list|detail|history|reconciliation) (stale|retry=[12]/2 cause=[a-z_]+ wait_ms=\d+|exhausted retries=\d cause=[a-z_]+|final|deadline retries=\d cause=[a-z_]+|retry_after_too_long retries=\d cause=[a-z_]+)""")
        for (line in run.lines) {
            assertTrue(line, allowed.matches(line))
            assertFalse(line, line.contains("secret") || line.contains("/private") || line.contains("bearer"))
        }
        assertTrue(run.failure is SocketTimeoutException)
    }
}
