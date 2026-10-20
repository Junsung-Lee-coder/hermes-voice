package com.rumi.hermesvoice.phone

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.*
import com.rumi.hermesvoice.core.notify.*
import com.rumi.hermesvoice.core.sessions.*
import com.rumi.hermesvoice.core.watchlink.*
import io.mockk.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual native code exchange, repository, listener and OS-facing notification boundaries.
 * Loopback synthetic auth/server, mocked GMS and keystore; not installed-device proof. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk=[34])
class FinalBoundariesR10bRoboTest : PhoneRoboBase() {
    private val stores=mutableListOf<ViewModelStore>()
    @Before fun setup() { baseSetUp(false) }
    @After fun cleanup() { stores.forEach { it.clear() };baseTearDown() }
    private fun model():PhoneViewModel {
        val store=ViewModelStore();stores+=store
        return ViewModelProvider(store,ViewModelProvider.AndroidViewModelFactory(app))[PhoneViewModel::class.java]
    }
    private fun waitFor(what:String,ms:Long=8_000,done:()->Boolean)=assertTrue(what,await(ms,done))
    private fun signIn(vm:PhoneViewModel) {
        val listField=PhoneViewModel::class.java.getDeclaredField("listJob").also { it.isAccessible=true }
        val previous=listField.get(vm)
        vm.signIn()
        var launch:Intent?=null
        waitFor("actual signIn launches native authorize before any credentials") {
            launch=launch?:shadowOf(app).nextStartedActivity;launch!=null
        }
        val url=launch!!.data.toString().toHttpUrl()
        assertEquals("/auth/native/authorize",url.encodedPath)
        fake.expectedChallenge=url.queryParameter("code_challenge")!!
        val redirect=url.queryParameter("redirect_uri")!!.toHttpUrl().newBuilder()
            .addQueryParameter("code","gw-code").addQueryParameter("state",url.queryParameter("state")).build()
        runBlocking(Dispatchers.IO) { app.http.newCall(okhttp3.Request.Builder().url(redirect).build()).execute().use { assertTrue(it.isSuccessful) } }
        // The real UI refresh owns repository work that resumes on Main. Join it by pumping,
        // never block that Main thread with a later runBlocking(create) while it holds the lock.
        waitFor("post-login real list refresh naturally joins") {
            val fresh=listField.get(vm)
            fresh !== previous && (fresh as? Job)?.isCompleted==true
        }
        check(vm.state.value.signedIn && app.tokens.load()!=null) { "Synthetic native login did not succeed" }
    }
    private fun alerts()=app.getSystemService(NotificationManager::class.java).activeNotifications.filter { it.notification.channelId in setOf("reply_arrival","reply_arrival_quiet") && !it.tag.endsWith("#notice") }
    @Test fun F01_signedOutActualSignInBindsVerifiedExistingAndCreatedCallbacks() {
        assertNull(app.tokens.load());assertNull(app.existingWiring())
        val id=fake.addStoredSession(AppSources.CONVERSATION,"Existing auth gap",false) { emptyList() }
        fake.injectFinalRow(id,"historical",1_700_000_090.0,"auth-old")
        fake.messagesHook={_,_,_->503};fake.reconciliationMessagesHook=fake.messagesHook
        val vm=model();signIn(vm)
        waitFor("authenticated marked session registered despite failed first body") { app.wiring().core.sessions.registry.find(id)!=null }
        val created=runBlocking { withTimeout(8_000) { app.wiring().core.sessions.createConversation("New voice target","new-auth","synthetic") } }
        val root=app.localStore.getString(ReceivedConversations.KEY)?.let(::JSONObject)
        val sessions=root?.optJSONObject("sessions")
        assertTrue("R10-F01: authenticated existing callback must establish tracking before first history failure",sessions?.optJSONObject(id)?.has("tracking_after")==true)
        assertEquals("new voice-session creation must establish empty frontier",0L,sessions!!.getJSONObject(created.storedSessionId).getLong("cursor"))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        fake.injectFinalRow(id,"later retained final",1_700_000_110.0,"auth-late")
        fake.injectFinalRow(created.storedSessionId,"new voice retained final",1_700_000_111.0,"auth-created-late")
        fake.messagesHook=null;fake.reconciliationMessagesHook=null
        runBlocking { withTimeout(8_000) { app.finalRecovery.reconcile() } }
        assertEquals(2,alerts().size);assertTrue(fake.rawPrompts.isEmpty())
    }
    @Test fun F01_actualNativeAccountReplacementRejectsLateOldCoreNamespace() {
        val vm=model();signIn(vm)
        val old=app.wiring();val ns=app.bodyScope()
        val original=fake.server.dispatcher
        fake.server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):okhttp3.mockwebserver.MockResponse {
                val response=original.dispatch(request)
                return if(request.requestUrl!!.encodedPath=="/auth/native/token") response.setBody(JSONObject(response.getBody()!!.clone().readUtf8()).put("user_id","replacement-user").toString()) else response
            }
        }
        try {
            signIn(vm)
            assertEquals("replacement-user",app.tokens.load()!!.userId)
            assertNotEquals(ns,app.bodyScope());assertNotSame(old,app.wiring())
            oldCoreRefused(old,"late-a-callback");assertTrue(fake.rawPrompts.isEmpty())
        } finally { fake.server.dispatcher=original }
    }
    private fun oldCoreRefused(old:PhoneApp.Wiring,alias:String) {
        val before=fake.rpcLog.count { it.optString("method")=="session.create" }
        val error=runCatching { runBlocking { withTimeout(8_000) { old.core.sessions.createConversation("Late old callback",alias,"synthetic") } } }.exceptionOrNull()
        assertTrue("retired construction cannot borrow replacement credentials",error is HermesAuthRequiredException)
        assertEquals("old core cannot create under the new login",before,fake.rpcLog.count { it.optString("method")=="session.create" })
    }
    @Test fun F01_retiredCoreCannotBorrowSameUserReplacementLoginCredentials() {
        val vm=model();signIn(vm);val old=app.wiring()
        signIn(vm)
        oldCoreRefused(old,"refused-old-login")
        val current=runBlocking { withTimeout(8_000) { app.wiring().core.sessions.createConversation("Current login","current-login","synthetic") } }
        assertEquals(0L,app.received.cursor(current.storedSessionId))
        assertTrue(fake.rawPrompts.isEmpty())
    }
    @Test fun F01_legacySameGenerationTokenRefreshKeepsItsConstructionNamespace() {
        val vm=model();signIn(vm)
        // Installed pre-epoch credentials are valid; ordinary refresh must persist their
        // existing legacy epoch rather than masquerading as an explicit new login.
        check(app.getSharedPreferences(KeystoreTokenStore.PREFERENCES_NAME,android.content.Context.MODE_PRIVATE).edit().remove("display_generation_v1").commit())
        check(app.tokens.displayGeneration()=="legacy")
        val before=app.bodyScope();val core=app.wiring()
        app.tokens.save(app.tokens.load()!!.copy(accessToken=fake.accessToken))
        assertEquals("ordinary legacy refresh is not a login generation change",before,app.bodyScope())
        assertSame(core,app.wiring());assertTrue(fake.rawPrompts.isEmpty())
    }
    @Test fun F01_explicitSameUserLoginRebindsButTokenRefreshDoesNot() {
        val vm=model();signIn(vm)
        val original=app.wiring();val namespace=app.bodyScope();val origin=app.asyncActionOrigin(original)
        app.tokens.save(app.tokens.load()!!.copy(accessToken=fake.accessToken))
        assertSame("ordinary same-generation refresh retains core",original,app.wiring())
        signIn(vm)
        assertNotEquals("explicit same-user login is a new private generation",namespace,app.bodyScope())
        assertNotSame(original,app.wiring());assertFalse(app.asyncActionCurrent(origin))
        oldCoreRefused(original,"old-auth")
        assertTrue(fake.rawPrompts.isEmpty())
    }
    private fun failedVoiceAfterRealLogin(newTarget:Boolean) {
        val idBefore=if(newTarget) null else fake.addStoredSession(AppSources.CONVERSATION,"Existing voice",false) { emptyList() }
        fake.messagesHook={_,_,_->503};fake.reconciliationMessagesHook=fake.messagesHook
        val vm=model();signIn(vm)
        if(idBefore!=null) waitFor("existing voice target registered") { app.wiring().core.sessions.registry.find(idBefore)!=null }
        val core=app.wiring().core
        val id=idBefore?:runBlocking { withTimeout(8_000) { core.sessions.createConversation("New voice","new-voice-boundary","synthetic") } }.storedSessionId
        fake.scripts[id]={listOf(FakeHermesDashboard.complete("accepted late voice final"))};fake.turnBehavior={"hold"}
        val request=com.rumi.hermesvoice.core.voice.VoiceTurnRequest("login-voice-boundary",VoiceOrigin.PHONE,TestAudio.speechWav(),"audio/wav",
            com.rumi.hermesvoice.core.voice.PlaybackSink { _,_-> },routing=com.rumi.hermesvoice.core.voice.TurnRouting.Direct(id))
        val work=app.appScope.async { core.orchestrator.run(request) }
        try {
            waitFor("real voice submitted exactly once before reply lookup loss") { fake.heldTurns.containsKey(id) }
            fake.replayEpoch="voice-new-epoch";fake.dropGateway()
            waitFor("accepted voice caller finishes honestly",20_000) { work.isCompleted }
            assertEquals(1,fake.rawPrompts.count { it.first==id })
            // The fixture metadata cutoff is 1700000100; this retained NEW final must
            // follow it, not use addMessage's historical-default timestamp (< cutoff).
            fake.finishHeldTurn(id,"accepted-voice-final-uid",finalTimestamp=1_700_000_110.0)
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            fake.messagesHook=null;fake.reconciliationMessagesHook=null
            runBlocking { withTimeout(8_000) { app.finalRecovery.reconcile() } }
            assertEquals("R10-F01: admitted voice request must freeze authenticated construction namespace",app.bodyScope(),request.namespace)
            assertTrue("accepted late voice final keeps notification opportunity",alerts().any { it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().contains("accepted late voice final") })
            assertEquals(1,fake.rawPrompts.count { it.first==id })
        } finally { fake.messagesHook=null;fake.reconciliationMessagesHook=null;runBlocking { withTimeout(8_000) { work.join() } } }
    }
    @Test fun F01_signedOutExistingVoiceLookupFailureRecoversWithOneSubmit()=failedVoiceAfterRealLogin(false)
    @Test fun F01_signedOutNewVoiceLookupFailureRecoversWithOneSubmit()=failedVoiceAfterRealLogin(true)
    private fun authenticate() { app.tokens.save(com.rumi.hermesvoice.core.auth.HermesBearerSession(fake.accessToken,fake.refreshToken,null,"basic","jun"));app.wiring() }
    private fun deliver(path:String,json:JSONObject,node:String="watch-node",manifest:Boolean=false) {
        val intent=Intent("com.google.android.gms.wearable.MESSAGE_RECEIVED").setPackage(app.packageName).setData(Uri.parse("wear://$node$path"))
        if(manifest) {
            val resolved=app.packageManager.queryIntentServices(intent,0)
            assertTrue("R10-F02: merged manifest must resolve final receipt and start listener",resolved.any { it.serviceInfo.name==PhoneWatchListenerService::class.java.name })
            app.startService(intent.setClass(app,PhoneWatchListenerService::class.java))
            assertNotNull(shadowOf(app).nextStartedService)
        }
        val controller=Robolectric.buildService(PhoneWatchListenerService::class.java).create()
        try {
            val event=mockk<MessageEvent>();every { event.path } returns path;every { event.sourceNodeId } returns node;every { event.data } returns json.toString().toByteArray()
            controller.get().onMessageReceived(event)
        } finally { controller.destroy() }
    }
    @Test fun F02_manifestStartedPostedReadReceiptRoundTripKeepsNodeAndScopeFences() {
        authenticate();val id=conversation("receipt-boundary");val ns=app.bodyScope()!!
        val wire=ConcurrentLinkedQueue<Triple<String,String,ByteArray>>()
        val client=mockk<MessageClient>();every { Wearable.getMessageClient(any<Context>()) } returns client
        every { client.sendMessage(any(),any(),any()) } answers { wire+=Triple(firstArg(),secondArg(),thirdArg<ByteArray>().copyOf());Tasks.forResult(1) }
        val m=com.rumi.hermesvoice.core.net.HistoryMessage(2,"assistant","receipt synthetic",200.0,messageUid="receipt-uid",finishReason="stop")
        app.received.scoped(ns) { checkpoint(id,0);ingest(id,listOf(m),false,true,"watch-node") }
        app.finalRecovery.reader("watch-node",ReaderResponse("reader-receipt",ReaderKind.HISTORY,true,sessionId=id,scope=app.sessionListScope(),bodyScope=ns))
        val identity=ReceivedConversations.identity(ns,id,2,"receipt-uid")
        fun packet(scope:String=ns)=JSONObject().put("v",1).put("scope",scope).put("posted",JSONArray().put(identity)).put("read",JSONArray().put(identity))
        deliver(FinalRecovery.RECEIPT,packet(),"other-node");settle()
        assertFalse(app.received.canonicalRow(id,2)!!.getBoolean("watch_done"));assertFalse(app.received.canonicalRow(id,2)!!.getBoolean("read"))
        deliver(FinalRecovery.RECEIPT,packet("0".repeat(64)));settle()
        assertFalse(app.received.canonicalRow(id,2)!!.getBoolean("watch_done"))
        deliver(FinalRecovery.RECEIPT,packet(),manifest=true)
        waitFor("actual listener commits posted and foreground read receipt") { app.received.canonicalRow(id,2)?.let { it.optBoolean("watch_done")&&it.optBoolean("read") }==true }
        waitFor("receipt ack returns to exact source") { wire.any { it.first=="watch-node" && it.second==FinalRecovery.RECEIPT_ACK && JSONObject(String(it.third)).getJSONArray("read").optString(0)==identity } }
        assertTrue(fake.rawPrompts.isEmpty())
    }
    @Test fun F04_originalPrivateCanonicalFinalRemainsQuietAcrossDeniedRestartAndEnrichment() {
        authenticate();val id=conversation("private-boundary");val ns=app.bodyScope()!!
        val devices=FakeDevices(listOf(Gear.speaker,Gear.a2dp))
        val privatePolicy=com.rumi.hermesvoice.core.headset.HeadsetPolicy({true},devices)
        org.robolectric.util.ReflectionHelpers.setField(app,"headset\$delegate",lazyOf(privatePolicy))
        assertNotNull(privatePolicy.output())
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val msgId=2L
        val reply=FinalReply("private-canonical",id,VoiceOrigin.WATCH,false,false,FinalReplySource.OWN,text="private answer",quiet=true,canonicalRowId=msgId,namespace=ns)
        runBlocking { assertTrue(app.replyAlerts.deliver(reply)) }
        fake.injectFinalRow(id,"private answer",300.0,"private-uid",reason="")
        // Address is taken from actual retained server bytes, including the creation seed.
        val actual=fake.rows.getValue(id).messages.last().getLong("id")
        assertEquals(msgId,actual)
        runBlocking { withTimeout(8_000) { app.finalRecovery.reconcile() } }
        assertTrue(alerts().isEmpty())
        // Same durable identity is enriched after losing the current headset/private-output policy.
        devices.disconnectAll();assertNull(privatePolicy.output())
        synchronized(fake.rows.getValue(id).messages) { fake.rows.getValue(id).messages.last().put("finish_reason","stop") }
        val recovered=FinalRecovery(app)
        org.robolectric.util.ReflectionHelpers.setField(app,"received\$delegate",lazyOf(ReceivedConversations(app.localStore,app::bodyScope)))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { withTimeout(8_000) { recovered.reconcile() } }
        assertEquals(1,alerts().size)
        assertEquals("R10-F04: original quiet survives durable recovery with no current headset","reply_arrival_quiet",alerts().single().notification.channelId)
        assertNull(alerts().single().notification.sound)
        assertTrue(app.received.pending(true).isEmpty());assertTrue(fake.rawPrompts.isEmpty())
    }
    @Test fun F04_stricterCurrentPrivatePolicyIsDurableBeforeDeniedPost() {
        authenticate();val id=conversation("stricter-boundary");val ns=app.bodyScope()!!
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        fake.injectFinalRow(id,"stricter canonical",401.0,"stricter-uid")
        val address=fake.rows.getValue(id).messages.last().getLong("id")
        runBlocking { app.replyAlerts.deliver(FinalReply("stricter-canonical",id,VoiceOrigin.PHONE,false,false,FinalReplySource.OWN,text="stricter canonical",quiet=false,canonicalRowId=address,namespace=ns)) }
        runBlocking { withTimeout(8_000) { app.finalRecovery.reconcile() } }
        val devices=FakeDevices(listOf(Gear.speaker,Gear.a2dp))
        val policy=com.rumi.hermesvoice.core.headset.HeadsetPolicy({true},devices)
        org.robolectric.util.ReflectionHelpers.setField(app,"headset\$delegate",lazyOf(policy))
        assertNotNull(policy.output())
        runBlocking { withTimeout(8_000) { app.finalRecovery.reconcile() } }
        devices.disconnectAll();assertNull(policy.output())
        org.robolectric.util.ReflectionHelpers.setField(app,"received\$delegate",lazyOf(ReceivedConversations(app.localStore,app::bodyScope)))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { withTimeout(8_000) { FinalRecovery(app).reconcile() } }
        assertEquals("stricter private admission cannot become speaker permission after loss","reply_arrival_quiet",alerts().single().notification.channelId)
        assertTrue(fake.rawPrompts.isEmpty())
    }
    @Test fun F04_knownNonprivateCanonicalFinalUsesNormalChannelAndCancelledFinalDoesNotPost() {
        authenticate();val id=conversation("normal-boundary");val ns=app.bodyScope()!!
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        fake.injectFinalRow(id,"normal canonical",400.0,"normal-uid")
        val address=fake.rows.getValue(id).messages.last().getLong("id")
        runBlocking { app.replyAlerts.deliver(FinalReply("normal-canonical",id,VoiceOrigin.PHONE,false,false,FinalReplySource.OWN,text="normal canonical",quiet=false,canonicalRowId=address,namespace=ns)) }
        runBlocking { withTimeout(8_000) { app.finalRecovery.reconcile() } }
        assertEquals("reply_arrival",alerts().single().notification.channelId)
        runBlocking { app.replyAlerts.deliver(FinalReply("normal-canonical",id,VoiceOrigin.PHONE,false,true,FinalReplySource.OWN,canonicalRowId=address,namespace=ns)) }
        runBlocking { withTimeout(8_000) { app.finalRecovery.reconcile() } }
        assertTrue(alerts().isEmpty());assertTrue(fake.rawPrompts.isEmpty())
    }
    @Test fun F05_reloginWhileActualRequestedHistoryIsHeldCannotRelabelOldAnswer() {
        authenticate();val id=conversation("held-reader-origin")
        fake.injectRow(id,"assistant","old account private body")
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        fake.messagesHook={_,_,_->entered.countDown();check(release.await(30,TimeUnit.SECONDS));0}
        val replies=ConcurrentLinkedQueue<ReaderResponse>();val client=mockk<MessageClient>()
        every { Wearable.getMessageClient(any<Context>()) } returns client
        every { client.sendMessage(any(),any(),any()) } answers { if(secondArg<String>()==WatchLinkPaths.READER_RESPONSE) ReaderResponse.decode(thirdArg())?.let(replies::add);Tasks.forResult(1) }
        val request=ReaderRequest("old-reader-origin",ReaderKind.HISTORY,id)
        val service=Robolectric.buildService(PhoneWatchListenerService::class.java).create()
        try {
            val event=mockk<MessageEvent>();every { event.path } returns WatchLinkPaths.READER_REQUEST;every { event.sourceNodeId } returns "watch-node";every { event.data } returns request.encode()
            service.get().onMessageReceived(event)
            waitFor("actual requested history is held") { entered.count==0L }
            app.revokeUnplayedReplies();app.tokens.clear();authenticate()
            release.countDown();fake.messagesHook=null
            waitFor("old response finishes without private content") { replies.isNotEmpty() }
            assertFalse("same stable user is not the same private login generation",replies.single().ok)
            assertTrue(replies.single().messages.isEmpty());assertTrue(fake.rawPrompts.isEmpty())
        } finally { release.countDown();fake.messagesHook=null;service.destroy() }
    }
    @Test fun F05_healthyReaderDoesNotWaitForUnrelatedSuspendedRecoveryPastWatchBudget() {
        authenticate();val blocked=conversation("blocked-recovery");val healthy=conversation("healthy-reader")
        fake.injectRow(healthy,"assistant","healthy reader canonical")
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        fake.reconciliationMessagesHook={id,_,_->if(id==blocked) { entered.countDown();check(release.await(30,TimeUnit.SECONDS)) };0 }
        val recovery=app.appScope.launch { app.finalRecovery.reconcile() }
        val replies=ConcurrentLinkedQueue<ReaderResponse>();val client=mockk<MessageClient>()
        every { Wearable.getMessageClient(any<Context>()) } returns client
        every { client.sendMessage(any(),any(),any()) } answers {
            if(secondArg<String>()==WatchLinkPaths.READER_RESPONSE) { assertEquals("watch-node",firstArg<String>());ReaderResponse.decode(thirdArg())?.let(replies::add) };Tasks.forResult(1)
        }
        try {
            waitFor("only unrelated reconciliation IO is suspended") { entered.count==0L }
            val request=ReaderRequest("healthy-reader-request",ReaderKind.HISTORY,healthy)
            val pending=WatchReaderState().select(healthy,request.reqId)
            val service=Robolectric.buildService(PhoneWatchListenerService::class.java).create()
            try {
                val event=mockk<MessageEvent>();every { event.path } returns WatchLinkPaths.READER_REQUEST;every { event.sourceNodeId } returns "watch-node";every { event.data } returns request.encode()
                service.get().onMessageReceived(event)
                val answered=await(WatchReaderLimits.REQUEST_TIMEOUT_MS) { replies.any { it.reqId==request.reqId } }
                val reduced=if(answered) pending.onResponse(replies.first { it.reqId==request.reqId }) else pending.onTimeout(request.reqId)
                assertEquals("R10-F05: healthy authoritative reader must beat unchanged Watch timeout",LoadStatus.READY,reduced.selectedHistory?.status)
                assertEquals("healthy reader canonical",reduced.selectedHistory!!.messages.last().text)
                assertFalse("recovery obligation lives independently",recovery.isCompleted)
                assertTrue(fake.rawPrompts.isEmpty())
            } finally { service.destroy() }
        } finally {
            release.countDown();fake.reconciliationMessagesHook=null
            runBlocking { withTimeout(8_000) { recovery.join() } }
        }
    }
}
