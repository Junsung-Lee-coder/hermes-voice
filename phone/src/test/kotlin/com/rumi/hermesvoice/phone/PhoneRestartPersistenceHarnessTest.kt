package com.rumi.hermesvoice.phone

import com.rumi.hermesvoice.core.diag.DiagCode
import com.rumi.hermesvoice.core.diag.DiagExportResult
import com.rumi.hermesvoice.core.diag.DiagFail
import com.rumi.hermesvoice.core.diag.DiagOrigin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Written against APIs that exist both before and after the diagnostics change, so the same bytes compile and run on either tree:
 * what the Phone recorded is still in a freshly created [PhoneDiagnostics] over the same app storage, and in its export.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class PhoneRestartPersistenceHarnessTest : PhoneRoboBase() {
    @Before fun setUp() = baseSetUp()
    @After fun tearDown() = baseTearDown()

    @Test fun aRecordedFailureIsStillExportedByAFreshlyCreatedDiagnostics() {
        val before = app.diagnostics
        before.log.record(DiagCode.REQUEST_FAILED, DiagOrigin.PHONE, ref = "restart-probe-turn", fail = DiagFail.TIMEOUT)
        assertEquals(1, before.log.snapshot().count { it.code == DiagCode.REQUEST_FAILED })

        Thread.sleep(2_000)
        val after = PhoneDiagnostics(app)
        val end = System.nanoTime() + 6_000_000_000L
        while (System.nanoTime() < end && after.log.snapshot().none { it.code == DiagCode.REQUEST_FAILED }) Thread.sleep(100)
        assertTrue("the failure recorded before the restart is present after it", after.log.snapshot().any { it.code == DiagCode.REQUEST_FAILED })

        val ready = runBlocking { after.export() } as DiagExportResult.Ready
        val text = ready.file.readText()
        assertTrue("the export names the failed request", text.contains("request_failed"))
        assertFalse(text.contains("restart-probe-turn"))
    }
}
