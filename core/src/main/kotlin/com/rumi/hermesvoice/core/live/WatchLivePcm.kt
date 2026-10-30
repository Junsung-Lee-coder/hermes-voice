package com.rumi.hermesvoice.core.live

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/** Ephemeral decoded Live audio, not TTS or a recording. No body is persisted or logged. */
data class LivePcmFrame(val sequence:Long,val sampleRate:Int,val channels:Int,val pcm:ByteArray) {
    fun valid()=sequence>0 && sampleRate in 8000..96000 && channels in 1..2 && pcm.isNotEmpty() &&
        pcm.size<=MAX_BYTES && pcm.size%(channels*2)==0
    companion object { const val MAX_BYTES=7680 }
}

/** Exact paired node is checked by the receiver separately. The path binds every app-owned media/output generation. */
object WatchLivePcm {
    const val PREFIX="/hv/v2/live/pcm/"
    data class Address(val control:String,val media:String,val epoch:String,val output:String) {
        fun path()=PREFIX+listOf(control,media,epoch,output).joinToString("/")
    }
    private val token=Regex("[A-Za-z0-9-]{1,80}")
    fun address(path:String):Address? {
        if(!path.startsWith(PREFIX))return null
        val p=path.removePrefix(PREFIX).split('/')
        return if(p.size==4 && p.all{token.matches(it)})Address(p[0],p[1],p[2],p[3]) else null
    }
    fun copy(data:ByteBuffer,bits:Int,rate:Int,channels:Int,frames:Int,sequence:Long):LivePcmFrame? {
        if(bits!=16 || channels !in 1..2 || frames<=0 || frames>LivePcmFrame.MAX_BYTES/(channels*2))return null
        val bytes=frames*channels*2
        if(data.remaining()!=bytes)return null
        val copy=ByteArray(bytes);data.duplicate().get(copy)
        return LivePcmFrame(sequence,rate,channels,copy).takeIf{it.valid()}
    }
    fun write(out:DataOutputStream,f:LivePcmFrame){require(f.valid());out.writeLong(f.sequence);out.writeInt(f.sampleRate);out.writeInt(f.channels);out.writeInt(f.pcm.size);out.write(f.pcm);out.flush()}
    fun read(input:DataInputStream):LivePcmFrame {
        val seq=input.readLong();val rate=input.readInt();val channels=input.readInt();val bytes=input.readInt()
        require(bytes in 1..LivePcmFrame.MAX_BYTES && rate in 8000..96000 && channels in 1..2 && bytes%(channels*2)==0 && seq>0)
        return LivePcmFrame(seq,rate,channels,ByteArray(bytes).also{input.readFully(it)})
    }
}
