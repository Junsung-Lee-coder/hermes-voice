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
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * R11b: the lookup of the Phone node is part of the same refresh as the send. A cancelled refresh publishes nothing even when its last
 * lookup comes back normally, a lookup that failed for a final typed reason is shown at once instead of being looked up again, and the
 * retries a lookup-only refresh spent travel with the request that finally goes out. The Data Layer is simulated; synthetic content only.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchReaderLookupR11bRoboTest {
    private lateinit var app: WatchApp
    private val sentRaw = ConcurrentLinkedQueue<ByteArray>()
    private val lookupResults = ArrayDeque<() -> Task<CapabilityInfo>>()
    private var lookups = 0
    private val node = "phone-node"
    private val work = "20260101_120000_workwork"

    private fun stubWearable() {
        mockkStatic(Wearable::class)
        val nodeInfo = mockk<Node>()
        every { nodeInfo.id } returns node
        every { nodeInfo.isNearby } returns true
        every { nodeInfo.displayName } returns "phone"
        val present = mockk<CapabilityInfo>()
        every { present.nodes } returns setOf(nodeInfo)
        every { present.name } returns WatchLinkPaths.CAPABILITY_PHONE
        val absent = mockk<CapabilityInfo>()
        every { absent.nodes } returns emptySet()
        every { absent.name } returns WatchLinkPaths.CAPABILITY_PHONE
        absentInfo = absent
        val capability = mockk<CapabilityClient>()
        every { capability.getCapability(any(), any()) } answers {
            lookups += 1
            lookupResults.removeFirstOrNull()?.invoke() ?: Tasks.forResult(present)
        }
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } answers {
            if (secondArg<String>() == WatchLinkPaths.READER_REQUEST) sentRaw.add(thirdArg<ByteArray>())
            Tasks.forResult(1)
        }
        val data = mockk<DataClient>()
        every { data.getDataItems(any<Uri>()) } returns Tasks.forException<DataItemBuffer>(IllegalStateException("harness: no data item"))
        every { Wearable.getCapabilityClient(any<Context>()) } returns capability
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        every { Wearable.getChannelClient(any<Context>()) } returns mockk<ChannelClient>()
        every { Wearable.getDataClient(any<Context>()) } returns data
    }

    private lateinit var absentInfo: CapabilityInfo
    private fun absent(): () -> Task<CapabilityInfo> = { Tasks.forResult(absentInfo) }
    private fun failed(code: Int): () -> Task<CapabilityInfo> = { Tasks.forException(ApiException(Status(code))) }

    private fun idle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun history() = app.reader.value.historyFor(work)!!

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as WatchApp
        stubWearable()
    }

    @After fun tearDown() = unmockkAll()

    @Test fun L1_cancellationDuringTheLastLookupIsNotPublishedAsOfflineAndLooksNothingUpAgain() {
        lookupResults.addAll(listOf(absent(), absent(), {
            app.scope.coroutineContext[Job]!!.cancelChildren()
            Tasks.forResult(absentInfo)
        }))
        app.selectSession(work); idle()
        idle(1_200)
        idle(2_200)
        assertEquals(3, lookups)
        assertEquals("a cancelled refresh publishes no failure", LoadStatus.LOADING, history().status)
        idle(20_000)
        assertEquals(3, lookups)
        assertEquals(LoadStatus.LOADING, history().status)
        assertEquals(0, sentRaw.size)
    }

    @Test fun L2_aLookupThatFailedForAFinalTypedReasonIsShownAtOnceAndNotLookedUpAgain() {
        lookupResults.addAll(List(5) { failed(CommonStatusCodes.API_NOT_CONNECTED) })
        app.selectSession(work); idle()
        assertEquals("shown at once, not after three lookups", LoadStatus.OFFLINE, history().status)
        idle(20_000)
        assertEquals(1, lookups)
        assertEquals(0, sentRaw.size)
        assertEquals(LoadStatus.OFFLINE, history().status)
    }

    @Test fun L3_aLookupThatFailedForATransientTypedReasonIsLookedUpAgainAndTheRetriesSpentTravelWithTheRequest() {
        lookupResults.addAll(listOf(failed(CommonStatusCodes.NETWORK_ERROR), failed(CommonStatusCodes.NETWORK_ERROR)))
        app.selectSession(work); idle()
        assertEquals(1, lookups)
        idle(1_200)
        assertEquals(2, lookups)
        idle(2_200)
        assertEquals(3, lookups)
        assertEquals(1, sentRaw.size)
        val wire = JSONObject(String(sentRaw.single(), Charsets.UTF_8))
        assertEquals("both retries were spent on lookups", 2, wire.optInt("retries_used", -1))
        assertEquals(LoadStatus.LOADING, history().status)
    }

    @Test fun L4_aRequestThatNeededNoRetryCarriesNoRetriesField() {
        app.selectSession(work); idle()
        assertEquals(1, sentRaw.size)
        assertFalse(JSONObject(String(sentRaw.single(), Charsets.UTF_8)).has("retries_used"))
    }
}
