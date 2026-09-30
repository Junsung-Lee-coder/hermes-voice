package com.rumi.hermesvoice.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.rumi.hermesvoice.core.background.BackgroundText
import com.rumi.hermesvoice.core.background.WatchVoiceStatus

/**
 * Keeps the Watch process in the foreground-service state for a background session the user
 * started from the visible app, with an ongoing notification that says what it is doing and a
 * Stop action. It holds no voice logic: listening, recording, sending and playback belong to the
 * application's [WatchVoiceRuntime] and [WatchApp].
 *
 * Types: `mediaPlayback` always (replies play with the app hidden); `microphone` only when the
 * session was started, or re-typed, from the visible app with the permission granted and the
 * Phone's wake settings including the Watch. The platform refuses the microphone type from the
 * background; that refusal is caught and reported, never worked around. Not sticky: a session the
 * system ended stays ended until the user starts it again.
 */
class WatchVoiceService : Service() {
    private var generation = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val voice = WatchApp.from(this).voice
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "background stop requested (notification)")
                voice.stopBackground()
                if (running !== this) stopSelf()
            }
            ACTION_START -> {
                generation = voice.background.generation
                running = this
                val microphone = intent.getBooleanExtra(EXTRA_MICROPHONE, false)
                when {
                    // Stopped before the platform delivered this start: enter as asked (the platform requires it), then end.
                    !voice.background.status.running -> {
                        enter(false)
                        finish()
                    }
                    enter(microphone) -> Unit
                    microphone && enter(false) -> voice.onMicrophoneRefused(generation)
                    else -> {
                        running = null
                        stopSelf()
                        voice.onServiceGone(generation)
                    }
                }
            }
            // Never redelivered or restarted by the system (START_NOT_STICKY): nothing to resume.
            else -> if (running !== this) stopSelf()
        }
        return START_NOT_STICKY
    }

    /** (Re)enters the foreground with the type for [microphone]; false when the platform refused. */
    private fun enter(microphone: Boolean): Boolean = runCatching {
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
            (if (microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        val voice = WatchApp.from(this).voice
        val status = voice.coordinator.status.let { if (it.session.running) it else it.copy(session = it.session.copy(running = true, microphone = microphone)) }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this, status), type)
        Log.i(TAG, "background service foreground microphone=$microphone generation=$generation")
    }.onFailure { Log.w(TAG, "background service type refused microphone=$microphone: ${it.javaClass.simpleName}") }.isSuccess

    /** Adds or drops the microphone type. Adding is only ever asked for while the app is on screen. */
    fun retype(microphone: Boolean): Boolean = enter(microphone)

    /** Shows what the session is doing now. */
    fun refresh(status: WatchVoiceStatus) {
        if (!status.session.running) return
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(this, status)) }
    }

    /** The user's stop: leave the foreground, remove the notification, end. */
    fun finish() {
        if (running === this) running = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "background service finished generation=$generation")
    }

    override fun onDestroy() {
        if (running === this) {
            // Not the user's stop: the session is shown as paused and is not started again by the app.
            running = null
            WatchApp.from(this).voice.onServiceGone(generation)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HermesVoiceWatch"
        private const val ACTION_START = "com.rumi.hermesvoice.watch.action.BACKGROUND_START"
        private const val ACTION_STOP = "com.rumi.hermesvoice.watch.action.BACKGROUND_STOP"
        private const val EXTRA_MICROPHONE = "microphone"
        private const val NOTIFICATION_ID = 41
        const val CHANNEL = "background"

        /** The running service of the current session (same process, main thread). */
        @Volatile var running: WatchVoiceService? = null
            private set

        fun start(context: Context, microphone: Boolean): Intent =
            Intent(context, WatchVoiceService::class.java).setAction(ACTION_START).putExtra(EXTRA_MICROPHONE, microphone)

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Background operation", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Hermes Voice listens or plays replies with the app closed"
                })
        }

        private fun notification(context: Context, status: WatchVoiceStatus): Notification {
            val open = PendingIntent.getActivity(context, 0, Intent(context, WatchActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(context, 1, Intent(context, WatchVoiceService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_voice)
                .setContentTitle("Hermes Voice")
                .setContentText(BackgroundText.watchNotification(status.session, status.loop) ?: "Stopped")
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
