package com.rumi.hermesvoice.phone

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** On-demand microphone lease only. No idle playback type, sticky restart, or automatic PCM recovery. */
class PhoneManualService : Service() {
    private var generation = -1L
    private var entered = false
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = PhoneApp.from(this).manualCapture
        val token = intent?.getLongExtra(EXTRA_GENERATION, -1L) ?: -1L
        when (intent?.action) {
            ACTION_START -> {
                // Stale delivery cannot replace a newer admitted lease or stop its service instance.
                if (!controller.isCurrent(token)) {
                    if (!entered) stopSelf(startId)
                    return START_NOT_STICKY
                }
                generation = token
                running = this
                val accepted = runCatching {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Preparing microphone…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    entered = true
                    // Only after actual native admission, and only for this exact requested generation.
                    controller.onAdmission(generation, true)
                }.isSuccess
                if (!accepted) { controller.onAdmission(token, false); finish(token) }
            }
            ACTION_STOP -> { controller.stop(token); if (!entered) stopSelf(startId) }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    fun refresh(token: Long, text: String) {
        if (token != generation || !entered) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    fun finish(token: Long) {
        if (token != generation) return
        entered = false
        if (running === this) running = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running === this) { running = null; PhoneApp.from(this).manualCapture.serviceGone(generation) }
        entered = false
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 45, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        // Data URI makes each capture's Stop immutable and distinct; an old notification cannot stop a newer microphone.
        val action = Intent(this, PhoneManualService::class.java).setAction(ACTION_STOP)
            .setData(android.net.Uri.parse("hermesvoice://capture/$generation")).putExtra(EXTRA_GENERATION, generation)
        val stop = PendingIntent.getService(this, generation.hashCode(), action, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_voice)
            .setContentTitle("Hermes Voice · Phone recording").setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open).addAction(0, "Stop", stop).build()
    }

    companion object {
        private const val ACTION_START = "com.rumi.hermesvoice.phone.MANUAL_START"
        private const val ACTION_STOP = "com.rumi.hermesvoice.phone.MANUAL_STOP"
        private const val EXTRA_GENERATION = "generation"
        private const val NOTIFICATION_ID = 45
        private const val CHANNEL = "phone_recording"
        @Volatile var running: PhoneManualService? = null
            private set
        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Phone recording", NotificationManager.IMPORTANCE_LOW))
        }
        fun admission(context: Context): ManualAdmission = ManualAdmission { token, _ ->
            ContextCompat.startForegroundService(context, Intent(context, PhoneManualService::class.java)
                .setAction(ACTION_START).putExtra(EXTRA_GENERATION, token))
            AutoCloseable { running?.finish(token) }
        }
    }
}
