package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.net.HistoryPage
import com.rumi.hermesvoice.core.voice.VoiceTurnPrompt
import com.rumi.hermesvoice.core.watchlink.PhoneReaderService
import com.rumi.hermesvoice.core.watchlink.ReaderKind
import com.rumi.hermesvoice.core.watchlink.ReaderRequest
import com.rumi.hermesvoice.core.watchlink.ReaderResponse
import com.rumi.hermesvoice.core.watchlink.WatchReaderState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Typed synthetic internal rows (background-process completions, delegation deliveries) are kept out of every visible transcript. */
class InternalEventVisibilityTest {
    private val h = CoreHarness()
    @After fun close() = h.close()

    private val completion = "[SYSTEM: Background process proc_synthetic_01 completed (exit code 0)]"
    private val completionKo = "[시스템: 백그라운드 프로세스 proc_synthetic_01 종료됨 (종료 코드 0)]"

    private fun parsed(vararg rows: String, returned: Int = rows.size) = HistoryPage.parse(
        JSONObject("{\"session_id\":\"s\",\"messages\":[" + rows.joinToString(",") + "],\"pagination\":{\"returned\":" + returned + "}}"),
    )

    private fun typed(stored: String, role: String, content: String, kind: String?, displayContent: String? = null) {
        val list = h.fake.rows.getValue(stored).messages
        synchronized(list) {
            val row = JSONObject().put("id", (list.size + 1).toLong()).put("session_id", stored).put("role", role)
                .put("content", content).put("timestamp", 1_700_000_000.0 + list.size)
            if (kind != null) row.put("display_kind", kind)
            if (displayContent != null) row.put("display_content", displayContent)
            list += row
        }
    }

    private fun conversation(): String = runBlocking { h.core.sessions.createConversation("Synthetic", "synthetic", "").storedSessionId }

    private fun ask(id: String, req: String = "internal-event-0001", offset: Int = 0): ReaderResponse = runBlocking {
        ReaderResponse.decode(PhoneReaderService(h.core.sessions).handle(ReaderRequest(req, ReaderKind.HISTORY, id, offset).encode())!!)!!
    }

    @Test fun typedInternalNotificationRowsAreNotShownButKeepTheirRawIdsAndCount() {
        val page = parsed(
            """{"id":11,"role":"user","content":"hello"}""",
            """{"id":12,"role":"user","content":"$completion","display_kind":"internal_notification"}""",
            """{"id":13,"role":"assistant","content":"answer"}""",
        )
        assertEquals(listOf(11L, 13L), page.messages.map { it.rowId })
        assertEquals(listOf(11L, 12L, 13L), page.rawIds)
        assertEquals(3, page.returned)
    }

    @Test fun typedDelegationDeliveryRowsAreNotShownWhateverTheirRole() {
        val page = parsed(
            """{"id":21,"role":"user","content":"delegated work finished","display_kind":"async_delegation_complete"}""",
            """{"id":22,"role":"assistant","content":"delegated work finished","display_kind":"async_delegation_complete"}""",
            """{"id":23,"role":"assistant","content":"the real answer"}""",
        )
        assertEquals(listOf(23L), page.messages.map { it.rowId })
        assertEquals(listOf(21L, 22L, 23L), page.rawIds)
        assertEquals(3, page.returned)
    }

    @Test fun anOrdinaryUserRowWithTheSameWordsStaysVisibleInAnyLanguage() {
        val page = parsed(
            """{"id":31,"role":"user","content":"$completion"}""",
            """{"id":32,"role":"user","content":"$completionKo"}""",
            """{"id":33,"role":"user","content":"$completion","display_kind":null}""",
            """{"id":34,"role":"user","content":"$completion","display_kind":""}""",
        )
        assertEquals(listOf(31L, 32L, 33L, 34L), page.messages.map { it.rowId })
        assertEquals(listOf(completion, completionKo, completion, completion), page.messages.map { it.text })
    }

    @Test fun assistantAnswersAboutABackgroundProcessStayVisible() {
        val page = parsed(
            """{"id":41,"role":"user","content":"$completion","display_kind":"internal_notification"}""",
            """{"id":42,"role":"assistant","content":"The background process proc_synthetic_01 finished with exit code 0."}""",
        )
        assertEquals(listOf(42L), page.messages.map { it.rowId })
        assertEquals("The background process proc_synthetic_01 finished with exit code 0.", page.messages.single().text)
    }

    @Test fun aSteerRowKeepsItsDisplayContent() {
        val page = parsed(
            """{"id":51,"role":"user","content":"raw steer wrapper","display_content":"actual steer words","display_kind":"user_steer"}""",
        )
        assertEquals(listOf("actual steer words"), page.messages.map { it.text })
    }

    @Test fun unknownOrDifferentlyCasedKindsNeverHideHumanInput() {
        val page = parsed(
            """{"id":61,"role":"user","content":"a","display_kind":"a_kind_from_a_newer_server"}""",
            """{"id":62,"role":"user","content":"b","display_kind":"INTERNAL_NOTIFICATION"}""",
            """{"id":63,"role":"user","content":"c","display_kind":"internal_notification_extra"}""",
            """{"id":64,"role":"user","content":"d"}""",
        )
        assertEquals(listOf(61L, 62L, 63L, 64L), page.messages.map { it.rowId })
    }

    @Test fun multilineTextPartsAndTheVoiceMarkerArePreserved() {
        val voice = VoiceTurnPrompt.compose("play the next song")
        val page = parsed(
            """{"id":71,"role":"user","content":"line one\nline two\n\nline four"}""",
            """{"id":72,"role":"user","content":[{"type":"text","text":"see file"},{"type":"image_url","image_url":{"url":"x"}}]}""",
            """{"id":73,"role":"user","content":${JSONObject.quote(voice)}}""",
        )
        assertEquals(listOf("line one\nline two\n\nline four", "see file", voice), page.messages.map { it.text })
    }

    @Test fun aPageOfOnlyInternalRowsHasNoMessagesAndKeepsEveryRawId() {
        val page = parsed(
            """{"id":81,"role":"user","content":"x","display_kind":"internal_notification"}""",
            """{"id":82,"role":"user","content":"y","display_kind":"internal_notification"}""",
            """{"id":83,"role":"user","content":"z","display_kind":"async_delegation_complete"}""",
        )
        assertTrue(page.messages.isEmpty())
        assertEquals(listOf(81L, 82L, 83L), page.rawIds)
        assertEquals(3, page.returned)
    }

    @Test fun theRepositoryHistoryCarriesTheProjectionToItsCallers() = runBlocking {
        val id = conversation()
        val seed = h.core.sessions.history(id, 50, 0).returned
        typed(id, "user", "question", null)
        typed(id, "user", completion, "internal_notification")
        typed(id, "user", completion, null)
        typed(id, "assistant", "answer", null)
        typed(id, "user", "delegated work finished", "async_delegation_complete")
        val page = h.core.sessions.history(id, 50, 0)
        assertEquals(listOf("question", completion, "answer"), page.messages.map { it.text })
        assertEquals(seed + 5, page.returned)
        assertEquals((1L..(seed + 5L)).toList(), page.rawIds)
    }

    @Test fun theWatchReaderProjectionHidesInternalRowsAndKeepsTheRawCursor() = runBlocking {
        val id = conversation()
        val seed = ask(id, "internal-event-seed").nextOffset
        typed(id, "user", "question", null)
        typed(id, "user", completion, "internal_notification")
        typed(id, "user", completion, null)
        typed(id, "assistant", "answer", null)
        typed(id, "user", "steer raw", "user_steer", displayContent = "steer shown")
        typed(id, "user", "delegated work finished", "async_delegation_complete")
        val page = ask(id)
        assertTrue(page.ok)
        assertEquals(listOf("question", completion, "answer", "steer shown"), page.messages.map { it.text })
        assertEquals("the raw cursor counts the hidden rows too", seed + 6, page.nextOffset)
        assertFalse(page.hasOlder)
        val state = WatchReaderState().select(id, page.reqId).onResponse(page)
        assertEquals(page.messages, state.selectedHistory!!.messages)
        assertTrue(h.fake.prompts.isEmpty())
    }

    @Test fun theWatchReaderSkipsAHiddenOnlyRawPageToTheNearestShownRows() = runBlocking {
        val id = conversation()
        typed(id, "user", "older question", null)
        typed(id, "assistant", "older answer", null)
        repeat(60) { typed(id, "user", completion, "internal_notification") }
        val page = ask(id)
        assertTrue(page.ok)
        assertEquals(listOf("older question", "older answer"), page.messages.map { it.text })
        assertTrue("reading never submits a turn", h.fake.prompts.isEmpty())
    }
}
