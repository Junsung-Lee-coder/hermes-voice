package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.attachments.AttachmentRef
import com.rumi.hermesvoice.core.attachments.AttachmentRefs
import com.rumi.hermesvoice.core.auth.HermesBearerSession
import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.auth.InMemoryHermesTokenStore
import com.rumi.hermesvoice.core.net.HermesDashboardClient
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The first request of a received file is the legacy `GET /api/files/read` (or `/api/media` for an image): small JSON answers, with
 * a finite body bound. Its non-success answer must be read through a small peek, never whole, and its live call must end when the
 * caller is cancelled after the headers have arrived. Real OkHttp against a raw loopback server that writes the bytes itself;
 * synthetic data only. Every server latch is released and every coroutine joined in `finally`.
 */
class StoredFileLegacyReadBoundaryTest {
    private val mib = 1024L * 1024L
    private val http = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build()
    private val tokens = InMemoryHermesTokenStore(HermesBearerSession("AT-1", "RT-1", null, "basic", "jun"))
    private val servers = mutableListOf<RawServer>()

    @After fun tearDown() { servers.forEach { it.close() } }

    private class RawRequest(val method: String, val target: String, val headers: Map<String, String>)

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
                    input.readNBytes(length)
                    val request = RawRequest(start.substringBefore(' '), start.substringAfter(' ').substringBefore(' '), headers)
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

    private fun file(path: String = "attachments/big.bin"): AttachmentRef = AttachmentRefs.extract("@file:`$path`").single()

    private fun image(path: String = "/img/big.png"): AttachmentRef = AttachmentRefs.extract("@image:$path").single()

    private fun head(out: OutputStream, status: String, vararg headers: String) {
        out.write((("HTTP/1.1 $status\r\n") + headers.joinToString("") { "$it\r\n" } + "Connection: close\r\n\r\n").toByteArray())
        out.flush()
    }

    private fun json(socket: Socket, status: String, body: String, vararg extra: String) {
        val bytes = body.toByteArray()
        head(socket.getOutputStream(), status, "Content-Type: application/json", "Content-Length: ${bytes.size}", *extra)
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    private fun smallFile(socket: Socket) =
        json(socket, "200 OK", """{"data_url":"data:text/plain;base64,aGVsbG8=","mime_type":"text/plain"}""")

    /** Writes [count] bytes of a fixed filler in 64 KiB writes, pacing each; returns how many the peer took before failing. */
    private fun filler(out: OutputStream, count: Long, chunked: Boolean = false, pauseMs: Long = 2, written: AtomicLong? = null): Long {
        val buffer = ByteArray(64 * 1024) { 'e'.code.toByte() }
        var sent = 0L
        while (sent < count) {
            val n = minOf(buffer.size.toLong(), count - sent).toInt()
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

    private fun allocated(): Long {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        return bean.allThreadIds.sumOf { id -> bean.getThreadAllocatedBytes(id).coerceAtLeast(0L) }
    }

    /** Runs a refused first request whose 40 MiB error body is paced; the client may neither hold nor consume it whole. */
    private fun assertBoundedRefusal(status: String, expected: Int, chunked: Boolean, ref: AttachmentRef) {
        val sent = AtomicLong()
        val s = server { _, socket ->
            val out = socket.getOutputStream()
            if (chunked) head(out, status, "Content-Type: text/plain", "Transfer-Encoding: chunked")
            else head(out, status, "Content-Type: text/plain", "Content-Length: ${40 * mib}")
            filler(out, 40 * mib, chunked = chunked, written = sent)
            if (chunked) out.write("0\r\n\r\n".toByteArray())
        }
        val before = allocated()
        val failure = try {
            runBlocking { client(s).fetchStoredFile(ref) }
            fail("expected a server failure")
            null
        } catch (error: HermesHttpException) {
            error
        }!!
        val used = allocated() - before
        assertEquals(expected, failure.status)
        assertTrue("a short message: ${failure.message!!.length}", failure.message!!.length < 400)
        assertFalse("the body is not exported", failure.message!!.contains("eeee"))
        assertTrue("bytes allocated for a 40 MiB error body: $used", used < 12 * mib)
        Thread.sleep(500)
        assertTrue("the sender was not drained to the end: ${sent.get()} of ${40 * mib}", sent.get() < 16 * mib)
    }

    // ── a refused first request is read through a small peek ─────────────────────────────────────

    @Test
    fun `a huge error body on the first file read is not held or drained whole`() =
        assertBoundedRefusal("500 Internal Server Error", 500, chunked = false, ref = file())

    @Test
    fun `a huge chunked error body on the first file read is not held or drained whole`() =
        assertBoundedRefusal("500 Internal Server Error", 500, chunked = true, ref = file())

    @Test
    fun `a huge error body on the first image read is not held or drained whole`() =
        assertBoundedRefusal("500 Internal Server Error", 500, chunked = false, ref = image())

    @Test
    fun `a huge refusal that sends an image to the files route is bounded and the files route still answers`() {
        val sent = AtomicLong()
        val s = server { request, socket ->
            if (request.target.startsWith("/api/media")) {
                head(socket.getOutputStream(), "404 Not Found", "Content-Type: text/plain", "Content-Length: ${40 * mib}")
                filler(socket.getOutputStream(), 40 * mib, written = sent)
            } else smallFile(socket)
        }
        val before = allocated()
        val stored = runBlocking { client(s).fetchStoredFile(image()) }
        val used = allocated() - before
        assertEquals("hello", String(stored.bytes))
        assertEquals(listOf("/api/media", "/api/files/read"), s.requests.map { it.target.substringBefore('?') })
        assertTrue("bytes allocated for the refused first request: $used", used < 12 * mib)
        Thread.sleep(500)
        assertTrue("the first sender was not drained to the end: ${sent.get()}", sent.get() < 16 * mib)
    }

    // ── small answers keep their status, detail and headers ─────────────────────────────────────

    @Test
    fun `a small refusal keeps its status and the server's short detail`() {
        val s = server { _, socket -> json(socket, "404 Not Found", """{"detail":"File not found"}""") }
        try {
            runBlocking { client(s).fetchStoredFile(file()) }
            fail("expected a refusal")
        } catch (error: HermesHttpException) {
            assertEquals(404, error.status)
            assertEquals("/api/files/read failed (404): File not found", error.message)
            assertTrue(error.readResponse)
            assertNull(error.retryAfterMs)
        }
    }

    @Test
    fun `a small refusal keeps its Retry-After and a long detail is still cut to 200 characters`() {
        val long = "d".repeat(300)
        val s = server { _, socket -> json(socket, "503 Service Unavailable", """{"detail":"$long"}""", "Retry-After: 7") }
        try {
            runBlocking { client(s).fetchStoredFile(file()) }
            fail("expected a refusal")
        } catch (error: HermesHttpException) {
            assertEquals(503, error.status)
            assertEquals(7_000L, error.retryAfterMs)
            assertEquals("/api/files/read failed (503): " + "d".repeat(200), error.message)
        }
    }

    @Test
    fun `a body that is not JSON gives the status alone`() {
        val s = server { _, socket ->
            val bytes = "<html>gateway down</html>".toByteArray()
            head(socket.getOutputStream(), "502 Bad Gateway", "Content-Type: text/html", "Content-Length: ${bytes.size}")
            socket.getOutputStream().write(bytes)
        }
        try {
            runBlocking { client(s).fetchStoredFile(file()) }
            fail("expected a refusal")
        } catch (error: HermesHttpException) {
            assertEquals(502, error.status)
            assertEquals("/api/files/read failed (502)", error.message)
        }
    }

    @Test
    fun `a small file still answers and a rejected token is refreshed once`() {
        val s = server { request, socket ->
            when {
                request.target.startsWith("/auth/native/refresh") -> json(socket, "200 OK",
                    """{"access_token":"AT-2","refresh_token":"RT-2","token_type":"Bearer","expires_at":1900000000,"provider":"basic","user_id":"jun"}""")
                request.headers["authorization"] == "Bearer AT-2" -> smallFile(socket)
                else -> json(socket, "401 Unauthorized", """{"detail":"Unauthorized"}""")
            }
        }
        val stored = runBlocking { client(s).fetchStoredFile(file("attachments/hello.txt")) }
        assertEquals("hello", String(stored.bytes))
        assertEquals("text/plain", stored.mimeType)
        val reads = s.requests.filter { it.target.startsWith("/api/files/read") }
        assertEquals(listOf("Bearer AT-1", "Bearer AT-2"), reads.map { it.headers["authorization"] })
        assertEquals(1, s.requests.count { it.target.startsWith("/auth/native/refresh") })
        assertTrue(s.requests.none { it.target.contains("token", ignoreCase = true) })
        assertEquals("AT-2", tokens.load()!!.accessToken)
    }

    // ── the live call ends with the caller, wherever the first request is blocked ────────────────

    private class Stall(val reached: CountDownLatch = CountDownLatch(1), val release: CountDownLatch = CountDownLatch(1),
                        val closedByClient: CountDownLatch = CountDownLatch(1))

    /**
     * Starts the first file read against a server that parks as [park] says, cancels the caller, and requires the read to end at once
     * as a cancellation (not as a failure); the connection must then be closed by the client. The latch is released and the coroutine
     * joined in `finally` whatever happens.
     */
    private fun assertCancelEndsTheRead(stall: Stall, s: RawServer, ref: AttachmentRef) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val call: Deferred<*> = scope.async(start = CoroutineStart.UNDISPATCHED) { client(s).fetchStoredFile(ref) }
        try {
            assertTrue("the server parked the request", stall.reached.await(10, TimeUnit.SECONDS))
            Thread.sleep(300)
            call.cancel()
            val ended = withTimeoutOrNull(3_000) { call.join(); true }
            assertTrue("the cancelled read did not end within 3 s while the response body was blocked", ended == true)
            try {
                call.await()
                fail("a cancelled read cannot succeed")
            } catch (_: CancellationException) {
            } catch (error: IOException) {
                fail("the cancellation was reported as a transport failure: $error")
            }
            assertTrue("the connection was closed by the cancelled client", stall.closedByClient.await(5, TimeUnit.SECONDS))
        } finally {
            stall.release.countDown()
            val joined = withTimeoutOrNull(15_000) { call.join(); true }
            scope.cancel()
            assertTrue("the read is still running after the server released it", joined == true)
        }
    }

    private fun parked(stall: Stall, socket: Socket) {
        stall.reached.countDown()
        socket.soTimeout = 50
        val input = socket.getInputStream()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
        while (stall.release.count > 0 && System.nanoTime() < deadline) {
            val ended = try { input.read() < 0 } catch (_: java.net.SocketTimeoutException) { false } catch (_: IOException) { true }
            if (ended) {
                stall.closedByClient.countDown()
                break
            }
        }
        stall.release.await(40, TimeUnit.SECONDS)
    }

    @Test
    fun `cancelling after the headers while the first file response body is blocked ends the read as a cancellation`() {
        val stall = Stall()
        val s = server { _, socket ->
            head(socket.getOutputStream(), "200 OK", "Content-Type: application/json", "Content-Length: ${8 * mib}")
            filler(socket.getOutputStream(), 64 * 1024, pauseMs = 0)
            socket.getOutputStream().flush()
            parked(stall, socket)
        }
        assertCancelEndsTheRead(stall, s, file())
    }

    @Test
    fun `cancelling after the headers while a refused first file response body is blocked ends the read as a cancellation`() {
        val stall = Stall()
        val s = server { _, socket ->
            head(socket.getOutputStream(), "500 Internal Server Error", "Content-Type: application/json", "Content-Length: 1000")
            filler(socket.getOutputStream(), 100, pauseMs = 0)
            socket.getOutputStream().flush()
            parked(stall, socket)
        }
        assertCancelEndsTheRead(stall, s, file())
    }

    @Test
    fun `cancelling after the headers while a first image response body is blocked ends the read as a cancellation`() {
        val stall = Stall()
        val s = server { _, socket ->
            head(socket.getOutputStream(), "200 OK", "Content-Type: application/json", "Content-Length: ${8 * mib}")
            filler(socket.getOutputStream(), 64 * 1024, pauseMs = 0)
            socket.getOutputStream().flush()
            parked(stall, socket)
        }
        assertCancelEndsTheRead(stall, s, image())
    }

    @Test
    fun `cancelling before any header arrives still ends the read as a cancellation`() {
        val stall = Stall()
        val s = server { _, socket -> parked(stall, socket) }
        assertCancelEndsTheRead(stall, s, file())
    }
}
