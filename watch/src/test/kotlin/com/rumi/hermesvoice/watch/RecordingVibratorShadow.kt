package com.rumi.hermesvoice.watch

import android.os.VibrationAttributes
import java.util.Collections
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSystemVibrator

/**
 * SCRATCH HARNESS: Robolectric's system vibrator, recording EVERY vibration (duration, usage) in order instead of only
 * the last one. It records what the app asked the platform for; it does not apply DND or OS vibration policy.
 */
@Implements(className = "android.os.SystemVibrator", isInAndroidSdk = false)
class RecordingVibratorShadow : ShadowSystemVibrator() {
    @Implementation(minSdk = 33)
    override fun vibrate(uid: Int, opPkg: String?, effect: Any?, reason: String?, attributes: Any?) {
        super.vibrate(uid, opPkg, effect, reason, attributes)
        events += milliseconds to (attributes as? VibrationAttributes)?.usage
    }

    companion object {
        val events: MutableList<Pair<Long, Int?>> = Collections.synchronizedList(mutableListOf())
    }
}
