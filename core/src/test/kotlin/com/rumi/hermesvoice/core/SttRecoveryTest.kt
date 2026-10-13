package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import com.rumi.hermesvoice.core.net.*
import com.rumi.hermesvoice.core.sessions.*
import com.rumi.hermesvoice.core.settings.*
import com.rumi.hermesvoice.core.voice.*
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A recording is transcribed again while the speech server is temporarily unavailable, and the turn is delivered exactly once
 * when it recovers. Time is virtual: waits and the recovery budget are the real production values.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SttRecoveryTest {
    private val events: MutableList<SttRecoveryEvent> = Collections.synchronizedList(mutableListOf())
    private val stages: MutableList<VoiceTurnStage> = Collections.synchronizedList(mutableListOf())
    private val submits: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val delivered = AtomicInteger()
    private val sessionsSubmitted: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val attemptTimes: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    private val calls = AtomicInteger()
    private var script: suspend (Int) -> String = { "hello there" }
    private var scope: TestScope? = null

    private val speech = object : HermesSpeechGateway {
        override suspend fun transcribe(audio: ByteArray, mimeType: String): String {
            val n = calls.incrementAndGet()
            attemptTimes += scope?.currentTime ?: 0L
            return script(n)
        }

        override suspend fun speak(text: String) = SpokenAudio("AUDIO:$text".toByteArray(), "audio/mpeg")
    }

    private val conversations = object : HermesConversationPort {
        override suspend fun create(source: String, title: String, seedInstruction: String, hidden: Boolean) = throw UnsupportedOperationException()

        override suspend fun submit(storedSessionId: String, text: String, attachments: List<OutgoingAttachment>): SubmittedTurn {
            if (storedSessionId == "router_session") {
                return Scripted(Channel<RecipientEvent>(Channel.UNLIMITED).apply {
                    trySend(RecipientEvent.Complete("""{"destination":"work","ack":"Sending to work."}""", "complete"))
                })
            }
            submits += voiceWords(text)
            sessionsSubmitted += storedSessionId
            return Scripted(Channel<RecipientEvent>(Channel.UNLIMITED).apply { trySend(RecipientEvent.Complete("done", "complete")) })
        }
    }

    private class Scripted(private val ch: Channel<RecipientEvent>) : SubmittedTurn {
        override val submitStatus = "streaming"
        override val attributable = true
        override suspend fun collect(timeoutMs: Long, onEvent: suspend (RecipientEvent) -> Unit) {
            for (e in ch) { onEvent(e); if (e is RecipientEvent.Complete) return }
        }
        override fun release() {}
    }

    private val listener = object : VoiceTurnListener {
        override fun onStage(turnId: String, stage: VoiceTurnStage) { stages += stage }
        override fun onSttRecovery(turnId: String, event: SttRecoveryEvent) { events += event }
        override fun onDelivered(route: AssembledRoute) { delivered.incrementAndGet() }
    }

    private val allowlist = DestinationAllowlist.create(listOf(DestinationEntry("work", "work_session")), "router_session")
    private val passThrough: (ByteArray, String) -> AudioInputVerdict = { _, _ -> AudioInputVerdict.USABLE }
    private val sink = PlaybackSink { _, _ -> }

    private fun orchestrator(policy: SttRecoveryPolicy = SttRecoveryPolicy()) = VoiceTurnOrchestrator(
        speech, conversations, inputGate = passThrough,
        config = { VoiceTurnConfig("router_session", allowlist, ResponsePlaybackSettings()) },
        listener = listener, sttPolicy = policy, recoveryClock = { scope?.currentTime ?: 0L },
    )

    private fun request(id: String, deadline: Long? = null, origin: VoiceOrigin = VoiceOrigin.PHONE) =
        VoiceTurnRequest(id, origin, byteArrayOf(1, 2, 3, 4), "audio/wav", sink, callerDeadlineMs = deadline)

    private fun unavailable(code: SttCode = SttCode.UNAVAILABLE) = HermesHttpException(400, SttSafeCode.message(code), code)
    private fun TestScope.arm() { scope = this }

    @Test fun `a first attempt that succeeds is submitted once with no recovery`() = runTest {
        arm()
        val outcome = orchestrator().run(request("t1"))
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.Completed)
        assertEquals(listOf("hello there"), submits)
        assertEquals(1, calls.get())
        assertEquals(1, delivered.get())
        assertFalse(stages.contains(VoiceTurnStage.STT_RECOVERING))
        assertTrue(events.none { it.phase == SttPhase.WAIT_STARTED })
    }

    @Test fun `repeated unavailability then recovery waits 5 then 15 seconds and delivers once`() = runTest {
        arm()
        script = { n -> if (n <= 2) throw unavailable() else "after recovery" }
        val outcome = orchestrator().run(request("t1"))
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.Completed)
        assertEquals(listOf(0L, 5_000L, 20_000L), attemptTimes.toList())
        assertEquals(listOf("after recovery"), submits)
        assertEquals(1, delivered.get())
        assertTrue(stages.contains(VoiceTurnStage.STT_RECOVERING))
        val waits = events.filter { it.phase == SttPhase.WAIT_STARTED }
        assertEquals(listOf(5_000L, 15_000L), waits.map { it.waitMs })
        assertEquals(SttPhase.RECOVERED, events.last { it.phase == SttPhase.RECOVERED }.phase)
    }

    @Test fun `the full default budget is at most six attempts within 180 seconds`() = runTest {
        arm()
        script = { throw unavailable(SttCode.BUSY) }
        val outcome = orchestrator().run(request("t1")) as VoiceTurnOutcome.NotDelivered
        assertEquals(VoiceTurnStage.TRANSCRIBING, outcome.stage)
        assertTrue(calls.get() in 4..6)
        assertTrue("budget respected: $currentTime", currentTime <= 180_000L)
        assertTrue(outcome.reason, outcome.reason.startsWith("stt_busy"))
        assertTrue(submits.isEmpty())
        assertEquals(0, delivered.get())
        assertTrue(events.any { it.phase == SttPhase.EXHAUSTED })
    }

    @Test fun `a legacy 400 without the envelope is not retried`() = runTest {
        arm()
        script = { throw HermesHttpException(400, "Transcription failed", null) }
        val outcome = orchestrator().run(request("t1")) as VoiceTurnOutcome.NotDelivered
        assertEquals(1, calls.get())
        assertEquals(0L, currentTime)
        assertTrue(submits.isEmpty())
        assertFalse(outcome.reason.contains("stt_"))
    }

    @Test fun `input and auth rejections and a permanent failure are never retried`() = runTest {
        arm()
        for (code in listOf(SttCode.INPUT_REJECTED, SttCode.AUTH_REJECTED, SttCode.TRANSCRIPTION_FAILED, SttCode.CONFIG_INVALID, SttCode.CANCELLED)) {
            calls.set(0)
            script = { throw unavailable(code) }
            val outcome = orchestrator().run(request("t-${code.wire}"))
            assertTrue("$code $outcome", outcome is VoiceTurnOutcome.NotDelivered)
            assertEquals("$code", 1, calls.get())
        }
        assertEquals(0L, currentTime)
        assertTrue(submits.isEmpty())
    }

    @Test fun `an unauthorized or forbidden status is not retried`() = runTest {
        arm()
        for (status in listOf(401, 403, 413, 415)) {
            calls.set(0)
            script = { throw HermesHttpException(status, "x", null) }
            orchestrator().run(request("t-$status"))
            assertEquals(status.toString(), 1, calls.get())
        }
    }

    @Test fun `a recovered attempt that fails permanently ends the turn without further waiting`() = runTest {
        arm()
        script = { n -> if (n == 1) throw unavailable() else throw unavailable(SttCode.INPUT_REJECTED) }
        val outcome = orchestrator().run(request("t1"))
        assertTrue(outcome is VoiceTurnOutcome.NotDelivered)
        assertEquals(2, calls.get())
        assertTrue(submits.isEmpty())
    }

    @Test fun `a tighter caller deadline ends recovery and is never reset`() = runTest {
        arm()
        script = { throw unavailable() }
        val outcome = orchestrator().run(request("t1", deadline = 20_000L))
        assertTrue(outcome is VoiceTurnOutcome.NotDelivered)
        assertTrue("stopped by the caller's deadline: $currentTime", currentTime <= 20_000L)
        assertTrue(submits.isEmpty())
    }

    @Test fun `stopping during a backoff wait cancels exactly that turn and never replays`() = runTest {
        arm()
        script = { throw unavailable() }
        val orchestrator = orchestrator()
        val result = async { orchestrator.run(request("t1")) }
        testScheduler.advanceTimeBy(2_000L)
        testScheduler.runCurrent()
        assertEquals(PendingPhase.WAITING_STT, orchestrator.pending.value.single().phase)
        assertTrue(orchestrator.stopTurn("t1"))
        assertEquals(VoiceTurnOutcome.Stopped, result.await())
        val callsAtStop = calls.get()
        testScheduler.advanceTimeBy(600_000L)
        testScheduler.runCurrent()
        assertEquals("no attempt after a Stop", callsAtStop, calls.get())
        assertTrue(submits.isEmpty())
        assertTrue(orchestrator.pending.value.isEmpty())
        assertTrue(events.any { it.phase == SttPhase.CANCELLED && it.waiting })
    }

    @Test fun `stopping during a transcription call cancels it and does not start a retry`() = runTest {
        arm()
        val inCall = CompletableDeferred<Unit>()
        script = { n -> if (n == 1) throw unavailable() else { inCall.complete(Unit); kotlinx.coroutines.awaitCancellation() } }
        val orchestrator = orchestrator()
        val result = async { orchestrator.run(request("t1")) }
        testScheduler.advanceTimeBy(5_100L)
        testScheduler.runCurrent()
        inCall.await()
        assertTrue(orchestrator.stopTurn("t1"))
        assertEquals(VoiceTurnOutcome.Stopped, result.await())
        assertEquals(2, calls.get())
        assertTrue(submits.isEmpty())
        assertTrue(events.any { it.phase == SttPhase.CANCELLED && !it.waiting })
    }

    @Test fun `a duplicate turn id during recovery is not transcribed or delivered twice`() = runTest {
        arm()
        script = { n -> if (n == 1) throw unavailable() else "once" }
        val orchestrator = orchestrator()
        val first = async { orchestrator.run(request("same")) }
        testScheduler.advanceTimeBy(1_000L)
        testScheduler.runCurrent()
        assertEquals(VoiceTurnOutcome.Duplicate("same"), orchestrator.run(request("same")))
        assertTrue(first.await() is VoiceTurnOutcome.Completed)
        assertEquals(listOf("once"), submits)
        assertEquals(2, calls.get())
    }

    @Test fun `an independent request is not blocked while another waits for the server`() = runTest {
        arm()
        script = { n -> if (n == 1) throw unavailable() else "second words" }
        val orchestrator = orchestrator()
        val waiting = async { orchestrator.run(request("slow")) }
        testScheduler.advanceTimeBy(1_000L)
        testScheduler.runCurrent()
        assertEquals(PendingPhase.WAITING_STT, orchestrator.pending.value.single { it.turnId == "slow" }.phase)
        val other = orchestrator.run(request("fast", origin = VoiceOrigin.WATCH))
        assertTrue(other.toString(), other is VoiceTurnOutcome.Completed)
        assertTrue(submits.contains("second words"))
        waiting.await()
        assertEquals(2, submits.size)
    }

    @Test fun `policy NONE keeps exactly one attempt`() = runTest {
        arm()
        script = { throw unavailable() }
        val outcome = orchestrator(SttRecoveryPolicy.NONE).run(request("t1")) as VoiceTurnOutcome.NotDelivered
        assertEquals(1, calls.get())
        assertEquals(0L, currentTime)
        assertTrue(outcome.reason, outcome.reason.contains("stt_unavailable") || outcome.reason.contains("temporarily"))
    }

    @Test fun `an IOException from the transcription call is not retried as an STT code`() = runTest {
        arm()
        script = { throw IOException("connection reset") }
        val outcome = orchestrator().run(request("t1"))
        assertTrue(outcome is VoiceTurnOutcome.NotDelivered)
        assertEquals(1, calls.get())
    }

    @Test fun `recovery events carry only fixed phases bounded numbers and the safe code`() = runTest {
        arm()
        script = { n -> if (n == 1) throw unavailable(SttCode.TIMEOUT) else "ok" }
        orchestrator().run(request("t1"))
        val dump = events.joinToString("|") { it.toString() }
        assertFalse(dump.contains("ok"))
        assertTrue(events.first().audioBytes == 4)
        assertEquals("audio/wav", events.first().mimeType)
        assertEquals(SttCode.TIMEOUT, events.first { it.phase == SttPhase.ATTEMPT_FAILED }.code)
        assertEquals(400, events.first { it.phase == SttPhase.ATTEMPT_FAILED }.status)
    }

    @Test fun `the safe code envelope is recognized only when intentionally emitted`() {
        assertEquals(SttCode.UNAVAILABLE, SttSafeCode.parse("[hermes-stt:stt_unavailable] speech recognition is temporarily unavailable"))
        assertEquals(SttCode.BUSY, SttSafeCode.parse("[hermes-stt:stt_busy]"))
        assertNull(SttSafeCode.parse("Transcription failed: [hermes-stt:stt_unavailable]"))
        assertNull(SttSafeCode.parse("[hermes-stt:made_up] x"))
        assertNull(SttSafeCode.parse("[hermes-stt:stt_busy]x"))
        assertNull(SttSafeCode.parse("[hermes-stt:stt_busy] two\nlines"))
        assertNull(SttSafeCode.parse("400"))
        assertNull(SttSafeCode.parse(null))
        assertNull(SttSafeCode.parse("[hermes-stt:stt_busy] " + "x".repeat(300)))
        assertTrue(SttCode.values().filter { it.retryable }.map { it.wire }.toSet() == setOf("stt_unavailable", "stt_busy", "stt_timeout"))
    }

    @Test fun `the conversation chosen when the recording was made is the one it is delivered to after the wait`() = runTest {
        arm()
        var selectedNow = "home_session"
        val both = DestinationAllowlist.create(listOf(DestinationEntry("work", "work_session"), DestinationEntry("home", "home_session")), null)
        val direct = VoiceTurnOrchestrator(
            speech, conversations, inputGate = passThrough,
            config = { VoiceTurnConfig(null, both, ResponsePlaybackSettings()) },
            listener = listener, recoveryClock = { scope?.currentTime ?: 0L },
        )
        script = { n -> if (n == 1) { selectedNow = "work_session"; throw unavailable() } else "hello again" }
        val req = VoiceTurnRequest("t1", VoiceOrigin.PHONE, byteArrayOf(1, 2, 3, 4), "audio/wav", sink, routing = TurnRouting.Direct("home_session"))
        val outcome = direct.run(req)
        assertTrue(outcome.toString(), outcome is VoiceTurnOutcome.Completed)
        assertEquals("the chat selection changed during the wait", "work_session", selectedNow)
        assertEquals(listOf("home_session"), sessionsSubmitted.toList())
        assertEquals(1, delivered.get())
    }
}
