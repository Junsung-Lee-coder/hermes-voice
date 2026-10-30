package com.rumi.hermesvoice.watch

import android.Manifest
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.live.WatchLiveWire
import com.rumi.hermesvoice.core.settings.*
import com.rumi.hermesvoice.core.wake.*
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import io.mockk.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * R36: the app's own wake recognizer and an explicit Watch Live hold. A real armed background wake session (the existing QA
 * recognizer fixture stands in for the platform recognizer; the window, gap, coordinator, presence and handoff logic are real),
 * the real WatchVoiceRuntime.onTalkPressed hold and the real WatchLiveVoiceRuntime. While the Live call is being set up (pending:
 * started, its media not yet opened by the Phone), no wake window may reopen, including the quiet-close gap's own re-arm;
 * after the call the eligible wake session re-arms as before. The Phone and media are synthetic boundaries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class WatchLiveHoldWakeGapR36RoboTest {
    private class Setup(message: String) : IllegalStateException(message)
    private lateinit var app: WatchApp
    private lateinit var claims: WakeAdmission
    private var service: org.robolectric.android.controller.ServiceController<WatchVoiceService>? = null
    private val sent = ConcurrentLinkedQueue<Pair<String, ByteArray>>()
    private var id = ""; private var media = ""; private var seq = 0L
    private class Peer : WatchLiveMedia {
        var mic: ((Boolean) -> Unit)? = null
        override suspend fun connect(signal: suspend (String) -> String, event: (String) -> Unit) {
            signal("watch-offer"); mic?.invoke(true); event("{\"type\":\"session.started\",\"session\":{\"id\":\"provider36\"}}")
        }
        override fun send(json: String) = true
        override fun observeMicrophone(listener: (Boolean) -> Unit) { mic = listener }
        override fun mute(muted: Boolean) {}; override fun output(enabled: Boolean) {}; override fun silence() { mic?.invoke(false) }; override fun close() {}
    }
    private fun idle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(done: () -> Boolean): Boolean { val end = System.nanoTime() + 8_000_000_000L; while (!done() && System.nanoTime() < end) { idle(20); Thread.sleep(10) }; return done() }
    private fun starts() = sent.filter { it.first == WatchLiveVoiceRuntime.COMMAND }.mapNotNull { runCatching { JSONObject(String(it.second)) }.getOrNull() }.filter { it.optInt("v") == 1 && it.optString("op") == "start" }
    private val listening get() = app.voice.wake.wake.listening
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as WatchApp
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        mockkStatic(Wearable::class)
        val node = mockk<Node>(); every { node.id } returns "phone36"; every { node.isNearby } returns true
        val caps = mockk<CapabilityClient>(); val info = mockk<CapabilityInfo>(); every { info.nodes } returns setOf(node)
        every { caps.getCapability(any(), any()) } returns Tasks.forResult(info); every { Wearable.getCapabilityClient(any<Context>()) } returns caps
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } answers {
            val path = secondArg<String>(); val bytes = thirdArg<ByteArray>().copyOf(); sent += path to bytes
            if (path == WatchLinkPaths.WAKE_CLAIM) {
                val verdict = WakeClaimService.handle(claims, "watch36", bytes)
                if (verdict != null) Handler(Looper.getMainLooper()).post { app.onWakeVerdict("phone36", verdict.encode()) }
            }
            Tasks.forResult(1)
        }
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        every { Wearable.getDataClient(any<Context>()) } returns mockk<DataClient>(relaxed = true)
        every { Wearable.getChannelClient(any<Context>()) } returns mockk<ChannelClient>(relaxed = true)
        claims = WakeAdmission(SystemClock::elapsedRealtime, { app.settings.value })
        app.applySettings(WatchSettings(wakeLocation = WakeLocation.BOTH, wakePatterns = "루미", watchLiveEnabled = true,
            watchBackgroundWakeEnabled = true, watchBackgroundWakeScreenOffEnabled = true, liveIdleSeconds = 10, revision = 3601).toJson())
        runBlocking { app.refreshPhoneReachable() }; app.voice.wake.qaFixtureRecognizer = true
        app.live.now = { SystemClock.elapsedRealtime() }; app.live.admission = { app.live.admitted(it) }
        app.live.routeFactory = { object : WatchLiveRoute { override fun open() = true; override fun valid() = true; override fun close() {} } }
        app.live.mediaFactory = { Peer() }
        app.voice.onUserOpened(); val visit = app.voice.onActivityResumed(); app.voice.onSettingsPulled(visit); app.voice.ensureBackground(); idle()
        val intent = shadowOf(app).nextStartedService ?: throw Setup("Watch standby service missing")
        service = Robolectric.buildService(WatchVoiceService::class.java, intent).create().startCommand(0, 1); idle()
        app.voice.onActivityPaused(); idle()
        if (!waitFor { listening && app.voice.presence.armed }) throw Setup("actual Watch standby not armed")
    }
    @After fun cleanup() { app.live.end(); runCatching { app.voice.stopBackground() }; runCatching { service?.destroy() }; app.scope.cancel(); unmockkAll() }
    /** The open window hears only ambient speech: it closes quietly ("not_matched") and the session schedules its short gap. */
    private fun quietClose() { app.voice.wake.qaHeard("ambient speech only", true); idle(); if (listening) throw Setup("quiet window did not close") }
    /** The explicit Root hold that starts Watch Live (WatchActivity -> WatchVoiceRuntime.onTalkPressed). */
    private fun hold() { if (!app.voice.onTalkPressed()) throw Setup("hold refused"); idle(20); if (!app.live.state.value.pending) throw Setup("hold did not start Live") }
    private fun packet(op: String, body: String = "") = app.live.onStatus("phone36", WatchLiveWire.packet(id, media, "boot36", ++seq, op, body))
    private fun openMedia() {
        id = starts().last().getString("id"); media = "media36"; seq = 0
        packet("open"); idle(); packet("answer", "phone-relay-ready"); packet("event", "{\"type\":\"session.started\",\"session\":{\"id\":\"provider36\"}}")
        if (!waitFor { app.live.state.value.active && !app.live.state.value.pending }) throw Setup("Watch Live media not connected")
    }

    @Test fun H01_aLiveHoldInTheWakeGapKeepsTheRecognizerClosedWhileLiveIsPending() {
        quietClose(); hold()
        idle(WakeContract.MIC_HANDOFF_MS + 200) // the gap's own re-arm comes due while the Phone has not opened the media yet
        assertTrue("the Live call is still being set up", app.live.state.value.pending)
        assertFalse("the app's own wake recognizer must not reopen while its Live call is being set up", listening)
    }

    @Test fun H02_aLiveHoldWhileListeningClosesTheWindowAndNothingReopensWhilePending() {
        hold(); idle(WakeContract.MIC_HANDOFF_MS + 200)
        assertTrue(app.live.state.value.pending); assertFalse(listening)
    }

    @Test fun H03_afterTheLiveCallTheEligibleWakeSessionReArmsAsBefore() {
        quietClose(); hold(); openMedia(); idle(WakeContract.MIC_HANDOFF_MS + 200)
        assertFalse("an active Live call keeps the wake closed", listening)
        app.live.end(); idle()
        assertTrue("the armed background wake session listens again once Live ended (pre-existing eligible ownership)", waitFor { listening })
        assertTrue(app.voice.presence.armed)
    }

    @Test fun H04_aStopDuringThePendingHoldEndsBothAndNothingReArms() {
        quietClose(); hold(); app.voice.stopBackground(); idle(WakeContract.MIC_HANDOFF_MS + 2_000)
        assertFalse(app.live.state.value.active || app.live.state.value.pending); assertFalse(listening); assertFalse(app.voice.presence.armed)
    }
}
