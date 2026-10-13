package com.rumi.hermesvoice.phone

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.diag.ApkIdentity
import com.rumi.hermesvoice.core.diag.DiagApp
import com.rumi.hermesvoice.core.diag.DiagBuild
import com.rumi.hermesvoice.core.diag.DiagExportResult
import com.rumi.hermesvoice.core.diag.DiagExporter
import com.rumi.hermesvoice.core.diag.DiagFileStore
import com.rumi.hermesvoice.core.diag.DiagJournal
import com.rumi.hermesvoice.core.diag.DiagLog
import com.rumi.hermesvoice.core.diag.FileDiagJournalStorage
import com.rumi.hermesvoice.core.diag.DiagShare
import com.rumi.hermesvoice.core.diag.DiagWatchClient
import com.rumi.hermesvoice.core.diag.DiagWireV2
import com.rumi.hermesvoice.core.diag.DiagWire
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * The private, user-started diagnostics export. Recording is a bounded ring buffer ([log]) whose last 24 hours are also kept in
 * one app-private file ([journal]) so they survive the process being recreated; nothing is sent anywhere by the app: [shareIntent] only opens the Android share sheet, the recipient is chosen by the user. The report is a file in
 * this app's cache (see [DiagFileStore] for its retention) shared by a read-only, per-file grant of a not-exported provider.
 */
class PhoneDiagnostics(private val app: PhoneApp) {
    val log = DiagLog(clock = SystemClock::elapsedRealtime, connectivity = { PhoneNetworkSnapshot.conn(app) })

    private val journalExecutor: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "hv-diag-journal").apply { isDaemon = true }
    }

    /** In the no-backup folder, apart from the share and viewer cache folders the FileProvider can reach. */
    val journal = DiagJournal(log, FileDiagJournalStorage(File(app.noBackupFilesDir, "diag-journal")), journalExecutor)

    init {
        journal.attach()
    }

    @Volatile private var cachedBuild: DiagBuild? = null

    /** Blocking: hashes the app's own installed APK once per process. The path is used to open it and is never exported. */
    private fun build(): DiagBuild = cachedBuild ?: ApkIdentity.of(runCatching { File(app.applicationInfo.sourceDir) }.getOrNull())
        .also { if (it.sha256 != null) cachedBuild = it }

    @Volatile private var askedNode: String? = null

    private val client = DiagWatchClient { bytes ->
        val node = watchNode() ?: return@DiagWatchClient false
        askedNode = node
        val path = if (DiagWire.decodeRequestAny(bytes)?.first == DiagWireV2.SCHEMA) WatchLinkPaths.DIAG_REQUEST_V2 else WatchLinkPaths.DIAG_REQUEST
        Wearable.getMessageClient(app).sendMessage(node, path, bytes).await()
        true
    }

    private val store = DiagFileStore(File(app.cacheDir, DiagShare.CACHE_SUBDIR), System::currentTimeMillis)

    private val packageInfo = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()

    private val exporter = DiagExporter(
        appVersionCode = packageInfo?.longVersionCode?.toInt() ?: 0,
        log = log,
        settings = ::settingsFlags,
        watch = { salt -> client.request(salt) },
        store = store,
        versionName = DiagApp.safeName(packageInfo?.versionName),
        build = ::build,
        network = { PhoneNetworkSnapshot.read(app) },
        persistence = journal::status,
    )

    /** Only yes/no switches, under fixed names: nothing typed, no address, name or account. */
    private fun settingsFlags(): Map<String, Boolean> {
        val watch = app.settings.watchSettings()
        val playback = app.settings.playback()
        return mapOf(
            "later_replies" to app.laterConsent.enabled,
            "voice_routing" to app.settings.routingEnabled,
            "play_first_response" to playback.playFirstResponse,
            "play_middle_responses" to playback.playMiddleResponses,
            "phone_background_wake" to watch.phoneBackgroundWakeEnabled,
            "watch_background_wake" to watch.watchBackgroundWakeEnabled,
        )
    }

    /** Whether the Watch app can be reached now (what the consent dialog says); asked only when the dialog opens. */
    suspend fun watchReachable(): Boolean = watchNode() != null

    private suspend fun watchNode(): String? = runCatching {
        Wearable.getCapabilityClient(app).getCapability(WatchLinkPaths.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE).await()
            .nodes.let { nodes -> (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id }
    }.getOrNull()

    /** A Watch answer, accepted only from the node that was asked. */
    fun onWatchResponse(sourceNodeId: String, bytes: ByteArray) {
        if (sourceNodeId == askedNode) client.onResponse(bytes)
    }

    suspend fun export(): DiagExportResult = exporter.export()

    /** Clears this phone's saved and in-memory diagnostics only: no conversation, setting, media or Watch data. False if the file could not be removed. */
    suspend fun clearLocalLog(): Boolean = withContext(Dispatchers.IO) { journal.clearNow() }

    /** Asks for what is recorded so far to be written (the app is going to the background). */
    fun flush() = journal.requestFlush()

    /** Old report files are removed when the app starts and with every export. */
    fun pruneOnStart() {
        store.prune()
    }

    /** The share sheet for [file]: this one file, read-only, to the app the user picks. Null if it isn't a report of this app. */
    fun shareIntent(
        context: Context,
        file: File,
        uriFor: (File) -> Uri = { FileProvider.getUriForFile(context, DiagShare.authority(app.packageName), it) },
    ): Intent? {
        if (!DiagShare.shareable(app.cacheDir, file)) return null
        val uri = uriFor(file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(DiagShare.CONTENT_TYPE)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Hermes Voice diagnostics")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("diagnostics", uri)
        val chooser = Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        chooser.clipData = ClipData.newRawUri("diagnostics", uri)
        return chooser
    }
}
