package com.rumi.hermesvoice.phone

import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.rumi.hermesvoice.core.sessions.AppSources
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.robolectric.Robolectric

/**
 * SCRATCH HARNESS (never packaged). Shared by the Phone navigation, creation, font-size, swipe and older-history tests: the real
 * MainActivity, PhoneViewModel, PhoneApp wiring and repository against the Hermes dashboard contract double. Simulated: the Data
 * Layer (mockk), the keystore, touch input (Compose test gestures) and window insets; no device and no real network. Synthetic data only.
 */
open class PhoneNavRoboBase : PhoneRoboBase() {
    val rule = createEmptyComposeRule()
    @get:Rule val cleanupThenCompose: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(ComposeDispatcherCleanup()).around(rule)

    @Before fun navSetUp() = baseSetUp()
    @After fun navTearDown() {
        fake.messagesHook = null
        fake.messagesOffsetOverride = null
        fake.listPageHook = null
        fake.archiveHook = null
        baseTearDown()
    }

    override fun pump() { idle(50); rule.waitForIdle() }

    fun showing(intent: Intent? = null): PhoneViewModel {
        controller = Robolectric.buildActivity(MainActivity::class.java, intent ?: Intent(app, MainActivity::class.java)).setup()
        idle(300)
        settle()
        return ViewModelProvider(controller!!.get())[PhoneViewModel::class.java]
    }

    fun titled(title: String, alias: String, description: String = "$alias things"): String {
        val owned = runBlocking { app.wiring().core.sessions.createConversation(title, alias, description) }
        fake.scripts[owned.storedSessionId] = { emptyList() }
        return owned.storedSessionId
    }

    fun marked(id: String, title: String = "Topic $id") {
        fake.addForeignRow(id, "discord", title = title)
        fake.setCreatedSource(id, AppSources.CONVERSATION)
    }

    fun shown(model: PhoneViewModel) = model.state.value.conversations.map { it.stored.id }

    fun tagIs(tag: String) = SemanticsMatcher("tag == $tag") { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    fun count(tag: String) = rule.onAllNodes(tagIs(tag)).fetchSemanticsNodes().size

    fun exists(tag: String) = count(tag) > 0

    fun row(id: String) = rule.onNodeWithTag("conversation_row_$id")

    fun showWith(vararg ids: String): PhoneViewModel {
        val model = showing()
        assertTrue("listed ${ids.toList()}", await(20_000) { shown(model).toSet() == ids.toSet() && ids.all { exists("conversation_row_$it") } })
        return model
    }

    fun bounds(tag: String): DpRect = rule.onNodeWithTag(tag).getBoundsInRoot()

    fun rootBounds(): DpRect = rule.onRoot().getBoundsInRoot()

    fun waitFor(what: String, timeoutMs: Long = 20_000, done: () -> Boolean) = assertTrue(what, await(timeoutMs, done))

    fun openCard(model: PhoneViewModel, id: String) {
        row(id).performClick()
        waitFor("$id opens") { model.state.value.selected?.storedSessionId == id && exists("chat_list") }
    }

    private val presenceOpened = java.util.concurrent.atomic.AtomicInteger()
    private val presenceConfirmed = java.util.concurrent.atomic.AtomicInteger()
    private var presenceObserved = false

    /**
     * Counts, without altering them, the real presence transitions (ConversationPresence.onOpened, synchronous) and the confirmation
     * reads they start (ReplyAlerts.confirmOpened, asynchronous: a second newest-page read after the visible open).
     */
    private fun observePresence() {
        if (presenceObserved) return
        presenceObserved = true
        val innerOpened = app.presence.onOpened
        app.presence.onOpened = { id -> presenceOpened.incrementAndGet(); innerOpened(id) }
        val innerConfirm = app.replyAlerts.confirmOpened!!
        app.replyAlerts.confirmOpened = { id -> try { innerConfirm(id) } finally { presenceConfirmed.incrementAndGet() } }
    }

    /** [openCard], then until the first page has landed and every confirmation read started by the visible open has finished. */
    fun openSettled(model: PhoneViewModel, id: String) {
        observePresence()
        openCard(model, id)
        waitFor("the first page landed for $id") { !model.state.value.opening }
        waitFor("the visible-open confirmation read finished (opened=${presenceOpened.get()} confirmed=${presenceConfirmed.get()})") {
            presenceOpened.get() == presenceConfirmed.get()
        }
    }

    fun systemBack() {
        controller!!.get().onBackPressedDispatcher.onBackPressed()
        settle()
    }

    fun textsUnder(node: SemanticsNode, out: MutableList<String> = mutableListOf()): List<String> {
        node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
        node.children.forEach { textsUnder(it, out) }
        return out
    }

    fun textLayout(tag: String): TextLayoutResult {
        val node = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val out = mutableListOf<TextLayoutResult>()
        rule.runOnUiThread { node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(out) }
        return out.first()
    }

    /** Appends [count] rows to a stored conversation: odd ids from the user, even ids from the assistant, text "<prefix>-<id>". */
    fun seed(stored: String, count: Int, prefix: String = "m") {
        val start = fake.rows.getValue(stored).messages.size
        for (i in 1..count) {
            val id = start + i
            fake.injectRow(stored, if (id % 2 == 1) "user" else "assistant", "$prefix-$id")
        }
    }

    fun messageRequests(stored: String) = fake.restLog.toList().filter { it.startsWith("GET /api/sessions/$stored/messages?") }

    fun offsetOf(request: String) = Regex("offset=(\\d+)").find(request)?.groupValues?.get(1)?.toInt() ?: 0

    /** The offsets of every history request after the first page: the "older" requests, in order. */
    fun olderOffsets(stored: String) = messageRequests(stored).map(::offsetOf).filter { it > 0 }

    /** One real upward scroll gesture (a finger drag downward) on the chat list. */
    fun scrollUp() = rule.onNodeWithTag("chat_list").performTouchInput { swipeDown(durationMillis = 250) }

    fun scrollUpUntil(max: Int = 40, done: () -> Boolean): Boolean {
        repeat(max) {
            if (done()) return true
            scrollUp()
            pump()
        }
        return done()
    }

    /** A drag on [tag]: starts at [startX] dp inside it, travels [dx]/[dy] dp, and ends with up (or cancel). */
    fun drag(tag: String, startX: Float, dx: Float, dy: Float = 0f, startYFraction: Float = 0.5f, steps: Int = 10,
        holdMs: Long = 0, cancel: Boolean = false) = rule.onNodeWithTag(tag).performTouchInput {
        val x0 = startX.dp.toPx()
        val y0 = height * startYFraction
        down(Offset(x0, y0))
        if (holdMs > 0) advanceEventTime(holdMs)
        for (i in 1..steps) moveTo(Offset(x0 + dx.dp.toPx() * i / steps, y0 + dy.dp.toPx() * i / steps))
        if (cancel) cancel() else up()
    }

    fun assertEqualsDp(message: String, expected: Float, actual: Float, tolerance: Float = 0.6f) =
        assertTrue("$message: expected $expected got $actual", kotlin.math.abs(expected - actual) <= tolerance)
}
