package com.rumi.hermesvoice.watch

import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.ChannelClient
import com.rumi.hermesvoice.core.live.LivePcmFrame
import com.rumi.hermesvoice.core.live.WatchLivePcm
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.tasks.await
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Eight copied realtime frames maximum. Congestion loses output admission, never Watch input/Hermes custody. */
internal class WatchLivePcmSender(private val app:WatchApp,private val node:String,address:WatchLivePcm.Address,
    private val current:()->Boolean,private val failed:()->Unit):AutoCloseable {
    private val closed=AtomicBoolean()
    private val queue=Channel<LivePcmFrame>(8)
    @Volatile private var channel:ChannelClient.Channel?=null
    private val client=Wearable.getChannelClient(app)
    private val worker=app.scope.launch(Dispatchers.IO) {
        try {
            val c=withTimeout(5000){client.openChannel(node,address.path()).await()};channel=c
            if(closed.get()||!current())return@launch
            val out=withTimeout(5000){client.getOutputStream(c).await()}
            DataOutputStream(out).use { stream ->
                for(frame in queue){if(closed.get()||!current())break;WatchLivePcm.write(stream,frame)}
            }
        } catch(c:CancellationException){throw c}
        catch(_:Exception){if(!closed.get()&&current())withContext(Dispatchers.Main){failed()}}
        finally {channel?.let{client.close(it)};channel=null;queue.close()}
    }
    fun offer(frame:LivePcmFrame){
        if(closed.get()||!current())return
        if(!queue.trySend(frame).isSuccess){close();app.scope.launch{if(current())failed()}}
    }
    override fun close(){
        if(!closed.compareAndSet(false,true))return
        queue.close();channel?.let{client.close(it)};worker.cancel()
    }
}
