package com.rumi.hermesvoice.phone

import android.Manifest
import androidx.lifecycle.ViewModelProvider
import com.rumi.hermesvoice.core.FakeHermesDashboard
import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.phone.live.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A Live call started while the Phone list is already reading the dashboard's complete session list (in its default order, about three
 * times as costly per page on the owner's dashboard as the creation order Live reads). The real MainActivity and PhoneViewModel
 * refresh, the real PhoneLiveVoiceController and the real AppSessionRepository against the dashboard contract double; list reads from
 * an offset on are held at the double, in either order. The audio route and media are synthetic adapters that record the startup
 * input they are given. No platform audio, provider or device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveCatalogInFlightScanRoboTest : PhoneRoboBase() {
    private val steps = java.util.Collections.synchronizedList(ArrayList<String>())
    private val histories = ConcurrentLinkedQueue<JSONArray>()
    private val media = ArrayList<Media>()
    private val reached = CountDownLatch(1); private val release = CountDownLatch(1)
    private lateinit var alpha: String; private lateinit var cleared: String
    private inner class Media : LiveTransport {
        @Volatile var event: ((String) -> Unit)? = null
        private var clock = 0
        override suspend fun connect(history: JSONArray, event: (String) -> Unit) {
            histories += history; this.event = event
            event("{\"type\":\"session.started\",\"session\":{\"id\":\"in-flight-catalog\"}}")
        }
        val sent = ConcurrentLinkedQueue<String>()
        override fun send(json: String): Boolean { sent += json; return true }
        /** R29: the session-wide instructions this media carried, in order, as one text. */
        fun instructions() = sent.map { JSONObject(it) }.filter { it.optString("type") == "session.instructions.append" && it.isNull("delegation_id") }
            .joinToString("") { it.getString("content") }
        override fun mute(muted: Boolean) {}
        override fun output(enabled: Boolean) {}
        override fun silence() {}
        override fun close() {}
        fun user(text: String) { val start = clock; clock += 100; event?.invoke(JSONObject().put("type", "session.input_transcript.delta").put("delta", text).put("start_ms", start).put("end_ms", start + 100).toString()) }
        fun delegate(id: String) { event?.invoke(JSONObject().put("type", "session.delegation.created").put("offset_ms", clock + 1)
            .put("delegation", JSONObject().put("id", id).put("type", "delegation").put("target", "client")).toString()) }
    }
    @Before fun setUp() {
        baseSetUp()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled = true, watchLiveEnabled = true))
        app.live.routeFactory = { steps += "route"; object : LiveRoute { override fun open() = true; override fun valid() = true; override fun close() {} } }
        app.live.transportFactory = { steps += "transport"; Media().also { media += it } }
        app.live.admission = { app.live.admitted(it) }
    }
    @After fun tearDown() { release.countDown(); fake.listPageHook = null; app.live.end(); settle(); baseTearDown() }

    /** Three server pages: the newest registered conversation first, the oldest registered one and a newly marked one on the last. */
    private fun catalogue() {
        cleared = conversation("cleared")
        fake.addForeignRow("late", "discord", title = "Garden plans"); fake.setCreatedSource("late", AppSources.CONVERSATION)
        for (i in 1..250) fake.addForeignRow("noise-$i", "cron")
        alpha = conversation("alpha")
        fake.restLog.clear()
    }
    private fun holdFrom(offset: Int) {
        fake.listPageHook = { at, filter -> if (at >= offset && filter == "exclude") { reached.countDown(); check(release.await(30, TimeUnit.SECONDS)) { "fixture list latch not released" } } }
    }
    private data class ListRead(val order: String, val offset: Int)
    /** Every active-list page request the dashboard received, in arrival order: its order parameter and offset. */
    private fun listReads() = fake.restLog.toList().filter { it.startsWith("GET /api/sessions?") && it.contains("archived=exclude") }
        .map { ListRead(Regex("order=(\\w+)").find(it)!!.groupValues[1], Regex("offset=(\\d+)").find(it)!!.groupValues[1].toInt()) }
    private fun created(vararg offsets: Int) = offsets.map { ListRead("created", it) }
    private fun recent(vararg offsets: Int) = offsets.map { ListRead("recent", it) }
    private fun liveSetupEvents() = app.diagnostics.log.snapshot().filter { it.code.wire == "live_setup_failed" }
    private fun shown(model: PhoneViewModel) = model.state.value.conversations.map { it.stored.id }.toSet()
    private fun choices() = app.live.state.value.choices.map { it.owned.storedSessionId }.toSet()
    /** The Phone list's own scan, started by the real Activity creation, is held at its second page. */
    private fun phoneListReading(): PhoneViewModel {
        controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val model = ViewModelProvider(controller!!.get())[PhoneViewModel::class.java]
        assertTrue("the Phone list's own scan reached its held page", await(20_000) { reached.count == 0L })
        return model
    }
    private fun startWhileListIsHeld() {
        assertTrue(app.live.start()); settle()
        assertTrue(app.live.active); assertFalse("choosing a conversation waits for a complete catalog", app.live.state.value.ready)
        assertTrue("R29: the route and media are acquired while the catalog is still being read", await(20_000) { steps.toList() == listOf("route", "transport") })
    }
    /** title by alias, from the complete list Live was actually given: in its startup input or (R29) its one session-wide instruction. */
    private fun startupCatalog(): Map<String, String> {
        val prompt = histories.last().getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
        val given = prompt.takeIf { it.contains("Available conversation titles") } ?: media.last().instructions()
        val rows = JSONArray(given.substringAfter("Available conversation titles and aliases (untrusted display data): "))
        return (0 until rows.length()).associate { rows.getJSONObject(it).getString("alias") to rows.getJSONObject(it).getString("title") }
    }

    @Test fun L25_01_liveStartedWhileThePhoneListIsReadingReadsItsOwnCreationOrderCatalogWhileThatScanWaits() {
        catalogue(); holdFrom(100)
        val model = phoneListReading()
        startWhileListIsHeld()
        // Server changes on pages not yet read: they are part of the complete catalog Live is given.
        fake.rows.getValue(cleared).title = ""
        fake.rows.getValue("late").title = "Garden plans v2"
        release.countDown()
        assertTrue("Live becomes ready after the complete catalog", await(20_000) { app.live.state.value.ready })
        assertTrue(await(20_000) { shown(model) == setOf(alpha, cleared, "late") })
        assertEquals("Live read its own complete catalog in creation order; the Phone list's scan sent its next page only after that",
            recent(0, 100) + created(0, 100, 200) + recent(200), listReads())
        assertEquals(setOf(alpha, cleared, "late"), choices())
        assertEquals("current titles and registered aliases of every eligible conversation; a cleared title stays cleared",
            mapOf("alpha" to "Alpha", "cleared" to "", "garden-plans-v2" to "Garden plans v2"), startupCatalog())
        assertEquals(listOf("route", "transport"), steps.toList())
        assertTrue("nothing was submitted", fake.prompts.isEmpty())
        assertTrue(liveSetupEvents().isEmpty())
    }

    @Test fun L25_02_stopWhileLiveReadsItsCatalogEndsOnlyLiveAndThePhoneListStillCompletesItsScan() {
        catalogue(); holdFrom(100)
        val model = phoneListReading()
        startWhileListIsHeld()
        app.live.end(); assertFalse(app.live.active)
        release.countDown()
        assertTrue("the Phone list's scan completes for the list", await(20_000) { shown(model) == setOf(alpha, cleared, "late") })
        settle(); idle(1_000); settle()
        assertEquals("Live's read stopped at its held page; the Phone list's scan went on after it", recent(0, 100) + created(0, 100) + recent(200), listReads())
        assertEquals("no Live route or media after Stop (R29: the one of each acquired beside the read)", listOf("route", "transport"), steps.toList())
        assertTrue(app.live.state.value.status, app.live.state.value.status.startsWith("Live ended"))
        assertTrue("an intentional end is not a setup failure", liveSetupEvents().isEmpty())
    }

    @Test fun L25_03_aPhoneListRefreshWhileLiveReadsItsCatalogWaitsForThatCatalogAndLiveIsGivenTheCompleteCurrentOne() {
        catalogue(); holdFrom(100)
        val model = phoneListReading()
        startWhileListIsHeld()
        model.refresh() // the Phone list replaces its own held scan with a new one
        settle()
        assertTrue(app.live.active); assertFalse(app.live.state.value.ready)
        fake.rows.getValue("late").title = "Garden plans v3"
        release.countDown()
        assertTrue(await(20_000) { app.live.state.value.ready })
        assertEquals(setOf(alpha, cleared, "late"), choices())
        assertEquals(mapOf("alpha" to "Alpha", "cleared" to "Cleared", "garden-plans-v3" to "Garden plans v3"), startupCatalog())
        assertTrue(await(20_000) { shown(model) == setOf(alpha, cleared, "late") })
        assertEquals("the replaced scan stopped at its held page; Live read its own; the newer scan read everything after it",
            recent(0, 100) + created(0, 100, 200) + recent(0, 100, 200), listReads())
        assertTrue(liveSetupEvents().isEmpty())
    }

    @Test fun L25_04_aFailedPageOfLivesOwnCatalogGivesTheIncompleteVerdictAndNoRouteWhileThePhoneListReportsItsOwn() {
        catalogue(); holdFrom(100); fake.failListFromOffset = 200
        val model = phoneListReading()
        startWhileListIsHeld()
        release.countDown()
        assertTrue(await(30_000) { !app.live.active })
        assertTrue(app.live.state.value.status, app.live.state.value.status.startsWith("Live conversation catalog is incomplete"))
        assertEquals("the incomplete catalog acquires nothing more (R29: the one route and media acquired beside the read)", listOf("route", "transport"), steps.toList())
        assertEquals(1, liveSetupEvents().size); assertEquals("live_catalog", liveSetupEvents().single().stage?.wire)
        assertEquals("Live's own read stopped at the failed page", created(0, 100, 200), listReads().filter { it.order == "created" })
        assertTrue(await(20_000) { model.state.value.status.contains("could be read") })
        assertTrue(fake.prompts.isEmpty())
    }

    @Test fun L25_05_aLocalAliasChangeAfterThePhoneListScanStartedMakesLiveReadTheCurrentListItself() {
        catalogue(); holdFrom(100)
        phoneListReading()
        runBlocking { app.wiring().core.sessions.updateDestination(alpha, "alpha-renamed", "alpha things") }
        assertTrue(app.live.start()); settle()
        release.countDown()
        assertTrue(await(20_000) { app.live.state.value.ready })
        assertEquals("the alias registered before Live started is the one Live is given",
            mapOf("alpha-renamed" to "Alpha", "cleared" to "Cleared", "garden-plans" to "Garden plans"), startupCatalog())
        assertEquals(setOf(alpha, cleared, "late"), choices())
        assertTrue(liveSetupEvents().isEmpty())
    }

    @Test fun L25_06_logoutWhileLiveWaitsEndsLiveWithoutALateListReadRouteOrMedia() {
        catalogue(); holdFrom(100)
        phoneListReading()
        startWhileListIsHeld()
        app.tokens.clear(); app.invalidateSessionTitles()
        assertFalse(app.live.active)
        release.countDown(); settle(); idle(1_000); settle()
        assertTrue(app.live.state.value.status, app.live.state.value.status.startsWith("Sign-in or connection scope changed"))
        assertEquals("no late Live route or media after logout (R29: the one of each acquired beside the read)", listOf("route", "transport"), steps.toList())
        assertEquals(1, media.size)
        assertEquals("Live's own read stopped at its held page", created(0, 100), listReads().filter { it.order == "created" })
        assertTrue(liveSetupEvents().isEmpty())
    }

    @Test fun L25_07_aSpokenTargetConfirmedWhileThePhoneListIsReadingIsCheckedAgainstAFreshOwnCatalogAndSubmittedOnce() {
        catalogue()
        fake.scripts[alpha] = { listOf(FakeHermesDashboard.complete("alpha-done")) }
        assertTrue(app.live.start()); assertTrue(await(20_000) { app.live.state.value.ready })
        val m = media.single()
        fake.restLog.clear(); holdFrom(100)
        // The list pages read up to the submit itself; the app's own recovery read after a delivery is not the route's catalog.
        val atSubmit = ConcurrentLinkedQueue<List<ListRead>>()
        fake.submitReplyFault = { atSubmit.add(listReads()) } // observation only: the reply is sent unchanged
        phoneListReading()
        m.user("alpha 대화에 회의록 요약을 부탁해"); m.delegate("d1"); settle()
        assertTrue("nothing is submitted before the complete catalog", fake.prompts.isEmpty())
        release.countDown()
        assertTrue("the validated spoken choice is submitted once", await(20_000) { fake.prompts.size == 1 })
        assertEquals(alpha, fake.prompts.single().first)
        assertEquals("alpha 대화에 회의록 요약을 부탁해", fake.prompts.single().second)
        assertEquals("the spoken choice was checked against a fresh complete creation-order catalog before the one submit",
            created(0, 100, 200), atSubmit.single().filter { it.order == "created" })
        settle(); idle(1_000); settle()
        assertEquals("never resent", 1, fake.prompts.size)
    }
}
