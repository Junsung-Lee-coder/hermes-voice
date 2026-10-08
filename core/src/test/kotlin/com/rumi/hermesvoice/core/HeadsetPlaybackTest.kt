package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.voice.PlaybackCue
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.TurnRouting
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Use headset" through the production core (HermesVoiceCore.connect, ReplyAlerts, the orchestrator and the Phone sink contract):
 * off is the existing behavior; on with a usable connected headset OUTPUT forces the Phone's eligible answers onto that headset,
 * never to the speaker, never over the Watch, never for longer than the existing follow bounds.
 */
class HeadsetPlaybackTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var rig: HeadsetRig? = null

    @After fun tearDown() {
        rig?.close()
        scope.cancel()
    }

    private fun rig(
        outputs: List<com.rumi.hermesvoice.core.headset.AudioEndpoint> = listOf(Gear.speaker),
        inputs: List<com.rumi.hermesvoice.core.headset.AudioEndpoint> = emptyList(),
        on: Boolean = false,
        laterOptIn: Boolean = false,
        windowMs: Long = 60_000L,
    ) = HeadsetRig(scope, FakeDevices(outputs, inputs), laterOptIn = laterOptIn, headsetOn = on, laterWindowMs = windowMs).also { rig = it }

    private fun HeadsetRig.finals() = sink.played.filter { it.startsWith("FINAL") }

    private val buds get() = listOf(Gear.speaker, Gear.a2dp)

    private class RecordingSink : PlaybackSink {
        val cues: MutableList<PlaybackCue> = Collections.synchronizedList(mutableListOf())
        override suspend fun play(audio: SpokenAudio, cue: PlaybackCue) {
            cues += cue
        }
    }

    // ── the voice request's own final answer ─────────────────────────────────────────────────

    @Test
    fun `off - a connected headset changes nothing  the answer plays as before and is not bound`() {
        val r = rig(buds)
        val stored = r.existing("work", "Done.")
        r.phoneTurn("off-1", stored)
        assertEquals(listOf("FINAL:Done."), r.finals())
        assertTrue(r.sink.cues.all { it.headset == null })
        assertEquals(0, r.devices.watching)
    }

    @Test
    fun `on with a connected headset - the final answer is bound to that headset and no other output`() {
        val r = rig(buds, on = true)
        val stored = r.existing("work", "Done.")
        r.phoneTurn("on-1", stored)
        assertEquals(listOf("FINAL:Done."), r.finals())
        assertEquals(Gear.a2dp, r.sink.cues.single { it.role == com.rumi.hermesvoice.core.SpokenRole.FINAL }.headset)
        assertTrue("heard: no new-reply notification", r.alertsShown.isEmpty())
    }

    @Test
    fun `on with nothing connected  or only a speaker  a car-less phone or input-only gear  is exactly the existing behavior`() {
        for (outs in listOf(listOf(Gear.speaker), listOf(Gear.speaker, Gear.bleSpeaker), listOf(Gear.speaker, Gear.hearingAid))) {
            val r = HeadsetRig(scope, FakeDevices(outs, listOf(Gear.scoIn)), headsetOn = true)
            try {
                val stored = r.existing("work", "Done.")
                r.phoneTurn("none-${outs.size}", stored)
                assertEquals(listOf("FINAL:Done."), r.finals())
                assertTrue(r.sink.cues.all { it.headset == null })
            } finally {
                r.close()
            }
        }
    }

    @Test
    fun `a headset connecting after the request was submitted takes the answer at its admission`() {
        val r = rig(on = true)
        val stored = r.existing("work", "Done.")
        r.fake.scripts[stored] = { r.devices.connect(Gear.wiredHeadphones); listOf(FakeHermesDashboard.complete("Done.")) }
        r.phoneTurn("late-connect", stored)
        assertEquals(Gear.wiredHeadphones, r.sink.cues.single { it.role == com.rumi.hermesvoice.core.SpokenRole.FINAL }.headset)
    }

    @Test
    fun `a headset that disconnects after admission and before the answer plays is not replaced by the speaker - text and a notification instead`() {
        val r = rig(buds, on = true)
        val stored = r.existing("work", "Done.")
        r.sink.onCue = { cue -> if (cue.role == com.rumi.hermesvoice.core.SpokenRole.FINAL) r.devices.disconnectAll() }
        r.phoneTurn("gone-before", stored)
        assertEquals("the final was admitted to the headset", Gear.a2dp, r.sink.offered.single { it.role == com.rumi.hermesvoice.core.SpokenRole.FINAL }.headset)
        assertTrue("no final played, nowhere", r.finals().isEmpty())
        r.waitFor("the no-audio notification") { r.alertsShown.isNotEmpty() }
        r.settle()
        assertEquals(1, r.alertsShown.size)
    }

    @Test
    fun `a headset already gone when the answer is admitted leaves the ordinary route - nothing is forced`() {
        val r = rig(buds, on = true)
        val stored = r.existing("work", "Done.")
        r.fake.scripts[stored] = { r.devices.disconnectAll(); listOf(FakeHermesDashboard.complete("Done.")) }
        r.phoneTurn("gone-earlier", stored)
        assertEquals(listOf("FINAL:Done."), r.finals())
        assertNull(r.sink.cues.single { it.role == com.rumi.hermesvoice.core.SpokenRole.FINAL }.headset)
        assertTrue(r.alertsShown.isEmpty())
    }

    // ── later replies: forced by the headset, not by the later-reply option ─────────────────

    @Test
    fun `on - a later reply is spoken on the headset even with Speak later replies off  and does not notify`() {
        val r = rig(buds, on = true)
        val stored = r.existing("work", "Started.")
        r.phoneTurn("later-1", stored)
        r.sink.played.clear()
        r.later(stored, "Finished.")
        r.waitFor("later reply spoken") { r.sink.played.isNotEmpty() }
        r.settle()
        assertEquals(listOf("FINAL:Finished."), r.sink.played.toList())
        val cue = r.sink.cues.last()
        assertTrue(cue.later)
        assertEquals(Gear.a2dp, cue.headset)
        assertTrue("sound, not a notification as well", r.alertsShown.isEmpty())
    }

    @Test
    fun `off - a later reply with Speak later replies off is neither spoken nor followed`() {
        val r = rig(buds, on = false)
        val stored = r.existing("work", "Started.")
        r.phoneTurn("later-off", stored)
        r.sink.played.clear()
        r.later(stored, "Finished.")
        r.settle(800)
        assertTrue(r.sink.played.isEmpty())
    }

    @Test
    fun `on with no headset and the option off - a later reply is as if neither were on`() {
        val r = rig(on = true)
        val stored = r.existing("work", "Started.")
        r.phoneTurn("later-none", stored)
        r.sink.played.clear()
        r.later(stored, "Finished.")
        r.settle(800)
        assertTrue(r.sink.played.isEmpty())
        assertTrue(r.alertsShown.isEmpty())
    }

    @Test
    fun `on with Speak later replies on - the later reply is bound to the headset too  and with no headset plays as before`() {
        val withBuds = rig(buds, on = true, laterOptIn = true)
        val stored = withBuds.existing("work", "Started.")
        withBuds.phoneTurn("opt-1", stored)
        withBuds.sink.played.clear()
        withBuds.later(stored, "Finished.")
        withBuds.waitFor("spoken") { withBuds.sink.played.isNotEmpty() }
        assertEquals(Gear.a2dp, withBuds.sink.cues.last().headset)
        withBuds.close()

        val bare = rig(on = true, laterOptIn = true)
        val s2 = bare.existing("work", "Started.")
        bare.phoneTurn("opt-2", s2)
        bare.sink.played.clear()
        bare.later(s2, "Finished.")
        bare.waitFor("spoken") { bare.sink.played.isNotEmpty() }
        assertNull(bare.sink.cues.last().headset)
    }

    @Test
    fun `the preference does not lengthen the follow - a reply after the window is not spoken and nothing is revived`() {
        val r = rig(buds, on = true, windowMs = 700)
        val stored = r.existing("work", "Started.")
        r.phoneTurn("window-1", stored)
        r.sink.played.clear()
        r.settle(1_500)
        r.later(stored, "Too late.")
        r.settle(800)
        assertTrue(r.sink.played.isEmpty())
        assertTrue(r.alertsShown.isEmpty())
    }

    @Test
    fun `turning the preference off while following stops forcing later replies`() {
        val r = rig(buds, on = true)
        val stored = r.existing("work", "Started.")
        r.phoneTurn("flip-1", stored)
        r.sink.played.clear()
        r.settings.useHeadset = false
        r.later(stored, "Finished.")
        r.settle(800)
        assertTrue(r.sink.played.isEmpty())
    }

    // ── mid-playback changes ─────────────────────────────────────────────────────────────────

    @Test
    fun `the headset disconnecting during playback ends it without a speaker fallback  and tells the user once`() {
        val r = rig(buds, on = true)
        val stored = r.existing("work", "Started.")
        r.phoneTurn("drop-1", stored)
        r.sink.played.clear()
        val gate = CompletableDeferred<Unit>()
        val base = r.sink.starts.get()
        r.sink.hold = gate
        r.later(stored, "Long answer.")
        r.waitFor("playing") { r.sink.starts.get() > base }
        r.devices.disconnectAll()
        r.waitFor("the no-audio notification") { r.alertsShown.isNotEmpty() }
        r.settle()
        assertEquals(1, r.alertsShown.size)
        assertTrue(r.sink.played.isEmpty())
        assertTrue("nothing was started on any other output", r.sink.cues.drop(1).all { it.headset == Gear.a2dp })
        gate.complete(Unit)
    }

    @Test
    fun `switching the preference off during headset playback lets it finish on the same headset`() {
        val r = rig(buds, on = true)
        val stored = r.existing("work", "Started.")
        r.phoneTurn("flip-play", stored)
        r.sink.played.clear()
        val gate = CompletableDeferred<Unit>()
        val base = r.sink.starts.get()
        r.sink.hold = gate
        r.later(stored, "Finish me.")
        r.waitFor("playing") { r.sink.starts.get() > base }
        r.settings.useHeadset = false
        gate.complete(Unit)
        r.waitFor("finished") { r.sink.played.isNotEmpty() }
        r.settle()
        assertEquals(listOf("FINAL:Finish me."), r.sink.played.toList())
        assertEquals(0, r.sink.cut.get())
        assertEquals(Gear.a2dp, r.sink.cues.last().headset)
        assertTrue(r.alertsShown.isEmpty())
    }

    @Test
    fun `Stop cuts a headset reply  nothing replays it on reconnect  and a stopped reply is not a lost one`() {
        val r = rig(buds, on = true)
        val stored = r.existing("work", "Started.")
        r.phoneTurn("stop-1", stored)
        r.sink.played.clear()
        val gate = CompletableDeferred<Unit>()
        val base = r.sink.starts.get()
        r.sink.hold = gate
        r.later(stored, "Stop me.")
        r.waitFor("playing") { r.sink.starts.get() > base }
        val before = r.sink.starts.get()
        r.core.orchestrator.stopFollowing()
        r.waitFor("cut") { r.sink.cut.get() == 1 }
        r.devices.disconnectAll()
        r.devices.connect(Gear.a2dp)
        r.settle(800)
        assertEquals("not started again by the reconnect", before, r.sink.starts.get())
        assertTrue(r.sink.played.isEmpty())
        assertTrue("a Stop is cancelled, not a new reply to announce", r.alertsShown.isEmpty())
    }

    @Test
    fun `a played final is never replayed when the headset reconnects`() {
        val r = rig(buds, on = true)
        val stored = r.existing("work", "Done.")
        r.phoneTurn("replay-1", stored)
        val starts = r.sink.starts.get()
        r.devices.disconnectAll()
        r.devices.connect(Gear.a2dp)
        r.settle(600)
        assertEquals(starts, r.sink.starts.get())
        assertEquals(listOf("FINAL:Done."), r.finals())
    }

    @Test
    fun `a sink failure keeps the answer as text with one notification  and no second route is tried`() {
        val r = rig(buds, on = true)
        val stored = r.existing("work", "Started.")
        r.phoneTurn("fail-1", stored)
        r.sink.played.clear()
        val starts = r.sink.starts.get()
        r.sink.failNext = "synthesis or focus failed"
        r.later(stored, "Will not play.")
        r.waitFor("the no-audio notification") { r.alertsShown.isNotEmpty() }
        r.settle()
        assertEquals(1, r.alertsShown.size)
        assertEquals(starts, r.sink.starts.get())
        assertTrue(r.sink.played.isEmpty())
    }

    // ── the Watch ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a request whose latest voice sender is the Watch is answered on the Phone headset only - amendment J supersedes the Phone-only rule`() {
        val r = rig(buds, on = true)
        val stored = r.existing("work", "Started.")
        val watch = RecordingSink()
        runBlocking {
            r.core.orchestrator.run(VoiceTurnRequest("watch-1", VoiceOrigin.WATCH, TestAudio.speechWav(), "audio/wav", watch, routing = TurnRouting.Direct(stored)))
        }
        assertTrue("nothing is handed to the Watch's speaker", watch.cues.isEmpty())
        assertEquals(Gear.a2dp, r.sink.cues.single { it.role == com.rumi.hermesvoice.core.SpokenRole.FINAL }.headset)
        r.sink.played.clear()
        r.later(stored, "For the Watch request.")
        r.waitFor("spoken on the headset") { r.sink.played.isNotEmpty() }
        r.settle()
        assertEquals(listOf("FINAL:For the Watch request."), r.sink.played.toList())
        assertEquals(Gear.a2dp, r.sink.cues.last().headset)
        assertTrue("never duplicated on the Watch", watch.cues.isEmpty())
        assertTrue(r.alertsShown.isEmpty())
    }

    @Test
    fun `with Speak later replies on  a later reply of a Watch request also plays on the Phone headset only`() {
        val r = rig(buds, on = true, laterOptIn = true)
        val stored = r.existing("work", "Started.")
        val watch = RecordingSink()
        runBlocking {
            r.core.orchestrator.run(VoiceTurnRequest("watch-2", VoiceOrigin.WATCH, TestAudio.speechWav(), "audio/wav", watch, routing = TurnRouting.Direct(stored)))
        }
        r.sink.played.clear()
        r.later(stored, "For the Watch.")
        r.waitFor("played on the headset") { r.sink.played.isNotEmpty() }
        r.settle()
        assertEquals(Gear.a2dp, r.sink.cues.last().headset)
        assertTrue(watch.cues.isEmpty())
    }
}
