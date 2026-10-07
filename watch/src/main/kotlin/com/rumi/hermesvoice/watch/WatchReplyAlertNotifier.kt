package com.rumi.hermesvoice.watch

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
 * The Watch's arrival alert for an unspoken final answer the Phone told it about ([com.rumi.hermesvoice.core.watchlink.ReplyAlertMessage]).
 * Local-only: it is owned by this Watch alone and never bridged to the Phone, which showed none for it. Its own channel (default
 * importance, the system's vibration/sound, permission, channel and Do Not Disturb are the system's), generic text, a private
 * lock screen, and a tap that opens exactly the conversation it names.
 */
class WatchReplyAlertNotifier(private val context: Context) : ReplyAlertPort {
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
            .setLocalOnly(true)
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

        /** Explicit and immutable; the data URI keeps each conversation's and answer's intent distinct. */
        fun openIntent(context: Context, alert: ReplyAlert): Intent =
            Intent(context, WatchActivity::class.java).setAction(ReplyAlertContent.ACTION_OPEN_REPLY)
                .setData(Uri.Builder().scheme("hermesvoice").authority("reply").appendPath(alert.storedSessionId)
                    .appendQueryParameter("r", alert.identity).build())
                .putExtra(ReplyAlertContent.EXTRA_SESSION_ID, alert.storedSessionId)
                .putExtra(ReplyAlertContent.EXTRA_IDENTITY, alert.identity)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
