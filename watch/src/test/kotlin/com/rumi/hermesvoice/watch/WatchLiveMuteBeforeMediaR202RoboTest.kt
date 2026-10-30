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

/** PHONE-R2-02 Watch origin: the real Watch runtime applies the Phone-declared/received mute to the native media it creates later. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@LooperMode(LooperMode.Mode.PAUSED)
class WatchLiveMuteBeforeMediaR202RoboTest {
    private lateinit var app:WatchApp;private lateinit var live:WatchLiveVoiceRuntime
    private val sent=ArrayList<ByteArray>();private val peers=ArrayList<Peer>()
    private var id="";private var seq=0L;private var media="m1";private var pendingAdmission:Long?=null
    private fun idle()=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    private class Peer:WatchLiveMedia {
        var mic:((Boolean)->Unit)?=null;var muted=false;var mutedAtConnect:Boolean?=null;var connected=false
        override suspend fun connect(signal:suspend(String)->String,event:(String)->Unit){
            mutedAtConnect=muted;connected=true;mic?.invoke(!muted)
            signal("v=0\r\nwatch-offer\r\n");event("{\"type\":\"session.started\",\"session\":{\"id\":\"provider\"}}")
        }
        override fun send(json:String)=true
        override fun observeMicrophone(listener:(Boolean)->Unit){mic=listener}
        override fun mute(muted:Boolean){this.muted=muted;mic?.invoke(!muted&&connected)}
        override fun output(enabled:Boolean){}
        override fun silence(){muted=true;mic?.invoke(false)}
        override fun close(){}
    }
    @Before fun setup(){
        app=RuntimeEnvironment.getApplication() as WatchApp
        app.localStore.putString("live_control_id","");app.localStore.putString("live_control_node","")
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        mockkStatic(Wearable::class);val messages=mockk<MessageClient>()
        every{messages.sendMessage(any(),any(),any())}answers{sent+=thirdArg<ByteArray>();Tasks.forResult(1)}
        every{Wearable.getMessageClient(any<android.content.Context>())}returns messages
        app.applySettings(WatchSettings(phoneLiveEnabled=false,watchLiveEnabled=true,revision=100).toJson());idle()
        live=WatchLiveVoiceRuntime(app){"phone"};live.now={1000L}
        live.admission={pendingAdmission=it} // Android admits the foreground microphone service later
        live.routeFactory={object:WatchLiveRoute{override fun open()=true;override fun valid()=true;override fun close(){}}}
        live.mediaFactory={Peer().also{peers+=it}}
    }
    @After fun cleanup(){live.end();idle();app.scope.coroutineContext[Job]?.cancel();unmockkAll()}
    private fun start(){live.toggle();idle();id=sent.map{JSONObject(it.toString(Charsets.UTF_8))}.single{it.optString("op")=="start"}.getString("id")}
    private fun packet(op:String,body:String="")=live.onStatus("phone",WatchLiveWire.packet(id,media,"phone-epoch",++seq,op,body))
    private fun admit(){val token=pendingAdmission;assertNotNull("the actual foreground admission was requested",token);live.admitted(token!!);idle()}

    @Test fun muteReceivedBeforeForegroundAdmissionIsTheNativeCaptureStateOfTheLaterMedia(){
        start();packet("open");idle()
        assertTrue(peers.isEmpty())
        packet("mute","true");idle();assertTrue(live.state.value.muted)
        admit()
        assertEquals(1,peers.size);assertTrue(peers.single().connected)
        assertEquals("a pre-admission mute must reach the media before capture",true,peers.single().mutedAtConnect)
        assertFalse(live.state.value.inputActive)
        packet("mute","false");idle();assertFalse(peers.single().muted);assertTrue(live.state.value.inputActive)
    }
    @Test fun phoneDeclaredMuteInTheOpenPacketIsAppliedAtMediaCreationAndUnmutedOpenStillCaptures(){
        start();packet("open",JSONObject().put("muted",true).toString());idle()
        assertTrue(live.state.value.muted)
        admit()
        assertEquals(true,peers.single().mutedAtConnect);assertFalse(live.state.value.inputActive)
        live.end();idle();sent.clear();seq=0;media="m2"
        start();packet("open",JSONObject().put("muted",false).toString());idle();admit()
        assertEquals("control: an unmuted admission captures",false,peers.last().mutedAtConnect)
        assertTrue(live.state.value.inputActive)
    }
}
