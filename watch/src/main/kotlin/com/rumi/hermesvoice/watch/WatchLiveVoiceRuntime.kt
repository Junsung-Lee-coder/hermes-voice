package com.rumi.hermesvoice.watch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.diag.DiagCause
import com.rumi.hermesvoice.core.diag.DiagClassify
import com.rumi.hermesvoice.core.diag.DiagCode
import com.rumi.hermesvoice.core.diag.DiagFail
import com.rumi.hermesvoice.core.diag.DiagOrigin
import com.rumi.hermesvoice.core.diag.DiagStage
import com.rumi.hermesvoice.core.live.*
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import com.rumi.hermesvoice.core.watchlink.HapticEvent
import org.json.JSONObject
import java.util.UUID

/** [listening]: the Phone confirmed this Watch call's speech can reach the provider now (started, verified input, not muted). */
data class WatchLiveState(val active:Boolean=false,val muted:Boolean=false,val status:String="Watch Live",val pending:Boolean=false,val inputActive:Boolean=false,val listening:Boolean=false)

/** Watch owns its real audio peripherals; Phone owns provider media/network, authentication and Hermes work. */
class WatchLiveVoiceRuntime(private val app:WatchApp,private val phone:suspend()->String?) {
    private val mutable=MutableStateFlow(WatchLiveState())
    val state:StateFlow<WatchLiveState> = mutable
    // The wearer's own microphone-state haptic when this Watch call starts and stops genuinely listening (no screen text).
    init{app.scope.launch{state.map{it.listening}.distinctUntilChanged().drop(1).collect{app.haptic(if(it)HapticEvent.RECORDING_START else HapticEvent.RECORDING_END)}}}
    private var id=app.localStore.getString("live_control_id")?.takeIf{it.isNotBlank()}
    private var node=app.localStore.getString("live_control_node")?.takeIf{it.isNotBlank()}
    private var sequence=-1L;private var phoneEpoch:String?=null;private var pendingProbe:String?=null
    @Volatile private var authorized=false;@Volatile private var mediaId:String?=null;private val retired=HashSet<String>()
    private var rx=0L;private var tx=0L;@Volatile private var generation=0L;private var lastPhone=0L
    private var media:WatchLiveMedia?=null;private var route:WatchLiveRoute?=null
    private var privateOwned=false;@Volatile private var outputToken:String?=null;private var streamId:String?=null
    private var inputHold:String?=null
    private var inputHeld=false
    private var cpu:android.os.PowerManager.WakeLock?=null
    private var answer:CompletableDeferred<String>?=null
    private var connection:Job?=null;private var guard:Job?=null;private var lifetime:LiveLifetime?=null
    private var outgoing:Channel<ByteArray>?=null;private var sender:Job?=null
    private var pendingWakeClaim:String?=null
    private var wakeSent=false
    /** This control's Start was sent and its media has not opened yet; an active Phone state meanwhile is still pending work, not idle. */
    private var awaitingMedia=false
    private var wakeCaptureEnded:(()->Unit)?=null
    private fun settleWakeCapture(){val ended=wakeCaptureEnded;wakeCaptureEnded=null;ended?.invoke()}
    /** R32: this media's open time, whether its one setup observation was made and whether the Phone relayed its session.started. */
    private var openedAtMs=0L;private var setupObserved=false;private var callStarted=false
    /** R41: whether this media's one relay observation (its packet counts, however it ended after Started) was made. */
    private var relayObserved=false
    internal var now:()->Long={android.os.SystemClock.elapsedRealtime()}
    internal var mediaFactory:()->WatchLiveMedia={
        val g=generation
        lateinit var created:WatchChannelMedia
        WatchChannelMedia(app,node?:error("Phone node missing"),WatchRelayAudio.Address(id?:error("Control missing"),mediaId?:error("Media missing"),phoneEpoch?:error("Epoch missing"),streamId?:error("Paired relay version unavailable")),
            {isCurrent(g)&&eligible()},{isCurrent(g)&&!privateOwned&&route?.valid()==true},
            {if(isCurrent(g))refuse("Phone audio relay unavailable; Watch audio stopped without fallback",created.lossStep?:WatchLiveSetupStep.RELAY_LOST,created.lossError)}).also{created=it}
    }
    internal var routeFactory:(()->Unit)->WatchLiveRoute={WatchNativeLiveRoute(app,it)}
    internal var admission:(Long)->Unit={token->ContextCompat.startForegroundService(app,Intent(app,WatchLiveVoiceService::class.java).putExtra("generation",token))}
    private fun save(){app.localStore.putString("live_control_id",id.orEmpty());app.localStore.putString("live_control_node",node.orEmpty())}
    fun start(recognizedText:String?=null,wakeTurn:Boolean=false,wakeClaimId:String?=null,captureEnded:(()->Unit)?=null):Boolean {
        fun refused():Boolean{
            if(wakeClaimId!=null && !(id!=null && pendingWakeClaim==wakeClaimId))
                app.sendWakeClaim(com.rumi.hermesvoice.core.wake.WakeClaimMessage(com.rumi.hermesvoice.core.wake.WakeClaimMessage.Op.RELEASE,wakeClaimId))
            return false
        }
        if(!app.settings.value.watchLiveEnabled)return refused()
        if(id!=null){if(mediaId!=null || mutable.value.pending)return refused();end(restart=true)}
        toggle(recognizedText,wakeTurn || wakeClaimId!=null,wakeClaimId,captureEnded);return true
    }
    fun toggle(recognizedText:String?=null,wakeTurn:Boolean=false,wakeClaimId:String?=null,captureEnded:(()->Unit)?=null){
        if(id!=null){if(mediaId!=null || mutable.value.pending){end();return};end(restart=app.settings.value.watchLiveEnabled)}
        if(!app.settings.value.watchLiveEnabled)return
        authorized=true;awaitingMedia=true;id=UUID.randomUUID().toString();sequence=-1;phoneEpoch=null
        pendingWakeClaim=wakeClaimId;wakeSent=false
        wakeCaptureEnded=captureEnded
        mutable.value=WatchLiveState(status="Preparing Watch Live; say which conversation",pending=true)
        command("start",recognizedText,wakeTurn,wakeClaimId,app.settings.value.revision)
    }
    /** R37: [restart]: a new control is published as pending right after this end, so no ended (idle) state is published in
     * between; the app's wake recognizer would reopen for that moment while the Watch Live call is still the microphone's owner. */
    fun end(restart:Boolean=false){
        val old=id;val target=node
        // Only positive pre-send cancellation gives a claim back. A failed/uncertain Data Layer
        // send may already have admitted Live on Phone; never release/retry that episode here.
        if(!wakeSent)pendingWakeClaim?.let{app.sendWakeClaim(com.rumi.hermesvoice.core.wake.WakeClaimMessage(com.rumi.hermesvoice.core.wake.WakeClaimMessage.Op.RELEASE,it))}
        pendingWakeClaim=null;wakeSent=false
        authorized=false;stopMedia(settleCapture=true);id=null;node=null;save();if(!restart)mutable.value=WatchLiveState(status="Watch Live ended; Hermes work continues")
        if(old!=null && target!=null)app.scope.launch{sendControl(target,JSONObject().put("v",1).put("id",old).put("op","end").put("probe",UUID.randomUUID().toString()))}
    }
    fun mute(){if(id!=null)command("mute")}
    /** Origin-device navigation revokes locally BEFORE any reader/network/foreground wait. */
    fun navigationChanged(){if(id!=null)end()}
    fun setIdle(seconds:Int){
        if(seconds !in 1..60)return
        app.scope.launch{val target=phone()?:return@launch;sendControl(target,JSONObject().put("v",1).put("id",UUID.randomUUID().toString()).put("op","idle").put("seconds",seconds).put("revision",app.settings.value.revision))}
    }
    fun setMode(enabled:Boolean){
        if(!enabled)end()
        app.scope.launch{val target=phone()?:return@launch;sendControl(target,JSONObject().put("v",1).put("id",UUID.randomUUID().toString()).put("op","mode").put("enabled",enabled).put("revision",app.settings.value.revision))}
    }
    /** R37: [keepPending]: a failed status probe of a call that was pending leaves it pending while its media is still awaited (never raises it).
     * R38 (T6): so does a failed send of the call's own Start: sendMessage is best-effort, so the Start may have arrived. */
    private suspend fun sendControl(target:String,j:JSONObject,keepPending:Boolean=false){
        try{Wearable.getMessageClient(app).sendMessage(target,COMMAND,j.toString().toByteArray()).await()}
        catch(c:CancellationException){throw c}
        // R37: a failed message (an End, Start or status) of a control that a newer control has replaced leaves the newer one alone.
        // R38: so does any late failure once this control's media is open: the Phone answered, so the call's state is the media's.
        catch(_:Exception){if((id==null || j.optString("id")==id) && mediaId==null)mutable.value=mutable.value.copy(status="Phone control not confirmed; check Phone",pending=keepPending&&awaitingMedia&&mutable.value.pending)}
    }
    private fun command(op:String,recognizedText:String?=null,wakeTurn:Boolean=false,wakeClaimId:String?=null,wakeRevision:Long?=null){
        val request=id?:return
        app.scope.launch{
            val target=phone()
            if(id!=request)return@launch
            if(target==null || (node!=null && node!=target)){end();return@launch}
            if(wakeTurn && (!app.settings.value.watchLiveEnabled || app.settings.value.revision!=wakeRevision)){end();return@launch}
            node=target;save();val probe=UUID.randomUUID().toString();pendingProbe=probe
            val wasPending=mutable.value.pending
            mutable.value=mutable.value.copy(pending=true)
            if(op=="start" && wakeTurn)wakeSent=true
            sendControl(target,JSONObject().put("v",1).put("id",request).put("op",op).put("probe",probe).apply{
                if(op=="mute")put("muted",!mutable.value.muted)
                if(op=="start"&&!recognizedText.isNullOrBlank())put("recognized",recognizedText)
                if(op=="start"&&wakeTurn){put("wake",true);put("wake_revision",wakeRevision);if(wakeClaimId!=null)put("wake_claim_id",wakeClaimId)}
            },keepPending=(op=="start"||op=="status")&&wasPending)
        }
    }
    fun onStatus(source:String,bytes:ByteArray){
        if(source!=node || bytes.size>WatchLiveWire.MAX_PACKET)return
        val j=try{JSONObject(bytes.toString(Charsets.UTF_8))}catch(_:Exception){return}
        if(j.optInt("v")==2){WatchLiveWire.parse(bytes)?.let{onMedia(source,it)};return}
        if(bytes.size>4096 || j.optInt("v")!=1 || j.optString("id")!=id || j.opt("watch_audio")!=true || j.opt("phone_audio")!=false)return
        val seq=(j.opt("seq") as? Number)?.toLong()?:return
        val active=j.opt("active") as? Boolean?:return;val muted=j.opt("muted") as? Boolean?:return
        val epoch=(j.opt("epoch") as? String)?.takeIf{it.length in 1..80}?:return
        if(epoch!=phoneEpoch){
            if(pendingProbe==null || j.optString("probe")!=pendingProbe)return
            // A positively answered status probe from a NEW Phone process revokes the old
            // process's media. This is not an unbound same-epoch idle/End instruction.
            if(mediaId!=null){authorized=false;stopMedia()}
            phoneEpoch=epoch;sequence=-1
        }
        if(seq<=sequence)return
        // v1 sequence and v2 media sequence are different domains. A delayed state for m1
        // cannot terminate/mute m2, even when its v1 sequence is newer. Unbound old v1 is
        // presentation-only once a v2 peer exists; current End carries the exact media id.
        if(mediaId!=null && j.optString("media")!=mediaId)return
        sequence=seq;if(j.optString("probe")==pendingProbe)pendingProbe=null
        if(!active){stopMedia(settleCapture=true);if(j.optString("end_reason")!="idle"){authorized=false;id=null;node=null;save()}}
        mutable.value=mutable.value.copy(active=active&&mediaId!=null,muted=muted,status=j.optString("status").take(300),pending=active&&awaitingMedia,listening=active&&mediaId!=null&&j.opt("listening")==true)
    }
    private fun onMedia(source:String,p:WatchLiveWire.Packet){
        if(source!=node || p.id!=id || !authorized || !app.settings.value.watchLiveEnabled)return
        if(p.op=="open"){
            if(p.media==mediaId || p.media in retired || retired.size>=1024 || p.seq!=1L)return
            if(phoneEpoch!=null && phoneEpoch!=p.epoch)return
            // R37: the new media is published active and pending below, in this same turn: replacing the old one publishes no idle state.
            phoneEpoch=p.epoch;stopMedia(publish=false);mediaId=p.media;rx=1;tx=0;lastPhone=now()
            openedAtMs=now();setupObserved=false;callStarted=false;relayObserved=false
            val target=node?:return
            val q=Channel<ByteArray>(64);outgoing=q
            sender=app.scope.launch{try{for(b in q)Wearable.getMessageClient(app).sendMessage(target,COMMAND,b).await()}catch(c:CancellationException){throw c}catch(error:Exception){if(mediaId==p.media)refuse("Watch signaling failed; no Phone audio fallback",WatchLiveSetupStep.SIGNALING,error)}}
            val declaredMute=runCatching{p.body.isNotBlank() && JSONObject(p.body).optBoolean("muted")}.getOrDefault(false)
            val options=runCatching{JSONObject(p.body)}.getOrNull()
            streamId=options?.optString("stream")?.takeIf{it.matches(Regex("[A-Za-z0-9-]{1,80}"))}
            privateOwned=app.settings.value.privateAudio || options?.optBoolean("private")==true
            outputToken=options?.optString("output")?.takeIf{it.matches(Regex("[A-Za-z0-9-]{1,80}"))}
            inputHold=options?.optString("input_hold")?.takeIf{it.matches(Regex("[A-Za-z0-9-]{1,80}"))}
            mutable.value=WatchLiveState(active=true,muted=declaredMute,pending=true,status="Admitting Watch microphone…")
            val token=++generation
            guard=app.scope.launch{
                val deadline=now()+60_000
                while(isCurrent(token)){
                    delay(500)
                    if(!eligible()){refuse("Watch microphone permission/private route changed",WatchLiveSetupStep.ELIGIBILITY_CHANGED,fail=ineligible());break}
                    if(now()-lastPhone>=5000){refuse("Phone control lease expired; Watch microphone closed",WatchLiveSetupStep.CONTROL_LEASE);break}
                    if(media==null && now()>=deadline){refuse("Android did not admit Watch microphone",WatchLiveSetupStep.NOT_ADMITTED);break}
                    checkLifetime()
                }
            }
            try{admission(token)}catch(error:Exception){refuse("Android refused Watch foreground microphone; allow access and use Talk",WatchLiveSetupStep.SERVICE_START,error)}
            return
        }
        if(p.media!=mediaId || p.epoch!=phoneEpoch || p.seq<=rx)return
        if(p.seq!=rx+1){refuse("Phone control sequence lost; Watch microphone closed",WatchLiveSetupStep.CONTROL_SEQUENCE);return}
        rx=p.seq;lastPhone=now()
        when(p.op){
            "dialogue"->if(p.body=="output"||p.body=="input")lifetime?.dialogue(now())
            "answer"->answer?.complete(p.body)
            "event"->{if(runCatching{JSONObject(p.body).optString("type")}.getOrNull()=="session.started"){lifetime?.connected(now());callStarted=true}}
            "send"->if(media?.send(p.body)!=true)refuse("Watch DataChannel unavailable; message not delivered",WatchLiveSetupStep.DATA_CHANNEL)
            "mute"->{val muted=p.body=="true";media?.mute(muted);mutable.value=mutable.value.copy(muted=muted)}
            "output"->{if(p.body=="true" && route?.valid()!=true)refuse("Watch output route unconfirmed",WatchLiveSetupStep.OUTPUT_ROUTE)else media?.output(p.body=="true")}
            "private"->{selectPrivate(p.body.takeIf{it.matches(Regex("[A-Za-z0-9-]{1,80}"))})}
            "input_watch"->{if(p.body==inputHold&&inputHold!=null){
                if(inputHeld&&media?.holdInput(false)!=true){refuse("Watch input release unavailable; start a new Talk",WatchLiveSetupStep.INPUT_RELEASE);return}
                inputHeld=false;inputHold=null
            }}
            "silence"->media?.silence()
            "close"->{stopMedia(settleCapture=true)}
            "ping"->Unit
        }
    }
    internal fun isCurrent(token:Long)=authorized&&mediaId!=null&&generation==token
    internal fun admissionRefused(token:Long,error:Throwable?=null,foreground:Boolean=false){
        if(isCurrent(token))refuse("Android refused Watch foreground microphone; allow access and use Talk",if(foreground)WatchLiveSetupStep.ADMISSION_SETUP else WatchLiveSetupStep.SERVICE_FOREGROUND,error)
    }
    private fun eligible()=app.settings.value.watchLiveEnabled&&app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
    private fun captureOnlyRoute()=object:WatchLiveRoute{override fun open()=true;override fun valid()=true;override fun close(){}}
    /** Sticky private ownership: removal never re-enables a public Watch speaker. Only a fresh Talk may choose local output. */
    private fun selectPrivate(token:String?) {
        privateOwned=true;outputToken=token
        val local=media?:return // admission still pending: no stop receipt until the real adapter exists
        try{
            local.phoneOutput() // returns only after the default adapter has stopped/released its native player
            route?.close();route=captureOnlyRoute();check(route?.open()!=false)
        }catch(error:Exception){refuse("Watch private-output stop was not confirmed; Live stopped without speaker fallback",WatchLiveSetupStep.PRIVATE_STOP,error);return}
        if(token!=null&&!emit("private_ready",token))refuse("Private output confirmation unavailable; Live stopped without fallback",WatchLiveSetupStep.PRIVATE_CONFIRMATION)
    }
    internal fun admitted(token:Long){
        if(!isCurrent(token)||media!=null)return
        if(!eligible()){refuse("Watch microphone is denied or busy; no Phone microphone fallback",WatchLiveSetupStep.NOT_ELIGIBLE,fail=ineligible());return}
        if(app.voice.capturing){refuse("Watch microphone is denied or busy; no Phone microphone fallback",WatchLiveSetupStep.BUSY);return}
        app.voice.coordinator.onBusy();app.stopPlayback("Watch Live owns audio")
        cpu=app.getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"HermesVoice:watch-live").apply{setReferenceCounted(false);acquire(360_000)}
        connection=app.scope.launch{
            var step=WatchLiveSetupStep.ROUTE_THREW // R32: the setup step this connection is in, for its one observation
            // Inside connect the media names its own Channel step.
            fun at()=if(step==WatchLiveSetupStep.CHANNEL_OPEN)media?.setupStep?:step else step
            try{
                val r=if(privateOwned)captureOnlyRoute() else routeFactory{if(isCurrent(token)&&!privateOwned){media?.silence();app.scope.launch{if(isCurrent(token)&&!privateOwned)refuse("Watch audio route/focus lost; no speaker fallback",WatchLiveSetupStep.ROUTE_LOST)}}}
                route=r;val opened=r.open();if(!opened)step=r.refusal?:WatchLiveSetupStep.ROUTE_NOT_VALID
                check(opened){"Watch communication audio route unavailable"}
                if(!isCurrent(token)){r.close();return@launch}
                step=WatchLiveSetupStep.MEDIA_FACTORY
                val t=mediaFactory();media=t
                step=WatchLiveSetupStep.MEDIA_SETUP
                if(privateOwned)selectPrivate(outputToken)
                lifetime=LiveLifetime(now(),app.settings.value.liveIdleSeconds*1000L,waitingForConnection=true)
                t.observeActivity{input->app.scope.launch{if(isCurrent(token)){lifetime?.dialogue(now());emit("activity",if(input) "input" else "output")}}}
                t.observeMicrophone{active->app.scope.launch{if(isCurrent(token)){mutable.value=mutable.value.copy(inputActive=active);emit("microphone",active.toString())}}}
                t.observeInputRoute{description->app.scope.launch{if(isCurrent(token))emit("input_route",description)}}
                t.mute(mutable.value.muted) // a mute received before admission is the native capture state before connect
                inputHold?.let{hold->if(t.holdInput(true)){inputHeld=true;check(emit("input_ready",hold))}}
                step=WatchLiveSetupStep.CHANNEL_OPEN
                t.connect({offer->step=WatchLiveSetupStep.OFFER_NOT_SENT;val deferred=CompletableDeferred<String>();answer=deferred;check(emit("offer",offer));step=WatchLiveSetupStep.ANSWER;withTimeout(60_000){deferred.await()}}){raw->app.scope.launch{
                    if(isCurrent(token)){
                        if(runCatching{JSONObject(raw).optString("type")}.getOrNull()=="session.started"){lifetime?.connected(now());callStarted=true}
                        emit("event",raw)
                    }
                }}
                if(!isCurrent(token)){t.close();return@launch}
                step=WatchLiveSetupStep.CONNECTED_NOT_SENT
                check(emit("connected"));mutable.value=mutable.value.copy(pending=false,status=if(privateOwned)"Watch microphone · Phone private Live output" else "Watch microphone/speaker · Phone network relay")
            }catch(c:CancellationException){
                // A setup bound that elapsed (or a cancelled Task) while this call is still current is no Stop: it is observed at its
                // step, then ends this connection exactly as before (no refusal, no retry).
                if(isCurrent(token))observeSetup(at(),c,null)
                throw c
            }
            catch(error:Exception){if(isCurrent(token)){
                // A route that refused is the app's own refusal, not an exception.
                val at=at()
                refuse("Watch Live unavailable: check paired Phone/network, Watch microphone/background permission and audio route; no automatic retry",at,error.takeUnless{at.n in 11..16})
            }}
        }
    }
    internal fun checkLifetime(){
        lifetime?.grace(app.settings.value.liveIdleSeconds)
        val reason=lifetime?.expired(now())?:return
        if(reason=="idle"){val muted=mutable.value.muted;emit("suspend");stopMedia(settleCapture=true);mutable.value=WatchLiveState(muted=muted,status="Watch Live idle; authorized late answer may reopen Watch microphone")}
        else refuse("Watch Live absolute cost limit reached",WatchLiveSetupStep.COST_LIMIT)
    }
    private fun emit(op:String,body:String=""):Boolean {
        val i=id?:return false;val m=mediaId?:return false;val e=phoneEpoch?:return false
        val packet=runCatching{WatchLiveWire.packet(i,m,e,++tx,op,body)}.getOrNull()?:return false
        return outgoing?.trySend(packet)?.isSuccess==true
    }
    /** [step]: where this Watch refusal happened; [error]: the exception that ended it, if one did; [fail]: the type of the app's own refusal. */
    private fun refuse(message:String,step:WatchLiveSetupStep,error:Throwable?=null,fail:DiagFail?=null){
        observeSetup(step,error,fail,refusal=true)
        emit("refused",message);authorized=false;stopMedia(settleCapture=true);mutable.value=WatchLiveState(status=message)
    }
    private fun ineligible()=if(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)DiagFail.AUTH else DiagFail.CANCELLED
    /** R32: a Watch refusal of this media before the Phone relayed its session.started, or a setup bound that elapsed (a cancellation
     * that was no Stop), is recorded once, before teardown, in the Watch's own journal: live_setup_failed (origin watch), n the fixed
     * [WatchLiveSetupStep], stage, fail/cause by the error's TYPE (or the step's own refusal type), status a Play services Task status,
     * ms since the Watch received this media's open. Never a message, id, device, endpoint or SDP. A Stop, a Phone end and stale
     * callbacks are not refusals; a refusal after the start is not setup. R34: such a [refusal] after the relayed session.started is
     * where the Watch stopped the started call: recorded once per media as live_ended (origin watch, stage live_start), [DiagEvent.n]
     * the same fixed step (for a media loss, the step the media reported); nothing else is recorded after the start. A loss the Phone's
     * own teardown caused before its silence arrived is recorded the same way: only the Phone's end tells the two apart. */
    private fun observeSetup(step:WatchLiveSetupStep,error:Throwable?,fail:DiagFail?,refusal:Boolean=false){
        if(mediaId==null||setupObserved||(callStarted&&!refusal))return
        setupObserved=true
        val chain=generateSequence(error){it.cause?.takeIf{cause->cause!==it}}.take(6).toList()
        val notAllowed=chain.any{it.javaClass.simpleName=="ForegroundServiceStartNotAllowedException"}
        val typed=error?.let{DiagClassify.of(it)}
        val kind=when{notAllowed->DiagFail.REFUSED;typed!=null->typed.fail;else->fail?:step.fail}
        val cause=when{notAllowed->DiagCause.REJECTED;typed!=null->typed.cause;kind==DiagFail.AUTH->DiagCause.AUTH;kind==DiagFail.TIMEOUT->DiagCause.TIMEOUT;kind==DiagFail.CANCELLED->DiagCause.CANCELLED;else->DiagCause.LOCAL}
        app.diag.record(if(callStarted)DiagCode.LIVE_ENDED else DiagCode.LIVE_SETUP_FAILED,DiagOrigin.WATCH,n=step.n,ms=(now()-openedAtMs).coerceAtLeast(0),fail=kind,
            stage=if(callStarted)DiagStage.LIVE_START else step.stage,cause=cause,status=chain.firstNotNullOfOrNull{(it as? ApiException)?.statusCode})
    }
    /** R41: once per media whose call started, however it stops (a Phone End or close too, which records no refusal): its relay counts in
     * the Watch's own journal, fixed numbers only, before teardown. */
    private fun observeRelay(media:WatchLiveMedia?){
        if(media==null||!callStarted||relayObserved)return
        val r=media.relay?:return
        relayObserved=true
        fun count(v:Long)=minOf(v,com.rumi.hermesvoice.core.diag.DiagLog.MAX_NUMBER.toLong()).toInt()
        app.diag.record(DiagCode.LIVE_RELAY_CAPTURE,DiagOrigin.WATCH,n=count(r.captured),ms=r.readWaitMs)
        app.diag.record(DiagCode.LIVE_RELAY_NONZERO,DiagOrigin.WATCH,n=count(r.nonzero),ms=r.speechMs)
        app.diag.record(DiagCode.LIVE_RELAY_SENT,DiagOrigin.WATCH,n=count(r.written),ms=r.writeMs)
    }
    /** R37: [publish] false only where the caller publishes the call's next busy state in the same turn (a new media's open). */
    private fun stopMedia(settleCapture:Boolean=false,publish:Boolean=true){
        ++generation;awaitingMedia=false;mediaId?.let{retired+=it};mediaId=null
        guard?.cancel();guard=null;connection?.cancel();connection=null;answer?.cancel();answer=null
        val old=media;media=null
        observeRelay(old)
        old?.silence();old?.close();route?.close();route=null;lifetime=null
        outputToken=null;streamId=null;privateOwned=false;inputHold=null;inputHeld=false
        outgoing?.close();outgoing=null;val worker=sender;sender=null
        if(worker!=null)app.scope.launch{withTimeoutOrNull(3000){worker.join()};worker.cancel()}
        cpu?.let{if(it.isHeld)it.release()};cpu=null
        WatchLiveVoiceService.running?.finish()
        // The native owner is stopped before shared custody is settled; presence sees idle last.
        if(settleCapture)settleWakeCapture()
        if(publish)mutable.value=mutable.value.copy(active=false,inputActive=false,pending=false,listening=false)
    }
    fun settingsChanged(){
        if(!app.settings.value.watchLiveEnabled){if(id!=null)end()}
        else if(app.settings.value.privateAudio && !privateOwned && mediaId!=null)selectPrivate(outputToken)
        lifetime?.grace(app.settings.value.liveIdleSeconds)
    }
    fun refresh(){if(id!=null)command("status")}
    companion object {const val COMMAND=WatchLinkPaths.LIVE_CONTROL;const val STATUS=WatchLinkPaths.LIVE_STATUS}
}
