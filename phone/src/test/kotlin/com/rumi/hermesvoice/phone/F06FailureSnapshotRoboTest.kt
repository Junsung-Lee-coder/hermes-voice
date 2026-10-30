package com.rumi.hermesvoice.phone

import com.rumi.hermesvoice.core.FakeHermesDashboard
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic passive-helper controls only; no upload, producer hold, or repair RED/GREEN. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class F06FailureSnapshotRoboTest {
    private fun exercise(test: (PhoneApp, FakeHermesDashboard, File) -> Unit) {
        val app = PhoneApp() // unattached; no onCreate/shared-prefs creation or pipeline
        val fake = FakeHermesDashboard()
        val root = Files.createTempDirectory("f06-passive-control-").toFile()
        try { test(app, fake, root) } finally {
            app.appScope.coroutineContext[Job]?.cancel()
            fake.close()
        }
    }

    private fun field(app: PhoneApp, name: String): Any? =
        PhoneApp::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(app)

    @Test fun C01_captureDoesNotInitializeLazyOutboxOrMutateKeysQueueJobsOrBytes() = exercise { app, fake, root ->
        val lazy = field(app, "watchFailureOutbox\$delegate") as Lazy<*>
        val local = field(app, "localStore\$delegate") as Lazy<*>
        assertFalse(lazy.isInitialized()); assertFalse(local.isInitialized())
        val primary = File(root, "hermes_voice_device_local.xml")
        val original = "<map><string name='synthetic'>watch-pf-0006 PRIVATE_CONTROL_MARKER</string></map>".toByteArray()
        primary.writeBytes(original)
        fake.transcribeStatuses.add(400 to "PRIVATE_CONTROL_QUEUE")
        val scripted = fake.transcribeStatuses.peek()
        @Suppress("UNCHECKED_CAST")
        val turns = field(app, "turns") as ConcurrentHashMap<Job, String>
        val turn = Job(); turns[turn] = "PRIVATE_CONTROL_TURN"
        val parent = app.appScope.coroutineContext[Job]!!
        val beforeFiles = root.list()!!.toSet()
        val text = F06FailureSnapshot.capture(app, fake, root)
        assertFalse(lazy.isInitialized()); assertFalse(local.isInitialized())
        assertSame(scripted, fake.transcribeStatuses.peek()); assertEquals(1, fake.transcribeStatuses.size)
        assertEquals(beforeFiles, root.list()!!.toSet()); assertArrayEquals(original, primary.readBytes())
        assertEquals(1, turns.size); assertEquals("PRIVATE_CONTROL_TURN", turns[turn]); assertTrue(turn.isActive)
        assertSame(parent, app.appScope.coroutineContext[Job]); assertTrue(parent.isActive)
        assertTrue(text.contains("initialized=false")); assertTrue(text.contains("containsTarget=true"))
        assertTrue(text.contains("scriptedCount=1")); assertFalse(text.contains("PRIVATE_CONTROL"))
        assertTrue(text.length <= F06FailureSnapshot.MAX_OUTPUT)
        turn.complete()
    }

    @Test fun C02_missingOversizedAndNonregularFilesHaveBoundedMetadataOnly() = exercise { app, fake, root ->
        val missing = File(root, "missing")
        val before = root.list()!!.toSet()
        val absent = F06FailureSnapshot.capture(app, fake, missing)
        assertEquals(2, Regex("exists=false").findAll(absent).count())
        assertEquals(before, root.list()!!.toSet()); assertFalse(missing.exists())
        val primary = File(root, "hermes_voice_device_local.xml")
        primary.writeBytes(ByteArray(F06FailureSnapshot.MAX_FILE_BYTES + 1) { 65 })
        val backup = File(root, "hermes_voice_device_local.xml.bak"); assertTrue(backup.mkdir())
        val text = F06FailureSnapshot.capture(app, fake, root)
        assertTrue(text.contains("read=SKIPPED_TOO_LARGE")); assertTrue(text.contains("read=SKIPPED_NONREGULAR"))
        assertEquals(F06FailureSnapshot.MAX_FILE_BYTES + 1L, primary.length()); assertTrue(backup.isDirectory)
        assertTrue(text.length <= F06FailureSnapshot.MAX_OUTPUT)
    }

    @Test fun C03_readErrorsAndThrowingSinkNeverReplaceOriginalFalseAssertion() = exercise { app, fake, root ->
        val notDirectory = File(root, "PRIVATE_CONTROL_ERROR\u0000") // invalid path, no filesystem write
        val text = F06FailureSnapshot.capture(app, fake, notDirectory)
        assertTrue(text.contains("errorType=")); assertFalse(text.contains("PRIVATE_CONTROL_ERROR"))
        var called = 0
        val verdict = false
        val failure = try {
            if (!verdict) F06FailureSnapshot.emit(app, fake, notDirectory) { called++; throw AssertionError("PRIVATE_CONTROL_SINK") }
            assertTrue("original false", verdict)
            null
        } catch (e: AssertionError) { e }
        assertEquals(1, called); assertNotNull(failure); assertEquals("original false", failure!!.message)
    }
}
