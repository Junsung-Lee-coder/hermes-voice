package com.rumi.hermesvoice.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import com.rumi.hermesvoice.core.notify.ReplyAlert
import com.rumi.hermesvoice.core.notify.ReplyPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Phone's new-reply notification while headset-only output is chosen (amendment J): shown on its own low-importance channel and
 * flagged silent, with the same text, tap and identity as the normal one. Only what the app asks the platform for is checked; whether
 * a paired watch or the OS bridge makes any sound is not exercised here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneQuietReplyAlertTest {
    private lateinit var notifier: ReplyAlertNotifier
    private lateinit var manager: NotificationManager
    private val alert = ReplyAlert("turn-1#later1", "20260101_120000_workwork")
    private val preview = ReplyPreview.of("Done.")

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager = app.getSystemService(NotificationManager::class.java)
        ReplyAlertNotifier.createChannel(app)
        notifier = ReplyAlertNotifier(app)
    }

    @Test fun `a quiet alert uses the low-importance channel and is flagged silent`() {
        assertTrue(notifier.show(alert, preview, quiet = true))
        val shown = manager.activeNotifications.single()
        assertEquals("reply_arrival_quiet", shown.notification.channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel("reply_arrival_quiet").importance)
        assertEquals(Notification.GROUP_ALERT_SUMMARY, shown.notification.groupAlertBehavior)
        assertNull(shown.notification.sound)
        assertEquals("turn-1#later1", shown.tag)
    }

    @Test fun `a quiet alert keeps the visible text the tap and the not-local-only bridge behaviour`() {
        notifier.show(alert, preview, quiet = true)
        val n = manager.activeNotifications.single().notification
        assertEquals("Done.", n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertNotNull(n.contentIntent)
        assertEquals("the paired-system bridge is left as it was", 0, n.flags and Notification.FLAG_LOCAL_ONLY)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test fun `a normal alert is unchanged - the default channel and no silent flag`() {
        notifier.show(alert, preview, quiet = false)
        val n = manager.activeNotifications.single().notification
        assertEquals("reply_arrival", n.channelId)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel("reply_arrival").importance)
        assertTrue(n.groupAlertBehavior != Notification.GROUP_ALERT_SUMMARY)
    }

    @Test fun `the same identity is one notification whichever way it is shown`() {
        notifier.show(alert, preview, quiet = true)
        notifier.show(alert, preview, quiet = true)
        assertEquals(1, manager.activeNotifications.size)
    }
}
