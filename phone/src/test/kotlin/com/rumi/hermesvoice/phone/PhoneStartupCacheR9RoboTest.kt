package com.rumi.hermesvoice.phone

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.auth.HermesBearerSession
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import io.mockk.every
import io.mockk.coEvery
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.rumi.hermesvoice.core.sessions.ConversationListing
import io.mockk.mockkObject
import io.mockk.mockk
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.MessageEvent
import com.rumi.hermesvoice.core.watchlink.*
import org.robolectric.Robolectric
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

/** Persisted private metadata is seeded before any ViewModel. Current HTTP list is held;
 * this tests actual startup/Compose ordering, not elapsed device performance. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w411dp-h1280dp")
class PhoneStartupCacheR9RoboTest : PhoneNavRoboBase() {
    private val key="session_list_titles_v1"
    private fun namespace(): String {
        val auth=app.tokens.load()!!
        val raw="${HermesDashboardEndpoint.parse(app.settings.dashboardUrl)}|${app.settings.profile}|${auth.provider}|${auth.userId}"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun snapshot(vararg pairs: Pair<String,String>) {
        val rows=JSONArray().apply { pairs.forEach { (id,title) -> put(JSONObject().put("id",id).put("title",title).put("last_active",1.0)) } }
        assertTrue(app.localStore.commitString(key,JSONObject().put("v",1).put("scope",namespace()).put("active",rows).put("archived",JSONArray()).toString()))
    }
    private fun cacheTitles(): List<String> = runCatching {
        val a=JSONObject(app.localStore.getString(key)!!).getJSONArray("active")
        (0 until a.length()).map { a.getJSONObject(it).getString("title") }
    }.getOrDefault(emptyList())
    private fun hold(): Pair<CountDownLatch,CountDownLatch> {
        val reached=CountDownLatch(1);val release=CountDownLatch(1)
        fake.listPageHook={ _,_ -> reached.countDown();check(release.await(30,TimeUnit.SECONDS)) { "fixture list latch not released" } }
        return reached to release
    }
    @Test fun E01_coldStartupPaintsSavedTitlesBeforeHeldHttpAndKeepsThemOffline() {
        val id=titled("Live title","cache")
        snapshot(id to "Saved title")
        assertEquals(listOf("Saved title"),cacheTitles())
        val (reached,release)=hold()
        fake.failListFromOffset=0
        try {
            val model=showing()
            waitFor("the real HTTP list is held") { reached.count==0L }
            assertEquals(listOf(id),shown(model))
            rule.onNodeWithText("Saved title").assertExists()
            assertTrue(model.state.value.conversations.single().stored.createdSource==null)
            assertEquals("no prompt/reply preview is cached","",model.state.value.conversations.single().stored.preview)
            release.countDown()
            waitFor("offline refresh reported") { model.state.value.status.startsWith("Couldn't") }
            assertEquals(listOf(id),shown(model))
        } finally { release.countDown() }
    }
    @Test fun E02_warmScreenRecreationAlsoReadsDurableSnapshotWithoutWaiting() {
        val id=titled("Live title","warm")
        snapshot(id to "Saved warm")
        val (reached,release)=hold()
        try {
            val first=showing()
            waitFor("first request held") { reached.count==0L }
            assertEquals(listOf(id),shown(first))
            controller!!.destroy();controller=null
            val recreated=showing()
            assertEquals(listOf(id),shown(recreated))
            rule.onNodeWithText("Saved warm").assertExists()
        } finally { release.countDown() }
    }
    @Test fun E03_completeRefreshReplacesSavedTitlesAndRemovesDeletedEntriesDurably() {
        val keep=titled("Fresh title","fresh")
        val gone=titled("Gone","gone")
        snapshot(keep to "Old title",gone to "Gone")
        fake.rows.remove(gone)
        val model=showing()
        waitFor("complete authoritative list") { shown(model)==listOf(keep) && model.state.value.conversations.single().stored.title=="Fresh title" }
        waitFor("new metadata persisted") { cacheTitles()==listOf("Fresh title") }
        val saved=app.localStore.getString(key)!!
        assertFalse(saved.contains("preview"))
        assertFalse(saved.contains("description"))
        assertFalse(saved.contains("access_token"))
    }
    @Test fun E04_logoutFencesHeldOldRefreshAndClearsDisplayedAndDurableTitles() {
        val id=titled("Live","logout")
        snapshot(id to "Saved")
        val (reached,release)=hold()
        try {
            val model=showing()
            waitFor("list held") { reached.count==0L }
            assertEquals(listOf(id),shown(model))
            model.signOut()
            assertTrue(model.state.value.conversations.isEmpty())
            release.countDown()
            waitFor("logout clears durable cache") { cacheTitles().isEmpty() }
            pump()
            assertTrue(model.state.value.conversations.isEmpty())
        } finally { release.countDown() }
    }
    @Test fun E05_explicitRevocationPrunesCacheButPartialScanDoesNotRevokeUnseenRows() {
        val revoked=titled("Revoked","revoked")
        val unseen=titled("Unseen","unseen")
        snapshot(revoked to "Revoked",unseen to "Unseen")
        fake.setCreatedSource(revoked,"discord")
        repeat(130) { fake.addForeignRow("cache-noise-$it","cron") }
        fake.failListFromOffset=100
        val model=showing()
        waitFor("partial read truth") { model.state.value.status.contains("Only 100 of ") }
        assertEquals(listOf(unseen),shown(model))
        waitFor("revoked title removed from persisted display") { cacheTitles()==listOf("Unseen") }
        assertTrue(fake.prompts.isEmpty())
    }
    @Test fun C01_profileAndAccountMismatchNeverRestoreOtherNamespaceAndCorruptCacheFallsBack() {
        val id=titled("Current","scope")
        snapshot(id to "Saved")
        app.tokens.save(HermesBearerSession(fake.accessToken,fake.refreshToken,null,"basic","different-user"))
        val (reached,release)=hold()
        try {
            val model=showing()
            waitFor("request held") { reached.count==0L }
            assertTrue(model.state.value.conversations.isEmpty())
            model.saveConnection(fake.baseUrl,"other-profile")
            release.countDown()
            assertTrue(model.state.value.conversations.isEmpty())
        } finally { release.countDown() }
        assertEquals("other-profile",app.settings.profile)
        assertTrue(app.tokens.load()==null)
    }
    @Test fun C02_corruptSnapshotFallsBackToNetworkWithoutChangingAuthoritativeRegistry() {
        val id=titled("Current","corrupt")
        app.localStore.commitString(key,"not json")
        val (reached,release)=hold()
        try {
            val model=showing()
            waitFor("request held") { reached.count==0L }
            assertTrue(shown(model).isEmpty())
            release.countDown()
            waitFor("normal remote list") { shown(model)==listOf(id) }
            assertTrue(app.wiring().core.sessions.registry.find(id)!=null)
        } finally { release.countDown() }
    }
    @Test fun E07_archiveUpdatesTheSavedActiveAndArchivedNamespacesIndependently() {
        val id=titled("Archive target","archive")
        snapshot(id to "Archive target")
        val model=showing()
        waitFor("active loaded") { shown(model)==listOf(id) && model.state.value.status.isEmpty() }
        model.setArchived(app.wiring().core.sessions.registry.find(id)!!,true)
        waitFor("archive removed from active display") { shown(model).isEmpty() && fake.rows.getValue(id).archived }
        waitFor("archive removed from active snapshot") { cacheTitles().isEmpty() }
        model.setShowArchived(true)
        waitFor("archived list loaded") { shown(model)==listOf(id) && model.state.value.status.isEmpty() }
        waitFor("archived namespace saved") {
            runCatching { JSONObject(app.localStore.getString(key)!!).getJSONArray("archived").length()==1 }.getOrDefault(false)
        }
        val (_,release)=hold()
        try {
            model.setShowArchived(false)
            assertTrue(shown(model).isEmpty())
        } finally { release.countDown() }
    }
    @Test fun E08_delayedOldDiskWriteDoesNotBlockFirstPaintOrResurrectAfterScopeChange() {
        val id=titled("Current","disk")
        snapshot(id to "Saved")
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val first=java.util.concurrent.atomic.AtomicBoolean(true)
        mockkObject(app.localStore)
        every { app.localStore.commitString(key,any()) } answers {
            if (first.getAndSet(false)) { entered.countDown();check(release.await(30,TimeUnit.SECONDS)) }
            callOriginal()
        }
        try {
            val model=showing()
            waitFor("old cache commit is held off the main thread") { entered.count==0L }
            assertEquals(listOf(id),shown(model))
            model.saveConnection(fake.baseUrl,"new-profile")
            assertTrue(shown(model).isEmpty())
            release.countDown()
            waitFor("new-scope clear follows old write") { cacheTitles().isEmpty() }
            assertTrue(shown(model).isEmpty())
        } finally { release.countDown() }
    }
    @Test fun E09_cachedRowNeverGrantsHistoryOrSubmitAuthorityAfterMarkerRevocation() {
        val id=titled("Cached","authority")
        snapshot(id to "Cached")
        fake.setCreatedSource(id,"discord")
        val (reached,release)=hold()
        try {
            val model=showing()
            waitFor("list held") { reached.count==0L }
            assertEquals(listOf(id),shown(model))
            row(id).performClick()
            waitFor("real per-action verification refused") { model.state.value.status.startsWith("Couldn't") }
            assertTrue(model.state.value.selected==null)
            assertTrue(fake.prompts.isEmpty())
            assertFalse(fake.restLog.any { it.startsWith("GET /api/sessions/$id/messages?") })
        } finally { release.countDown() }
    }
    private fun bridgeRequest(req: String, sent: java.util.concurrent.ConcurrentLinkedQueue<ByteArray>): org.robolectric.android.controller.ServiceController<PhoneWatchListenerService> {
        val messages=Wearable.getMessageClient(app)
        every { messages.sendMessage(any(),eq(WatchLinkPaths.READER_RESPONSE),any()) } answers {
            sent += thirdArg<ByteArray>();Tasks.forResult(1)
        }
        val service=Robolectric.buildService(PhoneWatchListenerService::class.java).create()
        val event=mockk<MessageEvent>()
        every { event.path } returns WatchLinkPaths.READER_REQUEST
        every { event.sourceNodeId } returns "watch-node"
        every { event.data } returns ReaderRequest(req,ReaderKind.SESSIONS).encode()
        service.get().onMessageReceived(event)
        return service
    }
    @Test fun E10_actualPhoneListenerStampsTheExistingAuthEndpointProfileNamespace() {
        val id=titled("Bridge","bridge")
        showWith(id)
        val sent=java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
        val service=bridgeRequest("r9-bridge-current",sent)
        try {
            waitFor("reader response sent") { sent.isNotEmpty() }
            val response=JSONObject(String(sent.single()))
            assertEquals(namespace(),response.optString("scope"))
            assertTrue(response.getBoolean("ok"))
            assertEquals(id,response.getJSONArray("sessions").getJSONObject(0).getString("id"))
            assertFalse(response.toString().contains("access_token"))
            assertTrue(fake.prompts.isEmpty())
        } finally { service.destroy() }
    }
    @Test fun E11_actualPhoneListenerDoesNotRelabelHeldOldScopeRowsAsANewProfile() {
        val id=titled("Old scope","oldscope")
        showWith(id)
        val (reached,release)=hold()
        val sent=java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
        val service=bridgeRequest("r9-bridge-held",sent)
        try {
            waitFor("actual listener list is held") { reached.count==0L }
            app.settings.profile="new-scope"
            release.countDown()
            waitFor("bounded response sent") { sent.isNotEmpty() }
            val response=JSONObject(String(sent.single()))
            assertFalse(response.getBoolean("ok"))
            assertEquals(ReaderError.UNAVAILABLE,response.getString("error"))
            assertEquals(namespace(),response.getString("scope"))
            assertEquals(0,response.getJSONArray("sessions").length())
            assertTrue(fake.prompts.isEmpty())
        } finally { release.countDown();service.destroy() }
    }
    @Test fun E06_cacheWriteFailureIsReportedWithoutDamagingTheRegistryOrFreshList() {
        val id=titled("Fresh","write")
        mockkObject(app.localStore)
        every { app.localStore.commitString(key,any()) } returns false
        val model=showing()
        waitFor("fresh list remains usable") { shown(model)==listOf(id) }
        waitFor("cache failure is honest") { model.state.value.status.contains("not saved") }
        assertTrue(app.wiring().core.sessions.registry.find(id)!=null)
        assertTrue(fake.prompts.isEmpty())
    }
    @Test fun E12_warmRecreationCannotResurrectADeletedTitleWhileTheNewSnapshotWriteIsHeld() {
        val keep=titled("Current","warm-current")
        val gone=titled("Gone","warm-gone")
        snapshot(keep to "Current",gone to "Gone")
        fake.rows.remove(gone)
        val entered=CountDownLatch(1);val diskRelease=CountDownLatch(1)
        val first=java.util.concurrent.atomic.AtomicBoolean(true)
        mockkObject(app.localStore)
        every { app.localStore.commitString(key,any()) } answers {
            if(first.getAndSet(false)) { entered.countDown();check(diskRelease.await(30,TimeUnit.SECONDS)) }
            callOriginal()
        }
        var networkRelease: CountDownLatch?=null
        try {
            val model=showing()
            waitFor("new authoritative snapshot write held") { entered.count==0L }
            assertEquals(listOf(keep),shown(model))
            assertEquals("the prior persisted snapshot is still on storage",listOf("Current","Gone"),cacheTitles())
            val (reached,release)=hold();networkRelease=release
            controller!!.destroy();controller=null
            val recreated=showing()
            waitFor("recreated screen's current HTTP refresh is held") { reached.count==0L }
            assertEquals("recent authoritative rows win over older disk metadata on warm recreation",listOf(keep),shown(recreated))
            assertEquals("display reuse is not a fabricated durability claim",listOf("Current","Gone"),cacheTitles())
        } finally { diskRelease.countDown();networkRelease?.countDown() }
    }
    @Test fun E13_failedWriteStillReusesRecentTitlesOnWarmRecreationWithoutClaimingDurability() {
        val keep=titled("Fresh failed-write title","failed-warm")
        val gone=titled("Gone","failed-gone")
        snapshot(keep to "Old",gone to "Gone")
        fake.rows.remove(gone)
        mockkObject(app.localStore)
        every { app.localStore.commitString(key,any()) } returns false
        val model=showing()
        waitFor("write failure reported") { model.state.value.status.contains("not saved") }
        assertEquals(listOf(keep),shown(model))
        val (reached,release)=hold()
        try {
            controller!!.destroy();controller=null
            val recreated=showing()
            waitFor("current response held") { reached.count==0L }
            assertEquals("failed disk persistence must not erase recent display knowledge",listOf(keep),shown(recreated))
            assertEquals("Fresh failed-write title",recreated.state.value.conversations.single().stored.title)
            assertEquals(listOf("Old","Gone"),cacheTitles())
            assertNull(recreated.state.value.conversations.single().stored.createdSource)
            assertEquals("",recreated.state.value.conversations.single().stored.preview)
        } finally { release.countDown() }
    }
    @Test fun E14_olderCallerCannotOverwriteTheNewerCallersRecentOrDurableTitles() {
        val keep=titled("Keep","caller-keep")
        val gone=titled("Gone","caller-gone")
        snapshot(keep to "Keep",gone to "Gone")
        val repository=app.wiring().core.sessions
        val old=runBlocking { repository.listConversations(false) }
        val fresh=ConversationListing(old.conversations.filter { it.stored.id==keep })
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val calls=java.util.concurrent.atomic.AtomicInteger()
        mockkObject(repository)
        coEvery { repository.listConversations(any(),any(),any()) } coAnswers {
            if(calls.incrementAndGet()==1) { entered.complete(Unit);release.await();old } else fresh
        }
        val oldStore=ViewModelStore();val newStore=ViewModelStore()
        fun model(store: ViewModelStore)=ViewModelProvider(store,ViewModelProvider.AndroidViewModelFactory(app))[PhoneViewModel::class.java]
        try {
            val older=model(oldStore);older.refresh()
            waitFor("older caller result held") { entered.isCompleted }
            val newer=model(newStore);newer.refresh()
            waitFor("newer caller result persisted") { shown(newer)==listOf(keep) && cacheTitles()==listOf("Keep") }
            release.complete(Unit)
            val job=PhoneViewModel::class.java.getDeclaredField("listJob").also { it.isAccessible=true }.get(older) as kotlinx.coroutines.Job
            waitFor("older caller naturally finished") { job.isCompleted }
            assertEquals("old caller cannot resurrect the deleted title",listOf("Keep"),cacheTitles())
            assertEquals(listOf(keep),app.cachedSessionTitles(false).map { it.stored.id })
            assertEquals(listOf(keep),shown(newer))
        } finally { release.complete(Unit);oldStore.clear();newStore.clear() }
    }
    @Test fun C03_delayedCommitCompletesWithoutLosingTheRecentDisplayOnlyHint() {
        val id=titled("Current","hint-commit")
        snapshot(id to "Old")
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        mockkObject(app.localStore)
        every { app.localStore.commitString(key,any()) } answers {
            entered.countDown();check(release.await(30,TimeUnit.SECONDS));callOriginal()
        }
        try {
            val model=showing()
            waitFor("commit held") { entered.count==0L }
            assertEquals(listOf("Current"),app.cachedSessionTitles(false).map { it.stored.title })
            assertEquals(listOf("Old"),cacheTitles())
            release.countDown()
            waitFor("commit finishes") { cacheTitles()==listOf("Current") && model.state.value.status.isEmpty() }
            assertEquals(listOf("Current"),app.cachedSessionTitles(false).map { it.stored.title })
            assertNull(app.cachedSessionTitles(false).single().stored.createdSource)
        } finally { release.countDown() }
    }
    @Test fun C04_recentFailedWriteHintIsFencedByInvalidationAndAuthScopeChanges() {
        val id=titled("Current","hint-scope")
        snapshot(id to "Old")
        mockkObject(app.localStore)
        every { app.localStore.commitString(key,any()) } returns false
        val model=showing()
        waitFor("fresh list not saved") { model.state.value.status.contains("not saved") }
        val original=app.tokens.load()!!
        app.tokens.save(HermesBearerSession(fake.accessToken,fake.refreshToken,null,"basic","other-user"))
        assertTrue(app.cachedSessionTitles(false).isEmpty())
        app.tokens.save(original)
        app.invalidateSessionTitles()
        assertTrue("invalidated hint cannot restore old disk metadata",app.cachedSessionTitles(false).isEmpty())
        model.signOut()
        assertTrue(app.cachedSessionTitles(false).isEmpty())
        assertEquals("failed clear never fabricates disk success",listOf("Old"),cacheTitles())
    }
    @Test fun C05_recentActiveAndArchivedHintsStaySeparateAcrossFailedWrites() {
        val active=titled("Active","hint-active")
        val archived=titled("Archived","hint-archive")
        val repository=app.wiring().core.sessions
        runBlocking { repository.setArchived(archived,true) }
        val activeRows=runBlocking { repository.listConversations(false) }.conversations
        val archivedRows=runBlocking { repository.listConversations(true) }.conversations
        mockkObject(app.localStore)
        every { app.localStore.commitString(key,any()) } returns false
        val scope=app.sessionListScope();val version=app.sessionTitlesVersion
        assertEquals(false,runBlocking { app.saveSessionTitles(scope,version,false,activeRows) { true } })
        assertEquals(false,runBlocking { app.saveSessionTitles(scope,version,true,archivedRows) { true } })
        assertEquals(listOf(active),app.cachedSessionTitles(false).map { it.stored.id })
        assertEquals(listOf(archived),app.cachedSessionTitles(true).map { it.stored.id })
        assertNull(app.localStore.getString(key))
    }
}
