package com.rumi.hermesvoice.phone

import androidx.lifecycle.ViewModelStore
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.KeyValueStore
import com.rumi.hermesvoice.core.auth.HermesBearerSession
import com.rumi.hermesvoice.core.sessions.OwnedSessionRegistry
import com.rumi.hermesvoice.core.watchlink.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real client/registry/ViewModel/listener schedules. MockWebServer and Data Layer are synthetic.
 * All held workers are released and joined before the assertion; no UI-thread latch deadlock. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w411dp-h1280dp")
class PhoneCacheClosureR9dRoboTest : PhoneNavRoboBase() {
    private val key="session_list_titles_v1"
    private fun snapshot(vararg rows: Pair<String,String>) {
        val data=JSONArray().apply { rows.forEach { (id,title) -> put(JSONObject().put("id",id).put("title",title).put("last_active",1.0)) } }
        check(app.localStore.commitString(key,JSONObject().put("v",1).put("scope",app.sessionListScope()).put("active",data).put("archived",JSONArray()).toString()))
    }
    private fun reject() { fake.rejectAccessTokens=setOf(fake.accessToken);fake.refreshAccepted=false }
    private fun finished(model: PhoneViewModel) = PhoneViewModel::class.java.getDeclaredField("listJob").also { it.isAccessible=true }.get(model).let { it as? kotlinx.coroutines.Job }?.isCompleted==true
    private fun reader(sent: ConcurrentLinkedQueue<ByteArray>): org.robolectric.android.controller.ServiceController<PhoneWatchListenerService> {
        val messages=Wearable.getMessageClient(app)
        every { messages.sendMessage(any(),eq(WatchLinkPaths.READER_RESPONSE),any()) } answers { sent+=thirdArg<ByteArray>();Tasks.forResult(1) }
        val service=Robolectric.buildService(PhoneWatchListenerService::class.java).create()
        val event=mockk<MessageEvent>()
        every { event.path } returns WatchLinkPaths.READER_REQUEST
        every { event.sourceNodeId } returns "watch-node"
        every { event.data } returns ReaderRequest("r09d-auth-wire",ReaderKind.SESSIONS).encode()
        service.get().onMessageReceived(event)
        return service
    }
    @Test fun D01_realRejectedRefreshClearsPhoneVisibleHistoryAndSavedTitles() {
        val id=titled("Saved","auth-loss")
        seed(id,2)
        val model=showWith(id);openCard(model,id)
        check(model.state.value.history.isNotEmpty())
        waitFor("initial list committed") { app.localStore.getString(key)?.contains(id)==true }
        reject();model.refresh()
        waitFor("actual list refresh naturally finished") { finished(model) }
        check(fake.refreshes==1 && app.tokens.load()==null) { "setup must traverse real REST401/refresh401/token-clear" }
        assertFalse("observed current auth loss cannot remain signed in",model.state.value.signedIn)
        assertTrue(model.state.value.conversations.isEmpty())
        assertNull(model.state.value.selected)
        assertTrue(model.state.value.history.isEmpty())
        assertTrue(model.state.value.status.startsWith("Sign in"))
        waitFor("auth invalidation reaches storage") { app.localStore.getString(key)=="{}" }
        assertNull(app.selectedConversationId)
        assertTrue(fake.prompts.isEmpty())
    }
    @Test fun D02_realPhoneListenerEmitsSignInRequiredAfterClientClearsTokens() {
        val id=titled("Saved","wire-auth");snapshot(id to "Saved")
        reject()
        val sent=ConcurrentLinkedQueue<ByteArray>();val service=reader(sent)
        try {
            waitFor("actual listener response") { sent.isNotEmpty() }
            check(fake.refreshes==1 && app.tokens.load()==null)
            val wire=sent.single()
            println("R09D_AUTH_WIRE_BASE64="+Base64.getEncoder().encodeToString(wire))
            val expected=ReaderResponse("r09d-auth-wire",ReaderKind.SESSIONS,false,error=ReaderError.SIGN_IN_REQUIRED).encode()
            assertArrayEquals("same wire consumed by WatchCacheClosureR9dRoboTest",expected,wire)
            assertTrue(fake.prompts.isEmpty())
        } finally { service.destroy() }
    }
    @Test fun D03_progressivePublicationSurvivesWarmRecreationWhileNextRawPageIsHeld() {
        repeat(130) { fake.addForeignRow("r09d-noise-$it","cron") }
        val revoked=titled("Revoked","progress-revoked")
        val renamed=titled("Fresh name","progress-name")
        val unseen=titled("Unseen","progress-unseen")
        snapshot(revoked to "Revoked",renamed to "Old name",unseen to "Unseen")
        fake.setCreatedSource(revoked,"discord")
        // Fixture sorts by insertion; exclude unseen from the first page without claiming revocation.
        fake.rows.getValue(unseen).archived=true
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        fake.listPageHook={ offset,_ -> if(offset>=100) { entered.countDown();check(release.await(30,TimeUnit.SECONDS)) } }
        try {
            val first=showing()
            waitFor("next page held after actual progressive publication") { entered.count==0L && first.state.value.status.contains("100 of") }
            check(revoked !in shown(first) && first.state.value.conversations.first { it.stored.id==renamed }.stored.title=="Fresh name")
            check(unseen in shown(first)) { "incomplete unseen row must remain" }
            // Construct a real second ViewModel; no manual cache read substitutes for initialState.
            val store=ViewModelStore()
            try {
                val second=PhoneViewModel(app);store.put("r09d-second",second)
                assertEquals("published removal must not resurrect",setOf(renamed,unseen),shown(second).toSet())
                assertEquals("Fresh name",second.state.value.conversations.first { it.stored.id==renamed }.stored.title)
                assertTrue(second.state.value.conversations.all { it.stored.createdSource==null && it.stored.preview.isEmpty() })
                assertTrue("progress memory is not a disk commit",app.localStore.getString(key)!!.contains("Old name"))
            } finally { store.clear() }
        } finally { release.countDown() }
    }
    @Test fun D04_firstRealViewModelStateDoesNotWaitForActualRegistryCommit() {
        val id=titled("Current","registry-held");snapshot(id to "Saved")
        val registry=app.wiring().core.sessions.registry
        val store=OwnedSessionRegistry::class.java.getDeclaredField("store").also { it.isAccessible=true }.get(registry) as KeyValueStore
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val constructed=CountDownLatch(1)
        val error=AtomicReference<Throwable>();val created=AtomicReference<PhoneViewModel>()
        mockkObject(store)
        every { store.commitString(OwnedSessionRegistry.KEY,any()) } answers { entered.countDown();check(release.await(15,TimeUnit.SECONDS));callOriginal() }
        val writer=Thread { try { registry.replaceAll { it } } catch(t: Throwable) { error.set(t) } }
        val next=Thread { try { created.set(PhoneViewModel(app)) } catch(t: Throwable) { error.set(t) } finally { constructed.countDown() } }
        var beforeRelease=false
        writer.start()
        try {
            check(entered.await(5,TimeUnit.SECONDS)) { "actual registry commit not reached" }
            next.start()
            beforeRelease=constructed.await(2,TimeUnit.SECONDS)
        } finally {
            release.countDown();writer.join(5000);if(next.state!=Thread.State.NEW) next.join(5000)
        }
        check(!writer.isAlive && !next.isAlive && error.get()==null) { "fixture worker failed: ${error.get()?.javaClass?.simpleName}" }
        val owner=ViewModelStore()
        try {
            created.get()?.let { owner.put("r09d-created",it) }
            assertTrue("first state must exist BEFORE unrelated authoritative registry commit release",beforeRelease)
            assertEquals(listOf(id),shown(created.get()))
            assertEquals("Saved",created.get().state.value.conversations.single().stored.title)
        } finally { owner.clear() }
    }
    private fun heldRefresh(success: Boolean, action: (PhoneViewModel,CountDownLatch) -> Unit) {
        val id=titled("Current","late-refresh")
        val model=showWith(id)
        waitFor("initial scan ends") { finished(model) }
        val original=fake.server.dispatcher
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        fake.server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response=original.dispatch(request)
                if(request.requestUrl!!.encodedPath=="/auth/native/refresh") { entered.countDown();check(release.await(30,TimeUnit.SECONDS)) }
                return response
            }
        }
        fake.rejectAccessTokens=setOf(fake.accessToken);fake.refreshAccepted=success
        try {
            model.refresh();waitFor("real refresh answer is held") { entered.count==0L }
            action(model,release)
        } finally { release.countDown();fake.server.dispatcher=original }
    }
    @Test fun D05_oldRefreshRefusalCannotEraseNewlySignedInAccount() = heldRefresh(false) { model,release ->
        val replacement=HermesBearerSession("synthetic-new-access","synthetic-new-refresh",null,"basic","new-user")
        app.tokens.save(replacement);release.countDown()
        waitFor("old caller naturally ends") { finished(model) }
        assertEquals("old refresh refusal cannot clear newer credentials",replacement,app.tokens.load())
    }
    @Test fun D06_oldRefreshSuccessCannotOverwriteNewlySignedInAccount() = heldRefresh(true) { model,release ->
        val replacement=HermesBearerSession("synthetic-new-access","synthetic-new-refresh",null,"basic","new-user")
        app.tokens.save(replacement);release.countDown()
        waitFor("old caller naturally ends") { finished(model) }
        assertEquals("old refresh success cannot replace newer credentials",replacement,app.tokens.load())
    }
    @Test fun C01_ordinaryOfflineRetainsTitlesAndAuthentication() {
        val id=titled("Saved","offline-control");snapshot(id to "Saved")
        fake.failListFromOffset=0
        val model=showing();waitFor("offline is honestly reported") { model.state.value.status.startsWith("Couldn't") }
        assertEquals(listOf(id),shown(model));assertTrue(model.state.value.signedIn);assertNotNull(app.tokens.load())
    }
    @Test fun C02_missingUnownedAndRevokedDisplayShellsNeverGrantActionsOrRecipients() {
        val owned=titled("Owned","shell-owned")
        val foreign="20260101_120000_foreign1";fake.addForeignRow(foreign,"discord")
        val missing="20260101_120000_missing1"
        fake.setCreatedSource(owned,"discord")
        snapshot(owned to "Revoked",foreign to "Foreign",missing to "Missing")
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        fake.listPageHook={ _,_ -> entered.countDown();check(release.await(30,TimeUnit.SECONDS)) }
        try {
            val model=showing();waitFor("discovery held") { entered.count==0L }
            val rows=model.state.value.conversations
            check(rows.any { it.stored.id==owned })
            rows.forEach { row ->
                model.open(row.owned)
                waitFor("real history guard refuses ${row.stored.id}") { model.state.value.status.startsWith("Couldn't") }
                assertNull(model.state.value.selected)
                var refused=0
                runBlocking {
                    // Registered local alias edits are bookkeeping, not renewed marker eligibility.
                    if(row.stored.id==owned) app.wiring().core.sessions.updateDestination(row.stored.id,"changed","")
                    else try { app.wiring().core.sessions.updateDestination(row.stored.id,"changed","");error("unowned alias accepted") } catch(_: com.rumi.hermesvoice.core.SessionNotOwnedException) { }
                    try { app.wiring().core.sessions.setArchived(row.stored.id,true) } catch(_: com.rumi.hermesvoice.core.SessionNotOwnedException) { refused++ }
                    try { app.wiring().core.sessions.history(row.stored.id) } catch(_: com.rumi.hermesvoice.core.SessionNotOwnedException) { refused++ }
                    try { app.wiring().core.sessions.sendMessage(row.stored.id,"synthetic must not send") } catch(_: com.rumi.hermesvoice.core.SessionNotOwnedException) { refused++ }
                    try { app.wiring().core.sessions.guardedPort().submit(row.stored.id,"synthetic must not send",emptyList()) } catch(_: com.rumi.hermesvoice.core.SessionNotOwnedException) { refused++ }
                    try { app.wiring().core.sessions.requireDeliverable(row.stored.id) } catch(_: com.rumi.hermesvoice.core.SessionNotOwnedException) { refused++ }
                }
                assertEquals(5,refused)
            }
            assertTrue(app.wiring().core.sessions.allowlist(null).entries.isEmpty())
            assertTrue(fake.prompts.isEmpty())
            assertFalse(fake.restLog.any { it.contains("/messages?") })
            assertFalse(fake.restLog.any { it.startsWith("PATCH ") })
        } finally { release.countDown() }
    }
    @Test fun C03_invalidIdAndOversizedSnapshotDoNotHydrateOrTouchRegistry() {
        titled("Owned","shape")
        val registry=app.wiring().core.sessions.registry
        val original=registry.all()
        snapshot("bad/id" to "Bad")
        val store=ViewModelStore()
        try { val model=PhoneViewModel(app);store.put("shape",model);assertTrue(shown(model).isEmpty()) } finally { store.clear() }
        val rows=(0..100).map { "20260101_120000_${it.toString().padStart(8,'0')}" to "Overflow" }
        snapshot(*rows.toTypedArray())
        assertTrue(app.cachedSessionTitles(false).isEmpty())
        assertEquals(original,registry.all())
    }
}
