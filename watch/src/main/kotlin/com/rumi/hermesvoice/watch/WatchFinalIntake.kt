package com.rumi.hermesvoice.watch

import com.rumi.hermesvoice.core.notify.ReplyAlert
import com.rumi.hermesvoice.core.watchlink.ReaderMessageRow
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/** Persist -> OS attempt -> persist result -> scoped receipt. Neither fetch nor transport means read.
 * A crash after post but before commit can repeat a stable notification; no exactly-once attention claim.
 */
class WatchFinalIntake(private val app:WatchApp, private val binding:()->Pair<String,String>?,
    private val visible:(String)->Boolean, private val availability:(String?)->Unit,
    private val arrived:(Set<String>)->Unit = {}) {
    private val mutex=Mutex()
    private val notifier=WatchReplyAlertNotifier(app)
    private val epoch=java.util.concurrent.atomic.AtomicLong()
    @Volatile private var blocked=false
    private val revoked=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var hint:JSONObject?=null
    private fun current(pair:Pair<String,String>,version:Long)=!blocked && binding()==pair && epoch.get()==version
    private fun map(root:JSONObject,key:String):JSONObject = root.optJSONObject(key)?:JSONObject().also { root.put(key,it) }
    private fun load(pair:Pair<String,String>):JSONObject {
        hint?.takeIf { it.optString("phone")==pair.first && it.optString("scope")==pair.second }?.let { return JSONObject(it.toString()) }
        val raw=app.localStore.getString(KEY)
        check(raw==null || raw.toByteArray().size<=1_048_576)
        val old=raw?.let { JSONObject(it) }
        val result=if(old?.optString("phone")==pair.first && old.optString("scope")==pair.second) old
            else JSONObject().put("v",1).put("phone",pair.first).put("scope",pair.second).put("rows",JSONObject())
        hint=JSONObject(result.toString());return result
    }
    private fun save(pair:Pair<String,String>,version:Long,root:JSONObject):Boolean {
        if(!current(pair,version) || root.toString().toByteArray().size>1_048_576 || !app.localStore.commitString(KEY,root.toString()) || !current(pair,version)) return false
        hint=JSONObject(root.toString());return true
    }
    fun invalidate() {
        blocked=true;val version=epoch.incrementAndGet();hint=null;revoked.clear()
        // The binding is fenced before any asynchronous old callback can post.
        val root=runCatching { JSONObject(app.localStore.getString(KEY)?:"{}") }.getOrNull()
        root?.optJSONObject("rows")?.let { rows -> for(k in rows.keys()) notifier.dismissConversation(rows.getJSONObject(k).optString("session_id")) }
        app.scope.launch(Dispatchers.IO) { mutex.withLock {
            if(epoch.get()==version && blocked && !app.localStore.commitString(KEY,"{}")) availability("Reply data not cleared. Check Watch storage.")
        } }
    }
    fun receive(node:String,scope:String?,finals:List<JSONObject>) {
        val pair=binding()?:return
        if(pair.first!=node || pair.second!=scope) return
        blocked=false
        val version=epoch.get()
        app.scope.launch(Dispatchers.IO) { mutex.withLock {
            if(!current(pair,version)) return@withLock
            try {
                val root=load(pair);val rows=root.getJSONObject("rows");val fresh=HashSet<String>()
                for(row in finals.take(100)) {
                    val key=row.optString("identity");val id=row.optString("session_id")
                    if(!row.optBoolean("final") || row.optString("uid").length>1024 || !key.matches(Regex("f[a-f0-9]{64}")) || !com.rumi.hermesvoice.core.voice.DestinationAllowlist.isValidSessionId(id) || row.optLong("row_id",-1)<0) continue
                    if(id in revoked || map(root,"removed").has(id) || map(root,"ignored").has(key)) continue
                    if(key!=com.rumi.hermesvoice.core.sessions.ReceivedConversations.identity(pair.second,id,row.optLong("row_id",-1),row.optString("uid"))) continue
                    val address="$id|${row.optLong("row_id",-1)}"
                    if(row.optBoolean("cancelled")) {
                        val cancelled=map(root,"cancelled_rows");check(cancelled.length()<2048 || cancelled.has(address));cancelled.put(address,true)
                        for(k in rows.keys().asSequence().toList()) if(rows.getJSONObject(k).let { it.optString("session_id")==id && it.optLong("row_id",-1)==row.optLong("row_id",-1) }) {
                            app.getSystemService(android.app.NotificationManager::class.java).cancel(k,WatchReplyAlertNotifier.NOTIFICATION_ID)
                            map(root,"ignored").put(k,true);rows.remove(k)
                        }
                        continue
                    }
                    if(map(root,"cancelled_rows").has(address)) continue
                    if(rows.has(key)) continue
                    if(rows.length()>=512) {
                        val spent=rows.keys().asSequence().firstOrNull { rows.getJSONObject(it).let { r -> r.optBoolean("posted")&&r.optBoolean("read")&&r.optBoolean("posted_ack") } }
                        check(spent!=null);rows.remove(spent)
                    }
                    rows.put(key,JSONObject(row.toString()).put("posted",false).put("read",false))
                    fresh+=id
                }
                check(save(pair,version,root))
                if(fresh.isNotEmpty() && current(pair,version)) withContext(Dispatchers.Main.immediate) { if(current(pair,version)) arrived(fresh) }
                drain(pair,version,root)
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { if(current(pair,version)) availability("Reply recovery not saved. Check Watch storage and reconnect.") }
        } }
    }
    fun remove(ids:Set<String>) {
        if(ids.isEmpty()) return
        revoked.addAll(ids);ids.forEach(notifier::dismissConversation)
        val pair=binding()?:return;val version=epoch.get()
        app.scope.launch(Dispatchers.IO) { mutex.withLock {
            if(!current(pair,version)) return@withLock
            try {
                val root=load(pair);val rows=root.getJSONObject("rows");val ignored=map(root,"ignored")
                ids.forEach { map(root,"removed").put(it,true) }
                for(k in rows.keys().asSequence().toList()) if(rows.getJSONObject(k).optString("session_id") in ids) {
                    ignored.put(k,true);rows.remove(k)
                }
                val reads=map(root,"read_rows")
                for(k in reads.keys().asSequence().toList()) if(reads.optString(k) in ids) reads.remove(k)
                while(ignored.length()>2048) ignored.remove(ignored.keys().next())
                check(save(pair,version,root))
            } catch(_:Exception) { if(current(pair,version)) availability("Removed reply state not saved. Check Watch storage.") }
        } }
    }
    fun eligible(ids:Set<String>) {
        ids.forEach { revoked.remove(it) }
        val pair=binding()?:return;val version=epoch.get()
        app.scope.launch(Dispatchers.IO) { mutex.withLock {
            if(!current(pair,version)) return@withLock
            try {
                val root=load(pair);val removed=map(root,"removed");val found=ids.filter { removed.has(it) }
                if(found.isNotEmpty()) { found.forEach { removed.remove(it) };check(save(pair,version,root)) }
            } catch(_:Exception) { if(current(pair,version)) availability("Receive eligibility not saved. Check Watch storage.") }
        } }
    }
    fun recover() {
        if(blocked) return
        val pair=binding()?:return;val version=epoch.get()
        app.scope.launch(Dispatchers.IO) { mutex.withLock {
            try { if(current(pair,version)) drain(pair,version,load(pair)) }
            catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { if(current(pair,version)) availability("Reply recovery pending. Check Watch storage and notifications.") }
        } }
    }
    private suspend fun drain(pair:Pair<String,String>,version:Long,root:JSONObject) {
        val rows=root.getJSONObject("rows");var denied=false
        for(k in rows.keys()) {
            if(!current(pair,version)) return
            val row=rows.getJSONObject(k)
            if(!row.optBoolean("posted")) {
                if(row.getString("session_id") in revoked || map(root,"removed").has(row.getString("session_id"))) continue
                val alert=ReplyAlert(k,row.getString("session_id"))
                if(notifier.show(alert,null,row.optBoolean("quiet",true) || row.optBoolean("private_origin") || app.settings.value.privateAudio)) {
                    if(!current(pair,version) || row.getString("session_id") in revoked) {
                        app.getSystemService(android.app.NotificationManager::class.java).cancel(k,WatchReplyAlertNotifier.NOTIFICATION_ID);return
                    }
                    row.put("posted",true)
                    if(!save(pair,version,root)) { row.put("posted",false);denied=true;break }
                } else denied=true
            }
        }
        if(!current(pair,version)) return
        availability(if(denied) "Reply alerts pending. Allow Watch notifications and the New replies channel; check storage." else null)
        receipt(pair,version,root)
    }
    private suspend fun receipt(pair:Pair<String,String>,version:Long,root:JSONObject) {
        if(!current(pair,version)) return
        val rows=root.getJSONObject("rows");val reads=map(root,"read_rows")
        for(batch in (rows.keys().asSequence().toList()+reads.keys().asSequence().toList()).distinct().chunked(100)) {
            if(!current(pair,version)) return
            val json=JSONObject().put("v",1).put("scope",pair.second)
                .put("posted",JSONArray(batch.filter { rows.optJSONObject(it)?.let { r -> r.optBoolean("posted")&&!r.optBoolean("posted_ack") }==true }))
                .put("read",JSONArray(batch.filter { reads.has(it) || rows.optJSONObject(it)?.let { r -> r.optBoolean("read")&&!r.optBoolean("read_ack") }==true }))
            if(json.getJSONArray("posted").length()==0 && json.getJSONArray("read").length()==0) continue
            try { withTimeout(5_000) { Wearable.getMessageClient(app).sendMessage(pair.first,"/hv/v1/finals/receipt",json.toString().toByteArray()).await() } }
            catch(cancelled:CancellationException) { if(cancelled !is TimeoutCancellationException) throw cancelled }
            catch(_:Exception) { /* Retry on the next permitted connection/execution. */ }
        }
    }
    fun acknowledged(node:String,bytes:ByteArray) {
        if(blocked || bytes.size>60_000) return
        val pair=binding()?:return;val version=epoch.get()
        val json=runCatching { JSONObject(String(bytes,Charsets.UTF_8)) }.getOrNull()?:return
        if(node!=pair.first || json.optInt("v")!=1 || json.optString("scope")!=pair.second) return
        app.scope.launch(Dispatchers.IO) { mutex.withLock {
            if(!current(pair,version)) return@withLock
            try {
                val root=load(pair);val rows=root.getJSONObject("rows");val reads=map(root,"read_rows")
                val posted=json.optJSONArray("posted")?:JSONArray();val read=json.optJSONArray("read")?:JSONArray()
                for(i in 0 until minOf(posted.length(),100)) rows.optJSONObject(posted.optString(i))?.let { if(it.optBoolean("posted")) it.put("posted_ack",true) }
                for(i in 0 until minOf(read.length(),100)) { reads.remove(read.optString(i));rows.optJSONObject(read.optString(i))?.put("read_ack",true) }
                check(save(pair,version,root))
                if(read.length()>0 && current(pair,version)) withContext(Dispatchers.Main.immediate) { if(current(pair,version)) app.loadSessions() }
            } catch(_:Exception) { if(current(pair,version)) availability("Read receipt pending. Check Watch storage and reconnect.") }
        } }
    }
    fun displayed(id:String,messages:List<ReaderMessageRow>) {
        if(blocked || !visible(id) || id in revoked) return
        val pair=binding()?:return;val version=epoch.get()
        app.scope.launch(Dispatchers.IO) { mutex.withLock {
            if(!current(pair,version) || !visible(id)) return@withLock
            try {
                val root=load(pair);val rows=root.getJSONObject("rows");val reads=map(root,"read_rows")
                if(map(root,"removed").has(id)) return@withLock
                for(m in messages.filter { it.role=="assistant" && it.received && it.rowId>=0 && !it.truncated }) {
                    val key=com.rumi.hermesvoice.core.sessions.ReceivedConversations.identity(pair.second,id,m.rowId,m.messageUid)
                    check(reads.length()<4096 || reads.has(key)) { "Reconnect Phone to save more reads" }
                    reads.put(key,id);rows.optJSONObject(key)?.put("read",true)
                }
                if(save(pair,version,root)) receipt(pair,version,root)
                else availability("Read state not saved. Check Watch storage and reconnect.")
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { availability("Read state not saved. Check Watch storage and reconnect.") }
        } }
    }
    companion object { const val KEY="received_finals_v1" }
}
