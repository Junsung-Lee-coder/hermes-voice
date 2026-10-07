package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.net.GatewayConversationPort
import com.rumi.hermesvoice.core.net.GatewayEvent
import com.rumi.hermesvoice.core.net.GatewayRpc
import com.rumi.hermesvoice.core.net.GatewaySubscription
import com.rumi.hermesvoice.core.net.LaterEnd
import com.rumi.hermesvoice.core.net.SubmittedTurn
import java.util.Collections
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A gateway whose subscriptions are real [GatewaySubscription]s fed by the test on a virtual clock (a contract double, not a live Hermes). */
internal class ScriptedGateway : GatewayRpc {
    val channels = java.util.concurrent.ConcurrentHashMap<String, Channel<GatewayEvent>>()
    val closed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile var onSubmit: (String) -> Unit = { }

    override suspend fun call(method: String, params: JSONObject, timeoutMs: Long): JSONObject = when (method) {
        "session.resume" -> JSONObject().put("session_id", "rt-" + params.getString("session_id"))
        "prompt.submit" -> JSONObject().put("status", "streaming").also { onSubmit(params.getString("session_id")) }
        else -> error("unexpected $method")
    }

    override fun subscribe(sessionId: String): GatewaySubscription {
        val channel = Channel<GatewayEvent>(Channel.UNLIMITED)
        channels[sessionId] = channel
        return GatewaySubscription(sessionId, channel) { closed += sessionId }
    }

    fun start(session: String) = GatewayEvent("message.start", session, JSONObject())
    fun complete(session: String, text: String) = GatewayEvent("message.complete", session, JSONObject().put("text", text).put("status", "complete"))
}

/**
 * The exact cutoff of the later-reply follow, through the PRODUCTION [GatewayConversationPort] / `GatewaySubmittedTurn.collectLater`
 * (kotlinx `withTimeoutOrNull(windowMs)`) on a virtual clock: no real waiting, no sleeping. Offsets are milliseconds after
 * `collectLater` was called. The window is exclusive at its deadline: a reply whose completion lands at offset window-1 is admitted;
 * one landing at offset window (the instant the timeout fires) or later is not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LaterFollowCutoffTest {
    private class Outcome(val end: LaterEnd, val endedAt: Long, val admitted: List<Pair<String, Long>>, val sent: Map<String, Boolean>, val gateway: ScriptedGateway)

    /**
     * One own reply (done at once), then `collectLater(windowMs)`, with a later turn's message.start at [startAt] and its
     * message.complete at [completeAt] (offsets). [senderFirst]: the senders are scheduled BEFORE the follow (so on a tie they
     * come first in the scheduler's queue); otherwise after it.
     */
    private suspend fun TestScope.scenario(windowMs: Long, startAt: Long, completeAt: Long, senderFirst: Boolean): Outcome {
        val gateway = ScriptedGateway()
        val turn = GatewayConversationPort { gateway }.submit("work", "hello", emptyList())
        val channel = gateway.channels.getValue("rt-work")
        channel.send(gateway.start("rt-work")); channel.send(gateway.complete("rt-work", "own reply"))
        turn.collect(1_000) { }
        val t0 = currentTime
        val admitted = Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
        val sent = Collections.synchronizedMap(linkedMapOf<String, Boolean>())
        fun senders() {
            launch { delay(startAt); sent["start"] = channel.trySend(gateway.start("rt-work")).isSuccess }
            launch { delay(completeAt); sent["complete"] = channel.trySend(gateway.complete("rt-work", "later reply")).isSuccess }
        }
        if (senderFirst) senders()
        val follow = async { turn.collectLater(windowMs) { admitted += it.text to (currentTime - t0) } }
        if (!senderFirst) senders()
        val end = follow.await()
        val endedAt = currentTime - t0
        advanceUntilIdle()
        return Outcome(end, endedAt, admitted.toList(), sent.toMap(), gateway)
    }

    private val windows = listOf(1, 30, 4320).map { it to it * 60_000L }

    @Test
    fun `a reply that completes one millisecond before the deadline is admitted once, then the window ends exactly at the deadline`() = runTest {
        for ((minutes, window) in windows) for (senderFirst in listOf(true, false)) {
            val o = scenario(window, startAt = window - 2, completeAt = window - 1, senderFirst = senderFirst)
            val label = "$minutes min senderFirst=$senderFirst"
            assertEquals(label, listOf("later reply" to window - 1), o.admitted)
            assertEquals(label, LaterEnd.WINDOW, o.end)
            assertEquals("$label: ended at the deadline, on the virtual clock", window, o.endedAt)
            assertEquals(label, listOf("rt-work"), o.gateway.closed)
        }
    }

    @Test
    fun `a reply that completes exactly at the deadline is not admitted - the timeout fires at the deadline, whichever was scheduled first`() = runTest {
        for ((minutes, window) in windows) for (senderFirst in listOf(true, false)) {
            val o = scenario(window, startAt = window - 1, completeAt = window, senderFirst = senderFirst)
            val label = "$minutes min senderFirst=$senderFirst"
            assertEquals("$label: nothing admitted at the cutoff instant", emptyList<Pair<String, Long>>(), o.admitted)
            assertEquals(label, LaterEnd.WINDOW, o.end)
            assertEquals(label, window, o.endedAt)
            assertEquals(label, listOf("rt-work"), o.gateway.closed)
        }
    }

    @Test
    fun `a reply that completes one millisecond after the deadline is not admitted and its frame meets a closed subscription`() = runTest {
        for ((minutes, window) in windows) for (senderFirst in listOf(true, false)) {
            val o = scenario(window, startAt = window - 1, completeAt = window + 1, senderFirst = senderFirst)
            val label = "$minutes min senderFirst=$senderFirst"
            assertEquals(label, emptyList<Pair<String, Long>>(), o.admitted)
            assertEquals(label, LaterEnd.WINDOW, o.end)
            assertEquals(label, window, o.endedAt)
            assertEquals("$label: the late complete was refused by the released subscription", false, o.sent["complete"])
            assertEquals(label, listOf("rt-work"), o.gateway.closed)
        }
    }

    @Test
    fun `a turn that starts at the deadline and completes after it is not admitted either`() = runTest {
        for ((minutes, window) in windows) {
            val o = scenario(window, startAt = window, completeAt = window + 1, senderFirst = true)
            assertEquals("$minutes min", emptyList<Pair<String, Long>>(), o.admitted)
            assertEquals("$minutes min", LaterEnd.WINDOW, o.end)
            assertEquals("$minutes min", false, o.sent["start"] == true && o.sent["complete"] == true)
        }
    }

    @Test
    fun `a reply admitted before the deadline does not extend the window, and nothing after it is admitted`() = runTest {
        val gateway = ScriptedGateway()
        val turn = GatewayConversationPort { gateway }.submit("work", "hello", emptyList())
        val channel = gateway.channels.getValue("rt-work")
        channel.send(gateway.start("rt-work")); channel.send(gateway.complete("rt-work", "own"))
        turn.collect(1_000) { }
        val window = 60_000L
        val t0 = currentTime
        val admitted = Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
        val follow = async { turn.collectLater(window) { admitted += it.text to (currentTime - t0) } }
        launch {
            delay(10_000); channel.trySend(gateway.start("rt-work")); channel.trySend(gateway.complete("rt-work", "first"))
            delay(49_999); channel.trySend(gateway.start("rt-work")); channel.trySend(gateway.complete("rt-work", "second"))
            delay(1); channel.trySend(gateway.start("rt-work")); channel.trySend(gateway.complete("rt-work", "third"))
        }
        assertEquals(LaterEnd.WINDOW, follow.await())
        advanceUntilIdle()
        assertEquals(listOf("first" to 10_000L, "second" to 59_999L), admitted.toList())
        assertEquals(window, currentTime - t0)
    }

    @Test
    fun `ending one session's follow at its deadline leaves another session's later follow and subscription untouched`() = runTest {
        val gateway = ScriptedGateway()
        val port = GatewayConversationPort { gateway }
        val work = port.submit("work", "a", emptyList())
        val workChannel = gateway.channels.getValue("rt-work")
        val home = port.submit("home", "b", emptyList())
        val homeChannel = gateway.channels.getValue("rt-home")
        for ((channel, session) in listOf(workChannel to "rt-work", homeChannel to "rt-home")) {
            channel.send(gateway.start(session)); channel.send(gateway.complete(session, "own"))
        }
        work.collect(1_000) { }; home.collect(1_000) { }
        val t0 = currentTime
        val workAdmitted = Collections.synchronizedList(mutableListOf<String>())
        val homeAdmitted = Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
        val workFollow = async { work.collectLater(60_000) { workAdmitted += it.text } }
        val homeFollow = async { home.collectLater(1_800_000) { homeAdmitted += it.text to (currentTime - t0) } }
        launch {
            delay(60_000)
            assertEquals(LaterEnd.WINDOW, workFollow.await())
            assertEquals("only the ended follow's subscription was released", listOf("rt-work"), gateway.closed.toList())
            homeChannel.trySend(gateway.start("rt-home")); homeChannel.trySend(gateway.complete("rt-home", "home later"))
            assertFalse("the ended session's subscription refuses frames", workChannel.trySend(gateway.complete("rt-work", "x")).isSuccess)
        }
        launch { delay(1_799_999); homeChannel.trySend(gateway.start("rt-home")); homeChannel.trySend(gateway.complete("rt-home", "home last")) }
        assertEquals(LaterEnd.WINDOW, homeFollow.await())
        advanceUntilIdle()
        assertEquals(emptyList<String>(), workAdmitted.toList())
        assertEquals(listOf("home later" to 60_000L, "home last" to 1_799_999L), homeAdmitted.toList())
        assertEquals(listOf("rt-work", "rt-home"), gateway.closed.toList())
        assertEquals(1_800_000L, currentTime - t0)
    }

    @Test
    fun `a newer submit to the same session ends the earlier follow as superseded before its deadline and no later frame is admitted to it`() = runTest {
        val gateway = ScriptedGateway()
        val port = GatewayConversationPort { gateway }
        val first: SubmittedTurn = port.submit("work", "a", emptyList())
        val firstChannel = gateway.channels.getValue("rt-work")
        firstChannel.send(gateway.start("rt-work")); firstChannel.send(gateway.complete("rt-work", "own"))
        first.collect(1_000) { }
        val admitted = Collections.synchronizedList(mutableListOf<String>())
        val t0 = currentTime
        val follow = async { first.collectLater(3_600_000) { admitted += it.text } }
        launch { delay(5_000); port.submit("work", "newer", emptyList()) }
        assertEquals(LaterEnd.SUPERSEDED, follow.await())
        assertEquals(5_000L, currentTime - t0)
        assertFalse("the superseded subscription refuses frames", firstChannel.trySend(gateway.complete("rt-work", "late")).isSuccess)
        assertEquals(emptyList<String>(), admitted.toList())
    }

    @Test
    fun `cancelling a follow (Stop) releases its subscription at once and admits nothing afterwards`() = runTest {
        val gateway = ScriptedGateway()
        val turn = GatewayConversationPort { gateway }.submit("work", "a", emptyList())
        val channel = gateway.channels.getValue("rt-work")
        channel.send(gateway.start("rt-work")); channel.send(gateway.complete("rt-work", "own"))
        turn.collect(1_000) { }
        val admitted = Collections.synchronizedList(mutableListOf<String>())
        val follow = async { turn.collectLater(259_200_000) { admitted += it.text } }
        launch { delay(1_000); follow.cancel() }
        advanceUntilIdle()
        assertTrue(follow.isCancelled)
        assertEquals(listOf("rt-work"), gateway.closed.toList())
        assertFalse(channel.trySend(gateway.complete("rt-work", "after stop")).isSuccess)
        assertEquals(emptyList<String>(), admitted.toList())
    }
}
