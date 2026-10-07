package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.HermesBearerSession
import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.auth.InMemoryHermesTokenStore
import com.rumi.hermesvoice.core.net.HermesDashboardClient
import com.rumi.hermesvoice.core.net.HermesSpeechGateway
import com.rumi.hermesvoice.core.voice.ChunkedSpeech
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v19 self-check R1, B1: `/api/audio/speak` is atomic and shows no progress before its bytes, so synthesis has NO total
 * time cut (it was a 10 minute `withTimeoutOrNull` around the whole request). A request ends with its audio, an error or
 * disconnect, or cancellation. The old expiry assertion was removed (see TtsBatcherTest), not relabelled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SynthesisNoCutoffTest {
    private val former = 10 * 60_000L
    private val longText = (1..200).joinToString("") { "Sentence number $it is long enough to matter here. " }

    private class Gateway(val delayMs: Long, val error: ((Int) -> Exception?)? = null) : HermesSpeechGateway {
        val spoken = java.util.Collections.synchronizedList(mutableListOf<String>())
        val started = AtomicInteger()
        override suspend fun transcribe(audio: ByteArray, mimeType: String) = error("not used")
        override suspend fun speak(text: String): SpokenAudio {
            val n = started.incrementAndGet()
            delay(delayMs)
            error?.invoke(n)?.let { throw it }
            spoken += text
            return SpokenAudio("AUDIO:$text".toByteArray(), "audio/mpeg")
        }
    }

    private class Pending : HermesSpeechGateway {
        val started = AtomicInteger()
        val cancelled = AtomicInteger()
        override suspend fun transcribe(audio: ByteArray, mimeType: String) = error("not used")
        override suspend fun speak(text: String): SpokenAudio {
            started.incrementAndGet()
            try {
                awaitCancellation()
            } catch (stopped: CancellationException) {
                cancelled.incrementAndGet()
                throw stopped
            }
        }
    }

    @Test
    fun `one healthy single chunk delayed beyond the former 10 minute bound is delivered intact`() = runTest {
        val gateway = Gateway(delayMs = former + 60_000)
        val speech = ChunkedSpeech(gateway, "A short answer.", this)
        assertEquals(1, speech.size)
        val result = async { runCatching { speech.current() } }
        advanceTimeBy(former + 59_999); runCurrent()
        assertFalse("still generating, not declared stalled", result.isCompleted)
        advanceTimeBy(1); runCurrent()
        val audio = result.await()
        assertTrue("the former total cut must not end a healthy synthesis: ${audio.exceptionOrNull()}", audio.isSuccess)
        assertEquals("A short answer.", String(audio.getOrThrow().bytes).removePrefix("AUDIO:"))
        assertEquals(former + 60_000, currentTime)
        assertEquals(1, gateway.started.get())
        speech.close()
    }

    @Test
    fun `a long reply far beyond every former boundary arrives with exact content and order`() = runTest {
        val gateway = Gateway(delayMs = 9 * 60_000)
        val speech = ChunkedSpeech(gateway, longText, this)
        assertTrue(speech.size > 5)
        val played = StringBuilder()
        for (i in 0 until speech.size) {
            val audio = speech.current()
            played.append(String(audio.bytes).removePrefix("AUDIO:"))
            speech.played(i)
        }
        assertEquals("no loss, no duplicate, no reorder", longText, played.toString())
        assertEquals(speech.size, gateway.started.get())
        assertTrue("the whole reply took ${currentTime / 60_000} min, far beyond the former 10 min total", currentTime > 6 * former)
        speech.close()
    }

    @Test
    fun `an atomic generation with no progress stays pending for a day and is cancelled promptly, once, by Stop`() = runTest {
        val gateway = Pending()
        val speech = ChunkedSpeech(gateway, longText, this)
        val result = async { runCatching { speech.current() } }
        advanceTimeBy(24 * 60 * 60_000L); runCurrent()
        assertFalse("no elapsed time alone ends it: ${result.isCompleted}", result.isCompleted)
        assertEquals(1, gateway.started.get())
        assertEquals(0, gateway.cancelled.get())
        val stoppedAt = currentTime
        speech.close()
        runCurrent()
        assertEquals("the request was cancelled exactly once", 1, gateway.cancelled.get())
        assertEquals("promptly, at the same instant", stoppedAt, currentTime)
        result.cancel()
        speech.close()
        runCurrent()
        assertEquals("closing twice does not cancel again", 1, gateway.cancelled.get())
    }

    @Test
    fun `closing after the first chunk was delivered cancels the prefetched next synthesis once and nothing else`() = runTest {
        val calls = AtomicInteger()
        val cancelled = AtomicInteger()
        val gateway = object : HermesSpeechGateway {
            override suspend fun transcribe(audio: ByteArray, mimeType: String) = error("not used")
            override suspend fun speak(text: String): SpokenAudio {
                if (calls.incrementAndGet() == 1) return SpokenAudio("AUDIO:$text".toByteArray(), "audio/mpeg")
                try {
                    awaitCancellation()
                } catch (stopped: CancellationException) {
                    cancelled.incrementAndGet()
                    throw stopped
                }
            }
        }
        val speech = ChunkedSpeech(gateway, longText, this)
        speech.current()
        speech.played(0)
        val second = async { runCatching { speech.current() } }
        advanceTimeBy(3 * former); runCurrent()
        assertFalse(second.isCompleted)
        assertEquals("the first chunk is not asked for again", 2, calls.get())
        speech.close()
        runCurrent()
        assertEquals(1, cancelled.get())
        assertEquals("a cancelled prefetch is not retried by closing", 2, calls.get())
        second.cancel()
    }

    @Test
    fun `a true error after a long wait surfaces as that error, not as a timeout, and resumes at the unplayed chunk`() = runTest {
        val gateway = Gateway(delayMs = former + 120_000) { n -> if (n == 2) IOException("connection reset") else null }
        val speech = ChunkedSpeech(gateway, longText, this)
        val first = runCatching { speech.current() }
        assertTrue("a healthy first chunk beyond the former bound is delivered: ${first.exceptionOrNull()}", first.isSuccess)
        speech.played(0)
        val failure = runCatching { speech.current() }.exceptionOrNull()
        assertTrue("the real error, not a stall: $failure", failure is IOException && failure.message == "connection reset")
        assertEquals(1, speech.next)
        speech.close()
    }

    @Test
    fun `a caller that stops waiting does not lose the request and a later wait resumes on the same synthesis`() = runTest {
        val gateway = Gateway(delayMs = 2 * former)
        val speech = ChunkedSpeech(gateway, "Resumed answer.", this)
        val first = async { speech.current() }
        advanceTimeBy(former); runCurrent()
        first.cancel()
        runCurrent()
        val second = async { runCatching { speech.current() } }
        advanceTimeBy(former); runCurrent()
        assertTrue(second.await().isSuccess)
        assertEquals("one request in total", 1, gateway.started.get())
        speech.close()
    }

    // ---- the production HermesDashboardClient over real loopback sockets (no live endpoint)

    private val fake = FakeHermesDashboard()
    private val http = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).callTimeout(600, TimeUnit.MILLISECONDS).build()
    private val tokens = InMemoryHermesTokenStore(HermesBearerSession(fake.accessToken, fake.refreshToken, null, "basic", "jun"))
    private val client = HermesDashboardClient(HermesDashboardEndpoint.parse(fake.baseUrl), http, tokens)

    @After fun tearDown() = fake.close()

    private fun waitCalls(idle: Boolean) = runBlocking {
        withTimeout(5_000) { while ((http.dispatcher.runningCallsCount() == 0) == !idle) delay(10) }
    }

    @Test
    fun `a body received progressively over several times the injected call deadline completes with exact audio`() = runBlocking {
        fake.speakPadding = 3_000
        fake.speakThrottleBytes = 512
        fake.speakThrottleMs = 150
        val started = System.nanoTime()
        val audio = client.speak("Progressive answer.")
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("Progressive answer.", fake.decodeSpoken(audio))
        assertTrue("received over $tookMs ms, beyond the 600 ms call limit the ordinary client has", tookMs > 800)
    }

    @Test
    fun `cancelling while the body is still arriving cancels the real call promptly instead of reading on`() = runBlocking {
        fake.speakPadding = 6_000
        fake.speakThrottleBytes = 128
        fake.speakThrottleMs = 200
        val job = async(kotlinx.coroutines.Dispatchers.Default) { runCatching { client.speak("Never finished.") } }
        delay(700)
        assertTrue("the body is still arriving (the full body needs about 9 s)", job.isActive)
        val started = System.nanoTime()
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertTrue("ended long before the body would have: ${(System.nanoTime() - started) / 1_000_000} ms", (System.nanoTime() - started) / 1_000_000 < 3_000)
        assertEquals(0, http.dispatcher.runningCallsCount())
    }

    @Test
    fun `closing the chunked speech cancels the real call of its in-flight synthesis`() = runBlocking {
        fake.speakDelayMs = 4_000
        val speech = ChunkedSpeech(client, longText, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default))
        val waiting = async { runCatching { speech.current() } }
        waitCalls(idle = false)
        speech.close()
        waitCalls(idle = true)
        assertEquals(0, http.dispatcher.runningCallsCount())
        waiting.cancel()
    }

    @Test
    fun `a connection cut in the middle of the body is a real error, not a cut by time`() = runBlocking {
        fake.speakPadding = 3_000
        fake.speakDisconnectMidBody = true
        val failure = runCatching { client.speak("Cut off.") }.exceptionOrNull()
        assertTrue("an I/O failure from the cut connection: $failure", failure is IOException)
        assertEquals(0, http.dispatcher.runningCallsCount())
    }

    @Test
    fun `a rejected token during a slow synthesis refreshes once and every chunk of a long reply arrives in order`() = runBlocking {
        fake.rejectAccessTokens = setOf("AT-1")
        fake.speakDelayMs = 700
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val speech = ChunkedSpeech(client, longText, scope)
        val played = StringBuilder()
        withTimeout(60_000) {
            for (i in 0 until speech.size) {
                played.append(fake.decodeSpoken(speech.current()))
                speech.played(i)
            }
        }
        // The production client trims each request text, so only the whitespace at the cut points differs.
        assertEquals(longText.filterNot { it.isWhitespace() }, played.toString().filterNot { it.isWhitespace() })
        assertEquals("one coalesced refresh, never one per chunk", 1, fake.refreshes)
        speech.close()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        Unit
    }
}
