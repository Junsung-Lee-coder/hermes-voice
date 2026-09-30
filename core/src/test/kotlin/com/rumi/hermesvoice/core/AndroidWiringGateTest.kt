package com.rumi.hermesvoice.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source gates for Android wiring the JVM suite cannot execute: they fail if a platform behavior
 * regresses to an easy wrong shape (a no-op gesture, a haptic on button intent, a service-scoped
 * context in app-scoped work). Runtime behavior is verified separately on devices/emulators.
 */
class AndroidWiringGateTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun source(path: String) = File(root, path).readText()

    private val watch = "watch/src/main/kotlin/com/rumi/hermesvoice/watch"
    private val phone = "phone/src/main/kotlin/com/rumi/hermesvoice/phone"

    @Test
    fun `right swipe backgrounds the task and the system swipe-to-dismiss cannot finish it instead`() {
        val activity = source("$watch/WatchActivity.kt")
        val background = activity.substringAfter("ReaderAction.BACKGROUND_APP ->").substringBefore("}")
        assertTrue(background.contains("moveTaskToBack(true)"))
        assertFalse(background.contains("finish"))
        assertTrue(activity.contains("ReaderAction.TOGGLE_SURFACE -> {") && activity.contains("app.toggleReaderSurface()"))
        assertTrue(activity.contains(".readerSwipe(::onSwipe)"))
        assertTrue(source("watch/src/main/res/values/themes.xml").contains("<item name=\"android:windowSwipeToDismiss\">false</item>"))
    }

    @Test
    fun `both reader lists take bezel input through a focused list with scroll haptics`() {
        val ui = source("$watch/ReaderUi.kt")
        assertEquals(2, Regex("\\.rotaryScroll\\(listState, focusRequester, onScrollStep\\)").findAll(ui).count())
        assertTrue(ui.contains("onRotaryScrollEvent"))
        assertTrue(ui.contains("haptics.shouldPulse(requested, consumed"))
        assertTrue("focus sits on the list, not around buttons", ui.contains("}.focusRequester(focusRequester).focusable()"))
        val activity = source("$watch/WatchActivity.kt")
        assertTrue(activity.contains("sessionsFocus.requestFocus()") && activity.contains("chatFocus.requestFocus()"))
        assertTrue(activity.contains("app.haptic(HapticEvent.SCROLL_STEP)"))
    }

    @Test
    fun `recording haptics follow real microphone audio, never the button`() {
        val activity = source("$watch/WatchActivity.kt")
        val talkPressed = activity.substringAfter("private fun onTalkPressed()").substringBefore("// ── capture")
        assertFalse(talkPressed.contains("haptic") || talkPressed.contains("onRecordingStarted"))
        assertTrue(activity.substringAfter("override fun onLive()").substringBefore("override fun onCalibrated()")
            .contains("if (!wakeRequest) app.onRecordingStarted(turnId)"))
        assertTrue(activity.substringAfter("override fun onCalibrated()").substringBefore("override fun onEnd(")
            .contains("app.onRecordingStarted(turnId)"))
        assertTrue(activity.substringAfter("private fun finishCapture(").substringBefore("// ── wake phrase")
            .contains("app.onRecordingEnded(active.turnId, endReason)"))
        val capture = source("$watch/WatchCapture.kt")
        assertTrue("start is confirmed by the recorder state", capture.contains("recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING"))
        assertFalse("no buzz on phone stage updates or timers", source("$watch/WatchApp.kt").contains("fun buzz("))
    }

    @Test
    fun `wake recognizer and recorder never share the microphone`() {
        val wake = source("$watch/WakeController.kt")
        val beforeHandoff = wake.substringAfter("is WakeOutcome.Handoff -> {").substringBefore("onHandoff(outcome)")
        assertTrue("the recognizer is released before the recorder is handed the microphone", beforeHandoff.contains("release()"))
        assertFalse("receiver never touches the microphone", wake.substringAfter("override fun onReceive").substringBefore("override fun onStart")
            .contains("startListening"))
        assertFalse(wake.contains("EXTRA_PREFER_OFFLINE"))
    }

    @Test
    fun `listener services keep only the application context in app-scoped work`() {
        val bridge = source("$phone/PhoneWatchBridge.kt")
        assertFalse(bridge.contains("this@PhoneWatchListenerService"))
        assertTrue(bridge.contains("DataLayerWatchTransport(context, channel.nodeId)") && bridge.contains("val context = applicationContext"))
        assertTrue(bridge.contains("Wearable.getMessageClient(context.applicationContext)"))
        assertFalse(source("$watch/WatchListenerService.kt").contains("this@WatchListenerService"))
        assertTrue(bridge.contains("BoundedRead.readAtMost(input, BoundedRead.FRAME_LIMIT)"))
    }

    @Test
    fun `phone answers reader requests and the label follows the route`() {
        assertTrue(source("phone/src/main/AndroidManifest.xml").contains("android:path=\"/hv/v1/reader/request\""))
        val app = source("$phone/PhoneApp.kt")
        assertTrue(app.contains("route?.device ?: flowOf(null)"))
        assertFalse(app.contains("_playbackDevice.value = origin"))
        assertTrue(source("$phone/MainActivity.kt").contains("handleQaAudio(intent, restored = savedInstanceState != null)"))
    }
}
