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
        assertTrue("handoff is cancellable and generation-bound (WakeDeviceController)",
            activity.contains("wake.wake.onHandoffDue(captureIdle = captures.activeId == null)") && activity.contains("removeCallbacks(handoffRunnable)"))
        assertFalse("no duration cap", activity.contains("maxTurnSeconds") || source("$watch/WatchCapture.kt").contains("limitFor"))
        val capture = source("$watch/WatchCapture.kt")
        assertTrue("start is confirmed by the recorder state", capture.contains("recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING"))
        assertTrue("the shared recording loop", capture.contains("PcmCaptureLoop({ recorder.read(it, 0, it.size) }, limitBytes, endpoint, listener"))
        assertTrue("the silence snapshot the wake flow passed in",
            activity.contains("SilenceEndpoint(sampleRate = WatchCapture.SAMPLE_RATE, silenceMs = silenceMs)") &&
                activity.contains("startCapture(TurnTrigger.WAKE_PHRASE, silenceMs)"))
        assertTrue(activity.contains("end(turnId, CaptureStop.of(reason))"))
        assertFalse("no buzz on phone stage updates or timers", source("$watch/WatchApp.kt").contains("fun buzz("))
        val app = source("$watch/WatchApp.kt")
        assertTrue("usage comes from the tested policy", app.contains("WatchHapticPolicy.usageFor(event)") &&
            app.contains("VibrationAttributes.USAGE_HARDWARE_FEEDBACK") && app.contains("VibrationAttributes.USAGE_TOUCH"))
        assertFalse("never bypasses Do Not Disturb or user settings", app.contains("FLAG_BYPASS"))
        assertFalse("the end reason is only the accepted stop's", activity.contains("lastStop"))
    }

    @Test
    fun `both wake adapters delegate to the shared device controller, foreground only`() {
        for ((path, device) in listOf("$watch/WakeController.kt" to "WATCH", "$phone/PhoneWakeController.kt" to "PHONE")) {
            val wake = source(path)
            assertTrue(path, wake.contains("WakeDeviceController(VoiceOrigin.$device, recognizerPort, timerPort, port, SystemClock::elapsedRealtime, initial)"))
            assertFalse("receiver never touches the microphone", wake.substringAfter("override fun onReceive").substringBefore("override fun onStart")
                .contains("startListening"))
            assertFalse(wake.contains("EXTRA_PREFER_OFFLINE"))
            assertTrue("pause closes the window", wake.contains("override fun onPause(owner: LifecycleOwner) = wake.onPause()"))
            assertTrue(wake.contains("wake.onResults(gen, heard, final)"))
        }
        assertTrue("the Watch waits for its synced settings", source("$watch/WakeController.kt").contains("wake.onResume(settingsPending = true)"))
        val watchActivity = source("$watch/WatchActivity.kt")
        assertTrue(watchActivity.contains("app.settings.collect { wake.wake.onSettings(it) }") && watchActivity.contains("wake.wake.onSettingsCurrent()"))
        val phoneActivity = source("$phone/MainActivity.kt")
        assertTrue(phoneActivity.contains("collect { phoneWake.wake.onSettings(it) }"))
        assertTrue("the Phone recorder gets the snapshot", phoneActivity.contains("model.startHandsFree(silenceMs)"))
        val model = source("$phone/PhoneViewModel.kt")
        assertTrue(model.contains("SilenceEndpoint(sampleRate = PhoneCapture.SAMPLE_RATE, silenceMs = silenceMs)"))
        assertTrue("the same capture lifecycle as the Watch", model.contains("private val captures = CaptureCoordinator(") &&
            model.contains("captures.stop(id, CaptureStop.of(reason))"))
        assertTrue(source("$phone/PhoneCapture.kt").contains("PcmCaptureLoop({ recorder.read(it, 0, it.size) }, LIMIT_BYTES, endpoint, listener"))
    }

    @Test
    fun `no new permission or background component, and the recognizer is visible on Android 11+`() {
        for (path in listOf("phone/src/main/AndroidManifest.xml", "watch/src/main/AndroidManifest.xml")) {
            val manifest = source(path)
            assertTrue(path, manifest.contains("<action android:name=\"android.speech.RecognitionService\" />"))
            assertFalse(path, manifest.contains("FOREGROUND_SERVICE") || manifest.contains("RECEIVE_BOOT_COMPLETED") || manifest.contains("WAKE_LOCK"))
        }
        val phonePermissions = Regex("uses-permission android:name=\"([^\"]+)\"").findAll(source("phone/src/main/AndroidManifest.xml"))
            .map { it.groupValues[1] }.toList()
        assertEquals(listOf("android.permission.INTERNET", "android.permission.RECORD_AUDIO"), phonePermissions)
    }

    @Test
    fun `settings migrate at phone start and the watch validates what it receives`() {
        assertTrue(source("$phone/PhoneApp.kt").contains("settings.migrate()"))
        val app = source("$watch/WatchApp.kt")
        assertTrue(app.contains("val result = replica.offer(json)") && app.contains("if (result == ReplicaUpdate.APPLIED)"))
        assertFalse(app.contains("WatchSettings.fromJson(json)"))
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
        assertTrue(source("$phone/MainActivity.kt").contains("handleQaIntent(intent, restored = savedInstanceState != null)"))
    }
}
