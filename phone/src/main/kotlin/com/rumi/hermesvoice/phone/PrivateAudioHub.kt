package com.rumi.hermesvoice.phone

import android.util.Log
import com.rumi.hermesvoice.core.headset.HeadsetPolicy
import com.rumi.hermesvoice.core.headset.PrivateAudioMonitor
import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.watchlink.PrivateAudioLedger
import com.rumi.hermesvoice.core.watchlink.PrivateAudioReceipt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps the core and the Watch told whether the headset is the private output. It watches the audio devices only while
 * "Use headset" is on (no polling, no service), tells the core on every report, and persists + publishes each change as a revisioned
 * Watch settings snapshot. The Phone's own privacy never waits for the Watch: an unreachable Watch leaves it pending in [ledger].
 */
class PrivateAudioHub(
    private val settings: AppSettings,
    policy: HeadsetPolicy,
    private val scope: CoroutineScope,
    private val publish: suspend (WatchSettings) -> Unit,
    private val onCore: (Boolean) -> Unit,
) : AutoCloseable {
    val ledger = PrivateAudioLedger()
    private val monitor = PrivateAudioMonitor(policy) { active -> report(active) }

    /** Applies the setting as it is now and reports the private state if it changed (call at start and after each toggle). */
    fun refresh() = monitor.refresh()

    /** The Watch's receipt of a snapshot; true only when it confirms the current private snapshot. */
    fun onReceipt(bytes: ByteArray): Boolean {
        val receipt = PrivateAudioReceipt.parse(bytes) ?: return false
        return ledger.received(receipt)
    }

    override fun close() = monitor.close()

    private fun report(active: Boolean) {
        onCore(active)
        if (!active && !settings.watchSettings().privateAudio) return
        val snapshot = settings.savePrivateAudio(active)
        ledger.published(snapshot.revision, active)
        scope.launch {
            runCatching { publish(snapshot) }.onFailure { Log.w(TAG, "private-audio snapshot not delivered: ${it.javaClass.simpleName}") }
        }
    }

    private companion object {
        const val TAG = "HermesVoicePrivate"
    }
}
