package com.rumi.hermesvoice.core.headset

/**
 * Tells [onChange] whether the headset is the private output (the setting is on AND a personal headset output is connected), at
 * every [refresh] while nothing was reported and then on each change. It holds the platform device callback only while the setting
 * is on and is released by [close]: no polling, no service.
 */
class PrivateAudioMonitor(private val policy: HeadsetPolicy, private val onChange: (Boolean) -> Unit) : AutoCloseable {
    private val lock = Any()
    private var watch: AutoCloseable? = null
    private var last: Boolean? = null
    private var closed = false

    /** Applies the setting as it is now (start or stop watching the devices) and reports the private state if it changed. */
    fun refresh() {
        val release: AutoCloseable?
        synchronized(lock) {
            if (closed) return
            release = if (!policy.enabled) watch.also { watch = null } else null
            if (policy.enabled && watch == null) watch = policy.watch(::evaluate)
        }
        release?.close()
        evaluate()
    }

    private fun evaluate() {
        val now = policy.output() != null
        synchronized(lock) {
            if (closed || last == now) return
            last = now
        }
        onChange(now)
    }

    override fun close() {
        val release = synchronized(lock) {
            closed = true
            watch.also { watch = null }
        }
        release?.close()
    }
}
