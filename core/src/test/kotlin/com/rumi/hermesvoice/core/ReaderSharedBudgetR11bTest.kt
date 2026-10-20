package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.watchlink.PhoneReaderService
import com.rumi.hermesvoice.core.watchlink.ReaderKind
import com.rumi.hermesvoice.core.watchlink.ReaderRequest
import com.rumi.hermesvoice.core.watchlink.ReaderRequestLedger
import com.rumi.hermesvoice.core.watchlink.ReaderResponse
import com.rumi.hermesvoice.core.watchlink.WatchReaderLimits
import java.nio.charset.StandardCharsets
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * R11b: one retry allowance per logical refresh across Watch and Phone. The Watch says how many retries it already spent (an additive,
 * validated request field an older Watch simply omits), the Phone continues the SAME allowance, and a repeated request id is one piece of
 * work. Real request codec, Phone reader service and ledger over the dashboard contract double. Synthetic content only.
 */
class ReaderSharedBudgetR11bTest {
    private val h = CoreHarness()

    @After fun tearDown() = h.close()

    private fun wire(vararg extra: Pair<String, Any?>): ByteArray {
        val json = JSONObject().put("v", 1).put("req_id", "rd-r11b-core-0001").put("kind", "sessions").put("offset", 0).put("limit", 20)
        extra.forEach { (k, v) -> json.put(k, v) }
        return json.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun unavailable() = MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json")
        .setBody("""{"detail":"synthetic read fault"}""")

    private fun arrivals(kind: String) = h.fake.readArrivals.toList().filter { it.first == kind }

    // ── wire ─────────────────────────────────────────────────────────────────────────────────

    @Test fun `a request that spent no retries is encoded exactly as before`() {
        val bytes = ReaderRequest("rd-r11b-core-0001", ReaderKind.SESSIONS).encode()
        assertFalse(JSONObject(String(bytes, StandardCharsets.UTF_8)).has("retries_used"))
        assertEquals(0, ReaderRequest.decode(bytes)!!.retriesUsed)
    }

    @Test fun `the retries a request already spent survive the wire and fit the request limit`() {
        for (spent in 1..2) {
            val request = ReaderRequest("rd-r11b-core-0001", ReaderKind.HISTORY, "20260101_120000_workwork", offset = 40, retriesUsed = spent)
            val bytes = request.encode()
            assertTrue(bytes.size <= WatchReaderLimits.MAX_REQUEST_BYTES)
            assertEquals(request, ReaderRequest.decode(bytes))
        }
    }

    @Test fun `a request from an older Watch without the field or with a null field spent nothing`() {
        assertEquals(0, ReaderRequest.decode(wire())!!.retriesUsed)
        assertEquals(0, ReaderRequest.decode(wire("retries_used" to JSONObject.NULL))!!.retriesUsed)
    }

    @Test fun `a retries field that is not a whole number of zero to two is refused`() {
        for (bad in listOf<Any>(3, -1, 99, 2.5, "1", true, 3_000_000_000L)) {
            assertNull("retries_used=$bad", ReaderRequest.decode(wire("retries_used" to bad)))
        }
        for (good in 0..2) assertEquals(good, ReaderRequest.decode(wire("retries_used" to good))!!.retriesUsed)
    }

    @Test fun `an unknown additional field is still tolerated`() {
        assertNotNull(ReaderRequest.decode(wire("retries_used" to 1, "future_field" to "x")))
    }

    // ── ledger ───────────────────────────────────────────────────────────────────────────────

    private fun request(id: String, spent: Int = 0) = ReaderRequest(id, ReaderKind.SESSIONS, retriesUsed = spent)

    @Test fun `the same request id from the same node while it is being served is dropped`() {
        val ledger = ReaderRequestLedger()
        assertEquals(0, ledger.admit("node-a", request("rd-r11b-led-0001")))
        assertNull(ledger.admit("node-a", request("rd-r11b-led-0001")))
        ledger.finish("node-a", "rd-r11b-led-0001", 0)
        assertEquals(0, ledger.admit("node-a", request("rd-r11b-led-0001")))
    }

    @Test fun `the same request id from another node is another refresh`() {
        val ledger = ReaderRequestLedger()
        assertEquals(0, ledger.admit("node-a", request("rd-r11b-led-0002")))
        assertEquals(0, ledger.admit("node-b", request("rd-r11b-led-0002")))
    }

    @Test fun `a repeated request continues with the retries already spent and never fewer than the request says`() {
        val ledger = ReaderRequestLedger()
        assertEquals(1, ledger.admit("node-a", request("rd-r11b-led-0003", spent = 1)))
        ledger.finish("node-a", "rd-r11b-led-0003", 2)
        assertEquals("the earlier run spent both", 2, ledger.admit("node-a", request("rd-r11b-led-0003", spent = 1)))
        ledger.finish("node-a", "rd-r11b-led-0003", 1)
        assertEquals("a smaller report never gives retries back", 2, ledger.admit("node-a", request("rd-r11b-led-0003")))
        ledger.finish("node-a", "rd-r11b-led-0003", null)
        assertEquals(2, ledger.admit("node-a", request("rd-r11b-led-0003", spent = 2)))
    }

    @Test fun `a run that ended without reporting leaves the request servable again`() {
        val ledger = ReaderRequestLedger()
        assertEquals(0, ledger.admit("node-a", request("rd-r11b-led-0004")))
        ledger.finish("node-a", "rd-r11b-led-0004", null)
        assertEquals(0, ledger.admit("node-a", request("rd-r11b-led-0004")))
    }

    @Test fun `the memory of spent retries is bounded`() {
        val ledger = ReaderRequestLedger(capacity = 2)
        for (i in 1..3) {
            assertEquals(0, ledger.admit("node-a", request("rd-r11b-led-100$i")))
            ledger.finish("node-a", "rd-r11b-led-100$i", 2)
        }
        assertEquals("the oldest was forgotten", 0, ledger.admit("node-a", request("rd-r11b-led-1001")))
        assertEquals("a recent one is kept", 2, ledger.admit("node-a", request("rd-r11b-led-1003")))
    }

    // ── Phone service ────────────────────────────────────────────────────────────────────────

    private fun historyBytes(spent: Int, session: String) =
        ReaderRequest("rd-r11b-core-0002", ReaderKind.HISTORY, session, retriesUsed = spent).encode()

    private fun serve(spent: Int, carried: Int? = null): Pair<ReaderResponse, Int> {
        val work = runBlocking { h.core.sessions.createConversation("Work", "work", "") }
        h.fake.readFault = { kind, _ -> if (kind == "messages") unavailable() else null }
        var reported = -1
        val response = runBlocking {
            PhoneReaderService(h.core.sessions).handle(historyBytes(spent, work.storedSessionId), carried) { reported = it }
        }
        return ReaderResponse.decode(response!!)!! to reported
    }

    @Test fun `a request that says both retries are spent gets one read and reports two`() {
        val (response, reported) = serve(spent = 2)
        assertFalse(response.ok)
        assertEquals(1, arrivals("messages").size)
        assertEquals(2, reported)
    }

    @Test fun `a request that says one retry is spent gets the first read and one retry`() {
        val (response, reported) = serve(spent = 1)
        assertFalse(response.ok)
        assertEquals(2, arrivals("messages").size)
        assertEquals(2, reported)
    }

    @Test fun `a request that spent nothing keeps the full Phone allowance`() {
        val (response, reported) = serve(spent = 0)
        assertFalse(response.ok)
        assertEquals(3, arrivals("messages").size)
        assertEquals(2, reported)
    }

    @Test fun `the carried count of a repeated request overrides the one on the wire`() {
        val (_, reported) = serve(spent = 0, carried = 2)
        assertEquals(1, arrivals("messages").size)
        assertEquals(2, reported)
    }

    @Test fun `a successful read reports the retries it actually spent`() {
        val work = runBlocking { h.core.sessions.createConversation("Work", "work", "") }
        var reported = -1
        val response = runBlocking {
            PhoneReaderService(h.core.sessions).handle(historyBytes(1, work.storedSessionId)) { reported = it }
        }
        assertTrue(ReaderResponse.decode(response!!)!!.ok)
        assertEquals(1, reported)
    }
}
