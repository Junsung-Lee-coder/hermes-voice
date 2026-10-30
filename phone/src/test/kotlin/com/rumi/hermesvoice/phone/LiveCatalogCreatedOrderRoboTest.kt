package com.rumi.hermesvoice.phone

import android.Manifest
import androidx.lifecycle.ViewModelProvider
import com.rumi.hermesvoice.core.FakeHermesDashboard
import com.rumi.hermesvoice.core.sessions.AppSources
import com.rumi.hermesvoice.core.sessions.OwnedRole
import com.rumi.hermesvoice.core.sessions.OwnedSession
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.phone.live.*
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
 * A Live call that reads the conversation catalog itself (no list scan in flight). The real PhoneLiveVoiceController and
 * AppSessionRepository over real HTTP against the dashboard contract double, which serves `order=created` as the server does (newest
 * started first, same rows). The audio route and media are synthetic adapters that record the startup input they are given.
 * No platform audio, provider or device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveCatalogCreatedOrderRoboTest : PhoneRoboBase() {
    private val steps = java.util.Collections.synchronizedList(ArrayList<String>())
    private val histories = ConcurrentLinkedQueue<JSONArray>()
    private val media = ArrayList<Media>()
    private val reached = CountDownLatch(1); private val release = CountDownLatch(1)
    private data class ListRead(val order: String, val offset: Int)
    private inner class Media : LiveTransport {
        @Volatile var event: ((String) -> Unit)? = null
        private var clock = 0
        override suspend fun connect(history: JSONArray, event: (String) -> Unit) {
            histories += history; this.event = event
            event("{\"type\":\"session.started\",\"session\":{\"id\":\"created-order-catalog\"}}")
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

    private fun registry() = app.wiring().core.sessions.registry
    private fun marked(id: String, title: String = "Topic $id", startedAt: Double? = null) {
        fake.addForeignRow(id, "discord", title = title); fake.setCreatedSource(id, AppSources.CONVERSATION)
        if (startedAt != null) fake.rows.getValue(id).startedAt = startedAt
    }
    private fun noise(count: Int) { for (i in 1..count) fake.addForeignRow("noise-$i", "cron") }
    private fun holdFrom(offset: Int) {
        fake.listPageHook = { at, filter -> if (at >= offset && filter == "exclude") { reached.countDown(); check(release.await(30, TimeUnit.SECONDS)) { "fixture list latch not released" } } }
    }
    /** Every active-list page request the dashboard received, in arrival order: its order parameter and offset. */
    private fun listReads() = fake.restLog.toList().filter { it.startsWith("GET /api/sessions?") && it.contains("archived=exclude") }
        .map { ListRead(Regex("order=(\\w+)").find(it)!!.groupValues[1], Regex("offset=(\\d+)").find(it)!!.groupValues[1].toInt()) }
    private fun created(vararg offsets: Int) = offsets.map { ListRead("created", it) }
    private fun recent(vararg offsets: Int) = offsets.map { ListRead("recent", it) }
    private fun liveSetupEvents() = app.diagnostics.log.snapshot().filter { it.code.wire == "live_setup_failed" }
    private fun choiceIds() = app.live.state.value.choices.map { it.owned.storedSessionId }
    private fun prompt() = histories.last().getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
    /** The complete list Live was actually given: in its startup input or (R29) its one session-wide instruction. */
    private fun catalogRows(): List<JSONObject> {
        val given = prompt().takeIf { it.contains("Available conversation titles") } ?: media.last().instructions()
        val rows = JSONArray(given.substringAfter("Available conversation titles and aliases (untrusted display data): "))
        return (0 until rows.length()).map { rows.getJSONObject(it) }
    }
    /** title by alias, from the complete list Live was actually given. */
    private fun startupCatalog() = catalogRows().associate { it.getString("alias") to it.getString("title") }
    private fun ready(): Media {
        assertTrue(app.live.start()); assertTrue("Live becomes ready", await(20_000) { app.live.state.value.ready })
        return media.last()
    }

    @Test fun C25_01_aColdLiveCallReadsTheWholeCatalogInCreationOrderWithTheSameCurrentEligibility() {
        val delta = conversation("delta"); fake.setCreatedSource(delta, "tui")
        val beta = conversation("beta"); fake.rows.getValue(beta).archived = true
        val gamma = conversation("gamma"); val gammaTip = fake.compress(gamma)
        val cleared = conversation("cleared"); fake.rows.getValue(cleared).title = ""
        marked("late", "Garden plans")
        noise(250)
        val alpha = conversation("alpha")
        fake.restLog.clear()
        ready()
        assertEquals("every eligible conversation, a compression tip under its registered alias, nothing unmarked or archived",
            setOf(alpha, cleared, gammaTip, "late"), choiceIds().toSet())
        assertEquals("current titles and registered aliases; a cleared title stays cleared",
            mapOf("alpha" to "Alpha", "cleared" to "", "gamma" to "Gamma", "garden-plans" to "Garden plans"), startupCatalog())
        assertEquals("the newly marked session is taken in under its derived alias", "garden-plans", registry().find("late")?.alias)
        assertEquals("the registered root now names its compression tip", gammaTip, registry().all().single { it.alias == "gamma" }.storedSessionId)
        assertNotNull("an unmarked registration is withheld, not deleted", registry().find(delta))
        assertEquals(listOf("route", "transport"), steps.toList()); assertTrue(liveSetupEvents().isEmpty()); assertTrue(fake.prompts.isEmpty())
        assertEquals("Live's own catalog read pages the creation order to the end", created(0, 100, 200), listReads())
    }

    @Test fun C25_02_liveStartedWhileThePhoneListScanIsHeldReadsItsOwnCreationOrderCatalogWhileThatScanWaits() {
        val alpha = conversation("alpha"); marked("late"); noise(250)
        fake.restLog.clear(); holdFrom(100)
        controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val model = ViewModelProvider(controller!!.get())[PhoneViewModel::class.java]
        assertTrue("the Phone list's own scan reached its held page", await(20_000) { reached.count == 0L })
        assertTrue(app.live.start()); settle(); assertFalse(app.live.state.value.ready)
        release.countDown()
        assertTrue(await(20_000) { app.live.state.value.ready })
        assertEquals(setOf(alpha, "late"), choiceIds().toSet())
        assertTrue(await(20_000) { model.state.value.conversations.map { it.stored.id }.toSet() == setOf(alpha, "late") })
        assertEquals("Live's own creation-order catalog; the Phone list's default-order scan sent its next page only after it",
            recent(0, 100) + created(0, 100, 200) + recent(200), listReads())
    }

    @Test fun C25_03_liveShowsItsChoicesInTheConversationListOrderNotTheOrderPagesWereRead() {
        marked("m-a", startedAt = 1_700_000_300.0); marked("m-c", startedAt = 1_700_000_100.0); marked("m-b", startedAt = 1_700_000_200.0)
        fake.restLog.clear()
        ready()
        val expected = app.finalRecovery.decorate(app.live.state.value.choices).map { it.owned.storedSessionId }
        assertEquals("nothing received yet: the conversation list orders these by id, newest first", listOf("m-c", "m-b", "m-a"), expected)
        assertEquals("Live's choices follow the conversation list's order", expected, choiceIds())
        assertEquals("and so does the catalog Live is given", listOf("topic-m-c", "topic-m-b", "topic-m-a"), catalogRows().map { it.getString("alias") })
        assertEquals(created(0), listReads())
    }

    @Test fun C25_04_whenMoreNewlyMarkedSessionsThanFreePlacesAppearTheDefaultOrderStillDecidesWhichAreTakenIn() {
        for (i in 1..98) {
            val id = fake.addStoredSession(AppSources.CONVERSATION, "t$i", false) { emptyList() }
            registry().put(OwnedSession(id, OwnedRole.CONVERSATION, "t$i", "a$i", "", archived = false, createdAtMs = 0L))
        }
        // The creation order would favour n-1 and n-2; the default order reads n-3 and n-2 first.
        marked("n-1", startedAt = 1_700_000_300.0); marked("n-2", startedAt = 1_700_000_200.0); marked("n-3", startedAt = 1_700_000_100.0)
        fake.restLog.clear()
        ready()
        assertNotNull(registry().find("n-3")); assertNotNull(registry().find("n-2"))
        assertNull("the two free places go to the sessions the default order took in", registry().find("n-1"))
        assertEquals(100, registry().all().count { it.role == OwnedRole.CONVERSATION && !it.archived })
        assertEquals(100, choiceIds().size); assertFalse("n-1" in choiceIds())
        assertEquals("the creation-order read is set aside and the default order's scan decides the places",
            created(0, 100) + recent(0, 100), listReads())
    }

    @Test fun C25_09_withEveryPlaceTakenANewlyMarkedSessionIsLeftOutInAnyOrderWithoutASecondRead() {
        for (i in 1..100) {
            val id = fake.addStoredSession(AppSources.CONVERSATION, "t$i", false) { emptyList() }
            registry().put(OwnedSession(id, OwnedRole.CONVERSATION, "t$i", "a$i", "", archived = false, createdAtMs = 0L))
        }
        marked("over", startedAt = 1_700_000_300.0)
        fake.restLog.clear()
        ready()
        assertNull("no free place: it is not taken in", registry().find("over"))
        assertEquals(100, choiceIds().size); assertFalse("over" in choiceIds())
        assertEquals("one creation-order read decides it", created(0, 100), listReads())
    }

    @Test fun C25_10_aConversationUnarchivedOnTheDashboardDoesNotTakeTheLastPlaceAheadOfTheDefaultOrder() {
        for (i in 1..99) {
            val id = fake.addStoredSession(AppSources.CONVERSATION, "t$i", false) { emptyList() }
            registry().put(OwnedSession(id, OwnedRole.CONVERSATION, "t$i", "a$i", "", archived = false, createdAtMs = 0L))
        }
        val e = fake.addStoredSession(AppSources.CONVERSATION, "Eve", false) { emptyList() }
        registry().put(OwnedSession(e, OwnedRole.CONVERSATION, "Eve", "eve", "", archived = true, createdAtMs = 0L))
        marked("c-new") // the newest row: read before e, on the same page, in the default order
        fake.restLog.clear()
        ready()
        assertNotNull("the default order gives the last place to the newly marked session", registry().find("c-new"))
        assertTrue("and the conversation unarchived elsewhere stays archived here", registry().find(e)!!.archived)
        assertEquals(100, registry().all().count { it.role == OwnedRole.CONVERSATION && !it.archived })
        assertTrue("c-new" in choiceIds())
        assertEquals("the creation-order read writes no unarchive; the default order's scan decides", created(0, 100) + recent(0, 100), listReads())
    }

    @Test fun C25_11_aConversationUnarchivedOnTheDashboardDoesNotTakeAnAliasAheadOfTheDefaultOrder() {
        for (i in 1..98) {
            val id = fake.addStoredSession(AppSources.CONVERSATION, "t$i", false) { emptyList() }
            registry().put(OwnedSession(id, OwnedRole.CONVERSATION, "t$i", "a$i", "", archived = false, createdAtMs = 0L))
        }
        val e = fake.addStoredSession(AppSources.CONVERSATION, "Old notes", false) { emptyList() }
        registry().put(OwnedSession(e, OwnedRole.CONVERSATION, "Old notes", "notes", "", archived = true, createdAtMs = 0L))
        marked("c-notes", "Notes")
        fake.restLog.clear()
        ready()
        assertEquals("the default order gives the free alias to the newly marked session", "notes", registry().find("c-notes")?.alias)
        assertTrue("and the conversation unarchived elsewhere stays archived here", registry().find(e)!!.archived)
        assertEquals("the creation-order read writes no unarchive; the default order's scan decides", created(0) + recent(0), listReads())
    }

    @Test fun C25_12_aSessionThePhoneListScanAlreadyTookInIsInLivesOwnCatalogUnderItsRegisteredAlias() {
        val alpha = conversation("alpha"); noise(250); marked("fresh")
        fake.restLog.clear(); holdFrom(100)
        controller = Robolectric.buildActivity(MainActivity::class.java).create()
        assertTrue("the Phone list's scan took in the newly marked session on its first page and holds the next",
            await(20_000) { reached.count == 0L && registry().find("fresh") != null })
        assertTrue(app.live.start()); settle(); assertFalse(app.live.state.value.ready)
        release.countDown()
        assertTrue(await(20_000) { app.live.state.value.ready })
        assertEquals(mapOf("alpha" to "Alpha", "topic-fresh" to "Topic fresh"), startupCatalog())
        assertTrue("the Phone list's scan goes on after Live's catalog", await(20_000) { ListRead("recent", 200) in listReads() })
        assertEquals("Live read its own catalog; the Phone list's scan went on after it", recent(0, 100) + created(0, 100, 200) + recent(200), listReads())
    }

    @Test fun C25_13_pinnedRowsOnEveryCreationOrderPageNeitherSkipOrderedRowsNorAddAnArchivedOne() {
        fun at(id: String, position: Int) { fake.rows.getValue(id).startedAt = 1_800_000_000.0 - position }
        marked("pin-archived"); fake.rows.getValue("pin-archived").archived = true; fake.rows.getValue("pin-archived").pinned = true
        for (i in 0..98) { fake.addForeignRow("a-$i", "cron"); at("a-$i", i) }
        fake.addForeignRow("pin-active", "cron"); fake.rows.getValue("pin-active").pinned = true; at("pin-active", 99)
        marked("skip-me"); at("skip-me", 100)
        for (i in 0..98) { fake.addForeignRow("b-$i", "cron"); at("b-$i", 101 + i) }
        marked("tail"); at("tail", 200)
        fake.restLog.clear()
        ready()
        assertEquals("every ordered marked row, and no archived one", setOf("skip-me", "tail"), choiceIds().toSet())
        assertNull("an archived pinned marked session is not taken in as active", registry().find("pin-archived"))
        assertEquals("the ordered windows are read one after another", created(0, 100, 200), listReads())
    }

    @Test fun C25_05_newlyMarkedSessionsWhoseAliasesCollideAreNamedAsTheDefaultOrderNamesThem() {
        val alpha = conversation("alpha")
        marked("dup-x", "Notes"); marked("dup-y", "Notes")
        fake.restLog.clear()
        ready()
        assertEquals("notes", registry().find("dup-x")?.alias)
        val other = registry().find("dup-y")?.alias.orEmpty()
        assertTrue(other, other.startsWith("notes-") && other.length > "notes-".length)
        assertEquals(setOf(alpha, "dup-x", "dup-y"), choiceIds().toSet())
        assertEquals("aliases that depend on each other are assigned by the default order's scan", created(0) + recent(0), listReads())
    }

    @Test fun C25_06_anIncompleteCreationOrderCatalogRefusesLiveOpensNothingAndTakesNothingIn() {
        conversation("alpha"); noise(250); marked("late")
        fake.failListFromOffset = 100; fake.restLog.clear()
        assertTrue(app.live.start()); assertTrue(await(20_000) { !app.live.active })
        assertTrue(app.live.state.value.status, app.live.state.value.status.startsWith("Live conversation catalog is incomplete"))
        assertEquals("nothing more for an incomplete catalog (R29: the one route and media acquired beside the read)", listOf("route", "transport"), steps.toList())
        assertEquals(1, liveSetupEvents().size); assertEquals("live_catalog", liveSetupEvents().single().stage?.wire)
        assertTrue(fake.prompts.isEmpty())
        assertEquals(created(0, 100), listReads())
        assertNull("an unfinished creation-order read takes in no newly marked session", registry().find("late"))
    }

    @Test fun C25_07_logoutDuringTheCreationOrderReadEndsLiveWithoutALateRouteMediaReadOrRegistration() {
        conversation("alpha"); noise(250); marked("late")
        fake.restLog.clear(); holdFrom(100)
        assertTrue(app.live.start())
        assertTrue("Live's own read reached its held page", await(20_000) { reached.count == 0L }); settle()
        assertTrue(app.live.active); assertFalse(app.live.state.value.ready)
        app.tokens.clear(); app.invalidateSessionTitles()
        assertFalse(app.live.active)
        release.countDown(); settle(); idle(1_000); settle()
        assertTrue(app.live.state.value.status, app.live.state.value.status.startsWith("Sign-in or connection scope changed"))
        assertEquals("no late route or media (R29: the one of each acquired beside the read)", listOf("route", "transport"), steps.toList()); assertEquals(1, media.size)
        assertTrue(liveSetupEvents().isEmpty())
        assertEquals("nothing is read after the held page", created(0, 100), listReads())
        assertNull("nothing held back is taken in after the account scope ended", registry().find("late"))
    }

    @Test fun C25_08_aSpokenTargetIsCheckedAgainstAFreshCreationOrderCatalogAndSubmittedOnce() {
        val alpha = conversation("alpha"); conversation("beta")
        fake.scripts[alpha] = { listOf(FakeHermesDashboard.complete("alpha-done")) }
        val m = ready()
        fake.restLog.clear()
        // The list pages read up to the submit itself; the app's own recovery read after a delivery is not the route's catalog.
        val atSubmit = ConcurrentLinkedQueue<List<ListRead>>()
        fake.submitReplyFault = { atSubmit.add(listReads()) } // observation only: the reply is sent unchanged
        m.user("alpha 대화에 회의록 요약을 부탁해"); m.delegate("d1")
        assertTrue("the validated spoken choice is submitted once", await(20_000) { fake.prompts.size == 1 })
        assertEquals(alpha, fake.prompts.single().first)
        assertEquals("alpha 대화에 회의록 요약을 부탁해", fake.prompts.single().second)
        assertEquals("the spoken choice was checked against Live's own fresh creation-order catalog", listOf(created(0)), atSubmit.toList())
        settle(); idle(1_000); settle()
        assertEquals("never resent", 1, fake.prompts.size)
    }
}
