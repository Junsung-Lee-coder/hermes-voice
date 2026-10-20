package com.rumi.hermesvoice.watch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.watchlink.*
import io.mockk.*
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.android.controller.ActivityController
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue

/** Actual fresh Application.onCreate + Activity.onResume, no manual loadSessions in startup tests.
 * Activity's lazy app dependency is explicitly bound to that attached fresh Application. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],shadows=[RecordingVibratorShadow::class])
class WatchCacheClosureR9dRoboTest {
    private lateinit var original: WatchApp
    private val key="reader_session_titles_v1"
    private val id="20260101_120000_workwork"
    private val namespace="a".repeat(64)
    private val requests=ConcurrentLinkedQueue<ReaderRequest>()
    private var phone: String?="phone-node"
    private var controller: ActivityController<WatchActivity>?=null
    private val apps=mutableListOf<WatchApp>()
    @Before fun setup() {
        original=RuntimeEnvironment.getApplication() as WatchApp
        shadowOf(original).grantPermissions(Manifest.permission.POST_NOTIFICATIONS,Manifest.permission.RECORD_AUDIO)
        mockkStatic(Wearable::class)
        val capability=mockk<CapabilityClient>()
        every { capability.addListener(any<CapabilityClient.OnCapabilityChangedListener>(),any<String>()) } returns Tasks.forResult(null)
        every { capability.getCapability(any(),any()) } answers {
            val nodes=phone?.let { p -> setOf(mockk<Node>().also { n -> every { n.id } returns p;every { n.isNearby } returns true }) }.orEmpty()
            Tasks.forResult(mockk<CapabilityInfo>().also { info -> every { info.nodes } returns nodes })
        }
        val messages=mockk<MessageClient>()
        every { messages.sendMessage(any(),any(),any()) } answers {
            if(secondArg<String>()==WatchLinkPaths.READER_REQUEST) ReaderRequest.decode(thirdArg<ByteArray>())?.let(requests::add)
            Tasks.forResult(1)
        }
        val data=mockk<DataClient>()
        every { data.getDataItems(any<Uri>()) } returns Tasks.forException<DataItemBuffer>(IllegalStateException("synthetic no settings item"))
        every { Wearable.getCapabilityClient(any<Context>()) } returns capability
        every { Wearable.getMessageClient(any<Context>()) } returns messages
        every { Wearable.getDataClient(any<Context>()) } returns data
        every { Wearable.getDataClient(any<android.app.Activity>()) } returns data
        every { Wearable.getChannelClient(any<Context>()) } returns mockk<ChannelClient>(relaxed=true)
    }
    @After fun cleanup() {
        runCatching { controller?.pause()?.stop()?.destroy() }
        apps.forEach { it.scope.cancel() }
        unmockkAll()
        runCatching { shadowOf(Looper.getMainLooper()).idle() }
        val companion=Class.forName("androidx.compose.ui.platform.AndroidUiDispatcher").getDeclaredField("Companion").also { it.isAccessible=true }.get(null)
        val ctx=companion.javaClass.getMethod("getMain").invoke(companion) as kotlin.coroutines.CoroutineContext
        val dispatcher=ctx[kotlin.coroutines.ContinuationInterceptor]!!
        for(n in listOf("scheduledTrampolineDispatch","scheduledFrameDispatch")) dispatcher.javaClass.getDeclaredField(n).also { it.isAccessible=true }.setBoolean(dispatcher,false)
    }
    private fun idle()=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    private fun snapshot(populated: Boolean) {
        val rows=JSONArray().apply { if(populated) put(JSONObject().put("id",id).put("title","Saved title").put("last_active",1.0)) }
        check(original.localStore.commitString(key,JSONObject().put("v",1).put("phone","phone-node").put("scope",namespace).put("rows",rows).toString()))
    }
    private fun cold(): WatchApp=WatchApp().also {
        ReflectionHelpers.callInstanceMethod<Void>(it,"attach",ClassParameter.from(Context::class.java,original.baseContext))
        it.onCreate();apps+=it
    }
    private fun launch(app: WatchApp) {
        val next=Robolectric.buildActivity(WatchActivity::class.java,Intent(original,WatchActivity::class.java))
        WatchActivity::class.java.getDeclaredField("app\$delegate").also { it.isAccessible=true }.set(next.get(),lazyOf(app))
        controller=next.create().start().resume().visible()
        idle()
    }
    private fun startup(populated: Boolean) {
        snapshot(populated)
        val app=cold()
        assertEquals(if(populated) listOf(id) else emptyList<String>(),app.reader.value.sessions.rows.map { it.id })
        assertEquals(LoadStatus.READY,app.reader.value.sessions.status)
        launch(app)
        assertEquals("actual Activity startup must reconcile restored READY cache without navigation",1,requests.count { it.kind==ReaderKind.SESSIONS })
        val pending=requests.single { it.kind==ReaderKind.SESSIONS }
        assertEquals(LoadStatus.LOADING,app.reader.value.sessions.status)
        assertEquals(if(populated) listOf(id) else emptyList<String>(),app.reader.value.sessions.rows.map { it.id })
        assertEquals(ReaderSurface.CHAT,app.reader.value.surface)
        // Resume again with the response still withheld: one request retains its custody.
        controller!!.pause().resume();idle()
        assertEquals(1,requests.count { it.kind==ReaderKind.SESSIONS })
        app.onReaderResponse("phone-node",ReaderResponse(pending.reqId,ReaderKind.SESSIONS,true,
            sessions=listOf(ReaderSessionRow(id,"Fresh title","","",2.0)),scope=namespace).encode());idle()
        assertEquals("Fresh title",app.reader.value.sessions.rows.single().title)
        assertNull(app.reader.value.selectedSessionId)
    }
    @Test fun D07_actualActivityReconcilesPopulatedSavedTitlesWhilePhoneResponseIsHeld()=startup(true)
    @Test fun D08_actualActivityReconcilesAnEmptySavedSnapshotWhilePhoneResponseIsHeld()=startup(false)
    @Test fun C04_noCacheStartupAlsoRefreshesAndOrdinaryOfflineRemainsTruthful() {
        original.localStore.commitString(key,"{}")
        val app=cold();launch(app)
        assertEquals(1,requests.count { it.kind==ReaderKind.SESSIONS })
        val request=requests.last()
        app.onReaderResponse("phone-node",ReaderResponse(request.reqId,ReaderKind.SESSIONS,false,error=ReaderError.UNAVAILABLE,scope=namespace).encode());idle()
        assertEquals(LoadStatus.ERROR,app.reader.value.sessions.status)
        assertEquals(ReaderError.UNAVAILABLE,app.reader.value.sessions.error)
    }
    @Test fun C05_samePhoneListenerAuthWireClearsTitlesAndSelectedHistory() {
        snapshot(true)
        val app=cold()
        app.selectSession(id);idle()
        val history=requests.last()
        app.onReaderResponse("phone-node",ReaderResponse(history.reqId,ReaderKind.HISTORY,true,sessionId=id,
            messages=listOf(ReaderMessageRow(1,"assistant","synthetic saved history",false)),nextOffset=1,scope=namespace).encode());idle()
        check(app.reader.value.selectedHistory!!.messages.isNotEmpty())
        // Fixed request identity permits byte-for-byte matching with the actual Phone emitter.
        app.loadSessions();idle()
        val actual=requests.last()
        val stateField=WatchApp::class.java.getDeclaredField("_reader").also { it.isAccessible=true }
        @Suppress("UNCHECKED_CAST") val flow=stateField.get(app) as kotlinx.coroutines.flow.MutableStateFlow<WatchReaderState>
        flow.value=flow.value.copy(sessions=flow.value.sessions.copy(pendingReqId="r09d-auth-wire"))
        @Suppress("UNCHECKED_CAST") val targets=WatchApp::class.java.getDeclaredField("readerTargets").also { it.isAccessible=true }.get(app) as java.util.concurrent.ConcurrentHashMap<String,String>
        targets.remove(actual.reqId);targets["r09d-auth-wire"]="phone-node"
        val bytes=ReaderResponse("r09d-auth-wire",ReaderKind.SESSIONS,false,error=ReaderError.SIGN_IN_REQUIRED).encode()
        println("R09D_AUTH_WIRE_BASE64="+Base64.getEncoder().encodeToString(bytes))
        app.onReaderResponse("phone-node",bytes);idle()
        assertTrue(app.reader.value.sessions.rows.isEmpty());assertNull(app.reader.value.selectedSessionId)
        assertTrue(app.reader.value.histories.isEmpty())
        assertEquals(ReaderError.SIGN_IN_REQUIRED,app.reader.value.sessions.error)
        val end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while(original.localStore.getString(key)!="{}" && System.nanoTime()<end) { idle();Thread.yield() }
        assertEquals("{}",original.localStore.getString(key))
    }
    @Test fun C06_wrongNodeCannotConsumePendingStartupOrDeleteSavedTitles() {
        snapshot(true);val app=cold();app.loadSessions();idle()
        val req=requests.last()
        app.onReaderResponse("foreign-node",ReaderResponse(req.reqId,ReaderKind.SESSIONS,false,error=ReaderError.SIGN_IN_REQUIRED).encode());idle()
        assertEquals(listOf(id),app.reader.value.sessions.rows.map { it.id })
        assertEquals(req.reqId,app.reader.value.sessions.pendingReqId)
        assertEquals(LoadStatus.LOADING,app.reader.value.sessions.status)
    }
}
