package com.rumi.hermesvoice.phone

import androidx.compose.ui.unit.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.voice.PendingPhase
import com.rumi.hermesvoice.core.voice.PendingTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). The production TalkBar: no static playback line in any playback state, the recording / routing /
 * pending / hands-free states kept, a 40dp visible button inside a 48dp touch target, and growth with large text.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h900dp")
class PhoneTalkBarRoboTest {
    val rule = createComposeRule()
    @get:Rule val cleanupThenCompose: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(ComposeDispatcherCleanup()).around(rule)

    @Before fun isolateComposeDispatcher() = ComposeDispatcherIsolation.reset()

    private val state = mutableStateOf(PhoneUiState(signedIn = true))
    private var talks = 0
    private var stopped = emptyList<String>()

    private fun show() {
        rule.setContent {
            MaterialTheme { TalkBar(state.value, onStopPending = { stopped = stopped + it }) { talks += 1 } }
        }
        rule.waitForIdle()
    }

    private fun textCount(part: String) = rule.onAllNodes(SemanticsMatcher("text contains $part") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(part) } == true
    }).fetchSemanticsNodes().size

    private fun height(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot().height.value

    @Test fun T01_noStaticPlaybackLineInAnyPlaybackState() {
        for (device in listOf(VoiceOrigin.WATCH, VoiceOrigin.PHONE, null)) {
            state.value = PhoneUiState(signedIn = true, playbackDevice = device)
            if (device == VoiceOrigin.WATCH) show() else rule.waitForIdle()
            assertEquals("no 'Voice replies play' line for $device", 0, textCount("Voice replies play"))
            assertEquals("no 'last voice request' wording for $device", 0, textCount("last voice request"))
            assertEquals("the Talk label is kept for $device", 1, textCount("Talk"))
        }
    }

    @Test fun T02_theVisibleButtonIsFortyDpInsideAFortyEightDpTarget() {
        show()
        assertEquals(1, rule.onAllNodes(SemanticsMatcher("tag talk") { it.config.getOrNull(SemanticsProperties.TestTag) == "talk" }).fetchSemanticsNodes().size)
        assertEquals("the visible button", 40f, height("talk_visual"), 0.6f)
        assertTrue("the touch target: ${height("talk")}", height("talk") >= 47.9f)
        assertTrue("the bar is only as tall as the target plus a small margin: ${height("talk")}", height("talk") <= 49f)
    }

    @Test fun T03_theLabelIsReadableAndTheButtonGrowsWithLargeText() {
        RuntimeEnvironment.setFontScale(2.0f)
        show()
        rule.onNodeWithTag("talk").assertTextContains("Talk")
        assertTrue("grew beyond 40dp for a doubled font: ${height("talk_visual")}", height("talk_visual") > 41f)
        assertTrue("the target holds the visible button", height("talk") >= height("talk_visual") - 0.5f)
    }

    @Test fun T04_recordingAndHandsFreeVariantsKeepTheirLabels() {
        state.value = PhoneUiState(signedIn = true, recording = true)
        show()
        assertEquals(1, textCount("Stop & send"))
        state.value = PhoneUiState(signedIn = true, handsFree = HandsFree.SPEAK_NOW)
        rule.waitForIdle()
        assertEquals(1, textCount("Send now"))
        assertEquals("the hands-free line is kept", 1, textCount("Speak now"))
        assertEquals("still 40dp when recording", 40f, height("talk_visual"), 0.6f)
    }

    @Test fun T05_routingOffAndPendingStopAreKept() {
        state.value = PhoneUiState(signedIn = true, routingEnabled = false,
            pending = listOf(PendingTurn("turn-1", VoiceOrigin.PHONE, PendingPhase.AWAITING, "work")))
        show()
        rule.onNodeWithTag("routing_target").assertTextContains("Routing off", substring = true)
        rule.onNodeWithTag("pending_stop_0").assertIsEnabled().performClick()
        assertEquals(listOf("turn-1"), stopped)
        val routing = rule.onNodeWithTag("routing_target").getBoundsInRoot()
        val talk = rule.onNodeWithTag("talk").getBoundsInRoot()
        assertTrue("nothing overlaps Talk", routing.bottom.value <= talk.top.value + 0.5f)
    }

    @Test fun T06_theButtonCallsTheTalkHandlerOnce() {
        show()
        rule.onNodeWithTag("talk").performClick()
        assertEquals(1, talks)
    }
}
