package com.rumi.hermesvoice.core.live

import java.io.DataInputStream
import java.io.DataOutputStream

/** Ephemeral duplex peripheral stream, never a file or a durable receipt. R41: BOTH directions are speech-grade 16 kHz mono in 20 ms
 *  packets, carried as IMA ADPCM ([RelayAdpcm], 164 payload bytes a packet: about 9.6 kB/s each way instead of R40's 33.4 kB/s up and
 *  98.8 kB/s down). The apps still exchange PCM16LE [LivePcmFrame]s; the codec exists only on the wire, here. The Watch records and plays
 *  16 kHz itself; the Phone's pinned ADM takes the uplink at its native 16 kHz input, and the Phone converts the provider's 48 kHz
 *  remote-sink output to 16 kHz itself ([WatchRelayDownlink]). */
object WatchRelayAudio {
    const val PREFIX="/hv/v3/live/audio/"
    const val UPLINK=1
    const val DOWNLINK=2
    const val UPLINK_RATE=16000
    const val FRAME_BYTES=640 // one packet of PCM16: twenty milliseconds at 16 kHz, one channel, two bytes per sample
    const val DOWNLINK_RATE=16000
    const val DOWNLINK_FRAME_BYTES=640
    /** The provider output the Phone converts from: the pinned remote sink's 48 kHz mono. Any other format is refused, never guessed. */
    const val PROVIDER_RATE=48000
    /** One packet on the wire: the ADPCM predictor, step index and a zero byte, then 320 four-bit codes. */
    const val CODED_BYTES=164
    /** Both apps name this exact wire; a pair across the R41 format change refuses itself instead of misreading audio. */
    const val OFFER="phone-relay/ima-adpcm/16000/20ms/mono"
    private const val MAGIC=0x48564134 // "HVA4": an ADPCM packet is never read as an earlier PCM frame, nor the reverse
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
        frame.pcm.size==FRAME_BYTES
    /** One packet, field by field: the header's size is the coded payload's (always [CODED_BYTES]). */
    fun write(output:DataOutputStream,direction:Int,frame:LivePcmFrame){
        require(valid(frame,direction));val coded=RelayAdpcm.encode(frame.pcm)
        output.writeInt(MAGIC);output.writeInt(direction);output.writeLong(frame.sequence)
        output.writeInt(frame.sampleRate);output.writeInt(frame.channels);output.writeInt(coded.size);output.write(coded);output.flush()
    }
    fun read(input:DataInputStream,direction:Int):LivePcmFrame {
        require(input.readInt()==MAGIC&&input.readInt()==direction)
        val seq=input.readLong();val rate=input.readInt();val channels=input.readInt();val bytes=input.readInt()
        require(direction in UPLINK..DOWNLINK&&seq>0&&rate==rateOf(direction)&&channels==1&&bytes==CODED_BYTES)
        return LivePcmFrame(seq,rate,channels,RelayAdpcm.decode(ByteArray(bytes).also{input.readFully(it)}))
    }
    /** Mute edges are capture epochs, not new provider sessions. Older in-flight uplink is never reused after unmute. */
    fun sequence(epoch:Long,counter:Long):Long {require(epoch in 0..0x7fffffffL&&counter in 1..0xffffffffL);return (epoch shl 32)or counter}
    fun captureEpoch(sequence:Long)=sequence ushr 32
    fun counter(sequence:Long)=sequence and 0xffffffffL
}
