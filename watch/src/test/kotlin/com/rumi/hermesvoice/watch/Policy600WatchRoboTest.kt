package com.rumi.hermesvoice.watch

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.notify.ReplyAlertContent
import com.rumi.hermesvoice.core.sessions.ReceivedConversations
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.watchlink.*
import io.mockk.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Real listener -> application binding -> durable intake -> actual notifier, with settings delivery held. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk=[34])
class Policy600WatchRoboTest {
    private lateinit var app:WatchApp
    private val scope="a".repeat(64)
    private val session="20260101_120000_policy"
    private val identity get()=ReceivedConversations.identity(scope,session,19,"policy600")
    private val sent=ConcurrentLinkedQueue<Pair<String,ByteArray>>()
    private val commits=AtomicInteger()
    private var refuse=false
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
        app.loadSessions();idle();val request=app.reader.value.sessions.pendingReqId!!
        app.onReaderResponse("phone-node",ReaderResponse(request,ReaderKind.SESSIONS,true,sessions=listOf(ReaderSessionRow(session,"Policy","policy","",1.0)),scope=scope).encode());idle()
        mockkObject(app.localStore)
        every{app.localStore.commitString(WatchFinalIntake.KEY,any())}answers{
            val result=if(refuse)false else callOriginal();commits.incrementAndGet();result
        }
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }
    @After fun cleanup(){app.scope.coroutineContext[Job]?.cancel();unmockkAll()}
    private fun idle()=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    private fun await(what:String,done:()->Boolean){val end=System.nanoTime()+10_000_000_000;while(!done()&&System.nanoTime()<end){idle();Thread.yield()};assertTrue(what,done())}
    private fun settled(receiver:WatchFinalIntake){
        val gate=WatchFinalIntake::class.java.getDeclaredField("mutex").also{it.isAccessible=true}.get(receiver)as kotlinx.coroutines.sync.Mutex
        val done=java.util.concurrent.atomic.AtomicBoolean()
        val job=app.scope.launch(kotlinx.coroutines.Dispatchers.IO){gate.lock();try{done.set(true)}finally{gate.unlock()}}
        await("the exact intake has left its commit/post/receipt boundary"){done.get()&&job.isCompleted}
    }
    private fun currentIntake():WatchFinalIntake {
        val lazy=WatchApp::class.java.getDeclaredField("receivedFinals\$delegate").also{it.isAccessible=true}.get(app)as Lazy<*>
        return lazy.value as WatchFinalIntake
    }
    private fun packet(quiet:Boolean)=JSONObject().put("v",1).put("scope",scope).put("finals",JSONArray().put(JSONObject()
        .put("identity",identity).put("session_id",session).put("row_id",19).put("uid","policy600").put("final",true)
        .put("quiet",quiet).put("private_origin",false).put("live_watch",true).put("node","original-watch")))
    private fun deliver(json:JSONObject,node:String="phone-node"){
        val count=commits.get();val controller=Robolectric.buildService(WatchListenerService::class.java).create()
        try{val event=mockk<MessageEvent>();every{event.path}returns "/hv/v1/finals/intake";every{event.sourceNodeId}returns node;every{event.data}returns json.toString().toByteArray();controller.get().onMessageReceived(event)
            if(node=="phone-node"&&json.optString("scope")==scope)await("actual intake attempt completes its durable boundary"){commits.get()>count}
        }finally{controller.destroy()}
    }
    private fun root()=JSONObject(app.localStore.getString(WatchFinalIntake.KEY)?:"{}")
    private fun saved()=root().optJSONObject("rows")?.optJSONObject(identity)
    private fun manager()=app.getSystemService(NotificationManager::class.java)
    private fun receipt(field:String)=sent.any{it.first=="/hv/v1/finals/receipt"&&JSONObject(String(it.second)).optJSONArray(field)?.toString()?.contains(identity)==true}
    private fun deniedPublic(){deliver(packet(false));await("first notifier actually refuses before permission is changed"){app.reader.value.sessions.alertNotice?.startsWith("Reply alerts pending.")==true};check(saved()?.optBoolean("quiet")==false&&saved()?.optBoolean("posted")==false){"FixtureSetup: denied public final"};assertFalse(receipt("posted"))}
    private fun silentPost(){await("actual post"){manager().activeNotifications.any{it.tag==identity}};val n=manager().activeNotifications.single{it.tag==identity}.notification
        assertEquals(ReplyAlertContent.QUIET_CHANNEL_ID,n.channelId);assertNull(n.sound);assertNull(n.vibrate);assertEquals(0,n.defaults and (Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE))
        assertFalse("settings replica is still held",app.settings.value.privateAudio)
        await("post is a separate receipt"){receipt("posted")};assertFalse(receipt("read"));assertFalse(saved()!!.optBoolean("read"))
    }
    @Test fun deniedOriginalThenTrueRetryPostsSilentlyWithoutSettingsReplication(){
        deniedPublic();shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);deliver(packet(true));silentPost()
        assertTrue(saved()!!.getBoolean("quiet"));assertEquals("original-watch",saved()!!.getString("node"))
    }
    @Test fun strengthenedUnpostedPolicySurvivesIntakeReconstructionAndDowngrade(){
        deniedPublic();deliver(packet(true));await("duplicate must durably strengthen even while post is denied"){saved()?.optBoolean("quiet")==true}
        assertTrue("duplicate must durably strengthen even while post is denied",saved()!!.getBoolean("quiet"));settled(currentIntake())
        val reconstructed=WatchFinalIntake(app,{"phone-node" to scope},{false},{})
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);reconstructed.recover();silentPost()
        manager().cancel(identity,WatchReplyAlertNotifier.NOTIFICATION_ID)
        val before=commits.get();reconstructed.receive("phone-node",scope,listOf(packet(false).getJSONArray("finals").getJSONObject(0)))
        await("reconstructed owner receives downgrade"){commits.get()>before};settled(reconstructed)
        assertTrue(saved()!!.getBoolean("quiet"));assertTrue(saved()!!.getBoolean("posted"));assertTrue(manager().activeNotifications.none{it.tag==identity})
    }
    @Test fun failedUpgradeCommitHasNoPostOrReceiptAndRemainsRetryable(){
        deniedPublic();refuse=true;shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);deliver(packet(true))
        await("save refusal is visible"){app.reader.value.sessions.alertNotice?.contains("not saved")==true}
        assertFalse(saved()!!.getBoolean("quiet"));assertFalse(saved()!!.getBoolean("posted"));assertFalse(receipt("posted"));assertTrue(manager().activeNotifications.none{it.tag==identity})
        refuse=false;deliver(packet(true));silentPost()
    }
    @Test fun alreadyPostedDuplicateStrengthensWithoutResettingPostedReadOrAck(){
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);deliver(packet(false));await("original post"){saved()?.optBoolean("posted")==true}
        app.onSessionFinalReceipt("phone-node",JSONObject().put("v",1).put("scope",scope).put("posted",JSONArray().put(identity)).toString().toByteArray())
        await("posted ACK"){saved()?.optBoolean("posted_ack")==true}
        app.selectSession(session);app.onForeground(true)
        app.contentDisplayed(session,listOf(ReaderMessageRow(19,"assistant","canonical policy body",false,"policy600",1.0)))
        await("exact foreground content is read independently"){saved()?.optBoolean("read")==true}
        app.onSessionFinalReceipt("phone-node",JSONObject().put("v",1).put("scope",scope).put("read",JSONArray().put(identity)).toString().toByteArray())
        await("read ACK"){saved()?.optBoolean("read_ack")==true}
        manager().cancel(identity,WatchReplyAlertNotifier.NOTIFICATION_ID);deliver(packet(true))
        await("posted duplicate quiet commit, not an unrelated foreground recovery commit"){saved()?.optBoolean("quiet")==true};settled(currentIntake())
        assertTrue("posted identity may tighten policy without reposting",saved()!!.getBoolean("quiet"));assertTrue(saved()!!.getBoolean("posted"));assertTrue(saved()!!.getBoolean("posted_ack"));assertTrue(saved()!!.getBoolean("read"));assertTrue(saved()!!.getBoolean("read_ack"))
        assertTrue(manager().activeNotifications.none{it.tag==identity});assertEquals("policy600",saved()!!.getString("uid"))
    }
    @Test fun foreignBindingCannotStrengthenOrPostTheSavedIdentity(){
        deniedPublic();deliver(packet(true),"foreign-phone");deliver(packet(true).put("scope","b".repeat(64)))
        assertFalse(saved()!!.getBoolean("quiet"));assertFalse(saved()!!.getBoolean("posted"));assertFalse(receipt("posted"))
    }
    @Test fun actualLaterPhoneBatchCaptureFeedsTheRealReceiverAndNotifier(){
        // Runner-only uploaded output of the real Phone test, never part of production settings.
        val input=java.io.File(System.getenv("TEMP")).parentFile.resolve("policy600-later-wire.json")
        val bytes=input.readBytes();val packet=JSONObject(String(bytes));val rows=packet.getJSONArray("finals")
        check(rows.length()==1){"FixtureSetup: later batch must contain one original-Watch final"}
        val row=rows.getJSONObject(0);val capturedScope=packet.getString("scope");val key=row.getString("identity")
        check(row.getString("node")=="original-watch"&&row.getBoolean("live_watch")){"FixtureSetup: original custody"}
        println("POLICY600_CONSUMED_LATER_SHA256="+java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})
        val intake=WatchFinalIntake(app,{"phone-node" to capturedScope},{false},{})
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        intake.receive("phone-node",capturedScope,(0 until rows.length()).map{rows.getJSONObject(it)})
        await("captured later batch actually posts"){manager().activeNotifications.any{it.tag==key}}
        val notification=manager().activeNotifications.single{it.tag==key}.notification
        assertEquals("actual Phone capture must reach the quiet notifier without a settings update",ReplyAlertContent.QUIET_CHANNEL_ID,notification.channelId)
        assertNull(notification.sound);assertNull(notification.vibrate)
        assertEquals(0,notification.defaults and (Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE))
        assertFalse(app.settings.value.privateAudio)
        await("captured final retains posted receipt custody"){sent.any{it.first=="/hv/v1/finals/receipt"&&JSONObject(String(it.second)).optJSONArray("posted")?.toString()?.contains(key)==true}}
        assertFalse(sent.any{it.first=="/hv/v1/finals/receipt"&&JSONObject(String(it.second)).optJSONArray("read")?.toString()?.contains(key)==true})
    }
    @Test fun cancelledOriginalCannotBeResurrectedByQuietDuplicate(){
        deniedPublic();val cancel=packet(true);cancel.getJSONArray("finals").getJSONObject(0).put("cancelled",true);deliver(cancel)
        await("cancellation retained"){root().optJSONObject("cancelled_rows")?.has("$session|19")==true}
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);deliver(packet(true))
        assertNull(saved());assertTrue(manager().activeNotifications.none{it.tag==identity});assertFalse(receipt("posted"))
    }
}
