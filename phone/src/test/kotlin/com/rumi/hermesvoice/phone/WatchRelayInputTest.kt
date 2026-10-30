package com.rumi.hermesvoice.phone

import android.media.AudioFormat
import com.rumi.hermesvoice.core.live.*
import com.rumi.hermesvoice.phone.live.WatchRelayInput
import org.junit.Test
import org.junit.Assert.*
import java.nio.ByteBuffer

class WatchRelayInputTest {
    /** R40: 20 ms Watch packets of 16 kHz mono; the pinned ADM takes them as 10 ms (320-byte) native buffers. */
    private fun frame(seq:Long=1,value:Byte=7)=LivePcmFrame(seq,16000,1,ByteArray(640){value})
    private fun read(input:WatchRelayInput):ByteArray {val b=ByteBuffer.allocateDirect(320);input.fill(b,AudioFormat.ENCODING_PCM_16BIT,1,16000);return ByteArray(320).also{b.duplicate().apply{clear();get(it)}}}
    @Test fun realBytesReachTheExternalNativeBufferInOrderAndDuplicateOrSkippedFramesAreRefused(){
        var current=true;val source=WatchRelayInput({current},{fail("valid frames must not fail")})
        assertTrue(source.offer(frame(1,3)));assertTrue(source.offer(frame(2,4)))
        for(value in listOf<Byte>(3,3,4,4))assertArrayEquals(ByteArray(320){value},read(source))
        assertFalse(source.offer(frame(2)));assertFalse(source.offer(frame(4)));source.close();current=false
    }
    @Test fun muteThenUnmuteCannotReplayQueuedOrLateAudioFromAnOlderCaptureEpoch(){
        val source=WatchRelayInput({true},{fail("muted input is not a transport error")})
        assertTrue(source.offer(frame(1,8)));source.mute(true);assertArrayEquals(ByteArray(320),read(source));source.mute(false)
        assertTrue(source.offer(frame(2,9)));assertTrue(source.offer(frame(WatchRelayAudio.sequence(2,1),5)))
        assertArrayEquals(ByteArray(320){5},read(source));source.close()
    }
    @Test fun boundedCongestionIsAnExplicitRefusalNeverDropOldAudioThenPretendDelivery(){
        val source=WatchRelayInput({true},{})
        repeat(16){assertTrue(source.offer(frame((it+1).toLong())))};assertFalse(source.offer(frame(17)));source.close() // 320 ms, as before
    }
    @Test fun revokedOwnershipAndUnsupportedFormatsNeverProduceUserInput(){
        var current=true;var refused=0;val source=WatchRelayInput({current},{refused++})
        assertFalse(source.offer(frame().copy(sampleRate=24000)));assertFalse(source.offer(frame(WatchRelayAudio.sequence(1,1))))
        assertTrue(source.offer(frame()));current=false;assertArrayEquals(ByteArray(320),read(source));assertFalse(source.offer(frame(2)))
        source.fill(ByteBuffer.allocateDirect(320),AudioFormat.ENCODING_PCM_16BIT,2,16000);assertEquals(1,refused);source.close()
    }
}
