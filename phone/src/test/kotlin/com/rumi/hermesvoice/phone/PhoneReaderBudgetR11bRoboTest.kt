package com.rumi.hermesvoice.phone

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.watchlink.ReaderError
import com.rumi.hermesvoice.core.watchlink.ReaderResponse
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import io.mockk.every
import io.mockk.mockk
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged). R11b: the real PhoneWatchListenerService answers raw Watch reader requests as they arrive on the
 * Data Layer wire. One logical refresh draws on ONE retry allowance across Watch and Phone (a request that says it already spent
 * retries leaves the Phone the rest), and the same request id delivered twice is one piece of work, not two. The wire here is the
 * JSON a Watch sends, not a class from the Watch module. Dashboard contract double; Data Layer simulated; synthetic content only.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1280dp")
class PhoneReaderBudgetR11bRoboTest : PhoneNavRoboBase() {
    private val sent = ConcurrentLinkedQueue<ByteArray>()

    private fun unavailable() = MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json")
        .setBody("""{"detail":"synthetic read fault"}""")

    private fun arrivals(kind: String) = fake.readArrivals.toList().filter { it.first == kind }

    private fun historyWire(reqId: String, session: String, retriesUsed: Any? = null): ByteArray =
        JSONObject().put("v", 1).put("req_id", reqId).put("kind", "history").put("session_id", session).put("offset", 0).put("limit", 20)
            .apply { if (retriesUsed != null) put("retries_used", retriesUsed) }.toString().toByteArray(StandardCharsets.UTF_8)

    private fun deliver(service: org.robolectric.android.controller.ServiceController<PhoneWatchListenerService>, wire: ByteArray, node: String = "watch-node") {
        val event = mockk<MessageEvent>()
        every { event.path } returns WatchLinkPaths.READER_REQUEST
        every { event.sourceNodeId } returns node
        every { event.data } returns wire
        service.get().onMessageReceived(event)
    }

    private fun listener(): org.robolectric.android.controller.ServiceController<PhoneWatchListenerService> {
        every { Wearable.getMessageClient(app).sendMessage(any(), eq(WatchLinkPaths.READER_RESPONSE), any()) } answers {
            sent += thirdArg<ByteArray>(); Tasks.forResult(1)
        }
        return Robolectric.buildService(PhoneWatchListenerService::class.java).create()
    }

    private fun conversation(): String {
        val id = titled("Synthetic alpha", "alpha")
        seed(id, 6, "m")
        return id
    }

    private fun settled(ms: Long = 3_500) { idle(ms); Thread.sleep(ms.coerceAtMost(1_500)); idle(ms) }

    @Test fun P1_aRequestThatSaysItAlreadySpentBothRetriesLeavesThePhoneOneTryOnly() {
        val id = conversation()
        fake.readFault = { kind, _ -> if (kind == "messages") unavailable() else null }
        val service = listener()
        val before = arrivals("messages").size
        deliver(service, historyWire("rd-r11b-p1-00000001", id, retriesUsed = 2))
        waitFor("the answer", 40_000) { sent.isNotEmpty() }
        settled()
        assertEquals("the Watch spent both retries: the Phone reads once and does not start its own two", 1, arrivals("messages").size - before)
        assertEquals(1, sent.size)
        assertEquals(ReaderError.UNAVAILABLE, ReaderResponse.decode(sent.single())!!.error)
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun P2_aRequestThatSaysItSpentOneRetryLeavesThePhoneOneRetry() {
        val id = conversation()
        fake.readFault = { kind, _ -> if (kind == "messages") unavailable() else null }
        val service = listener()
        val before = arrivals("messages").size
        deliver(service, historyWire("rd-r11b-p2-00000001", id, retriesUsed = 1))
        waitFor("the answer", 40_000) { sent.isNotEmpty() }
        settled()
        assertEquals("one Watch retry plus one Phone retry: two reads", 2, arrivals("messages").size - before)
        assertEquals(1, sent.size)
    }

    @Test fun P3_aRequestFromAnOlderWatchWithoutTheFieldKeepsTheFullPhoneAllowance() {
        val id = conversation()
        fake.readFault = { kind, _ -> if (kind == "messages") unavailable() else null }
        val service = listener()
        val before = arrivals("messages").size
        deliver(service, historyWire("rd-r11b-p3-00000001", id))
        waitFor("the answer", 40_000) { sent.isNotEmpty() }
        settled()
        assertEquals("the first try and the Phone's own two retries", 3, arrivals("messages").size - before)
        assertEquals(1, sent.size)
    }

    @Test fun P4_theSameRequestIdDeliveredTwiceWhileItIsBeingServedIsOneRefreshWithOneAnswer() {
        val id = conversation()
        fake.readFault = { kind, _ -> if (kind == "messages") unavailable() else null }
        val service = listener()
        val before = arrivals("messages").size
        val wire = historyWire("rd-r11b-p4-00000001", id)
        deliver(service, wire)
        deliver(service, wire)
        waitFor("the answer", 40_000) { sent.isNotEmpty() }
        settled(4_500)
        assertEquals("one logical refresh: the first try and two retries, not twice that", 3, arrivals("messages").size - before)
        assertEquals("one answer", 1, sent.size)
    }

    @Test fun P5_theSameRequestIdDeliveredAgainAfterItWasAnsweredDoesNotGetAFreshAllowance() {
        val id = conversation()
        fake.readFault = { kind, _ -> if (kind == "messages") unavailable() else null }
        val service = listener()
        val before = arrivals("messages").size
        val wire = historyWire("rd-r11b-p5-00000001", id)
        deliver(service, wire)
        waitFor("the first answer", 40_000) { sent.isNotEmpty() }
        settled()
        assertEquals(3, arrivals("messages").size - before)
        deliver(service, wire)
        waitFor("the second answer", 40_000) { sent.size >= 2 }
        settled()
        assertEquals("the allowance of this refresh is spent: one more read, no more retries", 4, arrivals("messages").size - before)
        assertEquals(2, sent.size)
    }

    @Test fun P6_theSameRequestIdFromAnotherNodeIsAnotherRefresh() {
        val id = conversation()
        fake.readFault = { kind, _ -> if (kind == "messages") unavailable() else null }
        val service = listener()
        val before = arrivals("messages").size
        val wire = historyWire("rd-r11b-p6-00000001", id, retriesUsed = 2)
        deliver(service, wire, node = "watch-node")
        deliver(service, wire, node = "other-watch")
        waitFor("both answers", 40_000) { sent.size >= 2 }
        settled()
        assertEquals("each node asked once", 2, arrivals("messages").size - before)
        assertFalse(sent.isEmpty())
    }
}
