package com.rumi.hermesvoice.watch

import android.Manifest
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.google.android.gms.tasks.TaskCompletionSource
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
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.shadows.ShadowLog
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random

/**
 * R37: every wake window the app opens across a Watch Live transition. A real armed background wake session (the existing QA
 * recognizer fixture stands in for the platform recognizer at the one place a recognizer is started, which logs "wake window
 * opened"; the window, gap, coordinator, presence, claim and handoff logic are real), the real WatchVoiceRuntime hold and Stop,
 * the real WatchVoiceService notification Stop and the real WatchLiveVoiceRuntime. The tests count those openings from the moment
 * of the hold, the wake request or the Stop, not only the final state: restarting a control kept after a refusal or an idle end,
 * the Phone's media open of a pending call, a failed End or Start of an older control, a failed status probe of a call being set up,
 * and the user's Stop of a pending or active call, open none. R38 (the parent's T6 decision): a failed send of the current call's own
 * Start is uncertain delivery, not a no-effect receipt (sendMessage is best-effort), so that call stays pending while it awaits its
 * media; correctly bound late media is still admitted, the next hold is its Stop, and nothing raises pending after a definitive end.
 * The Phone, its claim admission and the media are synthetic boundaries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class WatchLiveTransitionWakeR37RoboTest {
    private class Setup(message: String) : IllegalStateException(message)
    private lateinit var app: WatchApp
    private lateinit var claims: WakeAdmission
    private var service: org.robolectric.android.controller.ServiceController<WatchVoiceService>? = null
    private val sent = ConcurrentLinkedQueue<Pair<String, ByteArray>>()
    private val peers = ArrayList<Peer>()
    private var id = ""; private var media = ""; private var seq = 0L; private var phoneSeq = 0L; private var liveNow = 1_000L
    private var routeLost: (() -> Unit)? = null
    /** v1 End sends (by control id) whose Data Layer Task the test fails later, as an unreachable Phone does. */
    private val endFailures = HashMap<String, TaskCompletionSource<Int>>()
    /** The next v1 Start send's Data Layer Task, which the test fails later. */
    private var nextStartFailure: TaskCompletionSource<Int>? = null
    /** The next v1 status probe's Data Layer Task, which the test fails later. */
    private var nextStatusFailure: TaskCompletionSource<Int>? = null
    private val base = WatchSettings(wakeLocation = WakeLocation.BOTH, wakePatterns = "루미", watchLiveEnabled = true,
        watchBackgroundWakeEnabled = true, watchBackgroundWakeScreenOffEnabled = true, liveIdleSeconds = 10, revision = 3701)
    private class Peer : WatchLiveMedia {
        var mic: ((Boolean) -> Unit)? = null; var closed = false
        override suspend fun connect(signal: suspend (String) -> String, event: (String) -> Unit) {
            signal("watch-offer"); mic?.invoke(true); event("{\"type\":\"session.started\",\"session\":{\"id\":\"provider37\"}}")
        }
        override fun send(json: String) = true
        override fun observeMicrophone(listener: (Boolean) -> Unit) { mic = listener }
        override fun mute(muted: Boolean) {}; override fun output(enabled: Boolean) {}; override fun silence() { mic?.invoke(false) }; override fun close() { closed = true }
    }
    private fun idle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(done: () -> Boolean): Boolean { val end = System.nanoTime() + 8_000_000_000L; while (!done() && System.nanoTime() < end) { idle(20); Thread.sleep(10) }; return done() }
    private fun ops(op: String) = sent.filter { it.first == WatchLiveVoiceRuntime.COMMAND }.mapNotNull { runCatching { JSONObject(String(it.second)) }.getOrNull() }.filter { it.optInt("v") == 1 && it.optString("op") == op }
    private fun starts() = ops("start")
    private fun releases(claim: String) = sent.count { it.first == WatchLinkPaths.WAKE_CLAIM && WakeClaimMessage.decode(it.second)?.let { m -> m.claimId == claim && m.op == WakeClaimMessage.Op.RELEASE } == true }
    private val listening get() = app.voice.wake.wake.listening
    /** Every recognizer the wake flow started so far: the one place a window acquires it logs this line (fixture or platform). */
    private fun windows() = ShadowLog.getLogsForTag("HermesVoiceWake").count { it.msg.startsWith("wake window opened") }

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as WatchApp
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        mockkStatic(Wearable::class)
        val node = mockk<Node>(); every { node.id } returns "phone37"; every { node.isNearby } returns true
        val caps = mockk<CapabilityClient>(); val info = mockk<CapabilityInfo>(); every { info.nodes } returns setOf(node)
        every { caps.getCapability(any(), any()) } returns Tasks.forResult(info); every { Wearable.getCapabilityClient(any<Context>()) } returns caps
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } answers {
            val path = secondArg<String>(); val bytes = thirdArg<ByteArray>().copyOf(); sent += path to bytes
            val control = if (path == WatchLiveVoiceRuntime.COMMAND) runCatching { JSONObject(String(bytes)) }.getOrNull() else null
            val failing = control?.takeIf { it.optInt("v") == 1 && it.optString("op") == "end" }?.let { endFailures[it.optString("id")] }
            if (failing != null) return@answers failing.task
            if (control != null && control.optInt("v") == 1 && control.optString("op") == "start") nextStartFailure?.let { nextStartFailure = null; return@answers it.task }
            if (control != null && control.optInt("v") == 1 && control.optString("op") == "status") nextStatusFailure?.let { nextStatusFailure = null; return@answers it.task }
            if (path == WatchLinkPaths.WAKE_CLAIM) {
                val verdict = WakeClaimService.handle(claims, "watch37", bytes)
                if (verdict != null) Handler(Looper.getMainLooper()).post { app.onWakeVerdict("phone37", verdict.encode()) }
            }
            Tasks.forResult(1)
        }
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        every { Wearable.getDataClient(any<Context>()) } returns mockk<DataClient>(relaxed = true)
        every { Wearable.getChannelClient(any<Context>()) } returns mockk<ChannelClient>(relaxed = true)
        claims = WakeAdmission(SystemClock::elapsedRealtime, { app.settings.value })
        app.applySettings(base.toJson())
        runBlocking { app.refreshPhoneReachable() }; app.voice.wake.qaFixtureRecognizer = true
        app.live.now = { liveNow }; app.live.admission = { app.live.admitted(it) }
        app.live.routeFactory = { lost -> routeLost = lost; object : WatchLiveRoute { override fun open() = true; override fun valid() = true; override fun close() {} } }
        app.live.mediaFactory = { Peer().also { peers += it } }
        app.voice.onUserOpened(); val visit = app.voice.onActivityResumed(); app.voice.onSettingsPulled(visit); app.voice.ensureBackground(); idle()
        val intent = shadowOf(app).nextStartedService ?: throw Setup("Watch standby service missing")
        service = Robolectric.buildService(WatchVoiceService::class.java, intent).create().startCommand(0, 1); idle()
        app.voice.onActivityPaused(); idle()
        if (!waitFor { listening && app.voice.presence.armed }) throw Setup("actual Watch standby not armed")
    }
    @After fun cleanup() { app.live.end(); runCatching { app.voice.stopBackground() }; runCatching { service?.destroy() }; app.scope.cancel(); unmockkAll() }

    /** WatchActivity.onResume: the app on screen, its armed session listening under the foreground location (the Root hold needs it). */
    private fun show() {
        app.onForeground(true); val visit = app.voice.onActivityResumed(); app.voice.ensureBackground()
        runBlocking { app.refreshPhoneReachable() }; app.voice.onSettingsPulled(visit); idle()
        if (!waitFor { listening && app.voice.presence.armed && app.voice.presence.visible }) throw Setup("the visible app's armed wake is not listening")
    }
    /** WatchActivity.onPause: the app leaves the screen; the armed session goes on. */
    private fun hide() { app.voice.onActivityPaused(); app.onForeground(false); idle() }
    /** The open window hears only ambient speech: it closes quietly ("not_matched") and the session schedules its short gap. */
    private fun quietClose() { app.voice.wake.qaHeard("ambient speech only", true); idle(); if (listening) throw Setup("quiet window did not close") }
    /** The Root hold (WatchActivity.onTalkPressed -> WatchVoiceRuntime.onTalkPressed). */
    private fun hold() { if (!app.voice.onTalkPressed()) throw Setup("hold refused"); idle(20) }
    private fun packet(op: String, body: String = "") = app.live.onStatus("phone37", WatchLiveWire.packet(id, media, "boot37", ++seq, op, body))
    private fun openMedia() {
        id = starts().last().getString("id"); media = "media37-" + starts().size; seq = 0
        packet("open"); idle(); packet("answer", "phone-relay-ready"); packet("event", "{\"type\":\"session.started\",\"session\":{\"id\":\"provider37\"}}")
        if (!waitFor { app.live.state.value.active && !app.live.state.value.pending }) throw Setup("Watch Live media not connected")
    }
    /** The Phone's v1 end status for the current control (PhoneLiveWatchControl.sendState shape). */
    private fun phoneEnd(endReason: String) = app.live.onStatus("phone37", JSONObject().put("v", 1).put("id", id).put("epoch", "boot37").put("seq", ++phoneSeq)
        .put("probe", "").put("media", media).put("phone_audio", false).put("watch_audio", true).put("active", false).put("muted", false)
        .put("end_reason", endReason).put("status", "Phone Live ended").toString().toByteArray())
    /** A call refused by the Watch's own route loss (step 17) before the Phone's end status arrived: its control is still kept. */
    private fun keptAfterRouteLoss() {
        hold(); openMedia(); routeLost!!.invoke(); idle()
        if (app.live.state.value.active || app.live.state.value.pending) throw Setup("route loss did not refuse the call")
        if (!waitFor { listening }) throw Setup("the eligible wake did not re-arm after the refused call")
    }
    /** A call that went idle (LiveLifetime "idle"): its control is kept so an authorized late answer may reopen it. */
    private fun keptAfterIdle() {
        hold(); openMedia(); liveNow += 11_000; app.live.checkLifetime(); idle()
        if (app.live.state.value.active || app.live.state.value.pending || peers.last().closed.not()) throw Setup("the call did not go idle")
        if (!waitFor { listening }) throw Setup("the eligible wake did not re-arm after the idle end")
    }
    /** The session notification's own Stop action, delivered to WatchVoiceService as the platform delivers its PendingIntent. */
    private fun notificationStop() {
        val shown = shadowOf(service!!.get()).lastForegroundNotification ?: throw Setup("standby notification missing")
        val stop = shown.actions?.firstOrNull { it.title?.toString() == "Stop" } ?: throw Setup("notification Stop missing")
        service!!.get().onStartCommand(shadowOf(stop.actionIntent).savedIntent, 0, 2)
    }
    /** WatchActivity.onResume again: the resume probes a kept or pending call (refresh). */
    private fun resume() { app.onForeground(true); val visit = app.voice.onActivityResumed(); app.voice.ensureBackground(); runBlocking { app.refreshPhoneReachable() }; app.voice.onSettingsPulled(visit); idle() }
    /** A hold whose Start send the Data Layer reports as failed: sendMessage is best-effort, so its delivery is uncertain. */
    private fun holdWithFailedStart(): String {
        val failing = TaskCompletionSource<Int>(); nextStartFailure = failing
        hold(); if (!app.live.state.value.pending) throw Setup("hold did not start Live")
        val started = starts().last().getString("id")
        failing.setException(IllegalStateException("the Data Layer reported the Start as failed")); idle()
        return started
    }
    /** The Phone's v1 status for the current control before any media (its epoch is bound by the answered probe). */
    private fun phoneStatus(active: Boolean, endReason: String?, probe: String) = app.live.onStatus("phone37", JSONObject().put("v", 1).put("id", id).put("epoch", "boot37")
        .put("seq", ++phoneSeq).put("probe", probe).put("media", media).put("phone_audio", false).put("watch_audio", true).put("active", active).put("muted", false)
        .apply { if (endReason != null) put("end_reason", endReason) }.put("status", "Phone Live").toString().toByteArray())
    private fun startNoisePcm() {
        val random = Random(37)
        ShadowAudioRecord.setSourceProvider {
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int, isBlocking: Boolean): Int {
                    for (i in 0 until sizeInBytes / 2) { val s = random.nextInt(-40, 40); audioData[offsetInBytes + 2 * i] = (s and 0xff).toByte(); audioData[offsetInBytes + 2 * i + 1] = (s shr 8 and 0xff).toByte() }
                    Thread.sleep(5); return sizeInBytes - sizeInBytes % 2
                }
            }
        }
    }

    // ── the R36 pending gate from the visible app (the real Root hold is visible-only) ───────────────────────────────

    @Test fun T01_aVisibleHoldInTheQuietGapOpensNoWindowWhileLiveIsPending() {
        show(); quietClose(); val mark = windows(); hold()
        idle(WakeContract.MIC_HANDOFF_MS + 200) // the gap's own re-arm comes due while the Phone has not opened the media yet
        assertTrue("the Live call is being set up", app.live.state.value.pending)
        assertEquals("no wake window or recognizer from the hold on while Live is pending", mark, windows()); assertFalse(listening)
    }

    @Test fun T02_aPhoneSettingsPushWhileLiveIsPendingOpensNoWindow() {
        show(); hold(); assertTrue(app.live.state.value.pending); val mark = windows()
        app.applySettings(base.copy(revision = 3702).toJson()); idle(WakeContract.MIC_HANDOFF_MS + 200) // WatchListenerService SETTINGS -> applySettings
        assertTrue(app.live.state.value.pending)
        assertEquals("a settings re-evaluation during the pending call opens no window", mark, windows()); assertFalse(listening)
    }

    // ── V1: restarting a control kept after a refusal or an idle end ─────────────────────────────────────────────────

    @Test fun T03_aHoldOnAControlKeptAfterARouteLossOpensNoWindowAcrossTheRestart() {
        show(); keptAfterRouteLoss(); val old = id; val mark = windows()
        hold()
        assertTrue("the hold restarted the kept control as a new pending call", app.live.state.value.pending)
        assertEquals("the kept control was ended once and one new control started", listOf(old), ops("end").map { it.getString("id") })
        assertEquals(2, starts().size)
        assertEquals("no wake window or recognizer was acquired between the old control's end and the new pending state", mark, windows())
        idle(WakeContract.MIC_HANDOFF_MS + 200); assertEquals(mark, windows()); assertFalse(listening)
    }

    @Test fun T04_aHoldOnAControlKeptAfterAnIdleEndOpensNoWindowAcrossTheRestart() {
        show(); keptAfterIdle(); val old = id; val mark = windows()
        hold()
        assertTrue(app.live.state.value.pending)
        assertEquals(listOf(old), ops("end").map { it.getString("id") }); assertEquals(2, starts().size)
        assertEquals("no wake window or recognizer was acquired between the old control's end and the new pending state", mark, windows())
        idle(WakeContract.MIC_HANDOFF_MS + 200); assertEquals(mark, windows()); assertFalse(listening)
    }

    @Test fun T05_aRecognizedWakeRequestOnAKeptControlOpensNoWindowAcrossTheRestart() {
        show(); keptAfterIdle(); val old = id; val mark = windows(); val before = starts().size
        app.voice.wake.qaHeard("루미 오늘 날씨 알려줘", true)
        if (!waitFor { starts().size > before }) throw Setup("the recognized wake request did not reach Live control")
        val start = starts().last()
        assertTrue(start.optBoolean("wake")); assertEquals("오늘 날씨 알려줘", start.optString("recognized")); assertTrue(start.has("wake_claim_id"))
        assertEquals(listOf(old), ops("end").map { it.getString("id") }); assertTrue(app.live.state.value.pending)
        assertEquals("no wake window or recognizer was acquired between the old control's end and the new pending state", mark, windows())
        idle(WakeContract.MIC_HANDOFF_MS + 200); assertEquals(mark, windows()); assertFalse(listening)
    }

    @Test fun T06_aPhraseOnlyWakeOnAKeptControlOpensNoWindowAcrossTheRestart() {
        show(); keptAfterIdle(); val mark = windows(); val before = starts().size
        app.voice.wake.qaHeard("루미", true)
        if (!waitFor { starts().size > before }) throw Setup("the phrase-only handoff did not reach Live control")
        assertTrue(starts().last().optBoolean("wake")); assertTrue(app.live.state.value.pending)
        assertEquals("the handoff's capture custody keeps the wake closed across the restart", mark, windows())
        idle(WakeContract.MIC_HANDOFF_MS + 200); assertEquals(mark, windows()); assertFalse(listening)
    }

    @Test fun T07_aControlTheRefusedCallsPhoneEndClearedStartsFreshWithoutAnEnd() {
        show(); hold(); openMedia(); routeLost!!.invoke(); idle(); phoneEnd("failure"); idle()
        if (!waitFor { listening }) throw Setup("the eligible wake did not re-arm after the refused call")
        val mark = windows(); hold()
        assertTrue(app.live.state.value.pending); assertTrue("no kept control: nothing to end", ops("end").isEmpty()); assertEquals(2, starts().size)
        assertEquals(mark, windows())
    }

    // ── V8: the user's Stop of a pending or active call ──────────────────────────────────────────────────────────────

    @Test fun T08_theNotificationStopOfAPendingHeldCallOpensNoWindowAndStaleCallbacksDoNothing() {
        show(); hold(); assertTrue(app.live.state.value.pending); id = starts().last().getString("id"); hide(); val mark = windows()
        notificationStop()
        assertEquals("no wake window or recognizer from the Stop on", mark, windows())
        idle(WakeContract.MIC_HANDOFF_MS + 2_000)
        assertFalse(app.live.state.value.pending || app.live.state.value.active); assertFalse(listening); assertFalse(app.voice.presence.armed)
        assertEquals("the pending control ended once", listOf(id), ops("end").map { it.getString("id") })
        // Stale callbacks after the Stop: the Phone's late media open, a late gap alarm and a late recognizer result act on nothing.
        media = "late37"; seq = 0; packet("open"); app.voice.onRearmAlarm(); app.voice.wake.qaHeard("루미", true); idle(WakeContract.MIC_HANDOFF_MS + 2_000)
        assertTrue("no Watch media after the Stop", peers.isEmpty()); assertFalse(app.live.state.value.pending || app.live.state.value.active)
        assertEquals(mark, windows()); assertFalse(listening); assertEquals(1, starts().size)
    }

    @Test fun T09_theNotificationStopOfAPendingWakeStartedCallOpensNoWindowAndNeverReturnsItsSentClaim() {
        val before = starts().size; app.voice.wake.qaHeard("루미", true)
        if (!waitFor { starts().size > before }) throw Setup("the phrase-only handoff did not reach Live control")
        val start = starts().last(); val claim = start.getString("wake_claim_id"); id = start.getString("id")
        assertTrue(app.live.state.value.pending); val mark = windows()
        notificationStop()
        assertEquals("no wake window or recognizer from the Stop on", mark, windows())
        idle(WakeContract.MIC_HANDOFF_MS + 2_000)
        assertFalse(app.live.state.value.pending || app.live.state.value.active); assertFalse(listening); assertFalse(app.voice.presence.armed)
        assertEquals(listOf(id), ops("end").map { it.getString("id") })
        assertEquals("a claim already sent with the Start is never given back", 0, releases(claim)); assertEquals(1, starts().size - before)
        assertEquals(mark, windows())
    }

    @Test fun T10_theNotificationStopOfAnActiveCallOpensNoWindowAndClosesItsMedia() {
        show(); hold(); openMedia(); hide(); val mark = windows()
        notificationStop()
        assertEquals("no wake window or recognizer from the Stop on", mark, windows())
        idle(WakeContract.MIC_HANDOFF_MS + 2_000)
        assertTrue(peers.single().closed); assertFalse(app.live.state.value.active || app.live.state.value.inputActive)
        assertFalse(listening); assertFalse(app.voice.presence.armed); assertEquals(mark, windows())
    }

    // ── controls: eligible wake after Live, Live OFF and the ordinary recording ──────────────────────────────────────

    @Test fun T11_liveSwitchedOffWhilePendingEndsTheCallAndTheEligibleWakeListensAgain() {
        show(); hold(); id = starts().last().getString("id"); assertTrue(app.live.state.value.pending)
        app.applySettings(base.copy(watchLiveEnabled = false, revision = 3702).toJson()); idle()
        assertFalse(app.live.state.value.pending || app.live.state.value.active); assertEquals(listOf(id), ops("end").map { it.getString("id") })
        assertTrue("the armed wake session listens again once Live is off", waitFor { listening })
    }

    @Test fun T12_withLiveOffTheHoldRecordsOrdinarilyAndNoWindowOpensWhileItRecords() {
        app.applySettings(base.copy(watchLiveEnabled = false, revision = 3702).toJson()); idle(); startNoisePcm(); show(); val mark = windows()
        assertTrue(app.voice.onTalkPressed()); idle(WakeContract.MIC_HANDOFF_MS + 100) // the hold in the open window: recognizer released, recording after the pause
        assertTrue("the ordinary Watch recording runs", app.voice.capturing); assertTrue("no Live call", starts().isEmpty())
        idle(1_000); assertEquals("no wake window while the recording runs", mark, windows()); assertFalse(listening)
        assertTrue(app.voice.onTalkPressed()); idle(); assertFalse("the second hold ends the recording", app.voice.capturing)
    }

    // ── the Phone's media open and a failed old End: no idle state while the call is the microphone's owner ──────────

    @Test fun T13_thePhonesMediaOpenOfAPendingHeldCallOpensNoWindow() {
        show(); hold(); assertTrue(app.live.state.value.pending); val mark = windows()
        openMedia()
        assertEquals("no wake window or recognizer from the hold through the media open", mark, windows())
        idle(WakeContract.MIC_HANDOFF_MS + 200); assertEquals(mark, windows()); assertFalse(listening)
    }

    @Test fun T14_thePhonesMediaOpenOfARecognizedWakeRequestCallOpensNoWindow() {
        val before = starts().size; app.voice.wake.qaHeard("루미 오늘 날씨 알려줘", true)
        if (!waitFor { starts().size > before }) throw Setup("the recognized wake request did not reach Live control")
        assertTrue(app.live.state.value.pending); val mark = windows()
        openMedia()
        assertEquals("no wake window or recognizer from the wake request through the media open", mark, windows())
        idle(WakeContract.MIC_HANDOFF_MS + 200); assertEquals(mark, windows()); assertFalse(listening)
    }

    @Test fun T15_aFailedEndOfTheOldControlLeavesTheRestartedCallPendingWithNoWindow() {
        show(); keptAfterIdle(); val old = id; val failing = TaskCompletionSource<Int>(); endFailures[old] = failing; val mark = windows()
        hold(); assertTrue("the hold restarted the kept control as a new pending call", app.live.state.value.pending)
        assertEquals(listOf(old), ops("end").map { it.getString("id") })
        failing.setException(IllegalStateException("the Phone was unreachable for the old control's End")); idle()
        assertTrue("the old control's failed End leaves the restarted call pending", app.live.state.value.pending)
        assertEquals("no wake window or recognizer while the restarted call is pending", mark, windows())
        idle(WakeContract.MIC_HANDOFF_MS + 200); assertEquals(mark, windows()); assertFalse(listening)
    }

    @Test fun T16_aFailedStartOfAnOlderControlLeavesTheNewerCallPendingWithNoWindow() {
        show(); val failing = TaskCompletionSource<Int>(); nextStartFailure = failing
        hold(); assertTrue(app.live.state.value.pending); val older = starts().last().getString("id")
        hold(); assertFalse("the second hold ended the older pending call", app.live.state.value.pending); assertEquals(listOf(older), ops("end").map { it.getString("id") })
        hold(); assertTrue("the third hold started a newer call", app.live.state.value.pending); assertEquals(2, starts().size); val mark = windows()
        failing.setException(IllegalStateException("the Phone was unreachable for the older control's Start")); idle()
        assertTrue("the older control's failed Start leaves the newer call pending", app.live.state.value.pending)
        assertEquals("no wake window or recognizer while the newer call is pending", mark, windows())
        idle(WakeContract.MIC_HANDOFF_MS + 200); assertEquals(mark, windows()); assertFalse(listening)
    }

    @Test fun T17_aFailedStatusProbeOnResumeLeavesACallBeingSetUpPendingWithNoWindow() {
        show(); hold(); assertTrue(app.live.state.value.pending); hide(); val failing = TaskCompletionSource<Int>(); nextStatusFailure = failing
        app.onForeground(true); val visit = app.voice.onActivityResumed(); app.voice.ensureBackground() // WatchActivity.onResume: the resume probes the call
        runBlocking { app.refreshPhoneReachable() }; app.voice.onSettingsPulled(visit); idle()
        assertEquals("the resume sent one status probe for the call being set up", 1, ops("status").size); assertTrue(app.live.state.value.pending); val mark = windows()
        failing.setException(IllegalStateException("the Phone was unreachable for the status probe")); idle()
        assertTrue("a failed status probe leaves the call being set up pending", app.live.state.value.pending)
        assertEquals("no wake window or recognizer while the call is being set up", mark, windows())
        idle(WakeContract.MIC_HANDOFF_MS + 200); assertEquals(mark, windows()); assertFalse(listening)
    }

    @Test fun T18_aFailedStartAndThenAFailedStatusProbeLeaveTheUncertainCallPendingWithNoWindow() {
        // R38: this interleaving's earlier expectation (the failed Start clears pending) is superseded by the parent's T6 decision; the
        // never-raise guard it also exercised is kept by T26 (late failures after the Phone's definitive end).
        show(); val start = TaskCompletionSource<Int>(); nextStartFailure = start; hold(); assertTrue(app.live.state.value.pending)
        hide(); val probe = TaskCompletionSource<Int>(); nextStatusFailure = probe; resume() // the resume probes the call while its Start is in flight
        assertEquals(1, ops("status").size); val mark = windows()
        start.setException(IllegalStateException("the Phone was unreachable for the Start")); idle()
        assertTrue("the failed Start leaves the uncertain call pending", app.live.state.value.pending)
        probe.setException(IllegalStateException("the Phone was unreachable for the status probe")); idle()
        assertTrue("the failed probe leaves it pending too", app.live.state.value.pending)
        assertEquals("no wake window or recognizer while the uncertain call is pending", mark, windows()); assertEquals(1, starts().size)
    }

    // ── R38: the current call's own failed Start (delivery uncertain) ───────────────────────────────────────────────

    @Test fun T19_aFailedStartSendLeavesTheCurrentCallPendingWithAnHonestStatusAndNoWindow() {
        show(); val failing = TaskCompletionSource<Int>(); nextStartFailure = failing; hold(); assertTrue(app.live.state.value.pending); val mark = windows()
        failing.setException(IllegalStateException("the Data Layer reported the Start as failed")); idle()
        assertTrue("delivery is uncertain: the current call stays pending", app.live.state.value.pending)
        assertEquals("the status says so", "Phone control not confirmed; check Phone", app.live.state.value.status)
        assertEquals("no wake window or recognizer while the uncertain call is pending", mark, windows())
        idle(WakeContract.MIC_HANDOFF_MS + 200); assertEquals(mark, windows()); assertFalse(listening)
        assertEquals("nothing is resent", 1, starts().size)
    }

    @Test fun T20_theNextHoldStopsTheUncertainCallAndALaterHoldStartsANewOne() {
        show(); val old = holdWithFailedStart()
        hold()
        assertFalse("the next hold is the Stop of the uncertain call, not a new Start", app.live.state.value.pending || app.live.state.value.active)
        assertEquals(listOf(old), ops("end").map { it.getString("id") }); assertEquals(1, starts().size)
        assertTrue("the eligible wake listens again once the call ended", waitFor { listening })
        hold()
        assertTrue("a later deliberate hold starts a new call", app.live.state.value.pending); assertEquals(2, starts().size)
        assertNotEquals(old, starts().last().getString("id")); assertEquals(1, ops("end").size)
    }

    @Test fun T21_correctlyBoundLateMediaAfterAFailedStartIsAdmittedWithNoWindow() {
        show(); val failing = TaskCompletionSource<Int>(); nextStartFailure = failing; hold(); val mark = windows()
        failing.setException(IllegalStateException("the Data Layer reported the Start as failed")); idle()
        openMedia() // the Phone did receive it: its media for this exact control opens
        assertTrue(app.live.state.value.active && !app.live.state.value.pending); assertEquals(1, peers.size)
        assertEquals("no wake window or recognizer from the hold through the late media", mark, windows())
    }

    @Test fun T22_aLateStartFailureAfterTheMediaOpenedLeavesTheActiveCallAlone() {
        show(); val failing = TaskCompletionSource<Int>(); nextStartFailure = failing; hold(); openMedia()
        val before = app.live.state.value; val mark = windows()
        failing.setException(IllegalStateException("the Data Layer reported the Start as failed after the Phone opened its media")); idle()
        assertEquals("the open media of this control proves the Start arrived: the active call's state is untouched", before, app.live.state.value)
        assertEquals(mark, windows())
    }

    @Test fun T23_thePhonesDefinitiveEndOfAnUncertainCallEndsItAndNothingResurrectsIt() {
        show(); id = holdWithFailedStart()
        phoneStatus(active = false, endReason = "failure", probe = starts().last().getString("probe")); idle()
        assertFalse(app.live.state.value.pending || app.live.state.value.active); assertTrue("the eligible wake listens again", waitFor { listening })
        media = "late37"; seq = 0; packet("open"); idle(); assertTrue("no Watch media for the ended control", peers.isEmpty())
        hold(); assertTrue(app.live.state.value.pending); assertEquals("a fresh call: nothing to end", 2, starts().size); assertTrue(ops("end").isEmpty())
    }

    @Test fun T24_theNotificationStopOfAnUncertainCallOpensNoWindowAndNothingResurrectsIt() {
        show(); id = holdWithFailedStart(); hide(); val mark = windows()
        notificationStop()
        assertEquals("no wake window or recognizer from the Stop on", mark, windows())
        idle(WakeContract.MIC_HANDOFF_MS + 2_000)
        assertFalse(app.live.state.value.pending || app.live.state.value.active); assertFalse(listening); assertFalse(app.voice.presence.armed)
        assertEquals(listOf(id), ops("end").map { it.getString("id") })
        media = "late37"; seq = 0; packet("open"); app.voice.onRearmAlarm(); idle(WakeContract.MIC_HANDOFF_MS + 2_000)
        assertTrue(peers.isEmpty()); assertFalse(app.live.state.value.pending || app.live.state.value.active); assertEquals(mark, windows()); assertEquals(1, starts().size)
    }

    @Test fun T25_switchingLiveOffEndsAnUncertainCallAndTheEligibleWakeListensAgain() {
        show(); id = holdWithFailedStart()
        app.applySettings(base.copy(watchLiveEnabled = false, revision = 3702).toJson()); idle()
        assertFalse(app.live.state.value.pending || app.live.state.value.active); assertEquals(listOf(id), ops("end").map { it.getString("id") })
        assertTrue("the armed wake session listens again once Live is off", waitFor { listening })
        media = "late37"; seq = 0; packet("open"); idle(); assertTrue(peers.isEmpty()); assertFalse(app.live.state.value.pending)
    }

    @Test fun T26_lateStartAndProbeFailuresNeverRaisePendingAfterThePhonesDefinitiveIdleEnd() {
        show(); val start = TaskCompletionSource<Int>(); nextStartFailure = start; hold(); id = starts().last().getString("id")
        hide(); val probe = TaskCompletionSource<Int>(); nextStatusFailure = probe; resume(); assertEquals(1, ops("status").size)
        phoneStatus(active = false, endReason = "idle", probe = ops("status").last().getString("probe")); idle()
        assertFalse("the Phone's definitive answer ended the call (its control is kept)", app.live.state.value.pending || app.live.state.value.active)
        start.setException(IllegalStateException("the Phone was unreachable for the Start")); idle()
        probe.setException(IllegalStateException("the Phone was unreachable for the status probe")); idle()
        assertFalse("neither late failure raises pending after the definitive end", app.live.state.value.pending)
        assertTrue("the eligible wake listens again", waitFor { listening })
    }

    @Test fun T27_privateAudioSwitchedOnDuringAnUncertainCallKeepsItPendingAndItsLateMediaOpensPrivately() {
        show(); holdWithFailedStart(); val mark = windows()
        app.applySettings(base.copy(privateAudio = true, revision = 3702).toJson()); idle()
        assertTrue("the uncertain call stays pending", app.live.state.value.pending); assertEquals(mark, windows())
        openMedia()
        assertEquals("Watch microphone · Phone private Live output", app.live.state.value.status); assertEquals(mark, windows())
    }

    @Test fun T28_aWakeStartedCallWhoseStartFailedKeepsItsSentClaimAndTheNextHoldStopsIt() {
        val failing = TaskCompletionSource<Int>(); nextStartFailure = failing; val before = starts().size
        app.voice.wake.qaHeard("루미", true)
        if (!waitFor { starts().size > before }) throw Setup("the phrase-only handoff did not reach Live control")
        val start = starts().last(); val claim = start.getString("wake_claim_id"); id = start.getString("id")
        failing.setException(IllegalStateException("the Data Layer reported the wake-started Start as failed")); idle()
        assertTrue("the uncertain wake-started call stays pending", app.live.state.value.pending)
        resume(); hold()
        assertFalse("the next hold is the Stop of the uncertain call", app.live.state.value.pending || app.live.state.value.active)
        assertEquals(listOf(id), ops("end").map { it.getString("id") })
        assertEquals("a claim sent with the Start is never given back", 0, releases(claim)); assertEquals("nothing is resent", 1, starts().size - before)
        assertTrue("the eligible wake listens again", waitFor { listening })
    }

    @Test fun T29_aFailedResumeProbeOfAnActiveCallLeavesItsStateAloneWithNoWindow() {
        show(); hold(); openMedia(); hide(); val probe = TaskCompletionSource<Int>(); nextStatusFailure = probe; resume()
        assertEquals("the resume probed the active call", 1, ops("status").size); assertTrue(app.live.state.value.active)
        val before = app.live.state.value; val mark = windows()
        probe.setException(IllegalStateException("the Phone was unreachable for the status probe")); idle()
        assertEquals("a late failure once the call's media is open leaves the call's state alone", before, app.live.state.value)
        assertEquals("no wake window or recognizer during the active call", mark, windows())
    }
}
