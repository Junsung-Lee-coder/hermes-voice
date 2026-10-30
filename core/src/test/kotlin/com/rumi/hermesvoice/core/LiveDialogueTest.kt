package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.live.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

class LiveDialogueTest {
    private fun input(text: String, start: Int = 0, end: Int = 100) = JSONObject().put("type", "session.input_transcript.delta").put("delta", text).put("start_ms", start).put("end_ms", end).toString()
    private fun delegation(id: String = "d1", offset: Int = 200) = JSONObject().put("type", "session.delegation.created").put("offset_ms", offset).put("delegation", JSONObject().put("id", id).put("type", "delegation").put("target", "client")).toString()
    @Test fun transcriptAndDelegationNeverSubmitUntilExplicitBoundConfirmation() {
        val d = LiveDialogue("call", "scope")
        d.receive(input("금요일 파일을 확인해 주세요"))
        val pending = d.receive(delegation()) as LiveEvent.Confirmation
        assertEquals("금요일 파일을 확인해 주세요", pending.request.text)
        assertNull(d.confirm("wrong", "scope", "alpha", "요청"))
        assertNull(d.confirm("d1", "changed", "alpha", "요청"))
        val bound = d.confirm("d1", "scope", "alpha", "금요일 파일을 확인해 주세요")!!
        assertEquals("alpha", bound.sessionId)
        assertEquals("d1", bound.delegationId)
        assertNull(d.confirm("d1", "scope", "beta", "다시"))
        assertNull(d.receive(delegation()))
    }
    @Test fun casualOrQuotedAssistantSpeechIsNeverARouteCommand() {
        val d = LiveDialogue("call", "scope")
        val out = JSONObject(input("alpha")).put("type", "session.output_transcript.delta").toString()
        assertTrue(d.receive(out) is LiveEvent.Transcript)
        assertNull(d.receive(delegation()))
        assertNull(d.confirm("d1", "scope", "alpha", "request"))
    }
    @Test fun providerTargetContainsNoRequestOrSessionAndResponsesModeIsRejected() {
        val d = LiveDialogue("c", "s")
        assertNull(d.receive(delegation()))
        d.receive(input("목요일, 아니 금요일"))
        assertNull(d.receive(delegation("d2").replace("\"client\"", "\"responses\"")))
        val p = d.receive(delegation("d3")) as LiveEvent.Confirmation
        assertEquals("목요일, 아니 금요일", p.request.text)
        assertTrue(p.request.context.contains("User:"))
    }
    @Test fun noPartialBeyondOffsetAndNoEmptyOrControlSubmission() {
        val d = LiveDialogue("c", "s")
        d.receive(input("future", 300, 400)); assertNull(d.receive(delegation()))
        assertNull(d.confirm("d1", "s", "a", "NO_REPLY"))
        assertNull(d.receive("{bad"))
        assertNull(d.receive(input("bad", -1, 3)))
    }
    @Test fun endRejectsEveryLateEventAndConfirmation() {
        val d = LiveDialogue("c", "s")
        d.receive(input("work")); d.receive(delegation()); d.close()
        assertNull(d.confirm("d1", "s", "alpha", "work"))
        assertNull(d.receive(input("late"))); assertNull(d.receive(delegation("late")))
    }
    @Test fun appendsAreUtf8BoundedAndInjectionAckIsNotPlayback() {
        val packets = LiveWire.append("commentary", "d", "한글😀 ".repeat(1000))
        assertTrue(packets.size > 1)
        assertTrue(packets.all { JSONObject(it).getString("content").toByteArray(Charsets.UTF_8).size <= 480 })
        assertEquals("한글😀 ".repeat(1000), packets.joinToString("") { JSONObject(it).getString("content") })
        assertNull(LiveDialogue("c", "s").receive("{\"type\":\"session.commentary.appended\",\"client_event_id\":\"x\"}"))
    }
    @Test fun startedAndIndependentInputOutputStatesAndNoWebsocketStart() {
        val d = LiveDialogue("c", "s")
        assertTrue(d.receive("{\"type\":\"session.started\",\"session\":{\"id\":\"p\"}}") is LiveEvent.Started)
        assertEquals("session.close", JSONObject(LiveWire.close()).getString("type"))
        assertFalse(LiveWire.instructions("hello").contains("session.start"))
    }
}
