package com.rumi.hermesvoice.phone

import android.Manifest
import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.wake.*
import com.rumi.hermesvoice.core.settings.*
import com.rumi.hermesvoice.core.watchlink.*
import com.rumi.hermesvoice.core.voice.*
import com.rumi.hermesvoice.phone.live.*
import io.mockk.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Real Phone wake -> Live controller -> shared coordinator -> delayed legacy Watch intake.
 * Media/provider boundaries are deterministic adapters; wake admission and legacy intake are real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class LiveWakeAdmission604RoboTest:PhoneRoboBase(){
    private class Setup(message:String):IllegalStateException(message)
    private var connects=0
    private var opens=0
    private var token=0L
    private val admission get()=app.wiring().core.wakeAdmission
    private val epoch get()=admission.epoch
    @Before fun setup(){
        baseSetUp();shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.settings.saveWatchSettings(WatchSettings(wakeLocation=WakeLocation.BOTH,phoneLiveEnabled=true,watchLiveEnabled=false))
        val route={object:LiveRoute{override fun open():Boolean{opens++;return true};override fun valid()=true;override fun close(){}}}
        app.live.routeFactory={route()};app.live.watchRouteFactory=route
        val transport={object:LiveTransport{
            override suspend fun connect(history:JSONArray,event:(String)->Unit){connects++;event("{\"type\":\"session.started\",\"session\":{\"id\":\"native-fixture-604\"}}")}
            override fun send(json:String)=true;override fun mute(muted:Boolean){};override fun output(enabled:Boolean){};override fun silence(){};override fun close(){}
        }}
        app.live.transportFactory={transport()};app.live.watchTransportFactory={_,_->transport()}
        app.live.admission={token=it;app.live.admitted(it)}
        val node=mockk<Node>();every{node.id}returns "watch-604"
        val caps=mockk<CapabilityInfo>();every{caps.nodes}returns setOf(node)
        val client=mockk<CapabilityClient>();every{client.getCapability(any(),any())}returns Tasks.forResult(caps)
        every{Wearable.getCapabilityClient(any<Context>())}returns client
        fake.turnBehavior={"session_limit"}
    }
    @After fun teardown(){app.live.end();settle();baseTearDown()}
    private fun claim(id:String="phone-604",origin:VoiceOrigin=VoiceOrigin.PHONE,node:String="",window:Long=epoch):String{
        val verdict=admission.claim(WakeClaim(id,origin,node,app.settings.watchSettings().revision,1,window))
        if(verdict!=ClaimVerdict.GRANTED)throw Setup("claim setup: $verdict")
        return id
    }
    private fun background():WakeDevicePort=PhoneBackgroundRuntime::class.java.getDeclaredField("devicePort").apply{isAccessible=true}.get(app.phoneWake) as WakeDevicePort
    private fun start(background:Boolean,phrase:Boolean,id:String){
        if(background){if(phrase){if(!background().startRequestCapture(1000,id))throw Setup("background start refused") }else background().sendRecognized("same spoken episode",id)}
        else {val vm=PhoneViewModel(app);if(phrase){if(!vm.startHandsFree(1000,id))throw Setup("foreground start refused")}else vm.sendRecognizedRequest("same spoken episode",id)}
    }
    /** Cancel only after actual legacy acceptance was observed (or actual refusal returned).
     * This makes the native RED bounded without asking a fake backend to finish an accepted turn.
     */
    private fun legacyWatchAccepted(id:String):Boolean=runBlocking{
        val accepted=CompletableDeferred<Boolean>()
        val upload=WatchTurnUpload("late-watch-604",TurnTrigger.WAKE_PHRASE,WatchTurnUpload.MIME_TEXT,"same spoken episode".toByteArray(),id)
        val transport=object:WatchTransport{
            override val nodeId="watch-604"
            override suspend fun sendMessage(path:String,bytes:ByteArray){if(path==WatchLinkPaths.STATE && TurnStateMessage.decode(bytes)?.stage=="accepted")accepted.complete(true)}
            override suspend fun sendChannel(path:String,bytes:ByteArray){}
        }
        val job=launch(Dispatchers.IO){try{app.wiring().core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath(upload.turnId),upload.toFrame().encode(),transport);accepted.complete(false)}catch(c:CancellationException){throw c}}
        try{withTimeout(8000){accepted.await()}}finally{job.cancelAndJoin()}
    }
    private fun winner(background:Boolean,phrase:Boolean){
        val e=epoch;val id=claim();start(background,phrase,id)
        if(!await{app.live.state.value.ready})throw Setup("Live did not become ready")
        val peer=admission.claim(WakeClaim("late-watch-claim",VoiceOrigin.WATCH,"watch-604",app.settings.watchSettings().revision,1,e))
        val duplicateAccepted=legacyWatchAccepted("late-watch-claim")
        assertFalse("one Both episode already admitted to actual Phone Live cannot be accepted by real delayed Watch legacy intake",duplicateAccepted)
        assertNotEquals(ClaimVerdict.GRANTED,peer);assertEquals(e+1,epoch);assertEquals(1,connects)
        assertEquals(ClaimVerdict.USED,admission.renew(id,VoiceOrigin.PHONE,""))
    }
    @Test fun foregroundRecognizedLiveWinnerRejectsDelayedRealLegacySameEpoch()=winner(false,false)
    @Test fun foregroundPhraseOnlyLiveWinnerRejectsDelayedRealLegacySameEpoch()=winner(false,true)
    @Test fun backgroundRecognizedLiveWinnerRejectsDelayedRealLegacySameEpoch()=winner(true,false)
    @Test fun backgroundPhraseOnlyLiveWinnerRejectsDelayedRealLegacySameEpoch()=winner(true,true)
    @Test fun invalidClaimHasNoMediaProviderOrLegacyEffects(){
        PhoneViewModel(app).sendRecognizedRequest("invalid", "not-held-604");settle()
        assertFalse(app.live.active);assertEquals(0,opens);assertEquals(0,connects);assertTrue(fake.prompts.isEmpty())
    }
    @Test fun peerConsumedClaimRejectsLatePhoneLiveBeforeEffects(){
        val id=claim("watch-first",VoiceOrigin.WATCH,"watch-604")
        assertNull(admission.admitTurn(true,VoiceOrigin.WATCH,"watch-604",id))
        PhoneViewModel(app).sendRecognizedRequest("late", "phone-old");settle()
        assertEquals(0,opens);assertEquals(0,connects);assertFalse(app.live.active)
    }
    @Test fun permissionRefusalReleasesOnlyUnadmittedClaimForPeer(){
        val e=epoch;val id=claim();shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        PhoneViewModel(app).sendRecognizedRequest("refused",id)
        assertEquals(e,epoch);assertEquals(ClaimVerdict.GRANTED,admission.claim(WakeClaim("peer-after-refusal",VoiceOrigin.WATCH,"watch-604",app.settings.watchSettings().revision,1,e)))
        assertEquals(0,connects)
    }
    @Test fun stopBeforeForegroundAdmissionReleasesClaimAndStaleCallbackCannotConsume(){
        val e=epoch;val id=claim();app.live.admission={token=it}
        PhoneViewModel(app).sendRecognizedRequest("pending",id);app.live.end();app.live.admitted(token);settle()
        assertEquals(e,epoch);assertEquals(0,opens);assertEquals(ClaimVerdict.GRANTED,admission.claim(WakeClaim("peer-after-stop",VoiceOrigin.WATCH,"watch-604",app.settings.watchSettings().revision,1,e)))
    }
    @Test fun settingChangeDuringForegroundAdmissionRefusesBeforeMedia(){
        val e=epoch;val id=claim();app.live.admission={token=it}
        PhoneViewModel(app).sendRecognizedRequest("pending",id)
        app.settings.saveWatchSettings(app.settings.watchSettings().copy(wakeLocation=WakeLocation.WATCH))
        app.live.admitted(token);settle()
        assertFalse(app.live.active);assertEquals(e,epoch);assertEquals(0,opens);assertEquals(0,connects)
    }
    @Test fun offDuringForegroundAdmissionCannotConsumeOrOpenMedia(){
        val e=epoch;val id=claim();app.live.admission={token=it}
        PhoneViewModel(app).sendRecognizedRequest("pending",id)
        app.settings.saveWatchSettings(app.settings.watchSettings().copy(phoneLiveEnabled=false));app.live.settingsChanged();app.live.admitted(token);settle()
        assertEquals(e,epoch);assertEquals(0,opens);assertFalse(app.live.active)
    }
    @Test fun acceptedThenHangupNeverReleasesOldEpisode(){
        val e=epoch;val id=claim();start(false,false,id);assertTrue(await{app.live.state.value.ready});app.live.end()
        assertEquals(e+1,epoch);assertNotEquals(ClaimVerdict.GRANTED,admission.claim(WakeClaim("peer-after-hangup",VoiceOrigin.WATCH,"watch-604",app.settings.watchSettings().revision,1,e)))
        PhoneViewModel(app).sendRecognizedRequest("old",id);settle();assertEquals(1,connects)
    }
    @Test fun providerFailureAfterAdmissionDoesNotMakeEpisodeReusable(){
        val e=epoch;val id=claim();app.live.transportFactory={object:LiveTransport{
            override suspend fun connect(history:JSONArray,event:(String)->Unit){connects++;throw IllegalStateException("synthetic media failure")}
            override fun send(json:String)=false;override fun mute(muted:Boolean){};override fun output(enabled:Boolean){};override fun silence(){};override fun close(){}
        }}
        start(false,false,id);assertTrue(await{connects==1&&!app.live.active})
        assertEquals(e+1,epoch);assertNotEquals(ClaimVerdict.GRANTED,admission.claim(WakeClaim("after-failure",VoiceOrigin.WATCH,"watch-604",app.settings.watchSettings().revision,1,e)));assertTrue(fake.prompts.isEmpty())
    }
    @Test fun logoutDuringForegroundAdmissionRefusesBeforeMedia(){
        val e=epoch;val id=claim();app.live.admission={token=it}
        PhoneViewModel(app).sendRecognizedRequest("pending",id);app.tokens.clear();app.invalidateSessionTitles()
        app.live.admitted(token);settle()
        assertFalse(app.live.active);assertEquals(e,epoch);assertEquals(0,opens);assertEquals(0,connects)
    }
    @Test fun expiredClaimAtForegroundCallbackCannotOpenMedia(){
        val now=java.util.concurrent.atomic.AtomicLong(1000)
        val clock:()->Long={now.get()}
        WakeAdmission::class.java.getDeclaredField("clock").apply{isAccessible=true}.set(admission,clock)
        val e=epoch;val id=claim();app.live.admission={token=it}
        PhoneViewModel(app).sendRecognizedRequest("pending",id)
        now.addAndGet(WakeContract.CLAIM_TTL_MS+1)
        if(admission.holder()!=null)throw Setup("clock adapter did not expire actual coordinator lease")
        app.live.admitted(token);settle()
        assertFalse(app.live.active);assertEquals(e,epoch);assertEquals(0,opens);assertEquals(0,connects)
    }
    @Test fun duplicatePendingWakeDoesNotReleaseTheOriginalClaim(){
        val e=epoch;val id=claim();app.live.admission={token=it}
        PhoneViewModel(app).sendRecognizedRequest("pending",id)
        PhoneViewModel(app).sendRecognizedRequest("duplicate",id)
        assertEquals(VoiceOrigin.PHONE,admission.holder())
        app.live.admitted(token);assertTrue(await{app.live.state.value.ready});assertEquals(e+1,epoch);assertEquals(1,connects)
    }
    @Test fun oldSettingsClaimCannotBeReusedUnderNewLiveSettings(){
        val e=epoch;val id=claim()
        app.settings.saveWatchSettings(app.settings.watchSettings().copy(liveIdleSeconds=11))
        PhoneViewModel(app).sendRecognizedRequest("old settings",id);settle()
        assertFalse(app.live.active);assertEquals(e,epoch);assertEquals(0,opens);assertEquals(0,connects)
    }
    @Test fun manualTalkInBothNeedsNoClaimAndDoesNotConsumeWakeEpisode(){
        val e=epoch;assertTrue(app.live.start());assertTrue(await{app.live.state.value.ready});assertEquals(e,epoch)
    }
    private fun watchStart(id:String,claim:String,node:String="watch-604"){
        app.liveWatch.receive(node,JSONObject().put("v",1).put("id",id).put("op","start").put("wake",true).put("wake_claim_id",claim).put("recognized","watch wake words").toString().toByteArray())
    }
    @Test fun repeatedForegroundAdmissionCallbackConsumesAndOpensMediaOnce(){
        val e=epoch;val id=claim();app.live.admission={token=it}
        PhoneViewModel(app).sendRecognizedRequest("pending",id)
        app.live.admitted(token);app.live.admitted(token)
        assertTrue(await{app.live.state.value.ready});assertEquals(e+1,epoch);assertEquals(1,opens);assertEquals(1,connects)
    }
    @Test fun settingsChangeAcrossRealWatchCapabilityWaitCannotAdmitOldWake(){
        app.settings.saveWatchSettings(app.settings.watchSettings().copy(watchLiveEnabled=true))
        val coordinator=admission;val e=epoch;val id=claim("held-watch",VoiceOrigin.WATCH,"watch-604")
        val held=com.google.android.gms.tasks.TaskCompletionSource<CapabilityInfo>()
        val requested=java.util.concurrent.atomic.AtomicBoolean(false)
        val client=mockk<CapabilityClient>();every{client.getCapability(any(),any())}answers{requested.set(true);held.task}
        every{Wearable.getCapabilityClient(any<Context>())}returns client
        watchStart("held-control",id);assertTrue(await{requested.get()})
        app.settings.saveWatchSettings(app.settings.watchSettings().copy(liveIdleSeconds=11))
        val node=mockk<Node>();every{node.id}returns "watch-604"
        val info=mockk<CapabilityInfo>();every{info.nodes}returns setOf(node);held.setResult(info);settle()
        assertFalse(app.live.active);assertEquals(e,coordinator.epoch);assertEquals(0,opens);assertEquals(0,connects)
    }
    @Test fun watchLiveWinnerConsumesExactTransportClaimWithPhoneModeOff(){
        app.settings.saveWatchSettings(app.settings.watchSettings().copy(phoneLiveEnabled=false,watchLiveEnabled=true))
        val e=epoch;val id=claim("watch-live-604",VoiceOrigin.WATCH,"watch-604");watchStart("watch-control",id)
        assertTrue(await{app.live.state.value.ready});assertEquals(e+1,epoch);assertEquals(VoiceOrigin.WATCH,app.live.state.value.origin)
        assertNotEquals(ClaimVerdict.GRANTED,admission.claim(WakeClaim("phone-late",VoiceOrigin.PHONE,"",app.settings.watchSettings().revision,1,e)))
    }
    @Test fun watchLiveWrongTransportClaimNeverOpensMedia(){
        app.settings.saveWatchSettings(app.settings.watchSettings().copy(watchLiveEnabled=true))
        claim("watch-wrong-node",VoiceOrigin.WATCH,"another-watch");watchStart("bad-control","watch-wrong-node");settle()
        assertFalse(app.live.active);assertEquals(0,opens);assertEquals(0,connects)
    }
    @Test fun dualLiveDelayedWatchCannotReuseConsumedPhoneEpisodeAfterHangup(){
        app.settings.saveWatchSettings(app.settings.watchSettings().copy(watchLiveEnabled=true))
        val e=epoch;val id=claim();start(false,false,id);assertTrue(await{app.live.state.value.ready});app.live.end()
        admission.claim(WakeClaim("dual-watch",VoiceOrigin.WATCH,"watch-604",app.settings.watchSettings().revision,1,e))
        watchStart("dual-control","dual-watch");settle();assertEquals(1,connects);assertFalse(app.live.active)
    }
}
