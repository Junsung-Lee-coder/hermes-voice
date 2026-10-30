package com.rumi.hermesvoice.phone

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.live.WatchLiveWire
import com.rumi.hermesvoice.core.settings.WatchSettings
import io.mockk.*
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** Real Phone control + authenticated HTTP API + canonical recovery, synthetic Watch/provider peers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class WatchLiveRelayRoboTest:PhoneRoboBase(){
    private val packets=java.util.concurrent.ConcurrentLinkedQueue<WatchLiveWire.Packet>()
    private val creates=AtomicInteger();private var now=1000L
    private val mediaFixtures=ArrayList<PhoneRelayFixture>()
    private var offerBody:JSONObject?=null;private var refusal=false
    private var selected:WatchLiveWire.Packet?=null;private var sequence=0L
    @Before fun setup(){
        baseSetUp();app.live.monotonicMs={now};app.live.admission={app.live.admitted(it)}
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled=false,watchLiveEnabled=true))
        val node=mockk<Node>();every{node.id}returns "watch-node"
        val info=mockk<CapabilityInfo>();every{info.nodes}returns setOf(node)
        val caps=mockk<CapabilityClient>();every{caps.getCapability(any(),any())}returns Tasks.forResult(info)
        every{Wearable.getCapabilityClient(any<Context>())}returns caps
        val messages=mockk<MessageClient>();every{messages.sendMessage(any(),any(),any())}answers{
            WatchLiveWire.parse(thirdArg<ByteArray>())?.let{packets+=it};Tasks.forResult(1)
        };every{Wearable.getMessageClient(any<Context>())}returns messages
        val original=fake.server.dispatcher
        fake.server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{
            if(request.path=="/api/audio/voice-live/session"){
                creates.incrementAndGet();offerBody=JSONObject(request.body.readUtf8())
                if(refusal)return MockResponse().setResponseCode(503).setBody("synthetic uncertain creation")
                return MockResponse().setHeader("Content-Type","application/json").setBody(JSONObject().put("ok",true)
                    .put("session",JSONObject().put("id","synthetic-provider"))
                    .put("transport",JSONObject().put("type","webrtc").put("sdp","v=0\r\nanswer\r\n")).toString())
            };return original.dispatch(request)
        }}
    }
    @After fun cleanup(){app.live.end();mediaFixtures.forEach{it.close()};settle();baseTearDown()}
    private fun command(op:String){app.liveWatch.receive("watch-node",JSONObject().put("v",1).put("id","watch-call").put("op",op).put("probe","probe").toString().toByteArray())}
    private fun open(){command("start");assertTrue(await{packets.any{it.op=="open"}});selectNewest()}
    private fun selectNewest(){selected=packets.last{it.op=="open"};sequence=0;mediaFixtures+=PhoneRelayFixture(app,selected!!)}
    private fun incoming(op:String,body:String="",source:String="watch-node"){
        val p=selected!!;app.liveWatch.receive(source,WatchLiveWire.packet(p.id,p.media,p.epoch,++sequence,op,body))
    }
    private fun negotiate(){
        incoming("offer",com.rumi.hermesvoice.core.live.WatchRelayAudio.OFFER)
        assertTrue(await{packets.any{it.op=="answer"&&it.media==selected!!.media}})
        incoming("microphone","true");incoming("event","{\"type\":\"session.started\",\"session\":{\"id\":\"synthetic-provider\"}}")
        incoming("connected");assertTrue(await{app.live.state.value.ready&&app.live.state.value.inputActive})
        assertFalse(app.phoneMic.occupied);assertFalse(app.audio.microphoneClaimed(com.rumi.hermesvoice.core.VoiceOrigin.PHONE))
    }
    @Test fun watchOfferUsesSupportedAuthenticatedSessionCreationOnceAndNeverPhoneAudio(){
        conversation("alpha");open();negotiate()
        assertEquals("v=0\r\nphone-fixture-offer\r\n",offerBody!!.getString("sdp"));assertTrue(offerBody!!.getJSONArray("history").length()>0)
        incoming("offer","duplicate");settle();assertEquals(1,creates.get())
        app.live.setMuted(true);assertTrue(await{packets.any{it.op=="mute"&&it.body=="true"}})
        assertEquals(0,fake.speakRequests.get());assertTrue(fake.prompts.isEmpty())
    }
    @Test fun canonicalLateFinalAfterIdleCreatesFreshWatchMediaAndOriginMicrophoneWithoutResubmit(){
        val target=conversation("alpha");open();negotiate();app.live.bindSession(target)
        assertTrue(await{app.live.state.value.boundSessionId==target});val first=selected!!.media
        now+=10000;app.live.checkLifetime();assertFalse(app.live.active)
        fake.injectFinalRow(target,"late Watch answer",1_800_000_000.0,"late-watch")
        val done=java.util.concurrent.atomic.AtomicBoolean(false)
        app.appScope.launch{try{app.finalRecovery.reconcile()}finally{done.set(true)}}
        assertTrue(await{done.get()&&packets.count{it.op=="open"}==2});selectNewest();assertNotEquals(first,selected!!.media);negotiate()
        assertTrue(await{app.live.state.value.boundSessionId==target&&mediaFixtures.any{fx->fx.sent.any{it.contains("late Watch answer")}}})
        app.finalRecovery.requestReconcile();settle();assertEquals(2,creates.get());assertTrue(fake.prompts.isEmpty());assertFalse(app.settings.watchSettings().phoneLiveEnabled)
    }
    @Test fun endAfterIdleRevokesWatchFollowAndOldPeerCannotResume(){
        val target=conversation("alpha");open();negotiate();app.live.bindSession(target);assertTrue(await{app.live.state.value.boundSessionId==target})
        now+=10000;app.live.checkLifetime();command("end");settle()
        fake.injectFinalRow(target,"do not speak",1_800_000_000.0,"after-stop");app.finalRecovery.requestReconcile();settle()
        incoming("event","{\"type\":\"session.started\",\"session\":{\"id\":\"old\"}}")
        settle();assertFalse(app.live.active);assertEquals(1,creates.get());assertFalse(app.phoneMic.occupied)
    }
    @Test fun uncertainSdpCreationIsNotRetriedByDuplicateOfferOrWatchStart(){
        conversation("alpha");refusal=true;open();incoming("offer",com.rumi.hermesvoice.core.live.WatchRelayAudio.OFFER)
        assertTrue(await{creates.get()==1&&!app.live.active});incoming("offer","duplicate");command("start");settle()
        assertEquals(1,creates.get());assertFalse(app.phoneMic.occupied);assertTrue(fake.prompts.isEmpty())
    }
    @Test fun endBeforeWatchOfferCannotCreateUsingRetiredConversationOrNewCredentials(){
        conversation("alpha");open();app.live.end()
        incoming("offer","v=0\r\nlate-offer\r\n");settle()
        assertEquals(0,creates.get());assertFalse(app.live.active);assertFalse(app.phoneMic.occupied)
    }
    @Test fun wrongNodeAndWrongMediaCannotCreateProviderSession(){
        conversation("alpha");open();incoming("offer","bad","other-node");settle();assertEquals(0,creates.get())
        val p=selected!!;app.liveWatch.receive("watch-node",WatchLiveWire.packet(p.id,"other-media",p.epoch,1,"offer","bad"));settle();assertEquals(0,creates.get())
    }

    // The Watch-relay/provider caller keeps its typed setup observation; wire words keep these readable without new symbols.
    private fun liveSetupEvents()=app.diagnostics.log.snapshot().filter{it.code.wire=="live_setup_failed"}
    @Test fun watchProviderRejectionKeepsTheSpecificTypedReasonInsteadOfTheGenericMask(){
        conversation("alpha");refusal=true;open();incoming("offer",com.rumi.hermesvoice.core.live.WatchRelayAudio.OFFER)
        assertTrue(await{creates.get()==1&&!app.live.active})
        val status=app.live.state.value.status
        assertTrue(status,status.contains("Phone Live creation failed or uncertain")&&status.contains("HTTP 503"))
        assertFalse(status,status.contains("Live connection failed"))
        assertEquals("one typed Watch-relay setup observation",1,liveSetupEvents().size);val e=liveSetupEvents().single()
        assertEquals("watch",e.origin?.wire);assertEquals("live_session",e.stage?.wire);assertEquals(503,e.status);assertEquals("rejected",e.cause?.wire);assertNull(e.ref)
        val report=(kotlinx.coroutines.runBlocking{app.diagnostics.export()} as com.rumi.hermesvoice.core.diag.DiagExportResult.Ready).file.readText()
        assertTrue(report.contains("\"live_setup_failed\""))
        for(s in listOf("synthetic uncertain creation","phone-fixture-offer","synthetic-provider","Alpha",fake.accessToken,fake.refreshToken))assertFalse(s,report.contains(s)||status.contains(s))
        incoming("offer","duplicate");command("start");settle();assertEquals("never retried",1,creates.get())
    }
    @Test fun endDuringTheWatchProviderRequestIsCancellationNotAnObservedSetupFailureAndNeverRetried(){
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val original=fake.server.dispatcher
        fake.server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{
            if(request.path=="/api/audio/voice-live/session"){entered.countDown();release.await(10,java.util.concurrent.TimeUnit.SECONDS)}
            return original.dispatch(request)
        }}
        try{
            conversation("alpha");open();incoming("offer",com.rumi.hermesvoice.core.live.WatchRelayAudio.OFFER)
            assertTrue("the provider request is in flight",await{entered.count==0L})
            app.live.end();assertFalse(app.live.active)
        }finally{release.countDown()}
        assertTrue(await{creates.get()==1});settle()
        assertTrue(app.live.state.value.status,app.live.state.value.status.startsWith("Live ended"))
        assertTrue(liveSetupEvents().isEmpty())
        incoming("offer","duplicate");command("start");settle();assertEquals("never retried",1,creates.get());assertTrue(fake.prompts.isEmpty())
    }
    @Test fun watchRefusalBeforeProviderIsObservedOnceAsRelaySetupAndKeepsTheWatchReason(){
        conversation("alpha");open();incoming("refused","Watch microphone permission unavailable")
        assertTrue(await{!app.live.active});assertEquals(0,creates.get())
        assertTrue(app.live.state.value.status,app.live.state.value.status.contains("Watch microphone permission unavailable"))
        assertEquals("one Watch-relay setup observation",1,liveSetupEvents().size);val e=liveSetupEvents().single()
        assertEquals("live_relay",e.stage?.wire);assertEquals("watch",e.origin?.wire);assertEquals("local",e.cause?.wire);assertNull(e.status)
    }
    @Test fun watchStartWithUnconfirmedRelayRouteIsObservedOnceAsStartSetupRefusalWithoutRetry(){
        app.live.watchRouteFactory={object:com.rumi.hermesvoice.phone.live.LiveRoute{override fun open()=true;override fun valid()=false;override fun close(){}}}
        conversation("alpha");open();incoming("offer",com.rumi.hermesvoice.core.live.WatchRelayAudio.OFFER)
        assertTrue(await{creates.get()==1&&!app.live.active})
        assertTrue(app.live.state.value.status,app.live.state.value.status.startsWith("Live output route is not confirmed"))
        assertEquals("one Watch-origin setup observation",1,liveSetupEvents().size);val e=liveSetupEvents().single()
        assertEquals("watch",e.origin?.wire);assertEquals("live_start",e.stage?.wire);assertEquals("local",e.cause?.wire);assertNull(e.status)
        incoming("offer","duplicate");command("start");settle();assertEquals("never retried",1,creates.get());assertEquals(1,liveSetupEvents().size)
    }
}
