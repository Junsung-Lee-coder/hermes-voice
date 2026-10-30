package com.rumi.hermesvoice.watch

import android.Manifest
import android.content.Context
import android.os.Looper
import android.os.VibrationAttributes
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

/**
 * R29: a Watch-origin Live tells the wearer, without screen text, when the Phone confirmed that Watch speech can reach the provider
 * (the hardware recording-start haptic) and when it no longer can (the recording-end haptic). The real Watch hold and Live runtime;
 * the Phone Data Layer and native media are synthetic boundaries. Vibrations are what the app asked the platform for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],shadows=[RecordingVibratorShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class WatchLiveListeningCueR29RoboTest {
    private lateinit var app:WatchApp
    private val sent=ConcurrentLinkedQueue<JSONObject>()
    private var phoneSeq=0L;private var mediaSeq=0L
    private class Peer:WatchLiveMedia{
        override suspend fun connect(signal:suspend(String)->String,event:(String)->Unit){signal("watch-offer");event("{\"type\":\"session.started\",\"session\":{\"id\":\"r29\"}}")}
        override fun send(json:String)=true
        override fun mute(muted:Boolean){};override fun output(enabled:Boolean){};override fun silence(){};override fun close(){}
    }
    private fun idle()=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    @Before fun setup(){
        app=RuntimeEnvironment.getApplication() as WatchApp
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        mockkStatic(Wearable::class)
        val phone=mockk<Node>();every{phone.id}returns "phone-r29";every{phone.isNearby}returns true
        val info=mockk<CapabilityInfo>();every{info.nodes}returns setOf(phone)
        val caps=mockk<CapabilityClient>();every{caps.getCapability(any(),any())}returns Tasks.forResult(info)
        every{Wearable.getCapabilityClient(any<Context>())}returns caps
        val messages=mockk<MessageClient>();every{messages.sendMessage(any(),any(),any())}answers{
            if(secondArg<String>()==WatchLiveVoiceRuntime.COMMAND){runCatching{JSONObject(thirdArg<ByteArray>().toString(Charsets.UTF_8))}.getOrNull()?.let{sent+=it}};Tasks.forResult(1)
        }
        every{Wearable.getMessageClient(any<Context>())}returns messages
        every{Wearable.getDataClient(any<Context>())}returns mockk<DataClient>(relaxed=true)
        every{Wearable.getChannelClient(any<Context>())}returns mockk<ChannelClient>(relaxed=true)
        app.applySettings(WatchSettings(phoneLiveEnabled=false,watchLiveEnabled=true,revision=100).toJson());idle()
        app.live.admission={app.live.admitted(it)}
        app.live.routeFactory={object:WatchLiveRoute{override fun open()=true;override fun valid()=true;override fun close(){}}}
        app.live.mediaFactory={Peer()}
        RecordingVibratorShadow.events.clear()
    }
    @After fun teardown(){app.live.end();idle();app.scope.cancel();unmockkAll()}
    /** The Phone's own v1 control status shape (PhoneLiveWatchControl.envelope + sendState), with or without its listening field. */
    private fun phoneStatus(id:String,active:Boolean,listening:Boolean?,muted:Boolean=false,probe:String="")=app.live.onStatus("phone-r29",JSONObject().put("v",1).put("id",id)
        .put("epoch","phone-boot").put("seq",++phoneSeq).put("probe",probe).put("media","phone-call").put("phone_audio",false).put("watch_audio",true)
        .put("active",active).put("muted",muted).put("end_reason","").apply{if(listening!=null)put("listening",listening)}.put("status","Phone Live").toString().toByteArray())
    private fun phoneMedia(id:String,op:String,body:String="")=app.live.onStatus("phone-r29",WatchLiveWire.packet(id,"phone-call","phone-boot",++mediaSeq,op,body))
    private fun cues()=synchronized(RecordingVibratorShadow.events){RecordingVibratorShadow.events.toList()}.filter{it.second==VibrationAttributes.USAGE_HARDWARE_FEEDBACK}.map{it.first}
    /** A real Watch hold whose Phone admitted media and started the provider. */
    private fun startedCall():String{
        assertTrue("the real Watch hold reaches Watch Live",app.voice.onTalkPressed());idle()
        val start=sent.first{it.optString("op")=="start"};val id=start.getString("id")
        phoneStatus(id,active=true,listening=false,probe=start.getString("probe"));phoneMedia(id,"open",JSONObject().put("relay",true).put("stream","stream-r29").toString());idle()
        phoneMedia(id,"answer","phone-relay-ready");idle()
        phoneMedia(id,"event","{\"type\":\"session.started\",\"session\":{\"id\":\"r29\"}}");idle()
        assertTrue("Watch media is open",app.live.state.value.active)
        return id
    }

    @Test fun R29_W01_thePhoneConfirmedListeningMomentGivesTheRecordingStartHapticAndItsEndTheRecordingEndHaptic(){
        val id=startedCall()
        assertTrue("nothing is cued before the Phone confirms listening",cues().isEmpty())
        phoneStatus(id,active=true,listening=true);idle()
        assertEquals("one recording-start haptic",listOf(50L),cues())
        phoneStatus(id,active=true,listening=true);idle()
        assertEquals("a repeated state is not a new cue",listOf(50L),cues())
        phoneStatus(id,active=true,listening=false,muted=true);idle()
        assertEquals("muted: speech no longer reaches the provider",2,cues().size);assertNotEquals(50L,cues().last())
        phoneStatus(id,active=true,listening=true);idle()
        phoneStatus(id,active=false,listening=false);idle()
        assertEquals("start, end, start, end",4,cues().size);assertEquals(50L,cues()[2])
    }

    @Test fun R29_W02_anOlderPhoneWithoutTheFieldCuesNothingAndAnEndedCallCannotCue(){
        val id=startedCall()
        phoneStatus(id,active=true,listening=null);idle()
        assertTrue("an older Phone's status carries no listening claim",cues().isEmpty())
        app.live.end();idle();val before=cues().size
        phoneStatus(id,active=true,listening=true);idle()
        assertEquals("a late status for an ended control cannot cue",before,cues().size)
    }
}
