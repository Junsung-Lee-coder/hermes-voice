package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.net.GatewayConversationPort
import com.rumi.hermesvoice.core.net.GatewayEvent
import com.rumi.hermesvoice.core.voice.RecipientEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The own-response wait through the PRODUCTION [GatewayConversationPort] on a virtual clock (no real waiting): the bound given to
 * `collect` is an INACTIVITY bound. Real gateway events (start, delta, interim) restart it; pings and the time spent
 * speaking a piece do not count against it; a healthy response is never cut by a total time. A contract double, not a live Hermes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResponseInactivityTest {
    private val timeout = 60_000L

    private suspend fun TestScope.open(): Triple<ScriptedGateway, kotlinx.coroutines.channels.Channel<GatewayEvent>, com.rumi.hermesvoice.core.net.SubmittedTurn> {
        val gateway = ScriptedGateway()
        val turn = GatewayConversationPort { gateway }.submit("work", "hello", emptyList())
        return Triple(gateway, gateway.channels.getValue("rt-work"), turn)
    }

    private fun delta(text: String) = GatewayEvent("message.delta", "rt-work", JSONObject().put("text", text))

    @Test
    fun `a response that keeps producing events for far longer than the bound is not cut`() = runTest {
        val (gateway, channel, turn) = open()
        val seen = ArrayList<RecipientEvent>()
        val collecting = async { turn.collect(timeout) { seen += it } }
        launch {
            channel.send(gateway.start("rt-work"))
            repeat(40) { delay(timeout - 1); channel.send(delta("part $it")) }
            delay(timeout - 1)
            channel.send(gateway.complete("rt-work", "the whole answer"))
        }
        collecting.await()
        assertTrue("ran ${currentTime}ms, far beyond one bound of $timeout", currentTime > 40 * timeout)
        assertEquals("the whole answer", (seen.last() as RecipientEvent.Complete).text)
    }

    @Test
    fun `silence for the bound after the last event ends the wait at exactly the bound`() = runTest {
        val (gateway, channel, turn) = open()
        val collecting = async { runCatching { turn.collect(timeout) { } } }
        channel.send(gateway.start("rt-work"))
        runCurrent()
        advanceTimeBy(timeout - 1); runCurrent()
        assertFalse(collecting.isCompleted)
        advanceTimeBy(1); runCurrent()
        val failure = collecting.await().exceptionOrNull()
        assertTrue("$failure", failure is HermesProtocolException)
        assertEquals(timeout, currentTime)
        assertEquals("the subscription is released", listOf("rt-work"), gateway.closed)
    }

    @Test
    fun `pings and heartbeats are not progress`() = runTest {
        val (gateway, channel, turn) = open()
        var endedAt = -1L
        val collecting = async { runCatching { turn.collect(timeout) { } }.also { endedAt = currentTime } }
        channel.send(gateway.start("rt-work"))
        launch { repeat(100) { delay(5_000); channel.trySend(GatewayEvent(listOf("ping", "heartbeat", "keepalive", "PONG")[it % 4], "rt-work", null)) } }
        advanceUntilIdle()
        assertTrue(collecting.await().exceptionOrNull() is HermesProtocolException)
        assertEquals("cut after one bound of silence from real events, not extended by pings", timeout, endedAt)
    }

    @Test
    fun `time spent speaking or playing a piece is not counted against the bound`() = runTest {
        val (gateway, channel, turn) = open()
        val seen = ArrayList<String>()
        val collecting = async {
            turn.collect(timeout) { event ->
                delay(10 * 60_000L)
                seen += if (event is RecipientEvent.Complete) "final" else "interim"
            }
        }
        channel.send(gateway.start("rt-work"))
        channel.send(GatewayEvent("message.interim", "rt-work", JSONObject().put("text", "first")))
        channel.send(gateway.complete("rt-work", "final text"))
        collecting.await()
        assertEquals(listOf("interim", "final"), seen)
        assertEquals(20 * 60_000L, currentTime)
    }

    @Test
    fun `Stop while waiting ends the wait at once and releases the subscription exactly once`() = runTest {
        val (gateway, channel, turn) = open()
        val collecting = async { runCatching { turn.collect(timeout) { } } }
        channel.send(gateway.start("rt-work"))
        runCurrent()
        advanceTimeBy(30_000)
        collecting.cancel()
        runCurrent()
        assertEquals(30_000L, currentTime)
        assertEquals(listOf("rt-work"), gateway.closed)
    }

    @Test
    fun `a true stream error or disconnect ends the wait at once, not at the bound`() = runTest {
        val (gateway, channel, turn) = open()
        val collecting = async { runCatching { turn.collect(timeout) { } } }
        channel.send(gateway.start("rt-work"))
        runCurrent()
        advanceTimeBy(1_000)
        channel.close()
        runCurrent()
        assertTrue(collecting.await().exceptionOrNull() is HermesProtocolException)
        assertEquals(1_000L, currentTime)
        val (gateway2, channel2, turn2) = open()
        val errored = async { runCatching { turn2.collect(timeout) { } } }
        channel2.send(gateway2.start("rt-work"))
        channel2.send(GatewayEvent("error", "rt-work", JSONObject().put("message", "boom")))
        runCurrent()
        assertTrue(errored.await().exceptionOrNull() is HermesProtocolException)
        assertEquals(1_000L, currentTime)
    }
}
