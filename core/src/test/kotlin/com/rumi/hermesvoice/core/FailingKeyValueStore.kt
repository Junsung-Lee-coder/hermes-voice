package com.rumi.hermesvoice.core

/**
 * A preferences file that can stop accepting writes (full disk, revoked storage). While [failing] every write to a key that
 * [affects] is refused and NOT kept even for this process's reads (the worst case: a new process sees only [persisted]), and
 * [commitString] reports false. Everything else behaves like [InMemoryKeyValueStore].
 */
class FailingKeyValueStore(private val affects: (String) -> Boolean = { true }) : KeyValueStore {
    private val inner = InMemoryKeyValueStore()

    @Volatile var failing = false
    @Volatile var commitAttempts = 0
    private val refused: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** The keys whose write was refused, in order. */
    fun refusedKeys(): List<String> = synchronized(refused) { refused.toList() }

    /** What a process started now would read. */
    fun persisted(key: String): String? = inner.getString(key)

    private fun blocked(key: String): Boolean = failing && affects(key).also { if (it) refused += key }

    override fun getString(key: String): String? = inner.getString(key)
    override fun putString(key: String, value: String) { if (!blocked(key)) inner.putString(key, value) }
    override fun commitString(key: String, value: String): Boolean {
        commitAttempts += 1
        if (blocked(key)) return false
        return inner.commitString(key, value)
    }
    override fun getBoolean(key: String, default: Boolean): Boolean = inner.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) { if (!blocked(key)) inner.putBoolean(key, value) }
    override fun getInt(key: String, default: Int): Int = inner.getInt(key, default)
    override fun putInt(key: String, value: Int) { if (!blocked(key)) inner.putInt(key, value) }
}
