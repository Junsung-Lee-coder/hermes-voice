package com.rumi.hermesvoice.phone

import android.Manifest
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.phone.live.*
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A final wake recognizer result is real user input, not something Live may silently discard or replay to legacy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class LiveWakeSeedScope03RoboTest:PhoneRoboBase(){
    private var history:JSONArray?=null
    private var receive:((String)->Unit)?=null
    @Before fun setUp(){
        baseSetUp();shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled=true))
        app.live.routeFactory={object:LiveRoute{override fun open()=true;override fun valid()=true;override fun close(){}}}
        app.live.transportFactory={object:LiveTransport{
            override suspend fun connect(history:JSONArray,event:(String)->Unit){this@LiveWakeSeedScope03RoboTest.history=history;receive=event;event("{\"type\":\"session.started\",\"session\":{\"id\":\"p\"}}")}
            override fun send(json:String)=true;override fun mute(muted:Boolean){};override fun output(enabled:Boolean){};override fun silence(){};override fun close(){}
        }}
        app.live.admission={app.live.admitted(it)}
    }
    @After fun tearDown(){app.live.end();settle();baseTearDown()}
    @Test fun actualForegroundWakeRequestSeedsSupportedLiveHistoryAndDelegationContextWithoutLegacySubmit(){
        conversation("alpha")
        PhoneViewModel(app).sendRecognizedRequest("wake-command-seed-03")
        assertTrue(await{app.live.state.value.ready})
        assertTrue("the recognizer-consumed final request must reach Live",history!!.toString().contains("wake-command-seed-03"))
        receive!!("{\"type\":\"session.delegation.created\",\"offset_ms\":100,\"delegation\":{\"id\":\"wake-delegation\",\"type\":\"delegation\",\"target\":\"client\"}}")
        assertTrue(await{app.live.state.value.pending?.text=="wake-command-seed-03"})
        assertTrue(fake.prompts.isEmpty());assertNull(fake.lastTranscribedWav);assertEquals(0,fake.speakRequests.get())
    }
}
