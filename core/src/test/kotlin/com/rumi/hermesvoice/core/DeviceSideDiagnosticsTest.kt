package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.chat.ComposerDispatch
import com.rumi.hermesvoice.core.chat.ComposerDraft
import com.rumi.hermesvoice.core.chat.SendAdmission
import com.rumi.hermesvoice.core.net.OutgoingAttachment
import com.rumi.hermesvoice.core.diag.DiagApp
import com.rumi.hermesvoice.core.diag.DiagBuild
import com.rumi.hermesvoice.core.diag.DiagCause
import com.rumi.hermesvoice.core.diag.DiagCode
import com.rumi.hermesvoice.core.diag.DiagLog
import com.rumi.hermesvoice.core.diag.DiagModality
import com.rumi.hermesvoice.core.diag.DiagOrigin
import com.rumi.hermesvoice.core.diag.DiagPersistStatus
import com.rumi.hermesvoice.core.diag.DiagPseudonyms
import com.rumi.hermesvoice.core.diag.DiagSpeechError
import com.rumi.hermesvoice.core.diag.DiagStage
import com.rumi.hermesvoice.core.diag.DiagWatchResponder
import com.rumi.hermesvoice.core.diag.DiagWatchTransfer
import com.rumi.hermesvoice.core.diag.DiagWire
import com.rumi.hermesvoice.core.diag.DiagWireV2
import com.rumi.hermesvoice.core.diag.PhoneUnreachableException
import com.rumi.hermesvoice.core.diag.WatchDiagStatus
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Phone-side and Watch-side helpers that need no Android: recognizer errors, the Watch hand-over, the Watch's answer and attachment refusals. */
class DeviceSideDiagnosticsTest {
    private val secret = "SECRET-TOKEN-9f3a content://media/private/photo.jpg 10.0.0.7 Bearer abc"
    private var tick = 0L
    private fun log() = DiagLog(clock = { tick++ })

    @Test fun recognizerErrorsPointingAtTheNetworkOrServiceAreRecordedAndTheRestAreNot() {
        val log = log()
        assertEquals(DiagCause.TIMEOUT, DiagSpeechError.causeOf(1))
        assertEquals(DiagCause.DISCONNECT, DiagSpeechError.causeOf(2))
        assertEquals(DiagCause.REJECTED, DiagSpeechError.causeOf(4))
        assertEquals(DiagCause.AUTH, DiagSpeechError.causeOf(9))
        assertEquals(DiagCause.REJECTED, DiagSpeechError.causeOf(10))
        assertEquals(DiagCause.DISCONNECT, DiagSpeechError.causeOf(11))
        for (code in listOf(3, 5, 6, 7, 8, 12, 0, -1, 99)) assertNull("code $code is not a network condition", DiagSpeechError.causeOf(code))
        assertTrue(DiagSpeechError.record(log, DiagOrigin.WATCH, 2))
        assertFalse(DiagSpeechError.record(log, DiagOrigin.WATCH, 7))
        assertTrue("a mappable code with no log is recognised and nothing throws", DiagSpeechError.record(null, DiagOrigin.PHONE, 2))
        val event = log.snapshot().single()
        assertEquals(DiagCode.STT_RECOGNIZER_FAILED, event.code)
        assertEquals(DiagOrigin.WATCH, event.origin)
        assertEquals(DiagStage.STT, event.stage)
        assertEquals(DiagCause.DISCONNECT, event.cause)
        assertEquals(DiagModality.VOICE, event.modality)
        assertEquals(2, event.status)
    }

    @Test fun repeatedRecognizerErrorsAreTransientSoTheyCannotFloodTheLogOrTheFlushes() {
        val log = log()
        assertTrue(DiagCode.STT_RECOGNIZER_FAILED.transient)
        assertFalse(DiagCode.STT_RECOGNIZER_FAILED.legacy)
        repeat(1_000) { DiagSpeechError.record(log, DiagOrigin.PHONE, 2) }
        assertTrue(log.snapshot().size <= log.capacity)
    }

    @Test fun aWatchHandOverIsRecordedPerAttemptAndAStopIsNotAFailure() {
        val log = log()
        assertNull(DiagWatchTransfer.record(log, "turn-1", null, userStopped = false))
        val stopped = DiagWatchTransfer.record(log, "turn-2", CancellationException("user"), userStopped = false)!!
        assertTrue(stopped.userStopped)
        assertEquals("Stopped", stopped.status)
        val flagged = DiagWatchTransfer.record(log, "turn-3", IOException(secret), userStopped = true)!!
        assertTrue(flagged.userStopped)
        val codes = log.snapshot().map { it.code }
        assertEquals(listOf(DiagCode.WATCH_SENT, DiagCode.REQUEST_STOPPED, DiagCode.REQUEST_STOPPED), codes)
        assertTrue(log.snapshot().all { it.stage == DiagStage.TRANSFER && it.modality == DiagModality.VOICE && it.origin == DiagOrigin.WATCH })
    }

    @Test fun aTimeoutThatIsACancellationIsATimeoutNotAStop() {
        val log = log()
        val timeout = try {
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(1) { kotlinx.coroutines.delay(1_000) } }
            null
        } catch (e: TimeoutCancellationException) { e }!!
        val result = DiagWatchTransfer.record(log, "turn-t", timeout, userStopped = false)!!
        assertFalse(result.userStopped)
        assertEquals(DiagCause.TIMEOUT, result.cause)
        assertEquals(DiagCode.REQUEST_FAILED, log.snapshot().single().code)
    }

    @Test fun handOverFailuresCarryTheirOwnCategoryAndNeverTheExceptionText() {
        val log = log()
        val cases = listOf(
            SocketTimeoutException(secret) to DiagCause.TIMEOUT,
            UnknownHostException(secret) to DiagCause.DNS,
            IOException(secret) to DiagCause.DISCONNECT,
            SecurityException(secret) to DiagCause.AUTH,
            PhoneUnreachableException() to DiagCause.DISCONNECT,
        )
        for ((error, cause) in cases) {
            val result = DiagWatchTransfer.record(log, "turn-x", error, userStopped = false)!!
            assertEquals(error.javaClass.simpleName, cause, result.cause)
            assertFalse(result.status.contains("SECRET"))
            assertFalse(result.status.contains("content:"))
            assertFalse(result.status.contains("10.0.0.7"))
        }
        assertEquals("Couldn't reach the phone", DiagWatchTransfer.record(log, "t", PhoneUnreachableException(), false)!!.status)
        assertTrue(DiagWatchTransfer.record(log, "t", IOException(secret), false)!!.status.startsWith("Couldn't send to the phone: "))
        val shown = log.snapshot().joinToString { it.toString() }
        assertFalse(shown.contains("SECRET"))
        assertFalse(shown.contains("10.0.0.7"))
    }

    @Test fun twoAttemptsOfTheSameTurnAreTwoEventsWithNoTextDedupe() {
        val log = log()
        DiagWatchTransfer.record(log, "same", IOException("a"), false)
        DiagWatchTransfer.record(log, "same", IOException("a"), false)
        assertEquals(2, log.snapshot().size)
    }

    private val app = DiagApp("0.1.19-test", 20, DiagBuild.UNAVAILABLE)

    private fun responder(log: DiagLog) = DiagWatchResponder(log, { DiagPersistStatus.NOT_ENABLED }, { app })

    @Test fun anOlderPhonesRequestIsAnsweredInTheOldSchemaAndANewerOneInTheNewSchema() {
        val log = log()
        DiagWatchTransfer.record(log, "turn-1", IOException(secret), false)
        val salt = DiagPseudonyms.newSalt()
        val responder = responder(log)

        val old = responder.answer(DiagWire.encodeRequest(salt))!!
        val oldPart = DiagWire.decodeResponse(old, salt)
        assertEquals(WatchDiagStatus.REACHABLE, oldPart.status)
        assertEquals(1, oldPart.events.size)
        assertNull("an older Phone is not sent fields it does not know", oldPart.v2)

        val new = responder.answer(DiagWire.encodeRequestV2(salt))!!
        val newPart = DiagWireV2.decodeResponse(new, salt)
        assertEquals(WatchDiagStatus.REACHABLE, newPart.status)
        assertEquals(1, newPart.events.size)
        assertNotNull(newPart.v2)
        assertEquals("0.1.19-test", newPart.v2!!.app.versionName)
        assertEquals(DiagBuild.UNAVAILABLE, newPart.v2!!.app.build)

        for (bytes in listOf(old, new)) {
            val text = String(bytes, Charsets.UTF_8)
            assertFalse(text.contains("SECRET"))
            assertFalse(text.contains("turn-1"))
        }
    }

    @Test fun anUnreadableOrOversizedRequestGetsNoAnswer() {
        val responder = responder(log())
        assertNull(responder.answer("not json".toByteArray()))
        assertNull(responder.answer("""{"schema":9,"salt":"${DiagPseudonyms.newSalt()}"}""".toByteArray()))
        assertNull(responder.answer("""{"schema":2}""".toByteArray()))
        assertNull(responder.answer(ByteArray(0)))
    }

    @Test fun anAttachmentTheComposerRefusesIsRecordedAsALocalPreparationFailureWithoutItsName() {
        val log = log()
        val empty = OutgoingAttachment("SECRET-name.jpg", "image/jpeg", ByteArray(0))
        val draft = ComposerDraft(text = "hi", attachments = listOf(empty))
        assertTrue(ComposerDispatch.admit("s1", ComposerDraft(text = "hi"), 1L, log) is SendAdmission.Admitted)
        assertTrue("nothing recorded when admitted", log.snapshot().isEmpty())
        assertTrue(ComposerDispatch.admit(null, draft, 2L, log) is SendAdmission.Refused)
        assertTrue(ComposerDispatch.admit("s1", ComposerDraft(), 3L, log) is SendAdmission.Refused)
        assertTrue("a missing conversation or an empty draft is a no-op, not a failure", log.snapshot().isEmpty())
        assertTrue(ComposerDispatch.admit("s1", draft, 4L, log) is SendAdmission.Refused)
        val event = log.snapshot().single()
        assertEquals(DiagCode.REQUEST_FAILED, event.code)
        assertEquals(DiagStage.PREPARE, event.stage)
        assertEquals(DiagCause.LOCAL, event.cause)
        assertEquals(DiagModality.ATTACHMENT, event.modality)
        assertEquals(DiagOrigin.PHONE, event.origin)
        assertFalse(event.toString().contains("SECRET"))
    }

    @Test fun anAttachmentThatCouldNotBeReadIsAPreparationEventWithAFixedStatus() {
        val log = log()
        ComposerDispatch.attachmentNotRead(log, IllegalArgumentException(secret))
        ComposerDispatch.attachmentNotRead(log, SecurityException(secret))
        ComposerDispatch.attachmentNotRead(log, IOException(secret))
        val events = log.snapshot()
        assertEquals(3, events.size)
        assertTrue(events.all { it.code == DiagCode.REQUEST_FAILED && it.stage == DiagStage.PREPARE && it.modality == DiagModality.ATTACHMENT && it.origin == DiagOrigin.PHONE })
        assertEquals(listOf(DiagCause.LOCAL, DiagCause.AUTH, DiagCause.LOCAL), events.map { it.cause })
        assertEquals("That file is empty, unreadable or too large to attach", ComposerDispatch.attachmentNotReadStatus(IllegalArgumentException(secret)))
        assertEquals("Hermes Voice isn't allowed to read that file", ComposerDispatch.attachmentNotReadStatus(SecurityException(secret)))
        assertEquals("Couldn't read that file", ComposerDispatch.attachmentNotReadStatus(IOException(secret)))
        for (error in listOf(IllegalArgumentException(secret), SecurityException(secret), IOException(secret))) {
            assertFalse(ComposerDispatch.attachmentNotReadStatus(error).contains("SECRET"))
        }
        assertFalse(events.joinToString { it.toString() }.contains("SECRET"))
    }
}
