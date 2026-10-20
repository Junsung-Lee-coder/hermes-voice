package com.rumi.hermesvoice.phone

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** The real chat, PhoneViewModel and repository over the dashboard double: typed internal rows never become visible messages. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1280dp")
class PhoneInternalEventVisibilityRoboTest : PhoneNavRoboBase() {
    private val completion = "[SYSTEM: Background process proc_synthetic_01 completed (exit code 0)]"

    private fun ids(model: PhoneViewModel) = model.state.value.history.map { it.rowId }

    private fun texts(model: PhoneViewModel) = model.state.value.history.map { it.text }

    private fun add(stored: String, role: String, content: String, kind: String?, displayContent: String? = null): Long {
        val list = fake.rows.getValue(stored).messages
        synchronized(list) {
            val id = (list.size + 1).toLong()
            val row = JSONObject().put("id", id).put("session_id", stored).put("role", role).put("content", content)
                .put("timestamp", 1_700_000_000.0 + list.size)
            if (kind != null) row.put("display_kind", kind)
            if (displayContent != null) row.put("display_content", displayContent)
            list += row
            return id
        }
    }

    private fun rowsOf(stored: String) = synchronized(fake.rows.getValue(stored).messages) { fake.rows.getValue(stored).messages.toList() }

    private fun internalIds(stored: String) = rowsOf(stored).filter {
        it.optString("display_kind") in setOf("internal_notification", "async_delegation_complete")
    }.map { it.getLong("id") }

    private fun opened(stored: String): PhoneViewModel {
        val model = showWith(stored)
        openCard(model, stored)
        waitFor("the latest page is shown") { ids(model).isNotEmpty() }
        return model
    }

    @Test fun V01_openingTheChatShowsOrdinaryRowsAndHidesTypedInternalOnes() {
        val alpha = titled("Synthetic alpha", "alpha")
        val question = add(alpha, "user", "question", null)
        val hiddenCompletion = add(alpha, "user", completion, "internal_notification")
        val typedByTheUser = add(alpha, "user", completion, null)
        val answer = add(alpha, "assistant", "Background process proc_synthetic_01 finished.", null)
        val hiddenDelegation = add(alpha, "user", "delegated work finished", "async_delegation_complete")
        val steer = add(alpha, "user", "raw steer wrapper", "user_steer", displayContent = "steer shown")
        val unknown = add(alpha, "user", "from a newer server", "a_kind_from_a_newer_server")
        val model = opened(alpha)
        waitFor("the newest row is shown") { ids(model).contains(unknown) }
        assertEquals(listOf(question, typedByTheUser, answer, steer, unknown), ids(model))
        assertEquals(
            listOf("question", completion, "Background process proc_synthetic_01 finished.", "steer shown", "from a newer server"),
            texts(model),
        )
        assertFalse(ids(model).contains(hiddenCompletion) || ids(model).contains(hiddenDelegation))
        assertFalse("no bubble for the internal rows", exists("message_$hiddenCompletion") || exists("message_$hiddenDelegation"))
        assertTrue("the shown rows have bubbles", exists("message_$typedByTheUser") && exists("message_$answer"))
    }

    @Test fun V02_aRawPageOfOnlyInternalRowsAdvancesTheCursorWithoutStrandingEarlierMessages() {
        val alpha = titled("Synthetic alpha", "alpha")
        val base = fake.rows.getValue(alpha).messages.size.toLong()
        seed(alpha, 30, "early")
        repeat(100) { add(alpha, "user", completion, "internal_notification") }
        seed(alpha, 30, "late")
        val last = base + 160
        val early = (base + 1..base + 30).toList()
        val late = (last - 29..last).toList()
        val model = showWith(alpha)
        openCard(model, alpha)
        waitFor("the latest page is shown") { ids(model).isNotEmpty() && exists("message_$last") }
        assertEquals("only the 30 shown rows of the newest raw page", late, ids(model))
        assertTrue("older rows may exist", model.state.value.hasOlder)
        assertTrue("one scroll loads past the all-internal raw page", scrollUpUntil(60) { ids(model).size > 30 })
        waitFor("the page after the internal-only one arrived") { ids(model).min() <= base + 30 }
        pump()
        assertEquals("raw offsets, not the number of shown rows", listOf(50, 100), olderOffsets(alpha))
        assertEquals((base + 11..base + 30).toList() + late, ids(model))
        assertTrue("the next scroll reaches the true start", scrollUpUntil(60) { !model.state.value.hasOlder })
        waitFor("the first rows arrived") { ids(model).min() == base + 1 }
        assertEquals(listOf(50, 100, 150), olderOffsets(alpha))
        assertEquals(early + late, ids(model))
        assertTrue("no internal row was ever shown", ids(model).none { it in internalIds(alpha) })
    }

    @Test fun V03_reopeningMergesNewestRowsWithoutShowingInternalOnesThatArrivedMeanwhile() {
        val alpha = titled("Synthetic alpha", "alpha")
        seed(alpha, 60, "m")
        val model = opened(alpha)
        val before = ids(model)
        add(alpha, "user", completion, "internal_notification")
        val fresh = add(alpha, "assistant", "fresh answer", null)
        add(alpha, "user", "delegated work finished", "async_delegation_complete")
        val freshQuestion = add(alpha, "user", completion, null)
        rule.onNodeWithTag("back_button").performClick()
        waitFor("the list") { exists("conversation_row_$alpha") }
        openCard(model, alpha)
        waitFor("the newest rows are merged in") { ids(model).contains(freshQuestion) }
        assertEquals(before + listOf(fresh, freshQuestion), ids(model))
        assertTrue(ids(model).none { it in internalIds(alpha) })
        assertEquals(completion, texts(model).last())
    }

    @Test fun V04_internalRowsThatArriveWhileOlderHistoryLoadsDoNotShowOrDuplicate() {
        val alpha = titled("Synthetic alpha", "alpha")
        seed(alpha, 120, "m")
        val model = opened(alpha)
        val oldestShown = ids(model).min()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.messagesHook = { session, offset, _ ->
            if (session == alpha && offset > 0) {
                arrived.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
            0
        }
        assertTrue(scrollUpUntil(60) { arrived.count == 0L })
        repeat(5) { add(alpha, "user", completion, "internal_notification") }
        var lastAnswer = 0L
        repeat(3) { lastAnswer = add(alpha, "assistant", "late answer $it", null) }
        release.countDown()
        waitFor("the older page arrived") { ids(model).size > 50 }
        pump()
        val all = ids(model)
        assertEquals("unique ids", all.distinct(), all)
        assertEquals("ascending", all.sorted(), all)
        assertTrue("older rows were added", all.min() < oldestShown)
        assertTrue("no internal row is shown", all.none { it in internalIds(alpha) })
        fake.messagesHook = null
        rule.onNodeWithTag("back_button").performClick()
        waitFor("the list") { exists("conversation_row_$alpha") }
        openCard(model, alpha)
        waitFor("the newest arrivals are merged in") { ids(model).contains(lastAnswer) }
        val merged = ids(model)
        assertEquals("unique ids after the merge", merged.distinct(), merged)
        assertEquals("ascending after the merge", merged.sorted(), merged)
        assertTrue("no internal row is shown after the merge", merged.none { it in internalIds(alpha) })
        assertTrue("older rows are kept", merged.min() < oldestShown)
    }
}
