package com.rumi.hermesvoice.phone

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.lifecycle.ViewModelProvider
import com.rumi.hermesvoice.core.sessions.AppSources
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). The compact, title-only Conversations list: tap opens, a committed left swipe archives, through the real
 * MainActivity, PhoneViewModel, PhoneApp wiring and AppSessionRepository against the Hermes dashboard contract double. Simulated: the Data Layer
 * (mockk), the keystore and touch input (Compose test gestures); no device, no TalkBack, no real network. All titles and replies are synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class PhoneConversationsCompactRoboTest : PhoneRoboBase() {
    val rule = createEmptyComposeRule()
    @get:Rule val cleanupThenCompose: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(ComposeDispatcherCleanup()).around(rule)

    @Before fun setUp() = baseSetUp()
    @After fun tearDown() {
        fake.archiveHook = null
        fake.failArchiveStatus = 0
        fake.listPageHook = null
        baseTearDown()
    }

    override fun pump() { idle(50); rule.waitForIdle() }

    private fun showing(): PhoneViewModel {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle(300)
        settle()
        return ViewModelProvider(controller!!.get())[PhoneViewModel::class.java]
    }

    private fun titled(title: String, alias: String, description: String = "$alias things"): String {
        val owned = runBlocking { app.wiring().core.sessions.createConversation(title, alias, description) }
        fake.scripts[owned.storedSessionId] = { emptyList() }
        return owned.storedSessionId
    }

    private fun marked(id: String, title: String = "Topic $id") {
        fake.addForeignRow(id, "discord", title = title)
        fake.setCreatedSource(id, AppSources.CONVERSATION)
    }

    private fun shown(model: PhoneViewModel) = model.state.value.conversations.map { it.stored.id }

    private fun row(id: String) = rule.onNodeWithTag("conversation_row_$id")

    private fun rowPresent(id: String) = rule.onAllNodes(tagIs("conversation_row_$id")).fetchSemanticsNodes().isNotEmpty()

    private fun tagIs(tag: String) = SemanticsMatcher("tag == $tag") { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun patches(id: String) = fake.restLog.toList().count { it.startsWith("PATCH /api/sessions/$id?") }

    private fun anyPatch() = fake.restLog.toList().any { it.startsWith("PATCH ") }

    private fun textsUnder(node: SemanticsNode, out: MutableList<String> = mutableListOf()): List<String> {
        node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { out += it }
        node.children.forEach { textsUnder(it, out) }
        return out
    }

    private fun unmergedTexts(id: String): List<String> =
        textsUnder(rule.onNodeWithTag("conversation_row_$id", useUnmergedTree = true).fetchSemanticsNode()).distinct()

    private fun titleLayout(id: String): TextLayoutResult {
        val node = rule.onNodeWithTag("conversation_title_$id", useUnmergedTree = true).fetchSemanticsNode()
        val out = mutableListOf<TextLayoutResult>()
        rule.runOnUiThread { node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(out) }
        return out.single()
    }

    private fun horizontalExtent(id: String) = row(id).getBoundsInRoot().let { it.left to it.right }

    private fun customAction(id: String, label: String): (() -> Boolean)? {
        val actions = row(id).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        val match = actions.firstOrNull { it.label == label } ?: return null
        return { var done = false; rule.runOnUiThread { done = match.action() }; done }
    }

    private fun swipeLeftBy(id: String, fraction: Float) = row(id).performTouchInput {
        swipeLeft(startX = width * 0.95f, endX = width * (0.95f - fraction), durationMillis = 300)
    }

    private fun swipeRightBy(id: String, fraction: Float) = row(id).performTouchInput {
        swipeRight(startX = width * 0.05f, endX = width * (0.05f + fraction), durationMillis = 300)
    }

    private fun latchPatch(): Pair<CountDownLatch, CountDownLatch> {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.archiveHook = { _, _ ->
            reached.countDown()
            release.await(60, TimeUnit.SECONDS)
        }
        return reached to release
    }

    private fun assertOnConversationsTab() {
        rule.onNodeWithTag("settings_button").assertHasClickAction()
        assertNull("nothing was opened", showingModel.state.value.selected)
    }

    private lateinit var showingModel: PhoneViewModel

    private fun showWith(vararg ids: String): PhoneViewModel {
        val model = showing()
        showingModel = model
        assertTrue("listed ${ids.toList()}", await(20_000) { shown(model).toSet() == ids.toSet() && ids.all { rowPresent(it) } })
        return model
    }

    @Test fun C01_aCardShowsOnlyItsTitleAndNothingElse() {
        val plan = titled("Synthetic plan", "plan", "SYN-DESC routing words")
        fake.injectRow(plan, "assistant", "SYN-PREVIEW last reply text")
        val notes = titled("Synthetic notes", "notes", "SYN-DESC2 other words")
        val model = showWith(plan, notes)
        assertEquals(listOf("Synthetic plan"), unmergedTexts(plan))
        assertEquals(listOf("Synthetic notes"), unmergedTexts(notes))
        val allTexts = rule.onAllNodes(SemanticsMatcher("any text") { true }, useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        for (exact in listOf("Open", "Archive", "Unarchive")) assertFalse("no '$exact' button is shown", exact in allTexts)
        for (part in listOf("SYN-DESC", "SYN-PREVIEW")) assertTrue("'$part' is not shown", allTexts.none { it.contains(part) })
        assertTrue("no 'Voice alias:' row line", allTexts.none { it.startsWith("Voice alias:") })
        val conversation = model.state.value.conversations.first { it.stored.id == plan }
        assertEquals("the alias is still kept for voice routing", "plan", conversation.owned.alias)
        assertEquals("the description is still kept for voice routing", "SYN-DESC routing words", conversation.owned.description)
        assertTrue("the preview is still read", conversation.stored.preview.contains("SYN-PREVIEW"))
        assertTrue("the conversation is still a voice destination", app.wiring().core.sessions.allowlist(null).entries.any { it.alias == "plan" })
    }

    @Test fun C02_tappingACardOpensExactlyThatStoredSession() {
        val alpha = titled("Synthetic alpha", "alpha")
        val beta = titled("Synthetic beta", "beta")
        val model = showWith(alpha, beta)
        val rowsBefore = fake.rows.size
        row(beta).assertHasClickAction()
        row(beta).performClick()
        assertTrue("beta is opened", await { model.state.value.selected?.storedSessionId == beta })
        rule.onNodeWithTag("back_button").assertHasClickAction()
        assertEquals("no session was created or copied", rowsBefore, fake.rows.size)
        assertFalse("opening never archives", anyPatch())
    }

    @Test fun C03_aTouchTapOnACardOpensItToo() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = showWith(alpha)
        row(alpha).performTouchInput { click() }
        assertTrue("alpha is opened by a real tap", await { model.state.value.selected?.storedSessionId == alpha })
        assertFalse(anyPatch())
    }

    @Test fun C04_aLongKoreanAndEnglishTitleStaysOnOneEllipsizedLineWithItsFullTextForAccessibility() {
        val korean = "합성 테스트 대화 제목입니다 아주 길게 이어지는 제목으로 한 줄에 다 들어가지 않아 말줄임표로 끝나야 하는 경우입니다"
            .take(80)
        val english = "Synthetic very long conversation title that cannot fit on a single line of a narrow phone screen at all"
            .take(80)
        val kr = titled(korean, "kor")
        val en = titled(english, "eng")
        showWith(kr, en)
        for ((id, full) in listOf(kr to korean, en to english)) {
            val layout = titleLayout(id)
            assertEquals("one line", 1, layout.lineCount)
            assertTrue("ellipsized", layout.isLineEllipsized(0))
            rule.onNodeWithText(full).assertExists()
            assertTrue(unmergedTexts(id).toString(), unmergedTexts(id) == listOf(full))
        }
    }

    @Test fun C05_aShortTitleRowIsCompactButStillATouchTarget() {
        val ids = (1..3).map { titled("Short $it", "short$it") }
        showWith(*ids.toTypedArray())
        val bounds = ids.map { row(it).getBoundsInRoot() }
        for (b in bounds) {
            assertTrue("height ${b.height} >= 48dp", b.height.value >= 48f)
            assertTrue("height ${b.height} <= 56dp", b.height.value <= 56f)
            assertTrue("full width ${b.width}", b.width.value >= 370f)
        }
        val ordered = bounds.sortedBy { it.top.value }
        for (i in 1 until ordered.size) {
            val gap = ordered[i].top.value - ordered[i - 1].bottom.value
            assertTrue("gap $gap <= 8dp", gap in 0f..8f)
        }
    }

    @Test fun C06_aCommittedLeftSwipeArchivesThroughTheRealArchiveCall() {
        val alpha = titled("Synthetic alpha", "alpha")
        val beta = titled("Synthetic beta", "beta")
        val model = showWith(alpha, beta)
        row(alpha).performTouchInput { swipeLeft() }
        assertTrue("the archive request reached the dashboard", await { patches(alpha) == 1 })
        assertTrue("alpha is archived on the dashboard", await { fake.rows.getValue(alpha).archived })
        assertTrue("alpha left the active list", await(20_000) { alpha !in shown(model) && !rowPresent(alpha) })
        assertTrue("beta is untouched", rowPresent(beta) && !fake.rows.getValue(beta).archived && patches(beta) == 0)
        assertNull("alpha is no longer an active destination", app.wiring().core.sessions.activeConversation(alpha))
        assertNull("nothing was opened", model.state.value.selected)
        model.setShowArchived(true)
        assertTrue("alpha is listed as archived, not deleted", await(20_000) { shown(model) == listOf(alpha) && rowPresent(alpha) })
    }

    @Test fun C07_aPartialOrCancelledOrOppositeOrVerticalGestureNeverOpensOrArchives() {
        val ids = (1..4).map { titled("Synthetic $it", "g$it") }
        val model = showWith(*ids.toTypedArray())
        val target = ids[0]
        val before = horizontalExtent(target)
        swipeLeftBy(target, 0.10f)
        rule.waitForIdle()
        row(target).performTouchInput {
            down(Offset(width * 0.95f, centerY))
            moveTo(Offset(width * 0.70f, centerY))
            moveTo(Offset(width * 0.25f, centerY))
            cancel()
        }
        swipeRightBy(target, 0.8f)
        row(target).performTouchInput { swipeUp() }
        row(target).performTouchInput { swipeDown() }
        settle()
        Thread.sleep(300)
        settle()
        assertFalse("no archive request was made", anyPatch())
        assertOnConversationsTab()
        assertEquals("the list is unchanged", ids.toSet(), shown(model).toSet())
        assertEquals("the row is back at rest", before, horizontalExtent(target))
    }

    @Test fun C08_aFailedArchiveKeepsTheRowResetsTheGestureAndSaysSoTruthfully() {
        val alpha = titled("Synthetic alpha", "alpha")
        val beta = titled("Synthetic beta", "beta")
        val model = showWith(alpha, beta)
        val before = horizontalExtent(alpha)
        fake.failArchiveStatus = 503
        row(alpha).performTouchInput { swipeLeft() }
        assertTrue("the request was made", await { patches(alpha) == 1 })
        assertTrue("the failure is reported", await(20_000) { model.state.value.status.startsWith("Couldn't") })
        assertTrue("alpha stays listed", alpha in shown(model) && rowPresent(alpha))
        assertFalse("and is not archived on the dashboard", fake.rows.getValue(alpha).archived)
        assertTrue("the gesture was reset", await { horizontalExtent(alpha) == before })
        assertNull(model.state.value.selected)
        fake.failArchiveStatus = 0
        row(alpha).performTouchInput { swipeLeft() }
        assertTrue("a retry archives it", await(20_000) { fake.rows.getValue(alpha).archived && alpha !in shown(model) })
        assertEquals("one failed and one successful request", 2, patches(alpha))
    }

    @Test fun C09_aSecondArchiveRequestForARowAlreadyBeingArchivedIsNotSent() {
        val alpha = titled("Synthetic alpha", "alpha")
        val beta = titled("Synthetic beta", "beta")
        val model = showWith(alpha, beta)
        val owned = model.state.value.conversations.first { it.stored.id == alpha }.owned
        val (reached, release) = latchPatch()
        try {
            row(alpha).performTouchInput { swipeLeft() }
            assertTrue("the first request is in flight", await(30_000) { reached.count == 0L })
            model.setArchived(owned, true)
            customAction(alpha, "Archive")?.invoke()
            row(alpha).performTouchInput { swipeLeft() }
            settle()
            row(beta).performTouchInput { swipeLeft(startX = width * 0.95f, endX = width * 0.9f, durationMillis = 200) }
            settle()
            assertEquals("still exactly one request for alpha", 1, patches(alpha))
        } finally {
            release.countDown()
        }
        assertTrue("alpha is archived once", await(20_000) { fake.rows.getValue(alpha).archived && alpha !in shown(model) })
        assertEquals(1, patches(alpha))
        assertEquals(0, patches(beta))
    }

    @Test fun C10_archivedConversationsAreRestoredByARightSwipeOrTheUnarchiveActionAndNeverOpenedByATap() {
        val alpha = titled("Synthetic alpha", "alpha")
        val beta = titled("Synthetic beta", "beta")
        runBlocking { app.wiring().core.sessions.setArchived(alpha, true); app.wiring().core.sessions.setArchived(beta, true) }
        val model = showing()
        showingModel = model
        model.setShowArchived(true)
        assertTrue("both are listed as archived", await(20_000) { shown(model).toSet() == setOf(alpha, beta) && rowPresent(alpha) && rowPresent(beta) })
        assertEquals(listOf("Synthetic alpha"), unmergedTexts(alpha))
        val setupAlpha = patches(alpha)
        val setupBeta = patches(beta)
        val setupAll = fake.restLog.toList().count { it.startsWith("PATCH ") }
        assertTrue("no Unarchive button row", rule.onAllNodesWithText("Unarchive").fetchSemanticsNodes().isEmpty())
        row(alpha).assertHasNoClickAction()
        row(alpha).performTouchInput { click() }
        swipeLeftBy(alpha, 0.9f)
        settle()
        assertNull("an archived row is never opened (nor restored just to open it)", model.state.value.selected)
        assertEquals("a tap or a left swipe on an archived row restores nothing", setupAll, fake.restLog.toList().count { it.startsWith("PATCH ") })
        row(alpha).performTouchInput { swipeRight() }
        assertTrue("a right swipe unarchives alpha", await(20_000) { !fake.rows.getValue(alpha).archived && alpha !in shown(model) })
        val action = customAction(beta, "Unarchive")
        assertNotNull("the accessible Unarchive action exists", action)
        assertTrue(action!!.invoke())
        assertTrue("the action unarchives beta", await(20_000) { !fake.rows.getValue(beta).archived && beta !in shown(model) })
        assertEquals(setupAlpha + 1, patches(alpha))
        assertEquals(setupBeta + 1, patches(beta))
        model.setShowArchived(false)
        assertTrue("both are active again", await(20_000) { shown(model).toSet() == setOf(alpha, beta) })
    }

    @Test fun C11_everyActiveCardOffersTheAccessibleOpenAndArchiveActionsWithoutVisibleButtons() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = showWith(alpha)
        assertNotNull("an Archive action exists", customAction(alpha, "Archive"))
        assertNull("an active card has no Unarchive action", customAction(alpha, "Unarchive"))
        assertTrue(customAction(alpha, "Archive")!!.invoke())
        assertTrue("the action archives it", await(20_000) { fake.rows.getValue(alpha).archived && alpha !in shown(model) })
        assertEquals(1, patches(alpha))
    }

    @Test fun C12_aListRefreshedOrReorderedWhileARowIsBeingArchivedKeepsThatRowAndLosesNothing() {
        val alpha = titled("Synthetic alpha", "alpha")
        val beta = titled("Synthetic beta", "beta")
        val model = showWith(alpha, beta)
        val (reached, release) = latchPatch()
        try {
            row(alpha).performTouchInput { swipeLeft() }
            assertTrue("in flight", await(30_000) { reached.count == 0L })
            marked("fresh-1", "Synthetic fresh")
            model.refresh()
            settle()
            assertTrue("alpha is still known while its request runs", alpha in shown(model))
        } finally {
            release.countDown()
        }
        assertTrue("alpha archived", await(20_000) { fake.rows.getValue(alpha).archived })
        assertTrue("final list is exactly the unarchived rows", await(30_000) { shown(model).toSet() == setOf(beta, "fresh-1") && rowPresent("fresh-1") && !rowPresent(alpha) })
        assertEquals("one request", 1, patches(alpha))
        val ids = shown(model)
        assertEquals(ids.toString(), ids.toSet().size, ids.size)
    }

    @Test fun C13_aConnectionChangeWhileArchivingNeverTouchesTheNewConnectionsList() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = showWith(alpha)
        val (reached, release) = latchPatch()
        try {
            row(alpha).performTouchInput { swipeLeft() }
            assertTrue("in flight", await(30_000) { reached.count == 0L })
            model.saveConnection(fake.baseUrl, "another-profile")
            assertTrue(model.state.value.conversations.isEmpty())
        } finally {
            release.countDown()
        }
        settle()
        Thread.sleep(500)
        settle()
        assertTrue("the new connection's list stays empty", shown(model).isEmpty())
        assertTrue(model.state.value.status, !model.state.value.status.startsWith("Reading"))
    }

    @Test fun C14_switchingToTheArchivedListWhileArchivingShowsTheArchivedRowOnceItIsDone() {
        val alpha = titled("Synthetic alpha", "alpha")
        val beta = titled("Synthetic beta", "beta")
        val model = showWith(alpha, beta)
        val (reached, release) = latchPatch()
        try {
            row(alpha).performTouchInput { swipeLeft() }
            assertTrue("in flight", await(30_000) { reached.count == 0L })
            model.setShowArchived(true)
            settle()
        } finally {
            release.countDown()
        }
        assertTrue("the archived list ends with alpha exactly once", await(30_000) { shown(model) == listOf(alpha) && rowPresent(alpha) })
        assertEquals(1, patches(alpha))
    }

    @Test fun C15_archivingTheOpenConversationStillClearsTheSelection() {
        val alpha = titled("Synthetic alpha", "alpha")
        val beta = titled("Synthetic beta", "beta")
        val model = showWith(alpha, beta)
        row(alpha).performClick()
        assertTrue("alpha is open", await { model.state.value.selected?.storedSessionId == alpha })
        rule.onNodeWithTag("back_button").performClick()
        assertTrue("conversations are shown again", await { rowPresent(alpha) })
        row(alpha).performTouchInput { swipeLeft() }
        assertTrue("archived", await(20_000) { fake.rows.getValue(alpha).archived })
        assertTrue("the selection is cleared", await(20_000) { model.state.value.selected == null })
        assertTrue(await(20_000) { alpha !in shown(model) })
    }
}
