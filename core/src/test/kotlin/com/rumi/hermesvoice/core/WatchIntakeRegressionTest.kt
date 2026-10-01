package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.audio.QaLaunchGuard
import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.voice.PlaybackRoute
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.PlaybackTarget
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.watchlink.BoundedRead
import com.rumi.hermesvoice.core.watchlink.LinkFrame
import com.rumi.hermesvoice.core.watchlink.LinkProtocolException
import com.rumi.hermesvoice.core.watchlink.PlayRequest
import com.rumi.hermesvoice.core.watchlink.PlayedAck
import com.rumi.hermesvoice.core.watchlink.TurnStateMessage
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchTransport
import com.rumi.hermesvoice.core.watchlink.WatchTurnUpload
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Regressions for the Watch upload path, the playback-target label and the debug QA launch guard. */
class WatchIntakeRegressionTest {
    private val wav = TestAudio.speechWav()

    private class RecordingWatch(private val h: CoreHarness, override val nodeId: String = "watch-node-a") : WatchTransport {
        val states: MutableList<TurnStateMessage> = Collections.synchronizedList(mutableListOf())
        val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun sendMessage(path: String, bytes: ByteArray) {
            if (path == WatchLinkPaths.STATE) states += TurnStateMessage.decode(bytes)!!
        }
        override suspend fun sendChannel(path: String, bytes: ByteArray) {
            val play = PlayRequest.fromFrame(LinkFrame.decode(bytes))
            played += "${play.role}:${h.fake.decodeSpoken(com.rumi.hermesvoice.core.SpokenAudio(play.audio, play.mimeType))}"
            h.core.watchAcks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, true).encode())
        }
    }

    @Test
    fun `malformed and oversized watch uploads never move the playback route`() {
        CoreHarness().use { h ->
            val phoneTarget = PlaybackTarget(VoiceOrigin.PHONE, PlaybackSink { _, _ -> })
            h.core.orchestrator.playbackRoute.accept(phoneTarget)
            val watch = RecordingWatch(h)
            val bad = listOf(
                "nope".toByteArray(),
                WatchTurnUpload("turn-bad-02", TurnTrigger.PUSH_TO_TALK, "audio/wav", ByteArray(10)).toFrame().encode(),
                WatchTurnUpload("turn-other-03", TurnTrigger.PUSH_TO_TALK, "audio/wav", wav).toFrame().encode(),
            )
            runBlocking {
                bad.forEachIndexed { i, bytes ->
                    assertNull(h.core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath("turn-bad-0${i + 1}"), bytes, watch))
                }
                // Oversized: the Data Layer service reads at most the frame bound and drops the rest.
                val huge = ByteArrayInputStream(ByteArray(LinkFrame.MAX_PAYLOAD_BYTES + LinkFrame.MAX_HEADER_BYTES + 9))
                assertNull(BoundedRead.readAtMost(huge, LinkFrame.MAX_PAYLOAD_BYTES + LinkFrame.MAX_HEADER_BYTES + 8))
                assertNull(h.core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath("turn-bad-04"), null, watch))
            }
            assertSame(phoneTarget, h.core.orchestrator.playbackRoute.current())
            assertEquals(VoiceOrigin.PHONE, h.core.orchestrator.playbackRoute.device.value)
            assertEquals(List(4) { "rejected" }, watch.states.map { it.stage })
            assertTrue(watch.states.all { it.terminal })
            assertEquals(0, h.fake.server.requestCount)
        }
    }

    @Test
    fun `a watch turn stopped on the phone tells the watch it ended and stops its playback`() {
        CoreHarness().use { h ->
            h.fake.sourceScripts[AppSources.ROUTER] = { listOf(FakeHermesDashboard.complete("""{"destination":"work","ack":"On it."}""")) }
            val work = runBlocking { h.core.sessions.createConversation("Work", "work", "") }.storedSessionId
            h.fake.scripts[work] = { listOf(FakeHermesDashboard.complete("Done.")) }
            val playing = CountDownLatch(1)
            val messages: MutableList<Pair<String, TurnStateMessage>> = Collections.synchronizedList(mutableListOf())
            // A Watch that received the acknowledgement and is still playing it (no `played` comes back).
            val watch = object : WatchTransport {
                override val nodeId = "watch-node-a"
                override suspend fun sendMessage(path: String, bytes: ByteArray) { messages += path to TurnStateMessage.decode(bytes)!! }
                override suspend fun sendChannel(path: String, bytes: ByteArray) = playing.countDown()
            }
            val upload = WatchTurnUpload.recognized("turn-stop-01", "내일 일정 알려줘")
            runBlocking {
                val turn = launch(kotlinx.coroutines.Dispatchers.Default) {
                    h.core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath("turn-stop-01"), upload.toFrame().encode(), watch)
                }
                assertTrue(playing.await(10, TimeUnit.SECONDS))
                turn.cancel()
                turn.join()
            }
            val last = messages.last()
            assertEquals(WatchLinkPaths.STATE, last.first)
            assertTrue("the Watch leaves its waiting state", last.second.terminal)
            assertEquals(com.rumi.hermesvoice.core.watchlink.WatchTurnIntake.STOPPED_ON_PHONE, last.second.detail)
            assertTrue("the utterance in progress is stopped on the Watch", messages.any { it.first == WatchLinkPaths.STOP })
            assertEquals("the transcript was not delivered after the stop", 0, h.fake.prompts.count { it.first == work })
        }
    }

    @Test
    fun `bounded read returns the bytes when within the limit`() {
        assertArrayEquals(wav, BoundedRead.readAtMost(ByteArrayInputStream(wav), wav.size))
    }

    @Test
    fun `a recognized wake request skips transcription and is routed like speech`() {
        CoreHarness().use { h ->
            h.fake.sourceScripts[AppSources.ROUTER] = { listOf(FakeHermesDashboard.complete("""{"destination":"work","ack":"On it."}""")) }
            val work = runBlocking { h.core.sessions.createConversation("Work", "work", "") }.storedSessionId
            h.fake.scripts[work] = { listOf(FakeHermesDashboard.complete("Done.")) }
            val watch = RecordingWatch(h)
            val upload = WatchTurnUpload.recognized("turn-wake-01", "내일 일정 알려줘")
            val outcome = runBlocking {
                h.core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath("turn-wake-01"), upload.toFrame().encode(), watch)
            }
            assertTrue("$outcome", outcome is VoiceTurnOutcome.Completed)
            assertFalse(h.fake.timeline.any { it.startsWith("transcribe:") })
            assertEquals(Pair(work, "내일 일정 알려줘"), h.fake.prompts.last())
            assertEquals(listOf("ACK:On it.", "FINAL:Done."), watch.played.toList())
            assertEquals(VoiceOrigin.WATCH, h.core.orchestrator.playbackRoute.device.value)
        }
    }

    @Test
    fun `recognized request frames are validated`() {
        val ok = WatchTurnUpload.recognized("turn-wake-02", "  불 꺼  ")
        val decoded = WatchTurnUpload.fromFrame(WatchLinkPaths.turnPath("turn-wake-02"), LinkFrame.decode(ok.toFrame().encode()))
        assertEquals("불 꺼", decoded.recognizedText)
        assertEquals(TurnTrigger.WAKE_PHRASE, decoded.trigger)
        for (text in listOf("   ", "가".repeat(WatchTurnUpload.MAX_RECOGNIZED_CHARS + 1))) {
            try {
                WatchTurnUpload.fromFrame(WatchLinkPaths.turnPath("turn-wake-03"),
                    LinkFrame.decode(WatchTurnUpload("turn-wake-03", TurnTrigger.WAKE_PHRASE, WatchTurnUpload.MIME_TEXT,
                        text.toByteArray(Charsets.UTF_8)).toFrame().encode()))
                fail("recognized text '$text' should be rejected")
            } catch (_: LinkProtocolException) {
            }
        }
        try {
            WatchTurnUpload.fromFrame(WatchLinkPaths.turnPath("turn-wake-04"),
                LinkFrame.decode(WatchTurnUpload("turn-wake-04", TurnTrigger.PUSH_TO_TALK, WatchTurnUpload.MIME_TEXT,
                    "hi".toByteArray()).toFrame().encode()))
            fail("push-to-talk turns must carry audio")
        } catch (_: LinkProtocolException) {
        }
    }

    @Test
    fun `the playback-target label is updated inside the route lock, in acceptance order`() {
        repeat(20) {
            val route = PlaybackRoute()
            val observed: MutableList<VoiceOrigin?> = Collections.synchronizedList(mutableListOf())
            val pool = Executors.newFixedThreadPool(8)
            val start = CountDownLatch(1)
            val targets = List(64) { i -> PlaybackTarget(if (i % 2 == 0) VoiceOrigin.PHONE else VoiceOrigin.WATCH, PlaybackSink { _, _ -> }) }
            targets.forEach { target ->
                pool.execute {
                    start.await()
                    route.accept(target)
                    observed += route.device.value
                }
            }
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            assertEquals(route.current()?.device, route.device.value)
            assertEquals(64, observed.size)
        }
    }

    @Test
    fun `debug QA audio runs only for a fresh launch intent, never on recreation or from recents`() {
        assertTrue(QaLaunchGuard.shouldHandle(restoredFromSavedState = false, launchedFromHistory = false, alreadyHandled = false))
        assertFalse(QaLaunchGuard.shouldHandle(restoredFromSavedState = true, launchedFromHistory = false, alreadyHandled = false))
        assertFalse(QaLaunchGuard.shouldHandle(restoredFromSavedState = false, launchedFromHistory = true, alreadyHandled = false))
        assertFalse(QaLaunchGuard.shouldHandle(restoredFromSavedState = false, launchedFromHistory = false, alreadyHandled = true))
    }
}
