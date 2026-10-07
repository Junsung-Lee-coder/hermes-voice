package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.watchlink.TurnStateMessage
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchPhase
import com.rumi.hermesvoice.core.watchlink.WatchTalkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The Watch accepts a new request while earlier ones only wait for their replies, each keeping its own end. */
class WatchTalkWhileWaitingTest {
    private fun waiting(id: String, state: WatchTalkState = WatchTalkState()): WatchTalkState =
        state.startRecording(id, TurnTrigger.PUSH_TO_TALK).sending().onPhoneState(TurnStateMessage(id, "responding", "", false))

    @Test
    fun `a waiting request does not block the wake phrase or push to talk`() {
        val state = waiting("t1")
        assertEquals(WatchPhase.WAITING, state.phase)
        assertTrue(state.canArmWakePhrase)
        assertTrue(state.canStartPushToTalk)
    }

    @Test
    fun `recording and sending still own the microphone`() {
        val recording = WatchTalkState().startRecording("t1", TurnTrigger.PUSH_TO_TALK)
        assertFalse(recording.canArmWakePhrase)
        assertFalse(recording.canStartPushToTalk)
        assertFalse(recording.sending().canArmWakePhrase)
    }

    @Test
    fun `speaking owns the speaker so the wake phrase does not listen to it`() {
        val state = waiting("t1").playing("t1")
        assertFalse(state.canArmWakePhrase)
    }

    @Test
    fun `a new request moves the waiting one to earlier and keeps both`() {
        val second = waiting("t2", waiting("t1"))
        assertEquals("t2", second.turnId)
        assertEquals(listOf("t1"), second.earlier)
        assertEquals(2, second.waitingCount)
    }

    @Test
    fun `an earlier request ends on its own terminal state without touching the current one`() {
        val state = waiting("t2", waiting("t1"))
        val progressed = state.onPhoneState(TurnStateMessage("t1", "responding", "", false))
        assertEquals(state, progressed)
        val ended = state.onPhoneState(TurnStateMessage("t1", "done", "Done", true))
        assertEquals(emptyList<String>(), ended.earlier)
        assertEquals("t2", ended.turnId)
        assertEquals(WatchPhase.WAITING, ended.phase)
    }

    @Test
    fun `the current request ending keeps the earlier ones waiting`() {
        val state = waiting("t2", waiting("t1"))
        val ended = state.onPhoneState(TurnStateMessage("t2", "done", "Done", true))
        assertEquals(WatchPhase.IDLE, ended.phase)
        assertEquals(listOf("t1"), ended.earlier)
        assertEquals(1, ended.waitingCount)
    }

    @Test
    fun `a stage message for an unknown turn is ignored`() {
        val state = waiting("t1")
        assertEquals(state, state.onPhoneState(TurnStateMessage("zzz", "responding", "", false)))
    }

    @Test
    fun `the queued stage is shown truthfully`() {
        val state = WatchTalkState().startRecording("t1", TurnTrigger.PUSH_TO_TALK).sending()
            .onPhoneState(TurnStateMessage("t1", "queued", "", false))
        assertEquals(WatchTalkState.QUEUED, state.line)
    }

    @Test
    fun `the waiting bound refuses a new request instead of dropping one`() {
        var state = waiting("t0")
        for (n in 1..WatchTalkState.MAX_EARLIER) state = waiting("t$n", state)
        assertEquals(WatchTalkState.MAX_EARLIER, state.earlier.size)
        assertFalse(state.canArmWakePhrase)
        assertFalse(state.canStartPushToTalk)
        try {
            state.startRecording("overflow", TurnTrigger.WAKE_PHRASE)
            fail("a request past the bound must not start")
        } catch (_: IllegalStateException) {
        }
        assertEquals("t0", state.earlier.first())
        assertEquals(WatchTalkState.MAX_EARLIER + 1, state.waitingCount)
    }

    @Test
    fun `stop drops every request of the Watch and keeps the speaker`() {
        val state = waiting("t2", waiting("t1")).playing("t1")
        val stopped = state.stopped("Stopped")
        assertNull(stopped.turnId)
        assertEquals(emptyList<String>(), stopped.earlier)
        assertEquals(0, stopped.waitingCount)
        assertEquals("t1", stopped.speakingTurnId)
    }

    @Test
    fun `a discarded or unsent recording leaves the earlier waiting requests`() {
        val recording = waiting("t2", waiting("t1")).let { it.startRecording("t3", TurnTrigger.WAKE_PHRASE) }
        assertEquals(listOf("t1", "t2"), recording.earlier)
        assertEquals(listOf("t1", "t2"), recording.recordingDiscarded("Too quiet").earlier)
        assertEquals(listOf("t1", "t2"), recording.sending().sendFailed("No phone").earlier)
    }

    @Test
    fun `an earlier turn's audio is tracked as the speaker owner and ends`() {
        val state = waiting("t2", waiting("t1")).playing("t1")
        assertEquals("t1", state.speakingTurnId)
        assertEquals(WatchPhase.WAITING, state.phase)
        val ended = state.playbackEnded("t1")
        assertNull(ended.speakingTurnId)
        assertEquals(listOf("t1"), ended.earlier)
    }
}
