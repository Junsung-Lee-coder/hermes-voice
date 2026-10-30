package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.live.LiveLifetime
import org.junit.Test
import org.junit.Assert.*

class LiveLifetimeTest {
    @Test fun idleExpiresAtDefaultTenSecondsAndPollsOrAcksCannotExtendIt() {
        val p=LiveLifetime(1000)
        assertNull(p.expired(10999));assertEquals("idle",p.expired(11000))
    }
    @Test fun actualDialogueExtendsIdleButNeverAbsoluteCostCap() {
        val p=LiveLifetime(0)
        p.dialogue(9000);assertNull(p.expired(18000));assertEquals("idle",p.expired(19000))
        val cap=LiveLifetime(0)
        for(t in 0L..299000L step 1000L) cap.dialogue(t)
        assertEquals("cap",cap.expired(300000))
    }
    @Test fun backwardsClockAndBoundariesDoNotReopenAnExpiredSession() {
        val p=LiveLifetime(1000)
        p.dialogue(900);assertEquals("idle",p.expired(11000))
        p.dialogue(32000);assertEquals("idle",p.expired(32000))
    }
}
