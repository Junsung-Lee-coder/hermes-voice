package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.live.*
import org.junit.Test
import org.junit.Assert.*

class LiveWakeContextTest {
    private fun delegation(id:String)="{\"type\":\"session.delegation.created\",\"offset_ms\":100,\"delegation\":{\"id\":\"$id\",\"type\":\"delegation\",\"target\":\"client\"}}"
    @Test fun finalRecognizerInputIsAppContextNotInventedProviderTaskFieldsAndIsConsumedOnce(){
        val d=LiveDialogue("call","scope","wake request")
        val request=(d.receive(delegation("d")) as LiveEvent.Confirmation).request
        assertEquals("wake request",request.text);assertTrue(request.context.contains("wake request"))
        assertNotNull(d.confirm("d","scope","allowed-id","wake request"))
        assertNull(d.receive(delegation("d2")))
    }
    @Test fun aHumanLiteralSilentMarkerIsNotAnAssistantControl(){
        val d=LiveDialogue("call","scope","NO_REPLY")
        assertTrue(d.receive(delegation("d")) is LiveEvent.Confirmation)
        assertEquals("NO_REPLY",d.confirm("d","scope","allowed-id","NO_REPLY")!!.text)
    }
}
