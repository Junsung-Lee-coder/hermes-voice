package com.rumi.hermesvoice.watch

import android.content.Context
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.watchlink.*
import io.mockk.*
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** A fresh actual Application instance attached to the native private prefs context, then
 * actual onCreate/loadSessions/response callers. No retained reader state is copied. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk=[34],shadows=[RecordingVibratorShadow::class])
class WatchStartupCacheR9RoboTest {
    private lateinit var original: WatchApp
    private val key="reader_session_titles_v1"
    private var phone: String?="phone-node"
    private val scope="a".repeat(64)
    private val a="20260101_120000_workwork"
    private val b="20260101_130000_otherone"
    private val c="20260101_140000_thirdone"
    private val requests=ConcurrentLinkedQueue<Pair<String,ReaderRequest>>()
    @Before fun setup() {
        original=RuntimeEnvironment.getApplication() as WatchApp
        mockkStatic(Wearable::class)
        val capabilities=mockk<CapabilityClient>()
        every { capabilities.addListener(any<CapabilityClient.OnCapabilityChangedListener>(),any<String>()) } returns Tasks.forResult(null)
        every { capabilities.getCapability(any(),any()) } answers {
            val nodes=phone?.let { id -> setOf(mockk<Node>().also { n -> every { n.id } returns id;every { n.isNearby } returns true }) }.orEmpty()
            Tasks.forResult(mockk<CapabilityInfo>().also { info -> every { info.nodes } returns nodes })
        }
        val messages=mockk<MessageClient>()
        every { messages.sendMessage(any(),any(),any()) } answers {
            if(secondArg<String>()==WatchLinkPaths.READER_REQUEST) ReaderRequest.decode(thirdArg<ByteArray>())?.let { requests += firstArg<String>() to it }
            Tasks.forResult(1)
        }
        every { Wearable.getCapabilityClient(any<Context>()) } returns capabilities
        every { Wearable.getMessageClient(any<Context>()) } returns messages
    }
    @After fun cleanup()=unmockkAll()
    private fun idle()=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    private fun snapshot(vararg ids: String) {
        val rows=JSONArray().apply { ids.forEach { put(JSONObject().put("id",it).put("title","Saved $it").put("last_active",1.0)) } }
        assertTrue(original.localStore.commitString(key,JSONObject().put("v",1).put("phone","phone-node").put("scope",scope).put("rows",rows).toString()))
    }
    private fun cold(): WatchApp = WatchApp().also {
        ReflectionHelpers.callInstanceMethod<Void>(it,"attach",ClassParameter.from(Context::class.java,original.baseContext))
        it.onCreate()
    }
    private fun read(app: WatchApp): Pair<String,ReaderRequest> {
        val before=requests.size
        app.loadSessions();idle()
        assertEquals(before+1,requests.size)
        return requests.last()
    }
    private fun deliver(app: WatchApp,request: Pair<String,ReaderRequest>,ids: List<String>,complete: Boolean=true,removed: List<String> = emptyList(),from: String=request.first,newScope: String=scope,error: String?=null) {
        val response=ReaderResponse(request.second.reqId,ReaderKind.SESSIONS,error==null,error=error,
            sessions=ids.map { ReaderSessionRow(it,"Fresh $it","alias","not persisted",2.0) })
        val wire=JSONObject(String(response.encode())).put("scope",newScope).put("sessions_complete",complete).put("removed_session_ids",JSONArray(removed))
        app.onReaderResponse(from,wire.toString().toByteArray());idle()
    }
    private fun awaitSaved(label: String, done: () -> Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while (!done() && System.nanoTime()<end) { idle();Thread.yield() }
        assertTrue(label,done())
    }
    private fun ids(app: WatchApp)=app.reader.value.sessions.rows.map { it.id }
    @Test fun E01_coldActualStartupHydratesSavedTitlesBeforeAnyPhoneResponse() {
        snapshot(a,b)
        val app=cold()
        assertEquals(listOf(a,b),ids(app))
        assertTrue(app.reader.value.sessions.rows.all { it.preview.isEmpty() && it.alias.isEmpty() })
        read(app)
        assertEquals(listOf(a,b),ids(app))
        assertEquals(LoadStatus.LOADING,app.reader.value.sessions.status)
        app.onForeground(true);app.onForeground(false)
        assertEquals("warm screen lifecycle keeps the list",listOf(a,b),ids(app))
    }
    @Test fun E02_freshResponseReplacesAndPersistsOnlyMinimalTitlesAcrossColdRecreation() {
        snapshot(a,b)
        val app=cold()
        deliver(app,read(app),listOf(c))
        assertEquals(listOf(c),ids(app))
        awaitSaved("current titles reached private store") { original.localStore.getString(key)?.contains("Fresh $c")==true }
        assertEquals(listOf(c),ids(cold()))
        val saved=original.localStore.getString(key)!!
        assertFalse(saved.contains("not persisted"))
        assertFalse(saved.contains("alias"))
    }
    @Test fun E03_offlineRefreshKeepsLastKnownDisplayWithoutGrantingAHistoryRead() {
        snapshot(a)
        val app=cold();phone=null
        app.loadSessions();idle()
        assertEquals("the missing phone is looked for again before offline is shown",LoadStatus.LOADING,app.reader.value.sessions.status)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3_100))
        assertEquals(listOf(a),ids(app))
        assertEquals(LoadStatus.OFFLINE,app.reader.value.sessions.status)
        assertTrue(requests.isEmpty())
    }
    @Test fun E04_knownNewPhoneOwnerInvalidatesCacheAndOldResponseCannotResurrectIt() {
        snapshot(a)
        val app=cold();val old=read(app)
        phone="different-phone"
        val current=read(app)
        assertTrue(ids(app).isEmpty())
        deliver(app,old,listOf(a))
        assertTrue(ids(app).isEmpty())
        deliver(app,current,listOf(b))
        assertEquals(listOf(b),ids(app))
        awaitSaved("current titles reached private store") { original.localStore.getString(key)?.contains("Fresh $b")==true }
        assertEquals(listOf(b),ids(cold()))
    }
    @Test fun E05_observedLogoutClearsTitlesAndSameNodeScopeChangeCannotReuseOldRows() {
        snapshot(a)
        val app=cold()
        assertEquals(listOf(a),ids(app))
        deliver(app,read(app),emptyList(),error=ReaderError.SIGN_IN_REQUIRED)
        assertTrue(ids(app).isEmpty())
        awaitSaved("logout cleared private snapshot") { original.localStore.getString(key)=="{}" }
        assertTrue(ids(cold()).isEmpty())
        deliver(app,read(app),listOf(b),newScope="b".repeat(64))
        assertEquals(listOf(b),ids(app))
        deliver(app,read(app),emptyList(),complete=false,newScope="c".repeat(64))
        assertTrue("new endpoint/profile/account namespace is not an old partial list",ids(app).isEmpty())
    }
    @Test fun E06_partialResponseKeepsUnseenRowsButExplicitRevocationAndCompleteResultRemoveThem() {
        snapshot(a,b,c)
        val app=cold()
        deliver(app,read(app),listOf(b),complete=false,removed=listOf(a))
        assertEquals(setOf(b,c),ids(app).toSet())
        deliver(app,read(app),listOf(b))
        assertEquals(listOf(b),ids(app))
        awaitSaved("the complete snapshot, not the earlier partial one, reached private store") {
            runCatching {
                val rows=JSONObject(original.localStore.getString(key)!!).getJSONArray("rows")
                (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }==listOf(b)
            }.getOrDefault(false)
        }
        assertEquals(listOf(b),ids(cold()))
    }
    @Test fun E07_cacheWriteFailureKeepsFreshListButReportsItWasNotSaved() {
        val app=cold()
        mockkObject(app.localStore)
        every { app.localStore.commitString(key,any()) } returns false
        deliver(app,read(app),listOf(a))
        assertEquals(listOf(a),ids(app))
        assertEquals(LoadStatus.READY,app.reader.value.sessions.status)
        awaitSaved("actual cache failure published") { app.reader.value.sessions.error=="cache_not_saved" }
        assertEquals("cache_not_saved",app.reader.value.sessions.error)
    }
    @Test fun E08_delayedOldWriteCannotOutliveNewPhoneOwnerSnapshot() {
        snapshot(a)
        val app=cold()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val first=java.util.concurrent.atomic.AtomicBoolean(true)
        mockkObject(app.localStore)
        every { app.localStore.commitString(key,any()) } answers {
            if(first.getAndSet(false)) { entered.countDown();check(release.await(30,TimeUnit.SECONDS)) }
            callOriginal()
        }
        try {
            deliver(app,read(app),listOf(b))
            awaitSaved("old write held") { entered.count==0L }
            phone="new-phone"
            val current=read(app)
            assertTrue(ids(app).isEmpty())
            deliver(app,current,listOf(c),newScope="c".repeat(64))
            assertEquals(listOf(c),ids(app))
            release.countDown()
            awaitSaved("new namespace persisted last") { original.localStore.getString(key)?.contains("Fresh $c")==true }
            assertEquals(listOf(c),ids(cold()))
        } finally { release.countDown() }
    }
    @Test fun E09_positiveHistoryRejectionRemovesCachedTitleWithoutWaitingForListRefresh() {
        snapshot(a)
        val app=cold()
        assertEquals(listOf(a),ids(app))
        app.selectSession(a);idle()
        val request=requests.last()
        assertEquals(ReaderKind.HISTORY,request.second.kind)
        val rejection=ReaderResponse(request.second.reqId,ReaderKind.HISTORY,false,error=ReaderError.NOT_OWNED,sessionId=a)
        val bytes=JSONObject(String(rejection.encode())).put("scope",scope).toString().toByteArray()
        app.onReaderResponse(request.first,bytes);idle()
        assertTrue(ids(app).isEmpty())
        awaitSaved("rejected title removed durably") { original.localStore.getString(key)?.contains(a)==false }
        assertTrue(ids(cold()).isEmpty())
    }
    @Test fun C01_corruptSnapshotDoesNotDamageSettingsAndFetchesNormally() {
        original.localStore.commitString(key,"not json")
        val app=cold()
        assertTrue(ids(app).isEmpty())
        deliver(app,read(app),listOf(a))
        assertEquals(listOf(a),ids(app))
        assertEquals(original.settings.value,app.settings.value)
    }
    @Test fun C02_wrongPhoneResponseDoesNotReplacePendingListOrPersistForeignTitles() {
        snapshot(a)
        val app=cold();val pending=read(app)
        deliver(app,pending,listOf(b),from="foreign-node")
        assertEquals(LoadStatus.LOADING,app.reader.value.sessions.status)
        deliver(app,pending,listOf(c))
        assertEquals(listOf(c),ids(app))
    }
}
