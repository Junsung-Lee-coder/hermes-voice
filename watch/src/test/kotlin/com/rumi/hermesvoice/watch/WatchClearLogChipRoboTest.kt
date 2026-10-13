package com.rumi.hermesvoice.watch

import android.os.Looper
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.rumi.hermesvoice.core.watchlink.ReaderSessionsState
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). The production Conversations list on the Watch with its "clear this watch's log" chip:
 * the first tap only asks for confirmation, the second runs the supplied clear exactly once and says the result.
 * No watch screen, finger or rotary input.
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

    private fun show(clear: (suspend () -> Boolean)?) = rule.setContent {
        androidx.wear.compose.material.MaterialTheme {
            SessionsSurface(ReaderSessionsState(), null, FocusRequester(), {}, {}, {}, onClearLog = clear)
        }
    }

    @Test fun theFirstTapAsksAndOnlyTheSecondTapClears() {
        val calls = AtomicInteger()
        show { calls.incrementAndGet(); true }
        rule.onNodeWithTag("clear_watch_diagnostics").assertTextContains("Clear this watch's log")
        rule.onNodeWithTag("clear_watch_diagnostics").performClick()
        rule.waitForIdle()
        assertEquals(0, calls.get())
        rule.onNodeWithTag("clear_watch_diagnostics").assertTextContains("Tap again to clear")
        rule.onNodeWithTag("clear_watch_diagnostics").performClick()
        rule.waitUntil(5_000) { calls.get() == 1 }
        rule.waitForIdle()
        rule.onNodeWithTag("clear_watch_diagnostics").assertTextContains("This watch's log was cleared")
        assertEquals(1, calls.get())
    }

    @Test fun aFailedClearIsSaidSo() {
        show { false }
        rule.onNodeWithTag("clear_watch_diagnostics").performClick()
        rule.onNodeWithTag("clear_watch_diagnostics").performClick()
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("clear_watch_diagnostics").assertTextContains("Couldn't clear this watch's log") }.isSuccess }
    }

    @Test fun withoutAClearActionThereIsNoChip() {
        show(null)
        rule.onNodeWithTag("session_list").assertExists()
        rule.onAllNodesWithTagCount("clear_watch_diagnostics", 0)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String, expected: Int) {
        assertEquals(expected, onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size)
    }
}
