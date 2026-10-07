package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.settings.WatchSettings
import com.rumi.hermesvoice.core.wake.ClaimVerdict
import com.rumi.hermesvoice.core.wake.WakeAdmission
import com.rumi.hermesvoice.core.wake.WakeClaim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Phone's claim arbitration follows who MAY listen (foreground location or standby switch), so two devices never both send. */
class StandbyArbitrationTest {
    private fun admission(settings: WatchSettings) = WakeAdmission({ 1_000L }, { settings })
    private fun claim(origin: VoiceOrigin, id: String, node: String, revision: Long = 5) = WakeClaim(id, origin, node, revision, 1, 0)

    @Test
    fun `two standby switches on need a claim even with the location off, and the second device is held back`() {
        val settings = WatchSettings(WakeLocation.OFF, revision = 5, phoneBackgroundWakeEnabled = true, watchBackgroundWakeEnabled = true)
        val a = admission(settings)
        assertTrue(a.required())
        assertEquals(ClaimVerdict.GRANTED, a.claim(claim(VoiceOrigin.WATCH, "watch-claim-1", "w")))
        assertEquals(ClaimVerdict.HELD_BY_OTHER, a.claim(claim(VoiceOrigin.PHONE, "phone-claim-1", "p")))
        assertNull(a.admitTurn(true, VoiceOrigin.WATCH, "w", "watch-claim-1"))
        assertEquals("a wake turn without its claim is refused, never doubled", "wake_claim_invalid", a.admitTurn(true, VoiceOrigin.PHONE, "p", "phone-claim-1"))
    }

    @Test
    fun `a device that may not listen is refused and a lone listener needs no claim`() {
        val watchOnly = WatchSettings(WakeLocation.OFF, revision = 5, watchBackgroundWakeEnabled = true)
        val a = admission(watchOnly)
        assertFalse(a.required())
        assertEquals(ClaimVerdict.NOT_LISTENING, a.claim(claim(VoiceOrigin.PHONE, "phone-claim-1", "p")))
        assertEquals(ClaimVerdict.GRANTED, a.claim(claim(VoiceOrigin.WATCH, "watch-claim-1", "w")))
        assertNull("no claim is needed for a lone listener", a.admitTurn(true, VoiceOrigin.WATCH, "w", null))
    }

    @Test
    fun `the foreground location and a standby switch combine per device`() {
        val mixed = WatchSettings(WakeLocation.WATCH, revision = 5, phoneBackgroundWakeEnabled = true)
        val a = admission(mixed)
        assertTrue("the Watch listens in the foreground and the Phone in the background", a.required())
        assertEquals(ClaimVerdict.GRANTED, a.claim(claim(VoiceOrigin.PHONE, "phone-claim-1", "p")))
        assertEquals(ClaimVerdict.HELD_BY_OTHER, a.claim(claim(VoiceOrigin.WATCH, "watch-claim-1", "w")))
    }

    @Test
    fun `a claim made under other settings is still refused after a standby switch changed`() {
        var current = WatchSettings(WakeLocation.OFF, revision = 5, phoneBackgroundWakeEnabled = true, watchBackgroundWakeEnabled = true)
        val a = WakeAdmission({ 1_000L }, { current })
        current = current.copy(revision = 6, phoneBackgroundWakeEnabled = false)
        assertEquals(ClaimVerdict.STALE_SETTINGS, a.claim(claim(VoiceOrigin.WATCH, "watch-claim-1", "w", revision = 5)))
        assertEquals(ClaimVerdict.NOT_LISTENING, a.claim(claim(VoiceOrigin.PHONE, "phone-claim-1", "p", revision = 6)))
    }
}
