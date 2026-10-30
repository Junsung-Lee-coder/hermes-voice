package com.rumi.hermesvoice.phone

import android.Manifest
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import androidx.core.app.ServiceCompat
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.headset.*
import com.rumi.hermesvoice.core.live.*
import com.rumi.hermesvoice.phone.live.*
import io.mockk.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/** Existing actual control/native composition, synthetic Android routes and PCM only. No provider/hardware. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class LiveDeviceInputRoboTest {
    private lateinit var w:WatchPhoneRelayDefaultRoboTest
    private lateinit var audio:AudioManager
    private lateinit var record:AudioRecord
    private lateinit var out:AudioDeviceInfo
    private lateinit var source:AudioDeviceInfo
    private val starts=AtomicInteger();private val reads=AtomicInteger();private val releases=AtomicInteger()
    private var actual:AudioDeviceInfo?=null
    private var seq=0L
    private val pcm=ByteArray(960){(it%97).toByte()}
    private var fgs:(()->Unit)?=null
    private val services=ArrayList<org.robolectric.android.controller.ServiceController<PhoneLiveVoiceService>>()
    private fun endpoint(id:Int,type:Int,input:Boolean,address:String="earbud"):AudioDeviceInfo {
        val d=mockk<AudioDeviceInfo>();every{d.id}returns id;every{d.type}returns type
        every{d.address}returns address;every{d.isSource}returns input;every{d.isSink}returns !input
        every{d.productName}returns "synthetic";return d
    }
    @Before fun setup(){
        w=WatchPhoneRelayDefaultRoboTest();w.setup();shadowOf(w.app).grantPermissions(Manifest.permission.RECORD_AUDIO,Manifest.permission.BLUETOOTH_CONNECT)
        audio=w.app.getSystemService(AudioManager::class.java);mockkObject(audio)
        out=endpoint(901,AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,false)
        source=endpoint(902,AudioDeviceInfo.TYPE_BLUETOOTH_SCO,true);actual=source
        val comm=endpoint(903,AudioDeviceInfo.TYPE_BLUETOOTH_SCO,false)
        every{audio.getDevices(AudioManager.GET_DEVICES_INPUTS)}answers{arrayOf(source)}
        every{audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)}answers{arrayOf(out)}
        every{audio.availableCommunicationDevices}returns listOf(comm)
        every{audio.setCommunicationDevice(any())}returns true
        every{audio.communicationDevice}returns comm
        every{audio.mode}returns AudioManager.MODE_NORMAL;every{audio.mode=any()}just Runs
        val devices=object:HeadsetDevices {
            override fun outputs()=listOf(AudioEndpoint(out.id,out.type,out.address))
            override fun inputs()=audio.getDevices(AudioManager.GET_DEVICES_INPUTS).map{AudioEndpoint(it.id,it.type,it.address)}
            override fun watch(onChange:()->Unit)=AutoCloseable{}
        }
        w.app.settings.useHeadset=true
        PhoneApp::class.java.getDeclaredField("headset\$delegate").apply{isAccessible=true}.set(w.app,lazyOf(HeadsetPolicy({w.app.settings.useHeadset},devices)))
        Class.forName("android.media.AudioRecord");mockkStatic(AudioRecord::class)
        every{AudioRecord.getMinBufferSize(any(),any(),any())}returns 1920
        mockkConstructor(AudioRecord.Builder::class);record=mockk(relaxed=true)
        every{anyConstructed<AudioRecord.Builder>().setAudioSource(any())}answers{self as AudioRecord.Builder}
        every{anyConstructed<AudioRecord.Builder>().setAudioFormat(any())}answers{self as AudioRecord.Builder}
        every{anyConstructed<AudioRecord.Builder>().setBufferSizeInBytes(any())}answers{self as AudioRecord.Builder}
        every{anyConstructed<AudioRecord.Builder>().build()}returns record
        every{record.state}returns AudioRecord.STATE_INITIALIZED;every{record.recordingState}returns AudioRecord.RECORDSTATE_RECORDING
        every{record.setPreferredDevice(any())}returns true;every{record.routedDevice}answers{actual}
        every{record.startRecording()}answers{starts.incrementAndGet();Unit}
        every{record.read(any<ByteArray>(),any(),any(),any())}answers{reads.incrementAndGet();val b=firstArg<ByteArray>();val o=secondArg<Int>();val n=thirdArg<Int>();for(i in 0 until n)b[o+i]=pcm[i%pcm.size];n}
        every{record.release()}answers{releases.incrementAndGet();Unit}
        mockkStatic(ServiceCompat::class);every{ServiceCompat.startForeground(any(),any(),any(),any())}just Runs
        every{ServiceCompat.stopForeground(any(),any())}just Runs
    }
    @After fun cleanup(){w.app.live.end();services.forEach{it.destroy()};w.cleanup()}
    private fun open():WatchLiveWire.Packet {
        w.javaClass.getDeclaredMethod("start",Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(w,true)
        seq=1 // the existing start fixture already delivered the real offer as sequence one
        return w.javaClass.getDeclaredField("offer").apply{isAccessible=true}.get(w) as WatchLiveWire.Packet
    }
    private fun incoming(p:WatchLiveWire.Packet,op:String,body:String=""){
        w.app.liveWatch.receive("watch-node",WatchLiveWire.packet(p.id,p.media,p.epoch,++seq,op,body))
    }
    private fun request(p:WatchLiveWire.Packet):String {
        val token=JSONObject(p.body).optString("input_hold")
        assertTrue("selected headset requires an exact pre-capture Watch hold request",token.isNotBlank());return token
    }
    private fun admitInput(p:WatchLiveWire.Packet){
        incoming(p,"input_ready",request(p));w.settle()
        var intent=shadowOf(w.app).nextStartedService
        while(intent!=null&&!intent.getBooleanExtra("input_only",false))intent=shadowOf(w.app).nextStartedService
        assertNotNull("actual Phone input requires its own microphone FGS request",intent)
        assertTrue("input FGS must be distinct from output-only lease",intent!!.getBooleanExtra("input_only",false))
        fgs={val c=Robolectric.buildService(PhoneLiveVoiceService::class.java).create();services+=c;c.get().onStartCommand(intent,0,1)}
    }
    private fun fill(keyReads:IntArray?=null):ByteArray {
        w.await{w.javaClass.getDeclaredField("inputCallback").apply{isAccessible=true}.get(w)!=null}
        val cb=w.javaClass.getDeclaredField("inputCallback").apply{isAccessible=true}.get(w) as? JavaAudioDeviceModule.AudioBufferCallback
        // R40: the pinned record's 10 ms buffer at the input rate the production gave the Builder (48 kHz headset, 16 kHz Watch relay).
        val rate=w.javaClass.getDeclaredField("inputRate").apply{isAccessible=true}.getInt(w)
        assertNotNull("real provider external PCM callback",cb);val b=ByteBuffer.allocateDirect(2*rate/100)
        val before=FakeAndroidKeyStore.keyReadsOnThisThread()
        cb!!.onBuffer(b,AudioFormat.ENCODING_PCM_16BIT,1,rate,0,0)
        keyReads?.let{it[0]+=FakeAndroidKeyStore.keyReadsOnThisThread()-before}
        return ByteArray(b.capacity()).also{b.duplicate().apply{clear();get(it)}}
    }
    private fun phone(){
        w.app.settings.saveWatchSettings(w.app.settings.watchSettings().copy(phoneLiveEnabled=true))
        w.app.live.routeFactory={object:LiveRoute{override fun open()=true;override fun valid()=true;override fun close(){}}}
        w.conversation("phone-alpha");assertTrue(w.app.live.start())
    }
    @Test fun headsetInputBindsPrivatePlaybackToItsCommunicationSinkNotTheSourceOrOldA2dpId(){
        val p=open();admitInput(p);fgs!!();assertTrue(w.await{starts.get()==1});fill()
        val remote=PhoneLiveWatchControl::class.java.getDeclaredField("remote").apply{isAccessible=true}.get(w.app.liveWatch) as WatchLiveTransport
        var bound=-1;var writes=0
        remote.pcmFactory={id,current,_->bound=id;assertTrue(current());object:LivePcmOutput{override fun write(frame:LivePcmFrame):Boolean{writes++;return true};override fun close(){}}}
        remote.outputAdmitted(true);incoming(p,"private_ready",JSONObject(p.body).getString("output"))
        assertTrue(w.await{w.app.live.state.value.ready})
        val sink=w.javaClass.getDeclaredField("remoteSink").apply{isAccessible=true}.get(w) as org.webrtc.AudioTrackSink
        sink.onData(ByteBuffer.wrap(pcm),16,48000,1,480,0)
        assertTrue(w.await{writes==1});assertEquals(903,bound);assertNotEquals(source.id,bound);assertNotEquals(out.id,bound)
    }
    @Test fun idleLateFinalKeepsWatchOriginButRechecksNowUnavailableHeadsetInputWithoutResubmission(){
        var now=1000L;w.app.live.monotonicMs={now}
        val p=open();admitInput(p);fgs!!();assertTrue(w.await{starts.get()==1});fill();incoming(p,"connected")
        assertTrue(w.await{w.app.live.state.value.ready});val target=w.app.live.state.value.choices.single().owned.storedSessionId
        w.app.live.bindSession(target);assertTrue(w.await{w.app.live.state.value.boundSessionId==target})
        now+=10000;w.app.live.checkLifetime();assertFalse(w.app.live.active);assertTrue(w.await{releases.get()==1})
        every{audio.getDevices(AudioManager.GET_DEVICES_INPUTS)}returns emptyArray()
        w.fake.injectFinalRow(target,"synthetic late answer",1_800_000_000.0,"headset-idle-final")
        w.app.finalRecovery.requestReconcile()
        @Suppress("UNCHECKED_CAST")val packets=w.javaClass.getDeclaredField("packets").apply{isAccessible=true}.get(w) as java.util.concurrent.ConcurrentLinkedQueue<WatchLiveWire.Packet>
        assertTrue(w.await{packets.count{it.op=="open"}==2});val next=packets.last{it.op=="open"}
        assertNotEquals(p.media,next.media);assertTrue(JSONObject(next.body).optString("input_hold").isBlank())
        assertTrue(w.await{w.app.live.state.value.status.contains("Watch microphone")&&w.app.live.state.value.status.contains("unavailable")})
        assertEquals(VoiceOrigin.WATCH,w.app.live.state.value.origin);assertEquals(1,starts.get());assertTrue(w.fake.prompts.isEmpty())
    }
    @Test fun phoneOriginAlsoUsesTheVerifiedSelectedHeadsetInput(){phone();assertTrue(w.await{starts.get()==1});assertArrayEquals(pcm,fill());assertEquals(VoiceOrigin.PHONE,w.app.live.state.value.origin)}
    /** R33: [n] frames through the real ADM callback on this (the vendor audio) thread; the session key fetches made inside those
     *  callbacks only (the main looper the harness pumps between frames is not counted). */
    private fun keyReadsFor(n:Int,expected:ByteArray):Int{fill();val reads=intArrayOf(0);repeat(n){assertArrayEquals(expected,fill(reads))};return reads[0]}
    @Test fun K01_aCapturedPhoneFrameOnThePinnedAdmThreadFetchesNoSessionKey(){
        phone();assertTrue(w.await{starts.get()==1});assertArrayEquals(pcm,fill())
        assertEquals("100 captured 10 ms frames (the callback is the uplink's only pacer) must not decrypt the session",0,keyReadsFor(100,pcm))
        assertTrue(w.app.live.active)
    }
    @Test fun K02_aMutedPhoneFrameOnThePinnedAdmThreadFetchesNoSessionKeyEither(){
        phone();assertTrue(w.await{starts.get()==1});assertArrayEquals(pcm,fill());w.app.live.setMuted(true);w.settle()
        assertEquals("100 muted frames hand on silence without decrypting the session",0,keyReadsFor(100,ByteArray(960)))
    }
    @Test fun K03_aWatchOriginHeadsetFrameFetchesNoSessionKey(){
        val p=open();admitInput(p);fgs!!();assertTrue(w.await{starts.get()==1});assertArrayEquals(pcm,fill())
        assertEquals("the Watch relay's guard is just as hot on this thread",0,keyReadsFor(100,pcm))
    }
    @Test fun K04_aSignOutDuringTheCallStillStopsPcmFromTheVeryNextFrame(){
        phone();assertTrue(w.await{starts.get()==1});assertArrayEquals(pcm,fill())
        w.app.tokens.clear()
        repeat(3){assertArrayEquals("no PCM under a changed authority, from the very next frame",ByteArray(960),fill())};assertTrue(w.fake.prompts.isEmpty())
    }
    @Test fun K06_theRealAdapterObservesEveryFrameItsCallbackHandedWhileStartedZeroOrNot(){
        phone();assertTrue(w.await{w.app.live.state.value.started});assertArrayEquals(pcm,fill())
        repeat(49){fill()};w.app.live.setMuted(true);w.settle();repeat(25){assertArrayEquals(ByteArray(960),fill())}
        w.app.live.end();assertArrayEquals("a frame after its audio stopped",ByteArray(960),fill())
        assertTrue("the ended call's media numbers",w.await{w.app.diagnostics.log.snapshot().any{it.code.wire=="live_rtp_sent"}})
        w.app.diagnostics.log.snapshot().single{it.code.wire=="live_rtp_sent"}.let{
            assertEquals("75 handed 10 ms frames while started, captured or muted, none after its audio stopped",750L,it.ms);assertNull("the synthetic stats expose no packet count",it.n)
        }
    }
    @Test fun K05_aSameIdentityTokenRefreshKeepsTheCallAndItsPcm(){
        phone();assertTrue(w.await{starts.get()==1});assertArrayEquals(pcm,fill())
        w.app.tokens.save(com.rumi.hermesvoice.core.auth.HermesBearerSession("refreshed-access",w.fake.refreshToken,null,"basic","jun"))
        repeat(3){assertArrayEquals("the same account and generation keep their scope",pcm,fill())};assertTrue(w.app.live.active)
    }
    @Test fun phoneOriginOutputOnlyFallbackIsItsOwnVerifiedBuiltInNotAnotherHeadset(){
        source=endpoint(11,AudioDeviceInfo.TYPE_BUILTIN_MIC,true,"");actual=source;phone()
        assertTrue(w.await{starts.get()==1});assertArrayEquals(pcm,fill());verify{record.setPreferredDevice(source)}
        assertTrue(w.app.live.state.value.status.contains("Phone microphone"))
    }
    @Test fun bluetoothSourceWithDifferentAddressIsUnavailableNotPhysicalMicAbsence(){
        source=endpoint(904,AudioDeviceInfo.TYPE_BLUETOOTH_SCO,true,"another-headset");actual=source;open()
        assertTrue(w.await{w.app.live.state.value.status.contains("unavailable")&&w.app.live.state.value.status.contains("Watch microphone")})
        assertEquals(0,starts.get());assertFalse(w.app.live.state.value.status.contains("has no microphone"))
    }
    @Test fun usableHeadsetWaitsForExactWatchHoldBeforeAnyPhoneCapture(){val p=open();request(p);assertEquals(0,starts.get());assertFalse(w.app.phoneMic.occupied)}
    @Test fun distinctSinkAndSourceIdsUseVerifiedHeadsetPcmWithPhoneModeOffAndWatchOrigin(){
        val p=open();admitInput(p);fgs!!();assertTrue(w.await{starts.get()==1});assertTrue(w.await{w.app.live.state.value.inputActive})
        assertArrayEquals(pcm,fill());assertEquals(VoiceOrigin.WATCH,w.app.live.state.value.origin)
        assertFalse(w.app.settings.watchSettings().phoneLiveEnabled);assertTrue(w.app.phoneMic.occupied)
        verify{ServiceCompat.startForeground(any(),any(),any(),match{it and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE!=0})}
    }
    @Test fun outputOnlyIsTruthfulOriginalWatchFallbackWithoutPhoneCapture(){every{audio.getDevices(AudioManager.GET_DEVICES_INPUTS)}returns emptyArray();open();assertTrue(w.await{w.app.live.state.value.status.contains("Watch microphone")&&w.app.live.state.value.status.contains("unavailable")});assertEquals(0,starts.get())}
    @Test fun permissionDeniedDoesNotRequirePhoneMicForWatchFallback(){shadowOf(w.app).denyPermissions(Manifest.permission.RECORD_AUDIO);open();assertTrue(w.await{w.app.live.state.value.status.contains("Watch microphone")&&w.app.live.state.value.status.contains("permission")});assertEquals(0,starts.get());assertTrue(w.app.live.active)}
    @Test fun acceptedCommunicationRequestWithoutActualRecorderRouteNeverForwardsPhonePcm(){val p=open();actual=null;admitInput(p);fgs!!();assertTrue(w.await{releases.get()==1});assertTrue(fill().all{it==0.toByte()});assertTrue(w.app.live.state.value.status.contains("Watch microphone"));assertFalse(w.app.phoneMic.occupied)}
    @Test fun builtInPhoneMismatchIsRejectedAndOriginalWatchFallbackIsExplicit(){val p=open();actual=endpoint(10,AudioDeviceInfo.TYPE_BUILTIN_MIC,true,"");admitInput(p);fgs!!();assertTrue(w.await{releases.get()==1});assertTrue(fill().all{it==0.toByte()});assertTrue(w.app.live.state.value.status.contains("Watch microphone"));assertEquals(0,reads.get())}
    @Test fun fgsRefusalKeepsWatchOriginAndNeverOpensPhoneCapture(){val p=open();admitInput(p);every{ServiceCompat.startForeground(any(),any(),any(),any())}throws SecurityException("synthetic");fgs!!();assertTrue(w.await{w.app.live.state.value.status.contains("Watch microphone")&&w.app.live.state.value.status.contains("refused")});assertEquals(0,starts.get());assertTrue(w.app.live.active)}
    @Test fun stopDoesNotWaitForNativeFactoryAndClosesItsExactLateRecorderWithoutStartingIt(){
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val returnedWhileHeld=java.util.concurrent.atomic.AtomicBoolean(false)
        every{anyConstructed<AudioRecord.Builder>().build()}answers{
            entered.countDown();if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))returnedWhileHeld.set(true);record
        }
        try{
            val p=open();admitInput(p);fgs!!();assertTrue("actual native factory reached its held result",w.await{entered.count==0L})
            w.app.live.end();assertFalse("Stop must not wait for a held native factory",returnedWhileHeld.get());assertFalse(w.app.live.active)
            release.countDown();assertTrue("exact late native recorder must be released",w.await{releases.get()==1})
            assertEquals(0,starts.get());assertEquals(0,reads.get());assertFalse(w.app.phoneMic.occupied)
        }finally{release.countDown()}
    }
    @Test fun stopDuringFgsAcquisitionRejectsLateAdmission(){val p=open();admitInput(p);w.app.live.end();fgs!!();w.settle();assertEquals(0,starts.get());assertFalse(w.app.phoneMic.occupied);assertFalse(w.app.live.active)}
    @Test fun wrongHoldReceiptCannotStartPhoneMic(){val p=open();request(p);incoming(p,"input_ready","wrong");w.settle();assertEquals(0,starts.get());assertFalse(w.app.phoneMic.occupied)}
    @Test fun muteDuringAcquisitionIsAppliedBeforeCapturedBytes(){val p=open();admitInput(p);w.app.live.setMuted(true);fgs!!();w.settle();assertEquals(0,reads.get());assertTrue(w.app.live.state.value.muted);assertArrayEquals(ByteArray(960),fill());w.app.live.setMuted(false);assertTrue(w.await{starts.get()==1&&w.app.live.state.value.inputActive});assertArrayEquals(pcm,fill());assertFalse(w.app.live.state.value.muted)}
    @Test fun unexpectedWatchPcmAfterHeadsetSelectionEndsLiveInsteadOfMixingActiveInputs(){
        val p=open();admitInput(p);fgs!!();assertTrue(w.await{starts.get()==1});fill()
        w.javaClass.getDeclaredMethod("frame",Int::class.javaPrimitiveType,Long::class.javaPrimitiveType,ByteArray::class.java).apply{isAccessible=true}.invoke(w,1,1L,pcm)
        assertTrue(w.await{!w.app.live.active});assertTrue(w.await{releases.get()==1});assertTrue(w.fake.prompts.isEmpty())
    }
    @Test fun disconnectAfterConfirmationStopsInputWithoutSplicingWatchOrPhoneBuiltIn(){val p=open();admitInput(p);fgs!!();assertTrue(w.await{starts.get()==1});assertArrayEquals(pcm,fill());actual=endpoint(1,AudioDeviceInfo.TYPE_BUILTIN_MIC,true,"");assertArrayEquals(ByteArray(960),fill());assertTrue(w.await{!w.app.live.active});assertTrue(w.fake.prompts.isEmpty())}
    @Test fun accountSwapBeforeLateAdmissionCannotOpenMicrophone(){val p=open();admitInput(p);w.app.tokens.clear();w.app.invalidateSessionTitles();fgs!!();w.settle();assertEquals(0,starts.get());assertFalse(w.app.phoneMic.occupied)}

    // Original-Phone admission when Android exposes several built-in sources (bottom/back both map to TYPE_BUILTIN_MIC),
    // and the type-only Live setup observation of the real Phone caller. Wire words keep these readable without new symbols.
    private fun liveSetupEvents()=w.app.diagnostics.log.snapshot().filter{it.code.wire=="live_setup_failed"}
    // The journal thread replaces its file atomically. On the Windows test host an open read handle on that file, in any share mode,
    // makes the replace fail, and the journal writes again only after a later event. Open the durable file only while no journal write
    // is pending or running, so this observation cannot cause the failure it is looking for. Reads two flags; starts no work.
    private fun journalQuiet():Boolean{
        val journal=w.app.diagnostics.journal
        fun pending(name:String)=(com.rumi.hermesvoice.core.diag.DiagJournal::class.java.getDeclaredField(name).apply{isAccessible=true}.get(journal) as java.util.concurrent.atomic.AtomicBoolean).get()
        return !pending("scheduled")&&!pending("urgent")
    }
    private fun builtInOnly(vararg mics:AudioDeviceInfo){w.app.settings.useHeadset=false;every{audio.getDevices(AudioManager.GET_DEVICES_INPUTS)}answers{arrayOf(*mics)}}
    private fun heldSession(entered:java.util.concurrent.CountDownLatch,release:java.util.concurrent.CountDownLatch,posts:java.util.concurrent.ConcurrentLinkedQueue<String>,code:Int=200,body:String=""){
        val original=w.fake.server.dispatcher
        w.fake.server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):okhttp3.mockwebserver.MockResponse{
            if(request.path!="/api/audio/voice-live/session")return original.dispatch(request)
            posts+=request.body.readUtf8();entered.countDown();release.await(10,java.util.concurrent.TimeUnit.SECONDS)
            return if(code==200)original.dispatch(request) else okhttp3.mockwebserver.MockResponse().setResponseCode(code).setHeader("Content-Type","application/json").setBody(body)
        }}
    }
    @Test fun twoOsBuiltInMicsPinTheActuallyRoutedOneBeforePcmAndNeverSpliceTheOtherMidCall(){
        val bottom=endpoint(14,AudioDeviceInfo.TYPE_BUILTIN_MIC,true,"bottom");val back=endpoint(16,AudioDeviceInfo.TYPE_BUILTIN_MIC,true,"back")
        builtInOnly(bottom,back);actual=back;phone()
        assertTrue("bottom and back built-in sources still admit the original Phone microphone",w.await{starts.get()==1})
        assertArrayEquals("PCM only after the actually routed built-in source is pinned",pcm,fill())
        verify(exactly=0){record.setPreferredDevice(any())}
        assertTrue(w.app.live.state.value.status,w.app.live.state.value.status.contains("Phone microphone"))
        actual=bottom
        assertArrayEquals("another built-in source is never spliced mid-call",ByteArray(960),fill())
        assertTrue(w.await{!w.app.live.active});assertEquals(1,starts.get());assertTrue(w.fake.prompts.isEmpty())
    }
    @Test fun unmuteReopensOnlyThePinnedBuiltInAndRefusesTheOtherInsteadOfRepinning(){
        val bottom=endpoint(14,AudioDeviceInfo.TYPE_BUILTIN_MIC,true,"bottom");val back=endpoint(16,AudioDeviceInfo.TYPE_BUILTIN_MIC,true,"back")
        builtInOnly(bottom,back);actual=back;phone()
        assertTrue(w.await{starts.get()==1});assertArrayEquals(pcm,fill())
        w.app.live.setMuted(true);assertTrue(w.await{releases.get()==1});w.app.live.setMuted(false)
        assertTrue("unmute reopens the same pinned source",w.await{starts.get()==2});verify{record.setPreferredDevice(back)};assertArrayEquals(pcm,fill())
        w.app.live.setMuted(true);assertTrue(w.await{releases.get()==2});actual=bottom;w.app.live.setMuted(false)
        assertTrue("a different built-in after unmute ends input instead of being pinned",w.await{!w.app.live.active})
        assertTrue(w.await{releases.get()==3});assertEquals(3,starts.get());assertTrue(w.fake.prompts.isEmpty())
    }
    @Test fun routedBuiltInThatLeftTheCurrentDeviceSetIsRefusedBeforePcmAndObservedAsInputSetup(){
        val bottom=endpoint(14,AudioDeviceInfo.TYPE_BUILTIN_MIC,true,"bottom");val back=endpoint(16,AudioDeviceInfo.TYPE_BUILTIN_MIC,true,"back")
        var attached=arrayOf(bottom,back)
        w.app.settings.useHeadset=false;every{audio.getDevices(AudioManager.GET_DEVICES_INPUTS)}answers{attached}
        every{record.startRecording()}answers{starts.incrementAndGet();attached=arrayOf(bottom);Unit}
        actual=back;phone()
        assertTrue("the recorder opened and was refused on its actual route",w.await{releases.get()==1})
        assertTrue(w.await{!w.app.live.active});assertEquals(0,reads.get());assertTrue(w.fake.prompts.isEmpty())
        assertEquals("one Live setup observation",1,liveSetupEvents().size)
        assertEquals("live_input",liveSetupEvents().single().stage?.wire);assertEquals("phone",liveSetupEvents().single().origin?.wire)
    }
    @Test fun nonBuiltInRouteForTheOriginalPhoneMicrophoneIsRefusedBeforePcm(){
        val bottom=endpoint(14,AudioDeviceInfo.TYPE_BUILTIN_MIC,true,"bottom");val back=endpoint(16,AudioDeviceInfo.TYPE_BUILTIN_MIC,true,"back")
        builtInOnly(bottom,back);actual=endpoint(30,AudioDeviceInfo.TYPE_USB_HEADSET,true,"");phone()
        assertTrue(w.await{releases.get()==1});assertTrue(w.await{!w.app.live.active});assertEquals(0,reads.get())
        assertEquals("one Live setup observation",1,liveSetupEvents().size);assertEquals("live_input",liveSetupEvents().single().stage?.wire)
    }
    @Test fun noOsBuiltInMicStaysRefusedWithoutARecorderAndTheScreenNamesTheInputStep(){
        builtInOnly();phone()
        assertTrue(w.await{!w.app.live.active});assertEquals(0,starts.get());assertEquals(0,releases.get())
        assertEquals("one Live setup observation",1,liveSetupEvents().size);val e=liveSetupEvents().single()
        assertEquals("live_input",e.stage?.wire);assertEquals("phone",e.origin?.wire);assertNull(e.ref);assertNull(e.status)
        val status=w.app.live.state.value.status
        assertTrue(status,status.contains("opening the microphone")&&status.contains("nothing is retried automatically"))
    }
    @Test fun phoneProviderRejectionIsObservedAtSessionSetupWithTypedStatusAndNoPrivateTextInStatusJournalOrExport(){
        val body=JSONObject().put("detail","SENTINEL-provider-body sk-live-SECRET").toString();val title="Sentinel private title"
        val posts=java.util.concurrent.ConcurrentLinkedQueue<String>()
        val entered=java.util.concurrent.CountDownLatch(1);val held=java.util.concurrent.CountDownLatch(1)
        heldSession(entered,held,posts,502,body)
        kotlinx.coroutines.runBlocking{w.app.wiring().core.sessions.createConversation(title,"sentinel","sentinel things")}
        source=endpoint(11,AudioDeviceInfo.TYPE_BUILTIN_MIC,true,"");actual=source;w.app.settings.useHeadset=false;phone()
        assertTrue("the provider request is in flight",w.await{entered.count==0L})
        // R29: the list reaches the startup request only when it was here in time; the call's own complete catalogue holds it regardless.
        assertTrue("the private title really is this call's own text while its provider request is in flight",
            w.await{posts.single().contains(title)||w.app.live.state.value.choices.any{it.stored.title==title}})
        held.countDown()
        assertTrue(w.await{posts.size==1&&!w.app.live.active})
        val status=w.app.live.state.value.status
        assertTrue(status,status.contains("requesting the Live session")&&status.contains("HTTP 502"))
        assertEquals("one Live setup observation",1,liveSetupEvents().size);val e=liveSetupEvents().single()
        assertEquals("live_session",e.stage?.wire);assertEquals(502,e.status);assertEquals("rejected",e.cause?.wire);assertEquals("phone",e.origin?.wire);assertNull(e.ref)
        val secrets=listOf("SENTINEL","sk-live",title,"PHONE-OWNED","voice-live",w.fake.accessToken,w.fake.refreshToken,w.fake.baseUrl.removeSuffix("/"))
        for(s in secrets)assertFalse("status: $s",status.contains(s))
        val journal=java.io.File(w.app.noBackupFilesDir,"diag-journal")
        fun persisted()=journal.listFiles().orEmpty().filter{it.isFile&&!it.name.endsWith(".tmp")}.joinToString("\n"){runCatching{it.readText()}.getOrDefault("")}
        assertTrue("the observation is persisted",w.await{journalQuiet()&&persisted().contains("live_setup_failed")})
        val stored=persisted();for(s in secrets)assertFalse("journal: $s",stored.contains(s))
        val report=JSONObject((kotlinx.coroutines.runBlocking{w.app.diagnostics.export()} as com.rumi.hermesvoice.core.diag.DiagExportResult.Ready).file.readText())
        val events=report.getJSONObject("phone").getJSONArray("events")
        val live=(0 until events.length()).map{events.getJSONObject(it)}.filter{it.optString("code")=="live_setup_failed"}
        assertEquals(1,live.size);assertEquals("live_session",live[0].getString("stage"));assertEquals(502,live[0].getInt("status"))
        assertEquals("phone",live[0].getString("origin"));assertFalse(live[0].has("ref"))
        val exported=report.toString();for(s in secrets)assertFalse("export: $s",exported.contains(s))
        w.settle();assertEquals("never retried",1,posts.size)
    }
    @Test fun phoneStopDuringTheProviderRequestIsCancellationNotAnObservedSetupFailureAndNeverRetries(){
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val posts=java.util.concurrent.ConcurrentLinkedQueue<String>()
        heldSession(entered,release,posts)
        source=endpoint(11,AudioDeviceInfo.TYPE_BUILTIN_MIC,true,"");actual=source;w.app.settings.useHeadset=false
        try{
            phone();assertTrue("the provider request is in flight",w.await{entered.count==0L})
            w.app.live.end();assertFalse(w.app.live.active)
        }finally{release.countDown()}
        w.settle();assertTrue(w.app.live.state.value.status,w.app.live.state.value.status.startsWith("Live ended"))
        assertTrue(liveSetupEvents().isEmpty());assertEquals("never retried",1,posts.size);assertTrue(w.fake.prompts.isEmpty())
    }

    // Setup exits that end the call from a provider/route callback or the input/admission path, through the real controller, relay and WebRTC adapter.
    @Suppress("UNCHECKED_CAST") private fun <T> wf(name:String)=w.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(w) as T
    private fun providerEvent(json:JSONObject)=wf<org.webrtc.DataChannel.Observer>("eventObserver").onMessage(org.webrtc.DataChannel.Buffer(ByteBuffer.wrap(json.toString().toByteArray()),false))
    private fun builtInPhoneOnly(){source=endpoint(11,AudioDeviceInfo.TYPE_BUILTIN_MIC,true,"");actual=source;w.app.settings.useHeadset=false}
    private fun persistedAndExported():String{
        val journal=java.io.File(w.app.noBackupFilesDir,"diag-journal")
        fun persisted()=journal.listFiles().orEmpty().filter{it.isFile&&!it.name.endsWith(".tmp")}.joinToString("\n"){runCatching{it.readText()}.getOrDefault("")}
        assertTrue("the observation is persisted",w.await{journalQuiet()&&persisted().contains("live_setup_failed")})
        return persisted()+"\n"+(kotlinx.coroutines.runBlocking{w.app.diagnostics.export()} as com.rumi.hermesvoice.core.diag.DiagExportResult.Ready).file.readText()
    }
    private fun iceNeverGathers(){
        val factory=mockk<org.webrtc.PeerConnectionFactory>(relaxed=true);val builder=mockk<org.webrtc.PeerConnectionFactory.Builder>(relaxed=true)
        every{org.webrtc.PeerConnectionFactory.builder()}returns builder;every{builder.setAudioDeviceModule(any())}returns builder;every{builder.createPeerConnectionFactory()}returns factory
        val peer=mockk<org.webrtc.PeerConnection>(relaxed=true)
        every{factory.createPeerConnection(any<org.webrtc.PeerConnection.RTCConfiguration>(),any<org.webrtc.PeerConnection.Observer>())}returns peer
        every{peer.createOffer(any(),any())}answers{firstArg<org.webrtc.SdpObserver>().onCreateSuccess(org.webrtc.SessionDescription(org.webrtc.SessionDescription.Type.OFFER,"v=0\r\nPHONE-OWNED\r\n"))}
        every{peer.setLocalDescription(any(),any())}answers{firstArg<org.webrtc.SdpObserver>().onSetSuccess()} // ICE gathering never completes
    }
    @Test fun phonePreReadyProviderErrorIsObservedOnceAsUnclassifiedStartSetupWithoutPrivateTextAndNeverRetried(){
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1);val posts=java.util.concurrent.ConcurrentLinkedQueue<String>()
        heldSession(entered,release,posts);builtInPhoneOnly()
        try{
            phone();assertTrue("the provider request is in flight",w.await{entered.count==0L});assertFalse(w.app.live.state.value.ready)
            providerEvent(JSONObject().put("type","error").put("error",JSONObject().put("message","SENTINEL-provider-error sk-live-SECRET")))
            assertTrue(w.await{!w.app.live.active})
        }finally{release.countDown()}
        w.settle()
        val status=w.app.live.state.value.status
        assertTrue(status,status.startsWith("Live reported an error; no automatic reconnect"))
        assertEquals("one Live setup observation",1,liveSetupEvents().size);val e=liveSetupEvents().single()
        assertEquals("live_start",e.stage?.wire);assertEquals("unknown",e.fail?.wire);assertEquals("unknown",e.cause?.wire)
        assertEquals("phone",e.origin?.wire);assertNull(e.status);assertNull(e.ref)
        val text=persistedAndExported()+status
        for(s in listOf("SENTINEL","sk-live","PHONE-OWNED",w.fake.accessToken,w.fake.refreshToken))assertFalse(s,text.contains(s))
        w.settle();assertEquals("still one observation after the cancelled request settles",1,liveSetupEvents().size);assertEquals("never retried",1,posts.size)
    }
    @Test fun watchPreReadyIceErrorThroughTheRelayIsObservedOnceAsUnclassifiedStartSetup(){
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1);val posts=java.util.concurrent.ConcurrentLinkedQueue<String>()
        heldSession(entered,release,posts);w.app.settings.useHeadset=false
        try{
            open();assertTrue("the Phone-owned provider request is in flight",w.await{entered.count==0L});assertFalse(w.app.live.state.value.ready)
            wf<org.webrtc.PeerConnection.Observer>("peerObserver").onIceConnectionChange(org.webrtc.PeerConnection.IceConnectionState.FAILED)
            assertTrue(w.await{!w.app.live.active})
        }finally{release.countDown()}
        w.settle()
        assertTrue(w.app.live.state.value.status,w.app.live.state.value.status.startsWith("Live reported an error; no automatic reconnect"))
        assertEquals("one Live setup observation",1,liveSetupEvents().size);val e=liveSetupEvents().single()
        assertEquals("watch",e.origin?.wire);assertEquals("live_start",e.stage?.wire);assertEquals("unknown",e.cause?.wire);assertEquals("unknown",e.fail?.wire)
        val text=persistedAndExported();for(s in listOf("PHONE-OWNED","phone-relay",w.fake.accessToken))assertFalse(s,text.contains(s))
        assertEquals("never retried",1,posts.size);assertEquals(VoiceOrigin.WATCH,w.app.live.state.value.origin)
    }
    @Test fun phoneStartWithUnconfirmedOutputRouteIsObservedOnceAsStartSetupRefusal(){
        builtInPhoneOnly();w.app.settings.saveWatchSettings(w.app.settings.watchSettings().copy(phoneLiveEnabled=true))
        w.app.live.routeFactory={object:LiveRoute{override fun open()=true;override fun valid()=false;override fun close(){}}}
        w.conversation("phone-alpha");assertTrue(w.app.live.start())
        assertTrue(w.await{!w.app.live.active})
        assertTrue(w.app.live.state.value.status,w.app.live.state.value.status.startsWith("Live output route is not confirmed"))
        assertEquals("one Live setup observation",1,liveSetupEvents().size);val e=liveSetupEvents().single()
        assertEquals("live_start",e.stage?.wire);assertEquals("local",e.cause?.wire);assertEquals("refused",e.fail?.wire);assertEquals("phone",e.origin?.wire)
    }
    @Test fun watchProviderIceGatherTimeoutKeepsItsMediaStageAndEndsOnceWithoutRetry(){
        val posts=java.util.concurrent.ConcurrentLinkedQueue<String>()
        heldSession(java.util.concurrent.CountDownLatch(1),java.util.concurrent.CountDownLatch(0),posts);w.app.settings.useHeadset=false;iceNeverGathers();open()
        assertTrue("the provider's own media setup timeout ends Live, not the later relay deadline",w.await(25_000){!w.app.live.active})
        assertEquals("one Live setup observation",1,liveSetupEvents().size);val e=liveSetupEvents().single()
        assertEquals("watch",e.origin?.wire);assertEquals("live_media",e.stage?.wire);assertEquals("timeout",e.cause?.wire);assertEquals("timeout",e.fail?.wire)
        assertTrue(w.app.live.state.value.status,w.app.live.state.value.status.contains("Live setup timed out"))
        w.settle();assertEquals(1,liveSetupEvents().size);assertTrue("the session request was never reached or retried",posts.isEmpty())
    }
    @Test fun stopDuringTheWatchProviderMediaSetupStaysCancellationAndIsNeverRecordedOrResurrected(){
        val posts=java.util.concurrent.ConcurrentLinkedQueue<String>()
        heldSession(java.util.concurrent.CountDownLatch(1),java.util.concurrent.CountDownLatch(0),posts);w.app.settings.useHeadset=false;iceNeverGathers();open()
        assertTrue("provider media setup started",w.await{wf<Any?>("inputCallback")!=null})
        w.app.live.end();assertFalse(w.app.live.active)
        assertFalse("no observation even after the media timeout would have elapsed",w.await(14_000){liveSetupEvents().isNotEmpty()})
        assertFalse(w.app.live.active);assertTrue(w.app.live.state.value.status,w.app.live.state.value.status.startsWith("Live ended"));assertTrue(posts.isEmpty())
    }
    @Test fun phoneInputRouteLossBeforeReadyIsObservedOnceAsInputSetup(){
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1);val posts=java.util.concurrent.ConcurrentLinkedQueue<String>()
        heldSession(entered,release,posts);builtInPhoneOnly()
        try{
            phone();assertTrue(w.await{entered.count==0L});assertFalse(w.app.live.state.value.ready)
            assertArrayEquals(pcm,fill());actual=endpoint(12,AudioDeviceInfo.TYPE_BUILTIN_MIC,true,"")
            assertArrayEquals("no PCM from another route",ByteArray(960),fill())
            assertTrue(w.await{!w.app.live.active})
        }finally{release.countDown()}
        w.settle()
        assertTrue(w.app.live.state.value.status,w.app.live.state.value.status.contains("route lost"))
        assertEquals("one Live setup observation",1,liveSetupEvents().size);val e=liveSetupEvents().single()
        assertEquals("live_input",e.stage?.wire);assertEquals("local",e.cause?.wire);assertEquals("phone",e.origin?.wire)
        assertEquals("never retried",1,posts.size);assertEquals(1,starts.get())
    }
    @Test fun phoneAudioBusyAtAdmissionIsObservedOnceAsRouteSetupRefusal(){
        builtInPhoneOnly()
        // A later reply holds the Phone speaker and does not finish stopping within the admission wait.
        val slot=Class.forName("com.rumi.hermesvoice.core.voice.LaterSlot").getDeclaredConstructor(VoiceOrigin::class.java,kotlinx.coroutines.Job::class.java,java.lang.Boolean.TYPE)
            .apply{isAccessible=true}.newInstance(VoiceOrigin.PHONE,kotlinx.coroutines.Job(),false)
        com.rumi.hermesvoice.core.voice.AudioOwnership::class.java.getDeclaredField("later").apply{isAccessible=true}.set(w.app.audio,slot)
        phone()
        assertTrue(w.await{!w.app.live.active})
        assertTrue(w.app.live.state.value.status,w.app.live.state.value.status.startsWith("Phone audio is busy"))
        assertEquals("one Live setup observation",1,liveSetupEvents().size);val e=liveSetupEvents().single()
        assertEquals("live_route",e.stage?.wire);assertEquals("local",e.cause?.wire);assertEquals("phone",e.origin?.wire)
        assertEquals("the microphone never opened",0,starts.get())
    }
    @Test fun providerErrorAfterReadyIsRuntimeNotASetupObservation(){
        builtInPhoneOnly();phone();assertTrue(w.await{w.app.live.state.value.ready})
        providerEvent(JSONObject().put("type","error"))
        assertTrue(w.await{!w.app.live.active})
        assertTrue(w.app.live.state.value.status,w.app.live.state.value.status.startsWith("Live reported an error"))
        w.settle();assertTrue(liveSetupEvents().isEmpty())
    }
    @Test fun inputRouteLossAfterReadyStaysRuntimeWithoutASetupObservation(){
        builtInPhoneOnly();phone();assertTrue(w.await{w.app.live.state.value.ready})
        assertArrayEquals(pcm,fill());actual=endpoint(12,AudioDeviceInfo.TYPE_BUILTIN_MIC,true,"")
        assertArrayEquals(ByteArray(960),fill());assertTrue(w.await{!w.app.live.active})
        assertTrue(w.app.live.state.value.status,w.app.live.state.value.status.contains("route lost"))
        w.settle();assertTrue(liveSetupEvents().isEmpty())
    }
}
