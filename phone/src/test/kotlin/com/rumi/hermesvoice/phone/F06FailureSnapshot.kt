package com.rumi.hermesvoice.phone

import com.rumi.hermesvoice.core.FakeHermesDashboard
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** Failure-only, post-deadline metadata, NOT a snapshot of the deadline or a persistence retry.
 * No app/store API, Lazy.value, intrinsic monitor, callback installation, or shared-prefs loading.
 * File reads are bounded, not a wall-time/preemption guarantee for the host filesystem.
 */
internal object F06FailureSnapshot {
    const val MAX_FILE_BYTES = 16_384
    const val MAX_OUTPUT = 8_192
    private const val TARGET = "watch-pf-0006"

    fun emit(app: PhoneApp, fake: FakeHermesDashboard, prefs: File, output: (String) -> Unit = { println(it) }) {
        // Includes capture, formatting and sink errors, even AssertionError. The original Boolean is the sole verdict.
        try { output(capture(app, fake, prefs)) } catch (_: Throwable) { }
    }

    internal fun capture(app: PhoneApp, fake: FakeHermesDashboard, prefs: File): String {
        val start = System.nanoTime()
        val rows = mutableListOf("F06_POST_DEADLINE_V1 startNs=$start")
        fun record(role: String, read: () -> String) {
            rows += try { "$role ${read()}" } catch (e: Throwable) { "$role errorType=${type(e)}" }
        }
        record("fake") {
            // Bounded weakly-consistent queue traversal, no poll/peek payload or synchronized-list lock.
            val count = fake.transcribeStatuses.iterator().asSequence().take(17).count()
            "scriptedCount=$count capped=${count == 17} sttSeen=${fake.lastTranscribedWav != null} " +
                "sttCount=SKIPPED_NO_ATOMIC_COUNTER promptCount=SKIPPED_INTRINSIC_MONITOR"
        }
        record("outbox") {
            val delegate = field(app, "watchFailureOutbox\$delegate") as Lazy<*>
            "initialized=${delegate.isInitialized()} internalState=SKIPPED_INTRINSIC_MONITOR"
        }
        record("turns") {
            // The declared source field is a ConcurrentHashMap; never inspect values (turn ids).
            val turns = field(app, "turns") as ConcurrentHashMap<*, *>
            val jobs = turns.keys.iterator().asSequence().take(9).toList()
            "sampleCount=${jobs.size} capped=${jobs.size == 9} " + jobs.take(8).joinToString(" ") { job(it as Job) }
        }
        record("appScope") {
            val scope = field(app, "appScope") as CoroutineScope
            val parent = scope.coroutineContext[Job]
            val children = parent?.children?.take(9)?.toList().orEmpty()
            "parent=${parent?.let(::job) ?: "NONE"} childSampleCount=${children.size} capped=${children.size == 9} " +
                children.take(8).joinToString(" ", transform = ::job)
        }
        for ((role, name) in listOf("primary" to "hermes_voice_device_local.xml", "backup" to "hermes_voice_device_local.xml.bak")) {
            record("file_$role") { file(File(prefs, name)) }
        }
        record("thread_current") {
            Thread.currentThread().stackTrace.asSequence()
                .filter { it.className.startsWith("com.rumi.hermesvoice.") }.take(4)
                .joinToString(" ") { "method=${it.methodName.take(120)}" }
        }
        rows += "F06_POST_DEADLINE_V1 endNs=${System.nanoTime()} otherThreads=SKIPPED_BOUNDED_CAPTURE"
        return rows.joinToString("\n").take(MAX_OUTPUT)
    }

    private fun field(app: PhoneApp, name: String): Any? =
        PhoneApp::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(app)

    private fun job(value: Job): String = "type=${type(value)} id=${System.identityHashCode(value)} " +
        "active=${value.isActive} completed=${value.isCompleted} cancelled=${value.isCancelled}"

    private fun type(value: Any): String = value.javaClass.name.take(160)

    private fun file(file: File): String {
        try {
            val path = file.toPath()
            val attrs = java.nio.file.Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            val size = attrs.size()
            if (!attrs.isRegularFile || attrs.isSymbolicLink) return "exists=true read=SKIPPED_NONREGULAR"
            if (size > MAX_FILE_BYTES) return "exists=true size=$size read=SKIPPED_TOO_LARGE"
            val buffer = ByteBuffer.allocate(MAX_FILE_BYTES + 1)
            FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
                while (buffer.hasRemaining()) { if (channel.read(buffer) <= 0) break }
            }
            val bytes = buffer.position()
            val complete = bytes.toLong() == size && bytes <= MAX_FILE_BYTES
            val contains = if (complete) String(buffer.array(), 0, bytes, Charsets.UTF_8).contains(TARGET).toString() else "UNKNOWN"
            return "exists=true size=$size readBytes=$bytes complete=$complete containsTarget=$contains"
        } catch (_: java.nio.file.NoSuchFileException) {
            return "exists=false"
        } catch (e: Throwable) {
            return "exists=UNKNOWN errorType=${type(e)}"
        }
    }
}
