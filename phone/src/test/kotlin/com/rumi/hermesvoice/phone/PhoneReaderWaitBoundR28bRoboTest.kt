package com.rumi.hermesvoice.phone

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
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
import java.util.concurrent.atomic.AtomicBoolean
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
 * R28b (independent review NEEDS_CHANGE): the real PhoneWatchListenerService's LOCAL wait for the Data Layer task of a reader's settling
 * answer is finite, so an answer Task that never completes cannot keep that read admitted in the ledger or hold back the recovery request
 * that follows the answer. The fixture is an unset TaskCompletionSource for the settling answer send; the test releases only its own
 * tasks and latches afterwards. A local wait that ends unresolved means delivery UNKNOWN: the handed-off Task is not cancelled, no second
 * answer is sent automatically and nothing is submitted; a later repeat of the same READ request is a new read-only admission with its own
 * single answer. A failed or throwing answer send, and a dashboard page held across a sign-out or connection change, are fenced the same
 * way (no stale part, ledger released, recovery requested). Dashboard contract double; Data Layer simulated; synthetic content only.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1280dp")
class PhoneReaderWaitBoundR28bRoboTest : PhoneNavRoboBase() {
    /** A scenario precondition that did not hold: the harness, not the behaviour under test. */
    class Setup(message: String) : RuntimeException(message)

    private val sent = ConcurrentLinkedQueue<Triple<String, String, ByteArray>>()
    private val heldAnswers = ConcurrentLinkedQueue<TaskCompletionSource<Int>>()
    /** What a settling answer's send does: "hold" (unset task), "fail" (failed task), "throw" (the call throws) or "ok". */
    @Volatile private var answerSend = "ok"
    private val gate = CountDownLatch(1)
    private val entered = CountDownLatch(1)

    /** Releases only this test's own unresolved tasks and held pages. */
    @After fun releaseOwnFixtures() {
        heldAnswers.forEach { it.trySetResult(1) }
        gate.countDown()
    }

    private fun isAnswer(path: String, bytes: ByteArray) =
        path == WatchLinkPaths.READER_RESPONSE && JSONObject(String(bytes, StandardCharsets.UTF_8)).optInt("part", 0) == 0

    private fun listener(): org.robolectric.android.controller.ServiceController<PhoneWatchListenerService> {
        every { Wearable.getMessageClient(app).sendMessage(any(), any(), any()) } answers {
            sent += Triple(firstArg<String>(), secondArg<String>(), thirdArg<ByteArray>())
            val task: Task<Int> = if (!isAnswer(secondArg(), thirdArg())) Tasks.forResult(1) else when (answerSend) {
                "hold" -> TaskCompletionSource<Int>().also { heldAnswers += it }.task
                "fail" -> Tasks.forException(ApiException(Status(CommonStatusCodes.NETWORK_ERROR)))
                "throw" -> throw IllegalStateException("synthetic Data Layer call failure")
                else -> Tasks.forResult(1)
            }
            task
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

    private fun listWire(reqId: String, progress: Boolean = false): ByteArray =
        JSONObject().put("v", 1).put("req_id", reqId).put("kind", "sessions").put("offset", 0).put("limit", 20)
            .apply { if (progress) put("progress", true) }.toString().toByteArray(StandardCharsets.UTF_8)

    private fun to(path: String, reqId: String) = sent.toList().filter { it.second == path && it.first == "watch-node" }
        .map { JSONObject(String(it.third, StandardCharsets.UTF_8)) }.filter { it.optString("req_id") == reqId }
    private fun answers(reqId: String) = to(WatchLinkPaths.READER_RESPONSE, reqId).filter { it.optInt("part", 0) == 0 }
    private fun parts(reqId: String) = to(WatchLinkPaths.READER_RESPONSE, reqId).filter { it.optInt("part", 0) > 0 }
    private fun acceptances(reqId: String) = to("/hv/v1/reader/accepted", reqId)
    private fun listReads() = fake.readArrivals.toList().count { it.first == "list" }
    private fun settled(ms: Long = 3_500) { idle(ms); Thread.sleep(ms.coerceAtMost(1_500)); idle(ms) }

    /** Two registered conversations a full page apart: the scan needs two dashboard pages. */
    private fun twoPages(): Pair<String, String> {
        val older = titled("Synthetic older", "older")
        for (j in 0 until 99) fake.addForeignRow("foreign-r28b-$j", "discord")
        val newer = titled("Synthetic newer", "newer")
        return newer to older
    }

    @Test fun R01_anAnswerSendThatNeverCompletesReleasesTheReadAndRequestsRecoveryWithinItsLocalBound() {
        titled("Synthetic alpha", "alpha")
        answerSend = "hold"
        val service = listener()
        val wire = listWire("rd-r28b-r01-00000001")
        deliver(service, wire)
        waitFor("the settling answer was handed to the Data Layer", 30_000) { answers("rd-r28b-r01-00000001").size == 1 }
        waitFor("recovery is requested within the local bound although the answer Task never completes", 15_000) { listReads() >= 2 }
        deliver(service, wire)
        waitFor("a repeat of the same read is admitted again and answered once", 30_000) { answers("rd-r28b-r01-00000001").size == 2 }
        settled()
        assertEquals("one answer per admission, no automatic second answer", 2, answers("rd-r28b-r01-00000001").size)
        assertEquals(2, acceptances("rd-r28b-r01-00000001").size)
        assertTrue("reading never submits", fake.prompts.isEmpty())
        heldAnswers.forEach { it.setResult(1) }
        settled()
        assertEquals("a late Task completion sends nothing more", 2, answers("rd-r28b-r01-00000001").size)
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun R02_aProgressiveReadWithAHeldAnswerSendSendsItsPartOnceAndIsReleasedWithinTheBound() {
        twoPages()
        answerSend = "hold"
        val service = listener()
        val wire = listWire("rd-r28b-r02-00000001", progress = true)
        deliver(service, wire)
        waitFor("the settling answer was handed to the Data Layer", 30_000) { answers("rd-r28b-r02-00000001").size == 1 }
        assertEquals("the page-one part went out once, before the answer", 1, parts("rd-r28b-r02-00000001").size)
        waitFor("the held answer's read is released within the bound", 15_000) {
            deliver(service, wire); settled(500); answers("rd-r28b-r02-00000001").size == 2
        }
        settled()
        assertEquals(2, answers("rd-r28b-r02-00000001").size)
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun R03_aFailedAnswerSendStillReleasesTheReadAndRequestsRecovery() {
        titled("Synthetic alpha", "alpha")
        answerSend = "fail"
        val service = listener()
        val wire = listWire("rd-r28b-r03-00000001")
        deliver(service, wire)
        waitFor("the answer send was attempted", 30_000) { answers("rd-r28b-r03-00000001").size == 1 }
        waitFor("recovery is still requested", 15_000) { listReads() >= 2 }
        deliver(service, wire)
        waitFor("the read is released: a repeat is answered again", 30_000) { answers("rd-r28b-r03-00000001").size == 2 }
        settled()
        assertEquals(2, answers("rd-r28b-r03-00000001").size)
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun R04_aThrowingAnswerSendStillReleasesTheReadAndRequestsRecovery() {
        titled("Synthetic alpha", "alpha")
        answerSend = "throw"
        val service = listener()
        val wire = listWire("rd-r28b-r04-00000001")
        deliver(service, wire)
        waitFor("the answer send was attempted", 30_000) { answers("rd-r28b-r04-00000001").size == 1 }
        waitFor("recovery is still requested", 15_000) { listReads() >= 2 }
        deliver(service, wire)
        waitFor("the read is released: a repeat is answered again", 30_000) { answers("rd-r28b-r04-00000001").size == 2 }
        settled()
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun R05_aDashboardPageHeldAcrossASignOutSendsNoStalePart() {
        twoPages()
        val first = AtomicBoolean(true)
        fake.listPageHook = { offset, _ -> if (offset == 0 && first.compareAndSet(true, false)) { entered.countDown(); gate.await(30, TimeUnit.SECONDS) } }
        val service = listener()
        deliver(service, listWire("rd-r28b-r05-00000001", progress = true))
        if (!entered.await(20, TimeUnit.SECONDS)) throw Setup("the first dashboard page was not reached")
        app.tokens.clear()
        gate.countDown()
        waitFor("the read ends", 40_000) { answers("rd-r28b-r05-00000001").isNotEmpty() }
        settled()
        assertTrue("no part of the signed-out account is sent", parts("rd-r28b-r05-00000001").isEmpty())
        val a = answers("rd-r28b-r05-00000001").single()
        assertFalse("the answer carries no list of the signed-out account", a.getBoolean("ok"))
        assertEquals(0, a.getJSONArray("sessions").length())
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun R06_aDashboardPageHeldAcrossAConnectionChangeSendsNoStalePart() {
        twoPages()
        val first = AtomicBoolean(true)
        fake.listPageHook = { offset, _ -> if (offset == 0 && first.compareAndSet(true, false)) { entered.countDown(); gate.await(30, TimeUnit.SECONDS) } }
        val service = listener()
        deliver(service, listWire("rd-r28b-r06-00000001", progress = true))
        if (!entered.await(20, TimeUnit.SECONDS)) throw Setup("the first dashboard page was not reached")
        app.settings.dashboardUrl = "http://127.0.0.1:9/"
        gate.countDown()
        waitFor("the read ends", 40_000) { answers("rd-r28b-r06-00000001").isNotEmpty() }
        settled()
        assertTrue("no part of the previous connection is sent", parts("rd-r28b-r06-00000001").isEmpty())
        val a = answers("rd-r28b-r06-00000001").single()
        assertFalse(a.getBoolean("ok"))
        assertEquals(0, a.getJSONArray("sessions").length())
        assertTrue(fake.prompts.isEmpty())
    }
}
