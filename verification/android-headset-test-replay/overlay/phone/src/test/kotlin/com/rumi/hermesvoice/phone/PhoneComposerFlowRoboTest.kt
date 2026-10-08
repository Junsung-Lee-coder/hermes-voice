package com.rumi.hermesvoice.phone

import android.content.ActivityNotFoundException
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
import com.rumi.hermesvoice.core.attachments.AttachmentRefs
import com.rumi.hermesvoice.core.attachments.PreviewKind
import com.rumi.hermesvoice.core.auth.HermesBearerSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.File
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * SCRATCH HARNESS (never packaged). The new composer, failed-send, attachment-viewer and link paths through the REAL MainActivity,
 * PhoneViewModel and PhoneApp wiring against the Hermes dashboard contract double. Needs the feature, so it has no baseline run.
 * No real navigation, no real handler, no device: intents are captured, never started.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class PhoneComposerFlowRoboTest {
    private lateinit var app: PhoneApp
    private lateinit var fake: FakeHermesDashboard
    private var controller: ActivityController<MainActivity>? = null
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)

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

    @Test fun F01_aSendRefusedBeforeAdmissionKeepsTheDraft() {
        val model = open(conversation("work"))
        model.setDraft("   ")
        model.send()
        assertEquals("   ", model.state.value.draft)
        settle()
        assertTrue(prompts().isEmpty())
    }

    @Test fun F02_aFailedSendIsVisibleKeepsItsContentAndNeverOverwritesANewerDraft_andRetryDeliversIt() {
        val work = conversation("work")
        fake.turnBehavior = { "session_limit" }
        val model = open(work)
        model.setDraft("lost words")
        model.send()
        model.setDraft("newer words")
        assertTrue("the failure is kept", await { model.state.value.failedSends.size == 1 })
        assertEquals("the newer draft is untouched", "newer words", model.state.value.draft)
        val failed = model.state.value.failedSends.single()
        assertEquals("lost words", failed.send.text)
        assertEquals(work, failed.send.sessionId)
        assertEquals(0, model.state.value.sendsInFlight)
        fake.turnBehavior = { "start_first" }
        model.retryFailedSend(failed.send.id)
        assertTrue("retry reached Hermes with the kept text", await { prompts().lastOrNull() == "lost words" })
        assertTrue(await { model.state.value.failedSends.isEmpty() })
        assertEquals("retry never touches the composer", "newer words", model.state.value.draft)
    }

    @Test fun F03_dismissingAFailedSendDropsItAndKeepsTheDraft() {
        val model = open(conversation("work"))
        fake.turnBehavior = { "session_limit" }
        model.setDraft("x")
        model.send()
        assertTrue(await { model.state.value.failedSends.size == 1 })
        model.setDraft("y")
        model.dismissFailedSend(model.state.value.failedSends.single().send.id)
        assertTrue(model.state.value.failedSends.isEmpty())
        assertEquals("y", model.state.value.draft)
    }

    @Test fun F04_aStoredImageOpensInTheViewerThroughTheAuthenticatedClient_andCloseEmptiesTheCache() {
        fake.storedFiles["/img/a.png"] = "image/png" to png
        val model = open(conversation("work"))
        model.openAttachment(AttachmentRefs.extract("@image:/img/a.png").single())
        assertTrue("ready", await { model.state.value.viewer?.phase == ViewerPhase.READY })
        val viewed = model.state.value.viewer!!.file!!
        assertEquals(PreviewKind.IMAGE, viewed.kind)
        assertEquals("image/png", viewed.mimeType)
        assertTrue(ViewedFiles.shareable(app, viewed.file))
        assertEquals("Bearer ${fake.accessToken}", fake.storedFileRequests.single().second)
        model.closeViewer()
        assertNull(model.state.value.viewer)
        assertTrue("cache emptied", ViewedFiles.dir(app).listFiles().orEmpty().isEmpty())
    }

    @Test fun F05_aMissingFileShowsAVisibleNonBreakingFailureAndASecondOpenReplacesTheFirst() {
        fake.storedFiles["attachments/n.txt"] = "text/plain" to "hello".toByteArray()
        val model = open(conversation("work"))
        model.openAttachment(AttachmentRefs.extract("@file:attachments/gone.txt").single())
        assertTrue(await { model.state.value.viewer?.phase == ViewerPhase.FAILED })
        assertTrue(model.state.value.viewer!!.error!!.contains("no longer has"))
        model.openAttachment(AttachmentRefs.extract("@file:attachments/n.txt").single())
        assertTrue(await { model.state.value.viewer?.phase == ViewerPhase.READY })
        assertEquals(PreviewKind.TEXT, model.state.value.viewer!!.file!!.kind)
        assertEquals("hello", model.state.value.viewer!!.file!!.text)
    }

    @Test fun F06_aLocalAttachmentIsPreviewedFromItsOwnBytesWithoutTheNetwork() {
        val model = open(conversation("work"))
        val file = File(app.cacheDir, "pick.png").also { it.writeBytes(png) }
        model.addAttachment(android.net.Uri.fromFile(file))
        assertTrue("attached", await { model.state.value.attachments.size == 1 })
        model.previewAttachment(0)
        assertTrue(await { model.state.value.viewer?.phase == ViewerPhase.READY })
        assertEquals(PreviewKind.IMAGE, model.state.value.viewer!!.file!!.kind)
        assertTrue(fake.storedFileRequests.isEmpty())
    }

    @Test fun L01_onlyValidatedHttpLinksBecomeViewIntents_withNoExtrasAndNoCredentials() {
        val ok = LinkOpener.intentFor("https://example.com/a?b=1")!!
        assertEquals(Intent.ACTION_VIEW, ok.action)
        assertEquals("https://example.com/a?b=1", ok.dataString)
        assertTrue(ok.categories.contains(Intent.CATEGORY_BROWSABLE))
        assertNull(ok.extras)
        for (bad in listOf("javascript:alert(1)", "intent://x#Intent;end", "file:///etc/passwd", "data:text/html,x", "https://user:pw@example.com/", "https://", "")) {
            assertNull(bad, LinkOpener.intentFor(bad))
        }
    }

    @Test fun L02_openingReportsNoHandlerAndNeverThrows_andHandsOverNothingElse() {
        var started: Intent? = null
        assertNull(LinkOpener.open(app, "https://example.com/") { started = it })
        assertEquals("https://example.com/", started!!.dataString)
        assertTrue("an application context needs a new task", started!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(LinkOpener.NO_HANDLER, LinkOpener.open(app, "https://example.com/") { throw ActivityNotFoundException() })
        assertEquals(LinkOpener.NOT_OPENABLE, LinkOpener.open(app, "javascript:alert(1)") { throw AssertionError("must not start") })
    }

    @Test fun V01_viewedFilesStayInsideTheirFolderAndOpenWithIsAScopedContentGrant() {
        val written = ViewedFiles.write(app, "../../evil name.png", png)
        assertEquals(ViewedFiles.dir(app).canonicalFile, written.canonicalFile.parentFile)
        assertTrue(ViewedFiles.shareable(app, written))
        assertFalse(ViewedFiles.shareable(app, File(app.cacheDir, "diag/x.txt")))
        assertFalse(ViewedFiles.shareable(app, File(ViewedFiles.dir(app), "../escape.txt")))
        val viewed = ViewedAttachment("evil name.png", "image/png", PreviewKind.IMAGE, png.size, null, written, png)
        // The real FileProvider cannot resolve the cache root under Robolectric on the Windows host (NOT_PROVEN here); the xml contract is asserted in DiagnosticsManifestTest.
        val chooser = ViewedFiles.openWithIntent(app, viewed) { f -> android.net.Uri.parse("content://${app.packageName}.diagshare/viewed/${f.name}") }!!
        val view = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("content", view.data!!.scheme)
        assertEquals("${app.packageName}.diagshare", view.data!!.authority)
        assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, view.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        ViewedFiles.clear(app)
        assertTrue(ViewedFiles.dir(app).listFiles().orEmpty().isEmpty())
        assertNull("a file outside the folder is never shared", ViewedFiles.openWithIntent(app, ViewedAttachment("x", "text/plain", PreviewKind.TEXT, 1, "x", File(app.cacheDir, "x.txt"), byteArrayOf(1))) { throw AssertionError("must not mint a uri") })
    }

    @Test fun V02_imagesAreDecodedAtABoundedSize() {
        assertEquals(1, BoundedImage.sampleSize(1000, 800))
        assertEquals(1, BoundedImage.sampleSize(2048, 100))
        assertEquals(2, BoundedImage.sampleSize(4000, 100))
        assertEquals(16, BoundedImage.sampleSize(30000, 20000))
        assertNotNull(BoundedImage.MAX_DIMENSION)
    }
}
