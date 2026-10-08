package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.chat.ComposerDispatch
import com.rumi.hermesvoice.core.chat.ComposerDraft
import com.rumi.hermesvoice.core.chat.SendAdmission
import com.rumi.hermesvoice.core.net.AttachmentPolicy
import com.rumi.hermesvoice.core.net.OutgoingAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** B: what the composer captures, clears and keeps when a send is attempted. */
class ComposerDispatchTest {
    private val photo = OutgoingAttachment("p.png", "image/png", byteArrayOf(1, 2, 3))

    @Test
    fun `a valid send captures the exact text, bytes and conversation and leaves an empty composer in the same step`() {
        val draft = ComposerDraft("  keep my spacing \n", listOf(photo))
        val admission = ComposerDispatch.admit("conv-1", draft, 7) as SendAdmission.Admitted
        assertEquals("  keep my spacing \n", admission.send.text)
        assertEquals("conv-1", admission.send.sessionId)
        assertSame(photo, admission.send.attachments.single())
        assertEquals(7L, admission.send.id)
        assertEquals("", admission.remaining.text)
        assertTrue(admission.remaining.attachments.isEmpty())
    }

    @Test
    fun `a second tap on what is left of the composer is refused, so one tap cannot send twice`() {
        val first = ComposerDispatch.admit("conv-1", ComposerDraft("hello"), 1) as SendAdmission.Admitted
        val second = ComposerDispatch.admit("conv-1", first.remaining, 2)
        assertTrue(second is SendAdmission.Refused)
        assertNull((second as SendAdmission.Refused).reason)
    }

    @Test
    fun `empty, blank and conversation-less requests are refused before admission`() {
        assertTrue(ComposerDispatch.admit("conv-1", ComposerDraft(""), 1) is SendAdmission.Refused)
        assertTrue(ComposerDispatch.admit("conv-1", ComposerDraft(" \n\t"), 1) is SendAdmission.Refused)
        assertTrue(ComposerDispatch.admit(null, ComposerDraft("hello"), 1) is SendAdmission.Refused)
    }

    @Test
    fun `an invalid attachment set is refused with a visible reason and nothing is consumed`() {
        val tooMany = List(AttachmentPolicy.MAX_ATTACHMENTS + 1) { photo }
        val refused = ComposerDispatch.admit("conv-1", ComposerDraft("x", tooMany), 1) as SendAdmission.Refused
        assertTrue(refused.reason!!.contains("at most"))
        val empty = ComposerDispatch.admit("conv-1", ComposerDraft("x", listOf(OutgoingAttachment("e", "text/plain", ByteArray(0)))), 1)
        assertTrue(empty is SendAdmission.Refused)
    }

    @Test
    fun `an attachment-only message is valid`() {
        assertTrue(ComposerDispatch.admit("conv-1", ComposerDraft("", listOf(photo)), 1) is SendAdmission.Admitted)
    }

    @Test
    fun `only a failure before the message was sent is kept for retry`() {
        val send = (ComposerDispatch.admit("conv-1", ComposerDraft("hello"), 1) as SendAdmission.Admitted).send
        assertNull(ComposerDispatch.failure(send, ChatSendResult.Replied("hi", "complete")))
        assertNull(ComposerDispatch.failure(send, ChatSendResult.Accepted("queued")))
        assertNull(ComposerDispatch.failure(send, ChatSendResult.Failed("sent, but the connection dropped: IOException")))
        val failed = ComposerDispatch.failure(send, ChatSendResult.Failed("network: SocketTimeoutException"))!!
        assertSame(send, failed.send)
        assertEquals("network: SocketTimeoutException", failed.reason)
    }

    @Test
    fun `failed sends are bounded and the newest are kept`() {
        var failed = emptyList<com.rumi.hermesvoice.core.chat.FailedSend>()
        for (id in 1L..(ComposerDispatch.MAX_FAILED + 3L)) {
            val send = (ComposerDispatch.admit("c", ComposerDraft("m$id"), id) as SendAdmission.Admitted).send
            failed = ComposerDispatch.withFailed(failed, ComposerDispatch.failure(send, ChatSendResult.Failed("down"))!!)
        }
        assertEquals(ComposerDispatch.MAX_FAILED, failed.size)
        assertEquals(ComposerDispatch.MAX_FAILED + 3L, failed.last().send.id)
        assertEquals(4L, failed.first().send.id)
    }

    @Test
    fun `the status never says Hermes queued or steered the message`() {
        val text = listOf(ChatSendResult.Accepted("queued"), ChatSendResult.Accepted("steered"), ChatSendResult.Replied("r", null))
            .joinToString { ComposerDispatch.status(it) }.lowercase()
        assertTrue(!text.contains("queue") && !text.contains("steer"))
    }
}
