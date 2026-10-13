package com.rumi.hermesvoice.watch

import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.rumi.hermesvoice.core.watchlink.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*

/** Actual ChatSurface in a 192dp viewport; not a physical round Watch/display/rotary test. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchReaderRenderR6RoboTest {
    private val rule = createComposeRule()
    private val host = org.junit.rules.TestRule { base, _ -> object : org.junit.runners.model.Statement() {
        override fun evaluate() {
            val app = org.robolectric.RuntimeEnvironment.getApplication()
            shadowOf(app.packageManager).addActivityIfNotPresent(android.content.ComponentName(app.packageName, androidx.activity.ComponentActivity::class.java.name))
            base.evaluate()
        }
    } }
    @get:Rule val chain: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(host).around(rule)
    private val shown = mutableStateOf(history())
    private fun history(id: String = "a", top: Long = 20) = ReaderHistory(id,
        (1L..top).map { ReaderMessageRow(it, if (it % 2 == 0L) "assistant" else "user", "$id message $it", false) },
        hasOlder = true, nextOlderOffset = 20, status = LoadStatus.READY)
    private fun show() = rule.setContent {
        androidx.wear.compose.material.MaterialTheme {
            Box(Modifier.size(192.dp)) { ChatSurface("Conversation", shown.value, FocusRequester(), {}, {}, {}, {}) }
        }
    }
    @After fun drain() {
        runCatching { shadowOf(Looper.getMainLooper()).idle() }
        val companion = Class.forName("androidx.compose.ui.platform.AndroidUiDispatcher").getDeclaredField("Companion").also { it.isAccessible = true }.get(null)
        val context = companion.javaClass.getMethod("getMain").invoke(companion) as kotlin.coroutines.CoroutineContext
        val dispatcher = context[kotlin.coroutines.ContinuationInterceptor]!!
        for (name in listOf("scheduledTrampolineDispatch", "scheduledFrameDispatch")) {
            dispatcher.javaClass.getDeclaredField(name).also { it.isAccessible = true }.setBoolean(dispatcher, false)
        }
    }
    @Test fun coldLatestPageAnchorsAnExistingBubbleNotLoadOlderOrEmptyFooter() {
        show(); rule.waitForIdle()
        rule.onNodeWithText("a message 20").assertIsDisplayed()
        rule.onNodeWithTag("chat_list").performScrollToIndex(0)
        rule.onNodeWithTag("load_older").assertIsDisplayed()
    }
    @Test fun finalGrowthAndNewArrivalFollowOnlyWhenAlreadyAtLatest() {
        show(); rule.waitForIdle()
        rule.runOnIdle { shown.value = history(top = 21) }
        rule.waitForIdle(); rule.onNodeWithText("a message 21").assertIsDisplayed()
        rule.runOnIdle { shown.value = shown.value.copy(messages = shown.value.messages.dropLast(1) + ReaderMessageRow(21, "assistant", "final answer", false)) }
        rule.waitForIdle(); rule.onNodeWithText("final answer").assertIsDisplayed()
    }
    @Test fun browsingOlderMessagesIsNotForceJumpedByLatestRefresh() {
        show(); rule.waitForIdle()
        rule.onNodeWithTag("chat_list").performScrollToIndex(1)
        rule.onNodeWithText("a message 1").assertIsDisplayed()
        rule.runOnIdle { shown.value = history(top = 21) }
        rule.waitForIdle(); rule.onNodeWithText("a message 1").assertIsDisplayed()
    }
    @Test fun switchingSessionsWithOverlappingRowIdsShowsTheCorrectLatestContent() {
        show(); rule.waitForIdle()
        rule.onNodeWithTag("chat_list").performScrollToIndex(0)
        rule.runOnIdle { shown.value = history(id = "b", top = 5) }
        rule.waitForIdle(); rule.onNodeWithText("b message 5").assertIsDisplayed()
    }
    @Test fun emptyLoadingAndErrorAreHonestStatesNotPaginationOnly() {
        shown.value = ReaderHistory("a", status = LoadStatus.LOADING)
        show(); rule.waitForIdle(); rule.onNodeWithText("Loading…").assertIsDisplayed()
        rule.runOnIdle { shown.value = shown.value.copy(status = LoadStatus.ERROR, error = ReaderError.TIMEOUT) }
        rule.waitForIdle(); rule.onNodeWithText("Phone did not answer").assertIsDisplayed()
        rule.runOnIdle { shown.value = shown.value.copy(status = LoadStatus.READY, error = null) }
        rule.waitForIdle(); rule.onNodeWithText("No messages yet").assertIsDisplayed()
    }
}
