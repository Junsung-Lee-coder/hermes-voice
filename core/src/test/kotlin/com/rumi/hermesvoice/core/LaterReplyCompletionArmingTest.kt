package com.rumi.hermesvoice.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the repair of the four `LaterReplyCompletionTest` "phone - ..." failures seen in the r1-full run: the sink's hooks were
 * armed after the later reply was pushed, so a fast reply executed before they were set. Every scenario now arms before the push.
 */
class LaterReplyCompletionArmingTest {
    private val source: String = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
        .let { File(it, "core/src/test/kotlin/com/rumi/hermesvoice/core/LaterReplyCompletionTest.kt").readText() }

    @Test
    fun `the scenario arms its sink hooks before the later reply is pushed`() {
        val scenario = source.substringAfter("private fun phoneScenario(").substringBefore("// ── R1, Phone-shaped sink")
        assertTrue(scenario.indexOf("arm(h, sink, aPool, gate)") in 0 until scenario.indexOf("h.fake.pushLaterTurn("))
    }

    @Test
    fun `no phone scenario sets a hook inside its body`() {
        val phoneTests = source.substringAfter("// ── R1, Phone-shaped sink").substringBefore("// ── R1, the real WatchPlaybackSink")
        assertEquals(5, Regex("phoneScenario\\(").findAll(phoneTests).count())
        assertEquals(5, Regex("arm = \\{").findAll(phoneTests).count())
        for (body in phoneTests.split("}) {").drop(1)) {
            assertTrue(!body.substringBefore("@Test").contains("sink.beforeEnd ="))
        }
    }
}
