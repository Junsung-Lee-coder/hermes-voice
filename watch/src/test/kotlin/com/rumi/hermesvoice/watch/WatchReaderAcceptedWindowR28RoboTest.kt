package com.rumi.hermesvoice.watch

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
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
import com.rumi.hermesvoice.core.watchlink.ReaderMessageRow
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import org.robolectric.util.ReflectionHelpers

/**
 * R28: the installed27 Watch showed "Phone did not answer" while the Phone was still answering. On the owner's devices the Watch sent
 * three conversation-list requests and the Phone sent each answer 31.864, 33.480 and 26.445 s later, after the Watch's one 15 s window
 * had closed and dropped the request, so each answer was ignored as "not pending". This drives the REAL WatchApp reader callers (resume,
 * the selector's Retry, selecting a conversation, an older page, a reply alert on the visible conversation) and the REAL
 * WatchListenerService with the same slow answers on Robolectric's virtual clock.
 *
 * The Phone's positive acceptance of one exact request is the Phone→Watch message `/hv/v1/reader/accepted` carrying
 * `{"v":1,"req_id","kind","offset"[,"session_id"]}` (a Data Layer sendMessage Task that succeeded on the Watch says only that the
 * message left the Watch). Once the Phone accepted a still-pending request inside the first 15 s window, that request may be answered
 * until 45 s after its first try; a request the Phone never accepted keeps the 15 s window, and nothing late, superseded, foreign or
 * from another node completes or revives a request. Data Layer clients are simulated; no device, no live Phone; synthetic content only.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchReaderAcceptedWindowR28RoboTest {
    /** A scenario precondition that did not hold: the harness, not the behaviour under test. */
    class Setup(message: String) : RuntimeException(message)

    private lateinit var app: WatchApp
    private lateinit var manager: NotificationManager
    private val sent = ConcurrentLinkedQueue<Pair<String, ReaderRequest>>()

    private val phone = "phone-node"
    private val otherNode = "other-node"
    private val work = "20260101_120000_workwork"
    private val other = "20260101_130000_otherone"
    private val scope = "a".repeat(64)
    private val accepted = "/hv/v1/reader/accepted"
    private val rows = listOf(ReaderMessageRow(1, "user", "synthetic question", false), ReaderMessageRow(2, "assistant", "synthetic answer", false))
    private var start = 0L

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
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } answers {
            if (secondArg<String>() == WatchLinkPaths.READER_REQUEST) ReaderRequest.decode(thirdArg<ByteArray>())?.let { sent += firstArg<String>() to it }
            Tasks.forResult(1)
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
        manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO)
        stubWearable()
    }

    @After fun tearDown() = unmockkAll()

    private fun idle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Starts a scenario clock at the moment of the real caller below. */
    private fun mark() { start = SystemClock.elapsedRealtime() }

    /** Runs the virtual clock until [ms] after [mark]. */
    private fun at(ms: Long) {
        val left = start + ms - SystemClock.elapsedRealtime()
        if (left < 0) throw Setup("the scenario clock is already ${-left} ms past $ms")
        idle(left)
    }

    private fun last(kind: ReaderKind): ReaderRequest {
        val (node, request) = sent.lastOrNull() ?: throw Setup("no reader request was sent")
        if (node != phone || request.kind != kind) throw Setup("the last request was ${request.kind} to $node, not $kind to $phone")
        return request
    }

    private fun deliver(path: String, bytes: ByteArray, from: String = phone) {
        val event = mockk<MessageEvent>()
        every { event.path } returns path
        every { event.sourceNodeId } returns from
        every { event.data } returns bytes
        Robolectric.setupService(WatchListenerService::class.java).onMessageReceived(event)
        idle()
    }

    /** The Phone application holds [request] (what a code28 Phone sends at listener admission); the echo can be varied to test fences. */
    private fun accept(request: ReaderRequest, from: String = phone, kind: String = request.kind.wire, session: String? = request.sessionId,
                       offset: Int = request.offset) =
        deliver(accepted, JSONObject().put("v", 1).put("req_id", request.reqId).put("kind", kind).put("offset", offset)
            .apply { if (session != null) put("session_id", session) }.toString().toByteArray(Charsets.UTF_8), from)

    private fun answerList(request: ReaderRequest, ids: List<String>, from: String = phone) {
        val response = ReaderResponse(request.reqId, ReaderKind.SESSIONS, true, sessions = ids.map { ReaderSessionRow(it, "Fresh $it", "alias", "", 2.0) })
        deliver(WatchLinkPaths.READER_RESPONSE, JSONObject(String(response.encode(), Charsets.UTF_8)).put("scope", scope).toString().toByteArray(Charsets.UTF_8), from)
    }

    private fun answerHistory(request: ReaderRequest, messages: List<ReaderMessageRow> = rows, hasOlder: Boolean = false, from: String = phone) =
        deliver(WatchLinkPaths.READER_RESPONSE, ReaderResponse(request.reqId, ReaderKind.HISTORY, true, sessionId = request.sessionId, messages = messages,
            offset = request.offset, nextOffset = request.offset + messages.size, hasOlder = hasOlder).encode(), from)

    private fun refusal(request: ReaderRequest, error: String) = deliver(WatchLinkPaths.READER_RESPONSE,
        ReaderResponse(request.reqId, request.kind, false, error = error, sessionId = request.sessionId).encode())

    private fun list() = app.reader.value.sessions
    private fun ids() = list().rows.map { it.id }
    private fun history(id: String = work) = app.reader.value.historyFor(id) ?: throw Setup("no history entry for $id")

    private fun alerts() = manager.activeNotifications.filter { it.notification.channelId == "reply_arrival" || it.notification.channelId == "reply_arrival_quiet" }.map { it.tag }.toSet()

    @Test fun A01_noCacheSelectorStartup_aListThePhoneAcceptedAndAnswered31_864sLaterIsShownNotTimedOut() {
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val request = last(ReaderKind.SESSIONS)
        at(200); accept(request)
        at(15_500)
        assertEquals("the Phone accepted this request: it is still being answered after the first 15 s", LoadStatus.LOADING, list().status)
        assertNull(list().error)
        at(31_864); answerList(request, listOf(work, other))
        assertEquals(LoadStatus.READY, list().status)
        assertEquals(listOf(work, other), ids())
        at(50_000)
        assertEquals("a settled list is not timed out afterwards", LoadStatus.READY, list().status)
        assertNull(list().error)
        assertEquals("one request, no repeat", 1, sent.size)
    }

    @Test fun A02_cachedSelectorStartup_savedRowsStayShownWhileTheAcceptedListIsSlow_thenTheAnswerAfter33_48sReplacesThem() {
        val saved = JSONObject().put("v", 1).put("phone", phone).put("scope", scope)
            .put("rows", JSONArray().put(JSONObject().put("id", other).put("title", "Saved $other").put("last_active", 1.0)))
        if (!app.localStore.commitString("reader_session_titles_v1", saved.toString())) throw Setup("saved titles were not committed")
        ReflectionHelpers.callInstanceMethod<Void>(app, "hydrateReaderTitles")  // the real startup hydration onCreate runs
        if (ids() != listOf(other) || list().notice != "Saved conversations") throw Setup("the cached display was not hydrated: ${ids()} ${list().notice}")
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val request = last(ReaderKind.SESSIONS)
        at(150); accept(request)
        at(16_000)
        assertEquals("accepted and still being answered", LoadStatus.LOADING, list().status)
        assertEquals("the cached display stays shown meanwhile", listOf(other), ids())
        at(33_480); answerList(request, listOf(work))
        assertEquals(LoadStatus.READY, list().status)
        assertEquals("the Phone's current list replaces the saved display", listOf(work), ids())
        assertNull(list().notice)
    }

    @Test fun A03_theSelectorsRealRetryAfterAGenuineUnacceptedTimeout_isAcceptedAndItsAnswer26_445sLaterIsShown() {
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val first = last(ReaderKind.SESSIONS)
        at(15_100)
        assertEquals("never accepted: the 15 s window still ends", LoadStatus.ERROR, list().status)
        assertEquals(ReaderError.TIMEOUT, list().error)
        assertEquals("Phone did not answer", readerErrorText(list().error))
        at(31_864); answerList(first, listOf(work))
        assertEquals("a late answer to an unaccepted, timed-out request revives nothing", LoadStatus.ERROR, list().status)
        assertTrue(ids().isEmpty())
        mark(); app.loadSessions(); idle()  // the selector's Retry chip (onRefresh = app::loadSessions)
        val retry = last(ReaderKind.SESSIONS)
        if (retry.reqId == first.reqId) throw Setup("Retry did not start a new request")
        at(100); accept(retry)
        at(15_200)
        assertEquals("the accepted Retry is still being answered after 15 s", LoadStatus.LOADING, list().status)
        at(26_445); answerList(retry, listOf(work, other))
        assertEquals(LoadStatus.READY, list().status)
        assertEquals(listOf(work, other), ids())
        assertEquals(2, sent.size)
    }

    @Test fun A04_aPermanentlyStalledAcceptedList_timesOutAtTheBoundedWindowAndALaterAnswerCannotResurrectIt() {
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val request = last(ReaderKind.SESSIONS)
        at(100); accept(request)
        at(44_900)
        assertEquals("accepted: still waiting just before the bounded window ends", LoadStatus.LOADING, list().status)
        at(45_100)
        assertEquals("a stalled accepted read still ends", LoadStatus.ERROR, list().status)
        // The Phone accepted the read: a slow data read is "did not finish", never "Phone did not answer" (owner direction R28s).
        assertEquals("incomplete", list().error)
        assertEquals("Loading did not finish", readerErrorText(list().error))
        at(46_000); answerList(request, listOf(work))
        assertEquals("an answer after the bounded window revives nothing", LoadStatus.ERROR, list().status)
        assertTrue(ids().isEmpty())
        app.loadSessions(); idle()
        assertEquals("Retry still starts one new request", 2, sent.size)
        assertNotEquals(request.reqId, last(ReaderKind.SESSIONS).reqId)
    }

    @Test fun A05_openingAConversation_aLatestPageThePhoneAcceptedAndAnswered33_48sLaterIsShown() {
        mark(); app.selectSession(work); idle()
        val request = last(ReaderKind.HISTORY)
        if (request.sessionId != work || request.offset != 0) throw Setup("unexpected opening read ${request.sessionId} ${request.offset}")
        at(120); accept(request)
        at(15_500)
        assertEquals("the accepted opening read is still being answered", LoadStatus.LOADING, history().status)
        assertNull(history().error)
        at(33_480); answerHistory(request)
        assertEquals(LoadStatus.READY, history().status)
        assertEquals(rows, history().messages)
        assertEquals(work, app.reader.value.selectedSessionId)
    }

    @Test fun A06_aVisibleConversationsReplyAlert_isDismissedAfterAnAcceptedReadSucceeds30sLater() {
        val identity = "turn-r28a06#later1"
        deliver(WatchLinkPaths.REPLY, """{"v":1,"identity":"$identity","session_id":"$work"}""".toByteArray(Charsets.UTF_8))
        repeat(20) { if (identity !in alerts()) idle(100) }
        if (identity !in alerts()) throw Setup("the reply alert was not shown")
        app.onForeground(true)
        mark(); app.selectSession(work); idle()
        val request = last(ReaderKind.HISTORY)
        at(100); accept(request)
        at(30_000); answerHistory(request)
        at(31_000)
        assertEquals(LoadStatus.READY, history().status)
        assertFalse("the alert goes once the visible conversation's accepted read succeeded", identity in alerts())
    }

    @Test fun A07_anAcceptedRequestIsCompletedOnlyByItsOwnAnswerFromTheAcceptingNode() {
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val request = last(ReaderKind.SESSIONS)
        at(100); accept(request)
        at(20_000); answerList(request, listOf(other), from = otherNode)
        assertTrue("another node's answer completes nothing", ids().isEmpty())
        deliver(WatchLinkPaths.READER_RESPONSE, JSONObject(String(ReaderResponse("rd-r28-a07-foreign", ReaderKind.SESSIONS, true,
            sessions = listOf(ReaderSessionRow(other, "Foreign", "alias", "", 2.0))).encode(), Charsets.UTF_8)).put("scope", scope).toString().toByteArray(Charsets.UTF_8))
        assertTrue("an answer to another request completes nothing", ids().isEmpty())
        at(28_000); answerList(request, listOf(work))
        assertEquals(LoadStatus.READY, list().status)
        assertEquals(listOf(work), ids())
    }

    @Test fun A08_anOlderPageThePhoneAcceptedAndAnswered26_445sLaterIsMergedBelowTheLatestPage() {
        app.selectSession(work); idle()
        answerHistory(last(ReaderKind.HISTORY), (1L..20L).map { ReaderMessageRow(100 + it, "assistant", "latest $it", false) }, hasOlder = true)
        if (history().status != LoadStatus.READY || !history().hasOlder) throw Setup("the latest page with older history was not shown")
        mark(); app.loadOlder(); idle()
        val older = last(ReaderKind.HISTORY)
        if (older.offset != 20) throw Setup("unexpected older offset ${older.offset}")
        at(100); accept(older)
        at(15_500)
        assertEquals("the accepted older page is still being answered", LoadStatus.LOADING, history().status)
        at(26_445); answerHistory(older, (1L..5L).map { ReaderMessageRow(it, "user", "older $it", false) })
        assertEquals(LoadStatus.READY, history().status)
        assertEquals((1L..5L).toList() + (101L..120L).toList(), history().messages.map { it.rowId })
    }

    @Test fun G01_anAcceptanceFromAnotherNodeDoesNotHoldTheRequestOpen() {
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val request = last(ReaderKind.SESSIONS)
        at(100); accept(request, from = otherNode)
        at(15_100)
        assertEquals(LoadStatus.ERROR, list().status)
        assertEquals(ReaderError.TIMEOUT, list().error)
        at(31_000); answerList(request, listOf(work))
        assertEquals(LoadStatus.ERROR, list().status)
        assertTrue(ids().isEmpty())
    }

    @Test fun G02_anAcceptanceArrivingAfterTheFirstWindowClosedRevivesNothing() {
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val request = last(ReaderKind.SESSIONS)
        at(15_100)
        assertEquals(ReaderError.TIMEOUT, list().error)
        at(15_200); accept(request)
        at(20_000); answerList(request, listOf(work))
        assertEquals(LoadStatus.ERROR, list().status)
        assertTrue(ids().isEmpty())
        at(50_000)
        assertEquals(LoadStatus.ERROR, list().status)
        assertEquals(ReaderError.TIMEOUT, list().error)
    }

    @Test fun G03_anAcceptanceOfASupersededListRequestNeitherHoldsNorSettlesTheCurrentOne() {
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val old = last(ReaderKind.SESSIONS)
        at(1_000); app.loadSessions(); idle()
        val current = last(ReaderKind.SESSIONS)
        if (current.reqId == old.reqId) throw Setup("no superseding request")
        at(2_000); accept(old)
        at(16_100)
        assertEquals("the current, unaccepted request ends at its own 15 s", LoadStatus.ERROR, list().status)
        assertEquals(ReaderError.TIMEOUT, list().error)
        at(31_000); answerList(old, listOf(work))
        assertEquals("the superseded answer completes nothing", LoadStatus.ERROR, list().status)
        assertTrue(ids().isEmpty())
    }

    @Test fun G04_anAcceptanceEchoingAnotherConversationDoesNotHoldAHistoryRead() {
        mark(); app.selectSession(work); idle()
        val request = last(ReaderKind.HISTORY)
        at(100); accept(request, session = other)
        at(15_100)
        assertEquals(LoadStatus.ERROR, history().status)
        assertEquals(ReaderError.TIMEOUT, history().error)
    }

    @Test fun G05_anAcceptanceEchoingAnotherKindDoesNotHoldAHistoryRead() {
        mark(); app.selectSession(work); idle()
        val request = last(ReaderKind.HISTORY)
        at(100); accept(request, kind = "sessions", session = null)
        at(15_100)
        assertEquals(LoadStatus.ERROR, history().status)
        assertEquals(ReaderError.TIMEOUT, history().error)
    }

    @Test fun G06_anAcceptanceEchoingAnotherPageDoesNotHoldAnOlderPageRead() {
        app.selectSession(work); idle()
        answerHistory(last(ReaderKind.HISTORY), (1L..20L).map { ReaderMessageRow(100 + it, "assistant", "latest $it", false) }, hasOlder = true)
        if (!history().hasOlder) throw Setup("no older history")
        mark(); app.loadOlder(); idle()
        val older = last(ReaderKind.HISTORY)
        at(100); accept(older, offset = 0)
        at(15_100)
        assertEquals(LoadStatus.ERROR, history().status)
        assertEquals(ReaderError.TIMEOUT, history().error)
        assertEquals("the shown page stays", (101L..120L).toList(), history().messages.map { it.rowId })
    }

    @Test fun G07_changingTheConversationDuringAnAcceptedReadKeepsTheNewSelectionAndItsContent() {
        mark(); app.selectSession(work); idle()
        val first = last(ReaderKind.HISTORY)
        at(100); accept(first)
        at(5_000); app.selectSession(other); idle()
        if (last(ReaderKind.HISTORY).sessionId != other) throw Setup("no read for the new selection")
        at(20_000); answerHistory(first)
        assertEquals("a late page of the previous conversation does not change the selection", other, app.reader.value.selectedSessionId)
        assertTrue("nor fill the previous conversation while it is not shown", history(work).messages.isEmpty())
        assertTrue(history(other).messages.isEmpty())
    }

    @Test fun G08_aSignOutDuringAnAcceptedListWaitIsNotRevivedByThatListsLateAnswer() {
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val listRead = last(ReaderKind.SESSIONS)
        at(100); accept(listRead)
        at(1_000); app.selectSession(work); idle()
        val opening = last(ReaderKind.HISTORY)
        at(2_000); refusal(opening, ReaderError.SIGN_IN_REQUIRED)
        if (list().error != ReaderError.SIGN_IN_REQUIRED) throw Setup("the sign-out was not shown: ${list().error}")
        at(30_000); answerList(listRead, listOf(work))
        assertEquals("the signed-out state stays", ReaderError.SIGN_IN_REQUIRED, list().error)
        assertTrue("the list read before the sign-out revives no content", ids().isEmpty())
        assertNull(app.reader.value.selectedSessionId)
        at(50_000)
        assertEquals(ReaderError.SIGN_IN_REQUIRED, list().error)
        assertTrue(ids().isEmpty())
    }
}
