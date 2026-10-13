package com.rumi.hermesvoice.phone

import java.lang.reflect.Proxy

/** Synthetic admitted/denied/pending FGS leaf; never claims actual Android WIU eligibility. Reflection keeps R8 witness compilable. */
object R9TestAdmission {
    var admitted: Boolean? = true
    var pending: ((Boolean) -> Unit)? = null
    fun install(app: PhoneApp) {
        admitted = true
        pending = null
        val type = runCatching { Class.forName("com.rumi.hermesvoice.phone.ManualAdmission") }.getOrNull() ?: return
        val fake = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            if (method.name == "request") {
                val generation = args!![0] as Long
                @Suppress("UNCHECKED_CAST") val done = args[1] as (Long, Boolean) -> Unit
                val complete: (Boolean) -> Unit = { done(generation, it) }
                pending = complete
                admitted?.let(complete)
                AutoCloseable { }
            } else null
        }
        val field = PhoneApp::class.java.getDeclaredField("manualAdmission\$delegate")
        field.isAccessible = true
        field.set(app, lazyOf(fake))
    }
}
