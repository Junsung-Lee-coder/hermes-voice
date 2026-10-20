package com.rumi.hermesvoice.core.sessions

import com.rumi.hermesvoice.core.KeyValueStore
import com.rumi.hermesvoice.core.net.HistoryMessage
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Independent receive/read and alert authority. Body-cache eviction cannot spend any of this state.
 * One committed JSON value includes intake, pending alerts and cursor. Failed writes never publish
 * a newer in-memory checkpoint. Calls run on IO; no network or OS operation holds this monitor.
 */
class ReceivedConversations(private val store: KeyValueStore, private val namespace: () -> String?) {
    private val published=java.util.concurrent.atomic.AtomicReference<JSONObject?>()
    private var hint:JSONObject?
        get()=published.get()
        set(value) { published.set(value) }
    // Published snapshots are immutable. UI reads must not wait on an IO commit monitor.
    private fun view(ns:String)=hint?.takeIf { it.optString("scope")==ns }?:root(ns)
    /** Freeze the caller's namespace for this serialized data-only transaction, including commit.
     * The child shares only immutable snapshots; it cannot follow a later login into another root. */
    @Synchronized fun <T> scoped(ns:String,block:ReceivedConversations.()->T):T? {
        val provider=namespace
        if(provider()!=ns) return null
        val child=ReceivedConversations(store) { provider()?.takeIf { it==ns } }
        child.hint=hint
        return try { val result=child.block();if(provider()==ns) result else null }
            finally { hint=if(provider()==ns) child.hint else null }
    }
    private fun root(ns: String): JSONObject {
        val previous=hint
        previous?.takeIf { it.optString("scope")==ns }?.let { return JSONObject(it.toString()) }
        val raw=store.getString(KEY)
        if(raw!=null && raw.toByteArray().size>MAX_BYTES) error("Receive record too large")
        val old=raw?.let { JSONObject(it) }
        val result=if(old?.optString("scope")==ns) old else JSONObject().put("v",1).put("scope",ns).put("sessions",JSONObject())
        published.compareAndSet(previous,JSONObject(result.toString()))
        return result
    }
    private fun session(root: JSONObject,id:String):JSONObject {
        val sessions=root.getJSONObject("sessions")
        if(!sessions.has(id)) {
            check(sessions.length()<1000) { "Receive storage full" }
            sessions.put(id,JSONObject().put("cursor",-1).put("received",0.0).put("rows",JSONObject()).put("expected",JSONArray()))
        }
        return sessions.getJSONObject(id)
    }
    private fun commit(ns:String,r:JSONObject):Boolean {
        if(namespace()!=ns) return false
        val bytes=r.toString();check(bytes.toByteArray().size<=MAX_BYTES) { "Receive storage full" }
        if(hint?.let { it.optString("scope")==ns && it.toString()==bytes }==true) return true
        if(!store.commitString(KEY,bytes)) return false
        if(namespace()!=ns) { hint=null;return false }
        hint=r;return true
    }
    /** Server cutoff for an initially unavailable body, never receive ordering or a read receipt. */
    fun trackingEstablished(id:String,ns:String):Boolean {
        if(namespace()!=ns) return false
        val snapshot=hint?.takeIf { it.optString("scope")==ns }?:return false
        val prior=snapshot.optJSONObject("sessions")?.optJSONObject(id)?:return false
        return prior.optLong("cursor",-1)>=0 || prior.has("tracking_after")
    }
    @Synchronized fun beginTracking(id:String,serverLastActive:Double):Boolean {
        val ns=namespace()?:return false
        if(!serverLastActive.isFinite() || serverLastActive<=0) return true
        val prior=view(ns).getJSONObject("sessions").optJSONObject(id)
        if(prior!=null && (prior.optLong("cursor",-1)>=0 || prior.has("tracking_after"))) return true
        val r=root(ns);session(r,id).put("tracking_after",serverLastActive);return commit(ns,r)
    }
    @Synchronized fun cursor(id:String):Long? {
        val ns=namespace()?:return null
        return root(ns).getJSONObject("sessions").optJSONObject(id)?.optLong("cursor")?.takeIf { it>=0 }
    }
    fun knownIds():List<String> {
        val raw=hint?:store.getString(KEY)?.let { runCatching { JSONObject(it) }.getOrNull() }?:return emptyList()
        return raw.optJSONObject("sessions")?.keys()?.asSequence()?.toList().orEmpty()
    }
    fun receivedAt(id:String):Double {
        val ns=namespace()?:return 0.0
        return view(ns).getJSONObject("sessions").optJSONObject(id)?.optDouble("received",0.0)?:0.0
    }
    fun unread(id:String):Boolean {
        val ns=namespace()?:return false
        val rows=view(ns).getJSONObject("sessions").optJSONObject(id)?.optJSONObject("rows")?:return false
        return rows.keys().asSequence().any { rows.getJSONObject(it).let { row -> !row.optBoolean("read") && !row.optBoolean("baseline") } }
    }
    @Synchronized fun expect(id:String,rowId:Long,watchNode:String?=null,quiet:Boolean?=null):Boolean {
        val ns=namespace()?:return false;val r=root(ns);val s=session(r,id)
        val targets=s.optJSONObject("expected_nodes")?:JSONObject().also { s.put("expected_nodes",it) }
        val policies=s.optJSONObject("origin_quiet")?:JSONObject().also { s.put("origin_quiet",it) }
        if(quiet!=null) policies.put(rowId.toString(),policies.optBoolean(rowId.toString()) || quiet)
        val privateOrigin=policies.optBoolean(rowId.toString())
        targets.put(rowId.toString(),if(privateOrigin) "" else watchNode.orEmpty())
        // A canonical policy can arrive after history. It may tighten but never relax a row.
        for(k in s.getJSONObject("rows").keys()) {
            val row=s.getJSONObject("rows").getJSONObject(k)
            if(row.optLong("row_id",-1)==rowId && privateOrigin)
                row.put("quiet",true).put("private_origin",true).put("watch",false).put("watch_done",true).put("node","")
        }
        val old=s.getJSONArray("expected");val ids=(0 until old.length()).map { old.getLong(it) }
        if(rowId !in ids) { check(ids.size<256);old.put(rowId) }
        return commit(ns,r)
    }
    @Synchronized fun expected(id:String):Set<Long> {
        val ns=namespace()?:return emptySet();val a=root(ns).getJSONObject("sessions").optJSONObject(id)?.optJSONArray("expected")?:return emptySet()
        return (0 until a.length()).map { a.getLong(it) }.toSet()
    }
    /** Bootstrap is historical, never a mass arrival. Only an explicit canonical final-event address
     * may create a pending alert inside that baseline. Re-reading old history does not invent activity. */
    @Synchronized fun ingest(id:String,messages:List<HistoryMessage>,baseline:Boolean,watch:Boolean,watchNode:String?=null):Boolean {
        val ns=namespace()?:return false;val r=root(ns);val s=session(r,id);val rows=s.getJSONObject("rows")
        val cursor=s.optLong("cursor",-1);val expected=s.getJSONArray("expected").let { a -> (0 until a.length()).map { a.getLong(it) }.toSet() }
        for(m in messages) {
            if(m.rowId<0 || !m.received) continue
            val key=identity(ns,id,m.rowId,m.messageUid)
            val legacy=rows.keys().asSequence().firstOrNull { k -> rows.getJSONObject(k).let { it.optLong("row_id",-1)==m.rowId && it.optString("uid").isBlank() } }
            val old=rows.optJSONObject(key)?:legacy?.let { rows.remove(it) as JSONObject }.also { if(it!=null) { it.put("identity",key).put("uid",m.messageUid);rows.put(key,it) } }
            val targets=s.optJSONObject("expected_nodes")
            val node=if(targets?.has(m.rowId.toString())==true) targets.optString(m.rowId.toString()) else watchNode.orEmpty()
            val toWatch=if(targets?.has(m.rowId.toString())==true) node.isNotBlank() else watch && node.isNotBlank()
            val final=m.final || m.rowId in expected
            val policies=s.optJSONObject("origin_quiet")
            val policy=if(policies?.has(m.rowId.toString())==true) policies.optBoolean(m.rowId.toString()) else null

            if(m.timestamp.isFinite() && m.timestamp>0) s.put("received",maxOf(s.optDouble("received",0.0),m.timestamp))
            if(old!=null) {
                if(policy==true) old.put("quiet",true).put("private_origin",true).put("watch",false).put("watch_done",true).put("node","")
                val freshFinal = final && ((!old.optBoolean("final") && !old.optBoolean("baseline")) || (old.optBoolean("baseline") && m.rowId in expected))
                if(final) old.put("final",true)
                if(freshFinal) old.put("phone",false).put("watch",toWatch && !old.optBoolean("private_origin")).put("node",if(old.optBoolean("private_origin")) "" else node).put("watch_done",!toWatch || old.optBoolean("private_origin")).put("text",m.text.take(3000)).put("baseline",false)
                continue
            }
            // Never evict unread content or a pending device obligation to make room.
            if(rows.length()>=MAX_ROWS) {
                val disposable=rows.keys().asSequence().firstOrNull { k -> rows.getJSONObject(k).let { (it.optBoolean("read")||it.optBoolean("baseline"))&&it.optBoolean("phone")&&it.optBoolean("watch_done") } }
                check(disposable!=null) { "Read messages to continue recovery" };rows.remove(disposable)
            }
            val after=s.optDouble("tracking_after",0.0)
            val historic=((baseline && (after<=0 || !m.timestamp.isFinite() || m.timestamp<=after)) || m.rowId<=cursor) && m.rowId !in expected
            rows.put(key,JSONObject().put("identity",key).put("session_id",id).put("row_id",m.rowId).put("uid",m.messageUid)
                .put("timestamp",m.timestamp.takeIf { it.isFinite() }?:0.0).put("final",final).put("read",false).put("baseline",historic)
                .put("quiet",policy!=false).put("private_origin",policy==true)
                .put("phone",historic || !final).put("watch",toWatch && !historic && final).put("node",node).put("watch_done",!toWatch || historic || !final)
                .put("text",if(final && !historic) m.text.take(3000) else ""))
        }
        val consumed=messages.filter { it.received && (it.final || it.rowId in expected) }.map { it.rowId }.toSet()
        val cancelled=s.optJSONArray("cancelled")?.let { a -> (0 until a.length()).map { a.getLong(it) }.toSet() }.orEmpty()
        for(k in rows.keys()) if(rows.getJSONObject(k).optLong("row_id",-1) in cancelled) rows.getJSONObject(k).put("suppressed",true).put("phone",true).put("watch_done",true).put("text","")
        s.put("expected",JSONArray(expected.filter { it !in consumed }));consumed.forEach {
            s.optJSONObject("expected_nodes")?.remove(it.toString())
            s.optJSONObject("origin_quiet")?.remove(it.toString())
        }
        return commit(ns,r)
    }
    /** Latch stricter current private-output policy before any external post/relay effect. */
    @Synchronized fun restrictQuiet(identity:String):Boolean {
        val ns=namespace()?:return false;val r=root(ns)
        val sessions=r.getJSONObject("sessions")
        for(id in sessions.keys()) {
            val row=sessions.getJSONObject(id).getJSONObject("rows").optJSONObject(identity)?:continue
            row.put("quiet",true).put("private_origin",true).put("watch",false).put("watch_done",true).put("node","")
            return commit(ns,r)
        }
        return false
    }
    @Synchronized fun canonicalRow(id:String,rowId:Long):JSONObject? {
        val ns=namespace()?:return null;val rows=root(ns).getJSONObject("sessions").optJSONObject(id)?.optJSONObject("rows")?:return null
        return rows.keys().asSequence().map { rows.getJSONObject(it) }.firstOrNull { it.optLong("row_id",-1)==rowId }?.let { JSONObject(it.toString()) }
    }
    @Synchronized fun suppress(id:String,rowId:Long):Boolean {
        val ns=namespace()?:return false;val r=root(ns);val s=session(r,id)
        val old=s.optJSONArray("cancelled")?:JSONArray();val ids=(0 until old.length()).map { old.getLong(it) }
        if(rowId !in ids) { check(ids.size<256);old.put(rowId) };s.put("cancelled",old)
        val rows=s.getJSONObject("rows")
        for(k in rows.keys()) if(rows.getJSONObject(k).optLong("row_id",-1)==rowId) rows.getJSONObject(k).put("suppressed",true).put("phone",true).put("watch_done",true).put("text","")
        return commit(ns,r)
    }
    @Synchronized fun checkpoint(id:String,cursor:Long):Boolean {
        val ns=namespace()?:return false;val r=root(ns);val s=session(r,id);s.put("cursor",maxOf(cursor,s.optLong("cursor",-1)));return commit(ns,r)
    }
    @Synchronized fun cohortCursor():Int = namespace()?.let { root(it).optInt("cohort_cursor",0).coerceAtLeast(0) }?:0
    @Synchronized fun checkpointCohort(next:Int):Boolean {
        val ns=namespace()?:return false;val r=root(ns);r.put("cohort_cursor",next);return commit(ns,r)
    }
    @Synchronized fun pending(watch:Boolean=false):List<JSONObject> {
        val ns=namespace()?:return emptyList();val sessions=root(ns).getJSONObject("sessions")
        return sessions.keys().asSequence().flatMap { id -> val rows=sessions.getJSONObject(id).getJSONObject("rows");rows.keys().asSequence().map { rows.getJSONObject(it) } }
            .filter { it.optBoolean("final") && !(it.optBoolean(if(watch) "watch_done" else "phone")) }.map { JSONObject(it.toString()) }.toList()
    }
    @Synchronized fun watchReceiptAllowed(identity:String,node:String):Boolean {
        val ns=namespace()?:return false;val sessions=root(ns).getJSONObject("sessions")
        return sessions.keys().asSequence().any { id -> sessions.getJSONObject(id).getJSONObject("rows").optJSONObject(identity)?.let { it.optBoolean("watch") && it.optString("node")==node }==true }
    }
    @Synchronized fun acknowledge(identity:String,watch:Boolean,read:Boolean=false):Boolean {
        val ns=namespace()?:return false;val r=root(ns);val sessions=r.getJSONObject("sessions")
        for(id in sessions.keys()) {
            val row=sessions.getJSONObject(id).getJSONObject("rows").optJSONObject(identity)?:continue
            row.put(if(read) "read" else if(watch) "watch_done" else "phone",true)
            if(row.optBoolean("phone")&&row.optBoolean("watch_done")) row.put("text","")
            return commit(ns,r)
        }
        return false
    }
    @Synchronized fun displayed(id:String,visible:List<HistoryMessage>):Boolean {
        val ns=namespace()?:return false;val r=root(ns);val s=r.getJSONObject("sessions").optJSONObject(id)?:return true
        val rows=s.getJSONObject("rows")
        for(m in visible) rows.optJSONObject(identity(ns,id,m.rowId,m.messageUid))?.put("read",true)
        return commit(ns,r)
    }
    @Synchronized fun remove(ids:Set<String>):Boolean {
        val ns=namespace()?:return false;val r=root(ns);ids.forEach { r.getJSONObject("sessions").remove(it) };return commit(ns,r)
    }
    fun invalidate() { hint=null }
    companion object {
        const val KEY="received_conversations_v1"
        const val MAX_ROWS=256
        const val MAX_BYTES=4_194_304
        fun identity(scope:String,session:String,rowId:Long,uid:String=""):String = "f"+MessageDigest.getInstance("SHA-256")
            .digest("$scope|$session|${uid.ifBlank { "row:$rowId" }}".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
