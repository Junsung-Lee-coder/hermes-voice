package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.HermesBearerSession
import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.auth.InMemoryHermesTokenStore
import com.rumi.hermesvoice.core.diag.DiagCode
import com.rumi.hermesvoice.core.diag.DiagLog
import com.rumi.hermesvoice.core.diag.DiagReport
import com.rumi.hermesvoice.core.diag.WatchDiag
import com.rumi.hermesvoice.core.diag.WatchDiagStatus
import com.rumi.hermesvoice.core.net.OutgoingAttachment
import com.rumi.hermesvoice.core.sessions.OwnedSessionRegistry
import com.rumi.hermesvoice.core.settings.AppSettings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Written against APIs that exist both before and after the diagnostics change, so the same bytes compile and run on either tree:
 * the production [HermesVoiceCore.connect] wiring against the contract double, then the exported report.
 */
class BehavioralRegressionHarnessTest {
    private val fake = FakeHermesDashboard()
    private val store = InMemoryKeyValueStore()
    private val http: OkHttpClient = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
    private val log = DiagLog(clock = { System.nanoTime() / 1_000_000 })
    private val wiring = HermesVoiceCore.connect(
        HermesDashboardEndpoint.parse(fake.baseUrl), http,
        InMemoryHermesTokenStore(HermesBearerSession(fake.accessToken, fake.refreshToken, null, "basic", "jun")),
        OwnedSessionRegistry(store), AppSettings(store).apply { dashboardUrl = fake.baseUrl }, diag = log)
    private val core = wiring.first

    @After fun tearDown() {
        wiring.second.close()
        fake.close()
    }

    @Test
    fun `a typed send that fails while its attachment is staged reaches the exported report without leaking`() = runBlocking {
        val id = runBlocking { core.sessions.createConversation("Work", "work", "") }.storedSessionId
        fake.failFileAttach = true
        val result = core.chat.send(id, "private words SECRET-TEXT",
            listOf(OutgoingAttachment("Q3 secret-name.pdf", "application/pdf", "%PDF-1.7".toByteArray())))
        assertTrue("the send failed: $result", result is ChatSendResult.Failed)
        val failed = log.snapshot().filter { it.code == DiagCode.REQUEST_FAILED }
        assertEquals("the failed send is recorded exactly once", 1, failed.size)
        assertNotNull(failed.single().fail)
        val json = DiagReport.build(19, log.snapshot(), log.dropped(), log.now(), "0123456789abcdef", WatchDiag(WatchDiagStatus.OFFLINE), emptyMap())
        assertTrue("the export names the failed request", json.contains("request_failed"))
        assertFalse(json.contains("SECRET-TEXT"))
        assertFalse(json.contains("secret-name"))
        assertFalse(json.contains("disk full"))
        assertFalse(json.contains(id))
    }
}
