package com.rumi.hermesvoice.core.audio

import java.io.File

/**
 * Emulator QA input for DEBUGGABLE builds only: a WAV placed in the app's private `files/qa/`
 * directory (via `adb push` + `run-as`) can stand in for a microphone recording, because emulator
 * microphones on a headless host carry no speech. The audio then takes the normal voice path.
 * Callers must check that the app is debuggable; only a plain `<name>.wav` inside [dir] resolves.
 */
object QaAudio {
    const val EXTRA = "hv_qa_wav"
    const val DIR = "qa"
    private val NAME = Regex("^[a-z0-9-]{1,40}\\.wav$")
    private const val MAX_BYTES = 10 * 1024 * 1024

    fun resolve(dir: File, name: String?): File? {
        if (name == null || !NAME.matches(name)) return null
        val file = File(dir, name)
        if (file.canonicalFile.parentFile != dir.canonicalFile) return null
        return file.takeIf { it.isFile && it.length() in 45..MAX_BYTES.toLong() }
    }
}

/**
 * The debug QA extra is honoured only for a fresh launch intent: never when the activity is
 * re-created from saved state (rotation, process restore) or relaunched from Recents, both of
 * which replay the original intent, and never twice for the same intent.
 */
object QaLaunchGuard {
    fun shouldHandle(restoredFromSavedState: Boolean, launchedFromHistory: Boolean, alreadyHandled: Boolean): Boolean =
        !restoredFromSavedState && !launchedFromHistory && !alreadyHandled
}
