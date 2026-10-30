package com.rumi.hermesvoice.phone

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.*
import com.rumi.hermesvoice.core.headset.HeadsetPolicy
import com.rumi.hermesvoice.core.sessions.ReceivedConversations
import com.rumi.hermesvoice.core.watchlink.*
import io.mockk.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Real canonical ingestion, reader, retry and notification callers; only HTTP/Data Layer/device discovery leaves are synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Policy600PhoneRoboTest:PhoneRoboBase() {
    private val devices=FakeDevices()
    private val wires=ConcurrentLinkedQueue<ByteArray>()
    private var first:TaskCompletionSource<Int>?=null
    @Before fun setup(){
        baseSetUp();app.settings.useHeadset=true
        HeadsetPolicy::class.java.getDeclaredField("devices").also{it.isAccessible=true}.set(app.headset,devices)
        val client=mockk<MessageClient>()
        every{Wearable.getMessageClient(any<Context>())}returns client
        every{client.sendMessage(any(),any(),any())}answers{
            if(secondArg<String>()==FinalRecovery.FINALS){
                wires+=thirdArg<ByteArray>().copyOf()
                if(wires.size==1&&first!=null)first!!.task else Tasks.forException<Int>(java.io.IOException("synthetic Watch offline"))
            }else Tasks.forResult(1)
        }
    }
    @After fun cleanup(){first?.trySetResult(1);baseTearDown()}
    private fun cold()=ReceivedConversations(app.localStore){app.bodyScope()}
    private fun reconcile(){val done=AtomicBoolean();val job=app.appScope.launch{try{app.finalRecovery.reconcile()}finally{done.set(true)}};assertTrue(await{done.get()&&job.isCompleted})}
    private fun seed(count:Int,phonePosted:Boolean):Pair<String,List<Long>> {
        val id=conversation("policy")
        if(phonePosted)shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS) else shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val rows=(1..count).map { n ->
            fake.injectFinalRow(id,"policy-final-$n",1800000000.0+n,"policy-uid-$n")
            fake.rows.getValue(id).messages.last().getLong("id").also { assertTrue(app.received.expect(id,it,"original-watch",false,watchReceipt=true)) }
        }
        reconcile()
        check(cold().pending(true).size==count){"FixtureSetup: original-Watch rows not durably pending"}
        check(cold().pending(true).all{!it.optBoolean("quiet")&&it.optBoolean("phone")==phonePosted}){"FixtureSetup: initial policy/post state"}
        wires.clear();return id to rows
    }
    private fun assertCustody(row:JSONObject){
        assertTrue(row.getBoolean("quiet"));assertTrue(row.getBoolean("live_watch"));assertTrue(row.getBoolean("watch"))
        assertFalse(row.getBoolean("private_origin"));assertFalse(row.getBoolean("watch_done"));assertFalse(row.getBoolean("read"))
        assertEquals("original-watch",row.getString("node"))
    }
    @Test fun readerStrengtheningSurvivesWarmColdUnrelatedCommitAndOutputLoss(){
        val(id,rows)=seed(1,true);val rowId=rows.single();val ns=app.bodyScope()!!
        devices.connect(Gear.wiredHeadset);check(app.headset.output()!=null){"FixtureSetup: no private output"}
        val response=ReaderResponse("policy-reader",ReaderKind.SESSIONS,true,scope=app.sessionListScope(),bodyScope=ns)
        val firstReply=app.finalRecovery.reader("original-watch",response)
        assertTrue("first wire must be private",firstReply.finals.single().getBoolean("quiet"))
        assertTrue("outer reader must not republish its stale hint",app.received.canonicalRow(id,rowId)!!.getBoolean("quiet"))
        assertCustody(cold().canonicalRow(id,rowId)!!)
        assertTrue(app.received.checkpoint("20260101_120000_unrelated",4))
        assertTrue(app.received.ingest("20260101_120000_unrelated",emptyList(),false,false))
        devices.disconnectAll();check(app.headset.output()==null)
        assertCustody(app.received.canonicalRow(id,rowId)!!);assertCustody(cold().canonicalRow(id,rowId)!!)
        assertTrue(app.finalRecovery.reader("original-watch",response).finals.single().getBoolean("quiet"))
        assertTrue(app.finalRecovery.reader("foreign-watch",response).finals.isEmpty())
        assertFalse(app.finalRecovery.reader("original-watch",response.copy(bodyScope="wrong-scope")).ok)
        assertTrue(cold().watchReceiptAllowed(firstReply.finals.single().getString("identity"),"original-watch"))
        assertTrue(fake.prompts.isEmpty())
    }
    @Test fun watchOnlySecondBatchRebindsAfterTheFirstActualAwait(){
        val(id,rowIds)=seed(101,true);first=TaskCompletionSource()
        val done=AtomicBoolean();val job=app.appScope.launch{try{app.finalRecovery.reconcile()}finally{done.set(true)}}
        try{
            assertTrue("first actual batch is held",await{wires.size==1})
            check(JSONObject(String(wires.first())).getJSONArray("finals").length()==100){"FixtureSetup: first batch size"}
            assertFalse(done.get());check(app.headset.output()==null)
            devices.connect(Gear.wiredHeadset);check(app.headset.output()!=null)
            first!!.setResult(1)
            assertTrue("retry closes naturally",await{done.get()&&job.isCompleted})
            val packets=wires.map{JSONObject(String(it))}
            check(packets.size==2){"FixtureSetup: expected two real batches, got ${packets.size}"}
            val later=packets[1].getJSONArray("finals");assertEquals(1,later.length())
            println("POLICY600_LATER_WIRE_BASE64="+java.util.Base64.getEncoder().encodeToString(wires.last()))
            assertTrue("later batch must sample newly known H after awaited first send",later.getJSONObject(0).getBoolean("quiet"))
            val row=later.getJSONObject(0);assertEquals("original-watch",row.getString("node"));assertTrue(row.getBoolean("live_watch"))
            assertCustody(app.received.canonicalRow(id,row.getLong("row_id"))!!)
            assertCustody(cold().canonicalRow(id,row.getLong("row_id"))!!)
            assertEquals(101,cold().pending(true).size);assertTrue(cold().pending().isEmpty())
            assertEquals(rowIds.toSet(),packets.flatMap{p->p.getJSONArray("finals").let{a->(0 until a.length()).map{a.getJSONObject(it).getLong("row_id")}}}.toSet())
            assertTrue(fake.prompts.isEmpty())
        }finally{first!!.trySetResult(1);assertTrue("held sender released and joined",await{job.isCompleted})}
    }
    @Test fun initialDrainRechecksPhonePostAfterAwaitedWatchHandoff(){
        val(id,rows)=seed(1,false);first=TaskCompletionSource()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val done=AtomicBoolean();val job=app.appScope.launch{try{app.finalRecovery.reconcile()}finally{done.set(true)}}
        try{
            assertTrue(await{wires.size==1});assertFalse(done.get())
            devices.connect(Gear.wiredHeadset);check(app.headset.output()!=null);first!!.setResult(1)
            assertTrue(await{done.get()&&job.isCompleted})
            val row=cold().canonicalRow(id,rows.single())!!
            val posted=app.getSystemService(NotificationManager::class.java).activeNotifications.single{it.tag==row.getString("identity")}.notification
            assertEquals("post after a suspended Watch send needs fresh privacy",com.rumi.hermesvoice.core.notify.ReplyAlertContent.QUIET_CHANNEL_ID,posted.channelId)
            assertCustody(row);assertTrue(row.getBoolean("phone"));assertTrue(fake.prompts.isEmpty())
        }finally{first!!.trySetResult(1);assertTrue(await{job.isCompleted})}
    }
}
