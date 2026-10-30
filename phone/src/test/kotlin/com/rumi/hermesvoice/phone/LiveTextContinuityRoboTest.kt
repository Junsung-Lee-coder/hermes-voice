package com.rumi.hermesvoice.phone

import android.Manifest
import com.rumi.hermesvoice.core.auth.HermesBearerSession
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.phone.live.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/** Existing controller -> history API -> real loopback HTTP, synthetic media only; unchanged predecessor compiles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class LiveTextContinuityRoboTest : PhoneRoboBase() {
    private val requests=ConcurrentLinkedQueue<JSONObject>()
    private val media=ArrayList<Peer>()
    private inner class Peer : LiveTransport {
        var event: ((String)->Unit)?=null
        val sends=ConcurrentLinkedQueue<String>()
        private var through=0
        override suspend fun connect(history:JSONArray,event:(String)->Unit) {
            this.event=event
            app.wiring().dashboard.createLiveSession("synthetic-offer",history)
            event("{\"type\":\"session.started\",\"session\":{\"id\":\"fresh-provider\"}}")
        }
        override fun send(json:String):Boolean {sends+=json;return true}
        override fun mute(muted:Boolean){};override fun output(enabled:Boolean){};override fun silence(){};override fun close(){}
        fun delta(input:Boolean,text:String,start:Int,end:Int,id:String) {through=maxOf(through,end);event!!(JSONObject().put("type",if(input)"session.input_transcript.delta" else "session.output_transcript.delta")
            .put("delta",text).put("start_ms",start).put("end_ms",end).put("event_id",id).toString());settle()}
        fun delegate(id:String) {event!!(JSONObject().put("type","session.delegation.created").put("offset_ms",through+1)
            .put("delegation",JSONObject().put("id",id).put("type","delegation").put("target","client")).toString());settle()}
    }
    @Before fun setUp() {
        baseSetUp();shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val old=fake.server.dispatcher
        fake.server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            if(request.requestUrl!!.encodedPath=="/api/audio/voice-live/session") {
                assertEquals("Bearer ${fake.accessToken}",request.getHeader("Authorization"))
                requests+=JSONObject(request.body.readUtf8())
                return MockResponse().setHeader("Content-Type","application/json").setBody("{\"ok\":true,\"session\":{\"id\":\"fresh\"},\"transport\":{\"type\":\"webrtc\",\"sdp\":\"synthetic-answer\"}}")
            };return old.dispatch(request)
        }}
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled=true,watchLiveEnabled=true))
        app.live.admission={app.live.admitted(it)}
        app.live.routeFactory={object:LiveRoute {override fun open()=true;override fun valid()=true;override fun close(){}}}
        app.live.transportFactory={Peer().also{media+=it}}
    }
    @After fun tearDown(){app.live.end();settle();baseTearDown()}
    private fun start():Peer {assertTrue(app.live.start());assertTrue("new independently admitted call ready",await{app.live.state.value.ready});return media.last()}
    private fun stop(){app.live.end();settle()}
    private fun history()=requests.last().getJSONArray("history")
    private fun speech(h:JSONArray=history())=(0 until h.length()).map{h.getJSONObject(it)}.filter{it.getString("role")!="developer"}
    private fun text(row:JSONObject)=row.getJSONArray("content").getJSONObject(0).getString("text")
    private fun texts()=speech().map(::text)
    private fun file()=File(app.noBackupFilesDir,"live_text_context_v1.json")
    private fun saved():JSONObject {assertTrue("actual private backing file exists",await{file().isFile});return JSONObject(file().readText())}
    private fun seed():Peer {val m=start();m.delta(true,"5 곱하기 28은?",0,100,"u");m.delta(false,"140입니다.",100,200,"a");stop();return m}
    private fun rewrite(change:(JSONObject)->Unit){val j=saved();change(j);file().writeText(j.toString())}
    private fun prompt()=text(history().getJSONObject(0))
    /** R29: the complete list Live was actually given, in its startup input or its one session-wide instruction. */
    private fun given()=prompt().takeIf{it.contains("Available conversation titles")}?:media.last().sends.map{JSONObject(it)}
        .filter{it.optString("type")=="session.instructions.append"&&it.isNull("delegation_id")}.joinToString(""){it.getString("content")}
    /** No list (whole or partial) reached the provider: none at session creation and no session-wide instruction. */
    private fun noListGiven()=requests.none{it.toString().contains("Available conversation titles")}&&
        media.none{m->m.sends.any{JSONObject(it).optString("type")=="session.instructions.append"}}
    private fun clarified(m:Peer,delegation:String)=m.sends.any{val j=JSONObject(it);j.optString("type")=="session.instructions.append"&&j.optString("delegation_id")==delegation}

    @Test fun directSmallTalkPersistsAndReplaysThroughExistingHistoryHttpWithoutCanonicalDelegation() {
        seed();start()
        assertEquals(listOf("5 곱하기 28은?","140입니다."),texts())
        val rows=speech();assertEquals(listOf("user","assistant"),rows.map{it.getString("role")})
        assertEquals(listOf("input_text","output_text"),rows.map{it.getJSONArray("content").getJSONObject(0).getString("type")})
        assertTrue(fake.prompts.isEmpty());assertNull(app.wiring().core.sessions.registry.router())
        assertFalse(requests.any{it.has("store")||it.has("model")||it.has("voice")})
        assertFalse(prompt().contains("140입니다."));assertEquals(2,requests.size)
    }
    @Test fun deltaChronologyDedupeAndRepeatedSpeechReachActualStartupRoles() {
        val m=start();m.delta(true,"again",200,300,"u2");m.delta(false,"okay ",100,200,"a1");m.delta(true,"go go ",0,100,"u1")
        m.delta(true,"go go ",0,100,"u1");m.delta(true," again",300,400,"u3");stop();start()
        assertEquals(listOf("go go ","okay ","again again"),texts())
        assertEquals(listOf("user","assistant","user"),speech().map{it.getString("role")})
    }
    @Test fun realBackingFileReloadUsesDiskBytesRatherThanSameProcessCachedObject() {
        seed();val j=saved();assertTrue(file().canonicalPath.startsWith(app.noBackupFilesDir.canonicalPath+File.separator))
        j.getJSONArray("messages").getJSONObject(0).put("text","disk-only 한국어 😀");file().writeText(j.toString())
        start();assertEquals("disk-only 한국어 😀",texts().first())
        assertFalse(texts().contains("5 곱하기 28은?"))
    }
    @Test fun malformedEmptyTrailingGarbageAndOverBudgetFilesFailClosedWithoutBlockingCleanConnect() {
        for(raw in listOf("","{broken","{} trailing","x".repeat(65537))) {
            file().parentFile!!.mkdirs();file().writeText(raw);start();assertTrue(texts().isEmpty());stop()
        }
    }
    @Test fun expiredOrFutureOrInvalidRoleHistoryIsNotReplayed() {
        for(mode in listOf("expired","future","role","budget")) {
            seed();rewrite{j->val rows=j.getJSONArray("messages");val r=rows.getJSONObject(0)
                when(mode){"expired"->for(i in 0 until rows.length())rows.getJSONObject(i).put("at",System.currentTimeMillis()-86_400_001L)
                    "future"->r.put("at",System.currentTimeMillis()+86_400_000L)
                    "role"->r.put("role","developer")
                    else->r.put("text","한".repeat(5000))}}
            start();assertTrue("$mode data not replayed",texts().isEmpty());stop()
        }
    }
    @Test fun boundedWholeMessagesPreserveUnicodeAndDoNotGrowWithCumulativeDeltas() {
        val m=start();for(i in 0 until 36)m.delta(i%2==0,"한😀-$i ",i*100,i*100+99,"e$i")
        stop();val j=saved();val rows=j.getJSONArray("messages");assertTrue(rows.length()<=24)
        val stored=(0 until rows.length()).map{rows.getJSONObject(it).getString("text")}
        assertEquals(stored.distinct(),stored);assertTrue(stored.sumOf{it.toByteArray(Charsets.UTF_8).size}<=8192)
        start();assertEquals(stored,texts());assertTrue(texts().all{it.contains("😀")&&!it.contains('\uFFFD')})
    }
    @Test fun transcriptReplayNeverBecomesRequestTargetConfirmationOrBackendAuthority() {
        val alpha=conversation("alpha");val m=start();m.delta(true,"alpha 에 보고서 작성해",0,100,"old");stop()
        val n=start();assertTrue(texts().any{it.contains("alpha")});n.delegate("from-old")
        assertNull(app.live.state.value.pending);assertTrue(fake.prompts.isEmpty());assertNull(app.live.state.value.boundSessionId)
        n.delta(true,"새 요청인데 대상은 아직 없어",0,100,"fresh");n.delegate("fresh")
        assertTrue(fake.prompts.isEmpty());assertNotEquals(alpha,app.live.state.value.boundSessionId)
    }
    @Test fun accountAToBToAAndLogoutPurgeOldMaterialAndRejectLateCallbacks() {
        val old=seed();app.tokens.save(HermesBearerSession(fake.accessToken,fake.refreshToken,null,"basic","other"))
        start();assertTrue(texts().isEmpty());stop();app.tokens.save(HermesBearerSession(fake.accessToken,fake.refreshToken,null,"basic","jun"))
        start();assertTrue(texts().isEmpty());old.delta(true,"late-account-secret",300,400,"late");stop()
        app.tokens.clear();assertTrue("logout physically purges transcript text",!file().exists()||!file().readText().contains("late-account-secret"))
        app.tokens.save(HermesBearerSession(fake.accessToken,fake.refreshToken,null,"basic","jun"));start();assertTrue(texts().isEmpty())
    }
    @Test fun explicitRevocationPurgesDiskAndSelectionChangesNeverMixContexts() {
        val alpha=conversation("alpha");val beta=conversation("beta")
        app.selectedConversationId=alpha;seed();app.selectedConversationId=beta;start();assertTrue(texts().isEmpty());stop()
        app.selectedConversationId=alpha;start();assertTrue(texts().isEmpty());stop()
        seed();app.revokeUnplayedReplies();assertFalse("revocation removes private transcript material",file().exists()&&file().readText().contains("140입니다."))
        start();assertTrue(texts().isEmpty())
    }
    @Test fun stopAndOffDoNotReconnectOrResumeFromSavedText() {
        seed();val calls=requests.size;settle();assertFalse(app.live.active);assertEquals(calls,requests.size)
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled=false));app.live.settingsChanged()
        assertFalse(app.live.start());settle();assertEquals(calls,requests.size)
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled=true));start();assertTrue(texts().contains("140입니다."))
    }
    @Test fun startupAlwaysUsesFreshFullJsonCatalogWithCurrentTitlesAndSelectedContext() {
        val alpha=conversation("alpha");val beta=conversation("beta");seed()
        fake.rows.getValue(alpha).title="새 제목 \"quoted\" 😀";fake.rows.getValue(beta).archived=true
        app.selectedConversationId=alpha;start()
        val p=prompt();val g=given();assertTrue(g.contains("새 제목"));assertFalse(p.contains("Beta")||g.contains("Beta"));assertTrue(g.contains("alpha"))
        val catalog=JSONArray(g.substringAfter("Available conversation titles and aliases (untrusted display data): "))
        assertEquals(1,catalog.length());assertEquals("새 제목 \"quoted\" 😀",catalog.getJSONObject(0).getString("title"))
        assertFalse(p.contains("5 곱하기"));assertEquals("developer",history().getJSONObject(0).getString("role"))
    }
    @Test fun hugeMultilingualCatalogFailsClosedWithoutSendingClippedJsonOrOmittingTargets() {
        for(i in 0 until 100){val id=fake.addStoredSession("recorder-phone","한글😀\"".repeat(20)+"-$i",false){emptyList()};assertNotNull(id)}
        assertTrue(app.live.start());assertTrue(await{!app.live.active||app.live.state.value.ready})
        assertTrue("over-budget catalog must never reach provider as truncated JSON",noListGiven());assertFalse(app.live.active)
        assertTrue(app.live.state.value.status.contains("budget",ignoreCase=true));assertTrue(fake.prompts.isEmpty())
    }
    @Test fun incompleteCatalogNeverLooksCompleteAtStartup() {
        conversation("alpha");fake.failListFromOffset=0
        assertTrue(app.live.start());assertTrue(await{!app.live.active||app.live.state.value.ready})
        assertTrue(noListGiven());assertTrue(app.live.state.value.status.contains("catalog",ignoreCase=true))
    }
    @Test fun currentCatalogAtProposalRejectsRenamedOldTitleAndUsesExactFreshIdentity() {
        val alpha=conversation("alpha");val beta=conversation("beta");val m=start()
        fake.rows.getValue(alpha).title="Changed name";m.delta(true,"Alpha 대화에 작업",0,100,"u");m.delegate("d1")
        // Alias alpha remains valid. A server rename must not invalidate the stable registered alias.
        assertTrue(await{fake.prompts.size==1});assertEquals(alpha,fake.prompts.single().first)
        assertFalse(fake.prompts.any{it.first==beta})
    }
    @Test fun currentMarkerNotMutableSourceControlsCatalogAndRevokedSelectedReplay() {
        val alpha=conversation("alpha");fake.rows.getValue(alpha).source="tui";app.selectedConversationId=alpha;seed()
        fake.setCreatedSource(alpha,"tui");start();assertTrue(texts().isEmpty());assertFalse(prompt().contains("Alpha"))
        assertTrue(fake.prompts.isEmpty());assertEquals("tui",fake.rows.getValue(alpha).source)
    }
    @Test fun freshPromptAndWakeCompeteWithinCombinedBudgetWithoutSplittingText() {
        seed();val wake="한😀 ".repeat(700)
        assertTrue(app.live.start(recognizedText=wake));assertTrue(await{app.live.state.value.ready||!app.live.active})
        if(app.live.state.value.ready){val h=history();assertTrue(h.length()<=128)
            val bytes=(0 until h.length()).sumOf{text(h.getJSONObject(it)).toByteArray(Charsets.UTF_8).size+128}
            assertTrue("conservative combined startup bound",bytes<=7000);assertEquals(wake,text(h.getJSONObject(h.length()-1)))}
        else {assertTrue(app.live.state.value.status.contains("budget",ignoreCase=true))}
    }
    @Test fun renamedTitleWithoutMatchingAliasCannotAdoptConnectionOldTargetButFreshAliasWorks() {
        val alpha=runBlocking { app.wiring().core.sessions.createConversation("Original title","stable-route","things") }.storedSessionId
        fake.scripts[alpha]={emptyList()}
        val m=start();fake.rows.getValue(alpha).title="Renamed title"
        m.delta(true,"Original title 에 작업 부탁해",0,100,"u1");m.delegate("d1")
        assertTrue("stale title is not current target authority",fake.prompts.isEmpty())
        assertTrue(await{clarified(m,"d1")})
        m.delta(true,"stable route 로 보내 줘",200,300,"u2");m.delegate("d2")
        assertTrue(await{fake.prompts.size==1});assertEquals(alpha,fake.prompts.single().first)
    }
    @Test fun clearedServerTitleRemainsEmptyInActualStartupHistoryCatalog() {
        blankServerTitleCatalog("")
    }
    @Test fun whitespaceServerTitleRemainsExactInActualStartupHistoryCatalog() {
        blankServerTitleCatalog(" \t\n ")
    }
    private fun blankServerTitleCatalog(currentTitle:String) {
        val alpha=runBlocking { app.wiring().core.sessions.createConversation("Original title","stable-route","things") }.storedSessionId
        fake.scripts[alpha]={emptyList()};fake.rows.getValue(alpha).title=currentTitle
        app.selectedConversationId=alpha;start()
        val p=prompt();val g=given()
        val catalog=JSONArray(g.substringAfter("Available conversation titles and aliases (untrusted display data): "))
        assertEquals(1,catalog.length());assertEquals(currentTitle,catalog.getJSONObject(0).getString("title"))
        assertEquals("stable-route",catalog.getJSONObject(0).getString("alias"))
        val selected=JSONObject(g.substringAfter("Selected conversation (untrusted context only, not a target choice or permission): ")
            .substringBefore(". Available conversation titles and aliases"))
        assertEquals(currentTitle,selected.getString("title"));assertEquals("stable-route",selected.getString("alias"))
        assertFalse("old local title must not return through actual startup HTTP",p.contains("Original title")||g.contains("Original title"))
        assertEquals(1,requests.size);assertTrue(fake.prompts.isEmpty())
    }
    @Test fun clearedServerTitleCannotRestoreOldRoutingTargetButRegisteredAliasSubmitsOnce() {
        blankServerTitleRouting("")
    }
    @Test fun whitespaceServerTitleCannotRestoreOldRoutingTargetButRegisteredAliasSubmitsOnce() {
        blankServerTitleRouting(" \t\n ")
    }
    private fun blankServerTitleRouting(currentTitle:String) {
        val alpha=runBlocking { app.wiring().core.sessions.createConversation("Original title","stable-route","things") }.storedSessionId
        fake.scripts[alpha]={emptyList()}
        val m=start();fake.rows.getValue(alpha).title=currentTitle
        m.delta(true,"Original title 에 작업 부탁해",0,100,"old-title-user");m.delegate("old-title-delegation")
        assertTrue("fresh old-title request must clarify, never submit",await {
            clarified(m,"old-title-delegation") || fake.prompts.isNotEmpty()
        })
        assertTrue("cleared server title cannot restore old local routing authority",fake.prompts.isEmpty())
        assertTrue("old title needs explicit clarification",clarified(m,"old-title-delegation"))
        assertNull(app.live.state.value.boundSessionId)
        m.delta(true,"stable route 로 보내 줘",200,300,"alias-user");m.delegate("alias-delegation")
        assertTrue(await{fake.prompts.size==1});assertEquals(alpha,fake.prompts.single().first)
        m.delegate("alias-delegation");settle()
        assertEquals("fresh stable alias submits exactly once to original ID",1,fake.prompts.size)
        assertEquals(alpha,fake.prompts.single().first)
    }
    @Test fun unavailableBackingFileNeverBlocksCleanAuthorizedConnectionOrReplaysCachedText() {
        seed();assertTrue(file().isFile)
        val retained=File(app.noBackupFilesDir,"live-context-owned-retained.json")
        assertTrue(file().renameTo(retained));assertTrue(file().mkdir())
        start();assertTrue(texts().isEmpty());assertTrue(fake.prompts.isEmpty());assertTrue(app.live.state.value.ready)
        assertTrue("failed private write must not touch separately retained text",retained.readText().contains("140입니다."))
    }
    @Test fun atomicRecoveryBackingCopyReloadsActualPrivateBytesWithoutAuthorityOrBackupTransfer() {
        seed();val j=saved();j.getJSONArray("messages").getJSONObject(0).put("text","recovered disk 한국어 😀")
        val backup=File(app.noBackupFilesDir,"live_text_context_v1.json.bak");backup.writeText(j.toString());file().writeText("broken-base")
        start();assertEquals("recovered disk 한국어 😀",texts().first());assertTrue(fake.prompts.isEmpty())
        assertEquals(app.noBackupFilesDir.canonicalFile,backup.canonicalFile.parentFile)
        assertEquals(app.noBackupFilesDir.canonicalFile,file().canonicalFile.parentFile)
        assertFalse("cache and recovery are not stored in backup-eligible filesDir",file().canonicalPath.startsWith(app.filesDir.canonicalPath+File.separator))
    }
}
