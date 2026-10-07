package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.voice.PlaybackCue
import com.rumi.hermesvoice.core.watchlink.PlayedAck
import com.rumi.hermesvoice.core.watchlink.WatchAckRegistry
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchPlaybackSink
import com.rumi.hermesvoice.core.watchlink.WatchTransport
import java.util.Collections
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LEGACY wait for a Watch that reports no playback progress: 30 s plus 2 bytes per ms of audio, a size guess that is
 * explicitly NOT a duration (v19 self-check R1 migrated the claim that it "fits the clip"; a Watch that reports its real
 * player position is judged by WatchPlaybackProgressTest instead). Each chunk of a long reply keeps its own exact confirmation.
 * Virtual time through the production [WatchPlaybackSink] with its default timeout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LongAudioAckTest {
    private class Transport : WatchTransport {
        override val nodeId = "watch-node-a"
        val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val stops: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun sendMessage(path: String, bytes: ByteArray) {
            stops += path
        }
        override suspend fun sendChannel(path: String, bytes: ByteArray) {
            paths += path
        }
    }

    private fun clip(bytes: Int) = SpokenAudio(ByteArray(bytes), "audio/mpeg")

    @Test
    fun `a long clip is confirmed after a wait far beyond the fixed 30 seconds`() = runTest {
        val acks = WatchAckRegistry()
        val transport = Transport()
        val sink = WatchPlaybackSink(transport, acks)
        var done = false
        var failure: Throwable? = null
        val job = launch {
            try {
                sink.play(clip(6_000_000), PlaybackCue("turn-0001", com.rumi.hermesvoice.core.VoiceOrigin.WATCH, SpokenRole.FINAL, 1, "x"))
                done = true
            } catch (error: Throwable) {
                failure = error
            }
        }
        runCurrent()
        // 6 MB plays for up to 50 minutes at 16 kbps; 40 minutes in it is still being played, not timed out.
        advanceTimeBy(40 * 60_000L)
        runCurrent()
        assertTrue("still waiting, not cut: $failure", job.isActive && failure == null)
        assertTrue(acks.onPlayedMessage("watch-node-a", PlayedAck("turn-0001", 1, true).encode()))
        runCurrent()
        assertTrue(done)
    }

    @Test
    fun `a Watch that reports nothing is still given up on at the explicit legacy size-based limit`() = runTest {
        val acks = WatchAckRegistry()
        val transport = Transport()
        val sink = WatchPlaybackSink(transport, acks)
        var failure: Throwable? = null
        launch {
            try {
                sink.play(clip(2_000), PlaybackCue("turn-0002", com.rumi.hermesvoice.core.VoiceOrigin.WATCH, SpokenRole.FINAL, 1, "x"))
            } catch (error: Throwable) {
                failure = error
            }
        }
        runCurrent()
        val bound = 30_000L + 2_000 / 2
        advanceTimeBy(bound - 1)
        runCurrent()
        assertEquals(null, failure)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(failure is HermesPlaybackException)
        assertFalse("a late ack is ignored", acks.onPlayedMessage("watch-node-a", PlayedAck("turn-0002", 1, true).encode()))
    }

    @Test
    fun `each chunk of a long reply has its own wire sequence and exact confirmation`() = runTest {
        val acks = WatchAckRegistry()
        val transport = Transport()
        val sink = WatchPlaybackSink(transport, acks)
        var done = false
        launch {
            sink.play(clip(100), PlaybackCue("turn-0003", com.rumi.hermesvoice.core.VoiceOrigin.WATCH, SpokenRole.FINAL, 3, "x", part = 2))
            done = true
        }
        runCurrent()
        val wire = 3 + 2 * 10_000
        assertEquals(listOf(WatchLinkPaths.playPath("turn-0003", wire)), transport.paths.toList())
        assertFalse("the plain sequence does not confirm a chunk", acks.onPlayedMessage("watch-node-a", PlayedAck("turn-0003", 3, true).encode()))
        runCurrent()
        assertFalse(done)
        assertFalse("another node cannot confirm it", acks.onPlayedMessage("watch-node-b", PlayedAck("turn-0003", wire, true).encode()))
        assertTrue(acks.onPlayedMessage("watch-node-a", PlayedAck("turn-0003", wire, true).encode()))
        runCurrent()
        assertTrue(done)
    }
}
