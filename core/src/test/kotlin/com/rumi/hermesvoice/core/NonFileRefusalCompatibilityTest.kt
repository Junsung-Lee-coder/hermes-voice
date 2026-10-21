package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.attachments.AttachmentRefs
import com.rumi.hermesvoice.core.auth.HermesBearerSession
import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.auth.InMemoryHermesTokenStore
import com.rumi.hermesvoice.core.net.ArchivedFilter
import com.rumi.hermesvoice.core.net.HermesDashboardClient
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A refusal of an ordinary (non-file) dashboard request whose body cannot be read to its end is still the refusal the server sent:
 * the status, the applicable Retry-After and `readResponse`, and the fixed speech messages survive, and nothing is repeated. The
 * fault is a real one: the raw loopback server declares a longer body than it sends and closes, so reading the body throws an
 * IOException in OkHttp itself. Real client, real OkHttp; synthetic data only.
 */
class NonFileRefusalCompatibilityTest {
    private val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
    private val tokens = InMemoryHermesTokenStore(HermesBearerSession("AT-1", "RT-1", null, "basic", "jun"))
    private val servers = mutableListOf<RawServer>()

    @After fun tearDown() { servers.forEach { it.close() } }

    private class RawRequest(val method: String, val target: String)

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
                    var length = 0
                    while (true) {
                        val line = readLine(input) ?: return
                        if (line.isEmpty()) break
                        if (line.substringBefore(':').trim().equals("content-length", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                    }
                    input.readNBytes(length)
                    val request = RawRequest(start.substringBefore(' '), start.substringAfter(' ').substringBefore(' '))
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

    private fun head(socket: Socket, status: String, vararg headers: String) {
        socket.getOutputStream().write((("HTTP/1.1 $status\r\n") + headers.joinToString("") { "$it\r\n" } + "Connection: close\r\n\r\n").toByteArray())
        socket.getOutputStream().flush()
    }

    /** Declares 1000 body bytes, sends 30 and closes: reading the body to its end throws. */
    private fun truncated(socket: Socket, status: String, vararg extra: String) {
        head(socket, status, "Content-Type: application/json", "Content-Length: 1000", *extra)
        socket.getOutputStream().write("""{"detail":"half a body, secret""".toByteArray())
        socket.getOutputStream().flush()
    }

    private fun complete(socket: Socket, status: String, body: String, vararg extra: String) {
        val bytes = body.toByteArray()
        head(socket, status, "Content-Type: application/json", "Content-Length: ${bytes.size}", *extra)
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    private inline fun refusal(block: () -> Unit): HermesHttpException = try {
        block()
        fail("expected the server's refusal")
        throw IllegalStateException()
    } catch (error: HermesHttpException) {
        error
    }

    @Test
    fun `the fixture really fails the body read with an IOException`() {
        val s = server { _, socket -> truncated(socket, "503 Service Unavailable") }
        val request = Request.Builder().url(s.baseUrl + "api/sessions").build()
        http.newCall(request).execute().use { response ->
            assertEquals(503, response.code)
            try {
                response.body!!.string()
                fail("the body read must fail")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun `a read refusal whose body fails keeps its status, Retry-After and read flag and is not repeated`() {
        val s = server { _, socket -> truncated(socket, "503 Service Unavailable", "Retry-After: 7") }
        val error = refusal { runBlocking { client(s).listSessions(null, ArchivedFilter.EXCLUDE) } }
        assertEquals(503, error.status)
        assertEquals("/api/sessions failed (503)", error.message)
        assertEquals(7_000L, error.retryAfterMs)
        assertTrue(error.readResponse)
        assertNull(error.sttCode)
        assertFalse("no body text reaches the message", error.message!!.contains("secret"))
        assertEquals(1, s.requests.size)
    }

    @Test
    fun `a message history refusal whose body fails keeps its status and is not repeated`() {
        val s = server { _, socket -> truncated(socket, "502 Bad Gateway") }
        val error = refusal { runBlocking { client(s).getMessages("sess-1", 50, 0) } }
        assertEquals(502, error.status)
        assertEquals("/api/sessions/sess-1/messages failed (502)", error.message)
        assertNull(error.retryAfterMs)
        assertTrue(error.readResponse)
        assertEquals(1, s.requests.size)
    }

    @Test
    fun `a write refusal whose body fails keeps its status and Retry-After and is not marked a read`() {
        val s = server { _, socket -> truncated(socket, "429 Too Many Requests", "Retry-After: 3") }
        val error = refusal { runBlocking { client(s).setArchived("sess-1", true) } }
        assertEquals(429, error.status)
        assertEquals("/api/sessions/sess-1 failed (429)", error.message)
        assertEquals(3_000L, error.retryAfterMs)
        assertFalse(error.readResponse)
        assertEquals("PATCH", s.requests.single().method)
    }

    @Test
    fun `a transcription refusal whose body fails is the plain status without a speech code`() {
        val s = server { _, socket -> truncated(socket, "503 Service Unavailable", "Retry-After: 2") }
        val error = refusal { runBlocking { client(s).transcribe(ByteArray(16) { 1 }, "audio/wav") } }
        assertEquals(503, error.status)
        assertEquals("/api/audio/transcribe failed (503)", error.message)
        assertNull(error.sttCode)
        assertEquals(1, s.requests.size)
    }

    @Test
    fun `a speech refusal whose body fails is the fixed status text and is not repeated`() {
        val s = server { _, socket -> truncated(socket, "500 Internal Server Error") }
        val error = refusal { runBlocking { client(s).speak("hello") } }
        assertEquals(500, error.status)
        assertEquals("/api/audio/speak failed (500)", error.message)
        assertEquals(1, s.requests.size)
    }

    @Test
    fun `a small ordinary refusal still gives its short detail and Retry-After`() {
        val s = server { _, socket -> complete(socket, "429 Too Many Requests", """{"detail":"slow down"}""", "Retry-After: 3") }
        val error = refusal { runBlocking { client(s).listSessions(null, ArchivedFilter.EXCLUDE) } }
        assertEquals(429, error.status)
        assertEquals("/api/sessions failed (429): slow down", error.message)
        assertEquals(3_000L, error.retryAfterMs)
        assertTrue(error.readResponse)
    }

    @Test
    fun `a small transcription refusal keeps the fixed projection and drops the server's wording`() {
        val s = server { _, socket -> complete(socket, "400 Bad Request", """{"detail":"see https://internal.example/x?token=abc"}""") }
        val error = refusal { runBlocking { client(s).transcribe(ByteArray(16) { 1 }, "audio/wav") } }
        assertEquals(400, error.status)
        assertEquals("/api/audio/transcribe failed (400)", error.message)
        assertNull(error.sttCode)
    }

    @Test
    fun `a stored-file refusal whose body fails is still the status and is read once`() {
        val s = server { _, socket -> truncated(socket, "500 Internal Server Error", "Retry-After: 4") }
        val ref = AttachmentRefs.extract("@file:`attachments/big.bin`").single()
        val error = refusal { runBlocking { client(s).fetchStoredFile(ref) } }
        assertEquals(500, error.status)
        assertEquals("/api/files/read failed (500)", error.message)
        assertEquals(4_000L, error.retryAfterMs)
        assertTrue(error.readResponse)
        assertEquals(1, s.requests.size)
    }
}
