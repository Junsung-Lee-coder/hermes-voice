package com.rumi.hermesvoice.phone

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rumi.hermesvoice.core.net.HistoryMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The production Phone chat ([ChatPane]) inside the production frame ([PhoneChrome] with the
 * real [TalkBar] and tabs), rendered in a plain test activity set up like MainActivity
 * (edge-to-edge, adjustResize). Bounds are measured on screen: the newest message must sit fully
 * above the composer, and the composer above the keyboard. No Hermes, audio or network.
 */
@RunWith(AndroidJUnit4::class)
class ChatPaneUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private class Fixture {
        var session by mutableStateOf("s1")
        var history by mutableStateOf((101L..140L).map { msg(it) })
        var hasOlder by mutableStateOf(true)
        var draft by mutableStateOf("")
        var sends = 0
    }

    private val f = Fixture()

    @Before
    fun show() {
        rule.runOnUiThread {
            rule.activity.enableEdgeToEdge()
            @Suppress("DEPRECATION")
            rule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        rule.setContent {
            HermesVoiceTheme(dark = true) {
                PhoneChrome(title = "Fixture", tab = Tab.CHAT, onTab = {}, statusLines = emptyList(),
                    talkBar = { TalkBar(PhoneUiState(signedIn = true)) {} }) {
                    ChatPane(
                        sessionKey = f.session, history = f.history, hasOlder = f.hasOlder, draft = f.draft,
                        attachments = emptyList(), sending = false,
                        onLoadOlder = {
                            val oldest = f.history.first().rowId
                            f.history = ((oldest - 20) until oldest).map { msg(it) } + f.history
                            f.hasOlder = false
                        },
                        onDraft = { f.draft = it },
                        onSend = {
                            f.sends += 1
                            val sent = f.draft
                            f.draft = ""
                            f.history = f.history + HistoryMessage(f.history.last().rowId + 1, "user", "sent: $sent", 0.0)
                        },
                        onAttach = {}, onRemoveAttachment = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    // ── measuring ────────────────────────────────────────────────────────────────────────────

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow

    private fun messageBounds(id: Long): Rect? =
        rule.onAllNodesWithTag("message_$id", useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInWindow

    /** Fully inside the list's viewport. */
    private fun fullyVisible(id: Long): Boolean {
        val b = messageBounds(id) ?: return false
        val list = bounds("chat_list")
        return b.top >= list.top - 1 && b.bottom <= list.bottom + 1
    }

    private fun partlyVisible(id: Long): Boolean {
        val b = messageBounds(id) ?: return false
        val list = bounds("chat_list")
        return b.bottom > list.top + 1 && b.top < list.bottom - 1
    }

    /** The newest message's bottom edge is the list's bottom edge, which is above the composer. */
    private fun assertNewestReadable(id: Long) {
        rule.waitForIdle()
        val b = checkNotNull(messageBounds(id)) { "newest message $id is not on screen" }
        val list = bounds("chat_list")
        val composer = bounds("composer_row")
        assertTrue("newest bottom ${b.bottom} at list bottom ${list.bottom}", kotlin.math.abs(b.bottom - list.bottom) <= 2f)
        assertTrue("list bottom ${list.bottom} above composer top ${composer.top}", list.bottom <= composer.top + 1f)
        assertTrue("newest message has height", b.height > 0f)
    }

    private fun newest() = f.history.last().rowId

    private fun scrollToOlder(times: Int = 2) {
        repeat(times) {
            rule.onNodeWithTag("chat_list").performTouchInput { swipeDown(startY = top + height * 0.2f, endY = bottom - height * 0.1f, durationMillis = 500) }
            rule.waitForIdle()
        }
    }

    /** A message fully on screen in the middle of the list, and where it is. */
    private fun anchor(): Pair<Long, Float> {
        val ids = f.history.map { it.rowId }.filter(::fullyVisible)
        val id = ids[ids.size / 2]
        return id to messageBounds(id)!!.top
    }

    /**
     * The composer must sit just above whichever is higher: the keyboard or the bottom bar (Talk
     * bar + tabs). Lifted by exactly what the bottom bar doesn't cover, never by both.
     */
    private fun assertComposerJustAbove(imeTop: Float, label: String) {
        val composer = bounds("composer_row")
        val limit = minOf(imeTop, bounds("bottom_bar").top)
        val density = rule.activity.resources.displayMetrics.density
        val gapDp = (limit - composer.bottom) / density
        Log.i(TAG, "$label composer_bottom=${composer.bottom} ime_top=$imeTop bottom_bar_top=${bounds("bottom_bar").top} gap_dp=$gapDp")
        assertTrue("$label: composer bottom ${composer.bottom} above $limit", composer.bottom <= limit + 1)
        assertTrue("$label: composer sits on it (gap $gapDp dp; the row's own padding is 8 dp)", gapDp <= 12f)
    }

    private fun composeView(): View = (rule.activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)

    // ── follow the newest message ────────────────────────────────────────────────────────────

    @Test
    fun opensAtTheNewestMessageAboveTheComposer() {
        assertNewestReadable(140)
        assertFalse("the oldest page is not what opens", partlyVisible(101))
    }

    @Test
    fun aReplyWhileAtTheNewestMessageIsFollowed() {
        rule.runOnIdle { f.history = f.history + msg(141) }
        assertNewestReadable(141)
        rule.runOnIdle { f.history = f.history + msg(142) + msg(143) }
        assertNewestReadable(143)
    }

    @Test
    fun aReplyWhileReadingOlderMessagesKeepsThePlace() {
        scrollToOlder()
        val (id, top) = anchor()
        rule.runOnIdle { f.history = f.history + msg(141) }
        rule.waitForIdle()
        assertEquals("the message being read stays where it was", top, messageBounds(id)!!.top, 1.5f)
        assertFalse(partlyVisible(141))
    }

    @Test
    fun sendingReturnsToTheNewestMessage() {
        scrollToOlder()
        rule.onNodeWithTag("composer").performTextInput("hello")
        rule.waitForIdle()
        rule.onNodeWithTag("send").performClick()
        assertEquals(1, f.sends)
        assertNewestReadable(newest())
        Log.i(TAG, "sent and followed to ${newest()}")
    }

    @Test
    fun aGrowingReplyKeepsItsNewestLineInView() {
        val id = newest()
        for (lines in listOf(3, 12, 40, 120)) {
            rule.runOnIdle {
                f.history = f.history.dropLast(1) + HistoryMessage(id, "assistant", (1..lines).joinToString("\n") { "line $it of the reply" }, 0.0)
            }
            assertNewestReadable(id)
        }
        // Semantics bounds are clipped to the list; the node's own size is not.
        val height = rule.onNodeWithTag("message_$id", useUnmergedTree = true).fetchSemanticsNode().size.height
        assertTrue("oversized now: $height px taller than the list", height > bounds("chat_list").height)
    }

    @Test
    fun loadingOlderMessagesKeepsThePlace() {
        // An accessibility-style scroll (not a drag) to the top: it, too, is the user reading older messages.
        rule.onNodeWithTag("chat_list").performScrollToKey("load_older")
        rule.waitForIdle()
        val (before, beforeTop) = anchor()
        rule.onNodeWithTag("load_older").performClick()
        rule.waitForIdle()
        assertEquals("loading older rows above keeps the message being read in place", beforeTop, messageBounds(before)!!.top, 1.5f)
        rule.runOnIdle { f.history = f.history + msg(141) }
        rule.waitForIdle()
        assertEquals("and a reply arriving meanwhile does not pull it down", beforeTop, messageBounds(before)!!.top, 1.5f)
        val oldest = f.history.first().rowId
        assertTrue("older rows were added", oldest < 101)
        val (id, top) = anchor()
        rule.runOnIdle { f.history = f.history.toList() } // a recomposition with the same rows changes nothing
        assertEquals(top, messageBounds(id)!!.top, 1.5f)
    }

    @Test
    fun anotherConversationOpensAtItsNewestMessage() {
        scrollToOlder()
        rule.runOnIdle {
            f.session = "s2"
            f.history = (500L..530L).map { msg(it) }
        }
        assertNewestReadable(530)
    }

    @Test
    fun anEmptyConversationStillHasItsComposer() {
        rule.runOnIdle {
            f.session = "empty"
            f.history = emptyList()
            f.hasOlder = false
        }
        rule.waitForIdle()
        assertTrue(bounds("composer_row").height > 0f)
        rule.runOnIdle { f.history = listOf(msg(1)) }
        assertNewestReadable(1)
    }

    // ── keyboard ─────────────────────────────────────────────────────────────────────────────

    private fun imeBottom(): Int =
        ViewCompat.getRootWindowInsets(rule.activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0

    private fun waitForIme(shown: Boolean, timeoutMs: Long = 6_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            rule.waitForIdle()
            if ((imeBottom() > 0) == shown) return true
            Thread.sleep(100)
        }
        return false
    }

    /** REAL soft keyboard of the emulator; skipped (not passed) if the emulator shows none. */
    @Test
    fun realKeyboardBringsTheNewestMessageAboveComposerAndKeyboard() {
        scrollToOlder()
        rule.onNodeWithTag("composer").performClick()
        val shown = waitForIme(true)
        Log.i(TAG, "real keyboard shown=$shown ime_bottom=${imeBottom()}")
        assumeTrue("this emulator shows no soft keyboard", shown)
        Thread.sleep(600) // the keyboard's own animation
        assertNewestReadable(140)
        val decor = rule.activity.window.decorView
        val windowHeight = decor.height
        val imeTop = windowHeight - imeBottom()
        val root = checkNotNull(ViewCompat.getRootWindowInsets(decor))
        val onScreen = IntArray(2).also { composeView().getLocationOnScreen(it) }
        Log.i(TAG, "keyboard layout decor_h=$windowHeight compose_h=${composeView().height} compose_y=${onScreen[1]} " +
            "screen_h=${rule.activity.resources.displayMetrics.heightPixels} ime_bottom=${imeBottom()} " +
            "nav_bottom=${root.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom} " +
            "composer=${bounds("composer_row").top}..${bounds("composer_row").bottom} list=${bounds("chat_list").top}..${bounds("chat_list").bottom} " +
            "tabs=${bounds("tab_chat").top}..${bounds("tab_chat").bottom} density=${rule.activity.resources.displayMetrics.density}")
        // A compact IME (e.g. the toolbar shown with a hardware keyboard) is lower than the bottom bar: nothing to lift.
        Log.i(TAG, "real keyboard height=${imeBottom()} px; full keyboard above the bottom bar=${imeTop < bounds("bottom_bar").top}")
        assertComposerJustAbove(imeTop.toFloat(), "real keyboard")
        rule.onNodeWithTag("composer").performTextInput("draft kept")
        rule.runOnUiThread {
            WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        assertTrue(waitForIme(false))
        Thread.sleep(600)
        assertEquals("closing the keyboard keeps the draft", "draft kept", f.draft)
        assertNewestReadable(140)
        Log.i(TAG, "real keyboard checks passed ime_top=$imeTop")
    }

    /**
     * SIMULATED keyboard: keyboard insets dispatched to the Compose view through the platform's own
     * insets path (what the IME does), for emulators without a soft keyboard. Same assertions.
     */
    @Test
    fun keyboardInsetsLiftTheComposerOnceAndKeepTheNewestMessage() {
        assumeTrue("a real keyboard is up; the simulated one would fight it", imeBottom() == 0)
        val view = composeView()
        val base = checkNotNull(ViewCompat.getRootWindowInsets(view))
        val windowHeight = rule.activity.window.decorView.height
        for (imeHeight in listOf((windowHeight * 0.25f).toInt(), (windowHeight * 0.42f).toInt())) {
            val withIme = WindowInsetsCompat.Builder(base)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeHeight))
                .setVisible(WindowInsetsCompat.Type.ime(), true).build()
            rule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(view, withIme) }
            rule.waitForIdle()
            val imeTop = windowHeight - imeHeight
            assertTrue("this simulated keyboard is taller than the bottom bar", imeTop < bounds("bottom_bar").top)
            assertComposerJustAbove(imeTop.toFloat(), "simulated keyboard $imeHeight px")
            assertNewestReadable(140)
        }
        rule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(view, base) }
        rule.waitForIdle()
        assertNewestReadable(140)
    }

    @Test
    fun focusingTheComposerWhileReadingOlderReturnsToTheNewestMessage() {
        scrollToOlder()
        assertFalse(fullyVisible(140))
        rule.onNodeWithTag("composer").performClick()
        rule.waitForIdle()
        assertTrue(fullyVisible(140))
        assertNewestReadable(140)
    }

    private companion object {
        const val TAG = "HermesVoiceUiTest"

        fun msg(id: Long) = HistoryMessage(id, if (id % 2 == 0L) "user" else "assistant",
            "Message $id " + "words ".repeat((id % 7).toInt() * 6), id.toDouble())
    }
}
