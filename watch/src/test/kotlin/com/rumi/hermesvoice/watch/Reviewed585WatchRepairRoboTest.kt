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
class Reviewed585WatchRepairRoboTest {
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
        val node=mockk<Node>();every{node.id}returns "phone";every{node.isNearby}returns true
        val caps=mockk<CapabilityClient>();val info=mockk<CapabilityInfo>();every{info.nodes}returns setOf(node)
        every{caps.getCapability(any(),any())}returns Tasks.forResult(info)
        every{Wearable.getCapabilityClient(any<android.content.Context>())}returns caps
        app.applySettings(WatchSettings(phoneLiveEnabled=false,watchLiveEnabled=true,revision=100).toJson());idle()
        live=app.live;live.now={now};live.admission={live.admitted(it)}
        live.routeFactory={lost->lostRoutes+=lost;object:WatchLiveRoute{override fun open()=true;override fun valid()=true;override fun close(){}}}
        live.mediaFactory={Peer().also{peers+=it}}
    }
    @After fun cleanup(){live.end();idle();app.scope.coroutineContext[Job]?.cancel();unmockkAll()}
    private fun start(){live.toggle();idle();val j=sent.map{JSONObject(it.toString(Charsets.UTF_8))}.single{it.optString("op")=="start"};id=j.getString("id");open("initial-"+java.util.UUID.randomUUID().toString())}
    private fun open(next:String){media=next;seq=0;packet("open");idle()}
    private fun packet(op:String,body:String="",source:String="phone")=live.onStatus(source,WatchLiveWire.packet(id,media,"phone-epoch",++seq,op,body))
    private fun ready(){start();assertEquals("v=0\r\nwatch-offer\r\n",sent.mapNotNull{WatchLiveWire.parse(it)}.single{it.op=="offer"}.body);packet("answer","v=0\r\nanswer\r\n");idle();assertTrue(live.state.value.inputActive);assertTrue(live.state.value.active)}
    private fun idleOrigin(){now+=10000;live.checkLifetime();idle();assertFalse(live.state.value.active)}
    @Test fun actualWatchSessionSelectionRevokesTheIdleOriginLease(){
        ready();idleOrigin();app.selectSession("conversation-b");idle()
        open("m2");packet("answer","v=0\r\nanswer\r\n");idle()
        assertEquals("Watch navigation, not Phone selection, revokes capture",1,peers.size)
        assertFalse(live.state.value.inputActive)
    }
    @Test fun actualWatchReaderSurfaceNavigationRevokesAdmissionStillWaitingForFgs(){
        // A real reader navigation: a selected conversation's history back to the browser (a swipe that changes nothing does not revoke:
        // parent HOLD W1, WatchLiveNavigationRoboTest).
        app.selectSession("conversation-b");idle()
        ready();idleOrigin();var held:Long?=null;live.admission={held=it};open("m2");assertNotNull(held)
        app.toggleReaderSurface();idle();live.admitted(held!!);idle()
        assertEquals(1,peers.size);assertFalse(live.state.value.inputActive)
    }
    @Test fun oldIdleStatusCannotCloseTheNewMediaButCurrentEndStillCan(){
        ready();val first=media;idleOrigin();open("m2");packet("answer","v=0\r\nanswer\r\n");idle()
        fun status(m:String,s:Long,reason:String)=JSONObject().put("v",1).put("id",id).put("epoch","phone-epoch").put("seq",s)
            .put("media",m).put("active",false).put("muted",false).put("watch_audio",true).put("phone_audio",false).put("end_reason",reason).toString().toByteArray()
        live.onStatus("phone",status(first,10,"idle"));idle()
        assertTrue("old m1 idle must not terminate m2",live.state.value.active);assertFalse(peers.last().closed)
        live.onStatus("phone",status("m2",11,"explicit"));idle();assertTrue(peers.last().closed);assertFalse(live.state.value.active)
    }
    @Test fun privateOutputPriorityDoesNotRevokeWatchInputOrSubmission(){
        ready();app.applySettings(WatchSettings(watchLiveEnabled=true,privateAudio=true,revision=101).toJson());live.settingsChanged();idle()
        assertTrue("a Phone headset changes output, not Watch microphone authority",live.state.value.active)
        assertFalse(peers.single().closed);assertTrue(live.state.value.inputActive)
        assertFalse("Watch loudspeaker must remain silent",peers.single().output)
        packet("send","{\"type\":\"session.thinking.append\"}");idle();assertEquals(1,peers.single().commands.size)
    }
}
