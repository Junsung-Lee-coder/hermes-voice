package com.rumi.hermesvoice.watch

import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.rumi.hermesvoice.core.watchlink.BoundedRead
import com.rumi.hermesvoice.core.watchlink.LinkFrame
import com.rumi.hermesvoice.core.watchlink.PlayRequest
import com.rumi.hermesvoice.core.watchlink.PlayedAck
import com.rumi.hermesvoice.core.watchlink.TurnStateMessage
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Phone → Watch: playback channels, stage/stop messages, reader responses and the settings data
 * item. Work continues in the app scope after this service returns, so only the application
 * context is used there.
 */
class WatchListenerService : WearableListenerService() {
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (!channel.path.startsWith(WatchLinkPaths.PLAY_PREFIX)) return
        val context = applicationContext
        val app = WatchApp.from(context)
        app.scope.launch {
            val client = Wearable.getChannelClient(context)
            val request = runCatching {
                val input = client.getInputStream(channel).await()
                val bytes = withContext(Dispatchers.IO) { BoundedRead.readAtMost(input, BoundedRead.FRAME_LIMIT) } ?: error("too large")
                PlayRequest.fromFrame(LinkFrame.decode(bytes)).also {
                    check(WatchLinkPaths.playPath(it.turnId, it.sequence) == channel.path) { "path mismatch" }
                }
            }
            runCatching { client.close(channel).await() }
            request.onSuccess { app.play(it, channel.nodeId) }.onFailure {
                // Tell the Phone so it does not wait for the ack timeout.
                val parts = channel.path.removePrefix(WatchLinkPaths.PLAY_PREFIX).split('/')
                val seq = parts.getOrNull(1)?.toIntOrNull()
                if (parts.size == 2 && seq != null && WatchLinkPaths.isValidTurnId(parts[0])) {
                    runCatching {
                        Wearable.getMessageClient(context).sendMessage(channel.nodeId, WatchLinkPaths.PLAYED,
                            PlayedAck(parts[0], seq, false, "bad audio frame").encode()).await()
                    }
                }
            }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        val app = WatchApp.from(this)
        when (event.path) {
            WatchLinkPaths.STATE -> TurnStateMessage.decode(event.data)?.let { app.scope.launch { app.onPhoneState(it) } }
            WatchLinkPaths.STOP -> TurnStateMessage.decode(event.data)?.let { app.scope.launch { app.stopPlayback("stopped", it.turnId) } }
            WatchLinkPaths.READER_RESPONSE -> {
                val source = event.sourceNodeId
                val data = event.data
                app.scope.launch { app.onReaderResponse(source, data) }
            }
        }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        val app = WatchApp.from(this)
        events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WatchLinkPaths.SETTINGS }.forEach { event ->
            val json = DataMapItem.fromDataItem(event.dataItem).dataMap.getString("json") ?: return@forEach
            app.scope.launch { app.applySettings(json) }
        }
    }
}
