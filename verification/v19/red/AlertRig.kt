package com.rumi.hermesvoice.core

import java.util.Collections
import kotlinx.coroutines.CoroutineScope

/**
 * RED-bundle counterpart of AlertRig for the immutable repair baseline, which has no arrival-alert API: the same harness
 * without alerts. [shown] stays empty because nothing on the baseline can show an alert, so the observed tests fail by assertion.
 */
class AlertRig(private val scope: CoroutineScope) {
    val shown: MutableList<String> = Collections.synchronizedList(mutableListOf())

    fun harness(consent: Boolean = true): CoreHarness =
        CoreHarness(laterScope = scope, laterWindowMs = 60_000L).also { it.laterConsent.enabled = consent }
}
