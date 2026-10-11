package com.rumi.hermesvoice.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import com.rumi.hermesvoice.core.InMemoryKeyValueStore
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.notify.FinalReply
import com.rumi.hermesvoice.core.notify.FinalReplySource
import com.rumi.hermesvoice.core.notify.ReplyAlert
import com.rumi.hermesvoice.core.notify.ReplyAlertContent
import com.rumi.hermesvoice.core.notify.ReplyAlertLedger
import com.rumi.hermesvoice.core.notify.ReplyAlertResult
import com.rumi.hermesvoice.core.notify.ReplyAlerts
import com.rumi.hermesvoice.core.notify.ReplyPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * SCRATCH HARNESS (never packaged): the Phone's REAL notifier behind the REAL alert decision (admission, durable dedupe, audio
 * suppression) with synthetic answers. These need the feature's new types, so they are not part of the baseline run. The platform
 * notification service is Robolectric's: a payload check, not a lock screen, Do Not Disturb or a physical notification shade.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class PhoneReplyPreviewSurfaceTest {
    private lateinit var app: PhoneApp
    private lateinit var manager: NotificationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val work = "20260101_130000_work0001"
    private val home = "20260101_130000_home0001"

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
        app = RuntimeEnvironment.getApplication() as PhoneApp
        manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() = scope.cancel()

    private fun shown() = manager.activeNotifications.filter { it.notification.channelId == ReplyAlertContent.CHANNEL_ID }
    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private fun big(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

    private fun reply(identity: String, session: String, text: String?, heard: Boolean = false, cancelled: Boolean = false) =
        FinalReply(identity, session, VoiceOrigin.PHONE, heard, cancelled, FinalReplySource.OWN, text = text)

    private fun alerts() = ReplyAlerts(ReplyAlertLedger(InMemoryKeyValueStore()), ReplyAlertNotifier(app), scope)

    @Test fun U01_theCollapsedAndExpandedTextBindToTheirOwnReply_andThePublicVersionIsContentFree() = runBlocking {
        val decision = alerts()
        assertEquals(ReplyAlertResult.SHOWN_HERE, decision.dispatch(reply("turn-1#final", work, "First **answer**.\n\nDetails follow.")))
        assertEquals(ReplyAlertResult.SHOWN_HERE, decision.dispatch(reply("turn-2#final", home, "비공개 두 번째 답변입니다.")))
        val byTag = shown().associateBy { it.tag }
        assertEquals(setOf("turn-1#final", "turn-2#final"), byTag.keys)
        val one = byTag.getValue("turn-1#final").notification
        val two = byTag.getValue("turn-2#final").notification
        assertEquals("First answer. Details follow.", text(one))
        assertEquals("First answer.\n\nDetails follow.", big(one))
        assertEquals("비공개 두 번째 답변입니다.", text(two))
        assertEquals("비공개 두 번째 답변입니다.", big(two))
        assertEquals(work, shadowOf(one.contentIntent).savedIntent.getStringExtra(ReplyAlertContent.EXTRA_SESSION_ID))
        assertEquals(home, shadowOf(two.contentIntent).savedIntent.getStringExtra(ReplyAlertContent.EXTRA_SESSION_ID))
        for (n in listOf(one, two)) {
            assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
            assertEquals(ReplyAlertContent.PUBLIC_TITLE, n.publicVersion.extras.getString(Notification.EXTRA_TITLE))
            assertEquals(ReplyAlertContent.PUBLIC_BODY, text(n.publicVersion))
            assertNull(big(n.publicVersion))
            assertFalse(n.publicVersion.extras.toString().contains("answer") || n.publicVersion.extras.toString().contains("답변"))
        }
    }

    @Test fun U02_aReplayedAnswerIsOneAlert_andHeardOrCancelledAnswersAreNone() = runBlocking {
        val decision = alerts()
        assertEquals(ReplyAlertResult.SHOWN_HERE, decision.dispatch(reply("turn-1#final", work, "Once.")))
        assertEquals(ReplyAlertResult.DUPLICATE, decision.dispatch(reply("turn-1#final", work, "Once.")))
        assertEquals(ReplyAlertResult.SUPPRESSED_HEARD, decision.dispatch(reply("turn-2#final", work, "Heard.", heard = true)))
        assertEquals(ReplyAlertResult.SUPPRESSED_CANCELLED, decision.dispatch(reply("turn-3#final", work, "Stopped.", cancelled = true)))
        assertEquals(listOf("turn-1#final"), shown().map { it.tag })
        assertEquals("Once.", text(shown().single().notification))
    }

    @Test fun U03_aMissingPermissionOrDisabledChannelShowsNothing_evenWithAnAnswer() {
        val notifier = ReplyAlertNotifier(app)
        val preview = ReplyPreview.of("Hidden answer")
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(notifier.show(ReplyAlert("turn-1#final", work), preview))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(notifier.show(ReplyAlert("turn-1#final", work), preview))
        assertTrue(shown().isEmpty())
    }

    @Test fun U04_withoutAPreviewTheAlertIsTheGenericOne() {
        assertTrue(ReplyAlertNotifier(app).show(ReplyAlert("turn-1#final", work)))
        val n = shown().single().notification
        assertEquals(ReplyAlertContent.BODY, text(n))
        assertNull(big(n))
    }

    @Test fun U05_theSameIdentityReplacesItsNotificationInsteadOfStackingAnotherAnswer() {
        val notifier = ReplyAlertNotifier(app)
        assertTrue(notifier.show(ReplyAlert("turn-1#final", work), ReplyPreview.of("older text")))
        assertTrue(notifier.show(ReplyAlert("turn-1#final", work), ReplyPreview.of("newer text")))
        assertEquals(1, shown().size)
        assertEquals("newer text", text(shown().single().notification))
    }
}
