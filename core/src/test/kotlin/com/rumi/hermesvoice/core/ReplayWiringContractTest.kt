package com.rumi.hermesvoice.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-text contracts that the Play again path is wired end to end (they prove the wiring is written, not that Android runs it). */
class ReplayWiringContractTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun source(path: String) = File(root, path).readText()
    private val phone = "phone/src/main/kotlin/com/rumi/hermesvoice/phone"
    private val watch = "watch/src/main/kotlin/com/rumi/hermesvoice/watch"

    @Test
    fun `the Phone grants playback focus eligibility only to the visible app or its foreground services on Android 15`() {
        val app = source("$phone/PhoneApp.kt")
        assertTrue(app.contains("focusEligible = ::playbackFocusEligible"))
        assertTrue(app.contains("focusEligible(android.os.Build.VERSION.SDK_INT, activityVisible, PhoneRelayService.foregroundActive, PhoneWakeService.foregroundActive)"))
        assertFalse("object existence is not foreground admission", app.contains("PhoneRelayService.running != null"))
        assertTrue(source("$phone/PhoneAudio.kt").contains("if (focusEligible()) continuation.resumeWithException(HermesPlaybackTransientException"))
    }

    @Test
    fun `a Watch replay request reaches the relay on the Phone and the Watch sends it from a visible control`() {
        assertTrue(source("$phone/PhoneWatchBridge.kt").contains("WatchLinkPaths.REPLAY ->"))
        assertTrue(source("$phone/PhoneWatchBridge.kt").contains("watchIntake.replayReply("))
        assertTrue(source("$watch/WatchApp.kt").contains("WatchLinkPaths.REPLAY"))
        assertTrue(source("$watch/WatchActivity.kt").contains("onReplay = app::replayReply"))
        assertTrue(source("$watch/ReaderUi.kt").contains("testTag(\"play_again\")"))
    }

    @Test
    fun `the Phone shows a real Play again for every reply that did not play`() {
        assertTrue(source("$phone/MainActivity.kt").contains("UnplayedRepliesList(state.unplayed, onPlayAgain)"))
        assertTrue(source("$phone/MainActivity.kt").contains("onPlayAgain = model::playAgain"))
        assertTrue("the stored conversation of the tapped reply is what the replay is checked against",
            source("$phone/PhoneViewModel.kt").contains("app.replayReply(reply.identity, reply.storedSessionId.takeIf { it.isNotEmpty() }) { heard, detail ->"))
        assertTrue(source("$phone/MainActivity.kt").contains("onClick = { onPlayAgain(reply) }"))
        assertTrue(source("$phone/PhoneApp.kt").contains("core.orchestrator.unplayedReplies.collect"))
    }

    @Test
    fun `a foreground service counts only after the platform accepted startForeground and stops counting when it ends`() {
        for (file in listOf("PhoneRelayService.kt", "PhoneWakeService.kt")) {
            val text = source("$phone/$file")
            val entered = text.indexOf("ServiceCompat.startForeground(this, NOTIFICATION_ID")
            val flagged = text.indexOf("foregroundActive = true")
            assertTrue("$file sets the flag", flagged > entered && entered > 0)
            assertTrue("$file clears it in finish", text.substringAfter("fun finish()").substringBefore("override fun onDestroy").contains("foregroundActive = false"))
            assertTrue("$file clears it on destroy", text.substringAfter("override fun onDestroy").substringBefore("companion object").contains("foregroundActive = false"))
        }
    }
}
