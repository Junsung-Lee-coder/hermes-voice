package com.rumi.hermesvoice.watch

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.notify.ReplyAlertContent
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.watchlink.*
import io.mockk.*
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.time.Duration

/** Byte-exact Phone drain exports feed the real listener/intake/notifier while settings replication is held. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@LooperMode(LooperMode.Mode.PAUSED)
class CallerClosureWatchWireRoboTest {
    private lateinit var app:WatchApp
    private val sent=java.util.concurrent.ConcurrentLinkedQueue<Pair<String,ByteArray>>()
    @Before fun setup(){
        app=RuntimeEnvironment.getApplication()as WatchApp
        mockkStatic(Wearable::class)
        val node=mockk<Node>();every{node.id}returns "phone-node";every{node.isNearby}returns true
        val info=mockk<CapabilityInfo>();every{info.nodes}returns setOf(node)
        val caps=mockk<CapabilityClient>();every{caps.getCapability(any(),any())}returns Tasks.forResult(info)
        val messages=mockk<MessageClient>();every{messages.sendMessage(any(),any(),any())}answers{sent+=secondArg<String>()to thirdArg<ByteArray>().copyOf();Tasks.forResult(1)}
        every{Wearable.getCapabilityClient(any<Context>())}returns caps
        every{Wearable.getMessageClient(any<Context>())}returns messages
        app.applySettings(WatchSettings(watchLiveEnabled=true,privateAudio=false,revision=100).toJson())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }
    @After fun cleanup(){app.scope.coroutineContext[Job]?.cancel();unmockkAll()}
    private fun idle()=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    private fun await(what:String,done:()->Boolean){val end=System.nanoTime()+10_000_000_000;while(!done()&&System.nanoTime()<end){idle();Thread.yield()};assertTrue(what,done())}
    private fun boundary(kind:String){
        val bytes=javaClass.getResourceAsStream("/caller-final-wire-$kind.json")!!.use{it.readBytes()}
        val packet=JSONObject(String(bytes));val row=packet.getJSONArray("finals").getJSONObject(0)
        val scope=packet.getString("scope");val session=row.getString("session_id");val identity=row.getString("identity")
        println("CALLER_CONSUMED_${kind.uppercase()}_SHA256="+java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})
        assertTrue("Phone's actual drain emitted quiet, not an eventual settings guess",row.getBoolean("quiet"))
        assertFalse(app.settings.value.privateAudio)
        app.loadSessions();idle();val req=app.reader.value.sessions.pendingReqId!!
        app.onReaderResponse("phone-node",ReaderResponse(req,ReaderKind.SESSIONS,true,sessions=listOf(ReaderSessionRow(session,"Private reply","private","",1800000000.0)),scope=scope).encode());idle()
        val controller=Robolectric.buildService(WatchListenerService::class.java).create()
        fun deliver(){val event=mockk<MessageEvent>();every{event.path}returns "/hv/v1/finals/intake";every{event.sourceNodeId}returns "phone-node";every{event.data}returns bytes;controller.get().onMessageReceived(event);idle()}
        fun saved()=JSONObject(app.localStore.getString("received_finals_v1")?:"{}").optJSONObject("rows")?.optJSONObject(identity)
        fun hasReceipt(field:String)=sent.any{it.first=="/hv/v1/finals/receipt"&&JSONObject(String(it.second)).optJSONArray(field)?.toString()?.contains(identity)==true}
        try{
            deliver();val manager=app.getSystemService(NotificationManager::class.java)
            await("actual Watch notifier posts the emitted final"){manager.activeNotifications.any{it.tag==identity}}
            val notification=manager.activeNotifications.single{it.tag==identity}.notification
            assertEquals(ReplyAlertContent.QUIET_CHANNEL_ID,notification.channelId)
            assertNull(notification.sound);assertNull(notification.vibrate)
            assertEquals(0,notification.defaults and (Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE))
            assertFalse("settings DataItem is still held",app.settings.value.privateAudio)
            await("only a real OS post emits posted receipt"){hasReceipt("posted")}
            assertFalse(hasReceipt("read"));assertFalse(saved()!!.optBoolean("read"))
            app.onSessionFinalReceipt("phone-node",JSONObject().put("v",1).put("scope",scope).put("posted",JSONArray().put(identity)).toString().toByteArray())
            await("matching posted ACK is separately persisted"){saved()?.optBoolean("posted_ack")==true}
            manager.cancel(identity,WatchReplyAlertNotifier.NOTIFICATION_ID);deliver();app.onForeground(true);idle()
            assertTrue("duplicate final never creates a replacement audible alert",manager.activeNotifications.none{it.tag==identity})
            // The actual Watch reader display callback has a distinct read obligation.
            app.selectSession(session);app.onForeground(true)
            app.contentDisplayed(session,listOf(ReaderMessageRow(row.getLong("row_id"),"assistant","canonical body",false,row.getString("uid"),1800000000.0)))
            await("foreground exact content, not intake, creates read receipt"){hasReceipt("read")}
            app.onSessionFinalReceipt("phone-node",JSONObject().put("v",1).put("scope",scope).put("read",JSONArray().put(identity)).toString().toByteArray())
            await("read ACK is independently persisted"){saved()?.optBoolean("read_ack")==true}
            app.applySettings(WatchSettings(watchLiveEnabled=true,privateAudio=true,revision=101).toJson());deliver();idle()
            assertTrue(manager.activeNotifications.none{it.tag==identity})
        }finally{controller.destroy()}
    }
    @Test fun initialPrivateDrainWireIsQuietWithoutAWatchSettingsUpdate()=boundary("initial")
    @Test fun watchOnlyRetryWireAfterPhonePostIsQuietWithoutAWatchSettingsUpdate()=boundary("retry")
}
