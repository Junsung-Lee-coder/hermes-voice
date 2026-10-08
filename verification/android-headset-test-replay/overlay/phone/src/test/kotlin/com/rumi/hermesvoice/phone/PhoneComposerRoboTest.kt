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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). The composer's send through the REAL MainActivity, PhoneViewModel, PhoneApp wiring and
 * ChatService against the Hermes dashboard contract double. It uses only names that exist on the immutable baseline
 * (setDraft, send, state.draft), so the same file runs there for the RED proof. Simulated: the Data Layer (mockk) and the keystore.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class PhoneComposerRoboTest {
    private lateinit var app: PhoneApp
    private lateinit var fake: FakeHermesDashboard
    private var controller: ActivityController<MainActivity>? = null

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

    private fun idle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun await(timeoutMs: Long = 8_000, done: () -> Boolean): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            idle(50)
            if (done()) return true
            if (System.nanoTime() > end) return false
            Thread.sleep(25)
        }
    }

    private fun settle() = repeat(8) { idle(50); Thread.sleep(25) }

    @Before
    fun setUp() {
        ViewModelProvider.AndroidViewModelFactory::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == ViewModelProvider.AndroidViewModelFactory::class.java }
            .forEach { it.isAccessible = true; it.set(null, null) }
        FakeAndroidKeyStore.install()
        app = RuntimeEnvironment.getApplication() as PhoneApp
        stubWearable()
        fake = FakeHermesDashboard()
        app.settings.dashboardUrl = fake.baseUrl
        app.tokens.save(HermesBearerSession(fake.accessToken, fake.refreshToken, null, "basic", "jun"))
    }

    @After
    fun tearDown() {
        runCatching { controller?.destroy() }
        runCatching { fake.close() }
        unmockkAll()
    }

    private fun conversation(alias: String): String {
        val owned = runBlocking { app.wiring().core.sessions.createConversation(alias.replaceFirstChar { it.uppercase() }, alias, "$alias things") }
        fake.scripts[owned.storedSessionId] = { emptyList() }
        return owned.storedSessionId
    }

    private fun open(session: String): PhoneViewModel {
        val tap = Intent(app, MainActivity::class.java).setAction("com.rumi.hermesvoice.action.OPEN_REPLY")
            .putExtra("hv_reply_session", session).setData(android.net.Uri.parse("hermesvoice://reply/$session?r=chat-x#reply"))
        controller = Robolectric.buildActivity(MainActivity::class.java, tap).create()
        idle(300)
        val model = ViewModelProvider(controller!!.get())[PhoneViewModel::class.java]
        assertTrue("the conversation is selected", await { model.state.value.selected?.storedSessionId == session })
        return model
    }

    private fun prompts() = fake.rawPrompts.map { it.second }

    private fun submitStatuses(vararg statuses: String) {
        var n = 0
        fake.submitStatus = { statuses[minOf(n++, statuses.size - 1)] }
    }

    @Test fun C01_theDraftIsEmptyInTheSameStepAsTheTapAndTheMessageReachesHermesUnchanged() {
        val work = conversation("work")
        val model = open(work)
        model.setDraft("  hello there \n")
        model.send()
        assertEquals("cleared before any backend work ran", "", model.state.value.draft)
        assertTrue("the text reached Hermes (the repository has always trimmed the ends of a typed message)", await { prompts() == listOf("hello there") })
    }

    @Test fun C02_aSecondMessageIsSentAtOnceWhileTheFirstIsUnansweredWithNoPolicyChosenByTheApp() {
        val work = conversation("work")
        submitStatuses("streaming", "queued")
        val model = open(work)
        model.setDraft("first")
        model.send()
        assertTrue("A reached Hermes", await { prompts() == listOf("first") })
        model.setDraft("second")
        model.send()
        assertTrue("B reached Hermes while A is unanswered", await { prompts() == listOf("first", "second") })
        assertEquals("", model.state.value.draft)
        val submits = fake.rpcLog.filter { it.getString("method") == "prompt.submit" }.map { it.getJSONObject("params") }
        assertTrue("no queue/steer flag or mode from the app", submits.none { it.has("queued") || it.has("mode") || it.has("busy_input_mode") })
        fake.pushLaterTurn(work, FakeHermesDashboard.complete("a"), withStart = false)
        fake.pushLaterTurn(work, FakeHermesDashboard.complete("b"))
        settle()
    }

    @Test fun C03_textTypedAfterTheTapSurvivesTheEarlierMessagesCompletion() {
        val work = conversation("work")
        val model = open(work)
        model.setDraft("first")
        model.send()
        model.setDraft("typed after the tap")
        assertTrue("first reached Hermes", await { prompts() == listOf("first") })
        fake.pushLaterTurn(work, FakeHermesDashboard.complete("answer"), withStart = false)
        settle()
        settle()
        assertEquals("typed after the tap", model.state.value.draft)
    }
}
