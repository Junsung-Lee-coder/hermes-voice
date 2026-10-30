package com.rumi.hermesvoice.phone

import android.Manifest
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.phone.live.PhoneLiveVoiceService
import io.mockk.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Actual Phone service caller; platform FGS admission is synthetic, not a physical-background claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class WatchPhoneRelayServiceRoboTest:PhoneRoboBase(){
    private var token=0L;private var type=-1
    @Before fun setup(){
        baseSetUp();shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled=false,watchLiveEnabled=true))
        app.live.admission={token=it}
        app.live.watchRouteFactory={object:com.rumi.hermesvoice.phone.live.LiveRoute{override fun open()=true;override fun valid()=true;override fun close(){}}}
        app.live.watchTransportFactory={_,_->object:com.rumi.hermesvoice.phone.live.LiveTransport{
            override suspend fun connect(history:org.json.JSONArray,event:(String)->Unit){event("""{"type":"session.started","session":{"id":"service-fixture"}}""")}
            override fun send(json:String)=true;override fun mute(muted:Boolean){};override fun silence(){};override fun output(enabled:Boolean){};override fun close(){}
        }}
        mockkStatic(ServiceCompat::class)
        every{ServiceCompat.startForeground(any(),any(),any(),any())}answers{type=arg(3)}
        every{ServiceCompat.stopForeground(any(),any())}just Runs
    }
    @After fun cleanup(){app.live.end();settle();baseTearDown()}
    @Test fun realWatchRelayForegroundLeaseIsConnectedDeviceNotPhoneMicrophoneEvenWhenPermissionDenied(){
        assertTrue(app.live.start(VoiceOrigin.WATCH,"watch"));val controller=Robolectric.buildService(PhoneLiveVoiceService::class.java).create();val service=controller.get()
        try{service.onStartCommand(Intent(app,PhoneLiveVoiceService::class.java).putExtra("generation",token),0,1)
            assertEquals("Watch media/network requires the connected-device execution lease",ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,type)
            assertEquals(0,type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);assertFalse(app.phoneMic.occupied);assertTrue(app.live.active)
        }finally{controller.destroy()}
    }
    @Test fun realAndroidRelayAdmissionRefusalEndsOnlyLiveWithoutHermesResubmitOrPhoneMicFallback(){
        every{ServiceCompat.startForeground(any(),any(),any(),any())}throws SecurityException("synthetic OS refusal")
        assertTrue(app.live.start(VoiceOrigin.WATCH,"watch"));val controller=Robolectric.buildService(PhoneLiveVoiceService::class.java).create()
        try{controller.get().onStartCommand(Intent(app,PhoneLiveVoiceService::class.java).putExtra("generation",token),0,1);settle();assertFalse(app.live.active);assertFalse(app.phoneMic.occupied);assertTrue(fake.prompts.isEmpty());assertEquals(0,fake.speakRequests.get())}finally{controller.destroy()}
    }
}
