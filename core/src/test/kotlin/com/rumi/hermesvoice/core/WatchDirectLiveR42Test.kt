package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.live.LiveDialogue
import com.rumi.hermesvoice.core.live.LiveWire
import com.rumi.hermesvoice.core.live.WatchDirectLive
import com.rumi.hermesvoice.core.live.WatchLiveWire
import com.rumi.hermesvoice.core.live.WatchRelayAudio
import org.junit.Assert.*
import org.junit.Test

/** R42 (branch B): the Watch hands on exactly the provider event types the Phone's real parsers act on, and the Phone accepts only an SDP
 *  offer for the session request. Pure JVM over the shared parsers; says nothing about a device network. */
class WatchDirectLiveR42Test {
    private fun consumed(raw: String) = LiveDialogue("call", "scope", "wake words").receive(raw) != null || LiveWire.appended(raw) != null
    /** One valid event per type the Phone acts on. */
    private val samples = mapOf(
        "session.started" to "{\"type\":\"session.started\",\"session\":{\"id\":\"s\"}}",
        "session.closed" to "{\"type\":\"session.closed\"}",
        "error" to "{\"type\":\"error\",\"error\":{}}",
        "session.input_transcript.delta" to "{\"type\":\"session.input_transcript.delta\",\"event_id\":\"a\",\"start_ms\":0,\"end_ms\":10,\"delta\":\"안녕\"}",
        "session.output_transcript.delta" to "{\"type\":\"session.output_transcript.delta\",\"event_id\":\"b\",\"start_ms\":10,\"end_ms\":20,\"delta\":\"네\"}",
        "session.delegation.created" to "{\"type\":\"session.delegation.created\",\"delegation\":{\"type\":\"delegation\",\"target\":\"client\",\"id\":\"d\"},\"offset_ms\":30}",
        "session.instructions.appended" to "{\"type\":\"session.instructions.appended\",\"client_event_id\":\"i\"}",
        "session.thinking.appended" to "{\"type\":\"session.thinking.appended\",\"client_event_id\":\"t\"}",
        "session.commentary.appended" to "{\"type\":\"session.commentary.appended\",\"client_event_id\":\"c\"}")

    @Test fun everyTypeHandedOnIsOneThePhoneActsOnAndEveryConsumedSampleIsHandedOn() {
        assertEquals("one consumed sample per forwarded type, no extra type", WatchDirectLive.PHONE_EVENTS, samples.keys)
        for ((type, raw) in samples) {
            assertTrue("$type is consumed by the Phone's parsers", consumed(raw))
            assertTrue("$type is handed on", WatchDirectLive.forwarded(raw))
        }
        for (raw in listOf("{\"type\":\"session.updated\"}", "{\"type\":\"rate_limits.updated\"}", "{\"type\":\"session.input_audio_buffer.speech_started\"}",
                "{\"type\":\"session.created\"}", "{}", "not json", "")) {
            assertFalse("$raw stays on the Watch", WatchDirectLive.forwarded(raw))
            assertFalse("and the Phone would not act on it", consumed(raw))
        }
    }

    @Test fun onlyABoundedSdpOfferIsASessionOffer() {
        assertTrue(WatchDirectLive.offer("v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\n"))
        assertFalse("the relay's version word is not an offer", WatchDirectLive.offer(WatchRelayAudio.OFFER))
        assertFalse(WatchDirectLive.offer("")); assertFalse(WatchDirectLive.offer("phone-relay-ready"))
        assertFalse("beyond the paired control's payload bound", WatchDirectLive.offer("v=0" + "a".repeat(WatchLiveWire.MAX_PAYLOAD)))
        assertTrue(WatchDirectLive.offer("v=0" + "a".repeat(WatchLiveWire.MAX_PAYLOAD - 3)))
    }
}
