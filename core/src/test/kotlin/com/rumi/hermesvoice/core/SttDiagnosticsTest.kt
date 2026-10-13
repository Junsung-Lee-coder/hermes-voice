package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.diag.*
import com.rumi.hermesvoice.core.net.SttCode
import com.rumi.hermesvoice.core.voice.SttPhase
import com.rumi.hermesvoice.core.voice.SttRecoveryEvent
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnStage
import java.io.IOException
import java.util.concurrent.Executor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Speech-recognition recovery and the request lifecycle as the diagnostics record and export them: fixed words, small numbers, nothing else. */
class SttDiagnosticsTest {
    private class Clocks(var mono: Long = 1_000_000L, var wall: Long = 1_800_000_000_000L) {
        fun advance(ms: Long) { mono += ms; wall += ms }
    }

    private class MemStorage : DiagJournalStorage {
        @Volatile var bytes: ByteArray? = null
        override fun read(maxBytes: Int): ByteArray? = bytes?.also { if (it.size > maxBytes) throw DiagJournalOversize() }
        override fun write(bytes: ByteArray) { this.bytes = bytes }
        override fun delete() { bytes = null }
    }

    private val inline = Executor { it.run() }
    private val salt = "0123456789abcdef"
    private val app = DiagApp("0.1.20-dev", 20, DiagBuild(DiagBuildStatus.UNAVAILABLE))
    private val sentinels = listOf("SECRET-TOKEN-xyz", "https://hermes.example/secret/path?key=abc", "/home/user/private", "alice@example.com",
        "192.168.1.77", "session-id-424242", "hello this is what the user said", "Whisper-large-v3")

    private fun log(c: Clocks = Clocks()) = DiagLog(clock = { c.mono }, wallClock = { c.wall })

    private fun ev(phase: SttPhase, attempt: Int, code: SttCode? = null, status: Int? = null, waitMs: Long? = null, waiting: Boolean = false,
               failure: String? = null, bytes: Int = 4096, mime: String = "audio/wav", elapsed: Long = 120) =
        SttRecoveryEvent(phase, attempt, elapsed, bytes, mime, code = code, status = status, failure = failure, waitMs = waitMs, waiting = waiting)

    private fun events(log: DiagLog, code: DiagCode) = log.snapshot().filter { it.code == code }

    private fun report(log: DiagLog, schema: Int = 3): JSONObject = JSONObject(DiagReportV2.build(1_800_000_000_000L, app, log.snapshot(), log.now(), salt,
        WireCoverage.of(log.coverage(), DiagPersistStatus.NOT_ENABLED), DiagNetwork.UNKNOWN, WatchDiag(WatchDiagStatus.OFFLINE), emptyMap(), schema))

    @Test fun `every phase of the recovery has its own whitelisted word and the safe code`() {
        val log = log()
        val l = DiagVoiceListener(object : VoiceTurnListener {}, log)
        l.onSttRecovery("t", ev(SttPhase.ATTEMPT_STARTED, 1))
        l.onSttRecovery("t", ev(SttPhase.ATTEMPT_FAILED, 1, SttCode.UNAVAILABLE, 400))
        l.onSttRecovery("t", ev(SttPhase.WAIT_STARTED, 2, SttCode.UNAVAILABLE, 400, waitMs = 5_000))
        l.onSttRecovery("t", ev(SttPhase.ATTEMPT_STARTED, 2))
        l.onSttRecovery("t", ev(SttPhase.ATTEMPT_SUCCEEDED, 2))
        l.onSttRecovery("t", ev(SttPhase.RECOVERED, 2))
        val codes = log.snapshot().map { it.code }
        assertEquals(listOf(DiagCode.STT_ATTEMPT_STARTED, DiagCode.STT_ATTEMPT_FAILED, DiagCode.STT_WAIT_STARTED, DiagCode.STT_ATTEMPT_STARTED,
            DiagCode.STT_ATTEMPT_OK, DiagCode.STT_RECOVERED), codes)
        val failed = events(log, DiagCode.STT_ATTEMPT_FAILED).single()
        assertEquals(SttCode.UNAVAILABLE, failed.stt?.code)
        assertEquals(DiagCause.STT_UNAVAILABLE, failed.cause)
        assertEquals(400, failed.status)
        assertEquals(DiagStage.STT, failed.stage)
        assertEquals(5_000L, events(log, DiagCode.STT_WAIT_STARTED).single().stt?.waitMs)
        assertEquals(DiagMime.WAV, failed.stt?.mime)
        assertEquals(4096, failed.stt?.audioBytes)
        assertEquals("the app cannot see the backend: it says unknown", DiagBackend.UNKNOWN, failed.stt?.backend)
    }

    @Test fun `a cancel is a cancel and not a failure and exhaustion is its own word`() {
        val log = log()
        val l = DiagVoiceListener(object : VoiceTurnListener {}, log)
        l.onSttRecovery("a", ev(SttPhase.CANCELLED, 1, waiting = true, failure = "CancellationException"))
        l.onSttRecovery("b", ev(SttPhase.EXHAUSTED, 6, SttCode.BUSY, 400))
        val cancelled = events(log, DiagCode.STT_CANCELLED).single()
        assertEquals(DiagFail.CANCELLED, cancelled.fail)
        assertEquals(DiagCause.CANCELLED, cancelled.cause)
        assertTrue(events(log, DiagCode.STT_ATTEMPT_FAILED).isEmpty())
        val exhausted = events(log, DiagCode.STT_EXHAUSTED).single()
        assertEquals(DiagCause.STT_UNAVAILABLE, exhausted.cause)
        assertEquals(6, exhausted.n)
    }

    @Test fun `input and auth rejections are classified as rejections and never as an unavailable server`() {
        val log = log()
        val l = DiagVoiceListener(object : VoiceTurnListener {}, log)
        l.onSttRecovery("a", ev(SttPhase.ATTEMPT_FAILED, 1, SttCode.INPUT_REJECTED, 400))
        l.onSttRecovery("b", ev(SttPhase.ATTEMPT_FAILED, 1, SttCode.AUTH_REJECTED, 400))
        l.onSttRecovery("c", ev(SttPhase.ATTEMPT_FAILED, 1, null, 401))
        l.onSttRecovery("d", ev(SttPhase.ATTEMPT_FAILED, 1, null, 400))
        val causes = events(log, DiagCode.STT_ATTEMPT_FAILED).map { it.cause }
        assertEquals(listOf(DiagCause.REJECTED, DiagCause.AUTH, DiagCause.AUTH), causes.take(3))
        assertFalse(causes[3] == DiagCause.STT_UNAVAILABLE)
    }

    @Test fun `request_delivered is recorded only for a genuine accepted submit and transmission end is separate`() {
        val log = log()
        val l = DiagVoiceListener(object : VoiceTurnListener {}, log)
        l.onAccepted("t", VoiceOrigin.PHONE)
        l.onOutcome("t", VoiceTurnOutcome.NotDelivered(VoiceTurnStage.TRANSCRIBING, "failed").also { it.diagCause = DiagCause.STT_UNAVAILABLE })
        l.onTransmitted("t")
        val codes = log.snapshot().map { it.code }
        assertTrue(codes.toString(), DiagCode.REQUEST_FAILED in codes)
        assertTrue(codes.toString(), DiagCode.REQUEST_TRANSMISSION_ENDED in codes)
        assertFalse("a failed request is never reported delivered: $codes", DiagCode.REQUEST_DELIVERED in codes)
    }

    @Test fun `schema 3 reports carry stt details and reading notes and older schemas hide the new words`() {
        val log = log()
        val l = DiagVoiceListener(object : VoiceTurnListener {}, log)
        l.onSttRecovery("t", ev(SttPhase.WAIT_STARTED, 2, SttCode.TIMEOUT, 400, waitMs = 15_000))
        l.onTransmitted("t")
        val three = report(log, 3)
        assertEquals(3, three.getInt("schema"))
        val array = three.getJSONObject("phone").getJSONArray("events")
        val codes = (0 until array.length()).map { array.getJSONObject(it).getString("code") }
        assertTrue(codes.toString(), "stt_wait_started" in codes && "request_transmission_ended" in codes)
        val wait = (0 until array.length()).map { array.getJSONObject(it) }.first { it.getString("code") == "stt_wait_started" }
        val stt = wait.getJSONObject("stt")
        assertEquals("stt_timeout", stt.getString("code"))
        assertEquals("unknown", stt.getString("backend"))
        assertEquals("unknown", stt.getString("model"))
        assertEquals(15_000L, stt.getLong("wait_ms"))
        assertTrue(three.getJSONArray("reading").toString().contains("request_transmission_ended_is_bookkeeping"))
        val two = report(log, 2)
        assertEquals(2, two.getInt("schema"))
        val twoCodes = two.getJSONObject("phone").getJSONArray("events").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("code") } }
        assertTrue(twoCodes.toString(), twoCodes.none { it.startsWith("stt_") || it == "request_transmission_ended" })
    }

    @Test fun `no sentinel from an exception, a failure name or a message ever reaches the report`() {
        val log = log()
        val l = DiagVoiceListener(object : VoiceTurnListener {}, log)
        l.onSttRecovery(sentinels[5], ev(SttPhase.ATTEMPT_FAILED, 1, null, 502, failure = sentinels.joinToString("|")))
        l.onSttRecovery(sentinels[5], ev(SttPhase.ATTEMPT_FAILED, 2, null, 500, failure = "com.evil.${sentinels[0]}", mime = "audio/x-${sentinels[7]}"))
        l.onOutcome(sentinels[5], VoiceTurnOutcome.NotDelivered(VoiceTurnStage.TRANSCRIBING, sentinels.joinToString(" ")))
        val text = JSONObject(report(log).toString()).toString()
        for (s in sentinels) assertFalse("leaks: $s", text.contains(s))
        assertEquals("other", events(log, DiagCode.STT_ATTEMPT_FAILED).last().stt?.mime?.wire)
    }

    @Test fun `one request keeps one pseudonym across attempts and the raw turn id never leaves`() {
        val log = log()
        val l = DiagVoiceListener(object : VoiceTurnListener {}, log)
        val turn = "6f1c2c1e-6e0b-4c5e-9d0e-0a1b2c3d4e5f"
        l.onAccepted(turn, VoiceOrigin.WATCH)
        l.onSttRecovery(turn, ev(SttPhase.ATTEMPT_FAILED, 1, SttCode.UNAVAILABLE, 400))
        l.onSttRecovery(turn, ev(SttPhase.RECOVERED, 2))
        val json = report(log)
        assertFalse(json.toString().contains(turn))
        val a = json.getJSONObject("phone").getJSONArray("events")
        val refs = (0 until a.length()).map { a.getJSONObject(it).getString("ref") }.toSet()
        assertEquals(1, refs.size)
        assertTrue(DiagPseudonyms.FORMAT.matches(refs.single()))
    }

    @Test fun `stt events survive a restart through the journal and stay bounded`() {
        val c = Clocks()
        val storage = MemStorage()
        val first = log(c)
        DiagJournal(first, storage, inline).attach()
        val l = DiagVoiceListener(object : VoiceTurnListener {}, first)
        l.onSttRecovery("t", ev(SttPhase.WAIT_STARTED, 2, SttCode.UNAVAILABLE, 400, waitMs = 5_000))
        c.advance(60_000)
        val second = log(c)
        DiagJournal(second, storage, inline).attach()
        val restored = events(second, DiagCode.STT_WAIT_STARTED).single()
        assertEquals(SttCode.UNAVAILABLE, restored.stt?.code)
        assertEquals(5_000L, restored.stt?.waitMs)
        assertEquals(1, second.coverage().restored)
        repeat(1000) { second.record(DiagCode.STT_ATTEMPT_STARTED, ref = "x$it", n = it, stt = DiagStt(null, 10, DiagMime.WAV)) }
        assertTrue(second.snapshot().size <= DiagLog.MAX_CAPACITY)
        assertNotNull(second.dropped().takeIf { it > 0 })
    }

    @Test fun `a version 1 journal from an earlier build is still restored`() {
        val c = Clocks()
        val storage = MemStorage()
        val old = log(c)
        DiagJournal(old, storage, inline).attach()
        old.record(DiagCode.REQUEST_ACCEPTED, DiagOrigin.PHONE, "t", modality = DiagModality.VOICE)
        val v1 = JSONObject(String(storage.bytes!!)).put("v", 1)
        storage.bytes = v1.toString().toByteArray()
        val fresh = log(c)
        DiagJournal(fresh, storage, inline).attach()
        assertEquals(1, events(fresh, DiagCode.REQUEST_ACCEPTED).size)
    }

    @Test fun `values outside the fixed set are refused when a journal or wire event is read`() {
        assertNull(DiagSttJson.decode(JSONObject().put("code", "made_up")))
        assertNull(DiagSttJson.decode(JSONObject().put("mime", "audio/evil")))
        assertNull(DiagSttJson.decode(JSONObject().put("model", "Whisper-large-v3")))
        assertNull(DiagSttJson.decode(JSONObject().put("extra", 1)))
        assertNull(DiagSttJson.decode(JSONObject().put("bytes", -1)))
        assertNotNull(DiagSttJson.decode(JSONObject().put("code", "stt_busy").put("bytes", 10).put("mime", "wav").put("backend", "unknown").put("model", "unknown")))
    }

    @Test fun `an io failure name alone never makes a request look delivered`() {
        val log = log()
        log.record(DiagCode.STT_ATTEMPT_FAILED, IOException("secret ${sentinels[0]}"), DiagOrigin.PHONE, "t")
        val text = report(log).toString()
        assertFalse(text.contains(sentinels[0]))
        assertTrue(events(log, DiagCode.REQUEST_DELIVERED).isEmpty())
    }
}
