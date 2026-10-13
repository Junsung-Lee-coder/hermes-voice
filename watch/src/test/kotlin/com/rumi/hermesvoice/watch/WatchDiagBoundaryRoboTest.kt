package com.rumi.hermesvoice.watch

import android.content.Context
import android.net.Uri
import android.os.Looper
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
import com.rumi.hermesvoice.core.diag.DiagCode
import com.rumi.hermesvoice.core.diag.DiagOrigin
import com.rumi.hermesvoice.core.diag.DiagPseudonyms
import com.rumi.hermesvoice.core.diag.DiagWire
import com.rumi.hermesvoice.core.diag.WatchDiagStatus
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.time.Duration
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * SCRATCH HARNESS (never packaged). v19 self-check R1, diagnostic boundary: a Phone export's request arrives through the REAL
 * WatchListenerService.onMessageReceived and is handled by the REAL WatchApp.onDiagRequest. The Data Layer is simulated (a
 * mocked MessageClient records every send), so this proves the Watch side's routing, validation, answer identity and containment;
 * it is NOT a real Data Layer, a real Phone or a physical Watch.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], shadows = [RecordingVibratorShadow::class])
class WatchDiagBoundaryRoboTest {
    private data class Sent(val node: String, val path: String, val bytes: ByteArray)

    private lateinit var app: WatchApp
    private val sent: MutableList<Sent> = Collections.synchronizedList(mutableListOf())
    private var nextSend: () -> Task<Int> = { Tasks.forResult(1) }
    private val phone = "phone-node"
    private val secretRef = "turn-secret-reference-0001"

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
            sent += Sent(firstArg(), secondArg(), thirdArg())
            nextSend()
        }
        val channels = mockk<ChannelClient>()
        val data = mockk<DataClient>()
        every { data.getDataItems(any<Uri>()) } returns Tasks.forException<DataItemBuffer>(IllegalStateException("harness: no data item"))
        every { Wearable.getCapabilityClient(any<Context>()) } returns capability
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        every { Wearable.getChannelClient(any<Context>()) } returns channels
        every { Wearable.getDataClient(any<Context>()) } returns data
        every { Wearable.getDataClient(any<android.app.Activity>()) } returns data
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun deliver(source: String, path: String, bytes: ByteArray) {
        val service = Robolectric.setupService(WatchListenerService::class.java)
        val event = mockk<MessageEvent>()
        every { event.path } returns path
        every { event.sourceNodeId } returns source
        every { event.data } returns bytes
        service.onMessageReceived(event)
        idle(50)
    }

    private fun responses() = sent.filter { it.path == WatchLinkPaths.DIAG_RESPONSE }

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as WatchApp
        stubWearable()
        app.diag.record(DiagCode.WATCH_SENT, DiagOrigin.WATCH, secretRef, n = 3)
        app.diag.record(DiagCode.WATCH_PLAY_RECEIVED, DiagOrigin.WATCH, secretRef, n = 1)
    }

    @After
    fun tearDown() = unmockkAll()

    @Test fun D01_aValidRequestIsAnsweredOnceToTheAskingNodeWithItsOwnSaltAndOnlyPseudonyms() {
        val salt = DiagPseudonyms.newSalt()
        val before = app.diag.snapshot().size
        deliver("asking-node", WatchLinkPaths.DIAG_REQUEST, DiagWire.encodeRequest(salt))
        val answers = responses()
        assertEquals("exactly one typed answer", 1, answers.size)
        assertEquals("only to the node that asked", "asking-node", answers.single().node)
        val decoded = DiagWire.decodeResponse(answers.single().bytes, salt)
        assertEquals(WatchDiagStatus.REACHABLE, decoded.status)
        assertEquals(before, decoded.events.size)
        assertTrue(decoded.events.size <= DiagWire.MAX_EVENTS)
        assertTrue("bounded", answers.single().bytes.size <= DiagWire.MAX_BYTES)
        val text = String(answers.single().bytes)
        assertFalse("the raw reference never leaves the Watch: $text", text.contains(secretRef))
        assertTrue("the pseudonym is the salted one", text.contains(DiagPseudonyms.of(salt, secretRef)))
        assertEquals("answering changed nothing that was recorded", before, app.diag.snapshot().size)
    }

    @Test fun D02_aForeignSaltIsRejectedByThePhoneSideDecoder() {
        val asked = DiagPseudonyms.newSalt()
        val other = DiagPseudonyms.newSalt()
        deliver(phone, WatchLinkPaths.DIAG_REQUEST, DiagWire.encodeRequest(asked))
        assertEquals(WatchDiagStatus.REACHABLE, DiagWire.decodeResponse(responses().single().bytes, asked).status)
        assertEquals("an answer for another export is never half-accepted", WatchDiagStatus.INVALID,
            DiagWire.decodeResponse(responses().single().bytes, other).status)
    }

    @Test fun D03_twoRequestsFromTwoNodesAreEachAnsweredToTheirOwnNodeWithTheirOwnSalt() {
        val a = DiagPseudonyms.newSalt()
        val b = DiagPseudonyms.newSalt()
        deliver("node-a", WatchLinkPaths.DIAG_REQUEST, DiagWire.encodeRequest(a))
        deliver("node-b", WatchLinkPaths.DIAG_REQUEST, DiagWire.encodeRequest(b))
        val answers = responses()
        assertEquals(listOf("node-a", "node-b"), answers.map { it.node })
        assertEquals(WatchDiagStatus.REACHABLE, DiagWire.decodeResponse(answers[0].bytes, a).status)
        assertEquals(WatchDiagStatus.REACHABLE, DiagWire.decodeResponse(answers[1].bytes, b).status)
        assertEquals(WatchDiagStatus.INVALID, DiagWire.decodeResponse(answers[0].bytes, b).status)
    }

    @Test fun D04_malformedOversizedAndWrongShapedRequestsGetNoAnswerAndChangeNothing() {
        val salt = DiagPseudonyms.newSalt()
        val before = app.diag.snapshot()
        val hostile = listOf(
            ByteArray(0),
            "not json".toByteArray(),
            "{}".toByteArray(),
            """{"schema":1}""".toByteArray(),
            """{"schema":2,"salt":"$salt"}""".toByteArray(),
            """{"schema":1,"salt":"$salt","extra":"x"}""".toByteArray(),
            """{"schema":1,"salt":"short"}""".toByteArray(),
            """{"schema":1,"salt":"${salt.uppercase()}"}""".toByteArray(),
            """{"schema":"1","salt":"$salt"}""".toByteArray(),
            ByteArray(257) { 'x'.code.toByte() },
            ByteArray(300_000) { '{'.code.toByte() },
            (String(DiagWire.encodeRequest(salt)) + " ".repeat(300)).toByteArray(),
        )
        hostile.forEach { deliver(phone, WatchLinkPaths.DIAG_REQUEST, it) }
        assertEquals("no answer to anything that is not exactly a request", 0, responses().size)
        assertEquals(before, app.diag.snapshot())
        deliver(phone, WatchLinkPaths.DIAG_REQUEST, DiagWire.encodeRequest(salt))
        assertEquals("a good request right after still works", 1, responses().size)
    }

    @Test fun D05_aFailedSendIsContainedAndTheNextRequestIsStillAnswered() {
        nextSend = { Tasks.forException(java.io.IOException("harness: node unreachable with a secret-looking message")) }
        val salt = DiagPseudonyms.newSalt()
        deliver(phone, WatchLinkPaths.DIAG_REQUEST, DiagWire.encodeRequest(salt))
        assertEquals("the failed send was attempted once, not retried", 1, responses().size)
        nextSend = { Tasks.forResult(1) }
        deliver(phone, WatchLinkPaths.DIAG_REQUEST, DiagWire.encodeRequest(salt))
        assertEquals(2, responses().size)
    }

    @Test fun D06_aSendThatNeverCompletesIsBoundedAndDoesNotBlockTheHandlerOrLaterRequests() {
        val never = TaskCompletionSource<Int>()
        nextSend = { never.task }
        val salt = DiagPseudonyms.newSalt()
        val started = System.nanoTime()
        deliver(phone, WatchLinkPaths.DIAG_REQUEST, DiagWire.encodeRequest(salt))
        assertTrue("the listener returned at once", (System.nanoTime() - started) / 1_000_000 < 2_000)
        nextSend = { Tasks.forResult(1) }
        deliver(phone, WatchLinkPaths.DIAG_REQUEST, DiagWire.encodeRequest(salt))
        assertEquals("the second request was answered while the first was still pending", 2, responses().size)
        idle(6_000)
        app.diag.record(DiagCode.WATCH_RECORDING, DiagOrigin.WATCH, secretRef, n = 1)
        deliver(phone, WatchLinkPaths.DIAG_REQUEST, DiagWire.encodeRequest(salt))
        assertEquals("the hung send ended at its bound and nothing stayed held", 3, responses().size)
    }

    @Test fun D07_theBufferStaysBoundedAndTheAnswerStaysInsideTheSizeLimitWhateverWasRecorded() {
        repeat(2_000) { app.diag.record(DiagCode.WATCH_PLAY_RECEIVED, DiagOrigin.WATCH, "ref-$it", n = it % 50) }
        val salt = DiagPseudonyms.newSalt()
        deliver(phone, WatchLinkPaths.DIAG_REQUEST, DiagWire.encodeRequest(salt))
        val answer = responses().single().bytes
        assertTrue(answer.size <= DiagWire.MAX_BYTES)
        val decoded = DiagWire.decodeResponse(answer, salt)
        assertEquals(WatchDiagStatus.REACHABLE, decoded.status)
        assertTrue(decoded.events.size <= DiagWire.MAX_EVENTS)
        assertTrue("what did not fit is counted, not hidden", decoded.dropped > 0)
    }

    @Test fun D08_otherPathsAreNotTreatedAsDiagnosticRequests() {
        val salt = DiagPseudonyms.newSalt()
        deliver(phone, WatchLinkPaths.DIAG_RESPONSE, DiagWire.encodeRequest(salt))
        deliver(phone, "/hv/v1/unknown", DiagWire.encodeRequest(salt))
        assertEquals(0, sent.size)
    }
}
