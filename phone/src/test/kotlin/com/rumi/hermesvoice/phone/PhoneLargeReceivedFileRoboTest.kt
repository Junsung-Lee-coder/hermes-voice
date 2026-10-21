package com.rumi.hermesvoice.phone

import android.net.Uri
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import com.rumi.hermesvoice.core.attachments.AttachmentRef
import com.rumi.hermesvoice.core.attachments.PreviewKind
import com.rumi.hermesvoice.core.attachments.RefKind
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.Job
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Opening a received file larger than the old 21 MiB transfer limit, through the real MainActivity, PhoneViewModel, PhoneApp
 * wiring and HTTP client against the Hermes dashboard contract double: the file is streamed to the app's private viewed folder
 * (never held whole in memory), classified from its head, and every existing fence around a viewer (close, replacement, sign-out,
 * cancellation) still removes exactly its own file. Synthetic data only; no device, no real service.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1280dp")
class PhoneLargeReceivedFileRoboTest : PhoneNavRoboBase() {
    private val mib = 1024 * 1024
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun viewerJob(m: PhoneViewModel) = PhoneViewModel::class.java.getDeclaredField("viewerJob").also { it.isAccessible = true }.get(m) as? Job

    private fun fileRef(path: String) = AttachmentRef(RefKind.FILE, path, path.substringAfterLast('/'))
    private fun imageRef(path: String) = AttachmentRef(RefKind.IMAGE, path, path.substringAfterLast('/'))

    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun <T> intercepting(seen: MutableList<String>? = null, change: (RecordedRequest, MockResponse) -> MockResponse = { _, r -> r }, body: () -> T): T {
        val original = fake.server.dispatcher
        fake.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen?.add(request.path!!)
                return change(request, original.dispatch(request))
            }
        }
        try {
            return body()
        } finally {
            fake.server.dispatcher = original
        }
    }

    private fun prepared(): Pair<String, PhoneViewModel> {
        val id = titled("Viewer", "large-viewer")
        return id to showWith(id)
    }

    private fun openAndWait(m: PhoneViewModel, id: String, ref: AttachmentRef): Job {
        openStoredAttachmentFixture(fake, m, id, ref)
        val job = viewerJob(m)!!
        waitFor("the viewer load finishes", 90_000) { job.isCompleted }
        settle()
        return job
    }

    private fun ready(m: PhoneViewModel): ViewedAttachment {
        val viewer = m.state.value.viewer!!
        assertEquals(viewer.error, ViewerPhase.READY, viewer.phase)
        return viewer.file!!
    }

    private fun viewedFiles() = ViewedFiles.dir(app).listFiles().orEmpty().toList()

    private fun requests() = fake.storedFileRequests.map { it.first }

    @Test fun L01_aTextFileAboveTheOldLimitOpensFromAStreamedDownloadAndKeepsOnlyItsFileAndPreview() {
        val (id, m) = prepared()
        val head = "synthetic-large-head\n".toByteArray()
        val bytes = ByteArray(25 * mib) { 'a'.code.toByte() }.also { head.copyInto(it) }
        val ref = fileRef("attachments/large-log.txt")
        fake.storedFiles[ref.path] = "text/plain" to bytes
        val seen = Collections.synchronizedList(mutableListOf<String>())
        intercepting(seen) { openAndWait(m, id, ref) }
        val viewed = ready(m)
        assertEquals(PreviewKind.TEXT, viewed.kind)
        assertEquals("text/plain", viewed.mimeType)
        assertTrue(viewed.text!!.startsWith("synthetic-large-head"))
        assertTrue(viewed.text!!.length <= 20_000)
        assertEquals((25 * mib), viewed.size)
        assertEquals(25L * mib, viewed.file.length())
        assertEquals(sha(bytes), sha(viewed.file))
        assertTrue("the whole file is not kept in memory: ${viewed.bytes.size}", viewed.bytes.size < mib)
        assertTrue(ViewedFiles.shareable(app, viewed.file))
        assertEquals(listOf("/api/files/read?path=${ref.path}", "/api/files/download?path=${ref.path}"), requests())
        assertTrue(fake.storedFileRequests.all { it.second == "Bearer ${fake.accessToken}" })
        assertTrue("no token in any URL: $seen", seen.none { it.contains("token", ignoreCase = true) })
        assertEquals(listOf(viewed.file.canonicalPath), viewedFiles().map { it.canonicalPath })
    }

    @Test fun L02_aPdfAboveTheOldLimitIsDecidedFromItsHeadAndStaysAFile() {
        val (id, m) = prepared()
        val bytes = ByteArray(30 * mib).also { "%PDF-1.4\n".toByteArray().copyInto(it) }
        val ref = fileRef("attachments/large.pdf")
        fake.storedFiles[ref.path] = "application/pdf" to bytes
        openAndWait(m, id, ref)
        val viewed = ready(m)
        assertEquals(PreviewKind.PDF, viewed.kind)
        assertEquals("application/pdf", viewed.mimeType)
        assertEquals(30L * mib, viewed.file.length())
        assertEquals(sha(bytes), sha(viewed.file))
        assertTrue(viewed.bytes.size < mib)
    }

    @Test fun L03_anUnknownBinaryAboveTheOldLimitIsAFileCardWithOpenWithAndSave() {
        val (id, m) = prepared()
        val bytes = ByteArray(40 * mib) { (it * 7).toByte() }
        val ref = fileRef("attachments/blob.bin")
        fake.storedFiles[ref.path] = "application/octet-stream" to bytes
        openAndWait(m, id, ref)
        val viewed = ready(m)
        assertEquals(PreviewKind.OTHER, viewed.kind)
        assertEquals(40L * mib, viewed.file.length())
        assertNotNull(ViewedFiles.openWithIntent(app, viewed) { Uri.parse("content://synthetic/viewed") })
        val saved = File(app.cacheDir, "large-saved.bin")
        var done: Boolean? = null
        m.saveViewed(Uri.fromFile(saved)) { done = it }
        waitFor("Save finishes", 60_000) { done != null }
        assertEquals(true, done)
        assertEquals(sha(bytes), sha(saved))
        saved.delete()
    }

    @Test fun L04_aPictureAboveThePreviewLimitIsKeptAsAFileNotDecoded() {
        val (id, m) = prepared()
        val bytes = ByteArray(17 * mib).also { png.copyInto(it) }
        val ref = imageRef("/img/large.png")
        fake.storedFiles[ref.path] = "image/png" to bytes
        openAndWait(m, id, ref)
        val viewed = ready(m)
        assertEquals(PreviewKind.IMAGE, viewed.kind)
        assertEquals("image/png", viewed.mimeType)
        assertEquals(17L * mib, viewed.file.length())
        assertEquals("the picture's bytes are not held for decoding", 0, viewed.bytes.size)
        assertEquals(listOf("/api/media?path=${ref.path}", "/api/files/download?path=${ref.path}"), requests())
    }

    @Test fun L05_aPictureAboveTheMediaRoutesOwnCapGoesStraightToTheDownload() {
        val (id, m) = prepared()
        val bytes = ByteArray(30 * mib).also { png.copyInto(it) }
        val ref = imageRef("/img/huge.png")
        fake.storedFiles[ref.path] = "image/png" to bytes
        openAndWait(m, id, ref)
        val viewed = ready(m)
        assertEquals(PreviewKind.IMAGE, viewed.kind)
        assertEquals(30L * mib, viewed.file.length())
        assertEquals(listOf("/api/media?path=${ref.path}", "/api/files/download?path=${ref.path}"), requests())
    }

    @Test fun L06_filesUpToTheOldPreviewLimitKeepTheirOrdinaryPathWithoutTheDownload() {
        val (id, m) = prepared()
        val small = fileRef("attachments/small.txt")
        fake.storedFiles[small.path] = "text/plain" to "hello small".toByteArray()
        openAndWait(m, id, small)
        var viewed = ready(m)
        assertEquals("hello small", viewed.text)
        assertEquals("hello small", String(viewed.bytes))
        assertEquals(listOf("/api/files/read?path=${small.path}"), requests())

        val edge = fileRef("attachments/edge.bin")
        fake.storedFiles[edge.path] = "application/octet-stream" to ByteArray(16 * mib)
        fake.storedFileRequests.clear()
        openAndWait(m, id, edge)
        viewed = ready(m)
        assertEquals(16L * mib, viewed.file.length())
        assertEquals("exactly 16 MiB still arrives in one piece", listOf("/api/files/read?path=${edge.path}"), requests())
        assertEquals("only the newest viewer's file is left", 1, viewedFiles().size)
    }

    @Test fun L06b_aFileJustAboveThePreviewLimitTakesTheDownloadAfterTheRefusedRead() {
        val (id, m) = prepared()
        val over = fileRef("attachments/over.bin")
        fake.storedFiles[over.path] = "application/octet-stream" to ByteArray(17 * mib)
        openAndWait(m, id, over)
        val viewed = ready(m)
        assertEquals(17L * mib, viewed.file.length())
        assertEquals(listOf("/api/files/read?path=${over.path}", "/api/files/download?path=${over.path}"), requests())
        assertEquals("only the viewer's file is left", 1, viewedFiles().size)
    }

    @Test fun L07_aFileAbove100MiBIsRefusedWithTheRawLimitAndNothingIsDownloaded() {
        val (id, m) = prepared()
        val ref = fileRef("attachments/too-big.bin")
        fake.storedFileStatus = 413
        openAndWait(m, id, ref)
        val viewer = m.state.value.viewer!!
        assertEquals(ViewerPhase.FAILED, viewer.phase)
        assertTrue(viewer.error!!, viewer.error!!.contains("100 MiB"))
        assertFalse(viewer.error!!, viewer.error!!.contains("21 MiB"))
        assertEquals(listOf("/api/files/read?path=${ref.path}"), requests())
        assertTrue(viewedFiles().isEmpty())
    }

    @Test fun L08_aServerWithoutTheDownloadRouteGetsATruthfulRefusalAndNoFile() {
        val (id, m) = prepared()
        val ref = fileRef("attachments/needs-download.bin")
        fake.storedFiles[ref.path] = "application/octet-stream" to ByteArray(25 * mib)
        fake.downloadRouteAbsent = true
        openAndWait(m, id, ref)
        val viewer = m.state.value.viewer!!
        assertEquals(ViewerPhase.FAILED, viewer.phase)
        assertTrue(viewer.error!!, viewer.error!!.contains("16 MiB"))
        assertFalse(viewer.error!!, viewer.error!!.contains("21 MiB"))
        assertEquals(listOf("/api/files/read?path=${ref.path}", "/api/files/download?path=${ref.path}"), requests())
        assertTrue(viewedFiles().isEmpty())
        assertTrue(m.state.value.signedIn)
    }

    @Test fun L09_aRefusedDownloadIsNeverShownAsAFile() {
        val (id, m) = prepared()
        val ref = fileRef("attachments/refused.bin")
        fake.storedFiles[ref.path] = "application/octet-stream" to ByteArray(25 * mib)
        intercepting(change = { r, response ->
            if (r.path!!.startsWith("/api/files/download")) MockResponse().setResponseCode(403).setHeader("Content-Type", "application/json").setBody("""{"detail":"Access to this file is denied"}""")
            else response
        }) { openAndWait(m, id, ref) }
        val viewer = m.state.value.viewer!!
        assertEquals(ViewerPhase.FAILED, viewer.phase)
        assertNull(viewer.file)
        assertEquals("Hermes did not allow this file to be shown", viewer.error)
        assertTrue(viewedFiles().isEmpty())
    }

    @Test fun L10_aDownloadCutShortIsAFailureAndLeavesNoFile() {
        val (id, m) = prepared()
        val ref = fileRef("attachments/cut.bin")
        fake.storedFiles[ref.path] = "application/octet-stream" to ByteArray(25 * mib)
        intercepting(change = { r, response ->
            if (r.path!!.startsWith("/api/files/download")) response.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) else response
        }) { openAndWait(m, id, ref) }
        val viewer = m.state.value.viewer!!
        assertEquals(ViewerPhase.FAILED, viewer.phase)
        assertNull(viewer.file)
        assertTrue(viewer.error!!, viewer.error!!.startsWith("Could not load this file"))
        assertTrue(viewedFiles().isEmpty())
        assertTrue("a cut transfer is not a sign-out", m.state.value.signedIn)
    }

    @Test fun L11_aDownloadRejectedForItsTokenIsRefreshedOnceAndRetriedWithTheNewToken() {
        val (id, m) = prepared()
        val ref = fileRef("attachments/auth.bin")
        fake.storedFiles[ref.path] = "application/octet-stream" to ByteArray(25 * mib)
        val downloads = java.util.concurrent.atomic.AtomicInteger()
        intercepting(change = { r, response ->
            if (r.path!!.startsWith("/api/files/download") && downloads.incrementAndGet() == 1) {
                MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json").setBody("""{"error":"session_expired","detail":"Unauthorized"}""")
            } else response
        }) { openAndWait(m, id, ref) }
        val viewed = ready(m)
        assertEquals(1, fake.refreshes)
        val tries = fake.storedFileRequests.filter { it.first.startsWith("/api/files/download") }
        assertEquals(2, tries.size)
        assertEquals("Bearer AT-1", tries[0].second)
        assertEquals("Bearer AT-2", tries[1].second)
        assertEquals(25L * mib, viewed.file.length())
        assertTrue(m.state.value.signedIn)
    }

    @Test fun L12_closingTheViewerMidDownloadStopsItAndRemovesTheUnfinishedFile() {
        val (id, m) = prepared()
        val ref = fileRef("attachments/slow.bin")
        fake.storedFiles[ref.path] = "application/octet-stream" to ByteArray(25 * mib)
        fake.downloadThrottleBytes = 256L * 1024
        openStoredAttachmentFixture(fake, m, id, ref)
        val job = viewerJob(m)!!
        waitFor("bytes of the download are on disk", 60_000) { viewedFiles().any { it.length() > 0 } }
        assertFalse(job.isCompleted)
        val partial = viewedFiles().single()
        assertTrue(partial.length() < 25L * mib)
        m.closeViewer()
        waitFor("the cancelled download joins", 30_000) { job.isCompleted }
        settle()
        assertTrue(job.isCancelled)
        assertNull(m.state.value.viewer)
        assertFalse(partial.exists())
        assertTrue(viewedFiles().isEmpty())
    }

    @Test fun L13_aNewerViewerKeepsItsFileWhenTheOlderDownloadIsCancelled() {
        val (id, m) = prepared()
        val slow = fileRef("attachments/older.bin")
        val quick = fileRef("attachments/newer.txt")
        fake.storedFiles[slow.path] = "application/octet-stream" to ByteArray(25 * mib)
        fake.storedFiles[quick.path] = "text/plain" to "newer synthetic text".toByteArray()
        fake.downloadThrottleBytes = 256L * 1024
        openStoredAttachmentFixture(fake, m, id, slow)
        val older = viewerJob(m)!!
        waitFor("bytes of the older download are on disk", 60_000) { viewedFiles().any { it.length() > 0 } }
        val olderPartial = viewedFiles().single()
        openStoredAttachmentFixture(fake, m, id, quick)
        val newer = viewerJob(m)!!
        waitFor("the newer viewer is ready", 30_000) { newer.isCompleted && m.state.value.viewer?.phase == ViewerPhase.READY }
        waitFor("the older download joins", 30_000) { older.isCompleted }
        settle()
        assertTrue(older.isCancelled)
        val current = ready(m)
        assertEquals("newer synthetic text", current.text)
        assertTrue("the newer file is untouched", current.file.exists())
        assertEquals("newer synthetic text", current.file.readText())
        assertFalse("the older partial file is gone", olderPartial.exists())
        assertEquals(listOf(current.file.canonicalPath), viewedFiles().map { it.canonicalPath })
    }

    @Test fun L14_signingOutMidDownloadNeverPublishesTheFileAndLeavesNone() {
        val (id, m) = prepared()
        val ref = fileRef("attachments/origin.bin")
        fake.storedFiles[ref.path] = "application/octet-stream" to ByteArray(25 * mib)
        fake.downloadThrottleBytes = 256L * 1024
        openStoredAttachmentFixture(fake, m, id, ref)
        val job = viewerJob(m)!!
        waitFor("bytes of the download are on disk", 60_000) { viewedFiles().any { it.length() > 0 } }
        assertFalse(job.isCompleted)
        m.signOut()
        waitFor("the old download ends", 60_000) { job.isCompleted }
        settle()
        assertNull("no file reaches the state after the origin changed", m.state.value.viewer?.file)
        assertTrue(viewedFiles().isEmpty())
    }

    @Test fun U01_theViewerSaysTheRawSizeOfALargeFile() {
        val (id, m) = prepared()
        openCard(m, id)
        val ref = fileRef("attachments/size.bin")
        fake.storedFiles[ref.path] = "application/octet-stream" to ByteArray(25 * mib)
        openAndWait(m, id, ref)
        waitFor("the viewer shows the file's size") { exists("viewer_meta") }
        rule.onNodeWithTag("viewer_meta").assertTextContains("25.0 MiB", substring = true)
    }

    @Test fun U02_aPictureTooLargeToDecodeSaysSoAndKeepsOpenWithAndSave() {
        val (id, m) = prepared()
        openCard(m, id)
        val ref = imageRef("/img/too-large-to-decode.png")
        fake.storedFiles[ref.path] = "image/png" to ByteArray(17 * mib).also { png.copyInto(it) }
        openAndWait(m, id, ref)
        waitFor("the file card is shown") { exists("viewer_file_card") }
        assertTrue(exists("viewer_open_with"))
        assertTrue(exists("viewer_save"))
        assertTrue("the picture's reason says it is too large to preview",
            rule.onAllNodes(androidx.compose.ui.test.hasText("too large to preview", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }
}
