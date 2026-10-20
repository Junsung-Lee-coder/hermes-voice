package com.rumi.hermesvoice.phone

import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.rumi.hermesvoice.core.FakeHermesDashboard
import com.rumi.hermesvoice.core.auth.HermesBearerSession
import com.rumi.hermesvoice.core.chat.PendingSend
import io.mockk.coEvery
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Job
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual public send/auth/recreated ViewModel/Retry and app-owned custody; real HTTP and
 * WebSocket against the existing contract double. Only browser, keystore and Wear are synthetic. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w411dp-h1280dp")
class PhoneSendCustodyR9iRoboTest : PhoneNavRoboBase() {
    private fun listJob(m: PhoneViewModel)=PhoneViewModel::class.java.getDeclaredField("listJob").also { it.isAccessible=true }.get(m) as? Job
    private fun newModel(store: ViewModelStore)=ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[PhoneViewModel::class.java]
    private fun attach(m: PhoneViewModel) {
        val file=File(app.cacheDir,"r9i-origin.txt").also { it.writeText("synthetic account-A attachment") }
        m.addAttachment(Uri.fromFile(file))
        waitFor("public attachment read") { m.state.value.attachments.size==1 }
    }
    private fun login(m: PhoneViewModel, user: String): HermesBearerSession {
        m.signOut()
        fake.accessToken="synthetic-$user-access";fake.refreshToken="synthetic-$user-refresh"
        val auth=HermesBearerSession(fake.accessToken,fake.refreshToken,null,"basic",user)
        mockkStatic("com.rumi.hermesvoice.phone.SignInKt")
        try {
            coEvery { runNativeSignIn(any(),any()) } coAnswers { app.tokens.save(auth);"Signed in (synthetic browser completion)" }
            m.signIn()
            waitFor("public login and real complete list") { m.state.value.signedIn && m.state.value.conversations.isNotEmpty() && listJob(m)?.isCompleted==true }
        } finally { unmockkStatic("com.rumi.hermesvoice.phone.SignInKt") }
        return auth
    }
    /** TEST-OWNED completion observer of one exact request; it is NOT the send coroutine's Job. TextSends does not expose
     * that Job and the shared appScope can legitimately gain other children, so completed means only that the
     * application's send custody no longer holds this request id (active[id] is set before dispatch, removed in finished()). */
    private inner class ExactSendObservation(val id: Long) {
        val isCompleted: Boolean get() = activeSend(id) == null
    }
    private fun begin(m: PhoneViewModel): Pair<ExactSendObservation,PendingSend> {
        val idsBefore=app.textSends.ids.get()
        val knownIds=m.state.value.pendingSends.map { it.send.id }.toSet()
        m.setDraft("synthetic account-A immutable text");m.send()
        val pending=m.state.value.pendingSends.single { it.send.id !in knownIds }
        assertTrue("the admitted request is this send's own newly issued id",pending.send.id>idsBefore && pending.send.id==app.textSends.ids.get())
        return ExactSendObservation(pending.send.id) to pending
    }
    private fun crossAccount(active: Boolean) {
        val id=titled("Shared legitimate marker-valid session","r9i-shared")
        seed(id,2,"shared")
        val old=showWith(id);openCard(old,id);waitFor("initial list closes") { listJob(old)?.isCompleted==true }
        attach(old)
        val original=fake.server.dispatcher
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        fake.rejectAccessTokens=setOf(fake.accessToken);fake.refreshAccepted=false
        fake.server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response=original.dispatch(request)
                if(request.requestUrl!!.encodedPath=="/auth/native/refresh") {
                    entered.countDown();check(release.await(30,TimeUnit.SECONDS)) { "A refresh release timeout" }
                }
                return response
            }
        }
        val store=ViewModelStore();var job: ExactSendObservation?=null
        try {
            val started=begin(old);job=started.first;val pending=started.second
            waitFor("actual A refresh held") { entered.count==0L };check(!job.isCompleted)
            val auth=login(old,"account-B")
            assertEquals("same session really eligible for B",listOf(id),shown(old))
            if(!active) { release.countDown();waitFor("old A naturally finishes NOT_SENT") { job.isCompleted };settle() }
            // Recreate AFTER B login and, for the failed witness, AFTER A's outcome.
            val current=newModel(store)
            val restoredActive=current.state.value.pendingSends.any { it.send.id==pending.send.id }
            val restoredFailed=current.state.value.failedSends.any { it.send.id==pending.send.id }
            current.refresh();waitFor("B recreated real list publishes") { shown(current)==listOf(id) && listJob(current)?.isCompleted==true }
            openOwned(current,id)
            if(active) { release.countDown();waitFor("A completes after active snapshot") { job.isCompleted };settle() }
            fake.scripts[id]={ listOf(FakeHermesDashboard.complete("B must not receive A's request")) }
            val before=app.appScope.coroutineContext[Job]!!.children.toSet()
            // Exercise the real manual Retry caller before asserting absence of a snapshot leak.
            current.retryFailedSend(pending.send.id)
            val retries=app.appScope.coroutineContext[Job]!!.children.filter { it !in before }.toList()
            retries.forEach { retry -> waitFor("actual Retry transport naturally finishes") { retry.isCompleted } };settle()
            val transported=fake.rawPrompts.toList()
            // Also challenge the application-owned exact-send claim with the original immutable
            // object: hiding a ViewModel row alone must not supply the protection.
            val claimed=app.textSends.retry(app.wiring().core.chat,pending,app.asyncActionOrigin(app.wiring()))
            if(claimed) waitFor("stale exact-send owner claim finishes") { app.appScope.coroutineContext[Job]!!.children.none { it !in before && !it.isCompleted } }
            assertEquals(auth,app.tokens.load());assertTrue(current.state.value.signedIn)
            assertTrue("R09H-F01 manual B Retry must not issue A transport",transported.isEmpty() && fake.rawPrompts.isEmpty())
            assertFalse("R09H-F01 app-owned retry must reject another account's exact-send object",claimed)
            assertFalse("R09H-F01 active A payload must not restore in recreated B",restoredActive)
            assertFalse("R09H-F01 failed A payload must not restore in recreated B",restoredFailed)
            assertTrue(current.state.value.pendingSends.none { it.send.id==pending.send.id })
            assertTrue(current.state.value.failedSends.none { it.send.id==pending.send.id })
        } finally {
            release.countDown();job?.let { waitFor("finally joins A") { it.isCompleted } }
            store.clear();fake.server.dispatcher=original
        }
    }
    @Test fun A01_activeSnapshotCannotCrossRealBLoginAndRecreation()=crossAccount(true)
    @Test fun A02_delayedFailedSnapshotAndManualRetryCannotRebindAToB()=crossAccount(false)

    @Test fun A03_staleScreenManualRetryCannotCrossExplicitLogoutEvenForSameUser() {
        val id=titled("Same user new authentication","r9i-new-auth")
        val old=showWith(id);openCard(old,id);fake.turnBehavior={ "not_owned" }
        val started=begin(old);waitFor("definitive refusal") { started.first.isCompleted && old.state.value.failedSends.isNotEmpty() }
        val before=fake.rawPrompts.size
        login(old,"jun");openOwned(old,id)
        fake.turnBehavior={ "start_first" };fake.scripts[id]={ listOf(FakeHermesDashboard.complete("must not replay")) }
        old.retryFailedSend(started.second.send.id)
        waitFor("stale retry joins") { old.state.value.sendsInFlight==0 };settle()
        assertEquals("R09H-F01 explicit sign-out starts a new custody generation",before,fake.rawPrompts.size)
    }
    @Test fun C01_sameAccountRotationRecreationAndDefinitiveUnsentRetryClaimExactlyOnce() {
        val id=titled("Same account","r9i-rotation");val old=showWith(id);openCard(old,id);attach(old)
        fake.turnBehavior={ "not_owned" }
        val started=begin(old);waitFor("NOT_SENT failed request") { started.first.isCompleted && old.state.value.failedSends.size==1 }
        val kept=old.state.value.failedSends.single().send
        val store=ViewModelStore()
        try {
            val current=newModel(store);assertSame(kept,current.state.value.failedSends.single().send)
            openOwned(current,id)
            fake.turnBehavior={ "start_first" };fake.scripts[id]={ listOf(FakeHermesDashboard.complete("own exact answer")) }
            fake.refreshAccepted=true;fake.rejectAccessTokens=setOf(fake.accessToken)
            val before=fake.rawPrompts.size
            current.retryFailedSend(kept.id);current.retryFailedSend(kept.id)
            waitFor("same account retry completes") { current.state.value.sendsInFlight==0 && fake.rawPrompts.size==before+1 }
            assertEquals(1,fake.refreshes);assertTrue(current.state.value.signedIn);assertNotNull(app.tokens.load())
            assertTrue(current.state.value.failedSends.isEmpty())
            val expectedRef="@file:attachments/"+com.rumi.hermesvoice.core.net.AttachmentPolicy.safeName(kept.attachments.single().name)
            assertEquals(expectedRef+"\n"+kept.text,fake.rawPrompts.last().second)
            assertArrayEquals(kept.attachments.single().bytes,fake.stagedFiles.last().second)
            assertFalse(app.textSends.retry(app.wiring().core.chat,started.second,app.asyncActionOrigin(app.wiring())))
            assertSame(kept.attachments,started.second.send.attachments)
        } finally { store.clear() }
    }
    /** R09j test-only locked observation of app custody; never attaches/replaces the screen. */
    private fun activeSend(id: Long): PendingSend? {
        val owner=app.textSends
        val lock=TextSends::class.java.getDeclaredField("lock").also { it.isAccessible=true }.get(owner) as Any
        return synchronized(lock) {
            @Suppress("UNCHECKED_CAST")
            val active=TextSends::class.java.getDeclaredField("active").also { it.isAccessible=true }.get(owner) as Map<Long,PendingSend>
            active[id]
        }
    }
    @Test fun C02_sameAccountActiveRecreationStillObservesOwnCompletion() {
        val id=titled("Own active","r9i-own-active");val old=showWith(id);openCard(old,id)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val heldReaders=java.util.concurrent.atomic.AtomicInteger()
        val store=ViewModelStore();var job: ExactSendObservation?=null;var completionSent=false
        // This is the real read used by onSendAccepted/reloadSelected, BEFORE its response
        // snapshots the canonical user row. A later unrelated submit latch would not hold it.
        fake.messagesHook={ stored,_,_ ->
            if(stored==id && fake.rawPrompts.size==1) {
                heldReaders.incrementAndGet();entered.countDown()
                try { check(release.await(20,TimeUnit.SECONDS)) { "accepted history release timeout" } }
                finally { heldReaders.decrementAndGet() }
            }
            0
        }
        try {
            val started=begin(old);job=started.first
            waitFor("real accepted request") { fake.rawPrompts.size==1 && old.state.value.pendingSends.any { it.delivered } }
            waitFor("real accepted history response held") { entered.count==0L }
            val owned=activeSend(started.second.send.id)!!
            assertTrue("application owns accepted request before recreation",owned.delivered)
            assertSame(started.second.send,owned.send);assertFalse(started.first.isCompleted)
            val current=newModel(store);assertSame(started.second.send,current.state.value.pendingSends.single().send)
            release.countDown()
            fake.pushLaterTurn(id,FakeHermesDashboard.complete("own completion"),withStart=false)
            completionSent=true
            waitFor("own completion survives recreation") { started.first.isCompleted && current.state.value.sendsInFlight==0 }
            assertTrue(current.state.value.failedSends.isEmpty());assertEquals(1,fake.rawPrompts.size)
            assertEquals("own reply, not a possibly-sent failure", "",current.state.value.status)
        } finally {
            release.countDown();fake.messagesHook=null
            if(job?.isCompleted==false && !completionSent) fake.pushLaterTurn(id,FakeHermesDashboard.complete("own completion"),withStart=false)
            waitFor("finally releases all accepted history readers") { heldReaders.get()==0 }
            job?.let { waitFor("finally joins app-owned send") { it.isCompleted } }
            store.clear()
        }
    }

    /** R11d: the real public send() rebuilds the application wiring (PhoneApp.wiring launches its own long-lived
     * appScope collectors) inside the observed send window; the settings and wiring() calls here are public. */
    private fun wiringRebuildInsideNextSend() {
        val profile=app.settings.profile
        app.settings.profile="r11d-alt-profile";app.wiring();app.settings.profile=profile
    }
    private fun appChildren()=app.appScope.coroutineContext[Job]!!.children.toSet()
    @Test fun D00_wiringRebuildInsideSendCreatesMoreThanOneAppScopeChildWithOneExactRequest() {
        val id=titled("Extra child census","r11d-census");val old=showWith(id);openCard(old,id)
        fake.scripts[id]={ listOf(FakeHermesDashboard.complete("census exact answer")) }
        val idsBefore=app.textSends.ids.get();val promptsBefore=fake.rawPrompts.size;val children=appChildren()
        wiringRebuildInsideNextSend()
        old.setDraft("synthetic account-A immutable text");old.send()
        val fresh=appChildren().filter { it !in children }
        val pending=old.state.value.pendingSends.single()
        println("R11D-OBS census new_children=${fresh.size} active=${fresh.count { it.isActive }} request_id=${pending.send.id} ids_before=$idsBefore ids_after=${app.textSends.ids.get()}")
        assertTrue("fixture: a legitimate wiring rebuild leaves more than one new appScope child in the send window",fresh.count { it.isActive }>1)
        assertTrue(pending.send.id>idsBefore && pending.send.id==app.textSends.ids.get())
        waitFor("exact request completes") { old.state.value.sendsInFlight==0 && fake.rawPrompts.size==promptsBefore+1 };settle()
        println("R11D-OBS transport prompts_added=${fake.rawPrompts.size-promptsBefore} custody_active=${activeSend(pending.send.id)!=null}")
        assertEquals(promptsBefore+1,fake.rawPrompts.size);assertEquals(pending.send.text,fake.rawPrompts.last().second)
        assertNull(activeSend(pending.send.id));assertTrue(old.state.value.failedSends.isEmpty())
    }
    @Test fun D01_extraAppScopeChildrenDuringHeldAcceptedSendDoNotChangeTheExactRequestObservation() {
        val id=titled("Extra child held","r11d-held");val old=showWith(id);openCard(old,id)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val heldReaders=java.util.concurrent.atomic.AtomicInteger()
        var settled: (() -> Boolean)?=null;var completionSent=false
        fake.messagesHook={ stored,_,_ ->
            if(stored==id && fake.rawPrompts.size==1) {
                heldReaders.incrementAndGet();entered.countDown()
                try { check(release.await(20,TimeUnit.SECONDS)) { "accepted history release timeout" } }
                finally { heldReaders.decrementAndGet() }
            }
            0
        }
        try {
            val children=appChildren()
            wiringRebuildInsideNextSend()
            val started=begin(old);settled={ started.first.isCompleted }
            val fresh=appChildren().filter { it !in children && it.isActive }
            println("R11D-OBS held new_active_children=${fresh.size} request_id=${started.second.send.id}")
            assertTrue("fixture: more than one new appScope child while the exact send is observed",fresh.size>1)
            waitFor("real accepted request") { fake.rawPrompts.size==1 && old.state.value.pendingSends.any { it.delivered } }
            waitFor("real accepted history response held") { entered.count==0L }
            val owned=activeSend(started.second.send.id)!!
            assertTrue("application owns the accepted request",owned.delivered)
            assertSame(started.second.send,owned.send);assertFalse(started.first.isCompleted)
            assertEquals(started.second.send.text,fake.rawPrompts.single().second)
            release.countDown()
            fake.pushLaterTurn(id,FakeHermesDashboard.complete("own completion"),withStart=false)
            completionSent=true
            waitFor("own completion of the exact request") { started.first.isCompleted && old.state.value.sendsInFlight==0 }
            println("R11D-OBS held completed custody_active=${activeSend(started.second.send.id)!=null} prompts=${fake.rawPrompts.size}")
            assertNull(activeSend(started.second.send.id))
            assertTrue(old.state.value.failedSends.isEmpty());assertEquals(1,fake.rawPrompts.size)
            assertEquals("own reply, not a possibly-sent failure","",old.state.value.status)
        } finally {
            release.countDown();fake.messagesHook=null
            if(settled?.invoke()==false && !completionSent) fake.pushLaterTurn(id,FakeHermesDashboard.complete("own completion"),withStart=false)
            waitFor("finally releases all accepted history readers") { heldReaders.get()==0 }
            settled?.let { observe -> waitFor("finally joins the exact send") { observe() } }
        }
    }
    @Test fun D02_extraAppScopeChildrenDuringDefinitiveRefusalKeepOneUnsentRequestAndExactlyOneRetry() {
        val id=titled("Extra child refusal","r11d-refusal");val old=showWith(id);openCard(old,id)
        fake.turnBehavior={ "not_owned" }
        val promptsBefore=fake.rawPrompts.size;val children=appChildren()
        wiringRebuildInsideNextSend()
        val started=begin(old)
        val fresh=appChildren().filter { it !in children && it.isActive }
        println("R11D-OBS refusal new_active_children=${fresh.size} request_id=${started.second.send.id}")
        assertTrue("fixture: more than one new appScope child while the exact send is observed",fresh.size>1)
        waitFor("definitive refusal of the exact request") { started.first.isCompleted && old.state.value.failedSends.size==1 }
        val kept=old.state.value.failedSends.single().send
        assertSame(started.second.send,kept);assertEquals(started.second.send.id,app.textSends.ids.get())
        assertNull(activeSend(kept.id));assertEquals("definitive refusal issues no transport",promptsBefore,fake.rawPrompts.size)
        fake.turnBehavior={ "start_first" };fake.scripts[id]={ listOf(FakeHermesDashboard.complete("own exact answer")) }
        old.retryFailedSend(kept.id);old.retryFailedSend(kept.id)
        waitFor("positive-no-effect Retry completes once") { old.state.value.sendsInFlight==0 && fake.rawPrompts.size==promptsBefore+1 };settle()
        println("R11D-OBS refusal retry prompts_added=${fake.rawPrompts.size-promptsBefore} custody_active=${activeSend(kept.id)!=null}")
        assertEquals(promptsBefore+1,fake.rawPrompts.size);assertEquals(kept.text,fake.rawPrompts.last().second)
        assertTrue(old.state.value.failedSends.isEmpty());assertNull(activeSend(kept.id))
        assertFalse(app.textSends.retry(app.wiring().core.chat,started.second,app.asyncActionOrigin(app.wiring())))
    }
}
