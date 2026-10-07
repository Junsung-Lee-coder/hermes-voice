package com.rumi.hermesvoice.core.diag

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** App-owned report files: random names, a count and an age bound, atomic writes. Only files this store names are ever touched. */
class DiagFileStore(
    private val dir: File,
    private val wallClockMs: () -> Long,
    private val keep: Int = KEEP,
    private val maxAgeMs: Long = MAX_AGE_MS,
    private val random: java.security.SecureRandom = java.security.SecureRandom(),
) {
    sealed interface Written {
        data class Ok(val file: File) : Written
        data class Failed(val fail: DiagFail) : Written
    }

    /** Deletes expired files and all but the newest [keep]-[reserve]; returns how many were deleted. Errors never propagate. */
    @Synchronized
    fun prune(reserve: Int = 0): Int {
        val files = owned().sortedByDescending { it.lastModified() }
        val now = wallClockMs()
        var deleted = 0
        files.forEachIndexed { index, file ->
            val expired = now - file.lastModified() > maxAgeMs
            if ((expired || index >= (keep - reserve).coerceAtLeast(0)) && runCatching { file.delete() }.getOrDefault(false)) deleted += 1
        }
        dir.listFiles { f -> f.isFile && f.name.startsWith(TEMP_PREFIX) }?.forEach { runCatching { it.delete() } }
        return deleted
    }

    @Synchronized
    fun write(content: String): Written {
        return try {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return Written.Failed(DiagFail.IO)
            prune(reserve = 1)
            val name = ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val temp = File(dir, "$TEMP_PREFIX$name")
            val target = File(dir, "$PREFIX$name$SUFFIX")
            try {
                temp.writeBytes(content.toByteArray(Charsets.UTF_8))
                if (!temp.renameTo(target)) throw IOException("rename")
            } catch (error: IOException) {
                runCatching { temp.delete() }
                return Written.Failed(DiagFail.IO)
            }
            Written.Ok(target)
        } catch (error: SecurityException) {
            Written.Failed(DiagFail.IO)
        }
    }

    /** The files this store would hand out (name pattern only), newest first. */
    @Synchronized
    fun list(): List<File> = owned().sortedByDescending { it.lastModified() }

    private fun owned(): List<File> = dir.listFiles { f -> f.isFile && NAME.matches(f.name) }?.toList() ?: emptyList()

    companion object {
        const val PREFIX = "hv-diag-"
        const val SUFFIX = ".json"
        const val TEMP_PREFIX = "hv-diag-tmp-"
        const val KEEP = 3
        const val MAX_AGE_MS = 24L * 3_600_000
        val NAME = Regex("^hv-diag-[0-9a-f]{16}\\.json$")
        const val CONTENT_TYPE = "application/json"
    }
}

sealed interface DiagExportResult {
    /** Ready to share: [file] is in the store's directory; [watch] says what the Watch part was. */
    data class Ready(val file: File, val watch: WatchDiagStatus, val phoneEvents: Int, val watchEvents: Int) : DiagExportResult

    /** Another export is running: nothing was started. */
    object Busy : DiagExportResult

    data class Failed(val fail: DiagFail) : DiagExportResult
}

/**
 * Builds one report on the user's request: a snapshot of the Phone's ring buffer (so capture, playback and intake continue
 * undisturbed), one scoped, bounded request to the Watch (a missing or late answer is a marker, never a failure of the Phone
 * part), then a file in the app's own cache. One export at a time. Nothing here writes a preference or keeps a resource.
 */
class DiagExporter(
    private val appVersionCode: Int,
    private val log: DiagLog,
    private val settings: () -> Map<String, Boolean>,
    private val watch: suspend (salt: String) -> WatchDiag,
    private val store: DiagFileStore,
    private val watchTimeoutMs: Long = WATCH_TIMEOUT_MS,
    private val newSalt: () -> String = { DiagPseudonyms.newSalt() },
) {
    private val running = AtomicBoolean(false)

    suspend fun export(): DiagExportResult {
        if (!running.compareAndSet(false, true)) return DiagExportResult.Busy
        try {
            val salt = newSalt()
            val events = log.snapshot()
            val dropped = log.dropped()
            val now = log.now()
            val watchPart = try {
                withTimeoutOrNull(watchTimeoutMs) { watch(salt) } ?: WatchDiag(WatchDiagStatus.TIMEOUT)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                WatchDiag(WatchDiagStatus.OFFLINE)
            }
            val json = DiagReport.build(appVersionCode, events, dropped, now, salt, watchPart, settings())
            return when (val written = store.write(json)) {
                is DiagFileStore.Written.Ok -> DiagExportResult.Ready(written.file, watchPart.status, events.size, watchPart.events.size)
                is DiagFileStore.Written.Failed -> DiagExportResult.Failed(written.fail)
            }
        } finally {
            running.set(false)
        }
    }

    companion object {
        const val WATCH_TIMEOUT_MS = 6_000L
    }
}
