package com.rumi.hermesvoice.phone.live

import java.io.FileOutputStream
import java.nio.file.StandardCopyOption
import com.rumi.hermesvoice.core.live.LiveTextMessage
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files

/** Small text-only replay cache, never a request/confirmation journal. The trusted Android no-backup directory
 * excludes base, .new and .bak from BOTH cloud backup and device transfer. No audio, bearer, vendor session or work ID.
 * One scope/selection slot: switching it purges the former slot rather than accumulating account material.
 * Reads always hit the bounded backing file; no in-memory hydration cache can stand in for durable reload. */
internal class LiveTextHistory(private val directory: File, private val clock: () -> Long = System::currentTimeMillis) {
    private data class Saved(val message: LiveTextMessage, val at: Long)
    internal class Lease internal constructor(internal val generation: Long, internal val scope: String,
        internal val selection: String, internal val at: Long, val previous: List<LiveTextMessage>, internal val timestamps: List<Long>)
    private var generation = 0L
    private var blocked = false
    private val base get() = File(directory, NAME)
    private fun safe(): Boolean = directory.isDirectory && !Files.isSymbolicLink(directory.toPath()) &&
        listOf("", ".bak", ".new").all { suffix ->
            val f=File(directory, NAME+suffix)
            !Files.isSymbolicLink(f.toPath()) && (!f.exists() || f.isFile) &&
                f.canonicalFile == File(directory.canonicalFile, NAME+suffix)
        }
    private fun replace(source: File, target: File) {
        Files.move(source.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
    }
    private fun readBytes(): ByteArray {
        if(!safe())throw IllegalStateException("private context path")
        val backup=File(directory,NAME+".bak")
        if(backup.exists())replace(backup,base)
        if(!base.exists())return ByteArray(0)
        return base.inputStream().use { input ->
            val out=ByteArrayOutputStream();val buffer=ByteArray(4096)
            while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=MAX_FILE_BYTES);out.write(buffer,0,n)}
            out.toByteArray()
        }
    }
    private fun write(raw: String): Boolean {
        if (!safe()) return false
        val bytes=raw.toByteArray(Charsets.UTF_8)
        if(bytes.size>MAX_FILE_BYTES)return false
        return try {
            // A retained legacy recovery file is promoted within the same private directory first.
            val backup=File(directory,NAME+".bak")
            if(backup.exists())replace(backup,base)
            val staged=File(directory,NAME+".new")
            FileOutputStream(staged).use { out -> out.write(bytes);out.fd.sync() }
            replace(staged,base) // no non-atomic fallback when the filesystem cannot replace safely
            // AtomicFile.finishWrite is void and can merely LOG a failed rename/sync. Never report a
            // durable save from that signal: authenticate actual bounded disk bytes, on Android too.
            readBytes().contentEquals(bytes)
        } catch (_: Exception) { false }
    }
    /** Synchronous, bounded durable overwrite: account invalidation cannot be overtaken by a queued save. */
    @Synchronized fun invalidate(): Boolean {
        generation++
        blocked = !runCatching { write("{}") }.getOrDefault(false)
        return !blocked
    }
    private fun read(scope: String, selection: String, now: Long): List<Saved> {
        if(!base.exists() && !File(directory,NAME+".bak").exists())return emptyList()
        val bytes=readBytes()
        val raw=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val tokener=JSONTokener(raw);val j=tokener.nextValue() as? JSONObject ?: error("context object")
        require(tokener.nextClean()=='\u0000')
        // {} is the durable purged state, not a restored identity.
        if(j.length()==0)return emptyList()
        require(j.keys().asSequence().toSet()==setOf("v","scope","selection","messages") && j.get("v")==1)
        require(j.get("scope")==scope && j.get("selection")==selection)
        val rows=j.getJSONArray("messages");require(rows.length()<=MAX_MESSAGES)
        var total=0
        val messages=(0 until rows.length()).map { i ->
            val row=rows.getJSONObject(i);require(row.keys().asSequence().toSet()==setOf("role","text","at"))
            val role=row.get("role") as? String ?: error("role");require(role=="user"||role=="assistant")
            val text=row.get("text") as? String ?: error("text")
            require(text.isNotBlank() && Charsets.UTF_8.newEncoder().canEncode(text))
            total+=text.toByteArray(Charsets.UTF_8).size;require(total<=MAX_TEXT_BYTES)
            val at=row.get("at");require(at is Long || at is Int)
            val timestamp=(at as Number).toLong();require(timestamp in 0..now)
            Saved(LiveTextMessage(role,text),timestamp)
        }
        return messages.filter { now-it.at<TTL_MS }
    }
    /** Caller supplies its already verified current login generation and selected-conversation context. */
    @Synchronized fun begin(scope: String, selection: String): Lease? {
        generation++
        val now=clock()
        if(blocked && !invalidate())return null
        val old=try { read(scope,selection,now) } catch (_: Exception) {
            if(!invalidate())return null
            emptyList()
        }
        // Also prune expired text physically, before returning any replay.
        val lease=Lease(generation,scope,selection,now,old.map{it.message},old.map{it.at})
        if(!saveRows(lease,old))return null
        return lease
    }
    /** Replace this call's snapshot. NEVER append the cumulative display string as another turn. */
    @Synchronized fun save(lease: Lease, snapshot: List<LiveTextMessage>): Boolean {
        if(blocked || lease.generation!=generation)return false
        val current=snapshot.filter { (it.role=="user"||it.role=="assistant") && it.text.isNotBlank() &&
            Charsets.UTF_8.newEncoder().canEncode(it.text) && it.text.toByteArray(Charsets.UTF_8).size<=MAX_TEXT_BYTES }
        val rows=lease.previous.mapIndexed { i,m -> Saved(m,lease.timestamps[i]) } + current.map { Saved(it,lease.at) }
        return saveRows(lease,rows)
    }
    private fun saveRows(lease: Lease, rows: List<Saved>): Boolean {
        if(blocked || lease.generation!=generation)return false
        val now=clock()
        val kept=rows.filter { it.at<=now && now-it.at<TTL_MS }.takeLast(MAX_MESSAGES).toMutableList()
        while(kept.sumOf{it.message.text.toByteArray(Charsets.UTF_8).size}>MAX_TEXT_BYTES)kept.removeAt(0)
        val j=JSONObject().put("v",1).put("scope",lease.scope).put("selection",lease.selection)
            .put("messages",JSONArray().apply { kept.forEach { put(JSONObject().put("role",it.message.role).put("text",it.message.text).put("at",it.at)) } })
        if(!runCatching { write(j.toString()) }.getOrDefault(false)){blocked=true;generation++;return false}
        return true
    }
    companion object {
        const val NAME="live_text_context_v1.json"
        const val MAX_MESSAGES=24
        const val MAX_TEXT_BYTES=8192
        const val MAX_FILE_BYTES=65536
        const val TTL_MS=86_400_000L
    }
}
