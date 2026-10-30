package com.rumi.hermesvoice.core.live

import java.io.DataInputStream
import java.io.DataOutputStream

/** Ephemeral duplex peripheral stream, never a file or a durable receipt. R40: the Watch microphone uplink is speech-grade PCM16LE
 *  16 kHz mono in 20 ms packets, which the Phone's pinned ADM takes at that native input rate; the downlink is PCM16LE 48 kHz mono, the
 *  pinned remote sink's own output (it has no rate to choose and nothing here resamples). */
object WatchRelayAudio {
    const val PREFIX="/hv/v3/live/audio/"
    const val UPLINK=1
    const val DOWNLINK=2
    const val UPLINK_RATE=16000
    const val FRAME_BYTES=640 // one uplink packet: twenty milliseconds at 16 kHz, one channel, two bytes per sample
    const val DOWNLINK_RATE=48000
    const val DOWNLINK_FRAME_BYTES=960 // ten milliseconds of the remote sink's 48 kHz mono output
    /** Both apps name this exact wire; a pair across the R40 format change refuses itself instead of misreading PCM. */
    const val OFFER="phone-relay/pcm16/up16000-20ms/down48000/mono"
    private const val MAGIC=0x48565233
    private val token=Regex("[A-Za-z0-9-]{1,80}")
    data class Address(val control:String,val media:String,val epoch:String,val stream:String) {
        fun path()=PREFIX+listOf(control,media,epoch,stream).joinToString("/")
    }
    fun address(path:String):Address? {
        if(!path.startsWith(PREFIX))return null
        val p=path.removePrefix(PREFIX).split('/')
        return if(p.size==4&&p.all{token.matches(it)})Address(p[0],p[1],p[2],p[3])else null
    }
    fun rateOf(direction:Int)=if(direction==UPLINK)UPLINK_RATE else DOWNLINK_RATE
    fun valid(frame:LivePcmFrame,direction:Int)=direction in UPLINK..DOWNLINK&&frame.valid()&&frame.sampleRate==rateOf(direction)&&frame.channels==1&&
        (direction!=UPLINK||frame.pcm.size==FRAME_BYTES)
    fun write(output:DataOutputStream,direction:Int,frame:LivePcmFrame){
        require(valid(frame,direction));output.writeInt(MAGIC);output.writeInt(direction);output.writeLong(frame.sequence)
        output.writeInt(frame.sampleRate);output.writeInt(frame.channels);output.writeInt(frame.pcm.size);output.write(frame.pcm);output.flush()
    }
    fun read(input:DataInputStream,direction:Int):LivePcmFrame {
        require(input.readInt()==MAGIC&&input.readInt()==direction)
        val seq=input.readLong();val rate=input.readInt();val channels=input.readInt();val bytes=input.readInt()
        require(direction in UPLINK..DOWNLINK&&seq>0&&rate==rateOf(direction)&&channels==1&&bytes in 2..LivePcmFrame.MAX_BYTES&&bytes%2==0&&(direction!=UPLINK||bytes==FRAME_BYTES))
        return LivePcmFrame(seq,rate,channels,ByteArray(bytes).also{input.readFully(it)})
    }
    /** Mute edges are capture epochs, not new provider sessions. Older in-flight uplink is never reused after unmute. */
    fun sequence(epoch:Long,counter:Long):Long {require(epoch in 0..0x7fffffffL&&counter in 1..0xffffffffL);return (epoch shl 32)or counter}
    fun captureEpoch(sequence:Long)=sequence ushr 32
    fun counter(sequence:Long)=sequence and 0xffffffffL
}
