package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.net.*
import com.rumi.hermesvoice.core.voice.RecipientEvent
import org.junit.Assert.*

/** Explicit test-only migration oracle: queued ACK -> exact bounded refusal, never a neighbour.
 * Streaming after unresolved work -> exact lost-attribution refusal, never inferred from text or idle shape. */
internal suspend fun r7Refused(turn: SubmittedTurn) {
    val finals = mutableListOf<String>()
    val error = try {
        turn.collect(3_000) { if (it is RecipientEvent.Complete) finals += it.text }; null
    } catch (e: HermesException) { e } finally { turn.release() }
    assertEquals("NEIGHBOUR or own final must not be adopted without admission proof", emptyList<String>(), finals)
    assertNull("unproved request never becomes durable acceptance", turn.acceptance)
    val expected = when (turn.submitStatus) {
        "queued" -> "HermesQueuedAttributionException"
        "streaming" -> "HermesReplyUnrecoverableException"
        else -> throw AssertionError("unexpected migrated status ${turn.submitStatus}")
    }
    assertEquals("exact fail-closed outcome, not any exception", expected, error?.javaClass?.simpleName)
    if (turn.submitStatus == "queued") assertFalse("no armed queued collector", turn.attributable)
}
internal suspend fun r7LegacyRefused(port: HermesConversationPort, stored: String, acceptance: TurnAcceptance) {
    val finals = mutableListOf<String>()
    val error = try {
        val t = port.reattach(stored, acceptance)
        try { t.collect(3_000) { if (it is RecipientEvent.Complete) finals += it.text } } finally { t.release() }
        null
    } catch (e: HermesException) { e }
    assertEquals("NEIGHBOUR legacy acceptance must not be upgraded", emptyList<String>(), finals)
    assertEquals("HermesReplyUnrecoverableException", error?.javaClass?.simpleName)
}
internal const val R7_QUEUE_NOTICE = "Hermes accepted this message while busy, but this protocol cannot identify its queued reply. Open the conversation to check it; do not retry this request."
