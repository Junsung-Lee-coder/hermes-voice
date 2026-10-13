package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.diag.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Watch part of the export is asked for in the newest schema and falls back one step at a time; older readers never see newer kinds. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagWireFallbackTest {
    private val salt = "0123456789abcdef"
    private val app = DiagApp("0.2.0", 21, DiagBuild.UNAVAILABLE)

    private fun log(): DiagLog {
        val log = DiagLog(clock = { 9_000L })
        log.record(DiagCode.REQUEST_STARTED, DiagOrigin.WATCH, "t1", modality = DiagModality.VOICE)
        log.record(DiagCode.STT_ATTEMPT_FAILED, DiagOrigin.WATCH, "t1", stage = DiagStage.STT, modality = DiagModality.VOICE,
            stt = DiagStt(code = com.rumi.hermesvoice.core.net.SttCode.UNAVAILABLE, audioBytes = 1200, mime = DiagMime.WAV))
        log.record(DiagCode.REQUEST_TRANSMISSION_ENDED, DiagOrigin.WATCH, "t1", modality = DiagModality.VOICE)
        return log
    }

    private fun responder(log: DiagLog) = DiagWatchResponder(log, { DiagPersistStatus.NOT_ENABLED }, { app })

    @Test
    fun `the Watch answers each request in the schema asked and newer kinds appear only in schema 3`() {
        val log = log()
        val r = responder(log)
        val three = DiagWireV2.decodeResponse(r.answer(DiagWire.encodeRequestV3(salt))!!, salt, DiagWireV2.SCHEMA_3)
        val two = DiagWireV2.decodeResponse(r.answer(DiagWire.encodeRequestV2(salt))!!, salt)
        val one = DiagWire.decodeResponse(r.answer(DiagWire.encodeRequest(salt))!!, salt)
        assertEquals(WatchDiagStatus.REACHABLE, three.status)
        assertEquals(WatchDiagStatus.REACHABLE, two.status)
        assertEquals(WatchDiagStatus.REACHABLE, one.status)
        assertTrue(three.events.any { it.code == DiagCode.STT_ATTEMPT_FAILED } && three.events.any { it.code == DiagCode.REQUEST_TRANSMISSION_ENDED })
        assertTrue(two.events.none { it.code.v3 })
        assertTrue(one.events.none { it.code.v3 })
        assertEquals(DiagWireV2.SCHEMA_3, three.v2!!.schema)
        assertEquals(DiagWireV2.SCHEMA, two.v2!!.schema)
    }

    @Test
    fun `a schema 3 payload is refused by a schema 2 reader and never half accepted`() {
        val bytes = responder(log()).answer(DiagWire.encodeRequestV3(salt))!!
        assertEquals(WatchDiagStatus.INVALID, DiagWireV2.decodeResponse(bytes, salt).status)
    }

    @Test
    fun `a Watch that only knows schema 2 is asked 3 then 2 and its answer carries no newer kind`() = runTest {
        val sent = mutableListOf<Int>()
        lateinit var client: DiagWatchClient
        val r = responder(log())
        client = DiagWatchClient { bytes ->
            val schema = DiagWire.decodeRequestAny(bytes)!!.first
            sent += schema
            if (schema <= 2) client.onResponse(r.answer(bytes)!!)
            true
        }
        val result = CompletableDeferred<WatchDiag>()
        launch { result.complete(client.request(salt)) }
        runCurrent()
        assertEquals(listOf(3), sent)
        advanceTimeBy(DiagWatchClient.NEWER_WAIT_MS + 1); runCurrent()
        assertEquals(listOf(3, 2), sent)
        val answer = result.await()
        assertEquals(WatchDiagStatus.REACHABLE, answer.status)
        assertEquals(DiagWireV2.SCHEMA, answer.v2!!.schema)
        assertTrue(answer.events.none { it.code.v3 })
    }

    @Test
    fun `the exported report states the schema of each side and hides schema 3 kinds from a schema 2 report`() {
        val phone = log().snapshot()
        val coverage = WireCoverage.of(DiagLog(clock = { 0 }).coverage(), DiagPersistStatus.NOT_ENABLED)
        val three = org.json.JSONObject(DiagReportV2.build(0L, app, phone, 9_000L, salt, coverage, DiagNetwork.UNKNOWN,
            WatchDiag(WatchDiagStatus.OFFLINE), emptyMap(), DiagWireV2.SCHEMA_3))
        val two = org.json.JSONObject(DiagReportV2.build(0L, app, phone, 9_000L, salt, coverage, DiagNetwork.UNKNOWN,
            WatchDiag(WatchDiagStatus.OFFLINE), emptyMap(), DiagWireV2.SCHEMA))
        assertEquals(3, three.getInt("schema"))
        assertEquals(2, two.getInt("schema"))
        assertTrue(three.toString().contains("stt_attempt_failed"))
        assertFalse(two.toString().contains("stt_attempt_failed"))
        assertFalse(two.toString().contains("request_transmission_ended"))
        assertNotNull(three.getJSONArray("reading"))
        assertTrue(three.getJSONArray("reading").toString().contains("request_transmission_ended_is_bookkeeping_and_never_proof_of_delivery"))
        assertFalse(two.getJSONArray("reading").toString().contains("bookkeeping"))
    }
}
