package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.headset.HeadsetText
import com.rumi.hermesvoice.core.voice.AssembledRoute
import com.rumi.hermesvoice.core.voice.DestinationEntry
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.watchlink.ReplayRequest
import com.rumi.hermesvoice.core.watchlink.TurnStateMessage
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.VoiceOutcomeText
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchPhase
import com.rumi.hermesvoice.core.watchlink.WatchTalkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wire and the Watch state of "the reply's audio did not play": the retry stage, the failure and the same-reply Play again. */
class ReplayWireTest {
    private fun route() = AssembledRoute("turn-0001", VoiceOrigin.WATCH, "words", DestinationEntry("work", "work_session"), "ack")

    @Test
    fun `the audio_recovering stage shows a retry line and keeps the request waiting`() {
        var s = WatchTalkState().startRecording("turn-0001", TurnTrigger.PUSH_TO_TALK).sending()
        s = s.onPhoneState(TurnStateMessage("turn-0001", "audio_recovering", "", false))
        assertEquals(WatchTalkState.AUDIO_RECOVERING, s.line)
        assertEquals(WatchPhase.WAITING, s.phase)
        assertNull(s.replayTurnId)
    }

    @Test
    fun `a terminal state that carries replay offers Play again for that request and nothing else`() {
        var s = WatchTalkState().startRecording("turn-0001", TurnTrigger.PUSH_TO_TALK).sending()
        s = s.onPhoneState(TurnStateMessage("turn-0001", "done", "Delivered to work; reply playback failed", true, replay = true))
        assertEquals("turn-0001", s.replayTurnId)
        assertEquals(WatchPhase.IDLE, s.phase)
        val plain = WatchTalkState().startRecording("turn-0002", TurnTrigger.PUSH_TO_TALK).sending()
            .onPhoneState(TurnStateMessage("turn-0002", "done", "Delivered to work", true))
        assertNull("an ordinary end offers nothing", plain.replayTurnId)
    }

    @Test
    fun `asking again removes the control and the Phone's result is shown, offering it again when it failed again`() {
        var s = WatchTalkState().startRecording("turn-0001", TurnTrigger.PUSH_TO_TALK).sending()
            .onPhoneState(TurnStateMessage("turn-0001", "done", "failed", true, replay = true))
        s = s.replayRequested()
        assertNull(s.replayTurnId)
        assertEquals(WatchTalkState.REPLAY_REQUESTED, s.line)
        val failedAgain = s.onPhoneState(TurnStateMessage("turn-0001", "done", "Reply audio did not play: the media volume is 0 - raise it, then play the reply again", true, replay = true))
        assertEquals("turn-0001", failedAgain.replayTurnId)
        assertTrue(failedAgain.line.contains("media volume is 0"))
        val played = s.onPhoneState(TurnStateMessage("turn-0001", "done", "Reply played", true))
        assertNull(played.replayTurnId)
        assertEquals("Reply played", played.line)
    }

    @Test
    fun `a replay result never overwrites a newer request in progress`() {
        var s = WatchTalkState().startRecording("turn-0001", TurnTrigger.PUSH_TO_TALK).sending()
            .onPhoneState(TurnStateMessage("turn-0001", "done", "failed", true, replay = true)).replayRequested()
        s = s.startRecording("turn-0002", TurnTrigger.PUSH_TO_TALK)
        val after = s.onPhoneState(TurnStateMessage("turn-0001", "done", "Reply played", true))
        assertEquals(WatchPhase.RECORDING, after.phase)
        assertEquals("turn-0002", after.turnId)
        assertEquals(s.line, after.line)
    }

    @Test
    fun `a replay state for a turn that was never asked for is ignored`() {
        val s = WatchTalkState()
        assertEquals(s, s.onPhoneState(TurnStateMessage("turn-0009", "done", "Reply played", true)))
    }

    @Test
    fun `the replay request is strict and the state message stays compatible without it`() {
        val request = ReplayRequest("turn-0001")
        assertEquals(request, ReplayRequest.decode(request.encode()))
        assertNull(ReplayRequest.decode("""{"v":2,"turn_id":"turn-0001"}""".toByteArray()))
        assertNull(ReplayRequest.decode("""{"v":1,"turn_id":"../x"}""".toByteArray()))
        assertNull(ReplayRequest.decode("not json".toByteArray()))
        assertEquals("/hv/v1/replay", WatchLinkPaths.REPLAY)
        val old = TurnStateMessage.decode("""{"turn_id":"turn-0001","stage":"done","detail":"x","terminal":true}""".toByteArray())
        assertEquals(false, old?.replay)
        val withReplay = TurnStateMessage.decode(TurnStateMessage("turn-0001", "done", "x", true, replay = true).encode())
        assertEquals(true, withReplay?.replay)
        assertFalse(String(TurnStateMessage("turn-0001", "done", "x", true).encode()).contains("replay"))
    }

    @Test
    fun `a failed reply says why with a fixed phrase only and never an arbitrary exception text`() {
        val withNote = VoiceTurnOutcome.DeliveredResponseFailed(route(), listOf(SpokenRole.FIRST), "x").also { it.note = HeadsetText.VOLUME_ZERO }
        assertTrue(VoiceOutcomeText.describe(withNote), VoiceOutcomeText.describe(withNote).endsWith("the media volume is 0 - raise it, then play the reply again"))
        assertEquals("Delivered to work; reply playback failed", VoiceOutcomeText.describe(VoiceTurnOutcome.DeliveredResponseFailed(route(), emptyList(), "x")))
        assertEquals(HeadsetText.BACKGROUND_FOCUS, HeadsetText.fixedNote(HeadsetText.BACKGROUND_FOCUS))
        assertNull(HeadsetText.fixedNote("java.io.IOException: https://example.invalid/secret?token=abc"))
        assertNull(HeadsetText.fixedNote(null))
    }
}
