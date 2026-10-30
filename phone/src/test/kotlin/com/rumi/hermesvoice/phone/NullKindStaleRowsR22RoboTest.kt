package com.rumi.hermesvoice.phone

import android.Manifest
import android.app.NotificationManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.rumi.hermesvoice.core.net.HistoryMessage
import com.rumi.hermesvoice.core.net.HistoryPage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*

/**
 * Android's JSONObject reads an explicit JSON null as the four letters "null", so a row the server marks `"display_kind": null` must
 * be treated as untyped (absent), never as a named kind; a literal kind that is the string "null" stays a named kind. A row the server
 * now hides must also leave the screen when the stale copy is older than every row of the fresh page, or the fresh page shows nothing.
 * Synthetic data only; every row travels through the real Android parser.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w411dp-h1280dp")
class NullKindStaleRowsR22RoboTest : PhoneNavRoboBase() {
    private val absent = Any()

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

    private fun wire(id:Long,role:String,text:String,kind:Any?=absent,uid:Any?=absent,finish:String?=null,tools:Boolean=false):JSONObject {
        val row=JSONObject().put("id",id).put("role",role).put("content",text).put("timestamp",1_700_000_000.0+id)
        if (kind!==absent) row.put("display_kind",kind ?: JSONObject.NULL)
        if (uid!==absent) row.put("message_uid",uid ?: JSONObject.NULL)
        if (finish!=null) row.put("finish_reason",finish)
        if (tools) row.put("tool_calls",org.json.JSONArray().put(JSONObject().put("id","call_1")))
        return row
    }

    private fun serve(id:String,vararg rows:JSONObject) {
        val list=fake.rows.getValue(id).messages
        synchronized(list) { list.clear();rows.forEach { list += JSONObject(it.toString()).put("session_id",id) } }
    }

    private fun retag(id:String,rowId:Long,kind:String) {
        val list=fake.rows.getValue(id).messages
        synchronized(list) { list.first { it.getLong("id")==rowId }.put("display_kind",kind) }
    }

    @Test fun N01_anExplicitJsonNullKindIsUntypedWhileANamedOrStringNullKindIsNot() {
        val rows=listOf(
            wire(1,"assistant","NO_REPLY",kind=null),
            wire(2,"assistant","NO_REPLY"),
            wire(3,"assistant","NO_REPLY",kind=""),
            wire(4,"assistant","NO_REPLY",kind="a_named_kind"),
            wire(5,"assistant","NO_REPLY",kind="null"),
            wire(6,"user","NO_REPLY",kind=null),
            wire(7,"assistant","NO_REPLY",kind=null,tools=true),
            wire(8,"assistant","the real answer",kind=null,uid=null,finish="stop"),
            wire(9,"assistant","NO_REPLY",kind="internal_notification"),
            wire(10,"assistant","Use NO_REPLY when nothing needs saying.",kind=null),
            wire(11,"user","[SYSTEM: Background process proc_synthetic_01 completed (exit code 0)]",kind=null),
        )
        val page=HistoryPage.parse(JSONObject("{\"session_id\":\"s\",\"messages\":"+rows.joinToString(",","[","]")+",\"pagination\":{\"returned\":11}}"))
        assertEquals("only explicit-null/omitted/empty whole-response controls and the typed internal row are removed",
            listOf(4L,5L,6L,7L,8L,10L,11L),page.messages.map { it.rowId })
        assertEquals((1L..11L).toList(),page.rawIds)
        assertEquals(11,page.returned)
        assertEquals("an explicit null uid is no uid","",page.messages.first { it.rowId==8L }.messageUid)
    }

    @Test fun N02_aControlWithAnExplicitNullKindIsNeitherShownAlertedUnreadNorPending() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val id=titled("Null kind","nullkind")
        serve(id,wire(1,"user","question"),wire(2,"assistant","NO_REPLY",kind=null,uid="silent-a",finish="stop"))
        val store=ViewModelStore()
        try {
            val vm=model(store);vm.open(owned(id))
            waitFor("history shown") { vm.state.value.rawLoaded==2 }
            assertEquals(listOf("question"),texts(vm))
            assertEquals("every raw row still counts toward the older-page cursor",2,vm.state.value.rawLoaded)
            assertFalse("opening or fetching is not unread activity",app.received.unread(id))
            assertTrue("no pending control: ${app.received.pending().map { it.optString("text") }}",app.received.pending().none { it.optString("text")=="NO_REPLY" })
            assertTrue("no alert: ${arrivals().size}",arrivals().isEmpty())
            assertTrue(fake.prompts.isEmpty())
        } finally { store.clear() }
    }

    private fun staleCached(id:String,vararg cached:HistoryMessage) {
        val warm=ViewModelStore()
        try {
            val vm=model(warm);vm.open(owned(id))
            waitFor("scope ready") { vm.state.value.selected?.storedSessionId==id && texts(vm).isNotEmpty() }
            app.recentBodies.eligible(id)
            app.recentBodies.remember(HistoryPage(id,cached.toList(),cached.size,cached.map { it.rowId }),false)
            waitFor("cached page on disk") { app.localStore.getString("conversation_bodies_v1")?.contains("stale internal body")==true }
        } finally { warm.clear() }
        coldRecentBodies()
    }

    private fun openHeld(id:String,store:ViewModelStore,whileHeld:(PhoneViewModel)->Unit):PhoneViewModel {
        val reached=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            fake.messagesHook={ _,_,_ -> reached.countDown();check(release.await(30,TimeUnit.SECONDS));0 }
            val vm=model(store);vm.open(owned(id))
            waitFor("remote read held") { reached.count==0L }
            whileHeld(vm)
            return vm
        } finally { release.countDown();fake.messagesHook=null }
    }

    @Test fun N03_aCachedRowTheServerNowHidesIsDroppedEvenWhenItIsOlderThanEveryShownRow() {
        val id=titled("Stale older","staleolder")
        serve(id,wire(1,"assistant","stale internal body",finish="stop"),wire(2,"assistant","the answer",finish="stop"))
        staleCached(id,HistoryMessage(1,"assistant","stale internal body",1.0,finishReason="stop"),HistoryMessage(2,"assistant","the answer",2.0,finishReason="stop"))
        retag(id,1,"internal_notification")
        val store=ViewModelStore()
        try {
            val vm=openHeld(id,store) { held ->
                waitFor("the restarted cache is shown while the read is held") { texts(held)==listOf("stale internal body","the answer") }
            }
            waitFor("the fresh page replaced the stale copy",10_000) { texts(vm)==listOf("the answer") }
            assertEquals("the raw cursor still counts the hidden row",2,vm.state.value.rawLoaded)
        } finally { fake.messagesHook=null;store.clear() }
    }

    @Test fun N04_aPageWhoseEveryRowIsHiddenClearsTheRowsItNamesFromTheRestartedCache() {
        val id=titled("Hidden only","hiddenonly")
        serve(id,wire(1,"assistant","stale internal body",finish="stop"))
        staleCached(id,HistoryMessage(1,"assistant","stale internal body",1.0,finishReason="stop"))
        retag(id,1,"hidden")
        val store=ViewModelStore()
        try {
            val vm=openHeld(id,store) { held ->
                waitFor("the restarted cache is shown while the read is held") { texts(held)==listOf("stale internal body") }
            }
            waitFor("the hidden-only page removed the named row",10_000) { texts(vm).isEmpty() }
            assertEquals("the raw returned count is kept",1,vm.state.value.rawLoaded)
            assertFalse(app.received.unread(id))
        } finally { fake.messagesHook=null;store.clear() }
    }

    @Test fun N05_aRefreshOfTheOpenConversationDropsARowTheServerHasSinceHiddenAndKeepsTheRest() {
        val id=titled("Refresh","refresh")
        serve(id,wire(1,"assistant","stale internal body",finish="stop"),wire(2,"assistant","the answer",finish="stop"))
        val store=ViewModelStore()
        try {
            val vm=model(store);vm.open(owned(id))
            waitFor("both rows shown") { texts(vm)==listOf("stale internal body","the answer") }
            retag(id,1,"async_delegation_complete")
            vm.openOrResume(owned(id))
            waitFor("only the answer remains",10_000) { texts(vm)==listOf("the answer") }
        } finally { store.clear() }
    }

    @Test fun N06_aRowWhoseStableUidMovedToANewPhysicalIdIsNotShownTwiceButAnotherUidWithTheSameWordsIs() {
        val id=titled("Moved","moved")
        serve(id,wire(5,"assistant","same words",uid="uid-a",finish="stop"))
        val store=ViewModelStore()
        try {
            val vm=model(store);vm.open(owned(id))
            waitFor("first shown") { vm.state.value.history.map { it.rowId }==listOf(5L) }
            serve(id,wire(30,"assistant","same words",uid="uid-a",finish="stop"),wire(31,"assistant","same words",uid="uid-b",finish="stop"),
                wire(32,"user","same words"))
            vm.openOrResume(owned(id))
            waitFor("the moved row replaced its old copy; the other identities stay",10_000) { vm.state.value.history.map { it.rowId }==listOf(30L,31L,32L) }
        } finally { store.clear() }
    }
}
