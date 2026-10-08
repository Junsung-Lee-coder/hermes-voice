package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.net.AttachmentPolicy
import com.rumi.hermesvoice.core.net.OutgoingAttachment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAndAttachmentTest {
    private val h = CoreHarness()

    @After fun tearDown() = h.close()

    private fun conversation() = runBlocking { h.core.sessions.createConversation("Work", "work", "") }.storedSessionId

    private fun methods() = h.fake.rpcLog.map { it.getString("method") }

    @Test
    fun `text chat returns the destination reply and submits without choosing a busy-input policy`() = runBlocking {
        val id = conversation()
        val result = h.core.chat.send(id, "  what's on today?  ") as ChatSendResult.Replied
        assertEquals("reply from $id", result.text)
        assertEquals(listOf(id to "what's on today?"), h.fake.prompts.toList())
        val submit = h.fake.rpcLog.single { it.getString("method") == "prompt.submit" }.getJSONObject("params")
        assertTrue("the app leaves queue/steer/interrupt to Hermes", !submit.has("queued"))
    }

    @Test
    fun `image goes through image attach_bytes and is consumed by that same submit`() = runBlocking {
        val id = conversation()
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
        h.core.chat.send(id, "what is this?", listOf(OutgoingAttachment("../../etc/photo.png", "image/png", png)))
        assertEquals(listOf("session.create", "session.resume", "image.attach_bytes", "prompt.submit"), methods())
        val attach = h.fake.rpcLog[2].getJSONObject("params")
        assertEquals("photo.png", attach.getString("filename"))
        assertEquals("rt-$id", attach.getString("session_id"))
        assertEquals(1, h.fake.consumedImages.single().second.size)
        assertEquals("what is this?", h.fake.prompts.single().second)
    }

    @Test
    fun `non-image file is staged via file attach data_url and its ref prefixes the prompt`() = runBlocking {
        val id = conversation()
        val pdf = "%PDF-1.7 report".toByteArray()
        h.core.chat.send(id, "summarize", listOf(OutgoingAttachment("Q3 report.pdf", "application/pdf", pdf)))
        val params = h.fake.rpcLog.single { it.getString("method") == "file.attach" }.getJSONObject("params")
        assertTrue(params.getString("data_url").startsWith("data:application/pdf;base64,"))
        assertTrue("no gateway path may be sent", !params.has("path"))
        assertArrayEquals(pdf, h.fake.stagedFiles.single().second)
        assertEquals("@file:attachments/Q3 report.pdf\nsummarize", h.fake.prompts.single().second)
    }

    @Test
    fun `attachment failure detaches queued images and submits nothing`() = runBlocking {
        val id = conversation()
        h.fake.failFileAttach = true
        val result = h.core.chat.send(id, "both", listOf(
            OutgoingAttachment("a.jpg", "image/jpeg", byteArrayOf(1, 2, 3)),
            OutgoingAttachment("notes.txt", "text/plain", "x".toByteArray()),
        )) as ChatSendResult.Failed
        assertTrue(result.reason, result.reason.contains("disk full"))
        assertTrue(h.fake.prompts.isEmpty())
        assertTrue(methods().contains("image.detach"))
        assertTrue(h.fake.queuedImages.values.all { it.isEmpty() })
    }

    @Test
    fun `oversized or too many attachments are rejected before any RPC`() = runBlocking {
        val id = conversation()
        val before = h.fake.rpcLog.size
        val big = OutgoingAttachment("big.bin", "application/octet-stream", ByteArray(AttachmentPolicy.MAX_BYTES + 1))
        assertTrue(h.core.chat.send(id, "x", listOf(big)) is ChatSendResult.Failed)
        val many = (0..AttachmentPolicy.MAX_ATTACHMENTS).map { OutgoingAttachment("f$it.txt", "text/plain", byteArrayOf(1)) }
        assertTrue(h.core.chat.send(id, "x", many) is ChatSendResult.Failed)
        assertTrue(h.core.chat.send(id, "   ") is ChatSendResult.Failed)
        assertEquals(before, h.fake.rpcLog.size)
    }

    @Test
    fun `safe names strip paths and control characters`() {
        assertEquals("evil.sh", AttachmentPolicy.safeName("C:\\Users\\x\\..\\evil.sh"))
        assertEquals("a_b.txt", AttachmentPolicy.safeName("a\u0000\u0007b.txt"))
        assertEquals("attachment", AttachmentPolicy.safeName("..."))
    }
}
