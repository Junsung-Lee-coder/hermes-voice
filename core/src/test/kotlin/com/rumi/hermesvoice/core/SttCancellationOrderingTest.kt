package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import com.rumi.hermesvoice.core.diag.DiagLog
import com.rumi.hermesvoice.core.diag.DiagVoiceListener
import com.rumi.hermesvoice.core.net.*
import com.rumi.hermesvoice.core.auth.*
import com.rumi.hermesvoice.core.settings.*
import com.rumi.hermesvoice.core.voice.*
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Public-client schedules, not an assertion about the unrecorded h94f interleaving. */
class SttCancellationOrderingTest {
    private class Setup(message: String) : IllegalStateException(message)
    private fun await(latch: CountDownLatch, what: String) {
        if (!latch.await(10, TimeUnit.SECONDS)) throw Setup("missing $what")
    }

    /**
     * Pause the dispatch of an already exceptionally resumed await, not a cancellation handler.
     * The await scope has the live cancellation-watcher child; the watcher itself has no child.
     * This permits a genuine IOException-before-Stop schedule without replacing Call/await/client.
     */
    private class FailureDispatchGate : ThreadContextElement<Unit>, AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<FailureDispatchGate>
        val armed = AtomicBoolean()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val boundExpired = AtomicBoolean()
        val resumedJob = AtomicReference<Job?>()
        override fun updateThreadContext(context: CoroutineContext) {
            val job = context[Job]
            if (job != null && job.children.any() && armed.compareAndSet(true, false)) {
                resumedJob.set(job)
                entered.countDown()
                if (!release.await(10, TimeUnit.SECONDS)) boundExpired.set(true)
            }
        }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Unit) {}
    }

    private class Fixture(private val gate: FailureDispatchGate? = null, secondReset: Boolean = true) : AutoCloseable {
        val fake = FakeHermesDashboard()
        val uploads = AtomicInteger()
        val calls = AtomicInteger()
        val submitted = AtomicInteger()
        val delivered = AtomicInteger()
        val events: MutableList<SttRecoveryEvent> = Collections.synchronizedList(mutableListOf())
        val call = AtomicReference<Call?>()
        val speechError = AtomicReference<Throwable?>()
        val errorWhileActive = AtomicReference<Boolean?>()
        val secondFailed = CountDownLatch(1)
        val secondUploaded = CountDownLatch(1)
        var eventHook: (SttRecoveryEvent) -> Unit = {}
        val http = OkHttpClient.Builder().retryOnConnectionFailure(false)
            .readTimeout(20_000, TimeUnit.MILLISECONDS).callTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain -> call.set(chain.call()); calls.incrementAndGet(); chain.proceed(chain.request()) }
            .eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: IOException) {
                    if (calls.get() == 2) {
                        gate?.armed?.set(true)
                        secondFailed.countDown()
                    }
                }
            }).build()
        val client = HermesDashboardClient(HermesDashboardEndpoint.parse("http://127.0.0.1:${fake.server.port}/"), http,
            InMemoryHermesTokenStore().apply { save(HermesBearerSession(fake.accessToken, fake.refreshToken, null, "basic", "jun")) })
        private val speech = object : HermesSpeechGateway by client {
            override suspend fun transcribe(audio: ByteArray, mimeType: String): String = try {
                client.transcribe(audio, mimeType)
            } catch (error: Throwable) {
                speechError.set(error)
                errorWhileActive.set(currentCoroutineContext().isActive)
                throw error
            }
        }
        val orchestrator = VoiceTurnOrchestrator(speech, object : HermesConversationPort {
            override suspend fun create(source: String, title: String, seedInstruction: String, hidden: Boolean): CreatedSession = throw Setup("unexpected create")
            override suspend fun submit(storedSessionId: String, text: String, attachments: List<OutgoingAttachment>): SubmittedTurn {
                submitted.incrementAndGet()
                throw Setup("unexpected submit")
            }
        }, config = { VoiceTurnConfig("router_session", DestinationAllowlist.create(listOf(DestinationEntry("work", "work_session")), "router_session"), ResponsePlaybackSettings()) },
            inputGate = { _, _ -> AudioInputVerdict.USABLE },
            listener = DiagVoiceListener(object : VoiceTurnListener {
                override fun onSttRecovery(turnId: String, event: SttRecoveryEvent) { events += event; eventHook(event) }
                override fun onDelivered(route: AssembledRoute) { delivered.incrementAndGet() }
            }, DiagLog(clock = { System.nanoTime() / 1_000_000 })),
            sttPolicy = SttRecoveryPolicy(budgetMs = 12_000, maxAttempts = 4, backoffMs = listOf(1_000, 1_000, 1_000)))
        val request = VoiceTurnRequest("ordering", VoiceOrigin.PHONE, byteArrayOf(1, 2, 3, 4, 5), "audio/wav", PlaybackSink { _, _ -> })
        init {
            fake.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path?.substringBefore('?') != "/api/audio/transcribe") throw Setup("unexpected request path")
                    val n = uploads.incrementAndGet()
                    if (n == 2) secondUploaded.countDown()
                    return MockResponse().setSocketPolicy(if (n == 1 || secondReset) SocketPolicy.DISCONNECT_AFTER_REQUEST else SocketPolicy.NO_RESPONSE)
                }
            }
        }
        fun snapshot() = synchronized(events) { events.toList() }
        suspend fun assertStopped(run: Deferred<VoiceTurnOutcome>) {
            assertEquals(VoiceTurnOutcome.Stopped, withTimeout(10_000) { run.await() })
            val end = System.nanoTime() + 5_000_000_000L
            while (http.dispatcher.runningCallsCount() != 0) {
                if (System.nanoTime() > end) throw Setup("call did not close")
                delay(10)
            }
            delay(1_500)
            assertEquals(2, snapshot().count { it.phase == SttPhase.ATTEMPT_STARTED })
            assertEquals(2, uploads.get())
            assertEquals(0, submitted.get())
            assertEquals(0, delivered.get())
            assertTrue(orchestrator.pending.value.isEmpty())
            assertTrue(call.get()!!.isCanceled())
        }
        override fun close() {
            gate?.release?.countDown()
            fake.close()
            http.dispatcher.executorService.shutdown()
        }
    }

    @Test fun R09F_stop_before_exception_dispatch_is_cancellation_at_the_real_client_and_orchestrator() = runBlocking {
        val gate = FailureDispatchGate()
        Fixture(gate).use { f ->
            val run = async(Dispatchers.Default + gate) { f.orchestrator.run(f.request) }
            try {
                await(f.secondUploaded, "second real upload")
                await(f.secondFailed, "second real call failure")
                await(gate.entered, "exceptional continuation dispatch")
                assertTrue("the target scope is active before Stop", gate.resumedJob.get()!!.isActive)
                assertTrue(f.orchestrator.stopTurn("ordering"))
                assertFalse("Stop cancelled the exact paused scope", gate.resumedJob.get()!!.isActive)
                gate.release.countDown()
                f.assertStopped(run)
                assertFalse("dispatch gate bound expired", gate.boundExpired.get())
                assertEquals(false, f.errorWhileActive.get())
                val events = f.snapshot()
                println("R09F_EXCEPTION_DISPATCH error=${f.speechError.get()?.javaClass?.simpleName} active=${f.errorWhileActive.get()} events=${events.map { Triple(it.phase, it.attempt, it.waiting) }}")
                assertTrue("cancelled public client must propagate cancellation, not ${f.speechError.get()}", f.speechError.get() is CancellationException)
                assertTrue(events.any { it.phase == SttPhase.CANCELLED && it.attempt == 2 && !it.waiting })
                assertFalse(events.any { it.phase == SttPhase.ATTEMPT_FAILED && it.attempt == 2 })
            } finally {
                gate.release.countDown()
                run.cancelAndJoin()
            }
        }
    }

    @Test fun R09F_cumulative_uploads_and_attempts_are_not_a_live_call_precondition() = runBlocking {
        Fixture().use { f ->
            val backoff = CountDownLatch(1)
            val release = CountDownLatch(1)
            val expired = AtomicBoolean()
            f.eventHook = { event ->
                if (event.phase == SttPhase.WAIT_STARTED && event.attempt == 3) {
                    backoff.countDown()
                    if (!release.await(10, TimeUnit.SECONDS)) expired.set(true)
                }
            }
            val run = async(Dispatchers.Default) { f.orchestrator.run(f.request) }
            try {
                await(backoff, "legitimate backoff after second network failure")
                assertTrue(f.snapshot().count { it.phase == SttPhase.ATTEMPT_STARTED } == 2 && f.uploads.get() >= 2)
                assertTrue("the failed second call is no longer active", f.call.get()!!.isExecuted())
                assertTrue(f.orchestrator.stopTurn("ordering"))
                release.countDown()
                f.assertStopped(run)
                assertFalse(expired.get())
                val events = f.snapshot()
                println("R09F_CUMULATIVE_PREDICATE events=${events.map { Triple(it.phase, it.attempt, it.waiting) }}")
                assertTrue(events.any { it.phase == SttPhase.CANCELLED && it.waiting })
                assertFalse(events.any { it.phase == SttPhase.CANCELLED && !it.waiting })
            } finally {
                release.countDown()
                run.cancelAndJoin()
            }
        }
    }

    @Test fun R09F_Stop_while_the_real_second_call_is_live_is_cancellation() = runBlocking {
        Fixture(secondReset = false).use { f ->
            val run = async(Dispatchers.Default) { f.orchestrator.run(f.request) }
            try {
                await(f.secondUploaded, "second silent upload")
                assertEquals(1, f.http.dispatcher.runningCallsCount())
                assertFalse(f.call.get()!!.isCanceled())
                assertTrue(f.orchestrator.stopTurn("ordering"))
                f.assertStopped(run)
                assertTrue(f.speechError.get() is CancellationException)
                assertTrue(f.snapshot().any { it.phase == SttPhase.CANCELLED && !it.waiting })
            } finally { run.cancelAndJoin() }
        }
    }
}
