package com.rumi.hermesvoice.watch

import android.Manifest
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.VoiceOrigin
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

/** Real armed shared wake -> real handoff -> Watch Live/automatic idle -> presence rearm.
 * Data Layer/remote Phone and media are synthetic boundaries, not physical/provider proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@LooperMode(LooperMode.Mode.PAUSED)
class WatchLiveWakeLifecycle606RoboTest {
    private class Setup(message:String):IllegalStateException(message)
    private lateinit var app:WatchApp
    private lateinit var coordinator:WakeAdmission
    private var service:org.robolectric.android.controller.ServiceController<WatchVoiceService>?=null
    private val sent=ConcurrentLinkedQueue<Pair<String,ByteArray>>()
    private val peers=ArrayList<Peer>()
    private var id="";private var media="";private var seq=0L
    private var uncertain=false
    private class Peer:WatchLiveMedia{
        var mic:((Boolean)->Unit)?=null;var closed=false
        override suspend fun connect(signal:suspend(String)->String,event:(String)->Unit){
            signal("watch-offer");mic?.invoke(true);event("{\"type\":\"session.started\",\"session\":{\"id\":\"provider606\"}}")
        }
        override fun send(json:String)=true
        override fun observeActivity(listener:(Boolean)->Unit){}
        override fun observeMicrophone(listener:(Boolean)->Unit){mic=listener}
        override fun mute(muted:Boolean){};override fun output(enabled:Boolean){};override fun silence(){mic?.invoke(false)};override fun close(){closed=true}
    }
    private fun idle(ms:Long=50)=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(done:()->Boolean):Boolean{val end=System.nanoTime()+8_000_000_000L;while(!done()&&System.nanoTime()<end){idle(20);Thread.sleep(10)};return done()}
    private fun starts()=sent.filter{it.first==WatchLiveVoiceRuntime.COMMAND}.mapNotNull{runCatching{JSONObject(String(it.second))}.getOrNull()}.filter{it.optInt("v")==1&&it.optString("op")=="start"}
    @Before fun setup(){
        app=RuntimeEnvironment.getApplication() as WatchApp
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        mockkStatic(Wearable::class)
        val node=mockk<Node>();every{node.id}returns "phone606";every{node.isNearby}returns true
        val caps=mockk<CapabilityClient>();val info=mockk<CapabilityInfo>();every{info.nodes}returns setOf(node)
        every{caps.getCapability(any(),any())}returns Tasks.forResult(info);every{Wearable.getCapabilityClient(any<Context>())}returns caps
        val messages=mockk<MessageClient>()
        every{messages.sendMessage(any(),any(),any())}answers{
            val path=secondArg<String>();val bytes=thirdArg<ByteArray>().copyOf();sent+=path to bytes
            if(path==WatchLinkPaths.WAKE_CLAIM){
                val verdict=WakeClaimService.handle(coordinator,"watch606",bytes)
                if(verdict!=null)Handler(Looper.getMainLooper()).post{app.onWakeVerdict("phone606",verdict.encode())}
            }
            if(uncertain&&path==WatchLiveVoiceRuntime.COMMAND&&JSONObject(String(bytes)).optString("op")=="start")Tasks.forException<Int>(java.io.IOException("uncertain Data Layer send")) else Tasks.forResult(1)
        }
        every{Wearable.getMessageClient(any<Context>())}returns messages
        every{Wearable.getDataClient(any<Context>())}returns mockk<DataClient>(relaxed=true)
        every{Wearable.getChannelClient(any<Context>())}returns mockk<ChannelClient>(relaxed=true)
        coordinator=WakeAdmission(SystemClock::elapsedRealtime,{app.settings.value})
        app.applySettings(WatchSettings(wakeLocation=WakeLocation.BOTH,wakePatterns="루미",watchLiveEnabled=true,
            watchBackgroundWakeEnabled=true,watchBackgroundWakeScreenOffEnabled=true,liveIdleSeconds=2,revision=606).toJson())
        runBlocking{app.refreshPhoneReachable()};app.voice.wake.qaFixtureRecognizer=true
        app.live.now={SystemClock.elapsedRealtime()};app.live.admission={app.live.admitted(it)}
        app.live.routeFactory={object:WatchLiveRoute{override fun open()=true;override fun valid()=true;override fun close(){}}}
        app.live.mediaFactory={Peer().also{peers+=it}}
        app.voice.onUserOpened();val visit=app.voice.onActivityResumed();app.voice.onSettingsPulled(visit);app.voice.ensureBackground();idle()
        val intent=shadowOf(app).nextStartedService?:throw Setup("Watch standby service missing")
        service=Robolectric.buildService(WatchVoiceService::class.java,intent).create().startCommand(0,1);idle()
        app.voice.onActivityPaused();idle()
        if(!waitFor{app.voice.wake.wake.listening&&app.voice.presence.armed})throw Setup("actual Watch standby not armed")
    }
    @After fun cleanup(){app.live.end();runCatching{app.voice.stopBackground()};runCatching{service?.destroy()};app.scope.cancel();unmockkAll()}
    private fun phrase(){
        val before=starts().size;app.voice.wake.qaHeard("루미",true)
        if(!waitFor{starts().size>before})throw Setup("phrase-only actual handoff did not reach Live control")
        val start=starts().last();id=start.getString("id")
        if(!start.optBoolean("wake")||!start.has("wake_claim_id"))throw Setup("actual claim not transferred")
    }
    private fun packet(op:String,body:String="")=app.live.onStatus("phone606",WatchLiveWire.packet(id,media,"boot606",++seq,op,body))
    private fun ready(){
        phrase();val start=starts().last();val claim=start.getString("wake_claim_id")
        if(coordinator.admitTurn(true,VoiceOrigin.WATCH,"watch606",claim)!=null)throw Setup("Phone fixture refused actual claim")
        app.applyWakeEpoch(WakeEpochItem(coordinator.epoch,claim).toJson())
        media="media-"+starts().size;seq=0;packet("open");idle();packet("answer","answer")
        if(!waitFor{app.live.state.value.inputActive&&!app.live.state.value.pending})throw Setup("Watch native media not connected")
    }
    private fun idleEnd(){idle(2200);app.live.checkLifetime();idle();if(app.live.state.value.active)throw Setup("automatic idle did not close media")}
    @Test fun automaticIdleRearmsAndNextDeliberateWakeHasNewGenerationAndEpoch(){
        val gen=app.voice.wake.wake.generation;ready();assertEquals(1,peers.size);assertFalse(app.voice.capturing)
        assertFalse(app.voice.wake.wake.listening);idleEnd()
        assertTrue("normal ended Watch Live must settle matching shared capture before eligible presence rearm",waitFor{app.voice.wake.wake.listening})
        assertTrue(app.voice.wake.wake.generation>gen);ready();assertEquals(2,peers.size);assertEquals(2L,coordinator.epoch)
        assertNotEquals(ClaimVerdict.GRANTED,coordinator.claim(WakeClaim("late-phone",VoiceOrigin.PHONE,"",app.settings.value.revision,1,0)))
    }
    @Test fun pendingAndActiveLiveRemainBusy(){
        phrase();idle(300);assertTrue(app.live.state.value.pending);assertFalse(app.voice.wake.wake.listening)
        val count=starts().size;app.voice.wake.qaHeard("루미",true);idle(300);assertEquals(count,starts().size)
    }
    @Test fun uncertainStartNeverReturnsClaimOnEnd(){
        uncertain=true;phrase();val claim=starts().last().getString("wake_claim_id");idle();app.live.end();idle()
        assertFalse("uncertain transmitted claim must not be returned by capture settlement",sent.any{it.first==WatchLinkPaths.WAKE_CLAIM&&WakeClaimMessage.decode(it.second)?.let{m->m.claimId==claim&&m.op==WakeClaimMessage.Op.RELEASE}==true})
    }
    @Test fun permissionRevocationAndStandbyOffPreventEndFromRearming(){
        ready();shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO);app.voice.onPermissionResult();app.live.end();idle(2200)
        assertFalse(app.voice.wake.wake.listening);assertFalse(app.voice.presence.armed)
    }
    @Test fun stopAndOldMediaCannotResurrectCapture(){
        ready();app.voice.stopBackground();app.live.end();packet("open");idle(2200)
        assertFalse(app.voice.wake.wake.listening);assertFalse(app.live.state.value.inputActive);assertTrue(peers.single().closed)
    }
    @Test fun explicitEndSettlesOnlyOldHandoffAndStaleEndCannotClearNewLive(){
        ready();val oldId=id;val oldMedia=media;app.live.end()
        assertTrue("ended shared capture is no longer BUSY",waitFor{app.voice.wake.wake.listening})
        ready();app.live.onStatus("phone606",JSONObject().put("v",1).put("id",oldId).put("media",oldMedia).put("epoch","boot606").put("seq",100)
            .put("active",false).put("muted",false).put("phone_audio",false).put("watch_audio",true).toString().toByteArray());idle(100)
        assertTrue(app.live.state.value.active);assertFalse(app.voice.wake.wake.listening);assertFalse(peers.last().closed)
    }
}
