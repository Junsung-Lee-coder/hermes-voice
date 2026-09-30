package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.TestAudio.concat
import com.rumi.hermesvoice.core.TestAudio.mix
import com.rumi.hermesvoice.core.TestAudio.noise
import com.rumi.hermesvoice.core.TestAudio.silence
import com.rumi.hermesvoice.core.TestAudio.speech
import com.rumi.hermesvoice.core.TestAudio.wav
import com.rumi.hermesvoice.core.audio.AudioInputGate
import com.rumi.hermesvoice.core.audio.AudioInputVerdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The acoustic input gate: recordings with no usable audio never reach speech-to-text. It is an
 * energy check, not a speech detector, so it must let every real utterance through.
 */
class AudioInputGateTest {
    private fun verdict(bytes: ByteArray) = AudioInputGate.assess(bytes, "audio/wav")

    @Test
    fun `unreadable or empty recordings are invalid`() {
        assertEquals(AudioInputVerdict.INVALID, verdict(ByteArray(0)))
        assertEquals(AudioInputVerdict.INVALID, verdict("not a wav at all, just bytes".toByteArray()))
        assertEquals(AudioInputVerdict.INVALID, verdict(wav(ShortArray(0))))
        assertEquals("under 100 ms of audio", AudioInputVerdict.INVALID, verdict(wav(speech(0.05, 3_000))))
        assertEquals("8-bit PCM is not what the recorders produce", AudioInputVerdict.INVALID, verdict(wav(speech(1.0, 3_000), bits = 8)))
        assertEquals(AudioInputVerdict.INVALID, verdict(wav(speech(1.0, 3_000)).copyOf(40)))
    }

    @Test
    fun `digital silence and the near-silent emulator capture of turn fa21e6a0 are silent`() {
        assertEquals(AudioInputVerdict.SILENT, verdict(wav(silence(3.0))))
        // 294,956-byte capture, peak 2: the input glitch that STT turned into a spurious URL.
        assertEquals(AudioInputVerdict.SILENT, verdict(wav(noise(9.2, peak = 2))))
        assertEquals(AudioInputVerdict.SILENT, verdict(wav(noise(4.0, peak = 150))))
    }

    @Test
    fun `steady room or fan noise with nothing louder is no speech energy`() {
        assertEquals(AudioInputVerdict.NO_SPEECH_ENERGY, verdict(wav(noise(5.0, peak = 260))))
        assertEquals(AudioInputVerdict.NO_SPEECH_ENERGY, verdict(wav(noise(5.0, peak = 1_040))))
        assertEquals("one click is not speech", AudioInputVerdict.NO_SPEECH_ENERGY,
            verdict(wav(concat(noise(2.0, 60), speech(0.1, 6_000), noise(2.0, 60)))))
    }

    @Test
    fun `normal, soft, short and margin-free utterances are all usable`() {
        val cases = mapOf(
            "normal speech with quiet margins" to concat(noise(0.5, 40), speech(3.0, 2_500), noise(0.5, 40)),
            "soft speech in a quiet room" to mix(speech(3.0, 250, seed = 3), noise(3.0, 70)),
            "soft speech in a noisy room" to mix(speech(3.0, 600, seed = 4), noise(3.0, 520)),
            "a short command" to concat(noise(1.0, 40), speech(0.3, 1_500, seed = 5), noise(1.0, 40)),
            "speech with no margins" to speech(1.0, 2_000, seed = 6),
            "a long request" to concat(noise(0.4, 40), speech(30.0, 1_800, seed = 7)),
        )
        for ((name, pcm) in cases) assertEquals(name, AudioInputVerdict.USABLE, verdict(wav(pcm)))
        assertEquals(AudioInputVerdict.USABLE, verdict(TestAudio.speechWav()))
    }

    @Test
    fun `formats the gate cannot judge are left to the dashboard`() {
        assertEquals(AudioInputVerdict.USABLE, AudioInputGate.assess(byteArrayOf(1, 2, 3), "audio/ogg"))
    }
}
