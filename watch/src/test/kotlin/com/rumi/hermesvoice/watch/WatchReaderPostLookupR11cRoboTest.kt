package com.rumi.hermesvoice.watch

import android.content.Context
import android.net.Uri
import android.os.Looper
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.tasks.Task
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock

/**
 * R11c: the real WatchApp reader dispatch when the Phone lookup itself returns after the ONE original 15 s window has closed. A
 * request that has no time left when the node is known is not started; the current request is settled as not sent instead of
 * being left loading or sent once and timed out at once. This is a LOCAL admission check on the monotonic clock; it says nothing
 * about when a remote device starts or whether an already started Data Layer call is stopped. Data Layer clients are simulated and
 * the Robolectric clock is advanced to model a slow lookup. No device, no live Phone. Synthetic content only.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchReaderPostLookupR11cRoboTest {
    private lateinit var app: WatchApp
    private val sent = ConcurrentLinkedQueue<ReaderRequest>()
    private val sendResults = ArrayDeque<() -> Task<Int>>()
    private var lookups = 0
    private var onLookup: ((Int) -> Unit)? = null

    private val node = "phone-node"
    private val work = "20260101_120000_workwork"
    private val other = "20260101_130000_otherone"

    private fun transient(): () -> Task<Int> = { Tasks.forException(ApiException(Status(CommonStatusCodes.NETWORK_ERROR))) }

    private fun stubWearable() {
        mockkStatic(Wearable::class)
        val nodeInfo = mockk<Node>()
        every { nodeInfo.id } returns node
        every { nodeInfo.isNearby } returns true
        every { nodeInfo.displayName } returns "phone"
        val present = mockk<CapabilityInfo>()
        every { present.nodes } returns setOf(nodeInfo)
        every { present.name } returns WatchLinkPaths.CAPABILITY_PHONE
        val capability = mockk<CapabilityClient>()
        every { capability.getCapability(any(), any()) } answers {
            lookups += 1
            onLookup?.invoke(lookups)
            Tasks.forResult(present)
        }
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } answers {
            if (secondArg<String>() == WatchLinkPaths.READER_REQUEST) {
                ReaderRequest.decode(thirdArg<ByteArray>())?.let { sent.add(it) }
                val result = sendResults.removeFirstOrNull()?.invoke()
                if (result != null) return@answers result
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
    private fun advance(ms: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(ms))
    private fun history(id: String = work) = app.reader.value.historyFor(id)!!

    private fun answer(request: ReaderRequest) = app.onReaderResponse(node,
        ReaderResponse(request.reqId, request.kind, true, sessionId = request.sessionId,
            messages = listOf(ReaderMessageRow(1, "assistant", "synthetic latest", false)), nextOffset = 1).encode())

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as WatchApp
        stubWearable()
    }

    @After fun tearDown() = unmockkAll()

    @Test fun C1_aFirstLookupThatReturnsExactlyWhenTheWindowCloses_startsNoReadAndSettlesTheRequest() {
        onLookup = { if (it == 1) advance(15_000) }
        app.selectSession(work); idle()
        assertEquals("the window was spent by the lookup: no read is started", 0, sent.size)
        assertEquals("not left loading", LoadStatus.OFFLINE, history().status)
        assertEquals(ReaderError.OFFLINE, history().error)
        assertNull(history().pendingReqId)
        idle(20_000)
        assertEquals(0, sent.size)
        assertEquals(LoadStatus.OFFLINE, history().status)
        assertEquals("one lookup, no repeat", 1, lookups)
    }

    @Test fun C2_aFirstLookupThatReturnsOneMillisecondBeforeTheWindowClosesStillStartsTheRead() {
        onLookup = { if (it == 1) advance(14_999) }
        app.selectSession(work); idle()
        assertEquals("one millisecond is left: the read is still admitted", 1, sent.size)
        idle(20_000)
        assertEquals(1, sent.size)
        assertEquals(ReaderError.TIMEOUT, history().error)
    }

    @Test fun C3_aRetryWhoseLookupReturnsAfterTheOriginalWindowStartsNoNewRead() {
        sendResults.addAll(List(5) { transient() })
        onLookup = { if (it == 2) advance(20_000) }
        app.selectSession(work); idle()
        assertEquals(1, sent.size)
        idle(1_200)
        assertEquals("the retry was admitted at about 1 s but its lookup ended after 15 s", 2, lookups)
        assertEquals("no new read after the window closed", 1, sent.size)
        assertEquals("the current request is settled as not sent, not timed out after a late send", LoadStatus.OFFLINE, history().status)
        assertEquals(ReaderError.OFFLINE, history().error)
        assertNull(history().pendingReqId)
        idle(20_000)
        assertEquals(1, sent.size)
        assertEquals(2, lookups)
        assertEquals(LoadStatus.OFFLINE, history().status)
    }

    @Test fun C4_theSecondRetryWhoseLookupReturnsAfterTheOriginalWindowStartsNoThirdRead() {
        sendResults.addAll(List(5) { transient() })
        onLookup = { if (it == 3) advance(20_000) }
        app.selectSession(work); idle()
        idle(1_200)
        assertEquals(2, sent.size)
        idle(2_200)
        assertEquals(3, lookups)
        assertEquals("the second retry has no time left once its lookup returns: the third read is not started", 2, sent.size)
        assertEquals(LoadStatus.OFFLINE, history().status)
        idle(20_000)
        assertEquals(2, sent.size)
        assertEquals(LoadStatus.OFFLINE, history().status)
    }

    @Test fun C5_aRetryWhoseLookupIsSlowButInsideTheWindowIsStillSentAndAnswered() {
        sendResults.add(transient())
        onLookup = { if (it == 2) advance(5_000) }
        app.selectSession(work); idle()
        assertEquals(1, sent.size)
        idle(1_200)
        assertEquals(2, sent.size)
        assertEquals("the same request id", 1, sent.map { it.reqId }.toSet().size)
        answer(sent.last()); idle()
        assertEquals(LoadStatus.READY, history().status)
        idle(20_000)
        assertEquals(2, sent.size)
        assertEquals(LoadStatus.READY, history().status)
    }

    @Test fun C6_cancellationDuringAnOverdueRetryLookupSendsNothingAndPublishesNoFailure() {
        sendResults.addAll(List(5) { transient() })
        onLookup = {
            if (it == 2) {
                advance(20_000)
                app.scope.coroutineContext[Job]!!.cancelChildren()
            }
        }
        app.selectSession(work); idle()
        idle(1_200)
        assertEquals(2, lookups)
        assertEquals(1, sent.size)
        assertEquals("a cancelled refresh publishes no failure", LoadStatus.LOADING, history().status)
        idle(20_000)
        assertEquals(1, sent.size)
        assertEquals(LoadStatus.LOADING, history().status)
    }

    @Test fun C7_aRequestThatIsNoLongerTheSelectedOneWhenAnOverdueRetryLookupReturnsIsNotSent() {
        sendResults.add(transient())
        onLookup = {
            if (it == 2) {
                advance(20_000)
                app.selectSession(other)
            }
        }
        app.selectSession(work); idle()
        val first = sent.single()
        idle(1_200)
        assertEquals("the retired request is not sent again", 1, sent.count { it.reqId == first.reqId })
    }
}
