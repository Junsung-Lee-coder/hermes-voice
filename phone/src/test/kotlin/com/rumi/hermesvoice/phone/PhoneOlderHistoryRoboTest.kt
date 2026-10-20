package com.rumi.hermesvoice.phone

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). Older history loads by itself when the reader scrolls up to the top of the chat: the real
 * MainActivity, Compose scrolling (touch swipes on chat_list), PhoneViewModel, AppSessionRepository and the Hermes dashboard
 * contract double that serves /messages newest-first by raw offset. The double can latch, fail or mis-serve a page.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1280dp")
class PhoneOlderHistoryRoboTest : PhoneNavRoboBase() {

    private class Latch { val arrived = CountDownLatch(1); val release = CountDownLatch(1) }

    private fun latchOlder(stored: String): Latch {
        val latch = Latch()
        fake.messagesHook = { session, offset, _ ->
            if (session == stored && offset > 0) {
                latch.arrived.countDown()
                latch.release.await(30, TimeUnit.SECONDS)
            }
            0
        }
        return latch
    }

    private fun ids(model: PhoneViewModel) = model.state.value.history.map { it.rowId }

    private fun opened(stored: String, rows: Int, prefix: String = "m"): PhoneViewModel {
        seed(stored, rows, prefix)
        val model = showWith(stored)
        openCard(model, stored)
        waitFor("the latest page is shown") { ids(model).isNotEmpty() && exists("message_${ids(model).max()}") }
        return model
    }

    private fun idleFor(ms: Long) { idle(ms); rule.waitForIdle() }

    @Test fun A01_openingTheChatDoesNotLoadOlderPagesOnItsOwn() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = opened(alpha, 120)
        idleFor(2_000)
        assertEquals("only the newest 50 rows", 50, ids(model).size)
        assertEquals("no older request after opening", emptyList<Int>(), olderOffsets(alpha))
        rule.onNodeWithTag("composer").performClick()
        idleFor(500)
        assertEquals("none after focusing the composer", emptyList<Int>(), olderOffsets(alpha))
        controller!!.recreate()
        idle(300); settle()
        waitFor("the chat is back") { exists("chat_list") }
        idleFor(1_500)
        assertEquals("none after recreation", emptyList<Int>(), olderOffsets(alpha))
        assertTrue("older history is still available", model.state.value.hasOlder)
    }

    @Test fun A02_scrollingToTheTopLoadsOneOlderPageWithoutATapAndKeepsTheReadingPosition() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = opened(alpha, 120)
        val oldestShown = ids(model).min()
        val newest = ids(model).max()
        val latch = latchOlder(alpha)
        assertTrue("scrolling up asks for older history", scrollUpUntil(60) { latch.arrived.count == 0L })
        repeat(3) { scrollUp(); pump() }
        idleFor(500)
        assertEquals("one request in flight, extra scrolls add none", listOf(50), olderOffsets(alpha))
        waitFor("the oldest loaded message is on screen") { exists("message_$oldestShown") }
        val before = bounds("message_$oldestShown")
        latch.release.countDown()
        waitFor("the older page arrived") { ids(model).size == 100 }
        pump()
        val after = bounds("message_$oldestShown")
        assertEqualsDp("same top after the prepend", before.top.value, after.top.value, 1f)
        assertEqualsDp("same left", before.left.value, after.left.value, 1f)
        assertFalse("the newest message was not scrolled into view", exists("message_$newest"))
        val all = ids(model)
        assertEquals("ascending without duplicates", all.sorted().distinct(), all)
        assertEquals((newest - 99..newest).toList(), all)
        assertEquals("still a single older request", listOf(50), olderOffsets(alpha))
    }

    @Test fun A03_rowsThatLandedMeanwhileOverlapThePageAndAreNotDuplicated() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = opened(alpha, 120)
        val newest = ids(model).max()
        val latch = latchOlder(alpha)
        assertTrue(scrollUpUntil(60) { latch.arrived.count == 0L })
        seed(alpha, 5, "late")
        latch.release.countDown()
        waitFor("the older page arrived") { ids(model).size > 50 }
        pump()
        val all = ids(model)
        assertEquals("unique ids", all.distinct(), all)
        assertEquals("ascending", all.sorted(), all)
        assertEquals("the overlapping rows were merged into one contiguous run", (newest - 94..newest).toList(), all)
    }

    @Test fun A04_hiddenAndToolRowsAdvanceTheRawCursorWithoutStrandingEarlierMessages() {
        val alpha = titled("Synthetic alpha", "alpha")
        val base = fake.rows.getValue(alpha).messages.size.toLong()
        seed(alpha, 30, "early")
        repeat(100) { fake.injectRow(alpha, "tool", "tool-${it + 1}") }
        seed(alpha, 30, "late")
        val last = base + 160
        val early = (base + 1..base + 30).toList()
        val late = (last - 29..last).toList()
        val model = showWith(alpha)
        openCard(model, alpha)
        waitFor("the latest page is shown") { ids(model).isNotEmpty() && exists("message_$last") }
        assertEquals("only the 30 visible rows of the newest raw page", late, ids(model))
        assertTrue("older rows may exist", model.state.value.hasOlder)
        assertTrue("one scroll loads past the empty raw page", scrollUpUntil(60) { ids(model).size > 30 })
        waitFor("the page after the empty one arrived") { ids(model).min() <= base + 30 }
        pump()
        assertEquals("raw offsets, not the number of visible rows", listOf(50, 100), olderOffsets(alpha))
        assertEquals((base + 11..base + 30).toList() + late, ids(model))
        assertTrue("still more below the cursor", model.state.value.hasOlder)
        assertTrue("the next scroll reaches the true start", scrollUpUntil(60) { !model.state.value.hasOlder })
        waitFor("the first rows arrived") { ids(model).min() == base + 1 }
        assertEquals(listOf(50, 100, 150), olderOffsets(alpha))
        assertEquals(early + late, ids(model))
        assertFalse("no Load older once exhausted", exists("load_older"))
    }

    @Test fun A05_theTrueStartStopsFurtherRequests() {
        val short = titled("Synthetic short", "short")
        val shortModel = opened(short, 70)
        val newest = ids(shortModel).max()
        assertTrue(scrollUpUntil(60) { !shortModel.state.value.hasOlder })
        waitFor("everything is loaded") { ids(shortModel).size == 70 }
        repeat(5) { scrollUp(); pump() }
        idleFor(1_000)
        assertEquals(listOf(50), olderOffsets(short))
        assertFalse(exists("load_older"))
        assertEquals((newest - 69..newest).toList(), ids(shortModel))
    }

    @Test fun A05b_exactlyOnePageOfRowsEndsWithOneEmptyOlderPage() {
        val exact = titled("Synthetic exact", "exact")
        val model = opened(exact, 50)
        assertTrue("a full page may have an older one", model.state.value.hasOlder)
        assertTrue(scrollUpUntil(60) { !model.state.value.hasOlder })
        repeat(5) { scrollUp(); pump() }
        idleFor(1_000)
        assertEquals(listOf(50), olderOffsets(exact))
        assertEquals(50, ids(model).size)
        assertFalse(exists("load_older"))
    }

    @Test fun A06_aFailedPageShowsRetryAndDoesNotRetryByItself() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = opened(alpha, 120)
        var failNext = true
        fake.messagesHook = { _, offset, _ -> if (offset > 0 && failNext) { failNext = false; 503 } else 0 }
        assertTrue(scrollUpUntil(60) { olderOffsets(alpha).isNotEmpty() })
        waitFor("a Retry is offered") { exists("load_older_retry") }
        val attempts = olderOffsets(alpha).size
        repeat(5) { scrollUp(); pump() }
        idleFor(1_500)
        assertEquals("no automatic retry, however long or often the reader scrolls", attempts, olderOffsets(alpha).size)
        assertEquals("nothing was added", 50, ids(model).size)
        rule.onNodeWithTag("load_older_retry").performClick()
        waitFor("the retry succeeded") { ids(model).size == 100 }
        assertEquals("exactly one more request", attempts + 1, olderOffsets(alpha).size)
        assertFalse("Retry is gone", exists("load_older_retry"))
    }

    @Test fun A07_aLateOlderPageOfAPreviousConversationNeverEntersTheCurrentOne() {
        val alpha = titled("Synthetic alpha", "alpha")
        val beta = titled("Synthetic beta", "beta")
        seed(alpha, 120, "A")
        seed(beta, 120, "B")
        val model = showWith(alpha, beta)
        openCard(model, alpha)
        waitFor("alpha's latest page") { ids(model).size == 50 }
        val latch = latchOlder(alpha)
        assertTrue(scrollUpUntil(60) { latch.arrived.count == 0L })
        rule.onNodeWithTag("back_button").performClick()
        waitFor("the list") { exists("conversation_row_$beta") }
        openCard(model, beta)
        waitFor("beta's latest page") { model.state.value.selected?.storedSessionId == beta && ids(model).size == 50 }
        latch.release.countDown()
        idleFor(1_500)
        val history = model.state.value
        assertEquals(beta, history.selected?.storedSessionId)
        assertTrue("no row of the other conversation", history.history.none { it.text.startsWith("A-") })
        assertEquals("beta is still on its first page", 50, history.history.size)
        assertTrue(scrollUpUntil(60) { ids(model).size == 100 })
        assertTrue("beta's own older page is beta's", model.state.value.history.all { it.text.startsWith("B-") })
        assertEquals("beta asked for its own older page once", listOf(50), olderOffsets(beta))
    }

    @Test fun A08_aRepeatedPageNeitherLoopsNorReportsTheEnd() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = opened(alpha, 120)
        fake.messagesOffsetOverride = { _, offset -> if (offset > 0) 0 else offset }
        assertTrue(scrollUpUntil(60) { olderOffsets(alpha).isNotEmpty() })
        waitFor("the walk is dealt with") { !model.state.value.olderLoading }
        idleFor(2_000)
        val settled = olderOffsets(alpha).size
        assertTrue("a bounded number of attempts: $settled", settled in 1..4)
        idleFor(2_000)
        assertEquals("stable once idle", settled, olderOffsets(alpha).size)
        assertTrue("older history is not reported complete", model.state.value.hasOlder)
        assertEquals("no duplicate rows", ids(model).distinct(), ids(model))
        assertEquals("nothing was added", 50, ids(model).size)
        assertFalse("not stuck loading", exists("load_older_loading"))
        assertTrue("the reader can still ask again", exists("load_older") || exists("load_older_retry"))
    }

    @Test fun A09_theChatStillOpensOnTheNewestMessage() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = opened(alpha, 120)
        val newest = ids(model).max()
        assertTrue("newest message shown", exists("message_$newest"))
        val list = bounds("chat_list")
        val message = bounds("message_$newest")
        assertTrue("newest message is inside the list: ${message.bottom} vs ${list.bottom}", message.bottom.value <= list.bottom.value + 1f)
        assertTrue("and near its bottom edge", message.bottom.value >= list.bottom.value - 120f)
    }

    private fun rowsOf(stored: String) = synchronized(fake.rows.getValue(stored).messages) { fake.rows.getValue(stored).messages.toList() }

    private fun visibleRows(stored: String) = rowsOf(stored).filter {
        it.optString("role") in setOf("user", "assistant") && it.optString("display_kind") != "hidden" && it.optString("content").isNotBlank()
    }

    private fun visibleIds(stored: String) = visibleRows(stored).map { it.getLong("id") }

    /** New rows after the open: [visible] shown messages with [hidden] tool and hidden-kind rows mixed in (ids keep counting every raw row). */
    private fun nextId(stored: String, step: Int): Long? = if (step == 1) null else rowsOf(stored).last().getLong("id") + step

    private fun arrive(stored: String, visible: Int, hidden: Int, prefix: String = "new", idStep: Int = 1) {
        var extra = 0
        for (i in 1..visible) {
            val id = nextId(stored, idStep)
            val n = id ?: (fake.rows.getValue(stored).messages.size + 1).toLong()
            fake.injectRow(stored, if (n % 2 == 1L) "user" else "assistant", "$prefix-$n", id = id)
            if (extra < hidden && i % 3 == 0) {
                extra += 1
                if (extra % 2 == 0) fake.injectRow(stored, "tool", "tool-$extra", id = nextId(stored, idStep))
                else fake.injectRow(stored, "assistant", "hidden-$extra", hiddenKind = true, id = nextId(stored, idStep))
            }
        }
        while (extra < hidden) {
            extra += 1
            fake.injectRow(stored, "tool", "tool-$extra", id = nextId(stored, idStep))
        }
    }

    /** Leaves the chat and opens the same conversation again: the newest page is read again and merged into what is shown. */
    private fun reopen(model: PhoneViewModel, stored: String, newest: Long) {
        rule.onNodeWithTag("back_button").performClick()
        waitFor("the list") { exists("conversation_row_$stored") }
        openCard(model, stored)
        waitFor("the newest rows are merged in") { ids(model).contains(newest) }
    }

    private fun browseToTheStart(model: PhoneViewModel) =
        scrollUpUntil(160) { !model.state.value.hasOlder || model.state.value.olderError || exists("load_older_retry") }

    private fun assertComplete(model: PhoneViewModel, expected: List<Long>, stored: String) {
        val state = model.state.value
        assertFalse("no false failure after browsing up", state.olderError || exists("load_older_retry"))
        assertFalse("nothing is left below the cursor", state.hasOlder)
        assertEquals("every shown message once, in row-id order, none omitted", expected, state.history.map { it.rowId })
        val texts = visibleRows(stored).associate { it.getLong("id") to it.optString("content").trim() }
        assertEquals("the texts are the server's", expected.map { texts.getValue(it) }, state.history.map { it.text })
    }

    @Test fun A10_rowsThatArrivedAfterOpeningJoinInIdOrderAndTheGapFillsBeforeOlderHistory() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = opened(alpha, 100)
        assertEquals("the newest page of 50 rows", visibleIds(alpha).takeLast(50), ids(model))
        arrive(alpha, visible = 70, hidden = 40)
        val grownId = visibleIds(alpha).max()
        synchronized(fake.rows.getValue(alpha).messages) {
            fake.rows.getValue(alpha).messages.first { it.getLong("id") == grownId }.put("content", "grown reply text")
        }
        reopen(model, alpha, grownId)
        assertEquals("the newest row shows its grown text", "grown reply text", model.state.value.history.last().text)
        val loaded = ids(model)
        assertEquals("ascending without duplicates after the refresh", loaded.sorted().distinct(), loaded)
        val oldestShown = loaded.min()
        val latch = latchOlder(alpha)
        assertTrue("scrolling up asks for older history", scrollUpUntil(80) { latch.arrived.count == 0L })
        waitFor("the oldest loaded message is on screen") { exists("message_$oldestShown") }
        val before = bounds("message_$oldestShown")
        latch.release.countDown()
        waitFor("the first older page arrived") { ids(model).size > loaded.size || model.state.value.olderError }
        pump()
        val mid = ids(model)
        assertFalse("the first older page is not a failure", model.state.value.olderError)
        assertEquals("ascending without duplicates after the first older page", mid.sorted().distinct(), mid)
        assertEqualsDp("same top for the first loaded message", before.top.value, bounds("message_$oldestShown").top.value, 1f)
        assertEquals("same left", before.left.value, bounds("message_$oldestShown").left.value, 1f)
        fake.messagesHook = null
        assertTrue("browsing ends at the start or at a stop", browseToTheStart(model))
        assertComplete(model, visibleIds(alpha), alpha)
        assertEquals("the newest row kept its grown text", "grown reply text", model.state.value.history.last().text)
        val asked = olderOffsets(alpha)
        assertEquals("each raw offset once, moving older", asked.sorted().distinct(), asked)
        assertTrue("a bounded number of older requests: $asked", asked.size <= 8)
    }

    @Test fun A11_aHundredNewRowsBetweenOpeningAndTheFirstScrollDoNotStrandTheOldestMessages() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = opened(alpha, 100)
        val opening = ids(model)
        arrive(alpha, visible = 100, hidden = 0)
        val all = visibleIds(alpha)
        reopen(model, alpha, all.max())
        assertEquals("the newest page joined the opened page with a gap between", opening + all.takeLast(50), ids(model))
        assertTrue("browsing ends at the start or at a stop", browseToTheStart(model))
        assertComplete(model, all, alpha)
    }

    @Test fun A12_olderHistoryAlreadyLoadedDoesNotHideTheRowsThatArrivedLater() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = opened(alpha, 100)
        val existing = visibleIds(alpha)
        assertTrue("everything that existed is loaded", browseToTheStart(model))
        assertComplete(model, existing, alpha)
        arrive(alpha, visible = 60, hidden = 50)
        val newest = visibleIds(alpha).max()
        reopen(model, alpha, newest)
        val merged = ids(model)
        assertEquals("ascending without duplicates after the refresh", merged.sorted().distinct(), merged)
        assertTrue("browsing ends at the start or at a stop", browseToTheStart(model))
        assertComplete(model, visibleIds(alpha), alpha)
    }

    @Test fun A13_rowsThatArrivedWithoutARefreshStillJoinInOrderWhileBrowsingUp() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = opened(alpha, 100)
        arrive(alpha, visible = 40, hidden = 30)
        val total = rowsOf(alpha).size
        assertTrue("browsing ends at the start or at a stop", browseToTheStart(model))
        assertComplete(model, visibleIds(alpha).filter { it <= total - 50 }, alpha)
    }

    @Test fun A14_aRefreshWhileAnOlderRequestIsInFlightDoesNotLetTheStalePageSkipTheNewRows() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = opened(alpha, 100)
        val arrived = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val armed = AtomicBoolean(true)
        fake.messagesServedHook = { session, offset ->
            if (session == alpha && offset > 0 && armed.compareAndSet(true, false)) {
                arrived.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        assertTrue("an older request is on its way", scrollUpUntil(80) { arrived.count == 0L })
        arrive(alpha, visible = 60, hidden = 50)
        val newest = visibleIds(alpha).max()
        rule.runOnUiThread { model.openOrResume(model.state.value.selected!!) }
        waitFor("the refresh merged the newest rows") { ids(model).contains(newest) }
        release.countDown()
        waitFor("the late page is dealt with") { !model.state.value.olderLoading }
        val settled = ids(model)
        assertEquals("ascending without duplicates", settled.sorted().distinct(), settled)
        assertTrue("browsing ends at the start or at a stop", browseToTheStart(model))
        assertComplete(model, visibleIds(alpha), alpha)
    }

    @Test fun A16_aGrownNewestRowAndAFewNewRowsRefreshInPlaceWithoutLosingOlderHistory() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = opened(alpha, 100)
        assertTrue("one more page above is loaded first", scrollUpUntil(80) { ids(model).size > 50 })
        waitFor("the older page arrived") { ids(model).size == 100 }
        val existing = visibleIds(alpha)
        val grownId = existing.max()
        synchronized(fake.rows.getValue(alpha).messages) {
            fake.rows.getValue(alpha).messages.first { it.getLong("id") == grownId }.put("content", "grown reply text")
        }
        arrive(alpha, visible = 6, hidden = 3)
        val newest = visibleIds(alpha).max()
        reopen(model, alpha, newest)
        assertEquals("the grown row shows the server's text", "grown reply text", model.state.value.history.first { it.rowId == grownId }.text)
        val merged = ids(model)
        assertEquals("ascending without duplicates", merged.sorted().distinct(), merged)
        assertEquals("the older rows stayed", existing, merged.filter { it <= grownId })
        assertTrue("browsing ends at the start or at a stop", browseToTheStart(model))
        assertComplete(model, visibleIds(alpha), alpha)
    }

    /**
     * Rows arrive twice: before a refresh that leaves a gap, and again after the first older page with no refresh in between. The
     * server's next page at the same raw offset then holds rows that are all shown already, yet it still reaches older rows than
     * the oldest one passed so far, so the reader has to move on instead of being stopped.
     */
    private fun browseAfterASecondArrival(secondVisible: Int, secondHidden: Int) {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = opened(alpha, 100)
        arrive(alpha, visible = 100, hidden = 0)
        reopen(model, alpha, visibleIds(alpha).max())
        assertTrue("one older page is read", scrollUpUntil(80) { ids(model).size > 100 || model.state.value.olderError })
        waitFor("the first older page joined the shown rows") { ids(model).size == 150 || model.state.value.olderError }
        pump()
        assertFalse("the first older page is not a failure", model.state.value.olderError)
        assertEquals("the first older read", listOf(50), olderOffsets(alpha))
        val bound = model.state.value.olderBound
        val raw = model.state.value.rawLoaded
        arrive(alpha, secondVisible, secondHidden)
        val shown = ids(model).toSet()
        val newestShown = shown.max()
        val visible = visibleIds(alpha).toSet()
        val next = rowsOf(alpha).reversed().drop(raw).take(50).map { it.getLong("id") }.filter { it in visible }
        assertTrue("the next page holds rows that are all shown already: $next", next.isNotEmpty() && next.all { it in shown })
        assertTrue("and it still reaches older rows than the oldest one passed so far ($bound): $next", next.min() < bound)
        assertTrue("older rows are still unseen", visible.any { it < ids(model).min() })
        assertTrue("browsing ends at the start or at a stop", browseToTheStart(model))
        assertComplete(model, visibleIds(alpha).filter { it <= newestShown }, alpha)
        val asked = olderOffsets(alpha)
        assertEquals("each raw offset once, moving older", asked.sorted().distinct(), asked)
        assertTrue("a bounded number of older requests: $asked", asked.size <= 8)
    }

    @Test fun A17_moreRowsArrivingBetweenOlderReadsDoNotStopAKnownPageThatReachesOlderRows() {
        browseAfterASecondArrival(secondVisible = 20, secondHidden = 0)
    }

    @Test fun A18_hiddenRowsInTheSecondArrivalKeepTheRawCursorMovingTowardTheUnseenRows() {
        browseAfterASecondArrival(secondVisible = 30, secondHidden = 10)
    }

    private fun seedStep(stored: String, count: Int, step: Int, prefix: String = "m") {
        if (step == 1) { seed(stored, count, prefix); return }
        repeat(count) {
            val id = nextId(stored, step)!!
            fake.injectRow(stored, if (id % 2 == 1L) "user" else "assistant", "$prefix-$id", id = id)
        }
    }

    private fun openedStep(stored: String, rows: Int, step: Int, listed: List<String>): PhoneViewModel {
        seedStep(stored, rows, step)
        val model = showWith(stored, *listed.toTypedArray())
        openCard(model, stored)
        waitFor("the latest page is shown") { ids(model).isNotEmpty() && exists("message_${ids(model).max()}") }
        return model
    }

    private fun rawIdsOf(stored: String, offset: Int) = rowsOf(stored).reversed().drop(offset).take(50).map { it.getLong("id") }

    /**
     * 100 rows are open, 100 more arrive and a refresh leaves a gap, then one older page is read: the shown rows are the opened
     * ones plus the newest 50 plus that older page, the raw cursor is 100 and the oldest row passed is the first row of the older page.
     */
    private fun driftStart(idStep: Int, alpha: String = titled("Synthetic alpha", "alpha"), listed: List<String> = emptyList()): Pair<String, PhoneViewModel> {
        val model = openedStep(alpha, 100, idStep, listed)
        arrive(alpha, visible = 100, hidden = 0, idStep = idStep)
        reopen(model, alpha, visibleIds(alpha).max())
        assertTrue("one older page is read", scrollUpUntil(80) { ids(model).size > 100 || model.state.value.olderError })
        waitFor("the first older page joined the shown rows") { ids(model).size == 150 || model.state.value.olderError }
        pump()
        assertFalse("the first older page is not a failure", model.state.value.olderError)
        assertEquals("the first older read", listOf(50), olderOffsets(alpha))
        return alpha to model
    }

    /** Every message up to the newest one that was shown when the arrivals began is present once in row-id order, none omitted. */
    private fun assertNoneOmitted(model: PhoneViewModel, stored: String, newestShown: Long) {
        val state = model.state.value
        assertFalse("no false failure after browsing up", state.olderError || exists("load_older_retry"))
        assertFalse("nothing is left below the cursor", state.hasOlder)
        val shown = state.history.map { it.rowId }
        assertEquals("ascending without duplicates", shown.sorted().distinct(), shown)
        assertEquals(
            "every message up to the newest shown one is present once",
            visibleIds(stored).filter { it <= newestShown }, shown.filter { it <= newestShown },
        )
        val texts = visibleRows(stored).associate { it.getLong("id") to it.optString("content").trim() }
        assertEquals("the texts are the server's", shown.map { texts.getValue(it) }, state.history.map { it.text })
    }

    private fun finishDrift(alpha: String, model: PhoneViewModel, newestShown: Long, extraBudget: Int) {
        assertTrue("browsing ends at the start or at a stop", browseToTheStart(model))
        assertNoneOmitted(model, alpha, newestShown)
        val asked = olderOffsets(alpha)
        assertTrue("a bounded number of older requests: $asked", asked.size <= 8 + extraBudget)
        assertEquals("each raw offset once, moving older", asked.sorted().distinct(), asked)
        idleFor(1_500)
        assertEquals("nothing runs by itself afterwards", asked.size, olderOffsets(alpha).size)
    }

    /** After the first older page, each round adds [visible, hidden] rows and then one more older read is made, with no refresh in between. */
    private fun driftScenario(rounds: List<Pair<Int, Int>>, idStep: Int = 1) {
        val (alpha, model) = driftStart(idStep)
        val newestShown = ids(model).max()
        for ((visible, hidden) in rounds) {
            val before = olderOffsets(alpha).size
            arrive(alpha, visible, hidden, idStep = idStep)
            scrollUpUntil(80) { olderOffsets(alpha).size > before || model.state.value.olderError }
            waitFor("the older read is dealt with") { !model.state.value.olderLoading }
            pump()
            assertFalse("rows arriving between older reads are not a failure: ${olderOffsets(alpha)}", model.state.value.olderError)
        }
        finishDrift(alpha, model, newestShown, 6 * rounds.size)
    }

    @Test fun B01_49NewRowsBetweenOlderReadsDoNotStrandTheHistory() { driftScenario(listOf(49 to 0)) }

    @Test fun B02_exactly50NewRowsMakeTheNextPageIdenticalToTheOneBeforeAndTheReadProceeds() {
        val (alpha, model) = driftStart(1)
        val newestShown = ids(model).max()
        val previous = rawIdsOf(alpha, 50)
        arrive(alpha, visible = 50, hidden = 0)
        assertEquals("50 new rows make the raw page at offset 100 the page read before", previous, rawIdsOf(alpha, 100))
        finishDrift(alpha, model, newestShown, 0)
    }

    @Test fun B03_60NewRowsBetweenOlderReadsDoNotStrandTheHistory() { driftScenario(listOf(60 to 0)) }

    @Test fun B04_100NewRowsBetweenOlderReadsDoNotStrandTheHistory() { driftScenario(listOf(100 to 0)) }

    @Test fun B05_150NewRowsBetweenOlderReadsDoNotStrandTheHistory() { driftScenario(listOf(150 to 0)) }

    @Test fun B06_50NewRawRowsWithHiddenAndToolRowsDoNotStrandTheHistory() { driftScenario(listOf(30 to 20)) }

    @Test fun B07_60NewRawRowsWithHiddenAndToolRowsDoNotStrandTheHistory() { driftScenario(listOf(35 to 25)) }

    @Test fun B08_100NewRawRowsWithHiddenAndToolRowsDoNotStrandTheHistory() { driftScenario(listOf(60 to 40)) }

    @Test fun B09_150NewRawRowsMostlyHiddenDoNotStrandTheHistory() { driftScenario(listOf(90 to 60)) }

    @Test fun B10_50NewRowsThatAreAllToolRowsDoNotStrandTheHistory() { driftScenario(listOf(0 to 50)) }

    @Test fun B11_50NewRowsWithNoncontiguousRowIdsDoNotStrandTheHistory() { driftScenario(listOf(50 to 0), idStep = 3) }

    @Test fun B12_60NewRawRowsWithNoncontiguousRowIdsAndHiddenRowsDoNotStrandTheHistory() { driftScenario(listOf(36 to 24), idStep = 5) }

    @Test fun B13_twoRoundsOf60NewRowsBetweenOlderReadsDoNotStrandTheHistory() { driftScenario(listOf(60 to 0, 60 to 0)) }

    @Test fun B14_twoRoundsOfMixedRawRowsWithNoncontiguousIdsDoNotStrandTheHistory() { driftScenario(listOf(30 to 20, 60 to 40), idStep = 2) }

    @Test fun B15_threeRoundsAroundTheFiftyRowBoundaryDoNotStrandTheHistory() { driftScenario(listOf(49 to 0, 50 to 0, 60 to 0)) }

    @Test fun B16_theReadingPositionHoldsWhileOlderReadsMovePastRowsThatArrivedMeanwhile() {
        val (alpha, model) = driftStart(1)
        val oldest = ids(model).min()
        val newest = ids(model).max()
        arrive(alpha, visible = 60, hidden = 0)
        val latch = latchOlder(alpha)
        assertTrue("scrolling up asks for older history", scrollUpUntil(80) { latch.arrived.count == 0L })
        waitFor("the oldest loaded message is on screen") { exists("message_$oldest") }
        val before = bounds("message_$oldest")
        latch.release.countDown()
        waitFor("the older reads are dealt with") { !model.state.value.olderLoading && (ids(model).min() < oldest || model.state.value.olderError) }
        pump()
        assertFalse("the reads are not a failure: ${olderOffsets(alpha)}", model.state.value.olderError)
        assertEqualsDp("same top after the prepend", before.top.value, bounds("message_$oldest").top.value, 1f)
        assertEqualsDp("same left", before.left.value, bounds("message_$oldest").left.value, 1f)
        assertFalse("the newest message was not scrolled into view", exists("message_$newest"))
    }

    private fun refusedPageAfterTheJoin(served: (Int) -> Int) {
        val (alpha, model) = driftStart(1)
        val shown = ids(model)
        fake.messagesOffsetOverride = { _, offset -> if (offset >= 100) served(offset) else offset }
        assertTrue("the refused page is reported", scrollUpUntil(80) { model.state.value.olderError })
        waitFor("a Retry is offered") { exists("load_older_retry") }
        idleFor(1_500)
        val attempts = olderOffsets(alpha)
        assertTrue("a bounded number of attempts: $attempts", attempts.size in 2..5)
        assertEquals("nothing was added", shown, ids(model))
        assertTrue("older history is not reported complete", model.state.value.hasOlder)
        assertFalse("not stuck loading", exists("load_older_loading"))
        rule.onNodeWithTag("load_older_retry").performClick()
        waitFor("the retry was answered") { olderOffsets(alpha).size > attempts.size && !model.state.value.olderLoading }
        idleFor(1_000)
        assertTrue("one gesture's worth at most", olderOffsets(alpha).size - attempts.size in 1..4)
        assertTrue("still stopped with Retry", exists("load_older_retry"))
        assertEquals("nothing was added by the retry", shown, ids(model))
    }

    @Test fun B17_aServerThatRepeatsTheLastOlderPageWithNothingNewStopsWithRetry() { refusedPageAfterTheJoin { 50 } }

    @Test fun B18_aServerThatIgnoresTheOffsetAndAnswersWithTheNewestPageStopsWithRetry() { refusedPageAfterTheJoin { 0 } }

    @Test fun B19_hiddenRunsAndNewRowsBetweenReadsStillReachTheEarlierMessages() {
        val alpha = titled("Synthetic alpha", "alpha")
        seed(alpha, 30, "early")
        repeat(100) { fake.injectRow(alpha, "tool", "tool-${it + 1}") }
        seed(alpha, 30, "late")
        val last = rowsOf(alpha).last().getLong("id")
        val model = showWith(alpha)
        openCard(model, alpha)
        waitFor("the latest page is shown") { ids(model).isNotEmpty() && exists("message_$last") }
        val newestShown = ids(model).max()
        arrive(alpha, visible = 40, hidden = 20)
        assertTrue("browsing ends at the start or at a stop", browseToTheStart(model))
        assertNoneOmitted(model, alpha, newestShown)
    }

    /** Holds the second older request of the next gesture on a latch: a read of the walk that is under way when the test changes things. */
    private fun holdTheSecondOlderRead(alpha: String): Pair<Latch, () -> Boolean> {
        val latch = Latch()
        val seen = java.util.concurrent.atomic.AtomicInteger(0)
        fake.messagesHook = { session, offset, _ ->
            if (session == alpha && offset > 0 && seen.incrementAndGet() == 2) {
                latch.arrived.countDown()
                latch.release.await(30, TimeUnit.SECONDS)
            }
            0
        }
        return latch to { latch.arrived.await(5, TimeUnit.SECONDS) }
    }

    @Test fun B20_aRefreshWhileALaterReadOfTheWalkIsInFlightNeitherMovesTheCursorNorStrandsHistory() {
        val (alpha, model) = driftStart(1)
        arrive(alpha, visible = 60, hidden = 0)
        val (latch, held) = holdTheSecondOlderRead(alpha)
        scrollUpUntil(80) { latch.arrived.count == 0L || model.state.value.olderError }
        held()
        val newest = visibleIds(alpha).max()
        rule.runOnUiThread { model.openOrResume(model.state.value.selected!!) }
        waitFor("the refresh merged the newest rows") { ids(model).contains(newest) }
        latch.release.countDown()
        waitFor("the late answer is dealt with") { !model.state.value.olderLoading }
        pump()
        val settled = ids(model)
        assertEquals("ascending without duplicates", settled.sorted().distinct(), settled)
        assertTrue("browsing ends at the start or at a stop", browseToTheStart(model))
        assertComplete(model, visibleIds(alpha), alpha)
    }

    @Test fun B21_leavingTheConversationWhileALaterReadOfTheWalkIsInFlightKeepsBothConversationsApart() {
        val alpha = titled("Synthetic alpha", "alpha")
        val beta = titled("Synthetic beta", "beta")
        seed(beta, 120, "B")
        val (_, model) = driftStart(1, alpha, listOf(beta))
        arrive(alpha, visible = 60, hidden = 0)
        val (latch, held) = holdTheSecondOlderRead(alpha)
        scrollUpUntil(80) { latch.arrived.count == 0L || model.state.value.olderError }
        held()
        rule.onNodeWithTag("back_button").performClick()
        waitFor("the list") { exists("conversation_row_$beta") }
        openCard(model, beta)
        waitFor("beta's latest page") { model.state.value.selected?.storedSessionId == beta && ids(model).size == 50 }
        latch.release.countDown()
        idleFor(1_500)
        assertEquals(beta, model.state.value.selected?.storedSessionId)
        assertTrue("no row of the other conversation", model.state.value.history.none { !it.text.startsWith("B-") })
        assertEquals("beta is still on its first page", 50, ids(model).size)
        assertEquals("beta asked for nothing older", emptyList<Int>(), olderOffsets(beta))
        rule.onNodeWithTag("back_button").performClick()
        waitFor("the list") { exists("conversation_row_$alpha") }
        openCard(model, alpha)
        waitFor("alpha's newest rows") { ids(model).contains(visibleIds(alpha).max()) }
        assertTrue("browsing ends at the start or at a stop", browseToTheStart(model))
        assertComplete(model, visibleIds(alpha), alpha)
    }

    @Test fun B22_aLaterReadOfTheWalkThatFailsShowsRetryKeepsTheRowsAndTheRetryContinues() {
        val (alpha, model) = driftStart(1)
        val shown = ids(model)
        val newestShown = shown.max()
        arrive(alpha, visible = 60, hidden = 0)
        val reads = java.util.concurrent.atomic.AtomicInteger(0)
        fake.messagesHook = { session, offset, _ -> if (session == alpha && offset > 0 && reads.incrementAndGet() == 2) 503 else 0 }
        assertTrue("the failure is reported", scrollUpUntil(80) { model.state.value.olderError })
        waitFor("a Retry is offered") { exists("load_older_retry") }
        idleFor(1_000)
        val attempts = olderOffsets(alpha).size
        assertEquals("the rows are kept", shown, ids(model))
        assertTrue("older history is not reported complete", model.state.value.hasOlder)
        repeat(3) { scrollUp(); pump() }
        idleFor(1_000)
        assertEquals("no automatic retry", attempts, olderOffsets(alpha).size)
        rule.onNodeWithTag("load_older_retry").performClick()
        idleFor(1_000)
        assertTrue("browsing ends at the start or at a stop", browseToTheStart(model))
        assertNoneOmitted(model, alpha, newestShown)
    }

    @Test fun B23_rowsKeepingOnArrivingWithEveryReadNeverReportTheEndAndNeverLoopByThemselves() {
        val (alpha, model) = driftStart(1)
        fake.messagesServedHook = { session, offset ->
            if (session == alpha && offset > 0) repeat(60) { fake.injectRow(alpha, "user", "w-$it") }
        }
        repeat(8) { scrollUp(); pump() }
        waitFor("the walk is dealt with") { !model.state.value.olderLoading }
        idleFor(2_000)
        val settled = olderOffsets(alpha).size
        val shown = ids(model)
        assertEquals("ascending without duplicates", shown.sorted().distinct(), shown)
        assertTrue("older history is never reported complete while rows keep arriving", model.state.value.hasOlder)
        assertTrue("a bounded number of older requests: ${olderOffsets(alpha)}", settled <= 8 * 4 + 1)
        assertFalse("not stuck loading", exists("load_older_loading"))
        idleFor(2_000)
        assertEquals("nothing runs by itself", settled, olderOffsets(alpha).size)
    }

    @Test fun B24_aServerThatKeepsAnsweringWithTheSameRunOfHiddenRowsStopsWithRetryInsteadOfSkippingForever() {
        val alpha = titled("Synthetic alpha", "alpha")
        seed(alpha, 30, "early")
        repeat(100) { fake.injectRow(alpha, "tool", "tool-${it + 1}") }
        seed(alpha, 30, "late")
        val last = rowsOf(alpha).last().getLong("id")
        val model = showWith(alpha)
        openCard(model, alpha)
        waitFor("the latest page is shown") { ids(model).isNotEmpty() && exists("message_$last") }
        val shown = ids(model)
        fake.messagesOffsetOverride = { _, offset -> if (offset >= 50) 50 else offset }
        assertTrue("the repeated hidden page is reported", scrollUpUntil(60) { model.state.value.olderError })
        waitFor("a Retry is offered") { exists("load_older_retry") }
        idleFor(1_500)
        val attempts = olderOffsets(alpha)
        assertTrue("a bounded number of attempts: $attempts", attempts.size in 2..6)
        assertEquals("nothing was added", shown, ids(model))
        assertTrue("older history is not reported complete", model.state.value.hasOlder)
        idleFor(1_500)
        assertEquals("no hot loop", attempts.size, olderOffsets(alpha).size)
    }

    @Test fun A15_aPageThatRepeatsTheOneBeforeStillStopsWithRetryAfterProgress() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = opened(alpha, 200)
        fake.messagesOffsetOverride = { _, offset -> if (offset >= 100) 50 else offset }
        assertTrue("the second older page is refused", scrollUpUntil(150) { model.state.value.olderError })
        waitFor("a Retry is offered") { exists("load_older_retry") }
        idleFor(1_500)
        val attempts = olderOffsets(alpha)
        assertTrue("bounded attempts: $attempts", attempts.size in 2..4)
        assertEquals("only the first older page was added", 100, ids(model).size)
        assertEquals("no duplicate rows", ids(model).distinct(), ids(model))
        assertTrue("older history is not reported complete", model.state.value.hasOlder)
        rule.onNodeWithTag("load_older_retry").performClick()
        waitFor("the retry was answered") { olderOffsets(alpha).size >= attempts.size + 2 && !model.state.value.olderLoading }
        idleFor(1_000)
        assertEquals("one more pair: the page at the cursor is held and the page after it decides", attempts.size + 2, olderOffsets(alpha).size)
        assertTrue("still stopped with Retry", exists("load_older_retry"))
    }

    /**
     * The server ignores the offset (answers with the newest page) so an older read is refused with Retry; the server then answers
     * correctly again and exactly the refused page's worth of raw rows ([visible] shown + [hidden] not shown) arrives, so the right
     * page at the refused offset is the refused page itself. Retry must reach every older row with no refresh or reopen.
     */
    private fun refusedThenHealedAfterExactlyAPageOfArrivals(idStep: Int, visible: Int, hidden: Int) {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = openedStep(alpha, 200, idStep, emptyList())
        assertTrue("one older page is read", scrollUpUntil(80) { ids(model).size > 50 })
        waitFor("the first older page joined the shown rows") { ids(model).size == 100 || model.state.value.olderError }
        pump()
        assertFalse("the first older page is not a failure", model.state.value.olderError)
        assertEquals("the first older read", listOf(50), olderOffsets(alpha))
        val newestShown = ids(model).max()
        val refusedPage = rawIdsOf(alpha, 0)
        fake.messagesOffsetOverride = { _, offset -> if (offset >= 100) 0 else offset }
        assertTrue("the ignored offset is reported", scrollUpUntil(80) { model.state.value.olderError })
        waitFor("a Retry is offered") { exists("load_older_retry") }
        idleFor(1_500)
        val shown = ids(model)
        val attempts = olderOffsets(alpha)
        assertTrue("a bounded number of attempts: $attempts", attempts.size in 2..5)
        rule.onNodeWithTag("load_older_retry").performClick()
        waitFor("the retry against the still wrong server was answered") { olderOffsets(alpha).size > attempts.size && !model.state.value.olderLoading }
        idleFor(1_000)
        assertTrue("one gesture's worth at most: ${olderOffsets(alpha)}", olderOffsets(alpha).size - attempts.size in 1..4)
        assertTrue("a server that is still wrong is refused again with Retry", exists("load_older_retry"))
        assertEquals("nothing was added while the server was wrong", shown, ids(model))
        assertTrue("older history is not reported complete", model.state.value.hasOlder)

        fake.messagesOffsetOverride = null
        arrive(alpha, visible, hidden, idStep = idStep)
        assertEquals("the right page at the refused offset is the refused page", refusedPage, rawIdsOf(alpha, 100))
        val oldest = ids(model).min()
        val beforeHeal = olderOffsets(alpha).size
        rule.onNodeWithTag("load_older_retry").performClick()
        waitFor("the retry against the healed server was dealt with") {
            olderOffsets(alpha).size > beforeHeal && !model.state.value.olderLoading && (ids(model).min() < oldest || model.state.value.olderError)
        }
        pump()
        assertFalse("Retry is not refused again after the server healed: ${olderOffsets(alpha)}", model.state.value.olderError || exists("load_older_retry"))
        assertTrue("that one Retry reached older rows", ids(model).min() < oldest)
        assertTrue("one gesture's worth at most: ${olderOffsets(alpha)}", olderOffsets(alpha).size - beforeHeal in 1..4)
        assertTrue("browsing ends at the start or at a stop", browseToTheStart(model))
        assertNoneOmitted(model, alpha, newestShown)
    }

    @Test fun C01_retryReachesEveryOlderRowAfterARefusingServerHealsAndExactlyAPageOfRowsArrives() {
        refusedThenHealedAfterExactlyAPageOfArrivals(idStep = 1, visible = 100, hidden = 0)
    }

    @Test fun C02_retryReachesEveryOlderRowAfterTheHealWithHiddenToolRowsAndNoncontiguousIds() {
        refusedThenHealedAfterExactlyAPageOfArrivals(idStep = 3, visible = 60, hidden = 40)
    }
}
