package com.rumi.hermesvoice.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Secondary source gates: the behaviour itself is tested on the pure cores the Android adapters
 * delegate to (see WatchAdaptersTest). These only check that the adapters still delegate there and
 * keep platform wiring the JVM cannot run (theme flags, manifest, focus order, contexts).
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
        assertTrue(ui.contains("driver.offer(event.verticalScrollPixels)") && ui.contains("driver.drain("))
        assertTrue("focus sits on the list, not around buttons", ui.contains("}.focusRequester(focusRequester).focusable()"))
        val activity = source("$watch/WatchActivity.kt")
        assertTrue(activity.contains("sessionsFocus.requestFocus()") && activity.contains("chatFocus.requestFocus()"))
        assertTrue(activity.contains("app.haptic(HapticEvent.SCROLL_STEP)"))
    }

    @Test
    fun `the capture lifecycle and wake window delegate to the tested cores`() {
        val activity = source("$watch/WatchActivity.kt")
        val talkPressed = activity.substringAfter("private fun onTalkPressed()").substringBefore("// ── capture")
        assertFalse("no haptic on button intent", talkPressed.contains("haptic"))
        assertTrue(activity.contains("CaptureCoordinator(capturePort)"))
        assertTrue(activity.substringAfter("override fun onLive()").substringBefore("override fun onCalibrated()").contains("captures.onLive(turnId)"))
        assertTrue(activity.substringAfter("override fun onCalibrated()").substringBefore("override fun onEnd(").contains("captures.onCalibrated(turnId)"))
        assertTrue(activity.substringAfter("override fun onPause()").substringBefore("super.onPause()").contains("CaptureStop.LIFECYCLE"))
        assertTrue("handoff is cancellable and generation-bound",
            activity.contains("handoffGate.claim(handoffGeneration, wake.generation") && activity.contains("removeCallbacks(handoffRunnable)"))
        assertFalse("no duration cap", activity.contains("maxTurnSeconds") || source("$watch/WatchCapture.kt").contains("limitFor"))
        val capture = source("$watch/WatchCapture.kt")
        assertTrue("start is confirmed by the recorder state", capture.contains("recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING"))
        assertFalse("no buzz on phone stage updates or timers", source("$watch/WatchApp.kt").contains("fun buzz("))
        val app = source("$watch/WatchApp.kt")
        assertTrue("usage comes from the tested policy", app.contains("WatchHapticPolicy.usageFor(event)") &&
            app.contains("VibrationAttributes.USAGE_HARDWARE_FEEDBACK") && app.contains("VibrationAttributes.USAGE_TOUCH"))
        assertFalse("never bypasses Do Not Disturb or user settings", app.contains("FLAG_BYPASS"))
        assertFalse("the end reason is only the accepted stop's", activity.contains("lastStop"))
    }

    @Test
    fun `wake recognizer adapter delegates to the window coordinator`() {
        val wake = source("$watch/WakeController.kt")
        assertTrue(wake.contains("WakeWindowCoordinator(recognizerPort, timerPort, hostPort"))
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
