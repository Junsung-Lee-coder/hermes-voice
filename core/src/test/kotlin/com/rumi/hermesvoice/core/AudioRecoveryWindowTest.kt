package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.voice.AudioPhase
import com.rumi.hermesvoice.core.voice.AudioRecoveryEvent
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 60 s recovery window runs on the injected monotonic clock and only decides whether ANOTHER retry may be scheduled: a healthy
 * long synthesis or playback is never clipped by it, Stop ends IO in flight exactly once, and a wait gap holds neither the microphone
 * nor the speaker. The clock is virtual (jumped by the test), the retry delays are the real 1 s / 3 s.
 */
class AudioRecoveryWindowTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = AtomicLong(0L)
    private val rigs = mutableListOf<HeadsetRig>()
    private val events: MutableList<AudioRecoveryEvent> = Collections.synchronizedList(mutableListOf())
    private val listener = object : VoiceTurnListener {
        override fun onAudioRecovery(turnId: String, event: AudioRecoveryEvent) { events += event }
    }

    @After fun tearDown() {
        rigs.forEach { it.close() }
        scope.cancel()
    }

    private fun rig(): HeadsetRig = HeadsetRig(scope, FakeDevices(listOf(Gear.speaker, Gear.a2dp)), voiceListener = listener, headsetOn = true, recoveryClock = clock::get)
        .also { rigs += it }

    private fun HeadsetRig.primed(): String {
        val stored = existing("work", "Started.")
        phoneTurn("prime", stored)
        sink.played.clear()
        sink.offered.clear()
        speakerHolds.clear()
        events.clear()
        return stored
    }

    private val longReply = (1..60).joinToString("") { "Sentence number $it is long enough to matter here. " }.trim()

    @Test
    fun `a healthy playback that outlasts the window is not clipped and raises no alert`() {
        val r = rig()
        val stored = r.primed()
        val gate = CompletableDeferred<Unit>()
        r.sink.hold = gate
        val before = r.sink.starts.get()
        r.later(stored, "Long and healthy.")
        r.waitFor("the playback to start", 10_000) { r.sink.starts.get() > before }
        clock.addAndGet(300_000L)
        r.settle(300)
        assertTrue("still playing, nothing cut", r.sink.cut.get() == 0 && r.sink.played.isEmpty())
        gate.complete(Unit)
        r.waitFor("the reply to be heard", 10_000) { r.sink.played.isNotEmpty() }
        r.settle(300)
        assertEquals(listOf("FINAL:Long and healthy."), r.sink.played.toList())
        assertTrue(r.alertsShown.isEmpty())
        assertTrue("no recovery happened: $events", events.isEmpty())
    }

    @Test
    fun `a long reply whose chunks take longer than the window in all is heard to the end`() {
        val r = rig()
        val stored = r.primed()
        r.sink.onCue = { clock.addAndGet(30_000L) }
        val chunks = com.rumi.hermesvoice.core.voice.TtsBatcher.split(longReply)
        assertTrue(chunks.size >= 3)
        r.later(stored, longReply)
        r.waitFor("every chunk to be heard", 30_000) { r.sink.played.size >= chunks.size }
        r.settle(300)
        assertEquals(chunks.map { "FINAL:${it.trim()}" }, r.sink.played.toList())
        assertTrue("virtual time passed the window: ${clock.get()}", clock.get() > 60_000L)
        assertTrue(r.alertsShown.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test
    fun `no further retry is scheduled once the window since the first failure is used up`() {
        val r = rig()
        val stored = r.primed()
        r.sink.transientFailures = 9
        r.sink.onCue = { if (r.sink.offered.size == 2) clock.addAndGet(61_000L) }
        r.later(stored, "Too late for a third try.")
        r.waitFor("the no-audio notification", 20_000) { r.alertsShown.isNotEmpty() }
        r.settle(1_500)
        assertEquals("two attempts: the window forbade a third", 2, r.sink.offered.size)
        assertEquals(1, r.alertsShown.size)
        assertEquals(listOf(AudioPhase.WAIT_STARTED, AudioPhase.EXHAUSTED), events.map { it.phase })
    }

    @Test
    fun `the same two failures inside the window still get their third attempt`() {
        val r = rig()
        val stored = r.primed()
        r.sink.transientFailures = 2
        r.sink.onCue = { if (r.sink.offered.size == 2) clock.addAndGet(10_000L) }
        r.later(stored, "Third time lucky.")
        r.waitFor("the reply to be heard", 20_000) { r.sink.played.isNotEmpty() }
        assertEquals(3, r.sink.offered.size)
        assertEquals(listOf("FINAL:Third time lucky."), r.sink.played.toList())
        assertTrue(r.alertsShown.isEmpty())
    }

    @Test
    fun `Stop during playback cuts it once and a late completion plays nothing`() {
        val r = rig()
        val stored = r.primed()
        val gate = CompletableDeferred<Unit>()
        r.sink.hold = gate
        val before = r.sink.starts.get()
        r.later(stored, "Stopped mid sentence.")
        r.waitFor("the playback to start", 10_000) { r.sink.starts.get() > before }
        r.core.orchestrator.stopFollowing()
        r.waitFor("the playback to be cut", 5_000) { r.sink.cut.get() == 1 }
        gate.complete(Unit)
        r.settle(600)
        assertEquals("cut exactly once", 1, r.sink.cut.get())
        assertTrue("a late completion plays nothing: ${r.sink.played}", r.sink.played.isEmpty())
        assertTrue("a Stop is not a failure: ${r.alertsShown}", r.alertsShown.isEmpty())
    }

    @Test
    fun `Stop during synthesis offers nothing to the player afterwards`() {
        val r = rig()
        val stored = r.primed()
        val base = r.fake.speakRequests.get()
        r.fake.speakDelayMs = 1_500L
        r.later(stored, "Stopped while being made.")
        r.waitFor("the synthesis request", 10_000) { r.fake.speakRequests.get() > base }
        r.core.orchestrator.stopFollowing()
        r.settle(2_500)
        assertTrue("nothing offered after the Stop: ${r.sink.offered}", r.sink.offered.isEmpty())
        assertTrue(r.sink.played.isEmpty())
        assertTrue(r.alertsShown.isEmpty())
    }

    @Test
    fun `a failed then replayed reply raises one notification and is never announced again`() {
        val r = rig()
        val stored = r.primed()
        r.sink.failNext = "phone playback error 1/0"
        r.later(stored, "Announced once.")
        r.waitFor("the no-audio notification", 10_000) { r.alertsShown.isNotEmpty() }
        val identity = r.alertsShown.first().substringBefore('@')
        r.sink.failNext = "phone playback error 1/0"
        val first = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        assertTrue(r.core.orchestrator.replayAudio(identity) { heard, _ -> first.set(heard) })
        r.waitFor("the first replay to end", 10_000) { first.get() != null }
        assertEquals(false, first.get())
        assertEquals("a failed replay is not announced again", 1, r.alertsShown.size)
        val second = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        assertTrue(r.core.orchestrator.replayAudio(identity) { heard, _ -> second.set(heard) })
        r.waitFor("the second replay to end", 10_000) { second.get() != null }
        assertEquals(true, second.get())
        assertEquals(1, r.alertsShown.size)
        assertEquals(listOf("FINAL:Announced once."), r.sink.played.toList())
    }

    @Test
    fun `a synthesis wait holds neither the microphone nor the speaker`() {
        val r = rig()
        val stored = r.primed()
        val base = r.fake.speakRequests.get()
        r.fake.speakDelayMs = 1_500L
        r.later(stored, "Slowly made.")
        r.waitFor("the synthesis request", 10_000) { r.fake.speakRequests.get() > base }
        r.settle(300)
        assertFalse("no speaker hold while synthesizing: ${r.speakerHolds}", r.speakerHolds.lastOrNull()?.second == true)
        assertFalse(r.core.orchestrator.ownership.microphoneClaimed(VoiceOrigin.PHONE))
        r.waitFor("the reply to be heard", 15_000) { r.sink.played.isNotEmpty() }
        r.settle(300)
        assertEquals("every hold is released", r.speakerHolds.count { it.second }, r.speakerHolds.count { !it.second })
    }

    @Test
    fun `the wait between audio retries holds neither the microphone nor the speaker`() {
        val r = rig()
        val stored = r.primed()
        r.sink.transientFailures = 1
        r.later(stored, "Waits then plays.")
        r.waitFor("the first failed attempt", 10_000) { r.sink.offered.size == 1 }
        r.settle(300)
        assertFalse("no speaker hold while waiting to retry: ${r.speakerHolds}", r.speakerHolds.lastOrNull()?.second == true)
        assertFalse(r.core.orchestrator.ownership.microphoneClaimed(VoiceOrigin.PHONE))
        r.waitFor("the reply to be heard", 15_000) { r.sink.played.isNotEmpty() }
        r.settle(300)
        assertEquals(r.speakerHolds.count { it.second }, r.speakerHolds.count { !it.second })
    }

    @Test
    fun `a raw playback error is never spoken or shown as a fixed note and the next playback speaks only the reply`() {
        val raw = "HTTP 500 from https://tts.internal.example/v1?token=SECRET-1234 at /data/cache/clip.wav"
        assertEquals(null, com.rumi.hermesvoice.core.headset.HeadsetText.fixedNote(raw))
        val r = rig()
        val stored = r.primed()
        r.sink.failNext = raw
        r.later(stored, "Only the answer is ever spoken.")
        r.waitFor("the not-played notification", 10_000) { r.alertsShown.isNotEmpty() }
        r.settle(300)
        assertEquals("a non-transient failure is not retried", 1, r.sink.offered.size)
        assertTrue("nothing but the reply was synthesized or offered: ${r.sink.offered}", r.sink.offered.all { it.role == com.rumi.hermesvoice.core.SpokenRole.FINAL })
        assertTrue(r.sink.played.isEmpty())
        assertTrue("no event carries the raw text: $events", events.none { raw in it.toString() || "SECRET" in it.toString() })
    }
}
