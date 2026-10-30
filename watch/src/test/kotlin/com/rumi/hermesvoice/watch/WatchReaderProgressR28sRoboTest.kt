package com.rumi.hermesvoice.watch

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
 * R28s (owner direction: "keep Live streaming as it is; separate connection and data transfer for the rest"): the Watch list read is
 * control (the request, the Phone's acceptance, the final settle) separate from data (list parts that arrive while the Phone's
 * dashboard scan is still running). Drives the REAL WatchApp reader callers (resume, the selector's Retry, selecting a conversation)
 * and the REAL WatchListenerService on Robolectric's virtual clock, with synthetic Phone wire.
 *
 * Wire under test: a Watch list request says `"progress":true`; the Phone may then answer with list parts (`"part":N`, N >= 1,
 * `"sessions_complete":false`, scoped) before its final answer (no `part`). A part is shown at once and never settles the read, never
 * revokes a row not yet seen, and counts as the Phone app answering; duplicate, older, foreign-node, foreign-account, superseded or late
 * parts change nothing. An accepted read that does not finish inside the bounded window ends as "did not finish", not as a Phone that did
 * not answer; an unaccepted one still ends at 15 s as before. Data Layer clients are simulated; no device, no live Phone.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchReaderProgressR28sRoboTest {
    /** A scenario precondition that did not hold: the harness, not the behaviour under test. */
    class Setup(message: String) : RuntimeException(message)

    private lateinit var app: WatchApp
    private val sent = ConcurrentLinkedQueue<Pair<String, ReaderRequest>>()
    private val raw = ConcurrentLinkedQueue<JSONObject>()

    private val phone = "phone-node"
    @Volatile private var phoneNow = phone
    private val otherNode = "other-node"
    private val work = "20260101_120000_workwork"
    private val other = "20260101_130000_otherone"
    private val third = "20260101_140000_thirdone"
    private val scope = "a".repeat(64)
    private val accepted = "/hv/v1/reader/accepted"
    private var start = 0L

    private fun stubWearable() {
        mockkStatic(Wearable::class)
        val capability = mockk<CapabilityClient>()
        every { capability.getCapability(any(), any()) } answers {
            val node = mockk<Node>()
            every { node.id } returns phoneNow
            every { node.isNearby } returns true
            every { node.displayName } returns "phone"
            val info = mockk<CapabilityInfo>()
            every { info.nodes } returns setOf(node)
            every { info.name } returns WatchLinkPaths.CAPABILITY_PHONE
            Tasks.forResult(info)
        }
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } answers {
            if (secondArg<String>() == WatchLinkPaths.READER_REQUEST) {
                raw += JSONObject(String(thirdArg<ByteArray>(), Charsets.UTF_8))
                ReaderRequest.decode(thirdArg<ByteArray>())?.let { sent += firstArg<String>() to it }
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

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as WatchApp
        stubWearable()
    }

    @After fun tearDown() = unmockkAll()

    private fun idle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun mark() { start = SystemClock.elapsedRealtime() }
    private fun at(ms: Long) {
        val left = start + ms - SystemClock.elapsedRealtime()
        if (left < 0) throw Setup("the scenario clock is already ${-left} ms past $ms")
        idle(left)
    }

    private fun last(kind: ReaderKind, node: String = phone): ReaderRequest {
        val (to, request) = sent.lastOrNull() ?: throw Setup("no reader request was sent")
        if (to != node || request.kind != kind) throw Setup("the last request was ${request.kind} to $to, not $kind to $node")
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

    private fun accept(request: ReaderRequest, from: String = phone) =
        deliver(accepted, JSONObject().put("v", 1).put("req_id", request.reqId).put("kind", request.kind.wire).put("offset", request.offset)
            .apply { if (request.sessionId != null) put("session_id", request.sessionId) }.toString().toByteArray(Charsets.UTF_8), from)

    private fun rows(ids: List<String>) = ids.map { ReaderSessionRow(it, "Fresh $it", "alias", "", 2.0) }

    private fun listWire(reqId: String, ids: List<String>, complete: Boolean, removed: List<String>, wireScope: String, part: Int?): ByteArray {
        val response = ReaderResponse(reqId, ReaderKind.SESSIONS, true, sessions = rows(ids), sessionsComplete = complete, removedSessionIds = removed)
        return JSONObject(String(response.encode(), Charsets.UTF_8)).put("scope", wireScope).apply { if (part != null) put("part", part) }
            .toString().toByteArray(Charsets.UTF_8)
    }

    /** One list part the Phone sends while its scan is still running. */
    private fun part(reqId: String, n: Int, ids: List<String>, removed: List<String> = emptyList(), from: String = phone, wireScope: String = scope) =
        deliver(WatchLinkPaths.READER_RESPONSE, listWire(reqId, ids, false, removed, wireScope, n), from)

    /** The Phone's final list answer. */
    private fun answer(reqId: String, ids: List<String>, complete: Boolean = true, removed: List<String> = emptyList(), from: String = phone) =
        deliver(WatchLinkPaths.READER_RESPONSE, listWire(reqId, ids, complete, removed, scope, null), from)

    private fun answerHistory(request: ReaderRequest) = deliver(WatchLinkPaths.READER_RESPONSE, ReaderResponse(request.reqId, ReaderKind.HISTORY, true,
        sessionId = request.sessionId, messages = listOf(ReaderMessageRow(1, "assistant", "synthetic", false)), nextOffset = 1).encode())

    private fun refusal(request: ReaderRequest, error: String) = deliver(WatchLinkPaths.READER_RESPONSE,
        ReaderResponse(request.reqId, request.kind, false, error = error, sessionId = request.sessionId).encode())

    private fun cached(vararg ids: String) {
        val saved = JSONObject().put("v", 1).put("phone", phone).put("scope", scope)
            .put("rows", JSONArray().apply { ids.forEach { put(JSONObject().put("id", it).put("title", "Saved $it").put("last_active", 1.0)) } })
        if (!app.localStore.commitString("reader_session_titles_v1", saved.toString())) throw Setup("saved titles were not committed")
        ReflectionHelpers.callInstanceMethod<Void>(app, "hydrateReaderTitles")  // the real startup hydration onCreate runs
        if (ids() != ids.toList()) throw Setup("the cached display was not hydrated: ${ids()}")
    }

    private fun list() = app.reader.value.sessions
    private fun ids() = list().rows.map { it.id }
    private fun history(id: String = work) = app.reader.value.historyFor(id) ?: throw Setup("no history entry for $id")

    @Test fun B01_noCacheStartup_listPartsAreShownAtOnceWhileTheReadStaysOpen_thenTheCompleteAnswerSettlesIt() {
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val r = last(ReaderKind.SESSIONS)
        at(100); accept(r)
        at(2_000); part(r.reqId, 1, listOf(work))
        assertEquals("the first part is shown at once", listOf(work), ids())
        assertEquals("a part does not settle the read", LoadStatus.LOADING, list().status)
        at(5_000); part(r.reqId, 2, listOf(work, other))
        assertEquals(listOf(work, other), ids())
        assertEquals(LoadStatus.LOADING, list().status)
        at(31_864); answer(r.reqId, listOf(other, third))
        assertEquals(LoadStatus.READY, list().status)
        assertEquals("the complete answer is the list", listOf(other, third), ids())
        at(50_000)
        assertEquals(LoadStatus.READY, list().status)
        assertNull(list().error)
    }

    @Test fun B02_cacheFirstStartup_partsMergeIntoTheSavedDisplay_thenTheCompleteAnswerReplacesIt() {
        cached(other)
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val r = last(ReaderKind.SESSIONS)
        at(100); accept(r)
        at(3_000); part(r.reqId, 1, listOf(work))
        assertEquals("fresh rows first, the saved row not yet seen stays", listOf(work, other), ids())
        assertEquals(LoadStatus.LOADING, list().status)
        at(26_445); answer(r.reqId, listOf(work))
        assertEquals(LoadStatus.READY, list().status)
        assertEquals(listOf(work), ids())
        assertNull(list().notice)
    }

    @Test fun B03_anIncompleteFinalAfterPartsKeepsRowsNotYetSeenAndSaysPartial() {
        cached(other)
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val r = last(ReaderKind.SESSIONS)
        at(100); accept(r)
        at(2_000); part(r.reqId, 1, listOf(work))
        assertEquals(LoadStatus.LOADING, list().status)
        at(20_000); answer(r.reqId, listOf(work, third), complete = false)
        assertEquals(LoadStatus.READY, list().status)
        assertEquals("an unseen row of an incomplete scan is not revoked", setOf(work, third, other), ids().toSet())
        assertEquals("Partial list", list().notice)
    }

    @Test fun B04_aDuplicateOrOlderPartChangesNothing() {
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val r = last(ReaderKind.SESSIONS)
        at(100); accept(r)
        at(2_000); part(r.reqId, 2, listOf(work, other))
        assertEquals(LoadStatus.LOADING, list().status)
        at(3_000); part(r.reqId, 1, listOf(third))
        assertEquals("an older part arriving late adds nothing", listOf(work, other), ids())
        at(4_000); part(r.reqId, 2, listOf(third))
        assertEquals("a repeated part adds nothing", listOf(work, other), ids())
        assertEquals(LoadStatus.LOADING, list().status)
        at(10_000); answer(r.reqId, listOf(work, other, third))
        assertEquals(LoadStatus.READY, list().status)
        assertEquals(listOf(work, other, third), ids())
    }

    @Test fun B05_aPartFromAnotherNodeForAnotherRequestOrOfAnotherAccountIsIgnored() {
        cached(other)
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val r = last(ReaderKind.SESSIONS)
        at(100); accept(r)
        at(1_000); part(r.reqId, 1, listOf(work), from = otherNode)
        at(1_100); part("rd-r28s-unknown-0001", 1, listOf(work))
        at(1_200); part(r.reqId, 1, listOf(work), wireScope = "b".repeat(64))
        assertEquals("nothing foreign reached the display, nothing was invalidated", listOf(other), ids())
        assertEquals(LoadStatus.LOADING, list().status)
        at(2_000); part(r.reqId, 1, listOf(work))
        assertEquals(listOf(work, other), ids())
    }

    @Test fun B06_aPartAfterTheCompleteAnswerOrForASupersededRequestIsIgnored() {
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val r = last(ReaderKind.SESSIONS)
        at(100); accept(r)
        at(1_000); answer(r.reqId, listOf(work))
        at(1_100); part(r.reqId, 3, listOf(other))
        assertEquals("a settled read takes no more parts", listOf(work), ids())
        assertEquals(LoadStatus.READY, list().status)
        mark(); app.loadSessions(); idle()
        val older = last(ReaderKind.SESSIONS)
        at(100); app.loadSessions(); idle()
        val current = last(ReaderKind.SESSIONS)
        if (current.reqId == older.reqId) throw Setup("no superseding request")
        at(200); part(older.reqId, 1, listOf(third))
        assertFalse("a superseded read's part is not shown", third in ids())
        at(1_000); answer(current.reqId, listOf(other))
        assertEquals(listOf(other), ids())
    }

    @Test fun B07_anAcceptedListThatNeverCompletesEndsAsNotFinished_keepsItsParts_andRetryStillWorks() {
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val r = last(ReaderKind.SESSIONS)
        at(100); accept(r)
        at(3_000); part(r.reqId, 1, listOf(work))
        at(44_900)
        assertEquals(LoadStatus.LOADING, list().status)
        at(45_100)
        assertEquals(LoadStatus.ERROR, list().status)
        assertEquals("the Phone answered and sent data: not a Phone that did not answer", "incomplete", list().error)
        assertEquals("Loading did not finish", readerErrorText(list().error))
        assertEquals("what arrived stays shown", listOf(work), ids())
        at(46_000); answer(r.reqId, listOf(other))
        assertEquals("an answer after the bounded window revives nothing", listOf(work), ids())
        app.loadSessions(); idle()
        assertNotEquals(r.reqId, last(ReaderKind.SESSIONS).reqId)
    }

    @Test fun B08_anUnacceptedListStillEndsAt15sAsAPhoneThatDidNotAnswer() {
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val r = last(ReaderKind.SESSIONS)
        at(15_100)
        assertEquals(LoadStatus.ERROR, list().status)
        assertEquals(ReaderError.TIMEOUT, list().error)
        assertEquals("Phone did not answer", readerErrorText(list().error))
        at(20_000); part(r.reqId, 1, listOf(work))
        assertTrue(ids().isEmpty())
    }

    @Test fun B09_anAcceptedHistoryReadThatNeverAnswersEndsAsNotFinished() {
        mark(); app.selectSession(work); idle()
        val h = last(ReaderKind.HISTORY)
        at(100); accept(h)
        at(45_100)
        assertEquals(LoadStatus.ERROR, history().status)
        assertEquals("incomplete", history().error)
        assertEquals("Loading did not finish", readerErrorText(history().error))
    }

    @Test fun B10_aSignOutDuringAProgressiveListRevivesNothing() {
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val r = last(ReaderKind.SESSIONS)
        at(100); accept(r)
        at(1_000); part(r.reqId, 1, listOf(work))
        at(2_000); app.selectSession(work); idle()
        val h = last(ReaderKind.HISTORY)
        at(3_000); refusal(h, ReaderError.SIGN_IN_REQUIRED)
        if (list().error != ReaderError.SIGN_IN_REQUIRED) throw Setup("the sign-out was not shown: ${list().error}")
        at(4_000); part(r.reqId, 2, listOf(work, other))
        at(20_000); answer(r.reqId, listOf(work, other))
        assertEquals(ReaderError.SIGN_IN_REQUIRED, list().error)
        assertTrue("nothing of the read made before the sign-out comes back", ids().isEmpty())
        assertNull(app.reader.value.selectedSessionId)
    }

    @Test fun B11_openingAConversationWhileTheListReadIsOpenIsAnsweredIndependently() {
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val r = last(ReaderKind.SESSIONS)
        at(100); accept(r)
        at(1_000); app.selectSession(work); idle()
        val h = last(ReaderKind.HISTORY)
        at(1_500); answerHistory(h)
        assertEquals("the conversation read settles on its own", LoadStatus.READY, history().status)
        assertEquals("the list read is still open", LoadStatus.LOADING, list().status)
        assertEquals(r.reqId, list().pendingReqId)
    }

    @Test fun B12_aPartArrivingBeforeTheAcceptanceAlsoKeepsTheReadOpen() {
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val r = last(ReaderKind.SESSIONS)
        at(2_000); part(r.reqId, 1, listOf(work))
        at(15_500)
        assertEquals("the Phone app is answering: the first window does not end the read", LoadStatus.LOADING, list().status)
        assertEquals(listOf(work), ids())
        at(30_000); answer(r.reqId, listOf(work, other))
        assertEquals(LoadStatus.READY, list().status)
        assertEquals(listOf(work, other), ids())
    }

    @Test fun B13_aPhoneChangeDuringAProgressiveListDropsTheOldPhonesParts() {
        cached(other)
        mark(); app.refreshSessionTitlesOnResume(); idle()
        val r = last(ReaderKind.SESSIONS)
        at(100); accept(r)
        at(1_000); part(r.reqId, 1, listOf(work))
        phoneNow = "phone-two"
        at(2_000); app.loadSessions(); idle()
        val next = last(ReaderKind.SESSIONS, node = "phone-two")
        at(3_000); part(r.reqId, 2, listOf(work, third))
        at(4_000); answer(r.reqId, listOf(work, third))
        assertTrue("the old Phone's data is gone and stays gone", ids().isEmpty())
        assertEquals(next.reqId, list().pendingReqId)
    }

    @Test fun B14_aListRequestAsksForPartsAndAConversationReadDoesNot() {
        app.refreshSessionTitlesOnResume(); idle()
        last(ReaderKind.SESSIONS)
        assertTrue("the list request asks the Phone for parts", raw.last().optBoolean("progress"))
        app.selectSession(work); idle()
        last(ReaderKind.HISTORY)
        assertFalse(raw.last().has("progress"))
    }
}
