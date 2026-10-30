package com.rumi.hermesvoice.phone

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.core.app.ServiceCompat
import com.google.android.gms.wearable.ChannelClient
import com.rumi.hermesvoice.core.live.WatchLiveWire
import com.rumi.hermesvoice.phone.live.*
import io.mockk.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.webrtc.DataChannel
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Real controller -> default transport -> same actual Service instance. Only platform/provider leaves are doubled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class LiveInputServiceLifetimeRoboTest {
    private lateinit var fixture:LiveDeviceInputRoboTest
    private lateinit var w:WatchPhoneRelayDefaultRoboTest
    private lateinit var service:PhoneLiveVoiceService
    private lateinit var controller:org.robolectric.android.controller.ServiceController<PhoneLiveVoiceService>
    private var sequence=0L
    private var fgsType=0;private var fgsText="";private var fgsCalls=0
    private val stops=AtomicInteger()
    private class Setup(message:String):RuntimeException(message)
    private fun requireSetup(ok:Boolean,message:String){if(!ok)throw Setup(message)}
    private fun field(o:Any,n:String):Any?=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o)
    private fun await(f:()->Boolean):Boolean {
        val end=System.nanoTime()+8_000_000_000
        while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();if(f())return true;Thread.sleep(10)}
        return f()
    }
    @Before fun setup(){
        fixture=LiveDeviceInputRoboTest();fixture.setup();w=field(fixture,"w") as WatchPhoneRelayDefaultRoboTest
        controller=Robolectric.buildService(PhoneLiveVoiceService::class.java).create();service=controller.get()
        mockkObject(service)
        every{service.stopSelf(any())}answers{stops.incrementAndGet();Unit}
        every{service.stopSelf()}answers{stops.incrementAndGet();Unit}
        every{ServiceCompat.startForeground(any(),any(),any(),any())}answers{
            fgsType=arg(3);fgsText=arg<android.app.Notification>(2).extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString();fgsCalls++;Unit
        }
    }
    @After fun cleanup(){w.app.live.end();controller.destroy();fixture.cleanup()}
    @Suppress("UNCHECKED_CAST") private fun packets()=field(w,"packets") as ConcurrentLinkedQueue<WatchLiveWire.Packet>
    private fun remote()=field(w.app.liveWatch,"remote") as WatchLiveTransport
    private fun incoming(p:WatchLiveWire.Packet,op:String,body:String=""){
        w.app.liveWatch.receive("watch-node",WatchLiveWire.packet(p.id,p.media,p.epoch,++sequence,op,body))
    }
    private fun open(id:String):WatchLiveWire.Packet {
        if(id=="owner-a")w.conversation("alpha")
        w.app.liveWatch.receive("watch-node",JSONObject().put("v",1).put("id",id).put("op","start").put("probe","p").toString().toByteArray())
        requireSetup(await{packets().any{it.op=="open"&&it.id==id}},"real Watch control must open this owner")
        val p=packets().last{it.op=="open"&&it.id==id};sequence=0
        incoming(p,"offer",com.rumi.hermesvoice.core.live.WatchRelayAudio.OFFER)
        val c=mockk<ChannelClient.Channel>();every{c.nodeId}returns "watch-node"
        every{c.path}returns "/hv/v3/live/audio/${p.id}/${p.media}/${p.epoch}/${JSONObject(p.body).getString("stream")}"
        w.app.liveWatch.audioChannel(c)
        return p
    }
    private fun inputIntent(p:WatchLiveWire.Packet):Intent {
        val request=JSONObject(p.body).getString("input_hold")
        requireSetup(request.isNotBlank(),"real selected headset must require exact Watch hold")
        incoming(p,"input_ready",request)
        var found:Intent?=null
        requireSetup(await{
            var next=shadowOf(w.app).nextStartedService
            while(next!=null){if(next.getBooleanExtra("input_only",false))found=next;next=shadowOf(w.app).nextStartedService}
            found!=null
        },"real controller must issue input-only FGS intent")
        return found!!
    }
    private fun admit(intent:Intent,start:Int){service.onStartCommand(intent,0,start)}
    private fun capture(p:WatchLiveWire.Packet,start:Int):Intent {
        val intent=inputIntent(p);admit(intent,start)
        requireSetup(await{w.app.live.state.value.inputActive},"current verified headset capture must become active")
        requireSetup(fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE!=0,"microphone FGS must be admitted")
        return intent
    }
    private fun baseIntent(token:Long)=Intent(w.app,PhoneLiveVoiceService::class.java).putExtra("generation",token)
    @Test fun delayedOldProviderFinalizationCannotDowngradeSuccessorInputFgs(){
        val a=open("owner-a");capture(a,1);incoming(a,"connected")
        requireSetup(await{w.app.live.state.value.ready},"A must really be ready before its finalization wait")
        val old=remote();val oldEvents=field(w,"eventObserver") as DataChannel.Observer
        w.app.live.end()
        requireSetup(field(old,"closed")==false,"A must be held in actual provider finalization, not already closed")
        val b=open("owner-b");capture(b,2)
        requireSetup(field(old,"closed")==false,"A finalization must remain held until B captures")
        val text=fgsText;val calls=fgsCalls;val starts=(field(fixture,"starts") as AtomicInteger).get()
        val json=JSONObject().put("type","session.closed").toString()
        oldEvents.onMessage(DataChannel.Buffer(ByteBuffer.wrap(json.toByteArray()),false))
        requireSetup(await{field(old,"closed")==true},"old provider finalization must really close old transport")
        assertTrue("A delayed close must retain B microphone FGS",fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE!=0)
        assertEquals("old release must not rewrite successor notification",text,fgsText)
        assertEquals("already released A input must not downgrade B",calls,fgsCalls)
        assertTrue(w.app.live.state.value.inputActive);assertTrue(w.app.phoneMic.occupied)
        assertEquals(starts,(field(fixture,"starts") as AtomicInteger).get())
        assertSame(service,PhoneLiveVoiceService.running)
    }
    @Test fun timedOutSameCallInputIntentCannotStopHealthyOriginalWatchFallback(){
        val a=open("owner-a");val late=inputIntent(a);val token=late.getLongExtra("generation",-1)
        admit(baseIntent(token),1)
        requireSetup(w.await{w.app.live.state.value.inputNotice.contains("not confirmed in time")},"actual input admission timeout must fall back")
        val before=stops.get();val text=fgsText;val calls=fgsCalls
        admit(late,2)
        assertEquals("expired latest startId cannot stop admitted relay owner",before,stops.get())
        assertSame(service,PhoneLiveVoiceService.running);assertTrue(w.app.live.active)
        assertEquals(text,fgsText);assertEquals(calls,fgsCalls)
        assertFalse(w.app.phoneMic.occupied)
    }
    @Test fun oldGenerationLatestIntentCannotRetokenSameServiceOrBreakOwnerDestroy(){
        val a=open("owner-a");val oldIntent=capture(a,1)
        w.app.live.end(confirmed=true)
        requireSetup(await{field(remote(),"closed")==true},"A must settle before normal replacement")
        val b=open("owner-b");val current=capture(b,2);val before=stops.get()
        admit(oldIntent,3)
        assertEquals("late old request cannot stop B on the same service",before,stops.get())
        assertEquals("rejected intent cannot retoken B",current.getLongExtra("generation",-1),field(service,"token"))
        service.onDestroy()
        assertFalse("destroy must still end its real B owner",w.app.live.active)
    }
    @Test fun duplicateCurrentInputIntentIsHarmlessAndDestroyStillEndsItsOwner(){
        val a=open("owner-a");val current=capture(a,1);val before=stops.get();val calls=fgsCalls
        admit(current,2)
        assertEquals("duplicate admitted request is not an unowned refusal",before,stops.get())
        assertEquals(calls,fgsCalls);assertTrue(w.app.live.state.value.inputActive)
        service.onDestroy();assertFalse(w.app.live.active)
    }
    @Test fun ownInputReleaseReallyDropsMicrophoneTypeAndReleasesExactRecorder(){
        val a=open("owner-a");capture(a,1);val before=fgsCalls
        w.app.live.end(confirmed=true)
        assertTrue("current owner release must downgrade before foreground removal",fgsCalls>before)
        assertEquals(0,fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        assertFalse(w.app.phoneMic.occupied);assertFalse(w.app.live.active)
        assertEquals(1,(field(fixture,"releases") as AtomicInteger).get())
    }
    @Test fun unownedInvalidInputIntentStillStopsItsRefusedStart(){
        requireSetup(PhoneLiveVoiceService.running==null,"no admitted service in unowned control")
        admit(baseIntent(-1).putExtra("input_only",true).putExtra("input","expired"),7)
        assertEquals(1,stops.get());assertNull(PhoneLiveVoiceService.running);assertFalse(w.app.live.active)
    }
}
