package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.notify.FinalReply
import com.rumi.hermesvoice.core.notify.FinalReplySource
import com.rumi.hermesvoice.core.notify.ReplyAlert
import com.rumi.hermesvoice.core.notify.ReplyAlertLedger
import com.rumi.hermesvoice.core.notify.ReplyAlertPort
import com.rumi.hermesvoice.core.notify.ReplyAlerts
import com.rumi.hermesvoice.core.notify.ReplyPreview
import com.rumi.hermesvoice.core.voice.PlaybackSink
import java.util.Collections
import kotlinx.coroutines.CoroutineScope

/** What the Phone surface was asked to show: the alert's identity and conversation, and the preview text it was handed (null: generic). */
data class ShownAlert(val key: String, val summary: String?, val expanded: String?)

/** The Phone's arrival-alert surface as a recording port that also records the preview, wired into the production core like PhoneApp does. */
class PreviewRig(private val scope: CoroutineScope) {
    val shown: MutableList<ShownAlert> = Collections.synchronizedList(mutableListOf())
    private val port = object : ReplyAlertPort {
        override fun show(alert: ReplyAlert): Boolean = show(alert, null)
        override fun show(alert: ReplyAlert, preview: ReplyPreview?): Boolean {
            shown += ShownAlert("${alert.identity}@${alert.storedSessionId}", preview?.summary, preview?.expanded)
            return true
        }
    }
    val alerts = ReplyAlerts(ReplyAlertLedger(InMemoryKeyValueStore()), port, scope)

    fun harness(consent: Boolean = true): CoreHarness =
        CoreHarness(laterScope = scope, laterWindowMs = 60_000L, replyAlerts = alerts).also { it.laterConsent.enabled = consent }

    companion object {
        fun reply(identity: String, session: String, text: String?, heard: Boolean = false, cancelled: Boolean = false,
                  target: VoiceOrigin = VoiceOrigin.PHONE, sink: PlaybackSink? = null) =
            FinalReply(identity, session, target, heard, cancelled, FinalReplySource.OWN, sink, text)
    }
}
