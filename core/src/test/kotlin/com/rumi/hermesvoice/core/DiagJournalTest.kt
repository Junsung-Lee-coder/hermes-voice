package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.diag.DiagCause
import com.rumi.hermesvoice.core.diag.DiagCode
import com.rumi.hermesvoice.core.diag.DiagFail
import com.rumi.hermesvoice.core.diag.DiagJournal
import com.rumi.hermesvoice.core.diag.DiagJournalOversize
import com.rumi.hermesvoice.core.diag.DiagJournalStorage
import com.rumi.hermesvoice.core.diag.DiagLog
import com.rumi.hermesvoice.core.diag.DiagModality
import com.rumi.hermesvoice.core.diag.DiagOrigin
import com.rumi.hermesvoice.core.diag.DiagPersistState
import com.rumi.hermesvoice.core.diag.DiagStage
import com.rumi.hermesvoice.core.diag.FileDiagJournalStorage
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The persisted diagnostic journal: restart, retention, clock changes, damaged and full storage, concurrency and what it never writes. */
class DiagJournalTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Clocks(var mono: Long = 1_000_000L, var wall: Long = 1_800_000_000_000L) {
        fun advance(ms: Long) { mono += ms; wall += ms }
    }

    private class MemStorage : DiagJournalStorage {
        @Volatile var bytes: ByteArray? = null
        @Volatile var failWrites = false
        @Volatile var failDelete = false
        @Volatile var failRead = false
        val writes = AtomicInteger()
        val deletes = AtomicInteger()

        override fun read(maxBytes: Int): ByteArray? {
            if (failRead) throw IOException("read")
            val stored = bytes ?: return null
            if (stored.size > maxBytes) throw DiagJournalOversize()
            return stored
        }

        override fun write(bytes: ByteArray) {
            if (failWrites) throw IOException("No space left on device")
            this.bytes = bytes
            writes.incrementAndGet()
        }

        override fun delete() {
            if (failDelete) throw IOException("delete")
            deletes.incrementAndGet()
            bytes = null
        }
    }

    private val inline = Executor { it.run() }

    private fun log(c: Clocks, capacity: Int = 256) = DiagLog(capacity = capacity, clock = { c.mono }, wallClock = { c.wall })

    private fun failed(log: DiagLog, ref: String = "turn-1") =
        log.record(DiagCode.REQUEST_FAILED, DiagOrigin.PHONE, ref, fail = DiagFail.TIMEOUT, stage = DiagStage.SUBMIT, cause = DiagCause.TIMEOUT, modality = DiagModality.TEXT)

    @Test
    fun `flushed events survive a new process, with fixed words intact and refs only as one-way hashes`() {
        val c = Clocks(); val storage = MemStorage()
        val first = log(c); DiagJournal(first, storage, inline).attach()
        failed(first, "raw-session-id-SECRET-424242")
        val stored = String(storage.bytes!!, Charsets.UTF_8)
        assertFalse("the raw correlation is never written", stored.contains("SECRET") || stored.contains("424242"))
        c.advance(60_000)
        val second = log(c); val journal = DiagJournal(second, storage, inline); journal.attach()
        val event = second.snapshot().single()
        assertEquals(DiagCode.REQUEST_FAILED, event.code)
        assertEquals(DiagStage.SUBMIT, event.stage)
        assertEquals(DiagCause.TIMEOUT, event.cause)
        assertEquals(DiagModality.TEXT, event.modality)
        assertTrue(DiagJournal.REF_FORMAT.matches(event.ref!!))
        assertNotEquals("raw-session-id-SECRET-424242", event.ref)
        assertEquals(60_000L, second.now() - event.atMs)
        assertEquals(1, second.coverage().restored)
        assertEquals(DiagPersistState.OK, journal.status().state)
    }

    @Test
    fun `events older than the retention window are pruned and counted at restore`() {
        val c = Clocks(); val storage = MemStorage()
        val first = log(c); DiagJournal(first, storage, inline).attach()
        failed(first)
        c.advance(25L * 3_600_000)
        val second = log(c); DiagJournal(second, storage, inline).attach()
        assertTrue(second.snapshot().isEmpty())
        assertEquals(1L, second.coverage().retentionPruned)
    }

    @Test
    fun `a wall clock that moved backwards does not keep or invent events`() {
        val c = Clocks(); val storage = MemStorage()
        val first = log(c); DiagJournal(first, storage, inline).attach()
        failed(first)
        c.wall -= 3_600_000
        val second = log(c); DiagJournal(second, storage, inline).attach()
        assertTrue("an event dated in the future is not trusted", second.snapshot().isEmpty())
        assertEquals(1L, second.coverage().retentionPruned)
    }

    @Test
    fun `after a reboot the monotonic clock restarts but the age comes from the wall clock`() {
        val c = Clocks(mono = 5_000_000L); val storage = MemStorage()
        val first = log(c); DiagJournal(first, storage, inline).attach()
        failed(first)
        c.wall += 3_600_000
        c.mono = 1_000L
        val second = log(c); DiagJournal(second, storage, inline).attach()
        assertEquals(3_600_000L, second.now() - second.snapshot().single().atMs)
    }

    private fun restoreFrom(bytes: ByteArray?, maxBytes: Int = DiagJournal.MAX_BYTES): Triple<DiagLog, DiagJournal, MemStorage> {
        val c = Clocks(); val storage = MemStorage(); storage.bytes = bytes
        val l = log(c); val j = DiagJournal(l, storage, inline, maxBytes); j.attach()
        return Triple(l, j, storage)
    }

    private fun validJson(events: String = "", extra: String = "", wall: Long = 1_800_000_000_000L - 1_000) =
        """{"v":1,"saved":$wall,"dropped":0,"pruned":0$extra,"events":[$events]}""".toByteArray()

    @Test
    fun `garbage, truncated, extra-key, wrong-version and oversized storage are reset without failing`() {
        val good = validJson("""{"w":1799999999000,"c":"request_failed","s":"submit"}""")
        val cases = listOf(
            "garbage" to "not json at all \u0000\u0001".toByteArray(),
            "truncated" to good.copyOf(good.size / 2),
            "extra top key" to validJson(extra = ""","token":"SECRET""""),
            "wrong version" to good.decodeToString().replace("\"v\":1", "\"v\":9").toByteArray(),
            "negative counter" to good.decodeToString().replace("\"dropped\":0", "\"dropped\":-5").toByteArray(),
            "event not an object" to validJson("5"),
            "empty" to ByteArray(0),
        )
        for ((name, bytes) in cases) {
            val (l, j, storage) = restoreFrom(bytes)
            assertTrue("$name: nothing restored", l.snapshot().isEmpty())
            assertEquals("$name: reported as an invalid file", DiagPersistState.RESET_INVALID, j.status().state)
            assertFalse("$name: the bad bytes are not kept", storage.bytes?.contentEquals(bytes) == true)
            assertTrue("$name: skipped counted", j.status().skippedOnLoad >= 1)
        }
        val (_, j, storage) = restoreFrom(ByteArray(DiagJournal.MAX_BYTES + 1) { 'x'.code.toByte() })
        assertTrue("oversized file reset", j.status().skippedOnLoad >= 1)
        assertTrue(storage.deletes.get() >= 1)
    }

    @Test
    fun `an event with an unknown word is skipped and counted while valid neighbours are kept`() {
        val bytes = validJson("""{"w":1799999999000,"c":"made_up_code"},{"w":1799999999500,"c":"request_failed","ca":"timeout"},{"w":1799999999600,"c":"request_failed","s":"SECRET-STAGE"}""")
        val (l, j, _) = restoreFrom(bytes)
        // an invalid word makes that event unusable; the file as a whole stays well-formed
        assertEquals(1, l.snapshot().size)
        assertEquals(DiagCause.TIMEOUT, l.snapshot().single().cause)
        assertTrue(j.status().skippedOnLoad >= 2)
    }

    @Test
    fun `an unreadable store is reported and never fails recording`() {
        val c = Clocks(); val storage = MemStorage(); storage.failRead = true
        val l = log(c); val j = DiagJournal(l, storage, inline); j.attach()
        failed(l)
        assertEquals(1, l.snapshot().size)
        assertEquals(DiagPersistState.READ_FAILED, j.status().state)
    }

    @Test
    fun `a full disk only sets a status and counters, and recovers on the next good write`() {
        val c = Clocks(); val storage = MemStorage()
        val l = log(c); val j = DiagJournal(l, storage, inline); j.attach()
        storage.failWrites = true
        repeat(3) { failed(l, "t$it") }
        assertEquals("the log itself is untouched", 3, l.snapshot().size)
        assertEquals(DiagPersistState.WRITE_FAILED, j.status().state)
        assertTrue(j.status().writeFailures >= 3)
        storage.failWrites = false
        failed(l, "later")
        assertEquals(DiagPersistState.OK, j.status().state)
        assertNotNull(storage.bytes)
    }

    @Test
    fun `the stored size never exceeds the cap even with a full log`() {
        val c = Clocks(); val storage = MemStorage()
        val l = log(c); val j = DiagJournal(l, storage, inline, maxBytes = 4_096); j.attach()
        repeat(256) { failed(l, "turn-$it") }
        j.flushNow()
        assertTrue("size ${storage.bytes!!.size}", storage.bytes!!.size <= 4_096)
        val again = log(c); DiagJournal(again, storage, inline, maxBytes = 4_096).attach()
        assertTrue(again.snapshot().isNotEmpty())
    }

    @Test
    fun `frequent progress records never cause a write by themselves`() {
        val c = Clocks(); val storage = MemStorage()
        val l = log(c); DiagJournal(l, storage, inline).attach()
        val before = storage.writes.get()
        repeat(500) { l.record(DiagCode.SPEECH_CHUNK_DONE, ref = "t", n = it, ms = 5) }
        repeat(500) { l.record(DiagCode.RESPONSE_PROGRESS, ref = "t") }
        repeat(50) { l.record(DiagCode.PLAYBACK_DONE, ref = "t") }
        assertEquals(before, storage.writes.get())
    }

    @Test
    fun `clear empties memory and storage together and recording continues afterwards`() {
        val c = Clocks(); val storage = MemStorage()
        val l = log(c); val j = DiagJournal(l, storage, inline); j.attach()
        failed(l)
        assertNotNull(storage.bytes)
        assertTrue(j.clearNow())
        assertNull(storage.bytes)
        assertEquals(0, l.coverage().retained)
        failed(l, "after")
        assertEquals(1, l.snapshot().size)
        assertTrue(String(storage.bytes!!).contains("request_failed"))
    }

    @Test
    fun `clear reports failure and keeps working when storage cannot delete`() {
        val c = Clocks(); val storage = MemStorage()
        val l = log(c); val j = DiagJournal(l, storage, inline); j.attach()
        failed(l)
        storage.failDelete = true
        assertFalse(j.clearNow())
        failed(l, "still-recording")
        assertEquals(1, l.snapshot().size)
    }

    @Test
    fun `appends, flushes, clears and exports from many threads stay consistent and finish`() {
        val c = Clocks(); val storage = MemStorage()
        val l = DiagLog(capacity = 64, clock = { System.nanoTime() / 1_000_000 }, wallClock = { System.currentTimeMillis() })
        val executor = Executors.newSingleThreadExecutor { Thread(it, "diag-test").apply { isDaemon = true } }
        val j = DiagJournal(l, storage, executor); j.attach()
        val workers = Executors.newFixedThreadPool(6) { Thread(it).apply { isDaemon = true } }
        val done = CountDownLatch(6)
        repeat(4) { t ->
            workers.execute { try { repeat(300) { failed(l, "t$t-$it") } } finally { done.countDown() } }
        }
        workers.execute { try { repeat(30) { j.clearNow(); Thread.yield() } } finally { done.countDown() } }
        workers.execute { try { repeat(60) { l.snapshot(); l.coverage(); j.flushNow() } } finally { done.countDown() } }
        assertTrue("workers finished", done.await(20, TimeUnit.SECONDS))
        executor.shutdown()
        assertTrue("journal executor drained", executor.awaitTermination(20, TimeUnit.SECONDS))
        workers.shutdownNow()
        assertTrue(l.snapshot().size <= 64)
        j.flushNow()
        val fresh = DiagLog(capacity = 64, clock = { System.nanoTime() / 1_000_000 }, wallClock = { System.currentTimeMillis() })
        val check = DiagJournal(fresh, storage, inline); check.attach()
        assertNotEquals("the final file is well-formed", DiagPersistState.RESET_INVALID, check.status().state)
        assertEquals(l.snapshot().size, fresh.snapshot().size)
    }

    @Test
    fun `file storage is atomic, bounded and touches only its own two names`() {
        val dir = tmp.newFolder("journal")
        val other = File(dir, "unrelated.txt").apply { writeText("keep") }
        val storage = FileDiagJournalStorage(dir)
        assertNull(storage.read(1024))
        storage.write("hello".toByteArray())
        assertEquals("hello", String(storage.read(1024)!!))
        File(dir, FileDiagJournalStorage.FILE_NAME + ".tmp").writeText("half written")
        assertEquals("a stray temp file is never read", "hello", String(storage.read(1024)!!))
        var oversize = false
        try { storage.read(3) } catch (_: DiagJournalOversize) { oversize = true }
        assertTrue(oversize)
        storage.delete()
        assertNull(storage.read(1024))
        assertFalse(File(dir, FileDiagJournalStorage.FILE_NAME + ".tmp").exists())
        assertEquals("keep", other.readText())
    }

    @Test
    fun `a real file journal round trips across instances`() {
        val dir = tmp.newFolder("rt")
        val c = Clocks()
        val first = log(c); DiagJournal(first, FileDiagJournalStorage(dir), inline).attach()
        failed(first)
        val second = log(c); DiagJournal(second, FileDiagJournalStorage(dir), inline).attach()
        assertEquals(1, second.snapshot().size)
    }
}
