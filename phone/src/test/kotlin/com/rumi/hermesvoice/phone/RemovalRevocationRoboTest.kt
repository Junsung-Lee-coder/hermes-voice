package com.rumi.hermesvoice.phone

import android.Manifest
import android.app.NotificationManager
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.net.HistoryMessage
import com.rumi.hermesvoice.core.net.HistoryPage
import com.rumi.hermesvoice.core.notify.FinalReply
import com.rumi.hermesvoice.core.notify.FinalReplySource
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A local conversation removal (FinalRecovery.remove, the call PhoneViewModel makes for a list's removedIds) published while a recovery
 * run is admitting a page of that conversation. The real PhoneApp, FinalRecovery, ReplyAlerts, RecentBodies, AppSessionRepository and
 * HermesDashboardClient over real HTTP against the dashboard contract double; Robolectric's NotificationManager.
 *
 * Observation only: FinalRecovery's removal-version map is replaced by one that returns the real value but, at the recovery commit's
 * own read of it (the second read of the conversation on a recovery thread), waits until the removal has either returned or is blocked
 * on a monitor that the paused commit thread holds. Nothing about the map's values or the removal changes. The removal runs on its own
 * thread because the Robolectric main thread is the test thread, which releases the commit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemovalRevocationRoboTest : PhoneRoboBase() {
    private val entered = CountDownLatch(1); private val go = CountDownLatch(1)
    @Before fun setUp() { baseSetUp(); shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS) }
    @After fun tearDown() { go.countDown(); baseTearDown() }

    private class ObservedVersions(private val target: String, private val testThread: Thread,
                                   private val entered: CountDownLatch, private val go: CountDownLatch) : ConcurrentHashMap<String, Long>() {
        private val reads = AtomicInteger()
        @Volatile var commit: Thread? = null
        override fun get(key: String): Long? {
            val value = super.get(key)
            if (key == target && Thread.currentThread() !== testThread && reads.incrementAndGet() == 2) {
                commit = Thread.currentThread(); entered.countDown()
                check(go.await(30, TimeUnit.SECONDS)) { "fixture commit latch not released" }
            }
            return value
        }
    }
    private fun alerts() = app.getSystemService(NotificationManager::class.java).activeNotifications
        .filter { it.notification.channelId in setOf("reply_arrival", "reply_arrival_quiet") && !it.tag.endsWith("#notice") }
    /** The JVM's one consistent record of [thread]'s state and the owner of the monitor it waits for (java.lang.management is on the
     * test JVM, not on the Android compile classpath). */
    private fun blockedBehind(thread: Thread, owner: Thread): Boolean {
        val threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val info = Class.forName("java.lang.management.ThreadMXBean").getMethod("getThreadInfo", Long::class.javaPrimitiveType)
            .invoke(threads, thread.id) ?: return false
        val type = Class.forName("java.lang.management.ThreadInfo")
        return type.getMethod("getThreadState").invoke(info) == Thread.State.BLOCKED && type.getMethod("getLockOwnerId").invoke(info) == owner.id
    }

    @Test fun V25_01_aRemovalPublishedWhileARecoveryPageIsBeingAdmittedIsNeverUndoneByThatPage() {
        val removed = conversation("removed")
        val address = 1_000L
        val observed = ObservedVersions(removed, Thread.currentThread(), entered, go)
        ReflectionHelpers.setField(app.finalRecovery, "removalVersions", observed)
        val run = app.appScope.launch { app.finalRecovery.reconcile() }
        assertTrue("the recovery commit of that conversation is reading its removal version", await(20_000) { entered.count == 0L })
        val remover = Thread { app.finalRecovery.remove(setOf(removed)) }.apply { start() }
        assertTrue("the removal returned, or waits for a monitor the paused commit holds",
            await(20_000) { !remover.isAlive || blockedBehind(remover, observed.commit!!) })
        go.countDown()
        remover.join(20_000)
        assertFalse("the removal finished", remover.isAlive)
        runBlocking { withTimeout(20_000) { run.join() } }
        settle(); idle(1_000); settle()
        app.recentBodies.remember(HistoryPage(removed, listOf(HistoryMessage(address, "assistant", "late body", 600.0)), 1, listOf(address)), false)
        val kept = app.recentBodies.load(removed) != null
        val late = FinalReply("after-removal", removed, VoiceOrigin.PHONE, heard = false, cancelled = false, source = FinalReplySource.OWN,
            text = "late answer", canonicalRowId = address, namespace = app.bodyScope())
        val tookIn = runBlocking { app.replyAlerts.deliver(late) }
        assertEquals("the published removal is still in force: no new custody and no kept body for that conversation",
            "custody=false body=false", "custody=$tookIn body=$kept")
        assertTrue("and nothing of it alerts", alerts().isEmpty())
        assertTrue(fake.rawPrompts.isEmpty())
    }
}
