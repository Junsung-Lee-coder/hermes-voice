package com.rumi.hermesvoice.phone

import android.Manifest
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.FakeHermesDashboard
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exact585 review schedules through real controllers, HTTP repository, FinalRecovery and durable receive state.
 * Only provider media/Android route/FGS are synthetic. A provider append ACK is never a Watch posted/read receipt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Reviewed585RepairRoboTest:PhoneRoboBase() {
    private var now=1000L
    private var acking=true
    private val peers=ArrayList<Peer>()
    private inner class Peer:LiveTransport {
        var receive:((String)->Unit)?=null;var microphone:((Boolean)->Unit)?=null
        var muted=false;var mutedAtConnect:Boolean?=null;var connected=false;var closed=false;var ticks=0L
        val packets=java.util.concurrent.ConcurrentLinkedQueue<String>()
        override fun observeMicrophone(listener:(Boolean)->Unit){microphone=listener}
        override suspend fun connect(history:JSONArray,event:(String)->Unit){receive=event;connected=true;mutedAtConnect=muted;microphone?.invoke(!muted);event("{\"type\":\"session.started\",\"session\":{\"id\":\"provider\"}}")}
        override fun send(json:String):Boolean {
            packets+=json;val j=JSONObject(json);val type=j.optString("type")
            if(acking&&type.endsWith(".append"))receive?.invoke(JSONObject().put("type",type.removeSuffix(".append")+".appended").put("client_event_id",j.getString("event_id")).toString())
            return true
        }
        override fun mute(muted:Boolean){this.muted=muted;microphone?.invoke(connected&&!muted)}
        override fun output(enabled:Boolean){}
        override fun silence(){connected=false;microphone?.invoke(false)}
        override fun close(){closed=true}
        fun content(text:String)=packets.count{JSONObject(it).optString("content").contains(text)}
        /** One coherent snapshot: every packet parsed once, in order (a malformed packet throws, as in [content]). */
        fun contents()=packets.map{JSONObject(it).optString("content")}
        fun commentary(text:String)=packets.count{val j=JSONObject(it);j.optString("type")=="session.commentary.append"&&j.optString("content").contains(text)}
        fun say(text:String,input:Boolean=true){val t=ticks;ticks+=100;receive!!(JSONObject().put("type",if(input)"session.input_transcript.delta" else "session.output_transcript.delta").put("delta",text).put("start_ms",t).put("end_ms",ticks).toString())}
        fun delegate(id:String){receive!!(JSONObject().put("type","session.delegation.created").put("offset_ms",ticks+1).put("delegation",JSONObject().put("id",id).put("type","delegation").put("target","client")).toString())}
    }
    private var gate=CountDownLatch(0)
    private var entered=CountDownLatch(0)
    @Volatile private var hold:String?=null
    @Before fun setup(){
        baseSetUp();shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled=true,watchLiveEnabled=true,liveIdleSeconds=60))
        app.live.monotonicMs={now};app.live.admission={app.live.admitted(it)}
        app.live.transportFactory={Peer().also{peers+=it}};app.live.watchTransportFactory={_,_->Peer().also{peers+=it}}
        app.live.routeFactory={object:LiveRoute{override fun open()=true;override fun valid()=true;override fun close(){}}}
        app.live.watchRouteFactory={object:LiveRoute{override fun open()=true;override fun valid()=true;override fun close(){}}}
    }
    @After fun cleanup(){gate.countDown();fake.messagesHook=null;app.live.end();settle();baseTearDown()}
    private fun ready(target:String,origin:VoiceOrigin=VoiceOrigin.PHONE){assertTrue(app.live.start(origin,if(origin==VoiceOrigin.WATCH)"original-watch" else null));assertTrue(await{app.live.state.value.ready});app.live.bindSession(target);assertTrue(await{app.live.state.value.boundSessionId==target})}
    private fun suspendIdle(){now+=60_000;app.live.checkLifetime();assertFalse(app.live.active);assertEquals("idle",app.live.state.value.endReason)}
    private fun reconcile(){val done=java.util.concurrent.atomic.AtomicBoolean(false);val job=app.appScope.launch{try{app.finalRecovery.reconcile()}finally{done.set(true)}};try{assertTrue(await{done.get()})}finally{assertTrue(await{job.isCompleted})}}
    private fun final(target:String,text:String){fake.injectFinalRow(target,text,1_800_000_000.0,"uid-$text")}

    @Test fun beginningNavigationRevokesIdleFollowWhileTheNewHistoryIsHeld(){
        val a=conversation("alpha");val b=conversation("beta");val model=PhoneViewModel(app);settle()
        model.openOrResume(app.wiring().core.sessions.registry.find(a)!!);assertTrue(await{app.selectedConversationId==a});ready(a);suspendIdle()
        gate=CountDownLatch(1);entered=CountDownLatch(1);hold=b
        fake.messagesHook={id,_,_->if(id==hold){entered.countDown();gate.await(15,TimeUnit.SECONDS)};0}
        try{
            model.openOrResume(app.wiring().core.sessions.registry.find(b)!!)
            assertTrue("the actual B read is held, not just a later submit",await{entered.count==0L&&model.state.value.selected?.storedSessionId==b})
            final(a,"navigation-late");reconcile();settle()
            assertEquals("navigation admission, not successful history, revokes A",1,peers.size)
            assertFalse(app.live.active);assertEquals(0,peers.sumOf{it.commentary("navigation-late")})
        }finally{gate.countDown();hold=null;assertTrue(await{!model.state.value.opening})}
        assertTrue(fake.prompts.isEmpty());assertTrue(fake.rows.getValue(a).messages.any{it.optString("content")=="navigation-late"})
    }
    private fun mutedIdle(origin:VoiceOrigin){
        val a=conversation("alpha");ready(a,origin);app.live.setMuted(true);assertTrue(peers.single().muted);suspendIdle();final(a,"muted-late");reconcile()
        assertTrue(await{peers.size==2&&app.live.state.value.ready&&app.live.state.value.boundSessionId==a})
        assertEquals("an automatic continuation is not an Unmute",true,peers.last().mutedAtConnect)
        assertTrue(app.live.state.value.muted);assertFalse(app.live.state.value.inputActive)
        app.live.setMuted(false);assertTrue(await{app.live.state.value.inputActive})
        app.live.end();settle();ready(a,origin);assertEquals("a deliberate new Talk keeps its explicit unmuted policy",false,peers.last().mutedAtConnect)
        assertTrue(fake.prompts.isEmpty())
    }
    @Test fun phoneMuteSurvivesIdleBeforeTheNewNativeCapture()=mutedIdle(VoiceOrigin.PHONE)
    @Test fun watchMuteSurvivesIdleBeforeTheNewNativeCapture()=mutedIdle(VoiceOrigin.WATCH)
    @Test fun phoneTalkDoesNotBecomeTheForeignWatchEndButton(){
        val a=conversation("alpha");app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled=false,watchLiveEnabled=true));ready(a,VoiceOrigin.WATCH)
        PhoneViewModel(app).toggleRecording();settle()
        assertTrue("Phone Talk may refuse resources, not terminate Watch",app.live.active)
        assertEquals(VoiceOrigin.WATCH,app.live.state.value.origin);assertFalse(peers.single().closed)
    }
    private fun personalHeadset(){
        app.settings.useHeadset=true
        val devices=com.rumi.hermesvoice.core.FakeDevices(listOf(com.rumi.hermesvoice.core.Gear.wiredHeadset))
        val f=PhoneApp::class.java.getDeclaredField("headset\$delegate");f.isAccessible=true
        f.set(app,lazyOf(com.rumi.hermesvoice.core.headset.HeadsetPolicy({true},devices)))
    }
    @Test fun phoneHeadsetControlCannotEndForeignWatchLive(){
        val a=conversation("alpha");ready(a,VoiceOrigin.WATCH);personalHeadset()
        val outcome=app.manualCapture.onHeadsetCommand(com.rumi.hermesvoice.core.headset.RecordingCommand.STOP);settle()
        assertTrue(app.live.active);assertNotEquals(HeadsetOutcome.STOPPED,outcome)
        assertFalse(peers.single().closed)
    }
    @Test fun watchInputStartsIndependentlyOfPhonePrivateOutputAndPhoneMicrophonePermission(){
        val a=conversation("alpha");personalHeadset();shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        ready(a,VoiceOrigin.WATCH)
        assertEquals(VoiceOrigin.WATCH,app.live.state.value.origin);assertFalse(app.phoneMic.occupied)
        assertFalse(app.audio.microphoneClaimed(VoiceOrigin.PHONE));assertTrue(app.live.state.value.inputActive)
        peers.last().say("alpha 에 할 일 보내 줘");peers.last().delegate("private-watch")
        assertTrue(await{fake.prompts.size==1});assertEquals(a,fake.prompts.single().first)
        assertEquals(0,fake.speakRequests.get())
    }
    private fun marker(i:Int)="arrival-%04d-END".format(i)
    /** [Peer.content]'s check for markers 1..n (each in exactly ONE packet's content) on one snapshot, so a poll parses each packet once
     * rather than once per marker: re-parsing per marker made every await poll's cost grow with the packets already sent. */
    private fun everyArrivalOnce(peer:Peer,n:Int):Boolean{val seen=peer.contents();return (1..n).all{i->val m=marker(i);seen.count{it.contains(m)}==1}}
    private fun burst(n:Int){
        val a=conversation("alpha");ready(a)
        for(i in 1..n)fake.injectRow(a,"user",marker(i))
        assertTrue("all $n raw arrivals survive bounded scan/send yields",await(40_000){idle(2000);everyArrivalOnce(peers[0],n)})
        val contents=peers[0].packets.map{JSONObject(it).optString("content")}
        val order=(1..n).map{i->contents.indexOfFirst{it.contains(marker(i))}}
        assertEquals(order.sorted(),order);assertEquals(n,order.distinct().size);assertTrue(fake.prompts.isEmpty())
    }
    @Test fun moreThanFourPagesDoNotAcknowledgeAnIncompleteScan()=burst(150)
    @Test fun moreThanOneThousandRowsKeepTheirRequeryObligation()=burst(1001)
    /** Observer control on synthetic packets (no product path): the snapshot check agrees with the per-marker [Peer.content] oracle. */
    @Test fun burstObserverAgreesWithThePerMarkerContentOracle(){
        val n=12
        fun packet(content:Any?,field:String="content")=JSONObject().put("type","session.commentary.append").put("event_id","e").also{if(content!=null)it.put(field,content)}.toString()
        fun peer(vararg packets:String)=Peer().also{p->packets.forEach{p.packets+=it}}
        val complete=(1..n).map{packet("New message: ${marker(it)}")}
        fun agrees(expected:Boolean,peer:Peer){
            val seen=peer.contents()
            for(i in 1..n)assertEquals(marker(i),peer.content(marker(i)),seen.count{it.contains(marker(i))})
            assertEquals((1..n).all{peer.content(marker(it))==1},everyArrivalOnce(peer,n));assertEquals(expected,everyArrivalOnce(peer,n))
        }
        agrees(true,peer(*complete.toTypedArray()))
        agrees(true,peer(*complete.reversed().toTypedArray(),packet("unrelated"),packet(null),packet(marker(3),field="delta")))
        agrees(true,peer(*complete.drop(1).toTypedArray(),packet("${marker(1)} said twice: ${marker(1)}")))
        agrees(true,peer(*complete.drop(1).toTypedArray(),packet("x${marker(1)}y 한국어")))
        agrees(true,peer(*complete.drop(1).toTypedArray(),packet(JSONObject().put("text",marker(1)))))
        agrees(false,peer(*complete.filterIndexed{i,_->i!=6}.toTypedArray()))
        agrees(false,peer(*complete.toTypedArray(),packet("again ${marker(7)}")))
        agrees(false,peer(*complete.drop(1).toTypedArray(),packet("arrival-0001-EN"),packet("arrival-001-END"),packet("Arrival-0001-END")))
        agrees(false,peer(*complete.drop(1).toTypedArray(),packet(marker(1),field="delta")))
        agrees(false,peer())
    }
    @Test fun burstObserverRejectsAMalformedPacketLikeThePerMarkerOracle(){
        val peer=Peer().also{it.packets+=JSONObject().put("content",marker(1)).toString();it.packets+="{not json"}
        assertTrue(runCatching{peer.content(marker(1))}.exceptionOrNull() is org.json.JSONException)
        assertTrue(runCatching{everyArrivalOnce(peer,1)}.exceptionOrNull() is org.json.JSONException)
    }
    @Test fun initialHistoricFinalsOutsideTheContextTailAreNotNewCommentary(){
        val a=conversation("alpha");for(i in 1..30)final(a,"historic-%02d".format(i));ready(a)
        idle(2200);assertTrue(await{fake.restLog.toList().count{it.contains("/messages")}>1});settle()
        assertEquals("no arrival happened",0,peers.sumOf{p->(1..30).sumOf{p.commentary("historic-%02d".format(it))}})
    }
    @Test fun recoveryCursorCannotDiscardThirtyThreeUnacknowledgedFinalsBehindANonfinalTail(){
        val a=conversation("alpha");acking=false;ready(a)
        gate=CountDownLatch(1);entered=CountDownLatch(1);hold=a
        fake.messagesHook={id,_,limit->if(id==hold&&limit==30){entered.countDown();gate.await(15,TimeUnit.SECONDS)};0}
        try{
            assertTrue(await{entered.count==0L})
            for(i in 1..33)final(a,"owed-%02d-END".format(i));repeat(55){fake.injectRow(a,"user","tail-$it")}
            reconcile();settle();assertEquals(0,peers[0].commentary("owed-01-END"));suspendIdle()
        }finally{hold=null;gate.countDown()}
        acking=true;reconcile()
        assertTrue("even the first unacked final remains recoverable",await(40_000){idle(2000);peers.drop(1).sumOf{it.commentary("owed-01-END")}==1})
        assertTrue(await(40_000){idle(2000);(1..33).all{i->peers.drop(1).sumOf{it.commentary("owed-%02d-END".format(i))}==1}})
        suspendIdle();reconcile();settle();assertEquals("acknowledged finals do not reopen again",2,peers.size)
    }
    @Test fun explicitlyQuotedShortAssistantTitleIsNotAChoiceButAnExplicitShortTitleRequestIs(){
        conversation("alpha");val beta=conversation("beta");fake.scripts[beta]={listOf(FakeHermesDashboard.complete("done"))}
        assertTrue(app.live.start());assertTrue(await{app.live.state.value.ready});val p=peers.last()
        p.say("회의록 요약 부탁해");p.say("Beta",false);p.say("\"Beta\"");p.delegate("quoted");settle()
        assertTrue("explicit quoted echo is not routing authority",fake.prompts.isEmpty())
        p.say("Beta 로 보내 줘");p.delegate("explicit")
        assertTrue("a genuine explicit short title is still supported",await{fake.prompts.size==1});assertEquals(beta,fake.prompts.single().first)
    }
    @Test fun watchLiveFinalOwesTheOriginalNodeUntilARealPostedReceipt(){
        val a=conversation("alpha");fake.scripts[a]={listOf(FakeHermesDashboard.complete("watch-canonical-final").let{(kind,payload)->
            kind to payload.put("message_uid","watch-live-canonical-1").put("persisted_turn",JSONObject()
                .put("final_assistant_row_id",fake.rows.getValue(a).messages.size+1).put("complete",true))
        })}
        assertNull(app.wiring().core.orchestrator.playbackRoute.current())
        assertTrue(app.live.start(VoiceOrigin.WATCH,"original-watch"));assertTrue(await{app.live.state.value.ready})
        peers.last().say("alpha 에 작업 보내 줘");peers.last().delegate("watch-work")
        assertTrue(await{fake.prompts.size==1&&!app.live.state.value.working})
        check(fake.rows.getValue(a).messages.any{it.optString("message_uid")=="watch-live-canonical-1" && it.optString("finish_reason")=="stop"}){"FixtureSetup: contract-valid canonical final was not emitted"}
        reconcile()
        assertTrue("provider ACK cannot replace the original Watch obligation",await{app.received.pending(true).any{it.optString("node")=="original-watch"&&it.optString("text")=="watch-canonical-final"}})
        val row=app.received.pending(true).single{it.optString("text")=="watch-canonical-final"};val identity=row.getString("identity")
        app.live.end();reconcile();assertTrue(app.received.pending(true).any{it.optString("identity")==identity})
        fun receipt(node:String){app.finalRecovery.onReceipt(node,JSONObject().put("v",1).put("scope",app.bodyScope()).put("posted",JSONArray().put(identity)).toString().toByteArray())}
        receipt("wrong-watch");settle();assertTrue(app.received.pending(true).any{it.optString("identity")==identity})
        receipt("original-watch");assertTrue(await{app.received.pending(true).none{it.optString("identity")==identity}})
        assertEquals(1,fake.prompts.size);assertEquals(0,fake.speakRequests.get())
    }
}
