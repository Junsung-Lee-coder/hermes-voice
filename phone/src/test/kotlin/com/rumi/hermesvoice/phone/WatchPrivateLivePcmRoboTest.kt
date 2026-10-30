package com.rumi.hermesvoice.phone

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.live.*
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.headset.HeadsetPolicy
import com.rumi.hermesvoice.core.FakeDevices
import com.rumi.hermesvoice.core.Gear
import com.rumi.hermesvoice.phone.live.*
import io.mockk.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** Actual relay intake/private output generation; only provider/Channel/player platform leaves are synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class WatchPrivateLivePcmRoboTest:PhoneRoboBase(){
    private val packets=java.util.concurrent.ConcurrentLinkedQueue<WatchLiveWire.Packet>()
    private val devices=FakeDevices(listOf(Gear.wiredHeadset))
    private val writes=java.util.concurrent.ConcurrentLinkedQueue<LivePcmFrame>()
    private val released=AtomicInteger()
    private lateinit var channels:ChannelClient
    private var fixture:PhoneRelayFixture?=null
    @Before fun setup(){
        baseSetUp();app.settings.saveWatchSettings(WatchSettings(watchLiveEnabled=true));app.live.admission={app.live.admitted(it)}
        app.settings.useHeadset=true
        val f=PhoneApp::class.java.getDeclaredField("headset\$delegate");f.isAccessible=true;f.set(app,lazyOf(HeadsetPolicy({true},devices)))
        val n=mockk<Node>();every{n.id}returns "watch-node"
        val info=mockk<CapabilityInfo>();every{info.nodes}returns setOf(n)
        val caps=mockk<CapabilityClient>();every{caps.getCapability(any(),any())}returns Tasks.forResult(info)
        every{Wearable.getCapabilityClient(any<Context>())}returns caps
        val messages=mockk<MessageClient>();every{messages.sendMessage(any(),any(),any())}answers{WatchLiveWire.parse(thirdArg<ByteArray>())?.let{packets+=it};Tasks.forResult(1)}
        every{Wearable.getMessageClient(any<Context>())}returns messages
        channels=mockk(relaxed=true);every{Wearable.getChannelClient(any<Context>())}returns channels;every{channels.close(any())}returns Tasks.forResult(null)
        val original=fake.server.dispatcher
        fake.server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(r:okhttp3.mockwebserver.RecordedRequest):okhttp3.mockwebserver.MockResponse{
            if(r.path=="/api/audio/voice-live/session")return okhttp3.mockwebserver.MockResponse().setHeader("Content-Type","application/json").setBody("""{"ok":true,"session":{"id":"private-fixture"},"transport":{"type":"webrtc","sdp":"v=0\r\nfixture\r\n"}}""")
            return original.dispatch(r)
        }}
    }
    @After fun cleanup(){app.live.end();fixture?.close();settle();baseTearDown()}
    private fun start():Pair<WatchLiveWire.Packet,WatchLiveTransport>{
        conversation("alpha")
        app.liveWatch.receive("watch-node",JSONObject().put("v",1).put("id","control").put("op","start").put("probe","probe").toString().toByteArray())
        assertTrue(await{packets.any{it.op=="open"}});val open=packets.last{it.op=="open"}
        val fx=PhoneRelayFixture(app,open,bind=false);fixture=fx;val t=fx.native
        t.pcmFactory={id,current,_->assertEquals(Gear.wiredHeadset.id,id);assertTrue(current());assertFalse(app.phoneMic.occupied)
            object:LivePcmOutput{override fun write(frame:LivePcmFrame):Boolean{assertTrue(current());writes+=frame;return true};override fun close(){released.incrementAndGet()}}}
        return open to t
    }
    private fun channel(open:WatchLiveWire.Packet,node:String="watch-node",output:String=JSONObject(open.body).getString("stream")):ChannelClient.Channel {
        val c=mockk<ChannelClient.Channel>();every{c.nodeId}returns node;every{c.path}returns WatchRelayAudio.Address(open.id,open.media,open.epoch,output).path();return c
    }
    @Test fun decodedWatchFramesUseOnlyTheExactPrivateGenerationAndNeverPhoneCapture(){
        val (open,t)=start();assertTrue(JSONObject(open.body).getBoolean("private"));assertFalse(app.phoneMic.occupied);t.outputAdmitted(true)
        app.liveWatch.audioChannel(channel(open,node="wrong-node"));app.liveWatch.audioChannel(channel(open,output="old-output"));settle();verify(exactly=0){channels.getInputStream(any())}
        fixture!!.bind();app.liveWatch.receive("watch-node",WatchLiveWire.packet(open.id,open.media,open.epoch,1,"offer",WatchRelayAudio.OFFER))
        assertTrue(await{fixture!!.output!=null&&app.live.state.value.ready})
        app.liveWatch.receive("watch-node",WatchLiveWire.packet(open.id,open.media,open.epoch,2,"private_ready",JSONObject(open.body).getString("output")))
        fixture!!.output!!(LivePcmFrame(1,48000,1,byteArrayOf(1,2,3,4)))
        assertTrue(await{writes.size==1});assertArrayEquals(byteArrayOf(1,2,3,4),writes.single().pcm)
        devices.disconnectAll();app.liveWatch.privateChanged();assertTrue(await{released.get()>0})
        fixture!!.output!!(LivePcmFrame(2,48000,1,byteArrayOf(5,6)));settle();assertEquals(1,writes.size)
        assertTrue("output failure leaves Watch input/Hermes control alive",app.live.active)
        app.liveWatch.audioChannel(channel(open));settle();verify(exactly=1){channels.getInputStream(any())}
        assertFalse(app.phoneMic.occupied);assertEquals(0,fake.speakRequests.get())
    }
    @Test fun privateOutputLossAndForegroundRefusalNeverCloseOrSubstituteWatchInput(){
        val (_,t)=start();val before=packets.size;t.outputAdmitted(false);settle();assertTrue(app.live.active);assertFalse(app.phoneMic.occupied)
        devices.disconnectAll();app.liveWatch.privateChanged();settle();assertTrue(app.live.active);assertFalse(packets.drop(before).any{it.op in setOf("silence","close")})
        assertTrue(app.live.state.value.status.contains("Watch input continues"));assertTrue(app.live.state.value.outputNotice.contains("Watch input continues"));assertTrue(fake.prompts.isEmpty())
    }
}
