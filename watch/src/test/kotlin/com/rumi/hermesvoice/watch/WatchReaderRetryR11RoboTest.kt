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
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.watchlink.LoadStatus
import com.rumi.hermesvoice.core.watchlink.ReaderError
import com.rumi.hermesvoice.core.watchlink.ReaderKind
import com.rumi.hermesvoice.core.watchlink.ReaderMessageRow
import com.rumi.hermesvoice.core.watchlink.ReaderRequest
import com.rumi.hermesvoice.core.watchlink.ReaderResponse
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * R11: the real WatchApp reader dispatch. A reader request that never left the Watch (no reachable Phone node, or the Data Layer
 * refused the message) is tried again, within one budget of the first try and at most two retries (1 s, then 2 s); a request the Phone
 * did receive is never sent twice. Data Layer clients are simulated; no device, emulator or live Phone. Synthetic content only.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchReaderRetryR11RoboTest {
    private lateinit var app: WatchApp
    private val sent = ConcurrentLinkedQueue<ReaderRequest>()
    private var failSends = 0
    private var nodeMissing = 0
    private var nodeLookups = 0

    private val phone = "phone-node"
    private val work = "20260101_120000_workwork"
    private val other = "20260101_130000_otherone"

    private fun stubWearable() {
        mockkStatic(Wearable::class)
        val node = mockk<Node>()
        every { node.id } returns phone
        every { node.isNearby } returns true
        every { node.displayName } returns "phone"
        val present = mockk<CapabilityInfo>()
        every { present.nodes } returns setOf(node)
        every { present.name } returns WatchLinkPaths.CAPABILITY_PHONE
        val absent = mockk<CapabilityInfo>()
        every { absent.nodes } returns emptySet()
        every { absent.name } returns WatchLinkPaths.CAPABILITY_PHONE
        val capability = mockk<CapabilityClient>()
        every { capability.getCapability(any(), any()) } answers {
            nodeLookups += 1
            if (nodeMissing > 0) { nodeMissing -= 1; Tasks.forResult(absent) } else Tasks.forResult(present)
        }
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } answers {
            if (secondArg<String>() == WatchLinkPaths.READER_REQUEST) {
                ReaderRequest.decode(thirdArg<ByteArray>())?.let(sent::add)
                if (failSends > 0) { failSends -= 1; return@answers Tasks.forException<Int>(com.google.android.gms.common.api.ApiException(com.google.android.gms.common.api.Status(com.google.android.gms.common.api.CommonStatusCodes.NETWORK_ERROR))) }
            }
            Tasks.forResult(1)
        }
        val data = mockk<DataClient>()
        every { data.getDataItems(any<Uri>()) } returns Tasks.forException<DataItemBuffer>(IllegalStateException("harness: no data item"))
        every { Wearable.getCapabilityClient(any<Context>()) } returns capability
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        every { Wearable.getChannelClient(any<Context>()) } returns mockk<ChannelClient>()
        every { Wearable.getDataClient(any<Context>()) } returns data
    }

    private fun idle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as WatchApp
        stubWearable()
    }

    @After fun tearDown() = unmockkAll()

    private fun history() = app.reader.value.selectedHistory!!

    private fun answer(request: ReaderRequest, ok: Boolean = true) = app.onReaderResponse(phone,
        ReaderResponse(request.reqId, request.kind, ok, error = if (ok) null else ReaderError.UNAVAILABLE, sessionId = request.sessionId,
            messages = if (ok) listOf(ReaderMessageRow(1, "assistant", "synthetic latest", false)) else emptyList(), nextOffset = if (ok) 1 else 0).encode())

    @Test fun W1_aMessageTheDataLayerRefusedIsSentAgainAfterOneSecondAndThePageIsShown() {
        failSends = 1
        app.selectSession(work); idle()
        assertEquals("not given up on the first refusal", LoadStatus.LOADING, history().status)
        idle(1_200)
        assertEquals(2, sent.size)
        assertEquals("the same request", 1, sent.map { it.reqId }.toSet().size)
        answer(sent.last()); idle()
        assertEquals(LoadStatus.READY, history().status)
        assertEquals("synthetic latest", history().messages.single().text)
    }

    @Test fun W2_aMessageThatKeepsBeingRefusedIsSentThreeTimesAtOneAndTwoSecondsThenShownOffline() {
        failSends = 50
        app.selectSession(work); idle()
        assertEquals(1, sent.size)
        idle(900)
        assertEquals("no second try before one second", 1, sent.size)
        idle(300)
        assertEquals(2, sent.size)
        idle(1_500)
        assertEquals("no third try before two more seconds", 2, sent.size)
        idle(600)
        assertEquals(3, sent.size)
        assertEquals(LoadStatus.OFFLINE, history().status)
        idle(20_000)
        assertEquals("never a fourth", 3, sent.size)
        assertEquals(LoadStatus.OFFLINE, history().status)
    }

    @Test fun W3_aPhoneThatIsNotReachableIsLookedForThreeTimesAndTheSessionListIsThenShownOffline() {
        nodeMissing = 50
        app.loadSessions(); idle()
        assertEquals(LoadStatus.LOADING, app.reader.value.sessions.status)
        idle(3_400)
        assertEquals(3, nodeLookups)
        assertEquals(0, sent.size)
        assertEquals(LoadStatus.OFFLINE, app.reader.value.sessions.status)
        idle(20_000)
        assertEquals(3, nodeLookups)
    }

    @Test fun W4_aPhoneThatBecomesReachableDuringTheRetriesGetsTheRequestOnce() {
        nodeMissing = 1
        app.loadSessions(); idle()
        idle(1_200)
        assertEquals(1, sent.size)
        assertEquals(ReaderKind.SESSIONS, sent.single().kind)
        assertEquals(LoadStatus.LOADING, app.reader.value.sessions.status)
    }

    @Test fun W5_aRequestReplacedWhileItWaitsToBeSentAgainIsNeverSentAgain() {
        failSends = 1
        app.selectSession(work); idle()
        val first = sent.single()
        app.selectSession(other); idle()
        idle(5_000)
        assertTrue("the replaced request was not sent again: ${sent.map { it.reqId to it.sessionId }}", sent.count { it.reqId == first.reqId } == 1)
    }

    @Test fun W6_aRequestThePhoneReceivedIsNeverSentAgainAfterAnErrorOrTheTimeout() {
        app.selectSession(work); idle()
        val request = sent.single()
        answer(request, ok = false); idle()
        assertEquals(LoadStatus.ERROR, history().status)
        idle(20_000)
        assertEquals("one send only", 1, sent.size)
        app.refreshSelected(); idle()
        assertEquals(2, sent.size)
        idle(20_000)
        assertEquals("the timeout of a received request does not send it again", 2, sent.size)
        assertEquals(LoadStatus.ERROR, history().status)
    }
}
