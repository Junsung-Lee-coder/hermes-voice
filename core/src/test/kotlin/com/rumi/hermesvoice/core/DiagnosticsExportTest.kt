package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.diag.DiagCode
import com.rumi.hermesvoice.core.diag.DiagEvent
import com.rumi.hermesvoice.core.diag.DiagExportResult
import com.rumi.hermesvoice.core.diag.DiagExporter
import com.rumi.hermesvoice.core.diag.DiagFail
import com.rumi.hermesvoice.core.diag.DiagFileStore
import com.rumi.hermesvoice.core.diag.DiagLog
import com.rumi.hermesvoice.core.diag.DiagOrigin
import com.rumi.hermesvoice.core.diag.DiagPseudonyms
import com.rumi.hermesvoice.core.diag.DiagReport
import com.rumi.hermesvoice.core.diag.DiagShare
import com.rumi.hermesvoice.core.diag.DiagVoiceListener
import com.rumi.hermesvoice.core.diag.DiagWatchClient
import com.rumi.hermesvoice.core.diag.DiagWire
import com.rumi.hermesvoice.core.diag.WatchDiag
import com.rumi.hermesvoice.core.diag.WatchDiagStatus
import com.rumi.hermesvoice.core.voice.VoiceTurnListener
import com.rumi.hermesvoice.core.voice.VoiceTurnOutcome
import com.rumi.hermesvoice.core.voice.VoiceTurnStage
import java.io.File
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The private diagnostics export, off-device: what a report can contain (nothing but whitelisted codes, small numbers and
 * per-export pseudonyms), how the Watch's part is bounded and checked, one export at a time, retention and what may be shared.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsExportTest {
    @get:Rule val tmp = TemporaryFolder()

    private val salt = "0123456789abcdef"
    private val sentinels = listOf("SECRET-TOKEN-xyz", "https://hermes.example/secret/path?key=abc", "/home/user/private", "alice@example.com",
        "192.168.1.77", "Pixel-Watch-serial-ABC123", "session-id-424242", "hello this is what the user said")

    private fun log(clock: () -> Long = { 10_000L }) = DiagLog(capacity = 8, clock = clock)

    private fun report(events: List<DiagEvent>, watch: WatchDiag = WatchDiag(WatchDiagStatus.OFFLINE), settings: Map<String, Boolean> = emptyMap()) =
        DiagReport.build(19, events, 0, 10_000, salt, watch, settings)

    @Test
    fun `a report holds only whitelisted words, numbers and pseudonyms whatever the exception or id strings say`() {
        val log = log()
        val hostile = RuntimeException(sentinels.joinToString(" | "), IllegalStateException(sentinels.joinToString()))
        log.record(DiagCode.REQUEST_FAILED, hostile, DiagOrigin.PHONE, ref = sentinels[6])
        log.record(DiagCode.SPEECH_CHUNK_FAILED, SocketTimeoutException(sentinels[0]), ref = sentinels[6], n = 3)
        val text = report(log.snapshot())
        for (sentinel in sentinels) assertFalse("report leaks: $sentinel", text.contains(sentinel))
        assertFalse(text.contains("RuntimeException") || text.contains("IllegalState"))
        val json = JSONObject(text)
        assertEquals(DiagReport.KIND, json.getString("kind"))
        val events = json.getJSONObject("phone").getJSONArray("events")
        assertEquals(2, events.length())
        assertEquals("unknown", events.getJSONObject(0).getString("fail"))
        assertEquals("timeout", events.getJSONObject(1).getString("fail"))
        val ref = events.getJSONObject(0).getString("ref")
        assertTrue(DiagPseudonyms.FORMAT.matches(ref))
        assertEquals("one request keeps one pseudonym inside one export", ref, events.getJSONObject(1).getString("ref"))
        assertNotEquals(DiagPseudonyms.of("fedcba9876543210", sentinels[6]), ref)
    }

    private fun assertNotEquals(a: Any, b: Any) = assertFalse("$a == $b", a == b)

    @Test
    fun `failure codes come from the exception class alone`() {
        assertEquals(DiagFail.TIMEOUT, DiagFail.of(SocketTimeoutException("anything")))
        assertEquals(DiagFail.DISCONNECTED, DiagFail.of(java.net.ConnectException("x")))
        assertEquals(DiagFail.CANCELLED, DiagFail.of(kotlinx.coroutines.CancellationException("x")))
        assertEquals(DiagFail.UNKNOWN, DiagFail.of(RuntimeException("SECRET-TOKEN-xyz")))
        assertEquals(DiagFail.UNKNOWN, DiagFail.of(null))
        assertEquals(DiagFail.TIMEOUT, DiagFail.ofName("SocketTimeoutException"))
        assertEquals(DiagFail.UNKNOWN, DiagFail.ofName("com.evil.Class: SECRET"))
    }

    @Test
    fun `the report carries only yes-no settings under fixed names and no raw ids or time of day`() {
        val log = log()
        log.record(DiagCode.REQUEST_ACCEPTED, DiagOrigin.WATCH, ref = "turn-1")
        val json = JSONObject(report(log.snapshot(), settings = mapOf("later_replies" to true, "voice_routing" to false)))
        assertEquals("age_ms_before_export", json.getString("time_basis"))
        assertTrue(json.getJSONObject("settings").getBoolean("later_replies"))
        assertFalse(json.toString().contains("turn-1"))
        assertEquals(setOf("kind", "schema", "app_version_code", "time_basis", "phone", "watch", "settings"), json.keys().asSequence().toSet())
    }

    @Test
    fun `the ring buffer is bounded, drops the oldest and counts what it dropped`() {
        val log = DiagLog(capacity = 3, clock = { 1L })
        repeat(10) { log.record(DiagCode.RESPONSE_PROGRESS, n = it) }
        assertEquals(listOf(7, 8, 9), log.snapshot().map { it.n })
        assertEquals(7L, log.dropped())
        log.record(DiagCode.RESPONSE_PROGRESS, n = Int.MAX_VALUE, ms = Long.MAX_VALUE)
        assertEquals(DiagLog.MAX_NUMBER, log.snapshot().last().n)
        assertEquals(DiagLog.MAX_MS, log.snapshot().last().ms)
    }

    @Test
    fun `recording is safe from many threads`() {
        val log = DiagLog(capacity = 64, clock = { 1L })
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(8)
        repeat(8) { t -> pool.execute { repeat(500) { log.record(DiagCode.PLAYBACK_DONE, n = t) }; done.countDown() } }
        assertTrue(done.await(20, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(64, log.snapshot().size)
        assertEquals(8 * 500 - 64L, log.dropped())
    }

    @Test
    fun `the voice listener records typed events and passes every call on`() {
        val delegate = object : VoiceTurnListener {
            val stages = ArrayList<VoiceTurnStage>()
            override fun onStage(turnId: String, stage: VoiceTurnStage) { stages += stage }
        }
        val log = log()
        val listener = DiagVoiceListener(delegate, log)
        listener.onAccepted("secret-turn-id", com.rumi.hermesvoice.core.VoiceOrigin.WATCH)
        listener.onStage("secret-turn-id", VoiceTurnStage.QUEUED)
        listener.onOutcome("secret-turn-id", VoiceTurnOutcome.Stopped)
        assertEquals(listOf(VoiceTurnStage.QUEUED), delegate.stages)
        assertEquals(listOf(DiagCode.REQUEST_ACCEPTED, DiagCode.REQUEST_QUEUED, DiagCode.REQUEST_STOPPED), log.snapshot().map { it.code })
        assertFalse(report(log.snapshot()).contains("secret-turn-id"))
    }

    // ---- the Watch's part

    private fun response(vararg events: String, salt: String = this.salt, extra: String = "", dropped: String = "0") =
        """{"schema":1,"salt":"$salt","dropped":$dropped,"events":[${events.joinToString(",")}]$extra}""".toByteArray()

    private val goodEvent = """{"age_ms":5,"code":"watch_sent","origin":"watch","ref":"0a1b2c3d","n":2,"ms":9,"fail":"timeout"}"""

    @Test
    fun `a well-formed Watch answer is accepted`() {
        val part = DiagWire.decodeResponse(response(goodEvent), salt)
        assertEquals(WatchDiagStatus.REACHABLE, part.status)
        assertEquals(DiagCode.WATCH_SENT, part.events.single().code)
        assertEquals(DiagFail.TIMEOUT, part.events.single().fail)
    }

    @Test
    fun `malformed, hostile and oversized Watch answers become one marker and nothing is copied`() {
        fun status(bytes: ByteArray) = DiagWire.decodeResponse(bytes, salt).status
        assertEquals(WatchDiagStatus.INVALID, status("not json".toByteArray()))
        assertEquals(WatchDiagStatus.INVALID, status(byteArrayOf(0, 1, 2, -1, -2)))
        assertEquals(WatchDiagStatus.INVALID, status(response(goodEvent, salt = "fedcba9876543210")))
        assertEquals(WatchDiagStatus.INVALID, status(response(goodEvent, extra = ""","token":"SECRET-TOKEN-xyz"""")))
        assertEquals(WatchDiagStatus.INVALID, status(response("""{"age_ms":1,"code":"free_text","ref":"0a1b2c3d"}""")))
        assertEquals(WatchDiagStatus.INVALID, status(response("""{"age_ms":1,"code":"watch_sent","text":"SECRET-TOKEN-xyz"}""")))
        assertEquals(WatchDiagStatus.INVALID, status(response("""{"age_ms":1,"code":"watch_sent","ref":"SECRET-TOKEN-xyz"}""")))
        assertEquals(WatchDiagStatus.INVALID, status(response("""{"age_ms":-1,"code":"watch_sent"}""")))
        assertEquals(WatchDiagStatus.INVALID, status(response("""{"age_ms":"1","code":"watch_sent"}""")))
        assertEquals(WatchDiagStatus.INVALID, status(response("""{"age_ms":1,"code":"watch_sent","n":2000000}""")))
        assertEquals(WatchDiagStatus.INVALID, status(response("""{"age_ms":1,"code":"watch_sent","fail":"SECRET-TOKEN-xyz"}""")))
        assertEquals(WatchDiagStatus.INVALID, status(response("5")))
        assertEquals(WatchDiagStatus.INVALID, status(response(*Array(DiagWire.MAX_EVENTS + 1) { goodEvent })))
        assertEquals(WatchDiagStatus.TOO_LARGE, status(ByteArray(DiagWire.MAX_BYTES + 1) { 'a'.code.toByte() }))
        val hostile = DiagWire.decodeResponse(response("""{"age_ms":1,"code":"watch_sent","ref":"SECRET-TOKEN-xyz"}"""), salt)
        assertTrue(hostile.events.isEmpty())
    }

    @Test
    fun `the Watch's own encoder stays inside the size and event bounds and carries only pseudonyms`() {
        val events = (0 until 500).map { DiagEvent(it.toLong(), DiagCode.RESPONSE_PROGRESS, DiagOrigin.WATCH, "turn-$it-SECRET-TOKEN-xyz", it, it.toLong(), null) }
        val bytes = DiagWire.encodeResponse(salt, events, 3, 1_000)
        assertTrue(bytes.size <= DiagWire.MAX_BYTES)
        assertFalse(String(bytes).contains("SECRET-TOKEN-xyz"))
        val part = DiagWire.decodeResponse(bytes, salt)
        assertEquals(WatchDiagStatus.REACHABLE, part.status)
        assertTrue(part.events.size <= DiagWire.MAX_EVENTS)
        assertEquals("the newest events are kept", 499, part.events.last().n)
        assertEquals(3L + (500 - part.events.size), part.dropped)
    }

    @Test
    fun `a request must be exactly schema and salt`() {
        assertEquals(salt, DiagWire.decodeRequest(DiagWire.encodeRequest(salt)))
        assertNull(DiagWire.decodeRequest("""{"schema":1,"salt":"$salt","x":1}""".toByteArray()))
        assertNull(DiagWire.decodeRequest("""{"schema":2,"salt":"$salt"}""".toByteArray()))
        assertNull(DiagWire.decodeRequest("""{"schema":1,"salt":"short"}""".toByteArray()))
        assertNull(DiagWire.decodeRequest(ByteArray(1024) { 'x'.code.toByte() }))
        assertNull(DiagWire.decodeRequest(byteArrayOf(-1, -2)))
    }

    @Test
    fun `the Watch client ignores an answer for another export and an offline Watch is a marker`() = runTest {
        var sent: ByteArray? = null
        val client = DiagWatchClient { sent = it; true }
        val answer = CompletableDeferred<WatchDiag>()
        val job = launch { answer.complete(client.request(salt)) }
        runCurrent()
        assertEquals(salt, DiagWire.decodeRequest(sent!!))
        client.onResponse(response(goodEvent, salt = "fedcba9876543210"))
        runCurrent()
        assertFalse("a foreign salt is ignored, not an error", answer.isCompleted)
        client.onResponse(response(goodEvent))
        runCurrent()
        assertEquals(WatchDiagStatus.REACHABLE, answer.await().status)
        job.join()
        assertEquals(WatchDiagStatus.OFFLINE, DiagWatchClient { false }.request(salt).status)
        assertEquals(WatchDiagStatus.OFFLINE, DiagWatchClient { throw java.io.IOException("SECRET-TOKEN-xyz") }.request(salt).status)
        client.onResponse(response(goodEvent))
    }

    // ---- the exporter

    private fun store(name: String = "diag", now: () -> Long = { System.currentTimeMillis() }) = DiagFileStore(File(tmp.root, name), now)

    @Test
    fun `without a Watch the Phone part is still exported`() = runTest {
        val log = log(); log.record(DiagCode.REQUEST_ACCEPTED, DiagOrigin.PHONE, "t")
        val exporter = DiagExporter(19, log, { mapOf("later_replies" to false) }, { WatchDiag(WatchDiagStatus.OFFLINE) }, store())
        val ready = exporter.export() as DiagExportResult.Ready
        assertEquals(WatchDiagStatus.OFFLINE, ready.watch)
        val json = JSONObject(ready.file.readText())
        assertEquals("offline", json.getJSONObject("watch").getString("status"))
        assertEquals(1, json.getJSONObject("phone").getJSONArray("events").length())
        assertTrue(DiagFileStore.NAME.matches(ready.file.name))
    }

    @Test
    fun `a Watch that never answers is a timeout marker after the bound, in virtual time`() = runTest {
        val log = log()
        val exporter = DiagExporter(19, log, { emptyMap() }, { kotlinx.coroutines.awaitCancellation() }, store(), watchTimeoutMs = 6_000)
        val result = CompletableDeferred<DiagExportResult>()
        launch { result.complete(exporter.export()) }
        runCurrent()
        advanceTimeBy(5_999); runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(2); runCurrent()
        assertEquals(WatchDiagStatus.TIMEOUT, (result.await() as DiagExportResult.Ready).watch)
    }

    @Test
    fun `a failing Watch lookup never fails the Phone part`() = runTest {
        val exporter = DiagExporter(19, log(), { emptyMap() }, { throw IllegalStateException("SECRET-TOKEN-xyz") }, store())
        val ready = exporter.export() as DiagExportResult.Ready
        assertEquals(WatchDiagStatus.OFFLINE, ready.watch)
        assertFalse(ready.file.readText().contains("SECRET-TOKEN-xyz"))
    }

    @Test
    fun `a second export while one runs is refused as busy and the first still completes`() = runTest {
        val gate = CompletableDeferred<WatchDiag>()
        val exporter = DiagExporter(19, log(), { emptyMap() }, { gate.await() }, store())
        val first = CompletableDeferred<DiagExportResult>()
        launch { first.complete(exporter.export()) }
        runCurrent()
        assertEquals(DiagExportResult.Busy, exporter.export())
        gate.complete(WatchDiag(WatchDiagStatus.OFFLINE))
        runCurrent()
        assertTrue(first.await() is DiagExportResult.Ready)
        assertTrue("a later export is allowed again", exporter.export() is DiagExportResult.Ready)
    }

    @Test
    fun `an unwritable store is a safe failure code and the next export may run`() = runTest {
        val blocker = tmp.newFile("blocked")
        val exporter = DiagExporter(19, log(), { emptyMap() }, { WatchDiag(WatchDiagStatus.OFFLINE) },
            DiagFileStore(File(blocker, "diag"), { 0L }))
        val failed = exporter.export() as DiagExportResult.Failed
        assertEquals(DiagFail.IO, failed.fail)
        assertTrue(exporter.export() is DiagExportResult.Failed)
    }

    @Test
    fun `exporting does not disturb the recorded events`() = runTest {
        val log = log(); repeat(3) { log.record(DiagCode.PLAYBACK_DONE, n = it) }
        DiagExporter(19, log, { emptyMap() }, { WatchDiag(WatchDiagStatus.OFFLINE) }, store()).export()
        assertEquals(3, log.snapshot().size)
    }

    // ---- retention and sharing

    @Test
    fun `retention keeps at most three recent files, drops the old, and touches only its own names`() {
        var now = 100L * 3_600_000
        val dir = File(tmp.root, "diag").also { it.mkdirs() }
        val store = DiagFileStore(dir, { now })
        val foreign = File(dir, "notes.txt").also { it.writeText("mine") }
        val lookalike = File(dir, "hv-diag-zzzz.json").also { it.writeText("mine") }
        val written = (0 until 5).map {
            val ok = store.write("{}") as DiagFileStore.Written.Ok
            ok.file.setLastModified(now - (5 - it) * 1_000L)
            ok.file
        }
        assertTrue(store.list().size <= DiagFileStore.KEEP)
        val old = (store.write("{}") as DiagFileStore.Written.Ok).file
        old.setLastModified(now - DiagFileStore.MAX_AGE_MS - 1)
        assertEquals(1, store.prune().coerceAtLeast(1))
        assertFalse(old.exists())
        assertTrue(foreign.exists() && lookalike.exists())
        assertTrue(store.list().size <= DiagFileStore.KEEP)
        assertTrue(written.isNotEmpty())
    }

    @Test
    fun `leftover temp files are removed by pruning`() {
        val dir = File(tmp.root, "diag").also { it.mkdirs() }
        val temp = File(dir, DiagFileStore.TEMP_PREFIX + "abc").also { it.writeText("x") }
        DiagFileStore(dir, { 0L }).prune()
        assertFalse(temp.exists())
    }

    @Test
    fun `only a report file directly in the app's diag cache folder is shareable`() {
        val cache = tmp.newFolder("cache")
        val dir = File(cache, DiagShare.CACHE_SUBDIR).also { it.mkdirs() }
        val good = (DiagFileStore(dir, { 0L }).write("{}") as DiagFileStore.Written.Ok).file
        assertTrue(DiagShare.shareable(cache, good))
        assertTrue("traversal spelling of the same file is still the same file", DiagShare.shareable(cache, File(dir, "../diag/${good.name}")))
        val other = File(cache, good.name).also { it.writeText("{}") }
        assertFalse(DiagShare.shareable(cache, other))
        val badName = File(dir, "prefs.xml").also { it.writeText("x") }
        assertFalse(DiagShare.shareable(cache, badName))
        val outside = tmp.newFile("hv-diag-0123456789abcdef.json")
        assertFalse(DiagShare.shareable(cache, outside))
        val sub = File(dir, "sub").also { it.mkdirs() }
        val nested = File(sub, good.name).also { it.writeText("{}") }
        assertFalse(DiagShare.shareable(cache, nested))
        assertFalse(DiagShare.shareable(cache, File(dir, "hv-diag-ffffffffffffffff.json")))
        val link = File(dir, "hv-diag-aaaaaaaaaaaaaaaa.json")
        val linked = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()); true }.getOrDefault(false)
        if (linked) assertFalse("a link to a file outside is not shareable", DiagShare.shareable(cache, link))
        assertEquals("application/json", DiagShare.CONTENT_TYPE)
        assertEquals("com.rumi.hermesvoice.phone.diagshare", DiagShare.authority("com.rumi.hermesvoice.phone"))
        assertNotNull(DiagShare.authority("x"))
    }

    @Test
    fun `the consent text names what is and is not included and mentions the Watch`() {
        val included = DiagReport.CONTENT_LINES.joinToString(" ")
        assertTrue(included.contains("Watch") && included.contains("offline") && included.contains("pseudonyms"))
        val excluded = DiagReport.EXCLUDED_LINES.joinToString(" ")
        for (word in listOf("audio", "what you said", "replies", "addresses", "tokens", "device names or ids", "error messages")) {
            assertTrue("excluded text names: $word", excluded.contains(word))
        }
    }
}
