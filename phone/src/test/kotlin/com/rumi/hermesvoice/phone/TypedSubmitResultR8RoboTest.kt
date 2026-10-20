package com.rumi.hermesvoice.phone

import com.rumi.hermesvoice.core.FakeHermesDashboard
import com.rumi.hermesvoice.core.R8WireFaults
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Application-owned TextSends + real PhoneViewModel/Retry over real WebSocket; synthetic post-effect wire faults. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class TypedSubmitResultR8RoboTest : PhoneRoboBase() {
    private val release = CountDownLatch(1)
    @Before fun setUp() = baseSetUp()
    @After fun tearDown() { release.countDown(); baseTearDown() }
    private fun scenario(kind: String) {
        val work = conversation("work")
        val model = open(work)
        val acceptedBefore = app.diagnostics.log.snapshot().count { it.code == com.rumi.hermesvoice.core.diag.DiagCode.REQUEST_ACCEPTED }
        val effect = CountDownLatch(1)
        fake.turnBehavior = { "async_hold" }
        fake.submitReplyFault = { frame ->
            // The fixture has recorded the user-row effect before this independently held answer.
            R8WireFaults.mutate(frame, kind); effect.countDown()
            check(release.await(15, TimeUnit.SECONDS)) { "R08 fixture release was not signalled" }
        }
        model.setDraft("R"); model.send()
        val id = app.textSends.ids.get()
        assertTrue("real frame reached synthetic effect", await { effect.count == 0L })
        assertTrue(model.state.value.pendingSends.any { it.send.id == id && !it.delivered })
        // Finish the original request before the actual manual same-send Retry call.
        fake.pushLaterTurn(work, FakeHermesDashboard.complete("original-R-only"))
        release.countDown()
        assertTrue("real caller ends", await(20_000) { model.state.value.sendsInFlight == 0 })
        assertEquals("actual ChatService accepted callback stage must not run", acceptedBefore,
            app.diagnostics.log.snapshot().count { it.code == com.rumi.hermesvoice.core.diag.DiagCode.REQUEST_ACCEPTED })
        fake.submitReplyFault = null; fake.turnBehavior = { "start_first" }
        fake.scripts[work] = { listOf(FakeHermesDashboard.complete("new-request-answer")) }
        model.retryFailedSend(id)
        assertTrue(await(20_000) { model.state.value.sendsInFlight == 0 })
        assertEquals("R08 actual same-request Retry must not resend after $kind", listOf("R"), prompts())
        assertTrue("R08 uncertain request has no failed-send Retry row", model.state.value.failedSends.none { it.send.id == id })
        assertTrue("R08 no synthetic Sent/delivered projection", model.state.value.pendingSends.none { it.send.id == id })
        assertEquals("Acceptance uncertain; check the conversation. Do not retry this request", model.state.value.status)
        model.setDraft("R"); model.send() // separately authored NEW message, not a same-send Retry
        assertTrue(await(20_000) { prompts().size == 2 && model.state.value.sendsInFlight == 0 })
        assertEquals(listOf("R", "R"), prompts())
        assertEquals("a NEW supported admission still reaches the accepted stage", acceptedBefore + 1,
            app.diagnostics.log.snapshot().count { it.code == com.rumi.hermesvoice.core.diag.DiagCode.REQUEST_ACCEPTED })
    }
    @Test fun E01_rpcGenericCannotMintRetryOrSent() = scenario("rpcGeneric")
    @Test fun E02_rpcMissingReasonCannotMintRetryOrSent() = scenario("rpcMissingReason")
    @Test fun E03_rpcWrongReasonCannotMintRetryOrSent() = scenario("rpcWrongReason")
    @Test fun E04_rpcPrefixCannotMintRetryOrSent() = scenario("rpcPrefix")
    @Test fun E05_rpcWrongCodeCannotMintRetryOrSent() = scenario("rpcWrongCode")
    @Test fun E06_rpcStringCodeCannotMintRetryOrSent() = scenario("rpcStringCode")
    @Test fun E07_unknownCannotMintRetryOrSent() = scenario("unknown")
    @Test fun E08_emptyCannotMintRetryOrSent() = scenario("empty")
    @Test fun E09_missingStatusCannotMintRetryOrSent() = scenario("missingStatus")
    @Test fun E10_nullStatusCannotMintRetryOrSent() = scenario("nullStatus")
    @Test fun E11_numberStatusCannotMintRetryOrSent() = scenario("numberStatus")
    @Test fun E12_objectStatusCannotMintRetryOrSent() = scenario("objectStatus")
    @Test fun E13_arrayStatusCannotMintRetryOrSent() = scenario("arrayStatus")
    @Test fun E14_missingResultCannotMintRetryOrSent() = scenario("missingResult")
    @Test fun E15_nullResultCannotMintRetryOrSent() = scenario("nullResult")
    @Test fun E16_stringResultCannotMintRetryOrSent() = scenario("stringResult")
    @Test fun E17_arrayResultCannotMintRetryOrSent() = scenario("arrayResult")
    @Test fun E18_numberResultCannotMintRetryOrSent() = scenario("numberResult")
    @Test fun E19_malformedErrorCannotMintRetryOrSent() = scenario("malformedError")
}
