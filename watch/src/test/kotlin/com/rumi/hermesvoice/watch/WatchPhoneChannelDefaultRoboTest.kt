package com.rumi.hermesvoice.watch

import android.Manifest
import android.media.AudioRecord
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.AudioTrack
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
import java.io.*
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Real default runtime, AudioRecord/AudioTrack and ChannelClient boundaries are deterministic platform doubles. No Watch IP. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@LooperMode(LooperMode.Mode.PAUSED)
class WatchPhoneChannelDefaultRoboTest {
    private lateinit var app:WatchApp;private lateinit var live:WatchLiveVoiceRuntime
    private val sent=ConcurrentLinkedQueue<ByteArray>();private val opens=AtomicInteger();private val reads=AtomicInteger()
    private val microphone=ByteArray(960){(it%119).toByte()};private val uplink=ByteArrayOutputStream()
    private val downlink=PipedInputStream(8192);private val phone=PipedOutputStream(downlink)
    private val played=ConcurrentLinkedQueue<ByteArray>();private val permits=Semaphore(0)
    @Volatile private var stopped=false;private var id="";private var sequence=0L
    private fun idle()=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
    private fun await(condition:()->Boolean):Boolean {val end=System.nanoTime()+5_000_000_000;while(System.nanoTime()<end){idle();if(condition())return true;Thread.sleep(10)};return condition()}
    @Before fun setup(){
        app=RuntimeEnvironment.getApplication() as WatchApp;app.localStore.putString("live_control_id","");app.localStore.putString("live_control_node","")
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.applySettings(WatchSettings(phoneLiveEnabled=false,watchLiveEnabled=true,revision=100).toJson());idle()
        mockkStatic(Wearable::class);val messages=mockk<MessageClient>();every{messages.sendMessage(any(),any(),any())}answers{sent+=thirdArg<ByteArray>();Tasks.forResult(1)}
        every{Wearable.getMessageClient(any<android.content.Context>())}returns messages
        var openedPath="";val channels=mockk<ChannelClient>(relaxed=true);val c=mockk<ChannelClient.Channel>();every{c.nodeId}returns "phone";every{c.path}answers{openedPath}
        every{channels.openChannel(any(),any())}answers{assertEquals("phone",firstArg<String>());openedPath=secondArg();assertTrue(openedPath.startsWith("/hv/v3/live/audio/"));opens.incrementAndGet();Tasks.forResult(c)}
        every{channels.getInputStream(any())}returns Tasks.forResult(downlink as InputStream);every{channels.getOutputStream(any())}returns Tasks.forResult(uplink as OutputStream);every{channels.close(any())}returns Tasks.forResult(null)
        every{Wearable.getChannelClient(any<android.content.Context>())}returns channels
        Class.forName("android.media.AudioRecord");Class.forName("android.media.AudioTrack")
        mockkStatic(AudioRecord::class);every{AudioRecord.getMinBufferSize(any(),any(),any())}returns 1920
        mockkConstructor(AudioRecord.Builder::class);val record=mockk<AudioRecord>(relaxed=true)
        every{anyConstructed<AudioRecord.Builder>().setAudioSource(any())}answers{self as AudioRecord.Builder};every{anyConstructed<AudioRecord.Builder>().setAudioFormat(any())}answers{self as AudioRecord.Builder};every{anyConstructed<AudioRecord.Builder>().setBufferSizeInBytes(any())}answers{self as AudioRecord.Builder};every{anyConstructed<AudioRecord.Builder>().build()}returns record
        every{record.state}returns AudioRecord.STATE_INITIALIZED;every{record.recordingState}returns AudioRecord.RECORDSTATE_RECORDING;every{record.audioSessionId}returns 17
        // Explicit platform edge for original-Watch fallback. ShadowAudioManager otherwise has no
        // exposed input; a preferred-device request is not itself actual recording-route proof.
        val audio=app.getSystemService(AudioManager::class.java);mockkObject(audio)
        val original=mockk<AudioDeviceInfo>(relaxed=true);every{original.id}returns 7001;every{original.type}returns AudioDeviceInfo.TYPE_BUILTIN_MIC
        every{original.address}returns "synthetic-watch-input";every{original.isSource}returns true;every{original.isSink}returns false
        every{audio.getDevices(any())}answers{if(firstArg<Int>()==AudioManager.GET_DEVICES_INPUTS)arrayOf(original)else emptyArray()}
        every{record.setPreferredDevice(any())}answers{firstArg<AudioDeviceInfo?>()?.id==original.id};every{record.routedDevice}returns original
        every{record.read(any<ByteArray>(),any(),any(),any())}answers{permits.tryAcquire(2,java.util.concurrent.TimeUnit.SECONDS);if(stopped)-3 else{microphone.copyInto(firstArg<ByteArray>(),secondArg<Int>(),0,thirdArg<Int>());reads.incrementAndGet();thirdArg<Int>()}}
        every{record.stop()}answers{stopped=true;permits.release()}
        mockkStatic(AudioTrack::class);every{AudioTrack.getMinBufferSize(any(),any(),any())}returns 1920
        mockkConstructor(AudioTrack.Builder::class);val player=mockk<AudioTrack>(relaxed=true)
        every{anyConstructed<AudioTrack.Builder>().setAudioAttributes(any())}answers{self as AudioTrack.Builder};every{anyConstructed<AudioTrack.Builder>().setAudioFormat(any())}answers{self as AudioTrack.Builder};every{anyConstructed<AudioTrack.Builder>().setTransferMode(any())}answers{self as AudioTrack.Builder};every{anyConstructed<AudioTrack.Builder>().setBufferSizeInBytes(any())}answers{self as AudioTrack.Builder};every{anyConstructed<AudioTrack.Builder>().build()}returns player
        every{player.state}returns AudioTrack.STATE_INITIALIZED
        every{player.write(any<ByteArray>(),any(),any(),any())}answers{played+=firstArg<ByteArray>().copyOf();thirdArg<Int>()}
        live=WatchLiveVoiceRuntime(app){"phone"};live.admission={live.admitted(it)}
        live.routeFactory={object:WatchLiveRoute{override fun open()=true;override fun valid()=true;override fun close(){}}}
        // DO NOT replace mediaFactory: this test proves the production DEFAULT, not a helper.
    }
    @After fun cleanup(){stopped=true;permits.release(4);if(::live.isInitialized)live.end();phone.close();downlink.close();idle();app.scope.coroutineContext[Job]?.cancel();unmockkAll()}
    private fun packet(op:String,body:String="")=live.onStatus("phone",WatchLiveWire.packet(id,"media","epoch",++sequence,op,body))
    private fun start(){
        live.toggle();idle();id=sent.map{JSONObject(it.toString(Charsets.UTF_8))}.first{it.optString("op")=="start"}.getString("id")
        packet("open",JSONObject().put("stream","audio-stream").put("relay",true).put("muted",false).put("private",false).toString())
        assertTrue("default Watch media must open ChannelClient even without Watch internet",await{opens.get()==1})
        assertTrue(await{sent.mapNotNull{WatchLiveWire.parse(it)}.any{it.op=="offer"}});packet("answer","phone-relay-ready");packet("event","{\"type\":\"session.started\",\"session\":{\"id\":\"fixture\"}}")
        assertTrue(await{live.state.value.active&&!live.state.value.pending})
    }
    @Test fun noWatchInternetDefaultCapturesRealWatchPcmToPhoneAndReturnedFramesReachWatchSpeaker(){
        start();permits.release();assertTrue(await{uplink.size()>=28+640&&reads.get()>0})
        val input=DataInputStream(ByteArrayInputStream(uplink.toByteArray()));assertEquals(0x48565233,input.readInt());assertEquals(1,input.readInt());assertEquals(1L,input.readLong());assertEquals(16000,input.readInt());assertEquals(1,input.readInt());assertEquals(640,input.readInt());assertArrayEquals(microphone.copyOf(640),ByteArray(640).also{input.readFully(it)})
        packet("output","true");val bytes=ByteArray(960){(it%107).toByte()};DataOutputStream(phone).apply{writeInt(0x48565233);writeInt(2);writeLong(1);writeInt(48000);writeInt(1);writeInt(960);write(bytes);flush()}
        assertTrue(await{played.isNotEmpty()});assertArrayEquals(bytes,played.first());assertTrue(live.state.value.inputActive)
        assertFalse(app.settings.value.phoneLiveEnabled);assertFalse(app.voice.capturing)
    }
    @Test fun privateOutputAdmissionSuppressesWatchSpeakerWithoutSwitchingAwayFromWatchMicrophone(){
        start();packet("private","private-output");packet("output","true");permits.release();assertTrue(await{reads.get()>0})
        DataOutputStream(phone).apply{writeInt(0x48565233);writeInt(2);writeLong(1);writeInt(48000);writeInt(1);writeInt(960);write(ByteArray(960){3});flush()};idle();assertTrue(played.isEmpty());assertTrue(live.state.value.active)
        live.end();packet("answer","old");idle();assertFalse(live.state.value.active);assertEquals(1,opens.get())
    }
}
