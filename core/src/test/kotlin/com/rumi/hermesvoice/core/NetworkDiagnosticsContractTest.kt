package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.diag.ApkIdentity
import com.rumi.hermesvoice.core.diag.DiagApp
import com.rumi.hermesvoice.core.diag.DiagBuild
import com.rumi.hermesvoice.core.diag.DiagBuildStatus
import com.rumi.hermesvoice.core.diag.DiagCause
import com.rumi.hermesvoice.core.diag.DiagClassify
import com.rumi.hermesvoice.core.diag.DiagCode
import com.rumi.hermesvoice.core.diag.DiagConn
import com.rumi.hermesvoice.core.diag.DiagFail
import com.rumi.hermesvoice.core.diag.DiagJournal
import com.rumi.hermesvoice.core.diag.DiagLabels
import com.rumi.hermesvoice.core.diag.DiagLog
import com.rumi.hermesvoice.core.diag.DiagModality
import com.rumi.hermesvoice.core.diag.DiagNetwork
import com.rumi.hermesvoice.core.diag.DiagOrigin
import com.rumi.hermesvoice.core.diag.DiagPersistStatus
import com.rumi.hermesvoice.core.diag.DiagReportV2
import com.rumi.hermesvoice.core.diag.DiagStage
import com.rumi.hermesvoice.core.diag.DiagTransport
import com.rumi.hermesvoice.core.diag.DiagVoiceListener
import com.rumi.hermesvoice.core.diag.DiagWatchClient
import com.rumi.hermesvoice.core.diag.DiagWire
import com.rumi.hermesvoice.core.diag.DiagWireV2
import com.rumi.hermesvoice.core.diag.WatchDiagStatus
import com.rumi.hermesvoice.core.diag.WireCoverage
import com.rumi.hermesvoice.core.chat.ComposerDispatch
import com.rumi.hermesvoice.core.chat.DispatchedSend
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnStage
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Failure categories, the schema-2 Watch wire and report, build identity, voice failure typing and privacy against adversarial strings. */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkDiagnosticsContractTest {
    @get:Rule val tmp = TemporaryFolder()

    private val salt = "0123456789abcdef"
    private val secrets = listOf("SECRET-TOKEN-xyz", "https://hermes.example/secret?key=abc", "10.9.8.7", "Bearer abc.def.ghi", "what the user said aloud",
        "C:\\Users\\owner\\secret.pdf", "ssid-HomeNetwork-5G", "session-id-424242", "stack at com.example.Secret.method(Secret.kt:12)")

    private fun hostile(): List<Throwable> = secrets.flatMap { s ->
        listOf(HermesHttpException(500, s), HermesRpcException(-32000, s), HermesProtocolException(s, IOException(s)), IOException(s),
            RuntimeException(s, UnknownHostException(s)), IllegalArgumentException(s), SecurityException(s), HermesAuthRequiredException(s),
            HermesPlaybackException(s), SessionNotOwnedException(s))
    }

    // ---- classification: by type and explicit code only

    @Test
    fun `categories come from exception types and explicit codes`() {
        fun cause(e: Throwable, conn: DiagConn? = null) = DiagClassify.of(e, conn).cause
        assertEquals(DiagCause.DNS, cause(UnknownHostException("x")))
        assertEquals(DiagCause.CONNECT, cause(ConnectException("x")))
        assertEquals(DiagCause.TLS, cause(SSLHandshakeException("x")))
        assertEquals(DiagCause.TIMEOUT, cause(SocketTimeoutException("x")))
        assertEquals(DiagCause.DISCONNECT, cause(IOException("x")))
        assertEquals(DiagCause.AUTH, cause(HermesAuthRequiredException("x")))
        assertEquals(DiagCause.AUTH, cause(HermesHttpException(401, "x")))
        assertEquals(DiagCause.AUTH, cause(HermesHttpException(403, "x")))
        assertEquals(DiagCause.REJECTED, cause(HermesHttpException(500, "x")))
        assertEquals(500, DiagClassify.of(HermesHttpException(500, "x")).status)
        assertEquals(DiagCause.REJECTED, cause(HermesRpcException(-32000, "x")))
        assertEquals(DiagCause.AUTH, cause(HermesGatewayClosedException(4401, "x")))
        assertEquals(DiagCause.REJECTED, cause(HermesGatewayClosedException(4403, "x")))
        assertEquals(DiagCause.DISCONNECT, cause(HermesGatewayClosedException(1006, "x")))
        assertEquals(DiagCause.TIMEOUT, cause(HermesInactivityTimeoutException("x")))
        assertEquals(DiagCause.DISCONNECT, cause(HermesStreamEndedException("x")))
        assertEquals(DiagCause.REJECTED, cause(HermesTurnFailedException("x")))
        assertEquals(DiagCause.CANCELLED, cause(CancellationException("x")))
        assertEquals(DiagCause.UNKNOWN, cause(IllegalStateException("x")))
    }

    @Test
    fun `a wrapped network error is found in the cause chain and a message never decides`() {
        assertEquals(DiagCause.DNS, DiagClassify.of(HermesProtocolException("wrapped", UnknownHostException("a"))).cause)
        assertEquals(DiagCause.UNKNOWN, DiagClassify.of(IllegalStateException("UnknownHostException SocketTimeoutException 401 Unauthorized")).cause)
        assertEquals(DiagCause.UNKNOWN, DiagClassify.of(HermesProtocolException("HTTP 401 forbidden timeout dns tls")).cause)
        val loop = RuntimeException("a")
        loop.initCause(RuntimeException("b", loop))
        assertEquals(DiagCause.UNKNOWN, DiagClassify.of(loop).cause)
    }

    @Test
    fun `timeouts keep their phase and a deadline is not a user stop`() = runTest {
        val timeout = try { withTimeout(1) { kotlinx.coroutines.awaitCancellation() } } catch (e: TimeoutCancellationException) { e }
        assertEquals(DiagCause.TIMEOUT, DiagClassify.of(timeout).cause)
        val log = DiagLog(clock = { 1L })
        log.recordFailure(DiagCode.REQUEST_FAILED, SocketTimeoutException(), DiagOrigin.PHONE, "t", stage = DiagStage.UPLOAD, modality = DiagModality.ATTACHMENT)
        val event = log.snapshot().single()
        assertEquals(DiagCause.TIMEOUT, event.cause)
        assertEquals("the phase is retained, not turned into dns or connect", DiagStage.UPLOAD, event.stage)
    }

    @Test
    fun `the connectivity snapshot is context only and never turns a refused or authenticated failure into a network one`() {
        assertEquals(DiagCause.NO_NETWORK, DiagClassify.of(UnknownHostException(), DiagConn.NONE).cause)
        assertEquals(DiagCause.DNS, DiagClassify.of(UnknownHostException(), DiagConn.UNVALIDATED).cause)
        assertEquals(DiagCause.CONNECT, DiagClassify.of(ConnectException(), DiagConn.UNVALIDATED).cause)
        assertEquals(DiagCause.AUTH, DiagClassify.of(HermesHttpException(401, "x"), DiagConn.NONE).cause)
        assertEquals(DiagCause.REJECTED, DiagClassify.of(HermesHttpException(500, "x"), DiagConn.NONE).cause)
        assertEquals(DiagCause.TIMEOUT, DiagClassify.of(SocketTimeoutException(), DiagConn.NONE).cause)
        val log = DiagLog(clock = { 1L }, connectivity = { DiagConn.UNVALIDATED })
        log.recordFailure(DiagCode.REQUEST_FAILED, ConnectException(), DiagOrigin.PHONE, "t", stage = DiagStage.CONNECT)
        assertEquals(DiagConn.UNVALIDATED, log.snapshot().single().conn)
    }

    @Test
    fun `a connectivity probe that throws is unknown and never fails recording`() {
        val log = DiagLog(clock = { 1L }, connectivity = { throw IllegalStateException("SECRET") })
        log.recordFailure(DiagCode.REQUEST_FAILED, IOException(), DiagOrigin.PHONE, "t")
        assertEquals(DiagConn.UNKNOWN, log.snapshot().single().conn)
    }

    @Test
    fun `the safe label names stage and category, and acceptance decides the wording`() {
        val after = DiagLabels.failure(DiagStage.WAIT, DiagCause.DISCONNECT, acceptanceObserved = true)!!
        val before = DiagLabels.failure(DiagStage.UPLOAD, DiagCause.TIMEOUT, acceptanceObserved = false)!!
        assertTrue(after, after.startsWith("Hermes accepted it"))
        assertFalse(after.contains("never sent", ignoreCase = true))
        assertTrue(before, before.startsWith("Acceptance not observed"))
        assertTrue(before.contains("timed out") && before.contains("uploading"))
        assertNull(DiagLabels.failure(null, null, false))
        val failed = ChatSendResult.Failed("Hermes said SECRET-TOKEN-xyz", stage = DiagStage.SUBMIT, cause = DiagCause.REJECTED)
        val dispatched = DispatchedSend(1L, "s", "t", emptyList())
        val kept = ComposerDispatch.failure(dispatched, failed)!!
        assertEquals("the legacy reason is preserved", "Hermes said SECRET-TOKEN-xyz", kept.reason)
        assertFalse("the label shown never carries it", kept.label!!.contains("SECRET"))
        assertFalse(ComposerDispatch.statusSafe(failed).contains("SECRET"))
        assertEquals("sent, then lost", ComposerDispatch.statusSafe(ChatSendResult.Failed("sent, then lost")))
    }

    // ---- privacy

    @Test
    fun `adversarial exception text and ids reach neither the journal nor the export`() {
        val storage = object : com.rumi.hermesvoice.core.diag.DiagJournalStorage {
            var bytes: ByteArray? = null
            override fun read(maxBytes: Int) = bytes
            override fun write(bytes: ByteArray) { this.bytes = bytes }
            override fun delete() { bytes = null }
        }
        val log = DiagLog(clock = { 1_000L })
        DiagJournal(log, storage, java.util.concurrent.Executor { it.run() }).attach()
        hostile().forEachIndexed { i, e ->
            log.recordFailure(DiagCode.REQUEST_FAILED, e, DiagOrigin.PHONE, secrets[i % secrets.size], stage = DiagStage.SUBMIT, modality = DiagModality.TEXT)
        }
        val report = DiagReportV2.build(1_800_000_000_000L, DiagApp("0.1.19-dev", 20, DiagBuild.UNAVAILABLE), log.snapshot(), log.now(), salt,
            WireCoverage.of(log.coverage(), DiagPersistStatus.NOT_ENABLED), DiagNetwork(DiagConn.VALIDATED, setOf(DiagTransport.WIFI)),
            com.rumi.hermesvoice.core.diag.WatchDiag(WatchDiagStatus.OFFLINE), mapOf("later_replies" to true))
        val stored = String(storage.bytes!!, Charsets.UTF_8)
        for (s in secrets + listOf("hermes.example", "owner", "Bearer", "stack at")) {
            assertFalse("journal leaks $s", stored.contains(s))
            assertFalse("report leaks $s", report.contains(s))
        }
    }

    @Test
    fun `the voice listener types NotDelivered and response failures from the exception and not the reason text`() {
        val log = DiagLog(clock = { 1L })
        val listener = DiagVoiceListener(object : VoiceTurnListener {}, log)
        val notDelivered = VoiceTurnOutcome.NotDelivered(VoiceTurnStage.DELIVERING, "network: SocketTimeoutException").also {
            it.diagCause = DiagCause.TIMEOUT; it.diagStage = DiagStage.SUBMIT
        }
        listener.onOutcome("turn-a", notDelivered)
        listener.onOutcome("turn-b", VoiceTurnOutcome.NotDelivered(VoiceTurnStage.TRANSCRIBING, "SECRET-TOKEN-xyz timed out dns"))
        listener.onSpeechChunk("turn-c", 0, 2, 40, "SSLHandshakeException")
        val events = log.snapshot()
        val first = events[0]
        assertEquals(DiagCode.REQUEST_FAILED, first.code)
        assertEquals(DiagCause.TIMEOUT, first.cause)
        assertEquals(DiagStage.SUBMIT, first.stage)
        assertEquals(DiagModality.VOICE, first.modality)
        assertEquals("an untyped failure is unknown, whatever its text says", DiagCause.UNKNOWN, events[1].cause)
        assertEquals(DiagCode.SPEECH_CHUNK_FAILED, events[2].code)
        assertEquals(DiagStage.TTS, events[2].stage)
        assertEquals(DiagCause.TLS, events[2].cause)
        assertEquals(DiagFail.AUTH, DiagClassify.failOf(DiagCause.AUTH))
    }

    // ---- build identity

    @Test
    fun `build identity is a digest of the installed file or an honest unavailable or unreadable`() {
        assertEquals(DiagBuildStatus.UNAVAILABLE, ApkIdentity.of(null).status)
        assertEquals(DiagBuildStatus.UNAVAILABLE, ApkIdentity.of(File(tmp.root, "missing.apk")).status)
        assertEquals(DiagBuildStatus.UNAVAILABLE, ApkIdentity.of(tmp.root).status)
        val apk = tmp.newFile("base.apk").apply { writeBytes(ByteArray(300_000) { (it % 251).toByte() }) }
        val expected = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) }
        val built = ApkIdentity.of(apk)
        assertEquals(DiagBuildStatus.AVAILABLE, built.status)
        assertEquals(expected, built.sha256)
        assertEquals(DiagBuildStatus.UNREADABLE, ApkIdentity.of(apk, maxBytes = 1_000).status)
        assertEquals("unknown", DiagApp.safeName("1.0 \"bad\" /name"))
        assertEquals("0.1.19-dev", DiagApp.safeName("0.1.19-dev"))
        val report = DiagReportV2.build(0L, DiagApp("0.1.19-dev", 20, built), emptyList(), 0, salt, WireCoverage.of(DiagLog(clock = { 0 }).coverage(), DiagPersistStatus.NOT_ENABLED),
            DiagNetwork.UNKNOWN, com.rumi.hermesvoice.core.diag.WatchDiag(WatchDiagStatus.OFFLINE), emptyMap())
        assertTrue(report.contains(expected))
        assertFalse("no path is ever exported", report.contains("base.apk") || report.contains(tmp.root.path))
        val none = DiagReportV2.build(0L, DiagApp("0.1.19-dev", 20, DiagBuild.UNAVAILABLE), emptyList(), 0, salt, WireCoverage.of(DiagLog(clock = { 0 }).coverage(), DiagPersistStatus.NOT_ENABLED),
            DiagNetwork.UNKNOWN, com.rumi.hermesvoice.core.diag.WatchDiag(WatchDiagStatus.OFFLINE), emptyMap())
        val app = JSONObject(none).getJSONObject("app")
        assertEquals("unavailable", app.getString("build_status"))
        assertFalse(app.has("build_sha256"))
    }

    // ---- report shape and coverage

    @Test
    fun `an empty log states its coverage and is not presented as a healthy transmission`() {
        val log = DiagLog(capacity = 4, clock = { 5_000L })
        val json = JSONObject(DiagReportV2.build(1_800_000_000_000L, DiagApp("0.1.19-dev", 20, DiagBuild.UNAVAILABLE), log.snapshot(), log.now(), salt,
            WireCoverage.of(log.coverage(), DiagPersistStatus.NOT_ENABLED), DiagNetwork.UNKNOWN, com.rumi.hermesvoice.core.diag.WatchDiag(WatchDiagStatus.OFFLINE), emptyMap()))
        assertEquals(0, json.getJSONObject("phone").getJSONArray("events").length())
        val cov = json.getJSONObject("phone").getJSONObject("coverage")
        assertEquals(0, cov.getInt("retained")); assertEquals(24, cov.getInt("retention_hours")); assertEquals(4, cov.getInt("capacity"))
        assertEquals("not_enabled", cov.getString("persistence"))
        assertTrue(json.getJSONArray("reading").toString().contains("no_events_is_not_proof_of_delivery"))
        assertEquals("2027-01-15T08:00:00Z", json.getString("exported_utc"))
    }

    @Test
    fun `retained, capacity-dropped and retention-pruned are counted apart`() {
        var mono = 0L
        val log = DiagLog(capacity = 2, clock = { mono })
        log.record(DiagCode.REQUEST_FAILED, ref = "a"); log.record(DiagCode.REQUEST_FAILED, ref = "b"); log.record(DiagCode.REQUEST_FAILED, ref = "c")
        mono += DiagLog.RETENTION_MS + 1
        log.record(DiagCode.REQUEST_FAILED, ref = "d")
        val c = log.coverage()
        assertEquals(1, c.retained); assertEquals(1L, c.capacityDropped); assertEquals(2L, c.retentionPruned)
    }

    // ---- schema-2 wire

    private fun v2Bytes(events: List<com.rumi.hermesvoice.core.diag.DiagEvent>, log: DiagLog = DiagLog(clock = { 10_000L })) =
        DiagWireV2.encodeResponse(salt, events, 10_000L, log.coverage(), DiagPersistStatus.NOT_ENABLED, DiagApp("0.1.19-dev", 20, DiagBuild.UNAVAILABLE),
            DiagNetwork(DiagConn.UNVALIDATED, setOf(DiagTransport.VPN)))

    private fun v3Bytes(events: List<com.rumi.hermesvoice.core.diag.DiagEvent>, log: DiagLog = DiagLog(clock = { 10_000L })) =
        DiagWireV2.encodeResponse(salt, events, 10_000L, log.coverage(), DiagPersistStatus.NOT_ENABLED, DiagApp("0.1.19-dev", 20, DiagBuild.UNAVAILABLE),
            DiagNetwork(DiagConn.UNVALIDATED, setOf(DiagTransport.VPN)), DiagWireV2.SCHEMA_3)

    private fun sampleEvents(): List<com.rumi.hermesvoice.core.diag.DiagEvent> {
        val log = DiagLog(clock = { 9_000L })
        log.record(DiagCode.REQUEST_STARTED, DiagOrigin.WATCH, "t1", modality = DiagModality.VOICE)
        log.record(DiagCode.REQUEST_FAILED, DiagOrigin.WATCH, "t1", fail = DiagFail.TIMEOUT, stage = DiagStage.TRANSFER, cause = DiagCause.TIMEOUT, modality = DiagModality.VOICE, status = 504)
        return log.snapshot()
    }

    @Test
    fun `schema 2 round trips typed fields and is strict about everything else`() {
        val bytes = v2Bytes(sampleEvents())
        val ok = DiagWireV2.decodeResponse(bytes, salt)
        assertEquals(WatchDiagStatus.REACHABLE, ok.status)
        assertEquals(2, ok.events.size)
        assertEquals(DiagStage.TRANSFER, ok.events[1].stage)
        assertEquals(DiagCause.TIMEOUT, ok.events[1].cause)
        assertEquals(504, ok.events[1].status)
        assertNotNull(ok.v2)
        assertEquals(DiagConn.UNVALIDATED, ok.v2!!.network.conn)
        val tampered = JSONObject(String(bytes))
        tampered.put("extra", 1)
        assertEquals(WatchDiagStatus.INVALID, DiagWireV2.decodeResponse(tampered.toString().toByteArray(), salt).status)
        assertEquals(WatchDiagStatus.INVALID, DiagWireV2.decodeResponse(bytes, "fedcba9876543210").status)
        val badWord = String(bytes).replace("\"transfer\"", "\"SECRET-STAGE\"")
        assertEquals(WatchDiagStatus.INVALID, DiagWireV2.decodeResponse(badWord.toByteArray(), salt).status)
        assertEquals(WatchDiagStatus.INVALID, DiagWireV2.decodeResponse("{\"schema\":2}".toByteArray(), salt).status)
        assertEquals(WatchDiagStatus.TOO_LARGE, DiagWireV2.decodeResponse(ByteArray(DiagWire.MAX_BYTES + 1), salt).status)
    }

    @Test
    fun `the schema 2 answer stays within the bounds even for a full log`() {
        val log = DiagLog(capacity = 256, clock = { 9_000L })
        repeat(256) { log.record(DiagCode.REQUEST_FAILED, DiagOrigin.WATCH, "t$it", fail = DiagFail.TIMEOUT, stage = DiagStage.TRANSFER, cause = DiagCause.TIMEOUT, modality = DiagModality.VOICE, status = 99_999, ms = 7L * 24 * 3_600_000) }
        val bytes = v2Bytes(log.snapshot(), log)
        assertTrue("size ${bytes.size}", bytes.size <= DiagWire.MAX_BYTES)
        assertEquals(WatchDiagStatus.REACHABLE, DiagWireV2.decodeResponse(bytes, salt).status)
    }

    @Test
    fun `the schema 1 answer is unchanged for an older Phone and drops the newer kinds`() {
        val v1 = DiagWire.encodeResponse(salt, sampleEvents(), 0, 10_000L)
        val decoded = DiagWire.decodeResponse(v1, salt)
        assertEquals(WatchDiagStatus.REACHABLE, decoded.status)
        assertTrue("REQUEST_STARTED is a schema 2 kind", decoded.events.none { it.code == DiagCode.REQUEST_STARTED })
        assertFalse(String(v1).contains("stage") || String(v1).contains("cause"))
        assertNull("an older Phone's strict request decoder still accepts the schema 1 request", DiagWire.decodeRequest(DiagWire.encodeRequestV2(salt)))
        assertEquals(salt, DiagWire.decodeRequest(DiagWire.encodeRequest(salt)))
    }

    @Test
    fun `a newer Phone gets a schema 2 answer from a newer Watch with one request`() = runTest {
        lateinit var client: DiagWatchClient
        var requests = 0
        client = DiagWatchClient { bytes ->
            requests += 1
            val (schema, requestSalt) = DiagWire.decodeRequestAny(bytes)!!
            client.onResponse(when (schema) {
                3 -> v3Bytes(sampleEvents())
                2 -> v2Bytes(sampleEvents())
                else -> DiagWire.encodeResponse(requestSalt, sampleEvents(), 0, 10_000L)
            })
            true
        }
        val answer = client.request(salt)
        assertEquals(WatchDiagStatus.REACHABLE, answer.status)
        assertNotNull("answered in the newer schema", answer.v2)
        assertEquals("the newest schema is the one asked first and answered", DiagWireV2.SCHEMA_3, answer.v2!!.schema)
        assertEquals(1, requests)
    }

    @Test
    fun `a newer Phone falls back to the schema 1 request when an older Watch ignores the first`() = runTest {
        val sent = mutableListOf<Int>()
        lateinit var client: DiagWatchClient
        client = DiagWatchClient { bytes ->
            // An older Watch reads only the schema 1 request and ignores anything else.
            val v1 = DiagWire.decodeRequest(bytes)
            sent += if (v1 != null) 1 else DiagWire.decodeRequestAny(bytes)!!.first
            if (v1 != null) client.onResponse(DiagWire.encodeResponse(v1, sampleEvents(), 0, 10_000L))
            true
        }
        val result = kotlinx.coroutines.CompletableDeferred<com.rumi.hermesvoice.core.diag.WatchDiag>()
        launch { result.complete(client.request(salt)) }
        runCurrent()
        assertEquals(listOf(3), sent)
        assertFalse(result.isCompleted)
        advanceTimeBy(DiagWatchClient.NEWER_WAIT_MS + 1); runCurrent()
        assertEquals(listOf(3, 2), sent)
        assertFalse(result.isCompleted)
        advanceTimeBy(DiagWatchClient.NEWER_WAIT_MS + 1); runCurrent()
        assertEquals(listOf(3, 2, 1), sent)
        val answer = result.await()
        assertEquals(WatchDiagStatus.REACHABLE, answer.status)
        assertNull("an older Watch gives no coverage or build, which the report states", answer.v2)
        val report = JSONObject(DiagReportV2.build(0L, DiagApp("0.1.19-dev", 20, DiagBuild.UNAVAILABLE), emptyList(), 0, salt,
            WireCoverage.of(DiagLog(clock = { 0 }).coverage(), DiagPersistStatus.NOT_ENABLED), DiagNetwork.UNKNOWN, answer, emptyMap()))
        assertEquals(1, report.getJSONObject("watch").getInt("schema"))
        assertFalse(report.getJSONObject("watch").has("coverage"))
    }

    @Test
    fun `labels and wire words are the closed enums`() {
        assertEquals(DiagCause.values().size, DiagCause.values().map { it.wire }.toSet().size)
        DiagStage.values().forEach { assertTrue(DiagLabels.stage(it).isNotBlank()) }
        DiagCause.values().forEach { assertTrue(DiagLabels.cause(it).isNotBlank()) }
        assertEquals(DiagCause.DISCONNECT, DiagCause.fromWire("disconnect_io"))
        assertNull(DiagCause.fromWire("anything else"))
    }
}
