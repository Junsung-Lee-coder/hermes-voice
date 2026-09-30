package com.rumi.hermesvoice.core.net

import com.rumi.hermesvoice.core.HermesAuthRequiredException
import com.rumi.hermesvoice.core.HermesHttpException
import com.rumi.hermesvoice.core.HermesProtocolException
import com.rumi.hermesvoice.core.SpokenAudio
import com.rumi.hermesvoice.core.auth.HermesBearerSession
import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.auth.HermesTokenStore
import com.rumi.hermesvoice.core.sessions.HermesSessionsApi
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Speech half of the voice turn: the dashboard's existing audio relay routes, nothing new. */
interface HermesSpeechGateway {
    suspend fun transcribe(audio: ByteArray, mimeType: String): String
    suspend fun speak(text: String): SpokenAudio
}

/** One stored session row (`GET /api/sessions`, `GET /api/sessions/{id}`). */
data class StoredSession(
    val id: String,
    val source: String,
    val title: String,
    val preview: String,
    val startedAt: Double,
    val lastActive: Double,
    val messageCount: Int,
    val archived: Boolean,
) {
    companion object {
        fun fromJson(json: JSONObject): StoredSession = StoredSession(
            id = json.optString("id"),
            source = json.optString("source"),
            title = json.optString("title").takeUnless { json.isNull("title") }.orEmpty(),
            preview = json.optString("preview").takeUnless { json.isNull("preview") }.orEmpty(),
            startedAt = json.optDouble("started_at", 0.0).takeUnless { it.isNaN() } ?: 0.0,
            lastActive = json.optDouble("last_active", json.optDouble("started_at", 0.0)).takeUnless { it.isNaN() } ?: 0.0,
            messageCount = json.optInt("message_count"),
            archived = json.optBoolean("archived", false),
        )
    }
}

data class StoredSessionPage(val sessions: List<StoredSession>, val total: Int)

enum class ArchivedFilter(val wire: String) { EXCLUDE("exclude"), ONLY("only"), INCLUDE("include") }

/** A displayable transcript row. Tool rows and `display_kind: hidden` rows are dropped by [HistoryPage.parse]. */
data class HistoryMessage(val rowId: Long, val role: String, val text: String, val timestamp: Double)

data class HistoryPage(val sessionId: String, val messages: List<HistoryMessage>, val returned: Int) {
    companion object {
        private val VISIBLE_ROLES = setOf("user", "assistant")

        /** `GET /api/sessions/{id}/messages`: `display_content` (desktop projection) wins over raw `content`. */
        fun parse(json: JSONObject): HistoryPage {
            val rows = json.optJSONArray("messages") ?: JSONArray()
            val messages = (0 until rows.length()).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val role = row.optString("role")
                if (role !in VISIBLE_ROLES || row.optString("display_kind") == "hidden") return@mapNotNull null
                val text = contentText(if (row.has("display_content")) row.opt("display_content") else row.opt("content")).trim()
                if (text.isEmpty()) return@mapNotNull null
                HistoryMessage(row.optLong("id", -1), role, text, row.optDouble("timestamp", 0.0).takeUnless { it.isNaN() } ?: 0.0)
            }
            val pagination = json.optJSONObject("pagination")
            return HistoryPage(json.optString("session_id"), messages, pagination?.optInt("returned", rows.length()) ?: rows.length())
        }

        /** Content is a string or an OpenAI-style parts list; only text parts are shown. */
        fun contentText(content: Any?): String = when (content) {
            null, JSONObject.NULL -> ""
            is String -> content
            is JSONArray -> (0 until content.length()).mapNotNull { i ->
                when (val part = content.opt(i)) {
                    is String -> part
                    is JSONObject -> part.optString("text").takeIf { part.optString("type", "text") == "text" }
                    else -> null
                }
            }.joinToString("\n")
            is JSONObject -> content.optString("text")
            else -> content.toString()
        }
    }
}

/**
 * Client for the authenticated dashboard REST surface the Phone uses directly:
 * `/auth/native/token` + `/auth/native/refresh` (RFC 8252 bearer pair), `/api/auth/ws-ticket`,
 * `/api/audio/transcribe`, `/api/audio/speak`, and the session routes (`GET /api/sessions`,
 * `GET /api/sessions/{id}`, `GET /api/sessions/{id}/messages`, `PATCH /api/sessions/{id}`).
 * A 401 triggers one coalesced refresh; if the refresh is rejected the stored pair is cleared and
 * [HermesAuthRequiredException] is raised.
 */
class HermesDashboardClient(
    val endpoint: HermesDashboardEndpoint,
    private val http: OkHttpClient,
    private val tokens: HermesTokenStore,
    private val profile: String? = null,
) : HermesSpeechGateway, HermesSessionsApi {
    private val refreshLock = Mutex()

    suspend fun exchangeNativeCode(code: String, codeVerifier: String): HermesBearerSession = withContext(Dispatchers.IO) {
        val body = JSONObject().put("code", code).put("code_verifier", codeVerifier)
        val session = execute(request(endpoint.route("auth/native/token"), "POST", body, bearer = null)).use {
            if (it.code != 200) throw HermesHttpException(it.code, "native token exchange failed (${it.code})")
            HermesBearerSession.fromJson(it.body?.string().orEmpty())
        }
        tokens.save(session)
        session
    }

    fun signOutLocally() = tokens.clear()

    fun isSignedIn(): Boolean = tokens.load() != null

    suspend fun mintWsTicket(): String {
        val json = authorized(endpoint.route("api/auth/ws-ticket"), "POST", JSONObject(), scoped = false)
        return json.optString("ticket").ifBlank { throw HermesProtocolException("ws-ticket response has no ticket") }
    }

    override suspend fun transcribe(audio: ByteArray, mimeType: String): String {
        require(audio.isNotEmpty()) { "audio is empty" }
        require(audio.size <= MAX_TRANSCRIBE_BYTES) { "audio exceeds the dashboard's 25 MiB transcription limit" }
        val mime = mimeType.substringBefore(';').trim().lowercase()
        require(mime.startsWith("audio/")) { "transcription payload must be audio/*" }
        val dataUrl = "data:$mime;base64," + Base64.getEncoder().encodeToString(audio)
        val json = authorized(endpoint.route("api/audio/transcribe"), "POST",
            JSONObject().put("data_url", dataUrl).put("mime_type", mime))
        if (!json.optBoolean("ok", false)) throw HermesProtocolException("transcription response not ok")
        return json.optString("transcript").trim()
    }

    override suspend fun speak(text: String): SpokenAudio {
        val clean = text.trim()
        require(clean.isNotEmpty()) { "speech text is empty" }
        val json = authorized(endpoint.route("api/audio/speak"), "POST", JSONObject().put("text", clean))
        val dataUrl = json.optString("data_url")
        if (!json.optBoolean("ok", false) || !dataUrl.startsWith("data:") || !dataUrl.contains(";base64,")) {
            throw HermesProtocolException("speak response has no base64 data_url")
        }
        val header = dataUrl.substringBefore(";base64,")
        val mime = json.optString("mime_type").ifBlank { header.removePrefix("data:") }
        if (!mime.startsWith("audio/")) throw HermesProtocolException("speak response is not audio")
        val bytes = try {
            Base64.getDecoder().decode(dataUrl.substringAfter(";base64,"))
        } catch (error: IllegalArgumentException) {
            throw HermesProtocolException("speak response audio is not valid base64", error)
        }
        if (bytes.isEmpty()) throw HermesProtocolException("speak response audio is empty")
        return SpokenAudio(bytes, mime)
    }

    /** `GET /api/sessions?source=…` — the server-side source filter; `source` is categorization, not access control. */
    override suspend fun listSessions(source: String, archived: ArchivedFilter, limit: Int, offset: Int): StoredSessionPage {
        require(source.isNotBlank()) { "source filter is required" }
        val url = endpoint.route("api/sessions").newBuilder()
            .addQueryParameter("source", source)
            .addQueryParameter("archived", archived.wire)
            .addQueryParameter("order", "recent")
            .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            .addQueryParameter("offset", offset.coerceAtLeast(0).toString())
            .build()
        val json = authorized(url, "GET", null)
        val rows = json.optJSONArray("sessions") ?: JSONArray()
        val sessions = (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let(StoredSession::fromJson) }
        return StoredSessionPage(sessions, json.optInt("total", sessions.size))
    }

    /** `GET /api/sessions/{id}`; null when the dashboard has no such row (404). */
    override suspend fun getSession(sessionId: String): StoredSession? = try {
        StoredSession.fromJson(authorized(endpoint.route("api/sessions").newBuilder().addPathSegment(sessionId).build(), "GET", null))
    } catch (error: HermesHttpException) {
        if (error.status == 404) null else throw error
    }

    /** `GET /api/sessions/{id}/messages`, newest page first when [offset] is 0 (`order=latest`). */
    override suspend fun getMessages(sessionId: String, limit: Int, offset: Int): HistoryPage {
        val url = endpoint.route("api/sessions").newBuilder().addPathSegment(sessionId).addPathSegment("messages")
            .addQueryParameter("limit", limit.coerceIn(1, 500).toString())
            .addQueryParameter("offset", offset.coerceAtLeast(0).toString())
            .addQueryParameter("order", "latest")
            .build()
        return HistoryPage.parse(authorized(url, "GET", null))
    }

    /** `PATCH /api/sessions/{id}` with `{archived}`; the profile rides in the body as the route expects. */
    override suspend fun setArchived(sessionId: String, archived: Boolean) {
        val body = JSONObject().put("archived", archived)
        if (!profile.isNullOrBlank()) body.put("profile", profile)
        val json = authorized(endpoint.route("api/sessions").newBuilder().addPathSegment(sessionId).build(),
            "PATCH", body, scoped = false)
        if (!json.optBoolean("ok", false)) throw HermesProtocolException("archive update was not acknowledged")
    }

    /**
     * [Call.await] resumes on the caller's dispatcher, and reading the response body touches the
     * socket, so every request + parse runs on [Dispatchers.IO]: Android forbids network I/O on the
     * main thread (NetworkOnMainThreadException), which is where ViewModel coroutines run.
     */
    private suspend fun authorized(url: HttpUrl, method: String, body: JSONObject?, scoped: Boolean = true): JSONObject =
        withContext(Dispatchers.IO) { authorizedOnIo(url, method, body, scoped) }

    private suspend fun authorizedOnIo(url: HttpUrl, method: String, body: JSONObject?, scoped: Boolean): JSONObject {
        val target = if (scoped && !profile.isNullOrBlank()) {
            url.newBuilder().addQueryParameter("profile", profile).build()
        } else {
            url
        }
        var session = tokens.load() ?: throw HermesAuthRequiredException("Hermes dashboard sign-in required")
        repeat(2) { attempt ->
            execute(request(target, method, body, session.accessToken)).use { response ->
                when {
                    response.code == 401 && attempt == 0 -> session = refreshAfterRejection(session)
                    response.code == 401 -> throw HermesAuthRequiredException("Hermes dashboard rejected the refreshed session")
                    response.code !in 200..299 -> throw HermesHttpException(response.code, describeFailure(url, response))
                    else -> return parseObject(response)
                }
            }
        }
        throw HermesAuthRequiredException("Hermes dashboard sign-in required")
    }

    /** Coalesced: callers that saw the same rejected access token share one refresh round trip. */
    private suspend fun refreshAfterRejection(rejected: HermesBearerSession): HermesBearerSession = refreshLock.withLock {
        val current = tokens.load() ?: throw HermesAuthRequiredException("Hermes dashboard sign-in required")
        if (current.accessToken != rejected.accessToken) return current
        if (current.refreshToken.isBlank()) {
            tokens.clear()
            throw HermesAuthRequiredException("Hermes dashboard session expired")
        }
        val body = JSONObject().put("refresh_token", current.refreshToken).put("provider", current.provider)
        execute(request(endpoint.route("auth/native/refresh"), "POST", body, bearer = null)).use { response ->
            when (response.code) {
                200 -> HermesBearerSession.fromJson(response.body?.string().orEmpty()).also(tokens::save)
                400, 401 -> {
                    tokens.clear()
                    throw HermesAuthRequiredException("Hermes dashboard session expired; sign in again")
                }
                else -> throw HermesHttpException(response.code, "session refresh failed (${response.code})")
            }
        }
    }

    private fun request(url: HttpUrl, method: String, body: JSONObject?, bearer: String?): Request = Request.Builder()
        .url(url)
        .method(method, body?.toString()?.toRequestBody(JSON))
        .header("Accept", "application/json")
        .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
        .build()

    private fun parseObject(response: Response): JSONObject = try {
        JSONObject(response.body?.string().orEmpty())
    } catch (error: JSONException) {
        throw HermesProtocolException("dashboard returned non-JSON for ${response.request.url.encodedPath}", error)
    }

    private fun describeFailure(url: HttpUrl, response: Response): String {
        val detail = runCatching { JSONObject(response.body?.string().orEmpty()).optString("detail") }.getOrNull()
        return "${url.encodedPath} failed (${response.code})" + if (detail.isNullOrBlank()) "" else ": ${detail.take(200)}"
    }

    private suspend fun execute(request: Request): Response = http.newCall(request).await()

    companion object {
        const val MAX_TRANSCRIBE_BYTES = 25 * 1024 * 1024
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
    })
}
