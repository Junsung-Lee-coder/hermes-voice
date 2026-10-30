package com.rumi.hermesvoice.phone

import android.Manifest
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.speech.SpeechRecognizer
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.settings.*
import com.rumi.hermesvoice.core.wake.*
import com.rumi.hermesvoice.phone.live.*
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowSpeechRecognizer

/** Actual armed background presence -> onResults -> onHandoffDue -> Live -> automatic idle.
 * Only provider/media/OS recognizer inputs are synthetic; no capture state is cleared by the test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@LooperMode(LooperMode.Mode.PAUSED)
class LiveWakeLifecycle606RoboTest:PhoneRoboBase(){
    private class Setup(message:String):IllegalStateException(message)
    private var service:org.robolectric.android.controller.ServiceController<PhoneWakeService>?=null
    private var connects=0
    private var token=0L
    private val admission get()=app.wiring().core.wakeAdmission
    @Before fun setup(){
        baseSetUp();shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS)
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        app.settings.saveWatchSettings(WatchSettings(wakeLocation=WakeLocation.BOTH,wakePatterns="루미",phoneLiveEnabled=true,
            phoneBackgroundWakeEnabled=true,phoneBackgroundWakeScreenOffEnabled=true,liveIdleSeconds=2))
        // The actual coordinator uses wall time by default. Bind its lease clock to the Android
        // clock driven below, not a different fake clock whose expiry never reaches production.
        WakeAdmission::class.java.getDeclaredField("clock").apply{isAccessible=true}.set(admission,{SystemClock.elapsedRealtime()})
        app.live.monotonicMs={SystemClock.elapsedRealtime()}
        app.live.routeFactory={object:LiveRoute{override fun open()=true;override fun valid()=true;override fun close(){}}}
        app.live.transportFactory={object:LiveTransport{
            override suspend fun connect(history:JSONArray,event:(String)->Unit){connects++;event("{\"type\":\"session.started\",\"session\":{\"id\":\"lifecycle606\"}}")}
            override fun send(json:String)=true;override fun mute(muted:Boolean){};override fun output(enabled:Boolean){};override fun silence(){};override fun close(){}
        }}
        app.live.admission={token=it;app.live.admitted(it)}
        app.phoneWake.onAppShown();idle(50)
        val intent=shadowOf(app).nextStartedService?:throw Setup("standby service request missing")
        if(intent.component?.className!=PhoneWakeService::class.java.name)throw Setup("wrong standby service")
        service=Robolectric.buildService(PhoneWakeService::class.java,intent).create().startCommand(0,1)
        app.phoneWake.onAppHidden();idle(800)
        if(!await{app.phoneWake.listening})throw Setup("actual microphone-approved background did not arm")
    }
    @After fun cleanup(){app.live.end();runCatching{app.phoneWake.stop()};runCatching{service?.destroy()};baseTearDown()}
    private fun phrase(){
        val recognizer=ShadowSpeechRecognizer.getLatestSpeechRecognizer()?:throw Setup("recognizer absent")
        shadowOf(recognizer).triggerOnResults(Bundle().apply{putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,arrayListOf("루미"))})
        idle(350)
    }
    private fun ready(){phrase();if(!await{app.live.state.value.ready})throw Setup("actual phrase-only Live not ready")}
    private fun idleEnd(){idle(2200);if(!await{!app.live.active})throw Setup("automatic Live idle did not end")}
    @Test fun automaticIdleRearmsAndSecondDeliberatePhraseConsumesNewEpoch(){
        val first=admission.epoch;ready();assertEquals(first+1,admission.epoch)
        assertFalse(app.phoneWake.capturing);assertFalse(app.phoneWake.listening)
        idleEnd()
        assertTrue("normal ended Live must settle the matching shared handoff so armed background receives the next wake",await{app.phoneWake.listening})
        phrase();assertTrue(await{app.live.state.value.ready});assertEquals(2,connects);assertEquals(first+2,admission.epoch)
        assertNotEquals(ClaimVerdict.GRANTED,admission.claim(WakeClaim("delayed-peer",VoiceOrigin.WATCH,"watch",app.settings.watchSettings().revision,1,first)))
    }
    @Test fun activeAndPendingLiveStayBusyWithoutConsumingADuplicate(){
        app.live.admission={token=it};phrase();assertTrue(app.live.active);val e=admission.epoch
        idle(300);assertFalse(app.phoneWake.listening);app.live.admitted(token)
        assertTrue(await{app.live.state.value.ready});val accepted=admission.epoch
        idle(300);assertFalse(app.phoneWake.listening);assertEquals(e+1,accepted);assertEquals(accepted,admission.epoch);assertEquals(1,connects)
    }
    @Test fun stopAndLateAdmissionCannotResurrectBackground(){
        app.live.admission={token=it};phrase();app.phoneWake.stop();app.live.end();app.live.admitted(token);idle(2500)
        assertFalse(app.phoneWake.listening);assertFalse(app.live.active);assertEquals(0,connects);assertTrue(fake.rawPrompts.isEmpty())
    }
    @Test fun permissionRevocationAndStandbyOffCannotBeReopenedByEndedLive(){
        ready();shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        app.phoneWake.onEligibilityChanged();app.live.end();idle(2200)
        assertFalse(app.phoneWake.listening)
        app.settings.saveWatchSettings(app.settings.watchSettings().copy(phoneBackgroundWakeEnabled=false))
        app.phoneWake.standbyOff();shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);app.phoneWake.onEligibilityChanged();idle(100)
        assertFalse(app.phoneWake.listening);assertEquals(1,connects)
    }
    @Test fun logoutAndStaleAdmissionCannotReopenOrSubmit(){
        app.live.admission={token=it};phrase();PhoneViewModel(app).signOut();app.live.admitted(token);idle(2500)
        assertFalse(app.phoneWake.listening);assertFalse(app.live.active);assertEquals(0,connects);assertTrue(fake.rawPrompts.isEmpty())
    }
    @Test fun explicitEndSettlesCaptureWithoutReturningConsumedClaim(){
        val e=admission.epoch;ready();app.live.end();assertTrue(await{app.phoneWake.listening})
        assertNotEquals(ClaimVerdict.GRANTED,admission.claim(WakeClaim("old-peer",VoiceOrigin.WATCH,"watch",app.settings.watchSettings().revision,1,e)))
        assertTrue(fake.rawPrompts.isEmpty());assertEquals(e+1,admission.epoch)
    }
}
