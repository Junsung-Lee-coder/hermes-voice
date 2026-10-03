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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.rumi.hermesvoice.core.background.BackgroundText
import com.rumi.hermesvoice.core.background.PhoneWakeStatus

/**
 * Keeps the Phone process in the foreground-service state while the user has switched on
 * "listen for the wake phrase when the app is closed", with an ongoing notification that says
 * what it really does and a Stop action. It holds no logic: listening, recording and sending
 * belong to the application's [PhoneBackgroundRuntime]. It is separate from the relay
 * ([PhoneRelayService]), which keeps working on its own whether this runs or not.
 *
 * Types: `mediaPlayback` always (a reply to a request made with the app closed plays here);
 * `microphone` only when started, or re-typed, from the visible app with nothing blocking it. The
 * platform refuses the microphone type from the background; that refusal is caught and reported,
 * never worked around. Not sticky: a session the system ended stays ended until the user starts it again.
 */
class PhoneWakeService : Service() {
    private var generation = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val wake = PhoneApp.from(this).phoneWake
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "phone background listening stop requested (notification)")
                wake.stop()
                if (running !== this) stopSelf()
            }
            ACTION_START -> {
                generation = wake.background.session.generation
                running = this
                val microphone = intent.getBooleanExtra(EXTRA_MICROPHONE, false)
                when {
                    // Stopped before the platform delivered this start: enter as asked (the platform requires it), then end.
                    !wake.background.session.status.running -> {
                        enter(false)
                        finish()
                    }
                    enter(microphone) -> Unit
                    microphone && enter(false) -> wake.background.onMicrophoneRefused(generation)
                    else -> {
                        running = null
                        stopSelf()
                        wake.background.onServiceGone(generation)
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
        val status = PhoneApp.from(this).phoneWake.background.status.let {
            if (it.session.running) it else it.copy(session = it.session.copy(running = true, microphone = microphone))
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this, status), type)
        Log.i(TAG, "phone background listening service foreground microphone=$microphone generation=$generation")
    }.onFailure { Log.w(TAG, "phone background listening service type refused microphone=$microphone: ${it.javaClass.simpleName}") }.isSuccess

    /** Adds or drops the microphone type. Adding is only ever asked for while the app is on screen. */
    fun retype(microphone: Boolean): Boolean = enter(microphone)

    /** Shows what the session is doing now. */
    fun refresh(status: PhoneWakeStatus) {
        if (!status.session.running) return
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(this, status)) }
    }

    /** The user's stop: leave the foreground, remove the notification, end. */
    fun finish() {
        if (running === this) running = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "phone background listening service finished generation=$generation")
    }

    override fun onDestroy() {
        if (running === this) {
            // Not the user's stop: shown as paused, and not started again by the app.
            running = null
            PhoneApp.from(this).phoneWake.background.onServiceGone(generation)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HermesVoiceWake"
        private const val ACTION_START = "com.rumi.hermesvoice.phone.action.WAKE_START"
        private const val ACTION_STOP = "com.rumi.hermesvoice.phone.action.WAKE_STOP"
        private const val EXTRA_MICROPHONE = "microphone"
        private const val NOTIFICATION_ID = 43
        const val CHANNEL = "background_wake"

        /** The running service of the current session (same process, main thread). */
        @Volatile var running: PhoneWakeService? = null
            private set

        fun start(context: Context, microphone: Boolean): Intent =
            Intent(context, PhoneWakeService::class.java).setAction(ACTION_START).putExtra(EXTRA_MICROPHONE, microphone)

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Wake phrase in the background", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Hermes Voice may listen for the wake phrase with the app closed"
                })
        }

        private fun notification(context: Context, status: PhoneWakeStatus): Notification {
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(context, 2, Intent(context, PhoneWakeService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_voice)
                .setContentTitle("Hermes Voice")
                .setContentText(BackgroundText.phoneWakeNotification(status) ?: "Stopped")
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
