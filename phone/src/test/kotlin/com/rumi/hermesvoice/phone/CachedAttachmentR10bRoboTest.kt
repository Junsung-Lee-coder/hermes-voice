package com.rumi.hermesvoice.phone

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.rumi.hermesvoice.core.net.HistoryPage
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w411dp-h1280dp")
class CachedAttachmentR10bRoboTest : PhoneNavRoboBase() {
    private fun cachedTap(refused:Boolean,logout:Boolean=false,removed:Boolean=false,replacement:Boolean=false,switchSelection:Boolean=false) {
        val id=titled("Cached file","cached-file")
        val path="/synthetic/cached-boundary.txt"
        fake.storedFiles[path]="text/plain" to "authorized file bytes".toByteArray()
        fake.injectRow(id,"assistant","@file:$path")
        val wiring=app.wiring();val owned=wiring.core.sessions.registry.find(id)!!
        val page=runBlocking { wiring.core.sessions.history(id) }
        runBlocking { app.recentBodies.remember(page,false) { true } }
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val vm=showWith(id)
        val active=java.util.concurrent.atomic.AtomicInteger()
        fake.sessionDetailHook={ requested ->
            if(requested==id) {
                active.incrementAndGet()
                try { entered.countDown();check(release.await(30,TimeUnit.SECONDS)) }
                finally { active.decrementAndGet() }
            }
        }
        try {
            openCard(vm,id)
            waitFor("authoritative source lookup held") { entered.count==0L }
            // Actual list tap enters ChatTab immediately; saved body precedes authority.
            waitFor("actual cached attachment button mounted") { exists("message_text_${page.messages.last().rowId}_file_0") }
            rule.onNodeWithTag("message_text_${page.messages.last().rowId}_file_0").performClick()
            settle()
            assertEquals("R10-F03: cached display cannot authorize even one file GET before current eligibility",0,fake.storedFileRequests.size)
            if(refused) fake.setCreatedSource(id,"discord")
            if(removed) fake.rows.remove(id)
            if(switchSelection) {
                val other=titled("Different approved session","different-file")
                vm.open(app.wiring().core.sessions.registry.find(other)!!)
                waitFor("different session becomes selected while original attachment is checking") { vm.state.value.selected?.storedSessionId==other }
            }
            if(logout || replacement) vm.signOut()
            if(replacement) app.tokens.save(com.rumi.hermesvoice.core.auth.HermesBearerSession(fake.accessToken,fake.refreshToken,null,"basic","replacement-user"))
            release.countDown()
            if(refused || logout || removed || replacement) {
                waitFor("refused attachment leaves loading") { vm.state.value.viewer?.phase!=ViewerPhase.LOADING }
                assertTrue("refused or replaced origin never issues file GET",fake.storedFileRequests.isEmpty())
            } else {
                waitFor("approved exact cached origin reaches viewer") { vm.state.value.viewer?.phase==ViewerPhase.READY }
                assertEquals(1,fake.storedFileRequests.size)
                assertEquals("authorized file bytes",String(vm.state.value.viewer!!.file!!.bytes))
            }
            assertTrue(fake.rawPrompts.isEmpty())
        } finally {
            release.countDown()
            fake.sessionDetailHook=null
            waitFor("all held synthetic metadata workers released naturally") { active.get()==0 }
            val viewer=PhoneViewModel::class.java.getDeclaredField("viewerJob").also { it.isAccessible=true }.get(vm) as? kotlinx.coroutines.Job
            waitFor("actual attachment caller naturally joined") { viewer?.isCompleted!=false }
        }
    }
    @Test fun F03_cachedUiAttachmentWaitsForAuthoritativeMarkerThenReadsOnce()=cachedTap(false)
    @Test fun F03_cachedUiAttachmentRefusedMarkerNeverReads()=cachedTap(true)
    @Test fun F03_cachedUiAttachmentLogoutWhileCheckingNeverReads()=cachedTap(false,true)
    @Test fun F03_cachedUiAttachmentMissingSessionNeverReads()=cachedTap(false,removed=true)
    @Test fun F03_cachedUiAttachmentAccountReplacementNeverReads()=cachedTap(false,replacement=true)
    @Test fun F03_laterApprovedSelectionCannotAuthorizeRevokedOriginalAttachment()=cachedTap(true,switchSelection=true)
}
