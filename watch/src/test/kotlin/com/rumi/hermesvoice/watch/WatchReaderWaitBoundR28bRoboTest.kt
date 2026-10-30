package com.rumi.hermesvoice.watch

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
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
import com.rumi.hermesvoice.core.watchlink.LoadStatus
import com.rumi.hermesvoice.core.watchlink.ReaderError
import com.rumi.hermesvoice.core.watchlink.ReaderKind
import com.rumi.hermesvoice.core.watchlink.ReaderRequest
import com.rumi.hermesvoice.core.watchlink.ReaderResponse
import com.rumi.hermesvoice.core.watchlink.ReaderSessionRow
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import org.json.JSONArray
import org.json.JSONObject
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/**
 * R28b (independent review NEEDS_CHANGE): the Watch reader's LOCAL waits for its Phone lookup and its request send are bounded by the
 * request's ONE first window counted from the first try, so a Data Layer Task that never completes cannot keep a read loading. The
 * fixtures are unset TaskCompletionSource tasks on the REAL WatchApp caller (resume, the selector's Retry, selecting a conversation) and
 * the REAL WatchListenerService, on Robolectric's virtual clock; the test releases only its own tasks afterwards.
 *
 * A lookup still unresolved when the first window closes is a read that was not sent ("Phone not reachable"). A send whose Task is
 * still unresolved has an UNKNOWN delivery: it is never sent again; with no Phone answer the read ends at 15 s as "Phone did not
 * answer", and a Phone acceptance, part or settling answer that arrives meanwhile keeps its effect (accepted 45 s window, shown rows,
 * READY) whatever the Task does later. A late Task result, failure or answer never revives, resends or replaces anything. Data Layer
 * clients are simulated; no device, no live Phone; synthetic content only.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchReaderWaitBoundR28bRoboTest {
    /** A scenario precondition that did not hold: the harness, not the behaviour under test. */
    class Setup(message: String) : RuntimeException(message)

    private lateinit var app: WatchApp
    private val sent = ConcurrentLinkedQueue<Pair<String, ReaderRequest>>()
    private val heldLookups = ConcurrentLinkedQueue<TaskCompletionSource<CapabilityInfo>>()
    private val heldSends = ConcurrentLinkedQueue<TaskCompletionSource<Int>>()
    /** What the next request sends do, in order: "hold", "transient" or "ok"; empty: [sendDefault]. */
    private val sendPlan = ArrayDeque<String>()
    @Volatile private var sendDefault = "ok"
    @Volatile private var holdLookup = false
    private lateinit var info: CapabilityInfo

    private val phone = "phone-node"
    private val work = "20260101_120000_workwork"
    private val other = "20260101_130000_otherone"
    private val scope = "a".repeat(64)
    private var start = 0L

    private fun stubWearable() {
        mockkStatic(Wearable::class)
        val node = mockk<Node>()
        every { node.id } returns phone
        every { node.isNearby } returns true
        every { node.displayName } returns "phone"
        info = mockk()
        every { info.nodes } returns setOf(node)
        every { info.name } returns WatchLinkPaths.CAPABILITY_PHONE
        val capability = mockk<CapabilityClient>()
        every { capability.getCapability(any(), any()) } answers {
            if (holdLookup) TaskCompletionSource<CapabilityInfo>().also { heldLookups += it }.task else Tasks.forResult(info)
        }
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } answers {
            if (secondArg<String>() != WatchLinkPaths.READER_REQUEST) return@answers Tasks.forResult(1)
            ReaderRequest.decode(thirdArg<ByteArray>())?.let { sent += firstArg<String>() to it }
            val task: Task<Int> = when (sendPlan.removeFirstOrNull() ?: sendDefault) {
                "hold" -> TaskCompletionSource<Int>().also { heldSends += it }.task
                "transient" -> Tasks.forException(ApiException(Status(CommonStatusCodes.NETWORK_ERROR)))
                else -> Tasks.forResult(1)
            }
            task
        }
        val data = mockk<DataClient>()
        every { data.getDataItems(any<Uri>()) } returns Tasks.forException<DataItemBuffer>(IllegalStateException("harness: no data item"))
        every { Wearable.getCapabilityClient(any<Context>()) } returns capability
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        every { Wearable.getChannelClient(any<Context>()) } returns mockk<ChannelClient>()
        every { Wearable.getDataClient(any<Context>()) } returns data
    }

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as WatchApp
        stubWearable()
    }

    /** Releases only this test's own unresolved tasks, then the mocks. */
    @After fun tearDown() {
        heldLookups.forEach { it.trySetResult(info) }
        heldSends.forEach { it.trySetResult(1) }
        runCatching { idle() }
        unmockkAll()
    }

    private fun idle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun mark() { start = SystemClock.elapsedRealtime() }
    private fun at(ms: Long) {
        val left = start + ms - SystemClock.elapsedRealtime()
        if (left < 0) throw Setup("the scenario clock is already ${-left} ms past $ms")
        idle(left)
    }

    private fun last(kind: ReaderKind): ReaderRequest {
        val (to, request) = sent.lastOrNull() ?: throw Setup("no reader request was sent")
        if (to != phone || request.kind != kind) throw Setup("the last request was ${request.kind} to $to, not $kind to $phone")
        return request
    }

    private fun deliver(path: String, bytes: ByteArray) {
        val event = mockk<MessageEvent>()
        every { event.path } returns path
        every { event.sourceNodeId } returns phone
        every { event.data } returns bytes
        Robolectric.setupService(WatchListenerService::class.java).onMessageReceived(event)
        idle()
    }

    private fun accept(request: ReaderRequest) =
        deliver("/hv/v1/reader/accepted", JSONObject().put("v", 1).put("req_id", request.reqId).put("kind", request.kind.wire).put("offset", request.offset)
            .apply { if (request.sessionId != null) put("session_id", request.sessionId) }.toString().toByteArray(Charsets.UTF_8))

    private fun listWire(reqId: String, ids: List<String>, part: Int?): ByteArray {
        val response = ReaderResponse(reqId, ReaderKind.SESSIONS, true, sessions = ids.map { ReaderSessionRow(it, "Fresh $it", "alias", "", 2.0) },
            sessionsComplete = part == null)
        return JSONObject(String(response.encode(), Charsets.UTF_8)).put("scope", scope).apply { if (part != null) put("part", part) }
            .toString().toByteArray(Charsets.UTF_8)
    }

    private fun answer(reqId: String, ids: List<String>) = deliver(WatchLinkPaths.READER_RESPONSE, listWire(reqId, ids, null))
    private fun part(reqId: String, n: Int, ids: List<String>) = deliver(WatchLinkPaths.READER_RESPONSE, listWire(reqId, ids, n))

    private fun cached(vararg ids: String) {
        val saved = JSONObject().put("v", 1).put("phone", phone).put("scope", scope)
            .put("rows", JSONArray().apply { ids.forEach { put(JSONObject().put("id", it).put("title", "Saved $it").put("last_active", 1.0)) } })
        if (!app.localStore.commitString("reader_session_titles_v1", saved.toString())) throw Setup("saved titles were not committed")
        ReflectionHelpers.callInstanceMethod<Void>(app, "hydrateReaderTitles")
        if (ids() != ids.toList()) throw Setup("the cached display was not hydrated: ${ids()}")
    }

    private fun list() = app.reader.value.sessions
    private fun ids() = list().rows.map { it.id }
    private fun history(id: String = work) = app.reader.value.historyFor(id) ?: throw Setup("no history entry for $id")

    @Test fun W01_aPhoneLookupThatNeverCompletesEndsTheListReadAtItsFirstWindowAsNotSent() {
        holdLookup = true
        mark(); app.refreshSessionTitlesOnResume(); idle()
        if (heldLookups.size != 1) throw Setup("the lookup was not held: ${heldLookups.size}")
        at(14_900)
        assertEquals(LoadStatus.LOADING, list().status)
        at(15_100)
        assertEquals("the lookup's local wait ends with the first window", LoadStatus.OFFLINE, list().status)
        assertEquals(ReaderError.OFFLINE, list().error)
        assertEquals("Phone not reachable", readerErrorText(list().error))
        assertTrue("nothing was sent", sent.isEmpty())
        heldLookups.forEach { it.setResult(info) }
        at(20_000)
        assertTrue("a late lookup result sends nothing", sent.isEmpty())
        assertEquals(LoadStatus.OFFLINE, list().status)
    }

    @Test fun W02_aRequestSendThatNeverCompletesEndsAt15sAsNoAnswer_neverResent_andNothingLateRevivesIt() {
        sendDefault = "hold"
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val r = last(ReaderKind.SESSIONS)
        at(14_900)
        assertEquals(LoadStatus.LOADING, list().status)
        at(15_100)
        assertEquals("delivery unknown and no Phone answer: the first window still ends", LoadStatus.ERROR, list().status)
        assertEquals(ReaderError.TIMEOUT, list().error)
        assertEquals("Phone did not answer", readerErrorText(list().error))
        assertEquals("an unresolved send is never sent again", 1, sent.size)
        heldSends.forEach { it.setResult(1) }
        at(20_000)
        assertEquals("a late Task result revives and resends nothing", 1, sent.size)
        assertEquals(LoadStatus.ERROR, list().status)
        at(25_000); answer(r.reqId, listOf(work))
        assertEquals("a late answer revives nothing", LoadStatus.ERROR, list().status)
        assertTrue(ids().isEmpty())
    }

    @Test fun W03_aCachedDisplayStaysShownWhileAHeldRequestSendEndsAt15s() {
        cached(other)
        sendDefault = "hold"
        mark(); app.refreshSessionTitlesOnResume(); idle()
        last(ReaderKind.SESSIONS)
        at(15_100)
        assertEquals(LoadStatus.ERROR, list().status)
        assertEquals(ReaderError.TIMEOUT, list().error)
        assertEquals("the saved display stays", listOf(other), ids())
    }

    @Test fun W04_anAcceptanceReceivedWhileTheSendTaskIsHeldKeepsTheAccepted45sWindow() {
        sendDefault = "hold"
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val r = last(ReaderKind.SESSIONS)
        at(1_000); accept(r)
        at(15_100)
        assertEquals("the Phone accepted: unknown local queue completion does not end the read", LoadStatus.LOADING, list().status)
        at(30_000); answer(r.reqId, listOf(work))
        assertEquals(LoadStatus.READY, list().status)
        at(40_000); heldSends.forEach { it.setResult(1) }
        at(50_000)
        assertEquals("a late Task result does not regress READY", LoadStatus.READY, list().status)
        assertEquals(listOf(work), ids())
        assertEquals(1, sent.size)
    }

    @Test fun W05_anAcceptedReadWhoseSendTaskStaysHeldEndsAt45sAsNotFinished() {
        sendDefault = "hold"
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val r = last(ReaderKind.SESSIONS)
        at(1_000); accept(r)
        at(44_900)
        assertEquals(LoadStatus.LOADING, list().status)
        at(45_100)
        assertEquals(LoadStatus.ERROR, list().status)
        assertEquals("incomplete", list().error)
        assertEquals("Loading did not finish", readerErrorText(list().error))
        assertEquals(1, sent.size)
    }

    @Test fun W06_aPartReceivedWhileTheSendTaskIsHeldIsShownAndTheReadEndsAt45sKeepingIt() {
        sendDefault = "hold"
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val r = last(ReaderKind.SESSIONS)
        at(2_000); part(r.reqId, 1, listOf(work))
        assertEquals(listOf(work), ids())
        at(15_500)
        assertEquals(LoadStatus.LOADING, list().status)
        at(45_100)
        assertEquals(LoadStatus.ERROR, list().status)
        assertEquals("incomplete", list().error)
        assertEquals("what arrived stays shown", listOf(work), ids())
    }

    @Test fun W07_aSettlingAnswerReceivedWhileTheSendTaskIsHeldStaysSettledWhenThatTaskLaterFails() {
        sendDefault = "hold"
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val r = last(ReaderKind.SESSIONS)
        at(5_000); answer(r.reqId, listOf(work))
        assertEquals(LoadStatus.READY, list().status)
        at(20_000); heldSends.single().setException(ApiException(Status(CommonStatusCodes.NETWORK_ERROR)))
        at(25_000)
        assertEquals("a late transient failure neither regresses nor resends", LoadStatus.READY, list().status)
        assertEquals(listOf(work), ids())
        assertEquals(1, sent.size)
        mark(); app.loadSessions(); idle()
        val again = last(ReaderKind.SESSIONS)
        at(1_000); answer(again.reqId, listOf(work, other))
        at(2_000); heldSends.last().setException(ApiException(Status(CommonStatusCodes.DEVELOPER_ERROR)))
        at(10_000)
        assertEquals("a late final failure does not regress READY either", LoadStatus.READY, list().status)
        assertEquals(listOf(work, other), ids())
        assertEquals(2, sent.size)
    }

    @Test fun W08_aHeldEarlierRequestNeverReplacesTheSupersedingOne() {
        sendPlan.addLast("hold")
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val older = last(ReaderKind.SESSIONS)
        at(1_000); app.loadSessions(); idle()
        val current = last(ReaderKind.SESSIONS)
        if (current.reqId == older.reqId) throw Setup("no superseding request")
        at(2_000); answer(current.reqId, listOf(work))
        assertEquals(LoadStatus.READY, list().status)
        at(15_100)
        assertEquals("the earlier request's local bound settles nothing of the current one", LoadStatus.READY, list().status)
        at(20_000); heldSends.forEach { it.setResult(1) }
        at(25_000); answer(older.reqId, listOf(other))
        assertEquals(LoadStatus.READY, list().status)
        assertEquals(listOf(work), ids())
        assertEquals(2, sent.size)
    }

    @Test fun W09_aHeldConversationRequestSendEndsAt15sAsNoAnswer() {
        sendDefault = "hold"
        mark(); app.selectSession(work); idle()
        last(ReaderKind.HISTORY)
        at(14_900)
        assertEquals(LoadStatus.LOADING, history().status)
        at(15_100)
        assertEquals(LoadStatus.ERROR, history().status)
        assertEquals(ReaderError.TIMEOUT, history().error)
        assertEquals(work, app.reader.value.selectedSessionId)
    }

    @Test fun W11_anAcceptedReadWhoseSendTaskLaterFailsKeepsItsAcceptedWindowWithoutAnotherSend() {
        sendDefault = "hold"
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val r = last(ReaderKind.SESSIONS)
        at(1_000); accept(r)
        at(10_000); heldSends.single().setException(ApiException(Status(CommonStatusCodes.NETWORK_ERROR)))
        at(10_500); answer(r.reqId, listOf(work))
        assertEquals("the Phone already holds the read: a late local failure does not drop its answer", LoadStatus.READY, list().status)
        assertEquals(listOf(work), ids())
        at(20_000)
        assertEquals("and the read is not sent again", 1, sent.size)
    }

    @Test fun W10_aTransientSendFailureStillRetriesTheSameIdOnceWithinTheFirstWindow() {
        sendPlan.addLast("transient")
        mark(); app.refreshSessionTitlesOnResume(); idle()
        at(1_500)
        if (sent.size != 2) throw Setup("the transient retry did not happen: ${sent.size}")
        val (first, second) = sent.toList().map { it.second }
        assertEquals("the same request id", first.reqId, second.reqId)
        at(2_000); accept(second)
        at(16_000)
        assertEquals(LoadStatus.LOADING, list().status)
        at(20_000); answer(second.reqId, listOf(work))
        assertEquals(LoadStatus.READY, list().status)
        assertEquals(2, sent.size)
    }
}
