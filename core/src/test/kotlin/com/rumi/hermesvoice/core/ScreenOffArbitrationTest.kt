package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.wake.ClaimVerdict
import com.rumi.hermesvoice.core.wake.WakeAdmission
import com.rumi.hermesvoice.core.wake.WakeClaim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two devices, each deciding from its own screen and preference. Arbitration stays conservative (a device that MAY listen in
 * some mode makes a claim necessary), while only a device that is really listening ever raises a claim, so a screen-denied device
 * can neither delay nor double the other's request.
 */
class ScreenOffArbitrationTest {
    private fun watchHidden(watchPref: Boolean, phonePref: Boolean) =
        ComposedWatch(WakeLocation.BOTH, arbitrated = true, watchStandby = true, phoneStandby = true, watchScreenOff = watchPref, phoneScreenOff = phonePref)
            .apply { show(); start(); hide(); screenOff() }

    private fun phoneHidden(w: ComposedWatch): ScreenOffPhoneTest.Rig {
        val phone = ScreenOffPhoneTest.Rig(pref = false).startedAndHidden()
        phone.settings = w.settings
        phone.background.onEligibilityChanged(); phone.drain()
        phone.screenOff()
        return phone
    }

    private fun claim(w: ComposedWatch, id: String) = WakeClaim(id, VoiceOrigin.WATCH, "w", w.settings.revision, w.wake.generation, 0)

    @Test
    fun `an eligible screen-off Watch completes its episode through the claim while the screen-denied Phone listens to nothing`() {
        val watch = watchHidden(watchPref = true, phonePref = false)
        val phone = phoneHidden(watch)
        assertTrue("the Watch is listening on its own preference", watch.windowOpen)
        assertFalse("the Phone is screen-denied on its own preference", phone.wake.listening)
        val phoneListens = phone.listened()
        val admission = WakeAdmission({ watch.now }, { watch.settings })
        assertTrue("both devices may listen in some mode: a claim is required", admission.required())
        watch.heard("루미")
        assertEquals(listOf("claim:claim-1"), watch.claimsSent)
        assertNull("nothing is recorded before the Phone side grants the claim", watch.handoffIn)
        assertEquals(ClaimVerdict.GRANTED, admission.claim(claim(watch, "claim-1")))
        watch.wake.onClaimVerdict("claim-1", ClaimVerdict.GRANTED); watch.drain()
        assertNotNull(watch.handoffIn)
        watch.handoffDue()
        assertTrue(watch.capturing)
        assertEquals("the screen-denied Phone never opened a window", phoneListens, phone.listened())
        assertFalse(phone.wake.listening)
        assertFalse(phone.calls.any { it.startsWith("send:") || it == "capture" })
    }

    @Test
    fun `an eligible screen-off Phone and a screen-denied Watch are the mirror image`() {
        val watch = watchHidden(watchPref = false, phonePref = true)
        val phone = ScreenOffPhoneTest.Rig(pref = true).startedAndHidden()
        phone.settings = watch.settings
        phone.background.onEligibilityChanged(); phone.drain()
        phone.screenOff()
        assertTrue(phone.wake.listening)
        assertFalse("the Watch's own preference is off", watch.windowOpen)
        assertTrue(watch.held().isEmpty())
        assertNull(watch.rearmIn)
        watch.heard("루미")
        assertTrue("a torn-down Watch hears nothing and claims nothing: ${watch.claimsSent}", watch.claimsSent.isEmpty())
        assertNull(watch.handoffIn)
    }

    @Test
    fun `both devices eligible, one claim is granted and the other is held back`() {
        val watch = watchHidden(watchPref = true, phonePref = true)
        val admission = WakeAdmission({ watch.now }, { watch.settings })
        assertEquals(ClaimVerdict.GRANTED, admission.claim(claim(watch, "watch-claim-1")))
        assertEquals(ClaimVerdict.HELD_BY_OTHER, admission.claim(WakeClaim("phone-claim-1", VoiceOrigin.PHONE, "p", watch.settings.revision, 1, 0)))
    }

    @Test
    fun `neither device eligible, neither listens and nobody claims`() {
        val watch = watchHidden(watchPref = false, phonePref = false)
        val phone = phoneHidden(watch)
        assertFalse(watch.windowOpen)
        assertFalse(phone.wake.listening)
        assertTrue(watch.held().isEmpty())
        assertTrue(phone.held.isEmpty())
        watch.heard("루미")
        assertTrue(watch.claimsSent.isEmpty())
        assertNull(watch.rearmIn)
    }

    @Test
    fun `a claim made before a preference change is stale under the newer snapshot`() {
        val watch = watchHidden(watchPref = true, phonePref = true)
        val admission = WakeAdmission({ watch.now }, { watch.settings })
        val old = claim(watch, "watch-claim-1")
        watch.screenOffPreference(phone = false)
        assertEquals(ClaimVerdict.STALE_SETTINGS, admission.claim(old))
    }

    @Test
    fun `an episode waiting for its claim is dropped when the screen turns off, and a late grant records nothing`() {
        val watch = ComposedWatch(WakeLocation.BOTH, arbitrated = true, watchStandby = true, phoneStandby = true, watchScreenOff = false)
            .apply { show(); start(); hide() }
        watch.heard("루미")
        assertEquals(listOf("claim:claim-1"), watch.claimsSent)
        watch.screenOff()
        assertNull(watch.handoffIn)
        assertFalse(watch.windowOpen)
        watch.wake.onClaimVerdict("claim-1", ClaimVerdict.GRANTED); watch.drain()
        watch.handoffDue()
        assertFalse("a late grant never starts a recording after the teardown", watch.capturing)
        assertTrue(watch.held().isEmpty())
        assertFalse(watch.log.contains("stopService"))
    }
}
