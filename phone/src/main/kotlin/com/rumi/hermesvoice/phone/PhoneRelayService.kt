package com.rumi.hermesvoice.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.rumi.hermesvoice.core.background.BackgroundStatus
import com.rumi.hermesvoice.core.background.BackgroundText
import com.rumi.hermesvoice.core.background.HoldReason
import com.rumi.hermesvoice.core.background.WakeLockPort

/**
 * Keeps the Phone process in the foreground-service state while the user has switched on
 * "relay in the background", so Watch requests are transcribed, routed, delivered and answered
 * with this app closed and the screen off. It holds no logic: the relay is the application's
 * shared [com.rumi.hermesvoice.core.HermesVoiceCore], the same one the visible app uses. The
 * Phone does not listen or record here; its own microphone stays foreground-only.
 *
 * Types: `connectedDevice` (it exists to exchange requests and replies with the paired Watch)
 * and `mediaPlayback` (replies play on this Phone when it sent the latest voice request).
 * Started only from the visible app, stopped by the switch or by Stop in its notification, and
 * not sticky: a relay the system ended stays ended until the app is opened again.
 */
class PhoneRelayService : Service() {
    private var generation = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = PhoneApp.from(this)
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "background relay stop requested (notification)")
                app.stopRelay()
                if (running !== this) stopSelf()
            }
            ACTION_START -> {
                generation = app.relay.generation
                running = this
                val entered = runCatching {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this, app.relay.status.copy(running = true)),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                    Log.i(TAG, "background relay service foreground generation=$generation")
                }.onFailure { Log.w(TAG, "background relay service refused: ${it.javaClass.simpleName}") }.isSuccess
                if (!entered) {
                    running = null
                    stopSelf()
                    app.onRelayServiceGone(generation)
                } else if (!app.relay.status.running) {
                    // Switched off before the platform delivered this start: it had to enter the foreground; now it ends.
                    finish()
                }
            }
            // Never redelivered or restarted by the system (START_NOT_STICKY): nothing to resume.
            else -> if (running !== this) stopSelf()
        }
        return START_NOT_STICKY
    }

    fun refresh(status: BackgroundStatus) {
        if (!status.running) return
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(this, status)) }
    }

    /** The user's stop: leave the foreground, remove the notification, end. */
    fun finish() {
        if (running === this) running = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "background relay service finished generation=$generation")
    }

    override fun onDestroy() {
        if (running === this) {
            // Not the user's stop: the relay is shown as paused and resumes only when the app is opened.
            running = null
            PhoneApp.from(this).onRelayServiceGone(generation)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HermesVoiceRelay"
        private const val ACTION_START = "com.rumi.hermesvoice.phone.action.RELAY_START"
        private const val ACTION_STOP = "com.rumi.hermesvoice.phone.action.RELAY_STOP"
        private const val NOTIFICATION_ID = 42
        const val CHANNEL = "background_relay"

        /** The running service of the current relay session (same process). */
        @Volatile var running: PhoneRelayService? = null
            private set

        fun start(context: Context): Intent = Intent(context, PhoneRelayService::class.java).setAction(ACTION_START)

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Background relay", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Hermes Voice relays for the Watch with the app closed"
                })
        }

        private fun notification(context: Context, status: BackgroundStatus): Notification {
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(context, 1, Intent(context, PhoneRelayService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_voice)
                .setContentTitle("Hermes Voice")
                .setContentText(BackgroundText.phoneNotification(status) ?: "Stopped")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(open)
                .addAction(0, "Stop", stop)
                .build()
        }
    }
}

/** One partial wake lock per reason; every acquire carries the timeout [com.rumi.hermesvoice.core.background.WakeHolds] bounded. */
class AndroidWakeLocks(context: Context) : WakeLockPort {
    private val power = context.applicationContext.getSystemService(PowerManager::class.java)
    private val locks = HashMap<HoldReason, PowerManager.WakeLock>()

    @Synchronized
    override fun acquire(reason: HoldReason, timeoutMs: Long) {
        val lock = locks.getOrPut(reason) {
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HermesVoice:${reason.name.lowercase()}").apply { setReferenceCounted(false) }
        }
        lock.acquire(timeoutMs)
    }

    @Synchronized
    override fun release(reason: HoldReason) {
        locks[reason]?.takeIf { it.isHeld }?.release()
    }
}
