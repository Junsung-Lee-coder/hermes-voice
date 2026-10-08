package com.rumi.hermesvoice.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Private headset output (amendment J): with "Use headset" on and a usable headset OUTPUT connected, every reply the Phone speaks
 * - whichever device spoke the request, whichever device is the preferred reply device - plays only on that headset. Nothing is
 * handed to the Watch (no Watch speaker), and nothing falls back to the Phone's loudspeaker. Through the production core wiring.
 */
class PrivateAudioRoutingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var rig: PrivateAudioRig? = null

    @After fun tearDown() {
        rig?.close()
        scope.cancel()
    }

    private fun rig(
        outputs: List<com.rumi.hermesvoice.core.headset.AudioEndpoint> = listOf(Gear.speaker, Gear.a2dp),
        on: Boolean = true,
        laterOptIn: Boolean = false,
        withPhoneSink: Boolean = true,
    ) = PrivateAudioRig(scope, FakeDevices(outputs), laterOptIn = laterOptIn, headsetOn = on, withPhoneSink = withPhoneSink).also { rig = it }

    private val buds = Gear.a2dp

    @Test
    fun `a Watch voice request is answered on the Phone headset only - the acknowledgement and the final - and nothing reaches the Watch`() {
        val r = rig()
        val stored = r.existing("work", "Done.")
        r.watchTurn("w-1", stored)
        assertEquals("nothing is handed to the Watch", 0, r.watch.offered.size)
        assertEquals(listOf("FINAL:Done."), r.phoneFinals())
        assertTrue("every cue is bound to the headset", r.phone.cues.isNotEmpty() && r.phone.cues.all { it.headset == buds })
        assertTrue("the acknowledgement was spoken on the headset too", r.phone.cues.any { it.role == SpokenRole.ACK })
        assertTrue("heard: no alert", r.shown.isEmpty() && r.watch.watchAlerts.isEmpty())
    }

    @Test
    fun `without a headset a Watch voice request is answered on the Watch exactly as before`() {
        val r = rig(listOf(Gear.speaker))
        val stored = r.existing("work", "Done.")
        r.watchTurn("w-none", stored)
        assertTrue(r.watch.played.contains("FINAL:Done."))
        assertTrue(r.phone.offered.isEmpty())
    }

    @Test
    fun `with the setting off a connected headset changes nothing for a Watch request`() {
        val r = rig(on = false)
        val stored = r.existing("work", "Done.")
        r.watchTurn("w-off", stored)
        assertTrue(r.watch.played.contains("FINAL:Done."))
        assertTrue(r.phone.offered.isEmpty())
    }

    @Test
    fun `a later reply to a request whose latest sender is the Watch plays on the Phone headset - with the later-reply option off or on`() {
        for (optIn in listOf(false, true)) {
            val r = rig(laterOptIn = optIn)
            try {
                val stored = r.existing("work", "Started.")
                r.watchTurn("w-later-$optIn", stored)
                r.phone.played.clear()
                val watchStarts = r.watch.starts.get()
                r.later(stored, "Finished.")
                r.waitFor("the later reply on the headset") { r.phone.played.contains("FINAL:Finished.") }
                r.settle()
                assertEquals("never on the Watch (optIn=$optIn)", watchStarts, r.watch.starts.get())
                assertEquals(buds, r.phone.cues.last().headset)
                assertTrue(r.shown.isEmpty() && r.watch.watchAlerts.isEmpty())
            } finally {
                r.close()
            }
        }
    }

    @Test
    fun `a Phone request whose playback route is the Watch still speaks on the headset only`() {
        val r = rig()
        val first = r.existing("work", "Started.")
        val second = r.existing("home", "Home.")
        r.phoneTurn("p-1", first)
        r.watchTurn("w-2", second)
        r.phone.played.clear()
        val watchStarts = r.watch.starts.get()
        r.later(first, "Late for the phone request.")
        r.waitFor("spoken on the headset") { r.phone.played.contains("FINAL:Late for the phone request.") }
        assertEquals(watchStarts, r.watch.starts.get())
    }

    @Test
    fun `a headset that connects after the Watch request was submitted takes its answer`() {
        val r = rig(listOf(Gear.speaker))
        val stored = r.existing("work", "Done.")
        r.fake.scripts[stored] = { r.devices.connect(Gear.wiredHeadphones); listOf(FakeHermesDashboard.complete("Done.")) }
        r.watchTurn("w-connect", stored)
        assertEquals(listOf("FINAL:Done."), r.phoneFinals())
        assertTrue("the final never played on the Watch", r.watch.played.none { it.startsWith("FINAL") })
    }

    @Test
    fun `a headset that disconnects after admission ends the speech with no Watch or speaker fallback and a quiet notification`() {
        val r = rig()
        val stored = r.existing("work", "Started.")
        r.watchTurn("w-gone", stored)
        r.phone.played.clear()
        val watchStarts = r.watch.starts.get()
        r.phone.onCue = { cue -> if (cue.later) r.devices.disconnectAll() }
        r.later(stored, "Will be lost.")
        r.waitFor("the notification") { r.shown.isNotEmpty() }
        r.settle()
        assertEquals(1, r.shown.size)
        assertTrue("silent: the headset-only choice never becomes a public sound", r.shown.single().quiet)
        assertTrue(r.watch.watchAlerts.isEmpty())
        assertEquals(watchStarts, r.watch.starts.get())
        assertTrue(r.phone.played.isEmpty())
        r.devices.connect(Gear.a2dp)
        r.settle(600)
        assertTrue("a reconnect replays nothing", r.phone.played.isEmpty() && r.watch.starts.get() == watchStarts)
    }

    @Test
    fun `a sink failure keeps the Watch answer private - one quiet notification and no second route`() {
        val r = rig()
        val stored = r.existing("work", "Started.")
        r.watchTurn("w-fail", stored)
        r.phone.played.clear()
        val watchStarts = r.watch.starts.get()
        r.phone.failNext = "synthesis or focus failed"
        r.later(stored, "Will not play.")
        r.waitFor("the notification") { r.shown.isNotEmpty() }
        r.settle()
        assertTrue(r.shown.single().quiet)
        assertEquals(watchStarts, r.watch.starts.get())
        assertTrue(r.phone.played.isEmpty())
    }

    @Test
    fun `with no private sink the Watch is never used instead - nothing plays and the notification is quiet`() {
        val r = rig(withPhoneSink = false)
        val stored = r.existing("work", "Started.")
        r.watchTurn("w-nosink", stored)
        r.later(stored, "Nowhere to play.")
        r.waitFor("the notification") { r.shown.isNotEmpty() }
        r.settle()
        assertTrue(r.shown.all { it.quiet })
        assertEquals(0, r.watch.offered.size)
    }

    @Test
    fun `turning the setting off during private playback does not move that speech to a public speaker`() {
        val r = rig()
        val stored = r.existing("work", "Started.")
        r.watchTurn("w-flip", stored)
        r.phone.played.clear()
        val gate = CompletableDeferred<Unit>()
        val base = r.phone.starts.get()
        r.phone.hold = gate
        r.later(stored, "Finish me.")
        r.waitFor("playing") { r.phone.starts.get() > base }
        r.settings.useHeadset = false
        r.privateMonitor.refresh()
        gate.complete(Unit)
        r.waitFor("finished") { r.phone.played.isNotEmpty() }
        r.settle()
        assertEquals(listOf("FINAL:Finish me."), r.phone.played.toList())
        assertEquals(0, r.watch.offered.count { it.later })
        assertEquals(buds, r.phone.cues.last().headset)
    }

    @Test
    fun `the headset connecting while a reply plays on the Watch withdraws it and speaks it on the headset - once`() {
        val r = rig(listOf(Gear.speaker), laterOptIn = true)
        val stored = r.existing("work", "Started.")
        r.watchTurn("w-withdraw", stored)
        val gate = CompletableDeferred<Unit>()
        val base = r.watch.starts.get()
        r.watch.hold = gate
        r.later(stored, "Move me.")
        r.waitFor("playing on the Watch") { r.watch.starts.get() > base }
        r.devices.connect(Gear.a2dp)
        r.waitFor("the Watch playback was stopped") { r.watch.cut.get() == 1 }
        r.waitFor("spoken on the headset") { r.phone.played.contains("FINAL:Move me.") }
        r.settle()
        gate.complete(Unit)
        assertTrue("the Watch never finished it", r.watch.played.none { it.contains("Move me.") })
        assertEquals(1, r.phone.played.count { it == "FINAL:Move me." })
        assertTrue("heard on the headset: no alert", r.shown.isEmpty() && r.watch.watchAlerts.isEmpty())
        assertEquals(listOf(true), r.watchPrivate.drop(1))
    }

    @Test
    fun `a reply queued for the Watch when the headset connects is spoken on the headset and never sent to the Watch`() {
        val r = rig(listOf(Gear.speaker), laterOptIn = true)
        val stored = r.existing("work", "Started.")
        r.watchTurn("w-queued", stored)
        val gate = CompletableDeferred<Unit>()
        val base = r.watch.starts.get()
        r.watch.hold = gate
        r.later(stored, "First.")
        r.waitFor("first playing on the Watch") { r.watch.starts.get() > base }
        r.later(stored, "Second.")
        r.settle(300)
        r.devices.connect(Gear.a2dp)
        r.waitFor("both on the headset") { r.phone.played.containsAll(listOf("FINAL:First.", "FINAL:Second.")) }
        gate.complete(Unit)
        r.settle()
        assertEquals(1, r.watch.starts.get() - base)
        assertTrue("neither queued reply finished on the Watch", r.watch.played.none { it.contains("First.") || it.contains("Second.") })
    }

    @Test
    fun `Stop cuts private speech of a Watch request and nothing replays it on reconnect`() {
        val r = rig()
        val stored = r.existing("work", "Started.")
        r.watchTurn("w-stop", stored)
        r.phone.played.clear()
        val gate = CompletableDeferred<Unit>()
        val base = r.phone.starts.get()
        r.phone.hold = gate
        r.later(stored, "Stop me.")
        r.waitFor("playing") { r.phone.starts.get() > base }
        val before = r.phone.starts.get()
        r.core.orchestrator.stopFollowing()
        r.waitFor("cut") { r.phone.cut.get() == 1 }
        r.devices.disconnectAll()
        r.devices.connect(Gear.a2dp)
        r.settle(600)
        assertEquals(before, r.phone.starts.get())
        assertEquals(0, r.watch.offered.count { it.later })
        assertTrue(r.shown.isEmpty())
    }

    @Test
    fun `the request itself is unchanged - same stored conversation, one submission`() {
        val r = rig()
        val stored = r.existing("work", "Done.")
        val outcome = r.watchTurn("w-same", stored)
        assertNotNull(outcome)
        assertEquals(1, r.fake.prompts.count { it.first == stored })
    }

    @Test
    fun `a typed or Phone request is bound to the same headset as before`() {
        val r = rig()
        val stored = r.existing("work", "Done.")
        r.phoneTurn("p-same", stored)
        assertEquals(buds, r.phone.cues.single { it.role == SpokenRole.FINAL }.headset)
        assertNull(r.watch.offered.firstOrNull())
    }
}
