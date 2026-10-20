package com.rumi.hermesvoice.phone

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.FakeHermesDashboard
import com.rumi.hermesvoice.core.auth.HermesBearerSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Robolectric empties the main looper between tests, but Compose's process-wide AndroidUiDispatcher keeps its "dispatch already
 * posted" flags, so after the first test of a JVM no composition effect (and no Compose idle check) ever runs again. This clears
 * only those two flags and the global-snapshot "apply notification already sent" flag; the queued work is kept and runs on the next dispatch.
 */
object ComposeDispatcherIsolation {
    fun reset() {
        val companion = Class.forName("androidx.compose.ui.platform.AndroidUiDispatcher").getDeclaredField("Companion").also { it.isAccessible = true }.get(null)
        val context = companion.javaClass.getMethod("getMain").invoke(companion) as kotlin.coroutines.CoroutineContext
        val dispatcher = context[kotlin.coroutines.ContinuationInterceptor]!!
        for (name in listOf("scheduledTrampolineDispatch", "scheduledFrameDispatch")) {
            dispatcher.javaClass.getDeclaredField(name).also { it.isAccessible = true }.setBoolean(dispatcher, false)
        }
        val manager = Class.forName("androidx.compose.ui.platform.GlobalSnapshotManager")
        (manager.getDeclaredField("sent").also { it.isAccessible = true }.get(null) as java.util.concurrent.atomic.AtomicBoolean).set(false)
    }

    /** Runs what is already posted, then clears the flags, so a finished test never leaves the shared dispatcher "scheduled" for the next class. */
    fun drainAndReset() {
        runCatching { shadowOf(Looper.getMainLooper()).idle() }
        reset()
    }
}

/** Outermost rule: runs after the compose rule has closed, i.e. at the very end of a test. */
class ComposeDispatcherCleanup : org.junit.rules.ExternalResource() {
    override fun after() = ComposeDispatcherIsolation.drainAndReset()
}

/**
 * SCRATCH HARNESS (never packaged). The real PhoneApp wiring, MainActivity and PhoneViewModel against the Hermes dashboard contract
 * double. Simulated: the Data Layer (mockk) and the keystore (in-memory AES). No device, UI automation or real notification service.
 */

/** Legacy viewer/auth tests now supply the real source tuple required by the attachment UI.
 * Keep their original HTTP/materialization assertions; this is test-fixture data, not authority. */
internal fun openStoredAttachmentFixture(fake: com.rumi.hermesvoice.core.FakeHermesDashboard, model: PhoneViewModel,
    sessionId: String, ref: com.rumi.hermesvoice.core.attachments.AttachmentRef) {
    val marker=when(ref.kind) {
        com.rumi.hermesvoice.core.attachments.RefKind.IMAGE -> "@image:"
        com.rumi.hermesvoice.core.attachments.RefKind.FILE -> "@file:"
        com.rumi.hermesvoice.core.attachments.RefKind.MEDIA -> "MEDIA:"
    }
    val text=marker+ref.path
    fake.injectRow(sessionId,"assistant",text)
    val row=fake.rows.getValue(sessionId).messages.last()
    val opener=model.javaClass.declaredMethods.firstOrNull { it.name.startsWith("attachmentOpener") && it.parameterCount==1 }
    if(opener==null) model.openAttachment(ref) else {
        @Suppress("UNCHECKED_CAST")
        val action=opener.invoke(model,sessionId) as (com.rumi.hermesvoice.core.net.HistoryMessage,com.rumi.hermesvoice.core.attachments.AttachmentRef)->Unit
        action(com.rumi.hermesvoice.core.net.HistoryMessage(row.getLong("id"),"assistant",text,0.0),ref)
    }
}

open class PhoneRoboBase {
    lateinit var app: PhoneApp
    lateinit var fake: FakeHermesDashboard
    var controller: ActivityController<MainActivity>? = null

    private fun stubWearable() {
        mockkStatic(Wearable::class)
        val info = mockk<CapabilityInfo>()
        every { info.nodes } returns emptySet()
        every { info.name } returns "hermes_voice_watch"
        val capability = mockk<CapabilityClient>()
        every { capability.getCapability(any(), any()) } returns Tasks.forResult(info)
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } returns Tasks.forResult(1)
        val channels = mockk<ChannelClient>(relaxed = true)
        val data = mockk<DataClient>(relaxed = true)
        every { Wearable.getCapabilityClient(any<Context>()) } returns capability
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        every { Wearable.getChannelClient(any<Context>()) } returns channels
        every { Wearable.getDataClient(any<Context>()) } returns data
        every { Wearable.getDataClient(any<android.app.Activity>()) } returns data
    }

    fun idle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** One polling step of [await]/[settle]; a test that owns a Compose rule also lets the rule's own frame clock run. */
    open fun pump() = idle(50)

    fun await(timeoutMs: Long = 8_000, done: () -> Boolean): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            pump()
            if (done()) return true
            if (System.nanoTime() > end) return false
            Thread.sleep(25)
        }
    }

    fun settle() = repeat(8) { pump(); Thread.sleep(25) }

    fun baseSetUp(authenticated: Boolean = true) {
        ComposeDispatcherIsolation.reset()
        ViewModelProvider.AndroidViewModelFactory::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == ViewModelProvider.AndroidViewModelFactory::class.java }
            .forEach { it.isAccessible = true; it.set(null, null) }
        FakeAndroidKeyStore.install()
        app = RuntimeEnvironment.getApplication() as PhoneApp
        stubWearable()
        fake = FakeHermesDashboard()
        app.settings.dashboardUrl = fake.baseUrl
        if (authenticated) app.tokens.save(HermesBearerSession(fake.accessToken, fake.refreshToken, null, "basic", "jun"))
    }

    fun baseTearDown() {
        // End the disposable Application only after all assertions. Recovery must outlive
        // Activity recreation during a test, but must not poll another test's fake server.
        runCatching { controller?.pause()?.stop()?.destroy() }
        runCatching { app.appScope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
        runCatching { fake.close() }
        unmockkAll()
        ComposeDispatcherIsolation.drainAndReset()
    }

    /** A conversation whose replies are withheld until the test pushes them (fake.pushLaterTurn(..., withStart = false)). */
    fun conversation(alias: String): String {
        val owned = runBlocking { app.wiring().core.sessions.createConversation(alias.replaceFirstChar { it.uppercase() }, alias, "$alias things") }
        fake.scripts[owned.storedSessionId] = { emptyList() }
        return owned.storedSessionId
    }

    fun replyTap(session: String) = Intent(app, MainActivity::class.java).setAction("com.rumi.hermesvoice.action.OPEN_REPLY")
        .putExtra("hv_reply_session", session).setData(android.net.Uri.parse("hermesvoice://reply/$session?r=chat-x#reply"))

    fun open(session: String): PhoneViewModel {
        controller = Robolectric.buildActivity(MainActivity::class.java, replyTap(session)).create()
        idle(300)
        val model = ViewModelProvider(controller!!.get())[PhoneViewModel::class.java]
        assertTrue("the conversation is selected", await { model.state.value.selected?.storedSessionId == session })
        return model
    }

    fun openOwned(model: PhoneViewModel, session: String) {
        val owned = app.wiring().core.sessions.activeConversation(session)!!
        model.open(owned)
        assertTrue("selected", await { model.state.value.selected?.storedSessionId == session })
    }

    fun prompts() = fake.rawPrompts.map { it.second }
    fun tool(name: String, context: String? = null) =
        "tool.start" to org.json.JSONObject().put("tool_id", "t-$name").put("name", name).apply { if (context != null) put("context", context) }
}
