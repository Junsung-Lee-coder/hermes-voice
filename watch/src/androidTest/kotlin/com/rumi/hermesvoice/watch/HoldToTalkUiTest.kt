package com.rumi.hermesvoice.watch

import android.util.Log
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performRotaryScrollInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.material.MaterialTheme
import com.rumi.hermesvoice.core.watchlink.HoldToggleTracker
import com.rumi.hermesvoice.core.watchlink.LoadStatus
import com.rumi.hermesvoice.core.watchlink.ReaderHistory
import com.rumi.hermesvoice.core.watchlink.ReaderMessageRow
import com.rumi.hermesvoice.core.watchlink.ReaderSessionRow
import com.rumi.hermesvoice.core.watchlink.ReaderSessionsState
import com.rumi.hermesvoice.core.watchlink.ReaderSurface
import com.rumi.hermesvoice.core.watchlink.SwipeDirection
import com.rumi.hermesvoice.core.watchlink.WatchPhase
import com.rumi.hermesvoice.core.watchlink.WatchTalkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Watch's main screen as the activity composes it: [ReaderRoot] with the activity's
 * [readerSwipe] and the real [SessionsSurface] / [ChatSurface], in a plain test activity. The
 * hold calls a counter instead of the recorder (no audio, no Phone). Time is the test clock's.
 */
@RunWith(AndroidJUnit4::class)
class HoldToTalkUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var toggles = 0
    private val selected = mutableListOf<String>()
    private val swipes = mutableListOf<SwipeDirection>()
    private var surface by mutableStateOf(ReaderSurface.SESSIONS)
    private var talk by mutableStateOf(WatchTalkState())
    private var enabled = true

    private val sessions = ReaderSessionsState(
        rows = (1..14).map { ReaderSessionRow("s$it", "Conversation $it", "c$it", "preview $it", it.toDouble()) },
        status = LoadStatus.READY)
    private val history = ReaderHistory("s1", (1L..30L).map {
        ReaderMessageRow(it, if (it % 2 == 0L) "user" else "assistant", "Message $it " + "text ".repeat(6), false)
    }, status = LoadStatus.READY)

    @Before
    fun show() {
        rule.setContent {
            val chatFocus = remember { FocusRequester() }
            val sessionsFocus = remember { FocusRequester() }
            MaterialTheme {
                ReaderRoot(
                    modifier = Modifier.readerSwipe { swipes += it },
                    surfaceKey = surface,
                    recording = talk.phase == WatchPhase.RECORDING,
                    enabled = { enabled },
                    onHoldToggle = { toggles += 1 },
                ) {
                    when (surface) {
                        ReaderSurface.SESSIONS -> SessionsSurface(sessions, null, sessionsFocus, onSelect = { selected += it },
                            onRefresh = {}, onScrollStep = {})
                        ReaderSurface.CHAT -> ChatSurface("Conversation 1", history, chatFocus, onOlder = {}, onRetry = {},
                            onScrollStep = {}) { TalkStatusLine(talk, wakeListening = false) }
                    }
                    LaunchedEffect(surface) {
                        runCatching { if (surface == ReaderSurface.SESSIONS) sessionsFocus.requestFocus() else chatFocus.requestFocus() }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
    }

    private fun advance(ms: Long) = rule.mainClock.advanceTimeBy(ms, ignoreFrameDuration = true)

    /**
     * For gestures that really scroll a list: frames must run while it scrolls (a paused clock would
     * leave the scroll's recomposition pending forever). The hold's own second is still advanced
     * explicitly afterwards.
     */
    private fun framesRun() {
        rule.mainClock.autoAdvance = true
    }

    private val slop by lazy { ViewConfiguration.get(rule.activity).scaledTouchSlop.toFloat() }

    private fun chip(n: Int) = rule.onNodeWithTag("session_c$n")

    // ── the threshold, once per press, a new press to stop ───────────────────────────────────

    @Test
    fun nineHundredNinetyNineMillisecondsIsNotAHoldAndATapStillSelects() {
        chip(2).performTouchInput { down(center) }
        advance(HoldToggleTracker.HOLD_MS - 1)
        assertEquals(0, toggles)
        chip(2).performTouchInput { up() }
        advance(500)
        assertEquals(0, toggles)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals("a press shorter than the hold is still the chip's tap", listOf("s2"), selected.toList())
    }

    @Test
    fun oneSecondTogglesOnceWhileHeldAndTheReleaseIsNotATap() {
        chip(3).performTouchInput { down(center) }
        advance(HoldToggleTracker.HOLD_MS - 1)
        assertEquals(0, toggles)
        advance(1)
        assertEquals("exactly at 1000 ms, finger still down", 1, toggles)
        advance(4_000)
        chip(3).performTouchInput { moveBy(Offset(0f, slop * 4)) }
        advance(100)
        assertEquals("holding on, even moving, never toggles twice", 1, toggles)
        chip(3).performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(1, toggles)
        assertTrue("the release after a hold is not a selection", selected.isEmpty())
        assertTrue("nor a swipe", swipes.isEmpty())
    }

    @Test
    fun aSecondSeparateHoldTogglesAgain() {
        rule.onNodeWithTag("reader_root").performTouchInput { down(center) }
        advance(1_200)
        rule.onNodeWithTag("reader_root").performTouchInput { up() }
        advance(300)
        assertEquals(1, toggles)
        talk = WatchTalkState(WatchPhase.RECORDING, "turn-1", null, "Listening…")
        advance(100)
        rule.onNodeWithTag("reader_root").performTouchInput { down(center) }
        advance(999)
        assertEquals(1, toggles)
        advance(1)
        rule.onNodeWithTag("reader_root").performTouchInput { up() }
        advance(100)
        assertEquals("start, then stop with a new press", 2, toggles)
    }

    // ── movement and arbitration ─────────────────────────────────────────────────────────────

    @Test
    fun jitterWithinTheTouchSlopStillHolds() {
        chip(4).performTouchInput { down(center); moveBy(Offset(slop * 0.4f, slop * 0.4f)) }
        advance(1_000)
        assertEquals(1, toggles)
        chip(4).performTouchInput { up() }
    }

    @Test
    fun movingPastTheSlopAndBackCancels() {
        framesRun()
        // Mid-list, so the vertical out-and-back is a real scroll (a finger held against the top edge
        // would hold the stretch overscroll open, which animates every frame and never lets the test idle).
        rule.onNodeWithTag("session_list").performScrollToIndex(6)
        rule.onNodeWithTag("reader_root").performTouchInput { down(center); moveBy(Offset(0f, slop + 3f)) }
        rule.onNodeWithTag("reader_root").performTouchInput { moveBy(Offset(0f, -(slop + 3f))) }
        advance(2_000)
        rule.onNodeWithTag("reader_root").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals("vertical out and back", 0, toggles)
        // Sideways out and back: the swipe detector takes it, and travels back to nothing.
        rule.onNodeWithTag("reader_root").performTouchInput { down(center); moveBy(Offset(slop + 3f, 0f)) }
        rule.onNodeWithTag("reader_root").performTouchInput { moveBy(Offset(-(slop + 3f), 0f)) }
        advance(2_000)
        rule.onNodeWithTag("reader_root").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals("horizontal out and back", 0, toggles)
        assertTrue("no tap", selected.isEmpty())
        assertTrue("too short to be a swipe", swipes.isEmpty())
    }

    @Test
    fun aVerticalScrollScrollsTheListAndNeverToggles() {
        framesRun()
        rule.onNodeWithTag("session_list").performTouchInput { swipeUp(durationMillis = 300) }
        advance(2_000)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(0, toggles)
        assertTrue("the list moved", rule.onAllNodesWithTag("session_c1").fetchSemanticsNodes().isEmpty() ||
            rule.onAllNodesWithTag("session_c14").fetchSemanticsNodes().isNotEmpty())
        assertTrue(selected.isEmpty())
    }

    @Test
    fun aHorizontalSwipeIsStillTheSwipeAndNeverToggles() {
        framesRun()
        rule.onNodeWithTag("reader_root").performTouchInput { swipeLeft(durationMillis = 300) }
        advance(2_000)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(listOf(SwipeDirection.RIGHT_TO_LEFT), swipes.toList())
        assertEquals(0, toggles)
    }

    @Test
    fun aSecondFingerCancels() {
        rule.onNodeWithTag("reader_root").performTouchInput {
            down(0, center)
            down(1, center + Offset(30f, 30f))
        }
        advance(1_500)
        rule.onNodeWithTag("reader_root").performTouchInput { up(1); up(0) }
        advance(100)
        assertEquals(0, toggles)
    }

    @Test
    fun aCancelledPointerCancels() {
        rule.onNodeWithTag("reader_root").performTouchInput { down(center) }
        advance(600)
        rule.onNodeWithTag("reader_root").performTouchInput { cancel() }
        advance(1_000)
        assertEquals(0, toggles)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aBezelTurnDuringTheHoldCancels() {
        rule.onNodeWithTag("reader_root").performTouchInput { down(center) }
        advance(400)
        // On the paused clock, so the bezel turn lands at 400 ms (an auto-advancing idle wait would run
        // the clock past the second first); advancing then also runs the list's bezel scroll.
        rule.onNodeWithTag("session_list").performRotaryScrollInput { rotateToScrollVertically(60f) }
        advance(1_000)
        rule.onNodeWithTag("reader_root").performTouchInput { up() }
        advance(100)
        assertEquals(0, toggles)
    }

    // ── state, screens and lifecycle ─────────────────────────────────────────────────────────

    @Test
    fun notEnabledWhenTheSecondIsUpDoesNothing() {
        enabled = false // the app is not resumed, or a request is being sent
        rule.onNodeWithTag("reader_root").performTouchInput { down(center) }
        advance(1_500)
        rule.onNodeWithTag("reader_root").performTouchInput { up() }
        advance(100)
        assertEquals(0, toggles)
    }

    @Test
    fun changingScreensDuringTheHoldCancels() {
        rule.onNodeWithTag("reader_root").performTouchInput { down(center) }
        advance(500)
        surface = ReaderSurface.CHAT
        advance(1_500)
        rule.onNodeWithTag("reader_root").performTouchInput { up() }
        advance(100)
        assertEquals(0, toggles)
    }

    @Test
    fun leavingTheScreenDuringTheHoldCancelsEvenIfBackInTime() {
        rule.onNodeWithTag("reader_root").performTouchInput { down(center) }
        advance(300)
        rule.mainClock.autoAdvance = true
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.mainClock.autoAdvance = false
        advance(1_500)
        rule.onNodeWithTag("reader_root").performTouchInput { up() }
        advance(100)
        assertEquals(0, toggles)
    }

    @Test
    fun theChatHoldsOverTextAndBlankSpaceAndStillScrolls() {
        surface = ReaderSurface.CHAT
        advance(500)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        val bubble = rule.onAllNodesWithTag("bubble_received").fetchSemanticsNodes().first().boundsInRoot.center
        rule.onNodeWithTag("reader_root").performTouchInput { down(bubble) }
        advance(1_000)
        rule.onNodeWithTag("reader_root").performTouchInput { up() }
        advance(200)
        assertEquals("over a message", 1, toggles)
        rule.onNodeWithTag("reader_root").performTouchInput { down(Offset(width / 2f, 4f)) }
        advance(1_000)
        rule.onNodeWithTag("reader_root").performTouchInput { up() }
        advance(200)
        assertEquals("over blank space", 2, toggles)
        framesRun()
        rule.onNodeWithTag("chat_list").performTouchInput { swipeUp(durationMillis = 300) }
        advance(2_000)
        assertEquals("a scroll in chat is not a hold", 2, toggles)
        rule.onNodeWithTag("talk_status").assertExists()
    }

    @Test
    fun accessibilityHasTheSameStartAndStopWithoutAButton() {
        rule.mainClock.autoAdvance = true
        val actions = rule.onNodeWithTag("reader_root").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Start recording"), actions.map { it.label })
        rule.runOnIdle { actions.first().action() }
        assertEquals(1, toggles)
        rule.runOnIdle { talk = WatchTalkState(WatchPhase.RECORDING, "turn-2", null, "Listening…") }
        rule.waitForIdle()
        val recording = rule.onNodeWithTag("reader_root").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Stop recording and send"), recording.map { it.label })
        assertTrue("no Talk button", rule.onAllNodesWithTag("talk").fetchSemanticsNodes().isEmpty())
        Log.i("HermesVoiceUiTest", "accessibility action ok")
    }

    @Test
    fun aTapOnAChipStillSelectsIt() {
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("session_list").performScrollToNode(hasTestTag("session_c5"))
        chip(5).performClick()
        rule.waitForIdle()
        assertEquals(listOf("s5"), selected.toList())
        assertEquals(0, toggles)
    }
}
