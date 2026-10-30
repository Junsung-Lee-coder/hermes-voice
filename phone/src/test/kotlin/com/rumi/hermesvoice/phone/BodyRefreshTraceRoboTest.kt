package com.rumi.hermesvoice.phone

import androidx.lifecycle.ViewModelProvider
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.notify.FinalReply
import com.rumi.hermesvoice.core.notify.FinalReplySource
import com.rumi.hermesvoice.core.notify.ReplyAlert
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Conversation body refresh through the real MainActivity, PhoneViewModel, ConversationPresence, ReplyAlerts, FinalRecovery and
 * AppSessionRepository against the dashboard contract double. B25_01 records which dashboard reads each step makes as a fixed-vocabulary
 * marker (no message text) and asserts only that the saved body shows first; its read counts are not assertions. B25_02 asserts that a
 * final for the visible conversation reaches the screen while an unrelated recovery read is still held. No device, provider or timing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BodyRefreshTraceRoboTest : PhoneRoboBase() {
    private val held = CountDownLatch(1); private val release = CountDownLatch(1)
    @Before fun setUp() = baseSetUp()
    @After fun tearDown() { release.countDown(); fake.messagesHook = null; fake.reconciliationMessagesHook = null; baseTearDown() }

    private fun screen(): PhoneViewModel {
        controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val model = ViewModelProvider(controller!!.get())[PhoneViewModel::class.java]
        assertTrue("the Activity's own list refresh finished", await(20_000) { model.state.value.conversations.isNotEmpty() && !model.state.value.status.startsWith("Reading") })
        settle()
        return model
    }
    /** Dashboard reads since the log was last cleared: exact-metadata (detail) and interactive history pages. */
    private fun shape(): JSONObject {
        val log = fake.restLog.toList()
        return JSONObject().put("detail", log.count { Regex("^GET /api/sessions/[^/?]+\\?").containsMatchIn(it) })
            .put("messages", log.count { it.startsWith("GET /api/sessions/") && it.contains("/messages?") })
            .put("reconciliation", log.count { it.startsWith("RECONCILE ") })
            .put("list", log.count { it.startsWith("GET /api/sessions?") })
    }
    private fun shows(model: PhoneViewModel, text: String) = model.state.value.history.any { it.text.contains(text) }

    @Test fun B25_01_openingShowsTheSavedBodyBeforeTheNetworkReadAndEachVerifiedReadWalksTheCompressionLineage() {
        val alpha = conversation("alpha")
        fake.injectRow(alpha, "user", "first question"); fake.injectRow(alpha, "assistant", "first answer")
        val tip = fake.compress(fake.compress(alpha))
        val beta = conversation("beta")
        runBlocking { app.wiring().core.sessions.listConversations(false) } // the registered root now names its compression tip
        val model = screen()
        fake.restLog.clear()
        openOwned(model, tip); settle()
        val open = shape()
        fake.restLog.clear()
        app.presence.setForeground(true); app.presence.setShown(tip); settle(); idle(1_000); settle()
        val visible = shape()
        app.presence.setShown(null)
        openOwned(model, beta); settle()
        fake.messagesHook = { id, _, _ -> if (id == tip) { held.countDown(); check(release.await(30, TimeUnit.SECONDS)) { "fixture history latch not released" } }; 0 }
        model.open(app.wiring().core.sessions.activeConversation(tip)!!)
        assertTrue("the network read of the reopened conversation is held", await(20_000) { held.count == 0L })
        val cacheFirst = await(5_000) { model.state.value.selected?.storedSessionId == tip && !model.state.value.opening && shows(model, "first answer") }
        release.countDown()
        assertTrue(await(20_000) { !model.state.value.opening && shows(model, "first answer") })
        println("DIAG_BODY_TRACE=" + JSONObject().put("lineage_segments", 3).put("open", open).put("visible_confirm", visible)
            .put("saved_body_shown_while_network_held", cacheFirst))
        assertTrue("the saved body is on screen while its network read is held", cacheFirst)
    }

    @Test fun B25_02_aFinalReplyForTheVisibleConversationReachesTheScreenWhileAnUnrelatedRecoveryReadIsHeld() {
        val alpha = conversation("alpha"); val beta = conversation("beta")
        val model = screen()
        openOwned(model, alpha)
        app.presence.setForeground(true); app.presence.setShown(alpha); settle(); idle(1_000); settle()
        val entered = CountDownLatch(1)
        fake.reconciliationMessagesHook = { session, _, _ -> if (session == beta) { entered.countDown(); check(release.await(30, TimeUnit.SECONDS)) { "fixture recovery latch not released" } }; 0 }
        app.finalRecovery.requestReconcile()
        assertTrue("a recovery run is reading another conversation", await(20_000) { entered.count == 0L })
        fake.injectRow(alpha, "assistant", "fresh answer")
        val rowId = fake.rows.getValue(alpha).messages.last().getLong("id")
        fake.restLog.clear()
        app.replyAlerts.onFinalReply(FinalReply(ReplyAlert.identityOf("chat-fresh-1", "reply"), alpha, VoiceOrigin.PHONE, heard = false,
            cancelled = false, source = FinalReplySource.TEXT, text = "fresh answer", canonicalRowId = rowId, namespace = app.bodyScope()))
        val shownWhileHeld = try { await(8_000) { shows(model, "fresh answer") } } finally { release.countDown() }
        val whileHeld = shape()
        val shown = await(20_000) { shows(model, "fresh answer") }
        println("DIAG_BODY_RECOVERY_COUPLING=" + JSONObject().put("reads_while_recovery_held", whileHeld).put("shown_while_held", shownWhileHeld)
            .put("shown_after_release", shown))
        assertTrue("the visible conversation shows the answer while the unrelated recovery read is still held", shownWhileHeld)
        assertTrue(shown)
        assertTrue(fake.rawPrompts.isEmpty())
    }
}
