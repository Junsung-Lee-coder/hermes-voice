package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.attachments.AttachmentRef
import com.rumi.hermesvoice.core.attachments.AttachmentRefs
import com.rumi.hermesvoice.core.auth.HermesBearerSession
import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.auth.InMemoryHermesTokenStore
import com.rumi.hermesvoice.core.net.HermesDashboardClient
import com.rumi.hermesvoice.core.net.StoredDownloadUnavailableException
import com.rumi.hermesvoice.core.net.StoredFileSource
import com.rumi.hermesvoice.core.net.StoredFileTooLargeException
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A received file of up to 100 MiB is streamed from the dashboard's existing authenticated `GET /api/files/download` straight to a
 * file the caller owns. Real OkHttp against a raw loopback HTTP server that writes the bytes itself (so framing, truncation and
 * stalls are exactly what the test says); synthetic data only. Memory is measured as the bytes allocated by the whole process
 * during the transfer, which must not follow the wire size.
 */
class StoredDownloadCoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val mib = 1024L * 1024L
    private val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
    private val tokens = InMemoryHermesTokenStore(HermesBearerSession("AT-1", "RT-1", null, "basic", "jun"))
    private val servers = mutableListOf<RawServer>()

    @After fun tearDown() { servers.forEach { it.close() } }

    private class RawRequest(val method: String, val target: String, val headers: Map<String, String>, val body: String)

    private class RawServer(private val handler: (RawRequest, Socket) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val requests: MutableList<RawRequest> = Collections.synchronizedList(mutableListOf())
        val baseUrl = "http://127.0.0.1:${server.localPort}/"
        @Volatile private var closed = false

        init {
            Thread {
                while (!closed) {
                    val socket = try { server.accept() } catch (_: IOException) { break }
                    Thread { serve(socket) }.apply { isDaemon = true; start() }
                }
            }.apply { isDaemon = true; start() }
        }

        private fun readLine(input: InputStream): String? {
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (line.isEmpty()) null else line.toString()
                if (b == '\n'.code) return line.toString().trimEnd('\r')
                line.append(b.toChar())
            }
        }

        private fun serve(socket: Socket) {
            try {
                socket.use {
                    val input = BufferedInputStream(it.getInputStream())
                    val start = readLine(input) ?: return
                    val headers = LinkedHashMap<String, String>()
                    while (true) {
                        val line = readLine(input) ?: return
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
                    }
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = String(input.readNBytes(length), Charsets.UTF_8)
                    val request = RawRequest(start.substringBefore(' '), start.substringAfter(' ').substringBefore(' '), headers, body)
                    requests += request
                    handler(request, it)
                }
            } catch (_: IOException) {
            }
        }

        override fun close() {
            closed = true
            runCatching { server.close() }
        }
    }

    private fun server(handler: (RawRequest, Socket) -> Unit) = RawServer(handler).also { servers += it }

    private fun client(server: RawServer) = HermesDashboardClient(HermesDashboardEndpoint.parse(server.baseUrl), http, tokens)

    private fun ref(path: String = "attachments/big.bin"): AttachmentRef = AttachmentRefs.extract("@file:`$path`").single()

    private fun target(): File = File(temp.root, "target-${System.nanoTime()}.bin")

    private fun head(out: OutputStream, status: String, vararg headers: String) {
        out.write((("HTTP/1.1 $status\r\n") + headers.joinToString("") { "$it\r\n" } + "Connection: close\r\n\r\n").toByteArray())
        out.flush()
    }

    private fun json(socket: Socket, status: String, body: String) {
        val bytes = body.toByteArray()
        head(socket.getOutputStream(), status, "Content-Type: application/json", "Content-Length: ${bytes.size}")
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    private fun patternByte(index: Long): Byte = ((index * 31 + (index shr 8) + (index shr 20)) and 0xFF).toByte()

    /** Writes [count] pattern bytes from [from] with one reused buffer; returns how many the peer took before failing. */
    private fun pattern(out: OutputStream, from: Long, count: Long, chunked: Boolean = false, pauseMs: Long = 0, written: AtomicLong? = null): Long {
        val buffer = ByteArray(64 * 1024)
        var sent = 0L
        while (sent < count) {
            val n = minOf(buffer.size.toLong(), count - sent).toInt()
            for (i in 0 until n) buffer[i] = patternByte(from + sent + i)
            try {
                if (chunked) out.write(Integer.toHexString(n).toByteArray() + "\r\n".toByteArray())
                out.write(buffer, 0, n)
                if (chunked) out.write("\r\n".toByteArray())
            } catch (_: IOException) {
                return sent
            }
            sent += n
            written?.addAndGet(n.toLong())
            if (pauseMs > 0) Thread.sleep(pauseMs)
        }
        return sent
    }

    private fun assertPattern(file: File, size: Long) {
        assertEquals("file length", size, file.length())
        file.inputStream().buffered(64 * 1024).use { input ->
            val buffer = ByteArray(64 * 1024)
            var index = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                for (i in 0 until n) if (buffer[i] != patternByte(index + i)) fail("byte ${index + i} differs")
                index += n
            }
            assertEquals(size, index)
        }
    }

    private fun allocated(): Long {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        return bean.allThreadIds.sumOf { id -> bean.getThreadAllocatedBytes(id).coerceAtLeast(0L) }
    }

    private fun fixedOk(socket: Socket, size: Long, vararg extra: String) {
        head(socket.getOutputStream(), "200 OK", "Content-Type: application/octet-stream", "Content-Length: $size", *extra)
        pattern(socket.getOutputStream(), 0, size)
        socket.getOutputStream().flush()
    }

    // ── what the request is ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a declared-length download is written exactly, with only the bearer header and the path in the request`() = runBlocking {
        val size = 3 * mib + 17
        val s = server { _, socket -> fixedOk(socket, size, "Content-Disposition: attachment; filename=\"ignored-name.bin\"") }
        val out = target()
        val done = client(s).downloadStoredFile(ref("attachments/Q3 report.bin"), out)
        assertPattern(out, size)
        assertEquals(size, done.size)
        assertEquals("application/octet-stream", done.mimeType)
        val request = s.requests.single()
        assertEquals("GET", request.method)
        assertEquals("/api/files/download", request.target.substringBefore('?'))
        assertEquals("path=attachments/Q3 report.bin", java.net.URLDecoder.decode(request.target.substringAfter('?'), "UTF-8"))
        assertEquals("Bearer AT-1", request.headers["authorization"])
        assertEquals("identity", request.headers["accept-encoding"])
        assertFalse("no secret or token in the URL", request.target.contains("token", ignoreCase = true) || request.target.contains("AT-1"))
    }

    @Test
    fun `the type is the server's claim without parameters and the sender's file name is the reference's own`() = runBlocking {
        val s = server { _, socket ->
            head(socket.getOutputStream(), "200 OK", "Content-Type: Application/PDF; charset=binary", "Content-Length: 5",
                "Content-Disposition: attachment; filename=\"../../evil.sh\"")
            socket.getOutputStream().write("%PDF-".toByteArray())
        }
        val out = target()
        val done = client(s).downloadStoredFile(ref("attachments/doc.pdf"), out)
        assertEquals("application/pdf", done.mimeType)
        assertEquals("doc.pdf", done.name)
        assertEquals("%PDF-", out.readText())
    }

    @Test
    fun `a target that already holds bytes is replaced, not appended to`() = runBlocking {
        val s = server { _, socket -> fixedOk(socket, 1000) }
        val out = target().also { it.writeText("stale content that must not survive") }
        client(s).downloadStoredFile(ref(), out)
        assertPattern(out, 1000)
    }

    // ── framing ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a body of unknown length ends at the end of the stream`() = runBlocking {
        val size = 2 * mib + 5
        val s = server { _, socket ->
            head(socket.getOutputStream(), "200 OK", "Content-Type: application/octet-stream")
            pattern(socket.getOutputStream(), 0, size)
        }
        val out = target()
        val done = client(s).downloadStoredFile(ref(), out)
        assertPattern(out, size)
        assertEquals(size, done.size)
    }

    @Test
    fun `a chunked body is reassembled`() = runBlocking {
        val size = 2 * mib + 5
        val s = server { _, socket ->
            head(socket.getOutputStream(), "200 OK", "Content-Type: application/octet-stream", "Transfer-Encoding: chunked")
            pattern(socket.getOutputStream(), 0, size, chunked = true)
            socket.getOutputStream().write("0\r\n\r\n".toByteArray())
        }
        val out = target()
        client(s).downloadStoredFile(ref(), out)
        assertPattern(out, size)
    }

    @Test
    fun `a body cut short of its declared length is a failure and leaves no file`() {
        val s = server { _, socket ->
            head(socket.getOutputStream(), "200 OK", "Content-Type: application/octet-stream", "Content-Length: 1000000")
            pattern(socket.getOutputStream(), 0, 400_000)
        }
        val out = target()
        try {
            runBlocking { client(s).downloadStoredFile(ref(), out) }
            fail("a truncated body must not be accepted as the file")
        } catch (_: IOException) {
        }
        assertFalse(out.exists())
    }

    @Test
    fun `a chunked body that never ends properly is a failure and leaves no file`() {
        val s = server { _, socket ->
            head(socket.getOutputStream(), "200 OK", "Content-Type: application/octet-stream", "Transfer-Encoding: chunked")
            pattern(socket.getOutputStream(), 0, 300_000, chunked = true)
        }
        val out = target()
        try {
            runBlocking { client(s).downloadStoredFile(ref(), out) }
            fail("an unterminated chunked body must not be accepted as the file")
        } catch (_: IOException) {
        }
        assertFalse(out.exists())
    }

    @Test
    fun `malformed chunk framing is a failure and leaves no file`() {
        val s = server { _, socket ->
            head(socket.getOutputStream(), "200 OK", "Content-Type: application/octet-stream", "Transfer-Encoding: chunked")
            socket.getOutputStream().write("zz-not-hex\r\nabc\r\n0\r\n\r\n".toByteArray())
        }
        val out = target()
        try {
            runBlocking { client(s).downloadStoredFile(ref(), out) }
            fail("malformed framing must not be accepted as the file")
        } catch (_: IOException) {
        }
        assertFalse(out.exists())
    }

    @Test
    fun `an unreadable status line is a failure and leaves no file`() {
        val s = server { _, socket -> socket.getOutputStream().write("NOT-HTTP garbage\r\n\r\n".toByteArray()) }
        val out = target()
        try {
            runBlocking { client(s).downloadStoredFile(ref(), out) }
            fail("garbage must not be accepted as the file")
        } catch (_: IOException) {
        }
        assertFalse(out.exists())
    }

    @Test
    fun `an empty file is refused and leaves no file`() {
        val s = server { _, socket -> fixedOk(socket, 0) }
        val out = target()
        try {
            runBlocking { client(s).downloadStoredFile(ref(), out) }
            fail("an empty body is not a file")
        } catch (error: HermesProtocolException) {
            assertTrue(error.message!!.contains("empty"))
        }
        assertFalse(out.exists())
    }

    @Test
    fun `only a plain 200 is a file`() {
        for (status in listOf("204 No Content", "206 Partial Content")) {
            val s = server { _, socket ->
                head(socket.getOutputStream(), status, "Content-Type: application/octet-stream", "Content-Length: 3")
                socket.getOutputStream().write("abc".toByteArray())
            }
            val out = target()
            try {
                runBlocking { client(s).downloadStoredFile(ref(), out) }
                fail("$status must not be accepted as the file")
            } catch (_: Exception) {
            }
            assertFalse(status, out.exists())
        }
    }

    // ── limits ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a declared length above 100 MiB is refused before any of the body is read`() {
        val limit = StoredFileSource.MAX_DOWNLOAD_BYTES
        assertEquals(100L * mib, limit)
        val sent = AtomicLong()
        val s = server { _, socket ->
            head(socket.getOutputStream(), "200 OK", "Content-Type: application/octet-stream", "Content-Length: ${limit + 1}")
            pattern(socket.getOutputStream(), 0, limit + 1, written = sent)
        }
        val out = target()
        try {
            runBlocking { client(s).downloadStoredFile(ref(), out) }
            fail("expected the 100 MiB refusal")
        } catch (error: StoredFileTooLargeException) {
            assertEquals(limit, error.limitBytes)
            assertTrue(error.message!!, error.message!!.contains("100 MiB"))
        }
        assertFalse(out.exists())
        Thread.sleep(300)
        assertTrue("the transfer was abandoned, not read to the end: ${sent.get()}", sent.get() < 40 * mib)
    }

    @Test
    fun `a body without a length that outgrows 100 MiB is stopped at the limit and leaves no file`() {
        val sent = AtomicLong()
        val s = server { _, socket ->
            head(socket.getOutputStream(), "200 OK", "Content-Type: application/octet-stream", "Transfer-Encoding: chunked")
            pattern(socket.getOutputStream(), 0, 400 * mib, chunked = true, written = sent)
        }
        val out = target()
        try {
            runBlocking { client(s).downloadStoredFile(ref(), out) }
            fail("expected the 100 MiB refusal")
        } catch (error: StoredFileTooLargeException) {
            assertEquals(StoredFileSource.MAX_DOWNLOAD_BYTES, error.limitBytes)
        }
        assertFalse(out.exists())
        Thread.sleep(300)
        assertTrue("the sender was cut off near the limit: ${sent.get()}", sent.get() < 200 * mib)
    }

    @Test
    fun `exactly 100 MiB is accepted and the memory used does not follow the wire size`() = runBlocking {
        val size = StoredFileSource.MAX_DOWNLOAD_BYTES
        val s = server { _, socket -> fixedOk(socket, size) }
        val out = target()
        val before = allocated()
        val done = client(s).downloadStoredFile(ref(), out)
        val used = allocated() - before
        assertPattern(out, size)
        assertEquals(size, done.size)
        assertTrue("bytes allocated while moving 100 MiB: $used", used < 60 * mib)
    }

    // ── answers that are not a file ──────────────────────────────────────────────────────────────

    @Test
    fun `the managed refusals are reported by status and never written as a file`() {
        val cases = listOf(
            Triple("404 Not Found", """{"detail":"File not found"}""", 404),
            Triple("403 Forbidden", """{"detail":"Access to this file is denied"}""", 403),
            Triple("400 Bad Request", """{"detail":"Path is not a file"}""", 400),
            Triple("409 Conflict", """{"detail":"File is in use"}""", 409),
        )
        for ((status, body, code) in cases) {
            val s = server { _, socket -> json(socket, status, body) }
            val out = target()
            try {
                runBlocking { client(s).downloadStoredFile(ref(), out) }
                fail("$status must not be accepted")
            } catch (error: HermesHttpException) {
                assertEquals(code, error.status)
                assertTrue(error.message!!.contains(body.substringAfter(":\"").substringBefore('"')))
            }
            assertFalse(status, out.exists())
        }
    }

    @Test
    fun `the managed 413 is the 100 MiB limit`() {
        val s = server { _, socket -> json(socket, "413 Payload Too Large", """{"detail":"File is too large"}""") }
        val out = target()
        try {
            runBlocking { client(s).downloadStoredFile(ref(), out) }
            fail("expected the limit")
        } catch (error: StoredFileTooLargeException) {
            assertEquals(StoredFileSource.MAX_DOWNLOAD_BYTES, error.limitBytes)
        }
        assertFalse(out.exists())
    }

    @Test
    fun `a server without the route is told apart from a missing file`() {
        val s = server { _, socket -> json(socket, "404 Not Found", """{"detail":"Not Found"}""") }
        val out = target()
        try {
            runBlocking { client(s).downloadStoredFile(ref(), out) }
            fail("expected the route to be reported as unavailable")
        } catch (_: StoredDownloadUnavailableException) {
        }
        assertFalse(out.exists())
    }

    @Test
    fun `a huge error body is not read whole and gives a short message`() {
        val s = server { _, socket ->
            head(socket.getOutputStream(), "500 Internal Server Error", "Content-Type: text/plain", "Content-Length: ${40 * mib}")
            pattern(socket.getOutputStream(), 0, 40 * mib)
        }
        val out = target()
        val before = allocated()
        try {
            runBlocking { client(s).downloadStoredFile(ref(), out) }
            fail("expected a server failure")
        } catch (error: HermesHttpException) {
            assertEquals(500, error.status)
            assertTrue(error.message!!.length < 400)
        }
        assertTrue("the error body was bounded", allocated() - before < 12 * mib)
        assertFalse(out.exists())
    }

    // ── sign-in ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a rejected token is refreshed once and the download repeats with the new token only`() = runBlocking {
        val size = mib + 3
        val s = server { request, socket ->
            when {
                request.target.startsWith("/auth/native/refresh") -> json(socket, "200 OK",
                    """{"access_token":"AT-2","refresh_token":"RT-2","token_type":"Bearer","expires_at":1900000000,"provider":"basic","user_id":"jun"}""")
                request.headers["authorization"] == "Bearer AT-2" -> fixedOk(socket, size)
                else -> json(socket, "401 Unauthorized", """{"detail":"Unauthorized"}""")
            }
        }
        val out = target()
        client(s).downloadStoredFile(ref(), out)
        assertPattern(out, size)
        val downloads = s.requests.filter { it.target.startsWith("/api/files/download") }
        assertEquals(listOf("Bearer AT-1", "Bearer AT-2"), downloads.map { it.headers["authorization"] })
        assertEquals(1, s.requests.count { it.target.startsWith("/auth/native/refresh") })
        assertTrue(s.requests.none { it.target.contains("token", ignoreCase = true) && it.target.startsWith("/api/") })
        assertEquals("AT-2", tokens.load()!!.accessToken)
    }

    @Test
    fun `a second rejection after the refresh asks for sign-in and leaves no file`() {
        val s = server { request, socket ->
            if (request.target.startsWith("/auth/native/refresh")) json(socket, "200 OK",
                """{"access_token":"AT-2","refresh_token":"RT-2","token_type":"Bearer","expires_at":1900000000,"provider":"basic","user_id":"jun"}""")
            else json(socket, "401 Unauthorized", """{"detail":"Unauthorized"}""")
        }
        val out = target()
        try {
            runBlocking { client(s).downloadStoredFile(ref(), out) }
            fail("expected sign-in required")
        } catch (_: HermesAuthRequiredException) {
        }
        assertEquals(2, s.requests.count { it.target.startsWith("/api/files/download") })
        assertFalse(out.exists())
    }

    @Test
    fun `a signed-out client sends nothing`() {
        tokens.clear()
        val s = server { _, socket -> fixedOk(socket, 10) }
        val out = target()
        try {
            runBlocking { client(s).downloadStoredFile(ref(), out) }
            fail("expected sign-in required")
        } catch (_: HermesAuthRequiredException) {
        }
        assertTrue(s.requests.isEmpty())
        assertFalse(out.exists())
    }

    @Test
    fun `the bearer token is not sent to another origin by a redirect`() {
        val other = server { _, socket -> fixedOk(socket, 10) }
        val first = server { _, socket -> head(socket.getOutputStream(), "302 Found", "Location: ${other.baseUrl}elsewhere", "Content-Length: 0") }
        val out = target()
        runCatching { runBlocking { client(first).downloadStoredFile(ref(), out) } }
        assertEquals("Bearer AT-1", first.requests.single().headers["authorization"])
        assertTrue("the other origin was asked", other.requests.isNotEmpty())
        assertNull(other.requests.first().headers["authorization"])
    }

    // ── cancellation ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `cancelling while the server stalls in the middle of the body ends the read at once, closes the call and leaves no file`() = runBlocking {
        val stalled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closedByClient = CountDownLatch(1)
        val s = server { _, socket ->
            head(socket.getOutputStream(), "200 OK", "Content-Type: application/octet-stream", "Content-Length: ${50 * mib}")
            pattern(socket.getOutputStream(), 0, 256 * 1024)
            socket.getOutputStream().flush()
            stalled.countDown()
            // The client is silent while it reads, so its close shows as end-of-input on the server's side.
            val ended = try { socket.getInputStream().read() < 0 } catch (_: IOException) { true }
            if (ended) closedByClient.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
        val out = target()
        val job = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { client(s).downloadStoredFile(ref(), out) }
        assertTrue("the server stalled after the first bytes", stalled.await(10, TimeUnit.SECONDS))
        Thread.sleep(300)
        withTimeout(5_000) { job.cancel(); job.join() }
        assertTrue(job.isCancelled)
        assertTrue("the connection was closed by the cancelled client", closedByClient.await(5, TimeUnit.SECONDS))
        assertFalse("the partial file is removed", out.exists())
        release.countDown()
    }

    @Test
    fun `cancelling while bytes keep arriving and being written stops the transfer early and leaves no file`() = runBlocking {
        val sent = AtomicLong()
        val s = server { _, socket ->
            head(socket.getOutputStream(), "200 OK", "Content-Type: application/octet-stream", "Content-Length: ${100 * mib}")
            pattern(socket.getOutputStream(), 0, 100 * mib, pauseMs = 2, written = sent)
        }
        val out = target()
        val job = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { client(s).downloadStoredFile(ref(), out) }
        val end = System.nanoTime() + 10_000_000_000L
        while (sent.get() < 2 * mib && System.nanoTime() < end) Thread.sleep(10)
        assertTrue("bytes were flowing", sent.get() >= 2 * mib)
        withTimeout(5_000) { job.cancel(); job.join() }
        assertTrue(job.isCancelled)
        assertFalse("the partial file is removed", out.exists())
        Thread.sleep(300)
        assertTrue("the sender did not finish: ${sent.get()}", sent.get() < 90 * mib)
    }

    // ── the small-file route keeps its limit, and says the raw one ───────────────────────────────

    @Test
    fun `a stored file above the preview limit is refused with the raw preview limit, not the transport size`() {
        val fake = FakeHermesDashboard()
        try {
            val client = HermesDashboardClient(HermesDashboardEndpoint.parse(fake.baseUrl), http, tokens)
            fake.storedFiles["attachments/seventeen.bin"] = "application/octet-stream" to ByteArray(17 * 1024 * 1024)
            for (path in listOf("attachments/seventeen.bin")) {
                try {
                    runBlocking { client.fetchStoredFile(AttachmentRefs.extract("@file:$path").single()) }
                    fail("expected the preview limit")
                } catch (error: StoredFileTooLargeException) {
                    assertEquals(StoredFileSource.MAX_BYTES.toLong(), error.limitBytes)
                    assertTrue(error.message!!, error.message!!.contains("16 MiB"))
                    assertFalse(error.message!!, error.message!!.contains("21 MiB"))
                }
            }
            assertEquals(listOf("/api/files/read?path=attachments/seventeen.bin"), fake.storedFileRequests.map { it.first })
        } finally {
            fake.close()
        }
    }

    @Test
    fun `an image just above the preview limit is refused with the preview limit before it is decoded`() {
        val fake = FakeHermesDashboard()
        try {
            val client = HermesDashboardClient(HermesDashboardEndpoint.parse(fake.baseUrl), http, tokens)
            fake.storedFiles["/img/large.png"] = "image/png" to ByteArray(StoredFileSource.MAX_BYTES + 1024)
            try {
                runBlocking { client.fetchStoredFile(AttachmentRefs.extract("@image:/img/large.png").single()) }
                fail("expected the preview limit")
            } catch (error: StoredFileTooLargeException) {
                assertEquals(StoredFileSource.MAX_BYTES.toLong(), error.limitBytes)
                assertTrue(error.message!!.contains("16 MiB"))
            }
        } finally {
            fake.close()
        }
    }

    @Test
    fun `the media route's own 413 is the preview limit and the files route's own 413 is the 100 MiB limit`() {
        val fake = FakeHermesDashboard()
        try {
            val client = HermesDashboardClient(HermesDashboardEndpoint.parse(fake.baseUrl), http, tokens)
            fake.storedFileStatus = 413
            try {
                runBlocking { client.fetchStoredFile(AttachmentRefs.extract("@image:/img/huge.png").single()) }
                fail("expected a limit")
            } catch (error: StoredFileTooLargeException) {
                assertEquals(StoredFileSource.MAX_BYTES.toLong(), error.limitBytes)
            }
            try {
                runBlocking { client.fetchStoredFile(AttachmentRefs.extract("@file:attachments/huge.bin").single()) }
                fail("expected a limit")
            } catch (error: StoredFileTooLargeException) {
                assertEquals(StoredFileSource.MAX_DOWNLOAD_BYTES, error.limitBytes)
                assertTrue(error.message!!.contains("100 MiB"))
            }
            assertEquals(listOf("/api/media?path=/img/huge.png", "/api/files/read?path=attachments/huge.bin"), fake.storedFileRequests.map { it.first })
        } finally {
            fake.close()
        }
    }

    @Test
    fun `the dashboard contract double serves the download route with the same bytes and bearer rules`() = runBlocking {
        val fake = FakeHermesDashboard()
        try {
            val client = HermesDashboardClient(HermesDashboardEndpoint.parse(fake.baseUrl), http, tokens)
            val bytes = ByteArray(300_000) { patternByte(it.toLong()) }
            fake.storedFiles["attachments/doc.bin"] = "application/octet-stream" to bytes
            val out = target()
            tokens.save(HermesBearerSession(fake.accessToken, fake.refreshToken, null, "basic", "jun"))
            client.downloadStoredFile(ref("attachments/doc.bin"), out)
            assertPattern(out, bytes.size.toLong())
            assertNotNull(fake.storedFileRequests.single().second)
            assertEquals("/api/files/download?path=attachments/doc.bin", fake.storedFileRequests.single().first)
        } finally {
            fake.close()
        }
    }

    // Owner test-only falsifier of Y-F1. Not a product edit or an accepted-candidate test migration.
    private fun ownerCancelDuringRefusal(status: Int) {
        runBlocking {
            val bodyReadStarted = CountDownLatch(1)
            val closedByClient = CountDownLatch(1)
            val release = CountDownLatch(1)
            val s = server { _, socket ->
                head(socket.getOutputStream(), "$status Refused", "Content-Type: application/json", "Content-Length: 100000")
                // OkHttp 4.12 responseBodyStart reports first data, not readiness to read.
                socket.getOutputStream().write(123)
                socket.getOutputStream().flush()
                socket.soTimeout = 10000
                val ended = try { socket.getInputStream().read() < 0 } catch (_: IOException) { true }
                if (ended) closedByClient.countDown()
                release.await(20, TimeUnit.SECONDS)
            }
            val observed = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val boundedHttp = http.newBuilder().eventListener(object : okhttp3.EventListener() {
                override fun responseBodyStart(call: okhttp3.Call) { bodyReadStarted.countDown() }
            }).build()
            val api = HermesDashboardClient(HermesDashboardEndpoint.parse(s.baseUrl), boundedHttp, tokens)
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
            val out = target().also { it.writeBytes(byteArrayOf(1, 2, 3)) }
            val call = scope.async(start = CoroutineStart.UNDISPATCHED) {
                try { api.downloadStoredFile(ref(), out) }
                catch (error: Throwable) { observed.set(error); throw error }
            }
            try {
                assertTrue("the actual refusal body read started", bodyReadStarted.await(10, TimeUnit.SECONDS))
                call.cancel()
                withTimeout(5000) { call.join() }
                assertTrue("the client closed the blocked refusal socket", closedByClient.await(5, TimeUnit.SECONDS))
                assertFalse("the caller-owned partial is removed", out.exists())
                assertEquals("no follow-up or resubmission", 1, s.requests.size)
                assertTrue("CANCELLED_BINARY_REFUSAL_MUST_REMAIN_CANCELLATION actual=" + observed.get()?.javaClass?.name,
                    observed.get() is java.util.concurrent.CancellationException)
                try { call.await(); fail("a cancelled download cannot succeed") }
                catch (_: java.util.concurrent.CancellationException) { }
            } finally {
                release.countDown()
                call.cancel()
                withTimeout(15000) { call.join() }
                scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
        }
    }

    @Test fun ownerRefusalCancellation403() { ownerCancelDuringRefusal(403) }
    @Test fun ownerRefusalCancellation404() { ownerCancelDuringRefusal(404) }
    @Test fun ownerRefusalCancellation503() { ownerCancelDuringRefusal(503) }
    @Test fun ownerRefusalControl503() {
        val s = server { _, socket -> json(socket, "503 Refused", """{"detail":"wait"}""") }
        val out = target()
        try {
            runBlocking { client(s).downloadStoredFile(ref(), out) }
            fail("the ordinary noncancelled refusal must fail")
        } catch (error: HermesHttpException) {
            assertEquals(503, error.status)
            assertTrue(error.readResponse)
            assertEquals(1, s.requests.size)
            assertFalse(out.exists())
        }
    }
}
