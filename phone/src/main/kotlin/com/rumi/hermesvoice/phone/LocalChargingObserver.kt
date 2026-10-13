package com.rumi.hermesvoice.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler

/** Local event-only power observation. Stop invalidates queued events before unregistering only this receiver. */
internal class LocalChargingObserver(private val context: Context, private val handler: Handler, private val changed: (Boolean) -> Unit) {
    private var registered = false
    private var epoch = 0L
    private var sequence = 0L
    private var current = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED || !registered) return
            val token = epoch
            val order = ++sequence
            val charging = condition(intent)
            handler.post { if (registered && token == epoch && order == sequence) apply(charging) }
        }
    }

    fun start() {
        if (registered) return
        epoch++
        registered = true
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(receiver, filter)
        // Initial sticky state is adopted before a window can open, not deferred to a later broadcast.
        ++sequence
        apply(condition(sticky))
    }

    fun stop() {
        epoch++
        sequence++
        if (registered) context.unregisterReceiver(receiver)
        registered = false
        current = false
    }

    private fun apply(charging: Boolean) {
        if (current == charging) return
        current = charging
        changed(charging)
    }

    private fun condition(intent: Intent?): Boolean {
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        return plugged > 0 && (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
    }
}
