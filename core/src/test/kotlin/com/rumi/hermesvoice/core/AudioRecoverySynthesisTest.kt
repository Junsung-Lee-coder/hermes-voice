package com.rumi.hermesvoice.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reply whose audio could not be fetched is spoken again WITHOUT asking Hermes for the answer again: through the production core
 * (real HTTP against [FakeHermesDashboard]) and the Phone's headset sink contract. 3 audio attempts at most, backoff 1000 / 3000 ms.
 */
class AudioRecoverySynthesisTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var rig: HeadsetRig? = null

    @After fun tearDown() {
        rig?.close()
        scope.cancel()
    }

    private fun rigOn(): HeadsetRig = HeadsetRig(scope, FakeDevices(listOf(Gear.speaker, Gear.a2dp)), headsetOn = true).also { rig = it }

    private fun HeadsetRig.primed(): String {
        val stored = existing("work", "Started.")
        phoneTurn("prime", stored)
        sink.played.clear()
        return stored
    }

    @Test
    fun `a transient synthesis failure is retried after 1 s and the reply is heard on the headset`() {
        val r = rigOn()
        val stored = r.primed()
        val base = r.fake.speakRequests.get()
        r.fake.speakStatuses.add(503)
        r.later(stored, "Hello again.")
        r.waitFor("the reply to be heard", 15_000) { r.sink.played.isNotEmpty() }
        r.settle(300)
        assertEquals(listOf("FINAL:Hello again."), r.sink.played.toList())
        assertEquals("one failed and one successful synthesis", base + 2, r.fake.speakRequests.get())
        val arrivals = r.fake.speakArrivals.takeLast(2)
        assertTrue("backoff before attempt 2 is 1000 ms (was ${arrivals[1] - arrivals[0]})", arrivals[1] - arrivals[0] in 950..2500)
        assertTrue("a recovered reply is heard, so no no-audio notification: ${r.alertsShown}", r.alertsShown.isEmpty())
    }

    @Test
    fun `the third and last audio attempt succeeds after backoffs of 1 s then 3 s`() {
        val r = rigOn()
        val stored = r.primed()
        val base = r.fake.speakRequests.get()
        r.fake.speakStatuses.add(503)
        r.fake.speakStatuses.add(502)
        r.later(stored, "Third time.")
        r.waitFor("the reply to be heard", 20_000) { r.sink.played.isNotEmpty() }
        r.settle(300)
        assertEquals(listOf("FINAL:Third time."), r.sink.played.toList())
        assertEquals(base + 3, r.fake.speakRequests.get())
        val a = r.fake.speakArrivals.takeLast(3)
        assertTrue("first backoff 1000 ms (was ${a[1] - a[0]})", a[1] - a[0] in 950..2500)
        assertTrue("second backoff 3000 ms (was ${a[2] - a[1]})", a[2] - a[1] in 2950..5000)
        assertTrue(r.alertsShown.isEmpty())
    }

    @Test
    fun `three failed audio attempts end the reply - no fourth attempt - and the user is told once`() {
        val r = rigOn()
        val stored = r.primed()
        val base = r.fake.speakRequests.get()
        repeat(6) { r.fake.speakStatuses.add(503) }
        r.later(stored, "Never audible.")
        r.waitFor("the no-audio notification", 20_000) { r.alertsShown.isNotEmpty() }
        r.settle(1_500)
        assertEquals("exactly three audio attempts", base + 3, r.fake.speakRequests.get())
        assertEquals(1, r.alertsShown.size)
        assertTrue(r.sink.played.isEmpty())
    }

    @Test
    fun `a request the server rejects is not retried`() {
        val r = rigOn()
        val stored = r.primed()
        val base = r.fake.speakRequests.get()
        r.fake.speakStatuses.add(400)
        r.later(stored, "Rejected.")
        r.waitFor("the no-audio notification", 10_000) { r.alertsShown.isNotEmpty() }
        r.settle(1_800)
        assertEquals("a 400 is final", base + 1, r.fake.speakRequests.get())
        assertEquals(1, r.alertsShown.size)
        assertTrue(r.sink.played.isEmpty())
    }

    @Test
    fun `Stop while waiting to retry cancels the retry`() {
        val r = rigOn()
        val stored = r.primed()
        val base = r.fake.speakRequests.get()
        r.fake.speakStatuses.add(503)
        r.later(stored, "Stopped before retry.")
        r.waitFor("the first failed attempt", 10_000) { r.fake.speakRequests.get() == base + 1 }
        r.core.orchestrator.stopFollowing()
        r.settle(2_500)
        assertEquals("no retry after Stop", base + 1, r.fake.speakRequests.get())
        assertTrue(r.sink.played.isEmpty())
        assertTrue("a Stop is cancelled, not a new reply to announce: ${r.alertsShown}", r.alertsShown.isEmpty())
    }

    @Test
    fun `a retry never resubmits the request - Hermes is asked for the answer once`() {
        val r = rigOn()
        val stored = r.primed()
        val submitsBefore = r.fake.timeline.toList().count { it.startsWith("submit") }
        r.fake.speakStatuses.add(503)
        r.later(stored, "Once only.")
        r.waitFor("the reply to be heard", 15_000) { r.sink.played.isNotEmpty() }
        assertEquals(submitsBefore, r.fake.timeline.toList().count { it.startsWith("submit") })
    }
}
