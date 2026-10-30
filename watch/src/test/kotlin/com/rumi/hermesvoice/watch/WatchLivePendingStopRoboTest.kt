package com.rumi.hermesvoice.watch

import android.Manifest
import android.content.Context
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.live.WatchLiveWire
import com.rumi.hermesvoice.core.settings.WatchSettings
import io.mockk.*
import kotlinx.coroutines.cancel
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
import java.util.concurrent.ConcurrentLinkedQueue

/** Real Watch hold (WatchVoiceRuntime.onTalkPressed) and real Live runtime status/media callbacks.
 * The Phone Data Layer and native media are synthetic boundaries: no device, provider or physical touch proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@LooperMode(LooperMode.Mode.PAUSED)
class WatchLivePendingStopRoboTest {
    private lateinit var app:WatchApp
    private val sent=ConcurrentLinkedQueue<JSONObject>();private val peers=ArrayList<Peer>()
    private var now=1000L;private var phoneSeq=0L;private var mediaSeq=0L
    private class Peer:WatchLiveMedia{
        var closed=false
        override suspend fun connect(signal:suspend(String)->String,event:(String)->Unit){signal("watch-offer");event("{\"type\":\"session.started\",\"session\":{\"id\":\"pending-stop\"}}")}
        override fun send(json:String)=true
        override fun mute(muted:Boolean){};override fun output(enabled:Boolean){};override fun silence(){};override fun close(){closed=true}
    }
    private fun idle()=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    @Before fun setup(){
        app=RuntimeEnvironment.getApplication() as WatchApp
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        mockkStatic(Wearable::class)
        val phone=mockk<Node>();every{phone.id}returns "phone-stop";every{phone.isNearby}returns true
        val info=mockk<CapabilityInfo>();every{info.nodes}returns setOf(phone)
        val caps=mockk<CapabilityClient>();every{caps.getCapability(any(),any())}returns Tasks.forResult(info)
        every{Wearable.getCapabilityClient(any<Context>())}returns caps
        val messages=mockk<MessageClient>();every{messages.sendMessage(any(),any(),any())}answers{
            if(secondArg<String>()==WatchLiveVoiceRuntime.COMMAND){sent+=JSONObject(thirdArg<ByteArray>().toString(Charsets.UTF_8))};Tasks.forResult(1)
        }
        every{Wearable.getMessageClient(any<Context>())}returns messages
        every{Wearable.getDataClient(any<Context>())}returns mockk<DataClient>(relaxed=true)
        every{Wearable.getChannelClient(any<Context>())}returns mockk<ChannelClient>(relaxed=true)
        app.applySettings(WatchSettings(phoneLiveEnabled=false,watchLiveEnabled=true,revision=100).toJson());idle()
        app.live.now={now};app.live.admission={app.live.admitted(it)}
        app.live.routeFactory={object:WatchLiveRoute{override fun open()=true;override fun valid()=true;override fun close(){}}}
        app.live.mediaFactory={Peer().also{peers+=it}}
    }
    @After fun teardown(){app.live.end();idle();app.scope.cancel();unmockkAll()}
    private fun hold(){assertTrue("the real Watch hold reaches Watch Live",app.voice.onTalkPressed());idle()}
    private fun ops(op:String)=sent.filter{it.optInt("v")==1&&it.optString("op")==op}
    /** The Phone's own v1 control status shape (PhoneLiveWatchControl.envelope + sendState). */
    private fun phoneStatus(id:String,active:Boolean,probe:String="",endReason:String?=null)=app.live.onStatus("phone-stop",JSONObject().put("v",1).put("id",id)
        .put("epoch","phone-boot").put("seq",++phoneSeq).put("probe",probe).put("media","phone-call").put("phone_audio",false).put("watch_audio",true)
        .put("active",active).put("muted",false).apply{if(endReason!=null)put("end_reason",endReason)}.put("status","Phone Live").toString().toByteArray())
    private fun phoneMedia(id:String,media:String,op:String,body:String="")=app.live.onStatus("phone-stop",WatchLiveWire.packet(id,media,"phone-boot",++mediaSeq,op,body))

    @Test fun secondHoldWhilePhonePreparesMediaStopsThatControlOnceWithoutReplacingIt(){
        hold();val start=ops("start").single();val id=start.getString("id")
        phoneStatus(id,active=true,probe=start.getString("probe"));idle()
        assertFalse("no Watch media exists yet",app.live.state.value.active)
        hold()
        assertEquals("a hold during Phone preparation is the Stop of that control, not a replacement",1,ops("start").size)
        assertEquals(listOf(id),ops("end").map{it.getString("id")})
        assertFalse(app.live.state.value.active);assertFalse(app.live.state.value.pending)
        // Phone work already in flight for the stopped control can neither revive it nor open Watch media.
        phoneStatus(id,active=true);mediaSeq=0;phoneMedia(id,"late-media","open");idle()
        assertTrue(peers.isEmpty());assertFalse(app.live.state.value.active);assertFalse(app.live.state.value.pending)
        hold()
        assertEquals("the next deliberate hold starts exactly one fresh control",2,ops("start").size)
        assertNotEquals(id,ops("start").last().getString("id"));assertEquals(1,ops("end").size);assertFalse(app.voice.capturing)
    }

    @Test fun idleSuspendedControlStillRestartsOnceOnTheNextHoldDespiteADelayedActivePhoneStatus(){
        hold();val start=ops("start").single();val id=start.getString("id")
        phoneStatus(id,active=true,probe=start.getString("probe"))
        mediaSeq=0;phoneMedia(id,"media-1","open");idle();phoneMedia(id,"media-1","answer","answer");idle()
        assertTrue("positive control: real Watch media connected",app.live.state.value.active&&!app.live.state.value.pending)
        now+=10_000;app.live.checkLifetime();idle()
        assertTrue(peers.single().closed);assertFalse(app.live.state.value.active)
        // A Phone state sent before it processed the Watch suspend arrives after it: still idle, not new pending work.
        phoneStatus(id,active=true);idle()
        hold()
        val starts=ops("start")
        assertEquals("one deliberate hold after idle starts exactly one new control",2,starts.size)
        assertNotEquals(id,starts.last().getString("id"));assertEquals(listOf(id),ops("end").map{it.getString("id")})
        phoneStatus(id,active=false,endReason="idle");idle()
        assertTrue("the old control's idle end cannot clear the new control",app.live.state.value.pending)
        assertEquals(1,peers.size);assertFalse(app.voice.capturing)
    }
}
