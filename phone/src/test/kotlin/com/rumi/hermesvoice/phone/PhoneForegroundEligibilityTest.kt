package com.rumi.hermesvoice.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Audio focus on Android 15+ is only available to the visible app or to a foreground service the platform ADMITTED. The mere
 * existence of a service object (or the field that points at it) is not that admission.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class PhoneForegroundEligibilityTest {
    @Test fun `before Android 15 focus is not gated`() {
        assertTrue(PhoneApp.focusEligible(34, activityVisible = false, relayForeground = false, wakeForeground = false))
    }

    @Test fun `on Android 15 a hidden app with no admitted foreground service is not eligible`() {
        assertFalse(PhoneApp.focusEligible(35, activityVisible = false, relayForeground = false, wakeForeground = false))
    }

    @Test fun `on Android 15 the visible app or either admitted service is eligible`() {
        assertTrue(PhoneApp.focusEligible(35, activityVisible = true, relayForeground = false, wakeForeground = false))
        assertTrue(PhoneApp.focusEligible(35, activityVisible = false, relayForeground = true, wakeForeground = false))
        assertTrue(PhoneApp.focusEligible(35, activityVisible = false, relayForeground = false, wakeForeground = true))
    }

    @Test fun `a relay service that was started while the relay is off never counts as foreground`() {
        val app = RuntimeEnvironment.getApplication() as PhoneApp
        val controller = Robolectric.buildService(PhoneRelayService::class.java, PhoneRelayService.start(app)).create().startCommand(0, 1)
        assertFalse(controller.get().foregroundActive)
        assertFalse(PhoneRelayService.foregroundActive)
        assertFalse(PhoneRelayService.running != null)
    }

    @Test fun `a service that was only created is not foreground`() {
        val app = RuntimeEnvironment.getApplication() as PhoneApp
        assertFalse(Robolectric.buildService(PhoneRelayService::class.java).create().get().foregroundActive)
        assertFalse(Robolectric.buildService(PhoneWakeService::class.java).create().get().foregroundActive)
        assertFalse(PhoneWakeService.foregroundActive)
        assertTrue(app != null)
    }
}
