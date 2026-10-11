package com.rumi.hermesvoice.phone

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.rumi.hermesvoice.core.activity.ActivityBubble
import com.rumi.hermesvoice.core.chat.DispatchedSend
import com.rumi.hermesvoice.core.chat.PendingSend
import com.rumi.hermesvoice.core.net.HistoryMessage
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** SCRATCH HARNESS (never packaged). How the conversation pane draws a just-sent bubble and the transient receiving bubble. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PhoneChatPaneBubbleTest {
    val rule = createComposeRule()
    @get:Rule val cleanupThenCompose: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(ComposeDispatcherCleanup()).around(rule)

    @Before fun isolateComposeDispatcher() = ComposeDispatcherIsolation.reset()

    private fun pending(id: Long, text: String) = PendingSend(DispatchedSend(id, "s", text, emptyList()), 10)
    private fun bubble(key: String, label: String, closing: Boolean = false) = ActivityBubble(key, "s", label, closing)
    private val history = mutableStateOf(listOf(HistoryMessage(10, "assistant", "earlier", 10.0)))
    private val pendings = mutableStateOf(emptyList<PendingSend>())
    private val activity = mutableStateOf(emptyList<ActivityBubble>())

    private fun show() {
        rule.setContent {
            MaterialTheme {
                ChatPane(sessionKey = "s", history = history.value, hasOlder = false, draft = "", attachments = emptyList(),
                    onLoadOlder = {}, onDraft = {}, onSend = {}, onAttach = {}, onRemoveAttachment = {},
                    pendingSends = pendings.value, activity = activity.value)
            }
        }
        rule.waitForIdle()
    }

    @Test fun U01_aJustSentMessageIsDrawnAsMineWithItsTextAndASendingState() {
        pendings.value = listOf(pending(5, "hello **there**"))
        show()
        rule.onNodeWithTag("pending_send_5").assertIsDisplayed().assertTextContains("hello", substring = true)
        rule.onNodeWithTag("pending_send_5").assertTextContains("Sending", substring = true)
    }

    @Test fun U02_identicalPendingTextsAreTwoBubbles() {
        pendings.value = listOf(pending(1, "ok"), pending(2, "ok"))
        show()
        assertEquals(1, rule.onAllNodesWithTag("pending_send_1").fetchSemanticsNodes().size)
        assertEquals(1, rule.onAllNodesWithTag("pending_send_2").fetchSemanticsNodes().size)
    }

    @Test fun U03_theActivityBubbleShowsTheLatestLabelAndIsReplacedInPlace() {
        activity.value = listOf(bubble("k1", "⏳ Waiting for Hermes…"))
        show()
        rule.onNodeWithTag("activity_k1").assertIsDisplayed().assertTextContains("Waiting for Hermes", substring = true)
        rule.runOnIdle { activity.value = listOf(bubble("k1", "🐍 Running code")); Snapshot.sendApplyNotifications() }
        rule.waitForIdle()
        rule.onNodeWithTag("activity_k1").assertTextContains("Running code", substring = true)
        rule.runOnIdle { activity.value = listOf(bubble("k1", "🔎 Searching files")); Snapshot.sendApplyNotifications() }
        rule.waitForIdle()
        assertEquals("one bubble, never a log", 1, rule.onAllNodesWithTag("activity_k1").fetchSemanticsNodes().size)
        rule.onNodeWithTag("activity_k1").assertTextContains("Searching files", substring = true)
    }

    @Test fun U04_onceTheSlotIsReleasedTheFinalMessageIsShownAndNoActivityBubbleRemains() {
        history.value = history.value + HistoryMessage(11, "assistant", "the final answer", 11.0)
        activity.value = emptyList()
        show()
        assertEquals(0, rule.onAllNodesWithTag("activity_k1").fetchSemanticsNodes().size)
        rule.onNodeWithTag("message_11").assertIsDisplayed()
    }

    @Test fun U05_theActivityBubbleIsNotAMessageRow() {
        activity.value = listOf(bubble("k1", "🔎 Searching files"))
        show()
        assertEquals("only the real history row carries a message tag", 1,
            rule.onAllNodesWithTag("message_10").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag("message_text_activity_k1").fetchSemanticsNodes().size)
    }
}
