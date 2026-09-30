package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.TestAudio.noise
import com.rumi.hermesvoice.core.TestAudio.wav
import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.PlaybackTarget
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import com.rumi.hermesvoice.core.watchlink.CaptureCoordinator
import com.rumi.hermesvoice.core.watchlink.CapturePort
import com.rumi.hermesvoice.core.watchlink.CaptureStop
import com.rumi.hermesvoice.core.watchlink.HapticEvent
import com.rumi.hermesvoice.core.watchlink.LinkFrame
import com.rumi.hermesvoice.core.watchlink.PlayRequest
import com.rumi.hermesvoice.core.watchlink.PlayedAck
import com.rumi.hermesvoice.core.watchlink.TurnStateMessage
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchTransport
import com.rumi.hermesvoice.core.watchlink.WatchTurnUpload
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1: a push-to-talk recording with no usable audio (the emulator's peak-2 glitch) must stop before
 * speech-to-text: nothing is transcribed, routed or delivered, and the playback target stays put.
 */
class SilentInputIntakeTest {
    private val silent = wav(noise(9.2, peak = 2))

    private class Watch(private val h: CoreHarness, override val nodeId: String = "watch-node-a") : WatchTransport {
        val states: MutableList<TurnStateMessage> = Collections.synchronizedList(mutableListOf())
        val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun sendMessage(path: String, bytes: ByteArray) {
            if (path == WatchLinkPaths.STATE) states += TurnStateMessage.decode(bytes)!!
        }
        override suspend fun sendChannel(path: String, bytes: ByteArray) {
            val play = PlayRequest.fromFrame(LinkFrame.decode(bytes))
            played += play.role
            h.core.watchAcks.onPlayedMessage(nodeId, PlayedAck(play.turnId, play.sequence, true).encode())
        }
    }

    private fun scripted(h: CoreHarness) {
        h.fake.sourceScripts[AppSources.ROUTER] = { listOf(FakeHermesDashboard.complete("""{"destination":"work","ack":"On it."}""")) }
        val work = runBlocking { h.core.sessions.createConversation("Work", "work", "") }.storedSessionId
        h.fake.scripts[work] = { listOf(FakeHermesDashboard.complete("Done.")) }
    }

    @Test
    fun `a silent phone recording ends as no speech before transcription and keeps the watch as target`() {
        CoreHarness().use { h ->
            scripted(h)
            val watchTarget = PlaybackTarget(VoiceOrigin.WATCH, PlaybackSink { _, _ -> })
            h.core.orchestrator.playbackRoute.accept(watchTarget)
            val requestsBefore = h.fake.server.requestCount
            val phonePlayed = mutableListOf<String>()
            val outcome = runBlocking {
                h.core.orchestrator.run(VoiceTurnRequest("turn-silent-01", VoiceOrigin.PHONE, silent, "audio/wav",
                    PlaybackSink { _, cue -> phonePlayed += cue.role.name }))
            }
            assertEquals(VoiceTurnOutcome.NoSpeech, outcome)
            assertEquals("no dashboard call at all", requestsBefore, h.fake.server.requestCount)
            assertFalse(h.fake.timeline.any { it.startsWith("transcribe:") })
            assertTrue(h.fake.prompts.isEmpty())
            assertTrue(phonePlayed.isEmpty())
            assertSame("the latest accepted voice sender is unchanged", watchTarget, h.core.orchestrator.playbackRoute.current())
            assertEquals(VoiceOrigin.WATCH, h.core.orchestrator.playbackRoute.device.value)
        }
    }

    @Test
    fun `a genuine utterance right after still flows end to end and becomes the target`() {
        CoreHarness().use { h ->
            scripted(h)
            runBlocking {
                h.core.orchestrator.run(VoiceTurnRequest("turn-silent-02", VoiceOrigin.PHONE, silent, "audio/wav", PlaybackSink { _, _ -> }))
            }
            val played = mutableListOf<String>()
            val outcome = runBlocking {
                h.core.orchestrator.run(VoiceTurnRequest("turn-real-02", VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav",
                    PlaybackSink { _, cue -> played += cue.role.name }))
            }
            assertTrue("$outcome", outcome is VoiceTurnOutcome.Completed)
            assertEquals(listOf("ACK", "FINAL"), played)
            assertEquals(VoiceOrigin.PHONE, h.core.orchestrator.playbackRoute.device.value)
        }
    }

    @Test
    fun `a silent watch upload is answered no speech and never routed, played or made the target`() {
        CoreHarness().use { h ->
            scripted(h)
            val phoneTarget = PlaybackTarget(VoiceOrigin.PHONE, PlaybackSink { _, _ -> })
            h.core.orchestrator.playbackRoute.accept(phoneTarget)
            val watch = Watch(h)
            val frame = WatchTurnUpload("turn-silent-03", TurnTrigger.PUSH_TO_TALK, WatchTurnUpload.MIME_WAV, silent).toFrame().encode()
            val outcome = runBlocking { h.core.watchIntake.onTurnChannel(WatchLinkPaths.turnPath("turn-silent-03"), frame, watch) }
            assertEquals(VoiceTurnOutcome.NoSpeech, outcome)
            assertEquals("No speech detected", watch.states.last().detail)
            assertTrue(watch.states.last().terminal)
            assertTrue(watch.played.isEmpty())
            assertTrue(h.fake.prompts.isEmpty())
            assertFalse(h.fake.timeline.any { it.startsWith("transcribe:") })
            assertSame(phoneTarget, h.core.orchestrator.playbackRoute.current())
        }
    }

    private class Port(val wav: ByteArray?) : CapturePort {
        val calls = mutableListOf<String>()
        override fun stopRecorder(captureId: String, reason: CaptureStop): ByteArray? { calls += "stop:$reason"; return wav }
        override fun haptic(event: HapticEvent) { calls += "haptic:$event" }
        override fun cue(line: String) { calls += "cue" }
        override fun upload(captureId: String, trigger: TurnTrigger, wav: ByteArray) { calls += "upload" }
        override fun uploadRecognized(turnId: String, text: String) { calls += "recognized" }
        override fun discard(message: String) { calls += "discard:$message" }
    }

    @Test
    fun `the watch never uploads a silent push-to-talk or hands-free recording`() {
        for (reason in listOf(CaptureStop.TAP_SEND, CaptureStop.SILENCE)) {
            val port = Port(silent)
            val capture = CaptureCoordinator(port)
            val trigger = if (reason == CaptureStop.TAP_SEND) TurnTrigger.PUSH_TO_TALK else TurnTrigger.WAKE_PHRASE
            capture.begin("c", trigger)
            capture.onLive("c")
            capture.onCalibrated("c")
            capture.stop("c", reason)
            assertFalse("$reason", port.calls.contains("upload"))
            assertEquals("$reason", listOf("stop:$reason", "haptic:RECORDING_END", "discard:No speech detected"), port.calls.dropWhile { !it.startsWith("stop") })
            assertEquals("one end pulse", 1, port.calls.count { it == "haptic:RECORDING_END" })
        }
        val genuine = Port(TestAudio.speechWav())
        CaptureCoordinator(genuine).apply { begin("g", TurnTrigger.PUSH_TO_TALK); onLive("g"); tap() }
        assertEquals(listOf("haptic:RECORDING_START", "stop:TAP_SEND", "haptic:RECORDING_END", "upload"), genuine.calls)
    }
}
