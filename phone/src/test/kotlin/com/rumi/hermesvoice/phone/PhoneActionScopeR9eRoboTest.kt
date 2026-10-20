package com.rumi.hermesvoice.phone

import androidx.lifecycle.viewModelScope
import com.rumi.hermesvoice.core.auth.HermesBearerSession
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real VM/HTTP/registry/cache. Only browser completion is synthetic; no native browser/device claim.
 * Latches hold responses, not the main looper. Finally always releases every held server worker. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w411dp-h1280dp")
class PhoneActionScopeR9eRoboTest : PhoneNavRoboBase() {
    private val key="session_list_titles_v1"
    private fun listJob(model: PhoneViewModel) = PhoneViewModel::class.java.getDeclaredField("listJob").also { it.isAccessible=true }.get(model) as? Job
    private fun startJob(model: PhoneViewModel, start: () -> Unit): Job {
        val before=model.viewModelScope.coroutineContext[Job]!!.children.toSet()
        start()
        return model.viewModelScope.coroutineContext[Job]!!.children.single { it !in before }
    }
    private fun signInB(model: PhoneViewModel, only: String): HermesBearerSession {
        fake.rows.keys.filter { it!=only }.forEach { fake.setCreatedSource(it,"discord") }
        fake.accessToken="synthetic-B-access";fake.refreshToken="synthetic-B-refresh"
        val auth=HermesBearerSession(fake.accessToken,fake.refreshToken,null,"basic","account-B")
        mockkStatic("com.rumi.hermesvoice.phone.SignInKt")
        try {
            coEvery { runNativeSignIn(any(),any()) } coAnswers { app.tokens.save(auth);"Signed in (synthetic browser completion)" }
            model.signIn()
            waitFor("B actual post-sign-in refresh publishes its title list") {
                model.state.value.signedIn && shown(model)==listOf(only) && listJob(model)?.isCompleted==true
            }
            waitFor("B title snapshot is durable") { app.localStore.getString(key)?.contains(only)==true && app.localStore.getString(key)?.contains(app.sessionListScope()!!)==true }
        } finally { unmockkStatic("com.rumi.hermesvoice.phone.SignInKt") }
        return auth
    }
    private fun staleAuth(success: Boolean, kind: String) {
        val a=titled("A title","r9e-a");val b=titled("B title","r9e-b")
        seed(a,2,"A");seed(b,2,"B")
        val model=showWith(a,b);openCard(model,a)
        waitFor("initial scan ends") { listJob(model)?.isCompleted==true }
        val original=fake.server.dispatcher
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val bEntered=CountDownLatch(1);val bRelease=CountDownLatch(1)
        val aAccess=fake.accessToken
        fake.server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // Creation's registry transaction must finish before the independent history read
                // fails. Do not trap its automatic list refresh or ws-ticket behind the same mutex.
                if(kind=="create" && request.requestUrl!!.encodedPath.endsWith("/messages") && request.getHeader("Authorization")=="Bearer $aAccess") {
                    return MockResponse().setResponseCode(401).setBody("{\"error\":\"session_expired\"}")
                }
                val response=original.dispatch(request)
                if(request.requestUrl!!.encodedPath=="/auth/native/refresh") {
                    entered.countDown();check(release.await(30,TimeUnit.SECONDS)) { "A release timed out" }
                }
                return response
            }
        }
        fake.rejectAccessTokens=if(kind=="create") emptySet() else setOf(fake.accessToken);fake.refreshAccepted=success
        try {
            val old=startJob(model) {
                when(kind) {
                    "open" -> model.open(model.state.value.selected!!)
                    "reload" -> model.openOrResume(model.state.value.selected!!)
                    "create" -> model.createConversation("A created","r9e-created","")
                    "archive" -> model.setArchived(model.state.value.selected!!,true)
                    else -> error("unknown action")
                }
            }
            waitFor("independent A action waits in actual REST401 to refresh") { entered.count==0L }
            check(!old.isCompleted && fake.refreshes==1)
            val auth=signInB(model,b)
            model.open(model.state.value.conversations.single().owned)
            waitFor("B selection/history is published") { model.state.value.selected?.storedSessionId==b && model.state.value.history.any { it.text.startsWith("B-") } }
            val history=model.state.value.history
            val saved=app.localStore.getString(key)
            fake.listPageHook={ _,_ -> bEntered.countDown();check(bRelease.await(30,TimeUnit.SECONDS)) { "B release timed out" } }
            model.refresh();waitFor("B's subsequent real listJob is in flight") { bEntered.count==0L }
            val currentList=listJob(model)!!
            release.countDown();waitFor("old A action naturally completes") { old.isCompleted }
            assertEquals("core already preserves B credentials",auth,app.tokens.load())
            assertTrue("old A action must not sign out B UI",model.state.value.signedIn)
            assertEquals("B visible title list survives",listOf(b),shown(model))
            assertEquals("B selection survives",b,model.state.value.selected?.storedSessionId)
            assertEquals("B history survives",history,model.state.value.history)
            assertEquals("B private title snapshot survives",saved,app.localStore.getString(key))
            assertFalse("old A action must not cancel B listJob",currentList.isCancelled)
            bRelease.countDown();waitFor("B listJob naturally completes") { currentList.isCompleted }
            assertEquals(listOf(b),shown(model))
            assertTrue(fake.prompts.isEmpty())
        } finally {
            release.countDown();bRelease.countDown();fake.listPageHook=null;fake.server.dispatcher=original
        }
    }
    @Test fun E01_oldOpenRefreshRejectionAfterBPublicationCannotInvalidateB() = staleAuth(false,"open")
    @Test fun E02_oldOpenRefreshSuccessAfterBPublicationCannotInvalidateB() = staleAuth(true,"open")
    @Test fun E03_oldReloadRefreshRejectionAfterBPublicationCannotInvalidateB() = staleAuth(false,"reload")
    @Test fun E04_oldCreatedHistoryAuthFailureAfterBPublicationCannotInvalidateB() = staleAuth(false,"create")
    @Test fun C07_currentArchiveAuthLossStillInvalidates() {
        val a=titled("Current","r9e-archive");val model=showWith(a);openCard(model,a)
        waitFor("list ends") { listJob(model)?.isCompleted==true }
        fake.rejectAccessTokens=setOf(fake.accessToken);fake.refreshAccepted=false
        val job=startJob(model) { model.setArchived(model.state.value.selected!!,true) }
        waitFor("current archive auth-loss naturally ends") { job.isCompleted }
        check(fake.refreshes==1 && app.tokens.load()==null)
        assertFalse(model.state.value.signedIn);assertTrue(shown(model).isEmpty())
        assertNull(model.state.value.selected);assertTrue(model.state.value.history.isEmpty())
        assertTrue(model.state.value.status.startsWith("Sign in"))
    }

    @Test fun C06_oldSuccessfulOpenCannotPublishAfterConnectionChange() {
        val a=titled("A title","r9e-success");seed(a,2,"A")
        val model=showWith(a);waitFor("list ends") { listJob(model)?.isCompleted==true }
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        fake.messagesHook={ id,offset,_ -> if(id==a && offset==0) { entered.countDown();check(release.await(30,TimeUnit.SECONDS)) };0 }
        try {
            val old=startJob(model) { model.open(model.state.value.conversations.single().owned) }
            waitFor("real successful history response held") { entered.count==0L }
            model.saveConnection(app.settings.dashboardUrl,"replacement-profile")
            val status=model.state.value.status
            release.countDown();waitFor("old successful open naturally completes") { old.isCompleted }
            assertNull("old success cannot repopulate selection after connection change",model.state.value.selected)
            assertTrue(model.state.value.history.isEmpty())
            assertNull(app.selectedConversationId)
            assertEquals(status,model.state.value.status)
        } finally { release.countDown();fake.messagesHook=null }
    }
    @Test fun E05_oldSuccessfulOpenCannotPublishAfterBActualSignInRefresh() {
        val a=titled("A title","r9e-late-success-a");val b=titled("B title","r9e-late-success-b")
        seed(a,2,"A");seed(b,2,"B")
        val model=showWith(a,b);waitFor("initial scan ends") { listJob(model)?.isCompleted==true }
        val owned=model.state.value.conversations.single { it.stored.id==a }.owned
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        fake.messagesHook={ id,_,_ -> if(id==a) { entered.countDown();check(release.await(30,TimeUnit.SECONDS)) };0 }
        try {
            val old=startJob(model) { model.open(owned) }
            waitFor("A successful history HTTP response is held") { entered.count==0L }
            val auth=signInB(model,b)
            check(model.state.value.selected==null && model.state.value.history.isEmpty())
            val saved=app.localStore.getString(key)
            release.countDown();waitFor("A successful open naturally completes") { old.isCompleted }
            assertEquals(auth,app.tokens.load());assertTrue(model.state.value.signedIn)
            assertEquals(listOf(b),shown(model))
            assertNull("A's late success cannot select an old-account conversation after B publication",model.state.value.selected)
            assertTrue(model.state.value.history.isEmpty());assertNull(app.selectedConversationId)
            assertEquals(saved,app.localStore.getString(key))
        } finally { release.countDown();fake.messagesHook=null }
    }
    @Test fun E06_oldAuthFailureAfterExplicitSignOutCannotReplaceSignOutStatus() {
        val a=titled("A title","r9e-signed-out");val model=showWith(a)
        waitFor("list ends") { listJob(model)?.isCompleted==true }
        val original=fake.server.dispatcher
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        fake.server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response=original.dispatch(request)
                if(request.requestUrl!!.encodedPath=="/auth/native/refresh") { entered.countDown();check(release.await(30,TimeUnit.SECONDS)) }
                return response
            }
        }
        fake.rejectAccessTokens=setOf(fake.accessToken);fake.refreshAccepted=false
        try {
            val old=startJob(model) { model.open(model.state.value.conversations.single().owned) }
            waitFor("A actual refresh response held") { entered.count==0L }
            model.signOut();val status=model.state.value.status
            check(status.startsWith("Signed out"))
            release.countDown();waitFor("old action naturally completes after sign-out") { old.isCompleted }
            assertEquals("explicit sign-out is not a current request auth-loss",status,model.state.value.status)
            assertNull(app.tokens.load());assertFalse(model.state.value.signedIn);assertTrue(shown(model).isEmpty())
        } finally { release.countDown();fake.server.dispatcher=original }
    }
    @Test fun C01_currentOpenTokenClearStillInvalidatesItsOwnUiAndTitles() {
        val a=titled("Current","r9e-current");seed(a,2)
        val model=showWith(a);openCard(model,a)
        waitFor("cache committed") { app.localStore.getString(key)?.contains(a)==true && listJob(model)?.isCompleted==true }
        fake.rejectAccessTokens=setOf(fake.accessToken);fake.refreshAccepted=false
        val job=startJob(model) { model.open(model.state.value.selected!!) }
        waitFor("current real auth-loss action ends") { job.isCompleted }
        check(fake.refreshes==1 && app.tokens.load()==null)
        assertFalse(model.state.value.signedIn);assertTrue(shown(model).isEmpty())
        assertNull(model.state.value.selected);assertTrue(model.state.value.history.isEmpty())
        assertTrue(model.state.value.status.startsWith("Sign in"))
        waitFor("current title invalidation is durable") { app.localStore.getString(key)=="{}" }
    }
    @Test fun C02_ordinarySameAccountTokenRefreshStillOpensHistory() {
        val a=titled("Current","r9e-refresh");seed(a,2,"current")
        val model=showWith(a);waitFor("list ends") { listJob(model)?.isCompleted==true }
        val scope=app.sessionListScope()
        fake.accessToken="synthetic-rotated-access"
        val job=startJob(model) { model.open(model.state.value.conversations.single().owned) }
        waitFor("same-account refresh completes") { job.isCompleted }
        check(fake.refreshes==1)
        assertEquals(scope,app.sessionListScope());assertTrue(model.state.value.signedIn)
        assertEquals(a,model.state.value.selected?.storedSessionId)
        assertTrue(model.state.value.history.any { it.text.startsWith("current-") })
    }
    @Test fun C03_currentOrdinaryHttpFailureKeepsAuthAndReportsFeedback() {
        val a=titled("Current","r9e-offline");val model=showWith(a)
        waitFor("list ends") { listJob(model)?.isCompleted==true }
        val original=fake.server.dispatcher
        fake.server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest)=if(request.requestUrl!!.encodedPath.endsWith("/messages")) MockResponse().setResponseCode(503).setBody("offline") else original.dispatch(request)
        }
        try {
            val job=startJob(model) { model.open(model.state.value.conversations.single().owned) }
            waitFor("ordinary failed action ends") { job.isCompleted }
            assertTrue(model.state.value.status.startsWith("Couldn't"));assertTrue(model.state.value.signedIn)
            assertNotNull(app.tokens.load());assertEquals(listOf(a),shown(model))
        } finally { fake.server.dispatcher=original }
    }
    @Test fun C04_currentOlderHistoryAuthLossStillInvalidates() {
        val a=titled("Current","r9e-older");seed(a,110)
        val model=showWith(a);openCard(model,a)
        waitFor("newest history published") { model.state.value.hasOlder && model.state.value.history.isNotEmpty() }
        fake.rejectAccessTokens=setOf(fake.accessToken);fake.refreshAccepted=false
        model.loadOlder()
        waitFor("older actual auth loss") { app.tokens.load()==null && !model.state.value.olderLoading }
        assertFalse(model.state.value.signedIn);assertNull(model.state.value.selected)
        assertTrue(model.state.value.history.isEmpty());assertTrue(model.state.value.status.startsWith("Sign in"))
    }
    @Test fun C05_trueActionCancellationIsSilentAndKeepsCurrentCache() {
        val a=titled("Current","r9e-cancel");val model=showWith(a)
        waitFor("list ends and cache committed") { listJob(model)?.isCompleted==true && app.localStore.getString(key)?.contains(a)==true }
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        fake.messagesHook={ _,_,_ -> entered.countDown();check(release.await(30,TimeUnit.SECONDS));0 }
        try {
            val job=startJob(model) { model.open(model.state.value.conversations.single().owned) }
            waitFor("history request held") { entered.count==0L }
            val before=model.state.value;val saved=app.localStore.getString(key)
            job.cancel();release.countDown();waitFor("real coroutine cancellation ends") { job.isCompleted }
            assertTrue(job.isCancelled);assertEquals(before,model.state.value)
            assertEquals(saved,app.localStore.getString(key));assertNotNull(app.tokens.load())
        } finally { release.countDown();fake.messagesHook=null }
    }
}
