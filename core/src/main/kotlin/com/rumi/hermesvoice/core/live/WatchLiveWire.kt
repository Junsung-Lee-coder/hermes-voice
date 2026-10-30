package com.rumi.hermesvoice.core.live

import org.json.JSONObject
import java.util.Base64

/** App-internal controls only; default provider media/network belongs to Phone, Watch PCM uses its separate duplex channel.
 * Base64 bounds escape expansion below MessageClient's 100 KiB message limit. No PCM or key here.
 */
object WatchLiveWire {
    const val MAX_PAYLOAD = 65536
    const val MAX_PACKET = 90000
    fun packet(id: String, media: String, epoch: String, seq: Long, op: String, payload: String = ""): ByteArray {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        require(id.length in 1..80 && media.length in 1..80 && epoch.length in 1..80 && seq > 0 && bytes.size <= MAX_PAYLOAD)
        return JSONObject().put("v",2).put("id",id).put("media",media).put("epoch",epoch).put("seq",seq).put("op",op)
            .put("body",Base64.getEncoder().encodeToString(bytes)).toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_PACKET) }
    }
    data class Packet(val id:String,val media:String,val epoch:String,val seq:Long,val op:String,val body:String)
    fun parse(bytes:ByteArray):Packet? = try {
        if(bytes.size > MAX_PACKET) null else {
            val j=JSONObject(bytes.toString(Charsets.UTF_8))
            val id=j.getString("id");val media=j.getString("media");val epoch=j.getString("epoch");val seq=j.getLong("seq")
            val body=Base64.getDecoder().decode(j.getString("body"))
            if(j.getInt("v")!=2 || id.length !in 1..80 || media.length !in 1..80 || epoch.length !in 1..80 || seq<=0 || body.size>MAX_PAYLOAD) null
            else Packet(id,media,epoch,seq,j.getString("op"),body.toString(Charsets.UTF_8))
        }
    } catch(_:Exception){null}
}
