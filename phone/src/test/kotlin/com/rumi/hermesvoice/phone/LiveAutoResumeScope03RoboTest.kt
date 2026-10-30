package com.rumi.hermesvoice.phone

import android.Manifest
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.phone.live.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class LiveAutoResumeScope03RoboTest:PhoneRoboBase(){
    private var now=1000L
    private val media=ArrayList<Media>()
    private class Media:LiveTransport{
        var receive:((String)->Unit)?=null;var activity:((Boolean)->Unit)?=null;var mic:((Boolean)->Unit)?=null
        var silenced=false;val packets=java.util.concurrent.ConcurrentLinkedQueue<String>()
        override fun observeActivity(listener:(Boolean)->Unit){activity=listener}
        override fun observeMicrophone(listener:(Boolean)->Unit){mic=listener}
        override suspend fun connect(history:JSONArray,event:(String)->Unit){receive=event;mic?.invoke(true);event("{\"type\":\"session.started\",\"session\":{\"id\":\"new-provider\"}}")}
        override fun send(json:String):Boolean{packets+=json;return true}
        override fun mute(muted:Boolean){mic?.invoke(!muted)};override fun output(enabled:Boolean){};override fun silence(){silenced=true;mic?.invoke(false)};override fun close(){}
    }
    @Before fun setUp(){
        baseSetUp();shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.live.monotonicMs={now}
        app.live.transportFactory={Media().also{media+=it}}
        app.live.routeFactory={object:LiveRoute{override fun open()=true;override fun valid()=true;override fun close(){}}}
        app.live.admission={app.live.admitted(it)}
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled=true))
    }
    @After fun tearDown(){app.live.end();settle();baseTearDown()}
    private fun ready(target:String){assertTrue(app.live.start());assertTrue(await{app.live.state.value.ready&&app.live.state.value.inputActive});app.live.bindSession(target);assertTrue(await{app.live.state.value.boundSessionId==target})}
    private fun idleLive(){now+=app.settings.watchSettings().liveIdleSeconds*1000;app.live.checkLifetime();assertFalse(app.live.active);assertEquals("idle",app.live.state.value.endReason)}
    private fun arrival(target:String,uid:String){
        fake.injectFinalRow(target,"late-$uid",1_800_000_000.0,uid)
        val done=java.util.concurrent.atomic.AtomicBoolean(false)
        app.appScope.launch { try { app.finalRecovery.reconcile() } finally { done.set(true) } }
        assertTrue("actual canonical reconciliation returned",await{done.get()})
    }

    @Test fun actualCanonicalRecoveryAfterIdleReopensLiveAndMicOnceAndAllowsImmediateNewReply(){
        val target=conversation("alpha");ready(target);idleLive();arrival(target,"one")
        assertTrue(await{media.size==2&&app.live.state.value.ready&&app.live.state.value.inputActive&&app.live.state.value.boundSessionId==target})
        assertTrue(await{media.last().packets.any{it.contains("late-one")&&JSONObject(it).optString("type")=="session.commentary.append"}})
        assertTrue(media.last().packets.filter{JSONObject(it).optString("type")=="session.commentary.append"}.all{JSONObject(it).isNull("delegation_id")})
        app.finalRecovery.requestReconcile();settle();assertEquals(2,media.size);assertTrue(fake.prompts.isEmpty())
        media[0].receive!!("{\"type\":\"session.delegation.created\",\"offset_ms\":500,\"delegation\":{\"id\":\"old\",\"type\":\"delegation\",\"target\":\"client\"}}")
        media.last().receive!!("{\"type\":\"session.input_transcript.delta\",\"delta\":\"follow up\",\"start_ms\":0,\"end_ms\":100}")
        media.last().receive!!("{\"type\":\"session.delegation.created\",\"offset_ms\":200,\"delegation\":{\"id\":\"new\",\"type\":\"delegation\",\"target\":\"client\"}}")
        // PHONE-R2-01 migration: the resumed bound conversation takes the immediate spoken reply without a Phone tap; the
        // optional manual control can no longer resend it. The retired provider's "old" delegation still submits nothing.
        assertTrue(await{fake.prompts.size==1});assertEquals(target,fake.prompts.single().first)
        app.live.confirm("new",target,"follow up");settle();assertEquals(1,fake.prompts.size)
        assertEquals("follow up",fake.prompts.single().second);assertEquals(0,fake.speakRequests.get())
    }
    @Test fun explicitEndAndOffAfterIdleRevokeLateMicrophonePermission(){
        val target=conversation("alpha");ready(target);idleLive();app.live.end();arrival(target,"explicit");settle();assertEquals(1,media.size)
        ready(target);idleLive();PhoneViewModel(app).setLiveMode(com.rumi.hermesvoice.core.VoiceOrigin.PHONE,false)
        arrival(target,"off");settle();assertEquals(2,media.size);assertFalse(app.live.active)
    }
    @Test fun actualPermissionAndBackgroundAdmissionRefusalsKeepFinalWithoutFallbackOrRetry(){
        val target=conversation("alpha");ready(target);idleLive();shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        arrival(target,"denied");assertTrue(await{app.live.state.value.status.contains("Late answer saved")});assertEquals(1,media.size);assertFalse(app.phoneMic.occupied)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);ready(target);idleLive()
        app.live.admission={throw IllegalStateException("synthetic OS background admission refusal")}
        arrival(target,"background");assertTrue(await{app.live.state.value.status.contains("Late answer saved")});assertEquals(2,media.size);assertFalse(app.phoneMic.occupied)
        app.finalRecovery.requestReconcile();settle();assertEquals(2,media.size);assertTrue(fake.prompts.isEmpty());assertEquals(0,fake.speakRequests.get())
    }
    @Test fun actualNavigationAndLogoutFencesRejectStaleBoundReply(){
        val target=conversation("alpha");val other=conversation("other");ready(target);idleLive()
        val model=PhoneViewModel(app);model.openOrResume(app.wiring().core.sessions.registry.find(other)!!)
        assertTrue(await{app.selectedConversationId==other});arrival(target,"wrong-conversation");settle();assertEquals(1,media.size)
        app.selectedConversationId=null;ready(target);idleLive();app.tokens.clear();app.invalidateSessionTitles();arrival(target,"old-auth");settle();assertEquals(2,media.size);assertFalse(app.live.active)
    }
    @Test fun oneSecondIdleGraceBeginsAtActualSessionStartedNotDuringNegotiation(){
        conversation("alpha");PhoneViewModel(app).setLiveIdleSeconds("1")
        var event:((String)->Unit)?=null
        app.live.transportFactory={object:LiveTransport{
            override suspend fun connect(history:JSONArray,receiver:(String)->Unit){event=receiver}
            override fun send(json:String)=true;override fun mute(muted:Boolean){};override fun output(enabled:Boolean){};override fun silence(){};override fun close(){}
        }}
        assertTrue(app.live.start());assertTrue(await{event!=null})
        now+=5000;app.live.checkLifetime()
        assertTrue("a connecting call has an independent deadline, not a one-second dialogue timeout",app.live.active)
        assertFalse(app.live.state.value.ready)
        event!!("{\"type\":\"session.started\",\"session\":{\"id\":\"p\"}}")
        assertTrue(await{app.live.state.value.ready})
        now+=999;app.live.checkLifetime();assertTrue(app.live.active)
        now++;app.live.checkLifetime();assertFalse(app.live.active);assertEquals("idle",app.live.state.value.endReason)
    }
    @Test fun actualEditableGraceUpdatesOneRuntimeTimerAndSpeechOutlastsGrace(){
        val target=conversation("alpha");val model=PhoneViewModel(app)
        model.setLiveIdleSeconds("60");assertEquals(60,app.settings.watchSettings().liveIdleSeconds);assertTrue(media.isEmpty())
        ready(target);now+=59999;app.live.checkLifetime();assertTrue(app.live.active)
        model.setLiveIdleSeconds("1");app.live.checkLifetime();assertFalse(app.live.active);assertEquals(1,media.size)
        ready(target)
        repeat(20){now+=900;media.last().activity!!(true);app.live.checkLifetime();assertTrue(app.live.active)}
        now+=1000;app.live.checkLifetime();assertFalse(app.live.active);assertEquals(2,media.size)
        app.live.checkLifetime();assertEquals(2,media.size)
    }
}
