package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.HermesBearerSession
import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.auth.InMemoryHermesTokenStore
import com.rumi.hermesvoice.core.diag.DiagCode
import com.rumi.hermesvoice.core.diag.DiagLog
import com.rumi.hermesvoice.core.diag.DiagReport
import com.rumi.hermesvoice.core.diag.DiagPseudonyms
import com.rumi.hermesvoice.core.diag.WatchDiag
import com.rumi.hermesvoice.core.diag.WatchDiagStatus
import com.rumi.hermesvoice.core.net.OutgoingAttachment
import com.rumi.hermesvoice.core.sessions.OwnedSessionRegistry
import com.rumi.hermesvoice.core.settings.AppSettings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production [HermesVoiceCore.connect] wiring (real OkHttp HTTP and WebSocket) against [FakeHermesDashboard], with a
 * [DiagLog] attached: what a failed or half-finished text send leaves in the diagnostics that the user exports. This is a
 * contract double, not an authenticated Hermes run.
 */
class NetworkDiagnosticsBoundaryTest {
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

    private fun report(): String = DiagReport.build(19, log.snapshot(), log.dropped(), log.now(), "0123456789abcdef", WatchDiag(WatchDiagStatus.OFFLINE), emptyMap())

    @Test
    fun `a text send refused while staging an attachment is recorded as a failed request and leaks nothing`() = runBlocking {
        val id = conversation()
        fake.failFileAttach = true
        val result = core.chat.send(id, "private words SECRET-TEXT", listOf(OutgoingAttachment("Q3 secret-name.pdf", "application/pdf", "%PDF-1.7".toByteArray())))
        assertTrue(result is ChatSendResult.Failed)
        val failed = log.snapshot().filter { it.code == DiagCode.REQUEST_FAILED }
        assertEquals("exactly one failed request is recorded for the failed send", 1, failed.size)
        assertNotNull(failed.single().fail)
        val json = report()
        assertFalse(json.contains("SECRET-TEXT"))
        assertFalse(json.contains("secret-name"))
        assertFalse(json.contains("disk full"))
        assertFalse(json.contains(id))
    }

    @Test
    fun `a send whose reply connection drops after acceptance is not recorded as never sent`() = runBlocking {
        val id = conversation()
        fake.scripts[id] = { emptyList() }
        fake.turnBehavior = {
            Thread { Thread.sleep(150); http.dispatcher.cancelAll() }.start()
            "start_first"
        }
        val result = core.chat.send(id, "will be accepted")
        assertTrue("the reply wait ended in a failure: $result", result is ChatSendResult.Failed)
        val codes = log.snapshot().map { it.code }
        val accepted = codes.indexOf(DiagCode.REQUEST_ACCEPTED)
        val failed = codes.indexOf(DiagCode.REQUEST_FAILED)
        assertTrue("acceptance was observed and recorded: $codes", accepted >= 0)
        assertTrue("the failure follows the acceptance: $codes", failed > accepted)
        assertFalse("a response failure is not recorded as a refusal to send", codes.contains(DiagCode.REQUEST_REFUSED_FULL))
    }

    @Test
    fun `two identical failing sends are two attempts with different correlations`() = runBlocking {
        val id = conversation()
        fake.failFileAttach = true
        val attachment = listOf(OutgoingAttachment("a.pdf", "application/pdf", "%PDF-1.7".toByteArray()))
        core.chat.send(id, "same text", attachment)
        core.chat.send(id, "same text", attachment)
        val failed = log.snapshot().filter { it.code == DiagCode.REQUEST_FAILED }
        assertEquals(2, failed.size)
        assertNotNull(failed[0].ref)
        assertNotNull(failed[1].ref)
        assertNotEquals("a retry is a distinct attempt, never merged by its text", failed[0].ref, failed[1].ref)
        assertTrue(DiagPseudonyms.FORMAT.matches(DiagPseudonyms.of("0123456789abcdef", failed[0].ref!!)))
    }

    @Test
    fun `a retry after a staging failure is a new attempt and nothing was submitted for either`() = runBlocking {
        val id = conversation()
        fake.failFileAttach = true
        val attachment = listOf(OutgoingAttachment("a.pdf", "application/pdf", "%PDF-1.7".toByteArray()))
        core.chat.send(id, "same text", attachment)
        fake.failFileAttach = false
        core.chat.send(id, "same text", attachment)
        val started = log.snapshot().filter { it.code == DiagCode.REQUEST_STARTED }
        assertEquals("every attempt is its own start", 2, started.size)
        assertNotEquals(started[0].ref, started[1].ref)
        assertEquals("only the second attempt reached prompt.submit, once", 1, fake.prompts.count { it.first == id })
    }

    @Test
    fun `a user Stop while waiting is recorded as stopped, propagates as a cancellation and is not a failure`() = runBlocking {
        val id = conversation()
        fake.scripts[id] = { emptyList() }
        fake.turnBehavior = { "start_first" }
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { core.chat.send(id, "will be stopped") }
        val deadline = System.nanoTime() + 10_000_000_000L
        while (log.snapshot().none { it.code == DiagCode.REQUEST_ACCEPTED } && System.nanoTime() < deadline) kotlinx.coroutines.delay(20)
        assertTrue("acceptance was observed before the stop", log.snapshot().any { it.code == DiagCode.REQUEST_ACCEPTED })
        job.cancel()
        kotlinx.coroutines.withTimeout(10_000) { job.join() }
        assertTrue("the cancellation was not swallowed", job.isCancelled)
        val codes = log.snapshot().map { it.code }
        assertTrue(codes.toString(), codes.contains(DiagCode.REQUEST_STOPPED))
        assertFalse(codes.toString(), codes.contains(DiagCode.REQUEST_FAILED))
    }

    @Test
    fun `a successful text send records its start and acceptance and exports no content`() = runBlocking {
        val id = conversation()
        fake.scripts[id] = { listOf(FakeHermesDashboard.complete("answer SECRET-REPLY")) }
        core.chat.send(id, "question SECRET-TEXT")
        val codes = log.snapshot().map { it.code }
        assertTrue(codes.toString(), codes.indexOf(DiagCode.REQUEST_STARTED) < codes.indexOf(DiagCode.REQUEST_ACCEPTED))
        val json = report()
        assertFalse(json.contains("SECRET-TEXT") || json.contains("SECRET-REPLY"))
    }
}
