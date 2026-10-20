package com.rumi.hermesvoice.watch

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.watchlink.*
import io.mockk.*
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],shadows=[RecordingVibratorShadow::class])
class ReceivedFinalR10RoboTest {
    private val rule=createComposeRule()
    private val host=org.junit.rules.TestRule { base,_ -> object:org.junit.runners.model.Statement() {
        override fun evaluate() {
            val app=RuntimeEnvironment.getApplication()
            shadowOf(app.packageManager).addActivityIfNotPresent(android.content.ComponentName(app.packageName,androidx.activity.ComponentActivity::class.java.name))
            base.evaluate()
        }
    } }
    @get:Rule val chain:org.junit.rules.RuleChain=org.junit.rules.RuleChain.outerRule(host).around(rule)
    private val requests=ConcurrentLinkedQueue<ReaderRequest>()
    private val sent=ConcurrentLinkedQueue<Pair<String,ByteArray>>()
    private lateinit var original:WatchApp
    private val namespace="a".repeat(64)
    private val id="20260101_120000_workwork"
    private val watchIdentity get()="f"+java.security.MessageDigest.getInstance("SHA-256").digest("$namespace|$id|native-watch-final".toByteArray()).joinToString("") { "%02x".format(it) }
    @Before fun setup() {
        original=RuntimeEnvironment.getApplication() as WatchApp
        mockkStatic(Wearable::class)
        val cap=mockk<CapabilityClient>();val node=mockk<Node>()
        every { node.id } returns "phone-node";every { node.isNearby } returns true
        val info=mockk<CapabilityInfo>();every { info.nodes } returns setOf(node)
        every { cap.addListener(any<CapabilityClient.OnCapabilityChangedListener>(),any<String>()) } returns Tasks.forResult(null)
        every { cap.getCapability(any(),any()) } returns Tasks.forResult(info)
        val messages=mockk<MessageClient>()
        every { messages.sendMessage(any(),any(),any()) } answers {
            val path=secondArg<String>();val bytes=thirdArg<ByteArray>();sent+=path to bytes
            if(path==WatchLinkPaths.READER_REQUEST) ReaderRequest.decode(bytes)?.let { requests+=it }
            Tasks.forResult(1)
        }
        every { Wearable.getCapabilityClient(any<Context>()) } returns cap
        every { Wearable.getMessageClient(any<Context>()) } returns messages
    }
    @After fun cleanup() {
        unmockkAll()
        runCatching { shadowOf(Looper.getMainLooper()).idle() }
        val companion=Class.forName("androidx.compose.ui.platform.AndroidUiDispatcher").getDeclaredField("Companion").also { it.isAccessible=true }.get(null)
        val context=companion.javaClass.getMethod("getMain").invoke(companion) as kotlin.coroutines.CoroutineContext
        val dispatcher=context[kotlin.coroutines.ContinuationInterceptor]!!
        for(name in listOf("scheduledTrampolineDispatch","scheduledFrameDispatch")) dispatcher.javaClass.getDeclaredField(name).also { it.isAccessible=true }.setBoolean(dispatcher,false)
    }
    private fun idle()=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    private fun waitFor(what:String,done:()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(!done() && System.nanoTime()<end) { idle();Thread.yield() }
        assertTrue(what,done())
    }
    private fun cold()=WatchApp().also {
        ReflectionHelpers.callInstanceMethod<Void>(it,"attach",ClassParameter.from(Context::class.java,original.baseContext));it.onCreate()
    }
    private fun response(app:WatchApp,extra:Boolean=false) {
        app.loadSessions();idle();val request=requests.last()
        val row=JSONObject().put("id",id).put("title","Unread Watch").put("alias","watch").put("last_active",1.0).put("unread",extra)
        val json=JSONObject(String(ReaderResponse(request.reqId,ReaderKind.SESSIONS,true,scope=namespace).encode(),Charsets.UTF_8))
            .put("sessions",JSONArray().put(row))
        if(extra) json.put("finals",JSONArray().put(JSONObject().put("identity",watchIdentity).put("session_id",id).put("row_id",19).put("uid","native-watch-final").put("timestamp",900.0).put("final",true)))
        app.onReaderResponse("phone-node",json.toString().toByteArray());idle()
    }
    @Test fun W01_receivedUnreadIsReallyBoldInCompactWatchSurface() {
        val app=cold();response(app,true)
        val normal=ReaderSessionRow.fromJson(JSONObject().put("id","read-watch").put("title","Read Watch").put("unread",false))
        val sessions=app.reader.value.sessions.copy(rows=app.reader.value.sessions.rows+normal)
        rule.setContent { androidx.wear.compose.material.MaterialTheme { Box(Modifier.size(192.dp)) {
            SessionsSurface(sessions,null,FocusRequester(),{}, {}, {})
        } } }
        val node=rule.onNodeWithText("Unread Watch",useUnmergedTree=true).fetchSemanticsNode()
        val layouts=mutableListOf<TextLayoutResult>()
        rule.runOnUiThread { node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts) }
        assertEquals(FontWeight.Bold,layouts.single().layoutInput.style.fontWeight)
        rule.onNodeWithTag("session_list").performScrollToNode(hasText("Read Watch"))
        val readNode=rule.onNodeWithText("Read Watch",useUnmergedTree=true).fetchSemanticsNode()
        layouts.clear()
        rule.runOnUiThread { readNode.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts) }
        assertNotEquals("not a globally bold typography",FontWeight.Bold,layouts.single().layoutInput.style.fontWeight)
    }
    @Test fun W03_actualWatchForegroundPlacementReadsPhoneTargetBodyNotSelectionOrFetch() {
        response(original)
        val row=original.reader.value.sessions.rows.single()
        original.selectSession(row.id);idle()
        val request=requests.last { it.kind==ReaderKind.HISTORY }
        val message=JSONObject().put("row_id",17L).put("role","assistant").put("text","watch foreground real canonical")
            .put("uid","foreground-uid").put("timestamp",2.0).put("truncated",false)
        val payload=JSONObject(String(ReaderResponse(request.reqId,ReaderKind.HISTORY,true,sessionId=id,scope=namespace).encode()))
            .put("messages",JSONArray().put(message)).put("next_offset",1)
        original.onReaderResponse("phone-node",payload.toString().toByteArray());idle()
        val identity="f"+java.security.MessageDigest.getInstance("SHA-256").digest("$namespace|$id|foreground-uid".toByteArray()).joinToString("") { "%02x".format(it) }
        fun readReceipt()=sent.any { it.first=="/hv/v1/finals/receipt" && JSONObject(String(it.second)).optJSONArray("read")?.toString()?.contains(identity)==true }
        assertFalse("a selected/cached background body is not read",readReceipt())
        val intent=WatchReplyAlertNotifier.openIntent(original,com.rumi.hermesvoice.core.notify.ReplyAlert(identity,id))
        val controller=Robolectric.buildActivity(WatchActivity::class.java,intent).setup()
        try {
            waitFor("actual WatchActivity composes cached body before Phone answers") { rule.onAllNodesWithText("watch foreground real canonical").fetchSemanticsNodes().isNotEmpty() }
            waitFor("actual foreground placement sends read independently of Watch alert intake") { readReceipt() }
            assertTrue("reading is not an OS notification attempt",original.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
        } finally { controller.pause().stop().destroy();idle() }
    }

    @Test fun W04_exactPhoneProductionWireUsesRealWatchListenerDurableOSPathAndScopeRemovalFence() {
        val wire=javaClass.getResourceAsStream("/r10-phone-watch-wire.json")!!.use { it.readBytes() }
        assertEquals("f7e9b9179f99adfca4d372da8c515fc6cd6d772d0edb6af957abe80d2413ee74",java.security.MessageDigest.getInstance("SHA-256").digest(wire).joinToString("") { "%02x".format(it) })
        val packet=JSONObject(String(wire));val scope=packet.getString("scope");val final=packet.getJSONArray("finals").getJSONObject(0)
        val session=final.getString("session_id");val identity=final.getString("identity")
        shadowOf(original).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        original.loadSessions();idle()
        val listReq=original.reader.value.sessions.pendingReqId!!
        val title=ReaderSessionRow(session,"Actual Phone wire","wire","",9000.0)
        original.onReaderResponse("phone-node",ReaderResponse(listReq,ReaderKind.SESSIONS,true,sessions=listOf(title),scope=scope).encode());idle()
        val controller=Robolectric.buildService(WatchListenerService::class.java).create()
        fun deliver(bytes:ByteArray,node:String="phone-node") {
            val event=mockk<MessageEvent>()
            every { event.path } returns "/hv/v1/finals/intake";every { event.sourceNodeId } returns node;every { event.data } returns bytes
            controller.get().onMessageReceived(event);idle()
        }
        try {
            deliver(wire)
            waitFor("exact native Phone packet is durably ingested by Watch listener") { original.localStore.getString("received_finals_v1")?.contains(identity)==true }
            val manager=original.getSystemService(NotificationManager::class.java)
            assertTrue("OS denial is still pending, not successful delivery",manager.activeNotifications.isEmpty())
            val before=original.localStore.getString("received_finals_v1")
            deliver(JSONObject(String(wire)).put("scope","c".repeat(64)).toString().toByteArray());deliver(wire,"foreign-phone")
            assertEquals("wrong account and Phone cannot replace pending intake",before,original.localStore.getString("received_finals_v1"))
            shadowOf(original).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);original.onForeground(true)
            waitFor("the exact received Phone final reaches Watch NotificationManager") { manager.activeNotifications.any { it.tag==identity } }
            val expectedQuiet=final.optBoolean("quiet",true) || final.optBoolean("private_origin")
            val posted=manager.activeNotifications.single { it.tag==identity }.notification
            assertEquals("durable Phone policy reaches real Watch post after permission recovery",
                if(expectedQuiet) com.rumi.hermesvoice.core.notify.ReplyAlertContent.QUIET_CHANNEL_ID else com.rumi.hermesvoice.core.notify.ReplyAlertContent.CHANNEL_ID,posted.channelId)
            if(expectedQuiet) assertNull(posted.sound)
            waitFor("real Watch post is separately receipted") { sent.any { it.first=="/hv/v1/finals/receipt" && JSONObject(String(it.second)).optJSONArray("posted")?.toString()?.contains(identity)==true } }
            assertFalse("OS posting is not foreground reading",sent.any { it.first=="/hv/v1/finals/receipt" && JSONObject(String(it.second)).optJSONArray("read")?.toString()?.contains(identity)==true })
            original.loadSessions();idle();val removeReq=original.reader.value.sessions.pendingReqId!!
            original.onReaderResponse("phone-node",ReaderResponse(removeReq,ReaderKind.SESSIONS,true,scope=scope,removedSessionIds=listOf(session)).encode());idle()
            waitFor("explicit removal cancels actual Watch alert") { manager.activeNotifications.none { it.tag==identity } }
            deliver(wire);original.onForeground(true);idle()
            assertTrue("late exact-byte replay cannot revive a removed conversation",manager.activeNotifications.none { it.tag==identity })
        } finally { controller.destroy() }
    }

    @Test fun W05_exactCancelledFinalStaysFencedWithoutMutingTheNextFinal() {
        shadowOf(original).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        response(original,true)
        val manager=original.getSystemService(NotificationManager::class.java)
        waitFor("actual Watch arrival before cancellation") { manager.activeNotifications.any { it.tag==watchIdentity } }
        val controller=Robolectric.buildService(WatchListenerService::class.java).create()
        fun deliver(row:JSONObject) {
            val bytes=JSONObject().put("v",1).put("scope",namespace).put("finals",JSONArray().put(row)).toString().toByteArray()
            val event=mockk<MessageEvent>()
            every { event.path } returns "/hv/v1/finals/intake";every { event.sourceNodeId } returns "phone-node";every { event.data } returns bytes
            controller.get().onMessageReceived(event);idle()
        }
        try {
            val row=JSONObject().put("identity",watchIdentity).put("session_id",id).put("row_id",19).put("uid","native-watch-final").put("final",true)
            deliver(JSONObject(row.toString()).put("cancelled",true))
            waitFor("canonical Watch cancellation removes the actual OS post") { manager.activeNotifications.none { it.tag==watchIdentity } }
            deliver(row);original.onForeground(true);idle()
            assertTrue("late replay cannot revive this cancelled final",manager.activeNotifications.none { it.tag==watchIdentity })
            val uid="watch-after-cancel"
            val next="f"+java.security.MessageDigest.getInstance("SHA-256").digest("$namespace|$id|$uid".toByteArray()).joinToString("") { "%02x".format(it) }
            deliver(JSONObject().put("identity",next).put("session_id",id).put("row_id",20).put("uid",uid).put("final",true))
            waitFor("the next distinct Watch final remains eligible") { manager.activeNotifications.any { it.tag==next } }
        } finally { controller.destroy() }
    }

    @Test fun W02_deniedScopedIntakePersistsThenColdForegroundPostsAndAcknowledges() {
        shadowOf(original).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val app=cold();response(app,true)
        waitFor("Watch owes durable final, not transport-return success") { original.localStore.getString("received_finals_v1")?.contains("native-watch-final")==true }
        val manager=original.getSystemService(NotificationManager::class.java)
        assertTrue(manager.activeNotifications.isEmpty())
        shadowOf(original).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val next=cold();next.onForeground(true)
        waitFor("durable Watch pending reaches real OS post") { manager.activeNotifications.any { it.tag==watchIdentity } }
        waitFor("post receipt follows persistence and real presentation attempt") { sent.any { it.first=="/hv/v1/finals/receipt" } }
        val count=manager.activeNotifications.size
        next.onForeground(true);idle();assertEquals(count,manager.activeNotifications.size)
    }
}
