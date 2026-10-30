package com.rumi.hermesvoice.phone.live

import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.HermesHttpException
import com.rumi.hermesvoice.core.diag.DiagStage
import com.rumi.hermesvoice.core.diag.DiagTrace
import com.rumi.hermesvoice.core.live.*
import com.rumi.hermesvoice.core.net.HermesDashboardClient
import com.rumi.hermesvoice.phone.PhoneApp
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream

/** Watch-origin media; Phone owns provider/network. Only verified selected-headset capture may replace Watch PCM. */
internal class WatchLiveTransport(
    private val app:PhoneApp,private val dashboard:HermesDashboardClient,
    val node:String,val id:String,val media:String,val epoch:String,
    private val transmit:suspend(ByteArray)->Unit,
):LiveTransport {
    @Volatile private var closed=false
    @Volatile private var accepting=true
    private val scope=app.bodyScope()
    // Asked on the pinned ADM thread for every 10 ms frame: the exact scope without a per-frame Keystore decrypt (R33).
    private fun current()=accepting&&!closed&&scope!=null&&app.live.active&&app.live.callIdentity()==media&&app.liveBodyScope()==scope&&app.existingWiring()?.dashboard===dashboard
    private var inputSequence=0L;private var outputSequence=0L
    private var offerConsumed=false;private var opened=false
    @Volatile private var inputMuted=false
    @Volatile private var outputEnabled=false
    private val streamToken=java.util.UUID.randomUUID().toString()
    private val address=WatchRelayAudio.Address(id,media,epoch,streamToken)
    private val channelReady=CompletableDeferred<Unit>()
    private val ready=CompletableDeferred<Unit>()
    private var inputHold:String?=null
    private val held=CompletableDeferred<Unit>()
    private val inputAdmission=CompletableDeferred<Boolean>()
    private var waitingInputAdmission=false
    private var inputFgsLease:String?=null
    @Volatile private var phoneInputSelected=false
    @Volatile private var capture:LiveCaptureInput?=null
    private var phoneMic:AutoCloseable?=null
    private var phoneClaim:com.rumi.hermesvoice.core.voice.MicrophoneClaim?=null
    internal fun inputRequested(request:String)=current()&&waitingInputAdmission&&request==inputHold
    internal fun inputAdmitted(request:String,ok:Boolean){if(inputRequested(request))inputAdmission.complete(ok)}
    private fun releasePhoneInput():Boolean{
        val old=capture;old?.close()
        if(old!=null&&!old.settled())return false // retain mic exclusion; never authorize another input after uncertain native release
        capture=null;phoneClaim?.release();phoneClaim=null;phoneMic?.close();phoneMic=null;phoneInputSelected=false
        val lease=inputFgsLease;inputFgsLease=null
        microphone?.invoke(false);if(lease!=null)PhoneLiveVoiceService.running?.inputFinished(media,lease);return true
    }
    private suspend fun selectInput():ExternalLiveInput {
        val request=inputHold?:return input
        try{
            withTimeout(3000){held.await()};check(current())
            waitingInputAdmission=true
            inputFgsLease=request
            app.live.requestWatchInputAdmission(request)
            check(withTimeout(3000){inputAdmission.await()}&&current()){ "Phone microphone foreground access refused" }
            waitingInputAdmission=false
            check(androidx.core.content.ContextCompat.checkSelfPermission(app,android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED){"Phone microphone permission unavailable"}
            val target=LiveCaptureInput.headset(app)?:error("Headset input unavailable")
            check(!app.manualCapture.busy)
            phoneMic=app.phoneMic.takeManual()?:error("Phone microphone busy")
            val stopped=CompletableDeferred<Boolean>()
            phoneClaim=app.audio.claimMicrophone(com.rumi.hermesvoice.core.VoiceOrigin.PHONE).also{it.whenSpeakerStopped(app.mainScope){ok->stopped.complete(ok)}}
            check(withTimeout(3000){stopped.await()}&&current())
            val source=LiveCaptureInput(app,target,{current()&&privateSelected()},{app.mainScope.launch{if(current()&&phoneInputSelected){
                val active=capture?.active()==true
                if(active)app.live.inputPending("Headset microphone · OS capture route verified")
                microphone?.invoke(active)
            }}},{app.mainScope.launch{fail("Headset input route lost; start a new Talk. No Watch/built-in Phone microphone splice",DiagStage.LIVE_INPUT)}},{app.mainScope.launch{if(current()){activity?.invoke(true);command("dialogue","input")}}})
            capture=source;phoneInputSelected=true;source.mute(inputMuted);source.prepare()
            check(current());app.live.inputPending(if(inputMuted)"Headset microphone selected · muted" else "Headset microphone · OS capture route verified")
            return source
        }catch(c:CancellationException){
            check(releasePhoneInput()){ "Headset recorder release unconfirmed; restart the app before another microphone" }
            if(c !is TimeoutCancellationException)throw c
            waitingInputAdmission=false;check(current());check(command("input_watch",request));inputHold=null
            app.live.inputPending("Headset input not confirmed in time; original Watch microphone")
            return input
        }
        catch(_:Exception){
            waitingInputAdmission=false;check(releasePhoneInput()){ "Headset recorder release unconfirmed; restart the app before another microphone" };check(current())
            check(command("input_watch",request))
            inputHold=null
            app.live.inputPending("Headset input not confirmed or foreground access refused; original Watch microphone")
            return input
        }
    }
    private val input=WatchRelayInput(::current,::inputFailed)
    private fun inputFailed(){
        relayEnd(if(input.failure==WatchRelayInput.NATIVE_FORMAT)RELAY_NATIVE_FORMAT else RELAY_STALLED)
        app.mainScope.launch{fail("Watch microphone stream stalled or has an unsupported native format")}
    }
    private val downlink=Channel<LivePcmFrame>(16) // R41: 16 packets of 20 ms, the same 320 ms bound as 32 frames of 10 ms before
    private var downSequence=0L
    /** R41: this downlink generation's 48 -> 16 kHz converter; null while no Watch speaker output is admitted. Sink thread and main. */
    private val downGate=Any()
    private var downConverter:WatchRelayDownlink?=null
    private fun downlinkGeneration(open:Boolean)=synchronized(downGate){downConverter=if(open)downConverter?:WatchRelayDownlink() else null}
    /** R41: this call's fixed relay evidence (see [LiveRelayCounters]): the first relay boundary that ended it, and packet flow after Started. */
    private val relayEndCode=java.util.concurrent.atomic.AtomicInteger(RELAY_NONE);@Volatile private var relayEndNs=0L;@Volatile private var stoppedNs=0L
    @Volatile private var startedNs=0L;@Volatile private var lastUpNs=0L;@Volatile private var upGapMaxNs=0L;@Volatile private var downWriteMaxNs=0L
    private val upPackets=java.util.concurrent.atomic.AtomicLong();private val downPackets=java.util.concurrent.atomic.AtomicLong()
    /** Only the first boundary while the relay still accepts counts: after Stop (silence) a late stream end is the Stop's, not a cause.
     *  Lock-free: the native input and remote-sink threads call it, and never wait on this transport's monitor, which main may hold
     *  while the end it starts stops those same threads. */
    private fun relayEnd(code:Int){val now=System.nanoTime();if(accepting&&!closed&&relayEndCode.compareAndSet(RELAY_NONE,code))relayEndNs=now}
    @Volatile private var channel:ChannelClient.Channel?=null
    private var channelConsumed=false
    private var reader:Job?=null;private var writer:Job?=null;private var providerJob:Job?=null
    @Volatile private var provider:LiveTransport?=null
    internal var providerFactory:(ExternalLiveInput,(LivePcmFrame)->Unit)->LiveTransport={source,output->WebRtcLiveTransport(app,dashboard,source,output)}
    @Volatile private var privateOwned=false;@Volatile private var privateId:Int?=null
    @Volatile private var outputBlocked=false;@Volatile private var privateAdmitted=false
    @Volatile private var watchQuiesced=false
    private var outputToken:String?=null
    private var pcm:LivePcmOutput?=null
    internal var pcmFactory:(Int,()->Boolean,()->Unit)->LivePcmOutput={device,current,lost->PhonePrivateLivePcm(app,device,current,lost,selectedPolicy=current)}
    private fun inputLabel()=if(phoneInputSelected)"Headset" else "Watch"
    private fun privateSelected():Boolean {
        val id=app.headset.output()?.id?:return false
        return id==privateId || (phoneInputSelected&&id==capture?.playbackDeviceId())
    }
    private fun outputCurrent()=current()&&privateOwned&&watchQuiesced&&!outputBlocked&&privateSelected()
    private fun beginPrivate(){
        if(privateOwned)return
        val target=app.headset.output()?:return
        privateOwned=true;privateId=target.id;outputToken=java.util.UUID.randomUUID().toString()
        // Sticky admission happens before control/network waits: neither queue may spill onto a Watch speaker.
        while(downlink.tryReceive().isSuccess){}
        downlinkGeneration(false)
        if(opened)command("private",outputToken!!)
        if(opened)app.live.inputPending("New private output selected; original Watch microphone for this stream. Start a new Talk to verify headset input")
        app.live.outputPending("Waiting for Watch speaker stop confirmation; ${inputLabel()} input continues")
        app.live.requestWatchOutputAdmission()
    }
    fun privateChanged(){
        if(!privateOwned){beginPrivate();return}
        if(!privateSelected()){
            if(phoneInputSelected||inputHold!=null){releasePhoneInput();fail("Headset input/output disconnected; start a new Talk. No microphone splice or speaker fallback")}
            else stopOutput("Phone private output disconnected; ${inputLabel()} input continues, no speaker fallback")
        }
    }
    fun outputAdmitted(ok:Boolean){
        privateAdmitted=ok
        if(!ok)stopOutput("Android refused Phone private Live audio; ${inputLabel()} input continues. Open Phone and start a new Talk for audio.")
        else if(outputCurrent())app.live.outputPending("Watch microphone · Phone private Live output ready")
        else if(!outputBlocked)app.live.outputPending("Waiting for Watch speaker stop confirmation; ${inputLabel()} input continues")
    }
    private fun stopOutput(message:String){
        if(outputBlocked)return
        outputBlocked=true;pcm?.close();pcm=null
        downlinkGeneration(false)
        while(downlink.tryReceive().isSuccess){}
        if(current())app.live.outputPending(message)
    }
    private fun decoded(frame:LivePcmFrame){
        if(!current()||!outputEnabled||outputBlocked)return
        // The pinned remote sink's provider output is 48 kHz mono; any other format stops output, never guessed or resampled (R41).
        if(!frame.valid()||frame.sampleRate!=WatchRelayAudio.PROVIDER_RATE||frame.channels!=1){app.mainScope.launch{stopOutput("Live output format unavailable; no speaker fallback")};return}
        if(privateOwned){
            if(!privateAdmitted||!outputCurrent())return
            var player=pcm
            if(player==null){
                val created=try{pcmFactory(capture?.playbackDeviceId()?:privateId!!,::outputCurrent){app.mainScope.launch{stopOutput("Phone private Live route/focus lost; ${inputLabel()} input continues without speaker fallback")}}}catch(_:Exception){app.mainScope.launch{stopOutput("Phone private Live audio unavailable; ${inputLabel()} input continues")};return}
                if(!outputCurrent()){created.close();return};pcm=created;player=created
            }
            val admitted=player?:return
            if(!outputCurrent()||!admitted.write(frame))app.mainScope.launch{stopOutput("Phone private Live audio unavailable; ${inputLabel()} input continues, no replay or speaker fallback")}
        }else{
            // R41: the Watch hears 16 kHz: this generation's converter gives whole 20 ms packets (none, or a partial packet held back).
            val packets=synchronized(downGate){downConverter?.push(frame.pcm)}?:return
            for(coded in packets){
                if(!downlink.trySend(LivePcmFrame(++downSequence,WatchRelayAudio.DOWNLINK_RATE,1,coded)).isSuccess){
                    relayEnd(RELAY_DOWNLINK_CONGESTED);app.mainScope.launch{fail("Watch output congested; Live stopped without replay")};return
                }
            }
        }
    }
    @Synchronized fun receiveAudio(c:ChannelClient.Channel):Boolean {
        if(c.nodeId!=node||WatchRelayAudio.address(c.path)!=address||!current()||channelConsumed)return false
        channelConsumed=true;channel=c
        reader=app.mainScope.launch(Dispatchers.IO){
            try{
                val client=Wearable.getChannelClient(app)
                val source=withTimeout(5000){client.getInputStream(c).await()}
                val sink=withTimeout(5000){client.getOutputStream(c).await()}
                if(!current())return@launch
                routeReady=true;channelReady.complete(Unit)
                writer=app.mainScope.launch(Dispatchers.IO){
                    try{DataOutputStream(sink).use{out->for(frame in downlink){
                        if(!current())break
                        if(!privateOwned&&!outputBlocked&&outputEnabled){
                            check(current());val began=System.nanoTime()
                            WatchRelayAudio.write(out,WatchRelayAudio.DOWNLINK,frame)
                            if(startedNs!=0L){downPackets.incrementAndGet();val took=System.nanoTime()-began;if(took>downWriteMaxNs)downWriteMaxNs=took}
                        }
                    }}}
                    catch(cancelled:CancellationException){throw cancelled}
                    catch(error:Exception){relayEnd(RELAY_DOWNLINK_STREAM);withContext(Dispatchers.Main){fail("Watch output channel disconnected; no public fallback",error=error)}}
                }
                DataInputStream(source).use{stream->while(current()){
                    val frame=WatchRelayAudio.read(stream,WatchRelayAudio.UPLINK)
                    check(!phoneInputSelected){"Unexpected Watch PCM while the Phone headset owns input; no simultaneous sources"}
                    check(current()&&input.offer(frame)){"Watch input generation, sequence or bounded buffer refused"}
                    val now=System.nanoTime();val started=startedNs
                    if(started!=0L){upPackets.incrementAndGet();val gap=now-maxOf(lastUpNs,started);if(gap>upGapMaxNs)upGapMaxNs=gap;lastUpNs=now}
                }}
            }catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){
                relayEnd(if(error is java.io.IOException)RELAY_UPLINK_STREAM else RELAY_UPLINK_REFUSED)
                withContext(Dispatchers.Main){fail("Watch input channel unavailable, malformed or congested; no Phone microphone fallback",error=error)}
            }
            finally{Wearable.getChannelClient(app).close(c)}
        }
        return true
    }
    private var history:JSONArray?=null
    private var events:((String)->Unit)?=null
    private var activity:((Boolean)->Unit)?=null
    private var microphone:((Boolean)->Unit)?=null
    private val queue=Channel<ByteArray>(64)
    private var heartbeat:Job?=null;private var deadline:Job?=null
    @Volatile var routeReady=false;private set
    private val sender=app.mainScope.launch{
        try{for(bytes in queue)transmit(bytes)}catch(c:CancellationException){throw c}catch(error:Exception){relayEnd(RELAY_CONTROL);fail("Watch control transport unavailable",error=error)}
    }
    @Synchronized private fun command(op:String,body:String=""):Boolean {
        if(closed)return false
        val bytes=runCatching{WatchLiveWire.packet(id,media,epoch,++outputSequence,op,body)}.getOrNull()?:return false
        if(queue.trySend(bytes).isSuccess)return true
        relayEnd(RELAY_CONTROL);fail("Watch control queue full");return false
    }
    override suspend fun connect(history:JSONArray,event:(String)->Unit){
        check(current());this.history=history;events=event
        beginPrivate();opened=true
        if(privateOwned){
            if(androidx.core.content.ContextCompat.checkSelfPermission(app,android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                app.live.inputPending("Phone headset input permission unavailable; original Watch microphone")
            else if(LiveCaptureInput.headset(app)==null)app.live.inputPending("Headset input unavailable to Android; original Watch microphone")
            else {inputHold=java.util.UUID.randomUUID().toString();app.live.inputPending("Headset input pending verification; waiting for Watch microphone hold")}
        }
        check(command("open",JSONObject().put("relay",true).put("stream",streamToken).put("muted",inputMuted).put("private",privateOwned).put("output",outputToken.orEmpty()).put("input_hold",inputHold.orEmpty()).toString()))
        heartbeat=app.mainScope.launch{while(!closed){delay(1000);if(!current()){relayEnd(RELAY_SCOPE);fail("Watch Live account or media scope ended",observed=false);break};privateChanged();command("ping")}}
        deadline=app.mainScope.launch{delay(60_000);if(!ready.isCompleted)fail("Watch microphone/Phone relay did not admit Live; check paired access and foreground permission",error=java.util.concurrent.TimeoutException())}
        withTimeout(60_000){ready.await()}
    }
    fun receive(source:String,p:WatchLiveWire.Packet){
        if(closed||source!=node||p.id!=id||p.media!=media||p.epoch!=epoch||p.seq<=inputSequence)return
        if(p.seq!=inputSequence+1){relayEnd(RELAY_CONTROL);fail("Watch media sequence lost; no automatic reconnect");return}
        inputSequence=p.seq
        if(!current())return
        when(p.op){
            "offer"->{
                if(offerConsumed)return
                offerConsumed=true
                if(p.body!=WatchRelayAudio.OFFER){fail("Watch media version unavailable; update the paired apps, no direct-network fallback");return}
                val context=history?:return fail("Watch media arrived outside admission")
                val trace=DiagTrace(DiagStage.LIVE_RELAY) // setup step of the Phone-owned provider work; the session request marks its own
                providerJob=app.mainScope.launch(trace){
                    try{
                        withTimeout(10_000){channelReady.await()};if(!current())return@launch
                        trace.stage=DiagStage.LIVE_INPUT
                        val selected=selectInput();check(current())
                        trace.stage=DiagStage.LIVE_MEDIA
                        val t=providerFactory(selected,::decoded);provider=t
                        if(!current()){t.close();return@launch}
                        t.observeActivity{input->app.mainScope.launch{if(current()){activity?.invoke(input);if(!input)command("dialogue","output")}}}
                        t.mute(inputMuted)
                        t.connect(context){raw->app.mainScope.launch{
                            if(current()){events?.invoke(raw);if(current())command("event",raw)}
                            else if(!closed&&runCatching{JSONObject(raw).optString("type")}.getOrNull()=="session.closed")events?.invoke(raw)
                        }}
                        trace.stage=DiagStage.LIVE_RELAY
                        if(!current()){t.close();return@launch}
                        check(command("answer","phone-relay-ready"))
                    }catch(timeout:TimeoutCancellationException){fail("Live setup timed out; nothing is retried automatically",trace.stage,timeout)} // a setup step's own timeout, not Stop
                    catch(c:CancellationException){throw c}
                    catch(error:Exception){fail("Phone Live creation failed or uncertain"+((error as? HermesHttpException)?.status?.let{" (HTTP $it)"}?:"")+"; nothing is retried",trace.stage,error)}
                }
            }
            "connected"->{deadline?.cancel();ready.complete(Unit)}
            "input_ready"->{if(p.body==inputHold&&!held.isCompleted)held.complete(Unit)}
            "input_route"->{if(!phoneInputSelected)app.live.inputPending(p.body.take(200))}
            "private_ready"->{if(privateOwned&&p.body==outputToken&&!outputBlocked){watchQuiesced=true;if(privateAdmitted&&outputCurrent())app.live.outputPending("Watch microphone · Phone private Live output ready")}}
            // Watch owns only peripheral activity/capture reports; it cannot fabricate provider routing/delegation events.
            "event"->Unit
            "activity"->if(!phoneInputSelected)activity?.invoke(p.body=="input")
            "microphone"->{val active=p.body=="true";input.arm(active);if(!phoneInputSelected)microphone?.invoke(active)}
            "suspend"->app.live.end("Watch Live idle; authorized late replies may resume Watch microphone",kind="idle")
            "end","refused"->{relayEnd(RELAY_WATCH_ENDED);fail(p.body.take(300).ifBlank{"Watch Live stopped"},observed=p.op=="refused")}
        }
    }
    private companion object {
        /** [com.rumi.hermesvoice.core.diag.DiagCode.LIVE_RELAY_END]'s fixed relay boundaries. */
        const val RELAY_NONE=0;const val RELAY_STALLED=1;const val RELAY_NATIVE_FORMAT=2;const val RELAY_UPLINK_REFUSED=3;const val RELAY_UPLINK_STREAM=4
        const val RELAY_DOWNLINK_CONGESTED=5;const val RELAY_DOWNLINK_STREAM=6;const val RELAY_WATCH_ENDED=7;const val RELAY_SCOPE=8;const val RELAY_CONTROL=9
    }
    /** A setup failure (before Watch confirms) is observed once, type-only, before `ready` can resume the waiting connect inline,
     * so whichever side ends Live keeps it; the exception carries this specific reason instead of a generic one. Order is unchanged. */
    private fun fail(message:String,stage:DiagStage=DiagStage.LIVE_RELAY,error:Throwable?=null,observed:Boolean=true){
        if(closed||!accepting)return
        if(observed&&!ready.isCompleted&&app.live.active&&app.live.callIdentity()==media)app.liveSetupFailed(com.rumi.hermesvoice.core.VoiceOrigin.WATCH,stage,error)
        ready.completeExceptionally(LiveSetupFailure(message,error))
        if(app.live.active&&app.live.callIdentity()==media)app.live.end(message,kind="failure")
    }
    override fun observeActivity(listener:(Boolean)->Unit){activity=listener}
    override fun observeMicrophone(listener:(Boolean)->Unit){microphone=listener}
    /** The provider's numbers plus, once Started, this relay's own (end boundary, Watch packets and waits, Watch writes). */
    override suspend fun counters():LiveMediaCounters?=provider?.counters()?.let{c->
        val started=startedNs;val end=when{relayEndNs!=0L->relayEndNs;stoppedNs!=0L->stoppedNs;else->System.nanoTime()}
        val trailing=if(started==0L)0L else (end-maxOf(lastUpNs,started)).coerceAtLeast(0L)
        c.copy(relay=LiveRelayCounters(relayEndCode.get(),if(started==0L)null else ((end-started)/1_000_000).coerceAtLeast(0L),upPackets.get(),
            maxOf(upGapMaxNs,trailing)/1_000_000,downPackets.get(),downWriteMaxNs/1_000_000))
    }
    /** Watch speaker over the Phone-owned stream once output is enabled; a private Phone output only once admitted and the Watch quiesced. */
    override fun outputReady()=current()&&outputEnabled&&!outputBlocked&&routeReady&&(!privateOwned||(privateAdmitted&&outputCurrent()))
    override fun send(json:String)=!closed&&provider?.send(json)==true
    override fun mute(muted:Boolean){inputMuted=muted;input.mute(muted);capture?.mute(muted);provider?.mute(muted);if(opened)command("mute",muted.toString())}
    override fun output(enabled:Boolean){
        privateChanged();outputEnabled=enabled
        if(enabled&&startedNs==0L)startedNs=System.nanoTime() // the controller enables output exactly at the provider's session.started
        downlinkGeneration(enabled&&!privateOwned&&!outputBlocked)
        provider?.output(enabled);command("output",enabled.toString())
    }
    override fun silence(){if(stoppedNs==0L)stoppedNs=System.nanoTime();accepting=false;outputEnabled=false;downlinkGeneration(false);heartbeat?.cancel();releasePhoneInput();input.close();provider?.silence();stopOutput("Live audio stopped");command("silence")}
    override fun close(){
        if(closed)return
        if(stoppedNs==0L)stoppedNs=System.nanoTime()
        command("close");closed=true;accepting=false;routeReady=false;heartbeat?.cancel();deadline?.cancel();downlinkGeneration(false)
        releasePhoneInput();held.cancel();inputAdmission.cancel();input.close();providerJob?.cancel();provider?.close();provider=null
        reader?.cancel();writer?.cancel();channel?.let{Wearable.getChannelClient(app).close(it)};channel=null
        downlink.close();queue.close();channelReady.cancel();ready.cancel();pcm?.close();pcm=null
        events=null;activity=null;microphone=null
        app.mainScope.launch{withTimeoutOrNull(3000){sender.join()};sender.cancel()}
    }
}
