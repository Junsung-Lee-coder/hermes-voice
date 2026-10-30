package com.rumi.hermesvoice.phone

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.*
import com.rumi.hermesvoice.core.headset.HeadsetPolicy
import com.rumi.hermesvoice.core.live.WatchLiveWire
import com.rumi.hermesvoice.core.sessions.ReceivedConversations
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.phone.live.*
import io.mockk.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue

/** Actual setter, history poll, canonical capture/reconcile/drain. Only provider/OS/Data Layer leaves are synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class CallerClosurePhoneRoboTest:PhoneRoboBase() {
    private var now=1000L
    private val peers=ArrayList<Peer>()
    private var rejectAt=0
    private var rejectAll=false
    private var commentaryAttempts=0
    private var acking=true
    private val wires=ConcurrentLinkedQueue<ByteArray>()
    private val devices=FakeDevices(listOf(Gear.wiredHeadset))
    private inner class Peer:LiveTransport {
        var receive:((String)->Unit)?=null;var mic:((Boolean)->Unit)?=null
        var closed=false;var silenced=false;var muted=false
        val accepted=ArrayList<String>();val rejected=ArrayList<String>()
        override suspend fun connect(history:JSONArray,event:(String)->Unit){receive=event;mic?.invoke(!muted);event("{\"type\":\"session.started\",\"session\":{\"id\":\"provider\"}}")}
        override fun observeMicrophone(listener:(Boolean)->Unit){mic=listener}
        val greetings=ArrayList<String>()
        override fun send(json:String):Boolean {
            val j=JSONObject(json)
            // R30: the call's one ready-greeting request (not a canonical answer piece) is kept apart from the pieces counted here.
            if(j.optString("type")=="session.commentary.append"&&j.optString("content").startsWith("지금 바로 한국어로")){greetings+=json;return true}
            if(j.optString("type")=="session.commentary.append"){
                commentaryAttempts++
                if(rejectAll || commentaryAttempts==rejectAt){rejected+=json;return false}
            }
            accepted+=json
            if(acking&&j.optString("type").endsWith(".append"))ack(json)
            return true
        }
        fun ack(json:String){val j=JSONObject(json);receive?.invoke(JSONObject().put("type",j.getString("type").removeSuffix(".append")+".appended").put("client_event_id",j.getString("event_id")).toString())}
        fun ackAll(){accepted.toList().filter{JSONObject(it).optString("type").endsWith(".append")}.forEach(::ack)}
        fun commentary()=accepted.map{JSONObject(it)}.filter{it.optString("type")=="session.commentary.append"}
        override fun mute(muted:Boolean){this.muted=muted}
        override fun output(enabled:Boolean){}
        override fun silence(){silenced=true;mic?.invoke(false)}
        override fun close(){closed=true}
        fun say(text:String,input:Boolean=true){receive!!(JSONObject().put("type",if(input)"session.input_transcript.delta" else "session.output_transcript.delta").put("delta",text).put("start_ms",0).put("end_ms",100).toString())}
        fun delegate(){receive!!("{\"type\":\"session.delegation.created\",\"offset_ms\":101,\"delegation\":{\"id\":\"work\",\"type\":\"delegation\",\"target\":\"client\"}}")}
    }
    @Before fun setup(){
        baseSetUp();shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled=true,watchLiveEnabled=true,liveIdleSeconds=60))
        app.live.monotonicMs={now};app.live.admission={app.live.admitted(it)}
        app.live.transportFactory={Peer().also{peers+=it}};app.live.watchTransportFactory={_,_->Peer().also{peers+=it}}
        app.live.routeFactory={object:LiveRoute{override fun open()=true;override fun valid()=true;override fun close(){}}}
        app.live.watchRouteFactory={object:LiveRoute{override fun open()=true;override fun valid()=true;override fun close(){}}}
        val f=HeadsetPolicy::class.java.getDeclaredField("devices");f.isAccessible=true
        f.set(app.headset,devices) // keep the real Hub/core policy object; substitute only Android device discovery
        val client=mockk<MessageClient>()
        every{Wearable.getMessageClient(any<Context>())}returns client
        every{client.sendMessage(any(),any(),any())}answers{
            if(secondArg<String>()==FinalRecovery.FINALS){wires+=thirdArg<ByteArray>().copyOf();Tasks.forException<Int>(java.io.IOException("synthetic disconnected Watch"))}
            else Tasks.forResult(1)
        }
    }
    @After fun cleanup(){app.live.end();settle();baseTearDown()}
    private fun ready(a:String,origin:VoiceOrigin=VoiceOrigin.PHONE){assertTrue(app.live.start(origin,if(origin==VoiceOrigin.WATCH)"original-watch" else null));assertTrue(await{app.live.state.value.ready});app.live.bindSession(a);assertTrue(await{app.live.state.value.boundSessionId==a})}
    private fun reconcile(){val done=java.util.concurrent.atomic.AtomicBoolean();val job=app.appScope.launch{try{app.finalRecovery.reconcile()}finally{done.set(true)}};assertTrue(await{done.get()});assertTrue(await{job.isCompleted})}
    private fun idleCall(){now+=60_000;app.live.checkLifetime();assertFalse(app.live.active);assertEquals("idle",app.live.state.value.endReason)}
    private fun receipt(node:String,identity:String){app.finalRecovery.onReceipt(node,JSONObject().put("v",1).put("scope",app.bodyScope()).put("posted",JSONArray().put(identity)).toString().toByteArray())}
    private fun coldStore()=ReceivedConversations(app.localStore){app.bodyScope()}
    private fun captureWork(a:String){
        fake.scripts[a]={listOf(FakeHermesDashboard.complete("canonical-private-final").let{(kind,payload)->kind to payload.put("message_uid","caller-final").put("persisted_turn",JSONObject().put("final_assistant_row_id",fake.rows.getValue(a).messages.size+1).put("complete",true))})}
        ready(a,VoiceOrigin.WATCH);peers.last().say("alpha 에 작업 보내 줘");peers.last().delegate()
        assertTrue(await{fake.prompts.size==1&&!app.live.state.value.working})
        check(fake.rows.getValue(a).messages.any{it.optString("message_uid")=="caller-final"&&it.optString("finish_reason")=="stop"}){"FixtureSetup: canonical final missing"}
        reconcile()
    }
    private fun custody(afterPhonePost:Boolean){
        val a=conversation("alpha")
        // Hold Phone post until capture/ingest/drain has observed the original-node expectation.
        // Otherwise a generic history observation can legitimately post BEFORE canonical origin enrichment.
        if(afterPhonePost)shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        else shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        if(!afterPhonePost)app.settings.useHeadset=true
        captureWork(a)
        if(!afterPhonePost){shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);reconcile()}
        if(afterPhonePost){assertTrue(app.received.pending().isEmpty());app.settings.useHeadset=true;reconcile()}
        val pending=coldStore().pending(true)
        assertTrue("real private drain must preserve exact original Watch custody",pending.any{it.optString("uid")=="caller-final"&&it.optString("node")=="original-watch"&&it.optString("text")=="canonical-private-final"})
        val row=pending.single{it.optString("uid")=="caller-final"};val identity=row.getString("identity")
        assertTrue(row.getBoolean("quiet"));assertFalse(row.getBoolean("watch_done"));assertTrue(row.getBoolean("phone"))
        assertTrue(coldStore().watchReceiptAllowed(identity,"original-watch"));assertFalse(coldStore().watchReceiptAllowed(identity,"foreign"))
        receipt("foreign",identity);settle();assertTrue(coldStore().pending(true).any{it.optString("identity")==identity})
        // Provider append ACKs, Phone post and reconstructed store are not Watch receipts.
        peers.last().ackAll();settle();assertTrue(coldStore().pending(true).any{it.optString("identity")==identity})
        receipt("original-watch",identity);assertTrue(await{coldStore().pending(true).none{it.optString("identity")==identity}})
        assertEquals(1,fake.prompts.size);assertEquals(0,fake.speakRequests.get())
    }
    @Test fun originalWatchReceiptSurvivesRealPrivateDrainAndPhonePost()=custody(false)
    @Test fun originalWatchReceiptSurvivesPrivateConnectionAfterPhonePost()=custody(true)

    private fun finalWire(phoneAlreadyPosted:Boolean){
        val a=conversation("alpha");ready(a,VoiceOrigin.WATCH)
        fake.injectFinalRow(a,"wire-quiet-final",1800000000.0,"wire-final")
        val raw=fake.rows.getValue(a).messages.last().getLong("id")
        assertTrue(app.received.expect(a,raw,"original-watch",false,watchReceipt=true))
        // Use actual reconciliation to ingest, with notifications denied to retain the Phone path.
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);reconcile()
        check(app.received.canonicalRow(a,raw)?.optBoolean("quiet")==false){"FixtureSetup: public row was not saved"}
        if(phoneAlreadyPosted){shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);reconcile();check(app.received.pending().isEmpty()){"FixtureSetup: Phone post did not settle"}}
        wires.clear();app.settings.useHeadset=true;reconcile()
        check(wires.isNotEmpty()){"FixtureSetup: actual final sender not reached"}
        val relevant=wires.map{JSONObject(String(it))}.flatMap{p->p.getJSONArray("finals").let{rows->(0 until rows.length()).map{rows.getJSONObject(it)}}}.filter{it.getString("uid")=="wire-final"}
        val bytes=wires.first();println("CALLER_FINAL_WIRE_${if(phoneAlreadyPosted)"RETRY" else "INITIAL"}_BASE64="+java.util.Base64.getEncoder().encodeToString(bytes))
        assertTrue("EVERY emitted final carries effective quiet before settings replication",relevant.isNotEmpty()&&relevant.all{it.optBoolean("quiet")})
        assertTrue(coldStore().pending(true).any{it.optString("uid")=="wire-final"&&!it.optBoolean("watch_done")})
        assertFalse(app.phoneMic.occupied)
    }
    @Test fun initialDrainSerializesEffectiveQuietBeforeWatchSettings()=finalWire(false)
    @Test fun watchOnlyRetrySerializesEffectiveQuietAfterPhonePost()=finalWire(true)

    private fun setting(origin:VoiceOrigin,hasH:Boolean){
        if(!hasH)devices.disconnectAll()
        val a=conversation("alpha");val model=PhoneViewModel(app);settle()
        if(origin==VoiceOrigin.WATCH)shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        ready(a,origin);val media=peers.single();val call=app.live.callIdentity()
        model.setUseHeadset(true);settle()
        if(origin==VoiceOrigin.PHONE){assertFalse(app.live.active);assertTrue(media.silenced);return}
        assertTrue("actual settings caller must not End Watch input",app.live.active)
        assertEquals(call,app.live.callIdentity());assertTrue(app.live.state.value.inputActive);assertFalse(media.silenced)
        model.setUseHeadset(false);settle();assertTrue(app.live.active);assertEquals(call,app.live.callIdentity())
        assertFalse(app.phoneMic.occupied);assertFalse(app.audio.microphoneClaimed(VoiceOrigin.PHONE))
        media.say("alpha 에 작업 보내 줘");media.delegate();assertTrue(await{fake.prompts.size==1})
        app.live.end();assertFalse(app.live.active);assertTrue(media.silenced)
    }
    @Test fun realHeadsetSetterPreservesActiveWatchWithPersonalOutput()=setting(VoiceOrigin.WATCH,true)
    @Test fun realHeadsetSetterPreservesActiveWatchWithoutPersonalOutput()=setting(VoiceOrigin.WATCH,false)
    @Test fun realHeadsetSetterStillStopsPhoneOriginForRouteReadmission()=setting(VoiceOrigin.PHONE,true)
    @Test fun realHeadsetSetterRevokesOldAutomaticIdleLease(){
        val a=conversation("alpha");ready(a,VoiceOrigin.WATCH);idleCall();val model=PhoneViewModel(app);settle()
        model.setUseHeadset(true);model.setUseHeadset(false)
        fake.injectFinalRow(a,"must-not-resume",1800000000.0,"idle-revoked");reconcile();settle()
        assertEquals(1,peers.size);assertFalse(app.live.active)
    }
    private fun length(a:String,text:String):Long{fake.injectFinalRow(a,text,1800000000.0,"uid-$text",reason="length");return fake.rows.getValue(a).messages.last().getLong("id")}
    private fun terminal(a:String,id:Long,text:String){val messages=fake.rows.getValue(a).messages;synchronized(messages){val at=messages.indexOfFirst{it.getLong("id")==id};messages[at]=JSONObject(messages[at].toString()).put("content",text).put("finish_reason","stop")}}
    private fun noPendingAcks():Boolean {val f=PhoneLiveVoiceController::class.java.getDeclaredField("rowAcks");f.isAccessible=true;return (f.get(app.live)as Map<*,*>).isEmpty()}
    @Test fun knownTerminalUpdateRetriesFirstFalseSendAndAllPieceAcks(){
        val a=conversation("alpha");reconcile();val id=length(a,"working");reconcile()
        check(coldStore().unread(a)){"FixtureSetup: a received nonfinal row must be unread before Live ACK"}
        ready(a);acking=false;rejectAt=1
        terminal(a,id,"terminal-after-pressure");assertTrue(await{idle(2000);peers[0].rejected.isNotEmpty()})
        assertTrue("same known row must be offered after a false send",await{idle(2000);peers[0].commentary().any{it.getString("content").contains("terminal-after-pressure")}})
        assertFalse(noPendingAcks());peers[0].ackAll();assertTrue(await{noPendingAcks()})
        idle(4000);settle();assertEquals(1,peers[0].commentary().count{it.getString("content").contains("terminal-after-pressure")})
        assertTrue(coldStore().unread(a));assertTrue(fake.prompts.isEmpty())
    }
    @Test fun midPieceFailureDoesNotOverwriteAckCustodyOrConsumeLaterKnownUpdates(){
        val a=conversation("alpha");val x=length(a,"working-one");val y=length(a,"working-two");val z=length(a,"working-three");ready(a)
        acking=false;rejectAt=2
        terminal(a,x,"X".repeat(550)+"-FIRST-END");terminal(a,y,"SECOND-END");terminal(a,z,"THIRD-END")
        assertTrue(await{idle(2000);peers[0].rejected.isNotEmpty()})
        val p=peers[0];val first=p.commentary().first().getString("event_id");val refused=JSONObject(p.rejected.single()).getString("event_id")
        p.ackAll();settle()
        assertTrue("unsent later rows and remaining piece stay retryable",await{idle(2000);p.ackAll();p.commentary().any{it.getString("content").contains("THIRD-END")}&&noPendingAcks()})
        assertEquals("an accepted first piece is not resubmitted",1,p.commentary().count{it.getString("event_id")==first})
        assertEquals("the refused piece retains its original identity",1,p.commentary().count{it.getString("event_id")==refused})
        assertEquals(1,p.commentary().count{it.getString("content").contains("SECOND-END")})
        assertTrue(fake.prompts.isEmpty())
    }
    @Test fun belowFrontierTerminalUpdateRemainsOwedAcrossAutomaticIdleButNotExplicitEnd(){
        val a=conversation("alpha");val x=length(a,"working-idle");fake.injectFinalRow(a,"historic-final",1799999999.0,"historic");ready(a)
        rejectAll=true;terminal(a,x,"owed-below-frontier")
        assertTrue(await{idle(2000);peers[0].rejected.isNotEmpty()});idleCall();rejectAll=false;reconcile()
        assertTrue("below-frontier terminal update resumes the original microphone",await{idle(2000);peers.size==2&&peers[1].commentary().any{it.getString("content").contains("owed-below-frontier")}})
        assertEquals(0,peers.sumOf{p->p.commentary().count{it.getString("content").contains("historic-final")}})
        idleCall();reconcile();settle();assertEquals(2,peers.size)
        app.live.end();reconcile();settle();assertEquals(2,peers.size);assertTrue(fake.prompts.isEmpty())
    }
    @Test fun ownCanonicalResultPartialAppendKeepsItsRemainingPiecesAndOriginalEventIds(){
        val a=conversation("alpha");ready(a);val p=peers.single();acking=false;rejectAt=2
        val gate=java.util.concurrent.CountDownLatch(1)
        fake.messagesHook={_,_,limit->if(limit==30)gate.await(15,java.util.concurrent.TimeUnit.SECONDS);0}
        val answer="OWN-RESULT-"+"q".repeat(1100)+"-OWN-END"
        fake.scripts[a]={listOf(FakeHermesDashboard.complete(answer).let{(kind,payload)->kind to payload.put("message_uid","own-result").put("persisted_turn",JSONObject().put("final_assistant_row_id",fake.rows.getValue(a).messages.size+1).put("complete",true))})}
        try{
            p.say("alpha 에 작업 보내 줘");p.delegate()
            assertTrue("own sendLive result reaches its append before history can project it",await{p.rejected.isNotEmpty()&&!app.live.state.value.working})
        }finally{fake.messagesHook=null;gate.countDown()}
        val first=p.commentary().first().getString("event_id");val refused=JSONObject(p.rejected.single()).getString("event_id")
        p.ackAll();settle()
        assertTrue("history retry drains the owned result, not a new excerpt or event batch",await{idle(2000);p.ackAll();p.commentary().any{it.getString("content").contains("-OWN-END")}&&noPendingAcks()})
        assertEquals(answer,p.commentary().joinToString(""){it.getString("content")})
        assertEquals(1,p.commentary().count{it.getString("event_id")==first})
        assertEquals(1,p.commentary().count{it.getString("event_id")==refused})
        assertEquals(1,fake.prompts.size);assertEquals(0,fake.speakRequests.get())
    }
}
