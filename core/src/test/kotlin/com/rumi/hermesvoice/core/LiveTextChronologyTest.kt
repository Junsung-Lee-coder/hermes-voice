package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.live.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

/** The public timed-delta protocol has event identity, but no completed-turn or item event. */
class LiveTextChronologyTest {
    private fun delta(input: Boolean, text: String, start: Long, end: Long, id: String) = JSONObject()
        .put("type", if (input) "session.input_transcript.delta" else "session.output_transcript.delta")
        .put("delta", text).put("start_ms", start).put("end_ms", end).put("event_id", id).toString()
    private fun request(d: LiveDialogue): LiveRequest? = (d.receive(JSONObject().put("type", "session.delegation.created").put("offset_ms", 1000)
        .put("delegation", JSONObject().put("type", "delegation").put("target", "client").put("id", "d")).toString()) as? LiveEvent.Confirmation)?.request

    @Test fun duplicateEventIdentityDoesNotRepeatDisplayOrRequest() {
        val d=LiveDialogue("c","s"); val raw=delta(true,"alpha 에 작업",0,100,"e")
        d.receive(raw); assertNull("same event is not new activity",d.receive(raw))
        assertEquals("alpha 에 작업",request(d)!!.text)
    }
    @Test fun distinctEventsRetainGenuinelyRepeatedWordsAndSpaces() {
        val d=LiveDialogue("c","s")
        d.receive(delta(true,"go ",0,100,"a"));d.receive(delta(true,"go ",100,200,"b"))
        val event=d.receive(delta(true,"again",200,300,"c")) as LiveEvent.Transcript
        assertEquals("go go again",event.text)
    }
    @Test fun unevenArrivalUsesTimelineRatherThanCallbackOrder() {
        val d=LiveDialogue("c","s")
        d.receive(delta(true,"later",200,300,"late"))
        val event=d.receive(delta(true,"first ",0,100,"first")) as LiveEvent.Transcript
        assertEquals("first later",event.text);assertEquals("first later",request(d)!!.text)
    }
    @Test fun interleavedSpeakersHaveChronologicalContextWithoutCumulativeDuplication() {
        val d=LiveDialogue("c","s")
        d.receive(delta(true,"second",200,300,"c"));d.receive(delta(false,"answer",100,200,"b"));d.receive(delta(true,"first ",0,100,"a"))
        assertEquals("User: first \nAssistant: answer\nUser: second",request(d)!!.context)
    }
    @Test fun changedTextWithSameIdentityIsNotNewAuthority() {
        val d=LiveDialogue("c","s")
        d.receive(delta(false,"hello",0,100,"same"));d.receive(delta(true,"alpha do work",0,100,"same"))
        assertNull(request(d))
    }
    @Test fun identicalTextAtDistinctIntervalsIsNotContentDeduped() {
        val d=LiveDialogue("c","s")
        d.receive(delta(true,"yes ",0,100,"a"));d.receive(delta(false,"question",100,200,"b"));d.receive(delta(true,"yes ",200,300,"c"))
        assertEquals("yes yes",request(d)!!.text)
    }
    @Test fun malformedOrOverlongDeltaIsRejectedWholeNotUnicodeClipped() {
        val d=LiveDialogue("c","s")
        assertNull(d.receive(delta(true,"😀".repeat(4000),0,100,"huge")))
        assertNull(request(d))
    }
    @Test fun closedDialogueCannotReceiveOrConfirmOldText() {
        val d=LiveDialogue("c","s");d.receive(delta(true,"alpha work",0,100,"e"));request(d);d.close()
        assertNull(d.confirm("d","s","alpha","work"));assertNull(d.receive(delta(true,"new",100,200,"new")))
    }
}
