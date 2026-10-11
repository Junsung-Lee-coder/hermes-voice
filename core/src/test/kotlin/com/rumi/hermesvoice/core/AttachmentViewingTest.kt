package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.attachments.AttachmentRefs
import com.rumi.hermesvoice.core.attachments.FilePreview
import com.rumi.hermesvoice.core.attachments.PreviewKind
import com.rumi.hermesvoice.core.attachments.RefKind
import com.rumi.hermesvoice.core.auth.HermesBearerSession
import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.auth.InMemoryHermesTokenStore
import com.rumi.hermesvoice.core.net.HermesDashboardClient
import com.rumi.hermesvoice.core.net.StoredFileSource
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** D: finding, fetching and classifying the files named in stored messages. */
class AttachmentViewingTest {
    private val fake = FakeHermesDashboard()
    private val http = OkHttpClient.Builder().followRedirects(true).readTimeout(10, TimeUnit.SECONDS).build()
    private val tokens = InMemoryHermesTokenStore(HermesBearerSession("AT-1", "RT-1", null, "basic", "jun"))
    private val client = HermesDashboardClient(HermesDashboardEndpoint.parse(fake.baseUrl), http, tokens)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)

    @After fun tearDown() = fake.close()

    @Test
    fun `refs are found in the forms Hermes stores and nothing else`() {
        val refs = AttachmentRefs.extract("@image:/home/u/.hermes/images/a_1.png\n@file:`attachments/Q3 report.pdf`\nsummarize\nMEDIA:/tmp/out dir/chart.png")
        assertEquals(listOf(RefKind.IMAGE, RefKind.FILE, RefKind.MEDIA), refs.map { it.kind })
        assertEquals(listOf("/home/u/.hermes/images/a_1.png", "attachments/Q3 report.pdf", "/tmp/out dir/chart.png"), refs.map { it.path })
        assertEquals(listOf("a_1.png", "Q3 report.pdf", "chart.png"), refs.map { it.name })
        assertTrue(AttachmentRefs.extract("an email me@image:x and a plain sentence").isEmpty())
    }

    @Test
    fun `unsafe or oversized refs are dropped and the count is bounded`() {
        assertTrue(AttachmentRefs.extract("@file:../../etc/passwd").isEmpty())
        assertTrue(AttachmentRefs.extract("@file:a/../b").isEmpty())
        assertTrue(AttachmentRefs.extract("@image:https://example.com/x.png").isEmpty())
        assertTrue(AttachmentRefs.extract("@file:" + "a".repeat(AttachmentRefs.MAX_PATH + 1)).isEmpty())
        assertTrue(AttachmentRefs.extract("@file:\"unterminated").isEmpty())
        val many = (1..40).joinToString("\n") { "@file:attachments/f$it.txt" }
        assertEquals(AttachmentRefs.MAX_REFS, AttachmentRefs.extract(many).size)
    }

    @Test
    fun `an image is fetched through api media with only the bearer header and the path`() = runBlocking {
        fake.storedFiles["/img/a.png"] = "image/png" to png
        val ref = AttachmentRefs.extract("@image:/img/a.png").single()
        val file = client.fetchStoredFile(ref)
        assertEquals("image/png", file.mimeType)
        assertEquals(png.toList(), file.bytes.toList())
        assertEquals(listOf("/api/media?path=/img/a.png"), fake.storedFileRequests.map { it.first })
        assertEquals("Bearer AT-1", fake.storedFileRequests.single().second)
    }

    @Test
    fun `a file, or an image the media route refuses, is read through api files read`() = runBlocking {
        fake.storedFiles["attachments/n.txt"] = "text/plain" to "hello".toByteArray()
        fake.storedFiles["/img/odd.bin"] = "application/octet-stream" to byteArrayOf(1, 2, 3)
        val text = client.fetchStoredFile(AttachmentRefs.extract("@file:attachments/n.txt").single())
        assertEquals("hello", String(text.bytes))
        val odd = client.fetchStoredFile(AttachmentRefs.extract("@image:/img/odd.bin").single())
        assertEquals(3, odd.bytes.size)
        assertEquals(listOf("/api/files/read?path=attachments/n.txt", "/api/media?path=/img/odd.bin", "/api/files/read?path=/img/odd.bin"),
            fake.storedFileRequests.map { it.first })
    }

    @Test
    fun `a refusal or missing file is reported, not worked around`() = runBlocking {
        val ref = AttachmentRefs.extract("@file:attachments/missing.txt").single()
        try {
            client.fetchStoredFile(ref)
            fail("expected a failure")
        } catch (error: HermesHttpException) {
            assertEquals(404, error.status)
        }
        fake.storedFileStatus = 403
        try {
            client.fetchStoredFile(ref)
            fail("expected a failure")
        } catch (error: HermesHttpException) {
            assertEquals(403, error.status)
        }
        assertEquals(2, fake.storedFileRequests.size)
    }

    @Test
    fun `a file larger than the preview limit is refused before it is decoded`() = runBlocking {
        fake.storedFiles["attachments/big.bin"] = "application/octet-stream" to ByteArray(StoredFileSource.MAX_BYTES + 1024)
        try {
            client.fetchStoredFile(AttachmentRefs.extract("@file:attachments/big.bin").single())
            fail("expected a refusal")
        } catch (error: HermesProtocolException) {
            assertTrue(error.message!!.contains("limit"))
        }
    }

    @Test
    fun `the bearer token is never sent to another origin by a redirect`() = runBlocking {
        MockWebServer().use { other ->
            other.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            other.start()
            fake.storedFileRedirect = other.url("/stolen").toString()
            try {
                client.fetchStoredFile(AttachmentRefs.extract("@file:attachments/n.txt").single())
            } catch (_: Exception) {
            }
            val seen = other.takeRequest(2, TimeUnit.SECONDS)
            assertNull("no Authorization header reaches another origin", seen?.getHeader("Authorization"))
        }
    }

    @Test
    fun `a signed-out client does not fetch anything`() = runBlocking {
        tokens.clear()
        try {
            client.fetchStoredFile(AttachmentRefs.extract("@file:attachments/n.txt").single())
            fail("expected sign-in required")
        } catch (_: HermesAuthRequiredException) {
        }
        assertTrue(fake.storedFileRequests.isEmpty())
    }

    @Test
    fun `classification trusts the bytes, not the claimed type`() {
        assertEquals(PreviewKind.IMAGE, FilePreview.classify("x.bin", "application/octet-stream", png).kind)
        assertEquals("image/png", FilePreview.classify("x.jpg", "image/jpeg", png).mimeType)
        assertEquals(PreviewKind.OTHER, FilePreview.classify("evil.png", "image/png", "<html><script>x</script></html>".toByteArray()).kind)
        assertEquals(PreviewKind.PDF, FilePreview.classify("d", "application/octet-stream", "%PDF-1.7\n1 0 obj".toByteArray()).kind)
        assertEquals(PreviewKind.TEXT, FilePreview.classify("n.txt", "text/plain", "plain 한글".toByteArray()).kind)
        assertEquals(PreviewKind.TEXT, FilePreview.classify("n.md", "application/octet-stream", "# t".toByteArray()).kind)
        assertEquals(PreviewKind.OTHER, FilePreview.classify("a.zip", "application/zip", byteArrayOf(0x50, 0x4B, 3, 4)).kind)
        assertEquals(PreviewKind.OTHER, FilePreview.classify("n.txt", "text/plain", byteArrayOf(65, 0, 66)).kind)
        assertEquals(PreviewKind.OTHER, FilePreview.classify("n.txt", "text/plain", byteArrayOf(0xC3.toByte(), 0x28)).kind)
    }

    @Test
    fun `text previews are bounded and never cut a character in two`() {
        val long = "가".repeat(FilePreview.MAX_TEXT_BYTES)
        val preview = FilePreview.textPreview(long.toByteArray())!!
        assertTrue(preview.length <= FilePreview.MAX_TEXT_CHARS)
        assertFalse(preview.contains('�'))
    }

    @Test
    fun `cache names cannot escape their folder`() {
        for (hostile in listOf("../../etc/passwd", "..\\..\\x", "/abs/path/x.png", "..", ".hidden", "a b/c?.png", "")) {
            val safe = FilePreview.safeStorageName(hostile)
            assertTrue(hostile, safe.isNotEmpty() && safe.length <= 64 && !safe.contains('/') && !safe.contains('\\') && !safe.startsWith("."))
        }
        assertEquals("x.png", FilePreview.safeStorageName("/abs/path/x.png"))
    }
}
