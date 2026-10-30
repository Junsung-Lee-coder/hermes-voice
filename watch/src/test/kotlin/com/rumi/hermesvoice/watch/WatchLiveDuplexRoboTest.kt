package com.rumi.hermesvoice.watch

import android.Manifest
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.live.WatchLiveWire
import com.rumi.hermesvoice.core.settings.WatchSettings
import io.mockk.*
import kotlinx.coroutines.Job
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** Real Watch Talk/control/runtime callers, synthetic native media boundary: no device or provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@LooperMode(LooperMode.Mode.PAUSED)
class WatchLiveDuplexRoboTest {
    private lateinit var app:WatchApp;private lateinit var live:WatchLiveVoiceRuntime
    private val sent=ArrayList<ByteArray>();private val peers=ArrayList<Peer>();private val lostRoutes=ArrayList<()->Unit>()
    private var now=1000L;private var id="";private var seq=0L;private var media="m1"
    private fun idle()=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    private class Peer:WatchLiveMedia {
        var mic:((Boolean)->Unit)?=null;var activity:((Boolean)->Unit)?=null;var event:((String)->Unit)?=null
        var closed=false;var silenced=false;var muted=false;var output=false;val commands=ArrayList<String>()
        override suspend fun connect(signal:suspend(String)->String,event:(String)->Unit){
            this.event=event;mic?.invoke(true)
            assertEquals("v=0\r\nanswer\r\n",signal("v=0\r\nwatch-offer\r\n"))
            event("{\"type\":\"session.started\",\"session\":{\"id\":\"provider\"}}")
        }
        override fun send(json:String):Boolean{commands+=json;return true}
        override fun observeActivity(listener:(Boolean)->Unit){activity=listener}
        override fun observeMicrophone(listener:(Boolean)->Unit){mic=listener}
        override fun mute(muted:Boolean){this.muted=muted;mic?.invoke(!muted)}
        override fun output(enabled:Boolean){output=enabled}
        override fun privateOutput(listener:((com.rumi.hermesvoice.core.live.LivePcmFrame)->Unit)?){if(listener!=null)output=false}
        override fun silence(){silenced=true;mic?.invoke(false)}
        override fun close(){closed=true}
    }
    @Before fun setup(){
        app=RuntimeEnvironment.getApplication() as WatchApp
        app.localStore.putString("live_control_id","");app.localStore.putString("live_control_node","")
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        mockkStatic(Wearable::class);val messages=mockk<MessageClient>()
        every{messages.sendMessage(any(),any(),any())}answers{sent+=thirdArg<ByteArray>();Tasks.forResult(1)}
        every{Wearable.getMessageClient(any<android.content.Context>())}returns messages
        app.applySettings(WatchSettings(phoneLiveEnabled=false,watchLiveEnabled=true,revision=100).toJson());idle()
        live=WatchLiveVoiceRuntime(app){"phone"};live.now={now};live.admission={live.admitted(it)}
        live.routeFactory={lost->lostRoutes+=lost;object:WatchLiveRoute{override fun open()=true;override fun valid()=true;override fun close(){}}}
        live.mediaFactory={Peer().also{peers+=it}}
    }
    @After fun cleanup(){live.end();idle();app.scope.coroutineContext[Job]?.cancel();unmockkAll()}
    private fun start(){live.toggle();idle();val j=sent.map{JSONObject(it.toString(Charsets.UTF_8))}.single{it.optString("op")=="start"};id=j.getString("id");open("initial-"+java.util.UUID.randomUUID().toString())}
    private fun open(next:String){media=next;seq=0;packet("open");idle()}
    private fun packet(op:String,body:String="",source:String="phone")=live.onStatus(source,WatchLiveWire.packet(id,media,"phone-epoch",++seq,op,body))
    private fun ready(){start();assertEquals("v=0\r\nwatch-offer\r\n",sent.mapNotNull{WatchLiveWire.parse(it)}.single{it.op=="offer"}.body);packet("answer","v=0\r\nanswer\r\n");idle();assertTrue(live.state.value.inputActive);assertTrue(live.state.value.active)}
    @Test fun watchTalkNegotiatesItsOwnMediaAndDuplexCommandsUseTheWatchPeer(){
        ready();packet("output","true");packet("send","{\"type\":\"session.commentary.append\"}");packet("mute","true");idle()
        assertTrue(peers.single().output);assertTrue(peers.single().muted);assertFalse(live.state.value.inputActive)
        packet("mute","false");idle();assertTrue(live.state.value.inputActive);assertEquals(1,peers.single().commands.size)
        assertFalse(app.voice.capturing);assertFalse(sent.any{it.toString(Charsets.UTF_8).contains("api_key")})
    }
    @Test fun idleClosesNativeMediaAndLateAnswerReopensTheSameOriginMicrophone(){
        ready();now+=10000;live.checkLifetime();idle();assertTrue(peers.single().closed);assertFalse(live.state.value.active)
        assertTrue(sent.mapNotNull{WatchLiveWire.parse(it)}.any{it.op=="suspend"})
        open("m2");packet("answer","v=0\r\nanswer\r\n");idle()
        assertEquals(2,peers.size);assertTrue(live.state.value.inputActive)
        lostRoutes.first().invoke();idle();assertFalse(peers.last().silenced);assertTrue(live.state.value.inputActive)
        packet("open");idle();assertEquals(2,peers.size)
    }
    @Test fun newRecognizedTalkAfterIdleCarriesTheWholeRequestIntoFreshWatchAdmission(){
        ready();now+=10000;live.checkLifetime();idle()
        val oldId=id;assertTrue(live.start("새 요청 전체"));idle()
        val request=sent.map{JSONObject(it.toString(Charsets.UTF_8))}.last{it.optString("op")=="start"}
        id=request.getString("id");assertNotEquals(oldId,id);assertEquals("새 요청 전체",request.getString("recognized"))
        open("fresh-user-call");packet("answer","v=0\r\nanswer\r\n");idle()
        assertEquals(2,peers.size);assertTrue(live.state.value.inputActive)
    }
    @Test fun explicitEndAfterIdleRevokesLateAnswerAndOldNativeCallbacks(){
        ready();val old=peers.single();now+=10000;live.checkLifetime();idle();live.end();idle()
        open("m2");old.mic?.invoke(true);old.event?.invoke("{\"type\":\"session.started\"}");idle()
        assertEquals(1,peers.size);assertFalse(live.state.value.active);assertFalse(live.state.value.inputActive)
    }
    @Test fun deniedPermissionAndForegroundAdmissionRefusalNeverCreateMedia(){
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO);start();idle();assertTrue(peers.isEmpty());assertFalse(live.state.value.active)
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,app.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        live.end();sent.clear();shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,app.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        var refusedAdmissions=0
        live.admission={refusedAdmissions++;throw SecurityException("synthetic background refusal")};start();idle()
        assertEquals("a fresh media identity must reach the actual admission boundary",1,refusedAdmissions)
        assertTrue(peers.isEmpty());assertTrue(live.state.value.status.contains("Android refused"))
    }
    @Test fun privatePhoneHeadsetAndModeOffStopWatchAudioWithoutPhoneFallback(){
        ready();packet("output","true");assertTrue(peers.single().output)
        app.applySettings(WatchSettings(watchLiveEnabled=true,privateAudio=true,revision=101).toJson());live.settingsChanged();idle()
        assertFalse("only Watch output is suppressed",peers.single().output)
        assertFalse(peers.single().closed);assertTrue(live.state.value.active);assertTrue(live.state.value.inputActive)
        app.applySettings(WatchSettings(watchLiveEnabled=false,privateAudio=true,revision=102).toJson());live.settingsChanged();idle()
        assertTrue(peers.single().silenced);assertTrue(peers.single().closed);assertFalse(live.state.value.active)
        open("m2");idle();assertEquals(1,peers.size)
    }
    @Test fun wrongNodeOldMediaAndMissingSequenceCannotSteerThePeer(){
        ready();val oldSeq=seq;packet("output","true","other-node");seq=oldSeq
        assertFalse(peers.single().output)
        live.onStatus("phone",WatchLiveWire.packet(id,"wrong-media","phone-epoch",100,"output","true"));assertFalse(peers.single().output)
        seq+=2;packet("output","true");idle();assertTrue(peers.single().closed);assertFalse(live.state.value.active)
    }
    @Test fun configuredOneSecondGraceCountsAudioNotKeepalives(){
        app.applySettings(WatchSettings(watchLiveEnabled=true,liveIdleSeconds=1,revision=101).toJson());live.settingsChanged();ready()
        repeat(4){now+=900;peers.single().activity!!(true);idle();live.checkLifetime();assertTrue(live.state.value.active)}
        now+=1000;packet("ping");live.checkLifetime();idle();assertFalse(live.state.value.active)
    }
    @Test fun processLossAndPhoneLeaseExpiryNeverLeaveWatchCaptureRunning(){
        ready();now+=5000;shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));assertTrue(peers.single().closed);assertFalse(live.state.value.active)
        val cold=WatchLiveVoiceRuntime(app){"phone"};cold.mediaFactory={fail("cold process cannot open native media");Peer()}
        cold.onStatus("phone",WatchLiveWire.packet(id,"new-media","phone-epoch",1,"open"));idle();assertFalse(cold.state.value.active)
    }

    @Test fun privatePhoneOutputActivityKeepsWatchDuplexAliveButPingsDoNot(){
        app.applySettings(WatchSettings(watchLiveEnabled=true,liveIdleSeconds=1,revision=101).toJson());live.settingsChanged();ready()
        packet("private","private-output")
        repeat(4){now+=900;packet("dialogue","output");live.checkLifetime();assertTrue("genuine Phone provider speech must protect the originating Watch microphone even with no Watch speaker PCM",live.state.value.active)}
        now+=1000;packet("ping");live.checkLifetime();idle();assertFalse("control keepalive never extends the speech grace",live.state.value.active)
    }
}
