package com.rumi.hermesvoice.watch

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.rumi.hermesvoice.core.audio.EndpointDecision
import com.rumi.hermesvoice.core.audio.QaAudio
import com.rumi.hermesvoice.core.audio.SilenceEndpoint
import com.rumi.hermesvoice.core.settings.WakePhrasePatterns
import com.rumi.hermesvoice.core.watchlink.TurnTrigger
import com.rumi.hermesvoice.core.watchlink.WatchLinkPaths
import com.rumi.hermesvoice.core.watchlink.WatchPhase
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Push-to-talk (tap to start, tap to send; auto-stops at the max length) and, when enabled in the
 * Phone's Watch settings, a foreground-only wake phrase via the platform [SpeechRecognizer] that
 * starts a hands-free turn ended by [SilenceEndpoint]. The microphone is owned by one of them at a
 * time: the recognizer is released before capture starts, and re-armed only when the talk state is idle.
 */
class WatchActivity : ComponentActivity() {
    private val app by lazy { WatchApp.from(this) }
    private var capture: Capture? = null
    private var recognizer: SpeechRecognizer? = null
    private var resumed = false

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) rearmWakePhrase()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val talk by app.talk.collectAsStateWithLifecycle()
            val settings by app.settings.collectAsStateWithLifecycle()
            val phone by app.phoneReachable.collectAsStateWithLifecycle()
            MaterialTheme(colors = WatchColors) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colors.background), contentAlignment = Alignment.Center) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(
                            when (phone) {
                                true -> "Phone connected"
                                false -> "Phone not reachable"
                                null -> "Checking phone…"
                            },
                            color = if (phone == false) MaterialTheme.colors.error else MaterialTheme.colors.onSurfaceVariant,
                            style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center,
                        )
                        val recording = talk.phase == WatchPhase.RECORDING
                        Button(onClick = ::onTalkPressed, modifier = Modifier.padding(vertical = 6.dp).size(88.dp),
                            enabled = talk.phase != WatchPhase.SENDING,
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = if (recording) MaterialTheme.colors.error else MaterialTheme.colors.primary)) {
                            Text(if (recording) "Send" else "Talk", style = MaterialTheme.typography.title3)
                        }
                        Text(talk.line.ifBlank { if (settings.wakePhraseEnabled) "Say the wake phrase or tap" else "Tap to talk" },
                            color = MaterialTheme.colors.onBackground, style = MaterialTheme.typography.body2,
                            textAlign = TextAlign.Center, maxLines = 3)
                    }
                }
            }
        }
        handleQaAudio(intent)
        lifecycleScope.launch {
            // Release the recognizer whenever the microphone or speaker is busy (including playback
            // of a Phone turn's reply), so the wake phrase never listens to our own audio.
            app.talk.collect { if (it.canArmWakePhrase) rearmWakePhrase() else if (capture == null) releaseRecognizer() }
        }
        lifecycleScope.launch {
            app.settings.collect { if (it.wakePhraseEnabled) rearmWakePhrase() else releaseRecognizer() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleQaAudio(intent)
    }

    /**
     * Debuggable builds only: `am start ... --es hv_qa_wav <name>.wav` uploads files/qa/<name>.wav
     * to the Phone as a push-to-talk turn, over the same Data Layer path as a recording.
     */
    private fun handleQaAudio(intent: Intent?) {
        val name = intent?.getStringExtra(QaAudio.EXTRA) ?: return
        intent.removeExtra(QaAudio.EXTRA)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 || capture != null) return
        val file = QaAudio.resolve(File(filesDir, QaAudio.DIR), name) ?: return
        val turnId = app.newTurn(TurnTrigger.PUSH_TO_TALK) ?: return
        android.util.Log.i("HermesVoiceWatch", "qa audio submitted as a watch voice request turn=${turnId.take(12)}")
        app.upload(turnId, TurnTrigger.PUSH_TO_TALK, file.readBytes())
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        lifecycleScope.launch { pullSettings() }
        lifecycleScope.launch { app.refreshPhoneReachable() }
        if (!hasMic()) micPermission.launch(Manifest.permission.RECORD_AUDIO) else rearmWakePhrase()
    }

    override fun onPause() {
        resumed = false
        releaseRecognizer()
        capture?.let { finishCapture(send = false, reason = "Cancelled") }
        super.onPause()
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** The settings data item may predate this install's listener; read it directly on resume. */
    private suspend fun pullSettings() {
        runCatching {
            val items = Wearable.getDataClient(this).getDataItems(
                Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(WatchLinkPaths.SETTINGS).build()).await()
            try {
                items.firstOrNull()?.let { DataMapItem.fromDataItem(it).dataMap.getString("json") }?.let(app::applySettings)
            } finally {
                items.release()
            }
        }
    }

    private fun onTalkPressed() {
        val active = capture
        if (active != null) {
            finishCapture(send = true, reason = "")
            return
        }
        if (!hasMic()) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startCapture(TurnTrigger.PUSH_TO_TALK)
    }

    private fun startCapture(trigger: TurnTrigger) {
        releaseRecognizer()
        val turnId = app.newTurn(trigger) ?: return
        val endpoint = if (trigger == TurnTrigger.WAKE_PHRASE) {
            SilenceEndpoint(maxDurationMs = app.settings.value.maxTurnSeconds * 1000L)
        } else {
            null
        }
        val started = Capture(turnId, trigger, app.settings.value.maxTurnSeconds, endpoint) { decision ->
            runOnUiThread {
                if (capture?.turnId != turnId) return@runOnUiThread
                when (decision) {
                    EndpointDecision.NO_SPEECH -> finishCapture(send = false, reason = "Didn't hear anything")
                    else -> finishCapture(send = true, reason = "")
                }
            }
        }
        if (!started.start()) {
            app.discard("Microphone unavailable")
            return
        }
        capture = started
        app.buzz()
    }

    private fun finishCapture(send: Boolean, reason: String) {
        val active = capture ?: return
        capture = null
        val wav = active.stop()
        android.util.Log.i("HermesVoiceWatch", "watch mic captured bytes=${wav?.size ?: 0} send=$send")
        if (send && wav != null) app.upload(active.turnId, active.trigger, wav) else app.discard(reason.ifBlank { "Too short" })
    }

    // ── wake phrase ──────────────────────────────────────────────────────────────────────────

    private fun rearmWakePhrase() {
        if (!resumed || recognizer != null || capture != null || !hasMic()) return
        if (!app.settings.value.wakePhraseEnabled || !app.talk.value.canArmWakePhrase) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        val created = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = created
        created.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) = onRecognized(results, final = true)
            override fun onPartialResults(partialResults: Bundle?) = onRecognized(partialResults, final = false)
            override fun onError(error: Int) {
                releaseRecognizer()
                window.decorView.postDelayed({ rearmWakePhrase() }, REARM_DELAY_MS)
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        created.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
    }

    private fun onRecognized(bundle: Bundle?, final: Boolean) {
        if (recognizer == null) return
        val heard = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        if (heard.any { WakePhrasePatterns.matches(app.settings.value.wakePatterns, it) }) {
            // Release the recognizer's microphone before the recorder takes it.
            releaseRecognizer()
            startCapture(TurnTrigger.WAKE_PHRASE)
        } else if (final) {
            releaseRecognizer()
            window.decorView.postDelayed({ rearmWakePhrase() }, REARM_DELAY_MS)
        }
    }

    private fun releaseRecognizer() {
        recognizer?.let { runCatching { it.cancel() }; it.destroy() }
        recognizer = null
    }

    companion object {
        private const val REARM_DELAY_MS = 400L
    }
}

/** Always dark: black background (OLED), high-contrast text, blue Talk and red Send. */
private val WatchColors = Colors(
    primary = Color(0xFF8AB4F8),
    primaryVariant = Color(0xFF669DF6),
    secondary = Color(0xFF81C995),
    background = Color.Black,
    surface = Color(0xFF202124),
    error = Color(0xFFF28B82),
    onPrimary = Color(0xFF0B1D3A),
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color(0xFFE8EAED),
    onSurfaceVariant = Color(0xFFBDC1C6),
    onError = Color(0xFF3B0A08),
)

/** 16 kHz mono PCM16 capture to an in-memory WAV, with an optional hands-free endpoint. */
private class Capture(
    val turnId: String,
    val trigger: TurnTrigger,
    maxSeconds: Int,
    private val endpoint: SilenceEndpoint?,
    private val onEndpoint: (EndpointDecision) -> Unit,
) {
    private val limitBytes = SAMPLE_RATE * 2L * maxSeconds
    private val pcm = ByteArrayOutputStream()
    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission") // Checked by the activity before starting.
    fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, min.coerceAtLeast(FRAME_BYTES))
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: return false
        record = recorder
        running.set(true)
        recorder.startRecording()
        worker = Thread({
            val buffer = ByteArray(FRAME_BYTES)
            while (running.get()) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                val frame = buffer.copyOf(read)
                synchronized(pcm) { pcm.write(frame) }
                val decision = endpoint?.accept(frame) ?: EndpointDecision.CONTINUE
                val full = synchronized(pcm) { pcm.size() } >= limitBytes
                if (decision != EndpointDecision.CONTINUE || full) {
                    running.set(false)
                    onEndpoint(if (full && decision == EndpointDecision.CONTINUE) EndpointDecision.MAX_DURATION else decision)
                }
            }
        }, "hermes-voice-watch-capture").apply { start() }
        return true
    }

    fun stop(): ByteArray? {
        running.set(false)
        runCatching { record?.stop() }
        if (Thread.currentThread() !== worker) runCatching { worker?.join(1_500) }
        runCatching { record?.release() }
        record = null
        val data = synchronized(pcm) { pcm.toByteArray() }
        if (data.size < SAMPLE_RATE / 5) return null
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.size)
        }
        return header.array() + data
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_BYTES = 3_200
    }
}
