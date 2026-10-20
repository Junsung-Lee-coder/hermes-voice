package com.rumi.hermesvoice.phone

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

/** Real Phone MainActivity -> ViewModel -> REST repository; no device/server E2E. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1280dp")
class PhoneSystemVisibilityR9RoboTest : PhoneNavRoboBase() {
    private val notice = "[SYSTEM: Background process proc_synthetic completed (exit code 0)]"
    private fun add(id: String, role: String, text: String, kind: String? = null): Long {
        val list = fake.rows.getValue(id).messages
        synchronized(list) {
            val n = list.size+1L
            val row = JSONObject().put("id",n).put("role",role).put("content",text).put("timestamp",1700000000.0+n)
            if (kind != null) row.put("display_kind",kind)
            list += row
            return n
        }
    }
    @Test fun E01_actualPhoneChatHasNoProcessCompletionBubbleOrBlankRowAndKeepsHumanTwin() {
        val id = titled("Synthetic R9", "r9")
        val human = add(id,"user",notice)
        val hidden = add(id,"user",notice,"process_complete")
        val answer = add(id,"assistant","Actual answer about the system")
        val failure = add(id,"assistant","User-facing request failure","failed_turn")
        val model = showWith(id)
        openCard(model,id)
        waitFor("final visible row") { model.state.value.history.any { it.rowId == failure } }
        assertEquals(listOf(human,answer,failure),model.state.value.history.map { it.rowId })
        assertEquals(listOf(notice,"Actual answer about the system","User-facing request failure"),model.state.value.history.map { it.text })
        assertFalse("not even an empty message item",exists("message_$hidden"))
        assertTrue(exists("message_$human"))
        assertTrue(fake.prompts.isEmpty())
    }
    @Test fun E02_processOnlyOlderPageUsesRawOffsetsToReachRealHumanHistory() {
        val id = titled("Synthetic history", "history")
        val base = fake.rows.getValue(id).messages.size.toLong()
        seed(id,30,"early")
        repeat(100) { add(id,"user",notice,"process_complete") }
        seed(id,30,"late")
        val model = showWith(id)
        openCard(model,id)
        waitFor("latest loaded") { model.state.value.history.any { it.rowId == base+160 } }
        assertEquals((base+131..base+160).toList(),model.state.value.history.map { it.rowId })
        assertTrue(scrollUpUntil(60) { model.state.value.history.size > 30 })
        waitFor("past the process-only page") { model.state.value.history.minOf { it.rowId } <= base+30 }
        assertEquals(listOf(50,100),olderOffsets(id))
        assertEquals((base+11..base+30).toList()+(base+131..base+160).toList(),model.state.value.history.map { it.rowId })
        assertTrue(scrollUpUntil(60) { !model.state.value.hasOlder })
        waitFor("start reached") { model.state.value.history.minOf { it.rowId } == base+1 }
        assertEquals(listOf(50,100,150),olderOffsets(id))
        assertEquals((base+1..base+30).toList()+(base+131..base+160).toList(),model.state.value.history.map { it.rowId })
        assertTrue(fake.prompts.isEmpty())
    }
}
