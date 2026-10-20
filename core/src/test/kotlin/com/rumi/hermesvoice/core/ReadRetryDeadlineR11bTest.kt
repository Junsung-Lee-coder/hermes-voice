package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.sessions.ReadOp
import com.rumi.hermesvoice.core.sessions.readWithRetries
import com.rumi.hermesvoice.core.sessions.withReadRetries
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R11b O01: after a suspending pause the next read is admitted only while the refresh's start window still holds, not merely while the
 * refresh is still current. The clock and the pause are injected (a pause that oversleeps is a clock jump); nothing here is real time.
 */
class ReadRetryDeadlineR11bTest {
    private class Clock { var now = 0L }

    private class Run(val tries: Int, val waits: List<Long>, val lines: List<String>, val failure: Throwable?)

    /** [oversleepMs] maps the 1-based pause number to how long that pause really took; absent means exactly the requested wait. */
    private fun attempt(
        deadlineAfterMs: Long?,
        failures: Int,
        oversleepMs: Map<Int, Long> = emptyMap(),
        stillCurrent: (() -> Boolean)? = { true },
        pauseBody: (suspend (Clock, Long) -> Unit)? = null,
    ): Run {
        val clock = Clock()
        val waits = mutableListOf<Long>()
        val lines = mutableListOf<String>()
        var tries = 0
        val failure = try {
            runBlocking {
                withReadRetries(deadlineAfterMs, { lines += it }, { clock.now }, { ms ->
                    waits += ms
                    if (pauseBody != null) pauseBody(clock, ms) else clock.now += oversleepMs[waits.size] ?: ms
                }, stillCurrent) {
                    readWithRetries(ReadOp.HISTORY) {
                        val i = tries++
                        if (i < failures) throw IOException("synthetic transient")
                        "ok"
                    }
                }
            }
            null
        } catch (e: Throwable) { e }
        return Run(tries, waits, lines, failure)
    }

    @Test fun `a pause that oversleeps past the start window is not followed by another read`() {
        val run = attempt(deadlineAfterMs = 9_000, failures = 1, oversleepMs = mapOf(1 to 60_000L))
        assertEquals("the read after the oversleep must not start", 1, run.tries)
        assertTrue("${run.failure}", run.failure is IOException)
        assertEquals(listOf(1_000L), run.waits)
        assertTrue(run.lines.toString(), run.lines.any { it.startsWith("read_retry op=history deadline retries=1 cause=") })
    }

    @Test fun `an oversleep during the second pause stops after the second read`() {
        val run = attempt(deadlineAfterMs = 9_000, failures = 5, oversleepMs = mapOf(2 to 22_000L))
        assertEquals("first try, one in budget retry, no read after the oversleep", 2, run.tries)
        assertTrue("${run.failure}", run.failure is IOException)
        assertEquals(listOf(1_000L, 2_000L), run.waits)
    }

    @Test fun `a pause that ends exactly on the start window still allows the read`() {
        val run = attempt(deadlineAfterMs = 1_000, failures = 1)
        assertEquals(2, run.tries)
        assertNull(run.failure)
    }

    @Test fun `an ordinary in budget retry is read again and succeeds`() {
        val run = attempt(deadlineAfterMs = 9_000, failures = 2)
        assertEquals(3, run.tries)
        assertEquals(listOf(1_000L, 2_000L), run.waits)
        assertNull(run.failure)
    }

    @Test fun `cancellation during the pause is not turned into a read or a failure`() {
        val run = attempt(deadlineAfterMs = 9_000, failures = 5, pauseBody = { _, _ -> throw CancellationException("synthetic cancel") })
        assertEquals(1, run.tries)
        assertTrue("${run.failure}", run.failure is CancellationException)
    }

    @Test fun `a refresh that is no longer current after the pause is not read again`() {
        var current = true
        val run = attempt(deadlineAfterMs = 9_000, failures = 5, stillCurrent = { current }, pauseBody = { clock, ms -> clock.now += ms; current = false })
        assertEquals(1, run.tries)
        assertTrue("${run.failure}", run.failure is IOException)
    }
}
