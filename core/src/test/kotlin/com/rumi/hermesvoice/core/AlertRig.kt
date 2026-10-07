package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.notify.ReplyAlertLedger
import com.rumi.hermesvoice.core.notify.ReplyAlerts
import java.util.Collections
import kotlinx.coroutines.CoroutineScope

/** The Phone's arrival-alert surface as a recording port ("identity@conversation"), wired into the production core like PhoneApp does. */
class AlertRig(private val scope: CoroutineScope) {
    val shown: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val alerts = ReplyAlerts(ReplyAlertLedger(InMemoryKeyValueStore()), { shown += "${it.identity}@${it.storedSessionId}"; true }, scope)

    fun harness(consent: Boolean = true): CoreHarness =
        CoreHarness(laterScope = scope, laterWindowMs = 60_000L, replyAlerts = alerts).also { it.laterConsent.enabled = consent }
}
