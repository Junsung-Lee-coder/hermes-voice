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
import com.rumi.hermesvoice.core.wake.WakeClaimService
import com.rumi.hermesvoice.core.wake.WakeEpochItem
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
        // In the application scope, as a tracked turn: it outlives this service call and the screen.
        app.launchTurn(turnId, phoneOrigin = false) {
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
                return@launchTurn
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
            WatchLinkPaths.PLAY_PROGRESS ->
                runCatching { PhoneApp.from(this).wiring().core.watchAcks.onProgressMessage(event.sourceNodeId, event.data) }
            WatchLinkPaths.CANCEL -> {
                val request = TurnStateMessage.decode(event.data)
                val stopped = request != null && runCatching {
                    PhoneApp.from(this).wiring().core.watchIntake.cancelTurn(request.turnId, event.sourceNodeId)
                }.getOrNull() == true
                Log.i(TAG, "watch stop request from=${event.sourceNodeId.take(8)} stopped=$stopped")
            }
            WatchLinkPaths.PRIVATE_AUDIO -> {
                val confirmed = runCatching { PhoneApp.from(this).privateAudio.onReceipt(event.data) }.getOrDefault(false)
                Log.i(TAG, "watch private-audio receipt from=${event.sourceNodeId.take(8)} confirmed=$confirmed")
            }
            WatchLinkPaths.DIAG_RESPONSE -> PhoneApp.from(this).diagnostics.onWatchResponse(event.sourceNodeId, event.data)
            WatchLinkPaths.READER_REQUEST -> answerReader(event.sourceNodeId, event.data)
            WatchLinkPaths.WAKE_CLAIM -> answerWakeClaim(event.sourceNodeId, event.data)
        }
    }

    /**
     * "Both": the Watch asks for, renews or releases the wake claim. The Phone decides
     * ([com.rumi.hermesvoice.core.wake.WakeAdmission]) with the Watch's node id taken from the Data
     * Layer, and answers that node only. No answer (Phone not set up) makes the Watch fail closed.
     */
    private fun answerWakeClaim(nodeId: String, data: ByteArray) {
        val context = applicationContext
        val app = PhoneApp.from(context)
        val wiring = runCatching { app.wiring() }.getOrNull() ?: return
        val verdict = WakeClaimService.handle(wiring.core.wakeAdmission, nodeId, data)
        Log.i(TAG, "wake claim from=${nodeId.take(8)} verdict=${verdict?.verdict ?: "released"} holder=${wiring.core.wakeAdmission.holder()} " +
            "epoch=${wiring.core.wakeAdmission.epoch}")
        verdict ?: return
        app.appScope.launch {
            runCatching { Wearable.getMessageClient(context).sendMessage(nodeId, WatchLinkPaths.WAKE_VERDICT, verdict.encode()).await() }
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

/** Publishes the count of answered wake requests as a Data Layer item the Watch reads before it listens. */
object WakeEpochSync {
    suspend fun publish(context: Context, item: WakeEpochItem) {
        val request = PutDataMapRequest.create(WatchLinkPaths.WAKE_EPOCH).apply {
            dataMap.putString("json", item.toJson())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request).await()
    }
}
