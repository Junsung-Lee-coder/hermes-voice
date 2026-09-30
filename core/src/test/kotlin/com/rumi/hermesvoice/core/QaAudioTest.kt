package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.audio.QaAudio
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QaAudioTest {
    @Test
    fun `only a plain wav name inside the qa directory resolves`() {
        val root = Files.createTempDirectory("hv-qa").toFile()
        try {
            val dir = File(root, QaAudio.DIR).apply { mkdirs() }
            val wav = File(dir, "watch-work-1.wav").apply { writeBytes(ByteArray(200)) }
            File(root, "secret.wav").writeBytes(ByteArray(200))
            File(dir, "empty.wav").writeBytes(ByteArray(10))
            assertEquals(wav.canonicalFile, QaAudio.resolve(dir, "watch-work-1.wav")?.canonicalFile)
            for (bad in listOf(null, "", "../secret.wav", "..", "Watch.wav", "a.mp3", "sub/x.wav", "missing.wav", "empty.wav")) {
                assertNull(bad, QaAudio.resolve(dir, bad))
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
