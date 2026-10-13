package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.voice.AudioPhase
import com.rumi.hermesvoice.core.voice.AudioRecoveryEvent
import com.rumi.hermesvoice.core.voice.PendingPhase
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnStage
import java.util.Collections
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
 * A reply whose audio failed in the PLAYER (not in synthesis) is tried again from the audio already held, without asking Hermes or the
 * speech server again; the user sees the wait; a failure that is not transient is not retried and never reaches the loudspeaker.
 */
class AudioRecoveryPlaybackTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var rig: HeadsetRig? = null
    private val events: MutableList<AudioRecoveryEvent> = Collections.synchronizedList(mutableListOf())
    private val stages: MutableList<VoiceTurnStage> = Collections.synchronizedList(mutableListOf())
    private val diagnostics: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val listener = object : VoiceTurnListener {
        override fun onAudioRecovery(turnId: String, event: AudioRecoveryEvent) { events += event }
        override fun onStage(turnId: String, stage: VoiceTurnStage) { stages += stage }
        override fun onDiagnostic(turnId: String, stage: String, detail: String) { diagnostics += "$stage $detail" }
    }

    @After fun tearDown() {
        rig?.close()
        scope.cancel()
    }

    private fun rigOn(): HeadsetRig = HeadsetRig(scope, FakeDevices(listOf(Gear.speaker, Gear.a2dp)), voiceListener = listener, headsetOn = true).also { rig = it }

    private fun HeadsetRig.primed(): String {
        val stored = existing("work", "Started.")
        phoneTurn("prime", stored)
        sink.played.clear()
        sink.offered.clear()
        events.clear()
        stages.clear()
        return stored
    }

    @Test
    fun `a later reply whose player was not ready is played again from the same audio without new synthesis`() {
        val r = rigOn()
        val stored = r.primed()
        val speakBefore = r.fake.speakRequests.get()
        r.sink.transientFailures = 1
        r.later(stored, "Hello again.")
        r.waitFor("the reply to be heard", 15_000) { r.sink.played.isNotEmpty() }
        r.settle(300)
        assertEquals(listOf("FINAL:Hello again."), r.sink.played.toList())
        assertEquals("the audio held is reused: one synthesis in all", speakBefore + 1, r.fake.speakRequests.get())
        assertEquals("two cues were offered to the headset sink", 2, r.sink.offered.size)
        assertTrue("every cue stays bound to the headset: ${r.sink.offered}", r.sink.offered.all { it.headset != null })
        assertTrue("a recovered reply is heard, so no no-audio notification: ${r.alertsShown}", r.alertsShown.isEmpty())
        assertEquals(listOf(AudioPhase.WAIT_STARTED, AudioPhase.RECOVERED), events.map { it.phase })
        assertEquals(1, events.first().attempt)
        assertEquals(1_000L, events.first().waitMs)
    }

    @Test
    fun `a player failure that is not transient is not retried and the user is told once`() {
        val r = rigOn()
        val stored = r.primed()
        r.sink.failNext = "phone playback error 1/0"
        r.later(stored, "Plain failure.")
        r.waitFor("the no-audio notification", 10_000) { r.alertsShown.isNotEmpty() }
        r.settle(1_800)
        assertEquals("not retried", 1, r.sink.offered.size)
        assertEquals(1, r.alertsShown.size)
        assertTrue(r.sink.played.isEmpty())
        assertTrue("no recovery event for a terminal failure: $events", events.none { it.phase == AudioPhase.WAIT_STARTED })
    }

    @Test
    fun `three transient player failures end the reply - exactly three attempts - and the user is told once`() {
        val r = rigOn()
        val stored = r.primed()
        r.sink.transientFailures = 9
        r.later(stored, "Never audible.")
        r.waitFor("the no-audio notification", 20_000) { r.alertsShown.isNotEmpty() }
        r.settle(1_500)
        assertEquals("exactly three audio attempts", 3, r.sink.offered.size)
        assertEquals(1, r.alertsShown.size)
        assertTrue(r.sink.played.isEmpty())
        assertEquals(listOf(AudioPhase.WAIT_STARTED, AudioPhase.WAIT_STARTED, AudioPhase.EXHAUSTED), events.map { it.phase })
        assertEquals(listOf(1_000L, 3_000L), events.mapNotNull { it.waitMs })
    }

    @Test
    fun `the headset disconnecting during the wait ends the reply and nothing is played on the speaker instead`() {
        val r = rigOn()
        val stored = r.primed()
        r.sink.transientFailures = 1
        r.later(stored, "Private.")
        r.waitFor("the first failed attempt", 10_000) { r.sink.offered.size == 1 }
        r.devices.disconnectAll()
        r.waitFor("the no-audio notification", 10_000) { r.alertsShown.isNotEmpty() }
        r.settle(1_500)
        assertTrue("never spoken on the loudspeaker: ${r.sink.played}", r.sink.played.isEmpty())
        assertTrue("no cue without a headset: ${r.sink.offered}", r.sink.offered.all { it.headset != null })
        assertEquals(1, r.alertsShown.size)
    }

    @Test
    fun `Stop while waiting for the player retry cancels the retry and reports a Stop`() {
        val r = rigOn()
        val stored = r.primed()
        r.sink.transientFailures = 9
        r.later(stored, "Stopped before retry.")
        r.waitFor("the first failed attempt", 10_000) { r.sink.offered.size == 1 }
        r.core.orchestrator.stopFollowing()
        r.settle(3_500)
        assertEquals("no retry after Stop", 1, r.sink.offered.size)
        assertTrue(r.alertsShown.isEmpty())
        assertTrue("Stop is reported as cancelled: $events", events.any { it.phase == AudioPhase.CANCELLED })
    }

    @Test
    fun `a voice request's own reply is retried too - the request is not sent again - and the wait is visible`() {
        val r = rigOn()
        val stored = r.primed()
        val submitsBefore = r.fake.timeline.toList().count { it.startsWith("submit") }
        r.sink.transientFailures = 1
        val outcome = r.phoneTurn("own-1", stored)
        assertTrue("completed: $outcome", outcome is VoiceTurnOutcome.Completed)
        assertEquals(listOf("FINAL:Started."), r.sink.played.filter { it.startsWith("FINAL") })
        assertEquals("submitted once", submitsBefore + 1, r.fake.timeline.toList().count { it.startsWith("submit") })
        assertTrue("the wait is shown to the Phone and the Watch: $stages", VoiceTurnStage.AUDIO_RECOVERING in stages)
        assertEquals(VoiceTurnStage.RESPONDING, stages.last { it == VoiceTurnStage.AUDIO_RECOVERING || it == VoiceTurnStage.RESPONDING })
        assertTrue(events.any { it.phase == AudioPhase.RECOVERED })
        assertTrue(r.alertsShown.isEmpty())
        assertTrue("nothing is waiting any more", r.core.orchestrator.pending.value.isEmpty())
    }

    @Test
    fun `while the retry waits the turn is listed as waiting for audio`() {
        val r = rigOn()
        val stored = r.primed()
        r.sink.transientFailures = 1
        val phases: MutableList<PendingPhase> = Collections.synchronizedList(mutableListOf())
        val watcher = Thread {
            val end = System.nanoTime() + 8_000_000_000L
            while (System.nanoTime() < end) {
                r.core.orchestrator.pending.value.forEach { phases += it.phase }
                Thread.sleep(20)
            }
        }.also { it.start() }
        r.phoneTurn("own-2", stored)
        watcher.join()
        assertTrue("saw WAITING_AUDIO in $phases", PendingPhase.WAITING_AUDIO in phases)
    }

    @Test
    fun `recovery diagnostics and events carry no reply text`() {
        val r = rigOn()
        val stored = r.primed()
        r.sink.transientFailures = 2
        r.later(stored, "Secret words in the answer.")
        r.waitFor("the reply to be heard", 20_000) { r.sink.played.isNotEmpty() }
        val audio = diagnostics.filter { it.startsWith("audio_recovery") }
        assertTrue("audio_recovery diagnostics were recorded: $diagnostics", audio.isNotEmpty())
        assertFalse(audio.any { it.contains("Secret") || it.contains("words") })
        assertFalse(events.toString().contains("Secret"))
    }

    private fun HeadsetRig.failedReplyIdentity(): String = alertsShown.first().substringBefore('@')

    @Test
    fun `a reply that ended without audio is played again on request - same text - without sending anything`() {
        val r = rigOn()
        val stored = r.primed()
        r.sink.failNext = "phone playback error 1/0"
        r.later(stored, "Please say this again.")
        r.waitFor("the no-audio notification", 10_000) { r.alertsShown.isNotEmpty() }
        val submitsBefore = r.fake.timeline.toList().count { it.startsWith("submit") }
        val done = java.util.concurrent.atomic.AtomicReference<Pair<Boolean, String>?>(null)
        assertTrue("the failed reply can be replayed", r.core.orchestrator.replayAudio(r.failedReplyIdentity()) { heard, detail -> done.set(heard to detail) })
        r.waitFor("the replay to be heard", 10_000) { r.sink.played.isNotEmpty() }
        r.settle(300)
        assertEquals(listOf("FINAL:Please say this again."), r.sink.played.toList())
        assertEquals(true, done.get()?.first)
        assertEquals("nothing is sent again", submitsBefore, r.fake.timeline.toList().count { it.startsWith("submit") })
        assertEquals("one notification only", 1, r.alertsShown.size)
        assertFalse("a heard reply cannot be replayed again by mistake", r.core.orchestrator.replayAudio(r.failedReplyIdentity()))
    }

    @Test
    fun `a replay of a private reply with no headset connected plays nothing and can be asked again`() {
        val r = rigOn()
        val stored = r.primed()
        r.sink.failNext = "phone playback error 1/0"
        r.later(stored, "Private replay.")
        r.waitFor("the no-audio notification", 10_000) { r.alertsShown.isNotEmpty() }
        r.devices.disconnectAll()
        val done = java.util.concurrent.atomic.AtomicReference<Pair<Boolean, String>?>(null)
        assertTrue(r.core.orchestrator.replayAudio(r.failedReplyIdentity()) { heard, detail -> done.set(heard to detail) })
        r.waitFor("the replay to be refused", 10_000) { done.get() != null }
        assertEquals(false, done.get()?.first)
        assertTrue("never on the loudspeaker: ${r.sink.played}", r.sink.played.isEmpty())
        assertTrue(r.sink.offered.all { it.headset != null })
        r.devices.connect(Gear.a2dp)
        assertTrue("still available once the headset is back", r.core.orchestrator.replayAudio(r.failedReplyIdentity()))
        r.waitFor("the replay to be heard", 10_000) { r.sink.played.isNotEmpty() }
        assertEquals(listOf("FINAL:Private replay."), r.sink.played.toList())
    }

    @Test
    fun `the replies that can be played again are listed without their text and leave the list once heard`() {
        val r = rigOn()
        val stored = r.primed()
        assertTrue(r.core.orchestrator.unplayedReplies.value.isEmpty())
        r.sink.failNext = "phone playback error 1/0"
        r.later(stored, "Secret words that must not be listed.")
        r.waitFor("the no-audio notification", 10_000) { r.alertsShown.isNotEmpty() }
        r.waitFor("the list to show it", 5_000) { r.core.orchestrator.unplayedReplies.value.isNotEmpty() }
        val listed = r.core.orchestrator.unplayedReplies.value
        assertEquals(listOf(r.failedReplyIdentity()), listed.map { it.identity })
        assertFalse("never the reply text: $listed", listed.toString().contains("Secret"))
        assertTrue(r.core.orchestrator.replayAudio(r.failedReplyIdentity()))
        r.waitFor("the replay to be heard", 10_000) { r.sink.played.isNotEmpty() }
        r.waitFor("the list to be empty", 5_000) { r.core.orchestrator.unplayedReplies.value.isEmpty() }
    }

    @Test
    fun `a Watch node's replay request is refused for a Phone reply and for another node`() {
        val r = rigOn()
        val stored = r.primed()
        r.sink.failNext = "phone playback error 1/0"
        r.later(stored, "Phone owned.")
        r.waitFor("the no-audio notification", 10_000) { r.alertsShown.isNotEmpty() }
        assertFalse("a node cannot replay a reply that is not a Watch request's", r.core.orchestrator.replayAudio(r.failedReplyIdentity(), "node-a"))
        assertTrue(r.sink.played.isEmpty())
        assertTrue("the Phone itself still can", r.core.orchestrator.replayAudio(r.failedReplyIdentity()))
    }

    @Test
    fun `an unknown reply cannot be replayed`() {
        val r = rigOn()
        assertFalse(r.core.orchestrator.replayAudio("nope#later1"))
    }

    private val longReply = (1..60).joinToString("") { "Sentence number $it is long enough to matter here. " }.trim()

    @Test
    fun `a chunk that was heard is not played again when the next chunk needs a retry`() {
        val r = rigOn()
        val stored = r.primed()
        val chunks = com.rumi.hermesvoice.core.voice.TtsBatcher.split(longReply)
        assertTrue("the reply is long enough to be several chunks: ${chunks.size}", chunks.size >= 2)
        val speakBefore = r.fake.speakRequests.get()
        r.sink.transientAtPart = 1
        r.later(stored, longReply)
        r.waitFor("every chunk to be heard", 30_000) { r.sink.played.size >= chunks.size }
        r.settle(500)
        // The dashboard client sends text.trim() for synthesis, so what is heard is the trimmed chunk.
        assertEquals("each chunk exactly once, in order", chunks.map { "FINAL:${it.trim()}" }, r.sink.played.toList())
        val squash = { s: String -> s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") }
        assertEquals("nothing of the reply is lost", squash(longReply), squash(r.sink.played.joinToString(" ") { it.removePrefix("FINAL:") }))
        assertEquals("no chunk is synthesized twice", speakBefore + chunks.size, r.fake.speakRequests.get())
        assertTrue(r.alertsShown.isEmpty())
    }

    @Test
    fun `a server that refuses the request with 403 or 404 is not retried`() {
        for (status in listOf(403, 404)) {
            val r = rigOn()
            val stored = r.primed()
            val base = r.fake.speakRequests.get()
            r.fake.speakStatuses.add(status)
            r.later(stored, "Refused $status.")
            r.waitFor("the no-audio notification", 10_000) { r.alertsShown.isNotEmpty() }
            r.settle(1_800)
            assertEquals("status $status is final", base + 1, r.fake.speakRequests.get())
            r.close()
            rig = null
        }
    }
}
