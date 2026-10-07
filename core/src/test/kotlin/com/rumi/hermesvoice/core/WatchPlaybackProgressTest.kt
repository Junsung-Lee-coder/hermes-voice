package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.voice.PlaybackCue
import com.rumi.hermesvoice.core.watchlink.PlayedAck
import com.rumi.hermesvoice.core.watchlink.WatchAckRegistry
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchPlaybackSink
import com.rumi.hermesvoice.core.watchlink.WatchTransport
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v19 self-check R1, B2: the Phone does not guess a clip's duration from its byte size. A Watch that reports its REAL player
 * position (`/hv/v1/play_progress`: turn, sequence, position, duration) keeps the wait alive exactly while the position
 * strictly advances; every other message (heartbeat at a standing position, stale, duplicate, out of range, conflicting
 * duration, wrong node/turn/sequence) is ignored and never extends the wait. Virtual time through the production
 * [WatchPlaybackSink] and [WatchAckRegistry]; no real Watch or player is involved (that stays NOT_RUN).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchPlaybackProgressTest {
    private class Transport : WatchTransport {
        override val nodeId = "watch-node-a"
        val channels: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val messages: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var failChannel: Exception? = null
        override suspend fun sendMessage(path: String, bytes: ByteArray) {
            messages += path
        }
        override suspend fun sendChannel(path: String, bytes: ByteArray) {
            failChannel?.let { throw it }
            channels += path
        }
    }

    private val node = "watch-node-a"
    private fun progress(turn: String, seq: Int, pos: Long, dur: Long) =
        JSONObject().put("turn_id", turn).put("seq", seq).put("pos", pos).put("dur", dur).toString().toByteArray()

    private fun cue(turn: String, sequence: Int = 1, part: Int = 0) =
        PlaybackCue(turn, VoiceOrigin.WATCH, SpokenRole.FINAL, sequence, "x", part = part)

    /** A tiny valid compressed clip: under 100 bytes, so the legacy size guess allows only about 30 s. */
    private val tiny = SpokenAudio(ByteArray(100), "audio/ogg")

    private class Play(val job: kotlinx.coroutines.Job) {
        @Volatile var failure: Throwable? = null
        @Volatile var done = false
        val finished = AtomicInteger()
    }

    private fun TestScope.start(sink: WatchPlaybackSink, audio: SpokenAudio, cue: PlaybackCue): Play {
        lateinit var play: Play
        val job = launch {
            try {
                sink.playConfirmed(audio, cue) { play.finished.incrementAndGet() }
                play.done = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                play.failure = error
            }
        }
        play = Play(job)
        runCurrent()
        return play
    }

    /** Reports the real position every 5 s for [totalMs] of a clip that lasts [durationMs]; the wait must stay open. */
    private suspend fun TestScope.advancePlaying(acks: WatchAckRegistry, play: Play, turn: String, seq: Int, fromMs: Long, totalMs: Long, durationMs: Long) {
        var position = fromMs
        while (position + 5_000 <= fromMs + totalMs) {
            advanceTimeBy(5_000)
            position += 5_000
            acks.onProgressMessage(node, progress(turn, seq, position, durationMs))
            runCurrent()
            assertNull("alive at position $position ms: ${play.failure}", play.failure)
            assertFalse(play.done)
        }
    }

    @Test
    fun `a tiny valid compressed clip that really plays for 20 minutes is waited for while its position advances`() = runTest {
        val acks = WatchAckRegistry()
        val transport = Transport()
        val sink = WatchPlaybackSink(transport, acks)
        val play = start(sink, tiny, cue("turn-0001"))
        assertEquals(listOf(WatchLinkPaths.playPath("turn-0001", 1)), transport.channels.toList())
        // 100 bytes would have been given up on after 30 050 ms by the byte-size guess.
        advancePlaying(acks, play, "turn-0001", 1, 0, 20 * 60_000L, 20 * 60_000L)
        assertTrue(acks.onPlayedMessage(node, PlayedAck("turn-0001", 1, true).encode()))
        runCurrent()
        assertTrue(play.done)
        assertNull(play.failure)
        assertEquals("the end was confirmed exactly once", 1, play.finished.get())
    }

    @Test
    fun `a clip of any real length keeps going as long as the position advances, with the first progress arriving late`() = runTest {
        val acks = WatchAckRegistry()
        val sink = WatchPlaybackSink(Transport(), acks)
        val play = start(sink, tiny, cue("turn-0002"))
        // Transfer and preparing take most of the legacy allowance; the first real position then starts the progress window.
        advanceTimeBy(29_000); runCurrent()
        assertTrue("the first position is accepted", acks.onProgressMessage(node, progress("turn-0002", 1, 1_000, 3 * 60 * 60_000L)))
        advancePlaying(acks, play, "turn-0002", 1, 1_000, 3 * 60 * 60_000L - 1_000, 3 * 60 * 60_000L)
        assertTrue(acks.onPlayedMessage(node, PlayedAck("turn-0002", 1, true).encode()))
        runCurrent()
        assertTrue(play.done)
    }

    @Test
    fun `a playback whose progress stops is given up on 30 seconds after its last advance, and nothing late revives it`() = runTest {
        val acks = WatchAckRegistry()
        val transport = Transport()
        val sink = WatchPlaybackSink(transport, acks)
        val play = start(sink, tiny, cue("turn-0003"))
        advancePlaying(acks, play, "turn-0003", 1, 0, 15_000, 600_000)
        advanceTimeBy(29_999); runCurrent()
        assertNull("still inside the 30 s since the last advance", play.failure)
        advanceTimeBy(2); runCurrent()
        assertTrue("dead playback: ${play.failure}", play.failure is HermesPlaybackException)
        assertFalse("a late position is ignored", acks.onProgressMessage(node, progress("turn-0003", 1, 20_000, 600_000)))
        assertFalse("a late ack is ignored", acks.onPlayedMessage(node, PlayedAck("turn-0003", 1, true).encode()))
        assertEquals("the confirmation never ran", 0, play.finished.get())
    }

    @Test
    fun `heartbeats at a standing or backward position never keep a dead player alive`() = runTest {
        val acks = WatchAckRegistry()
        val sink = WatchPlaybackSink(Transport(), acks)
        val play = start(sink, tiny, cue("turn-0004"))
        advanceTimeBy(1_000)
        assertTrue(acks.onProgressMessage(node, progress("turn-0004", 1, 10_000, 600_000)))
        // The player froze at 10 s; a heartbeat repeats that position (and a stale lower one) every 5 s.
        repeat(5) { i ->
            advanceTimeBy(5_000)
            assertFalse("a repeated position is not progress", acks.onProgressMessage(node, progress("turn-0004", 1, 10_000, 600_000)))
            assertFalse("a stale lower position is not progress", acks.onProgressMessage(node, progress("turn-0004", 1, 9_000L - i, 600_000)))
            runCurrent()
        }
        advanceTimeBy(6_000); runCurrent()
        assertTrue("given up although heartbeats kept arriving: ${play.failure}", play.failure is HermesPlaybackException)
    }

    @Test
    fun `wrong node, turn, sequence, out of range and conflicting duration are rejected and extend nothing`() = runTest {
        val acks = WatchAckRegistry()
        val sink = WatchPlaybackSink(Transport(), acks)
        val play = start(sink, tiny, cue("turn-0005", sequence = 2))
        advanceTimeBy(1_000)
        assertTrue(acks.onProgressMessage(node, progress("turn-0005", 2, 1_000, 60_000)))
        assertFalse("another node", acks.onProgressMessage("watch-node-b", progress("turn-0005", 2, 2_000, 60_000)))
        assertFalse("another turn", acks.onProgressMessage(node, progress("turn-0006", 2, 2_000, 60_000)))
        assertFalse("another sequence", acks.onProgressMessage(node, progress("turn-0005", 3, 2_000, 60_000)))
        assertFalse("position beyond the duration", acks.onProgressMessage(node, progress("turn-0005", 2, 70_000, 60_000)))
        assertFalse("negative position", acks.onProgressMessage(node, progress("turn-0005", 2, -1, 60_000)))
        assertFalse("zero duration", acks.onProgressMessage(node, progress("turn-0005", 2, 0, 0)))
        assertFalse("a different duration than before", acks.onProgressMessage(node, progress("turn-0005", 2, 3_000, 90_000)))
        assertFalse("garbage", acks.onProgressMessage(node, "not json".toByteArray()))
        assertFalse("oversized", acks.onProgressMessage(node, ByteArray(600) { '{'.code.toByte() }))
        assertFalse("missing fields", acks.onProgressMessage(node, """{"turn_id":"turn-0005","seq":2}""".toByteArray()))
        advanceTimeBy(30_001); runCurrent()
        assertTrue("none of that extended the wait: ${play.failure}", play.failure is HermesPlaybackException)
    }

    @Test
    fun `progress for a clip nobody waits for, or after its ack, is ignored`() = runTest {
        val acks = WatchAckRegistry()
        assertFalse(acks.onProgressMessage(node, progress("turn-0007", 1, 1_000, 5_000)))
        val sink = WatchPlaybackSink(Transport(), acks)
        val play = start(sink, tiny, cue("turn-0007"))
        assertTrue(acks.onProgressMessage(node, progress("turn-0007", 1, 1_000, 5_000)))
        assertTrue(acks.onPlayedMessage(node, PlayedAck("turn-0007", 1, true).encode()))
        runCurrent()
        assertTrue(play.done)
        assertFalse("after the ack", acks.onProgressMessage(node, progress("turn-0007", 1, 2_000, 5_000)))
        assertFalse("a duplicate ack", acks.onPlayedMessage(node, PlayedAck("turn-0007", 1, true).encode()))
        assertEquals(1, play.finished.get())
    }

    @Test
    fun `an explicit Stop ends the wait promptly, tells the Watch once, and later messages are ignored`() = runTest {
        val acks = WatchAckRegistry()
        val transport = Transport()
        val sink = WatchPlaybackSink(transport, acks)
        val play = start(sink, tiny, cue("turn-0008"))
        advancePlaying(acks, play, "turn-0008", 1, 0, 60_000, 3_600_000)
        play.job.cancel()
        runCurrent()
        assertTrue(play.job.isCancelled)
        assertEquals("the Watch is told to stop exactly once", listOf(WatchLinkPaths.STOP), transport.messages.toList())
        assertFalse(acks.onProgressMessage(node, progress("turn-0008", 1, 65_000, 3_600_000)))
        assertFalse(acks.onPlayedMessage(node, PlayedAck("turn-0008", 1, true).encode()))
        assertEquals(0, play.finished.get())
        advanceTimeBy(10 * 60_000); runCurrent()
        assertEquals("nothing more is sent", 1, transport.messages.size)
    }

    @Test
    fun `a busy refusal after progress was reported ends once as busy, never as played, with no Stop`() = runTest {
        val acks = WatchAckRegistry()
        val transport = Transport()
        val sink = WatchPlaybackSink(transport, acks)
        val play = start(sink, tiny, cue("turn-0009"))
        assertTrue(acks.onProgressMessage(node, progress("turn-0009", 1, 500, 60_000)))
        assertTrue(acks.onPlayedMessage(node, PlayedAck("turn-0009", 1, false, PlayedAck.BUSY_RECORDING).encode()))
        runCurrent()
        assertTrue("busy, not a generic failure: ${play.failure}", play.failure is HermesPlaybackBusyException)
        assertFalse(acks.onPlayedMessage(node, PlayedAck("turn-0009", 1, false, PlayedAck.BUSY_RECORDING).encode()))
        assertFalse(acks.onPlayedMessage(node, PlayedAck("turn-0009", 1, true).encode()))
        assertEquals(0, play.finished.get())
        assertTrue(transport.messages.isEmpty())
    }

    @Test
    fun `a negative ack ends once as a failure and a later played ack cannot turn it into played`() = runTest {
        val acks = WatchAckRegistry()
        val sink = WatchPlaybackSink(Transport(), acks)
        val play = start(sink, tiny, cue("turn-0010"))
        assertTrue(acks.onPlayedMessage(node, PlayedAck("turn-0010", 1, false, "decode error").encode()))
        runCurrent()
        assertTrue(play.failure is HermesPlaybackException && play.failure !is HermesPlaybackBusyException)
        assertFalse(acks.onPlayedMessage(node, PlayedAck("turn-0010", 1, true).encode()))
        assertEquals(0, play.finished.get())
    }

    @Test
    fun `an unreachable Watch fails at once and leaves no waiter behind`() = runTest {
        val acks = WatchAckRegistry()
        val transport = Transport().also { it.failChannel = java.io.IOException("link down") }
        val sink = WatchPlaybackSink(transport, acks)
        val play = start(sink, tiny, cue("turn-0011"))
        assertTrue(play.failure is HermesPlaybackException)
        assertFalse(acks.onProgressMessage(node, progress("turn-0011", 1, 1_000, 5_000)))
        assertFalse(acks.onPlayedMessage(node, PlayedAck("turn-0011", 1, true).encode()))
    }

    @Test
    fun `a long sequence of chunks each waits only on its own wire sequence`() = runTest {
        val acks = WatchAckRegistry()
        val transport = Transport()
        val sink = WatchPlaybackSink(transport, acks)
        val chunks = 30
        val minutes = 3L
        for (part in 0 until chunks) {
            val wire = 7 + if (part > 0) part * 10_000 else 0
            val play = start(sink, tiny, cue("turn-0012", sequence = 7, part = part))
            assertEquals(WatchLinkPaths.playPath("turn-0012", wire), transport.channels.last())
            if (part > 0) {
                val previous = 7 + (if (part - 1 > 0) (part - 1) * 10_000 else 0)
                assertFalse("the previous chunk's late progress", acks.onProgressMessage(node, progress("turn-0012", previous, 100_000, 180_000)))
                assertFalse("the previous chunk's late ack", acks.onPlayedMessage(node, PlayedAck("turn-0012", previous, true).encode()))
                assertFalse("the plain sequence is not this chunk", acks.onProgressMessage(node, progress("turn-0012", 7, 1_000, 180_000)))
            }
            advancePlaying(acks, play, "turn-0012", wire, 0, minutes * 60_000 - 5_000, minutes * 60_000)
            assertTrue(acks.onPlayedMessage(node, PlayedAck("turn-0012", wire, true).encode()))
            runCurrent()
            assertTrue("chunk $part confirmed", play.done)
            assertEquals(1, play.finished.get())
        }
        assertEquals(chunks, transport.channels.size)
    }
}
