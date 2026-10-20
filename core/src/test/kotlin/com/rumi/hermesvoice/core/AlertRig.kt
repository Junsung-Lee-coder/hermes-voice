package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.notify.ReplyAlertLedger
import com.rumi.hermesvoice.core.notify.ReplyAlerts
import java.util.Collections
import kotlinx.coroutines.CoroutineScope

/** The Phone's arrival-alert surface as a recording port ("identity@conversation"), wired into the production core like PhoneApp does. */
class AlertRig(private val scope: CoroutineScope) {
    val shown: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile var accept = true
    @Volatile var throwing = false
    private val alerts = ReplyAlerts(ReplyAlertLedger(InMemoryKeyValueStore()), { if (throwing) throw IllegalStateException("synthetic refusal"); if (!accept) false else { shown += "${it.identity}@${it.storedSessionId}"; true } }, scope)

    fun harness(consent: Boolean = true): CoreHarness =
        CoreHarness(laterScope = scope, laterWindowMs = 60_000L, replyAlerts = alerts).also { it.laterConsent.enabled = consent }

    /** The app process restarted: a new core over [previous]'s dashboard and stored preferences, with the same alert surface and ledger. */
    fun harness(previous: CoreHarness, consent: Boolean = true): CoreHarness =
        CoreHarness(previous.fake, previous.store, previous.localStore, laterScope = scope, laterWindowMs = 60_000L, replyAlerts = alerts)
            .also { it.laterConsent.enabled = consent }
}
