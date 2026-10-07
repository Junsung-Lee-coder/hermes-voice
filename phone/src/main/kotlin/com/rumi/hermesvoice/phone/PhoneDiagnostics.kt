package com.rumi.hermesvoice.phone

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.FileProvider
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.diag.DiagExportResult
import com.rumi.hermesvoice.core.diag.DiagExporter
import com.rumi.hermesvoice.core.diag.DiagFileStore
import com.rumi.hermesvoice.core.diag.DiagLog
import com.rumi.hermesvoice.core.diag.DiagShare
import com.rumi.hermesvoice.core.diag.DiagWatchClient
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import java.io.File
import kotlinx.coroutines.tasks.await

/**
 * The private, user-started diagnostics export. Recording is a bounded in-memory ring buffer ([log]); nothing is sent anywhere
 * by the app: [shareIntent] only opens the Android share sheet, the recipient is chosen by the user. The report is a file in
 * this app's cache (see [DiagFileStore] for its retention) shared by a read-only, per-file grant of a not-exported provider.
 */
class PhoneDiagnostics(private val app: PhoneApp) {
    val log = DiagLog(clock = SystemClock::elapsedRealtime)

    @Volatile private var askedNode: String? = null

    private val client = DiagWatchClient { bytes ->
        val node = watchNode() ?: return@DiagWatchClient false
        askedNode = node
        Wearable.getMessageClient(app).sendMessage(node, WatchLinkPaths.DIAG_REQUEST, bytes).await()
        true
    }

    private val store = DiagFileStore(File(app.cacheDir, DiagShare.CACHE_SUBDIR), System::currentTimeMillis)

    private val exporter = DiagExporter(
        appVersionCode = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode.toInt() }.getOrDefault(0),
        log = log,
        settings = ::settingsFlags,
        watch = { salt -> client.request(salt) },
        store = store,
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

    /** Old report files are removed when the app starts and with every export. */
    fun pruneOnStart() {
        store.prune()
    }

    /** The share sheet for [file]: this one file, read-only, to the app the user picks. Null if it isn't a report of this app. */
    fun shareIntent(context: Context, file: File): Intent? {
        if (!DiagShare.shareable(app.cacheDir, file)) return null
        val uri = FileProvider.getUriForFile(context, DiagShare.authority(app.packageName), file)
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
