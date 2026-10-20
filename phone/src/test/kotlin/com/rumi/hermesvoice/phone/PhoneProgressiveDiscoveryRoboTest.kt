package com.rumi.hermesvoice.phone

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import com.rumi.hermesvoice.core.sessions.AppSources
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). The Conversations tab while the dashboard list is read page by page: the real MainActivity,
 * PhoneViewModel, PhoneApp wiring and AppSessionRepository against the Hermes dashboard contract double. Simulated: the Data Layer
 * (mockk) and the keystore; no device, no real network.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class PhoneProgressiveDiscoveryRoboTest : PhoneRoboBase() {
    val rule = createEmptyComposeRule()
    @get:Rule val cleanupThenCompose: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(ComposeDispatcherCleanup()).around(rule)

    @Before fun setUp() = baseSetUp()
    @After fun tearDown() {
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

    private fun marked(id: String) {
        fake.addForeignRow(id, "discord", title = "Topic $id")
        fake.setCreatedSource(id, AppSources.CONVERSATION)
    }

    private fun shown(model: PhoneViewModel) = model.state.value.conversations.map { it.stored.id }

    /** Same wait and verdict as `assertTrue(label, await(timeoutMs, done))`; when it times out the failure also names the awaited condition, what is shown and the last requests. */
    private fun awaitOrDiagnose(label: String, awaited: String, model: PhoneViewModel, timeoutMs: Long, done: () -> Boolean) {
        val met = await(timeoutMs, done)
        assertTrue("$label [waited ${timeoutMs}ms for: $awaited; shown=${shown(model)}; status='${model.state.value.status}'; last requests=${fake.restLog.toList().takeLast(12)}]", met)
    }

    private fun latch(offset: Int, archived: String): Pair<CountDownLatch, CountDownLatch> {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.listPageHook = { at, filter ->
            if (at >= offset && filter == archived) {
                reached.countDown()
                release.await(60, TimeUnit.SECONDS)
            }
        }
        return reached to release
    }

    @Test fun D01_sessionsFoundOnTheFirstPageAreShownAndOpenableWhileTheNextPageIsStillBeingRead() {
        val model = showing()
        marked("old-1")
        for (i in 1..250) fake.addForeignRow("noise-$i", "cron")
        marked("new-1")
        marked("new-2")
        val (reached, release) = latch(100, "exclude")
        try {
            model.refresh()
            awaitOrDiagnose("the second page was requested", "the second list page request reached the server double (offset 100, active scope)", model, 30_000) { reached.count == 0L }
            assertTrue("the first page's sessions are shown", await(20_000) { shown(model).toSet() == setOf("new-1", "new-2") })
            assertTrue("and on screen", await { rule.onAllNodesWithText("Topic new-1").fetchSemanticsNodes().isNotEmpty() })
            val firstShown = model.state.value.conversations.first().stored.id
            rule.onNodeWithTag("conversation_row_$firstShown").performClick()
            assertTrue("a found session can be opened", await {
                model.state.value.selected?.storedSessionId in setOf("new-1", "new-2")
            })
        } finally {
            release.countDown()
        }
        val opened = model.state.value.selected?.storedSessionId
        assertTrue("the rest of the list arrives", await(20_000) { "old-1" in shown(model) })
        val ids = shown(model)
        assertEquals(ids.toString(), ids.toSet().size, ids.size)
        assertEquals(setOf("new-1", "new-2", "old-1"), ids.toSet())
        assertEquals("the opened session stays selected", opened, model.state.value.selected?.storedSessionId)
    }

    @Test fun D02_aListStillBeingReadForOneScopeNeverReplacesTheListOfTheScopeChosenAfterIt() {
        val alpha = conversation("alpha")
        val old = conversation("old")
        runBlocking { app.wiring().core.sessions.setArchived(old, true) }
        for (i in 1..150) fake.addForeignRow("noise-$i", "cron")
        val model = showing()
        assertTrue("alpha is listed first", await(20_000) { shown(model) == listOf(alpha) })
        val (reached, release) = latch(100, "exclude")
        try {
            model.refresh()
            assertTrue("the active list is waiting for its second page", await(30_000) { reached.count == 0L })
            model.setShowArchived(true)
            assertTrue("the archived list is shown", await(20_000) { shown(model) == listOf(old) })
        } finally {
            release.countDown()
        }
        settle()
        Thread.sleep(500)
        settle()
        assertEquals("the late active list did not replace the archived one", listOf(old), shown(model))
        assertTrue(model.state.value.showArchived)
    }

    @Test fun D03_aPageThatFailsLeavesWhatWasFoundShownAndSaysTheListIsPartial() {
        val model = showing()
        marked("old-1")
        for (i in 1..250) fake.addForeignRow("noise-$i", "cron")
        marked("new-1")
        fake.failListFromOffset = 100
        model.refresh()
        awaitOrDiagnose("what the first page held is shown", "the list equals [new-1] while the second page fails", model, 20_000) { shown(model) == listOf("new-1") }
        assertTrue("the list says it is partial", await(20_000) { model.state.value.status.contains("Only 100 of ") })
        assertTrue(model.state.value.status, model.state.value.status.contains("may be missing some"))
        assertEquals(listOf("new-1"), shown(model))
        fake.failListFromOffset = -1
        model.refresh()
        assertTrue("a later complete read finds the rest", await(20_000) { shown(model).toSet() == setOf("new-1", "old-1") })
        assertTrue(model.state.value.status, await(20_000) { model.state.value.status.isEmpty() })
    }

    @Test fun D04_aListStillBeingReadForAnotherConnectionIsNeverShown() {
        val model = showing()
        marked("old-1")
        for (i in 1..250) fake.addForeignRow("noise-$i", "cron")
        marked("new-1")
        val (reached, release) = latch(100, "exclude")
        try {
            model.refresh()
            assertTrue(await(30_000) { reached.count == 0L })
            assertTrue(await(20_000) { shown(model) == listOf("new-1") })
            model.saveConnection(fake.baseUrl, "another-profile")
            assertTrue(model.state.value.conversations.isEmpty())
        } finally {
            release.countDown()
        }
        settle()
        Thread.sleep(500)
        settle()
        assertTrue(shown(model).toString(), shown(model).isEmpty())
        assertTrue(model.state.value.status, !model.state.value.status.startsWith("Reading"))
    }
}
