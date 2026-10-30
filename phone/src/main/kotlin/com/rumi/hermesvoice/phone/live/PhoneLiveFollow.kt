package com.rumi.hermesvoice.phone.live

import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.net.HistoryPage
import com.rumi.hermesvoice.phone.PhoneApp
import kotlinx.coroutines.*

/** Revocable process-owned mic authority. Cold recovery is text/notification only.
 * Canonical history owns bodies: 32 exact outstanding IDs plus a retained overflow RANGE,
 * not an evicting body queue or FinalRecovery's independent durable cursor. */
internal class PhoneLiveFollow(private val app:PhoneApp,private val controller:PhoneLiveVoiceController) {
    private class Lease(val scope:String,val session:String,val origin:VoiceOrigin,val node:String?,val navigation:String?,
        val headset:Boolean,val privateId:Int?,val until:Long,var through:Long) {
        var idle=false;var attempting=false;var blocked=false
        val owed=java.util.TreeSet<Long>();val handed=HashSet<Long>()
        // At most the context window's known nonfinal identities, not bodies or arbitrary historic finals.
        val known=LinkedHashSet<Long>();val updates=java.util.TreeSet<Long>()
        var revision=0L;var attemptedRevision=0L
        var rangeLow:Long?=null;var rangeHigh=0L;var attemptedHigh=0L
        fun due(id:Long)=(id>through || id in known) && id !in handed
        fun high()=maxOf(owed.lastOrNull()?:0L,rangeHigh)
    }
    @Volatile private var lease:Lease?=null
    fun armed()=lease!=null
    fun cursor(scope:String?,session:String)=lease?.takeIf{it.scope==scope&&it.session==session}?.through
    fun retries(scope:String?,session:String):Set<Long> = lease?.takeIf{it.scope==scope&&it.session==session}?.updates?.toSet().orEmpty()
    private fun remember(l:Lease,page:HistoryPage){
        for(row in page.messages)if(row.role=="assistant"&&row.received&&!row.final&&!row.hasToolCalls&&!row.silentControl&&l.known.size<256)l.known+=row.rowId
    }
    fun observedUpdate(row:Long){val l=lease?:return;if(row<=l.through&&row in l.known)owe(l,row)}
    fun bind(scope:String,session:String,origin:VoiceOrigin,node:String?,page:HistoryPage,now:Long) {
        val old=lease
        if(old!=null&&old.scope==scope&&old.session==session&&old.attempting){
            for(f in page.messages)if(f.final&&old.due(f.rowId))owe(old,f.rowId)
            remember(old,page);old.idle=false;old.attempting=false;return
        }
        lease=Lease(scope,session,origin,node,app.selectedConversationId,app.settings.useHeadset,app.headset.output()?.id,
            now+app.settings.laterReplyWindowMillis,maxOf(page.rawIds.maxOrNull()?:0L,page.messages.maxOfOrNull{it.rowId}?:0L)).also{remember(it,page)}
    }
    fun ended(kind:String){
        if(kind!="idle"){lease=null;return}
        val l=lease?:return;l.idle=true
        app.mainScope.launch(Dispatchers.Main){resume(l)}
    }
    fun revoke(){lease=null}
    fun settingsChanged(){lease?.let{if(!app.settings.watchSettings().liveEnabled(it.origin))lease=null}}
    fun page(scope:String,page:HistoryPage,now:Long){
        val l=lease?:return
        if(scope!=l.scope||page.sessionId!=l.session)return
        if(!valid(l,now)){lease=null;return}
        for(f in page.messages)if(f.final&&l.due(f.rowId))owe(l,f.rowId)
        remember(l,page)
        resume(l)
    }
    private fun owe(l:Lease,id:Long){
        if(id<=l.through){if(id in l.known&&l.updates.add(id))l.revision++;return}
        if(id in l.owed)return
        if(l.owed.size<WINDOW)l.owed+=id
        else {l.rangeLow=minOf(l.rangeLow?:id,id);l.rangeHigh=maxOf(l.rangeHigh,id)}
    }
    fun capacity(row:Long,pending:Set<Long>):Boolean {
        val l=lease?:return pending.size<WINDOW
        return row in l.handed || row in pending || l.handed.size+pending.count{it !in l.handed}<WINDOW
    }
    fun alreadyHanded(scope:String?,session:String,row:Long)=lease?.let{it.scope==scope&&it.session==session&&((row<=it.through&&row !in it.updates)||row in it.handed)}==true
    fun handed(scope:String?,session:String?,row:Long){
        val l=lease?:return;if(scope!=l.scope||session!=l.session)return
        l.known.remove(row);l.updates.remove(row)
        if(row<=l.through)return
        l.owed.remove(row);l.handed+=row
    }
    /** Only ordered delivery PLUS exact final append ACK may advance this raw frontier. */
    fun checkpoint(scope:String?,session:String,through:Long){
        val l=lease?:return;if(scope!=l.scope||session!=l.session||through<l.through)return
        l.through=through;l.owed.removeIf{it<=through};l.handed.removeIf{it<=through}
        if(l.rangeHigh<=through){l.rangeLow=null;l.rangeHigh=0}
        else if(l.rangeLow!=null)l.rangeLow=maxOf(l.rangeLow!!,through+1)
    }
    private fun resume(l:Lease){
        if(lease!==l||!l.idle||l.attempting||l.blocked||controller.active)return
        val due=l.high();if((due<=l.through||due<=l.attemptedHigh)&&(l.updates.isEmpty()||l.revision<=l.attemptedRevision))return
        if(!valid(l,controller.clockNow())){lease=null;return}
        l.attempting=true;l.attemptedHigh=due;l.attemptedRevision=l.revision
        app.mainScope.launch {
            try {
                app.wiring().core.sessions.requireDeliverable(l.session)
                if(!authorizes(l)||!l.idle||controller.active)return@launch
                if(!controller.resumeFromIdle(l.session,l.origin,l.node){authorizes(l)}){
                    l.attempting=false;l.blocked=true
                    controller.continuationPending("Late answer saved. Live microphone could not start; allow the originating device microphone/background access and use Talk to continue.")
                }
            }catch(c:CancellationException){throw c}
            catch(_:Exception){if(lease===l){l.attempting=false;l.blocked=true;controller.continuationPending("Late answer saved. Live continuation needs the current conversation, permission and private audio route.")}}
        }
    }
    private fun authorizes(l:Lease)=lease===l&&valid(l,controller.clockNow())
    private fun valid(l:Lease,now:Long)=app.bodyScope()==l.scope&&app.settings.watchSettings().liveEnabled(l.origin)&&
        app.selectedConversationId==l.navigation&&app.settings.useHeadset==l.headset&&app.headset.output()?.id==l.privateId&&now<l.until
    private companion object{const val WINDOW=32}
}
