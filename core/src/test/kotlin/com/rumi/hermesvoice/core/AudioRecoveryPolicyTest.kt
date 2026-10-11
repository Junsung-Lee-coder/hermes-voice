package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.voice.AudioFailureClassifier
import com.rumi.hermesvoice.core.voice.AudioFailureCode
import com.rumi.hermesvoice.core.voice.AudioRecoveryPolicy
import com.rumi.hermesvoice.core.voice.AudioRecoveryRun
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The audio recovery policy and classifier on their own: 3 attempts, 1000 / 3000 ms, a 60 s window from the FIRST classified failure. */
class AudioRecoveryPolicyTest {
    private var now = 0L
    private fun run(policy: AudioRecoveryPolicy = AudioRecoveryPolicy()) = AudioRecoveryRun(policy) { now }
    private val transient = IOException("network down")

    @Test
    fun `defaults are 3 attempts with backoffs of 1000 and 3000 ms in a 60 s window`() {
        val p = AudioRecoveryPolicy()
        assertEquals(3, p.maxAttempts)
        assertEquals(listOf(1_000L, 3_000L), p.backoffMs)
        assertEquals(60_000L, p.windowMs)
    }

    @Test
    fun `transient failures wait 1000 then 3000 ms and the third failure ends it`() {
        val r = run()
        assertEquals(1_000L, r.nextWaitMs(transient))
        now += 1_000
        assertEquals(3_000L, r.nextWaitMs(transient))
        now += 3_000
        assertNull("no fourth attempt", r.nextWaitMs(transient))
        assertEquals(3, r.attempts)
    }

    @Test
    fun `the window only forbids scheduling another retry once 60 s passed since the first failure`() {
        val r = run()
        assertEquals(1_000L, r.nextWaitMs(transient))
        // The retry itself (a long, healthy synthesis or playback) took 70 s: nothing cut it; only the NEXT retry is no longer scheduled.
        now += 70_000
        assertNull(r.nextWaitMs(transient))
        assertEquals(70_000L, r.elapsedMs())
    }

    @Test
    fun `the window starts at the first classified failure not at the start of a long healthy attempt`() {
        val r = run()
        now += 300_000
        assertEquals("a first failure after a very long healthy attempt is still retried", 1_000L, r.nextWaitMs(transient))
    }

    @Test
    fun `failures that are not transient are never retried and do not count`() {
        val r = run()
        assertNull(r.nextWaitMs(HermesHttpException(400, "bad")))
        assertEquals(0, r.attempts)
    }

    @Test
    fun `classification table`() {
        fun code(e: Throwable) = AudioFailureClassifier.classify(e)
        assertTrue(code(IOException("x")).transient)
        for (status in listOf(408, 425, 429, 500, 502, 503, 504)) assertTrue("$status", code(HermesHttpException(status, "x")).transient)
        for (status in listOf(400, 401, 403, 404, 413, 415, 422, 501, 505)) assertFalse("$status", code(HermesHttpException(status, "x")).transient)
        assertTrue(code(HermesPlaybackTransientException("x")).transient)
        assertEquals(AudioFailureCode.PLAYER_TRANSIENT, code(HermesPlaybackTransientException("x")))
        assertFalse(code(HermesPlaybackException("route lost")).transient)
        assertFalse(code(HermesPlaybackBusyException("busy")).transient)
        assertFalse(code(HermesProtocolException("empty audio")).transient)
        assertFalse(code(HermesAuthRequiredException("sign in")).transient)
        assertFalse(code(IllegalStateException("x")).transient)
    }
}
