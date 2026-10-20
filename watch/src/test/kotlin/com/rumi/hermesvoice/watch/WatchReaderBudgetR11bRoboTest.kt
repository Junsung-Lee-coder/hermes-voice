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
import com.google.android.gms.wearable.WearableStatusCodes
import com.rumi.hermesvoice.core.InMemoryKeyValueStore
import com.rumi.hermesvoice.core.net.ArchivedFilter
import com.rumi.hermesvoice.core.net.CreatedSession
import com.rumi.hermesvoice.core.net.HermesConversationPort
import com.rumi.hermesvoice.core.net.HistoryPage
import com.rumi.hermesvoice.core.net.StoredSession
import com.rumi.hermesvoice.core.net.StoredSessionPage
import com.rumi.hermesvoice.core.sessions.AppSessionRepository
import com.rumi.hermesvoice.core.sessions.HermesSessionsApi
import com.rumi.hermesvoice.core.sessions.OwnedRole
import com.rumi.hermesvoice.core.sessions.OwnedSession
import com.rumi.hermesvoice.core.sessions.OwnedSessionRegistry
import com.rumi.hermesvoice.core.watchlink.LoadStatus
import com.rumi.hermesvoice.core.watchlink.PhoneReaderService
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
import java.io.IOException
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSystemClock

/**
 * R11b: the real WatchApp reader dispatch measured against the ONE original 15 s window (slow lookup, slow send, an oversleeping
 * pause), a request that is no longer the pending one, cancellation, typed transport failures, the privacy of the new retry log
 * record, and the retry allowance shared with the real PhoneReaderService behind the same dispatch. Data Layer clients and the
 * dashboard are simulated; the Robolectric clock is advanced to model slow transport. No device, no live Phone. Synthetic content only.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchReaderBudgetR11bRoboTest {
    private lateinit var app: WatchApp
    private val sent = ConcurrentLinkedQueue<ReaderRequest>()
    private val sentRaw = ConcurrentLinkedQueue<ByteArray>()
    private val sendResults = ArrayDeque<() -> Task<Int>>()
    private var nodeMissing = 0
    private var nodeLookups = 0
    private var onLookup: (() -> Unit)? = null
    private var onSend: ((ReaderRequest) -> Unit)? = null
    private var phone: PhoneReaderService? = null
    private val phoneScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val backendReads = AtomicInteger()

    private val node = "phone-node"
    private val work = "20260101_120000_workwork"
    private val other = "20260101_130000_otherone"
    private val sentinelSession = "sntl-9f3a1c-session"

    private fun transient(code: Int = CommonStatusCodes.NETWORK_ERROR): () -> Task<Int> = { Tasks.forException(ApiException(Status(code))) }

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
        val capability = mockk<CapabilityClient>()
        every { capability.getCapability(any(), any()) } answers {
            nodeLookups += 1
            onLookup?.invoke()
            if (nodeMissing > 0) { nodeMissing -= 1; Tasks.forResult(absent) } else Tasks.forResult(present)
        }
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } answers {
            if (secondArg<String>() == WatchLinkPaths.READER_REQUEST) {
                val bytes = thirdArg<ByteArray>()
                val request = ReaderRequest.decode(bytes)
                if (request != null) { sent.add(request); sentRaw.add(bytes) }
                request?.let { onSend?.invoke(it) }
                val result = sendResults.removeFirstOrNull()?.invoke()
                if (result != null) return@answers result
                val service = phone
                if (service != null && request != null) {
                    phoneScope.launch { service.handle(bytes)?.let { app.onReaderResponse(node, it) } }
                }
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

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as WatchApp
        stubWearable()
    }

    @After fun tearDown() { phoneScope.coroutineContext[Job]?.cancelChildren(); unmockkAll() }

    private fun history(id: String = work) = app.reader.value.historyFor(id)!!

    private fun answer(request: ReaderRequest, ok: Boolean = true) = app.onReaderResponse(node,
        ReaderResponse(request.reqId, request.kind, ok, error = if (ok) null else ReaderError.UNAVAILABLE, sessionId = request.sessionId,
            messages = if (ok) listOf(ReaderMessageRow(1, "assistant", "synthetic latest", false)) else emptyList(), nextOffset = if (ok) 1 else 0).encode())

    /** A real PhoneReaderService over a real AppSessionRepository whose dashboard read of the page always fails for a transient reason. */
    private fun failingBackend(): PhoneReaderService {
        val registry = OwnedSessionRegistry(InMemoryKeyValueStore())
        listOf(work, other, sentinelSession).forEach {
            registry.put(OwnedSession(it, OwnedRole.CONVERSATION, "Synthetic $it", it.takeLast(8), "", false, 1L))
        }
        val api = object : HermesSessionsApi {
            override suspend fun listSessions(source: String?, archived: ArchivedFilter, limit: Int, offset: Int, excludeSources: List<String>) =
                StoredSessionPage(emptyList(), 0)
            override suspend fun getSession(sessionId: String) = StoredSession(sessionId, "recorder-phone", "Synthetic", "", 0.0, 1.0, 1, false,
                createdSource = "recorder-phone")
            override suspend fun getMessages(sessionId: String, limit: Int, offset: Int): HistoryPage {
                backendReads.incrementAndGet()
                throw IOException("synthetic transient backend failure")
            }
            override suspend fun setArchived(sessionId: String, archived: Boolean) = throw UnsupportedOperationException()
        }
        val conversations = mockk<HermesConversationPort>(relaxed = true)
        return PhoneReaderService(AppSessionRepository(api, conversations, registry, routerRuntime = null))
    }

    // ── O02: one original window ─────────────────────────────────────────────────────────────

    @Test fun B1_aSlowSendSpendsTheOriginalFifteenSecondWindowAndIsNotGivenAFreshOne() {
        onSend = { advance(10_000) }
        app.selectSession(work); idle()
        assertEquals(1, sent.size)
        idle(4_700)
        assertEquals("still inside the original window", LoadStatus.LOADING, history().status)
        idle(500)
        assertEquals("the original 15 s are over; no fresh 15 s after a slow send", LoadStatus.ERROR, history().status)
        assertEquals(ReaderError.TIMEOUT, history().error)
        assertEquals(1, sent.size)
    }

    @Test fun B2_aSlowPhoneLookupAndARefusedSendLeaveNoRoomForARetryOutsideTheOriginalWindow() {
        onLookup = { advance(14_500) }
        sendResults.addAll(List(5) { transient() })
        app.selectSession(work); idle()
        idle(6_000)
        assertEquals("a retry after a 1 s pause would start outside the original window", 1, sent.size)
        assertEquals(LoadStatus.OFFLINE, history().status)
    }

    @Test fun B3_aRetryPauseThatOversleepsPastTheWindowIsNotFollowedByAnotherSend() {
        sendResults.addAll(List(5) { transient() })
        app.selectSession(work); idle()
        assertEquals(1, sent.size)
        advance(20_000)
        idle(50)
        assertEquals("the pause overslept the whole window: nothing more is sent", 1, sent.size)
        assertEquals(LoadStatus.OFFLINE, history().status)
        idle(20_000)
        assertEquals(1, sent.size)
    }

    // ── O02: pending / currentness ───────────────────────────────────────────────────────────

    @Test fun B4_aHistoryRequestWhoseConversationWasRemovedFromTheListIsNotSentAfterItsPause() {
        sendResults.add(transient())
        app.selectSession(work); idle()
        val first = sent.single()
        assertEquals(LoadStatus.LOADING, history().status)
        app.loadSessions(); idle()
        val list = sent.last()
        assertEquals(ReaderKind.SESSIONS, list.kind)
        app.onReaderResponse(node, ReaderResponse(list.reqId, ReaderKind.SESSIONS, ok = true, removedSessionIds = listOf(work)).encode())
        idle()
        assertNull("the conversation is gone from the selection", app.reader.value.selectedSessionId)
        assertNull("no pending history request is left", app.reader.value.pendingHistoryRequest(first.reqId))
        idle(5_000)
        assertEquals("the old request is retired, not sent after its pause", listOf(first.reqId, list.reqId), sent.map { it.reqId })
    }

    // ── O02: cancellation ────────────────────────────────────────────────────────────────────

    @Test fun B5_cancellationDuringTheLastSendIsNotPublishedAsOfflineAndSendsNothingMore() {
        sendResults.addAll(listOf(transient(), transient(), {
            app.scope.coroutineContext[Job]!!.cancelChildren()
            Tasks.forException<Int>(ApiException(Status(CommonStatusCodes.NETWORK_ERROR)))
        }))
        app.selectSession(work); idle()
        idle(1_200)
        idle(2_200)
        assertEquals(3, sent.size)
        assertEquals("a cancelled refresh publishes no failure", LoadStatus.LOADING, history().status)
        idle(20_000)
        assertEquals(3, sent.size)
        assertEquals(LoadStatus.LOADING, history().status)
    }

    @Test fun B6_aTaskThatWasCancelledByTheDataLayerIsATerminalTransportFailureAndIsNotSentAgain() {
        sendResults.addAll(List(5) { { Tasks.forCanceled<Int>() } })
        app.selectSession(work); idle()
        idle(5_000)
        assertEquals("a cancelled Data Layer task is final, not a refused message", 1, sent.size)
        assertEquals(LoadStatus.OFFLINE, history().status)
    }

    // ── O03: typed admission ─────────────────────────────────────────────────────────────────

    @Test fun B7_terminalTypedTransportFailuresAreSentOnceAndShownAtOnce() {
        val terminals = listOf<() -> Task<Int>>(
            transient(CommonStatusCodes.DEVELOPER_ERROR), transient(CommonStatusCodes.SIGN_IN_REQUIRED), transient(CommonStatusCodes.INVALID_ACCOUNT),
            transient(CommonStatusCodes.API_NOT_CONNECTED), transient(WearableStatusCodes.INVALID_TARGET_NODE), transient(WearableStatusCodes.DATA_ITEM_TOO_LARGE),
            { Tasks.forException(SecurityException("synthetic permission rejection")) },
            { Tasks.forException(IllegalStateException("synthetic protocol rejection")) },
        )
        app.selectSession(work); idle()
        answer(sent.last()); idle()
        var expected = sent.size
        terminals.forEachIndexed { index, failure ->
            sendResults.clear()
            sendResults.addAll(List(4) { failure })
            app.refreshSelected(); idle()
            idle(5_000)
            expected += 1
            assertEquals("terminal failure #$index is not retried", expected, sent.size)
            assertEquals("terminal failure #$index", LoadStatus.OFFLINE, history().status)
            idle(20_000)
            assertEquals("terminal failure #$index stays unsent", expected, sent.size)
        }
    }

    @Test fun B8_aTransientTypedTransportFailureIsStillSentAgainAfterOneSecond() {
        sendResults.add(transient(WearableStatusCodes.TARGET_NODE_NOT_CONNECTED))
        app.selectSession(work); idle()
        assertEquals(1, sent.size)
        idle(1_200)
        assertEquals(2, sent.size)
        assertEquals("the same request", 1, sent.map { it.reqId }.toSet().size)
        answer(sent.last()); idle()
        assertEquals(LoadStatus.READY, history().status)
    }

    // ── O04: the new retry log carries no identifier ─────────────────────────────────────────

    @Test fun B9_theNewRetryLogRecordCarriesNoRequestOrConversationIdentifier() {
        sendResults.add(transient())
        app.selectSession(sentinelSession); idle()
        val id = sent.single().reqId
        idle(1_200)
        val retryLines = ShadowLog.getLogs().filter { it.tag == "HermesVoiceWatch" && it.msg.contains("retry=") }.map { it.msg }
        assertEquals(retryLines.toString(), 1, retryLines.size)
        assertTrue(retryLines.single(), retryLines.single().contains("cause=send_refused"))
        retryLines.forEach { line ->
            assertFalse("no request id prefix: $line", line.contains(id.take(12)) || line.contains("req="))
            assertFalse("no conversation id: $line", line.contains(sentinelSession))
            assertFalse("no node id: $line", line.contains(node))
        }
    }

    // ── O03: one budget across Watch and Phone, composed through the real dispatch ───────────

    @Test fun B10_noPhoneForTwoAttemptsThenATransientBackendFailureIsTwoRetriesInTotalNotFour() {
        phone = failingBackend()
        nodeMissing = 2
        app.selectSession(work); idle()
        idle(3_400)
        assertEquals(3, nodeLookups)
        assertEquals(1, sent.size)
        idle(20_000)
        assertEquals("the Watch spent both retries on lookups: the Phone reads once", 1, backendReads.get())
        assertEquals(LoadStatus.ERROR, history().status)
        assertEquals(ReaderError.UNAVAILABLE, history().error)
        assertEquals(1, sent.size)
    }

    @Test fun B11_oneRefusedSendThenATransientBackendFailureIsTwoRetriesInTotalNotThree() {
        phone = failingBackend()
        sendResults.add(transient())
        app.selectSession(work); idle()
        idle(1_200)
        assertEquals(2, sent.size)
        idle(20_000)
        assertEquals("one Watch retry plus one Phone retry: two reads of the page", 2, backendReads.get())
        assertEquals(LoadStatus.ERROR, history().status)
    }

    @Test fun B12_aRequestThatNeededNoWatchRetryKeepsTheFullPhoneAllowance() {
        phone = failingBackend()
        app.selectSession(work); idle()
        idle(20_000)
        assertEquals("first read plus the two Phone retries", 3, backendReads.get())
        assertEquals(LoadStatus.ERROR, history().status)
        assertEquals(1, sent.size)
    }
}
