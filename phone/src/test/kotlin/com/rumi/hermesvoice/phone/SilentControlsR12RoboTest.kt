package com.rumi.hermesvoice.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.rumi.hermesvoice.core.FakeHermesDashboard
import com.rumi.hermesvoice.core.net.AcceptedTurnStore
import com.rumi.hermesvoice.core.net.HistoryMessage
import com.rumi.hermesvoice.core.net.HistoryPage
import com.rumi.hermesvoice.core.sessions.ReceivedConversations
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*

/**
 * A whole-response assistant control (the gateway's silence contract) is not chat content, an arrival alert, unread activity or a
 * saved/pending body: history, the cache of an earlier build, a pending receive row and a live typed reply. A genuine answer next
 * to it is always still delivered. Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w411dp-h1280dp")
class SilentControlsR12RoboTest : PhoneNavRoboBase() {
    private fun model(store:ViewModelStore)=ViewModelProvider(store,ViewModelProvider.AndroidViewModelFactory.getInstance(app))[PhoneViewModel::class.java]
    private fun coldRecentBodies() {
        val cold=com.rumi.hermesvoice.core.sessions.RecentBodies(app.localStore,"conversation_bodies_v1",app::bodyScope,app.appScope)
        org.robolectric.util.ReflectionHelpers.setField(app,"recentBodies\$delegate",lazyOf(cold))
    }
    private fun owned(id:String)=app.wiring().core.sessions.registry.find(id)!!
    private fun texts(vm:PhoneViewModel)=vm.state.value.history.map { it.text }
    private fun arrivals()=app.getSystemService(NotificationManager::class.java).activeNotifications.filter {
        it.notification.channelId in setOf("reply_arrival","reply_arrival_quiet")
    }
    private fun arrivalTexts()=arrivals().map { n ->
        listOf(Notification.EXTRA_TITLE,Notification.EXTRA_TEXT,Notification.EXTRA_BIG_TEXT,Notification.EXTRA_SUB_TEXT)
            .joinToString("\n") { n.notification.extras.getCharSequence(it)?.toString().orEmpty() }
    }

    @Test fun P01_aControlOpenedFromHistoryIsNotShownAndTheRawCursorStillAdvances() {
        val id=titled("History","history")
        fake.injectRow(id,"user","question")
        fake.injectFinalRow(id,"NO_REPLY",10.0,"silent-a")
        fake.injectFinalRow(id,"[SILENT]",11.0,"silent-b")
        fake.injectFinalRow(id,"the real answer",12.0,"real-a")
        val store=ViewModelStore()
        try {
            val vm=model(store);vm.open(owned(id))
            waitFor("history shown") { texts(vm).contains("the real answer") }
            assertEquals(listOf("question","the real answer"),texts(vm))
            assertEquals("every raw row still counts toward the older-page cursor",fake.rows.getValue(id).messages.size,vm.state.value.rawLoaded)
        } finally { store.clear() }
    }

    @Test fun P02_aControlFinalFromTheServerIsNeitherNotifiedBoldNorPendingWhileAGenuineOneIs() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val a=titled("Quiet","quiet");val b=titled("Loud","loud")
        val vm=showWith(a,b);openCard(vm,a);systemBack();openCard(vm,b);systemBack()
        fake.injectFinalRow(a,"NO_REPLY",500.0,"quiet-control")
        fake.injectFinalRow(b,"a genuine answer",501.0,"loud-answer")
        vm.refresh()
        waitFor("the genuine answer is alerted") { arrivalTexts().any { it.contains("a genuine answer") } }
        vm.refresh();waitFor("second scan settled") { vm.state.value.status.isEmpty() }
        assertFalse("a control is never an arrival alert: ${arrivalTexts()}",arrivalTexts().any { it.contains("NO_REPLY") })
        assertEquals("exactly the genuine arrival",1,arrivals().size)
        assertTrue("no pending or unread control: ${app.received.pending().map { it.optString("text") }}",app.received.pending().none { it.optString("text")=="NO_REPLY" })
        assertFalse("a control is not unread activity",app.received.unread(a))
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun P03_aControlSavedByAnEarlierBuildIsNotShownFromTheCache() {
        val id=titled("Cached","cached")
        fake.injectRow(id,"user","question");fake.injectRow(id,"assistant","real answer")
        val first=ViewModelStore();val second=ViewModelStore()
        val reached=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            val legacy=HistoryPage(id,listOf(
                HistoryMessage(1,"user","question",1.0),
                HistoryMessage(2,"assistant","NO_REPLY",2.0,finishReason="stop"),
                HistoryMessage(3,"assistant","real answer",3.0,finishReason="stop"),
            ),3,listOf(1L,2L,3L))
            val warm=model(first);warm.open(owned(id))
            waitFor("scope ready") { warm.state.value.selected?.storedSessionId==id && texts(warm).contains("real answer") }
            app.recentBodies.eligible(id)
            app.recentBodies.remember(legacy,false)
            waitFor("legacy page on disk") { app.localStore.getString("conversation_bodies_v1")?.contains("\"NO_REPLY\"")==true }
            first.clear()
            coldRecentBodies()
            fake.messagesHook={ _,_,_ -> reached.countDown();check(release.await(30,TimeUnit.SECONDS));0 }
            val next=model(second)
            next.open(owned(id))
            waitFor("remote read held") { reached.count==0L }
            assertEquals("the cached control is dropped, the rest stays",listOf("question","real answer"),texts(next))
        } finally { release.countDown();fake.messagesHook=null;first.clear();second.clear() }
    }

    @Test fun P04_aPendingReceiveRowFromAnEarlierBuildThatHoldsAControlIsNotNotifiedButItsGenuineNeighbourIs() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val id=titled("Legacy","legacy")
        val ns=app.bodyScope()!!
        fun row(rowId:Long,uid:String,text:String)=JSONObject().put("identity",ReceivedConversations.identity(ns,id,rowId,uid)).put("session_id",id)
            .put("row_id",rowId).put("uid",uid).put("timestamp",100.0+rowId).put("final",true).put("read",false).put("baseline",false)
            .put("quiet",false).put("private_origin",false).put("phone",false).put("watch",false).put("node","").put("watch_done",true).put("text",text)
        val control=row(7,"legacy-control","NO_REPLY");val genuine=row(8,"legacy-genuine","a genuine pending answer")
        val rows=JSONObject().put(control.getString("identity"),control).put(genuine.getString("identity"),genuine)
        val root=JSONObject().put("v",1).put("scope",ns).put("sessions",JSONObject().put(id,
            JSONObject().put("cursor",8).put("received",108.0).put("rows",rows).put("expected",JSONArray())))
        assertTrue(app.localStore.commitString("received_conversations_v1",root.toString()))
        app.received.invalidate()
        val vm=showing()
        vm.refresh()
        waitFor("the genuine pending answer is alerted") { arrivalTexts().any { it.contains("a genuine pending answer") } }
        vm.refresh();waitFor("scan settled") { vm.state.value.status.isEmpty() }
        assertFalse("a stored control is never an arrival alert: ${arrivalTexts()}",arrivalTexts().any { it.contains("NO_REPLY") })
        assertEquals(1,arrivals().size)
        assertTrue("the control is settled, not left pending",app.received.pending().none { it.optString("text")=="NO_REPLY" })
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun P05_aTypedSendWhoseReplyIsAControlLeavesNoAlertNoNoticeNoBubbleAndNothingOwed() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val id=titled("Typed","typed")
        fake.injectRow(id,"assistant","an earlier answer")
        fake.scripts[id]={listOf(FakeHermesDashboard.complete("NO_REPLY"))}
        val store=ViewModelStore()
        try {
            val vm=model(store);vm.open(owned(id))
            waitFor("opened and its page landed") { vm.state.value.selected?.storedSessionId==id && texts(vm).contains("an earlier answer") }
            vm.setDraft("one prompt");vm.send()
            waitFor("the prompt reached Hermes once") { fake.rawPrompts.size==1 }
            waitFor("the turn finished") { vm.state.value.sendsInFlight==0 && AcceptedTurnStore(app.localStore).pending().isEmpty() }
            assertEquals(listOf("one prompt"),fake.rawPrompts.map { it.second })
            assertTrue("no alert and no 'reply not received' notice: ${arrivalTexts()}",arrivals().isEmpty())
            assertTrue("no activity bubble is left behind",vm.state.value.activity.isEmpty())
            assertFalse("a control is not chat content: ${texts(vm)}",texts(vm).contains("NO_REPLY"))
            assertTrue("a silent terminal is not a failure",vm.state.value.failedSends.isEmpty())
        } finally { store.clear() }
    }
}
