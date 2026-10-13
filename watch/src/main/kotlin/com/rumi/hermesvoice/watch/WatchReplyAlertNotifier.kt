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
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rumi.hermesvoice.core.notify.ReplyAlert
import com.rumi.hermesvoice.core.notify.ReplyAlertContent
import com.rumi.hermesvoice.core.notify.ReplyAlertPort
import com.rumi.hermesvoice.core.voice.DestinationAllowlist

/**
 * The Watch's arrival alert for an unspoken final answer the Phone told it about ([com.rumi.hermesvoice.core.watchlink.ReplyAlertMessage]).
 * Local-only: it is owned by this Watch alone and never bridged to the Phone, which showed none for it. Its own channel (default
 * importance, the system's vibration/sound, permission, channel and Do Not Disturb are the system's), generic text, a private
 * lock screen, and a tap that opens exactly the conversation it names.
 */
class WatchReplyAlertNotifier(private val context: Context) : ReplyAlertPort {
    override fun show(alert: ReplyAlert): Boolean = show(alert, null, false)

    /** [quiet]: the headset is the private output, so this alert makes no sound (its low-importance channel, silent): visual only. */
    override fun show(alert: ReplyAlert, preview: com.rumi.hermesvoice.core.notify.ReplyPreview?, quiet: Boolean): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return try {
            if (!allowed(manager, quiet)) return false
            manager.notify(alert.identity, NOTIFICATION_ID, build(alert, quiet))
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "reply alert not shown: ${error.javaClass.simpleName}")
            false
        }
    }

    /** Removes every stacked reply alert of this Watch for [storedSessionId] (matched by its extra, or for an alert without one by its content intent: see [belongsTo]; so it survives a restart); nothing else is touched. */
    override fun dismissConversation(storedSessionId: String): Int {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return 0
        return try {
            var removed = 0
            for (posted in manager.activeNotifications) {
                if (posted.id != NOTIFICATION_ID || posted.packageName != context.packageName) continue
                val channel = posted.notification.channelId
                if (channel != ReplyAlertContent.CHANNEL_ID && channel != ReplyAlertContent.QUIET_CHANNEL_ID) continue
                if (!belongsTo(posted, storedSessionId)) continue
                manager.cancel(posted.tag, posted.id)
                removed += 1
            }
            removed
        } catch (error: RuntimeException) {
            Log.w(TAG, "reply alerts not dismissed: ${error.javaClass.simpleName}")
            0
        }
    }

    /**
     * An alert carrying the session extra belongs to exactly that conversation: a different, non-string or otherwise malformed value is
     * never second-guessed. Only an alert WITHOUT the extra (posted by the previously installed build, which kept the conversation in its
     * content intent alone) falls back to [legacyContentIntentIs].
     */
    private fun belongsTo(posted: StatusBarNotification, storedSessionId: String): Boolean {
        val extras = posted.notification.extras
        if (extras != null && extras.containsKey(ReplyAlertContent.EXTRA_SESSION_ID)) {
            return extras.getString(ReplyAlertContent.EXTRA_SESSION_ID) == storedSessionId
        }
        return legacyContentIntentIs(posted, storedSessionId)
    }

    /**
     * Read-only exact identity probe for a legacy alert: the previous build's content PendingIntent was immutable, explicit, and keyed by
     * request code = identity.hashCode() and the data URI naming conversation + identity. FLAG_NO_CREATE without FLAG_UPDATE_CURRENT returns
     * the already registered token or null; it never creates, updates or sends anything. The alert matches only when that token is the
     * alert's own content intent, so another conversation's alert (different data URI) or a hash collision never matches.
     */
    private fun legacyContentIntentIs(posted: StatusBarNotification, storedSessionId: String): Boolean {
        val identity = posted.tag ?: return false
        val shown = posted.notification.contentIntent ?: return false
        if (!ReplyAlert.isValidIdentity(identity) || !DestinationAllowlist.isValidSessionId(storedSessionId)) return false
        val existing = PendingIntent.getActivity(context, identity.hashCode(), openIntent(context, ReplyAlert(identity, storedSessionId)),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return false
        return existing == shown
    }

    private fun allowed(manager: NotificationManager, quiet: Boolean): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(if (quiet) ReplyAlertContent.QUIET_CHANNEL_ID else ReplyAlertContent.CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun build(alert: ReplyAlert, quiet: Boolean): Notification {
        val channelId = if (quiet) ReplyAlertContent.QUIET_CHANNEL_ID else ReplyAlertContent.CHANNEL_ID
        val open = PendingIntent.getActivity(context, alert.identity.hashCode(), openIntent(context, alert),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val publicVersion = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_voice)
            .setContentTitle(ReplyAlertContent.PUBLIC_TITLE)
            .setContentText(ReplyAlertContent.PUBLIC_BODY)
            .build()
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_voice)
            .setContentTitle(ReplyAlertContent.TITLE)
            .setContentText(ReplyAlertContent.BODY)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setLocalOnly(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addExtras(android.os.Bundle().apply { putString(ReplyAlertContent.EXTRA_SESSION_ID, alert.storedSessionId) })
        if (quiet) builder.setSilent(true)
        return builder.build()
    }

    companion object {
        private const val TAG = "HermesVoiceReply"
        const val NOTIFICATION_ID = 7

        fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(ReplyAlertContent.CHANNEL_ID, ReplyAlertContent.CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Tells you a reply arrived when it was not spoken"
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                })
            manager.createNotificationChannel(
                NotificationChannel(ReplyAlertContent.QUIET_CHANNEL_ID, ReplyAlertContent.QUIET_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Tells you a reply arrived while the headset is the only output; makes no sound"
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                    setSound(null, null)
                    enableVibration(false)
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
