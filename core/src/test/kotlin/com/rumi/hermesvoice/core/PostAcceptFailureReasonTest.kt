package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.HermesBearerSession
import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.auth.InMemoryHermesTokenStore
import com.rumi.hermesvoice.core.diag.DiagApp
import com.rumi.hermesvoice.core.diag.DiagBuild
import com.rumi.hermesvoice.core.diag.DiagBuildStatus
import com.rumi.hermesvoice.core.diag.DiagClassify
import com.rumi.hermesvoice.core.diag.DiagCode
import com.rumi.hermesvoice.core.diag.DiagFail
import com.rumi.hermesvoice.core.diag.DiagLog
import com.rumi.hermesvoice.core.diag.DiagNetwork
import com.rumi.hermesvoice.core.diag.DiagPersistStatus
import com.rumi.hermesvoice.core.diag.DiagReportV2
import com.rumi.hermesvoice.core.diag.DiagStage
import com.rumi.hermesvoice.core.diag.WatchDiag
import com.rumi.hermesvoice.core.diag.WatchDiagStatus
import com.rumi.hermesvoice.core.diag.WireCoverage
import com.rumi.hermesvoice.core.sessions.OwnedSessionRegistry
import com.rumi.hermesvoice.core.settings.AppSettings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A text send that the dashboard accepted and the app then refuses to attribute fails a few milliseconds later at the wait stage.
 * Its diagnostics must say which fixed reason applied (a queued acknowledgement, or a reply position the app cannot prove)
 * instead of "unknown", without reading any exception text, and without sending anything again. Real OkHttp HTTP and WebSocket
 * against the dashboard contract double; synthetic data only.
 */
class PostAcceptFailureReasonTest {
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

    private fun conversation() = runBlocking { core.sessions.createConversation("Work", "work", "") }.storedSessionId

    private fun report(): String = DiagReportV2.build(1_800_000_000_000L, DiagApp("0.1.20-dev", 20, DiagBuild(DiagBuildStatus.UNAVAILABLE)), log.snapshot(), log.now(),
        "0123456789abcdef", WireCoverage.of(log.coverage(), DiagPersistStatus.NOT_ENABLED), DiagNetwork.UNKNOWN, WatchDiag(WatchDiagStatus.OFFLINE), emptyMap())

    @Test
    fun `a queued acknowledgement is recorded as accepted then failed with the queued-unattributable reason and is not sent again`() = runBlocking {
        val id = conversation()
        fake.scripts[id] = { emptyList() }
        fake.submitStatus = { "queued" }
        val result = core.chat.send(id, "first synthetic request")
        assertTrue("the queued acknowledgement ends the send as a failure: $result", result is ChatSendResult.Failed)
        assertTrue((result as ChatSendResult.Failed).acceptanceObserved)
        val events = log.snapshot().filter { it.code == DiagCode.REQUEST_ACCEPTED || it.code == DiagCode.REQUEST_FAILED }
        assertEquals(listOf(DiagCode.REQUEST_ACCEPTED, DiagCode.REQUEST_FAILED), events.map { it.code })
        val failed = events.last()
        assertEquals(DiagFail.PROTOCOL, failed.fail)
        assertEquals(DiagStage.WAIT, failed.stage)
        assertEquals("queued_unattributable", failed.cause?.wire)
        assertEquals("the app never sends the accepted request again", listOf("first synthetic request"), fake.rawPrompts.map { it.second })
        val json = report()
        assertTrue(json.contains("queued_unattributable"))
        assertFalse("no prompt text in the exported report", json.contains("first synthetic request"))
    }

    @Test
    fun `a later send in the same session after a queued acknowledgement fails with the reply-unrecoverable reason and is not sent again`() = runBlocking {
        val id = conversation()
        fake.scripts[id] = { emptyList() }
        fake.submitStatus = { "queued" }
        core.chat.send(id, "first synthetic request")
        fake.submitStatus = { "streaming" }
        val result = core.chat.send(id, "second synthetic request")
        assertTrue("the second send is refused attribution: $result", result is ChatSendResult.Failed)
        val failed = log.snapshot().filter { it.code == DiagCode.REQUEST_FAILED }.last()
        assertEquals(DiagFail.PROTOCOL, failed.fail)
        assertEquals(DiagStage.WAIT, failed.stage)
        assertEquals("reply_unrecoverable", failed.cause?.wire)
        assertEquals(listOf("first synthetic request", "second synthetic request"), fake.rawPrompts.map { it.second })
        val json = report()
        assertTrue(json.contains("reply_unrecoverable"))
        assertFalse(json.contains("second synthetic request"))
        assertFalse("no exception text in the exported report", json.contains("FIFO position"))
    }

    @Test
    fun `the classifier names the two fixed reasons by exception type, also when wrapped, and still calls other protocol failures unknown`() {
        assertEquals("queued_unattributable", DiagClassify.of(HermesQueuedAttributionException()).cause.wire)
        assertEquals("reply_unrecoverable", DiagClassify.of(HermesReplyUnrecoverableException("synthetic")).cause.wire)
        assertEquals("reply_unrecoverable", DiagClassify.of(HermesProtocolException("outer", HermesReplyUnrecoverableException("inner"))).cause.wire)
        assertEquals(DiagFail.PROTOCOL, DiagClassify.of(HermesQueuedAttributionException()).fail)
        assertEquals(DiagFail.PROTOCOL, DiagClassify.of(HermesReplyUnrecoverableException("synthetic")).fail)
        val other = DiagClassify.of(HermesProtocolException("synthetic"))
        assertEquals("unknown", other.cause.wire)
        assertEquals(DiagFail.PROTOCOL, other.fail)
        assertEquals("timeout", DiagClassify.of(HermesInactivityTimeoutException("synthetic")).cause.wire)
        assertEquals("disconnect_io", DiagClassify.of(HermesStreamEndedException("synthetic")).cause.wire)
    }

    @Test
    fun `an ordinary send that is answered records no failure`() = runBlocking {
        val id = conversation()
        fake.scripts[id] = { listOf(FakeHermesDashboard.complete("synthetic answer")) }
        val result = core.chat.send(id, "ordinary synthetic request")
        assertTrue("$result", result is ChatSendResult.Replied)
        assertTrue(log.snapshot().none { it.code == DiagCode.REQUEST_FAILED })
    }
}
