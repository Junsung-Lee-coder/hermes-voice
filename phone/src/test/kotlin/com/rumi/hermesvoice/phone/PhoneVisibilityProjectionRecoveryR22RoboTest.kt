package com.rumi.hermesvoice.phone

import com.rumi.hermesvoice.core.net.HistoryPage
import com.rumi.hermesvoice.core.sessions.ReceivedConversations
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** Real asynchronous projection/receive pipeline with only the durable commit refused. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1280dp")
class PhoneVisibilityProjectionRecoveryR22RoboTest : PhoneNavRoboBase() {
    private fun read(id: String): HistoryPage = runBlocking { app.wiring().core.sessions.history(id) }
    private fun idle(id: String): Boolean {
        val observations = ReflectionHelpers.getField<Map<String, Job>>(app.finalRecovery, "observations")
        return observations[id]?.isCompleted != false
    }

    @Test fun P01_aFailedHiddenProjectionCannotOverrideALaterDurablyVisibleGenuineArrival() {
        val id = titled("Projection recovery", "projection-recovery")
        val rows = fake.rows.getValue(id).messages
        synchronized(rows) { rows.clear() }
        fake.injectFinalRow(id, "genuine visible answer", 100.0, "projection-uid")
        runBlocking { app.finalRecovery.observedNewest(read(id), app.bodyScope()) }
        assertTrue(app.received.unread(id))
        val local = app.localStore
        val before = local.getString(ReceivedConversations.KEY)!!
        val receivedAt = app.received.receivedAt(id)
        val fail = AtomicBoolean(false)
        val attempts = AtomicInteger()
        mockkObject(local)
        try {
            every { local.commitString(ReceivedConversations.KEY, any()) } answers {
                if (fail.get()) { attempts.incrementAndGet(); false } else callOriginal()
            }
            synchronized(rows) { rows.single().put("display_kind", "hidden") }
            fail.set(true)
            app.finalRecovery.observeNewestAsync(read(id), app.bodyScope())
            waitFor("the failed hidden observation actually finished") {
                attempts.get() > 0 && app.finalRecovery.notice.value?.contains("Receive state not saved") == true && idle(id)
            }
            assertEquals("a refused commit cannot rewrite durable receipt authority", before, local.getString(ReceivedConversations.KEY))
            assertTrue(ReceivedConversations(local, app::bodyScope).unread(id))

            fail.set(false)
            synchronized(rows) { rows.single().remove("display_kind") }
            val revision = app.finalRecovery.revision.value
            app.finalRecovery.observeNewestAsync(read(id), app.bodyScope())
            waitFor("the later authoritative visible observation committed and settled") {
                app.finalRecovery.revision.value > revision && idle(id)
            }
            assertTrue("an obsolete failed hidden projection cannot outlive a successful visible observation", app.received.unread(id))
            assertTrue(ReceivedConversations(local, app::bodyScope).unread(id))
            assertEquals(receivedAt, app.received.receivedAt(id), 0.0)
            val root = JSONObject(local.getString(ReceivedConversations.KEY)!!)
            val saved = root.getJSONObject("sessions").getJSONObject(id).getJSONObject("rows")
                .getJSONObject(ReceivedConversations.identity(app.bodyScope()!!, id, 1, "projection-uid"))
            assertFalse(saved.getBoolean("read"))
            assertFalse(saved.getBoolean("baseline"))
            assertFalse(saved.getBoolean("phone"))
            assertTrue(app.received.pending().any { it.optString("uid") == "projection-uid" })
            assertTrue(fake.prompts.isEmpty())
        } finally { fail.set(false); unmockkAll() }
    }
}
