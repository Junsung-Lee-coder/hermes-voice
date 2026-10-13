package com.rumi.hermesvoice.core.diag

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** The coarse transports a network snapshot may name. No SSID, address, operator, host or account ever accompanies them. */
enum class DiagTransport(val wire: String) {
    WIFI("wifi"), CELLULAR("cellular"), VPN("vpn"), ETHERNET("ethernet"), BLUETOOTH("bluetooth"), OTHER("other");

    companion object {
        fun fromWire(wire: String?): DiagTransport? = values().firstOrNull { it.wire == wire }
    }
}

/**
 * The connectivity category at one instant: context for reading a report, never a statement about why something failed and
 * never consulted before sending (an unvalidated network can still reach a local or VPN server).
 */
data class DiagNetwork(val conn: DiagConn, val transports: Set<DiagTransport> = emptySet()) {
    companion object {
        val UNKNOWN = DiagNetwork(DiagConn.UNKNOWN)
    }
}

enum class DiagBuildStatus(val wire: String) {
    AVAILABLE("available"), UNAVAILABLE("unavailable"), UNREADABLE("unreadable");

    companion object {
        fun fromWire(wire: String?): DiagBuildStatus? = values().firstOrNull { it.wire == wire }
    }
}

/** The identity of the installed build: a SHA-256 of the app's own APK, or an honest statement that there is none. */
data class DiagBuild(val status: DiagBuildStatus, val sha256: String? = null) {
    init {
        require((status == DiagBuildStatus.AVAILABLE) == (sha256 != null)) { "digest only for an available build" }
        require(sha256 == null || HEX64.matches(sha256)) { "digest format" }
    }

    companion object {
        val HEX64 = Regex("^[0-9a-f]{64}$")
        val UNAVAILABLE = DiagBuild(DiagBuildStatus.UNAVAILABLE)
        val UNREADABLE = DiagBuild(DiagBuildStatus.UNREADABLE)
    }
}

/** Reads the app's own installed APK; the path is only ever used to open it and is never exported or logged. */
object ApkIdentity {
    const val MAX_APK_BYTES = 512L * 1024 * 1024

    /** Blocking file IO: call it off the main thread. */
    fun of(apk: File?, maxBytes: Long = MAX_APK_BYTES): DiagBuild {
        if (apk == null) return DiagBuild.UNAVAILABLE
        return try {
            if (!apk.isFile) return DiagBuild.UNAVAILABLE
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            java.io.FileInputStream(apk).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) return DiagBuild.UNREADABLE
                    digest.update(buffer, 0, count)
                }
            }
            DiagBuild(DiagBuildStatus.AVAILABLE, digest.digest().joinToString("") { "%02x".format(it) })
        } catch (_: IOException) {
            DiagBuild.UNREADABLE
        } catch (_: SecurityException) {
            DiagBuild.UNREADABLE
        }
    }
}

/** The app version as it is exported: a bounded dotted name and the numeric code, with the build identity next to them. */
data class DiagApp(val versionName: String, val versionCode: Int, val build: DiagBuild) {
    companion object {
        val NAME_FORMAT = Regex("^[0-9A-Za-z._+-]{1,40}$")
        const val UNKNOWN_NAME = "unknown"

        fun safeName(name: String?): String = name?.takeIf { NAME_FORMAT.matches(it) } ?: UNKNOWN_NAME
    }
}

/** Counters and settings of one device's log as they travel and are exported. */
data class WireCoverage(
    val retained: Int,
    val capacityDropped: Long,
    val retentionPruned: Long,
    val restored: Int,
    val capacity: Int,
    val retentionHours: Int,
    val loggingForMs: Long,
    val persistence: DiagPersistState,
    val skippedOnLoad: Long,
    val writeFailures: Long,
) {
    companion object {
        fun of(coverage: DiagCoverage, persist: DiagPersistStatus) = WireCoverage(
            coverage.retained, coverage.capacityDropped, coverage.retentionPruned, coverage.restored, coverage.capacity,
            (coverage.retentionMs / 3_600_000L).toInt(), coverage.loggingForMs, persist.state, persist.skippedOnLoad, persist.writeFailures)
    }
}

/** Short, fixed phrases for the Phone and Watch screens. They describe a stage and a category only; no payload text is ever involved. */
object DiagLabels {
    fun stage(stage: DiagStage): String = when (stage) {
        DiagStage.PREPARE -> "preparing"
        DiagStage.VERIFY -> "checking the conversation"
        DiagStage.CONNECT -> "connecting"
        DiagStage.RESUME -> "opening the session"
        DiagStage.UPLOAD -> "uploading an attachment"
        DiagStage.SUBMIT -> "sending"
        DiagStage.WAIT -> "waiting for the reply"
        DiagStage.RECEIVE -> "receiving the reply"
        DiagStage.STT -> "transcribing"
        DiagStage.TTS -> "speech synthesis"
        DiagStage.TRANSFER -> "Phone and Watch transfer"
        DiagStage.PLAYBACK -> "playback"
    }

    fun cause(cause: DiagCause): String = when (cause) {
        DiagCause.NO_NETWORK -> "no network"
        DiagCause.DNS -> "name lookup failed"
        DiagCause.CONNECT -> "could not connect"
        DiagCause.TLS -> "secure connection failed"
        DiagCause.TIMEOUT -> "timed out"
        DiagCause.DISCONNECT -> "connection dropped"
        DiagCause.AUTH -> "sign-in or permission"
        DiagCause.REJECTED -> "rejected by Hermes"
        DiagCause.CANCELLED -> "stopped"
        DiagCause.LOCAL -> "refused by the app"
        DiagCause.UNKNOWN -> "unknown cause"
        DiagCause.STT_UNAVAILABLE -> "speech server unavailable"
    }

    /** "category while stage" for a status line, or null when neither is known. Fixed words only. */
    fun detail(atStage: DiagStage?, because: DiagCause?): String? {
        if (atStage == null && because == null) return null
        return listOfNotNull(because?.let { cause(it) }, atStage?.let { "while ${stage(it)}" }).joinToString(" ")
    }

    /**
     * "Hermes accepted it; the reply failed: connection dropped while waiting for the reply" or
     * "Acceptance not observed: timed out while uploading an attachment". Null when nothing is known.
     */
    fun failure(atStage: DiagStage?, because: DiagCause?, acceptanceObserved: Boolean): String? {
        if (atStage == null && because == null) return null
        val what = listOfNotNull(because?.let { cause(it) }, atStage?.let { "while ${stage(it)}" }).joinToString(" ")
        return if (acceptanceObserved) "Hermes accepted it; then: $what" else "Acceptance not observed: $what"
    }
}
