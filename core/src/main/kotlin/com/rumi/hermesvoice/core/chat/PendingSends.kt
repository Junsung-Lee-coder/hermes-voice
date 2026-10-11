package com.rumi.hermesvoice.core.chat

import com.rumi.hermesvoice.core.net.HistoryMessage

/**
 * A message shown the instant it was admitted, before the server's own row exists. [baselineRowId] is the newest row the
 * conversation had loaded at that moment; only a user row beyond it can ever stand for this send.
 */
data class PendingSend(val send: DispatchedSend, val baselineRowId: Long, val delivered: Boolean = false)

/** The pure bookkeeping behind the immediate sent bubble: one pending per send, replaced one for one by the server's user row. */
object PendingSends {
    const val MAX_PER_SESSION = 12

    private val blanks = Regex("\\s+")

    fun add(list: List<PendingSend>, pending: PendingSend): List<PendingSend> {
        var next = list + pending
        val session = pending.send.sessionId
        while (next.count { it.send.sessionId == session } > MAX_PER_SESSION) {
            val oldestDelivered = next.firstOrNull { it.send.sessionId == session && it.delivered } ?: break
            next = next - oldestDelivered
        }
        return next
    }

    fun markDelivered(list: List<PendingSend>, id: Long): List<PendingSend> =
        list.map { if (it.send.id == id) it.copy(delivered = true) else it }

    fun remove(list: List<PendingSend>, id: Long): List<PendingSend> = list.filterNot { it.send.id == id }

    fun baseline(history: List<HistoryMessage>): Long = history.maxOfOrNull { it.rowId } ?: 0L

    /**
     * Drops the pending sends of [sessionId] that [history] now carries. A user row beyond a send's baseline claims at most one
     * send, in send order; text is matched after whitespace normalisation and without the server's leading attachment references,
     * and a send without text takes one still-unclaimed row that holds only attachment references after the text sends have taken
     * theirs. A row never claims by text alone: rows at or before the baseline, other roles and other conversations are ignored.
     */
    fun reconcile(list: List<PendingSend>, sessionId: String, history: List<HistoryMessage>): List<PendingSend> {
        val rows = history.filter { it.role == "user" }.sortedBy { it.rowId }
        if (rows.isEmpty()) return list
        val claimed = HashSet<Long>()
        val resolved = HashSet<Long>()
        val mine = list.filter { it.send.sessionId == sessionId }
        for (textual in listOf(true, false)) {
            for (pending in mine) {
                val text = normalise(pending.send.text)
                if ((text.isNotEmpty()) != textual) continue
                val row = rows.firstOrNull { row ->
                    row.rowId > pending.baselineRowId && row.rowId !in claimed &&
                        if (textual) normalise(row.text) == text || normalise(withoutReferences(row.text)) == text
                        else row.text.isNotBlank() && normalise(withoutReferences(row.text)).isEmpty()
                } ?: continue
                claimed += row.rowId
                resolved += pending.send.id
            }
        }
        return if (resolved.isEmpty()) list else list.filterNot { it.send.sessionId == sessionId && it.send.id in resolved }
    }

    private fun normalise(text: String) = text.replace(blanks, " ").trim()

    private fun withoutReferences(text: String) =
        text.lineSequence().dropWhile { it.trimStart().startsWith("@file:") }.joinToString("\n")
}
