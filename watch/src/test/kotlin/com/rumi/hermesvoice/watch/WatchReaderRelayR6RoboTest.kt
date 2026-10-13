package com.rumi.hermesvoice.watch

import android.content.Context
import android.net.Uri
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItemBuffer
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.watchlink.LoadStatus
import com.rumi.hermesvoice.core.watchlink.ReaderSurface
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchNavigation
import com.rumi.hermesvoice.core.watchlink.WatchNavigationGuard.Verdict
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.time.Duration
import org.json.JSONObject
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * R6 relay fixture through the REAL WatchApp (settings
 * replica, navigation guard, reader state and request dispatch) and the REAL WatchListenerService NAVIGATE adapter. Platform inputs
 * (Data Layer clients) are simulated; no device, emulator or live Phone is involved.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchReaderRelayR6RoboTest {
    private lateinit var app: WatchApp
    private lateinit var messages: MessageClient
    private var revision = 0L
    private val requests = java.util.concurrent.ConcurrentLinkedQueue<com.rumi.hermesvoice.core.watchlink.ReaderRequest>()

    private val phone = "phone-node"
    private val work = "20260101_120000_workwork"
    private val other = "20260101_130000_otherone"

    private fun stubWearable() {
        mockkStatic(Wearable::class)
        val node = mockk<Node>()
        every { node.id } returns phone
        every { node.isNearby } returns true
        every { node.displayName } returns "phone"
        val info = mockk<CapabilityInfo>()
        every { info.nodes } returns setOf(node)
        every { info.name } returns WatchLinkPaths.CAPABILITY_PHONE
        val capability = mockk<CapabilityClient>()
        every { capability.getCapability(any(), any()) } returns Tasks.forResult(info)
        messages = mockk()
        every { messages.sendMessage(any(), any(), any()) } answers {
            if (secondArg<String>() == WatchLinkPaths.READER_REQUEST) com.rumi.hermesvoice.core.watchlink.ReaderRequest.decode(thirdArg<ByteArray>())?.let(requests::add)
            Tasks.forResult(1)
        }
        val channels = mockk<ChannelClient>()
        val data = mockk<DataClient>()
        every { data.getDataItems(any<Uri>()) } returns Tasks.forException<DataItemBuffer>(IllegalStateException("harness: no data item"))
        every { Wearable.getCapabilityClient(any<Context>()) } returns capability
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        every { Wearable.getChannelClient(any<Context>()) } returns channels
        every { Wearable.getDataClient(any<Context>()) } returns data
    }

    private fun idle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun snapshot(autoNavigate: Boolean, rev: Long = ++revision): String =
        WatchSettings(watchAutoNavigateToRouted = autoNavigate, revision = rev).toJson()

    private fun option(on: Boolean) {
        app.applySettings(snapshot(on))
        idle()
    }

    private fun startTurn(): String {
        app.discard("harness: the previous recording ended")
        val turnId = app.newTurn(TurnTrigger.PUSH_TO_TALK)
        assertNotNull("the Watch started a request", turnId)
        app.navigationGuard.onUploadNode(turnId!!, phone)
        return turnId
    }

    private fun navigate(turnId: String, session: String, generation: Long, created: Boolean = false, from: String = phone): Verdict? {
        val verdict = app.onNavigation(from, WatchNavigation(turnId, session, generation, created).encode())
        idle()
        return verdict
    }

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as WatchApp
        stubWearable()
    }

    @After
    fun tearDown() = unmockkAll()

    private fun deliver(request: com.rumi.hermesvoice.core.watchlink.ReaderRequest, source: String = phone, text: String = "latest") {
        val bytes = com.rumi.hermesvoice.core.watchlink.ReaderResponse(request.reqId, request.kind, true,
            sessionId = request.sessionId, messages = listOf(com.rumi.hermesvoice.core.watchlink.ReaderMessageRow(1, "assistant", text, false)),
            nextOffset = 1).encode()
        val service = Robolectric.buildService(WatchListenerService::class.java).create().get()
        val event = mockk<MessageEvent>()
        every { event.path } returns WatchLinkPaths.READER_RESPONSE
        every { event.sourceNodeId } returns source
        every { event.data } returns bytes
        service.onMessageReceived(event); idle()
    }

    @Test fun actualListenerHydratesTheSelectedReaderAndRefreshReplacesAGrowingFinal() {
        app.selectSession(work); idle()
        val first = requests.last()
        deliver(first)
        assertEquals("latest", app.reader.value.selectedHistory!!.messages.single().text)
        app.refreshSelected(); idle()
        deliver(requests.last(), text = "**final answer**")
        assertEquals("**final answer**", app.reader.value.selectedHistory!!.messages.single().text)
        assertEquals(1, app.reader.value.selectedHistory!!.messages.size)
        assertEquals(work, app.reader.value.selectedSessionId)
    }

    @Test fun wrongNodeDoesNotConsumeTheLegitimatePendingReaderResponse() {
        app.selectSession(work); idle()
        val request = requests.last()
        deliver(request, source = "unrelated-node")
        assertEquals(LoadStatus.LOADING, app.reader.value.selectedHistory!!.status)
        deliver(request)
        assertEquals(LoadStatus.READY, app.reader.value.selectedHistory!!.status)
        assertEquals("latest", app.reader.value.selectedHistory!!.messages.single().text)
    }

    @Test fun wrongPageIdentityFromThePhoneDoesNotStrandThePendingLatestLoad() {
        app.selectSession(work); idle(); val request = requests.last()
        val wrong = com.rumi.hermesvoice.core.watchlink.ReaderResponse(request.reqId, request.kind, true,
            sessionId = work, offset = 20, nextOffset = 40).encode()
        app.onReaderResponse(phone, wrong)
        assertEquals(LoadStatus.LOADING, app.reader.value.selectedHistory!!.status)
        deliver(request)
        assertEquals(LoadStatus.READY, app.reader.value.selectedHistory!!.status)
    }

    @Test fun lateReplyForEarlierSelectionCannotReplaceTheNewSession() {
        app.selectSession(work); idle(); val first = requests.last()
        app.selectSession(other); idle(); val second = requests.last()
        deliver(first)
        assertEquals(other, app.reader.value.selectedSessionId)
        assertTrue(app.reader.value.selectedHistory!!.messages.isEmpty())
        deliver(second, text = "other latest")
        assertEquals("other latest", app.reader.value.selectedHistory!!.messages.single().text)
    }
}
