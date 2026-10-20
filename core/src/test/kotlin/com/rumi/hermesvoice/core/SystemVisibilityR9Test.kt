package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.watchlink.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Actual process-completion producer contract, not the older internal_notification kind.
 * Real REST parser/repository/PhoneReaderService + wire/cache; synthetic dashboard only. */
class SystemVisibilityR9Test {
    private val h = CoreHarness()
    private val notice = "[SYSTEM: Background process proc_synthetic completed (exit code 0)]\nSynthetic tool output"
    @After fun close() = h.close()
    private fun add(id: String, role: String, body: String, kind: String? = null, shown: String? = null): Long {
        val list = h.fake.rows.getValue(id).messages
        synchronized(list) {
            val n = list.size + 1L
            val row = JSONObject().put("id", n).put("role", role).put("content", body).put("timestamp", 1700000000.0+n)
            if (kind != null) row.put("display_kind", kind)
            if (shown != null) row.put("display_content", shown)
            list += row
            return n
        }
    }
    private suspend fun create() = h.core.sessions.createConversation("Synthetic", "synthetic", "").storedSessionId
    private suspend fun reader(id: String) = ReaderResponse.decode(PhoneReaderService(h.core.sessions).handle(
        ReaderRequest("r9-history", ReaderKind.HISTORY, id).encode())!!)!!

    @Test fun E01_realRestRepositoryHidesProcessCompletionButKeepsIdenticalHumanWordsAndFailure() = runBlocking {
        val id = create()
        val before = h.core.sessions.history(id, 50).returned
        val human = add(id,"user",notice)
        add(id,"user",notice,"process_complete")
        val answer = add(id,"assistant",notice)
        val voice = add(id,"user","🎙 genuine voice request\n@file: report.txt")
        val steer = add(id,"user","steer wrapper","steer","actual human steer")
        val failure = add(id,"assistant","The request failed; retry safely.","failed_turn")
        val raw = synchronized(h.fake.rows.getValue(id).messages) { h.fake.rows.getValue(id).messages.map { it.toString() } }
        val page = h.core.sessions.history(id, 50)
        assertEquals(listOf(human,answer,voice,steer,failure),page.messages.map { it.rowId })
        assertEquals(listOf(notice,notice,"🎙 genuine voice request\n@file: report.txt","actual human steer","The request failed; retry safely."),page.messages.map { it.text })
        assertEquals(before+6,page.returned)
        assertEquals((1L..before+6L).toList(),page.rawIds)
        assertEquals(raw,synchronized(h.fake.rows.getValue(id).messages) { h.fake.rows.getValue(id).messages.map { it.toString() } })
        assertTrue(h.fake.restLog.any { it.startsWith("GET /api/sessions/$id/messages?") && it.contains("order=latest") })
        assertTrue(h.fake.prompts.isEmpty())
    }
    @Test fun E02_hiddenOnlyRawPagesAdvanceThroughRealPhoneReaderWireAndCache() = runBlocking {
        val id = create()
        val seed = reader(id).nextOffset
        val a = add(id,"user","older human")
        val b = add(id,"assistant","older answer")
        repeat(60) { add(id,"user",notice,"process_complete") }
        val page = reader(id)
        assertTrue(page.ok)
        assertEquals(listOf(a,b),page.messages.map { it.rowId })
        assertEquals(seed+62,page.nextOffset)
        assertFalse(page.hasOlder)
        val cached = WatchReaderState().select(id,page.reqId).onResponse(ReaderResponse.decode(page.encode())!!)
        assertEquals(listOf("older human","older answer"),cached.selectedHistory!!.messages.map { it.text })
        assertTrue(h.fake.prompts.isEmpty())
    }
    @Test fun C01_unknownKindsAndUserQuotesRemainVisibleWithoutPrefixFiltering() = runBlocking {
        val id = create()
        val words = listOf("[System: quoted by a person]","proc_synthetic summary tool error","시스템 오류를 설명해 주세요")
        val ids = words.map { add(id,"user",it,"newer_kind") }
        assertEquals(ids,h.core.sessions.history(id,50).messages.map { it.rowId })
        assertEquals(words,h.core.sessions.history(id,50).messages.map { it.text })
    }
}
