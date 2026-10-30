package com.rumi.hermesvoice.watch

import com.rumi.hermesvoice.core.live.WatchLiveWire
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual default Watch runtime/Channel adapter, held before any AudioRecord; synthetic platform only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class WatchDeviceInputRoboTest {
    private lateinit var w:WatchPhoneChannelDefaultRoboTest
    private lateinit var live:WatchLiveVoiceRuntime
    private var id="";private var seq=0L
    private val hold="exact-input-hold"
    @Before fun setup(){
        w=WatchPhoneChannelDefaultRoboTest();w.setup();live=field("live") as WatchLiveVoiceRuntime
        val app=field("app") as WatchApp
        val audio=app.getSystemService(android.media.AudioManager::class.java);io.mockk.mockkObject(audio)
        val original=io.mockk.mockk<android.media.AudioDeviceInfo>()
        io.mockk.every{original.id}returns 41;io.mockk.every{original.type}returns android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC
        io.mockk.every{original.address}returns "";io.mockk.every{original.isSource}returns true
        io.mockk.every{audio.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)}returns arrayOf(original)
        val record=android.media.AudioRecord.Builder().build()
        io.mockk.every{record.setPreferredDevice(any())}returns true;io.mockk.every{record.routedDevice}returns original
    }
    @After fun cleanup(){w.cleanup()}
    private fun field(n:String)=w.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(w)
    private fun await(f:()->Boolean):Boolean {val end=System.nanoTime()+5_000_000_000;while(System.nanoTime()<end){w.javaClass.getDeclaredMethod("idle").apply{isAccessible=true}.invoke(w);if(f())return true;Thread.sleep(10)};return f()}
    @Suppress("UNCHECKED_CAST") private fun packets()=(field("sent") as java.util.concurrent.ConcurrentLinkedQueue<ByteArray>).mapNotNull{WatchLiveWire.parse(it)}
    private fun packet(op:String,body:String="")=live.onStatus("phone",WatchLiveWire.packet(id,"media","epoch",++seq,op,body))
    private fun open(muted:Boolean=false){
        live.toggle();assertTrue(await{(field("sent") as Collection<*>).isNotEmpty()})
        @Suppress("UNCHECKED_CAST")val sent=field("sent") as java.util.concurrent.ConcurrentLinkedQueue<ByteArray>
        id=sent.map{JSONObject(it.toString(Charsets.UTF_8))}.first{it.optString("op")=="start"}.getString("id")
        packet("open",JSONObject().put("stream","audio-stream").put("relay",true).put("private",true).put("output","private-token").put("muted",muted).put("input_hold",hold).toString())
        assertTrue(await{packets().any{it.op=="offer"}})
    }
    private fun answer(){packet("answer","phone-relay-ready");assertTrue(await{packets().any{it.op=="connected"}})}
    @Test fun exactInitialHoldIsAcknowledgedBeforeAnyLocalMicrophoneOrProviderAnswer(){open();assertTrue("Watch must positively acknowledge no local capture",await{packets().any{it.op=="input_ready"&&it.body==hold}});verifyNoInput()}
    private fun verifyNoInput(){
        assertFalse(live.state.value.inputActive);assertEquals(0,(field("reads") as java.util.concurrent.atomic.AtomicInteger).get())
        val media=WatchLiveVoiceRuntime::class.java.getDeclaredField("media").apply{isAccessible=true}.get(live) as? WatchChannelMedia
        if(media!=null)assertNull("no native capture job may be scheduled while held/muted",WatchChannelMedia::class.java.getDeclaredField("capture").apply{isAccessible=true}.get(media))
    }
    @Test fun connectedHeadsetModeKeepsWatchCaptureClosedDespiteUnmutedUser(){open();answer();packet("mute","false");assertTrue(await{live.state.value.active});verifyNoInput()}
    @Test fun explicitFallbackRestoresOnlyOriginalWatchMic(){open();packet("input_watch",hold);answer();(field("permits") as java.util.concurrent.Semaphore).release();assertTrue(await{live.state.value.inputActive});assertTrue(await{(field("reads") as java.util.concurrent.atomic.AtomicInteger).get()>0})}
    @Test fun wrongFallbackTokenCannotOpenLocalMic(){open();packet("input_watch","wrong");answer();packet("mute","false");verifyNoInput()}
    @Test fun userMuteSurvivesOriginalInputFallback(){open(true);packet("input_watch",hold);answer();verifyNoInput();packet("mute","false");(field("permits") as java.util.concurrent.Semaphore).release();assertTrue(await{live.state.value.inputActive})}
    @Test fun watchLocalSelectedHeadsetRequiresItsActualSourceNotOutputId(){localRoute(false,false)}
    @Test fun watchLocalAcceptedRequestButWrongInputDiscardsPcm(){localRoute(true,false)}
    @Test fun watchLocalOutputOnlyHeadphonesUseOriginalBuiltInInput(){localRoute(false,true)}
    private fun localRoute(wrong:Boolean,outputOnly:Boolean){
        val d=WatchCapturePermissionRoboTest();d.setup()
        try{
            fun f(n:String)=d.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(d)
            val app=f("app") as WatchApp;val record=f("record") as android.media.AudioRecord
            val audio=app.getSystemService(android.media.AudioManager::class.java);io.mockk.mockkObject(audio)
            fun endpoint(id:Int,type:Int,input:Boolean):android.media.AudioDeviceInfo {
                val e=io.mockk.mockk<android.media.AudioDeviceInfo>()
                io.mockk.every{e.id}returns id;io.mockk.every{e.type}returns type;io.mockk.every{e.address}returns "local"
                io.mockk.every{e.isSource}returns input;io.mockk.every{e.isSink}returns !input;return e
            }
            val output=endpoint(201,if(outputOnly)android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES else android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,false)
            val input=endpoint(202,if(outputOnly)android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC else android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,true)
            io.mockk.every{audio.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)}returns arrayOf(output)
            io.mockk.every{audio.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)}returns arrayOf(input)
            io.mockk.every{record.setPreferredDevice(any())}returns true
            io.mockk.every{record.routedDevice}returns if(wrong)endpoint(999,android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC,true)else input
            val media=f("media") as WatchChannelMedia
            kotlinx.coroutines.runBlocking{media.connect({"phone-relay-ready"},{})}
            val failures=f("failures") as java.util.concurrent.atomic.AtomicInteger
            val uplink=f("uplink") as java.io.ByteArrayOutputStream
            if(wrong){assertTrue(await{failures.get()==1});assertEquals(0,uplink.size())}
            else{assertTrue(await{uplink.size()>=28+com.rumi.hermesvoice.core.live.WatchRelayAudio.FRAME_BYTES});io.mockk.verify{record.setPreferredDevice(input)}}
        }finally{d.cleanup()}
    }
    @Test fun stopRejectsLateFallbackAndNeverReopensCapture(){open();live.end();packet("input_watch",hold);packet("answer","phone-relay-ready");verifyNoInput();assertFalse(live.state.value.active)}
}
