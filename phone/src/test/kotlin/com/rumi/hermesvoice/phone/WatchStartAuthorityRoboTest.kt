package com.rumi.hermesvoice.phone

import android.content.Context
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.auth.HermesBearerSession
import com.rumi.hermesvoice.core.live.WatchLiveWire
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.wake.*
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.phone.live.*
import io.mockk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** Actual listener/control/capability wait and app revocation; only native/provider effects doubled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class WatchStartAuthorityRoboTest:PhoneRoboBase(){
    private class Setup(message:String):IllegalStateException(message)
    private lateinit var listener:PhoneWatchListenerService
    private lateinit var held:TaskCompletionSource<CapabilityInfo>
    private lateinit var capable:CapabilityInfo
    private val lookups=AtomicInteger()
    private var opens=0;private var connects=0
    private val sent=java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    @Before fun setup(){
        baseSetUp();app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled=false,watchLiveEnabled=true,wakeLocation=WakeLocation.BOTH))
        app.wiring()
        val node=mockk<Node>();every{node.id}returns "watch-authority"
        capable=mockk();every{capable.nodes}returns setOf(node)
        held=TaskCompletionSource()
        val capabilities=mockk<CapabilityClient>()
        every{capabilities.getCapability(any(),any())}answers{lookups.incrementAndGet();held.task}
        every{Wearable.getCapabilityClient(any<Context>())}returns capabilities
        val messages=mockk<MessageClient>()
        every{messages.sendMessage(any(),any(),any())}answers{sent+=thirdArg<ByteArray>();Tasks.forResult(1)}
        every{Wearable.getMessageClient(any<Context>())}returns messages
        app.live.watchRouteFactory={object:LiveRoute{override fun open():Boolean{opens++;return true};override fun valid()=true;override fun close(){}}}
        app.live.watchTransportFactory={_,_->object:LiveTransport{
            override suspend fun connect(history:JSONArray,event:(String)->Unit){connects++;event("{\"type\":\"session.started\",\"session\":{\"id\":\"authority-fixture\"}}")}
            override fun send(json:String)=true;override fun mute(muted:Boolean){};override fun output(enabled:Boolean){};override fun silence(){};override fun close(){}
        }}
        app.live.admission={app.live.admitted(it)}
        listener=Robolectric.buildService(PhoneWatchListenerService::class.java).create().get()
    }
    @After fun cleanup(){app.live.end();settle();baseTearDown()}
    private fun control(op:String="start",id:String="pending-authority",enabled:Boolean?=null,claim:String?=null){
        val bytes=JSONObject().put("v",1).put("id",id).put("op",op).apply{if(enabled!=null)put("enabled",enabled);if(claim!=null){put("wake",true);put("wake_claim_id",claim);put("wake_revision",app.settings.watchSettings().revision)}}.toString().toByteArray()
        val event=mockk<MessageEvent>();every{event.path}returns WatchLinkPaths.LIVE_CONTROL
        every{event.sourceNodeId}returns "watch-authority";every{event.data}returns bytes
        listener.onMessageReceived(event)
    }
    private fun pending(){control();if(!await{lookups.get()>0})throw Setup("real capability Task not awaited");if(app.live.active)throw Setup("start bypassed held capability")}
    private fun login(user:String){
        val origin=app.asyncActionOrigin(app.wiring())
        if(!app.completeNativeSignIn(origin,HermesBearerSession(fake.accessToken,fake.refreshToken,null,"basic",user)))throw Setup("real app login refused")
    }
    private fun release(){held.setResult(capable);settle()}
    private fun refused(){release();assertFalse("a revoked pre-await Watch Start cannot bind current authority",app.live.active);assertEquals(0,opens);assertEquals(0,connects);assertTrue(fake.prompts.isEmpty())}
    @Test fun accountSwitchDuringCapabilityWaitCannotStartUnderNewAccount(){pending();login("other");refused()}
    @Test fun accountRoundTripCannotRestoreOldAdmission(){pending();login("other");login("jun");refused()}
    @Test fun signOutAndSameAccountLoginCannotRestoreOldAdmission(){pending();PhoneViewModel(app).signOut();login("jun");refused()}
    @Test fun explicitRevocationWithoutChangingAccountCannotRestoreOldAdmission(){pending();app.revokeUnplayedReplies();app.wiring();refused()}
    @Test fun explicitEndWhileIdleRevokesPendingStart(){pending();app.live.end();refused()}
    @Test fun watchEndOvertakesPendingStartWithoutWaitingForItsCapability(){pending();control("end");refused()}
    @Test fun watchOffOvertakesPendingStartAndOnDoesNotRestoreIt(){pending();control("mode",enabled=false);release();app.settings.saveWatchSettings(app.settings.watchSettings().copy(watchLiveEnabled=true));app.live.settingsChanged();settle();assertFalse("OFF received while waiting must revoke admission before late Task success",app.live.active);assertEquals(0,opens)}
    @Test fun settingsOffOnRoundTripCannotRestorePendingStart(){pending();app.settings.saveWatchSettings(app.settings.watchSettings().copy(watchLiveEnabled=false));app.live.settingsChanged();app.settings.saveWatchSettings(app.settings.watchSettings().copy(watchLiveEnabled=true));app.live.settingsChanged();refused()}
    @Test fun unchangedAccountStartsOnceAndNeedsNoPhoneMicrophoneOrPhoneLive(){pending();release();assertTrue(await{app.live.state.value.ready});assertEquals(1,opens);assertEquals(1,connects);assertFalse(app.settings.watchSettings().phoneLiveEnabled);control();settle();assertEquals(1,connects)}
    @Test fun newIntentAfterRevocationCanStartButOldControlRemainsRetired(){pending();app.live.end();release();val before=connects;control(id="new-authority");assertTrue(await{connects>before});app.live.end();settle();control();settle();assertFalse(app.live.active);assertEquals(before+1,connects)}
    @Test fun capabilityFailureCannotAdmitOrCreateMedia(){pending();held.setException(IllegalStateException("synthetic capability failure"));settle();assertFalse(app.live.active);assertEquals(0,connects)}
    private fun wakeClaim():Pair<com.rumi.hermesvoice.core.wake.WakeAdmission,Long>{
        val admission=app.wiring().core.wakeAdmission;val epoch=admission.epoch
        if(admission.claim(WakeClaim("authority-wake",VoiceOrigin.WATCH,"watch-authority",app.settings.watchSettings().revision,1,epoch))!=ClaimVerdict.GRANTED)throw Setup("wake claim not granted")
        return admission to epoch
    }
    @Test fun duplicatePendingWatchWakeCannotReleaseTheOriginalForegroundClaim(){
        val(admission,epoch)=wakeClaim();var token=0L;app.live.admission={token=it}
        control(claim="authority-wake");control(claim="authority-wake")
        if(!await{lookups.get()==2})throw Setup("both real capability waits not reached")
        release();assertTrue(app.live.active);assertEquals(0,opens)
        assertEquals("a duplicate status-only start cannot release the original pending OS claim",VoiceOrigin.WATCH,admission.holder())
        app.live.admitted(token);assertTrue(await{app.live.state.value.ready});assertEquals(epoch+1,admission.epoch);assertEquals(1,connects)
        assertEquals(ClaimVerdict.USED,admission.renew("authority-wake",VoiceOrigin.WATCH,"watch-authority"))
    }
    @Test fun revokedPendingWatchWakeReleasesOnlyUnadmittedClaim(){
        val(admission,epoch)=wakeClaim();control(claim="authority-wake")
        if(!await{lookups.get()>0})throw Setup("wake capability wait not reached")
        app.live.end();refused();assertEquals(epoch,admission.epoch)
        assertEquals(ClaimVerdict.GRANTED,admission.claim(WakeClaim("peer-after-authority",VoiceOrigin.PHONE,"",app.settings.watchSettings().revision,1,epoch)))
    }
}
