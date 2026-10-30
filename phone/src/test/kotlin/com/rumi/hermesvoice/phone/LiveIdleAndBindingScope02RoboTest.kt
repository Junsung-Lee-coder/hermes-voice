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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class LiveIdleAndBindingScope02RoboTest : PhoneRoboBase() {
    private var now=1000L
    private var opens=0
    private var silenced=false
    private var receiver:((String)->Unit)?=null
    private val packets=java.util.concurrent.ConcurrentLinkedQueue<String>()
    @Before fun setUp(){
        baseSetUp();shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.live.monotonicMs={now}
        app.live.routeFactory={object:LiveRoute{override fun open()=true;override fun valid()=true;override fun close(){}}}
        app.live.transportFactory={opens++;object:LiveTransport{
            override suspend fun connect(history:JSONArray,event:(String)->Unit){receiver=event;event("{\"type\":\"session.started\",\"session\":{\"id\":\"p\"}}")}
            override fun send(json:String):Boolean{packets+=json;return true}
            override fun mute(muted:Boolean){};override fun output(enabled:Boolean){};override fun silence(){silenced=true};override fun close(){}
        }}
        app.live.admission={app.live.admitted(it)}
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled=true))
    }
    @After fun tearDown(){app.live.end();settle();baseTearDown()}
    private fun ready(){assertTrue(app.live.start());assertTrue(await{app.live.state.value.ready})}
    private fun input(text:String="work"){receiver!!(JSONObject().put("type","session.input_transcript.delta").put("delta",text).put("start_ms",0).put("end_ms",100).toString());settle()}
    @Test fun unknownFinalWithoutCanonicalStopRemainsTextOnlyWithoutGuessingOrResend(){
        val target=conversation("alpha");fake.turnBehavior={"hold"};fake.scripts[target]={listOf(FakeHermesDashboard.complete("late-scope02-final"))}
        ready();input()
        receiver!!("{\"type\":\"session.delegation.created\",\"offset_ms\":200,\"delegation\":{\"id\":\"d\",\"type\":\"delegation\",\"target\":\"client\"}}")
        assertTrue(await{app.live.state.value.pending!=null})
        app.live.confirm("d",target,"work");assertTrue(await{fake.heldTurns.containsKey(target)})
        now+=30000;app.live.checkLifetime()
        assertFalse(app.live.active);assertEquals("idle",app.live.state.value.endReason);assertTrue(silenced);assertFalse(app.phoneMic.occupied)
        fake.finishHeldTurn(target)
        assertTrue(await{fake.rows.getValue(target).messages.any{it.optString("content")=="late-scope02-final"}})
        settle();PhoneViewModel(app);settle()
        assertEquals(1,opens);assertEquals(1,fake.prompts.size);assertTrue(app.settings.watchSettings().phoneLiveEnabled)
        assertEquals(0,fake.speakRequests.get())
        assertFalse(packets.any{JSONObject(it).optString("type")=="session.commentary.append" && it.contains("late-scope02-final")})
    }
    @Test fun realDialogueExtendsIdleButAbsoluteCapAndExplicitEndRemainDistinct(){
        ready()
        repeat(33){now+=9000;input("hello ");assertTrue(app.live.active)}
        now=301000;app.live.checkLifetime();assertFalse(app.live.active);assertEquals("cap",app.live.state.value.endReason)
        ready();app.live.end();assertEquals("explicit",app.live.state.value.endReason)
        receiver!!("{\"type\":\"session.started\"}");settle();assertFalse(app.live.active);assertEquals(2,opens)
    }
    @Test fun explicitBindingHydratesOnlyVisibleChosenHistoryAndPollsArrivalsWithoutReadOrWorkAcceptance(){
        val target=conversation("alpha");val other=conversation("other")
        fake.injectRow(target,"user","visible-bound-context")
        fake.injectRow(target,"user","hidden-internal-carrier",hiddenKind=true)
        fake.injectRow(target,"assistant","NO_REPLY")
        fake.injectRow(target,"tool","private-tool-output")
        fake.injectRow(other,"user","other-chat-private")
        ready();val selected=app.selectedConversationId
        app.live.bindSession(target)
        assertTrue(await{packets.any{it.contains("visible-bound-context")}})
        assertEquals(selected,app.selectedConversationId);assertTrue(fake.prompts.isEmpty())
        assertFalse(packets.any{it.contains("hidden-internal-carrier")||it.contains("private-tool-output")||it.contains("other-chat-private")||it.contains("NO_REPLY")})
        fake.injectFinalRow(target,"arriving-final-live",123.0,"arriving-uid")
        idle(2200)
        assertTrue(await{packets.any{JSONObject(it).optString("type")=="session.commentary.append"&&it.contains("arriving-final-live")}})
        val count=packets.count{JSONObject(it).optString("type")=="session.commentary.append"&&it.contains("arriving-final-live")}
        idle(2200);settle();assertEquals(count,packets.count{JSONObject(it).optString("type")=="session.commentary.append"&&it.contains("arriving-final-live")})
        now+=30000;app.live.checkLifetime();assertEquals("idle",app.live.state.value.endReason)
        assertTrue(fake.prompts.isEmpty());assertEquals(0,fake.speakRequests.get())
    }
}
