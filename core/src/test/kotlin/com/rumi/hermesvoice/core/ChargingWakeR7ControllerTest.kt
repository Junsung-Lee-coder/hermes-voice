package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.background.HoldReason
import com.rumi.hermesvoice.core.settings.WakeLocation
import com.rumi.hermesvoice.core.wake.WakeDeviceController
import org.junit.Assert.*
import org.junit.Test

/**
 * The real predecessor controller has no charge input and is left unchanged when it lacks it.
 * No shim implements charging, selection, retry, capture or epochs. On the successor this forwards the same local
 * platform fact as its Android battery adapter. Missing input is a no-op (predecessor behavior), not compile/setup RED.
 */
internal fun WakeDeviceController.r7LocalCharge(charging: Boolean) {
    javaClass.methods.firstOrNull { it.name == "onChargingChanged" && it.parameterTypes.toList() == listOf(java.lang.Boolean.TYPE) }
        ?.invoke(this, charging)
}

class ChargingWakeR7ControllerTest {
    private fun option(w: ComposedWatch, phone: Boolean? = null, watch: Boolean? = null) {
        w.settings = w.settings.copy(revision = w.settings.revision + 1).r7Charging(phone, watch)
        w.coordinator.onEligibilityChanged(); w.drain()
    }
    private fun battery(w: ComposedWatch, localCharging: Boolean) {
        w.wake.r7LocalCharge(localCharging)
        w.coordinator.onEligibilityChanged(); w.drain()
    }
    private fun ready(charging: Boolean = true, enabled: Boolean = true): ComposedWatch =
        ComposedWatch(WakeLocation.WATCH, watchStandby = true, watchScreenOff = false, phoneScreenOff = false).apply {
            option(this, watch = enabled); battery(this, charging)
            show(); start(); hide(); screenOff()
        }

    @Test fun acceptedHandoffSurvivesUnplugAndOffWithoutAcceptingANewPhrase() {
        val w = ready(); assertTrue(w.windowOpen)
        w.heard("루미"); assertNotNull(w.handoffIn)
        battery(w, false); option(w, watch = false)
        assertNotNull("already accepted microphone handoff remains owned", w.handoffIn)
        w.handoffDue(); assertTrue(w.capturing)
    }

    @Test fun chargingPreservesArbitrationAndNonListeningWorkHolds() {
        val w = ComposedWatch(WakeLocation.BOTH, arbitrated = true, watchStandby = true, watchScreenOff = false)
        option(w, watch = true); battery(w, true); w.show(); w.start(); w.hide(); w.screenOff()
        assertTrue(w.windowOpen); w.heard("루미")
        assertFalse("no capture before a real granted claim", w.capturing); assertNotNull(w.claimsSent.firstOrNull())
        battery(w, false); option(w, watch = false); w.handoffDue()
        assertFalse("unaccepted claim is revoked", w.capturing)
        for (reason in listOf(HoldReason.TRANSFER, HoldReason.PLAYBACK)) w.holds.acquire(reason)
        val before = w.held(); battery(w, true); battery(w, false)
        assertEquals("charge only manages idle LISTEN resources", before, w.held())
    }

    @Test fun chargingCannotBypassUnreachablePhoneOrCooldown() {
        val w = ready(); assertTrue(w.windowOpen)
        w.busy = true; w.coordinator.onBusy(); w.drain(); w.busy = false
        w.reachable = false; w.coordinator.onIdle(); w.drain()
        assertFalse(w.windowOpen); assertNotNull(w.rearmIn)
        battery(w, false); assertNull(w.rearmIn)
        w.reachable = true; w.cooldownUntil = w.now + 4000
        battery(w, true); assertFalse(w.windowOpen); assertNotNull(w.rearmIn)
        w.rearmDue(); assertTrue(w.windowOpen)
    }

    @Test fun selectedChargingWatchListensWithScreenOffAndAodWithoutScreenForcing() {
        val w = ready()
        assertTrue("charging ON must allow the selected armed Watch while dark", w.windowOpen)
        assertFalse(w.screenOn)
        w.enterAmbient()
        assertTrue("ambient remains noninteractive but charging eligibility survives", w.windowOpen)
        assertFalse(w.screenOn); assertTrue(w.ambient)
        assertTrue(w.held().contains(HoldReason.LISTEN))
        assertTrue(w.lockLog.filter { it.startsWith("acquire:LISTEN:") }.all { it.substringAfterLast(':').toLong() in 1..65_000 })
    }

    @Test fun independentLocalChargeAndPreferenceMatrix() {
        for (local in listOf(false, true)) for (watch in listOf(false, true)) for (phone in listOf(false, true)) {
            val w = ready(charging = local, enabled = watch)
            option(w, phone = phone)
            assertEquals("Watch uses only local=$local watchOption=$watch, never Phone option=$phone", local && watch, w.windowOpen)
        }
    }

    @Test fun chargingCannotOverrideListenOnOrTheStandbyMaster() {
        for (mode in WakeLocation.values()) for (master in listOf(false, true)) {
            val w = ComposedWatch(mode, watchStandby = master, watchScreenOff = false, phoneScreenOff = false)
            option(w, phone = true, watch = true); battery(w, true)
            w.show(); w.start(); w.hide(); w.screenOff()
            assertEquals("mode=$mode standby=$master", mode.listensOn(VoiceOrigin.WATCH) && master, w.windowOpen)
        }
    }

    @Test fun chargingLiftsExpiredScreenBudgetButUnplugDoesNotMintANewEpoch() {
        val w = ComposedWatch(WakeLocation.WATCH, watchStandby = true, watchScreenOff = false)
        option(w, watch = true); w.show(); w.start(); w.hide()
        w.now += 6_000; w.wake.onTimer(); w.drain()
        assertFalse("ordinary five second activation expired", w.windowOpen)
        val end = w.wake.screenBudgetEndMs()
        battery(w, true)
        assertTrue("charging may rearm after the ordinary budget expired", w.windowOpen)
        val listens = w.listens()
        repeat(5) { battery(w, true) }
        assertEquals("duplicate charge callbacks never restart a live window", listens, w.listens())
        battery(w, false)
        assertFalse("unplug returns to expired ordinary activation", w.windowOpen)
        assertEquals("no invented screen activation", end, w.wake.screenBudgetEndMs())
        assertNull(w.rearmIn)
        w.coordinator.onRearmDue(); w.drain()
        assertFalse(w.windowOpen)
    }

    @Test fun chargingContinuouslyEligibleRearmUsesBoundedWindowsAndNoLockInTheGap() {
        val w = ready()
        assertTrue(w.windowOpen)
        repeat(3) {
            w.windowTimeout()
            assertFalse(w.windowOpen); assertFalse(w.held().contains(HoldReason.LISTEN))
            assertNotNull("bounded window is followed through existing rearm", w.rearmIn)
            w.rearmDue(); assertTrue(w.windowOpen)
        }
    }

    @Test fun optionOffCancelsIdleRetryAndLateAlarmOrRecognizerCannotResurrect() {
        val w = ready(); assertTrue(w.windowOpen)
        val gen = w.wake.generation
        w.wake.onError(gen, 2); w.drain()
        assertNotNull("positive pending retry", w.rearmIn)
        option(w, watch = false)
        assertNull(w.rearmIn); assertFalse(w.windowOpen); assertFalse(w.held().contains(HoldReason.LISTEN))
        w.coordinator.onRearmDue(); w.wake.onResults(gen, listOf("루미"), true); w.drain()
        assertFalse(w.windowOpen); assertFalse(w.capturing); assertNull(w.handoffIn)
    }

    @Test fun unplugCancelsIdleBackoffAndRepeatedEventsDoNotRearm() {
        val w = ready(); assertTrue(w.windowOpen)
        w.wake.onError(w.wake.generation, 2); w.drain(); assertNotNull(w.rearmIn)
        battery(w, false)
        assertNull(w.rearmIn); assertFalse(w.windowOpen); assertFalse(w.held().contains(HoldReason.LISTEN))
        val listens = w.listens()
        repeat(3) { battery(w, false); w.coordinator.onRearmDue(); w.drain() }
        assertEquals(listens, w.listens())
    }

    @Test fun unplugAndOptionOffPreserveAcceptedHandsFreeCapture() {
        val w = ready(); assertTrue(w.windowOpen)
        w.heard("루미"); w.handoffDue(); assertTrue("accepted actual wake capture", w.capturing)
        w.log.clear()
        battery(w, false); option(w, watch = false)
        assertTrue(w.capturing)
        assertTrue(w.held().contains(HoldReason.CAPTURE))
        assertTrue(w.log.none { it.startsWith("cancel_capture:") })
        assertNull(w.rearmIn)
        w.capturing = false; w.holds.release(HoldReason.CAPTURE)
        w.wake.onRequestCaptureEnded(sent = true); w.coordinator.onIdle(); w.drain()
        assertFalse(w.windowOpen); assertNull(w.rearmIn)
    }

    @Test fun unplugAndOptionOffPreserveManualCapture() {
        val w = ready(); assertTrue(w.windowOpen)
        w.pttRecording = true; w.busy = true; w.coordinator.onBusy(); w.drain()
        battery(w, false); option(w, watch = false)
        assertTrue(w.pttRecording); assertNull(w.rearmIn)
        assertTrue(w.log.none { it.startsWith("cancel_capture:") })
    }

    @Test fun ordinaryScreenOffRecognitionRemainsOnWithoutCharging() {
        val w = ready(charging = false, enabled = false)
        assertFalse(w.windowOpen)
        w.screenOffPreference(watch = true)
        assertTrue(w.windowOpen)
        battery(w, true); battery(w, false)
        assertTrue("existing screen-off setting is independent", w.windowOpen)
    }

    @Test fun microphonePermissionAndOsAdmissionStillBlockCharging() {
        val denied = ComposedWatch(WakeLocation.WATCH, watchStandby = true, watchScreenOff = false)
        option(denied, watch = true); battery(denied, true); denied.micPermission = false
        denied.show(); denied.start(); denied.hide(); denied.screenOff()
        assertFalse(denied.windowOpen); assertFalse(denied.coordinator.presence.armed)
        val refused = ComposedWatch(WakeLocation.WATCH, asyncService = true, watchStandby = true, watchScreenOff = false)
        option(refused, watch = true); battery(refused, true); refused.serviceAcceptsMic = false
        refused.show(); refused.start(); refused.serviceEnters(); refused.hide(); refused.screenOff()
        assertFalse(refused.windowOpen); assertFalse(refused.coordinator.presence.armed)
    }

    @Test fun busyPlaybackAndStopKeepTheirExistingExclusion() {
        val w = ready(); assertTrue(w.windowOpen)
        w.busy = true; w.coordinator.onBusy(); w.drain(); assertFalse(w.windowOpen)
        battery(w, true); assertFalse(w.windowOpen)
        w.busy = false; w.coordinator.onIdle(); w.drain(); assertTrue(w.windowOpen)
        w.coordinator.presence.onPlaybackBusy(); w.drain(); assertFalse(w.windowOpen)
        w.stop(); val listens = w.listens()
        battery(w, true); w.coordinator.onRearmDue(); w.drain()
        assertEquals(listens, w.listens()); assertFalse(w.windowOpen); assertTrue(w.held().isEmpty())
    }
}
