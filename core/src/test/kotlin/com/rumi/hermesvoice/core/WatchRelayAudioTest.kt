package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.live.*
import org.junit.Test
import org.junit.Assert.*
import java.io.*

class WatchRelayAudioTest {
    /** One 20 ms packet of each direction's own rate: a speech-band 440 Hz tone (R41: both directions are 16 kHz mono). */
    private fun of(direction:Int,seq:Long=1,bytes:Int=WatchRelayAudio.FRAME_BYTES)=LivePcmFrame(seq,WatchRelayAudio.rateOf(direction),1,ByteArray(bytes).also{b->
        for(i in 0 until bytes/2){val v=Math.round(8000*Math.sin(2*Math.PI*440*i/WatchRelayAudio.rateOf(direction))).toInt();b[2*i]=v.toByte();b[2*i+1]=(v shr 8).toByte()}})
    private fun encoded(direction:Int,frame:LivePcmFrame)=ByteArrayOutputStream().also{WatchRelayAudio.write(DataOutputStream(it),direction,frame)}.toByteArray()
    private fun samples(pcm:ByteArray)=IntArray(pcm.size/2){((pcm[2*it].toInt() and 255) or (pcm[2*it+1].toInt() shl 8)).toShort().toInt()}
    private fun snrDb(a:ByteArray,b:ByteArray):Double{val x=samples(a);val y=samples(b);var s=0.0;var e=0.0;for(i in x.indices){s+=x[i].toDouble()*x[i];e+=(x[i]-y[i]).toDouble()*(x[i]-y[i])};return 10*Math.log10(s/maxOf(e,1e-9))}
    @Test fun bothDirectionsKeepSequenceAndSpeechAndNeverAcceptTheOppositeDirection(){
        for(direction in 1..2){val f=of(direction);val bytes=encoded(direction,f);val read=WatchRelayAudio.read(DataInputStream(ByteArrayInputStream(bytes)),direction)
            assertEquals(f.sequence,read.sequence);assertEquals(f.sampleRate,read.sampleRate);assertEquals(f.pcm.size,read.pcm.size)
            assertTrue("speech survives the wire: ${snrDb(f.pcm,read.pcm)} dB",snrDb(f.pcm,read.pcm)>=20.0)
            assertArrayEquals("decoding is deterministic",read.pcm,WatchRelayAudio.read(DataInputStream(ByteArrayInputStream(bytes)),direction).pcm)
            assertThrows(IllegalArgumentException::class.java){WatchRelayAudio.read(DataInputStream(ByteArrayInputStream(bytes)),3-direction)}}
    }
    @Test fun addressBindsAllFourGenerationsAndRejectsTraversalExtraTokensAndOtherProtocols(){
        val a=WatchRelayAudio.Address("control","media","epoch","stream");assertEquals(a,WatchRelayAudio.address(a.path()))
        for(p in listOf(a.path()+"/extra",a.path().replace("control",".."),"/hv/v2/live/pcm/control/media/epoch/stream",a.path().replace("stream","")))assertNull(p,WatchRelayAudio.address(p))
    }
    @Test fun inputAndOutputBoundsFormatsAndTruncationAreFailClosedBeforeAllocation(){
        for(direction in 1..2){
            val good=encoded(direction,of(direction))
            for(size in listOf(0,1,7681,Int.MAX_VALUE)){
                // The real header with only its payload size changed: refused before any payload is allocated or read.
                val header=good.copyOf(28);header[24]=(size ushr 24).toByte();header[25]=(size ushr 16).toByte();header[26]=(size ushr 8).toByte();header[27]=size.toByte()
                assertThrows(IllegalArgumentException::class.java){WatchRelayAudio.read(DataInputStream(ByteArrayInputStream(header)),direction)}
            }
            assertThrows(EOFException::class.java){WatchRelayAudio.read(DataInputStream(ByteArrayInputStream(good.copyOf(good.size-1))),direction)}
        }
        assertFalse(WatchRelayAudio.valid(of(1).copy(sampleRate=24000),1));assertFalse(WatchRelayAudio.valid(of(2).copy(channels=2),2));assertFalse(WatchRelayAudio.valid(of(1,bytes=638),1))
    }
    @Test fun captureMuteEpochAndPerEpochSequenceCannotAliasEachOther(){
        val first=WatchRelayAudio.sequence(0,1);val resumed=WatchRelayAudio.sequence(2,1)
        assertEquals(1L,first);assertNotEquals(first,resumed);assertEquals(2L,WatchRelayAudio.captureEpoch(resumed));assertEquals(1L,WatchRelayAudio.counter(resumed))
        assertThrows(IllegalArgumentException::class.java){WatchRelayAudio.sequence(0,0)};assertThrows(IllegalArgumentException::class.java){WatchRelayAudio.sequence(-1,1)}
    }
}
