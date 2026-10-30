package com.rumi.hermesvoice.phone

import android.Manifest
import android.app.NotificationManager
import com.rumi.hermesvoice.core.KeyValueStore
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.auth.HermesBearerSession
import com.rumi.hermesvoice.core.notify.FinalReply
import com.rumi.hermesvoice.core.notify.FinalReplySource
import com.rumi.hermesvoice.core.sessions.ReceivedConversations
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A final answer's durable intake (ReplyAlerts -> FinalRecovery.capture) while a background recovery run waits on unrelated dashboard
 * IO. The real PhoneApp, ReplyAlerts, FinalRecovery, AppSessionRepository and HermesDashboardClient over real HTTP against the dashboard
 * contract double, where the recovery read is held. Robolectric's NotificationManager; no device, provider or real network timing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryCaptureRoboTest : PhoneRoboBase() {
    private val entered = CountDownLatch(1); private val release = CountDownLatch(1)
    @Before fun setUp() { baseSetUp(); shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS) }
    @After fun tearDown() { release.countDown(); fake.reconciliationMessagesHook = null; fake.listPageHook = null; baseTearDown() }

    private fun alerts() = app.getSystemService(NotificationManager::class.java).activeNotifications
        .filter { it.notification.channelId in setOf("reply_arrival", "reply_arrival_quiet") && !it.tag.endsWith("#notice") }
    /** The recovery run's history read of [id] waits at the dashboard double until released. */
    private fun holdRecoveryRead(id: String) {
        fake.reconciliationMessagesHook = { session, _, _ -> if (session == id) { entered.countDown(); check(release.await(30, TimeUnit.SECONDS)) { "fixture recovery latch not released" } }; 0 }
    }
    private fun startRecovery() = app.appScope.launch { app.finalRecovery.reconcile() }.also {
        assertTrue("a recovery run is waiting on unrelated dashboard IO", await(20_000) { entered.count == 0L })
    }
    private fun finalOf(id: String, text: String, uid: String): Long {
        fake.injectFinalRow(id, text, 500.0, uid)
        return fake.rows.getValue(id).messages.last().getLong("id")
    }
    private fun reply(id: String, address: Long, cancelled: Boolean = false) = FinalReply("final-$id-$address", id, VoiceOrigin.PHONE, heard = false,
        cancelled = cancelled, source = FinalReplySource.OWN, text = if (cancelled) null else "answer of $id", canonicalRowId = address, namespace = app.bodyScope())

    @Test fun R25_01_aFinalIsTakenIntoDurableCustodyWhileUnrelatedRecoveryHistoryIsHeldAndItsAlertFollowsOnce() {
        val blocked = conversation("blocked"); val target = conversation("target")
        holdRecoveryRead(blocked)
        val run = startRecovery()
        val address = finalOf(target, "answer of target", "target-uid")
        val first = app.appScope.async { app.replyAlerts.deliver(reply(target, address)) }
        val second = app.appScope.async { app.replyAlerts.deliver(reply(target, address)) }
        val (tookIn, expected) = try {
            Pair(await(5_000) { first.isCompleted && second.isCompleted }, app.received.scoped(app.bodyScope()!!) { expected(target) }.orEmpty())
        } finally { release.countDown() }
        assertTrue("both deliveries finished before the unrelated recovery read was released", tookIn)
        assertTrue(runBlocking { first.await() && second.await() })
        assertEquals("the expectation is durable at once", setOf(address), expected)
        runBlocking { withTimeout(20_000) { run.join() } }
        assertTrue("the durable obligation is honoured by a following recovery run, once", await(20_000) { alerts().size == 1 })
        settle(); idle(1_000); settle()
        assertEquals(1, alerts().size)
        assertTrue(fake.rawPrompts.isEmpty())
    }

    @Test fun R25_02_aFinalIsTakenInWhileTheRecoveryRunsOwnListReadIsHeld() {
        val target = conversation("target"); conversation("other")
        fake.listPageHook = { _, _ -> entered.countDown(); check(release.await(30, TimeUnit.SECONDS)) { "fixture list latch not released" } }
        val run = startRecovery()
        val address = finalOf(target, "answer of target", "target-uid")
        val delivery = app.appScope.async { app.replyAlerts.deliver(reply(target, address)) }
        val tookIn = try { await(5_000) { delivery.isCompleted } } finally { release.countDown() }
        assertTrue("the delivery finished before the recovery's list read was released", tookIn)
        assertTrue(runBlocking { delivery.await() })
        runBlocking { withTimeout(20_000) { run.join() } }
        assertTrue(await(20_000) { alerts().size == 1 })
        assertTrue(fake.rawPrompts.isEmpty())
    }

    @Test fun R25_03_aNewLoginsFinalIsTakenInWhileTheOldLoginsRecoveryReadIsHeldAndOnlyTheNewLoginAlerts() {
        val blocked = conversation("blocked"); val target = conversation("target")
        val oldScope = app.bodyScope()!!
        holdRecoveryRead(blocked)
        val run = startRecovery()
        app.revokeUnplayedReplies(); app.tokens.clear()
        app.tokens.save(HermesBearerSession(fake.accessToken, fake.refreshToken, null, "basic", "jun")); app.wiring()
        val newScope = app.bodyScope()!!
        assertNotEquals("a new login generation", oldScope, newScope)
        val address = finalOf(target, "answer of target", "target-uid")
        val delivery = app.appScope.async { app.replyAlerts.deliver(reply(target, address)) }
        val (tookIn, expected) = try {
            Pair(await(5_000) { delivery.isCompleted }, app.received.scoped(newScope) { expected(target) }.orEmpty())
        } finally { release.countDown() }
        assertTrue("the new login's delivery finished while the old login's recovery read was held", tookIn)
        assertTrue(runBlocking { delivery.await() })
        assertEquals("its expectation is durable under the new login at once", setOf(address), expected)
        runBlocking { withTimeout(20_000) { run.join() } }
        assertTrue("the new login's obligation is honoured by a run of the new login", await(20_000) { alerts().size == 1 })
        settle(); idle(1_000); settle()
        assertEquals("the old login's run, released after the change, adds nothing", 1, alerts().size)
        assertTrue(fake.rawPrompts.isEmpty())
    }

    @Test fun R25_04_aConversationRemovedWhileItsRecoveryReadIsHeldIsNotIngestedWhenTheReadReturns() {
        val removed = conversation("removed")
        val address = finalOf(removed, "answer of removed", "removed-uid")
        holdRecoveryRead(removed)
        val run = startRecovery()
        app.finalRecovery.remove(setOf(removed))
        release.countDown()
        runBlocking { withTimeout(20_000) { run.join() } }
        settle(); idle(1_000); settle()
        assertNull("the held page of a removed conversation is not ingested", app.received.canonicalRow(removed, address))
        assertTrue(alerts().isEmpty())
        assertTrue(fake.rawPrompts.isEmpty())
    }

    @Test fun R25_05_aCancelledFinalIsSuppressedWhileUnrelatedRecoveryIsHeldAndNeverAlerts() {
        val blocked = conversation("blocked"); val target = conversation("target")
        holdRecoveryRead(blocked)
        val run = startRecovery()
        val address = finalOf(target, "stopped answer", "stopped-uid")
        val delivery = app.appScope.async { app.replyAlerts.deliver(reply(target, address, cancelled = true)) }
        val tookIn = try { await(5_000) { delivery.isCompleted } } finally { release.countDown() }
        assertTrue("the Stop of a final is recorded while unrelated recovery IO is held", tookIn)
        assertTrue(runBlocking { delivery.await() })
        runBlocking { withTimeout(20_000) { run.join() } }
        runBlocking { withTimeout(20_000) { app.finalRecovery.reconcile() } }
        settle()
        assertTrue("a stopped final never alerts", alerts().isEmpty())
        assertTrue(fake.rawPrompts.isEmpty())
    }

    @Test fun R25_06_aFinalWhoseCustodyCannotBeSavedIsNotClaimedHandledAndNeverAlerts() {
        val target = conversation("target")
        val failing = object : KeyValueStore by app.localStore { override fun commitString(key: String, value: String) = false }
        ReflectionHelpers.setField(app, "received\$delegate", lazyOf(ReceivedConversations(failing, app::bodyScope)))
        val address = finalOf(target, "answer of target", "target-uid")
        assertFalse("an unsaved intake is not reported handled", runBlocking { app.replyAlerts.deliver(reply(target, address)) })
        runBlocking { withTimeout(20_000) { app.finalRecovery.reconcile() } }
        settle()
        assertTrue(alerts().isEmpty())
        assertTrue(fake.rawPrompts.isEmpty())
    }
}
