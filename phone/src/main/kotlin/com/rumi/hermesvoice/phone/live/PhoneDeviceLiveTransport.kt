package com.rumi.hermesvoice.phone.live

import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.diag.DiagStage
import com.rumi.hermesvoice.core.net.HermesDashboardClient
import com.rumi.hermesvoice.core.live.LivePcmFrame
import com.rumi.hermesvoice.phone.PhoneApp
import kotlinx.coroutines.*
import org.json.JSONArray

/** Phone-origin Live uses the same verified external capture as Watch-origin headset input.
 * The ordinary ADM capture factory is never a fallback. */
internal class PhoneDeviceLiveTransport(private val app:PhoneApp,private val dashboard:HermesDashboardClient):LiveTransport {
    private val call=app.live.callIdentity();private val scope=app.bodyScope()
    @Volatile private var closed=false;private var muted=false;private var output=false
    private var native:LiveTransport?=null;private var capture:LiveCaptureInput?=null;private var pcm:LivePcmOutput?=null
    private var microphone:((Boolean)->Unit)?=null;private var activity:((Boolean)->Unit)?=null
    private var inputName="Phone microphone"
    private fun captureChanged(){app.mainScope.launch{if(current()){
        val active=capture?.active()==true
        if(active)app.live.inputPending(inputName+" · OS capture route verified")
        microphone?.invoke(active)
    }}}
    private val privateId=app.headset.output()?.id
    // Asked on the pinned ADM thread for every 10 ms frame: the exact scope without a per-frame Keystore decrypt (R33).
    private fun current()=!closed&&scope!=null&&app.live.active&&app.live.callIdentity()==call&&app.liveBodyScope()==scope&&app.existingWiring()?.dashboard===dashboard
    private fun policySelected():Boolean {val id=app.headset.output()?.id;return id==privateId||(id!=null&&id==capture?.playbackDeviceId())}
    private fun outputCurrent()=current()&&policySelected()
    private fun lose(){if(current()){silence();app.mainScope.launch{if(current()){
        // Before the provider started only the input route can be lost here: one type-only setup observation in the same step as end; after it is runtime.
        if(!app.live.state.value.started)app.liveSetupFailed(VoiceOrigin.PHONE,DiagStage.LIVE_INPUT,null)
        app.live.end("Live input/output route lost; start a new Talk. No microphone splicing or speaker fallback",kind="failure")
    }}}}
    private fun decoded(frame:LivePcmFrame){
        if(!output||!current())return
        if(!outputCurrent()){lose();return}
        val player=pcm?:PhonePrivateLivePcm(app,capture?.playbackDeviceId()?:privateId?:return,::outputCurrent,::lose,ownsAudioLease=true,selectedPolicy=::policySelected).also{pcm=it}
        if(!player.write(frame))lose()
    }
    override suspend fun connect(history:JSONArray,event:(String)->Unit){
        check(current())
        val personal=app.headset.output()
        val preferred=LiveCaptureInput.headset(app)
        // Original Phone input admits every OS-exposed built-in source; LiveCaptureInput pins the actually routed one.
        var selected=preferred?.let{listOf(it)}?:LiveCaptureInput.original(app)?:error("Original Phone microphone unavailable")
        var headset=preferred!=null
        var description=if(preferred!=null)"Headset input route pending" else if(personal!=null)"Headset input unavailable; original Phone microphone" else "Phone microphone"
        app.live.inputPending(description)
        suspend fun prepare(){
            inputName=if(headset)"Headset microphone" else description
            val source=LiveCaptureInput(app,selected,{current()&&(privateId==null||policySelected())},{captureChanged()},::lose,{activity?.invoke(true)})
            capture=source;source.mute(muted);source.prepare()
        }
        try{prepare()}catch(c:CancellationException){
            capture?.close()
            if(c !is TimeoutCancellationException)throw c
            check(capture?.settled()!=false&&current());capture=null
            selected=LiveCaptureInput.original(app)?:error("Original Phone microphone unavailable");headset=false
            description="Headset input not confirmed; original Phone microphone";app.live.inputPending(description);prepare()
        }catch(_:Exception){
            capture?.close();check(capture?.settled()!=false);capture=null;check(current())
            if(preferred==null)throw IllegalStateException("Original Phone input route unavailable")
            selected=LiveCaptureInput.original(app)?:error("Original Phone microphone unavailable");headset=false
            description="Headset input not confirmed; original Phone microphone";app.live.inputPending(description);prepare()
        }
        check(current());app.live.inputPending(if(muted)inputName+" selected · muted" else if(headset)"Headset microphone · OS capture route verified" else description)
        val t=WebRtcLiveTransport(app,dashboard,capture,if(privateId!=null)::decoded else null);native=t
        t.observeActivity{activity?.invoke(it)};t.mute(muted);t.output(output)
        t.connect(history,event)
    }
    override fun send(json:String)=!closed&&native?.send(json)==true // End may still send the owned session.close, never new work
    override fun mute(muted:Boolean){this.muted=muted;capture?.mute(muted);native?.mute(muted)}
    override fun output(enabled:Boolean){output=enabled;native?.output(enabled);if(!enabled){pcm?.close();pcm=null}}
    override fun observeActivity(listener:(Boolean)->Unit){activity=listener}
    override fun observeMicrophone(listener:(Boolean)->Unit){microphone=listener}
    override suspend fun counters():LiveMediaCounters?=native?.counters()
    override fun outputReady()=output&&current()&&(privateId==null||policySelected())
    override fun silence(){output=false;capture?.close();if(capture?.settled()==false)app.live.unsettledPhoneInput(call);native?.silence();pcm?.close();pcm=null}
    override fun close(){if(closed)return;closed=true;silence();native?.close();native=null;microphone=null;activity=null}
}
