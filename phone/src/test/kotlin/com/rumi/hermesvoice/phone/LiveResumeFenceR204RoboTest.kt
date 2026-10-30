package com.rumi.hermesvoice.phone

import android.Manifest
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.phone.live.*
import kotlinx.coroutines.CompletableDeferred
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
 * PHONE-R2-04: an automatic late-answer resume admitted for A is fenced by A's follow lease and navigation at every
 * suspension/effect boundary (session list, SDP/provider connect, context history) before microphone, output and commentary.
 * The real PhoneViewModel opens B while each wait is held. Synthetic provider/media peer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveResumeFenceR204RoboTest : PhoneRoboBase() {
    private var now = 1000L
    private val media = ArrayList<Media>()
    private class Media(val gate: CompletableDeferred<Unit>?) : LiveTransport {
        @Volatile var closed = false; @Volatile var outputEnabled = false; @Volatile var silenced = false; @Volatile var micActive = false
        var mic: ((Boolean) -> Unit)? = null
        val packets = java.util.concurrent.ConcurrentLinkedQueue<String>()
        override fun observeMicrophone(listener: (Boolean) -> Unit) { mic = listener }
        override suspend fun connect(history: JSONArray, event: (String) -> Unit) {
            gate?.await() // the provider SDP answer is still outstanding
            check(!closed) { "a closed transport never applies the remote description" } // as WebRtcLiveTransport.connect
            micActive = true; mic?.invoke(true)
            event("{\"type\":\"session.started\",\"session\":{\"id\":\"provider\"}}")
        }
        override fun send(json: String): Boolean { packets += json; return true }
        override fun mute(muted: Boolean) {}
        override fun output(enabled: Boolean) { outputEnabled = enabled }
        override fun silence() { silenced = true; micActive = false; outputEnabled = false; mic?.invoke(false) }
        override fun close() { closed = true }
        fun spoken(text: String) = packets.count { val j = JSONObject(it); j.optString("type") == "session.commentary.append" && j.optString("content").contains(text) }
    }
    @Volatile private var connectGate: CompletableDeferred<Unit>? = null
    @Volatile private var holdList = false
    @Volatile private var listEntered = CountDownLatch(1)
    @Volatile private var listGate = CountDownLatch(1)
    @Volatile private var holdHistory: String? = null
    @Volatile private var historyEntered = CountDownLatch(1)
    @Volatile private var historyGate = CountDownLatch(1)
    @Before fun setUp() {
        baseSetUp(); shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.live.monotonicMs = { now }
        app.live.transportFactory = { Media(connectGate).also { media += it } }
        app.live.routeFactory = { object : LiveRoute { override fun open() = true; override fun valid() = true; override fun close() {} } }
        app.live.admission = { app.live.admitted(it) }
        fake.listPageHook = { _, _ -> if (holdList && app.live.state.value.active) { holdList = false; listEntered.countDown(); listGate.await(15, TimeUnit.SECONDS) } }
        fake.messagesHook = { id, _, _ -> if (holdHistory == id && app.live.state.value.active) { holdHistory = null; historyEntered.countDown(); historyGate.await(15, TimeUnit.SECONDS) }; 0 }
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled = true))
    }
    @After fun tearDown() { listGate.countDown(); historyGate.countDown(); connectGate?.complete(Unit); app.live.end(); settle(); baseTearDown() }
    private fun ready(target: String) {
        assertTrue(app.live.start()); assertTrue(await { app.live.state.value.ready && app.live.state.value.inputActive })
        app.live.bindSession(target); assertTrue(await { app.live.state.value.boundSessionId == target })
    }
    private fun idleLive() { now += app.settings.watchSettings().liveIdleSeconds * 1000; app.live.checkLifetime(); assertFalse(app.live.active); assertEquals("idle", app.live.state.value.endReason) }
    private fun arrival(target: String, text: String) {
        fake.injectFinalRow(target, text, 1_800_000_000.0, "uid-$text")
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        app.appScope.launch { try { app.finalRecovery.reconcile() } finally { done.set(true) } }
        assertTrue("actual canonical reconciliation returned", await { done.get() })
    }
    private fun navigate(model: PhoneViewModel, to: String) {
        model.openOrResume(app.wiring().core.sessions.registry.find(to)!!); assertTrue(await { app.selectedConversationId == to })
    }
    private fun assertRevokedResume(target: String, text: String) {
        settle()
        assertFalse("no microphone under revoked authority", media.drop(1).any { it.micActive })
        assertFalse("no output under revoked authority", media.drop(1).any { it.outputEnabled })
        assertTrue("no commentary under revoked authority", media.all { it.spoken(text) == 0 })
        assertFalse(app.live.active); assertFalse(app.phoneMic.occupied)
        assertTrue("A's canonical text is retained", fake.rows.getValue(target).messages.any { it.optString("content") == text })
        assertTrue(fake.prompts.isEmpty()); assertEquals(0, fake.speakRequests.get())
    }

    @Test fun navigationWhileResumeWaitsForTheSessionListStopsBeforeNativeMedia() {
        val target = conversation("alpha"); val other = conversation("other"); val model = PhoneViewModel(app); settle()
        ready(target); idleLive()
        listEntered = CountDownLatch(1); listGate = CountDownLatch(1); holdList = true
        try {
            arrival(target, "late-list")
            assertTrue("the resume admitted for A is waiting on its session list", await { listEntered.count == 0L })
            navigate(model, other)
        } finally { listGate.countDown() }
        assertRevokedResume(target, "late-list")
        assertEquals("no native media is even created", 1, media.size)
    }

    @Test fun navigationWhileResumeWaitsForTheProviderSdpNeverStartsMicOrOutput() {
        val target = conversation("alpha"); val other = conversation("other"); val model = PhoneViewModel(app); settle()
        ready(target); idleLive()
        val gate = CompletableDeferred<Unit>(); connectGate = gate
        try {
            arrival(target, "late-sdp")
            assertTrue("the resume admitted for A is waiting on its SDP answer", await { media.size == 2 })
            navigate(model, other)
        } finally { gate.complete(Unit) }
        assertRevokedResume(target, "late-sdp")
        assertFalse(media.last().micActive)
    }

    @Test fun navigationWhileResumeWaitsForContextHistoryNeverDeliversCommentaryOrKeepsTheMic() {
        val target = conversation("alpha"); val other = conversation("other"); val model = PhoneViewModel(app); settle()
        ready(target); idleLive()
        historyEntered = CountDownLatch(1); historyGate = CountDownLatch(1); holdHistory = target
        try {
            arrival(target, "late-context")
            assertTrue("the resume admitted for A is waiting on A's context history", await { historyEntered.count == 0L && media.size == 2 })
            navigate(model, other)
        } finally { historyGate.countDown() }
        settle()
        assertTrue("no commentary under revoked authority", media.all { it.spoken("late-context") == 0 })
        assertFalse("the resumed microphone is closed when its authority is revoked", app.live.active)
        assertFalse(media.last().micActive); assertFalse(app.phoneMic.occupied)
        assertTrue(fake.rows.getValue(target).messages.any { it.optString("content") == "late-context" })
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun unchangedNavigationResumesAndIntentionalManualBindingIsNotNavigationFenced() {
        val target = conversation("alpha"); val other = conversation("other"); val model = PhoneViewModel(app); settle()
        ready(target); idleLive()
        arrival(target, "late-positive")
        assertTrue("control: the same authority resumes Live and the microphone", await { media.size == 2 && app.live.state.value.ready && media.last().micActive })
        assertTrue(await { media.last().spoken("late-positive") == 1 })
        app.live.end(); settle()
        navigate(model, other)
        assertTrue(app.live.start()); assertTrue(await { app.live.state.value.ready })
        app.live.bindSession(target); assertTrue("a deliberate manual binding is independent of the open conversation", await { app.live.state.value.boundSessionId == target })
        navigate(model, target); navigate(model, other)
        fake.injectFinalRow(target, "manual-arrival", 1_800_000_000.0, "manual")
        assertTrue(await { media.last().spoken("manual-arrival") == 1 })
        assertTrue(app.live.active)
    }
}
