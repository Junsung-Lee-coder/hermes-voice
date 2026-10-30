package com.rumi.hermesvoice.phone

import android.Manifest
import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.FakeDevices
import com.rumi.hermesvoice.core.Gear
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.headset.HeadsetPolicy
import com.rumi.hermesvoice.core.live.WatchLiveWire
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.phone.live.*
import io.mockk.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Real ViewModel -> private hub -> WatchLiveTransport; native Watch microphone is modelled by protocol reports. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class CallerClosureHeadsetTransportRoboTest:PhoneRoboBase(){
    private val packets=java.util.concurrent.ConcurrentLinkedQueue<WatchLiveWire.Packet>()
    private val devices=FakeDevices(listOf(Gear.wiredHeadset))
    private lateinit var open:WatchLiveWire.Packet;private var sequence=0L
    private var mediaFixture:PhoneRelayFixture?=null
    @Before fun setup(){
        baseSetUp();app.live.admission={app.live.admitted(it)};app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled=false,watchLiveEnabled=true,liveIdleSeconds=60))
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val f=HeadsetPolicy::class.java.getDeclaredField("devices");f.isAccessible=true;f.set(app.headset,devices)
        val node=mockk<Node>();every{node.id}returns "watch-node"
        val info=mockk<CapabilityInfo>();every{info.nodes}returns setOf(node)
        val caps=mockk<CapabilityClient>();every{caps.getCapability(any(),any())}returns Tasks.forResult(info)
        every{Wearable.getCapabilityClient(any<Context>())}returns caps
        val messages=mockk<MessageClient>();every{messages.sendMessage(any(),any(),any())}answers{WatchLiveWire.parse(thirdArg<ByteArray>())?.let{packets+=it};Tasks.forResult(1)}
        every{Wearable.getMessageClient(any<Context>())}returns messages
        val original=fake.server.dispatcher
        fake.server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{
            if(request.path=="/api/audio/voice-live/session")return MockResponse().setHeader("Content-Type","application/json").setBody(JSONObject().put("ok",true).put("session",JSONObject().put("id","provider")).put("transport",JSONObject().put("type","webrtc").put("sdp","v=0\r\nanswer\r\n")).toString())
            return original.dispatch(request)
        }}
    }
    @After fun cleanup(){app.live.end();mediaFixture?.close();settle();baseTearDown()}
    private fun incoming(op:String,body:String=""){app.liveWatch.receive("watch-node",WatchLiveWire.packet(open.id,open.media,open.epoch,++sequence,op,body))}
    private fun begin(){
        conversation("alpha")
        app.liveWatch.receive("watch-node",JSONObject().put("v",1).put("id","control").put("op","start").toString().toByteArray())
        assertTrue(await{packets.any{it.op=="open"}});open=packets.last{it.op=="open"};mediaFixture=PhoneRelayFixture(app,open)
        incoming("offer",com.rumi.hermesvoice.core.live.WatchRelayAudio.OFFER);assertTrue(await{packets.any{it.op=="answer"}})
        incoming("microphone","true");incoming("event","{\"type\":\"session.started\",\"session\":{\"id\":\"provider\"}}");incoming("connected")
        assertTrue(await{app.live.state.value.ready&&app.live.state.value.inputActive})
    }
    private fun checkSetting(hasHeadset:Boolean){
        if(!hasHeadset)devices.disconnectAll()
        val model=PhoneViewModel(app);settle();begin();val baseline=packets.size
        model.setUseHeadset(true);settle()
        assertTrue("real settings caller preserves original Watch media",app.live.active)
        assertTrue(app.live.state.value.inputActive);assertEquals(open.media,app.live.callIdentity())
        assertFalse(packets.drop(baseline).any{it.op in setOf("silence","close")})
        if(hasHeadset){
            assertTrue("actual split transport receives private output policy",packets.any{it.op=="private"&&it.media==open.media})
            val f=PhoneLiveWatchControl::class.java.getDeclaredField("remote");f.isAccessible=true
            (f.get(app.liveWatch) as WatchLiveTransport).outputAdmitted(false)
            devices.disconnectAll();app.liveWatch.privateChanged();settle()
            assertTrue(app.live.active);assertTrue(app.live.state.value.inputActive)
            assertTrue(app.live.state.value.outputNotice.contains("Watch input continues"))
        }
        model.setUseHeadset(false);settle();assertTrue(app.live.active)
        assertFalse(packets.drop(baseline).any{it.op in setOf("silence","close")})
        assertFalse(app.phoneMic.occupied);assertFalse(app.audio.microphoneClaimed(VoiceOrigin.PHONE))
        app.settings.saveWatchSettings(app.settings.watchSettings().copy(watchLiveEnabled=false));app.live.settingsChanged()
        assertFalse(app.live.active);assertTrue(await{packets.any{it.op=="silence"}})
        assertTrue(fake.prompts.isEmpty());assertEquals(0,fake.speakRequests.get())
    }
    @Test fun actualHeadsetSetterReachesOutputOnlyTransportWithHeadsetAndPhonePermissionDenied()=checkSetting(true)
    @Test fun actualHeadsetSetterKeepsWatchMediaWithoutPhonePersonalOutput()=checkSetting(false)
}
