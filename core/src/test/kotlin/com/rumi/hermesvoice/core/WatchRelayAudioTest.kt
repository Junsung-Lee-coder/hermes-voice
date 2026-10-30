package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.live.*
import org.junit.Test
import org.junit.Assert.*
import java.io.*

class WatchRelayAudioTest {
    private fun frame(seq:Long=1,bytes:Int=960)=LivePcmFrame(seq,48000,1,ByteArray(bytes){(it%127).toByte()})
    /** R40: the Watch microphone uplink is 16 kHz mono in 20 ms packets; the provider downlink stays 48 kHz mono. */
    private fun uplink(seq:Long=1,bytes:Int=640)=LivePcmFrame(seq,16000,1,ByteArray(bytes){(it%127).toByte()})
    private fun of(direction:Int)=if(direction==1)uplink() else frame()
    private fun encoded(direction:Int,frame:LivePcmFrame)=ByteArrayOutputStream().also{WatchRelayAudio.write(DataOutputStream(it),direction,frame)}.toByteArray()
    @Test fun bothDirectionsPreserveSampleIdentityAndNeverAcceptTheOppositeDirection(){
        for(direction in 1..2){val f=of(direction);val bytes=encoded(direction,f);val read=WatchRelayAudio.read(DataInputStream(ByteArrayInputStream(bytes)),direction);assertEquals(f.sequence,read.sequence);assertArrayEquals(f.pcm,read.pcm)
            assertThrows(IllegalArgumentException::class.java){WatchRelayAudio.read(DataInputStream(ByteArrayInputStream(bytes)),3-direction)}}
    }
    @Test fun addressBindsAllFourGenerationsAndRejectsTraversalExtraTokensAndOtherProtocols(){
        val a=WatchRelayAudio.Address("control","media","epoch","stream");assertEquals(a,WatchRelayAudio.address(a.path()))
        for(p in listOf(a.path()+"/extra",a.path().replace("control",".."),"/hv/v2/live/pcm/control/media/epoch/stream",a.path().replace("stream","")))assertNull(p,WatchRelayAudio.address(p))
    }
    @Test fun inputAndOutputBoundsFormatsAndTruncationAreFailClosedBeforeAllocation(){
        for(direction in 1..2){
            for(size in listOf(0,1,7681,Int.MAX_VALUE)){
                val out=ByteArrayOutputStream();DataOutputStream(out).apply{writeInt(0x48565233);writeInt(direction);writeLong(1);writeInt(of(direction).sampleRate);writeInt(1);writeInt(size)}
                assertThrows(IllegalArgumentException::class.java){WatchRelayAudio.read(DataInputStream(ByteArrayInputStream(out.toByteArray())),direction)}
            }
            val bytes=encoded(direction,of(direction));assertThrows(EOFException::class.java){WatchRelayAudio.read(DataInputStream(ByteArrayInputStream(bytes.copyOf(bytes.size-1))),direction)}
        }
        assertFalse(WatchRelayAudio.valid(uplink().copy(sampleRate=24000),1));assertFalse(WatchRelayAudio.valid(frame().copy(channels=2),2));assertFalse(WatchRelayAudio.valid(uplink(bytes=638),1))
    }
    @Test fun captureMuteEpochAndPerEpochSequenceCannotAliasEachOther(){
        val first=WatchRelayAudio.sequence(0,1);val resumed=WatchRelayAudio.sequence(2,1)
        assertEquals(1L,first);assertNotEquals(first,resumed);assertEquals(2L,WatchRelayAudio.captureEpoch(resumed));assertEquals(1L,WatchRelayAudio.counter(resumed))
        assertThrows(IllegalArgumentException::class.java){WatchRelayAudio.sequence(0,0)};assertThrows(IllegalArgumentException::class.java){WatchRelayAudio.sequence(-1,1)}
    }
}
