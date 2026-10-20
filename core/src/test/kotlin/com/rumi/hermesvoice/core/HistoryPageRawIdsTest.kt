package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.net.HistoryPage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryPageRawIdsTest {
    private fun page(vararg rows: String, returned: Int = rows.size) = HistoryPage.parse(
        JSONObject("{\"session_id\":\"s\",\"messages\":[" + rows.joinToString(",") + "],\"pagination\":{\"returned\":" + returned + "}}"),
    )

    @Test fun everyReturnedRowKeepsItsIdWhetherItIsShownOrNot() {
        val parsed = page(
            """{"id":11,"role":"user","content":"a"}""",
            """{"id":14,"role":"tool","content":"t"}""",
            """{"id":17,"role":"assistant","content":"b","display_kind":"hidden"}""",
            """{"id":20,"role":"assistant","content":"   "}""",
            """{"id":23,"role":"assistant","content":"c"}""",
        )
        assertEquals("only the shown rows are messages", listOf(11L, 23L), parsed.messages.map { it.rowId })
        assertEquals("the raw ids cover all five rows, in the server's order", listOf(11L, 14L, 17L, 20L, 23L), parsed.rawIds)
        assertEquals(5, parsed.returned)
    }

    @Test fun rowsWithoutAnIdAreNotInventedAndAnEmptyPageHasNone() {
        assertEquals(listOf(5L), page("""{"role":"user","content":"x"}""", """{"id":5,"role":"user","content":"y"}""").rawIds)
        assertEquals(emptyList<Long>(), page().rawIds)
    }
}
