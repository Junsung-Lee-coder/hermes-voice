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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/**
 * What a recovery notice is about, a fixed category for the screen and the log. Only [actionable] ones need the user and are drawn beside
 * Talk: device storage the receive state could not be written to, and alerts that could not be posted (notifications not allowed, the New
 * replies channel off, or the store refusing the acknowledgement). A reply still owed to the Watch, a connection/server retry of the
 * recovery and a lost sign-in (the sign-in screen asks for that itself) are logged by category only.
 */
enum class RecoveryNoticeKind(val wire:String,val actionable:Boolean) {
    STORAGE("storage",true), NOTIFICATIONS("notifications",true), WATCH_DELIVERY("watch_delivery",false), RECOVERY("recovery",false), SIGN_IN("sign_in",false)
}
data class RecoveryNotice(val kind:RecoveryNoticeKind,val text:String)

/** Read-only, application-owned recovery. It never calls submit, playback, acceptance or Retry. */
class FinalRecovery(private val app:PhoneApp) {
    /** Only the confirmed NEW-session creation path calls this, before any prompt. A failed
     * first history lookup must not turn its later answer into silent historic bootstrap. */
    suspend fun created(id:String,ns:String?) = withContext(Dispatchers.IO) {
        if(ns==null || app.bodyScope()!=ns) return@withContext
        try {
            if(app.received.scoped(ns) { if(cursor(id)==null) checkpoint(id,0) else true }!=true && app.bodyScope()==ns)
                post(RecoveryNoticeKind.STORAGE,"New conversation arrival tracking not saved. Check storage and refresh.")
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(_:Exception) { if(app.bodyScope()==ns) post(RecoveryNoticeKind.STORAGE,"New conversation arrival tracking not saved. Check storage and refresh.") }
    }
    /** An authoritative newest page can establish intake before a later reply lookup fails. */
    suspend fun verified(id:String,after:Double,ns:String?) {
        // Eligibility was just checked by the repository. An already durable receive frontier
        // needs no IO hop/commit monitor on every interactive older-page read.
        if(ns==null || app.bodyScope()!=ns || app.received.trackingEstablished(id,ns)) return
        withContext(Dispatchers.IO) {
        if(ns==null || app.bodyScope()!=ns) return@withContext
        try {
            if(app.received.scoped(ns) { beginTracking(id,after) }!=true && app.bodyScope()==ns) post(RecoveryNoticeKind.STORAGE,"Receive tracking not saved. Check storage and refresh.")
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(_:Exception) { if(app.bodyScope()==ns) post(RecoveryNoticeKind.STORAGE,"Receive tracking not saved. Check storage and refresh.") }
        }
    }
    private val observations = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** Same intake as [observedNewest], started in the background: the interactive page publish never waits for the receive store.
     * Observations of one conversation run in order, and a foreground read of it waits for them ([displayed]). */
    fun observeNewestAsync(page:com.rumi.hermesvoice.core.net.HistoryPage,ns:String?) {
        val id=page.sessionId
        if(ns==null || app.bodyScope()!=ns || id in revoked) return
        // Publish identity-only attention before the matching body without waiting behind
        // an unrelated receive commit. Durable authority remains background IO.
        val projection=app.received.projectVisibility(ns,id,page.suppressed,page.messages)
        val job=synchronized(observations) {
            val previous=observations[id]
            app.appScope.launch(Dispatchers.IO) {
                try { previous?.join();observedNewest(page,ns) }
                finally {
                    // The preview belongs only to this in-flight observation. A refused
                    // commit keeps its storage notice, not an immortal presentation override.
                    app.received.visibilitySettled(projection)
                }
            }.also { observations[id]=it }
        }
        job.invokeOnCompletion { observations.remove(id,job) }
    }
    /** An authoritative newest page can establish intake before a later reply lookup fails. */
    suspend fun observedNewest(page:com.rumi.hermesvoice.core.net.HistoryPage,ns:String?) = withContext(Dispatchers.IO) {
        // The receive store serializes this short data transaction. Do not wait behind
        // a recovery scan's suspended network/auth request for another account/session.
        run {
            if(ns==null || app.bodyScope()!=ns || page.sessionId in revoked) return@run false
            try {
                check(app.received.scoped(ns) {
                if(page.sessionId in revoked) return@scoped true
                val initial=cursor(page.sessionId)==null
                val route=app.existingWiring()?.core?.orchestrator?.playbackRoute?.current()
                val node=(route?.sink as? com.rumi.hermesvoice.core.watchlink.WatchPlaybackSink)?.nodeId
                // Name the page's hidden receipts first: they may be what frees a slot for its genuine rows.
                check(reclassify(page.sessionId,page.suppressed,page.messages))
                check(ingest(page.sessionId,page.messages,initial,route?.device==VoiceOrigin.WATCH && app.headset.output()==null,node))
                if(initial) check(checkpoint(page.sessionId,page.rawIds.maxOrNull()?:0L))
                true
                }==true)
                revision.value++
                true
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { if(app.bodyScope()==ns) post(RecoveryNoticeKind.STORAGE,"Receive state not saved. Check storage and refresh.");false }
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
        // A Watch cancel waits after the custody transaction, never inside it.
        val cancelSends=ArrayList<Pair<String,JSONObject>>()
        val saved=mutex.withLock {
            if(!sourceCurrent() || app.bodyScope()!=ns || !app.asyncActionCurrent(origin) || reply.storedSessionId in revoked) return@withLock false
            if(wiring.core.sessions.registry.find(reply.storedSessionId)==null) return@withLock false
            val node=reply.watchNode ?: if(reply.target==VoiceOrigin.WATCH && !reply.quiet) (reply.sink as? com.rumi.hermesvoice.core.watchlink.WatchPlaybackSink)?.nodeId else null
            if(reply.cancelled) {
                val row=app.received.scoped(ns) { canonicalRow(reply.storedSessionId,address) }
                val saved=app.received.scoped(ns) { suppress(reply.storedSessionId,address) }==true
                row?.let { app.getSystemService(android.app.NotificationManager::class.java).cancel(it.getString("identity"),ReplyAlertNotifier.NOTIFICATION_ID) }
                val uid=row?.optString("uid").orEmpty()
                val cancel=JSONObject().put("identity",com.rumi.hermesvoice.core.sessions.ReceivedConversations.identity(ns,reply.storedSessionId,address,uid))
                    .put("session_id",reply.storedSessionId).put("row_id",address).put("uid",uid).put("final",true).put("cancelled",true)
                for(target in setOfNotNull(node,row?.optString("node")?.takeIf { it.isNotBlank() })) cancelSends+=target to JSONObject().put("v",1).put("scope",ns).put("finals",JSONArray().put(cancel))
                if(!saved) post(RecoveryNoticeKind.STORAGE,"Cancelled reply state not saved. Check storage and refresh.")
                revision.value++;saved
            } else app.received.scoped(ns) { expect(reply.storedSessionId,address,node,reply.quiet,watchReceipt=reply.watchNode!=null) }==true
        }
        for((target,json) in cancelSends) send(target,json)
        // A run already reading may have passed this conversation: one more run follows it.
        if(saved && !reply.cancelled) requestReconcile(ns,followUp=true)
        return saved
    }
    val revision=MutableStateFlow(0L)
    private val noticeText=MutableStateFlow<String?>(null)
    private val typed=MutableStateFlow<RecoveryNotice?>(null)
    /** The current recovery notice's text (null: none). */
    val notice:StateFlow<String?> = noticeText
    /** The same notice with its fixed category: only [RecoveryNoticeKind.actionable] ones are drawn; every new one is logged by category. */
    val notices:StateFlow<RecoveryNotice?> = typed
    /** Every notice goes through here, so its text and category never disagree; the log gets the category only, never the text. */
    @Synchronized private fun post(kind:RecoveryNoticeKind?,text:String?) {
        val next=if(kind==null || text==null) null else RecoveryNotice(kind,text)
        if(next!=null && next!=typed.value) android.util.Log.w(TAG,"recovery notice kind=${next.kind.wire}")
        typed.value=next;noticeText.value=next?.text
    }
    /** Short store/custody transactions only (a capture, one history page's intake, one drain step): never held over a list, history
     * or Watch wait, so an interactive capture never queues behind a recovery run's network IO. */
    private val mutex=Mutex()
    /** Recovery runs take turns here; nothing interactive waits on it. */
    private val runs=Mutex()
    // Guarded by this object: a requested run is going, and the scope a capture asked to follow it with.
    private var recovering=false
    private var followUpScope:String?=null
    private val revoked=ConcurrentHashMap.newKeySet<String>()
    private val removalVersions=ConcurrentHashMap<String,Long>()
    /** Leaf monitor: a removal's publication, a recovery commit's admission with its clearing of both revocations, and [invalidate]'s
     * clearing are each one step under it. Nothing under it waits for the network, [mutex] or a receive-store transaction. */
    private val revocation=Any()
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
        synchronized(revocation) { revoked.clear();removalVersions.clear() }
        cancelledRows.clear()
        app.received.knownIds().forEach { notifier.dismissConversation(it) }
        app.received.invalidate()
        watchNode=null
        revision.value++
    }
    fun remove(ids:Set<String>) {
        if(ids.isEmpty()) return
        val ns=app.bodyScope()?:return
        // A commit admitted before this step may finish; one admitted after it sees the new version and is refused.
        synchronized(revocation) {
            revoked.addAll(ids)
            ids.forEach { id -> removalVersions.merge(id,1L) { a,b -> a+b } }
        }
        ids.forEach { app.recentBodies.evict(it);notifier.dismissConversation(it) }
        app.appScope.launch(Dispatchers.IO) {
            if(app.received.scoped(ns) { remove(ids) }!=true && app.bodyScope()==ns) post(RecoveryNoticeKind.STORAGE,"Removed conversation data could not be saved. Check device storage.")
            revision.value++
        }
    }
    fun decorate(rows:List<AppConversation>):List<AppConversation> = runCatching {
        rows.map { it.copy(unread=app.received.unread(it.stored.id),receivedAt=app.received.receivedAt(it.stored.id)) }
            .sortedWith(compareByDescending<AppConversation> { it.receivedAt }.thenByDescending { it.stored.id })
    }.getOrElse { post(RecoveryNoticeKind.STORAGE,"Saved receive state is unavailable. Check storage and refresh.");rows }

    /** Coalesced application-owned recovery is never an interactive reader's network barrier. A [followUp] request (a final just taken
     * into custody) made while a run is going is not dropped: one more run, for its scope, follows that run. */
    @Synchronized fun requestReconcile(expectedScope:String?=app.bodyScope(), followUp:Boolean=false) {
        if(expectedScope==null) return
        if(recovering) { if(followUp) followUpScope=expectedScope; return }
        recovering=true
        val job=app.appScope.launch(start=CoroutineStart.LAZY) {
            var scope:String=expectedScope
            while(true) {
                reconcile(expectedScope=scope)
                scope=synchronized(this@FinalRecovery) { followUpScope.also { followUpScope=null; if(it==null) recovering=false } } ?: break
            }
        }
        job.invokeOnCompletion { cause -> synchronized(this@FinalRecovery) { if(cause!=null && requestedRecovery===job) { recovering=false;followUpScope=null } } }
        requestedRecovery=job
        job.start()
    }

    /** Runs take turns on [runs]. Their network reads (the list, each history page) wait outside [mutex]; every commit takes [mutex] and
     * checks again, inside it, that the run's login, connection and scope and the conversation's removal state are still the ones read for. */
    suspend fun reconcile(known:ConversationListing?=null, expectedScope:String?=app.bodyScope()) = withContext(Dispatchers.IO) {
        runs.withLock run@{
            val wiring=app.existingWiring()?:return@run
            val ns=expectedScope?:return@run
            val epoch=generation
            val origin=app.asyncActionOrigin(wiring)
            fun current()=epoch==generation && app.asyncActionCurrent(origin) && app.bodyScope()==ns
            if(!current()) return@run
            // A receive-store write this run could not make is a storage failure the user can act on; any other failure is the run's retry.
            var storeFailed=false
            fun stored(ok:Boolean?) { if(ok!=true) { storeFailed=true;error("Receive state not saved") } }
            try {
                val listing=known?:wiring.core.sessions.listConversations(false,adoptMarked=false)
                var reconciliationFailed=false
                val cohort=mutex.withLock {
                    if(!current()) return@run
                    remove(listing.removedIds)
                    (listing.conversations.map { it.stored.id } + wiring.core.sessions.registry.all().filter { it.role==com.rumi.hermesvoice.core.sessions.OwnedRole.CONVERSATION }.map { it.storedSessionId })
                        .distinct().filter { it !in listing.removedIds }.sorted()
                }
                val start=if(cohort.isEmpty()) 0 else app.received.cohortCursor()%cohort.size
                val batch=if(cohort.isEmpty()) emptyList() else (cohort.drop(start)+cohort.take(start)).take(100)
                for(id in batch) {
                    if(!current()) return@run
                    val removalVersion=removalVersions[id]?:0L
                    try {
                        val cursor=app.received.scoped(ns) { cursor(id) }
                        var offset=0;var head=cursor?:0L;var previous:Set<Long>?=null
                        while(current()) {
                            val page=wiring.core.sessions.reconciliationHistory(id,limit=50,offset=offset)
                            // One page's intake is one custody transaction; a change of login, scope or removal since the read wins.
                            val done=mutex.withLock {
                                if(!current()) return@withLock true
                                // Admission and clearing are one step against a removal's publication. The generation is checked in the
                                // same step: invalidate() resets the versions, so an older login's commit never matches a newer removal.
                                val admitted=synchronized(revocation) {
                                    (epoch==generation && (removalVersions[id]?:0L)==removalVersion).also { if(it) { revoked.remove(id);app.recentBodies.eligible(id) } }
                                }
                                if(!admitted) return@withLock true
                                check(page.sessionId==id && page.returned in 0..50 && page.rawIds.size==page.returned) { "Invalid history page" }
                                val ids=page.rawIds.toSet()
                                check(page.returned<50 || ids!=previous) { "History did not advance" }
                                head=maxOf(head,page.rawIds.maxOrNull()?:0L)
                                val route=wiring.core.orchestrator.playbackRoute.current()
                                // Name the page's hidden receipts first: they may be what frees a slot for its genuine rows.
                                stored(app.received.scoped(ns) { reclassify(id,page.suppressed,page.messages) })
                                stored(app.received.scoped(ns) { ingest(id,page.messages,cursor==null,route?.device==VoiceOrigin.WATCH && app.headset.output()==null,
                                    (route?.sink as? com.rumi.hermesvoice.core.watchlink.WatchPlaybackSink)?.nodeId) })
                                app.live.onSessionMessages(ns,page)
                                val unresolved=app.received.scoped(ns) { expected(id) }?:emptySet()
                                val olderExpected=unresolved.any { expected -> expected<(page.rawIds.minOrNull()?:Long.MAX_VALUE) }
                                if(page.returned<50 || (cursor!=null && page.rawIds.any { it<=cursor } && !olderExpected && app.received.receivedAt(id)>0)) {
                                    stored(app.received.scoped(ns) { checkpoint(id,head) });return@withLock true
                                }
                                previous=ids;offset+=page.returned
                                false
                            }
                            if(done) break
                            yield()
                        }
                    } catch(cancelled:CancellationException) { throw cancelled }
                    catch(_:SessionNotOwnedException) { mutex.withLock { if(current()) remove(setOf(id)) } }
                    catch(auth:HermesAuthRequiredException) { throw auth }
                    catch(_:Exception) { reconciliationFailed=true }
                }
                if(!current()) return@run
                drain(ns,::current)
                mutex.withLock { if(current() && cohort.isNotEmpty()) stored(app.received.scoped(ns) { checkpointCohort((start+batch.size)%cohort.size) }) }
                if(current() && storeFailed) post(RecoveryNoticeKind.STORAGE,"Some replies could not be saved. Check device storage, then refresh. Nothing is resent.")
                else if(current() && reconciliationFailed) post(RecoveryNoticeKind.RECOVERY,"Some replies could not be reconciled. Check connection and storage, then refresh. Nothing is resent.")
                revision.value++
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:HermesAuthRequiredException) {
                if(epoch==generation) { invalidate();app.recentBodies.invalidate();post(RecoveryNoticeKind.SIGN_IN,"Sign in to recover replies.") }
            } catch(_:Exception) {
                if(current() && storeFailed) post(RecoveryNoticeKind.STORAGE,"Some replies could not be saved. Check device storage, then refresh. Nothing is resent.")
                else if(current()) post(RecoveryNoticeKind.RECOVERY,"Reply recovery pending. Check connection and device storage, then refresh.")
            }
        }
    }
    private suspend fun drain(ns:String,current:()->Boolean) {
        var denied=false
        for(snapshot in app.received.pending()) {
            // Each row is decided and posted in short custody steps; its Watch send waits between them.
            var watch:Pair<String,JSONObject>?=null
            val first=mutex.withLock {
                if(!current()) return
                val row=effectiveRow(ns,snapshot)
                if(row==null) { denied=true;return@withLock null }
                val id=row.getString("session_id")
                if(id in revoked) return@withLock null
                if("$ns|$id|${row.optLong("row_id",-1)}" in cancelledRows) return@withLock null
                if(SilentControl.isWholeResponse(row.optString("text"))) { if(!settleControl(ns,row)) denied=true;return@withLock null }
                // A successful transport call is NOT a Watch receipt. Keep that obligation durable.
                val node=row.optString("node")
                if(watchPending(ns,row,node)) watch=node to JSONObject().put("v",1).put("scope",ns).put("finals",JSONArray().put(wire(row)))
                row
            } ?: continue
            watch?.let { (node,json) -> send(node,json) }
            mutex.withLock {
                if(!current()) return
                // Watch delivery suspended above: Phone posting is a separate, current policy boundary.
                val row=effectiveRow(ns,first)
                if(row==null) { denied=true;return@withLock }
                val id=row.getString("session_id")
                if(row.optBoolean("phone") || !pendingVisible(ns,row)) return@withLock
                val alert=ReplyAlert(row.getString("identity"),id)
                if(notifier.show(alert,ReplyPreview.of(row.optString("text")),row.optBoolean("quiet",true))) {
                    if(current() && "$ns|$id|${row.optLong("row_id",-1)}" !in cancelledRows) {
                        if(app.received.scoped(ns) { acknowledge(alert.identity,false) }!=true) denied=true
                    } else app.getSystemService(android.app.NotificationManager::class.java).cancel(alert.identity,ReplyAlertNotifier.NOTIFICATION_ID)
                } else denied=true
            }
        }
        // Retry Watch separately even after the fallback Phone post succeeded.
        if(current()) {
            // Keep only candidate identities here; no future batch's privacy is sampled before an await.
            val groups=mutex.withLock { app.received.pending(true).filter { it.optString("node").isNotBlank() }.groupBy { it.getString("node") } }
            for((node,pending) in groups) for(batch in pending.chunked(100)) {
                if(!current()) return
                val rows=mutex.withLock {
                    batch.mapNotNull { effectiveRow(ns,it).also { row -> if(row==null)denied=true } }
                        .filter { row -> !SilentControl.isWholeResponse(row.optString("text")).also { silent -> if(silent) settleControl(ns,row) } }
                        .filter { watchPending(ns,it,node) }
                }
                if(rows.isNotEmpty() && current()) send(node,JSONObject().put("v",1).put("scope",ns).put("finals",JSONArray().apply { rows.forEach { put(wire(it)) }}))
            }
        }
        when {
            denied -> post(RecoveryNoticeKind.NOTIFICATIONS,"Reply alerts pending. Allow notifications, check the New replies channel and device storage, then refresh.")
            app.received.pending(true).isNotEmpty() -> post(RecoveryNoticeKind.WATCH_DELIVERY,"Watch reply alerts pending. Reconnect the Watch and allow its notifications.")
            else -> post(null,null)
        }
    }
    /** A control saved by an earlier build owes no alert on either device and is not unread activity. */
    private fun settleControl(ns:String,row:JSONObject):Boolean {
        val identity=row.getString("identity")
        return app.received.scoped(ns) { acknowledge(identity,false) && acknowledge(identity,true) && acknowledge(identity,false,read=true) }==true
    }
    /** Commit stricter local policy, then serialize that same owned snapshot. Reader already owns a scoped child. */
    private fun effectiveRow(store:com.rumi.hermesvoice.core.sessions.ReceivedConversations,row:JSONObject):JSONObject? {
        if(app.headset.output()!=null && !store.restrictQuiet(row.getString("identity"))) return null
        return store.canonicalRow(row.getString("session_id"),row.getLong("row_id"))?.takeIf { it.optString("identity")==row.optString("identity") }
    }
    private fun effectiveRow(ns:String,row:JSONObject):JSONObject? = app.received.scoped(ns) { effectiveRow(this,row) }
    private fun pendingVisible(ns:String,row:JSONObject):Boolean = row.optBoolean("final") && !row.optBoolean("suppressed") && !row.optBoolean("display_hidden") &&
        row.getString("session_id") !in revoked && "$ns|${row.getString("session_id")}|${row.optLong("row_id",-1)}" !in cancelledRows
    private fun watchPending(ns:String,row:JSONObject,node:String):Boolean = pendingVisible(ns,row) && row.optBoolean("watch") && !row.optBoolean("watch_done") &&
        (row.optBoolean("live_watch") || !row.optBoolean("private_origin")) && node.isNotBlank() && row.optString("node")==node
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
            response.copy(bodyScope=ns,sessions=rows,finals=pending(true).mapNotNull { effectiveRow(this,it) }.filter { watchPending(ns,it,node) }.take(100).map(::wire))
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
            for(i in 0 until minOf(read.length(),100)) if(app.received.scoped(ns) { (node==watchNode || watchReceiptAllowed(read.optString(i),node)) && acknowledge(read.optString(i),false,true) }==true) readAck.put(read.optString(i))
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
        private const val TAG="HermesVoice"
        const val FINALS="/hv/v1/finals/intake"
        const val RECEIPT="/hv/v1/finals/receipt"
        const val RECEIPT_ACK="/hv/v1/finals/receipt_ack"
    }
}
