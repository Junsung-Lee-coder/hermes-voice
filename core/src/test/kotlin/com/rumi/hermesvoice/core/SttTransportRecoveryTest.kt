package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import com.rumi.hermesvoice.core.auth.*
import com.rumi.hermesvoice.core.diag.*
import com.rumi.hermesvoice.core.net.*
import com.rumi.hermesvoice.core.settings.*
import com.rumi.hermesvoice.core.sessions.*
import com.rumi.hermesvoice.core.voice.*
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A temporary TRANSPORT failure of the Phone dashboard's speech request (name not resolved, connection refused, connection
 * reset, time-out) is recovered exactly like the server's own "temporarily unavailable" answer: the same recording is
 * transcribed again, inside the same bounded budget, and the turn is delivered once.
 *
 * Failures are produced at the real boundary: the real [HermesDashboardClient] over a real OkHttp client talking to a real
 * loopback socket server (DNS: the OkHttp [Dns] hook; connect: a closed port; reset: the server closes the connection after the
 * request; time-out: the server never answers within the client's read time; TLS refusal: an OkHttp interceptor raising the
 * handshake failure the platform raises). Waits and budget here are shortened by policy only; the production policy and its
 * virtual-time run are asserted separately.
 */
class SttTransportRecoveryTest {
    private val fake = FakeHermesDashboard()
    private val tokens = InMemoryHermesTokenStore()
    private val started = AtomicInteger()
    private val events: MutableList<SttRecoveryEvent> = Collections.synchronizedList(mutableListOf())
    private val stages: MutableList<VoiceTurnStage> = Collections.synchronizedList(mutableListOf())
    private val delivered = AtomicInteger()
    private val submitted: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val uploads: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val allowlist = DestinationAllowlist.create(listOf(DestinationEntry("work", "work_session")), "router_session")
    private val diag = DiagLog(clock = { System.nanoTime() / 1_000_000 })
    private val dnsFailures = AtomicInteger()
    private val callRef = AtomicReference<Call?>()
    private var owned: OkHttpClient? = null

    @After fun tearDown() {
        fake.close()
        owned?.dispatcher?.executorService?.shutdown()
    }

    private val dnsBase get() = "http://$DNS_NAME:${fake.server.port}/"

    private val shortPolicy = SttRecoveryPolicy(budgetMs = 12_000, maxAttempts = 4, backoffMs = listOf(1_000, 1_000, 1_000))

    private fun httpClient(readMs: Long = 20_000, interceptor: okhttp3.Interceptor? = null): OkHttpClient =
        OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(60, TimeUnit.SECONDS).readTimeout(readMs, TimeUnit.MILLISECONDS)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    if (dnsFailures.get() > 0 && started.get() <= dnsFailures.get()) throw UnknownHostException(hostname)
                    if (hostname == DNS_NAME) return listOf(java.net.InetAddress.getByName("127.0.0.1"))
                    return Dns.SYSTEM.lookup(hostname)
                }
            })
            .addInterceptor { chain -> callRef.set(chain.call()); chain.proceed(chain.request()) }
            .also { builder -> if (interceptor != null) builder.addInterceptor(interceptor) }
            .build().also { owned = it }

    private fun dashboard(http: OkHttpClient, base: String = "http://127.0.0.1:${fake.server.port}/") =
        HermesDashboardClient(HermesDashboardEndpoint.parse(base), http, tokens)

    private fun signIn() = tokens.save(HermesBearerSession(fake.accessToken, fake.refreshToken, null, "basic", "jun"))

    /** [behave] answers a transcription request given how many attempts the orchestrator has started; null: the normal dashboard. */
    private fun script(behave: (attempt: Int) -> MockResponse?) {
        val original = fake.server.dispatcher
        fake.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.substringBefore('?') == "/api/audio/transcribe") {
                    uploads += request.body.snapshot().sha256().hex()
                    behave(started.get())?.let { return it }
                }
                return original.dispatch(request)
            }
        }
    }

    private fun reset() = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
    private fun silent() = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
    private fun json(status: Int, body: String) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    private class Scripted(private val ch: Channel<RecipientEvent>) : SubmittedTurn {
        override val submitStatus = "streaming"
        override val attributable = true
        override suspend fun collect(timeoutMs: Long, onEvent: suspend (RecipientEvent) -> Unit) {
            for (e in ch) { onEvent(e); if (e is RecipientEvent.Complete) return }
        }
        override fun release() {}
    }

    private var submitFailure: IOException? = null
    private val conversations = object : HermesConversationPort {
        override suspend fun create(source: String, title: String, seedInstruction: String, hidden: Boolean) = throw UnsupportedOperationException()
        override suspend fun submit(storedSessionId: String, text: String, attachments: List<OutgoingAttachment>): SubmittedTurn {
            if (storedSessionId == "router_session") {
                return Scripted(Channel<RecipientEvent>(Channel.UNLIMITED).apply {
                    trySend(RecipientEvent.Complete("""{"destination":"work","ack":"Sending to work."}""", "complete"))
                })
            }
            submitted += voiceWords(text)
            submitFailure?.let { throw it }
            return Scripted(Channel<RecipientEvent>(Channel.UNLIMITED).apply { trySend(RecipientEvent.Complete("done", "complete")) })
        }
    }

    private val listener = object : VoiceTurnListener {
        override fun onStage(turnId: String, stage: VoiceTurnStage) { stages += stage }
        override fun onSttRecovery(turnId: String, event: SttRecoveryEvent) {
            if (event.phase == SttPhase.ATTEMPT_STARTED) started.set(event.attempt)
            events += event
        }
        override fun onDelivered(route: AssembledRoute) { delivered.incrementAndGet() }
    }

    private val played = AtomicInteger()
    private val sink = PlaybackSink { _, _ -> played.incrementAndGet() }

    private fun orchestrator(client: HermesDashboardClient, policy: SttRecoveryPolicy = shortPolicy) = VoiceTurnOrchestrator(client, conversations,
        inputGate = { _, _ -> AudioInputVerdict.USABLE },
        config = { VoiceTurnConfig("router_session", allowlist, ResponsePlaybackSettings()) },
        listener = DiagVoiceListener(listener, diag), sttPolicy = policy)

    private fun request(origin: VoiceOrigin = VoiceOrigin.PHONE) = VoiceTurnRequest("turn-1", origin, byteArrayOf(1, 2, 3, 4, 5), "audio/wav", sink)

    private fun attempts() = events.count { it.phase == SttPhase.ATTEMPT_STARTED }
    private fun waits() = events.count { it.phase == SttPhase.WAIT_STARTED }

    private fun awaitUntil(what: String, limitMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.nanoTime() + limitMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > end) fail("timed out waiting for $what; events=${events.map { it.phase to it.attempt }}")
            Thread.sleep(20)
        }
    }

    private fun assertDeliveredOnce(outcome: VoiceTurnOutcome) {
        assertTrue(outcome.toString() + " events=" + events.map { listOf(it.phase, it.attempt, it.transport, it.failure) }, outcome is VoiceTurnOutcome.Completed)
        assertEquals(listOf(fake.transcript), submitted.toList())
        assertEquals(1, delivered.get())
        assertEquals("the recording is the one first recorded, every attempt", 1, uploads.toSet().size)
    }

    private fun assertNothingLeaksEndpoint() {
        val text = diag.snapshot().toString() + events.toString()
        assertFalse(text, text.contains("localhost") || text.contains(DNS_NAME) || text.contains(fake.server.port.toString()) || text.contains("127.0.0.1"))
    }

    // ---- the client's own boundary --------------------------------------------------------------------------------------

    @Test fun `each temporary transport failure of the speech request is typed by the dashboard client`() = runBlocking {
        signIn()
        // DNS
        dnsFailures.set(100)
        started.set(1)
        val dns = dashboard(httpClient(), dnsBase)
        val e1 = runCatching { dns.transcribe(ByteArray(8) { 1 }, "audio/wav") }.exceptionOrNull()
        assertTrue(e1.toString(), e1 is HermesSttTransportException)
        assertEquals(SttTransport.DNS, (e1 as HermesSttTransportException).transport)
        assertEquals(SttCode.UNAVAILABLE, e1.sttCode)
        assertTrue(e1.cause is UnknownHostException)
        assertEquals("the safe wording never repeats the cause or the host", "speech server unreachable (dns)", e1.message)
        dnsFailures.set(0)

        // CONNECT: a port nobody listens on
        val closedPort = ServerSocket(0).use { it.localPort }
        val connect = dashboard(httpClient(), "http://127.0.0.1:$closedPort/")
        val e2 = runCatching { connect.transcribe(ByteArray(8) { 1 }, "audio/wav") }.exceptionOrNull()
        assertTrue(e2.toString(), e2 is HermesSttTransportException)
        assertEquals(SttTransport.CONNECT, (e2 as HermesSttTransportException).transport)
        assertTrue(e2.cause is ConnectException)

        // RESET: the server drops the connection after reading the upload
        script { reset() }
        val e3 = runCatching { dashboard(httpClient()).transcribe(ByteArray(8) { 1 }, "audio/wav") }.exceptionOrNull()
        assertTrue(e3.toString(), e3 is HermesSttTransportException)
        assertEquals(SttTransport.RESET, (e3 as HermesSttTransportException).transport)

        // TIMEOUT: the server never answers
        script { silent() }
        val e4 = runCatching { dashboard(httpClient(readMs = 400)).transcribe(ByteArray(8) { 1 }, "audio/wav") }.exceptionOrNull()
        assertTrue(e4.toString(), e4 is HermesSttTransportException)
        assertEquals(SttTransport.TIMEOUT, (e4 as HermesSttTransportException).transport)
        assertEquals(SttCode.TIMEOUT, e4.sttCode)
        assertTrue(e4.cause is InterruptedIOException)
    }

    @Test fun `the transport typing is limited to the speech request`() = runBlocking {
        signIn()
        script { null }
        dnsFailures.set(100)
        started.set(1)
        val client = dashboard(httpClient(), dnsBase)
        val plain = runCatching { client.speak("hello") }.exceptionOrNull()
        assertTrue("synthesis keeps its plain IOException: $plain", plain is UnknownHostException)
    }

    @Test fun `a TLS trust refusal is not a transport failure`() = runBlocking {
        signIn()
        val refuse = okhttp3.Interceptor { throw SSLHandshakeException("handshake").apply { initCause(CertificateException("untrusted")) } }
        val error = runCatching { dashboard(httpClient(interceptor = refuse)).transcribe(ByteArray(8) { 1 }, "audio/wav") }.exceptionOrNull()
        assertTrue(error.toString(), error is SSLException)
        assertFalse(error is HermesSttTransportException)
        assertEquals(0, fake.server.requestCount)
    }

    @Test fun `a call cancelled by call cancel is not a transport failure`() = runBlocking {
        signIn()
        script { silent() }
        val client = dashboard(httpClient())
        val result = async(Dispatchers.Default) { runCatching { client.transcribe(ByteArray(8) { 1 }, "audio/wav") } }
        awaitUntil("the upload reaching the server") { uploads.isNotEmpty() }
        callRef.get()!!.cancel()
        val outcome = withTimeout(10_000) { result.await() }
        val error = outcome.exceptionOrNull()
        assertNotNull(error)
        assertFalse("a cancelled call is not reported as a transport failure: $error", error is HermesSttTransportException)
    }

    @Test fun `cancelling the coroutine cancels the live call and reports no transport failure`() = runBlocking {
        signIn()
        script { silent() }
        val http = httpClient()
        val client = dashboard(http)
        val job = launch(Dispatchers.Default) { client.transcribe(ByteArray(8) { 1 }, "audio/wav") }
        awaitUntil("the upload reaching the server") { uploads.isNotEmpty() }
        job.cancel()
        withTimeout(10_000) { job.join() }
        assertTrue(job.isCancelled)
        awaitUntil("the OkHttp call to end", 5_000) { http.dispatcher.runningCallsCount() == 0 }
    }

    @Test fun `the exact classification is by type and phase never by message text`() {
        fun c(error: IOException, canceled: Boolean = false) = SttTransportClassifier.classify(error, canceled)
        assertEquals(SttTransport.DNS, c(UnknownHostException("x")))
        assertEquals(SttTransport.CONNECT, c(ConnectException("x")))
        assertEquals(SttTransport.CONNECT, c(NoRouteToHostException("x")))
        assertEquals(SttTransport.RESET, c(SocketException("anything")))
        assertEquals(SttTransport.RESET, c(EOFException()))
        assertEquals(SttTransport.RESET, c(IOException("wrapped", SocketException("inner"))))
        assertEquals(SttTransport.TIMEOUT, c(SocketTimeoutException("x")))
        assertEquals(SttTransport.TIMEOUT, c(InterruptedIOException("timeout")))
        assertNull("message text alone is never enough", c(IOException("connection reset by peer")))
        assertNull(c(IOException("timeout")))
        assertNull(c(IOException("unknown host")))
        assertNull("a protocol violation is not in the set", c(java.net.ProtocolException("unexpected end of stream")))
        assertNull(c(IOException("Canceled")))
        assertNull("TLS wins over a socket cause", c(SSLHandshakeException("h").apply { initCause(SocketException("reset")) }))
        assertNull(c(SSLException("tls", EOFException())))
        assertNull(c(IOException("x", CertificateException("untrusted"))))
        assertNull("a cancelled call surfaces as many socket errors, none a transport failure", c(SocketException("Socket closed"), canceled = true))
        assertNull(c(UnknownHostException("x"), canceled = true))
    }

    @Test fun `unsupported media and oversize audio are refused before any network use`() = runBlocking {
        signIn()
        val client = dashboard(httpClient())
        assertTrue(runCatching { client.transcribe(ByteArray(0), "audio/wav") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { client.transcribe(ByteArray(8), "text/plain") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { client.transcribe(ByteArray(HermesDashboardClient.MAX_TRANSCRIBE_BYTES + 1), "audio/wav") }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, fake.server.requestCount)
    }

    // ---- the recovery path ----------------------------------------------------------------------------------------------

    @Test fun `a reset connection is waited out and the same recording is delivered once`() = runBlocking {
        signIn()
        script { attempt -> if (attempt <= 2) reset() else null }
        val outcome = withTimeout(60_000) { orchestrator(dashboard(httpClient())).run(request()) }
        assertDeliveredOnce(outcome)
        assertEquals(3, attempts())
        assertEquals(2, waits())
        assertTrue(events.filter { it.phase == SttPhase.WAIT_STARTED }.all { it.transport == SttTransport.RESET && it.code == null && it.status == null })
        assertTrue(events.filter { it.phase == SttPhase.WAIT_STARTED }.all { it.failure != null && it.failure != "" })
        assertTrue(stages.contains(VoiceTurnStage.STT_RECOVERING))
        assertNothingLeaksEndpoint()
    }

    @Test fun `a name that does not resolve is waited out and recovered`() = runBlocking {
        signIn()
        script { null }
        dnsFailures.set(2)
        val outcome = withTimeout(60_000) { orchestrator(dashboard(httpClient(), dnsBase)).run(request()) }
        assertDeliveredOnce(outcome)
        assertEquals(3, attempts())
        assertTrue(events.filter { it.phase == SttPhase.WAIT_STARTED }.all { it.transport == SttTransport.DNS })
        assertNothingLeaksEndpoint()
    }

    @Test fun `a read time-out is waited out and recovered`() = runBlocking {
        signIn()
        script { attempt -> if (attempt <= 1) silent() else null }
        val outcome = withTimeout(60_000) { orchestrator(dashboard(httpClient(readMs = 500))).run(request()) }
        assertDeliveredOnce(outcome)
        assertEquals(2, attempts())
        assertEquals(SttTransport.TIMEOUT, events.single { it.phase == SttPhase.WAIT_STARTED }.transport)
    }

    @Test fun `a first attempt that cannot connect is recovered inside the same bounded budget and then ends honestly`() = runBlocking {
        signIn()
        val closedPort = ServerSocket(0).use { it.localPort }
        val outcome = withTimeout(60_000) {
            orchestrator(dashboard(httpClient(), "http://127.0.0.1:$closedPort/"), SttRecoveryPolicy(budgetMs = 8_000, maxAttempts = 3, backoffMs = listOf(1_000, 1_000))).run(request())
        }
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.NotDelivered)
        assertEquals(VoiceTurnStage.TRANSCRIBING, (outcome as VoiceTurnOutcome.NotDelivered).stage)
        assertEquals(3, attempts())
        val end = events.last { it.phase == SttPhase.EXHAUSTED }
        assertEquals(SttCode.UNAVAILABLE, end.code)
        assertEquals(SttTransport.CONNECT, end.transport)
        assertTrue(submitted.isEmpty())
        assertEquals(0, delivered.get())
        assertFalse(outcome.reason, outcome.reason.contains("127.0.0.1") || outcome.reason.contains(closedPort.toString()))
    }

    @Test fun `a Watch turn is recovered with its own sink and delivered once`() = runBlocking {
        signIn()
        script { attempt -> if (attempt <= 1) reset() else null }
        val outcome = withTimeout(60_000) { orchestrator(dashboard(httpClient())).run(request(VoiceOrigin.WATCH)) }
        assertDeliveredOnce(outcome)
        assertTrue("the reply reached the turn's own sink", played.get() >= 1)
    }

    @Test fun `Stop during the wait after a transport failure ends exactly that turn without another attempt`() = runBlocking {
        signIn()
        script { reset() }
        val orchestrator = orchestrator(dashboard(httpClient()), SttRecoveryPolicy(budgetMs = 30_000, maxAttempts = 4, backoffMs = listOf(5_000, 5_000, 5_000)))
        val run = async(Dispatchers.Default) { orchestrator.run(request()) }
        awaitUntil("the wait") { waits() == 1 }
        assertEquals(PendingPhase.WAITING_STT, orchestrator.pending.value.single().phase)
        assertTrue(orchestrator.stopTurn("turn-1"))
        assertEquals(VoiceTurnOutcome.Stopped, withTimeout(10_000) { run.await() })
        val before = attempts()
        Thread.sleep(1_500)
        assertEquals("no attempt after a Stop", before, attempts())
        assertTrue(submitted.isEmpty())
        assertTrue(orchestrator.pending.value.isEmpty())
        assertTrue(events.any { it.phase == SttPhase.CANCELLED && it.waiting })
    }

    @Test fun `Stop during a retried call cancels the live OkHttp call and nothing is retried or submitted`() = runBlocking {
        signIn()
        script { attempt -> if (attempt <= 1) reset() else silent() }
        val http = httpClient()
        val orchestrator = orchestrator(dashboard(http))
        val run = async(Dispatchers.Default) { orchestrator.run(request()) }
        awaitUntil("the second attempt in flight") { attempts() == 2 && uploads.size >= 2 }
        assertTrue(orchestrator.stopTurn("turn-1"))
        assertEquals(VoiceTurnOutcome.Stopped, withTimeout(10_000) { run.await() })
        awaitUntil("the OkHttp call to end", 5_000) { http.dispatcher.runningCallsCount() == 0 }
        Thread.sleep(1_500)
        assertEquals(2, attempts())
        assertTrue(submitted.isEmpty())
        assertEquals(0, delivered.get())
        assertTrue(events.any { it.phase == SttPhase.CANCELLED && !it.waiting })
    }

    @Test fun `a TLS trust refusal ends the turn at once with no retry`() = runBlocking {
        signIn()
        val refuse = okhttp3.Interceptor { throw SSLHandshakeException("handshake").apply { initCause(CertificateException("untrusted")) } }
        val outcome = withTimeout(30_000) { orchestrator(dashboard(httpClient(interceptor = refuse))).run(request()) }
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.NotDelivered)
        assertEquals(1, attempts())
        assertEquals(0, waits())
        assertTrue(submitted.isEmpty())
    }

    @Test fun `an auth refusal is never retried`() = runBlocking {
        signIn()
        fake.rejectAccessTokens = setOf("AT-1", "AT-2")
        val outcome = withTimeout(30_000) { orchestrator(dashboard(httpClient())).run(request()) }
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.NotDelivered && (outcome as VoiceTurnOutcome.NotDelivered).authRequired)
        assertEquals(1, attempts())
        assertEquals(0, waits())
        assertTrue(submitted.isEmpty())
    }

    @Test fun `a permission refusal and a raw generic 400 are never retried`() = runBlocking {
        signIn()
        for ((status, body) in listOf(403 to """{"detail":"forbidden"}""", 400 to """{"detail":"Transcription failed"}""")) {
            events.clear(); uploads.clear(); started.set(0)
            script { json(status, body) }
            val outcome = withTimeout(30_000) { orchestrator(dashboard(httpClient())).run(request()) }
            assertTrue("$status $outcome", outcome is VoiceTurnOutcome.NotDelivered)
            assertEquals("$status", 1, attempts())
            assertEquals("$status", 0, waits())
            assertEquals("$status", 1, uploads.size)
        }
        assertTrue(submitted.isEmpty())
    }

    @Test fun `every permanent provider refusal is final after one attempt`() = runBlocking {
        signIn()
        for (code in SttCode.values().filter { !it.retryable }) {
            events.clear(); uploads.clear(); started.set(0)
            script { json(400, """{"detail":"[hermes-stt:${code.wire}] ${SttSafeCode.message(code)}"}""") }
            val outcome = withTimeout(30_000) { orchestrator(dashboard(httpClient())).run(request()) }
            assertTrue("${code.wire} $outcome", outcome is VoiceTurnOutcome.NotDelivered)
            assertEquals(code.wire, 1, attempts())
            assertEquals(code.wire, 0, waits())
        }
        assertTrue(submitted.isEmpty())
    }

    @Test fun `a conversation submit that fails after the transcript is never retried or re-transcribed`() = runBlocking {
        signIn()
        script { attempt -> if (attempt <= 1) reset() else null }
        submitFailure = SocketException("reset while submitting")
        val outcome = withTimeout(60_000) { orchestrator(dashboard(httpClient())).run(request()) }
        assertFalse(outcome.toString(), outcome is VoiceTurnOutcome.Completed)
        assertEquals("transcribed once more, not again after the submit failed", 2, attempts())
        assertEquals("submitted exactly once", 1, submitted.size)
        Thread.sleep(1_200)
        assertEquals(2, attempts())
        assertEquals(1, submitted.size)
    }

    @Test fun `a server that answered 200 with a bad body is not a transport failure`() = runBlocking {
        signIn()
        script { json(200, "not json at all") }
        val outcome = withTimeout(30_000) { orchestrator(dashboard(httpClient())).run(request()) }
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.NotDelivered)
        assertEquals(1, attempts())
        assertEquals(0, waits())
    }

    // ---- production policy ----------------------------------------------------------------------------------------------

    @Test fun `the production policy is unchanged`() {
        val policy = SttRecoveryPolicy()
        assertEquals(180_000L, policy.budgetMs)
        assertEquals(6, policy.maxAttempts)
        assertEquals(listOf(5_000L, 15_000L, 30_000L, 60_000L, 60_000L), policy.backoffMs)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `with the production policy a persistent transport failure makes at most six attempts within 180 seconds`() = runTest {
        val clockScope: TestScope = this
        val calls = AtomicInteger()
        val times = Collections.synchronizedList(mutableListOf<Long>())
        val ev = Collections.synchronizedList(mutableListOf<SttRecoveryEvent>())
        val speech = object : HermesSpeechGateway {
            override suspend fun transcribe(audio: ByteArray, mimeType: String): String {
                calls.incrementAndGet(); times += clockScope.currentTime
                throw HermesSttTransportException(SttTransport.RESET, java.net.SocketException("reset"))
            }
            override suspend fun speak(text: String) = SpokenAudio("A".toByteArray(), "audio/mpeg")
        }
        val orchestrator = VoiceTurnOrchestrator(speech, conversations, inputGate = { _, _ -> AudioInputVerdict.USABLE },
            config = { VoiceTurnConfig("router_session", allowlist, ResponsePlaybackSettings()) },
            listener = object : VoiceTurnListener { override fun onSttRecovery(turnId: String, event: SttRecoveryEvent) { ev += event } },
            recoveryClock = { clockScope.currentTime })
        val outcome = orchestrator.run(request()) as VoiceTurnOutcome.NotDelivered
        assertEquals(VoiceTurnStage.TRANSCRIBING, outcome.stage)
        assertTrue("attempts ${calls.get()}", calls.get() in 4..6)
        assertEquals(listOf(0L, 5_000L, 20_000L, 50_000L, 110_000L), times.toList().take(5))
        assertTrue("budget respected: ${clockScope.currentTime}", clockScope.currentTime <= 180_000L)
        assertTrue(submitted.isEmpty())
        assertEquals(SttCode.UNAVAILABLE, ev.last { it.phase == SttPhase.EXHAUSTED }.code)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `a tighter caller deadline bounds recovery of a transport failure`() = runTest {
        val clockScope: TestScope = this
        val calls = AtomicInteger()
        val speech = object : HermesSpeechGateway {
            override suspend fun transcribe(audio: ByteArray, mimeType: String): String {
                calls.incrementAndGet()
                throw HermesSttTransportException(SttTransport.CONNECT, java.net.ConnectException("refused"))
            }
            override suspend fun speak(text: String) = SpokenAudio("A".toByteArray(), "audio/mpeg")
        }
        val orchestrator = VoiceTurnOrchestrator(speech, conversations, inputGate = { _, _ -> AudioInputVerdict.USABLE },
            config = { VoiceTurnConfig("router_session", allowlist, ResponsePlaybackSettings()) },
            listener = object : VoiceTurnListener {}, recoveryClock = { clockScope.currentTime })
        val outcome = orchestrator.run(VoiceTurnRequest("turn-1", VoiceOrigin.PHONE, byteArrayOf(1, 2, 3), "audio/wav", sink, callerDeadlineMs = 30_000L))
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.NotDelivered)
        assertTrue("the caller's own deadline is never extended: ${clockScope.currentTime}", clockScope.currentTime <= 30_000L)
        assertTrue("attempts ${calls.get()}", calls.get() in 3..4)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `a single-attempt policy keeps a transport failure final`() = runTest {
        val calls = AtomicInteger()
        val speech = object : HermesSpeechGateway {
            override suspend fun transcribe(audio: ByteArray, mimeType: String): String {
                calls.incrementAndGet()
                throw HermesSttTransportException(SttTransport.DNS, java.net.UnknownHostException("h"))
            }
            override suspend fun speak(text: String) = SpokenAudio("A".toByteArray(), "audio/mpeg")
        }
        val orchestrator = VoiceTurnOrchestrator(speech, conversations, inputGate = { _, _ -> AudioInputVerdict.USABLE },
            config = { VoiceTurnConfig("router_session", allowlist, ResponsePlaybackSettings()) },
            listener = object : VoiceTurnListener {}, sttPolicy = SttRecoveryPolicy.NONE)
        val outcome = orchestrator.run(request())
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.NotDelivered)
        assertEquals(1, calls.get())
    }

    private companion object { const val DNS_NAME = "localhost" }
}
