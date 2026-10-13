package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import com.rumi.hermesvoice.core.auth.*
import com.rumi.hermesvoice.core.diag.*
import com.rumi.hermesvoice.core.net.*
import com.rumi.hermesvoice.core.settings.*
import com.rumi.hermesvoice.core.sessions.*
import com.rumi.hermesvoice.core.voice.*
import com.rumi.hermesvoice.core.watchlink.VoiceOutcomeText
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * N5/N4 regression, adopted from the independent reviewer's supplement falsifier (seed sha256 887ca8e6...cf0c) with harness-only
 * corrections (nullable `Response.body`, `Throwable?` in templates, and `finish()` tolerating a deliberately cancelled Deferred).
 *
 * N5: real [HermesDashboardClient] + real OkHttp + real MockWebServer sockets that send the response HEADERS immediately and then
 * throttle a partial JSON body, in REAL elapsed time. The HTTP client has the Phone's timeouts (connect 15 s, read 120 s, NO
 * callTimeout), so only the caller's coroutine (deadline, budget or Stop) can end the call. The harness cancels the underlying call
 * only as a last resort after HARD_CAP_MS and then marks the result as not natural, which fails the promptness assertion.
 *
 * N4: legacy / non-envelope HTTP 400/500 on /api/audio/transcribe carrying synthetic sentinel detail; each sink is asserted separately.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SttBodyPhaseCancellationTest {
    private val fake = FakeHermesDashboard()
    private val tokens = InMemoryHermesTokenStore()
    private val started = AtomicInteger()
    private val events: MutableList<SttRecoveryEvent> = Collections.synchronizedList(mutableListOf())
    private val delivered = AtomicInteger()
    private val submitted: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val uploads: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())
    private val responses: MutableList<Triple<String, Response, Long>> = Collections.synchronizedList(mutableListOf())
    private val allowlist = DestinationAllowlist.create(listOf(DestinationEntry("work", "work_session")), "router_session")
    private val diag = DiagLog(clock = { System.nanoTime() / 1_000_000 })
    private var owned: OkHttpClient? = null
    private val t0 = System.nanoTime()

    @After fun tearDown() {
        calls.forEach { it.cancel() }
        fake.close()
        owned?.dispatcher?.executorService?.shutdown()
    }

    private fun nowMs() = (System.nanoTime() - t0) / 1_000_000
    private fun hit(path: String) = hits.getOrPut(path) { AtomicInteger() }.get()

    private fun httpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            calls += chain.call()
            val response = chain.proceed(chain.request())
            responses += Triple(chain.request().url.encodedPath, response, nowMs())
            response
        }
        .build().also { owned = it }

    private fun dashboard(http: OkHttpClient) =
        HermesDashboardClient(HermesDashboardEndpoint.parse("http://127.0.0.1:${fake.server.port}/"), http, tokens)

    private fun signIn() = tokens.save(HermesBearerSession(fake.accessToken, fake.refreshToken, null, "basic", "jun"))

    /** [route] answers by path and the 1-based count of requests for that path; null falls through to the normal fake dashboard. */
    private fun route(behave: (path: String, n: Int) -> MockResponse?) {
        val original = fake.server.dispatcher
        fake.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path?.substringBefore('?').orEmpty()
                val n = hits.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                if (path == "/api/audio/transcribe") uploads += request.body.snapshot().sha256().hex()
                behave(path, n)?.let { return it }
                return original.dispatch(request)
            }
        }
    }

    private fun jsonResp(status: Int, body: String) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    /** Headers go out at once (MockWebServer flushes them before the body); the body then arrives 7 bytes per 500 ms: about 5 s in all. */
    private fun trickle(status: Int, body: String) = jsonResp(status, body).throttleBody(7, 500, TimeUnit.MILLISECONDS)

    private val okBody = """{"ok":true,"transcript":"hello","provider":"fake","pad":"${"x".repeat(33)}"}""" // ~70 bytes
    private val errBody = """{"detail":"upstream gave up","pad":"${"y".repeat(40)}"}"""
    private val refreshBody = """{"access_token":"AT-9","refresh_token":"RT-9","provider":"basic","pad":"${"z".repeat(30)}"}"""

    private val audio = byteArrayOf(1, 2, 3, 4, 5)
    private val audioBefore = audio.clone()

    private class Scripted(private val ch: Channel<RecipientEvent>) : SubmittedTurn {
        override val submitStatus = "streaming"
        override val attributable = true
        override suspend fun collect(timeoutMs: Long, onEvent: suspend (RecipientEvent) -> Unit) {
            for (e in ch) { onEvent(e); if (e is RecipientEvent.Complete) return }
        }
        override fun release() {}
    }

    private val conversations = object : HermesConversationPort {
        override suspend fun create(source: String, title: String, seedInstruction: String, hidden: Boolean) = throw UnsupportedOperationException()
        override suspend fun submit(storedSessionId: String, text: String, attachments: List<OutgoingAttachment>): SubmittedTurn {
            if (storedSessionId == "router_session") {
                return Scripted(Channel<RecipientEvent>(Channel.UNLIMITED).apply {
                    trySend(RecipientEvent.Complete("""{"destination":"work","ack":"Sending to work."}""", "complete"))
                })
            }
            submitted += text
            return Scripted(Channel<RecipientEvent>(Channel.UNLIMITED).apply { trySend(RecipientEvent.Complete("done", "complete")) })
        }
    }

    private val listener = object : VoiceTurnListener {
        override fun onSttRecovery(turnId: String, event: SttRecoveryEvent) {
            if (event.phase == SttPhase.ATTEMPT_STARTED) started.set(event.attempt)
            events += event
        }
        override fun onDelivered(route: AssembledRoute) { delivered.incrementAndGet() }
    }
    private val sink = PlaybackSink { _, _ -> }

    private fun orchestrator(client: HermesDashboardClient, policy: SttRecoveryPolicy) = VoiceTurnOrchestrator(client, conversations,
        inputGate = { _, _ -> AudioInputVerdict.USABLE },
        config = { VoiceTurnConfig("router_session", allowlist, ResponsePlaybackSettings()) },
        listener = DiagVoiceListener(listener, diag), sttPolicy = policy)

    private fun request(deadline: Long? = null) = VoiceTurnRequest("turn-1", VoiceOrigin.PHONE, audio, "audio/wav", sink, callerDeadlineMs = deadline)
    private fun attempts() = events.count { it.phase == SttPhase.ATTEMPT_STARTED }

    private fun awaitUntil(what: String, limitMs: Long = 8_000, condition: () -> Boolean) {
        val end = System.nanoTime() + limitMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > end) fail("timed out waiting for $what; events=${events.map { it.phase to it.attempt }} hits=$hits")
            Thread.sleep(10)
        }
    }

    private data class Finish<T>(val result: Result<T>?, val doneAtMs: Long, val natural: Boolean)

    @Volatile private var cancelledAtBound = -1

    /**
     * Wait (real time) for [job]. [boundMs] after [fromMs] the number of already-cancelled OkHttp calls is sampled (the caller's
     * deadline/Stop should by then have cancelled the live call). If the job has not ended by HARD_CAP_MS every call is cancelled
     * as a last-resort rescue and the result is marked not natural.
     */
    private fun <T> finish(job: Deferred<Result<T>>, fromMs: Long, boundMs: Long = 2_200): Finish<T> {
        val capAt = System.nanoTime() + HARD_CAP_MS * 1_000_000
        cancelledAtBound = -1
        while (!job.isCompleted && System.nanoTime() < capAt) {
            if (cancelledAtBound < 0 && nowMs() - fromMs >= boundMs) cancelledAtBound = calls.count { it.isCanceled() }
            Thread.sleep(10)
        }
        if (cancelledAtBound < 0) cancelledAtBound = calls.count { it.isCanceled() }
        var natural = true
        if (!job.isCompleted) {
            natural = false
            calls.forEach { it.cancel() }
            runBlocking { withTimeout(8_000) { job.join() } }
        }
        val done: Result<T> = try { job.getCompleted() } catch (e: kotlinx.coroutines.CancellationException) { Result.failure(e) }
        return Finish(done, nowMs() - fromMs, natural)
    }

    private fun ev(label: String, vararg kv: Pair<String, Any?>) =
        println("SUPP-EVIDENCE $label " + kv.joinToString(" ") { "${it.first}=${it.second}" })

    private fun transcribePath() = "/api/audio/transcribe"

    /** The properties the owner asked for after a cut-off body: call cancelled, response closed, no retry, audio stable. */
    private fun assertCancelledAndClosedNoRetry(label: String, expectedTranscribeUploads: Int = 1, responseIndex: Int = -1) {
        val resp = if (responseIndex >= 0) responses[responseIndex].second else responses.last { it.first == transcribePath() }.second
        val cancelledCalls = cancelledAtBound
        val bodyOpen = resp.body!!.source().isOpen
        ev(label, "calls" to calls.size, "callsCancelledAtBound" to cancelledCalls, "responseBodySourceOpen" to bodyOpen,
            "transcribeUploads" to uploads.size, "transcribeHits" to hit(transcribePath()), "refreshHits" to hit("/auth/native/refresh"),
            "attempts" to attempts(), "submitted" to submitted.size, "delivered" to delivered.get())
        assertTrue("$label: the live OkHttp call must have been cancelled (calls cancelled at the bound=$cancelledCalls of ${calls.size})", cancelledCalls >= 1)
        assertFalse("$label: the response body must be closed once the caller gave up", bodyOpen)
        assertEquals("$label: no retry of the upload", expectedTranscribeUploads, uploads.size)
        assertTrue("$label: nothing submitted", submitted.isEmpty())
        assertEquals("$label: nothing delivered", 0, delivered.get())
        assertTrue("$label: the caller's audio is unchanged", audio.contentEquals(audioBefore))
        fake.lastTranscribedWav?.let { assertTrue("$label: the server received the same recording", it.contentEquals(audioBefore)) }
    }

    private fun assertPrompt(label: String, doneAfterMs: Long, limitMs: Long, natural: Boolean) {
        ev("$label-timing", "returnedAfterMs" to doneAfterMs, "promptLimitMs" to limitMs, "naturalWorkerCompletion" to natural,
            "headersReceivedAtMs" to responses.map { it.first to it.third })
        assertTrue("$label: returned after $doneAfterMs ms; the deadline/Stop bound was ${limitMs} ms and the body is only complete after ~5000 ms " +
            "(natural=$natural). The coroutine did NOT return promptly.", doneAfterMs <= limitMs)
    }

    // ================================================================== N5: controls ==================================================================

    @Test fun `n5 control 1 ordinary success returns the transcript and closes the response`() = runBlocking {
        signIn()
        route { _, _ -> null }
        val client = dashboard(httpClient())
        val start = nowMs()
        val text = client.transcribe(audio, "audio/wav").also { }
        ev("n5-c1", "returnedAfterMs" to (nowMs() - start), "text" to text, "uploads" to uploads.size)
        assertEquals(fake.transcript, text)
        assertEquals(1, uploads.size)
        assertFalse("the response body is closed after a normal read", responses.last { it.first == transcribePath() }.second.body!!.source().isOpen)
    }

    @Test fun `n5 control 2 the trickled body is a valid fixture and completes naturally under a generous deadline`() {
        signIn()
        route { p, _ -> if (p == transcribePath()) trickle(200, okBody.replace("hello", "move my 2pm meeting to 3")) else null }
        val client = dashboard(httpClient())
        val from = nowMs()
        val job = CoroutineScope(Dispatchers.Default).async { runCatching { withTimeout(20_000) { client.transcribe(audio, "audio/wav") } } }
        awaitUntil("the headers to arrive") { responses.any { it.first == transcribePath() } }
        val headersAt = responses.first { it.first == transcribePath() }.third - from
        assertTrue("headers must arrive immediately, before the body is done: $headersAt ms", headersAt < 1_500)
        val f = finish(job, from)
        ev("n5-c2", "headersAtMs" to headersAt, "bodyCompleteAfterMs" to f.doneAtMs, "result" to f.result, "natural" to f.natural)
        assertTrue(f.result.toString(), f.result!!.isSuccess)
        assertEquals("move my 2pm meeting to 3", f.result.getOrNull())
        assertTrue("the throttled body must really take seconds (took ${f.doneAtMs} ms)", f.doneAtMs >= 3_000)
        assertEquals(1, uploads.size)
    }

    @Test fun `n5 control 3 cancelling before any header returns promptly and cancels the call`() {
        signIn()
        route { p, _ -> if (p == transcribePath()) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) else null }
        val client = dashboard(httpClient())
        val from = nowMs()
        val job = CoroutineScope(Dispatchers.Default).async { runCatching { withTimeout(1_200) { client.transcribe(audio, "audio/wav") } } }
        val f = finish(job, from)
        ev("n5-c3", "returnedAfterMs" to f.doneAtMs, "result" to f.result, "natural" to f.natural, "cancelledCalls" to calls.count { it.isCanceled() })
        assertTrue("control: pre-header cancel must be prompt (${f.doneAtMs} ms)", f.doneAtMs <= 2_200)
        assertTrue("control: the call was cancelled (cancelledAtBound=$cancelledAtBound)", cancelledAtBound >= 1)
        assertEquals("control: no retry", 1, uploads.size)
        assertTrue(submitted.isEmpty())
    }

    // ================================================================== N5: body phase, client level ==================================================================

    private fun clientDeadlineCase(label: String, status: Int, body: String, deadlineMs: Long = 1_200) {
        signIn()
        route { p, _ -> if (p == transcribePath()) trickle(status, body) else null }
        val client = dashboard(httpClient())
        val from = nowMs()
        val job = CoroutineScope(Dispatchers.Default).async { runCatching { withTimeout(deadlineMs) { client.transcribe(audio, "audio/wav") } } }
        awaitUntil("headers") { responses.any { it.first == transcribePath() } }
        val headersAt = responses.first { it.first == transcribePath() }.third - from
        assertTrue("$label: headers must be immediate ($headersAt ms)", headersAt < 1_000)
        val f = finish(job, from, deadlineMs + 1_000)
        ev(label, "result" to f.result, "returnedAfterMs" to f.doneAtMs, "natural" to f.natural, "headersAtMs" to headersAt)
        assertPrompt(label, f.doneAtMs, deadlineMs + 1_000, f.natural)
        assertCancelledAndClosedNoRetry(label)
    }

    @Test fun `n5 1 http 200 body cut off mid-body by the coroutine deadline`() = clientDeadlineCase("n5-1-200-deadline", 200, okBody)

    @Test fun `n5 2 http 500 error body cut off mid-body by the coroutine deadline`() = clientDeadlineCase("n5-2-500-deadline", 500, errBody)

    @Test fun `n5 3 http 401 then refresh response body cut off mid-body by the coroutine deadline`() {
        signIn()
        fake.rejectAccessTokens = setOf(fake.accessToken)
        route { p, _ -> if (p == "/auth/native/refresh") trickle(200, refreshBody) else null }
        val client = dashboard(httpClient())
        val from = nowMs()
        val job = CoroutineScope(Dispatchers.Default).async { runCatching { withTimeout(1_200) { client.transcribe(audio, "audio/wav") } } }
        awaitUntil("the refresh headers") { responses.any { it.first == "/auth/native/refresh" } }
        val f = finish(job, from, 2_200)
        ev("n5-3-401-refresh", "result" to f.result, "returnedAfterMs" to f.doneAtMs, "natural" to f.natural, "refreshHits" to hit("/auth/native/refresh"))
        assertPrompt("n5-3-401-refresh", f.doneAtMs, 2_200, f.natural)
        val refreshResp = responses.first { it.first == "/auth/native/refresh" }.second
        assertTrue("the refresh call was cancelled by the bound (cancelledAtBound=$cancelledAtBound)", cancelledAtBound >= 1)
        assertFalse("the refresh response body is closed", refreshResp.body!!.source().isOpen)
        assertEquals("the upload was not repeated after the refresh", 1, hit(transcribePath()))
        assertTrue(submitted.isEmpty())
    }

    @Test fun `n5 4 http 200 body cut off mid-body by cancelling the calling coroutine`() {
        signIn()
        route { p, _ -> if (p == transcribePath()) trickle(200, okBody) else null }
        val client = dashboard(httpClient())
        val from = nowMs()
        val job = CoroutineScope(Dispatchers.Default).async { runCatching { client.transcribe(audio, "audio/wav") } }
        awaitUntil("headers") { responses.any { it.first == transcribePath() } }
        Thread.sleep(600)
        val cancelledAt = nowMs()
        job.cancel()
        val f = finish(job, cancelledAt, 1_000)
        ev("n5-4-job-cancel", "cancelToReturnMs" to f.doneAtMs, "natural" to f.natural, "sinceStartMs" to (nowMs() - from))
        assertPrompt("n5-4-job-cancel", f.doneAtMs, 1_000, f.natural)
        assertCancelledAndClosedNoRetry("n5-4-job-cancel")
    }

    // ================================================================== N5: real orchestrator ==================================================================

    @Test fun `n5 5 explicit Stop during a trickled 200 body returns promptly with no retry and no submit`() {
        signIn()
        route { p, _ -> if (p == transcribePath()) trickle(200, okBody) else null }
        val orch = orchestrator(dashboard(httpClient()), SttRecoveryPolicy(budgetMs = 30_000, maxAttempts = 4, backoffMs = listOf(1_000, 1_000, 1_000)))
        val job = CoroutineScope(Dispatchers.Default).async { runCatching { orch.run(request()) } }
        awaitUntil("headers") { responses.any { it.first == transcribePath() } }
        Thread.sleep(600)
        val stopAt = nowMs()
        assertTrue(orch.stopTurn("turn-1"))
        val f = finish(job, stopAt, 1_000)
        ev("n5-5-stop", "outcome" to f.result?.getOrNull(), "stopToReturnMs" to f.doneAtMs, "natural" to f.natural)
        assertPrompt("n5-5-stop", f.doneAtMs, 1_000, f.natural)
        assertEquals(VoiceTurnOutcome.Stopped, f.result!!.getOrThrow())
        Thread.sleep(800)
        assertCancelledAndClosedNoRetry("n5-5-stop")
        assertEquals("no second attempt after Stop", 1, attempts())
    }

    @Test fun `n5 6 recording-wide budget of 1200 ms with a trickled 200 body ends at the budget with no retry and no submit`() {
        signIn()
        route { p, _ -> if (p == transcribePath()) trickle(200, okBody) else null }
        val orch = orchestrator(dashboard(httpClient()), SttRecoveryPolicy(budgetMs = 1_200, maxAttempts = 4, backoffMs = listOf(1_000, 1_000, 1_000)))
        val from = nowMs()
        val job = CoroutineScope(Dispatchers.Default).async { runCatching { orch.run(request()) } }
        val f = finish(job, from)
        ev("n5-6-budget", "outcome" to f.result?.getOrNull(), "returnedAfterMs" to f.doneAtMs, "natural" to f.natural)
        assertPrompt("n5-6-budget", f.doneAtMs, 1_200 + 1_000, f.natural)
        assertTrue(f.result.toString(), f.result!!.getOrThrow() is VoiceTurnOutcome.NotDelivered)
        assertCancelledAndClosedNoRetry("n5-6-budget")
    }

    @Test fun `n5 7 caller deadline of 1200 ms with a trickled 200 body ends at the deadline with no retry and no submit`() {
        signIn()
        route { p, _ -> if (p == transcribePath()) trickle(200, okBody) else null }
        val orch = orchestrator(dashboard(httpClient()), SttRecoveryPolicy(budgetMs = 60_000, maxAttempts = 4, backoffMs = listOf(1_000, 1_000, 1_000)))
        val from = nowMs()
        val job = CoroutineScope(Dispatchers.Default).async { runCatching { orch.run(request(deadline = System.nanoTime() / 1_000_000 + 1_200)) } }
        val f = finish(job, from)
        ev("n5-7-callerDeadline", "outcome" to f.result?.getOrNull(), "returnedAfterMs" to f.doneAtMs, "natural" to f.natural)
        assertPrompt("n5-7-callerDeadline", f.doneAtMs, 1_200 + 1_000, f.natural)
        assertTrue(f.result.toString(), f.result!!.getOrThrow() is VoiceTurnOutcome.NotDelivered)
        assertCancelledAndClosedNoRetry("n5-7-callerDeadline")
    }

    @Test fun `n5 8 recording-wide budget with a trickled 500 error body ends at the budget with no retry`() {
        signIn()
        route { p, _ -> if (p == transcribePath()) trickle(500, errBody) else null }
        val orch = orchestrator(dashboard(httpClient()), SttRecoveryPolicy(budgetMs = 1_200, maxAttempts = 4, backoffMs = listOf(1_000, 1_000, 1_000)))
        val from = nowMs()
        val job = CoroutineScope(Dispatchers.Default).async { runCatching { orch.run(request()) } }
        val f = finish(job, from)
        ev("n5-8-budget-500", "outcome" to f.result?.getOrNull(), "returnedAfterMs" to f.doneAtMs, "natural" to f.natural)
        assertPrompt("n5-8-budget-500", f.doneAtMs, 1_200 + 1_000, f.natural)
        assertCancelledAndClosedNoRetry("n5-8-budget-500")
    }

    // ================================================================== N4 ==================================================================

    private val sentUrl = "https://sentinel-host.invalid/hook?token=SUPP-SENTINEL-TOKEN-91c4"
    private val sentPath = "/home/sentinel/SUPP-SENTINEL-PATH-91c4/key.pem"
    private val sentDetail = "upstream $sentUrl failed reading $sentPath"
    private fun hasSentinel(text: String?) = text != null && (text.contains("SUPP-SENTINEL") || text.contains("sentinel-host.invalid") || text.contains("/home/sentinel"))
    private fun detailJson(detail: String) = org.json.JSONObject().put("detail", detail).toString()

    private fun legacy(status: Int, detail: String = sentDetail) {
        signIn()
        route { p, _ -> if (p == transcribePath()) jsonResp(status, detailJson(detail)) else null }
    }

    @Test fun `n4 1 client exception message for a legacy 400 does not echo raw detail`() = runBlocking {
        legacy(400)
        val error = runCatching { dashboard(httpClient()).transcribe(audio, "audio/wav") }.exceptionOrNull()
        ev("n4-1-400-exception", "type" to error?.javaClass?.simpleName, "message" to error?.message, "status" to (error as? HermesHttpException)?.status, "sttCode" to (error as? HermesHttpException)?.sttCode)
        assertTrue(error.toString(), error is HermesHttpException)
        assertEquals(400, (error as HermesHttpException).status)
        assertFalse("raw upstream detail (URL/token/path) is echoed into HermesHttpException.message: ${error.message}", hasSentinel(error.message))
    }

    @Test fun `n4 2 client exception message for a legacy 500 does not echo raw detail`() = runBlocking {
        legacy(500)
        val error = runCatching { dashboard(httpClient()).transcribe(audio, "audio/wav") }.exceptionOrNull()
        ev("n4-2-500-exception", "type" to error?.javaClass?.simpleName, "message" to error?.message, "status" to (error as? HermesHttpException)?.status)
        assertTrue(error.toString(), error is HermesHttpException)
        assertFalse("raw upstream detail is echoed into HermesHttpException.message: ${error?.message}", hasSentinel(error?.message))
    }

    @Test fun `n4 3 turn outcome reason for a legacy 400 does not carry raw detail`() {
        legacy(400)
        val orch = orchestrator(dashboard(httpClient()), SttRecoveryPolicy(budgetMs = 12_000, maxAttempts = 4, backoffMs = listOf(1_000, 1_000, 1_000)))
        val outcome = runBlocking { withTimeout(10_000) { orch.run(request()) } }
        ev("n4-3-outcome-reason", "outcome" to outcome)
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.NotDelivered)
        assertFalse("NotDelivered.reason carries the raw upstream detail: ${(outcome as VoiceTurnOutcome.NotDelivered).reason}", hasSentinel(outcome.reason))
    }

    @Test fun `n4 4 UI text, diagnostics log and recovery events never carry raw detail for legacy 400 and 500`() {
        for (status in listOf(400, 500)) {
            events.clear(); uploads.clear(); hits.clear()
            legacy(status)
            val orch = orchestrator(dashboard(httpClient()), SttRecoveryPolicy(budgetMs = 12_000, maxAttempts = 4, backoffMs = listOf(1_000, 1_000, 1_000)))
            val outcome = runBlocking { withTimeout(10_000) { orch.run(request()) } }
            val ui = VoiceOutcomeText.describe(outcome)
            val diagText = diag.snapshot().toString()
            val eventText = events.toString()
            ev("n4-4-sinks-$status", "ui" to ui, "diag" to diagText.take(300), "events" to eventText.take(300))
            assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.NotDelivered)
            assertFalse("UI text: $ui", hasSentinel(ui))
            assertFalse("diagnostics log: $diagText", hasSentinel(diagText))
            assertFalse("recovery events: $eventText", hasSentinel(eventText))
        }
    }

    @Test fun `n4 5 a legacy 400 and a legacy 500 are not retried`() {
        for (status in listOf(400, 500)) {
            events.clear(); uploads.clear(); hits.clear()
            legacy(status)
            val orch = orchestrator(dashboard(httpClient()), SttRecoveryPolicy(budgetMs = 12_000, maxAttempts = 4, backoffMs = listOf(1_000, 1_000, 1_000)))
            val outcome = runBlocking { withTimeout(10_000) { orch.run(request()) } }
            ev("n4-5-no-retry-$status", "attempts" to attempts(), "uploads" to uploads.size, "outcome" to outcome.javaClass.simpleName)
            assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.NotDelivered)
            assertEquals("status $status must not be retried", 1, attempts())
            assertEquals(1, uploads.size)
            assertTrue(submitted.isEmpty())
        }
    }

    @Test fun `n4 6 an envelope marker quoted mid-text is not accepted as a safe code`() = runBlocking {
        legacy(503, "proxy said: [hermes-stt:stt_unavailable] please retry $sentUrl")
        val error = runCatching { dashboard(httpClient()).transcribe(audio, "audio/wav") }.exceptionOrNull()
        ev("n4-6-midtext", "message" to error?.message, "sttCode" to (error as? HermesHttpException)?.sttCode)
        assertTrue(error.toString(), error is HermesHttpException)
        assertEquals("a code mentioned in the middle of text must not be treated as the envelope", null, (error as HermesHttpException).sttCode)
        val orch = orchestrator(dashboard(httpClient()), SttRecoveryPolicy(budgetMs = 12_000, maxAttempts = 4, backoffMs = listOf(1_000, 1_000, 1_000)))
        events.clear(); uploads.clear()
        val outcome = withTimeout(10_000) { orch.run(request()) }
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.NotDelivered)
        assertEquals("mid-text mention must not trigger a retry", 1, attempts())
    }

    @Test fun `n4 7 an exact envelope marker with a hostile tail is reduced to the fixed wording`() = runBlocking {
        legacy(503, "[hermes-stt:stt_unavailable] $sentUrl $sentPath")
        val error = runCatching { dashboard(httpClient()).transcribe(audio, "audio/wav") }.exceptionOrNull()
        ev("n4-7-marker-tail", "message" to error?.message, "sttCode" to (error as? HermesHttpException)?.sttCode)
        assertTrue(error.toString(), error is HermesHttpException)
        assertFalse("tail text must never survive: ${error?.message}", hasSentinel(error?.message))
    }

    companion object { const val HARD_CAP_MS = 9_000L }
}
