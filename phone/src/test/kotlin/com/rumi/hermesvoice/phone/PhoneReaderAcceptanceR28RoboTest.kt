package com.rumi.hermesvoice.phone

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.watchlink.ReaderResponse
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import io.mockk.every
import io.mockk.mockk
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * R28: the real PhoneWatchListenerService tells the asking Watch node that THIS app holds its exact reader request, at once, while the
 * dashboard read behind the answer is still outstanding. On the owner's devices the answers left the Phone 26 to 34 s after the Watch's
 * request, past the Watch's 15 s window; a Data Layer sendMessage Task that succeeded on the Watch says only that the message left the
 * Watch, so without an application acceptance the Watch cannot tell a Phone that is answering from one that never got the request.
 *
 * Wire: Phone→Watch message `/hv/v1/reader/accepted` to the requesting node only, `{"v":1,"req_id","kind","offset"[,"session_id"]}`:
 * an echo of the request, no content, no account or endpoint. The answer itself is unchanged and is sent once. The dashboard read is
 * held with the fake's own latches (deterministic). Dashboard contract double; Data Layer simulated; synthetic content only.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1280dp")
class PhoneReaderAcceptanceR28RoboTest : PhoneNavRoboBase() {
    /** A scenario precondition that did not hold: the harness, not the behaviour under test. */
    class Setup(message: String) : RuntimeException(message)

    private val accepted = "/hv/v1/reader/accepted"
    private val sent = ConcurrentLinkedQueue<Triple<String, String, ByteArray>>()
    private val gate = CountDownLatch(1)
    private val entered = CountDownLatch(1)

    @After fun releaseHeldReads() = gate.countDown()

    private fun listener(): org.robolectric.android.controller.ServiceController<PhoneWatchListenerService> {
        every { Wearable.getMessageClient(app).sendMessage(any(), any(), any()) } answers {
            sent += Triple(firstArg<String>(), secondArg<String>(), thirdArg<ByteArray>()); Tasks.forResult(1)
        }
        return Robolectric.buildService(PhoneWatchListenerService::class.java).create()
    }

    private fun deliver(service: org.robolectric.android.controller.ServiceController<PhoneWatchListenerService>, wire: ByteArray, node: String = "watch-node") {
        val event = mockk<MessageEvent>()
        every { event.path } returns WatchLinkPaths.READER_REQUEST
        every { event.sourceNodeId } returns node
        every { event.data } returns wire
        service.get().onMessageReceived(event)
    }

    private fun sessionsWire(reqId: String): ByteArray =
        JSONObject().put("v", 1).put("req_id", reqId).put("kind", "sessions").put("offset", 0).put("limit", 20).toString().toByteArray(StandardCharsets.UTF_8)

    private fun historyWire(reqId: String, session: String, offset: Int = 0): ByteArray =
        JSONObject().put("v", 1).put("req_id", reqId).put("kind", "history").put("session_id", session).put("offset", offset).put("limit", 20)
            .toString().toByteArray(StandardCharsets.UTF_8)

    private fun to(path: String, node: String = "watch-node") = sent.toList().filter { it.second == path && it.first == node }
    private fun acceptances(node: String = "watch-node") = to(accepted, node).map { JSONObject(String(it.third, StandardCharsets.UTF_8)) }
    private fun answers(node: String = "watch-node") = to(WatchLinkPaths.READER_RESPONSE, node)
    private fun keys(json: JSONObject) = json.keys().asSequence().toSet()

    private fun held(what: String) { if (!entered.await(20, TimeUnit.SECONDS)) throw Setup("$what was not reached") }
    private fun settled(ms: Long = 3_500) { idle(ms); Thread.sleep(ms.coerceAtMost(1_500)); idle(ms) }

    private fun conversation(): String {
        val id = titled("Synthetic alpha", "alpha")
        seed(id, 6, "m")
        return id
    }

    @Test fun P01_aListRequestIsAcceptedToItsOwnNodeWhileTheDashboardListReadIsStillHeld() {
        conversation()
        fake.listPageHook = { _, _ -> entered.countDown(); gate.await(30, TimeUnit.SECONDS) }
        val service = listener()
        deliver(service, sessionsWire("rd-r28-p01-00000001"))
        held("the dashboard list read")
        waitFor("the Phone tells the asking Watch it holds this exact request", 10_000) { acceptances().isNotEmpty() }
        assertTrue("no answer yet: the read is still held", answers().isEmpty())
        val a = acceptances().single()
        assertEquals("an echo only: no content, account or endpoint", setOf("v", "req_id", "kind", "offset"), keys(a))
        assertEquals(1, a.getInt("v")); assertEquals("rd-r28-p01-00000001", a.getString("req_id")); assertEquals("sessions", a.getString("kind")); assertEquals(0, a.getInt("offset"))
        assertTrue("to the asking node only", sent.none { it.second == accepted && it.first != "watch-node" })
        gate.countDown()
        waitFor("the answer", 40_000) { answers().isNotEmpty() }
        settled()
        assertEquals("one acceptance", 1, acceptances().size)
        assertEquals("one answer", 1, answers().size)
        assertEquals("rd-r28-p01-00000001", ReaderResponse.decode(answers().single().third)!!.reqId)
    }

    @Test fun P02_aHistoryRequestIsAcceptedWithItsConversationAndPageWhileTheHistoryReadIsStillHeld() {
        val id = conversation()
        fake.messagesHook = { _, _, _ -> entered.countDown(); gate.await(30, TimeUnit.SECONDS); 0 }
        val service = listener()
        deliver(service, historyWire("rd-r28-p02-00000001", id))
        held("the dashboard history read")
        waitFor("the Phone tells the asking Watch it holds this exact request", 10_000) { acceptances().isNotEmpty() }
        assertTrue(answers().isEmpty())
        val a = acceptances().single()
        assertEquals(setOf("v", "req_id", "kind", "offset", "session_id"), keys(a))
        assertEquals("history", a.getString("kind")); assertEquals(id, a.getString("session_id")); assertEquals(0, a.getInt("offset"))
        gate.countDown()
        waitFor("the answer", 40_000) { answers().isNotEmpty() }
        settled()
        assertEquals(1, acceptances().size)
        assertEquals(1, answers().size)
        assertTrue(ReaderResponse.decode(answers().single().third)!!.ok)
    }

    @Test fun P03_theSameRequestRepeatedWhileItIsServedIsAcceptedAgainButReadAndAnsweredOnce() {
        val id = conversation()
        fake.messagesHook = { _, _, _ -> entered.countDown(); gate.await(30, TimeUnit.SECONDS); 0 }
        val service = listener()
        val wire = historyWire("rd-r28-p03-00000001", id)
        deliver(service, wire)
        held("the dashboard history read")
        val before = fake.readArrivals.toList().count { it.first == "messages" }
        deliver(service, wire)
        waitFor("both deliveries are acknowledged: the request is still being served", 10_000) { acceptances().size == 2 }
        gate.countDown()
        waitFor("the answer", 40_000) { answers().isNotEmpty() }
        settled(4_500)
        assertEquals("one piece of work", before, fake.readArrivals.toList().count { it.first == "messages" })
        assertEquals("one answer", 1, answers().size)
        assertTrue(acceptances().all { it.getString("req_id") == "rd-r28-p03-00000001" })
    }

    @Test fun P04_anUndecodableOrForeignVersionRequestIsNeverAcceptedOrAnswered() {
        conversation()
        val service = listener()
        deliver(service, "not a request".toByteArray(StandardCharsets.UTF_8))
        deliver(service, JSONObject().put("v", 2).put("req_id", "rd-r28-p04-00000001").put("kind", "sessions").toString().toByteArray(StandardCharsets.UTF_8))
        settled()
        assertTrue(acceptances().isEmpty())
        assertTrue(answers().isEmpty())
    }
}
