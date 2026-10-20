package com.rumi.hermesvoice.phone

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). A rightward swipe that starts at the left edge of the screen returns to Conversations; the
 * activation region is the first 40dp measured from the physical screen edge (the pane's 16dp side padding does not move it).
 * Other drags (short, leftward, cancelled, started elsewhere, mostly vertical, held, on the composer or Talk, inside a scrolled
 * code block) leave the chat as it is. Real MainActivity and Compose touch gestures; no device.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1280dp")
class PhoneChatSwipeBackRoboTest : PhoneNavRoboBase() {

    private fun onList() = exists("settings_button") && !exists("chat_list")

    private fun stayed(model: PhoneViewModel, stored: String, what: String) {
        pump(); idle(300); settle()
        assertTrue("$what: still in the chat", exists("chat_list") && exists("composer"))
        assertFalse("$what: not on the list", exists("settings_button"))
        assertEquals("$what: same conversation", stored, model.state.value.selected?.storedSessionId)
    }

    private fun prepared(rows: Int = 6): Triple<PhoneViewModel, String, Unit> {
        val alpha = titled("Synthetic alpha", "alpha")
        seed(alpha, rows, "swipe")
        val model = showWith(alpha)
        openCard(model, alpha)
        waitFor("history is shown") { model.state.value.history.isNotEmpty() && exists("message_${model.state.value.history.last().rowId}") }
        return Triple(model, alpha, Unit)
    }

    @Test fun S01_aRightwardSwipeFromTheLeftEdgeReturnsToConversationsAndKeepsTheSession() {
        val (model, alpha, _) = prepared()
        rule.onNodeWithTag("composer").performTextInput("a half-written reply")
        pump()
        val history = model.state.value.history.map { it.rowId }
        drag("chat_list", startX = 12f, dx = 160f)
        waitFor("the list is shown") { onList() }
        assertEquals("the selection is kept", alpha, model.state.value.selected?.storedSessionId)
        assertEquals("the history is kept", history, model.state.value.history.map { it.rowId })
        assertEquals("the draft is kept", "a half-written reply", model.state.value.draft)
        assertFalse("not finished", controller!!.get().isFinishing)
        assertTrue("no cancel or destination change was made", fake.rpcLog.toList().none { it.toString().contains("interrupt") || it.toString().contains("session.close") })
    }

    @Test fun S02_aShortSwipeStays() {
        val (model, alpha, _) = prepared()
        drag("chat_list", startX = 12f, dx = 40f)
        stayed(model, alpha, "short swipe")
    }

    @Test fun S03_aLeftwardSwipeStays() {
        val (model, alpha, _) = prepared()
        drag("chat_list", startX = 30f, dx = -20f)
        stayed(model, alpha, "leftward swipe")
        drag("chat_list", startX = 200f, dx = -160f)
        stayed(model, alpha, "leftward swipe from the middle")
    }

    @Test fun S04_aCancelledGestureStays() {
        val (model, alpha, _) = prepared()
        drag("chat_list", startX = 12f, dx = 160f, cancel = true)
        stayed(model, alpha, "cancelled swipe")
    }

    @Test fun S05_aSwipeThatStartsInTheMiddleOrOnTheRightStays() {
        val (model, alpha, _) = prepared()
        drag("chat_list", startX = 150f, dx = 160f)
        stayed(model, alpha, "swipe from the middle")
        drag("chat_list", startX = 300f, dx = 80f)
        stayed(model, alpha, "swipe from the right")
    }

    @Test fun S06_aMostlyVerticalDragFromTheEdgeScrollsAndStays() {
        val (model, alpha, _) = prepared(120)
        waitFor("a full page is loaded") { model.state.value.history.size == 50 }
        drag("chat_list", startX = 12f, dx = 30f, dy = 600f)
        stayed(model, alpha, "vertical drag from the edge")
        var older = false
        repeat(60) {
            if (!older) {
                drag("chat_list", startX = 12f, dx = 8f, dy = 700f)
                pump()
                older = olderOffsets(alpha).isNotEmpty()
            }
        }
        stayed(model, alpha, "many vertical drags from the edge")
        assertTrue("scrolling from the edge reached the top and asked for older history", olderOffsets(alpha).isNotEmpty())
    }

    @Test fun S07_aPressHeldBeforeDraggingIsNotANavigationSwipe() {
        val (model, alpha, _) = prepared()
        drag("chat_list", startX = 12f, dx = 160f, holdMs = 800)
        stayed(model, alpha, "held press then drag")
    }

    @Test fun S08_aTapAtTheEdgeStays() {
        val (model, alpha, _) = prepared()
        rule.onNodeWithTag("chat_list").performTouchInput { click(Offset(12.dp.toPx(), height / 2f)) }
        stayed(model, alpha, "tap at the edge")
    }

    @Test fun S09_aSwipeOnTheComposerTheAttachButtonOrTalkStays() {
        val (model, alpha, _) = prepared()
        drag("composer", startX = 12f, dx = 160f)
        stayed(model, alpha, "swipe on the composer")
        drag("attach", startX = 4f, dx = 160f)
        stayed(model, alpha, "swipe on the attach button")
        drag("talk", startX = 12f, dx = 160f)
        stayed(model, alpha, "swipe on Talk")
        assertFalse("Talk did not start recording", model.state.value.recording)
    }

    @Test fun S10_aHorizontalTableScrollIsNotStolenByTheNavigationSwipe() {
        val alpha = titled("Synthetic alpha", "alpha")
        fake.injectRow(alpha, "user", "plain")
        fake.injectRow(alpha, "assistant", "| a | b |\n|---|---|\n| " + "x".repeat(120) + " | y |")
        val model = showWith(alpha)
        openCard(model, alpha)
        waitFor("both messages") { model.state.value.history.size == 2 && exists("message_2") }
        val table = rule.onNode(SemanticsMatcher("a sideways-scrolling table") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.endsWith("_scroll") == true
        }, useUnmergedTree = true)
        table.performTouchInput { swipeLeft() }
        pump()
        stayed(model, alpha, "table scrolled left")
        table.performTouchInput {
            val y = height / 2f
            down(Offset(4.dp.toPx(), y))
            for (i in 1..10) moveTo(Offset(4.dp.toPx() + 160.dp.toPx() * i / 10, y))
            up()
        }
        stayed(model, alpha, "a swipe the scrolled table takes")
        drag("message_2", startX = 150f, dx = 160f)
        stayed(model, alpha, "a swipe from the middle of the message")
        assertNotNull(model.state.value.selected)
    }

    /** A swipe in screen coordinates: starts [startX] dp from the physical left edge, at height [yDp] (default: the middle of the chat list). */
    private fun screenSwipe(startX: Float, dx: Float, dy: Float = 0f, yDp: Float? = null, beforeUp: () -> Unit = {}) {
        val list = bounds("chat_list")
        val y0 = yDp ?: ((list.top.value + list.bottom.value) / 2f)
        rule.onRoot().performTouchInput {
            down(Offset(startX.dp.toPx(), y0.dp.toPx()))
            for (i in 1..10) moveTo(Offset((startX + dx * i / 10f).dp.toPx(), (y0 + dy * i / 10f).dp.toPx()))
            beforeUp()
            up()
        }
    }

    @Test fun S11_aSwipeThatStartsInTheOuterSixteenDpOfTheScreenGoesBack() {
        val (model, alpha, _) = prepared()
        for (x in listOf(2f, 8f, 15f)) {
            assertTrue("in the chat before x=$x", exists("chat_list"))
            screenSwipe(x, 160f)
            waitFor("a swipe from x=$x dp goes back") { onList() }
            assertEquals("the selection is kept", alpha, model.state.value.selected?.storedSessionId)
            openCard(model, alpha)
        }
    }

    @Test fun S12_aSwipeThatStartsBetweenSixteenAndFortyDpFromTheScreenEdgeGoesBack() {
        val (model, alpha, _) = prepared()
        for (x in listOf(20f, 39f)) {
            assertTrue("in the chat before x=$x", exists("chat_list"))
            screenSwipe(x, 160f)
            waitFor("a swipe from x=$x dp goes back") { onList() }
            openCard(model, alpha)
        }
    }

    @Test fun S13_aSwipeThatStartsBeyondFortyDpFromTheScreenEdgeStays() {
        val (model, alpha, _) = prepared()
        for (x in listOf(44f, 52f, 56f)) {
            screenSwipe(x, 160f)
            stayed(model, alpha, "swipe from x=$x dp")
        }
    }

    @Test fun S14_theDistanceDirectionAndReleaseGuardsApplyAtTheScreenEdge() {
        val (model, alpha, _) = prepared()
        screenSwipe(8f, 70f)
        stayed(model, alpha, "70dp is short of the 72dp commit")
        screenSwipe(8f, 100f, dy = 60f)
        stayed(model, alpha, "mostly vertical")
        screenSwipe(8f, 160f, beforeUp = { assertTrue("nothing happens before the finger is lifted", exists("chat_list") && !onList()) })
        waitFor("lifting the finger commits it") { onList() }
        openCard(model, alpha)
        screenSwipe(8f, 80f)
        waitFor("80dp commits") { onList() }
    }

    @Test fun S15_aSwipeAlongTheComposerRowOrTheTalkBarStaysEvenAtTheScreenEdge() {
        val (model, alpha, _) = prepared()
        val composer = bounds("composer_row")
        screenSwipe(8f, 160f, yDp = (composer.top.value + composer.bottom.value) / 2f)
        stayed(model, alpha, "swipe along the composer row")
        val talk = bounds("talk")
        screenSwipe(8f, 160f, yDp = (talk.top.value + talk.bottom.value) / 2f)
        stayed(model, alpha, "swipe along the Talk bar")
        assertFalse("Talk did not start recording", model.state.value.recording)
    }
}
