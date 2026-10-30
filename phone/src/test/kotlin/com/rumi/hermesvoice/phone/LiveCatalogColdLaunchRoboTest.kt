package com.rumi.hermesvoice.phone

import android.Manifest
import androidx.lifecycle.ViewModelProvider
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.phone.live.*
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * A Live call started right at a cold app launch, as in the failed physical trial (admitted about one second after launch): the real
 * MainActivity creation and start begin the Phone list's own scan and the app's background recovery, and Talk is pressed at once.
 * The dashboard contract double answers one sessions read at a time, each held for its cost measured on the owner's dashboard
 * (5,779-5,791 sessions; medians: a default-order list page 766 ms, a creation-order page 236 ms; four concurrent page reads each took
 * four times as long), divided by [SCALE]. Reads with no measured cost are served at once and count 0 (the critical path is a lower
 * bound). The real PhoneLiveVoiceController, PhoneViewModel, FinalRecovery and AppSessionRepository over real HTTP; the audio route
 * and media are synthetic adapters. No device, provider or real dashboard timing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveCatalogColdLaunchRoboTest : PhoneRoboBase() {
    companion object {
        const val SCALE = 50L
        /** Measured median server cost per read, ms (parent-catalogue-GET-benchmark.json, created-order-GET-benchmark.json). */
        val COST = mapOf("list:recent" to 766L, "list:created" to 236L)
        const val SETUP_DEADLINE_MS = 60_000L
    }
    private val served = Collections.synchronizedList(ArrayList<String>())
    @Volatile private var atRoute = -1
    private val media = ArrayList<Media>()
    private val held = CountDownLatch(1); private val release = CountDownLatch(1)
    private lateinit var registered: Set<String>
    private lateinit var alpha: String
    private inner class Media : LiveTransport {
        override suspend fun connect(history: JSONArray, event: (String) -> Unit) { event("{\"type\":\"session.started\",\"session\":{\"id\":\"cold-launch\"}}") }
        override fun send(json: String) = true
        override fun mute(muted: Boolean) {}
        override fun output(enabled: Boolean) {}
        override fun silence() {}
        override fun close() {}
    }
    @Before fun setUp() {
        baseSetUp()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled = true, watchLiveEnabled = true))
        // R29: the route is opened beside the catalogue read, not after it: what the dashboard had served by then shows which waited.
        app.live.routeFactory = { atRoute = served.size; object : LiveRoute { override fun open() = true; override fun valid() = true; override fun close() {} } }
        app.live.transportFactory = { Media().also { media += it } }
        app.live.admission = { app.live.admitted(it) }
    }
    @After fun tearDown() { release.countDown(); fake.sessionsReadHook = null; app.live.end(); settle(); baseTearDown() }

    private val server = ReentrantLock(true)
    /** One read at a time, in arrival order. [hold] runs first, outside the server: a held read does not occupy it. */
    private fun oneReadAtATime(hold: (String) -> Unit = {}) {
        fake.sessionsReadHook = { label ->
            hold(label)
            server.lock()
            try { served += label; Thread.sleep((COST[label] ?: 0L) / SCALE) } finally { server.unlock() }
        }
    }
    /** The owner's dashboard size: 58 pages of rows that are not this app's, its own conversations the newest rows. */
    private fun ownersDashboard() {
        for (i in 1..5_787) fake.addForeignRow("noise-$i", "cron")
        alpha = conversation("alpha")
        registered = setOf(alpha, conversation("beta"), conversation("gamma"))
        fake.restLog.clear(); served.clear()
    }
    private fun coldLaunchAndTalk(): PhoneViewModel {
        controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        val model = ViewModelProvider(controller!!.get())[PhoneViewModel::class.java]
        assertTrue(app.live.start())
        return model
    }
    private fun choices() = app.live.state.value.choices.map { it.owned.storedSessionId }.toSet()
    private fun liveSetupEvents() = app.diagnostics.log.snapshot().filter { it.code.wire == "live_setup_failed" }
    private fun listed(model: PhoneViewModel) = model.state.value.conversations.map { it.stored.id }.toSet()

    @Test fun V26_01_aLiveCallStartedAtColdLaunchHasItsCompleteCatalogueWithinHalfTheWholeSetupDeadlineOnAOneReadAtATimeDashboard() {
        ownersDashboard(); oneReadAtATime()
        val model = coldLaunchAndTalk()
        assertTrue("Live becomes ready", await(60_000) { app.live.state.value.ready })
        // Read when ready is observed (conservative: a read served just after it counts too).
        val path = served.toList()
        val measured = path.sumOf { COST[it] ?: 0L }
        assertTrue("the route and media were acquired before the ${path.count { it == "list:created" }}-page catalogue was complete: " +
            "${path.take(atRoute).count { it == "list:created" }} pages", atRoute >= 0 && path.take(atRoute).count { it == "list:created" } < path.count { it == "list:created" })
        assertEquals("the complete current catalogue", registered, choices())
        assertTrue("the catalogue's critical path, in measured dashboard time, is at most half the ${SETUP_DEADLINE_MS} ms whole-setup " +
            "deadline: the route, provider session, media start (itself bounded at 15 s) and a phone round trip per catalogue page need the " +
            "other half. Measured $measured ms over ${path.groupingBy { it }.eachCount()}", measured <= SETUP_DEADLINE_MS / 2)
        assertTrue("the Phone list still completes its own scan", await(60_000) { listed(model) == registered && !model.state.value.status.startsWith("Reading") })
        assertTrue(fake.prompts.isEmpty())
        assertTrue(liveSetupEvents().isEmpty())
    }

    @Test fun V26_02_openingAConversationWhileLivesCatalogueIsReadPublishesItsNewestPageBeforeThatCatalogueCompletes() {
        ownersDashboard()
        fake.injectRow(alpha, "assistant", "the alpha body")
        val lists = AtomicInteger()
        // From the fourth list read on, every list read (of any scan, either order) is held before the dashboard serves it.
        oneReadAtATime { label -> if (label.startsWith("list:") && lists.incrementAndGet() > 3) { held.countDown(); check(release.await(30, TimeUnit.SECONDS)) { "fixture list latch not released" } } }
        val model = coldLaunchAndTalk()
        assertTrue("a list read is held", await(20_000) { held.count == 0L })
        assertFalse("Live waits for its complete catalogue", app.live.state.value.ready)
        model.open(app.wiring().core.sessions.activeConversation(alpha)!!)
        val published = try {
            await(10_000) { model.state.value.selected?.storedSessionId == alpha && !model.state.value.opening && model.state.value.history.any { it.text.contains("the alpha body") } }
        } finally { release.countDown() }
        assertTrue("the opened conversation's newest page is shown while the catalogue is still being read", published)
        assertTrue(await(60_000) { app.live.state.value.ready })
        assertEquals(registered, choices())
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun V26_03_aCatalogueStillBeingReadAtTheWholeSetupDeadlineIsRecordedWithItsPagesAndTimeInThatStepOnly() {
        for (i in 1..250) fake.addForeignRow("noise-$i", "cron")
        // R29: the call starts (and hears) beside its catalogue; a 60 s idle grace keeps it open until the 60 s whole setup deadline.
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled = true, watchLiveEnabled = true, liveIdleSeconds = 60))
        conversation("alpha"); fake.restLog.clear()
        val lists = AtomicInteger()
        oneReadAtATime { label -> if (label.startsWith("list:") && lists.incrementAndGet() == 2) { held.countDown(); check(release.await(90, TimeUnit.SECONDS)) { "fixture list latch not released" } } }
        assertTrue(app.live.start())
        assertTrue("the catalogue's second page is being read", await(20_000) { held.count == 0L })
        idle(SETUP_DEADLINE_MS); settle()
        val event = liveSetupEvents().single()
        assertEquals("live_catalog", event.stage?.wire); assertEquals("timeout", event.fail?.wire)
        assertEquals("one catalogue page had arrived", 1, event.n)
        assertTrue("the time spent in the catalogue step, by the app's own clock: ${event.ms}", event.ms != null && event.ms!! in (SETUP_DEADLINE_MS - 1_000)..SETUP_DEADLINE_MS)
        assertFalse(app.live.active)
        assertTrue(app.live.state.value.status, app.live.state.value.status.startsWith("Live connection deadline reached"))
        release.countDown(); settle(); idle(1_000); settle()
        assertEquals("still one observation", 1, liveSetupEvents().size)
        assertEquals("R29: the one media created beside the held catalogue, never a second", 1, media.size); assertTrue(fake.prompts.isEmpty())
    }
}
