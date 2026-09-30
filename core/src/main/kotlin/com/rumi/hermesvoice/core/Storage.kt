package com.rumi.hermesvoice.core

/** Minimal persistence seam; Android backs it with a private SharedPreferences file. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
}

class InMemoryKeyValueStore : KeyValueStore {
    private val values = HashMap<String, Any>()
    @Synchronized override fun getString(key: String): String? = values[key] as? String
    @Synchronized override fun putString(key: String, value: String) { values[key] = value }
    @Synchronized override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default
    @Synchronized override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    @Synchronized override fun getInt(key: String, default: Int): Int = values[key] as? Int ?: default
    @Synchronized override fun putInt(key: String, value: Int) { values[key] = value }
}
