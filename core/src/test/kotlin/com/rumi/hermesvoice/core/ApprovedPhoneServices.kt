package com.rumi.hermesvoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Latest app-only Live authority permits exactly one additional service, never a headset interception service. */
internal fun assertApprovedPhoneServiceCohort(manifest: String) {
    val approved = setOf(".PhoneRelayService", ".PhoneWakeService", ".PhoneManualService", ".PhoneWatchListenerService", ".live.PhoneLiveVoiceService")
    val actual = Regex("""<service\s+android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()
    assertEquals("only the five approved legacy/manual/Live services", approved, actual)
    assertEquals("no duplicate or additional service", approved.size, Regex("<service\\s").findAll(manifest).count())
    val live = manifest.substringAfter("android:name=\".live.PhoneLiveVoiceService\"").substringBefore("/>")
    assertTrue("Live service is not exported", live.contains("android:exported=\"false\""))
    assertTrue("Live service declares paired connectedDevice plus independent Phone microphone/media types", live.contains("android:foregroundServiceType=\"connectedDevice|microphone|mediaPlayback\""))
}
