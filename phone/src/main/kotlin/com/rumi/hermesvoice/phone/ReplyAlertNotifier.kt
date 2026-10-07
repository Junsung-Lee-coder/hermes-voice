package com.rumi.hermesvoice.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rumi.hermesvoice.core.notify.ReplyAlert
import com.rumi.hermesvoice.core.notify.ReplyAlertContent
import com.rumi.hermesvoice.core.notify.ReplyAlertPort

/**
 * The Phone's arrival alert for an unspoken final answer: its own channel (default importance, so the system's vibration or
 * sound applies; permission, channel and Do Not Disturb are the system's), generic text only, a private lock screen, and a
 * tap that opens exactly the conversation it names. Not local-only: the paired-system bridge may mirror it, and the app sends
 * no separate Watch alert for a Phone-target reply.
 */
class ReplyAlertNotifier(private val context: Context) : ReplyAlertPort {
    override fun show(alert: ReplyAlert): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return try {
            if (!allowed(manager)) return false
            manager.notify(alert.identity, NOTIFICATION_ID, build(alert))
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "reply alert not shown: ${error.javaClass.simpleName}")
            false
        }
    }

    private fun allowed(manager: NotificationManager): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(ReplyAlertContent.CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun build(alert: ReplyAlert): Notification {
        val open = PendingIntent.getActivity(context, alert.identity.hashCode(), openIntent(context, alert),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val publicVersion = NotificationCompat.Builder(context, ReplyAlertContent.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_voice)
            .setContentTitle(ReplyAlertContent.PUBLIC_TITLE)
            .setContentText(ReplyAlertContent.PUBLIC_BODY)
            .build()
        return NotificationCompat.Builder(context, ReplyAlertContent.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_voice)
            .setContentTitle(ReplyAlertContent.TITLE)
            .setContentText(ReplyAlertContent.BODY)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "HermesVoiceReply"
        const val NOTIFICATION_ID = 7

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(ReplyAlertContent.CHANNEL_ID, ReplyAlertContent.CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Tells you a reply arrived when it was not spoken"
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                })
        }

        /** Explicit and immutable: only this app's own entry opens it. The data URI keeps each conversation's and answer's intent distinct. */
        fun openIntent(context: Context, alert: ReplyAlert): Intent =
            Intent(context, MainActivity::class.java).setAction(ReplyAlertContent.ACTION_OPEN_REPLY)
                .setData(Uri.Builder().scheme("hermesvoice").authority("reply").appendPath(alert.storedSessionId)
                    .appendQueryParameter("r", alert.identity).build())
                .putExtra(ReplyAlertContent.EXTRA_SESSION_ID, alert.storedSessionId)
                .putExtra(ReplyAlertContent.EXTRA_IDENTITY, alert.identity)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
