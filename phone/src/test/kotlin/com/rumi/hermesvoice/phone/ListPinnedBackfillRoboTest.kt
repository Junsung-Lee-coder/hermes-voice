package com.rumi.hermesvoice.phone

import com.rumi.hermesvoice.core.sessions.AppSources
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The dashboard list appends every pinned row a page window missed, archived or not, to every page (hermes_state_sessions.py
 * list_sessions_rich include_pinned, both orders), while `offset`/`limit` address the ordered window. The real AppSessionRepository and
 * HermesDashboardClient over real HTTP against the dashboard contract double, which serves that back-fill. No device or server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListPinnedBackfillRoboTest : PhoneRoboBase() {
    @Before fun setUp() = baseSetUp()
    @After fun tearDown() = baseTearDown()

    private fun repo() = app.wiring().core.sessions
    private fun noise(id: String, pinned: Boolean = false) { fake.addForeignRow(id, "cron"); fake.rows.getValue(id).pinned = pinned }
    private fun marked(id: String, archived: Boolean = false, pinned: Boolean = false) {
        fake.addForeignRow(id, "discord", archived = archived, title = "Topic $id"); fake.setCreatedSource(id, AppSources.CONVERSATION)
        fake.rows.getValue(id).pinned = pinned
    }
    private fun listOffsets(view: String) = fake.restLog.toList().filter { it.startsWith("GET /api/sessions?") && it.contains("archived=$view") }
        .map { Regex("offset=(\\d+)").find(it)!!.groupValues[1].toInt() }

    @Test fun P25_01_pinnedRowsOnEveryPageNeitherSkipOrderedRowsNorBringArchivedOnesIntoTheActiveList() {
        val gone = conversation("gone"); fake.rows.getValue(gone).archived = true; fake.rows.getValue(gone).pinned = true
        marked("pin-archived", archived = true, pinned = true)
        // Default order lists the newest inserted first: tail is last (position 200), skip-me is the first row of the second window.
        marked("tail")
        for (i in 98 downTo 0) noise("b-$i")
        marked("skip-me")
        noise("pin-active", pinned = true)
        for (i in 98 downTo 0) noise("a-$i")
        fake.restLog.clear()
        val listing = runBlocking { repo().listConversations(false) }
        assertTrue(listing.error.toString(), listing.complete)
        assertEquals("every ordered marked row, and no archived one, in the active list", setOf("skip-me", "tail"), listing.map { it.stored.id }.toSet())
        assertNotNull("the first row of the second window is read and taken in", repo().registry.find("skip-me"))
        assertNull("an archived pinned marked session is not taken in as active", repo().registry.find("pin-archived"))
        assertTrue("a registered conversation archived on the dashboard is withheld from the active list", gone in listing.removedIds)
        assertEquals("the ordered windows are read one after another", listOf(0, 100, 200), listOffsets("exclude"))
    }

    @Test fun P25_02_anActivePinnedConversationAppendedToArchivedPagesIsNotListedAsArchived() {
        val kept = conversation("kept")
        runBlocking { repo().setArchived(kept, true) }
        val active = conversation("active-pinned"); fake.rows.getValue(active).pinned = true
        fake.restLog.clear()
        val listing = runBlocking { repo().listConversations(true) }
        assertTrue(listing.error.toString(), listing.complete)
        assertEquals("only archived conversations in the archived list", setOf(kept), listing.map { it.stored.id }.toSet())
        assertFalse("the active registration stays active", repo().registry.find(active)!!.archived)
        assertEquals(listOf(0), listOffsets("only"))
    }
}
