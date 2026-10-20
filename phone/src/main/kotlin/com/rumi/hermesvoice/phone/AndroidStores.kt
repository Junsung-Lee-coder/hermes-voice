package com.rumi.hermesvoice.phone

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.rumi.hermesvoice.core.KeyValueStore
import com.rumi.hermesvoice.core.auth.HermesBearerSession
import com.rumi.hermesvoice.core.auth.HermesTokenStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SharedPreferencesKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }

    /** `SharedPreferences.commit()`: synchronous, and its result says whether the write reached disk. */
    override fun commitString(key: String, value: String): Boolean = prefs.edit().putString(key, value).commit()
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
}

/** Reads nothing and refuses every durable write while [blocked] is true (a clear of the unplayed-reply cache is owed and has not succeeded yet); [source] is looked up at each call. */
class FencedKeyValueStore(private val source: () -> KeyValueStore, private val blocked: () -> Boolean) : KeyValueStore {
    override fun getString(key: String): String? = if (blocked()) null else source().getString(key)
    override fun putString(key: String, value: String) { if (!blocked()) source().putString(key, value) }
    override fun commitString(key: String, value: String): Boolean = if (blocked()) false else source().commitString(key, value)
    override fun getBoolean(key: String, default: Boolean): Boolean = source().getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) { source().putBoolean(key, value) }
    override fun getInt(key: String, default: Int): Int = source().getInt(key, default)
    override fun putInt(key: String, value: Int) { source().putInt(key, value) }
}

/**
 * The dashboard bearer pair, AES-256-GCM encrypted under a non-exportable Android Keystore key.
 * The prefs file is excluded from backup and device transfer (res/xml backup rules): a restored
 * copy could not be decrypted anyway, and a session must never migrate to another device.
 */
class KeystoreTokenStore(context: Context) : HermesTokenStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun load(): HermesBearerSession? {
        val blob = prefs.getString(KEY_SESSION, null) ?: return null
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_BYTES))
            HermesBearerSession.fromJson(String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8))
        } catch (_: Exception) {
            // Undecryptable (key invalidated, restored file, corruption): fail closed to "signed out".
            prefs.edit().remove(KEY_SESSION).commit()
            null
        }
    }

    @Synchronized
    override fun save(session: HermesBearerSession) {
        persist(session, false)
    }

    /** Explicit native login, unlike an ordinary bearer refresh, starts new private authority. */
    @Synchronized
    fun saveLogin(session: HermesBearerSession) {
        persist(session, true)
    }

    private fun persist(session: HermesBearerSession, freshLogin: Boolean) {
        val old = load()
        val epoch = if (!freshLogin && old?.provider == session.provider && old.userId == session.userId)
            prefs.getString("display_generation_v1", null) ?: "legacy"
            else java.util.UUID.randomUUID().toString()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(session.toJson().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(KEY_SESSION, Base64.encodeToString(sealed, Base64.NO_WRAP)).putString("display_generation_v1", epoch).commit()) {
            "could not persist the Hermes session"
        }
    }

    @Synchronized
    override fun clear() {
        // Same-user re-login is a new display namespace even if deleting body snapshots failed.
        prefs.edit().remove(KEY_SESSION).putString("display_generation_v1", java.util.UUID.randomUUID().toString()).commit()
    }

    @Synchronized fun displayGeneration(): String = prefs.getString("display_generation_v1", "legacy") ?: "legacy"

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    companion object {
        const val PREFERENCES_NAME = "hermes_voice_secure"
        private const val KEY_SESSION = "bearer_session_v1"
        private const val KEY_ALIAS = "hermes_voice_bearer_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}
