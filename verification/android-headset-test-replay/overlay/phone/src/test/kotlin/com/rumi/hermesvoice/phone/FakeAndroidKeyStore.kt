package com.rumi.hermesvoice.phone

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

/**
 * SCRATCH HARNESS (never packaged): the host JVM has no "AndroidKeyStore", so the production [KeystoreTokenStore] is exercised against
 * an in-memory AES key provider of the same name. It proves the app's sign-in/token handling runs; it is not the hardware keystore.
 */
object FakeAndroidKeyStore {
    private val keys = ConcurrentHashMap<String, SecretKey>()

    class Store : KeyStoreSpi() {
        override fun engineGetKey(alias: String?, password: CharArray?): Key? = alias?.let { keys[it] }
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCreationDate(alias: String?): Date? = null
        override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) {}
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) {}
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) {}
        override fun engineDeleteEntry(alias: String?) { alias?.let { keys.remove(it) } }
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())
        override fun engineContainsAlias(alias: String?): Boolean = alias != null && keys.containsKey(alias)
        override fun engineSize(): Int = keys.size
        override fun engineIsKeyEntry(alias: String?): Boolean = alias != null && keys.containsKey(alias)
        override fun engineIsCertificateEntry(alias: String?): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) {}
        override fun engineLoad(stream: InputStream?, password: CharArray?) {}
    }

    class Generator : KeyGeneratorSpi() {
        private var alias = ""
        override fun engineInit(random: SecureRandom?) { throw UnsupportedOperationException("a key spec is required") }
        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            alias = (params as KeyGenParameterSpec).keystoreAlias
        }
        override fun engineInit(keysize: Int, random: SecureRandom?) { throw UnsupportedOperationException("a key spec is required") }
        override fun engineGenerateKey(): SecretKey {
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            keys[alias] = key
            return key
        }
    }

    fun install() {
        Security.removeProvider("AndroidKeyStore")
        Security.addProvider(object : Provider("AndroidKeyStore", 1.0, "scratch harness keystore") {
            init {
                put("KeyStore.AndroidKeyStore", Store::class.java.name)
                put("KeyGenerator.AES", Generator::class.java.name)
            }
        })
    }
}
