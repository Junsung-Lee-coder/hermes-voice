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

    /** Looked up by wire word: a Live setup failure is a schema 3 kind of fixed words that older readers never get and the journal keeps. */
    @Test
    fun liveSetupFailureIsASchema3KindOfFixedWordsThatOlderReadersNeverSeeAndTheJournalKeeps() {
        val code = DiagCode.fromWire("live_setup_failed")
        val session = DiagStage.fromWire("live_session")
        assertNotNull("a Live setup failure is a whitelisted kind", code)
        assertNotNull("its setup step is a fixed word", session)
        assertFalse("never in a schema 1 answer", code!!.legacy)
        assertTrue("introduced with schema 3", code.v3)
        val wall = 1_800_000_000_000L
        val log = DiagLog(clock = { 9_000L }, wallClock = { wall })
        val secret = "SENTINEL-provider-body sk-live-SECRET https://hermes.example/api/audio/voice-live/session"
        log.recordFailure(code, HermesHttpException(502, "/api/audio/voice-live/session failed (502): $secret"), DiagOrigin.WATCH, stage = session)
        val event = log.snapshot().single()
        assertEquals(DiagFail.REFUSED, event.fail)
        assertEquals(DiagCause.REJECTED, event.cause)
        assertEquals(502, event.status)
        assertEquals(null, event.ref)
        val r = responder(log)
        val one = DiagWire.decodeResponse(r.answer(DiagWire.encodeRequest(salt))!!, salt)
        val two = DiagWireV2.decodeResponse(r.answer(DiagWire.encodeRequestV2(salt))!!, salt)
        val three = DiagWireV2.decodeResponse(r.answer(DiagWire.encodeRequestV3(salt))!!, salt, DiagWireV2.SCHEMA_3)
        assertEquals(WatchDiagStatus.REACHABLE, one.status)
        assertEquals(WatchDiagStatus.REACHABLE, two.status)
        assertEquals(WatchDiagStatus.REACHABLE, three.status)
        assertTrue("older readers are never handed the newer kind", one.events.isEmpty() && two.events.isEmpty())
        val wire = three.events.single()
        assertEquals(code, wire.code)
        assertEquals(session, wire.stage)
        assertEquals(502, wire.status)
        assertEquals(DiagCause.REJECTED, wire.cause)
        assertEquals(null, wire.ref)
        val coverage = WireCoverage.of(log.coverage(), DiagPersistStatus.NOT_ENABLED)
        val report3 = DiagReportV2.build(0L, app, log.snapshot(), 9_000L, salt, coverage, DiagNetwork.UNKNOWN,
            WatchDiag(WatchDiagStatus.OFFLINE), emptyMap(), DiagWireV2.SCHEMA_3)
        val report2 = DiagReportV2.build(0L, app, log.snapshot(), 9_000L, salt, coverage, DiagNetwork.UNKNOWN,
            WatchDiag(WatchDiagStatus.OFFLINE), emptyMap(), DiagWireV2.SCHEMA)
        assertTrue(report3.contains("\"live_setup_failed\"") && report3.contains("\"live_session\""))
        assertFalse(report2.contains("live_setup_failed") || report2.contains("live_session"))
        var stored: ByteArray? = null
        val storage = object : DiagJournalStorage {
            override fun read(maxBytes: Int): ByteArray? = stored
            override fun write(bytes: ByteArray) { stored = bytes }
            override fun delete() { stored = null }
        }
        DiagJournal(log, storage, java.util.concurrent.Executor { it.run() }).flushNow()
        val restarted = DiagLog(clock = { 20_000L }, wallClock = { wall + 1_000L })
        DiagJournal(restarted, storage, java.util.concurrent.Executor { it.run() }).restoreNow()
        val restored = restarted.snapshot().single()
        assertEquals(code, restored.code)
        assertEquals(session, restored.stage)
        assertEquals(502, restored.status)
        assertEquals(DiagOrigin.WATCH, restored.origin)
        for (text in listOf(report3, report2, String(stored!!, Charsets.UTF_8))) {
            assertFalse(text, text.contains("SENTINEL") || text.contains("sk-live") || text.contains("hermes.example") || text.contains("voice-live"))
        }
    }
}
