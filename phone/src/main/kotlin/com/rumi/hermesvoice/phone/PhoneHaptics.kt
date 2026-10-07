package com.rumi.hermesvoice.phone

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.rumi.hermesvoice.core.watchlink.HapticEvent
import com.rumi.hermesvoice.core.watchlink.WatchHapticPolicy

/**
 * The Phone's vibrator, for cues that must work with no screen attached (the app closed or the
 * screen off): the system Vibrator with the cue's exact waveform. On Android 13+ the cues are
 * attributed as hardware feedback (the microphone's state changed), like the Watch's. Nothing
 * bypasses Do Not Disturb or the user's vibration settings, and a device without a vibrator is silent.
 */
class PhoneHaptics(context: Context) {
    private val app = context.applicationContext

    /** A wake phrase was accepted on this Phone (one 30 ms pulse; not proof the request will succeed). */
    fun wakeAccepted() = vibrate(longArrayOf(0L, WAKE_ACCEPTED_MS), "wake_accepted")

    /** A recording start or end cue of the Phone's background listening (the screen-off twin of the app's own cue). */
    fun cue(event: HapticEvent) = vibrate(WatchHapticPolicy.patternFor(event).timings(), event.name.lowercase())

    private fun vibrate(timings: LongArray, what: String) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                app.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                app.getSystemService(Vibrator::class.java)
            } ?: return
            if (!vibrator.hasVibrator()) return
            val effect = VibrationEffect.createWaveform(timings, -1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK))
            } else {
                vibrator.vibrate(effect)
            }
            Log.i(TAG, "phone haptic $what")
        }
    }

    companion object {
        private const val TAG = "HermesVoiceWake"
        const val WAKE_ACCEPTED_MS = 30L
    }
}
