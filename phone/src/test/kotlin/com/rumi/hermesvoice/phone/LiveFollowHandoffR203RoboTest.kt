package com.rumi.hermesvoice.phone

import android.Manifest
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.phone.live.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * PHONE-R2-03: observing a canonical final (actual FinalRecovery ordering) is not a Live handoff. Only an append the provider
 * acknowledged (context delivery, never "heard") hands it off, so recovery-before-idle cannot consume the late resume, and
 * new arrivals use lossless raw pagination rather than the bounded initial context. Synthetic provider peer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveFollowHandoffR203RoboTest : PhoneRoboBase() {
    private var now = 1000L
    private val media = ArrayList<Media>()
    private class Media(@Volatile var acking: Boolean) : LiveTransport {
        @Volatile var receive: ((String) -> Unit)? = null
        var mic: ((Boolean) -> Unit)? = null
        val packets = java.util.concurrent.ConcurrentLinkedQueue<String>()
        override fun observeMicrophone(listener: (Boolean) -> Unit) { mic = listener }
        override suspend fun connect(history: JSONArray, event: (String) -> Unit) { receive = event; mic?.invoke(true); event("{\"type\":\"session.started\",\"session\":{\"id\":\"provider\"}}") }
        override fun send(json: String): Boolean {
            packets += json
            val j = JSONObject(json); val type = j.optString("type")
            // The provider's documented context-delivery acknowledgement: it is not speech or playback evidence.
            if (acking && type.endsWith(".append")) receive?.invoke(JSONObject().put("type", type.removeSuffix(".append") + ".appended")
                .put("event_id", "ack-" + j.getString("event_id")).put("client_event_id", j.getString("event_id")).put("start_ms", 0).put("end_ms", 1).toString())
            return true
        }
        override fun mute(muted: Boolean) { mic?.invoke(!muted) }
        override fun output(enabled: Boolean) {}
        override fun silence() { mic?.invoke(false) }
        override fun close() {}
        fun spoken(text: String) = packets.count { val j = JSONObject(it); j.optString("type") == "session.commentary.append" && j.optString("content").contains(text) }
        fun context(text: String) = packets.indexOfFirst { JSONObject(it).optString("content").contains(text) }
    }
    @Volatile private var acking = true
    @Volatile private var holdPoll: String? = null
    @Volatile private var pollEntered = CountDownLatch(1)
    @Volatile private var pollGate = CountDownLatch(1)
    @Before fun setUp() {
        baseSetUp(); shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.live.monotonicMs = { now }
        app.live.transportFactory = { Media(acking).also { media += it } }
        app.live.routeFactory = { object : LiveRoute { override fun open() = true; override fun valid() = true; override fun close() {} } }
        app.live.admission = { app.live.admitted(it) }
        // Holds only the active controller's own (non-reconciliation) poll of the bound conversation.
        fake.messagesHook = { id, _, _ ->
            if (holdPoll == id && app.live.state.value.active) { holdPoll = null; pollEntered.countDown(); pollGate.await(15, TimeUnit.SECONDS) }
            0
        }
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled = true))
    }
    @After fun tearDown() { pollGate.countDown(); app.live.end(); settle(); baseTearDown() }
    private fun ready(target: String) {
        assertTrue(app.live.start()); assertTrue(await { app.live.state.value.ready && app.live.state.value.inputActive })
        app.live.bindSession(target); assertTrue(await { app.live.state.value.boundSessionId == target })
    }
    private fun idleLive() { now += app.settings.watchSettings().liveIdleSeconds * 1000; app.live.checkLifetime(); assertFalse(app.live.active); assertEquals("idle", app.live.state.value.endReason) }
    private fun reconcile() {
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        app.appScope.launch { try { app.finalRecovery.reconcile() } finally { done.set(true) } }
        assertTrue("actual canonical reconciliation returned", await { done.get() })
    }

    @Test fun recoveryObservationBeforeIdleDoesNotConsumeTheLateLiveAndMicrophoneResume() {
        val target = conversation("alpha"); ready(target)
        pollEntered = CountDownLatch(1); pollGate = CountDownLatch(1); holdPoll = target
        try {
            assertTrue("the controller's next own poll is held before it can append anything", await { pollEntered.count == 0L })
            fake.injectFinalRow(target, "late-R-final", 1_800_000_000.0, "late-r")
            reconcile(); settle() // FinalRecovery's posted observation of R runs while Live is still active
            assertTrue(app.live.active); assertEquals(1, media.size); assertEquals(0, media[0].spoken("late-R-final"))
            idleLive()
        } finally { pollGate.countDown() }
        reconcile()
        assertTrue("an observed but never handed-off final still resumes Live and the origin microphone",
            await { media.size == 2 && app.live.state.value.ready && app.live.state.value.inputActive && app.live.state.value.boundSessionId == target })
        assertTrue(await { media.last().spoken("late-R-final") == 1 })
        reconcile(); app.finalRecovery.requestReconcile(); settle()
        assertEquals("the exact identity resumes once", 2, media.size); assertEquals(1, media.last().spoken("late-R-final"))
        assertEquals(0, media[0].spoken("late-R-final"))
        assertTrue(fake.prompts.isEmpty()); assertEquals(0, fake.speakRequests.get())
    }

    @Test fun anUnacknowledgedAppendIsNotAHandoffButAnAcknowledgedOneIsAndNeitherIsARead() {
        val target = conversation("alpha")
        acking = false; ready(target)
        fake.injectFinalRow(target, "unacked-final", 1_800_000_000.0, "unacked")
        assertTrue("the active call's own poll appends the arrival", await { media[0].spoken("unacked-final") == 1 })
        idleLive()
        acking = true
        assertTrue("without the provider's append acknowledgement the final is still owed to Live", await { media.size == 2 && app.live.state.value.ready })
        assertTrue(await { media.last().spoken("unacked-final") == 1 })
        idleLive(); reconcile(); settle()
        assertEquals("an acknowledged handoff is not resumed again", 2, media.size)
        fake.injectFinalRow(target, "acked-final", 1_800_000_000.0, "acked")
        reconcile()
        assertTrue(await { media.size == 3 && media.last().spoken("acked-final") == 1 })
        assertTrue(fake.prompts.isEmpty()); assertEquals(0, fake.speakRequests.get())
    }

    @Test fun newArrivalBurstBeyondTwentyAndBeyondOnePageReachesLiveLosslesslyInOrderOnce() {
        val target = conversation("alpha")
        for (i in 1..40) fake.injectRow(target, "user", "old-%02d".format(i))
        ready(target)
        assertTrue("initial context stays bounded", await { media[0].context("old-40") >= 0 })
        assertEquals(-1, media[0].context("old-01"))
        for (i in 1..45) fake.injectRow(target, "user", "burst-%02d".format(i))
        assertTrue("every new eligible row reaches Live, not only the last 20 of the newest 30",
            await(40_000) { (1..45).all { media[0].context("burst-%02d".format(it)) >= 0 } })
        val order = (1..45).map { media[0].context("burst-%02d".format(it)) }
        assertEquals("arrival order is preserved", order.sorted(), order)
        idle(2200); settle()
        for (i in 1..45) assertEquals("burst-%02d once".format(i), 1, media[0].packets.count { JSONObject(it).optString("content").contains("burst-%02d".format(i)) })
        assertEquals(-1, media[0].context("old-01")); assertTrue(fake.prompts.isEmpty())
    }
}
