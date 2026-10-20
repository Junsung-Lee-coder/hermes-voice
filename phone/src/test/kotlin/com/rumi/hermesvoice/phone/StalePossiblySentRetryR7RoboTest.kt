package com.rumi.hermesvoice.phone

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import com.rumi.hermesvoice.core.FakeHermesDashboard
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Two real ViewModels sharing PhoneApp/TextSends. A stale screen cannot turn an already
 * possibly-sent retry into a second transport attempt. No injected FailedSend or forged state. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class StalePossiblySentRetryR7RoboTest : PhoneRoboBase() {
    private val secondStore = ViewModelStore()
    @Before fun setUp() = baseSetUp()
    @After fun tearDown() { secondStore.clear(); baseTearDown() }

    @Test fun R12_staleScreenCannotRetryTheSamePossiblySentRequestAfterAnotherScreenOwnsIt() {
        val work = conversation("work")
        val older = open(work)
        fake.turnBehavior = { "not_owned" } // definitive pre-admission refusal, no prompt row/effect
        older.setDraft("R"); older.send()
        assertTrue(await { older.state.value.failedSends.size == 1 && older.state.value.sendsInFlight == 0 })
        val id = older.state.value.failedSends.single().send.id
        assertTrue(prompts().isEmpty())
        // A second real screen becomes the application's current listener; the older projection
        // is still alive (multi-Activity/back-stack case) and retains its legitimate old failed row.
        val newer = ViewModelProvider(secondStore, ViewModelProvider.AndroidViewModelFactory(app))[PhoneViewModel::class.java]
        newer.open(app.wiring().core.sessions.activeConversation(work)!!)
        assertTrue(await { newer.state.value.selected?.storedSessionId == work && newer.state.value.failedSends.any { it.send.id == id } })
        val accepted = AtomicBoolean(false)
        fake.turnBehavior = { "async_hold" }
        fake.beforeAsyncReply = { accepted.set(true); fake.dropGateway() }
        newer.retryFailedSend(id)
        assertTrue(await(20_000) { accepted.get() && newer.state.value.sendsInFlight == 0 })
        assertTrue(newer.state.value.failedSends.none { it.send.id == id })
        assertTrue("the old projection really is stale, not an invented failure", older.state.value.failedSends.any { it.send.id == id })
        fake.pushLaterTurn(work, FakeHermesDashboard.complete("original-R-finished"))
        fake.beforeAsyncReply = null
        fake.turnBehavior = { "start_first" }
        fake.scripts[work] = { listOf(FakeHermesDashboard.complete("wrong-resubmission-answer")) }
        assertEquals(work, older.state.value.selected?.storedSessionId)
        older.setDraft("independently composed draft")
        older.retryFailedSend(id) // actual stale Retry caller, not an independently composed message
        assertTrue("either the guarded stale admission ends, or a real duplicate frame is observed", await(20_000) {
            older.state.value.sendsInFlight == 0 || prompts().size > 1
        })
        assertEquals("same-request Retry must consult application authority, not a stale screen", listOf("R"), prompts())
        assertEquals("independently composed draft", older.state.value.draft)
        assertTrue(older.state.value.failedSends.none { it.send.id == id })
        assertFalse(older.state.value.status.contains("Not sent", ignoreCase = true))
    }
}
