package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.net.HermesSpeechGateway
import com.rumi.hermesvoice.core.voice.ChunkedSpeech
import com.rumi.hermesvoice.core.voice.TtsBatcher
import java.util.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sentence- and size-aware batching of a long reply into speech requests, and the ordered, bounded synthesis of those chunks. */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsBatcherTest {
    private fun assertSurrogateSafe(chunks: List<String>) {
        for (chunk in chunks) {
            assertFalse("chunk starts inside a pair", chunk.isNotEmpty() && Character.isLowSurrogate(chunk.first()))
            assertFalse("chunk ends inside a pair", chunk.isNotEmpty() && Character.isHighSurrogate(chunk.last()))
        }
    }

    @Test
    fun `short text stays one request exactly as it was`() {
        for (text in listOf("", "Hi", "x".repeat(TtsBatcher.SINGLE_MAX))) assertEquals(listOf(text), TtsBatcher.split(text))
    }

    @Test
    fun `chunks always concatenate back to the input and respect the size bound`() {
        val random = Random(42)
        val alphabet = listOf("word", "a", "Hello.", "Really?", "Yes!", "\n", "\n\n", " ", "  ", "한국어 문장입니다。", "😀", "é", "https://example.com/very/long/path", "\t")
        repeat(300) {
            val text = buildString { repeat(50 + random.nextInt(2_500)) { append(alphabet[random.nextInt(alphabet.size)]); if (random.nextBoolean()) append(' ') } }
            val chunks = TtsBatcher.split(text)
            assertEquals("no loss, no duplicate, no reorder", text, chunks.joinToString(""))
            assertTrue(chunks.all { it.length <= TtsBatcher.SINGLE_MAX })
            assertSurrogateSafe(chunks)
            if (chunks.size > 1) assertTrue("no blank chunk", chunks.none { it.isBlank() })
        }
    }

    @Test
    fun `a long reply is cut at paragraph and sentence ends before anything else`() {
        val sentence = "This is one fairly ordinary sentence of reasonable length. "
        val paragraph = sentence.repeat(8).trim() + "\n\n"
        val text = paragraph.repeat(10)
        val chunks = TtsBatcher.split(text)
        assertTrue(chunks.size > 1)
        assertEquals(text, chunks.joinToString(""))
        for (chunk in chunks.dropLast(1)) assertTrue("ends at a paragraph break: '${chunk.takeLast(12)}'", chunk.endsWith("\n\n"))
        val prose = sentence.repeat(120)
        for (chunk in TtsBatcher.split(prose).dropLast(1)) assertTrue(chunk.trimEnd().endsWith("."))
    }

    @Test
    fun `text with no boundary at all is cut by size, never inside a surrogate pair, and still loses nothing`() {
        for (unit in listOf("a", "😀", "한", "😀a")) {
            val text = unit.repeat(5_000 / unit.length)
            val chunks = TtsBatcher.split(text)
            assertEquals(text, chunks.joinToString(""))
            assertTrue(chunks.all { it.length <= TtsBatcher.SINGLE_MAX })
            assertSurrogateSafe(chunks)
        }
        for (offset in 0..3) {
            val text = "x".repeat(TtsBatcher.TARGET - 2 + offset) + "😀".repeat(2_000)
            assertSurrogateSafe(TtsBatcher.split(text))
            assertEquals(text, TtsBatcher.split(text).joinToString(""))
        }
    }

    @Test
    fun `whitespace-only stretches never become their own request`() {
        val text = "Start. " + " ".repeat(3_000) + "End."
        val chunks = TtsBatcher.split(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.none { it.isBlank() })
    }

    // ---- ChunkedSpeech

    private class Gateway(val delayMs: Long = 0, val fail: Set<Int> = emptySet()) : HermesSpeechGateway {
        val spoken = java.util.Collections.synchronizedList(mutableListOf<String>())
        override suspend fun transcribe(audio: ByteArray, mimeType: String) = error("not used")
        override suspend fun speak(text: String): SpokenAudio {
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            spoken += text
            if (spoken.size in fail) throw HermesProtocolException("nope")
            return SpokenAudio(text.toByteArray(), "audio/mpeg")
        }
    }

    private val longText = ("A sentence that is long enough to matter here. ").repeat(200)

    @Test
    fun `chunks are synthesized in order, the next is prefetched, and at most two clips are held`() = runTest {
        val gateway = Gateway()
        val speech = ChunkedSpeech(gateway, longText, this)
        assertTrue(speech.size > 1)
        val played = StringBuilder()
        for (i in 0 until speech.size) {
            val audio = speech.current()
            played.append(String(audio.bytes))
            assertEquals("a retry of the same piece does not ask the gateway again", audio, speech.current())
            speech.played(i)
            runCurrent()
            assertTrue("never more than the current and the next requested ahead", gateway.spoken.size <= i + 2)
        }
        assertEquals(longText, played.toString())
        assertEquals("each chunk synthesized exactly once", speech.size, gateway.spoken.size)
        speech.close()
    }

    @Test
    fun `a failed piece surfaces and a later retry resumes at the first unplayed piece without repeating played ones`() = runTest {
        val gateway = Gateway(fail = setOf(2))
        val speech = ChunkedSpeech(gateway, longText, this)
        speech.current(); speech.played(0)
        var failure: Throwable? = null
        try { speech.current() } catch (e: HermesProtocolException) { failure = e }
        assertTrue(failure != null)
        assertEquals("resumes at the unplayed piece", 1, speech.next)
        speech.close()
    }

    // Timeout-contract migration (v19 self-check R1): the first-v19 test here also asserted that ONE pending piece
    // fails with HermesProtocolException after exactly STALL_MS (10 min). That expiry was the false total
    // synthesis cutoff itself and is intentionally removed, not relabelled: its replacement is
    // SynthesisNoCutoffTest (a single healthy piece beyond 10 min, pending until Stop, then prompt cancellation).
    @Test
    fun `a healthy long reply is never cut by a total time`() = runTest {
        val slow = Gateway(delayMs = 4 * 60_000)
        val speech = ChunkedSpeech(slow, longText, this)
        var total = 0L
        for (i in 0 until speech.size) { speech.current(); speech.played(i); total = currentTime }
        assertTrue("$total ms in total is far beyond one piece's former bound", total > 10 * 60_000)
        speech.close()
    }

    @Test
    fun `closing cancels synthesis in flight`() = runTest {
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val gateway = object : HermesSpeechGateway {
            override suspend fun transcribe(audio: ByteArray, mimeType: String) = error("not used")
            override suspend fun speak(text: String): SpokenAudio {
                started.complete(Unit)
                try { awaitCancellation() } catch (e: kotlinx.coroutines.CancellationException) { cancelled = true; throw e }
            }
        }
        val speech = ChunkedSpeech(gateway, longText, this)
        val current = async { runCatching { speech.current() } }
        started.await()
        speech.close()
        runCurrent()
        assertTrue(cancelled)
        current.cancel()
    }
}
