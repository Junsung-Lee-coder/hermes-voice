package com.rumi.hermesvoice.phone

import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.MockResponse
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
 * SCRATCH HARNESS (never packaged). R11: the Conversations list and an opened chat's history are read again, inside one budget of
 * the first try and at most two retries, when the dashboard read fails for a transient reason. The real MainActivity, PhoneViewModel,
 * PhoneApp wiring, repository and HTTP client against the Hermes dashboard contract double. Simulated: the Data Layer (mockk), the
 * keystore and touch input; no device and no real network. Synthetic content only.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1280dp")
class PhoneReadRetryR11RoboTest : PhoneNavRoboBase() {

    private fun answer(status: Int) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json")
        .setBody("""{"detail":"synthetic read fault"}""")

    private fun failFirst(kind: String, times: Int, status: Int = 503) {
        val left = AtomicInteger(times)
        fake.readFault = { k, _ -> if (k == kind && left.getAndDecrement() > 0) answer(status) else null }
    }

    private fun arrivals(kind: String) = fake.readArrivals.toList().filter { it.first == kind }

    private fun errorShown(model: PhoneViewModel) = model.state.value.status.startsWith("Couldn't do that")

    @Test fun R01_aTransientListFailureIsReadAgainAndTheListAppearsWithoutAnError() {
        val alpha = titled("Synthetic alpha", "alpha")
        failFirst("list", 1)
        val seen = mutableListOf<Pair<Int, String>>()
        controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
        idle(300)
        val model = androidx.lifecycle.ViewModelProvider(controller!!.get())[PhoneViewModel::class.java]
        waitFor("the list appears after the retry") {
            seen += arrivals("list").size to model.state.value.status
            shown(model) == listOf(alpha)
        }
        assertTrue("a second read of the list: ${arrivals("list")}", arrivals("list").size >= 2)
        assertTrue("no error was shown while the retry was pending: $seen", seen.none { it.second.startsWith("Couldn't do that") })
    }

    @Test fun R02_aListThatKeepsFailingIsTriedThreeTimesAndKeepsWhatIsShownWithOneTruthfulError() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = showWith(alpha)
        val before = arrivals("list").size
        fake.readFault = { kind, _ -> if (kind == "list") answer(503) else null }
        val seen = mutableListOf<Pair<Int, String>>()
        model.refresh()
        waitFor("the error is reported after the budget", 40_000) {
            val tries = arrivals("list").size - before
            seen += tries to model.state.value.status
            tries >= 3 && errorShown(model)
        }
        idle(3_500)
        rule.waitForIdle()
        assertEquals("one first try and two retries, never a fourth", 3, arrivals("list").size - before)
        assertTrue("no error before the budget ended: $seen", seen.filter { it.first < 3 }.none { it.second.startsWith("Couldn't do that") })
        assertEquals("the rows stay", listOf(alpha), shown(model))
        assertTrue(model.state.value.status, errorShown(model))
        assertFalse("a manual refresh is still possible", model.state.value.status.contains("Reading the dashboard"))
    }

    @Test fun R03_aTerminalListRejectionIsNotRetried() {
        val alpha = titled("Synthetic alpha", "alpha")
        val model = showWith(alpha)
        val before = arrivals("list").size
        fake.readFault = { kind, _ -> if (kind == "list") answer(403) else null }
        model.refresh()
        waitFor("the error is reported") { errorShown(model) }
        idle(3_500)
        assertEquals(1, arrivals("list").size - before)
        assertEquals(listOf(alpha), shown(model))
    }

    @Test fun R04_aTransientHistoryFailureWhileOpeningAChatIsReadAgainAndTheMessagesAppear() {
        val alpha = titled("Synthetic alpha", "alpha")
        seed(alpha, 6, "m")
        val model = showWith(alpha)
        failFirst("messages", 1)
        openCard(model, alpha)
        waitFor("the chat shows its messages after the retry") { model.state.value.history.size == 6 }
        assertTrue("a second read of the page: ${arrivals("messages")}", arrivals("messages").size >= 2)
        assertFalse(model.state.value.status, errorShown(model))
    }

    @Test fun R05_aHistoryThatKeepsFailingIsTriedThreeTimesAndTheOpenChatKeepsItsMessages() {
        val alpha = titled("Synthetic alpha", "alpha")
        seed(alpha, 6, "m")
        val model = showWith(alpha)
        openCard(model, alpha)
        waitFor("the latest page") { model.state.value.history.size == 6 }
        val before = arrivals("messages").size
        fake.readFault = { kind, _ -> if (kind == "messages") answer(503) else null }
        model.openOrResume(app.wiring().core.sessions.activeConversation(alpha)!!)
        waitFor("the budget is spent", 40_000) { arrivals("messages").size - before >= 3 }
        idle(3_500)
        rule.waitForIdle()
        assertEquals(3, arrivals("messages").size - before)
        assertEquals("the messages stay", 6, model.state.value.history.size)
    }
}
