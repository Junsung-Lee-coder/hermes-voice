package com.rumi.hermesvoice.phone

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
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
 * R28s (owner direction: "keep Live streaming as it is; separate connection and data transfer for the rest"): the real
 * PhoneWatchListenerService answers a Watch list read as data that progresses page by page, separate from the control that admitted it.
 * A Watch that asks for parts (`"progress":true`) gets each dashboard page's conversations found so far as a scoped list part
 * (`"part":N`, `"sessions_complete":false`) while later pages are still being read, then the final answer; parts per read are bounded.
 * The app's own recovery run is no longer started at the read's admission, where its list and history reads competed with the Watch's
 * read on the same dashboard: it is requested once the answer was sent. Pages are held with the fake dashboard's own latches
 * (deterministic). Dashboard contract double; Data Layer simulated; synthetic content only.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1280dp")
class PhoneReaderProgressR28sRoboTest : PhoneNavRoboBase() {
    /** A scenario precondition that did not hold: the harness, not the behaviour under test. */
    class Setup(message: String) : RuntimeException(message)

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

    private fun listWire(reqId: String, progress: Boolean = true): ByteArray =
        JSONObject().put("v", 1).put("req_id", reqId).put("kind", "sessions").put("offset", 0).put("limit", 20)
            .apply { if (progress) put("progress", true) }.toString().toByteArray(StandardCharsets.UTF_8)

    private fun historyWire(reqId: String, session: String): ByteArray =
        JSONObject().put("v", 1).put("req_id", reqId).put("kind", "history").put("session_id", session).put("offset", 0).put("limit", 20)
            .toString().toByteArray(StandardCharsets.UTF_8)

    private fun responses(node: String = "watch-node") =
        sent.toList().filter { it.second == WatchLinkPaths.READER_RESPONSE && it.first == node }.map { JSONObject(String(it.third, StandardCharsets.UTF_8)) }
    private fun parts(reqId: String) = responses().filter { it.optString("req_id") == reqId && it.optInt("part", 0) > 0 }
    private fun finals(reqId: String) = responses().filter { it.optString("req_id") == reqId && it.optInt("part", 0) == 0 }
    private fun ids(json: JSONObject) = json.getJSONArray("sessions").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
    private fun listReads() = fake.readArrivals.toList().count { it.first == "list" }

    private fun held(what: String) { if (!entered.await(20, TimeUnit.SECONDS)) throw Setup("$what was not reached") }
    private fun settled(ms: Long = 3_500) { idle(ms); Thread.sleep(ms.coerceAtMost(1_500)); idle(ms) }

    /** [count] registered conversations, oldest first, each followed by 99 newer foreign rows: one registered conversation per 100-row page. */
    private fun spread(count: Int): List<String> {
        val ids = ArrayList<String>()
        for (i in 0 until count) {
            ids += titled("Synthetic $i", "syn$i")
            if (i < count - 1) for (j in 0 until 99) fake.addForeignRow("foreign-r28s-$i-$j", "discord")
        }
        return ids.reversed()  // newest first, the dashboard's order
    }

    @Test fun Q01_aProgressiveListReadSendsThePageOneConversationsBeforeItsHeldPageTwoFinishes() {
        val (newer, older) = spread(2)
        fake.listPageHook = { offset, _ -> if (offset == 100) { entered.countDown(); gate.await(30, TimeUnit.SECONDS) } }
        val service = listener()
        deliver(service, listWire("rd-r28s-q01-00000001"))
        held("the second dashboard page")
        waitFor("page one's conversations reach the Watch while page two is still being read", 10_000) { parts("rd-r28s-q01-00000001").isNotEmpty() }
        assertTrue("no final answer yet", finals("rd-r28s-q01-00000001").isEmpty())
        val p = parts("rd-r28s-q01-00000001").single()
        assertEquals(1, p.getInt("part"))
        assertTrue(p.getBoolean("ok"))
        assertFalse("a part is never a complete list", p.optBoolean("sessions_complete", true))
        assertEquals(listOf(newer), ids(p))
        assertEquals("scoped like the answer", app.sessionListScope(), p.optString("scope"))
        assertEquals(app.bodyScope(), p.optString("body_scope"))
        assertFalse("a part carries no finals", p.has("finals"))
        gate.countDown()
        waitFor("the final answer", 40_000) { finals("rd-r28s-q01-00000001").isNotEmpty() }
        settled()
        val f = finals("rd-r28s-q01-00000001").single()
        assertTrue(f.optBoolean("sessions_complete", true))
        assertEquals(setOf(newer, older), ids(f).toSet())
        assertEquals(1, parts("rd-r28s-q01-00000001").size)
        assertTrue("reading never submits", fake.prompts.isEmpty())
    }

    @Test fun Q02_aWatchThatDoesNotAskForPartsGetsOnlyTheCompleteAnswer() {
        val (newer, older) = spread(2)
        val service = listener()
        deliver(service, listWire("rd-r28s-q02-00000001", progress = false))
        waitFor("the final answer", 40_000) { finals("rd-r28s-q02-00000001").isNotEmpty() }
        settled()
        assertTrue(parts("rd-r28s-q02-00000001").isEmpty())
        assertEquals(setOf(newer, older), ids(finals("rd-r28s-q02-00000001").single()).toSet())
    }

    @Test fun Q03_aWatchListReadStartsNoRecoveryReadBeforeItsAnswerWasSent_andRecoveryStillFollows() {
        titled("Synthetic alpha", "alpha")
        fake.listPageHook = { _, _ -> entered.countDown(); gate.await(30, TimeUnit.SECONDS) }
        val service = listener()
        deliver(service, listWire("rd-r28s-q03-00000001"))
        held("the Watch read's dashboard page")
        Thread.sleep(3_000); idle(500)
        assertEquals("only the Watch read is on the dashboard while it is being answered", 1, listReads())
        gate.countDown()
        waitFor("the final answer", 40_000) { finals("rd-r28s-q03-00000001").isNotEmpty() }
        waitFor("the app's recovery run follows the answer", 20_000) { listReads() >= 2 }
    }

    @Test fun Q04_whileTheListPageIsHeldAConversationReadOfTheSameWatchIsAnswered() {
        val id = titled("Synthetic beta", "beta")
        seed(id, 4, "m")
        fake.listPageHook = { _, _ -> entered.countDown(); gate.await(30, TimeUnit.SECONDS) }
        val service = listener()
        deliver(service, listWire("rd-r28s-q04-00000001"))
        held("the Watch list read's dashboard page")
        deliver(service, historyWire("rd-r28s-q04-00000002", id))
        waitFor("the conversation read is answered while the list is held", 20_000) { finals("rd-r28s-q04-00000002").isNotEmpty() }
        assertTrue(finals("rd-r28s-q04-00000002").single().getBoolean("ok"))
        assertTrue("the list read is still held", finals("rd-r28s-q04-00000001").isEmpty())
        gate.countDown()
        waitFor("the list answer", 40_000) { finals("rd-r28s-q04-00000001").isNotEmpty() }
    }

    @Test fun Q05_listPartsPerReadAreBoundedAndCumulative() {
        val all = spread(10)
        val service = listener()
        deliver(service, listWire("rd-r28s-q05-00000001"))
        waitFor("the final answer", 60_000) { finals("rd-r28s-q05-00000001").isNotEmpty() }
        settled()
        val p = parts("rd-r28s-q05-00000001").sortedBy { it.getInt("part") }
        assertEquals("ten pages, nine of them unfinished: at most eight parts", (1..8).toList(), p.map { it.getInt("part") })
        p.forEachIndexed { i, part -> assertEquals("part ${i + 1} holds everything found so far", all.take(i + 1).toSet(), ids(part).toSet()) }
        assertEquals(all.toSet(), ids(finals("rd-r28s-q05-00000001").single()).toSet())
        assertTrue(fake.prompts.isEmpty())
    }
}
