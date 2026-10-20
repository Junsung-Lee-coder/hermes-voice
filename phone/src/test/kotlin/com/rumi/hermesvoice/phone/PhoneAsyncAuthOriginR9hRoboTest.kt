package com.rumi.hermesvoice.phone

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rumi.hermesvoice.core.auth.HermesBearerSession
import com.rumi.hermesvoice.core.attachments.AttachmentRef
import com.rumi.hermesvoice.core.attachments.RefKind
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

/** Actual public Phone send/preview -> real ChatService/repository/client and HTTP refresh.
 * Browser completion, Android keystore and Wear transport are synthetic, as in PhoneRoboBase.
 * No private completion injection. Every held HTTP worker and caller is released/joined in finally. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w411dp-h1280dp")
class PhoneAsyncAuthOriginR9hRoboTest : PhoneNavRoboBase() {
    private val key="session_list_titles_v1"
    private val ref=AttachmentRef(RefKind.FILE,"/synthetic/r9h.txt","r9h.txt")
    private fun listJob(m: PhoneViewModel)=PhoneViewModel::class.java.getDeclaredField("listJob").also { it.isAccessible=true }.get(m) as? Job
    private fun viewerJob(m: PhoneViewModel)=PhoneViewModel::class.java.getDeclaredField("viewerJob").also { it.isAccessible=true }.get(m) as? Job
    private fun sendJob(m: PhoneViewModel): Job {
        val before=app.appScope.coroutineContext[Job]!!.children.toSet()
        m.setDraft("synthetic r9h text");m.send()
        return app.appScope.coroutineContext[Job]!!.children.single { it !in before }
    }
    private fun signInB(m: PhoneViewModel, b: String): HermesBearerSession {
        m.signOut()
        fake.rows.keys.filter { it!=b }.forEach { fake.setCreatedSource(it,"discord") }
        fake.accessToken="synthetic-B-access";fake.refreshToken="synthetic-B-refresh"
        val auth=HermesBearerSession(fake.accessToken,fake.refreshToken,null,"basic","account-B")
        mockkStatic("com.rumi.hermesvoice.phone.SignInKt")
        try {
            coEvery { runNativeSignIn(any(),any()) } coAnswers { app.tokens.save(auth);"Signed in (synthetic browser completion)" }
            m.signIn()
            waitFor("B public sign-in actually publishes real list") { m.state.value.signedIn && shown(m)==listOf(b) && listJob(m)?.isCompleted==true }
            waitFor("B durable title snapshot") { app.localStore.getString(key)?.contains(b)==true && app.localStore.getString(key)?.contains(app.sessionListScope()!!)==true }
        } finally { unmockkStatic("com.rumi.hermesvoice.phone.SignInKt") }
        return auth
    }
    private fun stale(status: Int, preview: Boolean, replace: Boolean=false) {
        val a=titled("A title","r9h-a");val b=titled("B title","r9h-b")
        seed(a,2,"A");seed(b,2,"B")
        var m=showWith(a,b);openCard(m,a)
        waitFor("A initial list closes") { listJob(m)?.isCompleted==true }
        fake.storedFiles[ref.path]="text/plain" to "A file".toByteArray()
        val original=fake.server.dispatcher
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val bEntered=CountDownLatch(1);val bRelease=CountDownLatch(1)
        fake.refreshAccepted=status==200
        fake.rejectAccessTokens=setOf(fake.accessToken)
        fake.server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response=original.dispatch(request)
                if(request.requestUrl!!.encodedPath=="/auth/native/refresh") {
                    entered.countDown();check(release.await(30,TimeUnit.SECONDS)) { "A release timeout" }
                    return if(status==400) MockResponse().setResponseCode(400).setBody("{\"error\":\"session_expired\"}") else response
                }
                return response
            }
        }
        var old: Job?=null;var currentList: Job?=null
        try {
            old=if(preview) { openStoredAttachmentFixture(fake,m,a,ref);viewerJob(m)!! } else sendJob(m)
            waitFor("A real REST rejection/refresh is held before response") { entered.count==0L }
            check(!old.isCompleted && fake.refreshes==1)
            if(replace) {
                check(!preview)
                controller!!.get().viewModelStore.clear()
                m=ViewModelProvider(controller!!.get())[PhoneViewModel::class.java]
                check(m.state.value.pendingSends.isNotEmpty())
            }
            val auth=signInB(m,b)
            m.open(m.state.value.conversations.single().owned)
            waitFor("B real selection and history published") { m.state.value.selected?.storedSessionId==b && m.state.value.history.any { it.text.startsWith("B-") } }
            val history=m.state.value.history;val saved=app.localStore.getString(key)
            fake.listPageHook={ _,_ -> bEntered.countDown();check(bRelease.await(30,TimeUnit.SECONDS)) { "B release timeout" } }
            m.refresh();waitFor("B current independent list refresh held") { bEntered.count==0L }
            currentList=listJob(m)!!
            val viewer=m.state.value.viewer
            release.countDown();waitFor("A old caller naturally completes") { old.isCompleted };settle()
            assertEquals("real token compare-commit preserves B",auth,app.tokens.load())
            assertTrue("old A auth result must not invalidate B signed-in UI",m.state.value.signedIn)
            assertEquals(listOf(b),shown(m));assertEquals(b,m.state.value.selected?.storedSessionId)
            assertEquals(history,m.state.value.history);assertEquals(saved,app.localStore.getString(key))
            assertFalse("A cannot cancel B refresh",currentList.isCancelled)
            if(preview) assertEquals("A cannot publish its viewer into B",viewer,m.state.value.viewer)
            assertTrue("A must not submit a prompt",fake.prompts.isEmpty())
            bRelease.countDown();waitFor("B refresh naturally completes") { currentList.isCompleted }
        } finally {
            release.countDown();bRelease.countDown();fake.listPageHook=null
            old?.let { waitFor("finally joins old caller") { it.isCompleted } }
            currentList?.let { waitFor("finally joins B refresh") { it.isCompleted } }
            fake.server.dispatcher=original
        }
    }
    @Test fun H01_oldSendRefresh401CannotInvalidatePublishedB()=stale(401,false)
    @Test fun H02_oldSendRefresh200ToReplacementListenerCannotInvalidateB()=stale(200,false,true)
    @Test fun H03_oldSendRefresh400CannotInvalidatePublishedB()=stale(400,false)
    @Test fun H04_oldPreviewRefresh401CannotInvalidatePublishedB()=stale(401,true)
    @Test fun H05_oldPreviewRefresh200CannotInvalidatePublishedB()=stale(200,true)
    @Test fun H06_oldPreviewRefresh400CannotInvalidatePublishedB()=stale(400,true)

    private fun current(preview: Boolean, loss: Boolean) {
        val a=titled("Current title","r9h-current");seed(a,2,"current")
        val m=showWith(a);openCard(m,a)
        waitFor("initial list closes") { listJob(m)?.isCompleted==true }
        fake.storedFiles[ref.path]="text/plain" to "current file".toByteArray()
        val scope=app.sessionListScope()
        fake.refreshAccepted=!loss;fake.rejectAccessTokens=setOf(fake.accessToken)
        if(!preview && !loss) fake.scripts[a]={ listOf(com.rumi.hermesvoice.core.FakeHermesDashboard.complete("current exact answer")) }
        val job=if(preview) { openStoredAttachmentFixture(fake,m,a,ref);viewerJob(m)!! } else sendJob(m)
        waitFor("current caller completes") { job.isCompleted };settle()
        assertEquals(1,fake.refreshes)
        if(loss) {
            assertNull(app.tokens.load());assertFalse("genuine current auth loss still invalidates",m.state.value.signedIn)
            if(preview) assertEquals(ViewerPhase.FAILED,m.state.value.viewer?.phase)
            else assertTrue(m.state.value.failedSends.isNotEmpty())
        } else {
            assertNotNull(app.tokens.load());assertTrue(m.state.value.signedIn);assertEquals(scope,app.sessionListScope())
            if(preview) { assertEquals(ViewerPhase.READY,m.state.value.viewer?.phase);assertEquals("current file",m.state.value.viewer?.file?.text) }
            else { assertEquals(1,fake.prompts.size);assertTrue(m.state.value.failedSends.isEmpty()) }
        }
    }
    @Test fun C01_currentSendRealRefreshTokenClearStillInvalidates()=current(false,true)
    @Test fun C02_currentPreviewRealRefreshTokenClearStillInvalidates()=current(true,true)
    @Test fun C03_currentSendOrdinaryTokenRotationStillSucceeds()=current(false,false)
    @Test fun C04_currentPreviewOrdinaryTokenRotationStillSucceeds()=current(true,false)
    @Test fun C05_previewOfflineFailureKeepsAuth() {
        val a=titled("Current","r9h-offline");val m=showWith(a)
        waitFor("list closes") { listJob(m)?.isCompleted==true }
        fake.storedFileStatus=503
        openStoredAttachmentFixture(fake,m,a,ref);val job=viewerJob(m)!!
        waitFor("offline preview completes") { job.isCompleted }
        assertEquals(ViewerPhase.FAILED,m.state.value.viewer?.phase);assertTrue(m.state.value.signedIn);assertNotNull(app.tokens.load())
    }
    @Test fun C06_closedPreviewCannotPublishLateAuthFailure() {
        val a=titled("Current","r9h-closed");val m=showWith(a)
        waitFor("list closes") { listJob(m)?.isCompleted==true }
        val original=fake.server.dispatcher;val entered=CountDownLatch(1);val release=CountDownLatch(1)
        fake.rejectAccessTokens=setOf(fake.accessToken);fake.refreshAccepted=false
        fake.server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response=original.dispatch(request)
                if(request.requestUrl!!.encodedPath=="/auth/native/refresh") { entered.countDown();check(release.await(30,TimeUnit.SECONDS)) }
                return response
            }
        }
        var job: Job?=null
        try {
            openStoredAttachmentFixture(fake,m,a,ref);job=viewerJob(m)!!
            waitFor("preview refresh held") { entered.count==0L }
            m.closeViewer();release.countDown();waitFor("cancelled preview joins") { job.isCompleted }
            assertTrue(job.isCancelled);assertNull(m.state.value.viewer);assertTrue(m.state.value.signedIn)
        } finally { release.countDown();job?.let { waitFor("finally joins preview") { it.isCompleted } };fake.server.dispatcher=original }
    }
}
