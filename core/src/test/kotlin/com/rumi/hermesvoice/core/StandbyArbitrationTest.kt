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

/** The Phone's claim arbitration follows who MAY listen ("Listen on", the master gate), so two devices never both send. */
class StandbyArbitrationTest {
    private fun admission(settings: WatchSettings) = WakeAdmission({ 1_000L }, { settings })
    private fun claim(origin: VoiceOrigin, id: String, node: String, revision: Long = 5) = WakeClaim(id, origin, node, revision, 1, 0)

    @Test
    fun `Listen on Both needs a claim whatever the standby switches say, and the second device is held back`() {
        for ((phoneStandby, watchStandby) in listOf(true to true, false to false)) {
            val settings = WatchSettings(WakeLocation.BOTH, revision = 5, phoneBackgroundWakeEnabled = phoneStandby, watchBackgroundWakeEnabled = watchStandby)
            val a = admission(settings)
            assertTrue(a.required())
            assertEquals(ClaimVerdict.GRANTED, a.claim(claim(VoiceOrigin.WATCH, "watch-claim-1", "w")))
            assertEquals(ClaimVerdict.HELD_BY_OTHER, a.claim(claim(VoiceOrigin.PHONE, "phone-claim-1", "p")))
            assertNull(a.admitTurn(true, VoiceOrigin.WATCH, "w", "watch-claim-1"))
            assertEquals("a wake turn without its claim is refused, never doubled", "wake_claim_invalid", a.admitTurn(true, VoiceOrigin.PHONE, "p", "phone-claim-1"))
        }
    }

    @Test
    fun `two standby switches on with Listen on Off need no claim and admit no device`() {
        val settings = WatchSettings(WakeLocation.OFF, revision = 5, phoneBackgroundWakeEnabled = true, watchBackgroundWakeEnabled = true)
        val a = admission(settings)
        assertFalse(a.required())
        assertEquals(ClaimVerdict.NOT_LISTENING, a.claim(claim(VoiceOrigin.WATCH, "watch-claim-1", "w")))
        assertEquals(ClaimVerdict.NOT_LISTENING, a.claim(claim(VoiceOrigin.PHONE, "phone-claim-1", "p")))
    }

    @Test
    fun `a device Listen on leaves out is refused and a lone listener needs no claim`() {
        val watchOnly = WatchSettings(WakeLocation.WATCH, revision = 5, phoneBackgroundWakeEnabled = true, watchBackgroundWakeEnabled = true)
        val a = admission(watchOnly)
        assertFalse(a.required())
        assertEquals("its own standby switch does not admit the Phone", ClaimVerdict.NOT_LISTENING, a.claim(claim(VoiceOrigin.PHONE, "phone-claim-1", "p")))
        assertEquals(ClaimVerdict.GRANTED, a.claim(claim(VoiceOrigin.WATCH, "watch-claim-1", "w")))
        assertNull("no claim is needed for a lone listener", a.admitTurn(true, VoiceOrigin.WATCH, "w", null))
    }

    @Test
    fun `Listen on decides who may be claimed, the standby switches only narrow when a selected device listens`() {
        val mixed = WatchSettings(WakeLocation.WATCH, revision = 5, phoneBackgroundWakeEnabled = true)
        val a = admission(mixed)
        assertFalse("the Phone's standby switch does not make it a second listener", a.required())
        assertEquals(ClaimVerdict.NOT_LISTENING, a.claim(claim(VoiceOrigin.PHONE, "phone-claim-1", "p")))
        assertEquals(ClaimVerdict.GRANTED, a.claim(claim(VoiceOrigin.WATCH, "watch-claim-1", "w")))
    }

    @Test
    fun `a claim made under other settings is still refused after Listen on or a standby switch changed`() {
        var current = WatchSettings(WakeLocation.BOTH, revision = 5, phoneBackgroundWakeEnabled = true, watchBackgroundWakeEnabled = true)
        val a = WakeAdmission({ 1_000L }, { current })
        current = current.copy(revision = 6, wakeLocation = WakeLocation.WATCH)
        assertEquals(ClaimVerdict.STALE_SETTINGS, a.claim(claim(VoiceOrigin.WATCH, "watch-claim-1", "w", revision = 5)))
        assertEquals(ClaimVerdict.NOT_LISTENING, a.claim(claim(VoiceOrigin.PHONE, "phone-claim-1", "p", revision = 6)))
        current = current.copy(revision = 7, phoneBackgroundWakeEnabled = false)
        assertEquals(ClaimVerdict.STALE_SETTINGS, a.claim(claim(VoiceOrigin.WATCH, "watch-claim-2", "w", revision = 6)))
    }
}
