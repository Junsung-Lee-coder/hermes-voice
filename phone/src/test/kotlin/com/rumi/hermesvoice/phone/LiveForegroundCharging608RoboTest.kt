package com.rumi.hermesvoice.phone

import android.Manifest
import android.content.Intent
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import com.rumi.hermesvoice.core.FakeHermesDashboard
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.settings.*
import com.rumi.hermesvoice.core.wake.*
import com.rumi.hermesvoice.phone.live.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.util.ReflectionHelpers

/** Actual RESUMED MainActivity port -> ViewModel -> shared handoff -> Live -> automatic idle.
 * The next window is reconciled by a genuine local unplug/replug broadcast, NOT a settings tap,
 * direct rearm/onIdle call, test-supplied settlement or capture-state mutation. This is an
 * environmental charging reconciliation test, not a promise of a new idle polling loop.
 * Provider/media/recognizer/battery are synthetic. Await bounds are 8s; ActivityController
 * pause/stop/destroy teardown is the existing bounded Robolectric lane (no ActivityScenario).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@LooperMode(LooperMode.Mode.PAUSED)
class LiveForegroundCharging608RoboTest:PhoneRoboBase(){
    private class Setup(message:String):IllegalStateException(message)
    private var service:org.robolectric.android.controller.ServiceController<PhoneWakeService>?=null
    private lateinit var foreground:PhoneWakeController
    private lateinit var model:PhoneViewModel
    private var connects=0
    private var silences=0
    private var token=0L
    private var receiver:((String)->Unit)?=null
    private val admission get()=app.wiring().core.wakeAdmission
    private fun capturing()=ReflectionHelpers.getField<Boolean>(foreground.wake,"capturing")
    @Before fun setup(){
        baseSetUp()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS)
        org.robolectric.shadows.ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        app.settings.saveWatchSettings(WatchSettings(wakeLocation=WakeLocation.BOTH,wakePatterns="루미",
            phoneLiveEnabled=true,phoneBackgroundWakeEnabled=true,phoneWakeWhileCharging=true,liveIdleSeconds=2))
        WakeAdmission::class.java.getDeclaredField("clock").apply{isAccessible=true}.set(admission,{SystemClock.elapsedRealtime()})
        app.live.monotonicMs={SystemClock.elapsedRealtime()}
        app.live.routeFactory={object:LiveRoute{override fun open()=true;override fun valid()=true;override fun close(){}}}
        app.live.transportFactory={object:LiveTransport{
            override suspend fun connect(history:JSONArray,event:(String)->Unit){connects++;receiver=event;event("{\"type\":\"session.started\",\"session\":{\"id\":\"foreground608\"}}")}
            override fun send(json:String)=true
            override fun mute(muted:Boolean){};override fun output(enabled:Boolean){}
            override fun silence(){silences++};override fun close(){}
        }}
        app.live.admission={token=it;app.live.admitted(it)}
        @Suppress("DEPRECATION")
        app.sendStickyBroadcast(batteryIntent(true))
        controller=Robolectric.buildActivity(MainActivity::class.java).create().start()
        foreground=ReflectionHelpers.getField(controller!!.get(),"phoneWake")
        foreground.qaFixtureRecognizer=true
        model=ViewModelProvider(controller!!.get())[PhoneViewModel::class.java]
        controller!!.resume().visible();idle(100)
        val intent=shadowOf(app).nextStartedService?:throw Setup("standby service request missing")
        if(intent.component?.className!=PhoneWakeService::class.java.name)throw Setup("wrong service request")
        service=Robolectric.buildService(PhoneWakeService::class.java,intent).create().startCommand(0,1)
        if(!await{foreground.wake.listening&&PhoneWakeService.foregroundActive&&app.phoneWake.status.value.session.microphone})
            throw Setup("resumed local-charging selected microphone-approved foreground did not arm")
    }
    @After fun cleanup(){
        app.live.end()
        runCatching{service?.destroy()};service=null
        runCatching{app.phoneWake.stop()}
        @Suppress("DEPRECATION")
        app.removeStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED))
        baseTearDown()
    }
    private fun batteryIntent(on:Boolean)=Intent(Intent.ACTION_BATTERY_CHANGED)
        .putExtra(BatteryManager.EXTRA_PLUGGED,if(on) BatteryManager.BATTERY_PLUGGED_USB else 0)
        .putExtra(BatteryManager.EXTRA_STATUS,if(on) BatteryManager.BATTERY_STATUS_CHARGING else BatteryManager.BATTERY_STATUS_DISCHARGING)
    private fun battery(on:Boolean){app.sendBroadcast(batteryIntent(on));idle(100)}
    private fun chargingEvent(){battery(false);battery(true)}
    private fun phrase(){
        if(!foreground.wake.listening)throw Setup("no foreground window for deliberate phrase")
        foreground.qaHeard("루미",true);idle(350)
        if(!app.live.active&&!model.handsFreeCapturing())throw Setup("Activity handoff did not reach selected engine: pending=${foreground.wake.episodePending}, capture=${capturing()}, live=${app.live.state.value.status}")
    }
    private fun ready(){phrase();if(!await{app.live.state.value.ready})throw Setup("real Activity phrase-only Live did not become ready")}
    private fun idleEnd(){idle(2200);if(!await{!app.live.active&&app.live.state.value.endReason=="idle"})throw Setup("normal automatic idle missing")}
    private fun nextWindow(){chargingEvent();assertTrue("automatic idle must settle foreground handoff before real local replug reconciliation opens the next eligible charging window",await{foreground.wake.listening})}
    @Test fun automaticIdleThenLocalReplugAllowsNextDeliberateWakeAndRejectsOldPeer(){
        val first=admission.epoch;ready();assertEquals(first+1,admission.epoch)
        assertFalse(foreground.wake.listening);assertTrue(capturing())
        idleEnd();assertEquals(1,silences);assertFalse(app.phoneMic.occupied)
        nextWindow();assertFalse(capturing())
        phrase();assertTrue(await{app.live.state.value.ready});assertEquals(2,connects);assertEquals(first+2,admission.epoch)
        assertNotEquals(ClaimVerdict.GRANTED,admission.claim(WakeClaim("old-peer",VoiceOrigin.WATCH,"watch",app.settings.watchSettings().revision,1,first)))
        assertTrue(fake.rawPrompts.isEmpty())
    }
    @Test fun pendingAndActiveRemainBusyUntilActualLiveMediaStops(){
        app.live.admission={token=it};phrase();val first=admission.epoch
        assertTrue(app.live.active);assertTrue(capturing());assertEquals(0,connects)
        chargingEvent();assertFalse(foreground.wake.listening);assertEquals(first,admission.epoch)
        app.live.admitted(token);assertTrue(await{app.live.state.value.ready});assertTrue(capturing())
        chargingEvent();assertFalse(foreground.wake.listening);assertEquals(first+1,admission.epoch)
        idleEnd();assertEquals(1,silences)
        assertFalse("only actual Live end, after native silence, must settle the foreground capture",capturing())
    }
    @Test fun stalePriorSettlementCannotClearANewerActualForegroundHandoff(){
        ready();val stale=foreground.wake.liveCaptureSettlement();idleEnd();nextWindow()
        phrase();assertTrue(await{app.live.state.value.ready});val e=admission.epoch
        stale();assertTrue(capturing());assertTrue(app.live.active)
        chargingEvent();assertFalse(foreground.wake.listening);assertEquals(e,admission.epoch);assertEquals(2,connects)
    }
    @Test fun ordinaryForegroundNeverBecomesContinuousAfterLiveIdle(){
        ready();battery(false);idleEnd();idle(6000)
        assertFalse(foreground.wake.listening);assertEquals(1,connects);assertTrue(fake.rawPrompts.isEmpty())
    }
    @Test fun revokedMicrophoneBlocksTheExistingChargingReconciliation(){
        ready();idleEnd();shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        chargingEvent();idle(5500);assertFalse(foreground.wake.listening);assertEquals(1,connects)
    }
    @Test fun excludedDeviceCannotRearmFromChargingAfterEndedLive(){
        ready();model.setWakeLocation(WakeLocation.WATCH)
        idleEnd();chargingEvent();assertFalse(foreground.wake.listening);assertEquals(1,connects)
    }
    @Test fun standbyOffDoesNotAuthorizeAChargingWindowAfterOrdinaryVisitExpires(){
        ready();model.setPhoneBackgroundWake(false);idleEnd();idle(5500);chargingEvent()
        assertFalse(foreground.wake.listening);assertEquals(1,connects)
    }
    @Test fun offAndStaleAdmissionCannotOpenMediaOrConsumeThePendingClaim(){
        app.live.admission={token=it};val e=admission.epoch;phrase()
        model.setLiveMode(VoiceOrigin.PHONE,false);app.live.admitted(token);idle(2500)
        assertFalse(app.live.active);assertEquals(e,admission.epoch);assertEquals(0,connects)
        assertTrue(fake.rawPrompts.isEmpty())
    }
    @Test fun logoutAndStaleAdmissionCannotReopenTheActivityCaller(){
        app.live.admission={token=it};val e=admission.epoch;phrase()
        model.signOut();app.live.admitted(token);chargingEvent();idle(2500)
        assertFalse(app.live.active);assertFalse(foreground.wake.listening);assertEquals(e,admission.epoch)
        assertEquals(0,connects);assertTrue(fake.rawPrompts.isEmpty())
    }
    @Test fun explicitStopBeforeAdmissionReleasesOnlyUnadmittedClaimAndFencesLateCallback(){
        app.live.admission={token=it};val e=admission.epoch;phrase()
        app.live.end();app.live.admitted(token);idle(500)
        assertFalse(app.live.active);assertFalse("explicit end also settles only its matching foreground capture",capturing())
        assertEquals(e,admission.epoch);assertEquals(0,connects)
        assertEquals(ClaimVerdict.GRANTED,admission.claim(WakeClaim("after-stop",VoiceOrigin.WATCH,"watch",app.settings.watchSettings().revision,1,e)))
    }
    @Test fun legacyRecorderDiscardStillUsesItsExistingCompletionCallback(){
        org.robolectric.shadows.ShadowAudioRecord.setSourceProvider {
            object:org.robolectric.shadows.ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(bytes:ByteArray,offset:Int,size:Int,blocking:Boolean):Int {
                    java.util.Arrays.fill(bytes,offset,offset+size,0.toByte());Thread.sleep(5);return size
                }
            }
        }
        model.setLiveMode(VoiceOrigin.PHONE,false);idle(100)
        phrase();assertTrue(model.handsFreeCapturing());assertTrue(capturing());assertEquals(0,connects)
        model.cancelHandsFree("pause");idle(100)
        assertFalse(model.handsFreeCapturing());assertFalse(capturing());assertFalse(app.live.active)
        assertTrue(fake.rawPrompts.isEmpty())
    }
    @Test fun acceptedHermesWorkSurvivesAutomaticIdleWithoutCancelResubmitOrTts(){
        val target=conversation("foreground-held");fake.turnBehavior={"hold"}
        fake.scripts[target]={listOf(FakeHermesDashboard.complete("foreground-held-final"))}
        ready()
        receiver!!(JSONObject().put("type","session.input_transcript.delta").put("delta","work").put("start_ms",0).put("end_ms",100).toString());settle()
        receiver!!("{\"type\":\"session.delegation.created\",\"offset_ms\":200,\"delegation\":{\"id\":\"held-d\",\"type\":\"delegation\",\"target\":\"client\"}}")
        if(!await{app.live.state.value.pending!=null})throw Setup("delegation fixture not pending")
        app.live.confirm("held-d",target,"work")
        if(!await{fake.heldTurns.containsKey(target)})throw Setup("Hermes fixture did not accept held work")
        idleEnd();assertEquals(1,silences)
        assertFalse("ending Live must settle only local handoff while accepted backend work survives",capturing())
        assertTrue(fake.heldTurns.containsKey(target));assertEquals(1,fake.prompts.size)
        fake.finishHeldTurn(target)
        assertTrue(await{fake.rows.getValue(target).messages.any{it.optString("content")=="foreground-held-final"}})
        // The log is appended by the app's background reads meanwhile: read it under its own lock.
        assertEquals(1,fake.prompts.size);assertEquals(0,fake.speakRequests.get());assertFalse(synchronized(fake.restLog){fake.restLog.any{it.contains("/api/audio/speak")}})
    }
}
