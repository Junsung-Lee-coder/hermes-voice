package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.watchlink.TurnStateMessage
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchPhase
import com.rumi.hermesvoice.core.watchlink.WatchTalkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchTalkStateTest {
    @Test
    fun `a push-to-talk turn walks through phone stages and playback back to idle`() {
        var s = WatchTalkState().startRecording("turn-0001", TurnTrigger.PUSH_TO_TALK)
        assertFalse(s.canArmWakePhrase)
        s = s.sending()
        s = s.onPhoneState(TurnStateMessage("turn-0001", "routed", "work", false))
        assertEquals(WatchPhase.WAITING, s.phase)
        assertEquals("→ work", s.line)
        s = s.playing("turn-0001")
        assertEquals(WatchPhase.PLAYING, s.phase)
        s = s.onPhoneState(TurnStateMessage("turn-0001", "responding", "", false))
        assertEquals("a stage update never interrupts playback", WatchPhase.PLAYING, s.phase)
        s = s.playbackEnded("turn-0001")
        s = s.onPhoneState(TurnStateMessage("turn-0001", "done", "Delivered to work", true))
        assertEquals(WatchTalkState(line = "Delivered to work"), s)
        assertTrue(s.canArmWakePhrase)
    }

    @Test
    fun `messages for other turns are ignored`() {
        val s = WatchTalkState().startRecording("turn-0002", TurnTrigger.WAKE_PHRASE).sending()
        assertEquals(s, s.onPhoneState(TurnStateMessage("turn-0001", "done", "old", true)))
        val other = s.playing("turn-0001")
        assertEquals("another turn's audio does not change this turn's phase", WatchPhase.SENDING, other.phase)
        assertEquals(s, other.playbackEnded("turn-0001"))
    }

    @Test
    fun `playing a phone turn's reply on an idle watch shows it and keeps the wake phrase off the speaker`() {
        val idle = WatchTalkState(line = "Delivered to work")
        assertTrue(idle.canArmWakePhrase)
        val speaking = idle.playing("phone-turn-01")
        assertEquals(WatchPhase.IDLE, speaking.phase)
        assertEquals(WatchTalkState.PLAYING_OTHER, speaking.line)
        assertFalse("never listen for the wake phrase over our own playback", speaking.canArmWakePhrase)
        assertTrue("push-to-talk stays available", speaking.canStartPushToTalk)
        assertEquals("a stale end for another utterance is ignored", speaking, speaking.playbackEnded("phone-turn-02"))
        val done = speaking.playbackEnded("phone-turn-01")
        assertEquals(WatchTalkState(), done)
        assertTrue(done.canArmWakePhrase)
    }

    @Test
    fun `a new watch recording keeps tracking the reply still playing from an earlier turn`() {
        val speaking = WatchTalkState().playing("phone-turn-01")
        val recording = speaking.startRecording("turn-0007", TurnTrigger.PUSH_TO_TALK)
        assertEquals("phone-turn-01", recording.speakingTurnId)
        val discarded = recording.recordingDiscarded("Too short")
        assertFalse(discarded.canArmWakePhrase)
        assertTrue(discarded.playbackEnded("phone-turn-01").canArmWakePhrase)
    }

    @Test
    fun `wake phrase cannot start while busy but push-to-talk may start while waiting`() {
        val waiting = WatchTalkState().startRecording("turn-0003", TurnTrigger.PUSH_TO_TALK).sending()
            .onPhoneState(TurnStateMessage("turn-0003", "responding", "", false))
        assertFalse(waiting.canArmWakePhrase)
        assertTrue(waiting.canStartPushToTalk)
        assertEquals(WatchPhase.RECORDING, waiting.startRecording("turn-0004", TurnTrigger.PUSH_TO_TALK).phase)
        try {
            waiting.startRecording("turn-0005", TurnTrigger.WAKE_PHRASE)
            throw AssertionError("wake phrase must not start while a reply is pending")
        } catch (_: IllegalStateException) {
        }
    }
}
