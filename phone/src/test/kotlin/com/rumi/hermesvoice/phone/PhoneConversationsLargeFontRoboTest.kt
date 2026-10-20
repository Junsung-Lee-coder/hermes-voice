package com.rumi.hermesvoice.phone

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). The compact Conversations rows on a narrow phone at the normal and at a doubled font scale,
 * through the real MainActivity, PhoneViewModel and AppSessionRepository against the dashboard contract double. The window is tall
 * so that every row is composed; the width and the font scale are what the checks vary. No device and no screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h1280dp")
class PhoneConversationsLargeFontRoboTest : PhoneRoboBase() {
    val rule = createEmptyComposeRule()
    @get:Rule val cleanupThenCompose: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(ComposeDispatcherCleanup()).around(rule)

    @Before fun setUp() = baseSetUp()
    @After fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
        baseTearDown()
    }

    override fun pump() { idle(50); rule.waitForIdle() }

    private val shortTitle = "Short"
    private val longEnglish = "Synthetic very long conversation title that cannot fit on one line of a narrow phone"
    private val longKorean = "합성 테스트 대화 제목입니다 아주 길게 이어져서 좁은 화면 한 줄에 다 들어가지 않는 제목"

    private fun titled(title: String, alias: String): String {
        val owned = runBlocking { app.wiring().core.sessions.createConversation(title, alias, "$alias things") }
        fake.scripts[owned.storedSessionId] = { emptyList() }
        return owned.storedSessionId
    }

    private fun showing(vararg ids: String): PhoneViewModel {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle(300)
        settle()
        val model = ViewModelProvider(controller!!.get())[PhoneViewModel::class.java]
        assertTrue("listed", await(20_000) {
            model.state.value.conversations.map { it.stored.id }.toSet() == ids.toSet() &&
                ids.all { id -> rule.onAllNodesTagged("conversation_row_$id") }
        })
        return model
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesTagged(tag: String) =
        onAllNodes(SemanticsMatcher("tag == $tag") { it.config.getOrNull(SemanticsProperties.TestTag) == tag }).fetchSemanticsNodes().isNotEmpty()

    private fun row(id: String) = rule.onNodeWithTag("conversation_row_$id")

    private fun titleLayout(id: String): TextLayoutResult {
        val node = rule.onNodeWithTag("conversation_title_$id", useUnmergedTree = true).fetchSemanticsNode()
        val out = mutableListOf<TextLayoutResult>()
        rule.runOnUiThread { node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(out) }
        return out.single()
    }

    private fun checkRows(ids: List<String>, maxRowDp: Float) {
        val screen = rule.onRoot().getBoundsInRoot().width.value
        for (id in ids) {
            val rowBounds = row(id).getBoundsInRoot()
            val titleBounds = rule.onNodeWithTag("conversation_title_$id", useUnmergedTree = true).getBoundsInRoot()
            assertTrue("$id touch target ${rowBounds.height} >= 48dp", rowBounds.height.value >= 48f)
            assertTrue("$id row ${rowBounds.height} <= $maxRowDp dp", rowBounds.height.value <= maxRowDp)
            assertTrue("$id row inside the screen (${rowBounds.left}..${rowBounds.right} of $screen)", rowBounds.left.value >= 0f && rowBounds.right.value <= screen)
            assertTrue("$id title inside its row vertically", titleBounds.top.value >= rowBounds.top.value && titleBounds.bottom.value <= rowBounds.bottom.value)
            assertTrue("$id title inside its row horizontally", titleBounds.left.value >= rowBounds.left.value && titleBounds.right.value <= rowBounds.right.value)
            assertEquals("$id is one line", 1, titleLayout(id).lineCount)
        }
    }

    @Test fun L01_narrowScreenNormalFontKeepsEveryRowOneCompactEllipsizedLine() {
        val ids = listOf(titled(shortTitle, "short"), titled(longEnglish, "eng"), titled(longKorean, "kor"))
        showing(*ids.toTypedArray())
        checkRows(ids, 56f)
        assertTrue("the short title is not cut", !titleLayout(ids[0]).isLineEllipsized(0))
        assertTrue("the long English title is ellipsized", titleLayout(ids[1]).isLineEllipsized(0))
        assertTrue("the long Korean title is ellipsized", titleLayout(ids[2]).isLineEllipsized(0))
    }

    @Test fun L02_aDoubledFontScaleGrowsTheRowsWithoutClippingTheTitleOrTheTouchTarget() {
        RuntimeEnvironment.setFontScale(2.0f)
        val ids = listOf(titled(shortTitle, "short"), titled(longEnglish, "eng"), titled(longKorean, "kor"))
        showing(*ids.toTypedArray())
        checkRows(ids, 100f)
        assertTrue("the long English title is ellipsized", titleLayout(ids[1]).isLineEllipsized(0))
        assertTrue("the long Korean title is ellipsized", titleLayout(ids[2]).isLineEllipsized(0))
    }

    @Test fun L03_tapAndSwipeStillWorkOnANarrowScreenWithADoubledFontScale() {
        RuntimeEnvironment.setFontScale(2.0f)
        val alpha = titled(longEnglish, "alpha")
        val beta = titled(shortTitle, "beta")
        val model = showing(alpha, beta)
        row(beta).performClick()
        assertTrue("beta opens", await { model.state.value.selected?.storedSessionId == beta })
        rule.onNodeWithTag("back_button").performClick()
        assertTrue(await { rule.onAllNodesTagged("conversation_row_$alpha") })
        row(alpha).performTouchInput { swipeLeft() }
        assertTrue("alpha is archived", await(20_000) { fake.rows.getValue(alpha).archived })
        assertTrue("alpha left the list", await(20_000) { !rule.onAllNodesTagged("conversation_row_$alpha") })
        assertTrue("beta stays", rule.onAllNodesTagged("conversation_row_$beta") && !fake.rows.getValue(beta).archived)
    }
}
