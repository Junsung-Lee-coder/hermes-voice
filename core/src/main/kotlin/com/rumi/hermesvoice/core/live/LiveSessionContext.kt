package com.rumi.hermesvoice.core.live

import com.rumi.hermesvoice.core.net.HistoryPage
import com.rumi.hermesvoice.core.net.HistoryMessage
import java.security.MessageDigest

data class LiveSessionUpdate(val commentary: Boolean, val content: String, val rowId: Long = -1)

/** Display-only projection. It never advances a read cursor, settles an alert or confirms playback. */
class LiveSessionContext(private val sessionId: String, private val knownRetries: Set<Long> = emptySet()) {
    init { require(knownRetries.size<=256) }
    private val previous = LinkedHashMap<Long,Pair<String?,Boolean>>()
    private val seen = LinkedHashMap<Long,String>()
    private val spoken = LinkedHashSet<Long>()
    private var historicThrough = -1L
    private fun eligible(row: HistoryMessage): Boolean = row.text.isNotBlank() && !row.hasToolCalls && !row.silentControl &&
        (row.role == "user" || (row.role == "assistant" && row.received))
    @Synchronized fun page(page: HistoryPage, hydrate: Boolean): List<LiveSessionUpdate> {
        require(page.sessionId == sessionId) { "Live context session mismatch" }
        val updates=ArrayList<LiveSessionUpdate>()
        // Only the INITIAL hydration is bounded; arrivals are handed in, in order, by the caller's lossless raw cursor.
        val eligible=page.messages.filter(::eligible)
        val visible=if(hydrate)eligible.takeLast(20).map{it.rowId}.toSet() else emptySet()
        if(hydrate)historicThrough=maxOf(historicThrough,page.rawIds.maxOrNull()?:0L,page.messages.maxOfOrNull{it.rowId}?:0L)
        for(row in eligible) {
            if(!hydrate && row.rowId<=historicThrough && row.rowId !in seen && row.rowId !in knownRetries)continue
            val fingerprint=MessageDigest.getInstance("SHA-256").digest((row.role+row.text+row.finishReason).toByteArray()).joinToString("") { "%02x".format(it) }
            if(seen[row.rowId]==fingerprint) continue
            previous[row.rowId]=seen[row.rowId] to (row.rowId in spoken)
            while(previous.size>256)previous.remove(previous.keys.first())
            seen[row.rowId]=fingerprint
            val speak=!hydrate && row.final && spoken.add(row.rowId)
            if(hydrate && row.final) spoken.add(row.rowId)
            val excerpt=row.text.takeLast(600)
            if(!hydrate || row.rowId in visible) updates+=LiveSessionUpdate(speak,"Session ${row.role}${if(excerpt.length<row.text.length) " (excerpt)" else ""}: $excerpt",row.rowId)
        }
        while(seen.size>256) { val id=seen.keys.first();seen.remove(id);spoken.remove(id);previous.remove(id) }
        return updates
    }
    /** An update the transport did not take is offered again by the next page (backpressure, not loss). */
    @Synchronized fun forget(rowId: Long) {
        val before=previous.remove(rowId)?:return
        if(before.first==null)seen.remove(rowId) else seen[rowId]=before.first!!
        if(before.second)spoken.add(rowId) else spoken.remove(rowId)
    }
    /** Exact canonical identity only; never match another reply by text or timestamp. */
    @Synchronized fun ownFinal(rowId: Long?): Boolean = rowId == null || spoken.add(rowId)
}
