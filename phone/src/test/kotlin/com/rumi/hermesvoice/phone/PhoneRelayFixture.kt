package com.rumi.hermesvoice.phone

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.live.*
import com.rumi.hermesvoice.phone.live.*
import io.mockk.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.concurrent.Semaphore
import java.util.concurrent.ConcurrentLinkedQueue

/** Deterministic provider/OS leaves for migrated custody/privacy tests. Native external-input wiring is independently exercised by WatchPhoneRelayDefaultRoboTest. */
internal class PhoneRelayFixture(private val app:PhoneApp,val open:WatchLiveWire.Packet,bind:Boolean=true):AutoCloseable {
    val sent=ConcurrentLinkedQueue<String>()
    var output:((LivePcmFrame)->Unit)?=null
    var activity:((Boolean)->Unit)?=null
    /** The provider's own event callback and the startup input it was created with (for tests that speak as the provider). */
    @Volatile var event:((String)->Unit)?=null
    @Volatile var history:JSONArray?=null
    private val input=object:InputStream(){
        private val ended=Semaphore(0);@Volatile private var closed=false
        override fun read():Int{if(!closed)ended.acquire();return -1}
        override fun close(){closed=true;ended.release()}
    }
    val native:WatchLiveTransport
    val channel:ChannelClient.Channel
    init {
        val field=PhoneLiveWatchControl::class.java.getDeclaredField("remote").apply{isAccessible=true};native=field.get(app.liveWatch) as WatchLiveTransport
        native.providerFactory={_,out->output=out;object:LiveTransport {
            override suspend fun connect(history:JSONArray,event:(String)->Unit){
                this@PhoneRelayFixture.history=history;this@PhoneRelayFixture.event=event
                val (session,_)=app.wiring().dashboard.createLiveSession("v=0\r\nphone-fixture-offer\r\n",history)
                event(JSONObject().put("type","session.started").put("session",JSONObject().put("id",session)).toString())
            }
            override fun send(json:String):Boolean{sent+=json;return true}
            override fun observeActivity(listener:(Boolean)->Unit){this@PhoneRelayFixture.activity=listener}
            override fun mute(muted:Boolean){};override fun output(enabled:Boolean){};override fun silence(){};override fun close(){}
        }}
        channel=mockk();every{channel.nodeId}returns "watch-node"
        every{channel.path}returns WatchRelayAudio.Address(open.id,open.media,open.epoch,JSONObject(open.body).getString("stream")).path()
        val client=Wearable.getChannelClient(app)
        every{client.getInputStream(channel)}returns Tasks.forResult(input as InputStream)
        every{client.getOutputStream(channel)}returns Tasks.forResult(ByteArrayOutputStream() as OutputStream)
        every{client.close(channel)}answers{input.close();Tasks.forResult(null)}
        if(bind)this.bind()
    }
    fun bind(){app.liveWatch.audioChannel(channel)}
    override fun close(){input.close()}
}
