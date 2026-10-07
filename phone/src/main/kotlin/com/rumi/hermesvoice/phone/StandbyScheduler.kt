package com.rumi.hermesvoice.phone

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * The one platform alarm behind the hidden Phone session's gap between two wake windows. The gap holds no CPU wake lock, and a
 * handler alone is not delivered while the CPU sleeps with the screen off, so each gap is also scheduled here: one
 * non-exact [AlarmManager.setAndAllowWhileIdle] alarm on the elapsed-realtime clock. It needs no exact-alarm permission,
 * is delivered late (and, in Doze, at most about once per several minutes: a lower duty cycle, by design), and there is
 * never more than one: a new gap replaces the old alarm, and [cancel] leaves nothing scheduled.
 */
class StandbyScheduler(private val context: Context) {
    private val alarms: AlarmManager = context.getSystemService(AlarmManager::class.java)

    /** An alarm is believed to be scheduled by this process (so a cancel is only sent when there is something to cancel). */
    private var scheduled = false

    private fun operation(): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE,
        Intent(context, StandbyAlarmReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(delayMs: Long) {
        scheduled = true
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, operation())
    }

    /** Cancels the alarm; [force] also cancels one an earlier process of this app may have left (process start). */
    fun cancel(force: Boolean = false) {
        if (!scheduled && !force) return
        scheduled = false
        alarms.cancel(operation())
    }

    private companion object {
        const val REQUEST_CODE = 0x57A1
        const val ACTION = "com.rumi.hermesvoice.phone.STANDBY_ALARM"
    }
}

/** Receives the standby alarm on the main thread and hands it to the background runtime, which runs the due gap at most once. */
class StandbyAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as? PhoneApp)?.phoneWake?.onRearmAlarm()
    }
}
