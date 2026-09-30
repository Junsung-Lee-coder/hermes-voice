package com.rumi.hermesvoice.core.auth

import com.rumi.hermesvoice.core.HermesProtocolException
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/**
 * The per-user bearer pair the dashboard mints at `/auth/native/token`. Obtained at runtime only;
 * persisted by a [HermesTokenStore] (Keystore-encrypted on Android), never compiled into the APK.
 */
data class HermesBearerSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long?,
    val provider: String,
    val userId: String,
) {
    override fun toString(): String = "HermesBearerSession(provider=$provider, userId=$userId, expiresAt=$expiresAt)"

    fun toJson(): String = JSONObject().put("access_token", accessToken).put("refresh_token", refreshToken)
        .put("expires_at", expiresAt ?: JSONObject.NULL).put("provider", provider).put("user_id", userId).toString()

    companion object {
        /** Parses `_bearer_payload` (`/auth/native/token` and `/auth/native/refresh`). */
        fun fromJson(raw: String): HermesBearerSession {
            val json = JSONObject(raw)
            val access = json.optString("access_token")
            val refresh = json.optString("refresh_token")
            if (access.isBlank()) throw HermesProtocolException("token response is missing access_token")
            if (json.optString("token_type", "Bearer") != "Bearer") throw HermesProtocolException("unsupported token_type")
            return HermesBearerSession(
                accessToken = access,
                refreshToken = refresh,
                expiresAt = if (json.isNull("expires_at")) null else json.optLong("expires_at"),
                provider = json.optString("provider"),
                userId = json.optString("user_id"),
            )
        }
    }
}

interface HermesTokenStore {
    fun load(): HermesBearerSession?
    fun save(session: HermesBearerSession)
    fun clear()
}

class InMemoryHermesTokenStore(private var session: HermesBearerSession? = null) : HermesTokenStore {
    @Synchronized override fun load(): HermesBearerSession? = session
    @Synchronized override fun save(session: HermesBearerSession) { this.session = session }
    @Synchronized override fun clear() { session = null }
}

/** RFC 7636 S256 pair; the verifier never leaves the Phone except in the `/auth/native/token` body. */
class PkcePair private constructor(val verifier: String, val challenge: String) {
    override fun toString(): String = "PkcePair(challenge=$challenge)"

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): PkcePair {
            val bytes = ByteArray(48).also(random::nextBytes)
            val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            return PkcePair(verifier, challengeFor(verifier))
        }

        fun challengeFor(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
    }
}

/**
 * The dashboard base URL. Bearer tokens only travel over HTTPS, or plain HTTP to a loopback
 * address or a Tailscale (100.64.0.0/10) address whose WireGuard tunnel already encrypts it.
 */
class HermesDashboardEndpoint private constructor(val baseUrl: HttpUrl) {
    fun route(path: String): HttpUrl = baseUrl.newBuilder().apply {
        path.trim('/').split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) }
    }.build()

    override fun toString(): String = baseUrl.toString()

    companion object {
        fun parse(raw: String): HermesDashboardEndpoint {
            val url = raw.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("dashboard URL is not a valid http(s) URL")
            require(url.username.isEmpty() && url.password.isEmpty()) { "dashboard URL must not embed credentials" }
            require(url.query == null && url.fragment == null) { "dashboard URL must not carry a query or fragment" }
            require(url.isHttps || isEncryptedTunnelOrLoopback(url.host)) {
                "dashboard URL must use https (http is allowed only for loopback or Tailscale 100.64.0.0/10 hosts)"
            }
            // Drop a trailing empty segment so route() never produces "//".
            val segments = url.pathSegments.filter { it.isNotEmpty() }
            val base = url.newBuilder().encodedPath("/").apply { segments.forEach { addPathSegment(it) } }.build()
            return HermesDashboardEndpoint(base)
        }

        private fun isEncryptedTunnelOrLoopback(host: String): Boolean {
            if (host == "127.0.0.1" || host == "::1" || host == "localhost") return true
            val parts = host.split('.').mapNotNull { it.toIntOrNull() }
            return parts.size == 4 && host.count { it == '.' } == 3 && parts[0] == 100 && parts[1] in 64..127
        }
    }
}

object HermesNativeSignIn {
    const val CALLBACK_PATH = "/hermes-native-callback"

    /** `GET /auth/native/authorize`; the browser (never the app) collects any password or IdP login. */
    fun authorizeUrl(
        endpoint: HermesDashboardEndpoint,
        pkce: PkcePair,
        redirectUri: String,
        state: String,
        provider: String? = null,
    ): HttpUrl {
        requireLoopbackRedirect(redirectUri)
        return endpoint.route("auth/native/authorize").newBuilder()
            .addQueryParameter("code_challenge", pkce.challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("state", state)
            .apply { if (!provider.isNullOrBlank()) addQueryParameter("provider", provider) }
            .build()
    }

    /** Mirrors the gateway's `_validate_loopback_redirect_uri`: http on a loopback IP literal only. */
    fun requireLoopbackRedirect(redirectUri: String) {
        val url = redirectUri.toHttpUrlOrNull() ?: throw IllegalArgumentException("redirect_uri is not a URL")
        require(url.scheme == "http") { "native redirect_uri must be http:// on the loopback interface" }
        require(url.host == "127.0.0.1" || url.host == "::1") { "native redirect_uri host must be 127.0.0.1 or ::1" }
    }

    fun newState(random: SecureRandom = SecureRandom()): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(random::nextBytes))
}

sealed class LoopbackCallbackResult {
    data class Code(val code: String) : LoopbackCallbackResult() {
        override fun toString(): String = "Code(<redacted>)"
    }
    data class Failed(val reason: String) : LoopbackCallbackResult()
}

object LoopbackCallbackParser {
    /** Parses the request target of the loopback redirect (`/path?code=…&state=…`). */
    fun parse(requestTarget: String, expectedPath: String, expectedState: String): LoopbackCallbackResult? {
        val path = requestTarget.substringBefore('?')
        if (path != expectedPath) return null
        val query = requestTarget.substringAfter('?', "")
        val params = query.split('&').filter { it.contains('=') }.associate { pair ->
            val (key, value) = pair.split('=', limit = 2)
            decode(key) to decode(value)
        }
        params["error"]?.let { return LoopbackCallbackResult.Failed("authorization_error") }
        val state = params["state"].orEmpty()
        if (state.isEmpty() || !MessageDigest.isEqual(state.toByteArray(), expectedState.toByteArray())) {
            return LoopbackCallbackResult.Failed("state_mismatch")
        }
        val code = params["code"].orEmpty()
        if (code.isEmpty()) return LoopbackCallbackResult.Failed("code_missing")
        return LoopbackCallbackResult.Code(code)
    }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}

/**
 * One-shot RFC 8252 §7.3 loopback receiver bound to 127.0.0.1 on an ephemeral port. It answers
 * unrelated requests (favicon, probes) with 404 and keeps waiting until the callback path arrives
 * or [awaitCallback]'s deadline passes. Blocking; call it off the main thread.
 */
class LoopbackCallbackServer(private val path: String = HermesNativeSignIn.CALLBACK_PATH) : Closeable {
    private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))

    val redirectUri: String get() = "http://127.0.0.1:${socket.localPort}$path"

    fun awaitCallback(expectedState: String, timeoutMs: Long): LoopbackCallbackResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return LoopbackCallbackResult.Failed("timeout")
            socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val client = try {
                socket.accept()
            } catch (_: SocketTimeoutException) {
                return LoopbackCallbackResult.Failed("timeout")
            }
            client.use { connection ->
                connection.soTimeout = 5_000
                val requestLine = readLine(connection.getInputStream()) ?: ""
                val parts = requestLine.split(' ')
                val result = if (parts.size >= 2 && parts[0] == "GET") {
                    LoopbackCallbackParser.parse(parts[1], path, expectedState)
                } else {
                    null
                }
                val (status, body) = when (result) {
                    null -> "404 Not Found" to "Not found."
                    is LoopbackCallbackResult.Code -> "200 OK" to "Signed in. You can return to Hermes Voice."
                    is LoopbackCallbackResult.Failed -> "400 Bad Request" to "Sign-in failed. Return to Hermes Voice and retry."
                }
                val html = "<!doctype html><meta charset=utf-8><title>Hermes Voice</title><p>$body</p>"
                val bytes = html.toByteArray(StandardCharsets.UTF_8)
                connection.getOutputStream().apply {
                    write(("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
                    write(bytes)
                    flush()
                }
                if (result != null) return result
            }
        }
    }

    override fun close() = socket.close()

    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (buffer.length < 8192) {
            val next = input.read()
            if (next < 0) return buffer.toString().ifEmpty { null }
            if (next == '\n'.code) return buffer.toString().trimEnd('\r')
            buffer.append(next.toChar())
        }
        return buffer.toString()
    }
}
