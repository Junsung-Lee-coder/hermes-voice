package com.rumi.hermesvoice.watch

import android.Manifest
import android.media.AudioManager
import android.media.AudioRecord
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Looper
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.diag.DiagCode
import com.rumi.hermesvoice.core.live.LiveDialogue
import com.rumi.hermesvoice.core.live.LiveWire
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
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * R42 (branch B, Watch-direct experiment): an ordinary Watch-origin call (Watch microphone, Watch speaker) is owned end to end by the Watch's
 * own WebRTC peer on the Watch's OS default internet route, including the Phone-proxied Bluetooth uplink; the Phone only turns the Watch's
 * offer into the authenticated session and returns its answer over the exact paired control.
 *
 * Real: the Watch Live runtime, its DEFAULT media factory and the production Watch WebRTC adapter. Doubles at the platform/provider edges only:
 * the Data Layer (MessageClient, ChannelClient), the default network as ConnectivityManager reports it (Bluetooth, Wi-Fi absent), the audio
 * route, and the pinned WebRTC native objects (JNI is not loadable on a JVM; the pinned Java API is). A PASS proves the app's own ownership,
 * ordering, fences and teardown against those edges; it is NOT evidence that WebRTC/ICE/DTLS media actually crosses a Watch's Bluetooth
 * proxy, nor of audibility, latency or battery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@LooperMode(LooperMode.Mode.PAUSED)
class WatchDirectNetworkR42RoboTest {
    private lateinit var app:WatchApp;private lateinit var live:WatchLiveVoiceRuntime
    private lateinit var connectivity:ConnectivityManager
    private val sent=ConcurrentLinkedQueue<ByteArray>()
    private val opens=AtomicInteger();private val recorders=AtomicInteger()
    private val initialized=AtomicInteger();private val admBuilds=AtomicInteger();private val factories=AtomicInteger()
    private val order=CopyOnWriteArrayList<String>()
    private val peers=CopyOnWriteArrayList<Peer>()
    private lateinit var module:JavaAudioDeviceModule
    private var recordState:JavaAudioDeviceModule.AudioRecordStateCallback?=null
    private var transports=setOf(NetworkCapabilities.TRANSPORT_BLUETOOTH)
    private var capabilities=setOf(NetworkCapabilities.NET_CAPABILITY_INTERNET,NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    private var networkUp=true
    @Volatile private var stall=false
    private var id="";private var media="media-1";private var epoch="epoch-1";private var sequence=0L

    /** One native peer the production adapter created: its own data channel, descriptions and teardown. */
    private class Peer(val pc:PeerConnection,val data:DataChannel) {
        var observer:PeerConnection.Observer?=null;var events:DataChannel.Observer?=null
        val remote=CopyOnWriteArrayList<SessionDescription>();val toProvider=CopyOnWriteArrayList<String>()
    }

    private fun idle()=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
    private fun await(condition:()->Boolean):Boolean {val end=System.nanoTime()+5_000_000_000;while(System.nanoTime()<end){idle();if(condition())return true;Thread.sleep(5)};return condition()}
    private fun settle()=repeat(6){idle()}
    private fun v2()=sent.mapNotNull{WatchLiveWire.parse(it)}
    private fun v1()=sent.mapNotNull{runCatching{JSONObject(it.toString(Charsets.UTF_8))}.getOrNull()}.filter{it.optInt("v")==1}
    private fun ops(op:String)=v2().filter{it.op==op}
    private fun phone(op:String,body:String="")=live.onStatus("phone",WatchLiveWire.packet(id,media,epoch,++sequence,op,body))
    private fun provider(peer:Peer,json:String)=peer.events!!.onMessage(DataChannel.Buffer(ByteBuffer.wrap(json.toByteArray()),false))
    private fun started()="{\"type\":\"session.started\",\"session\":{\"id\":\"fixture\"}}"

    @Before fun setup(){
        app=RuntimeEnvironment.getApplication() as WatchApp;app.localStore.putString("live_control_id","");app.localStore.putString("live_control_node","")
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.applySettings(WatchSettings(phoneLiveEnabled=false,watchLiveEnabled=true,revision=100).toJson());idle()
        mockkStatic(Wearable::class);val messages=mockk<MessageClient>()
        every{messages.sendMessage(any(),any(),any())}answers{sent+=thirdArg<ByteArray>();if(stall)TaskCompletionSource<Int>().task else Tasks.forResult(1)}
        every{Wearable.getMessageClient(any<android.content.Context>())}returns messages
        val channels=mockk<ChannelClient>(relaxed=true);every{channels.openChannel(any(),any())}answers{opens.incrementAndGet();TaskCompletionSource<ChannelClient.Channel>().task}
        every{Wearable.getChannelClient(any<android.content.Context>())}returns channels
        // The app's own recorder (the paired relay's capture) must never be built for direct media: count any attempt.
        Class.forName("android.media.AudioRecord");mockkConstructor(AudioRecord.Builder::class)
        every{anyConstructed<AudioRecord.Builder>().build()}answers{recorders.incrementAndGet();throw IllegalStateException("no app recorder in this test")}
        // The OS default network exactly as Android reports it: Bluetooth (the Phone-proxied uplink), Watch Wi-Fi absent.
        connectivity=app.getSystemService(ConnectivityManager::class.java);mockkObject(connectivity)
        val network=mockk<Network>();val caps=mockk<NetworkCapabilities>()
        every{connectivity.activeNetwork}answers{if(networkUp)network else null}
        every{connectivity.getNetworkCapabilities(any())}answers{if(networkUp)caps else null}
        every{caps.hasTransport(any())}answers{firstArg<Int>() in transports}
        every{caps.hasCapability(any())}answers{firstArg<Int>() in capabilities}
        // Pinned WebRTC Java API; its JNI is mocked at the static entry points the production adapter calls.
        mockkStatic(PeerConnectionFactory::class);mockkStatic(JavaAudioDeviceModule::class)
        every{PeerConnectionFactory.initialize(any())}answers{initialized.incrementAndGet();order+="initialize";Unit}
        module=mockk(relaxed=true)
        every{module.setMicrophoneMute(any())}answers{order+="mic:${firstArg<Boolean>()}";Unit}
        every{module.setSpeakerMute(any())}answers{order+="speaker:${firstArg<Boolean>()}";Unit}
        every{module.release()}answers{order+="adm-release";Unit}
        val b=mockk<JavaAudioDeviceModule.Builder>(relaxed=true)
        every{JavaAudioDeviceModule.builder(any())}answers{admBuilds.incrementAndGet();b}
        every{JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()}returns false
        every{JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported()}returns false
        every{b.setAudioSource(any())}returns b;every{b.setAudioAttributes(any())}returns b
        every{b.setUseHardwareAcousticEchoCanceler(any())}returns b;every{b.setUseHardwareNoiseSuppressor(any())}returns b
        every{b.setAudioRecordStateCallback(any())}answers{recordState=firstArg();b}
        every{b.setSamplesReadyCallback(any())}returns b;every{b.setPlaybackSamplesReadyCallback(any())}returns b
        every{b.setEnableVolumeLogger(any())}returns b;every{b.setStopRecordingOnMute(any())}returns b
        every{b.createAudioDeviceModule()}answers{order+="adm";module}
        // Native peers the adapter may create, in order (stubbed here, never inside an answer).
        val pool=(1..3).map{index->
            val pc=mockk<PeerConnection>(relaxed=true);val data=mockk<DataChannel>(relaxed=true);val peer=Peer(pc,data)
            val offer="v=0\r\no=- $index 2 IN IP4 127.0.0.1\r\ns=WATCH-OWNED-OFFER-$index\r\n"
            every{pc.createOffer(any(),any())}answers{firstArg<SdpObserver>().onCreateSuccess(SessionDescription(SessionDescription.Type.OFFER,offer))}
            every{pc.setLocalDescription(any(),any())}answers{firstArg<SdpObserver>().onSetSuccess();peer.observer!!.onIceGatheringChange(PeerConnection.IceGatheringState.COMPLETE)}
            every{pc.localDescription}returns SessionDescription(SessionDescription.Type.OFFER,offer)
            every{pc.setRemoteDescription(any(),any())}answers{peer.remote+=secondArg<SessionDescription>();firstArg<SdpObserver>().onSetSuccess()}
            every{pc.createDataChannel(any(),any())}returns data
            every{pc.close()}answers{order+="peer-close";Unit};every{pc.dispose()}answers{order+="peer-dispose";Unit}
            every{pc.setAudioRecording(any())}answers{order+="recording:${firstArg<Boolean>()}";Unit}
            every{data.registerObserver(any())}answers{peer.events=firstArg();Unit}
            every{data.state()}returns DataChannel.State.OPEN;every{data.bufferedAmount()}returns 0L
            every{data.send(any())}answers{val buffer=firstArg<DataChannel.Buffer>().data;val bytes=ByteArray(buffer.remaining());buffer.duplicate().get(bytes);peer.toProvider+=bytes.toString(Charsets.UTF_8);true}
            peer
        }
        val factory=mockk<PeerConnectionFactory>(relaxed=true)
        every{factory.createAudioSource(any())}returns mockk(relaxed=true)
        every{factory.createAudioTrack(any(),any())}returns mockk(relaxed=true)
        every{factory.dispose()}answers{order+="factory-dispose";Unit}
        every{factory.createPeerConnection(any<PeerConnection.RTCConfiguration>(),any<PeerConnection.Observer>())}answers{
            val peer=pool[peers.size];peer.observer=secondArg();peers+=peer;peer.pc
        }
        val fb=mockk<PeerConnectionFactory.Builder>(relaxed=true);every{PeerConnectionFactory.builder()}returns fb
        every{fb.setAudioDeviceModule(any())}returns fb
        every{fb.createPeerConnectionFactory()}answers{factories.incrementAndGet();order+="factory";factory}
        live=WatchLiveVoiceRuntime(app){"phone"};live.admission={live.admitted(it)}
        // The Watch audio route is its own platform edge (WatchNativeLiveRoute has its own tests); here it opens and stays valid.
        live.routeFactory={object:WatchLiveRoute{override fun open()=true;override fun valid()=true;override fun close(){}}}
        // DO NOT replace mediaFactory: the production default chooses the media from the Phone's open.
    }
    @After fun cleanup(){stall=false;if(::live.isInitialized)live.end();idle();app.scope.coroutineContext[Job]?.cancel();unmockkAll()}

    private fun talk():JSONObject{
        live.toggle();idle()
        val start=v1().last{it.optString("op")=="start"};id=start.getString("id");return start
    }
    private fun openDirect(muted:Boolean=false){phone("open",JSONObject().put("direct",true).put("muted",muted).put("private",false).toString())}
    /** Talk, the Phone's direct open, and the Watch's own offer handed to the Phone. */
    private fun offered(muted:Boolean=false):Peer{
        val start=talk();assertEquals("an ordinary Watch Start tells the Phone direct media is possible",true,start.opt("direct"))
        openDirect(muted)
        assertTrue("the Watch made its own offer and handed it to the Phone",await{ops("offer").isNotEmpty()})
        return peers.single()
    }
    private fun connected():Peer{
        val peer=offered()
        phone("answer",PROVIDER_ANSWER);assertTrue("the Watch reports its peer negotiated",await{ops("connected").isNotEmpty()})
        provider(peer,started());assertTrue(await{ops("event").any{JSONObject(it.body).optString("type")=="session.started"}})
        return peer
    }
    private fun assertNoNetworkForcing(){
        verify(exactly=0){connectivity.requestNetwork(any<NetworkRequest>(),any<ConnectivityManager.NetworkCallback>())}
        verify(exactly=0){connectivity.registerNetworkCallback(any<NetworkRequest>(),any<ConnectivityManager.NetworkCallback>())}
        verify(exactly=0){connectivity.bindProcessToNetwork(any())}
    }

    @Test fun W01_anOrdinaryWatchCallRunsItsOwnPeerOverTheBluetoothDefaultNetworkAndHandsOnlyTheOfferToThePhone(){
        val peer=offered()
        val offer=ops("offer").single()
        assertEquals("the exact offer of the Watch's own peer, unchanged","v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=WATCH-OWNED-OFFER-1\r\n",offer.body)
        assertEquals("one Watch peer factory and one Watch audio device module",listOf(1,1),listOf(factories.get(),admBuilds.get()))
        assertTrue(initialized.get()>=1)
        assertEquals("no paired PCM channel for direct media",0,opens.get())
        assertEquals("no app recorder: Watch capture is the WebRTC audio device module's",0,recorders.get())
        // The only default network reported here is Bluetooth (Wi-Fi absent): the peer above was built on it, nothing was requested or bound.
        assertNoNetworkForcing()
        assertEquals("the Watch speaker stays muted until the Phone confirms output","speaker:true",order.last{it.startsWith("speaker:")})
        phone("answer",PROVIDER_ANSWER)
        assertTrue(await{ops("connected").isNotEmpty()})
        assertEquals("the Phone's session answer reaches the Watch peer exactly once, as the answer",listOf(PROVIDER_ANSWER),peer.remote.map{it.description})
        assertEquals(SessionDescription.Type.ANSWER,peer.remote.single().type)
        provider(peer,started())
        assertTrue("the provider's session.started crosses to the Phone exactly",await{ops("event").any{it.body==started()}})
        phone("output","true");settle()
        assertEquals("output enabled on the Watch's own audio device module","speaker:false",order.last{it.startsWith("speaker:")})
        val command="{\"type\":\"session.commentary.append\",\"event_id\":\"g1\",\"delegation_id\":null,\"content\":\"hi\"}"
        phone("send",command);settle()
        assertEquals("a Phone provider command goes out on the Watch data channel byte for byte",listOf(command),peer.toProvider)
        assertTrue(live.state.value.active);assertTrue(live.state.value.status,live.state.value.status.contains("direct"))
        assertEquals(0,opens.get());assertEquals(1,factories.get());assertNoNetworkForcing()
    }

    @Test fun W02_withoutAValidatedDefaultInternetRouteTheWatchRefusesBeforeAnyPeerMicrophoneOrOffer(){
        for((name,setup) in listOf<Pair<String,()->Unit>>(
            "no default network" to {networkUp=false},
            "default network without internet" to {networkUp=true;capabilities=setOf(NetworkCapabilities.NET_CAPABILITY_VALIDATED)},
            "default internet not validated" to {networkUp=true;capabilities=setOf(NetworkCapabilities.NET_CAPABILITY_INTERNET)})){
            setup();sent.clear();sequence=0L;media="media-$name".replace(' ','-');epoch="epoch-${media.length}"
            if(::live.isInitialized)live.end();idle()
            live=WatchLiveVoiceRuntime(app){"phone"};live.admission={live.admitted(it)}
            live.routeFactory={object:WatchLiveRoute{override fun open()=true;override fun valid()=true;override fun close(){}}}
            val observed=app.diag.snapshot().count{it.code==DiagCode.LIVE_SETUP_FAILED&&it.n==61}
            talk();openDirect()
            assertTrue("$name: an explicit refusal",await{ops("refused").isNotEmpty()})
            assertTrue(name,ops("offer").isEmpty())
            assertEquals("$name: no WebRTC initialization, audio device module or peer",listOf(0,0,0),listOf(initialized.get(),admBuilds.get(),factories.get()))
            assertEquals("$name: and no paired PCM fallback",0,opens.get())
            assertTrue(name+": "+live.state.value.status,live.state.value.status.contains("internet"))
            assertFalse(live.state.value.active)
            assertEquals("$name: its own setup step, once",observed+1,app.diag.snapshot().count{it.code==DiagCode.LIVE_SETUP_FAILED&&it.n==61})
        }
        assertNoNetworkForcing()
    }

    @Test fun W03_everyProviderEventThePhoneConsumesCrossesInOrderAndTheRestStayOnTheWatch(){
        val peer=connected();val before=ops("event").size
        val events=listOf(
            "{\"type\":\"session.updated\",\"session\":{}}",
            "{\"type\":\"session.input_audio_buffer.speech_started\",\"audio_start_ms\":10}",
            "{\"type\":\"session.input_transcript.delta\",\"event_id\":\"t1\",\"start_ms\":0,\"end_ms\":100,\"delta\":\"알파에 물어봐\"}",
            "{\"type\":\"rate_limits.updated\",\"rate_limits\":[]}",
            "{\"type\":\"session.output_transcript.delta\",\"event_id\":\"t2\",\"start_ms\":100,\"end_ms\":200,\"delta\":\"네\"}",
            "{\"type\":\"session.output_audio.started\"}",
            "{\"type\":\"session.delegation.created\",\"delegation\":{\"type\":\"delegation\",\"target\":\"client\",\"id\":\"d1\"},\"offset_ms\":300}",
            "{\"type\":\"session.commentary.appended\",\"client_event_id\":\"c1\"}",
            "{\"type\":\"session.instructions.appended\",\"client_event_id\":\"c2\"}",
            "{\"type\":\"session.thinking.appended\",\"client_event_id\":\"c3\"}",
            "not json",
            "{\"type\":\"session.created\",\"session\":{}}",
            "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\"}}")
        for(e in events)provider(peer,e)
        settle()
        val crossed=ops("event").drop(before).map{it.body}
        // The oracle is the Phone's own real parsers: whatever they would act on must cross, in order and exactly once.
        fun consumed(raw:String)=LiveDialogue("call","scope","wake words").receive(raw)!=null||LiveWire.appended(raw)!=null
        assertEquals("every event the Phone's parsers consume crossed, in order, once",events.filter(::consumed),crossed.filter(::consumed))
        assertTrue("nothing crossed that the Phone does not consume",crossed.all(::consumed))
        assertTrue("provider chatter stays on the Watch",crossed.none{it.contains("rate_limits")||it.contains("speech_started")||it.contains("session.updated")||it.contains("output_audio.started")})
    }

    @Test fun W04_stopWhileTheSessionAnswerIsHeldClosesTheWatchPeerAndALateAnswerIsNeverApplied(){
        val peer=offered()
        live.end();settle()
        assertTrue("Stop closed and disposed the Watch peer",order.containsAll(listOf("peer-close","peer-dispose","adm-release","factory-dispose")))
        assertTrue("its capture was stopped before teardown",order.indexOf("recording:false") in 0 until order.indexOf("peer-close"))
        phone("answer",PROVIDER_ANSWER);settle()
        assertTrue("the late answer reached no peer",peer.remote.isEmpty())
        assertEquals("never a second offer, factory or relay",listOf(1,1,0),listOf(ops("offer").size,factories.get(),opens.get()))
        assertFalse(live.state.value.active)
        assertTrue("the Phone was told this control ended",v1().any{it.optString("op")=="end"&&it.optString("id")==id})
    }

    @Test fun W05_anAnswerFromAnOlderMediaOrAnotherPhoneEpochNeverReachesTheCurrentPeer(){
        val first=offered()
        // The same control's next media replaces the first (the Phone's own new open); the first peer is torn down.
        media="media-2";sequence=0L;openDirect()
        assertTrue(await{peers.size==2&&ops("offer").size==2})
        val second=peers[1]
        assertTrue("the replaced peer was disposed",order.count{it=="peer-dispose"}>=1)
        live.onStatus("phone",WatchLiveWire.packet(id,"media-1",epoch,2,"answer","v=0\r\nOLD\r\n"));settle()
        live.onStatus("phone",WatchLiveWire.packet(id,"media-2","another-epoch",2,"answer","v=0\r\nFOREIGN\r\n"));settle()
        assertTrue("an older media's answer is inert",first.remote.isEmpty()&&second.remote.isEmpty())
        phone("answer",PROVIDER_ANSWER)
        assertTrue(await{second.remote.size==1});assertEquals(PROVIDER_ANSWER,second.remote.single().description)
        assertTrue(first.remote.isEmpty())
    }

    @Test fun W06_aPrivateOrPersonalWatchOutputNeverRunsDirectAndAMidCallPrivateSelectionStopsTheSpeaker(){
        // Private output selected before Talk: the Start does not offer direct; a direct open anyway is refused before any peer.
        app.applySettings(WatchSettings(phoneLiveEnabled=false,watchLiveEnabled=true,privateAudio=true,revision=101).toJson());idle()
        val start=talk();assertNotEquals("a private Watch never offers direct media",true,start.opt("direct"))
        openDirect()
        assertTrue(await{ops("refused").isNotEmpty()})
        assertEquals("no Watch peer or paired fallback",listOf(0,0),listOf(factories.get(),opens.get()))
        assertTrue(app.diag.snapshot().any{it.code==DiagCode.LIVE_SETUP_FAILED&&it.n==60})
        // A personal (Bluetooth) output connected on the Watch is not the ordinary route either.
        app.applySettings(WatchSettings(phoneLiveEnabled=false,watchLiveEnabled=true,privateAudio=false,revision=102).toJson());idle()
        val audio=app.getSystemService(AudioManager::class.java);mockkObject(audio)
        val headset=mockk<android.media.AudioDeviceInfo>(relaxed=true);every{headset.type}returns android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        every{headset.isSink}returns true;every{headset.id}returns 77;every{headset.address}returns "watch-earbud"
        every{audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)}returns arrayOf(headset)
        assertNotEquals("a Watch headset is not ordinary Watch audio",true,talk().opt("direct"))
        unmockkObject(audio)
        // Mid-call: private output selected while a direct call runs. The Watch speaker stops and the call ends; no Phone output handoff.
        sent.clear();media="media-mid";sequence=0L;live.end();idle()
        val peer=connected();phone("output","true");settle()
        app.applySettings(WatchSettings(phoneLiveEnabled=false,watchLiveEnabled=true,privateAudio=true,revision=103).toJson());live.settingsChanged();settle()
        assertEquals("the Watch speaker was muted again","speaker:true",order.last{it.startsWith("speaker:")})
        assertTrue("and the Watch peer was disposed",order.contains("peer-dispose"))
        assertTrue("an explicit refusal, not a private handoff",ops("refused").isNotEmpty()&&ops("private_ready").isEmpty())
        assertFalse(live.state.value.active);assertEquals(0,opens.get())
        assertEquals("nothing went to the provider but the peer's one close request",listOf("session.close"),peer.toProvider.map{JSONObject(it).optString("type")})
    }

    @Test fun W07_aMuteBeforeMediaIsTheNativeCaptureStateAndPhoneMuteEdgesReachTheWatchAudioDeviceModule(){
        offered(muted=true)
        assertTrue("muted before the peer factory exists",order.indexOf("mic:true") in 0 until order.indexOf("factory"))
        assertFalse(order.contains("mic:false"))
        phone("mute","false");settle();assertEquals("mic:false",order.last{it.startsWith("mic:")})
        phone("mute","true");settle();assertEquals("mic:true",order.last{it.startsWith("mic:")})
        assertTrue(live.state.value.muted)
    }

    @Test fun W08_explicitStopReleasesEveryNativeResourceAndLateNativeCallbacksAreInert(){
        val peer=connected();phone("output","true");settle()
        recordState!!.onWebRtcAudioRecordStart();settle()
        assertTrue("the Watch's own capture start is reported to the Phone",ops("microphone").any{it.body=="true"})
        live.end();settle()
        assertTrue(order.containsAll(listOf("recording:false","peer-close","peer-dispose","factory-dispose","adm-release")))
        assertEquals("speaker:true",order.last{it.startsWith("speaker:")})
        assertEquals("the Watch asked the provider to close once, on its own data channel",listOf("session.close"),peer.toProvider.map{JSONObject(it).optString("type")})
        val after=sent.size
        recordState!!.onWebRtcAudioRecordStart();provider(peer,started());provider(peer,"{\"type\":\"session.output_transcript.delta\",\"event_id\":\"late\",\"start_ms\":0,\"end_ms\":1,\"delta\":\"late\"}");settle()
        assertTrue("no microphone or provider event after Stop",sent.toList().drop(after).mapNotNull{WatchLiveWire.parse(it)}.none{it.op=="microphone"||it.op=="event"})
        assertFalse(live.state.value.active);assertFalse(live.state.value.inputActive)
    }

    @Test fun W09_aRelayOpenStillUsesThePairedChannelAndNoWatchPeer(){
        talk()
        phone("open",JSONObject().put("relay",true).put("stream","audio-stream").put("muted",false).put("private",false).toString())
        assertTrue("the Phone-owned relay keeps its paired channel",await{opens.get()==1})
        assertEquals("and no Watch peer or WebRTC module",listOf(0,0,0),listOf(initialized.get(),admBuilds.get(),factories.get()))
        repeat(12){repeat(4){idle()};phone("ping")} // about 2.4 s with the Phone lease kept
        assertTrue("the relay call is still the current one",live.state.value.active)
        assertTrue("the relay's liveness is its PCM stream: no Watch ping",ops("ping").isEmpty())
    }

    @Test fun W10_aProviderEventTheWatchCannotHandToThePhoneEndsTheCallExplicitlyNeverASilentGap(){
        val peer=connected();settle()
        stall=true // the Data Layer stops completing sends: the Watch's bounded control queue fills
        repeat(80){provider(peer,"{\"type\":\"session.output_transcript.delta\",\"event_id\":\"q$it\",\"start_ms\":$it,\"end_ms\":${it+1},\"delta\":\"x\"}")}
        assertTrue("an explicit end",await{!live.state.value.active})
        assertTrue(live.state.value.status,live.state.value.status.contains("Live event"))
        assertTrue("the Watch peer is gone",order.contains("peer-dispose"))
        assertTrue("its own step",app.diag.snapshot().any{it.n==63})
    }

    @Test fun W11_theWatchsOwnDirectMediaTellsThePhoneItIsAliveAndStopsTellingAfterStop(){
        connected()
        repeat(5){repeat(20){idle()};phone("ping")} // five seconds, the Phone lease kept by a Phone ping each second
        val pings=ops("ping").size
        assertTrue("the Watch pinged the Phone about once a second: $pings",pings>=4)
        live.end();settle();val after=sent.size
        repeat(3){repeat(20){idle()}}
        assertTrue("no Watch ping after Stop",sent.toList().drop(after).mapNotNull{WatchLiveWire.parse(it)}.none{it.op=="ping"})
    }

    @Test fun W12_thePhonesMutedStateStopsTheWatchCaptureAtOnceAndAnOlderQueuedUnmuteCannotUndoIt(){
        val peer=connected();phone("output","true");settle()
        var stateSeq=0L
        fun state(muted:Boolean)=live.onStatus("phone",JSONObject().put("v",1).put("id",id).put("epoch",epoch).put("seq",++stateSeq).put("probe","").put("media",media)
            .put("active",true).put("muted",muted).put("watch_audio",true).put("phone_audio",false).put("end_reason","").put("status","Live").toString().toByteArray())
        state(false);settle();val before=order.size
        state(true);settle() // no ordered "mute" command has arrived: it is queued behind provider commands
        assertEquals("the state alone muted the Watch's own capture","mic:true",order.last{it.startsWith("mic:")})
        assertTrue(order.size>before);assertTrue(live.state.value.muted)
        phone("mute","false");settle() // the older unmute drains after the newer state
        assertEquals("an older queued unmute cannot reopen the microphone","mic:true",order.last{it.startsWith("mic:")})
        assertTrue(live.state.value.muted)
        phone("mute","true");settle();assertEquals("mic:true",order.last{it.startsWith("mic:")})
        state(false);settle()
        assertEquals("the newer unmuted state reopens it","mic:false",order.last{it.startsWith("mic:")})
        assertFalse(live.state.value.muted)
        phone("mute","true");settle();assertEquals("an ordered Mute with no newer state still mutes","mic:true",order.last{it.startsWith("mic:")})
        assertTrue(peer.remote.size==1)
    }

    @Test fun W13_afterSilenceAnUnmutedStateAlreadyOnItsWayCannotReopenTheWatchCapture(){
        connected();phone("output","true");settle()
        var stateSeq=0L
        fun state(muted:Boolean)=live.onStatus("phone",JSONObject().put("v",1).put("id",id).put("epoch",epoch).put("seq",++stateSeq).put("probe","").put("media",media)
            .put("active",true).put("muted",muted).put("watch_audio",true).put("phone_audio",false).put("end_reason","").put("status","Live").toString().toByteArray())
        state(true);settle();assertEquals("mic:true",order.last{it.startsWith("mic:")})
        phone("silence");settle()
        state(false);settle() // the user's Unmute just before Stop, overtaken by the silence
        assertEquals("capture stays closed after silence","mic:true",order.last{it.startsWith("mic:")})
        phone("mute","false");settle()
        assertEquals("the ordered unmute too","mic:true",order.last{it.startsWith("mic:")})
        assertEquals("speaker:true",order.last{it.startsWith("speaker:")})
    }

    companion object {
        const val PROVIDER_ANSWER="v=0\r\no=- 9 2 IN IP4 0.0.0.0\r\ns=PROVIDER-ANSWER\r\n"
    }
}
