package com.rumi.hermesvoice.phone

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import com.rumi.hermesvoice.core.HermesPlaybackException
import com.rumi.hermesvoice.core.SpokenAudio
import com.rumi.hermesvoice.core.voice.PlaybackCue
import com.rumi.hermesvoice.core.voice.PlaybackSink
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** 16 kHz mono PCM16 capture into an in-memory WAV (the format `/api/audio/transcribe` accepts as audio/wav). */
class WavRecorder(private val maxSeconds: Int = 120) {
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private val pcm = ByteArrayOutputStream()
    private val running = AtomicBoolean(false)

    val isRecording: Boolean get() = running.get()

    @SuppressLint("MissingPermission") // Callers request RECORD_AUDIO first.
    fun start() {
        if (running.get()) return
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, minBuffer.coerceAtLeast(FRAME_BYTES))
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }
        pcm.reset()
        record = recorder
        running.set(true)
        recorder.startRecording()
        val limit = SAMPLE_RATE * 2L * maxSeconds
        worker = Thread({
            val buffer = ByteArray(FRAME_BYTES)
            while (running.get() && pcm.size() < limit) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) synchronized(pcm) { pcm.write(buffer, 0, read) }
            }
        }, "hermes-voice-recorder").apply { start() }
    }

    /** Stops and returns the WAV, or null when nothing usable was captured. */
    fun stop(): ByteArray? {
        if (!running.getAndSet(false)) return null
        runCatching { record?.stop() }
        runCatching { worker?.join(1_500) }
        runCatching { record?.release() }
        record = null
        worker = null
        val data = synchronized(pcm) { pcm.toByteArray() }
        return if (data.size < SAMPLE_RATE / 5) null else wav(data)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val FRAME_BYTES = 5_120

        /** Largest absolute PCM16 sample after the 44-byte header (0 means digital silence); for logs only. */
        fun peak(wav: ByteArray): Int {
            var peak = 0
            var i = 44
            while (i + 1 < wav.size) {
                val sample = (wav[i].toInt() and 0xFF) or (wav[i + 1].toInt() shl 8)
                peak = maxOf(peak, kotlin.math.abs(sample.toShort().toInt()))
                i += 2
            }
            return peak
        }

        fun wav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(pcm.size)
            }
            return header.array() + pcm
        }
    }
}

/** Plays on the Phone speaker; returns when playback ends; cancellation stops playback immediately. */
class PhoneSpeakerSink(private val context: Context) : PlaybackSink {
    override suspend fun play(audio: SpokenAudio, cue: PlaybackCue) {
        val extension = when {
            audio.mimeType.contains("ogg") -> "ogg"
            audio.mimeType.contains("wav") -> "wav"
            audio.mimeType.contains("flac") -> "flac"
            else -> "mp3"
        }
        val file = File(context.cacheDir, "hv-${cue.turnId.hashCode().toUInt()}-${cue.sequence}.$extension")
        withContext(Dispatchers.IO) { file.writeBytes(audio.bytes) }
        try {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val player = MediaPlayer()
                    continuation.invokeOnCancellation { runCatching { player.stop() }; player.release() }
                    player.setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                    player.setOnCompletionListener {
                        it.release()
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    player.setOnErrorListener { mp, what, extra ->
                        mp.release()
                        if (continuation.isActive) continuation.resumeWithException(HermesPlaybackException("phone playback error $what/$extra"))
                        true
                    }
                    try {
                        player.setDataSource(file.absolutePath)
                        player.setOnPreparedListener { it.start() }
                        player.prepareAsync()
                    } catch (error: Exception) {
                        player.release()
                        continuation.resumeWithException(HermesPlaybackException("phone playback failed: ${error.javaClass.simpleName}"))
                    }
                }
            }
        } finally {
            file.delete()
        }
    }
}
