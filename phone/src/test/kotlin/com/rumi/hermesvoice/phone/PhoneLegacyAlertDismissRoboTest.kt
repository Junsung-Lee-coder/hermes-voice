package com.rumi.hermesvoice.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.notify.FinalReply
import com.rumi.hermesvoice.core.notify.FinalReplySource
import com.rumi.hermesvoice.core.notify.ReplyAlert
import com.rumi.hermesvoice.core.notify.ReplyAlertLedger
import com.rumi.hermesvoice.core.notify.ReplyAlertResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * SCRATCH HARNESS (never packaged). Alerts posted by the previously installed build (R4: no session extra in the notification, an
 * immutable explicit activity PendingIntent keyed by identity.hashCode() and the conversation/identity data URI) are removed when
 * their exact conversation is shown, through the REAL ReplyAlertNotifier / ReplyAlerts and Robolectric's NotificationManager.
 *
 * BOUNDARY: Robolectric's PendingIntent registry and NotificationManager are an in-process approximation of the real OS. They prove
 * the code's own decisions (which alerts it matches, that the probe passes FLAG_NO_CREATE and never creates, updates or sends); the
 * real system_server token registry, cross-process PendingIntent equality and a real notification shade are NOT_RUN here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneLegacyAlertDismissRoboTest : PhoneRoboBase() {
    private lateinit var manager: NotificationManager
    private var counter = 0

    @Before fun setUp() {
        baseSetUp()
        manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After fun tearDown() = baseTearDown()

    private val sessionA = "20260101_120000_aaaaaaaa"
    private val sessionB = "20260101_130000_bbbbbbbb"
    private val immutableUpdate = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun replyAlerts(): List<StatusBarNotification> = manager.activeNotifications.filter {
        it.notification.channelId == "reply_arrival" || it.notification.channelId == "reply_arrival_quiet"
    }

    private fun tags() = replyAlerts().map { it.tag }.toSet()

    private fun canonicalIntent(session: String, identity: String, marker: String? = null): Intent =
        ReplyAlertNotifier.openIntent(app, ReplyAlert(identity, session)).also { if (marker != null) it.putExtra("marker", marker) }

    /** What the previous build posted: the canonical immutable activity intent, and NO session extra on the notification. */
    private fun legacyContentIntent(session: String, identity: String, marker: String? = null): PendingIntent =
        PendingIntent.getActivity(app, identity.hashCode(), canonicalIntent(session, identity, marker), immutableUpdate)

    private fun legacy(session: String, identity: String = "chat-n${++counter}#reply", quiet: Boolean = false, marker: String? = null): String {
        val notification = Notification.Builder(app, if (quiet) "reply_arrival_quiet" else "reply_arrival")
            .setSmallIcon(R.drawable.ic_stat_voice).setContentTitle("Hermes replied").setContentText("A new reply is ready")
            .setContentIntent(legacyContentIntent(session, identity, marker)).setAutoCancel(true).build()
        assertTrue("a legacy alert carries no session extra", !notification.extras.containsKey("hv_reply_session"))
        manager.notify(identity, 7, notification)
        assertTrue(identity in tags())
        return identity
    }

    private fun custom(identity: String?, channel: String = "reply_arrival", id: Int = 7, intent: PendingIntent?, extras: Bundle? = null) {
        val builder = Notification.Builder(app, channel).setSmallIcon(R.drawable.ic_stat_voice).setContentTitle("custom")
        if (intent != null) builder.setContentIntent(intent)
        if (extras != null) builder.addExtras(extras)
        if (identity == null) manager.notify(id, builder.build()) else manager.notify(identity, id, builder.build())
    }

    @Test fun L01_aStackOfLegacyAlertsOnBothChannelsIsRemovedForItsConversationOnly() {
        val a1 = legacy(sessionA)
        val a2 = legacy(sessionA, quiet = true)
        val b1 = legacy(sessionB)
        val b2 = legacy(sessionB, quiet = true)
        assertEquals(2, ReplyAlertNotifier(app).dismissConversation(sessionA))
        assertEquals("only B's alerts remain", setOf(b1, b2), tags())
        assertTrue(a1 !in tags() && a2 !in tags())
        assertEquals(2, ReplyAlertNotifier(app).dismissConversation(sessionB))
        assertTrue(tags().isEmpty())
    }

    @Test fun L02_aNotifierCreatedAfterProcessRecreationFindsTheLegacyStackFromPlatformStateAlone() {
        val a = legacy(sessionA)
        val b = legacy(sessionB)
        val fresh = ReplyAlertNotifier(app.applicationContext)
        assertEquals(1, fresh.dismissConversation(sessionA))
        assertEquals(setOf(b), tags())
        assertTrue(a !in tags())
        assertEquals("nothing left for A: a quiet no-op", 0, ReplyAlertNotifier(app).dismissConversation(sessionA))
    }

    /** The app's REAL ledger claims [identity] by showing it once; the posted alert is then replaced by the previous build's form under the same tag. */
    private fun claimedLegacy(session: String): String {
        val identity = ReplyAlert.identityOf("chat-legacy-${++counter}", "reply")
        val first = FinalReply(identity, session, VoiceOrigin.PHONE, false, false, FinalReplySource.TEXT, text = "old answer")
        assertEquals(ReplyAlertResult.SHOWN_HERE, runBlocking { app.replyAlerts.dispatch(first) })
        legacy(session, identity)
        assertTrue("the posted alert is now the legacy form", !manager.activeNotifications.single { it.tag == identity }.notification.extras.containsKey("hv_reply_session"))
        return identity
    }

    @Test fun L03_theRealOpenPathDismissesLegacyAlerts_theLedgerStaysClaimed_andALaterDistinctReplyAlertsAgain() {
        val legacyA = claimedLegacy(sessionA)
        val legacyB = claimedLegacy(sessionB)
        app.replyAlerts.onConversationOpened(sessionA)
        assertEquals(setOf(legacyB), tags())
        val replay = FinalReply(legacyA, sessionA, VoiceOrigin.PHONE, false, false, FinalReplySource.TEXT, text = "again")
        assertEquals("a dismissed identity is never posted again", ReplyAlertResult.DUPLICATE, runBlocking { app.replyAlerts.dispatch(replay) })
        assertEquals(setOf(legacyB), tags())
        val later = ReplyAlert.identityOf("chat-later", "reply")
        val distinct = FinalReply(later, sessionA, VoiceOrigin.PHONE, false, false, FinalReplySource.TEXT, text = "newer answer")
        assertEquals(ReplyAlertResult.SHOWN_HERE, runBlocking { app.replyAlerts.dispatch(distinct) })
        assertEquals(setOf(legacyB, later), tags())
        app.replyAlerts.onConversationOpened(sessionA)
        assertEquals("the new alert has the extra and is removed by it", setOf(legacyB), tags())
    }

    @Test fun L04_newAlertsAreStillMatchedByTheirExtra_andConflictingOrMalformedExtrasAreNeverOverriddenByTheContentIntent() {
        val notifier = ReplyAlertNotifier(app)
        assertTrue(notifier.show(ReplyAlert("fresh-1#reply", sessionA)))
        assertTrue(notifier.show(ReplyAlert("fresh-2#reply", sessionB), null, true))
        custom("conflict#reply", intent = legacyContentIntent(sessionA, "conflict#reply"), extras = Bundle().apply { putString("hv_reply_session", sessionB) })
        custom("integer#reply", intent = legacyContentIntent(sessionA, "integer#reply"), extras = Bundle().apply { putInt("hv_reply_session", 5) })
        custom("blank#reply", intent = legacyContentIntent(sessionA, "blank#reply"), extras = Bundle().apply { putString("hv_reply_session", "") })
        custom("nullvalue#reply", intent = legacyContentIntent(sessionA, "nullvalue#reply"), extras = Bundle().apply { putString("hv_reply_session", null) })
        assertEquals("only the exact extra of A", 1, notifier.dismissConversation(sessionA))
        assertEquals(setOf("fresh-2#reply", "conflict#reply", "integer#reply", "blank#reply", "nullvalue#reply"), tags())
        assertEquals("B: its own fresh alert and the one whose extra names B, nothing else", 2, notifier.dismissConversation(sessionB))
        assertEquals(setOf("integer#reply", "blank#reply", "nullvalue#reply"), tags())
    }

    @Test fun L05_unknownNullOrMismatchedLegacyAlertsArePreserved() {
        val notifier = ReplyAlertNotifier(app)
        custom("nointent#reply", intent = null)
        custom(null, intent = legacyContentIntent(sessionA, "notag#reply"))
        custom("bad tag!", intent = legacyContentIntent(sessionA, "badtag#reply"))
        custom("other-request#reply", intent = PendingIntent.getActivity(app, 12345, canonicalIntent(sessionA, "other-request#reply"), immutableUpdate))
        custom("other-action#reply", intent = PendingIntent.getActivity(app, "other-action#reply".hashCode(),
            canonicalIntent(sessionA, "other-action#reply").setAction("elsewhere"), immutableUpdate))
        custom("other-session#reply", intent = legacyContentIntent(sessionB, "other-session#reply"))
        custom("unregistered#reply", intent = PendingIntent.getActivity(app, 7, Intent(app, MainActivity::class.java).setAction("x"), immutableUpdate))
        val before = manager.activeNotifications.map { it.tag to it.id }.toSet()
        assertEquals(0, notifier.dismissConversation(sessionA))
        assertEquals("every non-matching alert is preserved", before, manager.activeNotifications.map { it.tag to it.id }.toSet())
        assertEquals("the one that names B is B's", 1, notifier.dismissConversation(sessionB))
        assertTrue("other-session#reply" !in tags())
        assertEquals(0, notifier.dismissConversation("not a session id!"))
        assertEquals(0, notifier.dismissConversation(""))
        assertEquals(before.size - 1, manager.activeNotifications.size)
    }

    @Test fun L06_aHashCollisionOfTwoIdentitiesNeverMatchesTheOtherConversation() {
        val ids = listOf("AaAa", "AaBB", "BBAa", "BBBB")
        assertEquals("the identities really collide", 1, ids.map { it.hashCode() }.toSet().size)
        legacy(sessionA, identity = "AaAa")
        legacy(sessionB, identity = "BBBB")
        // This tag's content intent is the colliding token of ANOTHER identity for A: same request code, different data URI.
        custom("AaBB", intent = legacyContentIntent(sessionA, "BBAa"))
        assertEquals("only the alert whose own token is A's", 1, ReplyAlertNotifier(app).dismissConversation(sessionA))
        assertEquals(setOf("BBBB", "AaBB"), tags())
        assertEquals(1, ReplyAlertNotifier(app).dismissConversation(sessionB))
        assertEquals("the colliding mismatch is never claimed", setOf("AaBB"), tags())
    }

    @Test fun L07_theProbeNeverCreatesUpdatesOrSendsAnything() {
        val matchA = legacy(sessionA, marker = "original-a")
        val keepB = legacy(sessionB, marker = "original-b")
        custom("probe-only#reply", intent = legacyContentIntent(sessionB, "probe-only#reply", "original-probe"))
        val registeredB = legacyContentIntent(sessionB, keepB, "original-b")
        val savedBefore = shadowOf(registeredB).savedIntent.getStringExtra("marker")
        assertEquals("original-b", savedBefore)
        assertNull("the probe's target does not exist yet", PendingIntent.getActivity(app, "probe-only#reply".hashCode(),
            canonicalIntent(sessionA, "probe-only#reply"), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
        ReplyAlertNotifier(app).dismissConversation(sessionA)
        assertTrue(matchA !in tags())
        assertNull("a probe for a non-matching conversation did not create the token it asked about", PendingIntent.getActivity(app,
            "probe-only#reply".hashCode(), canonicalIntent(sessionA, "probe-only#reply"), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
        assertNotNull(PendingIntent.getActivity(app, keepB.hashCode(), canonicalIntent(sessionB, keepB), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
        val untouched = manager.activeNotifications.single { it.tag == keepB }.notification.contentIntent
        assertEquals("a surviving alert's token keeps its original saved intent", "original-b", shadowOf(untouched).savedIntent.getStringExtra("marker"))
        assertNull("no activity was started by the probe (nothing was sent)", shadowOf(app).nextStartedActivity)
    }

    @Test fun L08_unrelatedNotificationsAndStateAreInvariant() {
        val a = legacy(sessionA)
        val b = legacy(sessionB)
        custom("service", channel = "hermes_service", id = 41, intent = legacyContentIntent(sessionA, "service"))
        custom("wake-like#reply", channel = "wake", id = 8, intent = legacyContentIntent(sessionA, "wake-like#reply"))
        custom("other-channel#reply", channel = "recording", id = 7, intent = legacyContentIntent(sessionA, "other-channel#reply"))
        custom("same-channel-other-id#reply", id = 8, intent = legacyContentIntent(sessionA, "same-channel-other-id#reply"))
        val ledgerBefore = app.localStore.getString(ReplyAlertLedger.KEY)
        val others = manager.activeNotifications.map { it.tag to it.id }.toSet() - setOf(a to 7, b to 7)
        assertEquals(1, ReplyAlertNotifier(app).dismissConversation(sessionA))
        assertEquals("service, wake, other-channel and other-id notifications are untouched", others + (b to 7),
            manager.activeNotifications.map { it.tag to it.id }.toSet())
        assertEquals("the durable ledger is not touched by a dismissal", ledgerBefore, app.localStore.getString(ReplyAlertLedger.KEY))
    }
}
