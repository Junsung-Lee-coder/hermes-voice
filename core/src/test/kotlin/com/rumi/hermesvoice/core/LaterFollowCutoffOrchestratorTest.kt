package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import com.rumi.hermesvoice.core.net.GatewayConversationPort
import com.rumi.hermesvoice.core.net.HermesSpeechGateway
import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.voice.DestinationAllowlist
import com.rumi.hermesvoice.core.voice.DestinationEntry
import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.TurnRouting
import com.rumi.hermesvoice.core.voice.VoiceTurnConfig
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import java.util.Collections
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Phone's stored minutes (1, 30, 4320) through the real orchestrator and the real [GatewayConversationPort], on a virtual
 * clock: a later reply completing one millisecond before the deadline is queued and played, one completing at the deadline or after
 * it is never queued, played or reported, and the follow ends as "window" at exactly the configured offset.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LaterFollowCutoffOrchestratorTest {
    private val speech = object : HermesSpeechGateway {
        override suspend fun transcribe(audio: ByteArray, mimeType: String) = "hello"
        override suspend fun speak(text: String) = SpokenAudio(text.toByteArray(), "audio/mpeg")
    }
    private val entries = listOf(DestinationEntry("work", "work_session"))

    private class Outcome(val replies: List<Triple<String, Boolean, Long>>, val ends: List<Triple<String, String, Long>>, val played: List<String>)

    private suspend fun TestScope.run(minutes: Int, completeAtOffset: Long): Outcome {
        val settings = AppSettings(InMemoryKeyValueStore())
        settings.laterReplyWindowMinutes = minutes
        val gateway = ScriptedGateway()
        gateway.onSubmit = { session ->
            val channel = gateway.channels.getValue(session)
            channel.trySend(gateway.start(session)); channel.trySend(gateway.complete(session, "own reply"))
        }
        val replies = Collections.synchronizedList(mutableListOf<Triple<String, Boolean, Long>>())
        val ends = Collections.synchronizedList(mutableListOf<Triple<String, String, Long>>())
        val played = Collections.synchronizedList(mutableListOf<String>())
        val listener = object : VoiceTurnListener {
            override fun onLaterReply(turnId: String, played: Boolean, detail: String) { replies += Triple(turnId, played, currentTime) }
            override fun onLaterFollowEnded(turnId: String, reason: String) { ends += Triple(turnId, reason, currentTime) }
        }
        val orchestrator = VoiceTurnOrchestrator(speech, GatewayConversationPort { gateway },
            config = { VoiceTurnConfig(null, DestinationAllowlist.create(entries, null), ResponsePlaybackSettings()) },
            listener = listener, inputGate = { _, _ -> AudioInputVerdict.USABLE }, laterScope = this,
            laterWindowProvider = { settings.laterReplyWindowMillis }, laterEnabled = { true })
        val outcome = orchestrator.run(VoiceTurnRequest("cut-turn-0001", VoiceOrigin.PHONE, byteArrayOf(1, 2, 3), "audio/wav",
            PlaybackSink { audio, cue -> if (cue.later) played += String(audio.bytes) }, routing = TurnRouting.Direct("work_session")))
        assertTrue(outcome is VoiceTurnOutcome.Completed)
        runCurrent()
        val t0 = currentTime
        val channel = gateway.channels.getValue("rt-work_session")
        launch {
            delay(completeAtOffset - 1); channel.trySend(gateway.start("rt-work_session"))
            delay(1); channel.trySend(gateway.complete("rt-work_session", "later reply"))
        }
        advanceUntilIdle()
        return Outcome(replies.map { Triple(it.first, it.second, it.third - t0) }, ends.map { Triple(it.first, it.second, it.third - t0) }, played.toList())
    }

    @Test
    fun `a setting of 1, 30 or 4320 minutes admits and plays a reply completing one millisecond before the deadline and ends as window at that deadline`() = runTest {
        for (minutes in listOf(1, 30, 4320)) {
            val window = minutes * 60_000L
            val o = run(minutes, completeAtOffset = window - 1)
            assertEquals("$minutes min plays the reply", listOf("later reply"), o.played)
            assertEquals("$minutes min reports it once", 1, o.replies.size)
            assertEquals("$minutes min", true, o.replies.single().second)
            assertEquals("$minutes min", listOf(Triple("cut-turn-0001", "window", window)), o.ends)
        }
    }

    @Test
    fun `a reply completing exactly at the deadline or after it is never queued, played or reported`() = runTest {
        for (minutes in listOf(1, 30, 4320)) {
            val window = minutes * 60_000L
            for (offset in listOf(window, window + 1)) {
                val o = run(minutes, completeAtOffset = offset)
                assertEquals("$minutes min at $offset: nothing played", emptyList<String>(), o.played)
                assertEquals("$minutes min at $offset: nothing reported", emptyList<Triple<String, Boolean, Long>>(), o.replies)
                assertEquals("$minutes min at $offset", listOf(Triple("cut-turn-0001", "window", window)), o.ends)
            }
        }
    }
}
