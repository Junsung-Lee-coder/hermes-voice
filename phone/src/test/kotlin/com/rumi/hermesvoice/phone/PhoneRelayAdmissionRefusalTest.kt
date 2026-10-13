package com.rumi.hermesvoice.phone

import androidx.core.app.ServiceCompat
import com.rumi.hermesvoice.core.background.BackgroundNotice
import io.mockk.every
import io.mockk.mockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual PhoneApp -> PhoneRelayService callbacks; Android admission is simulated, no device/audio proof. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class PhoneRelayAdmissionRefusalTest : PhoneRoboBase() {
    private val services = mutableListOf<ServiceController<PhoneRelayService>>()
    private var refuse = false

    @Before fun setUp() {
        baseSetUp()
        app.onActivityStarted()
        mockkStatic(ServiceCompat::class)
        every { ServiceCompat.startForeground(any(), any(), any(), any()) } answers {
            if (refuse) throw SecurityException("admission refused")
            Unit
        }
    }

    @After fun tearDown() {
        runCatching { app.stopRelay() }
        services.forEach { runCatching { it.destroy() } }
        baseTearDown()
    }

    private fun startService(): ServiceController<PhoneRelayService> {
        app.startRelay()
        val intent = shadowOf(app).nextStartedService
        assertNotNull("a visible start requested the actual relay", intent)
        return Robolectric.buildService(PhoneRelayService::class.java, intent).create().startCommand(0, 1)
            .also { services += it }
    }

    @Test fun refusalRemainsRefusedAfterDestroyAndDuplicateGone() {
        refuse = true
        val service = startService()
        val endedGeneration = app.relay.generation - 1
        assertEquals(BackgroundNotice.REFUSED, app.relay.status.notice)
        assertTrue(app.relay.status.wanted)
        assertFalse(app.relay.status.running)
        assertFalse(app.relay.status.microphone)
        assertFalse(app.relay.isCurrent(endedGeneration))
        assertFalse(app.relayForeground.value)
        assertFalse(service.get().foregroundActive)
        service.destroy()
        services.remove(service)
        app.onRelayServiceGone(endedGeneration)
        assertEquals(BackgroundNotice.REFUSED, app.relay.status.notice)
    }

    @Test fun visibleRetryClearsRefusalAndOldCleanupCannotPauseTheNewSession() {
        refuse = true
        val old = startService()
        assertEquals(BackgroundNotice.REFUSED, app.relay.status.notice)
        val endedGeneration = app.relay.generation - 1
        refuse = false
        val current = startService()
        val currentGeneration = app.relay.generation
        assertEquals(BackgroundNotice.RUNNING, app.relay.status.notice)
        assertTrue(app.relayForeground.value)
        assertTrue(current.get().foregroundActive)
        assertTrue(app.relay.isCurrent(currentGeneration))
        old.destroy()
        services.remove(old)
        app.onRelayServiceGone(endedGeneration)
        assertEquals(BackgroundNotice.RUNNING, app.relay.status.notice)
        assertEquals(currentGeneration, app.relay.generation)
        assertTrue(app.relayForeground.value)
    }

    @Test fun actualSystemDestructionIsPausedNotRefused() {
        val service = startService()
        assertTrue(app.relayForeground.value)
        service.destroy()
        services.remove(service)
        assertEquals(BackgroundNotice.PAUSED, app.relay.status.notice)
        assertTrue(app.relay.status.wanted)
        assertFalse(app.relay.status.running)
        assertFalse(app.relayForeground.value)
    }

    @Test fun explicitStopAfterRefusalIsOffAndLateCleanupCannotRestoreWanted() {
        refuse = true
        val service = startService()
        val endedGeneration = app.relay.generation - 1
        app.stopRelay()
        service.destroy()
        services.remove(service)
        app.onRelayServiceGone(endedGeneration)
        assertEquals(BackgroundNotice.OFF, app.relay.status.notice)
        assertFalse(app.relay.status.wanted)
        assertFalse(app.relay.status.running)
        assertFalse(app.relayForeground.value)
    }
}
