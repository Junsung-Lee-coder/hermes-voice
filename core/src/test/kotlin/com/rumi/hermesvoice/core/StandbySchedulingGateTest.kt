package com.rumi.hermesvoice.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source gates for the Android scheduling adapters (the behavior is tested on the pure cores and, for the
 * Watch's screen-off re-arm, by the Robolectric ShadowAlarmManager test). A text match proves a line exists.
 */
class StandbySchedulingGateTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun source(path: String): String {
        val file = File(root, path)
        assertTrue("missing source file $path", file.isFile)
        return file.readText()
    }

    private val watch = "watch/src/main/kotlin/com/rumi/hermesvoice/watch"
    private val phone = "phone/src/main/kotlin/com/rumi/hermesvoice/phone"

    @Test
    fun `each app schedules its standby re-arm only through one non-exact allow-while-idle alarm adapter`() {
        for (dir in listOf(watch, phone)) {
            val scheduler = source("$dir/StandbyScheduler.kt")
            assertTrue(scheduler.contains("setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP"))
            assertTrue("immutable, explicit-component alarm", scheduler.contains("PendingIntent.FLAG_IMMUTABLE") && scheduler.contains("StandbyAlarmReceiver::class.java"))
            for (forbidden in listOf("setExact", "setAlarmClock", "canScheduleExactAlarms", "SCHEDULE_EXACT_ALARM", "setRepeating", "setInexactRepeating", "RTC_WAKEUP")) {
                assertFalse("$dir: $forbidden", scheduler.contains(forbidden))
            }
            assertTrue("a cancel exists so OFF leaves nothing scheduled", scheduler.contains("alarms.cancel("))
        }
        for (manifest in listOf("watch", "phone").map { source("$it/src/main/AndroidManifest.xml") }) {
            assertFalse(manifest.contains("SCHEDULE_EXACT_ALARM") || manifest.contains("USE_EXACT_ALARM"))
            assertTrue(manifest.contains("StandbyAlarmReceiver"))
            val receiver = manifest.substringAfter("StandbyAlarmReceiver").substringBefore("/>")
            assertTrue("the alarm receiver is not exported", receiver.contains("android:exported=\"false\""))
        }
    }

    @Test
    fun `the Watch re-arm is event-driven and never polls the Phone's reachability before a window`() {
        val runtime = source("$watch/WatchVoiceRuntime.kt")
        val rearm = runtime.substringAfter("private val rearmRunnable").substringBefore("\n    }")
        assertFalse("no node query in the rearm path", rearm.contains("refreshPhoneReachable") || rearm.contains("withTimeoutOrNull"))
        assertTrue(rearm.contains("coordinator.onRearmDue()"))
        assertFalse(runtime.contains("onRearmTimer"))
        assertTrue("reachability changes reach the coordinator as events", runtime.contains("coordinator.onReachabilityChanged()"))
        assertEquals("the runtime still takes none of the session's holds", 0, listOf("LISTEN", "HANDOFF", "REARM").count { runtime.contains("HoldReason.$it") })
    }

    @Test
    fun `the Watch's own wake-lock adapter stays free of alarms, and a recorded hold list has no re-arm hold`() {
        val app = source("$watch/WatchApp.kt")
        assertFalse(app.contains("AlarmManager"))
        assertFalse(source("core/src/main/kotlin/com/rumi/hermesvoice/core/background/WakeHolds.kt").contains("REARM("))
    }
}
