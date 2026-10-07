package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.settings.LaterReplyWindow
import com.rumi.hermesvoice.core.voice.VoiceTurnOrchestrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The Phone-owned later-reply window: range, default, strict parsing, persistence and the minutes → millis conversion. */
class LaterReplyWindowSettingTest {
    private fun settings(store: KeyValueStore = InMemoryKeyValueStore()) = AppSettings(store)

    @Test
    fun `the default is 30 minutes and the range is 1 to 4320 minutes, which is 72 hours`() {
        assertEquals(30, LaterReplyWindow.DEFAULT_MINUTES)
        assertEquals(1, LaterReplyWindow.MIN_MINUTES)
        assertEquals(4320, LaterReplyWindow.MAX_MINUTES)
        assertEquals(72 * 60, LaterReplyWindow.MAX_MINUTES)
        assertEquals("later_reply_window_minutes", AppSettings.KEY_LATER_REPLY_WINDOW_MINUTES)
        assertEquals(30, settings().laterReplyWindowMinutes)
        assertEquals(VoiceTurnOrchestrator.LATER_WINDOW_MS, settings().laterReplyWindowMillis)
        assertTrue(LaterReplyWindow.presets.all { it in 1..4320 })
        assertTrue(LaterReplyWindow.presets.contains(30) && LaterReplyWindow.presets.contains(4320))
    }

    @Test
    fun `boundary values 1, 30 and 4320 persist and convert to exact Long milliseconds`() {
        for ((minutes, millis) in listOf(1 to 60_000L, 30 to 1_800_000L, 4320 to 259_200_000L)) {
            val store = InMemoryKeyValueStore()
            settings(store).laterReplyWindowMinutes = minutes
            assertEquals(minutes.toString(), store.getString("later_reply_window_minutes"))
            val reread = settings(store)
            assertEquals(minutes, reread.laterReplyWindowMinutes)
            assertEquals(millis, reread.laterReplyWindowMillis)
            assertEquals(millis, LaterReplyWindow.millis(minutes))
        }
        assertTrue("the largest window does not fit in an Int of milliseconds, so the conversion is Long",
            LaterReplyWindow.millis(4320) > Int.MAX_VALUE.toLong() / 10)
        assertEquals(VoiceTurnOrchestrator.LATER_WINDOW_MAX_MS, LaterReplyWindow.millis(4320))
        assertEquals(VoiceTurnOrchestrator.LATER_WINDOW_MIN_MS, LaterReplyWindow.millis(1))
    }

    @Test
    fun `the setter refuses anything outside the range and leaves the stored value alone`() {
        val store = InMemoryKeyValueStore()
        val s = settings(store)
        s.laterReplyWindowMinutes = 90
        for (bad in listOf(0, -1, 4321, Int.MIN_VALUE, Int.MAX_VALUE)) {
            try {
                s.laterReplyWindowMinutes = bad
                fail("$bad must be refused")
            } catch (_: IllegalArgumentException) {
            }
            assertEquals(90, settings(store).laterReplyWindowMinutes)
        }
        try {
            LaterReplyWindow.millis(0)
            fail("0 minutes has no milliseconds")
        } catch (_: IllegalArgumentException) {
        }
        try {
            LaterReplyWindow.millis(4321)
            fail("4321 minutes has no milliseconds")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `a missing, malformed, corrupt or out-of-range stored value reads as 30 minutes`() {
        val bad = listOf("", " ", "abc", "-5", "+30", "0", "00", "4321", "99999", "1000000", "30.5", "3e1", "0x1e", "30 ", " 30", "١٢", "NaN",
            "Infinity", "9999999999999999999", "{\"v\":30}", "30,0", "１２")
        for (raw in bad) {
            val store = InMemoryKeyValueStore()
            store.putString("later_reply_window_minutes", raw)
            val s = settings(store)
            assertEquals("stored '$raw'", 30, s.laterReplyWindowMinutes)
            assertEquals("stored '$raw'", 1_800_000L, s.laterReplyWindowMillis)
            assertNull("stored '$raw'", LaterReplyWindow.parseStored(raw))
        }
        assertNull(LaterReplyWindow.parseStored(null))
        assertEquals(1, LaterReplyWindow.parseStored("1"))
        assertEquals(4320, LaterReplyWindow.parseStored("4320"))
        assertEquals(45, LaterReplyWindow.parseStored("045"))
    }

    @Test
    fun `typed entries are strict whole numbers scaled by their unit, range-checked without overflow`() {
        fun valid(text: String, unit: LaterReplyWindow.Unit) = (LaterReplyWindow.fromEntry(text, unit) as LaterReplyWindow.Entry.Valid).minutes
        fun invalid(text: String, unit: LaterReplyWindow.Unit) = (LaterReplyWindow.fromEntry(text, unit) as LaterReplyWindow.Entry.Invalid).reason
        val m = LaterReplyWindow.Unit.MINUTES
        val h = LaterReplyWindow.Unit.HOURS
        val d = LaterReplyWindow.Unit.DAYS
        assertEquals(1, valid("1", m))
        assertEquals(30, valid(" 30 ", m))
        assertEquals(4320, valid("4320", m))
        assertEquals(60, valid("1", h))
        assertEquals(4320, valid("72", h))
        assertEquals(1440, valid("1", d))
        assertEquals(4320, valid("3", d))
        assertEquals("At most 3 days (4320 minutes)", invalid("4321", m))
        assertEquals("At most 3 days (4320 minutes)", invalid("73", h))
        assertEquals("At most 3 days (4320 minutes)", invalid("4", d))
        assertEquals("At most 3 days (4320 minutes)", invalid("9999999", d))
        assertEquals("At least 1 minute", invalid("0", m))
        assertEquals("At least 1 minute", invalid("000", d))
        for (junk in listOf("", "  ", "-1", "+1", "1.5", "1e2", "0x10", "abc", "1 2", "٣", "99999999999999999999", "NaN")) {
            assertEquals("'$junk'", "Enter a whole number", invalid(junk, m))
        }
    }

    @Test
    fun `the displayed unit is the largest that divides the stored minutes exactly, and the words name the exact duration`() {
        assertEquals(LaterReplyWindow.Unit.MINUTES to 30, LaterReplyWindow.displayUnit(30) to LaterReplyWindow.displayValue(30))
        assertEquals(LaterReplyWindow.Unit.HOURS to 1, LaterReplyWindow.displayUnit(60) to LaterReplyWindow.displayValue(60))
        assertEquals(LaterReplyWindow.Unit.MINUTES to 90, LaterReplyWindow.displayUnit(90) to LaterReplyWindow.displayValue(90))
        assertEquals(LaterReplyWindow.Unit.HOURS to 6, LaterReplyWindow.displayUnit(360) to LaterReplyWindow.displayValue(360))
        assertEquals(LaterReplyWindow.Unit.DAYS to 1, LaterReplyWindow.displayUnit(1440) to LaterReplyWindow.displayValue(1440))
        assertEquals(LaterReplyWindow.Unit.DAYS to 3, LaterReplyWindow.displayUnit(4320) to LaterReplyWindow.displayValue(4320))
        assertEquals("1 minute", LaterReplyWindow.describe(1))
        assertEquals("30 minutes", LaterReplyWindow.describe(30))
        assertEquals("1 hour", LaterReplyWindow.describe(60))
        assertEquals("1 hour 30 minutes", LaterReplyWindow.describe(90))
        assertEquals("1 day", LaterReplyWindow.describe(1440))
        assertEquals("1 day 1 hour 1 minute", LaterReplyWindow.describe(1501))
        assertEquals("3 days", LaterReplyWindow.describe(4320))
        assertEquals("an invalid value is described as the default, never as something it is not", "30 minutes", LaterReplyWindow.describe(0))
        for (minutes in LaterReplyWindow.MIN_MINUTES..LaterReplyWindow.MAX_MINUTES) {
            val unit = LaterReplyWindow.displayUnit(minutes)
            assertEquals(minutes, LaterReplyWindow.displayValue(minutes) * unit.minutes)
        }
    }

    @Test
    fun `changing the duration never touches the later-reply opt-in or any other setting`() {
        val written = mutableListOf<String>()
        val backing = InMemoryKeyValueStore()
        val store = object : KeyValueStore by backing {
            override fun putString(key: String, value: String) { written += key; backing.putString(key, value) }
        }
        val local = InMemoryKeyValueStore()
        val consent = com.rumi.hermesvoice.core.background.LaterReplyConsent(local)
        assertFalse(consent.enabled)
        val s = settings(store)
        val before = s.watchSettings()
        s.laterReplyWindowMinutes = 4320
        assertFalse("a long window is not consent", consent.enabled)
        assertEquals(before, s.watchSettings())
        assertFalse(s.playFirstResponse || s.playMiddleResponses || s.autoNavigateToRouted || s.watchAutoNavigateToRouted)
        assertTrue(s.routingEnabled)
        assertEquals("the duration is Phone-store only: nothing about it is in the shared Watch snapshot",
            false, s.watchSettings().toJson().contains("later"))
        consent.enabled = true
        s.laterReplyWindowMinutes = 1
        assertTrue("the opt-in keeps its own state", consent.enabled)
        assertEquals("only its own key is written", listOf("later_reply_window_minutes", "later_reply_window_minutes"), written.toList())
    }
}
