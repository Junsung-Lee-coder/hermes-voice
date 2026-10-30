package com.rumi.hermesvoice.phone

import android.Manifest
import android.content.Context
import android.media.AudioDeviceInfo
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.diag.DiagCode
import com.rumi.hermesvoice.core.diag.DiagStage
import com.rumi.hermesvoice.core.headset.AudioEndpoint
import com.rumi.hermesvoice.core.headset.HeadsetDevices
import com.rumi.hermesvoice.core.headset.HeadsetPolicy
import com.rumi.hermesvoice.core.live.WatchLiveWire
import com.rumi.hermesvoice.core.live.WatchRelayAudio
import com.rumi.hermesvoice.core.settings.WatchSettings
import io.mockk.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * R42 (branch B, Watch-direct experiment): the Phone side of an ordinary Watch-origin call whose media the Watch owns. Real: PhoneApp, the
 * Live controller, the Watch control and the DEFAULT Watch transport choice, the dashboard client over the session API contract double. The
 * Phone hands the Watch's own offer to the authenticated session request once and returns the answer; it builds no peer, audio device
 * module, microphone or PCM stream for that call, and keeps every policy decision. Doubles: the Data Layer, the paired Watch's packets, the
 * dashboard (MockWebServer) and the pinned WebRTC static entry points (counted: they must stay untouched). A PASS is the Phone's own ownership,
 * fences and no-resend behavior; it does not show that a Watch's Bluetooth-proxied uplink carries WebRTC media.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchDirectSessionR42RoboTest : PhoneRoboBase() {
    private val packets = ConcurrentLinkedQueue<WatchLiveWire.Packet>()
    private val states = ConcurrentLinkedQueue<JSONObject>()
    private val initialized = AtomicInteger(); private val factories = AtomicInteger(); private val admBuilds = AtomicInteger()
    private val channelReads = AtomicInteger()
    private val sessions = CopyOnWriteArrayList<JSONObject>()
    @Volatile private var hold: CountDownLatch? = null
    @Volatile private var sessionCode = 200
    @Volatile private var headsetPresent = false
    private lateinit var channels: ChannelClient
    private var open: WatchLiveWire.Packet? = null
    private var seq = 0L

    @Before fun setup() {
        baseSetUp(); shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO) // direct Watch media needs no Phone microphone
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled = false, watchLiveEnabled = true, liveIdleSeconds = 60))
        app.live.admission = { app.live.admitted(it) }
        val n = mockk<Node>(); every { n.id } returns "watch-node"; val info = mockk<CapabilityInfo>(); every { info.nodes } returns setOf(n)
        val caps = mockk<CapabilityClient>(); every { caps.getCapability(any(), any()) } returns Tasks.forResult(info)
        every { Wearable.getCapabilityClient(any<Context>()) } returns caps
        val messages = mockk<MessageClient>()
        every { messages.sendMessage(any(), any(), any()) } answers {
            val bytes = thirdArg<ByteArray>(); val parsed = WatchLiveWire.parse(bytes)
            if (parsed != null) packets += parsed else runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()?.takeIf { it.optInt("v") == 1 }?.let { states += it }
            Tasks.forResult(1)
        }
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        channels = mockk(relaxed = true); every { Wearable.getChannelClient(any<Context>()) } returns channels
        every { channels.getInputStream(any()) } answers { channelReads.incrementAndGet(); Tasks.forResult<java.io.InputStream>(ByteArrayInputStream(ByteArray(0))) }
        every { channels.close(any()) } returns Tasks.forResult(null)
        // The Phone-owned media's entry points: counted, and never reached by a direct Watch call.
        mockkStatic(PeerConnectionFactory::class); mockkStatic(JavaAudioDeviceModule::class)
        every { PeerConnectionFactory.initialize(any()) } answers { initialized.incrementAndGet(); Unit }
        every { PeerConnectionFactory.builder() } answers { factories.incrementAndGet(); mockk(relaxed = true) }
        every { JavaAudioDeviceModule.builder(any()) } answers { admBuilds.incrementAndGet(); mockk(relaxed = true) }
        // The Phone's own headset policy (Use headset on): a personal output is present only when the test says so.
        val devices = object : HeadsetDevices {
            override fun outputs() = if (headsetPresent) listOf(AudioEndpoint(901, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "earbud")) else emptyList()
            override fun inputs() = emptyList<AudioEndpoint>()
            override fun watch(onChange: () -> Unit) = AutoCloseable {}
        }
        app.settings.useHeadset = true
        PhoneApp::class.java.getDeclaredField("headset\$delegate").apply { isAccessible = true }.set(app, lazyOf(HeadsetPolicy({ app.settings.useHeadset }, devices)))
        val original = fake.server.dispatcher
        fake.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(r: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                if (r.path != "/api/audio/voice-live/session") return original.dispatch(r)
                sessions += JSONObject(r.body.readUtf8())
                hold?.await(10, TimeUnit.SECONDS)
                if (sessionCode != 200) return okhttp3.mockwebserver.MockResponse().setResponseCode(sessionCode).setHeader("Content-Type", "application/json").setBody("{\"ok\":false,\"detail\":\"provider private words\"}")
                return okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json")
                    .setBody(JSONObject().put("ok", true).put("session", JSONObject().put("id", "fixture")).put("transport", JSONObject().put("type", "webrtc").put("sdp", PROVIDER_ANSWER)).toString())
            }
        }
    }
    @After fun cleanup() { hold?.countDown(); app.live.end(); settle(); baseTearDown() }

    /** The simulated Watch pings about every 500 ms of fake time while a call is open, as a real direct Watch does (off: a Watch gone silent). */
    @Volatile private var watchAlive = true
    private var pumps = 0
    override fun pump() { super.pump(); if (watchAlive && open != null && app.live.active && ++pumps % 10 == 0) incoming("ping") }
    private fun status() = app.live.state.value.status
    private fun ops(op: String) = packets.filter { it.op == op }
    private fun start(direct: Boolean = true, control: String = "direct-control") {
        if (open == null) conversation("alpha")
        packets.clear(); seq = 0L
        app.liveWatch.receive("watch-node", JSONObject().put("v", 1).put("id", control).put("op", "start").put("probe", "p").apply { if (direct) put("direct", true) }.toString().toByteArray())
        assertTrue("the Phone opened this Watch call's media", await { packets.any { it.op == "open" } }); open = packets.last { it.op == "open" }
    }
    private fun incoming(op: String, body: String = "") { val p = open!!; app.liveWatch.receive("watch-node", WatchLiveWire.packet(p.id, p.media, p.epoch, ++seq, op, body)) }
    private fun started() = "{\"type\":\"session.started\",\"session\":{\"id\":\"fixture\"}}"
    private fun answered() {
        start(); incoming("offer", WATCH_OFFER)
        assertTrue("the Phone returned the session answer", await { ops("answer").isNotEmpty() })
    }
    private fun running() {
        answered(); incoming("connected"); incoming("event", started())
        assertTrue("the call started: ${status()}", await { app.live.state.value.started })
    }
    private fun assertNoPhoneMedia() {
        assertEquals("no Phone WebRTC initialization, peer factory or audio device module", listOf(0, 0, 0), listOf(initialized.get(), factories.get(), admBuilds.get()))
        assertEquals("no Phone PCM stream", 0, channelReads.get())
    }

    @Test fun P01_anOrdinaryWatchStartGetsDirectMediaAndThePhoneOnlyTurnsTheWatchOfferIntoTheSession() {
        start()
        val body = JSONObject(open!!.body)
        assertEquals("the Phone chose the Watch's own direct media", true, body.opt("direct"))
        assertFalse("no paired PCM relay stream for it", body.has("relay") || body.has("stream"))
        incoming("offer", WATCH_OFFER)
        assertTrue(await { ops("answer").isNotEmpty() })
        assertEquals("one authenticated session request", 1, sessions.size)
        assertEquals("carrying the Watch's own offer byte for byte", WATCH_OFFER, sessions.single().getString("sdp"))
        assertTrue("and the Phone-owned startup context", sessions.single().has("history"))
        assertEquals("the provider's answer reaches the Watch exactly", PROVIDER_ANSWER, ops("answer").single().body)
        assertNoPhoneMedia()
        // A PCM channel the Watch might open for this call is refused: the direct call has no Phone relay.
        val c = mockk<ChannelClient.Channel>(); every { c.nodeId } returns "watch-node"
        every { c.path } returns WatchRelayAudio.Address(open!!.id, open!!.media, open!!.epoch, "stream").path()
        app.liveWatch.audioChannel(c); settle()
        verify(exactly = 1) { channels.close(c) }; assertEquals(0, channelReads.get())
        incoming("connected"); incoming("event", started())
        assertTrue(await { app.live.state.value.started })
        assertTrue("output is enabled on the Watch", await { ops("output").any { it.body == "true" } })
        assertTrue(status(), status().contains("direct"))
        incoming("microphone", "true")
        assertTrue("the Watch's own capture makes the call genuinely listen", await { app.live.state.value.listening })
        fun greetings() = ops("send").count { JSONObject(it.body).optString("type") == "session.commentary.append" }
        assertTrue("the ready greeting goes to the Watch's data channel", await { greetings() == 1 })
        incoming("microphone", "false"); incoming("microphone", "true"); settle()
        assertEquals("once per call", 1, greetings())
        assertEquals("never a second session request", 1, sessions.size)
        assertNoPhoneMedia()
    }

    @Test fun P02_aSessionRequestHeldWhenTheUserStopsIsNeitherAnsweredNorResent() {
        hold = CountDownLatch(1)
        start(); incoming("offer", WATCH_OFFER)
        assertTrue("the request is in flight", await { sessions.size == 1 })
        app.live.end(); settle()
        hold!!.countDown(); Thread.sleep(200); settle()
        assertTrue("a maybe-accepted session is never answered after Stop", ops("answer").isEmpty())
        assertEquals("and never resent", 1, sessions.size)
        assertFalse(app.live.active); assertTrue(status(), status().startsWith("Live ended"))
        assertTrue("the Watch media was told to close", ops("close").isNotEmpty())
        assertNoPhoneMedia()
    }

    @Test fun P03_aRefusedSessionEndsTheCallTruthfullyNothingIsRetriedAndNoSdpReachesDiagnostics() {
        sessionCode = 503
        start(); incoming("offer", WATCH_OFFER)
        assertTrue("the call ends", await { !app.live.active })
        assertTrue(status(), status().contains("HTTP 503") && status().contains("nothing is retried"))
        assertEquals(1, sessions.size); assertTrue(ops("answer").isEmpty())
        val journal = app.diagnostics.log.snapshot()
        assertTrue("one typed setup observation at the session step", journal.any { it.code == DiagCode.LIVE_SETUP_FAILED && it.stage == DiagStage.LIVE_SESSION })
        assertFalse("never the SDP or provider text", (journal.joinToString() + status()).let { it.contains("WATCH-OWNED") || it.contains("private words") })
        assertNoPhoneMedia()
    }

    @Test fun P04_aSelectedPhonePrivateOutputOrAWatchWithoutTheFlagKeepsThePhoneOwnedRelay() {
        headsetPresent = true
        start()
        val privateOpen = JSONObject(open!!.body)
        assertTrue("a selected Phone private output keeps the qualified Phone-owned relay", privateOpen.optBoolean("relay") && privateOpen.optBoolean("private"))
        assertFalse(privateOpen.optBoolean("direct"))
        app.live.end(); settle()
        headsetPresent = false
        start(direct = false, control = "older-watch-control")
        val relayOpen = JSONObject(open!!.body)
        assertTrue("a Watch that did not offer direct media keeps the relay", relayOpen.optBoolean("relay") && relayOpen.has("stream"))
        assertFalse(relayOpen.optBoolean("direct"))
    }

    @Test fun P05_theRelayWordOrAMalformedOfferOnDirectMediaIsRefusedBeforeAnySessionRequest() {
        start(); incoming("offer", WatchRelayAudio.OFFER)
        assertTrue(await { !app.live.active })
        assertTrue(status(), status().contains("Watch media version unavailable"))
        assertTrue("nothing was sent to the session API", sessions.isEmpty())
        assertNoPhoneMedia()
    }

    @Test fun P06_onlyTheExactOwnerEpochAndSequenceReachTheCallAndAGapEndsIt() {
        answered(); incoming("connected")
        val p = open!!
        app.liveWatch.receive("watch-node", WatchLiveWire.packet(p.id, p.media, "another-phone-epoch", seq + 1, "event", started()))
        app.liveWatch.receive("another-node", WatchLiveWire.packet(p.id, p.media, p.epoch, seq + 1, "event", started()))
        app.liveWatch.receive("watch-node", WatchLiveWire.packet(p.id, "another-media", p.epoch, seq + 1, "event", started())); settle()
        assertFalse("a foreign epoch, node or media starts nothing", app.live.state.value.started)
        assertTrue(app.live.active)
        app.liveWatch.receive("watch-node", WatchLiveWire.packet(p.id, p.media, p.epoch, seq + 2, "event", started()))
        assertTrue("a sequence gap ends the call", await { !app.live.active })
        assertTrue(status(), status().contains("sequence lost")); assertFalse(app.live.state.value.started)
    }

    @Test fun P07_aPhonePrivateOutputConnectedMidCallEndsTheDirectCallWithoutAnyFallback() {
        running()
        headsetPresent = true; app.liveWatch.privateChanged()
        assertTrue("the direct call ends", await { !app.live.active })
        assertTrue(status(), status().contains("private output"))
        assertTrue("never a private-output handoff of Watch media", ops("private").isEmpty())
        assertTrue("the Watch media was silenced", ops("silence").isNotEmpty())
        assertNoPhoneMedia()
    }

    @Test fun P08_afterTheEndWithoutTheProvidersReceiptTheCallStaysTruthfullyUnconfirmed() {
        running()
        app.live.end()
        assertTrue("the Watch media is silenced at once", ops("silence").isNotEmpty() || await { ops("silence").isNotEmpty() })
        assertTrue("the provider close goes through the Watch data channel", await { ops("send").any { JSONObject(it.body).optString("type") == "session.close" } })
        val ended = app.live.state.value.output
        incoming("event", "{\"type\":\"session.output_transcript.delta\",\"event_id\":\"late\",\"start_ms\":0,\"end_ms\":5,\"delta\":\"late words\"}")
        idle(3500); settle() // the real Watch has already closed its peer: no receipt comes back
        assertTrue(status(), status().contains("final usage unconfirmed"))
        assertFalse(status(), status().contains("· finalized"))
        assertEquals("a late transcript is not delivered to the ended call", ended, app.live.state.value.output)
        assertTrue("then the Watch media closes", await { ops("close").isNotEmpty() })
        assertEquals("one session request in the whole call", 1, sessions.size)
    }

    @Test fun P09_signOutWhileTheSessionIsInFlightNeverHandsTheAnswerToTheWatch() {
        hold = CountDownLatch(1)
        start(); incoming("offer", WATCH_OFFER)
        assertTrue(await { sessions.size == 1 })
        PhoneViewModel(app).signOut(); settle()
        hold!!.countDown(); Thread.sleep(200); settle()
        assertFalse(app.live.active)
        assertTrue("no answer after the account scope ended", ops("answer").isEmpty())
        assertEquals(1, sessions.size)
        assertNoPhoneMedia()
    }

    @Test fun P10_ifTheProvidersCloseReceiptStillCrossesItAloneFinalizesTheEndedCall() {
        running()
        app.live.end()
        assertTrue(await { ops("send").any { JSONObject(it.body).optString("type") == "session.close" } })
        incoming("event", "{\"type\":\"session.input_transcript.delta\",\"event_id\":\"late-in\",\"start_ms\":0,\"end_ms\":5,\"delta\":\"late words\"}")
        incoming("event", "{\"type\":\"session.closed\"}")
        assertTrue(status(), await { status().contains("· finalized") && !status().contains("unconfirmed") })
        assertEquals("the late input transcript reached nothing", "", app.live.state.value.input)
    }

    @Test fun P11_aWatchThatStopsAnsweringEndsTheDirectCallOnThePhone() {
        running(); incoming("microphone", "true")
        assertTrue(await { app.live.state.value.listening })
        watchAlive = false; idle(6000); settle() // the Watch's own pings stop: no packet for more than five seconds
        assertTrue("the Phone ends the call itself", await { !app.live.active })
        assertTrue(status(), status().contains("Watch stopped answering"))
        assertTrue("the Watch media was told to stop", ops("silence").isNotEmpty())
        assertEquals(1, sessions.size); assertNoPhoneMedia()
    }

    @Test fun P12_aProviderCommandBurstBeyondTheBoundIsBackpressureNeverAnEndOrASequenceGap() {
        running(); incoming("ping")
        // The Data Layer holds the next packet it is given, so the bounded queue fills behind it during one synchronous burst on the main
        // thread, as the controller's own context loops send.
        val messages = Wearable.getMessageClient(app)
        val held = CopyOnWriteArrayList<com.google.android.gms.tasks.TaskCompletionSource<Int>>()
        every { messages.sendMessage(any(), any(), any()) } answers {
            WatchLiveWire.parse(thirdArg<ByteArray>())?.let { packets += it }
            com.google.android.gms.tasks.TaskCompletionSource<Int>().also { held += it }.task
        }
        val transport = com.rumi.hermesvoice.phone.live.PhoneLiveVoiceController::class.java.getDeclaredField("transport").apply { isAccessible = true }.get(app.live) as com.rumi.hermesvoice.phone.live.LiveTransport
        val accepted = (0 until 200).count { transport.send(JSONObject().put("type", "session.thinking.append").put("event_id", "b$it").put("content", "x").toString()) }
        assertEquals("the Data Layer holds exactly the first packet", 1, held.size)
        assertTrue("some were queued and the rest refused piecewise: $accepted", accepted in 1..199)
        assertTrue("backpressure is not an end", app.live.active)
        app.live.setMuted(true)
        assertTrue("a control still has room behind a full provider share", app.live.active && app.live.state.value.muted)
        every { messages.sendMessage(any(), any(), any()) } answers { WatchLiveWire.parse(thirdArg<ByteArray>())?.let { packets += it }; Tasks.forResult(1) }
        held.forEach { it.setResult(1) }
        assertTrue("the queue drained", await { ops("send").count { JSONObject(it.body).optString("type") == "session.thinking.append" } == accepted })
        assertTrue("the mute reached the Watch after them, in order", ops("mute").any { it.body == "true" })
        val sequences = packets.map { it.seq }
        assertEquals("every Phone packet to the Watch is in sequence, without a gap", (1L..sequences.size.toLong()).toList(), sequences)
        assertTrue(app.live.active)
    }

    @Test fun P13_theHeartbeatRepeatsTheCurrentStateSoALostSendCannotLeaveTheWatchMutedOrUnmutedWrongly() {
        running(); app.live.setMuted(true); settle()
        app.live.setMuted(false); settle()
        states.clear() // from here nothing in the Phone state changes: only the heartbeat can send it
        repeat(5) { idle(300); incoming("ping") }
        val repeated = states.toList()
        assertTrue("the current state was repeated about once a second: ${repeated.size}", repeated.size >= 1)
        assertTrue("carrying the current unmuted state, active, for this media", repeated.all { it.getBoolean("active") && !it.getBoolean("muted") && it.getString("media") == open!!.media })
        val before = states.size
        assertEquals("each with a newer state sequence", repeated.map { it.getLong("seq") }.sorted(), repeated.map { it.getLong("seq") })
        app.live.end(); settle(); states.clear(); repeat(4) { idle(500) }
        assertTrue("no repeat after the end (was $before)", states.none { it.optBoolean("active") })
    }

    companion object {
        const val WATCH_OFFER = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=WATCH-OWNED-OFFER\r\n"
        const val PROVIDER_ANSWER = "v=0\r\no=- 9 2 IN IP4 0.0.0.0\r\ns=PROVIDER-ANSWER\r\n"
    }
}
