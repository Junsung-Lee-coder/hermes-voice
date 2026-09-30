package com.rumi.hermesvoice.phone

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.watchlink.LinkFrame
import com.rumi.hermesvoice.core.watchlink.TurnStateMessage
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchTransport
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Data Layer sends to the single Watch node a turn came from. */
class DataLayerWatchTransport(context: Context, override val nodeId: String) : WatchTransport {
    private val messages = Wearable.getMessageClient(context)
    private val channels = Wearable.getChannelClient(context)

    override suspend fun sendMessage(path: String, bytes: ByteArray) {
        messages.sendMessage(nodeId, path, bytes).await()
    }

    override suspend fun sendChannel(path: String, bytes: ByteArray) {
        val channel = channels.openChannel(nodeId, path).await()
        try {
            val output = channels.getOutputStream(channel).await()
            withContext(Dispatchers.IO) { output.use { it.write(bytes); it.flush() } }
        } catch (error: Exception) {
            runCatching { channels.close(channel).await() }
            throw error
        }
    }
}

/**
 * Receives Watch turns (`/hv/v1/turn/<id>` channels) and playback ACKs (`/hv/v1/played`). Turns
 * are handed to the shared [com.rumi.hermesvoice.core.HermesVoiceCore]; an accepted turn makes its
 * Watch node the playback target, reached through [DataLayerWatchTransport].
 */
class PhoneWatchListenerService : WearableListenerService() {
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val path = channel.path
        if (WatchLinkPaths.turnIdFromPath(path) == null) return
        val app = PhoneApp.from(this)
        val transport = DataLayerWatchTransport(this, channel.nodeId)
        app.appScope.launch {
            val client = Wearable.getChannelClient(this@PhoneWatchListenerService)
            val bytes = try {
                val input = client.getInputStream(channel).await()
                withContext(Dispatchers.IO) { readBounded(input, LinkFrame.MAX_PAYLOAD_BYTES + LinkFrame.MAX_HEADER_BYTES + 8) }
            } catch (error: Exception) {
                Log.w(TAG, "watch turn read failed: ${error.javaClass.simpleName}")
                null
            } finally {
                runCatching { client.close(channel).await() }
            }
            val wiring = runCatching { app.wiring() }.getOrNull()
            val turnId = WatchLinkPaths.turnIdFromPath(path)!!
            if (bytes == null || wiring == null) {
                runCatching {
                    transport.sendMessage(WatchLinkPaths.STATE, TurnStateMessage(turnId, "rejected",
                        if (wiring == null) "Set up Hermes on the phone" else "Recording transfer failed", true).encode())
                }
                return@launch
            }
            val outcome = wiring.core.watchIntake.onTurnChannel(path, bytes, transport)
            Log.i(TAG, "watch turn ${turnId.take(12)} outcome=${outcome?.javaClass?.simpleName}")
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchLinkPaths.PLAYED) return
        val accepted = runCatching { PhoneApp.from(this).wiring().core.watchAcks.onPlayedMessage(event.sourceNodeId, event.data) }
        if (accepted.getOrNull() != true) Log.w(TAG, "ignored playback ack from ${event.sourceNodeId.take(8)}")
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray? = input.use { stream ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (out.size() + read > limit) return null
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    }

    companion object {
        private const val TAG = "HermesVoiceWatch"
    }
}

/** Whether a reachable Watch node advertises the Hermes Voice Watch capability (the app is installed there). */
object WatchPresence {
    suspend fun reachable(context: Context): Boolean = Wearable.getCapabilityClient(context)
        .getCapability(WatchLinkPaths.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE).await().nodes.isNotEmpty()
}

/** Publishes the Watch settings as a Data Layer item the Watch observes. */
object WatchSettingsSync {
    suspend fun publish(context: Context, settings: WatchSettings) {
        val request = PutDataMapRequest.create(WatchLinkPaths.SETTINGS).apply {
            dataMap.putString("json", settings.toJson())
            dataMap.putLong("updated_at", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request).await()
    }
}
