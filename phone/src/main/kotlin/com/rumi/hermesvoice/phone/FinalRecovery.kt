package com.rumi.hermesvoice.phone

import com.rumi.hermesvoice.core.net.SilentControl
import com.rumi.hermesvoice.core.HermesAuthRequiredException
import com.rumi.hermesvoice.core.SessionNotOwnedException
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.net.HistoryMessage
import com.rumi.hermesvoice.core.notify.ReplyAlert
import com.rumi.hermesvoice.core.notify.ReplyPreview
import com.rumi.hermesvoice.core.sessions.AppConversation
import com.rumi.hermesvoice.core.sessions.ConversationListing
import com.rumi.hermesvoice.core.watchlink.ReaderResponse
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/** Read-only, application-owned recovery. It never calls submit, playback, acceptance or Retry. */
class FinalRecovery(private val app:PhoneApp) {
    /** Only the confirmed NEW-session creation path calls this, before any prompt. A failed
     * first history lookup must not turn its later answer into silent historic bootstrap. */
    suspend fun created(id:String,ns:String?) = withContext(Dispatchers.IO) {
        if(ns==null || app.bodyScope()!=ns) return@withContext
        try {
            if(app.received.scoped(ns) { if(cursor(id)==null) checkpoint(id,0) else true }!=true && app.bodyScope()==ns)
                notice.value="New conversation arrival tracking not saved. Check storage and refresh."
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(_:Exception) { if(app.bodyScope()==ns) notice.value="New conversation arrival tracking not saved. Check storage and refresh." }
    }
    /** An authoritative newest page can establish intake before a later reply lookup fails. */
    suspend fun verified(id:String,after:Double,ns:String?) {
        // Eligibility was just checked by the repository. An already durable receive frontier
        // needs no IO hop/commit monitor on every interactive older-page read.
        if(ns==null || app.bodyScope()!=ns || app.received.trackingEstablished(id,ns)) return
        withContext(Dispatchers.IO) {
        if(ns==null || app.bodyScope()!=ns) return@withContext
        try {
            if(app.received.scoped(ns) { beginTracking(id,after) }!=true && app.bodyScope()==ns) notice.value="Receive tracking not saved. Check storage and refresh."
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(_:Exception) { if(app.bodyScope()==ns) notice.value="Receive tracking not saved. Check storage and refresh." }
        }
    }
    private val observations = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** Same intake as [observedNewest], started in the background: the interactive page publish never waits for the receive store.
     * Observations of one conversation run in order, and a foreground read of it waits for them ([displayed]). */
    fun observeNewestAsync(page:com.rumi.hermesvoice.core.net.HistoryPage,ns:String?) {
        val id=page.sessionId
        val job=synchronized(observations) {
            val previous=observations[id]
            app.appScope.launch(Dispatchers.IO) { previous?.join();observedNewest(page,ns) }.also { observations[id]=it }
        }
        job.invokeOnCompletion { observations.remove(id,job) }
    }
    /** An authoritative newest page can establish intake before a later reply lookup fails. */
    suspend fun observedNewest(page:com.rumi.hermesvoice.core.net.HistoryPage,ns:String?) = withContext(Dispatchers.IO) {
        // The receive store serializes this short data transaction. Do not wait behind
        // a recovery scan's suspended network/auth request for another account/session.
        run {
            if(ns==null || app.bodyScope()!=ns || page.sessionId in revoked) return@run
            try {
                check(app.received.scoped(ns) {
                if(page.sessionId in revoked) return@scoped true
                val initial=cursor(page.sessionId)==null
                val route=app.existingWiring()?.core?.orchestrator?.playbackRoute?.current()
                val node=(route?.sink as? com.rumi.hermesvoice.core.watchlink.WatchPlaybackSink)?.nodeId
                check(ingest(page.sessionId,page.messages,initial,route?.device==VoiceOrigin.WATCH && app.headset.output()==null,node))
                if(initial) check(checkpoint(page.sessionId,page.rawIds.maxOrNull()?:0L))
                true
                }==true)
                revision.value++
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { if(app.bodyScope()==ns) notice.value="Receive state not saved. Check storage and refresh." }
        }
    }
    /** The gateway supplies an address, not a second alert identity and never request attribution
     * inferred from neighbouring history. Hydration still requires authoritative stop + row address. */
    suspend fun capture(reply:com.rumi.hermesvoice.core.notify.FinalReply):Boolean {
        val ns=reply.namespace?:return false
        val address=reply.canonicalRowId?:return false
        fun sourceCurrent()=reply.sourceGeneration==null || app.replyAlerts.currentGeneration?.invoke()==reply.sourceGeneration
        if(!sourceCurrent()) return false
        val wiring=app.existingWiring()?:return false
        val origin=app.asyncActionOrigin(wiring)
        if(reply.cancelled && app.bodyScope()==ns) cancelledRows.add("$ns|${reply.storedSessionId}|$address")
        val saved=mutex.withLock {
            if(!sourceCurrent() || app.bodyScope()!=ns || !app.asyncActionCurrent(origin) || reply.storedSessionId in revoked) return@withLock false
            if(wiring.core.sessions.registry.find(reply.storedSessionId)==null) return@withLock false
            val node=if(reply.target==VoiceOrigin.WATCH && !reply.quiet) (reply.sink as? com.rumi.hermesvoice.core.watchlink.WatchPlaybackSink)?.nodeId else null
            if(reply.cancelled) {
                val row=app.received.scoped(ns) { canonicalRow(reply.storedSessionId,address) }
                val saved=app.received.scoped(ns) { suppress(reply.storedSessionId,address) }==true
                row?.let { app.getSystemService(android.app.NotificationManager::class.java).cancel(it.getString("identity"),ReplyAlertNotifier.NOTIFICATION_ID) }
                val uid=row?.optString("uid").orEmpty()
                val cancel=JSONObject().put("identity",com.rumi.hermesvoice.core.sessions.ReceivedConversations.identity(ns,reply.storedSessionId,address,uid))
                    .put("session_id",reply.storedSessionId).put("row_id",address).put("uid",uid).put("final",true).put("cancelled",true)
                for(target in setOfNotNull(node,row?.optString("node")?.takeIf { it.isNotBlank() })) send(target,JSONObject().put("v",1).put("scope",ns).put("finals",JSONArray().put(cancel)))
                if(!saved) notice.value="Cancelled reply state not saved. Check storage and refresh."
                revision.value++;saved
            } else app.received.scoped(ns) { expect(reply.storedSessionId,address,node,reply.quiet) }==true
        }
        if(saved && !reply.cancelled) requestReconcile(ns)
        return saved
    }
    val revision=MutableStateFlow(0L)
    val notice=MutableStateFlow<String?>(null)
    private val mutex=Mutex()
    private val revoked=ConcurrentHashMap.newKeySet<String>()
    private val removalVersions=ConcurrentHashMap<String,Long>()
    private val cancelledRows=ConcurrentHashMap.newKeySet<String>()
    private var loop:Job?=null
    private var requestedRecovery:Job?=null
    @Volatile private var generation=0L
    @Volatile private var watchNode:String?=null
    private val notifier=ReplyAlertNotifier(app)
    fun start() {
        if(loop?.isActive==true) return
        loop=app.appScope.launch {
            while(isActive) {
                if(app.activityVisible || app.relayForeground.value || PhoneWakeService.foregroundActive) requestReconcile()
                delay(5_000)
            }
        }
    }
    fun invalidate() {
        generation++
        requestedRecovery?.cancel()
        revoked.clear()
        removalVersions.clear()
        cancelledRows.clear()
        app.received.knownIds().forEach { notifier.dismissConversation(it) }
        app.received.invalidate()
        watchNode=null
        revision.value++
    }
    fun remove(ids:Set<String>) {
        if(ids.isEmpty()) return
        val ns=app.bodyScope()?:return
        revoked.addAll(ids)
        ids.forEach { id -> removalVersions.merge(id,1L) { a,b -> a+b } }
        ids.forEach { app.recentBodies.evict(it);notifier.dismissConversation(it) }
        app.appScope.launch(Dispatchers.IO) {
            if(app.received.scoped(ns) { remove(ids) }!=true && app.bodyScope()==ns) notice.value="Removed conversation data could not be saved. Check device storage."
            revision.value++
        }
    }
    fun decorate(rows:List<AppConversation>):List<AppConversation> = runCatching {
        rows.map { it.copy(unread=app.received.unread(it.stored.id),receivedAt=app.received.receivedAt(it.stored.id)) }
            .sortedWith(compareByDescending<AppConversation> { it.receivedAt }.thenByDescending { it.stored.id })
    }.getOrElse { notice.value="Saved receive state is unavailable. Check storage and refresh.";rows }

    /** Coalesced application-owned recovery is never an interactive reader's network barrier. */
    @Synchronized fun requestReconcile(expectedScope:String?=app.bodyScope()) {
        if(expectedScope==null || requestedRecovery?.isActive==true) return
        requestedRecovery=app.appScope.launch { reconcile(expectedScope=expectedScope) }
    }

    suspend fun reconcile(known:ConversationListing?=null, expectedScope:String?=app.bodyScope()) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val wiring=app.existingWiring()?:return@withLock
            val ns=expectedScope?:return@withLock
            val epoch=generation
            val origin=app.asyncActionOrigin(wiring)
            fun current()=epoch==generation && app.asyncActionCurrent(origin) && app.bodyScope()==ns
            if(!current()) return@withLock
            try {
                val listing=known?:wiring.core.sessions.listConversations(false,adoptMarked=false)
                if(!current()) return@withLock
                var reconciliationFailed=false
                remove(listing.removedIds)
                val cohort=(listing.conversations.map { it.stored.id } + wiring.core.sessions.registry.all().filter { it.role==com.rumi.hermesvoice.core.sessions.OwnedRole.CONVERSATION }.map { it.storedSessionId })
                    .distinct().filter { it !in listing.removedIds }.sorted()
                val start=if(cohort.isEmpty()) 0 else app.received.cohortCursor()%cohort.size
                val batch=if(cohort.isEmpty()) emptyList() else (cohort.drop(start)+cohort.take(start)).take(100)
                for(id in batch) {
                    if(!current()) return@withLock
                    val removalVersion=removalVersions[id]?:0L
                    try {
                        val cursor=app.received.scoped(ns) { cursor(id) }
                        var offset=0;var head=cursor?:0L;var previous:Set<Long>?=null
                        while(current()) {
                            val page=wiring.core.sessions.reconciliationHistory(id,limit=50,offset=offset)
                            if(!current() || (removalVersions[id]?:0L)!=removalVersion) break
                            revoked.remove(id);app.recentBodies.eligible(id)
                            check(page.sessionId==id && page.returned in 0..50 && page.rawIds.size==page.returned) { "Invalid history page" }
                            val ids=page.rawIds.toSet()
                            check(page.returned<50 || ids!=previous) { "History did not advance" }
                            head=maxOf(head,page.rawIds.maxOrNull()?:0L)
                            val route=wiring.core.orchestrator.playbackRoute.current()
                            check(app.received.scoped(ns) { ingest(id,page.messages,cursor==null,route?.device==VoiceOrigin.WATCH && app.headset.output()==null,
                                (route?.sink as? com.rumi.hermesvoice.core.watchlink.WatchPlaybackSink)?.nodeId) }==true)
                            val unresolved=app.received.scoped(ns) { expected(id) }?:emptySet()
                            val olderExpected=unresolved.any { expected -> expected<(page.rawIds.minOrNull()?:Long.MAX_VALUE) }
                            if(page.returned<50 || (cursor!=null && page.rawIds.any { it<=cursor } && !olderExpected && app.received.receivedAt(id)>0)) {
                                check(app.received.scoped(ns) { checkpoint(id,head) }==true);break
                            }
                            previous=ids;offset+=page.returned
                            yield()
                        }
                    } catch(cancelled:CancellationException) { throw cancelled }
                    catch(_:SessionNotOwnedException) { if(current()) remove(setOf(id)) }
                    catch(auth:HermesAuthRequiredException) { throw auth }
                    catch(_:Exception) { reconciliationFailed=true }
                }
                if(!current()) return@withLock
                drain(ns,::current)
                if(current() && cohort.isNotEmpty()) check(app.received.scoped(ns) { checkpointCohort((start+batch.size)%cohort.size) }==true)
                if(current() && reconciliationFailed) notice.value="Some replies could not be reconciled. Check connection and storage, then refresh. Nothing is resent."
                revision.value++
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:HermesAuthRequiredException) {
                if(epoch==generation) { invalidate();app.recentBodies.invalidate();notice.value="Sign in to recover replies." }
            } catch(_:Exception) { if(current()) notice.value="Reply recovery pending. Check connection and device storage, then refresh." }
        }
    }
    private suspend fun drain(ns:String,current:()->Boolean) {
        var denied=false
        for(row in app.received.pending()) {
            val id=row.getString("session_id")
            if(!current()) return
            if(id in revoked) continue
            if("$ns|$id|${row.optLong("row_id",-1)}" in cancelledRows) continue
            if(SilentControl.isWholeResponse(row.optString("text"))) { if(!settleControl(ns,row)) denied=true;continue }
            val alert=ReplyAlert(row.getString("identity"),id)
            val currentPrivate=app.headset.output()!=null
            if(currentPrivate && app.received.scoped(ns) { restrictQuiet(alert.identity) }!=true) { denied=true;continue }
            val quiet=row.optBoolean("quiet",true) || currentPrivate
            // A successful transport call is NOT a Watch receipt. Keep that obligation durable.
            if(row.optBoolean("watch") && !row.optBoolean("watch_done") && !row.optBoolean("private_origin") && !currentPrivate) {
                row.optString("node").takeIf { it.isNotBlank() }?.let { node -> send(node,JSONObject().put("v",1).put("scope",ns).put("finals",JSONArray().put(wire(row)))) }
            }
            if(!current()) return
            if(notifier.show(alert,ReplyPreview.of(row.optString("text")),quiet)) {
                if(current() && "$ns|$id|${row.optLong("row_id",-1)}" !in cancelledRows) {
                    if(app.received.scoped(ns) { acknowledge(alert.identity,false) }!=true) denied=true
                } else app.getSystemService(android.app.NotificationManager::class.java).cancel(alert.identity,ReplyAlertNotifier.NOTIFICATION_ID)
            } else denied=true
        }
        // Retry Watch separately even after the fallback Phone post succeeded.
        if(current() && app.headset.output()==null) {
            val groups=app.received.pending(true).filter { row -> !SilentControl.isWholeResponse(row.optString("text")).also { silent -> if(silent) settleControl(ns,row) } }.filter { !it.optBoolean("private_origin") && it.getString("session_id") !in revoked && it.optString("node").isNotBlank() }.groupBy { it.getString("node") }
            for((node,pending) in groups) for(batch in pending.chunked(100)) {
                if(!current()) return
                send(node,JSONObject().put("v",1).put("scope",ns).put("finals",JSONArray().apply { batch.forEach { put(wire(it)) } }))
            }
        }
        notice.value=when {
            denied -> "Reply alerts pending. Allow notifications, check the New replies channel and device storage, then refresh."
            app.received.pending(true).isNotEmpty() -> "Watch reply alerts pending. Reconnect the Watch and allow its notifications."
            else -> null
        }
    }
    /** A control saved by an earlier build owes no alert on either device and is not unread activity. */
    private fun settleControl(ns:String,row:JSONObject):Boolean {
        val identity=row.getString("identity")
        return app.received.scoped(ns) { acknowledge(identity,false) && acknowledge(identity,true) && acknowledge(identity,false,read=true) }==true
    }
    private fun wire(row:JSONObject)=JSONObject(row.toString()).apply { remove("text");remove("phone");remove("read");remove("watch_done") }
    private suspend fun send(node:String,json:JSONObject,path:String=FINALS) {
        try { withTimeout(5_000) { Wearable.getMessageClient(app).sendMessage(node,path,json.toString().toByteArray()).await() } }
        catch(cancelled:CancellationException) { if(cancelled !is TimeoutCancellationException) throw cancelled }
        catch(_:Exception) { /* pending authority is unchanged */ }
    }
    /** Called only after the existing node/request/account checks of a reader response. */
    fun reader(node:String,response:ReaderResponse):ReaderResponse {
        val ns=response.bodyScope?:app.bodyScope()?.takeIf { response.scope==app.sessionListScope() }?:return response
        val enriched=app.received.scoped(ns) {
            val rows=response.sessions.map { it.copy(lastActive=receivedAt(it.id),unread=unread(it.id)) }
                .sortedWith(compareByDescending<com.rumi.hermesvoice.core.watchlink.ReaderSessionRow> { it.lastActive }.thenByDescending { it.id })
            response.copy(bodyScope=ns,sessions=rows,finals=pending(true).filter { it.getString("session_id") !in revoked && it.optString("node")==node }.take(100).map(::wire))
        }?:return ReaderResponse(response.reqId,response.kind,ok=false,error=com.rumi.hermesvoice.core.watchlink.ReaderError.UNAVAILABLE,
            sessionId=response.sessionId,scope=app.sessionListScope())
        watchNode=node
        return enriched
    }
    fun onReceipt(node:String,bytes:ByteArray) {
        if(bytes.size>60_000) return
        val json=runCatching { JSONObject(String(bytes,Charsets.UTF_8)) }.getOrNull()?:return
        val ns=app.bodyScope()?:return
        if(json.optInt("v")!=1 || json.optString("scope")!=ns) return
        app.appScope.launch(Dispatchers.IO) {
            if(app.bodyScope()!=ns) return@launch
            val ids=json.optJSONArray("posted")?:JSONArray()
            val postedAck=JSONArray();val readAck=JSONArray()
            for(i in 0 until minOf(ids.length(),100)) if(app.received.scoped(ns) { watchReceiptAllowed(ids.optString(i),node) && acknowledge(ids.optString(i),true) }==true) postedAck.put(ids.optString(i))
            val read=json.optJSONArray("read")?:JSONArray()
            if(node==watchNode) for(i in 0 until minOf(read.length(),100)) if(app.received.scoped(ns) { acknowledge(read.optString(i),false,true) }==true) readAck.put(read.optString(i))
            if(app.bodyScope()==ns && (postedAck.length()>0 || readAck.length()>0)) send(node,JSONObject().put("v",1).put("scope",ns).put("posted",postedAck).put("read",readAck),RECEIPT_ACK)
            revision.value++
        }
    }
    fun displayed(id:String,messages:List<HistoryMessage>) {
        if(!app.presence.isVisible(id)) return
        val ns=app.bodyScope()?:return
        app.appScope.launch(Dispatchers.IO) {
            observations[id]?.join()
            if(app.bodyScope()==ns && app.presence.isVisible(id) && app.received.scoped(ns) { displayed(id,messages) }==true) revision.value++
        }
    }
    companion object {
        const val FINALS="/hv/v1/finals/intake"
        const val RECEIPT="/hv/v1/finals/receipt"
        const val RECEIPT_ACK="/hv/v1/finals/receipt_ack"
    }
}
