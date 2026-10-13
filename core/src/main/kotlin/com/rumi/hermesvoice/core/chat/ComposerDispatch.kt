package com.rumi.hermesvoice.core.chat

import com.rumi.hermesvoice.core.ChatSendResult
import com.rumi.hermesvoice.core.diag.DiagCause
import com.rumi.hermesvoice.core.diag.DiagClassify
import com.rumi.hermesvoice.core.diag.DiagCode
import com.rumi.hermesvoice.core.diag.DiagFail
import com.rumi.hermesvoice.core.diag.DiagLog
import com.rumi.hermesvoice.core.diag.DiagModality
import com.rumi.hermesvoice.core.diag.DiagOrigin
import com.rumi.hermesvoice.core.diag.DiagStage
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
class FailedSend(val send: DispatchedSend, val reason: String, val label: String? = null)

sealed class SendAdmission {
    /** Valid: [send] goes to the transport, and the composer becomes [remaining] in the same step. */
    class Admitted(val send: DispatchedSend, val remaining: ComposerDraft) : SendAdmission()

    /** Refused before admission: the draft is kept untouched. [reason] is shown when not null. */
    class Refused(val reason: String?) : SendAdmission()
}

/** The Phone composer's send rules, free of Android so they can be checked on the JVM. */
object ComposerDispatch {
    fun admit(sessionId: String?, draft: ComposerDraft, id: Long, diag: DiagLog? = null): SendAdmission {
        if (sessionId == null) return SendAdmission.Refused(null)
        if (draft.isEmpty) return SendAdmission.Refused(null)
        try {
            AttachmentPolicy.validate(draft.attachments)
        } catch (error: IllegalArgumentException) {
            diag?.record(DiagCode.REQUEST_FAILED, DiagOrigin.PHONE, fail = DiagFail.REFUSED, stage = DiagStage.PREPARE, cause = DiagCause.LOCAL,
                modality = DiagModality.ATTACHMENT, n = draft.attachments.size)
            return SendAdmission.Refused(error.message ?: "invalid attachment")
        }
        return SendAdmission.Admitted(DispatchedSend(id, sessionId, draft.text, draft.attachments), ComposerDraft())
    }

    /**
     * An attachment that could not be read (or was refused by size) while the user was adding it, before any upload. A missing
     * read permission is told apart from the app's own refusal and from a storage failure; nothing about the file is kept.
     */
    fun attachmentNotRead(diag: DiagLog?, error: Throwable) {
        val cause = when (error) {
            is SecurityException -> DiagCause.AUTH
            else -> DiagCause.LOCAL
        }
        diag?.record(DiagCode.REQUEST_FAILED, DiagOrigin.PHONE, fail = DiagClassify.failOf(cause), stage = DiagStage.PREPARE, cause = cause,
            modality = DiagModality.ATTACHMENT)
    }

    /** What the screen says about an attachment that could not be added: fixed words, never the exception's own text. */
    fun attachmentNotReadStatus(error: Throwable): String = when (error) {
        is IllegalArgumentException -> "That file is empty, unreadable or too large to attach"
        is SecurityException -> "Hermes Voice isn't allowed to read that file"
        else -> "Couldn't read that file"
    }

    /** Whether Hermes has the message: everything except a failure before it was sent. */
    fun delivered(result: ChatSendResult): Boolean = result !is ChatSendResult.Failed || result.reason.startsWith("sent,")

    fun failure(send: DispatchedSend, result: ChatSendResult): FailedSend? =
        if (delivered(result)) null else (result as ChatSendResult.Failed).let { FailedSend(send, it.reason, safeLabel(it)) }

    /** The fixed-word stage and category of a failed send ("Acceptance not observed: timed out while sending"), or null when none was observed. */
    fun safeLabel(result: ChatSendResult.Failed): String? =
        com.rumi.hermesvoice.core.diag.DiagLabels.failure(result.stage, result.cause, result.acceptanceObserved)

    /** What a status line shows: the safe label when the failure was typed, else the legacy text. */
    fun statusSafe(result: ChatSendResult): String =
        (result as? ChatSendResult.Failed)?.let { safeLabel(it) } ?: status(result)

    /** Failed sends kept for retry; bounded because each holds its attachment bytes. The oldest is dropped past the bound. */
    const val MAX_FAILED = 8

    fun withFailed(failed: List<FailedSend>, added: FailedSend): List<FailedSend> = (failed + added).takeLast(MAX_FAILED)

    fun status(result: ChatSendResult): String = when (result) {
        is ChatSendResult.Replied -> ""
        is ChatSendResult.Accepted -> "Sent to Hermes; the reply will appear after refresh"
        is ChatSendResult.Failed -> result.reason
    }
}
