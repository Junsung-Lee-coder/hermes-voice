package com.rumi.hermesvoice.phone

import android.Manifest
import com.rumi.hermesvoice.core.FakeHermesDashboard
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.phone.live.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Pre-scope02-compatible actual controller/repository/submit entry: history must reach Live, never another conversation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class LiveSessionContextScope02RoboTest : PhoneRoboBase() {
    private val packets = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private var receiver: ((String)->Unit)? = null
    @Before fun setUp() {
        baseSetUp(); shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.live.routeFactory={ object:LiveRoute { override fun open()=true;override fun valid()=true;override fun close(){} } }
        app.live.transportFactory={ object:LiveTransport {
            override suspend fun connect(history:JSONArray,event:(String)->Unit) { receiver=event; event("{\"type\":\"session.started\",\"session\":{\"id\":\"p\"}}") }
            override fun send(json:String):Boolean { packets+=json; return true }
            override fun mute(muted:Boolean){};override fun silence(){};override fun output(enabled:Boolean){};override fun close(){}
        } }
        app.live.admission={app.live.admitted(it)}
    }
    @After fun tearDown(){ app.live.end();settle();baseTearDown() }
    @Test fun boundVisibleSessionHistoryAndArrivingReplyReachLiveWithoutUnrelatedContentOrTts() {
        val target=conversation("alpha");val other=conversation("other")
        fake.injectRow(target,"user","history-evidence-alpha")
        fake.injectRow(other,"user","unrelated-private-other")
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled=true))
        assertTrue(app.live.start());assertTrue(await {app.live.state.value.ready})
        receiver!!("{\"type\":\"session.input_transcript.delta\",\"delta\":\"do work\",\"start_ms\":0,\"end_ms\":100}")
        receiver!!("{\"type\":\"session.delegation.created\",\"offset_ms\":200,\"delegation\":{\"id\":\"d\",\"type\":\"delegation\",\"target\":\"client\"}}")
        assertTrue(await {app.live.state.value.pending!=null})
        fake.scripts[target]={listOf(FakeHermesDashboard.complete("reply-evidence-alpha"))}
        app.live.confirm("d",target,"do work")
        assertTrue("bound canonical visible history must be supplied to GPT-Live",await {packets.any {JSONObject(it).optString("content").contains("history-evidence-alpha")}})
        assertTrue("actual arriving Hermes final must be supplied to GPT-Live",await {packets.any {JSONObject(it).optString("content").contains("reply-evidence-alpha")}})
        assertFalse(packets.any {it.contains("unrelated-private-other")})
        assertEquals(0, fake.speakRequests.get())
        assertEquals(1,fake.prompts.size)
    }
}
