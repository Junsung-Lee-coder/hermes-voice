package com.rumi.hermesvoice.phone.live

import com.google.android.gms.wearable.*
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.phone.PhoneApp
import com.rumi.hermesvoice.phone.WatchSettingsSync
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.collect
import org.json.JSONObject

/** Exact-node controls only. No PCM, backend credential, provider route field or playback receipt travels here. */
class PhoneLiveWatchControl(private val app: PhoneApp) {
    private var remote: WatchLiveTransport? = null
    fun privateChanged(){remote?.privateChanged()}
    fun outputAdmitted(ok:Boolean){remote?.outputAdmitted(ok)}
    internal fun inputRequested(request:String)=remote?.inputRequested(request)==true
    internal fun inputAdmitted(request:String,ok:Boolean){remote?.inputAdmitted(request,ok)}
    fun audioChannel(channel:ChannelClient.Channel){
        if(remote?.receiveAudio(channel)!=true)Wearable.getChannelClient(app).close(channel)
    }
    internal fun admitting(node:String,call:String){if(owner?.first==node)ownerCall=call}
    internal fun transport(w:PhoneApp.Wiring,node:String):LiveTransport {
        val (ownerNode,id)=owner ?: error("Watch control lease missing")
        check(ownerNode==node)
        ownerCall=app.live.callIdentity()
        return WatchLiveTransport(app,w.dashboard,node,id,ownerCall!!,processEpoch) { bytes ->
            Wearable.getMessageClient(app).sendMessage(node,WatchLinkPaths.LIVE_STATUS,bytes).await()
        }.also { remote=it }
    }
    private var owner: Pair<String,String>? = null
    private var ownerCall: String? = null
    private val retired = HashSet<String>()
    private val pendingStarts = ArrayList<Pair<String,String>>()
    private val processEpoch = java.util.UUID.randomUUID().toString()
    private var sequence = 0L
    init { app.mainScope.launch { app.live.state.collect { sendState() } } }

    fun receive(node:String,bytes:ByteArray) {
        if(bytes.size>com.rumi.hermesvoice.core.live.WatchLiveWire.MAX_PACKET) return
        val j=try{JSONObject(bytes.toString(Charsets.UTF_8))}catch(_:Exception){return}
        if(j.optInt("v") !in 1..2) return
        val id=j.optString("id").takeIf{it.matches(Regex("[a-zA-Z0-9-]{1,80}"))}?:return
        val op=j.optString("op")
        if(j.optInt("v")==1 && op !in setOf("start","end","mute","mode","status","idle")) return
        val probe=(j.opt("probe") as? String)?.takeIf{it.length<=80}.orEmpty()
        val wakeControl=op=="start" && (j.opt("wake")==true || j.has("wake_claim_id") || j.has("recognized"))
        val wakeClaim=(j.opt("wake_claim_id") as? String)?.takeIf{com.rumi.hermesvoice.core.wake.WakeClaimMessage.isValidClaimId(it)}
        if(op=="start" && ((j.has("wake") && j.opt("wake") !is Boolean) || (j.has("wake_claim_id") && wakeClaim==null)))return
        val wakeWiring=if(op=="start")runCatching{app.wiring()}.getOrNull() else null
        val wakeScope=if(op=="start")app.bodyScope() else null
        val wakeRevision=if(op=="start")app.settings.watchSettings().revision else null
        val startAuthority=if(op=="start")app.live.startAuthority() else null
        fun releaseUnadmitted(){if(wakeControl && wakeClaim!=null)wakeWiring?.core?.wakeAdmission?.release(wakeClaim,VoiceOrigin.WATCH,node)}
        app.mainScope.launch {
            // R35: a Watch v2 media packet is handed on before any wait, so the current transport sees exactly the Wear listener's own
            // arrival order (one listener thread, FIFO onto main). Only that transport consumes it, and it admits only its exact owner
            // node (admitted by the capability check of its Start below), control, media, process epoch and next sequence, under the
            // current account and call. The reachable-capability lookup discovers a Watch-role node for v1 controls; per media packet
            // independent lookups could only reorder (Task completion order) or silently drop the owner's own packets into a sequence gap.
            if(j.optInt("v")==2){com.rumi.hermesvoice.core.live.WatchLiveWire.parse(bytes)?.let{remote?.receive(node,it)};return@launch}
            val pending=node to id
            // Terminal intent overtakes capability lookup, but only cancels this node's pending
            // starts; it cannot open media, change settings or end another active owner here.
            if(op=="end" && pending in pendingStarts && retired.size<1024)retired+=id
            if(op=="mode" && j.opt("enabled")==false){
                for((source,request) in pendingStarts)if(source==node && retired.size<1024)retired+=request
            }
            if(op=="start"){
                if(pendingStarts.size>=1024){releaseUnadmitted();inactive(node,id,probe,"Pending control history full; use a new Talk press");return@launch}
                pendingStarts+=pending
            }
            try {
            val capable=try{Wearable.getCapabilityClient(app).getCapability(WatchLinkPaths.CAPABILITY_WATCH,CapabilityClient.FILTER_REACHABLE).await().nodes.any{it.id==node}}
                catch(cancelled:CancellationException){releaseUnadmitted();throw cancelled}catch(_:Exception){false}
            if(!capable){releaseUnadmitted();return@launch}
            if(op=="start" && (wakeWiring==null || wakeScope==null || app.existingWiring()!==wakeWiring || app.bodyScope()!=wakeScope || app.settings.watchSettings().revision!=wakeRevision || (app.live.startAuthority()!=startAuthority && !(owner==(node to id) && ownerCall==app.live.callIdentity() && app.live.active)))){
                releaseUnadmitted();inactive(node,id,probe,"Live settings, account or control changed; use a new Talk press or wake phrase");return@launch
            }
            if(op=="mode" || op=="idle") {
                val latest=app.settings.watchSettings()
                // An old Watch form is not authority to overwrite a newer Phone-owned snapshot.
                if(!(op=="mode" && j.opt("enabled")==false) && (j.opt("revision") as? Number)?.toLong()!=latest.revision){publish(latest);return@launch}
                val requested=if(op=="mode") {
                    val enabled=j.opt("enabled") as? Boolean?:return@launch
                    latest.copy(watchLiveEnabled=enabled)
                } else {
                    val seconds=(j.opt("seconds") as? Int)?.takeIf{it in 1..60}?:return@launch
                    latest.copy(liveIdleSeconds=seconds)
                }
                val saved=app.settings.saveWatchSettings(requested)
                app.sharedVoiceSettings.value=saved;app.live.settingsChanged();publish(saved);return@launch
            }
            val owns=owner==(node to id) && ownerCall==app.live.callIdentity()
            if(op=="status") {
                if(owns) sendState(probe) else inactive(node,id,probe,"Previous Phone Live call ended; Talk starts a new call")
                return@launch
            }
            if(op=="start") {
                if(owns && app.live.active){sendState(probe);return@launch}
                if(id in retired || retired.size>=1024){releaseUnadmitted();inactive(node,id,probe,"Previous control was closed or the control history is full; use a new Talk press or Phone Talk");return@launch}
                retired+=id // before any foreground/media admission; duplicate start never creates a second session.
                if(app.live.active){releaseUnadmitted();inactive(node,id,probe,"Phone audio is busy");return@launch}
                val recognized=j.opt("recognized") as? String
                if(recognized!=null && recognized.length>6000){releaseUnadmitted();inactive(node,id,probe,"Wake request is too long; open Live and restate it");return@launch}
                val sentRevision=j.opt("wake_revision")
                if(wakeControl && j.has("wake_revision") && ((sentRevision !is Long && sentRevision !is Int) || (sentRevision as Number).toLong()!=wakeRevision)){
                    releaseUnadmitted();inactive(node,id,probe,"Wake settings changed; say a new wake phrase");return@launch
                }
                owner=node to id
                app.live.start(VoiceOrigin.WATCH,node,recognized,wakeTurn=wakeControl,wakeClaimId=wakeClaim,wakeSettingsRevision=wakeRevision)
                ownerCall=if(app.live.active) app.live.callIdentity() else null
                sendState(probe);return@launch
            }
            if(owns) {
                if(op=="end") app.live.end("Ended from Watch; Hermes work continues")
                if(op=="mute") (j.opt("muted") as? Boolean)?.let{app.live.setMuted(it)}
                sendState(probe)
            } else inactive(node,id,probe,"Previous Phone Live call ended")
            } finally { if(op=="start")pendingStarts.remove(pending) }
        }
    }
    private suspend fun publish(settings:com.rumi.hermesvoice.core.settings.WatchSettings) {
        try{WatchSettingsSync.publish(app,settings)}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){}
    }
    private fun envelope(id:String,probe:String)=JSONObject().put("v",1).put("id",id).put("epoch",processEpoch).put("seq",++sequence)
        .put("probe",probe).put("media",ownerCall.orEmpty()).put("phone_audio",false).put("watch_audio",true)
    private fun inactive(node:String,id:String,probe:String,status:String) {
        if(retired.size<1024) retired+=id // an End/status that overtakes an old Start is a tombstone, not permission to reopen it.
        send(node,envelope(id,probe).put("active",false).put("muted",false).put("status",status))
    }
    private fun sendState(probe:String="") {
        val(node,id)=owner?:return
        val s=app.live.state.value
        val owns=ownerCall==app.live.callIdentity()
        // "listening": the Phone-confirmed moment Watch speech can reach the provider (an older Watch ignores the field).
        send(node,envelope(id,probe).put("active",s.active&&owns).put("muted",s.muted).put("end_reason",s.endReason).put("listening",owns&&s.listening)
            .put("status",if(!s.active||owns)(s.status+if(s.inputNotice.isNotBlank())" · "+s.inputNotice else "").take(300) else "Previous Watch-controlled Live ended"))
    }
    private fun send(node:String,json:JSONObject) {
        val bytes=json.toString().toByteArray(Charsets.UTF_8)
        app.mainScope.launch {
            try{Wearable.getMessageClient(app).sendMessage(node,WatchLinkPaths.LIVE_STATUS,bytes).await()}
            catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){}
        }
    }
    companion object { const val COMMAND=WatchLinkPaths.LIVE_CONTROL;const val STATE=WatchLinkPaths.LIVE_STATUS }
}
