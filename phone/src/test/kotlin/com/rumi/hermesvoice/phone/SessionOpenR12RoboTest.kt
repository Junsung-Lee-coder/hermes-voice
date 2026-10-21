package com.rumi.hermesvoice.phone

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.rumi.hermesvoice.core.attachments.AttachmentRef
import com.rumi.hermesvoice.core.attachments.RefKind
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

/**
 * Opening conversation B after A: the screen must be B's (title, body, draft, viewer) from the first moment, whatever the cache or
 * the network are doing, and the open path must not wait on disk reads on the main thread or on unrelated receive-state commits.
 * Real MainActivity-free PhoneViewModel, PhoneApp wiring and repository against the Hermes dashboard contract double; synthetic data.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w411dp-h1280dp")
class SessionOpenR12RoboTest : PhoneNavRoboBase() {
    private class Gate { val reached=CountDownLatch(1);val release=CountDownLatch(1) }

    private val gates=ConcurrentHashMap<String,java.util.concurrent.ConcurrentLinkedQueue<Gate>>()
    private fun hold(session:String):Gate = Gate().also { gate ->
        gates.getOrPut(session) { java.util.concurrent.ConcurrentLinkedQueue() }.add(gate)
        fake.messagesHook={ id,_,_ ->
            gates[id]?.poll()?.let { it.reached.countDown();check(it.release.await(30,TimeUnit.SECONDS)) }
            0
        }
    }
    private fun releaseAll() { gates.values.forEach { q -> q.forEach { it.release.countDown() } };fake.messagesHook=null }
    private fun model(store:ViewModelStore)=ViewModelProvider(store,ViewModelProvider.AndroidViewModelFactory.getInstance(app))[PhoneViewModel::class.java]
    private fun owned(id:String)=app.wiring().core.sessions.registry.find(id)!!
    private fun coldRecentBodies() {
        val cold=com.rumi.hermesvoice.core.sessions.RecentBodies(app.localStore,"conversation_bodies_v1",app::bodyScope,app.appScope)
        org.robolectric.util.ReflectionHelpers.setField(app,"recentBodies\$delegate",lazyOf(cold))
    }
    private fun texts(vm:PhoneViewModel)=vm.state.value.history.map { it.text }

    @Test fun O01_openingBFromAShowsNothingOfAWhileBsReadIsHeld() {
        val a=titled("Alpha","alpha");val b=titled("Beta","beta")
        fake.injectRow(a,"assistant","ALPHA-BODY");fake.injectRow(b,"assistant","BETA-BODY")
        val store=ViewModelStore()
        try {
            val vm=model(store);vm.open(owned(a))
            waitFor("A shown") { texts(vm)==listOf("ALPHA-BODY") }
            val gate=hold(b)
            vm.open(owned(b))
            waitFor("B's real read entered") { gate.reached.count==0L }
            val during=vm.state.value
            assertEquals("the screen is B's the moment B is opened",b,during.selected?.storedSessionId)
            assertFalse("A's body must not be shown as B: ${during.history.map { it.text }}",during.history.any { it.text=="ALPHA-BODY" })
            gate.release.countDown()
            waitFor("B arrives") { texts(vm)==listOf("BETA-BODY") }
            assertEquals(b,vm.state.value.selected?.storedSessionId)
            assertTrue(fake.prompts.isEmpty())
        } finally { releaseAll();store.clear() }
    }

    @Test fun O02_aDraftBelongsToItsConversationAndAIsNeverOfferedAsBsWhileBLoads() {
        val a=titled("Alpha","alpha");val b=titled("Beta","beta")
        fake.injectRow(a,"assistant","ALPHA-BODY");fake.injectRow(b,"assistant","BETA-BODY")
        val store=ViewModelStore()
        try {
            val vm=model(store);vm.open(owned(a))
            waitFor("A shown") { texts(vm)==listOf("ALPHA-BODY") }
            vm.setDraft("typed for alpha only")
            val gate=hold(b)
            vm.open(owned(b))
            waitFor("B's real read entered") { gate.reached.count==0L }
            assertEquals("A's unsent text must not sit in B's composer","",vm.state.value.draft)
            gate.release.countDown()
            waitFor("B arrives") { texts(vm)==listOf("BETA-BODY") }
            assertEquals("",vm.state.value.draft)
            vm.open(owned(a))
            waitFor("A returns with its own draft") { vm.state.value.selected?.storedSessionId==a && vm.state.value.draft=="typed for alpha only" }
        } finally { releaseAll();store.clear() }
    }

    @Test fun O03_anAttachmentViewerOpenedInAIsClosedWhenBIsOpened() {
        val a=titled("Alpha","alpha");val b=titled("Beta","beta")
        fake.injectRow(a,"assistant","ALPHA-BODY");fake.injectRow(b,"assistant","BETA-BODY")
        val store=ViewModelStore()
        try {
            val vm=model(store);vm.open(owned(a))
            waitFor("A shown") { texts(vm)==listOf("ALPHA-BODY") }
            openStoredAttachmentFixture(fake,vm,a,AttachmentRef(RefKind.FILE,"/synthetic/r12.txt","r12.txt"))
            assertNotNull("A's viewer is open",vm.state.value.viewer)
            val gate=hold(b)
            vm.open(owned(b))
            waitFor("B's real read entered") { gate.reached.count==0L }
            assertNull("A's attachment must not stay on top of B",vm.state.value.viewer)
        } finally { releaseAll();store.clear() }
    }

    @Test fun O04_rapidAThenBThenAKeepsOnlyTheLastOpenedConversationWhatEverOrderTheReadsLand() {
        val a=titled("Alpha","alpha");val b=titled("Beta","beta")
        fake.injectRow(a,"assistant","ALPHA-BODY");fake.injectRow(b,"assistant","BETA-BODY")
        val store=ViewModelStore()
        try {
            val vm=model(store);vm.open(owned(a))
            waitFor("A shown") { texts(vm)==listOf("ALPHA-BODY") }
            val heldB=hold(b);val heldA=hold(a)
            vm.open(owned(b));waitFor("B's read entered") { heldB.reached.count==0L }
            vm.open(owned(a));waitFor("A's second read entered") { heldA.reached.count==0L }
            fake.injectRow(a,"assistant","ALPHA-NEWER")
            heldB.release.countDown()
            settle()
            assertEquals(a,vm.state.value.selected?.storedSessionId)
            assertFalse("a late B result must not appear in A: ${texts(vm)}",texts(vm).contains("BETA-BODY"))
            heldA.release.countDown()
            waitFor("A reconciles") { texts(vm).contains("ALPHA-NEWER") }
            assertFalse(texts(vm).contains("BETA-BODY"))
            assertEquals(texts(vm).size,vm.state.value.history.map { it.rowId }.toSet().size)
        } finally { releaseAll();store.clear() }
    }

    @Test fun O05_theSavedPageIsReadOffTheMainThreadAndShownBeforeTheHeldRemoteRead() {
        val id=titled("Cold","cold");fake.injectRow(id,"assistant","durable cold canonical")
        val first=ViewModelStore();val second=ViewModelStore()
        try {
            val before=model(first);before.open(owned(id))
            waitFor("disk snapshot written") { app.localStore.getString("conversation_bodies_v1")?.contains("durable cold canonical")==true }
            first.clear()
            coldRecentBodies()
            val readers=CopyOnWriteArrayList<Thread>()
            val spied=app.localStore
            io.mockk.mockkObject(spied)
            io.mockk.every { spied.getString("conversation_bodies_v1") } answers { readers+=Thread.currentThread();callOriginal() }
            val gate=hold(id)
            val next=model(second)
            val caller=Thread.currentThread()
            next.open(owned(id))
            waitFor("remote read held") { gate.reached.count==0L }
            assertEquals("the saved page is shown before the held remote read",listOf("durable cold canonical"),texts(next))
            assertTrue("the saved page was read from disk",readers.isNotEmpty())
            assertFalse("disk read and JSON decode must not run on the thread that opened the conversation: $readers",readers.any { it===caller })
        } finally { releaseAll();first.clear();second.clear() }
    }

    @Test fun O06_anUnrelatedHeldReceiveCommitCannotBlockTheOpenedConversationsNewestPage() {
        val id=titled("Open","open");fake.injectRow(id,"assistant","fresh newest canonical")
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val store=ViewModelStore()
        var holder:Thread?=null
        try {
            val spied=app.localStore
            io.mockk.mockkObject(spied)
            io.mockk.every { spied.commitString("received_conversations_v1",any()) } answers {
                entered.countDown();check(release.await(30,TimeUnit.SECONDS));callOriginal()
            }
            holder=Thread { app.received.checkpoint("an-unrelated-conversation",5) }.also { it.start() }
            assertTrue("the unrelated receive commit is really held",entered.await(20,TimeUnit.SECONDS))
            val vm=model(store);vm.open(owned(id))
            waitFor("the newest page is shown while that commit is still held") { texts(vm)==listOf("fresh newest canonical") }
            assertEquals("the commit was held throughout",1L,release.count)
        } finally { release.countDown();holder?.join(10_000);releaseAll();store.clear() }
    }

    @Test fun O07_aSendTappedBeforeTheOpenedConversationsPageLandsIsRefusedWithItsDraftKeptAndNothingGoesToTheOtherConversation() {
        val a=titled("Alpha","alpha");val b=titled("Beta","beta")
        fake.injectRow(a,"assistant","ALPHA-BODY");fake.injectRow(b,"user","same words");fake.injectRow(b,"assistant","BETA-BODY")
        val store=ViewModelStore()
        try {
            val vm=model(store);vm.open(owned(a))
            waitFor("A shown") { texts(vm)==listOf("ALPHA-BODY") }
            val gate=hold(b)
            vm.open(owned(b))
            waitFor("B's real read entered") { gate.reached.count==0L }
            vm.setDraft("same words");vm.send()
            assertTrue("the draft is kept for B",vm.state.value.draft=="same words")
            assertTrue("nothing is pending or in flight",vm.state.value.pendingSends.isEmpty() && vm.state.value.sendsInFlight==0)
            assertTrue("nothing reached Hermes: ${fake.rawPrompts}",fake.rawPrompts.isEmpty())
            gate.release.countDown()
            waitFor("B arrives") { texts(vm)==listOf("same words","BETA-BODY") }
            vm.send()
            waitFor("the prompt reached Hermes once, for B") { fake.rawPrompts.size==1 }
            assertEquals(listOf(b to "same words"),fake.rawPrompts.map { it.first to it.second })
            waitFor("its own row is shown") { vm.state.value.history.count { it.role=="user" && it.text=="same words" }==2 }
        } finally { releaseAll();store.clear() }
    }
}
