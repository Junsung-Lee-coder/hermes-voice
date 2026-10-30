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
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.io.IOException
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Same running Application/intake, failed privacy upgrade, then foreground recovery BEFORE retransmit.
 * Store false/throw is tested both before and after a real write; neither implies no durable effect.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk=[34])
class WatchQuietRecovery603RoboTest {
    private lateinit var app:WatchApp
    private val scope="a".repeat(64)
    private val session="20260101_120000_q603"
    private val identity get()=ReceivedConversations.identity(scope,session,19,"q603")
    private val sent=ConcurrentLinkedQueue<Pair<String,ByteArray>>()
    private enum class Fault { NONE, FALSE, THROW, FALSE_AFTER_WRITE, THROW_AFTER_WRITE, CANCEL }
    @Volatile private var fault=Fault.NONE
    private class Gate { val entered=CountDownLatch(1);val release=CountDownLatch(1) }
    private class Setup(message:String):RuntimeException(message)
    private val next=AtomicReference<Gate?>()
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
        app.loadSessions();idle();val request=app.reader.value.sessions.pendingReqId?:throw Setup("sessions request")
        app.onReaderResponse("phone-node",ReaderResponse(request,ReaderKind.SESSIONS,true,sessions=listOf(ReaderSessionRow(session,"Quiet recovery","q603","",1.0)),scope=scope).encode());idle()
        mockkObject(app.localStore)
        every{app.localStore.commitString(WatchFinalIntake.KEY,any())}answers{
            next.getAndSet(null)?.let { g ->
                g.entered.countDown()
                if(!g.release.await(10,TimeUnit.SECONDS))throw Setup("commit gate not released")
            }
            when(fault){
                Fault.FALSE -> false
                Fault.THROW -> throw IOException("synthetic storage refusal")
                Fault.CANCEL -> throw CancellationException("synthetic intake cancellation")
                Fault.FALSE_AFTER_WRITE -> {callOriginal();false}
                Fault.THROW_AFTER_WRITE -> {callOriginal();throw IOException("synthetic uncertain commit")}
                Fault.NONE -> callOriginal()
            }
        }
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }
    @After fun cleanup(){next.getAndSet(null)?.release?.countDown();app.scope.coroutineContext[Job]?.cancel();unmockkAll()}
    private fun idle()=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    private fun waitFor(what:String,done:()->Boolean){val end=System.nanoTime()+10_000_000_000;while(!done()&&System.nanoTime()<end){idle();Thread.yield()};if(!done())throw Setup(what)}
    private fun intake():WatchFinalIntake {
        val lazy=WatchApp::class.java.getDeclaredField("receivedFinals\$delegate").also{it.isAccessible=true}.get(app)as Lazy<*>
        return lazy.value as WatchFinalIntake
    }
    /** Enqueue the exact intake mutex observer WHILE its named operation holds the commit latch.
     * FIFO lock admission then proves that operation finished post/receipt too, not just a commit count.
     */
    private fun operation(receiver:WatchFinalIntake=intake(),action:()->Unit){
        val g=Gate();check(next.compareAndSet(null,g))
        try{
            action();waitFor("named intake entered its commit"){g.entered.count==0L}
            val mutex=WatchFinalIntake::class.java.getDeclaredField("mutex").also{it.isAccessible=true}.get(receiver)as Mutex
            val observer=app.scope.launch(start=CoroutineStart.UNDISPATCHED){mutex.withLock { }}
            check(!observer.isCompleted){"observer must queue behind held operation"}
            g.release.countDown();waitFor("named intake finished commit, notifier and receipt"){observer.isCompleted}
        }finally{g.release.countDown();next.compareAndSet(g,null)}
    }
    private fun row(quiet:Boolean,id:String=session,rowId:Long=19,uid:String="q603",account:String=scope)=JSONObject()
        .put("identity",ReceivedConversations.identity(account,id,rowId,uid)).put("session_id",id).put("row_id",rowId).put("uid",uid)
        .put("final",true).put("quiet",quiet).put("private_origin",false).put("live_watch",true).put("node","original-watch").put("body","canonical retained body")
    private fun deliver(r:JSONObject,node:String="phone-node",account:String=scope){
        val controller=Robolectric.buildService(WatchListenerService::class.java).create()
        try{val event=mockk<MessageEvent>();every{event.path}returns "/hv/v1/finals/intake";every{event.sourceNodeId}returns node
            every{event.data}returns JSONObject().put("v",1).put("scope",account).put("finals",JSONArray().put(r)).toString().toByteArray()
            controller.get().onMessageReceived(event)
        }finally{controller.destroy()}
    }
    private fun root()=JSONObject(app.localStore.getString(WatchFinalIntake.KEY)?:"{}")
    private fun saved(key:String=identity)=root().optJSONObject("rows")?.optJSONObject(key)
    private fun manager()=app.getSystemService(NotificationManager::class.java)
    private fun receipt(field:String,key:String=identity)=sent.any{it.first=="/hv/v1/finals/receipt"&&JSONObject(String(it.second)).optJSONArray(field)?.let{a->(0 until a.length()).any{a.optString(it)==key}}==true}
    private fun denied(){
        operation{deliver(row(false))}
        val r=saved()?:throw Setup("original final not stored")
        if(r.optBoolean("quiet")||r.optBoolean("posted")||manager().activeNotifications.any{it.tag==identity})throw Setup("original must be public and denied")
        assertFalse(receipt("posted"));assertFalse(receipt("read"))
    }
    private fun failedUpgrade(f:Fault){
        denied();fault=f;shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        operation{deliver(row(true))}
        assertTrue("failed upgrade itself cannot post",manager().activeNotifications.none{it.tag==identity})
        assertFalse(receipt("posted"));assertFalse(receipt("read"));assertFalse(saved()!!.optBoolean("posted"))
        assertFalse("settings replica deliberately remains public",app.settings.value.privateAudio)
    }
    private fun noPostOrReceipt(){
        assertTrue("observed private final must not post stale public policy during failed recovery",manager().activeNotifications.none{it.tag==identity})
        assertFalse("no uncommitted posted receipt",receipt("posted"));assertFalse(receipt("read"))
        assertFalse(saved()!!.optBoolean("posted"));assertFalse(saved()!!.optBoolean("read"))
    }
    private fun quietPosted(){
        val n=manager().activeNotifications.single{it.tag==identity}.notification
        assertEquals("foreground recovery must honor already-observed quiet policy",ReplyAlertContent.QUIET_CHANNEL_ID,n.channelId)
        assertNull(n.sound);assertNull(n.vibrate);assertEquals(0,n.defaults and(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE))
        assertEquals(Notification.VISIBILITY_PRIVATE,n.visibility);assertNotNull(n.publicVersion)
        assertTrue(saved()!!.getBoolean("quiet"));assertTrue(saved()!!.getBoolean("posted"));assertFalse(saved()!!.optBoolean("read"))
        assertTrue(receipt("posted"));assertFalse(receipt("read"));assertFalse(app.settings.value.privateAudio)
        assertEquals("original-watch",saved()!!.getString("node"));assertEquals("canonical retained body",saved()!!.getString("body"))
    }
    @Test fun falseUpgradeThenActualForegroundWhileStorageStillFails(){
        failedUpgrade(Fault.FALSE);operation{app.onForeground(true)};noPostOrReceipt()
    }
    @Test fun thrownUpgradeThenSameIntakeGeneralRecoveryWhileStorageStillFails(){
        failedUpgrade(Fault.THROW);operation{intake().recover()};noPostOrReceipt()
    }
    @Test fun falseAfterRealWriteStillDoesNotAuthorizeForegroundPostOrReceipt(){
        failedUpgrade(Fault.FALSE_AFTER_WRITE);assertTrue(saved()!!.getBoolean("quiet"))
        operation{app.onForeground(true)};noPostOrReceipt()
    }
    @Test fun throwAfterRealWriteStillDoesNotAuthorizeGeneralPostOrReceipt(){
        failedUpgrade(Fault.THROW_AFTER_WRITE);assertTrue(saved()!!.getBoolean("quiet"))
        operation{intake().recover()};noPostOrReceipt()
    }
    @Test fun restoredStorageForegroundRecoversQuietBeforeAnyRetransmission(){
        failedUpgrade(Fault.FALSE);fault=Fault.NONE;operation{app.onForeground(true)};quietPosted()
        manager().cancel(identity,WatchReplyAlertNotifier.NOTIFICATION_ID)
        operation{deliver(row(false))};assertTrue(saved()!!.getBoolean("quiet"));assertTrue(manager().activeNotifications.none{it.tag==identity})
    }
    @Test fun thrownUpgradeRestoredStorageGeneralRecoveryCommitsQuiet(){
        failedUpgrade(Fault.THROW);fault=Fault.NONE;operation{intake().recover()};quietPosted()
    }
    @Test fun sameIdentityFalseRetryCannotForgetUncommittedPrivacy(){
        failedUpgrade(Fault.FALSE);operation{deliver(row(false))};noPostOrReceipt()
        fault=Fault.NONE;operation{deliver(row(false))};quietPosted()
    }
    @Test fun unrelatedIntakeCommitPreservesQuietAndOriginalCustody(){
        failedUpgrade(Fault.FALSE);fault=Fault.NONE
        val other=row(false,id="20260101_120001_other",rowId=20,uid="other")
        operation{deliver(other)};quietPosted()
        val key=other.getString("identity");assertEquals(ReplyAlertContent.CHANNEL_ID,manager().activeNotifications.single{it.tag==key}.notification.channelId)
        assertEquals("canonical retained body",saved(key)!!.getString("body"));assertTrue(receipt("posted",key));assertFalse(receipt("read",key))
    }
    @Test fun cancellationAtUpgradeCommitRetainsKnownPrivacyForForeground(){
        failedUpgrade(Fault.CANCEL);fault=Fault.NONE;operation{app.onForeground(true)};quietPosted()
    }
    @Test fun foreignNodeScopeAndForgedIdentityDoNotTightenOriginal(){
        denied();deliver(row(true),node="foreign-phone");deliver(row(true),account="b".repeat(64))
        operation{deliver(row(true).put("identity","f"+"0".repeat(64)))}
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);operation{app.onForeground(true)}
        assertEquals(ReplyAlertContent.CHANNEL_ID,manager().activeNotifications.single{it.tag==identity}.notification.channelId)
        assertFalse(saved()!!.getBoolean("quiet"));assertTrue(receipt("posted"));assertFalse(receipt("read"))
    }
    @Test fun cancelledOriginalRemainsCancelledAfterFailedUpgradeAndRecovery(){
        failedUpgrade(Fault.FALSE);fault=Fault.NONE;operation{deliver(row(true).put("cancelled",true))}
        assertNull(saved());assertTrue(root().getJSONObject("cancelled_rows").has("$session|19"))
        // A following receive and foreground cannot revive the cancelled obligation.
        operation{deliver(row(false))};app.onForeground(true);assertNull(saved());assertFalse(receipt("posted"));assertFalse(receipt("read"))
        assertTrue(manager().activeNotifications.none{it.tag==identity})
    }
    private val completion=AtomicReference<Gate?>()
    private fun controlled(visible:Boolean=false)=WatchFinalIntake(app,{"phone-node" to scope},{visible},{
        completion.getAndSet(null)?.let { g -> g.entered.countDown();if(!g.release.await(10,TimeUnit.SECONDS))throw Setup("availability gate") }
    })
    private fun recovered(receiver:WatchFinalIntake){
        val g=Gate();check(completion.compareAndSet(null,g))
        try{
            receiver.recover();waitFor("same receiver reached recovery availability"){g.entered.count==0L}
            val mutex=WatchFinalIntake::class.java.getDeclaredField("mutex").also{it.isAccessible=true}.get(receiver)as Mutex
            val observer=app.scope.launch(start=CoroutineStart.UNDISPATCHED){mutex.withLock { }}
            check(!observer.isCompleted);g.release.countDown();waitFor("same recovery completed receipt boundary"){observer.isCompleted}
        }finally{g.release.countDown();completion.compareAndSet(g,null)}
    }
    @Test fun revokedConversationDoesNotPostWhileRemovalPersistenceFails(){
        val receiver=controlled()
        operation(receiver){receiver.receive("phone-node",scope,listOf(row(false)))}
        fault=Fault.FALSE;operation(receiver){receiver.receive("phone-node",scope,listOf(row(true)))}
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        operation(receiver){receiver.remove(setOf(session))};recovered(receiver);noPostOrReceipt()
        fault=Fault.NONE;operation(receiver){receiver.remove(setOf(session))};assertNull(saved())
        operation(receiver){receiver.receive("phone-node",scope,listOf(row(false)))};assertNull(saved());assertFalse(receipt("posted"))
    }
    @Test fun postedReadAndAckBitsAreNotResetByFailedQuietUpgrade(){
        val receiver=controlled(visible=true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        operation(receiver){receiver.receive("phone-node",scope,listOf(row(false)))}
        operation(receiver){receiver.acknowledged("phone-node",JSONObject().put("v",1).put("scope",scope).put("posted",JSONArray().put(identity)).toString().toByteArray())}
        operation(receiver){receiver.displayed(session,listOf(ReaderMessageRow(19,"assistant","canonical retained body",false,"q603",1.0)))}
        operation(receiver){receiver.acknowledged("phone-node",JSONObject().put("v",1).put("scope",scope).put("read",JSONArray().put(identity)).toString().toByteArray())}
        manager().cancel(identity,WatchReplyAlertNotifier.NOTIFICATION_ID);fault=Fault.FALSE
        operation(receiver){receiver.receive("phone-node",scope,listOf(row(true)))}
        fault=Fault.NONE;recovered(receiver)
        for(bit in listOf("quiet","posted","read","posted_ack","read_ack"))assertTrue(bit,saved()!!.getBoolean(bit))
        assertEquals("original-watch",saved()!!.getString("node"));assertEquals("canonical retained body",saved()!!.getString("body"))
        assertTrue(manager().activeNotifications.none{it.tag==identity})
    }
    @Test fun invalidationAndReplacementBindingCannotReviveOldAccountPrivacyOrRows(){
        var account:Pair<String,String>?="phone-node" to scope
        val receiver=WatchFinalIntake(app,{account},{false},{})
        operation(receiver){receiver.receive("phone-node",scope,listOf(row(false)))}
        fault=Fault.FALSE;operation(receiver){receiver.receive("phone-node",scope,listOf(row(true)))}
        fault=Fault.NONE;operation(receiver){receiver.invalidate()}
        account="new-phone" to "b".repeat(64)
        val other=row(false,id="20260101_120001_new",rowId=20,uid="new",account=account!!.second)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        operation(receiver){receiver.receive(account!!.first,account!!.second,listOf(other))}
        assertNull(saved());assertEquals(ReplyAlertContent.CHANNEL_ID,manager().activeNotifications.single{it.tag==other.getString("identity")}.notification.channelId)
        assertTrue(manager().activeNotifications.none{it.tag==identity});assertFalse(receipt("posted"));assertFalse(receipt("read"))
    }
}
