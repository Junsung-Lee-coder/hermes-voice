package com.rumi.hermesvoice.watch

import android.os.Looper
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.rumi.hermesvoice.core.watchlink.ReaderSessionsState
import com.rumi.hermesvoice.core.watchlink.LoadStatus
import com.rumi.hermesvoice.core.watchlink.ReaderSessionRow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). The production Conversations list on the Watch. Since the owner's UI contract of 2026-10-27 the
 * Watch has only its conversation list and the history: the "clear this watch's log" chip is gone from the list, and clearing the log
 * remains an app function (no control on the watch screen). No watch screen, finger or rotary input.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchClearLogChipRoboTest {
    private val rule = createComposeRule()

    private val registerHostActivity = org.junit.rules.TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                val app = org.robolectric.RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(android.content.ComponentName(app.packageName, androidx.activity.ComponentActivity::class.java.name))
                base.evaluate()
            }
        }
    }

    @get:Rule val chain: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(registerHostActivity).around(rule)

    @After fun drain() {
        runCatching { shadowOf(Looper.getMainLooper()).idle() }
        val companion = Class.forName("androidx.compose.ui.platform.AndroidUiDispatcher").getDeclaredField("Companion").also { it.isAccessible = true }.get(null)
        val context = companion.javaClass.getMethod("getMain").invoke(companion) as kotlin.coroutines.CoroutineContext
        val dispatcher = context[kotlin.coroutines.ContinuationInterceptor]!!
        for (name in listOf("scheduledTrampolineDispatch", "scheduledFrameDispatch")) {
            dispatcher.javaClass.getDeclaredField(name).also { it.isAccessible = true }.setBoolean(dispatcher, false)
        }
    }

    private fun show(sessions: ReaderSessionsState = ReaderSessionsState()) = rule.setContent {
        androidx.wear.compose.material.MaterialTheme {
            SessionsSurface(sessions, null, FocusRequester(), {}, {}, {})
        }
    }

    private fun noChip() {
        rule.onNodeWithTag("session_list").assertExists()
        rule.onAllNodesWithTagCount("clear_watch_diagnostics", 0)
        assertEquals(0, rule.onAllNodesWithText("Clear this watch's log", substring = true).fetchSemanticsNodes().size)
    }

    @Test fun theListOffersNoLogChipAndTheAppStillClearsTheLog() {
        show()
        noChip()
        val app = org.robolectric.RuntimeEnvironment.getApplication() as WatchApp
        assertTrue("clearing this watch's log is still the app's own function", runBlocking { app.clearLocalLog() })
    }

    @Test fun aListWithConversationsOffersNoLogChipEither() {
        show(ReaderSessionsState(rows = listOf(ReaderSessionRow("qa-fixture-1", "QA fixture 1", "qa1", "", 0.0)), status = LoadStatus.READY))
        rule.onNodeWithTag("session_qa1").assertExists()
        noChip()
    }

    @Test fun withoutAClearActionThereIsNoChip() {
        show()
        rule.onNodeWithTag("session_list").assertExists()
        rule.onAllNodesWithTagCount("clear_watch_diagnostics", 0)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String, expected: Int) {
        assertEquals(expected, onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size)
    }
}
