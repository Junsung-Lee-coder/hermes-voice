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
import com.rumi.hermesvoice.core.watchlink.BoundedRead
import com.rumi.hermesvoice.core.watchlink.PhoneReaderService
import com.rumi.hermesvoice.core.watchlink.ReaderError
import com.rumi.hermesvoice.core.watchlink.ReaderRequest
import com.rumi.hermesvoice.core.watchlink.ReaderResponse
import com.rumi.hermesvoice.core.watchlink.TurnStateMessage
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Data Layer sends to the single Watch node a turn came from. Holds only the application
 * context: it lives in app-scoped coroutines well past the listener service that created it.
 */
class DataLayerWatchTransport(context: Context, override val nodeId: String) : WatchTransport {
    private val messages = Wearable.getMessageClient(context.applicationContext)
    private val channels = Wearable.getChannelClient(context.applicationContext)

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
 * Receives Watch turns (`/hv/v1/turn/<id>` channels), playback ACKs (`/hv/v1/played`) and reader
 * requests (`/hv/v1/reader/request`). Turns are handed to the shared
 * [com.rumi.hermesvoice.core.HermesVoiceCore]; an accepted turn makes its Watch node the playback
 * target, reached through [DataLayerWatchTransport]. Reader requests are answered to the asking
 * node only and never touch voice state. Work outlives this service, so only the application
 * context is used from app-scoped coroutines.
 */
class PhoneWatchListenerService : WearableListenerService() {
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val path = channel.path
        val turnId = WatchLinkPaths.turnIdFromPath(path) ?: return
        val context = applicationContext
        val app = PhoneApp.from(context)
        val transport = DataLayerWatchTransport(context, channel.nodeId)
        app.appScope.launch {
            val client = Wearable.getChannelClient(context)
            // null = unreadable or larger than one frame: rejected below without reaching the orchestrator.
            val bytes = try {
                val input = client.getInputStream(channel).await()
                withContext(Dispatchers.IO) { BoundedRead.readAtMost(input, BoundedRead.FRAME_LIMIT) }
            } catch (error: Exception) {
                Log.w(TAG, "watch turn read failed: ${error.javaClass.simpleName}")
                null
            } finally {
                runCatching { client.close(channel).await() }
            }
            val wiring = runCatching { app.wiring() }.getOrNull()
            if (wiring == null) {
                runCatching {
                    transport.sendMessage(WatchLinkPaths.STATE, TurnStateMessage(turnId, "rejected", "Set up Hermes on the phone", true).encode())
                }
                return@launch
            }
            Log.i(TAG, "watch turn ${turnId.take(12)} received bytes=${bytes?.size ?: -1}")
            val outcome = wiring.core.watchIntake.onTurnChannel(path, bytes, transport)
            Log.i(TAG, "watch turn ${turnId.take(12)} outcome=${outcome?.javaClass?.simpleName ?: "rejected"}")
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WatchLinkPaths.PLAYED -> {
                val accepted = runCatching { PhoneApp.from(this).wiring().core.watchAcks.onPlayedMessage(event.sourceNodeId, event.data) }
                if (accepted.getOrNull() != true) Log.w(TAG, "ignored playback ack from ${event.sourceNodeId.take(8)}")
            }
            WatchLinkPaths.READER_REQUEST -> answerReader(event.sourceNodeId, event.data)
        }
    }

    private fun answerReader(nodeId: String, data: ByteArray) {
        val context = applicationContext
        val app = PhoneApp.from(context)
        val request = ReaderRequest.decode(data) ?: return
        app.appScope.launch {
            val wiring = runCatching { app.wiring() }.getOrNull()
            val response = when {
                wiring == null -> ReaderResponse(request.reqId, request.kind, ok = false, error = ReaderError.NOT_CONFIGURED,
                    sessionId = request.sessionId).encode()
                app.tokens.load() == null -> ReaderResponse(request.reqId, request.kind, ok = false, error = ReaderError.SIGN_IN_REQUIRED,
                    sessionId = request.sessionId).encode()
                else -> PhoneReaderService(wiring.core.sessions).handle(data)
            } ?: return@launch
            val sent = runCatching {
                Wearable.getMessageClient(context).sendMessage(nodeId, WatchLinkPaths.READER_RESPONSE, response).await()
            }
            Log.i(TAG, "reader ${request.kind.wire} req=${request.reqId.take(12)} to=${nodeId.take(8)} bytes=${response.size} " +
                "sent=${sent.isSuccess}")
        }
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
