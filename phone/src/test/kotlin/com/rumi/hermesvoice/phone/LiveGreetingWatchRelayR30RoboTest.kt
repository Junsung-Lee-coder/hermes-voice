package com.rumi.hermesvoice.phone

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.FakeDevices
import com.rumi.hermesvoice.core.Gear
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.headset.HeadsetPolicy
import com.rumi.hermesvoice.core.live.*
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.wake.ClaimVerdict
import com.rumi.hermesvoice.core.wake.WakeClaim
import com.rumi.hermesvoice.phone.live.*
import io.mockk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R30 on the Watch paths: the greeting of a plain Watch hold travels over the Phone-owned relay, only once the call's actual output is
 * admitted (a selected Phone private output: Android's admission AND the Watch's quiesced speaker), and a Watch wake request never
 * greets. R31: model output the relay discarded before that admission does not cancel the owed greeting; output that arrived while the
 * output was admitted still does. The real PhoneLiveWatchControl and WatchLiveTransport; the provider, channel and players are
 * synthetic platform leaves, so these prove the requests, never that a greeting was generated, played or heard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveGreetingWatchRelayR30RoboTest : PhoneRoboBase() {
    private val packets = java.util.concurrent.ConcurrentLinkedQueue<WatchLiveWire.Packet>()
    private val devices = FakeDevices(listOf(Gear.wiredHeadset))
    private var fixture: PhoneRelayFixture? = null
    private var seq = 0L
    private var clock = 0L
    private fun watch(private: Boolean, listenOn: WakeLocation = WakeLocation.OFF) {
        app.settings.saveWatchSettings(WatchSettings(watchLiveEnabled = true, liveIdleSeconds = 60, wakeLocation = listenOn)); app.live.admission = { app.live.admitted(it) }
        app.settings.useHeadset = private
        if (private) { val f = PhoneApp::class.java.getDeclaredField("headset\$delegate"); f.isAccessible = true; f.set(app, lazyOf(HeadsetPolicy({ true }, devices))) }
        val n = mockk<Node>(); every { n.id } returns "watch-node"; every { n.isNearby } returns true
        val info = mockk<CapabilityInfo>(); every { info.nodes } returns setOf(n)
        val caps = mockk<CapabilityClient>(); every { caps.getCapability(any(), any()) } returns Tasks.forResult(info)
        every { Wearable.getCapabilityClient(any<Context>()) } returns caps
        val messages = mockk<MessageClient>(); every { messages.sendMessage(any(), any(), any()) } answers { WatchLiveWire.parse(thirdArg<ByteArray>())?.let { packets += it }; Tasks.forResult(1) }
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        val channels = mockk<ChannelClient>(relaxed = true); every { Wearable.getChannelClient(any<Context>()) } returns channels; every { channels.close(any()) } returns Tasks.forResult(null)
        val original = fake.server.dispatcher
        fake.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() { override fun dispatch(r: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
            if (r.path == "/api/audio/voice-live/session") return okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"session":{"id":"greeting-relay"},"transport":{"type":"webrtc","sdp":"v=0\r\nfixture\r\n"}}""")
            return original.dispatch(r)
        } }
        conversation("alpha")
    }
    @Before fun setup() { baseSetUp() }
    @After fun cleanup() { app.live.end(); fixture?.close(); settle(); baseTearDown() }
    private fun control(extra: JSONObject.() -> Unit = {}) = app.liveWatch.receive("watch-node",
        JSONObject().put("v", 1).put("id", "control").put("op", "start").put("probe", "probe").apply(extra).toString().toByteArray())
    private fun incoming(open: WatchLiveWire.Packet, op: String, body: String = "") =
        app.liveWatch.receive("watch-node", WatchLiveWire.packet(open.id, open.media, open.epoch, ++seq, op, body))
    private fun greetings() = fixture?.sent.orEmpty().map { JSONObject(it) }.filter { it.optString("type") == "session.commentary.append" && it.optString("content").startsWith("지금 바로 한국어로") }
    private fun live(wire: String) = app.diagnostics.log.snapshot().filter { it.code.wire == wire }
    /** The provider's own output transcript delta for this call, through the relay's real event path. */
    private fun model(text: String) { val start = clock; clock += 100
        fixture!!.event!!(JSONObject().put("type", "session.output_transcript.delta").put("delta", text).put("start_ms", start).put("end_ms", start + 100).put("event_id", "m$start").toString()) }
    /** A Watch hold (or, with [extra], a Watch wake control) whose Phone relay started the provider; [microphone]: the Watch reports its microphone active. */
    private fun startedRelay(microphone: Boolean = true, extra: JSONObject.() -> Unit = {}): WatchLiveWire.Packet {
        control(extra)
        assertTrue("the Phone opened the Watch relay", await(20_000) { packets.any { it.op == "open" } }); val open = packets.last { it.op == "open" }
        fixture = PhoneRelayFixture(app, open)
        incoming(open, "offer", WatchRelayAudio.OFFER)
        assertTrue(await(20_000) { packets.any { it.op == "answer" } })
        incoming(open, "connected")
        assertTrue(await(20_000) { app.live.state.value.ready })
        if (microphone) { incoming(open, "microphone", "true"); assertTrue(await(20_000) { app.live.state.value.inputActive }) }
        return open
    }
    /** A selected Phone private output: the Watch quiesced its speaker and Android admitted the private output. */
    private fun admitPrivate(open: WatchLiveWire.Packet) {
        fixture!!.native.pcmFactory = { _, _, _ -> object : LivePcmOutput { override fun write(frame: LivePcmFrame) = true; override fun close() {} } }
        incoming(open, "private_ready", JSONObject(open.body).getString("output")); fixture!!.native.outputAdmitted(true)
    }

    @Test fun W30_01_aPlainWatchHoldIsGreetedOnceOverThePhoneOwnedRelayWithTheGreetingInItsStartupInput() {
        watch(private = false)
        startedRelay()
        assertTrue("one request over the relay's provider transport", await(20_000) { greetings().size == 1 })
        assertTrue(greetings().single().isNull("delegation_id"))
        assertFalse("no Phone microphone substitution", app.phoneMic.occupied)
        settle(); assertEquals(1, greetings().size)
    }

    @Test fun W30_02_aSelectedPrivateOutputIsGreetedOnlyAfterAndroidAdmittedItAndTheWatchQuiesced() {
        watch(private = true)
        val open = startedRelay()
        val t = fixture!!.native
        t.pcmFactory = { _, _, _ -> object : LivePcmOutput { override fun write(frame: LivePcmFrame) = true; override fun close() {} } }
        settle(); assertTrue("private output not admitted: nothing the caller could hear", greetings().isEmpty())
        incoming(open, "private_ready", JSONObject(open.body).getString("output"))
        incoming(open, "microphone", "true"); settle()
        assertTrue("the Watch quiesced but Android has not admitted the private output yet", greetings().isEmpty())
        t.outputAdmitted(true)
        assertTrue("admitted and quiesced: the one request", await(20_000) { greetings().size == 1 })
        incoming(open, "microphone", "true"); t.outputAdmitted(true); settle()
        assertEquals("once", 1, greetings().size)
    }

    /** R31 (D2): the recognized Watch wake holds the Phone coordinator's own granted claim, so its call genuinely opens, starts and listens. */
    @Test fun W30_03_aRecognizedWatchWakeThatOpensStartsAndListensNeverGreets() {
        watch(private = false, listenOn = WakeLocation.BOTH)
        val admission = app.wiring().core.wakeAdmission; val epoch = admission.epoch; val revision = app.settings.watchSettings().revision
        assertEquals(ClaimVerdict.GRANTED, admission.claim(WakeClaim("greeting-wake-r31", VoiceOrigin.WATCH, "watch-node", revision, 1, epoch)))
        startedRelay { put("wake", true); put("wake_claim_id", "greeting-wake-r31"); put("wake_revision", revision); put("recognized", "alpha 에 회의록 정리 부탁해") }
        assertEquals(VoiceOrigin.WATCH, app.live.state.value.origin)
        assertTrue("the wake call started and listens", app.live.state.value.started && app.live.state.value.inputActive && app.live.state.value.listening)
        assertEquals("its one claim was used, once", epoch + 1, admission.epoch)
        assertEquals(ClaimVerdict.USED, admission.renew("greeting-wake-r31", VoiceOrigin.WATCH, "watch-node"))
        val h = fixture!!.history!!
        assertFalse("no greeting developer message in its startup input", LiveStartupInput.greets(h))
        h.getJSONObject(h.length() - 1).let { assertEquals("user", it.getString("role")); assertEquals("alpha 에 회의록 정리 부탁해", it.getJSONArray("content").getJSONObject(0).getString("text")) }
        settle(); idle(1_000); settle()
        assertTrue("a Watch wake request never greets", greetings().isEmpty())
        assertTrue(live("live_greeting").isEmpty())
    }

    @Test fun W30_04_modelOutputDiscardedBeforeThePrivateOutputWasAdmittedStillLetsAdmissionGreetOnce() {
        watch(private = true)
        val open = startedRelay()
        model("안녕하세요. 준비됐습니다."); settle()
        assertTrue("the private output is not admitted: no request yet", greetings().isEmpty())
        admitPrivate(open)
        assertTrue("admitted: the owed greeting is requested", await(20_000) { greetings().size == 1 })
        assertTrue(greetings().single().isNull("delegation_id"))
        incoming(open, "microphone", "true"); fixture!!.native.outputAdmitted(true); settle()
        assertEquals("once", 1, greetings().size)
        app.live.end(); settle()
        assertEquals("the observations still count every model output transcript", 1, live("live_spoke").single().n)
    }

    @Test fun W30_05_modelOutputThatArrivedWhileThePrivateOutputWasAdmittedStillMeansNoLateGreeting() {
        watch(private = true)
        val open = startedRelay(microphone = false)
        admitPrivate(open); settle()
        assertTrue("admitted but the Watch microphone is not verified yet: no request", greetings().isEmpty())
        model("안녕하세요. 준비됐습니다."); settle()
        incoming(open, "microphone", "true")
        assertTrue(await(20_000) { app.live.state.value.listening }); settle(); idle(1_000); settle()
        assertTrue("the model already spoke over the admitted output: no second greeting", greetings().isEmpty())
    }

    @Test fun W30_06_aStopAfterDiscardedModelOutputNeverLetsALateAdmissionGreet() {
        watch(private = true)
        val open = startedRelay()
        model("안녕하세요."); settle()
        val t = fixture!!.native
        app.live.end(); settle()
        t.pcmFactory = { _, _, _ -> object : LivePcmOutput { override fun write(frame: LivePcmFrame) = true; override fun close() {} } }
        incoming(open, "private_ready", JSONObject(open.body).getString("output")); t.outputAdmitted(true); incoming(open, "microphone", "true")
        settle(); idle(1_000); settle()
        assertTrue("an ended call's late admission requests nothing", greetings().isEmpty())
        assertTrue(live("live_greeting").isEmpty())
    }

    @Test fun W30_07_aBlankDeltaOverTheAdmittedOutputAfterDiscardedOutputIsNotSpeechAndTheGreetingStillFollows() {
        watch(private = true)
        val open = startedRelay(microphone = false)
        model("안녕하세요."); settle()
        admitPrivate(open); settle()
        model(" "); settle()
        incoming(open, "microphone", "true")
        assertTrue("listening, admitted, nothing heard: the one request", await(20_000) { greetings().size == 1 })
        settle(); assertEquals(1, greetings().size)
    }
}
