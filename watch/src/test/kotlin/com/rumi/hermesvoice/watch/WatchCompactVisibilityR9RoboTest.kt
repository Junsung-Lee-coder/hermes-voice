package com.rumi.hermesvoice.watch

import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.rumi.hermesvoice.core.net.HistoryPage
import com.rumi.hermesvoice.core.watchlink.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*

/** Production Watch surfaces in a 192dp viewport, real Compose semantics/clicks; synthetic/native only. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchCompactVisibilityR9RoboTest {
    private val rule = createComposeRule()
    private val host = org.junit.rules.TestRule { base, _ -> object : org.junit.runners.model.Statement() {
        override fun evaluate() {
            val app = org.robolectric.RuntimeEnvironment.getApplication()
            shadowOf(app.packageManager).addActivityIfNotPresent(android.content.ComponentName(app.packageName, androidx.activity.ComponentActivity::class.java.name))
            base.evaluate()
        }
    } }
    @get:Rule val chain: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(host).around(rule)
    @After fun drain() {
        runCatching { shadowOf(Looper.getMainLooper()).idle() }
        val companion = Class.forName("androidx.compose.ui.platform.AndroidUiDispatcher").getDeclaredField("Companion").also { it.isAccessible = true }.get(null)
        val context = companion.javaClass.getMethod("getMain").invoke(companion) as kotlin.coroutines.CoroutineContext
        val dispatcher = context[kotlin.coroutines.ContinuationInterceptor]!!
        for (name in listOf("scheduledTrampolineDispatch", "scheduledFrameDispatch")) {
            dispatcher.javaClass.getDeclaredField(name).also { it.isAccessible = true }.setBoolean(dispatcher, false)
        }
    }
    private fun texts(node: SemanticsNode): List<String> = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + node.children.flatMap(::texts)
    private val rows = listOf(ReaderSessionRow("exact-a","Short title","alias-a","SYSTEM PREVIEW",1.0),
        ReaderSessionRow("exact-b","Other title","alias-b","",2.0))
    private fun sessions(data: ReaderSessionsState, onSelect: (String) -> Unit = {}) = rule.setContent {
        androidx.wear.compose.material.MaterialTheme { Box(Modifier.size(192.dp)) {
            SessionsSurface(data,"exact-a",FocusRequester(),onSelect,{}, {})
        } }
    }
    @Test fun E01_normalSessionRowsExposeOnlyTitleNotPreviewOrAlias() {
        sessions(ReaderSessionsState(rows = rows,status = LoadStatus.READY))
        for (row in rows) {
            rule.onNodeWithTag("session_list").performScrollToNode(hasTestTag("session_${row.alias}"))
            val node = rule.onNodeWithTag("session_${row.alias}",useUnmergedTree = true).fetchSemanticsNode()
            assertEquals(listOf(row.title),texts(node))
        }
        assertEquals("metadata is not erased", "SYSTEM PREVIEW", rows[0].preview)
        assertEquals("alias-b",rows[1].alias)
    }
    @Test fun E02_watchChatDoesNotLayOutProcessCompletionAsUserBubble() {
        val page = HistoryPage.parse(JSONObject("""{"session_id":"s","messages":[
          {"id":1,"role":"user","content":"human twin"},
          {"id":2,"role":"user","content":"human twin","display_kind":"process_complete"},
          {"id":3,"role":"assistant","content":"answer"}]}"""))
        val response = ReaderResponse("r9-watch",ReaderKind.HISTORY,true,sessionId="s",messages=page.messages.map {
            ReaderMessageRow(it.rowId,it.role,it.text,false) },nextOffset=page.returned)
        val state = WatchReaderState().select("s","r9-watch").onResponse(ReaderResponse.decode(response.encode())!!)
        rule.setContent { androidx.wear.compose.material.MaterialTheme { Box(Modifier.size(192.dp)) {
            ChatSurface("Synthetic",state.selectedHistory!!,FocusRequester(),{},{},{},{})
        } } }
        rule.waitForIdle()
        assertEquals(listOf(1L,3L),state.selectedHistory!!.messages.map { it.rowId })
        assertEquals(1,rule.onAllNodesWithTag("bubble_sent").fetchSemanticsNodes().size)
        assertEquals(1,rule.onAllNodesWithTag("bubble_received").fetchSemanticsNodes().size)
        assertEquals(3,state.selectedHistory!!.nextOlderOffset)
    }
    @Test fun C01_fullRowClickSelectsExactIdAndCompactTouchTargetIsRetained() {
        val selected = mutableListOf<String>()
        sessions(ReaderSessionsState(rows=rows,status=LoadStatus.READY),selected::add)
        for (row in rows) {
            rule.onNodeWithTag("session_list").performScrollToNode(hasTestTag("session_${row.alias}"))
            val node=rule.onNodeWithTag("session_${row.alias}")
            val b=node.getBoundsInRoot()
            assertTrue("48dp minimum",b.height.value >= 48f)
            assertTrue("compact maximum",b.height.value <= 56f)
            assertTrue("full available width",b.width.value >= 168f)
            node.assertHasClickAction().performTouchInput { click() }
        }
        assertEquals(listOf("exact-a","exact-b"),selected)
    }
    @Test fun C02_longTitleIsOneEllipsizedLineButAccessibleInFull() {
        val title="아주 긴 대화 제목 Synthetic conversation title that cannot fit on a round watch"
        sessions(ReaderSessionsState(rows=listOf(rows[0].copy(title=title)),status=LoadStatus.READY))
        val node=rule.onNodeWithText(title,useUnmergedTree=true).fetchSemanticsNode()
        val layouts=mutableListOf<TextLayoutResult>()
        rule.runOnUiThread { node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts) }
        assertEquals(1,layouts.single().lineCount)
        assertTrue(layouts.single().isLineEllipsized(0))
        assertEquals(title,node.config[SemanticsProperties.Text].single().text)
    }
}
