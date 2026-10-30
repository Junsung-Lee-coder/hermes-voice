package com.rumi.hermesvoice.phone.live

import com.rumi.hermesvoice.core.HermesHttpException
import com.rumi.hermesvoice.core.diag.DiagStage
import com.rumi.hermesvoice.core.diag.DiagTrace
import com.rumi.hermesvoice.core.live.WatchDirectLive
import com.rumi.hermesvoice.core.live.WatchLiveWire
import com.rumi.hermesvoice.core.net.HermesDashboardClient
import com.rumi.hermesvoice.phone.PhoneApp
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject

/** A Watch-origin media whose audio route the Watch has confirmed (the controller's Watch route reads it). */
internal interface WatchMediaRoute { val routeReady: Boolean }

/** B (Watch-direct experiment): ordinary Watch-origin Live whose media the Watch owns: its microphone, speaker, WebRTC peer and provider data
 *  channel, on the Watch's own OS default network. The Phone owns only the authenticated session request made with the Watch's offer, the
 *  startup context, settings and every policy decision: no Phone peer, microphone, PCM stream or speaker. Offer, answer and events cross the
 *  exact paired control (node, control, media, process epoch, next sequence) and are never logged. */
internal class WatchDirectLiveTransport(
    private val app:PhoneApp,private val dashboard:HermesDashboardClient,
    val node:String,val id:String,val media:String,val epoch:String,
    private val transmit:suspend(ByteArray)->Unit,
):LiveTransport,WatchMediaRoute {
    @Volatile private var closed=false
    @Volatile private var accepting=true
    private val scope=app.bodyScope()
    private fun current()=accepting&&!closed&&scope!=null&&app.live.active&&app.live.callIdentity()==media&&app.liveBodyScope()==scope&&app.existingWiring()?.dashboard===dashboard
    private var inputSequence=0L;private var outputSequence=0L
    private var offerConsumed=false;private var opened=false
    @Volatile private var inputMuted=false
    @Volatile private var outputEnabled=false
    private val ready=CompletableDeferred<Unit>()
    /** The Watch opened its own audio route before its offer; the Watch checks that route again before it enables any output. */
    @Volatile override var routeReady=false;private set
    private var history:JSONArray?=null
    private var events:((String)->Unit)?=null
    private var activity:((Boolean)->Unit)?=null
    private var microphone:((Boolean)->Unit)?=null
    private var session:Job?=null;private var heartbeat:Job?=null;private var deadline:Job?=null
    /** One ordered bound for controls and provider commands (true: a provider command). Provider commands may hold at most
     *  [PROVIDER_SLOTS] of it (about the relay data channel's 64 KiB of append packets), so Stop, Mute and the lease always have room. */
    private val queue=Channel<Pair<ByteArray,Boolean>>(128)
    private val providerQueued=java.util.concurrent.atomic.AtomicInteger()
    /** When the exact owner Watch was last heard (its own direct media pings, as the relay's PCM stream shows the relay is alive). */
    @Volatile private var lastWatch=0L
    private val sender=app.mainScope.launch{
        try{for((bytes,provider) in queue){if(provider)providerQueued.decrementAndGet();transmit(bytes)}}
        catch(c:CancellationException){throw c}catch(error:Exception){fail("Watch control transport unavailable",error=error)}
    }
    /** The sequence advances only for a queued packet. [backpressure] (a provider command): refused alone when its share is full (the caller
     *  offers it again, as with the relay's data channel); a control that finds the queue full ends the call. */
    @Synchronized private fun command(op:String,body:String="",backpressure:Boolean=false):Boolean {
        if(closed)return false
        if(backpressure&&providerQueued.get()>=PROVIDER_SLOTS)return false
        val next=outputSequence+1
        val bytes=runCatching{WatchLiveWire.packet(id,media,epoch,next,op,body)}.getOrNull()?:return false
        if(backpressure)providerQueued.incrementAndGet()
        if(queue.trySend(bytes to backpressure).isSuccess){outputSequence=next;return true}
        if(backpressure){providerQueued.decrementAndGet();return false}
        fail("Watch control queue full");return false
    }
    private companion object { const val PROVIDER_SLOTS=96 }
    override suspend fun connect(history:JSONArray,event:(String)->Unit){
        check(current());this.history=history;events=event;opened=true;lastWatch=app.live.clockNow()
        check(command("open",JSONObject().put(WatchDirectLive.OPEN,true).put("muted",inputMuted).put("private",false).toString()))
        heartbeat=app.mainScope.launch{while(!closed){delay(1000);if(!current()){fail("Watch Live account or media scope ended",observed=false);break};privateChanged()
            if(app.live.clockNow()-lastWatch>=5000){fail("Watch stopped answering; direct Live stopped without Phone audio fallback");break};command("ping");app.liveWatch.repeatState()}}
        deadline=app.mainScope.launch{delay(60_000);if(!ready.isCompleted)fail("Watch direct Live did not connect; check the Watch's internet route (Bluetooth via Phone, Wi-Fi or LTE). No Phone audio relay",error=java.util.concurrent.TimeoutException())}
        withTimeout(60_000){ready.await()}
    }
    /** A Phone private output selected while the Watch owns the media: no handoff exists, so the call ends (no Watch speaker or Phone audio fallback). */
    fun privateChanged(){if(opened&&app.headset.output()!=null)fail("Phone private output selected during a direct Watch call; Live stopped without Watch speaker or Phone audio fallback. Start a new Talk")}
    fun receive(source:String,p:WatchLiveWire.Packet){
        if(closed||source!=node||p.id!=id||p.media!=media||p.epoch!=epoch||p.seq<=inputSequence)return
        if(p.seq!=inputSequence+1){fail("Watch media sequence lost; no automatic reconnect");return}
        inputSequence=p.seq;lastWatch=app.live.clockNow()
        if(p.op=="event"){
            // The provider's events come from the Watch's own data channel. After the end only its close receipt still finalizes the call.
            if(current())events?.invoke(p.body)
            else if(runCatching{JSONObject(p.body).optString("type")}.getOrNull()=="session.closed")events?.invoke(p.body)
            return
        }
        if(!current())return
        when(p.op){
            "offer"->{
                if(offerConsumed)return
                offerConsumed=true
                if(!WatchDirectLive.offer(p.body)){fail("Watch media version unavailable; update the paired apps, no Phone audio fallback");return}
                val context=history?:return fail("Watch media arrived outside admission")
                routeReady=true
                val trace=DiagTrace(DiagStage.LIVE_MEDIA) // the session request marks its own step
                session=app.mainScope.launch(trace){
                    try{
                        val (_,answer)=dashboard.createLiveSession(p.body,context) // ONCE with the Watch's own offer; never retried after uncertainty.
                        trace.stage=DiagStage.LIVE_ANSWER
                        if(!current())return@launch
                        check(command("answer",answer))
                    }catch(timeout:TimeoutCancellationException){fail("Live setup timed out; nothing is retried automatically",trace.stage,timeout)}
                    catch(c:CancellationException){throw c}
                    catch(error:Exception){fail("Phone Live creation failed or uncertain"+((error as? HermesHttpException)?.status?.let{" (HTTP $it)"}?:"")+"; nothing is retried",trace.stage,error)}
                }
            }
            "connected"->{deadline?.cancel();ready.complete(Unit)}
            "activity"->activity?.invoke(p.body=="input")
            "microphone"->microphone?.invoke(p.body=="true")
            "input_route"->app.live.inputPending(p.body.take(200))
            "suspend"->app.live.end("Watch Live idle; authorized late replies may resume Watch microphone",kind="idle")
            "end","refused"->fail(p.body.take(300).ifBlank{"Watch Live stopped"},observed=p.op=="refused")
        }
    }
    /** As the relay: a setup failure (before the Watch connected) is observed once, type-only, before `ready` can resume connect inline. */
    private fun fail(message:String,stage:DiagStage=DiagStage.LIVE_RELAY,error:Throwable?=null,observed:Boolean=true){
        if(closed||!accepting)return
        if(observed&&!ready.isCompleted&&app.live.active&&app.live.callIdentity()==media)app.liveSetupFailed(com.rumi.hermesvoice.core.VoiceOrigin.WATCH,stage,error)
        ready.completeExceptionally(LiveSetupFailure(message,error))
        if(app.live.active&&app.live.callIdentity()==media)app.live.end(message,kind="failure")
    }
    override fun observeActivity(listener:(Boolean)->Unit){activity=listener}
    override fun observeMicrophone(listener:(Boolean)->Unit){microphone=listener}
    /** The Watch plays the provider's speech on its own admitted route once output is enabled. */
    override fun outputReady()=current()&&outputEnabled&&routeReady
    override fun send(json:String)=!closed&&command("send",json,backpressure=true)
    override fun mute(muted:Boolean){inputMuted=muted;if(opened)command("mute",muted.toString())}
    override fun output(enabled:Boolean){outputEnabled=enabled;command("output",enabled.toString())}
    override fun silence(){accepting=false;outputEnabled=false;heartbeat?.cancel();command("silence")}
    override fun close(){
        if(closed)return
        command("close");closed=true;accepting=false;routeReady=false;heartbeat?.cancel();deadline?.cancel();session?.cancel()
        queue.close();ready.cancel();events=null;activity=null;microphone=null
        app.mainScope.launch{withTimeoutOrNull(3000){sender.join()};sender.cancel()}
    }
}
