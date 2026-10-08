package com.rumi.hermesvoice.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source gates for the private headset output (amendment J) that the JVM cannot run: that the real Watch app refuses and stops audio
 * while the Phone says the headset is the only output, that the core is given the Phone's headset-aware sink as its private sink,
 * and that nothing polls or adds a service. A text match proves a line exists, never runtime timing; behavior is pinned in
 * PrivateAudio*Test (core) and the Watch and Phone Robolectric tests.
 */
class PrivateAudioWiringContractTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun source(path: String) = File(root, path).takeIf { it.isFile }?.readText().orEmpty()

    private val core = "core/src/main/kotlin/com/rumi/hermesvoice/core"
    private val watch = "watch/src/main/kotlin/com/rumi/hermesvoice/watch"
    private val phone = "phone/src/main/kotlin/com/rumi/hermesvoice/phone"

    @Test
    fun `the core hands the Phone's headset-aware sink to the orchestrator as its private sink`() {
        assertTrue(source("$core/HermesVoiceCore.kt").contains("privateSink = phoneSink"))
        assertTrue(source("$core/voice/VoiceTurnOrchestrator.kt").contains("fun onPrivateOutputChanged(active: Boolean)"))
    }

    @Test
    fun `the old refusal of later replies not owned by the Phone is gone`() {
        assertFalse(source("$core/headset/Headset.kt").contains("NOT_PHONE_FOR_LATER"))
        assertFalse(source("$core/voice/VoiceTurnOrchestrator.kt").contains("NOT_PHONE_FOR_LATER"))
    }

    @Test
    fun `the Watch checks the Phone's private state before it creates a player and acknowledges it did not play`() {
        val app = source("$watch/WatchApp.kt")
        val play = app.substringAfter("fun play(request: PlayRequest, nodeId: String) {").substringBefore("unstoppedResponseTurns += request.turnId")
        assertTrue("play refuses while private", play.contains("replica.current.privateAudio") && play.contains("refusePlayback("))
        val apply = app.substringAfter("fun applySettings(json: String) {").substringBefore("private val _phoneReachable")
        assertTrue("applying a private snapshot stops the player", apply.contains("stopPlayback("))
        assertTrue("and reports a receipt", apply.contains("WatchLinkPaths.PRIVATE_AUDIO") && apply.contains("PrivateAudioReceipt("))
        assertTrue(source("$watch/WatchReplyAlertNotifier.kt").contains("setSilent(true)"))
    }

    @Test
    fun `the Phone routes the receipt to the private-audio object and never polls for the headset`() {
        val bridge = source("$phone/PhoneWatchBridge.kt")
        assertTrue(bridge.contains("WatchLinkPaths.PRIVATE_AUDIO ->"))
        val hub = source("$phone/PrivateAudioHub.kt")
        assertTrue(hub.contains("PrivateAudioMonitor("))
        assertFalse(hub.contains("Thread.sleep") || hub.contains("while (true)") || hub.contains("delay("))
        assertEquals("no headset service was added", 3, Regex("<service\\s").findAll(source("phone/src/main/AndroidManifest.xml")).count())
    }

    @Test
    fun `the monitor registers the platform device callback only while the setting is on`() {
        val monitor = source("$core/headset/PrivateAudioMonitor.kt")
        assertTrue(monitor.contains("policy.watch(") && monitor.contains("policy.enabled"))
        assertFalse(monitor.contains("Thread.sleep") || monitor.contains("delay("))
    }
}
