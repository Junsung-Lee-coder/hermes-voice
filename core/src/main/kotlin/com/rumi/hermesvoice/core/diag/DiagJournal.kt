package com.rumi.hermesvoice.core.diag

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Where the journal bytes live. Implementations may throw [IOException]; the journal treats every failure as non-fatal. */
interface DiagJournalStorage {
    /** The stored bytes, or null when there are none. Throws [DiagJournalOversize] if more than [maxBytes] are stored. */
    @Throws(IOException::class)
    fun read(maxBytes: Int): ByteArray?

    /** Replaces the stored bytes atomically: a reader sees the old content or the new content, never a mixture. */
    @Throws(IOException::class)
    fun write(bytes: ByteArray)

    @Throws(IOException::class)
    fun delete()
}

class DiagJournalOversize : IOException("journal too large")

/** The journal as one small file in an app-private folder. Nothing else in that folder is ever read, written or deleted. */
class FileDiagJournalStorage(private val dir: File, private val name: String = FILE_NAME) : DiagJournalStorage {
    private val target get() = File(dir, name)
    private val temp get() = File(dir, "$name.tmp")

    override fun read(maxBytes: Int): ByteArray? {
        val file = target
        if (!file.isFile) return null
        FileInputStream(file).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size() + count > maxBytes) throw DiagJournalOversize()
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        }
    }

    override fun write(bytes: ByteArray) {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("folder")
        val tmp = temp
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: IOException) {
            runCatching { tmp.delete() }
            throw error
        }
    }

    override fun delete() {
        Files.deleteIfExists(target.toPath())
        runCatching { Files.deleteIfExists(temp.toPath()) }
    }

    companion object {
        const val FILE_NAME = "hv-diag-journal.json"
    }
}

/** How the journal itself is doing: a fixed word for the export, so "no events" can be read in light of it. */
enum class DiagPersistState(val wire: String) {
    NOT_ENABLED("not_enabled"), OK("ok"), WRITE_FAILED("write_failed"), READ_FAILED("read_failed"), RESET_INVALID("reset_invalid_file");

    companion object {
        fun fromWire(wire: String?): DiagPersistState? = values().firstOrNull { it.wire == wire }
    }
}

data class DiagPersistStatus(
    val state: DiagPersistState,
    val writeFailures: Long = 0,
    val skippedOnLoad: Long = 0,
    val clearedByUser: Boolean = false,
) {
    companion object {
        val NOT_ENABLED = DiagPersistStatus(DiagPersistState.NOT_ENABLED)
    }
}

/**
 * Keeps the last 24 hours of [DiagLog] events (at most the log's capacity and [maxBytes]) in app-private storage so that they
 * survive the process being recreated. Writes are coalesced on [executor], happen only after an event that is not a frequent
 * progress record (never per audio chunk), and never run on the caller's thread; a failed write only sets [status]. Events not
 * yet flushed when a process dies are lost: that is the one honest limit of this design.
 *
 * What is written is the typed event only. Correlation refs are written only in their stable one-way form (the keys are random
 * UUIDs, so it cannot be reversed or guessed) and a restored ref is already in that form, which keeps one request one request across
 * a restart; no text, id, address or message is ever part of an event, so none can be stored.
 */
class DiagJournal(
    private val log: DiagLog,
    private val storage: DiagJournalStorage,
    private val executor: Executor,
    private val maxBytes: Int = MAX_BYTES,
) {
    private val storageLock = Any()
    private val urgent = AtomicBoolean(false)
    private val scheduled = AtomicBoolean(false)

    @Volatile private var state = DiagPersistState.OK
    @Volatile private var writeFailures = 0L
    @Volatile private var skippedOnLoad = 0L
    @Volatile private var attached = false
    @Volatile private var restored = false

    fun status(): DiagPersistStatus = DiagPersistStatus(if (attached) state else DiagPersistState.NOT_ENABLED, writeFailures, skippedOnLoad)

    /** Starts journaling: the stored events are restored in the background, and every later important event is flushed. */
    fun attach() {
        if (attached) return
        attached = true
        log.onChange = ::onEvent
        urgent.set(true)
        submit { restoreNow(); urgent.set(true); drain() }
    }

    private fun onEvent(event: DiagEvent) {
        if (event.code.transient) return
        urgent.set(true)
        schedule()
    }

    /** Asks for a flush of everything recorded so far (including frequent progress records), e.g. when the app is stopped. */
    fun requestFlush() {
        if (!attached) return
        urgent.set(true)
        schedule()
    }

    private fun schedule() {
        if (scheduled.compareAndSet(false, true)) {
            if (!submit(::drain)) scheduled.set(false)
        }
    }

    private fun submit(task: () -> Unit): Boolean = try {
        executor.execute(task)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    private fun drain() {
        try {
            while (urgent.getAndSet(false)) flushNow()
        } finally {
            scheduled.set(false)
            if (urgent.get()) schedule()
        }
    }

    /** Synchronously writes the current events (tests and lifecycle callers on a background thread). Never throws. */
    fun flushNow() {
        synchronized(storageLock) {
            if (attached && !restored) return
            try {
                val view = log.persistView()
                val bytes = encode(view)
                storage.write(bytes)
                if (state != DiagPersistState.RESET_INVALID && state != DiagPersistState.READ_FAILED) state = DiagPersistState.OK
            } catch (_: Exception) {
                writeFailures += 1
                state = DiagPersistState.WRITE_FAILED
            }
        }
    }

    /** Reads, validates and restores the stored events. Anything invalid resets the stored journal; it never throws. */
    fun restoreNow() {
        synchronized(storageLock) {
            try {
                restoreLocked()
            } finally {
                restored = true
            }
        }
    }

    private fun restoreLocked() {
        run {
            val bytes = try {
                storage.read(maxBytes)
            } catch (_: DiagJournalOversize) {
                resetStored()
                return
            } catch (_: Exception) {
                state = DiagPersistState.READ_FAILED
                return
            }
            if (bytes == null) return
            val decoded = decode(bytes, log.wallNow(), log.now(), log.capacity, log.retentionMs)
            if (decoded == null) {
                resetStored()
                return
            }
            skippedOnLoad += decoded.skipped
            log.restore(decoded.events, decoded.capacityDropped, decoded.retentionPruned + decoded.pruned)
        }
    }

    private fun resetStored() {
        state = DiagPersistState.RESET_INVALID
        skippedOnLoad += 1
        try { storage.delete() } catch (_: Exception) { writeFailures += 1 }
    }

    /**
     * Forgets this device's events, memory and storage together. Operations in flight are not touched: they simply keep
     * recording afterwards. A flush that was already waiting finds the log empty (or newer) and writes just that.
     */
    fun clearNow(): Boolean = synchronized(storageLock) {
        log.clear()
        urgent.set(false)
        try {
            storage.delete()
            if (state == DiagPersistState.RESET_INVALID || state == DiagPersistState.READ_FAILED || state == DiagPersistState.WRITE_FAILED) state = DiagPersistState.OK
            true
        } catch (_: Exception) {
            writeFailures += 1
            state = DiagPersistState.WRITE_FAILED
            false
        }
    }

    internal class Decoded(
        val events: List<DiagEvent>,
        val capacityDropped: Long,
        val retentionPruned: Long,
        val pruned: Long,
        val skipped: Long,
    )

    private fun hashRef(event: DiagEvent): String? = DiagPseudonyms.keyOf(event)

    private fun encode(view: DiagPersistView): ByteArray {
        var events = view.events.takeLast(log.capacity)
        var trimmed = 0L
        while (true) {
            val array = JSONArray()
            events.forEach { array.put(eventJson(it)) }
            val bytes = JSONObject().put("v", VERSION).put("saved", log.wallNow())
                .put("dropped", (view.capacityDropped + trimmed).coerceAtMost(DiagLog.MAX_COUNTER))
                .put("pruned", view.retentionPruned.coerceAtMost(DiagLog.MAX_COUNTER)).put("events", array)
                .toString().toByteArray(Charsets.UTF_8)
            if (bytes.size <= maxBytes || events.isEmpty()) return bytes
            val drop = maxOf(1, events.size / 8)
            events = events.drop(drop)
            trimmed += drop
        }
    }

    private fun eventJson(e: DiagEvent): JSONObject = JSONObject().put("w", e.wallMs ?: 0L).put("c", e.code.wire).apply {
        e.origin?.let { put("o", it.wire) }
        hashRef(e)?.let { put("r", it) }
        e.n?.let { put("n", it) }
        e.ms?.let { put("ms", it) }
        e.fail?.let { put("f", it.wire) }
        e.stage?.let { put("s", it.wire) }
        e.cause?.let { put("ca", it.wire) }
        e.modality?.let { put("m", it.wire) }
        e.conn?.let { put("cn", it.wire) }
        e.status?.let { put("st", it) }
        e.stt?.let { put("t", DiagSttJson.encode(it)) }
    }

    companion object {
        const val VERSION = 2
        private val ACCEPTED_VERSIONS = setOf(1, 2)
        const val MAX_BYTES = 96 * 1024
        const val MAX_SKEW_MS = 5L * 60_000
        val REF_FORMAT = Regex("^[0-9a-f]{16}$")
        private val TOP_KEYS = setOf("v", "saved", "dropped", "pruned", "events")
        private val EVENT_KEYS = setOf("w", "c", "o", "r", "n", "ms", "f", "s", "ca", "m", "cn", "st", "t")

        private fun integral(value: Any?, min: Long, max: Long): Long? =
            if (value is Int || value is Long) (value as Number).toLong().takeIf { it in min..max } else null

        /**
         * Strict decoding: anything that is not a well-formed journal of this version returns null (the caller resets the
         * stored file). Single events with an unknown code or word are skipped and counted; an event dated in the future
         * (the wall clock moved back) or past the retention window is pruned rather than trusted.
         */
        internal fun decode(bytes: ByteArray, wallNow: Long, monoNow: Long, capacity: Int, retentionMs: Long): Decoded? {
            return try {
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                if (!TOP_KEYS.containsAll(json.keys().asSequence().toSet()) || (json.opt("v") as? Int) !in ACCEPTED_VERSIONS) return null
                val dropped = integral(json.opt("dropped"), 0, DiagLog.MAX_COUNTER) ?: return null
                val pruned = integral(json.opt("pruned"), 0, DiagLog.MAX_COUNTER) ?: return null
                val array = json.opt("events") as? JSONArray ?: return null
                if (array.length() > capacity) return null
                val events = ArrayList<DiagEvent>(array.length())
                var skipped = 0L
                var prunedHere = 0L
                for (i in 0 until array.length()) {
                    val item = array.opt(i) as? JSONObject ?: return null
                    val wall = integral(item.opt("w"), 0, Long.MAX_VALUE / 4) ?: return null
                    val event = decodeEvent(item, wall, monoNow - (wallNow - wall))
                    when {
                        event == null -> skipped += 1
                        wall > wallNow + MAX_SKEW_MS || wallNow - wall > retentionMs -> prunedHere += 1
                        else -> events += event
                    }
                }
                Decoded(events, dropped, pruned, prunedHere, skipped)
            } catch (_: JSONException) {
                null
            }
        }

        private fun decodeEvent(item: JSONObject, wall: Long, atMs: Long): DiagEvent? {
            if (!EVENT_KEYS.containsAll(item.keys().asSequence().toSet() - "w")) return null
            val code = DiagCode.fromWire(item.opt("c") as? String) ?: return null
            val origin = if (item.has("o")) DiagOrigin.values().firstOrNull { it.wire == item.opt("o") } ?: return null else null
            val ref = if (item.has("r")) (item.opt("r") as? String)?.takeIf { REF_FORMAT.matches(it) } ?: return null else null
            val n = if (item.has("n")) integral(item.opt("n"), 0, DiagLog.MAX_NUMBER.toLong())?.toInt() ?: return null else null
            val ms = if (item.has("ms")) integral(item.opt("ms"), 0, DiagLog.MAX_MS) ?: return null else null
            val fail = if (item.has("f")) DiagFail.fromWire(item.opt("f") as? String) ?: return null else null
            val stage = if (item.has("s")) DiagStage.fromWire(item.opt("s") as? String) ?: return null else null
            val cause = if (item.has("ca")) DiagCause.fromWire(item.opt("ca") as? String) ?: return null else null
            val modality = if (item.has("m")) DiagModality.fromWire(item.opt("m") as? String) ?: return null else null
            val conn = if (item.has("cn")) DiagConn.fromWire(item.opt("cn") as? String) ?: return null else null
            val status = if (item.has("st")) integral(item.opt("st"), -DiagClassify.MAX_STATUS.toLong(), DiagClassify.MAX_STATUS.toLong())?.toInt() ?: return null else null
            val stt = if (item.has("t")) DiagSttJson.decode(item.opt("t") as? JSONObject ?: return null) ?: return null else null
            return DiagEvent(atMs, code, origin, ref, n, ms, fail, wall, stage, cause, modality, conn, status, opaque = true, stt = stt)
        }
    }
}
