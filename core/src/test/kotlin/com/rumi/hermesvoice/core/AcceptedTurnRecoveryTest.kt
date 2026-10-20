package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.voice.PlaybackSink
import com.rumi.hermesvoice.core.voice.TurnRouting
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnRequest
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reply the server finished after the connection that carried the accepted turn was lost, observed through the production
 * wiring (real OkHttp + gateway socket, orchestrator, chat service) against [FakeHermesDashboard]'s `hold` turns, which keep
 * running on the server (their events are stamped and ringed for `session.events.since`) while the transport is dropped.
 * Contract double, not an authenticated Hermes E2E. The recovery must never resubmit the prompt and must deliver one decision.
 */
class AcceptedTurnRecoveryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val process = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rig = AlertRig(scope)
    private val shown: MutableList<String> get() = rig.shown
    private val played: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val sink = PlaybackSink { _, cue -> played += cue.role.name }

    @After
    fun stop() {
        process.cancel()
        scope.cancel()
    }

    private fun existing(h: CoreHarness, alias: String): String {
        val owned = runBlocking { h.core.sessions.createConversation(alias.replaceFirstChar { it.uppercase() }, alias, "$alias things") }
        h.fake.scripts[owned.storedSessionId] = { listOf(FakeHermesDashboard.interim("Working."), FakeHermesDashboard.complete("Done.")) }
        h.fake.turnBehavior = { "hold" }
        return owned.storedSessionId
    }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(timeoutMs) { while (!condition()) delay(10) }
        } catch (_: Exception) {
            throw AssertionError("timed out waiting for $what")
        }
    }

    private fun settle(ms: Long = 1_500) = runBlocking { delay(ms) }

    private fun submits(h: CoreHarness) = h.fake.rpcLog.toList().count { it.optString("method") == "prompt.submit" }

    private fun voice(h: CoreHarness, turnId: String, work: String, owner: CoroutineScope = process) = owner.async {
        h.core.orchestrator.run(VoiceTurnRequest(turnId, VoiceOrigin.PHONE, TestAudio.speechWav(), "audio/wav", sink, routing = TurnRouting.Direct(work)))
    }

    private fun chat(h: CoreHarness, work: String, owner: CoroutineScope = process) = owner.async { h.core.chat.send(work, "hello") }

    private fun held(h: CoreHarness, work: String) = waitFor("the server holding the turn") { h.fake.heldTurns.containsKey(work) }

    @Test
    fun `a voice reply finished after the connection dropped is spoken once and the prompt is not sent again`() {
        rig.harness().use { h ->
            val work = existing(h, "work")
            val outcome = voice(h, "r-voice-0001", work)
            held(h, work)
            h.fake.dropGateway()
            settle(700)
            h.fake.finishHeldTurn(work)
            val result = runBlocking { withTimeout(20_000) { outcome.await() } }
            assertTrue("$result", result is VoiceTurnOutcome.Completed)
            assertEquals(listOf("FINAL"), played.filter { it == "FINAL" })
            assertEquals(1, submits(h))
            settle()
            assertTrue("a spoken reply never alerts: $shown", shown.isEmpty())
        }
    }

    @Test
    fun `a text reply finished after the connection dropped is delivered once with one alert`() {
        rig.harness().use { h ->
            val work = existing(h, "work")
            val sent = chat(h, work)
            held(h, work)
            h.fake.dropGateway()
            h.fake.finishHeldTurn(work)
            val result = runBlocking { withTimeout(20_000) { sent.await() } }
            assertTrue("$result", result is ChatSendResult.Replied && result.text == "Done.")
            waitFor("the alert") { shown.isNotEmpty() }
            settle()
            assertEquals("$shown", 1, shown.size)
            assertTrue(shown.single().startsWith("chat-") && shown.single().endsWith("#reply@$work"))
            assertEquals(1, submits(h))
        }
    }

    @Test
    fun `a recovered reply is not alerted again when the app restarts`() {
        val first = rig.harness()
        val work = existing(first, "work")
        val sent = chat(first, work)
        held(first, work)
        first.fake.dropGateway()
        first.fake.finishHeldTurn(work)
        runBlocking { withTimeout(20_000) { sent.await() } }
        waitFor("the alert") { shown.isNotEmpty() }
        first.stop()
        rig.harness(first).use { second ->
            settle(2_500)
            assertEquals("$shown", 1, shown.size)
            assertEquals(1, submits(second))
        }
    }

    @Test
    fun `a text reply the server finished while the app process was gone is alerted once after restart`() {
        val first = rig.harness()
        val work = existing(first, "work")
        chat(first, work)
        held(first, work)
        process.cancel()
        first.stop()
        first.fake.finishHeldTurn(work)
        assertTrue("nothing was delivered while the process was gone: $shown", shown.isEmpty())
        rig.harness(first).use { second ->
            waitFor("the reconciled alert") { shown.isNotEmpty() }
            settle(2_500)
            assertEquals("$shown", 1, shown.size)
            assertTrue(shown.single().startsWith("chat-") && shown.single().endsWith("#reply@$work"))
            assertEquals("the prompt is never sent again", 1, submits(second))
        }
    }

    @Test
    fun `a voice reply the server finished while the app process was gone is alerted once after restart, as not heard`() {
        val first = rig.harness()
        val work = existing(first, "work")
        voice(first, "r-voice-0002", work)
        held(first, work)
        process.cancel()
        first.stop()
        first.fake.finishHeldTurn(work)
        rig.harness(first).use { second ->
            waitFor("the reconciled alert") { shown.isNotEmpty() }
            settle(2_500)
            assertEquals(listOf("r-voice-0002#final@$work"), shown.toList())
            assertEquals(1, submits(second))
            assertTrue("a reply nobody asked to hear now is not spoken on restart: $played", played.none { it == "FINAL" })
        }
    }

    @Test
    fun `a reply the server is still producing is alerted once when it finishes after the restart`() {
        val first = rig.harness()
        val work = existing(first, "work")
        chat(first, work)
        held(first, work)
        process.cancel()
        first.stop()
        rig.harness(first).use { second ->
            settle(1_000)
            assertTrue("$shown", shown.isEmpty())
            second.fake.finishHeldTurn(work)
            waitFor("the reconciled alert") { shown.isNotEmpty() }
            settle(2_500)
            assertEquals("$shown", 1, shown.size)
            assertEquals(1, submits(second))
        }
    }

    @Test
    fun `a server restart between the acceptance and the reconnect is not recovered`() {
        rig.harness().use { h ->
            val work = existing(h, "work")
            val sent = chat(h, work)
            held(h, work)
            h.fake.dropGateway()
            h.fake.replayEpoch = "e2"
            h.fake.finishHeldTurn(work)
            val result = runBlocking { withTimeout(20_000) { sent.await() } }
            assertTrue("$result", result is ChatSendResult.Failed)
            settle()
            assertEquals("R01 policy migration: accepted terminal failure owes one notice, never a guessed reply", 1, shown.size)
            assertTrue(shown.single().endsWith("#notice@$work"))
            assertEquals(1, submits(h))
        }
    }

    @Test
    fun `events the server no longer holds are not guessed at`() {
        rig.harness().use { h ->
            val work = existing(h, "work")
            val sent = chat(h, work)
            held(h, work)
            h.fake.dropGateway()
            h.fake.finishHeldTurn(work)
            h.fake.evictRing("rt-$work")
            val result = runBlocking { withTimeout(20_000) { sent.await() } }
            assertTrue("$result", result is ChatSendResult.Failed)
            settle()
            assertEquals("R01 policy migration: accepted terminal failure owes one notice, never a guessed reply", 1, shown.size)
            assertTrue(shown.single().endsWith("#notice@$work"))
            assertEquals(1, submits(h))
        }
    }

    @Test
    fun `an unrelated assistant row is never taken for the reply of an accepted turn`() {
        val first = rig.harness()
        val work = existing(first, "work")
        chat(first, work)
        held(first, work)
        process.cancel()
        first.stop()
        first.fake.injectRow(work, "assistant", "Unrelated earlier-looking answer")
        rig.harness(first).use { second ->
            settle(3_000)
            assertTrue("$shown", shown.isEmpty())
            assertEquals(1, submits(second))
        }
    }


    @Test fun R01_liveVoiceEpochFailureHandsOffOneExactNoticeWithoutFinalAudio() {
        rig.harness().use { h ->
            val work = existing(h, "work")
            val sent = voice(h, "r-live-voice-epoch", work)
            held(h, work)
            h.fake.replayEpoch = "e2"
            h.fake.dropGateway()
            val result = runBlocking { withTimeout(20_000) { sent.await() } }
            assertTrue("$result", result is VoiceTurnOutcome.DeliveredResponseFailed)
            waitFor("live voice terminal notice") { shown.isNotEmpty() }
            assertEquals(listOf("r-live-voice-epoch#notice@$work"), shown.toList())
            assertTrue(played.none { it == "FINAL" })
            assertEquals(1, submits(h))
        }
    }

    @Test fun R01_liveRemoteFailureIsNotClientCancellation() {
        rig.harness().use { h ->
            val work = existing(h, "work")
            val sent = chat(h, work)
            held(h, work)
            h.fake.pushLaterTurn(work, "error" to org.json.JSONObject().put("message", "synthetic terminal failure"), withStart = false)
            val result = runBlocking { withTimeout(20_000) { sent.await() } }
            assertTrue(result is ChatSendResult.Failed && result.acceptanceObserved)
            waitFor("live terminal notice") { shown.isNotEmpty() }
            assertTrue(shown.single().endsWith("#notice@$work"))
            assertEquals(1, submits(h))
        }
    }

    @Test fun R01_refusedAndThrowingLiveNoticesStayOwedAndRetryAfterReconstruction() {
        for (throws in listOf(false, true)) {
            rig.accept = false
            rig.throwing = throws
            val first = rig.harness()
            val work = existing(first, "work")
            val sent = chat(first, work)
            held(first, work)
            first.fake.replayEpoch = "e2"
            first.fake.dropGateway()
            runBlocking { withTimeout(20_000) { sent.await() } }
            settle(500)
            val owed = com.rumi.hermesvoice.core.net.AcceptedTurnStore(first.store).pending()
            assertEquals("refused/throwing live handoff must leave accepted authority owed", 1, owed.size)
            val identity = owed.single().id + "#notice@" + work
            first.stop()
            rig.accept = true
            rig.throwing = false
            rig.harness(first).use { second ->
                waitFor("reconstructed notice") { identity in shown }
                second.core.reconcileAccepted()
                settle(500)
                assertEquals(1, shown.count { it == identity })
                assertEquals(1, submits(second))
                assertTrue(com.rumi.hermesvoice.core.net.AcceptedTurnStore(second.store).pending().isEmpty())
            }
        }
    }

    @Test fun R01_cancelledLiveTextWaitDoesNotEmitFailureNotice() {
        rig.harness().use { h ->
            val work = existing(h, "work")
            val sent = chat(h, work)
            held(h, work)
            runBlocking { sent.cancel(); sent.join() }
            settle(300)
            assertTrue(shown.isEmpty())
            assertEquals(1, submits(h))
        }
    }

    @Test
    fun `a Stop on a voice turn is not revived by the restart`() {
        val first = rig.harness()
        val work = existing(first, "work")
        voice(first, "r-voice-0003", work)
        held(first, work)
        assertTrue(first.core.orchestrator.stopTurn("r-voice-0003"))
        settle(500)
        first.stop()
        first.fake.finishHeldTurn(work)
        rig.harness(first).use { second ->
            settle(3_000)
            assertTrue("$shown", shown.isEmpty())
            assertEquals(1, submits(second))
        }
    }
}
