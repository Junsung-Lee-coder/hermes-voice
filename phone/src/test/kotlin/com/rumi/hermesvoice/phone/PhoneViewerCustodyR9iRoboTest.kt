package com.rumi.hermesvoice.phone

import android.net.Uri
import com.rumi.hermesvoice.core.attachments.AttachmentRef
import com.rumi.hermesvoice.core.attachments.RefKind
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Test-owned observer pauses INSIDE the real materialize(IO) call, before delegating the
 * actual production write. Neither HTTP latches nor replacement writers supply this witness. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w411dp-h1280dp")
class PhoneViewerCustodyR9iRoboTest : PhoneNavRoboBase() {
    private fun job(m: PhoneViewModel)=PhoneViewModel::class.java.getDeclaredField("viewerJob").also { it.isAccessible=true }.get(m) as Job
    private fun cancelledWrite(newer: Boolean) {
        val id=titled("Viewer","r9i-viewer");val m=showWith(id)
        val oldRef=AttachmentRef(RefKind.FILE,"/synthetic/old-r9i.txt","old-r9i.txt")
        val newRef=AttachmentRef(RefKind.FILE,"/synthetic/new-r9i.txt","new-r9i.txt")
        val oldBytes="old private synthetic bytes".toByteArray();val newBytes="new current synthetic bytes".toByteArray()
        fake.storedFiles[oldRef.path]="text/plain" to oldBytes
        fake.storedFiles[newRef.path]="text/plain" to newBytes
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val written=AtomicReference<File>();val writerThread=AtomicReference<String>()
        mockkObject(ViewedFiles)
        every { ViewedFiles.write(any(),any(),any()) } answers {
            val old=secondArg<String>()==oldRef.name
            if(old) {
                writerThread.set(Thread.currentThread().name)
                entered.countDown();check(release.await(30,TimeUnit.SECONDS)) { "actual IO writer release timeout" }
            }
            // The original ViewedFiles.write creates and writes its unique file. Preserve
            // both that returned identity and its actual bytes before prompt cancellation.
            val file=callOriginal() as File
            if(old) { check(file.readBytes().contentEquals(oldBytes));written.set(file) }
            file
        }
        var oldJob: Job?=null;var currentJob: Job?=null
        try {
            openStoredAttachmentFixture(fake,m,id,oldRef);oldJob=job(m)
            waitFor("inside actual materialization IO before real write") { entered.count==0L }
            check(written.get()==null && !oldJob.isCompleted)
            m.closeViewer() // cancel + directory clear occurs BEFORE the old unique file exists
            assertNull(m.state.value.viewer);assertTrue(ViewedFiles.dir(app).listFiles().orEmpty().isEmpty())
            var current: ViewedAttachment?=null
            if(newer) {
                openStoredAttachmentFixture(fake,m,id,newRef);currentJob=job(m)
                waitFor("new viewer's real write and READY") { currentJob.isCompleted && m.state.value.viewer?.phase==ViewerPhase.READY }
                current=m.state.value.viewer!!.file!!
                assertArrayEquals(newBytes,current.file.readBytes());assertTrue(ViewedFiles.shareable(app,current.file))
            }
            release.countDown();waitFor("cancelled materialization naturally joins") { oldJob.isCompleted };settle()
            assertTrue("actual old caller was cancelled",oldJob.isCancelled)
            val ownFile=checkNotNull(written.get()) { "real writer did not complete" }
            assertTrue(writerThread.get().isNotBlank())
            assertFalse("R09H-F02 prompt-cancelled IO return must remove its own late unique file",ownFile.exists())
            if(newer) {
                assertSame("stale cleanup cannot replace the new viewer",current,m.state.value.viewer?.file)
                assertTrue("stale cleanup cannot delete newer viewer file",current!!.file.exists())
                assertArrayEquals(newBytes,current.file.readBytes())
                assertEquals(listOf(current.file.canonicalPath),ViewedFiles.dir(app).listFiles().orEmpty().map { it.canonicalPath })
            } else {
                assertNull(m.state.value.viewer);assertTrue(ViewedFiles.dir(app).listFiles().orEmpty().isEmpty())
            }
        } finally {
            release.countDown()
            oldJob?.let { waitFor("finally joins old IO") { it.isCompleted } }
            currentJob?.let { waitFor("finally joins current IO") { it.isCompleted } }
            unmockkObject(ViewedFiles)
        }
    }
    @Test fun V01_closeBeforeLateRealWriteCleansPromptCancelledResult()=cancelledWrite(false)
    @Test fun V02_lateCancelledWriteCleansOnlyItsFileNotNewViewerFile()=cancelledWrite(true)
    @Test fun C01_validComposerPreviewOpenSaveAndCloseRetainActualBytes() {
        val id=titled("Valid viewer","r9i-valid-viewer");val m=showWith(id)
        val bytes="valid synthetic composer preview".toByteArray()
        val original=File(app.cacheDir,"r9i-composer.txt").also { it.writeBytes(bytes) }
        m.addAttachment(Uri.fromFile(original));waitFor("actual composer attachment") { m.state.value.attachments.size==1 }
        m.previewAttachment(0);val work=job(m)
        waitFor("local materialization READY") { work.isCompleted && m.state.value.viewer?.phase==ViewerPhase.READY }
        val viewed=m.state.value.viewer!!.file!!
        assertArrayEquals(bytes,viewed.file.readBytes());assertArrayEquals(bytes,viewed.bytes)
        assertTrue(ViewedFiles.shareable(app,viewed.file))
        assertNotNull(ViewedFiles.openWithIntent(app,viewed) { Uri.parse("content://synthetic/viewed") })
        val saved=File(app.cacheDir,"r9i-saved.txt");var done: Boolean?=null
        m.saveViewed(Uri.fromFile(saved)) { done=it };waitFor("public Save callback") { done!=null }
        assertEquals(true,done);assertArrayEquals(bytes,saved.readBytes())
        m.closeViewer();assertNull(m.state.value.viewer);assertFalse(viewed.file.exists())
    }
}
