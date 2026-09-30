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
        val runtime = source("$watch/WatchVoiceRuntime.kt")
        val talkPressed = runtime.substringAfter("fun onTalkPressed(): Boolean").substringBefore("// ── capture")
        assertFalse("no haptic on button intent", talkPressed.contains("haptic"))
        assertTrue(runtime.contains("CaptureCoordinator(capturePort)"))
        assertTrue(runtime.substringAfter("override fun onLive()").substringBefore("override fun onCalibrated()").contains("captures.onLive(turnId)"))
        assertTrue(runtime.substringAfter("override fun onCalibrated()").substringBefore("override fun onEnd(").contains("captures.onCalibrated(turnId)"))
        // Leaving the screen goes to the tested presence rules, which end a recording unsent unless a background session is armed.
        assertTrue(activity.substringAfter("override fun onPause()").substringBefore("super.onPause()").contains("voice.onActivityPaused()"))
        assertTrue(runtime.substringAfter("fun onActivityPaused()").substringBefore("fun onPermissionGranted()").contains("presence.onActivityPaused()"))
        assertTrue(runtime.substringAfter("override fun cancelCapture(reason: String)").substringBefore("}").contains("CaptureStop.LIFECYCLE"))
        assertTrue("handoff is cancellable and generation-bound (WakeDeviceController)",
            runtime.contains("wake.wake.onHandoffDue(captureIdle = captures.activeId == null)") && runtime.contains("removeCallbacks(handoffRunnable)"))
        assertFalse("no duration cap", runtime.contains("maxTurnSeconds") || source("$watch/WatchCapture.kt").contains("limitFor"))
        val capture = source("$watch/WatchCapture.kt")
        assertTrue("start is confirmed by the recorder state", capture.contains("recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING"))
        assertTrue("the shared recording loop", capture.contains("PcmCaptureLoop({ recorder.read(it, 0, it.size) }, limitBytes, endpoint, listener"))
        assertTrue("the silence snapshot the wake flow passed in",
            runtime.contains("SilenceEndpoint(sampleRate = WatchCapture.SAMPLE_RATE, silenceMs = silenceMs)") &&
                runtime.contains("startCapture(TurnTrigger.WAKE_PHRASE, silenceMs)"))
        assertTrue(runtime.contains("end(turnId, CaptureStop.of(reason))"))
        assertFalse("no buzz on phone stage updates or timers", source("$watch/WatchApp.kt").contains("fun buzz("))
        val app = source("$watch/WatchApp.kt")
        assertTrue("usage comes from the tested policy", app.contains("WatchHapticPolicy.usageFor(event)") &&
            app.contains("VibrationAttributes.USAGE_HARDWARE_FEEDBACK") && app.contains("VibrationAttributes.USAGE_TOUCH"))
        assertFalse("never bypasses Do Not Disturb or user settings", app.contains("FLAG_BYPASS"))
        assertFalse("the end reason is only the accepted stop's", runtime.contains("lastStop"))
        // The activity holds no voice logic of its own: no recorder, coordinator or wake controller is created per screen.
        for (owned in listOf("CaptureCoordinator(", "WatchCapture(", "WakeController(", "SpeechRecognizer")) assertFalse(owned, activity.contains(owned))
    }

    @Test
    fun `both wake adapters delegate to the shared device controller`() {
        for ((path, device) in listOf("$watch/WakeController.kt" to "WATCH", "$phone/PhoneWakeController.kt" to "PHONE")) {
            val wake = source(path)
            assertTrue(path, wake.contains("WakeDeviceController(VoiceOrigin.$device, recognizerPort, timerPort, port, SystemClock::elapsedRealtime, initial, claims)"))
            // Stale callbacks of a released recognizer are dropped by token, for errors and results alike.
            assertTrue(path, wake.contains("created.setRecognitionListener(listenerFor(generation, onDevice, guard.open()))"))
            assertTrue(path, wake.contains("if (!guard.isCurrent(token)) return") &&
                wake.contains("if (guard.isCurrent(token)) onRecognized(gen, results, final = true)") &&
                wake.contains("if (guard.isCurrent(token)) onRecognized(gen, partialResults, final = false)"))
            assertTrue(path, wake.substringAfter("private fun destroyRecognizer()").substringBefore("}").contains("guard.close()"))
            assertFalse("the fallback no longer tests only for a non-null recognizer", wake.contains("error in LANGUAGE_ERRORS && recognizer != null"))
            assertFalse(wake.contains("EXTRA_PREFER_OFFLINE"))
            assertTrue(wake.contains("wake.onResults(gen, heard, final)"))
            // Privacy: recognized words are never logged, only how many candidates there were.
            assertFalse(path, Regex("Log\\.[a-z]\\([^\n]*\\$\\{?heard(?!\\.size)").containsMatchIn(wake))
        }
        // The Phone's wake phrase stays bound to its visible activity: pausing closes the window.
        val phoneWake = source("$phone/PhoneWakeController.kt")
        assertTrue(phoneWake.contains("private val activity: ComponentActivity") && phoneWake.contains("override fun onPause(owner: LifecycleOwner) = wake.onPause()"))
        assertFalse("receiver never touches the microphone", phoneWake.substringAfter("override fun onReceive").substringBefore("override fun onStart")
            .contains("startListening"))
        // The Watch's belongs to the application runtime and follows the tested presence rules.
        val watchWake = source("$watch/WakeController.kt")
        assertFalse(watchWake.contains("ComponentActivity") || watchWake.contains("LifecycleObserver"))
        val runtime = source("$watch/WatchVoiceRuntime.kt")
        assertTrue(runtime.contains("val presence: WakePresence = WakePresence(wake.wake, presencePort)"))
        assertTrue("the Watch waits for its synced settings", runtime.contains("presence.onActivityResumed(settingsPending = true)"))
        assertTrue(runtime.substringAfter("fun onSettingsPulled()").substringBefore("fun onActivityPaused()").let {
            it.indexOf("wake.wake.onSettings(app.settings.value)") in 0 until it.indexOf("presence.onSettingsCurrent()")
        })
        assertTrue(runtime.substringAfter("app.settings.collect {").substringBefore("}").contains("wake.wake.onSettings(it)"))
        assertFalse("the screen receiver only posts a signal", runtime.substringAfter("private val screenReceiver").substringBefore("private val qaReceiver")
            .let { it.contains("startListening") || it.contains("startCapture") })
        assertTrue("present = on screen or an armed session; the screen state is waived only for an armed session",
            runtime.contains("resumed = presence.present,") &&
                runtime.contains("interactive = presence.armed || app.getSystemService(PowerManager::class.java)?.isInteractive == true,"))
        val watchActivity = source("$watch/WatchActivity.kt")
        assertTrue(watchActivity.substringAfter("override fun onResume()").substringBefore("override fun onPause()").let {
            it.indexOf("voice.onActivityResumed()") in 0 until it.indexOf("pullSettings()") && it.indexOf("pullSettings()") < it.indexOf("voice.onSettingsPulled()")
        })
        val phoneActivity = source("$phone/MainActivity.kt")
        assertTrue(phoneActivity.contains("collect { phoneWake.wake.onSettings(it) }"))
        assertTrue("the Phone recorder gets the snapshot", phoneActivity.contains("model.startHandsFree(silenceMs, claimId)"))
        val model = source("$phone/PhoneViewModel.kt")
        assertTrue(model.contains("SilenceEndpoint(sampleRate = PhoneCapture.SAMPLE_RATE, silenceMs = silenceMs)"))
        assertTrue("the same capture lifecycle as the Watch", model.contains("private val captures = CaptureCoordinator(") &&
            model.contains("captures.stop(id, CaptureStop.of(reason))"))
        assertTrue(source("$phone/PhoneCapture.kt").contains("PcmCaptureLoop({ recorder.read(it, 0, it.size) }, LIMIT_BYTES, endpoint, listener"))
    }

    @Test
    fun `both apps carry the wake claim with their requests and the phone answers claims from the transport's node`() {
        val bridge = source("$phone/PhoneWatchBridge.kt")
        assertTrue(bridge.contains("WatchLinkPaths.WAKE_CLAIM -> answerWakeClaim(event.sourceNodeId, event.data)"))
        assertTrue(bridge.contains("WakeClaimService.handle(wiring.core.wakeAdmission, nodeId, data)"))
        assertTrue(source("phone/src/main/AndroidManifest.xml").contains("android:path=\"/hv/v1/wake/claim\""))
        val phoneActivity = source("$phone/MainActivity.kt")
        assertTrue(phoneActivity.contains("model.startHandsFree(silenceMs, claimId)") && phoneActivity.contains("model.sendRecognizedRequest(request, claimId)"))
        assertTrue(phoneActivity.contains("admission()?.claim(WakeClaim(claimId, VoiceOrigin.PHONE, \"\", settingsRevision, generation, epoch))"))
        val model = source("$phone/PhoneViewModel.kt")
        assertTrue(model.contains("submitVoice(wav, wakeTurn = true, wakeClaimId = claimId)") && model.contains("wakeTurn = true, wakeClaimId = claimId)"))
        assertTrue("a busy phone says so instead of dropping the request", model.substringAfter("fun sendRecognizedRequest").substringBefore("recognizedClaimId = claimId")
            .contains("return onWakeClosed(\"unfinished_request\")"))
        val watchRuntime = source("$watch/WatchVoiceRuntime.kt")
        assertTrue(watchRuntime.contains("app.upload(captureId, trigger, wav, claimId)") && watchRuntime.contains("app.uploadRecognized(turnId, text, recognizedClaimId)"))
        assertTrue(watchRuntime.contains("wake.wake.onRequestCaptureEnded(sent = true)") && watchRuntime.contains("wake.wake.onRequestCaptureEnded(sent = false)"))
        val watchApp = source("$watch/WatchApp.kt")
        assertTrue("a verdict counts only from the node the claim went to", watchApp.contains("if (!claimSender.accepts(verdict.claimId, sourceNodeId))"))
        assertTrue(source("$watch/WatchListenerService.kt").contains("WatchLinkPaths.WAKE_VERDICT ->"))
    }

    @Test
    fun `the watch keeps its claim until the phone answers and sends every claim message through the one ordered sender`() {
        val watchApp = source("$watch/WatchApp.kt")
        assertTrue(watchApp.contains("fun sendWakeClaim(message: WakeClaimMessage) = claimSender.offer(message)"))
        assertEquals("claim messages leave the Watch in one place only", 1, Regex("WatchLinkPaths\\.WAKE_CLAIM").findAll(watchApp).count())
        assertTrue(watchApp.contains("WakeClaimSender(scope, ::phoneNode,"))
        val upload = watchApp.substringAfter("fun upload(turnId: String, trigger: TurnTrigger, wav: ByteArray").substringBefore("fun uploadRecognized")
        assertTrue("the claim is kept before the recording is handed to the link",
            upload.indexOf("claimId?.let { keepClaimInTransit(it, turnId, wav.size) }") in 0 until upload.indexOf("send(turnId, WatchTurnUpload("))
        assertTrue(watchApp.substringAfter("fun uploadRecognized").substringBefore("// ── wake arbitration").contains("wakeClaimId?.let { keepClaimInTransit(it, turnId, request.length) }"))
        assertTrue("kept by the application, not the activity, so leaving the screen does not drop it",
            watchApp.contains("transitJob = scope.launch {") && watchApp.contains("delay(WakeContract.CLAIM_RENEW_MS)") && watchApp.contains("transit.tick()"))
        assertTrue(watchApp.contains("transit.onTransferFailed(turnId)") && watchApp.contains("transit.onPhoneState(message.turnId, message.terminal)") &&
            watchApp.contains("transit.onVerdict(verdict.claimId, verdict.verdict)"))
        assertTrue("a claim lost on the way is shown", watchApp.contains("it.sendFailed(\"Couldn't confirm with the phone. Say it again\")"))
        // The Phone says a turn is accepted at once, before it waits its place in line.
        assertTrue(source("core/src/main/kotlin/com/rumi/hermesvoice/core/watchlink/PhoneWatchRelay.kt")
            .contains("states.trySend(TurnStateMessage(turnId, \"accepted\", \"\", false))"))
    }

    @Test
    fun `both apps share the phone's count of answered wake requests and read it before they listen`() {
        val app = source("$phone/PhoneApp.kt")
        assertTrue(app.contains("core.onWakeEpisode = { episode ->") && app.contains("publishWakeEpoch(WakeEpochItem(episode.epoch, episode.claimId))") &&
            app.contains("publishWakeEpoch(WakeEpochItem(core.wakeAdmission.epoch))"))
        assertTrue(source("$phone/PhoneWatchBridge.kt").contains("PutDataMapRequest.create(WatchLinkPaths.WAKE_EPOCH)"))
        val phoneActivity = source("$phone/MainActivity.kt")
        assertTrue(phoneActivity.contains("override fun epoch(): Long = admission()?.epoch ?: 0L") &&
            phoneActivity.contains("phoneWake.wake.onEpisodeAnswered(it.epoch, it.claimId)"))
        assertTrue(source("watch/src/main/AndroidManifest.xml").contains("android:path=\"/hv/v1/wake/epoch\""))
        assertTrue(source("$watch/WatchListenerService.kt").contains("WatchLinkPaths.WAKE_EPOCH -> app.scope.launch { app.applyWakeEpoch(json) }"))
        val watchActivity = source("$watch/WatchActivity.kt")
        val watchRuntime = source("$watch/WatchVoiceRuntime.kt")
        assertTrue(watchRuntime.contains("override fun epoch(): Long = app.wakeEpoch") &&
            watchRuntime.contains("app.sendWakeClaim(WakeClaimMessage(WakeClaimMessage.Op.CLAIM, claimId, settingsRevision, generation, epoch))") &&
            watchRuntime.contains("app.wakeEpochListener = { epoch, claimId -> wake.wake.onEpisodeAnswered(epoch, claimId) }"))
        val resume = watchActivity.substringAfter("override fun onResume()").substringBefore("override fun onPause()")
        assertTrue("the count is read before the Watch may listen",
            watchActivity.contains("pullItem(WatchLinkPaths.WAKE_EPOCH, app::applyWakeEpoch)") &&
                resume.indexOf("pullSettings()") in 0 until resume.indexOf("voice.onSettingsPulled()"))
        assertTrue("the wake location changing mid-request is said on both devices",
            watchRuntime.contains("\"wake_mode_changed\" ->") && source("$phone/PhoneViewModel.kt").contains("\"wake_mode_changed\" ->"))
    }

    @Test
    fun `talk tapped in an open wake window releases the recognizer and waits the handoff pause on both devices`() {
        val phoneActivity = source("$phone/MainActivity.kt")
        val phoneTalk = phoneActivity.substringAfter("private fun onTalk()").substringBefore("override fun onCreate")
        assertTrue(phoneTalk.contains("phoneWake.wake.onBusy()") && phoneTalk.contains("postDelayed(talkAfterRelease, WakeContract.MIC_HANDOFF_MS)"))
        assertTrue(phoneActivity.contains("PhoneScreen(model, recognizerAvailable, onTalk = ::onTalk)"))
        val watchTalk = source("$watch/WatchVoiceRuntime.kt").substringAfter("fun onTalkPressed(): Boolean").substringBefore("// ── capture")
        assertTrue(watchTalk.contains("presence.onBusy()") && watchTalk.contains("postDelayed(talkAfterRelease, WakeContract.MIC_HANDOFF_MS)"))
        // On the Phone, leaving the screen (rotation included) cancels a hands-free recording unsent: the documented lifecycle contract.
        assertTrue(source("$phone/PhoneWakeController.kt").contains("override fun onPause(owner: LifecycleOwner) = wake.onPause()"))
    }

    private fun permissions(manifest: String) = Regex("uses-permission android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1].removePrefix("android.permission.") }.toList()

    @Test
    fun `the manifests declare exactly the background components that are used, and nothing that starts or wakes by itself`() {
        val phoneManifest = source("phone/src/main/AndroidManifest.xml")
        val watchManifest = source("watch/src/main/AndroidManifest.xml")
        assertEquals(listOf("INTERNET", "RECORD_AUDIO", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_CONNECTED_DEVICE", "FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "CHANGE_NETWORK_STATE", "POST_NOTIFICATIONS", "WAKE_LOCK"), permissions(phoneManifest))
        assertEquals(listOf("RECORD_AUDIO", "VIBRATE", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_MICROPHONE", "FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "POST_NOTIFICATIONS", "WAKE_LOCK"), permissions(watchManifest))
        for (manifest in listOf(phoneManifest, watchManifest)) {
            assertTrue(manifest.contains("<action android:name=\"android.speech.RecognitionService\" />"))
            // No boot start, battery-optimization exemption, overlay, full-screen intent, exact alarm, accessibility or assistant role.
            for (forbidden in listOf("RECEIVE_BOOT_COMPLETED", "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "SYSTEM_ALERT_WINDOW", "USE_FULL_SCREEN_INTENT",
                "SCHEDULE_EXACT_ALARM", "USE_EXACT_ALARM", "BIND_ACCESSIBILITY_SERVICE", "BIND_VOICE_INTERACTION", "FOREGROUND_SERVICE_DATA_SYNC",
                "FOREGROUND_SERVICE_SPECIAL_USE", "<receiver", "dataSync", "shortService", "specialUse", "systemExempted")) {
                assertFalse(forbidden, manifest.contains(forbidden))
            }
        }
        // The foreground services are private to the app and typed for what they do; the Phone's never has the microphone.
        val phoneService = phoneManifest.substringAfter("android:name=\".PhoneRelayService\"").substringBefore("/>")
        assertTrue(phoneService.contains("android:exported=\"false\"") && phoneService.contains("android:foregroundServiceType=\"connectedDevice|mediaPlayback\""))
        val watchService = watchManifest.substringAfter("android:name=\".WatchVoiceService\"").substringBefore("/>")
        assertTrue(watchService.contains("android:exported=\"false\"") && watchService.contains("android:foregroundServiceType=\"microphone|mediaPlayback\""))
        assertEquals("only the Data Layer listeners are exported", 1, Regex("<service[^>]*android:exported=\"true\"").findAll(phoneManifest).count())
        assertEquals(1, Regex("<service[^>]*android:exported=\"true\"").findAll(watchManifest).count())
        val relay = source("$phone/PhoneRelayService.kt")
        assertFalse(relay.substringBefore("class AndroidWakeLocks").let { it.contains("MICROPHONE") || it.contains("AudioRecord") || it.contains("SpeechRecognizer") })
    }

    @Test
    fun `background operation starts only from the visible app, is not restarted by the system and is stopped from its notification or the app`() {
        val watchActivity = source("$watch/WatchActivity.kt")
        val runtime = source("$watch/WatchVoiceRuntime.kt")
        val watchService = source("$watch/WatchVoiceService.kt")
        val watchApp = source("$watch/WatchApp.kt")
        val listener = source("$watch/WatchListenerService.kt")
        // Off unless started: the opt-in key is only written by the session itself, from Start and Stop.
        assertTrue(runtime.contains("BackgroundSession(app.localStore, KEY_BACKGROUND, servicePort, resumeWhenVisible = false)"))
        assertEquals("one place starts it: the activity's control", 1, Regex("voice\\.startBackground\\(\\)").findAll(watchActivity).count())
        assertTrue(watchActivity.substringAfter("private fun startBackgroundNow()").substringBefore("}").contains("voice.startBackground()"))
        assertTrue("the start is refused unless the activity is on screen",
            runtime.substringAfter("fun startBackground(): BackgroundStatus").substringBefore("fun stopBackground()").contains("background.start(visible = presence.visible,"))
        for (elsewhere in listOf(watchService, watchApp, listener)) {
            assertFalse(elsewhere.contains("startBackground(") || elsewhere.contains("background.start(") || elsewhere.contains("startForegroundService"))
        }
        assertEquals("the service is started in one place", 1, Regex("startForegroundService").findAll(runtime).count())
        // The microphone type is requested only when the session asks for it, and a refusal is caught, reported and not retried.
        assertTrue(watchService.contains("(if (microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)"))
        assertTrue(watchService.contains("microphone && enter(false) -> voice.onMicrophoneRefused(generation)"))
        assertTrue(watchService.substringAfter("private fun enter(microphone: Boolean): Boolean = runCatching {").contains("}.onFailure {"))
        assertTrue("armed from a resumed activity, after its settings are current",
            runtime.substringAfter("fun onSettingsPulled()").substringBefore("fun onActivityPaused()").contains("background.onVisible(app.settings.value.watchWakeEnabled, hasMic())"))
        assertTrue("a settings change disarms or arms through the tested session rules, never directly",
            runtime.contains("background.onMicrophoneWanted(app.settings.value.watchWakeEnabled, visible = presence.visible, permission = hasMic())"))
        assertTrue(runtime.contains("presence.onArmed(status.microphone)"))
        // Both services: not sticky, no redelivery, the session's generation, a Stop action to the service itself.
        for ((service, stop, gone) in listOf(Triple(watchService, "voice.stopBackground()", "voice.onServiceGone(generation)"),
            Triple(source("$phone/PhoneRelayService.kt"), "app.stopRelay()", "onRelayServiceGone(generation)"))) {
            assertTrue(service.contains("return START_NOT_STICKY"))
            assertFalse(service.contains("START_STICKY") || service.contains("START_REDELIVER_INTENT"))
            assertTrue(service.substringAfter("ACTION_STOP -> {").substringBefore("ACTION_START -> {").contains(stop))
            assertTrue(service.substringAfter("override fun onDestroy()").contains(gone))
            assertTrue(service.contains(".addAction(0, \"Stop\", stop)") && service.contains(".setOngoing(true)"))
            assertTrue(service.contains("PendingIntent.FLAG_IMMUTABLE") && !service.contains("FLAG_MUTABLE"))
            assertFalse("no full-screen intent or screen wake", service.contains("setFullScreenIntent") || service.contains("ACQUIRE_CAUSES_WAKEUP"))
        }
        // Stop with the app hidden ends everything of the session: recording, pending upload, playback, wake locks.
        val stop = runtime.substringAfter("fun stopBackground()").substringBefore("fun onMicrophoneRefused")
        for (step in listOf("background.stop()", "end(it, CaptureStop.LIFECYCLE)", "app.cancelPendingUploads(\"Stopped\")", "app.stopPlayback(\"stopped\")", "app.holds.releaseAll()")) {
            assertTrue(step, stop.contains(step))
        }
        assertTrue(watchApp.contains("if (request.turnId in stoppedTurns) return refusePlayback(request, nodeId, \"stopped on the watch\")"))
        assertTrue(watchApp.substringAfter("fun cancelPendingUploads(reason: String)").substringBefore("private fun send(").contains("job.cancel()"))
        // The Phone: the switch in the visible app, resumed only when an activity is started, turns stopped when closed.
        val phoneApp = source("$phone/PhoneApp.kt")
        assertTrue(phoneApp.contains("BackgroundSession(settingsStore, KEY_BACKGROUND_RELAY, relayPort, resumeWhenVisible = true)"))
        assertTrue(phoneApp.contains("relay.start(visible = activityVisible, microphoneWanted = false, microphonePermission = false)"))
        assertTrue(phoneApp.substringAfter("fun onActivityStarted()").substringBefore("fun onActivityStopped()").contains("relay.onVisible(microphoneWanted = false, microphonePermission = false)"))
        assertTrue(source("$phone/MainActivity.kt").substringAfter("override fun onStart()").substringBefore("override fun onStop()").contains("PhoneApp.from(this).onActivityStarted()"))
        assertTrue(phoneApp.substringAfter("fun stopRelay()").substringBefore("fun onRelayServiceGone").let {
            it.contains("relay.stop()") && it.contains("if (!hidden) return") && it.contains("turns.keys.toList().forEach { it.cancel() }") && it.contains("holds.releaseAll()")
        })
        assertEquals(1, Regex("startForegroundService").findAll(phoneApp).count())
        assertFalse(source("$phone/PhoneWatchBridge.kt").let { it.contains("startForegroundService") || it.contains("startRelay(") })
        assertFalse("the voice settings never carry or migrate the opt-in",
            source("core/src/main/kotlin/com/rumi/hermesvoice/core/settings/AppSettings.kt").let { it.contains("background") || it.contains("relay") })
    }

    @Test
    fun `wake locks are time-limited, the screen is never forced on, and the debug trigger exists only in debug builds`() {
        val sources = listOf("$watch/WatchApp.kt", "$watch/WatchVoiceRuntime.kt", "$watch/WatchVoiceService.kt", "$watch/WatchActivity.kt", "$watch/WakeController.kt",
            "$watch/WatchListenerService.kt", "$phone/PhoneApp.kt", "$phone/PhoneRelayService.kt", "$phone/PhoneAudio.kt", "$phone/MainActivity.kt",
            "$phone/PhoneViewModel.kt", "$phone/PhoneWatchBridge.kt").associateWith(::source)
        assertEquals("wake locks are created in one adapter per app", 2, sources.values.sumOf { Regex("newWakeLock\\(").findAll(it).count() })
        for ((path, text) in sources) {
            assertFalse("$path: every acquire has a timeout", Regex("\\.acquire\\(\\)").containsMatchIn(text))
            for (forbidden in listOf("FULL_WAKE_LOCK", "SCREEN_BRIGHT_WAKE_LOCK", "ACQUIRE_CAUSES_WAKEUP", "setTurnScreenOn", "FLAG_TURN_SCREEN_ON",
                "setShowWhenLocked", "ACTION_BOOT_COMPLETED", "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "setExact", "AlarmManager")) {
                assertFalse("$path: $forbidden", text.contains(forbidden))
            }
        }
        assertTrue(sources.getValue("$watch/WatchApp.kt").contains("PowerManager.PARTIAL_WAKE_LOCK, \"HermesVoice:\${reason.name.lowercase()}\"") &&
            sources.getValue("$watch/WatchApp.kt").contains("lock.acquire(timeoutMs)"))
        val runtime = sources.getValue("$watch/WatchVoiceRuntime.kt")
        assertTrue("the screen is kept on for a recording or a foreground window, never for a whole background session",
            runtime.contains("_keepScreenOn.value = recorder != null || (_wakeListening.value && !presence.armed)"))
        assertTrue("a hidden window holds the CPU only for its own length",
            runtime.contains("if (open && presence.armed) app.holds.acquire(HoldReason.LISTEN, wake.wake.windowMs + LISTEN_HOLD_MARGIN_MS)") &&
                runtime.contains("else if (!open) app.holds.release(HoldReason.LISTEN)"))
        assertTrue(runtime.substringAfter("override fun stopRecorder(").substringBefore("override fun haptic(").contains("app.holds.release(HoldReason.CAPTURE)"))
        val qa = runtime.substringBefore("ContextCompat.registerReceiver(app, qaReceiver").substringAfterLast("\n")
        assertTrue("the QA receiver is registered only in a debuggable build",
            runtime.substringBefore("ContextCompat.registerReceiver(app, qaReceiver").trimEnd().endsWith("// Exported so `adb shell am broadcast` reaches it; never registered in a release build.") &&
                runtime.contains("if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {") && qa.isBlank())
        assertEquals(1, Regex("RECEIVER_EXPORTED").findAll(runtime.replace("RECEIVER_NOT_EXPORTED", "")).count())
        val phoneApp = sources.getValue("$phone/PhoneApp.kt")
        assertEquals(1, Regex("RECEIVER_EXPORTED").findAll(phoneApp).count())
        assertTrue(phoneApp.substringBefore("ContextCompat.registerReceiver(this, qaReceiver").trimEnd()
            .endsWith("if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {\n            // Exported so `adb shell am broadcast` reaches it; never registered in a release build."))
        assertTrue("the Phone's debug trigger can only stop", phoneApp.substringAfter("private val qaReceiver").substringBefore("// ── background relay").let {
            it.contains("if (intent.getStringExtra(QA_RELAY) != \"stop\") return") && !it.contains("startRelay") })
    }

    @Test
    fun `playback asks for audio focus and only a finished player acknowledges it as played`() {
        val watchApp = source("$watch/WatchApp.kt")
        assertTrue(watchApp.contains("mp.setOnCompletionListener { if (player === it) finishPlayback(ok = true, error = \"\") }"))
        assertEquals("nothing else reports a successful playback", 1, Regex("finishPlayback\\(ok = true").findAll(watchApp).count())
        assertFalse(watchApp.contains("sendPlayed(request, node, ok = true") || watchApp.contains("sendPlayed(request, nodeId, ok = true"))
        assertTrue(watchApp.contains("if (!requestFocus()) return refusePlayback(request, nodeId, \"audio focus denied\")"))
        assertTrue(watchApp.substringAfter("private fun finishPlayback(").substringBefore("private fun refusePlayback(").contains("abandonFocus()"))
        val phoneAudio = source("$phone/PhoneAudio.kt")
        assertTrue(phoneAudio.contains("AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK") && phoneAudio.contains("phone playback refused: audio focus denied") &&
            phoneAudio.contains("abandonAudioFocusRequest(it)"))
        for (text in listOf(watchApp, phoneAudio)) {
            assertFalse("volume and Do Not Disturb are left to the system", text.contains("setStreamVolume") || text.contains("adjustStreamVolume") ||
                text.contains("setInterruptionFilter") || text.contains("FLAG_BYPASS"))
        }
    }

    @Test
    fun `voice turns of both origins run in the application, not in a screen`() {
        val model = source("$phone/PhoneViewModel.kt")
        assertTrue(model.contains("app.launchTurn(turnId, phoneOrigin = true) {"))
        assertFalse("no turn is tied to the view model's scope", model.substringAfter("private fun runPhoneTurn(").substringBefore("// ── phone hands-free").contains("viewModelScope"))
        assertTrue(source("$phone/PhoneWatchBridge.kt").contains("app.launchTurn(turnId, phoneOrigin = false) {"))
        val app = source("$phone/PhoneApp.kt")
        assertTrue(app.substringAfter("fun launchTurn(").let { it.contains("appScope.launch(start = CoroutineStart.LAZY)") && it.contains("holds.acquire(HoldReason.TURN)") &&
            it.contains("if (turns.isEmpty()) holds.release(HoldReason.TURN)") })
        assertEquals("one core, one connector: the relay adds none", 1, Regex("HermesVoiceCore\\.connect\\(").findAll(app).count())
    }

    @Test
    fun `a conversation created by a voice turn is shown on the phone and the watch without asking`() {
        val app = source("$phone/PhoneApp.kt")
        assertTrue(app.contains("if (route.created) conversationsCreated.value += 1"))
        assertTrue(source("$phone/PhoneViewModel.kt").contains("app.conversationsCreated.collect { count -> if (count > 0 && _state.value.signedIn) refresh() }"))
        val watchApp = source("$watch/WatchApp.kt")
        assertTrue(watchApp.substringAfter("if (message.terminal && ownTurn) {").substringBefore("}").contains("loadSessions()"))
        assertFalse("the Watch never creates sessions or talks to Hermes", watchApp.contains("session.create") || watchApp.contains("OkHttp"))
        assertFalse("creation only goes through the session repository", source("$phone/PhoneViewModel.kt").contains("conversations.create("))
    }

    @Test
    fun `the phone's session data is saved with a confirmed commit, never a fire-and-forget apply`() {
        val stores = source("$phone/AndroidStores.kt")
        assertTrue(stores.contains("override fun commitString(key: String, value: String): Boolean = prefs.edit().putString(key, value).commit()"))
        val sessions = source("core/src/main/kotlin/com/rumi/hermesvoice/core/sessions/AppSessions.kt")
        assertFalse("registry, journal and migration marker never use the unconfirmed write", sessions.contains("putString("))
        assertTrue("and their writes run off the main thread", sessions.contains("private val io: CoroutineDispatcher = Dispatchers.IO") &&
            Regex("withContext\\(io\\) \\{ (journal|registry)\\.").findAll(sessions).count() >= 6)
        assertTrue(source("$phone/PhoneViewModel.kt").contains("wiring.core.sessions.updateDestination(session.storedSessionId, alias, description)"))
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
