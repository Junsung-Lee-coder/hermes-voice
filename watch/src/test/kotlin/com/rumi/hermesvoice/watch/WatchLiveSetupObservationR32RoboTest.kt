package com.rumi.hermesvoice.watch

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.media.AudioManager
import android.os.Looper
import androidx.core.app.ServiceCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.diag.*
import com.rumi.hermesvoice.core.live.WatchLiveWire
import com.rumi.hermesvoice.core.settings.WatchSettings
import io.mockk.*
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * R32: a Watch Live call the Watch refuses before it started leaves exactly one fixed-step, type-only observation in the Watch's own
 * journal (live_setup_failed, origin watch): [DiagEvent.n] the fixed step, [DiagEvent.stage]/fail/cause the type, [DiagEvent.status]
 * a Play-services Task status, [DiagEvent.ms] time since the Phone opened the call's media. Never text, ids, devices or endpoints.
 * Real Watch hold, real WatchLiveVoiceRuntime and, per case, the real foreground service, native route or Channel media; the Phone
 * Data Layer and the platform leaves (Tasks, focus, startForeground) are synthetic, so these prove the observation, never a physical cause.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class WatchLiveSetupObservationR32RoboTest {
    private lateinit var app: WatchApp
    private val sent = ConcurrentLinkedQueue<ByteArray>()
    private lateinit var channels: ChannelClient
    private var now = 1000L; private var phoneSeq = 0L; private var mediaSeq = 0L
    private var routeValid = true
    private class Peer : WatchLiveMedia {
        override suspend fun connect(signal: suspend (String) -> String, event: (String) -> Unit) { check(signal("watch-offer") == "phone-relay-ready") }
        override fun send(json: String) = false
        override fun mute(muted: Boolean) {}; override fun output(enabled: Boolean) {}; override fun silence() {}; override fun close() {}
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    private fun await(done: () -> Boolean): Boolean { val end = System.nanoTime() + 5_000_000_000; while (System.nanoTime() < end) { idle(); if (done()) return true; Thread.sleep(10) }; return done() }
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as WatchApp
        app.localStore.putString("live_control_id", ""); app.localStore.putString("live_control_node", "")
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        mockkStatic(Wearable::class)
        val phone = mockk<Node>(); every { phone.id } returns "phone-obs"; every { phone.isNearby } returns true
        val info = mockk<CapabilityInfo>(); every { info.nodes } returns setOf(phone)
        val caps = mockk<CapabilityClient>(); every { caps.getCapability(any(), any()) } returns Tasks.forResult(info)
        every { Wearable.getCapabilityClient(any<Context>()) } returns caps
        val messages = mockk<MessageClient>(); every { messages.sendMessage(any(), any(), any()) } answers {
            if (secondArg<String>() == WatchLiveVoiceRuntime.COMMAND) sent += thirdArg<ByteArray>(); Tasks.forResult(1)
        }
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        every { Wearable.getDataClient(any<Context>()) } returns mockk<DataClient>(relaxed = true)
        channels = mockk(relaxed = true); every { Wearable.getChannelClient(any<Context>()) } returns channels
        app.applySettings(WatchSettings(phoneLiveEnabled = false, watchLiveEnabled = true, revision = 100).toJson()); idle()
        app.live.now = { now }
        app.live.routeFactory = { object : WatchLiveRoute { override fun open() = true; override fun valid() = routeValid; override fun close() {} } }
    }
    @After fun teardown() { app.live.end(); idle(); app.scope.cancel(); unmockkAll() }
    private fun setupFailures() = app.diag.snapshot().filter { it.code == DiagCode.LIVE_SETUP_FAILED }
    private fun v1(op: String) = sent.mapNotNull { runCatching { JSONObject(it.toString(Charsets.UTF_8)) }.getOrNull() }.filter { it.optInt("v") == 1 && it.optString("op") == op }
    private fun media(op: String) = sent.mapNotNull { WatchLiveWire.parse(it) }.filter { it.op == op }
    /** The Phone's own v1 control status for this control (PhoneLiveWatchControl.envelope + sendState shape). */
    private fun status(id: String, active: Boolean, probe: String = "", endReason: String? = null) = app.live.onStatus("phone-obs", JSONObject().put("v", 1).put("id", id)
        .put("epoch", "phone-boot").put("seq", ++phoneSeq).put("probe", probe).put("media", "phone-call").put("phone_audio", false).put("watch_audio", true)
        .put("active", active).put("muted", false).apply { if (endReason != null) put("end_reason", endReason) }.put("status", "Phone Live").toString().toByteArray())
    /** The real Watch hold, then the Phone's own status and its media open for this control (PhoneLiveWatchControl/WatchLiveTransport shapes). */
    private fun holdAndOpen(): String {
        assertTrue("the real Watch hold reaches Watch Live", app.voice.onTalkPressed()); idle()
        val start = v1("start").single(); val id = start.getString("id")
        status(id, active = true, probe = start.getString("probe"))
        phone(id, "open", JSONObject().put("relay", true).put("stream", "obs-stream").put("muted", false).put("private", false).put("output", "").put("input_hold", "").toString())
        idle(); return id
    }
    /** The real default Channel media's Channel and streams: [input] is what the Phone relay's downlink delivers. */
    private fun channelWith(input: java.io.InputStream) {
        val channel = mockk<ChannelClient.Channel>(); var path = ""
        every { channel.nodeId } returns "phone-obs"; every { channel.path } answers { path }
        every { channels.openChannel(any(), any()) } answers { path = secondArg(); Tasks.forResult(channel) }
        every { channels.getInputStream(any()) } returns Tasks.forResult(input)
        every { channels.getOutputStream(any()) } returns Tasks.forResult(java.io.ByteArrayOutputStream() as java.io.OutputStream)
    }
    private fun phone(id: String, op: String, body: String = "") = app.live.onStatus("phone-obs", WatchLiveWire.packet(id, "phone-call", "phone-boot", ++mediaSeq, op, body))
    private fun single(): DiagEvent {
        val events = setupFailures(); assertEquals("exactly one Watch setup observation: $events", 1, events.size)
        return events.single().also { e ->
            assertEquals(DiagOrigin.WATCH, e.origin); assertNull("no correlation id", e.ref); assertFalse(e.opaque)
        }
    }

    @Test fun O01_aForegroundServiceStartThatThrowsIsObservedOnceAtItsFixedStepByTypeOnly() {
        app.live.admission = { throw SecurityException("synthetic foreground refusal with private words") }
        holdAndOpen()
        assertEquals("the Watch still tells the Phone the same refusal", "Android refused Watch foreground microphone; allow access and use Talk", media("refused").single().body)
        now += 5_000; repeat(4) { idle() }
        val e = single()
        assertEquals(1, e.n); assertEquals(DiagStage.LIVE_INPUT, e.stage); assertEquals(DiagFail.AUTH, e.fail); assertEquals(DiagCause.AUTH, e.cause); assertEquals(0L, e.ms)
        assertFalse("never the exception text", app.diag.snapshot().joinToString().contains("private words"))
    }

    @Test fun O02_aStartForegroundTheSystemDoesNotAllowInTheRealServiceIsObservedAsRejectedAtTheServiceStep() {
        mockkStatic(ServiceCompat::class)
        every { ServiceCompat.startForeground(any(), any(), any(), any()) } throws ForegroundServiceStartNotAllowedException("synthetic not allowed")
        holdAndOpen()
        val intent = shadowOf(app).nextStartedService; assertNotNull("the real admission started the Watch Live service", intent)
        Robolectric.buildService(WatchLiveVoiceService::class.java, intent).create().startCommand(0, 1); idle()
        assertEquals("Android refused Watch foreground microphone; allow access and use Talk", media("refused").single().body)
        val e = single()
        assertEquals(2, e.n); assertEquals(DiagStage.LIVE_INPUT, e.stage); assertEquals(DiagFail.REFUSED, e.fail); assertEquals(DiagCause.REJECTED, e.cause)
    }

    @Test fun O03_aMicrophonePermissionMissingAtAdmissionIsObservedAsAuthAtTheAdmissionStep() {
        app.live.admission = { app.live.admitted(it) }
        assertTrue(app.voice.onTalkPressed()); idle()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val start = v1("start").single(); val id = start.getString("id")
        app.live.onStatus("phone-obs", JSONObject().put("v", 1).put("id", id).put("epoch", "phone-boot").put("seq", ++phoneSeq).put("probe", start.getString("probe"))
            .put("media", "phone-call").put("phone_audio", false).put("watch_audio", true).put("active", true).put("muted", false).put("status", "Phone Live").toString().toByteArray())
        phone(id, "open", JSONObject().put("relay", true).put("stream", "obs-stream").put("private", false).toString()); idle()
        assertEquals("Watch microphone is denied or busy; no Phone microphone fallback", media("refused").single().body)
        val e = single()
        assertEquals(4, e.n); assertEquals(DiagStage.LIVE_INPUT, e.stage); assertEquals(DiagFail.AUTH, e.fail)
    }

    @Test fun O04_theRealNativeRouteSaysWhichOfItsChecksRefused() {
        app.live.admission = { app.live.admitted(it) }
        app.live.routeFactory = { WatchNativeLiveRoute(app, it) }
        shadowOf(app.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        holdAndOpen(); assertTrue(await { media("refused").isNotEmpty() })
        val e = single()
        assertEquals("audio focus was not granted", 13, e.n); assertEquals(DiagStage.LIVE_ROUTE, e.stage); assertEquals(DiagFail.REFUSED, e.fail); assertEquals(DiagCause.LOCAL, e.cause)
        assertTrue(media("offer").isEmpty())
    }

    @Test fun O05_aFailedChannelOpenOfTheRealMediaIsObservedAtTheChannelStepWithItsTaskStatusAndTime() {
        app.live.admission = { app.live.admitted(it) }
        val opening = TaskCompletionSource<ChannelClient.Channel>(); var opens = 0
        every { channels.openChannel(any(), any()) } answers { opens++; opening.task }
        holdAndOpen(); assertTrue("the real default media asked for its Channel", await { opens == 1 })
        now += 700
        opening.setException(ApiException(Status(WearableStatusCodes.TARGET_NODE_NOT_CONNECTED)))
        assertTrue(await { media("refused").isNotEmpty() })
        val e = single()
        assertEquals(21, e.n); assertEquals(DiagStage.LIVE_RELAY, e.stage); assertEquals(WearableStatusCodes.TARGET_NODE_NOT_CONNECTED, e.status); assertEquals(700L, e.ms)
        repeat(4) { idle() }; assertEquals("once", 1, setupFailures().size)
    }

    @Test fun O06_aWatchStopWhileAdmittingAndItsLateCallbacksAreNeverSetupFailures() {
        var token = -1L; app.live.admission = { token = it }
        holdAndOpen(); assertTrue(token >= 0)
        assertTrue("the hold during admission is the Stop", app.voice.onTalkPressed()); idle()
        app.live.admitted(token); app.live.admissionRefused(token); now += 70_000; repeat(4) { idle() }
        assertTrue("a Stop and stale callbacks are not refusals", media("refused").isEmpty())
        assertTrue(setupFailures().isEmpty())
    }

    @Test fun O07_aCallThatConnectsAndStartsRecordsNoSetupFailureAndALaterRefusalIsNotOne() {
        app.live.admission = { app.live.admitted(it) }
        app.live.mediaFactory = { Peer() }
        val id = holdAndOpen()
        assertTrue(await { media("offer").isNotEmpty() }); phone(id, "answer", "phone-relay-ready")
        assertTrue("normal control: connected", await { media("connected").isNotEmpty() })
        phone(id, "event", "{\"type\":\"session.started\",\"session\":{\"id\":\"obs\"}}"); idle()
        assertTrue(setupFailures().isEmpty())
        routeValid = false; phone(id, "output", "true"); idle()
        assertEquals("Watch output route unconfirmed", media("refused").single().body)
        assertTrue("a refusal after the call started is not a setup failure", setupFailures().isEmpty())
    }

    @Test fun O08_aChannelOpenThatTimesOutIsObservedOnceAtTheChannelStepAsATimeoutAndTheWatchStillDoesNotRefuse() {
        app.live.admission = { app.live.admitted(it) }
        var opens = 0; every { channels.openChannel(any(), any()) } answers { opens++; TaskCompletionSource<ChannelClient.Channel>().task }
        holdAndOpen(); assertTrue(await { opens == 1 })
        now += 1200; shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_100)); repeat(4) { idle() }
        val e = single()
        assertEquals(21, e.n); assertEquals(DiagStage.LIVE_RELAY, e.stage); assertEquals(DiagFail.TIMEOUT, e.fail); assertEquals(DiagCause.TIMEOUT, e.cause); assertEquals(1200L, e.ms)
        assertTrue("an elapsed bound stays what it was: no Watch refusal is added", media("refused").isEmpty())
    }

    @Test fun O09_aRelayDownlinkLostWhileTheWatchAwaitsThePhoneAnswerIsObservedAtTheDownlinkStep() {
        app.live.admission = { app.live.admitted(it) }
        channelWith(java.io.ByteArrayInputStream(ByteArray(0)))
        holdAndOpen(); assertTrue(await { media("refused").isNotEmpty() })
        assertEquals("Phone audio relay unavailable; Watch audio stopped without fallback", media("refused").single().body)
        val e = single()
        assertEquals(33, e.n); assertEquals(DiagStage.LIVE_RELAY, e.stage)
        repeat(4) { idle() }; assertEquals("once", 1, setupFailures().size)
    }

    @Test fun O10_aPhoneEndWhileTheWatchIsAdmittingAndItsLateCallbacksAreNeverSetupFailures() {
        var token = -1L; app.live.admission = { token = it }
        val id = holdAndOpen(); assertTrue(token >= 0)
        status(id, active = false, endReason = "explicit"); idle()
        app.live.admitted(token); app.live.admissionRefused(token); now += 70_000; repeat(4) { idle() }
        assertTrue("a Phone end and stale callbacks are not refusals", media("refused").isEmpty())
        assertTrue(setupFailures().isEmpty())
    }
}
