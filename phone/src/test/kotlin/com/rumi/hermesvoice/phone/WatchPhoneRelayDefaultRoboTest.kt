package com.rumi.hermesvoice.phone

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.live.WatchLiveWire
import com.rumi.hermesvoice.core.settings.WatchSettings
import io.mockk.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Actual Watch control -> Phone native peer/input/output composition; only OS/DataLayer/provider leaves are synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class WatchPhoneRelayDefaultRoboTest:PhoneRoboBase(){
    private val packets=ConcurrentLinkedQueue<WatchLiveWire.Packet>()
    private val factories=AtomicInteger()
    private lateinit var channels:ChannelClient
    private val uplink=PipedInputStream(8192);private val watch=PipedOutputStream(uplink)
    private val downlink=ByteArrayOutputStream()
    private var inputCallback:JavaAudioDeviceModule.AudioBufferCallback?=null
    /** R40: the ADM input rate the production gave the pinned Builder (setSampleRate sets both rates, setInputSampleRate the input). */
    private var inputRate=0
    private var remoteSink:AudioTrackSink?=null
    private var recordEnabled=true
    private var outputMuted=false
    private var microphoneStarts=0
    private var eventObserver:DataChannel.Observer?=null
    private var peerObserver:PeerConnection.Observer?=null
    private var offer:WatchLiveWire.Packet?=null
    private var seq=0L
    @Before fun setup(){
        baseSetUp();shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled=false,watchLiveEnabled=true))
        app.live.admission={app.live.admitted(it)} // the separate service test exercises Android FGS admission
        val n=mockk<Node>();every{n.id}returns "watch-node";val info=mockk<CapabilityInfo>();every{info.nodes}returns setOf(n)
        val caps=mockk<CapabilityClient>();every{caps.getCapability(any(),any())}returns Tasks.forResult(info)
        every{Wearable.getCapabilityClient(any<Context>())}returns caps
        val messages=mockk<MessageClient>();every{messages.sendMessage(any(),any(),any())}answers{WatchLiveWire.parse(thirdArg<ByteArray>())?.let{packets+=it};Tasks.forResult(1)}
        every{Wearable.getMessageClient(any<Context>())}returns messages
        channels=mockk(relaxed=true);every{Wearable.getChannelClient(any<Context>())}returns channels
        every{channels.getInputStream(any())}returns Tasks.forResult(uplink as InputStream)
        every{channels.getOutputStream(any())}returns Tasks.forResult(downlink as OutputStream)
        every{channels.close(any())}returns Tasks.forResult(null)
        mockkStatic(PeerConnectionFactory::class);mockkStatic(JavaAudioDeviceModule::class)
        every{PeerConnectionFactory.initialize(any())}just Runs
        val module=mockk<JavaAudioDeviceModule>(relaxed=true)
        every{module.setAudioRecordEnabled(any())}answers{recordEnabled=firstArg()}
        every{module.setSpeakerMute(any())}answers{outputMuted=firstArg()}
        val b=mockk<JavaAudioDeviceModule.Builder>(relaxed=true)
        every{JavaAudioDeviceModule.builder(any())}returns b
        every{JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()}returns false
        every{JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported()}returns false
        every{b.setAudioSource(any())}returns b;every{b.setAudioAttributes(any())}returns b
        every{b.setUseHardwareAcousticEchoCanceler(any())}returns b;every{b.setUseHardwareNoiseSuppressor(any())}returns b
        every{b.setAudioRecordStateCallback(any())}returns b;every{b.setSamplesReadyCallback(any())}returns b
        every{b.setPlaybackSamplesReadyCallback(any())}returns b;every{b.setEnableVolumeLogger(any())}returns b
        every{b.setStopRecordingOnMute(any())}returns b;every{b.setSampleRate(any())}answers{inputRate=firstArg();b}
        every{b.setInputSampleRate(any())}answers{inputRate=firstArg();b};every{b.setOutputSampleRate(any())}returns b
        every{b.setUseStereoInput(any())}returns b;every{b.setUseStereoOutput(any())}returns b
        every{b.setAudioBufferCallback(any())}answers{inputCallback=firstArg();b}
        every{b.createAudioDeviceModule()}returns module
        val factory=mockk<PeerConnectionFactory>(relaxed=true);val fb=mockk<PeerConnectionFactory.Builder>(relaxed=true)
        every{PeerConnectionFactory.builder()}returns fb;every{fb.setAudioDeviceModule(any())}returns fb
        every{fb.createPeerConnectionFactory()}answers{if(recordEnabled)microphoneStarts++;factories.incrementAndGet();factory}
        val peer=mockk<PeerConnection>(relaxed=true)
        every{factory.createPeerConnection(any<PeerConnection.RTCConfiguration>(),any<PeerConnection.Observer>())}answers{peerObserver=secondArg();peer}
        every{factory.createAudioSource(any())}returns mockk(relaxed=true)
        every{factory.createAudioTrack(any(),any())}returns mockk(relaxed=true)
        every{peer.createOffer(any(),any())}answers{firstArg<SdpObserver>().onCreateSuccess(SessionDescription(SessionDescription.Type.OFFER,"v=0\r\nPHONE-OWNED\r\n"))}
        every{peer.setLocalDescription(any(),any())}answers{firstArg<SdpObserver>().onSetSuccess();peerObserver!!.onIceGatheringChange(PeerConnection.IceGatheringState.COMPLETE)}
        every{peer.localDescription}returns SessionDescription(SessionDescription.Type.OFFER,"v=0\r\nPHONE-OWNED\r\n")
        every{peer.setRemoteDescription(any(),any())}answers{firstArg<SdpObserver>().onSetSuccess();event("session.started")}
        val data=mockk<DataChannel>(relaxed=true);every{peer.createDataChannel(any(),any())}returns data
        every{data.registerObserver(any())}answers{eventObserver=firstArg()};every{data.state()}returns DataChannel.State.OPEN;every{data.bufferedAmount()}returns 0;every{data.send(any())}returns true
        val track=mockk<org.webrtc.AudioTrack>(relaxed=true);every{track.addSink(any())}answers{remoteSink=firstArg()}
        val receiver=mockk<RtpReceiver>();every{receiver.track()}returns track
        every{peer.setRemoteDescription(any(),any())}answers{firstArg<SdpObserver>().onSetSuccess();peerObserver!!.onAddTrack(receiver,emptyArray());event("session.started")}
        val original=fake.server.dispatcher
        fake.server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(r:okhttp3.mockwebserver.RecordedRequest):okhttp3.mockwebserver.MockResponse {
            if(r.path=="/api/audio/voice-live/session")return okhttp3.mockwebserver.MockResponse().setHeader("Content-Type","application/json").setBody("{\"ok\":true,\"session\":{\"id\":\"fixture\"},\"transport\":{\"type\":\"webrtc\",\"sdp\":\"v=0\\r\\nfixture\\r\\n\"}}")
            return original.dispatch(r)
        }}
    }
    @After fun cleanup(){app.live.end();watch.close();uplink.close();settle();baseTearDown()}
    private fun event(type:String){val json=JSONObject().put("type",type).put("session",JSONObject().put("id","fixture")).toString();eventObserver?.onMessage(DataChannel.Buffer(ByteBuffer.wrap(json.toByteArray()),false))}
    private fun incoming(op:String,body:String="") {val p=offer!!;app.liveWatch.receive("watch-node",WatchLiveWire.packet(p.id,p.media,p.epoch,++seq,op,body))}
    private fun start(openChannel:Boolean=true){
        conversation("alpha");app.liveWatch.receive("watch-node",JSONObject().put("v",1).put("id","relay-control").put("op","start").put("probe","p").toString().toByteArray())
        assertTrue(await{packets.any{it.op=="open"}});offer=packets.last{it.op=="open"}
        incoming("offer",com.rumi.hermesvoice.core.live.WatchRelayAudio.OFFER)
        if(openChannel)app.liveWatch.audioChannel(channel())
    }
    private fun channel(node:String="watch-node",stream:String?=null):ChannelClient.Channel {
        val p=offer!!;val c=mockk<ChannelClient.Channel>();every{c.nodeId}returns node
        every{c.path}returns "/hv/v3/live/audio/${p.id}/${p.media}/${p.epoch}/${stream?:JSONObject(p.body).optString("stream","missing")}";return c
    }
    private fun frame(direction:Int,sequence:Long,bytes:ByteArray)=DataOutputStream(watch).apply{writeInt(0x48565233);writeInt(direction);writeLong(sequence);writeInt(if(direction==1)16000 else 48000);writeInt(1);writeInt(bytes.size);write(bytes);flush()}
    @Test fun phoneOffPermissionDeniedStillOwnsProviderWithoutOpeningPhoneMicAndRealWatchBytesReachNativeInput(){
        start();assertTrue("Watch relay must construct the PHONE provider peer, not forward Watch SDP",await{factories.get()==1})
        assertEquals(0,microphoneStarts);assertFalse(recordEnabled);assertTrue(outputMuted);assertFalse(app.phoneMic.occupied)
        val bytes=ByteArray(640){(it%127).toByte()};frame(1,1,bytes) // R40: one 20 ms 16 kHz Watch packet
        val target=ByteBuffer.allocateDirect(320);val callback=inputCallback;assertNotNull("supported external input callback must be bound",callback)
        callback!!.onBuffer(target,AudioFormat.ENCODING_PCM_16BIT,1,16000,0,0)
        val actual=ByteArray(320);target.duplicate().apply{clear();get(actual)};assertArrayEquals(bytes.copyOf(320),actual)
        assertFalse(app.settings.watchSettings().phoneLiveEnabled);assertTrue(app.settings.watchSettings().watchLiveEnabled)
        assertEquals(0,fake.speakRequests.get());assertTrue(fake.prompts.isEmpty())
    }
    @Test fun returnedProviderAudioUsesOnlyPairedWatchDownlinkAndStopNeverReplaysOrResubmits(){
        start();assertTrue("Phone media owner must exist",await{factories.get()==1});incoming("connected")
        assertTrue(await{app.live.state.value.ready});val bytes=ByteArray(960){(it%101).toByte()}
        remoteSink!!.onData(ByteBuffer.wrap(bytes),16,48000,1,480,0)
        assertTrue(await{downlink.size()>=988});val input=DataInputStream(ByteArrayInputStream(downlink.toByteArray()))
        assertEquals(0x48565233,input.readInt());assertEquals(2,input.readInt());assertEquals(1L,input.readLong());assertEquals(48000,input.readInt());assertEquals(1,input.readInt());assertEquals(960,input.readInt());assertArrayEquals(bytes,ByteArray(960).also{input.readFully(it)})
        app.live.end();val count=downlink.size();remoteSink!!.onData(ByteBuffer.wrap(bytes),16,48000,1,480,0);settle();assertEquals(count,downlink.size());assertTrue(fake.prompts.isEmpty());assertEquals(0,microphoneStarts)
    }
    @Test fun wrongNodeRetiredStreamAndWrongDirectionNeverReachNativeInput(){
        start(false)
        app.liveWatch.audioChannel(channel(node="stranger"));app.liveWatch.audioChannel(channel(stream="retired"));settle()
        verify(exactly=0){channels.getInputStream(any())};app.liveWatch.audioChannel(channel());assertTrue("Phone media owner must exist",await{factories.get()==1});frame(2,1,ByteArray(960){7})
        assertTrue(await{!app.live.active});assertTrue(fake.prompts.isEmpty());assertEquals(0,microphoneStarts)
    }
}
