package com.rumi.hermesvoice.phone

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.voice.PendingPhase
import com.rumi.hermesvoice.core.voice.PendingTurn
import com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator.UnplayedReply
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * A pending voice request's own Stop and an unplayed reply's Play again (parent HOLD I1 of the owner's UI contract of 2026-10-27) belong in
 * the relevant conversation's chat: never on the list or in Settings, never as rows above the one global Talk. A request not yet routed to
 * a conversation may go to any, so its Stop is in whichever chat is open. Rendering and navigating stop, play, record or submit nothing;
 * the chat's controls are the request's and the reply's own actions. The real MainActivity and PhoneViewModel; the pending requests and
 * unplayed replies are synthetic entries of the app's own lists (the orchestrator does not know them, so a Stop or Play again of one is
 * answered "already ended" / "can't be played again now", which is how a tap is observed here).
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1280dp")
class PhoneConversationActionsRoboTest : PhoneNavRoboBase() {
    private fun shown(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun assertNoVoiceRows(where: String) {
        for (tag in listOf("pending_requests", "pending_stop_0", "unplayed_replies", "unplayed_play_0", "unplayed_background_status", "unplayed_background_action"))
            assertFalse("no $tag on $where", exists(tag))
    }
    private fun nothingWasStoppedOrPlayed(model: PhoneViewModel) {
        assertNotEquals("no request was stopped", "That request already ended", model.state.value.voiceStatus)
        assertFalse("no reply was played", shown("can't be played again"))
        assertFalse("nothing records", app.manualCapture.busy || model.state.value.recording)
        assertFalse(app.live.active); assertTrue("nothing submitted", fake.prompts.isEmpty())
    }

    @Test fun A27_01_aRequestsStopAndARepliesPlayAgainAreOnlyInTheirConversationsChat() {
        val alpha = titled("Alpha", "alpha"); val beta = titled("Beta", "beta")
        val model = showWith(alpha, beta)
        app.pendingTurns.value = listOf(PendingTurn("turn-alpha", VoiceOrigin.PHONE, PendingPhase.AWAITING, "alpha"),
            PendingTurn("turn-unrouted", VoiceOrigin.WATCH, PendingPhase.TRANSMITTING, null))
        app.unplayedReplies.value = listOf(UnplayedReply("reply-beta#final", "turn-beta", VoiceOrigin.PHONE, beta))
        waitFor("the screen knows them") { model.state.value.pending.size == 2 && model.state.value.unplayed.size == 1 }
        settle()
        assertNoVoiceRows("the list")
        assertEquals("one Talk", 1, count("talk"))
        rule.onNodeWithTag("settings_button").performClick(); settle()
        assertNoVoiceRows("Settings")
        systemBack()
        openSettled(model, alpha)
        assertTrue("alpha's request and the unrouted one", exists("pending_stop_0") && exists("pending_stop_1"))
        rule.onNodeWithTag("pending_0").assertTextContains("Request to alpha", substring = true)
        assertFalse("beta's reply is not in alpha's chat", exists("unplayed_replies"))
        systemBack()
        assertNoVoiceRows("the list again")
        openSettled(model, beta)
        assertTrue("beta's reply", exists("unplayed_play_0"))
        assertTrue("only the unrouted request", exists("pending_stop_0") && !exists("pending_stop_1"))
        rule.onNodeWithTag("pending_0").assertTextContains("Watch request", substring = true)
        assertEquals("one Talk", 1, count("talk"))
        nothingWasStoppedOrPlayed(model)
    }

    @Test fun A27_02_theChatsStopAndPlayAgainAreTheRequestsAndTheRepliesOwnActions() {
        val alpha = titled("Alpha", "alpha")
        val model = showWith(alpha)
        app.pendingTurns.value = listOf(PendingTurn("turn-alpha", VoiceOrigin.PHONE, PendingPhase.AWAITING, "alpha"))
        app.unplayedReplies.value = listOf(UnplayedReply("reply-alpha#final", "turn-a", VoiceOrigin.PHONE, alpha))
        waitFor("the screen knows them") { model.state.value.pending.size == 1 && model.state.value.unplayed.size == 1 }
        openSettled(model, alpha)
        nothingWasStoppedOrPlayed(model)
        rule.onNodeWithTag("pending_stop_0").performClick(); settle()
        waitFor("that request's own Stop ran") { model.state.value.voiceStatus == "That request already ended" }
        rule.onNodeWithTag("unplayed_play_0").performClick(); settle()
        waitFor("that reply's own Play again ran") { shown("That reply can't be played again now") }
        assertTrue("nothing submitted", fake.prompts.isEmpty())
    }
}
