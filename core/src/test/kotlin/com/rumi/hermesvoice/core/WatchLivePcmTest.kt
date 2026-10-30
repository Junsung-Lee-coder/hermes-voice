package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.live.*
import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.nio.ByteBuffer

class WatchLivePcmTest {
    @Test fun decodedRemoteBufferIsCopiedBeforeTheNativeCallbackReturns(){
        val bytes=byteArrayOf(1,2,3,4);val input=ByteBuffer.wrap(bytes)
        val frame=WatchLivePcm.copy(input,16,48000,1,2,1)!!
        bytes.fill(0);assertArrayEquals(byteArrayOf(1,2,3,4),frame.pcm);assertEquals(0,input.position())
    }
    @Test fun negotiatedFormatAndExactFramesRoundTripWithoutTextOrBase64(){
        val out=ByteArrayOutputStream();val f=LivePcmFrame(42,24000,2,ByteArray(1920){it.toByte()})
        WatchLivePcm.write(DataOutputStream(out),f)
        val decoded=WatchLivePcm.read(DataInputStream(ByteArrayInputStream(out.toByteArray())))
        assertEquals(42L,decoded.sequence);assertEquals(24000,decoded.sampleRate);assertEquals(2,decoded.channels);assertArrayEquals(f.pcm,decoded.pcm)
    }
    @Test fun invalidHugeStereoAndUnsupportedSampleFormatsAreRefusedBeforeAllocation(){
        assertNull(WatchLivePcm.copy(ByteBuffer.allocate(4),32,48000,1,1,1))
        assertNull(WatchLivePcm.copy(ByteBuffer.allocate(4),16,48000,3,1,1))
        val out=ByteArrayOutputStream();DataOutputStream(out).apply{writeLong(1);writeInt(48000);writeInt(1);writeInt(Int.MAX_VALUE)}
        assertThrows(IllegalArgumentException::class.java){WatchLivePcm.read(DataInputStream(ByteArrayInputStream(out.toByteArray())))}
    }
    @Test fun partialFramesCannotBeInventedAndTruncatedChannelIsNotPlayed(){
        assertNull(WatchLivePcm.copy(ByteBuffer.allocate(5),16,48000,1,2,1))
        val out=ByteArrayOutputStream();DataOutputStream(out).apply{writeLong(1);writeInt(48000);writeInt(1);writeInt(4);writeByte(1)}
        assertThrows(EOFException::class.java){WatchLivePcm.read(DataInputStream(ByteArrayInputStream(out.toByteArray())))}
    }
    @Test fun channelAddressBindsControlMediaPhoneEpochAndPrivateOutputGeneration(){
        val a=WatchLivePcm.Address("control","media","epoch","output");assertEquals(a,WatchLivePcm.address(a.path()))
        assertNull(WatchLivePcm.address(a.path()+"/extra"));assertNull(WatchLivePcm.address(WatchLivePcm.PREFIX+"../media/epoch/output"))
        assertNull(WatchLivePcm.address("/hv/v1/turn/a"))
    }
}
