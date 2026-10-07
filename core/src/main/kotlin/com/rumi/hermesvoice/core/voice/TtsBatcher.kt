package com.rumi.hermesvoice.core.voice

import com.rumi.hermesvoice.core.SpokenAudio
import com.rumi.hermesvoice.core.net.HermesSpeechGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * Splits a long reply into speech-synthesis requests that are each small enough to finish in seconds
 * (`/api/audio/speak` is one atomic JSON response, so a long text is only audible after all of it
 * was synthesized). Text up to [SINGLE_MAX] chars stays ONE request, exactly as before. Longer text
 * is cut at paragraph breaks, then sentence ends, then line breaks, then whitespace, and only as
 * a last resort inside a word, never inside a UTF-16 surrogate pair. The chunks concatenate back to
 * exactly the input: nothing is dropped, repeated, trimmed or reordered, and no chunk is blank.
 */
object TtsBatcher {
    const val SINGLE_MAX = 1_600
    const val TARGET = 1_000
    private const val MIN_CHUNK = 200

    fun split(text: String, target: Int = TARGET, max: Int = SINGLE_MAX): List<String> {
        require(target in 2..max) { "target must be within 2..max" }
        if (text.length <= max) return listOf(text)
        val chunks = ArrayList<String>()
        var start = 0
        while (text.length - start > max) {
            val cut = cutPoint(text, start, target, max)
            chunks += text.substring(start, cut)
            start = cut
        }
        chunks += text.substring(start)
        return mergeBlank(chunks)
    }

    private fun cutPoint(text: String, start: Int, target: Int, max: Int): Int {
        val minEnd = start + minOf(MIN_CHUNK, target / 2)
        // First within the target size, then up to the maximum.
        for (limit in listOf(start + target, start + max)) {
            val found = bestBoundary(text, minEnd, limit)
            if (found > 0) return found
        }
        var hard = start + target
        if (hard < text.length && Character.isLowSurrogate(text[hard]) && Character.isHighSurrogate(text[hard - 1])) hard -= 1
        return hard
    }

    /**
     * The cut (exclusive end of a chunk) of the best boundary in [from, limit], or -1: a cut is where
     * a word starts after whitespace (the whitespace stays with the chunk before it), or right after a
     * CJK sentence end. Paragraph breaks win over sentence ends and line breaks, those over a plain space.
     */
    private fun bestBoundary(text: String, from: Int, limit: Int): Int {
        var paragraph = -1
        var sentence = -1
        var space = -1
        val end = minOf(limit, text.length - 1)
        var i = maxOf(from, 1)
        while (i <= end) {
            val previous = text[i - 1]
            val current = text[i]
            if (!current.isWhitespace()) {
                if (previous.isWhitespace()) {
                    var runStart = i - 1
                    var newlines = 0
                    while (runStart >= 0 && text[runStart].isWhitespace()) {
                        if (text[runStart] == '\n') newlines += 1
                        runStart -= 1
                    }
                    when {
                        newlines >= 2 -> paragraph = i
                        runStart >= 0 && isSentenceEnd(text, runStart) -> sentence = i
                        newlines == 1 -> sentence = i
                        else -> space = i
                    }
                } else if (previous in "。！？") {
                    sentence = i
                }
            }
            i += 1
        }
        return when {
            paragraph > 0 -> paragraph
            sentence > 0 -> sentence
            else -> space
        }
    }

    /** Whether the last non-blank char at [index] (before whitespace) ends a sentence, optionally followed by closing quotes/brackets. */
    private fun isSentenceEnd(text: String, index: Int): Boolean {
        var k = index
        while (k > 0 && text[k] in CLOSERS) k -= 1
        return text[k] in ".!?…。！？"
    }

    private const val CLOSERS = "\"'”’)]」』"

    private fun mergeBlank(chunks: List<String>): List<String> {
        val merged = ArrayList<String>()
        for (chunk in chunks) {
            if (chunk.isBlank() && merged.isNotEmpty()) merged[merged.size - 1] = merged.last() + chunk else merged += chunk
        }
        if (merged.size > 1 && merged.first().isBlank()) {
            val first = merged.removeAt(0)
            merged[0] = first + merged[0]
        }
        return merged
    }
}

/**
 * One reply's speech as an ordered list of [TtsBatcher] chunks, synthesized one at a time with the
 * next chunk fetched while the current one plays (at most two audio clips are held, never the whole
 * reply). A chunk is synthesized once: a retry resumes at [next], the first chunk not confirmed
 * played, and never asks the gateway again for an audio it still holds or already played.
 *
 * No time limit is put on a synthesis request here: `/api/audio/speak` is atomic and reports no progress before
 * its bytes, so elapsed time alone cannot show that generation failed. A request ends with its audio, an error or
 * disconnect from the gateway, or by cancellation ([close], or the caller's own cancellation), which cancels the
 * underlying call.
 */
internal class ChunkedSpeech(
    private val speech: HermesSpeechGateway,
    val text: String,
    private val scope: CoroutineScope,
    private val onSynthesized: (index: Int, count: Int, chars: Int, ms: Long, bytes: Int, failure: String?) -> Unit = { _, _, _, _, _, _ -> },
) {
    val chunks: List<String> = TtsBatcher.split(text)
    val size: Int get() = chunks.size

    /** The first chunk not confirmed played yet. */
    @Volatile var next = 0
        private set

    private val ready = HashMap<Int, Deferred<Result<SpokenAudio>>>()
    private val live = HashSet<Deferred<Result<SpokenAudio>>>()
    private var heldIndex = -1
    private var held: SpokenAudio? = null

    /** The audio of chunk [next]; synthesized once and kept until [played]. Fetching it starts the next chunk's synthesis. */
    suspend fun current(): SpokenAudio {
        val index = next
        if (heldIndex == index) return held!!
        // Left in [ready] while awaited: a cancelled wait resumes on the same request instead of asking again.
        val pending = synchronized(ready) { ready.getOrPut(index) { synthesize(index) } }
        val result = pending.await()
        synchronized(ready) { if (ready[index] === pending) ready.remove(index) }
        val audio = result.getOrThrow()
        heldIndex = index
        held = audio
        if (index + 1 < chunks.size) synchronized(ready) { if (index + 1 !in ready) ready[index + 1] = synthesize(index + 1) }
        return audio
    }

    /** Chunk [index] was confirmed played to the end: it is never played, nor kept, again. */
    fun played(index: Int) {
        if (index + 1 > next) next = index + 1
        if (heldIndex <= index) {
            held = null
            heldIndex = -1
        }
    }

    /** Cancels every synthesis still in flight, the awaited one included. */
    fun close() {
        synchronized(ready) { ready.clear() }
        val running = synchronized(live) { live.toList() }
        running.forEach { it.cancel() }
        held = null
    }

    private fun synthesize(index: Int): Deferred<Result<SpokenAudio>> = scope.async {
        val chunk = chunks[index]
        val started = System.nanoTime()
        fun ms() = (System.nanoTime() - started) / 1_000_000
        try {
            val audio = speech.speak(chunk)
            onSynthesized(index, chunks.size, chunk.length, ms(), audio.bytes.size, null)
            Result.success(audio)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onSynthesized(index, chunks.size, chunk.length, ms(), 0, error.javaClass.simpleName)
            Result.failure(error)
        }
    }.also { job ->
        synchronized(live) { live += job }
        job.invokeOnCompletion { synchronized(live) { live -= job } }
    }
}
