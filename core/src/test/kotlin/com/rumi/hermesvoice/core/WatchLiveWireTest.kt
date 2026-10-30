package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.live.WatchLiveWire
import org.junit.Test
import org.junit.Assert.*

class WatchLiveWireTest {
    @Test fun exactSdpAndUnicodeSurviveTheBoundedAppEnvelope(){
        val text="v=0\r\na=ice:한글\r\n"
        val p=WatchLiveWire.parse(WatchLiveWire.packet("call","media","epoch",1,"offer",text))!!
        assertEquals(text,p.body);assertEquals("offer",p.op);assertEquals(1L,p.seq)
    }
    @Test fun worstCaseEscapingStaysUnderMessageClientLimit(){
        val bytes=WatchLiveWire.packet("call","media","epoch",1,"event","\"".repeat(65536))
        assertTrue(bytes.size<100*1024);assertEquals(65536,WatchLiveWire.parse(bytes)!!.body.length)
    }
    @Test fun oversizeInvalidBase64AndInvalidIdentityFailClosed(){
        assertNull(WatchLiveWire.parse(ByteArray(90001)))
        assertNull(WatchLiveWire.parse("{\"v\":2}".toByteArray()))
        try{WatchLiveWire.packet("call","media","epoch",1,"event","a".repeat(65537));fail()}catch(_:IllegalArgumentException){}
        try{WatchLiveWire.packet("","media","epoch",1,"event");fail()}catch(_:IllegalArgumentException){}
    }
}
