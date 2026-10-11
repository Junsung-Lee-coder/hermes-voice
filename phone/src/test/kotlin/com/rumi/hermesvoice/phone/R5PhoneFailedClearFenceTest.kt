package com.rumi.hermesvoice.phone

import android.content.Context
import android.content.SharedPreferences
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.notify.ReplyAlert
import com.rumi.hermesvoice.core.voice.UnplayedReplyStore
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * R5 completion: when the stored copy of the unplayed replies cannot be cleared (the commit fails), a NEW wiring of the same
 * dashboard/profile must not read it back, replay it or add to it, and the user is told with fixed text; once a clear works the
 * fence lifts. Through the real PhoneApp wiring, PhoneViewModel sign-out and connection-save, and the real SharedPreferences backing
 * with only the commit made to fail. Not covered: a process that dies while the commit keeps failing (the stored text then remains
 * on the phone until some later access clears it).
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class R5PhoneFailedClearFenceTest : PhoneRoboBase() {
    private val secret = "R5-FENCE-CACHED-ANSWER-TEXT"
    private val commits = AtomicInteger()
    @Volatile private var failing = false

    @Before fun setUp() {
        baseSetUp()
        val real = real()
        app.unplayedPrefs = { if (failing) failingView(real) else real }
    }

    @After fun tearDown() = baseTearDown()

    private fun real(): SharedPreferences = app.getSharedPreferences(UnplayedReplyStore.PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun failingView(base: SharedPreferences): SharedPreferences = object : SharedPreferences by base {
        override fun edit(): SharedPreferences.Editor = FailingEditor()
    }

    // Chained calls (putString(..).commit(), clear().commit()) must stay on this wrapper, so every mutator returns it.
    private inner class FailingEditor : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = this
        override fun putStringSet(key: String?, values: MutableSet<String>?) = this
        override fun putInt(key: String?, value: Int) = this
        override fun putLong(key: String?, value: Long) = this
        override fun putFloat(key: String?, value: Float) = this
        override fun putBoolean(key: String?, value: Boolean) = this
        override fun remove(key: String?) = this
        override fun clear() = this
        override fun commit(): Boolean { commits.incrementAndGet(); return false }
        override fun apply() {}
    }

    private fun storeKey(): String {
        val endpoint = HermesDashboardEndpoint.parse(app.settings.dashboardUrl)
        val digest = MessageDigest.getInstance("SHA-256").digest("$endpoint|${app.settings.profile}".toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        return UnplayedReplyStore.KEY + "_" + digest
    }

    private fun seed(turnId: String): String {
        val identity = ReplyAlert.identityOf(turnId, "final")
        val store = UnplayedReplyStore(SharedPreferencesKeyValueStore(real()), key = storeKey())
        assertTrue(store.put(UnplayedReplyStore.Entry(identity, turnId, VoiceOrigin.WATCH, "watch-node", "s-1", true, secret, System.currentTimeMillis())))
        return identity
    }

    private fun storedAnywhere(): Boolean = real().all.values.any { it?.toString()?.contains(secret) == true }

    @Test fun aFailedClearOnSignOutKeepsTheStoredTextHiddenFromAFreshWiringOfTheSameIdentityAndTellsTheUser() {
        val identity = seed("turn-so")
        assertTrue(app.wiring().core.orchestrator.unplayedReplies.value.any { it.identity == identity })
        val model = PhoneViewModel(app)
        failing = true
        model.signOut()
        assertTrue("the failed commit was really attempted", commits.get() > 0)
        assertTrue("nothing is claimed deleted: the stored string still holds it", storedAnywhere())
        assertTrue("the user is told, with fixed text", model.state.value.status.contains(PhoneViewModel.UNPLAYED_NOT_CLEARED))
        assertFalse("the note never carries the answer", model.state.value.status.contains(secret))
        assertTrue(await { app.unplayedReplies.value.isEmpty() })

        val fresh = app.wiring()
        settle()
        assertTrue("a new wiring of the same dashboard/profile does not load the stored text", fresh.core.orchestrator.unplayedReplies.value.isEmpty())
        assertTrue(app.unplayedReplies.value.isEmpty())
        val calls = AtomicInteger()
        assertFalse("and it cannot be replayed", app.replayReply(identity) { _, _ -> calls.incrementAndGet() })
        settle()
        assertEquals(0, calls.get())
        assertTrue(fake.rawPrompts.isEmpty())

        failing = false
        assertTrue("once the clear works the stored text is removed", app.revokeUnplayedReplies())
        assertFalse(storedAnywhere())
        assertTrue(app.wiring().core.orchestrator.unplayedReplies.value.isEmpty())
    }

    @Test fun aFailedClearWhenTheDashboardProfileChangesIsReportedAndTheOldTextStaysHiddenEvenWhenTheOldIdentityReturns() {
        val identity = seed("turn-id")
        val original = app.settings.profile
        assertTrue(app.wiring().core.orchestrator.unplayedReplies.value.any { it.identity == identity })
        val model = PhoneViewModel(app)
        failing = true
        model.saveConnection(app.settings.dashboardUrl, "another-profile")
        assertTrue(model.state.value.status.contains(PhoneViewModel.UNPLAYED_NOT_CLEARED))
        assertTrue(storedAnywhere())
        model.saveConnection(app.settings.dashboardUrl, original)
        settle()
        assertTrue("back on the old identity the stored text is still not read", app.wiring().core.orchestrator.unplayedReplies.value.isEmpty())
        assertTrue(app.unplayedReplies.value.isEmpty())
        assertFalse(app.replayReply(identity) { _, _ -> })
    }

    @Test fun aSuccessfulClearReportsNothingExtraAndLeavesTheFenceOpen() {
        val identity = seed("turn-ok")
        assertTrue(app.wiring().core.orchestrator.unplayedReplies.value.any { it.identity == identity })
        val model = PhoneViewModel(app)
        model.signOut()
        assertEquals("Signed out on this phone", model.state.value.status)
        assertFalse(storedAnywhere())
        val later = seed("turn-later")
        assertTrue("a reply stored after a successful clear is kept and listed by the next wiring", app.wiring().core.orchestrator.unplayedReplies.value.any { it.identity == later })
    }

    @Test fun theFenceReadsNothingAndWritesNothingWhileBlockedAndPassesThroughWhenOpen() {
        val kv = com.rumi.hermesvoice.core.InMemoryKeyValueStore()
        kv.putString("k", "v")
        var blocked = true
        val fenced = FencedKeyValueStore({ kv }, { blocked })
        assertNull(fenced.getString("k"))
        assertFalse(fenced.commitString("k", "changed"))
        assertEquals("v", kv.getString("k"))
        blocked = false
        assertEquals("v", fenced.getString("k"))
        assertTrue(fenced.commitString("k", "changed"))
        assertEquals("changed", kv.getString("k"))
    }
}
