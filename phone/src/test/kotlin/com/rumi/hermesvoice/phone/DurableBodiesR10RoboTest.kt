package com.rumi.hermesvoice.phone

import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.ViewModelStore
import com.rumi.hermesvoice.core.sessions.OwnedSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w411dp-h1280dp")
class DurableBodiesR10RoboTest : PhoneNavRoboBase() {
    private fun model(store: ViewModelStore) = ViewModelProvider(store,ViewModelProvider.AndroidViewModelFactory.getInstance(app))[PhoneViewModel::class.java]
    @Test fun B01_recreatedReaderShowsSavedCanonicalBodyBeforeHeldServerThenMergesById() {
        val id=titled("Body","body")
        fake.injectRow(id,"assistant","canonical 한국어 @file:/synthetic/report.pdf")
        val owned=app.wiring().core.sessions.registry.find(id)!!
        val first=ViewModelStore();val second=ViewModelStore()
        val reached=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            val before=model(first);before.open(owned)
            waitFor("first real history displayed") { before.state.value.history.any { it.text.startsWith("canonical") } }
            first.clear()
            fake.messagesHook={ _,_,_ -> reached.countDown();check(release.await(30,TimeUnit.SECONDS));0 }
            val after=model(second);after.open(owned)
            waitFor("successor real history read entered") { reached.count==0L }
            assertEquals("saved body must not wait for server",before.state.value.history,after.state.value.history)
            assertEquals(id,after.state.value.selected?.storedSessionId)
            fake.injectRow(id,"assistant","distinct new incoming")
            release.countDown()
            waitFor("authoritative newest page reconciled") { after.state.value.history.any { it.text=="distinct new incoming" } }
            assertEquals(after.state.value.history.size,after.state.value.history.map { it.rowId }.toSet().size)
            assertTrue(fake.prompts.isEmpty())
        } finally { release.countDown();fake.messagesHook=null;first.clear();second.clear() }
    }
    @Test fun B04_bodyWriteFailureIsVisibleAndDoesNotLoseOnlineContent() {
        val local=app.localStore
        val attempts=java.util.concurrent.atomic.AtomicInteger()
        val failing=local
        io.mockk.mockkObject(failing)
        io.mockk.every { failing.commitString("conversation_bodies_v1",any()) } answers {
            attempts.incrementAndGet();false
        }
        org.robolectric.util.ReflectionHelpers.setField(app,"localStore\$delegate",lazyOf(failing))
        val id=titled("Storage failure","storagefail")
        fake.injectRow(id,"assistant","online content stays canonical")
        val vm=showWith(id);openCard(vm,id)
        waitFor("real body writer refused") { attempts.get()>0 }
        waitFor("actionable cache failure is visible") { rule.onAllNodesWithText("Recent messages not saved",substring=true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(vm.state.value.history.any { it.text=="online content stays canonical" });assertTrue(fake.prompts.isEmpty())
    }

    @Test fun B05_failedClearAndLateWriteCannotCrossSameUserRelogin() {
        val local=app.localStore
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val readEntered=CountDownLatch(1);val readRelease=CountDownLatch(1)
        val first=ViewModelStore();val second=ViewModelStore()
        val guarded=local
        io.mockk.mockkObject(guarded)
        io.mockk.every { guarded.commitString("conversation_bodies_v1",any()) } answers {
            val value=secondArg<String>()
            if(value=="{}") false else {
                entered.countDown();check(release.await(30,TimeUnit.SECONDS))
                local.commitString("conversation_bodies_v1",value)
            }
        }
        org.robolectric.util.ReflectionHelpers.setField(app,"localStore\$delegate",lazyOf(guarded))
        try {
            val id=titled("Relogin","relogin");fake.injectRow(id,"assistant","old private body")
            val owned=app.wiring().core.sessions.registry.find(id)!!
            val before=model(first);before.open(owned)
            waitFor("old body write really entered") { entered.count==0L }
            fun privateScope()=app.javaClass.methods.firstOrNull { it.name=="bodyScope" && it.parameterCount==0 }?.invoke(app)?:app.sessionListScope()
            val oldScope=privateScope();before.signOut()
            app.tokens.save(com.rumi.hermesvoice.core.auth.HermesBearerSession(fake.accessToken,fake.refreshToken,null,"basic","jun"))
            assertNotEquals("same user re-login is a new private cache generation",oldScope,privateScope())
            release.countDown();first.clear()
            fake.messagesHook={ _,_,_ -> readEntered.countDown();check(readRelease.await(30,TimeUnit.SECONDS));0 }
            val next=model(second);next.open(owned)
            waitFor("new scope read is held") { readEntered.count==0L }
            assertTrue("old body must not hydrate under replacement login",next.state.value.history.isEmpty())
            assertTrue(fake.prompts.isEmpty())
        } finally { release.countDown();readRelease.countDown();fake.messagesHook=null;first.clear();second.clear() }
    }

    @Test fun B07_coldPhoneApplicationUsesDiskBeforeHeldRead() {
        val first=ViewModelStore();val second=ViewModelStore()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        var fresh:PhoneApp?=null
        try {
            val id=titled("Cold Phone","coldphone");fake.injectRow(id,"assistant","durable cold Phone canonical")
            val owned=app.wiring().core.sessions.registry.find(id)!!
            val before=model(first);before.open(owned)
            waitFor("actual Phone disk commit completed") { localSnapshotContains("durable cold Phone canonical") }
            first.clear()
            fake.messagesHook={ _,_,_ -> entered.countDown();check(release.await(30,TimeUnit.SECONDS));0 }
            val nextApp=PhoneApp().also {
                org.robolectric.util.ReflectionHelpers.callInstanceMethod<Void>(it,"attach",
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(android.content.Context::class.java,app))
                it.onCreate()
            }
            fresh=nextApp
            assertEquals("relaunch preserves the same authenticated namespace",app.sessionListScope(),nextApp.sessionListScope())
            val next=ViewModelProvider(second,ViewModelProvider.AndroidViewModelFactory(nextApp))[PhoneViewModel::class.java]
            next.open(owned)
            waitFor("cold application's actual read held") { entered.count==0L }
            assertEquals("fresh application must use the saved disk page, not old VM memory",listOf("durable cold Phone canonical"),next.state.value.history.map { it.text })
            assertTrue(fake.prompts.isEmpty())
        } finally { release.countDown();fake.messagesHook=null;first.clear();second.clear();fresh?.onTerminate() }
    }

    @Test fun B06_recentStorageBoundsKeepWholeCanonicalPagesAndRawCursor() {
        val store=ViewModelStore()
        try {
            val vm=model(store)
            repeat(5) { n ->
                val id=titled("Bound $n","bound$n");repeat(55) { fake.injectRow(id,"assistant","canonical-$n-$it 한국어 @file:/synthetic/a.pdf") }
                vm.open(app.wiring().core.sessions.registry.find(id)!!)
                waitFor("bounded page $n read") { vm.state.value.selected?.storedSessionId==id && vm.state.value.history.lastOrNull()?.text?.startsWith("canonical-$n-")==true }
            }
            waitFor("latest bounded snapshot saved") { localSnapshotContains("canonical-4-") }
            val raw=app.localStore.getString("conversation_bodies_v1")!!
            val pages=org.json.JSONObject(raw).getJSONArray("pages")
            assertEquals(4,pages.length());assertTrue(raw.toByteArray().size<=1_048_576)
            for(i in 0 until pages.length()) {
                val p=pages.getJSONObject(i);assertEquals(50,p.getInt("returned"));assertTrue(p.getBoolean("has_older"))
                assertEquals(50,p.getJSONArray("messages").length());assertEquals(50,p.getJSONArray("raw_ids").length())
                assertTrue(p.getJSONArray("messages").getJSONObject(0).getString("text").contains("한국어 @file:/synthetic/a.pdf"))
            }
            assertTrue(fake.prompts.isEmpty())
        } finally { store.clear() }
    }
    private fun localSnapshotContains(text:String)=app.localStore.getString("conversation_bodies_v1")?.contains(text)==true

    @Test fun B02_savedBodyCannotSurviveLogoutAndLateRead() {
        val id=titled("Scope","scopebody")
        fake.injectRow(id,"assistant","private synthetic body")
        val owned=app.wiring().core.sessions.registry.find(id)!!
        val store=ViewModelStore();val reached=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            val vm=model(store);vm.open(owned)
            waitFor("body loaded") { vm.state.value.history.isNotEmpty() }
            fake.messagesHook={ _,_,_ -> reached.countDown();check(release.await(30,TimeUnit.SECONDS));0 }
            vm.open(owned);waitFor("old read held") { reached.count==0L }
            vm.signOut();release.countDown();pump()
            assertTrue(vm.state.value.history.isEmpty());assertNull(vm.state.value.selected)
            assertTrue(fake.prompts.isEmpty())
        } finally { release.countDown();fake.messagesHook=null;store.clear() }
    }
}
