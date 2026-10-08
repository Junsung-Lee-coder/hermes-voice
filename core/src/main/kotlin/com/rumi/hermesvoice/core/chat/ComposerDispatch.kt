package com.rumi.hermesvoice.core.chat

import com.rumi.hermesvoice.core.ChatSendResult
import com.rumi.hermesvoice.core.net.AttachmentPolicy
import com.rumi.hermesvoice.core.net.OutgoingAttachment

/** What is typed and attached in the composer of one conversation. */
data class ComposerDraft(val text: String = "", val attachments: List<OutgoingAttachment> = emptyList()) {
    val isEmpty: Boolean get() = text.isBlank() && attachments.isEmpty()
}

/** One dispatched request: exactly what the composer held for [sessionId] when it was sent. */
class DispatchedSend(val id: Long, val sessionId: String, val text: String, val attachments: List<OutgoingAttachment>) {
    override fun toString(): String = "DispatchedSend(id=$id, attachments=${attachments.size})"
}

/** A dispatched request that did not reach Hermes; its content is kept here, never put back over a newer draft. */
class FailedSend(val send: DispatchedSend, val reason: String)

sealed class SendAdmission {
    /** Valid: [send] goes to the transport, and the composer becomes [remaining] in the same step. */
    class Admitted(val send: DispatchedSend, val remaining: ComposerDraft) : SendAdmission()

    /** Refused before admission: the draft is kept untouched. [reason] is shown when not null. */
    class Refused(val reason: String?) : SendAdmission()
}

/** The Phone composer's send rules, free of Android so they can be checked on the JVM. */
object ComposerDispatch {
    fun admit(sessionId: String?, draft: ComposerDraft, id: Long): SendAdmission {
        if (sessionId == null) return SendAdmission.Refused(null)
        if (draft.isEmpty) return SendAdmission.Refused(null)
        try {
            AttachmentPolicy.validate(draft.attachments)
        } catch (error: IllegalArgumentException) {
            return SendAdmission.Refused(error.message ?: "invalid attachment")
        }
        return SendAdmission.Admitted(DispatchedSend(id, sessionId, draft.text, draft.attachments), ComposerDraft())
    }

    /** Whether Hermes has the message: everything except a failure before it was sent. */
    fun delivered(result: ChatSendResult): Boolean = result !is ChatSendResult.Failed || result.reason.startsWith("sent,")

    fun failure(send: DispatchedSend, result: ChatSendResult): FailedSend? =
        if (delivered(result)) null else FailedSend(send, (result as ChatSendResult.Failed).reason)

    /** Failed sends kept for retry; bounded because each holds its attachment bytes. The oldest is dropped past the bound. */
    const val MAX_FAILED = 8

    fun withFailed(failed: List<FailedSend>, added: FailedSend): List<FailedSend> = (failed + added).takeLast(MAX_FAILED)

    fun status(result: ChatSendResult): String = when (result) {
        is ChatSendResult.Replied -> ""
        is ChatSendResult.Accepted -> "Sent to Hermes; the reply will appear after refresh"
        is ChatSendResult.Failed -> result.reason
    }
}
