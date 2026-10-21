package com.rumi.hermesvoice.core.net

import com.rumi.hermesvoice.core.HermesAuthRequiredException
import com.rumi.hermesvoice.core.HermesHttpException
import com.rumi.hermesvoice.core.HermesProtocolException
import com.rumi.hermesvoice.core.SpokenAudio
import com.rumi.hermesvoice.core.auth.HermesBearerSession
import com.rumi.hermesvoice.core.auth.HermesDashboardEndpoint
import com.rumi.hermesvoice.core.auth.HermesTokenStore
import com.rumi.hermesvoice.core.sessions.HermesSessionsApi
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
import okhttp3.ResponseBody.Companion.toResponseBody
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
    /** `sessions.created_source`: stamped once when the row is created; null when the server has no value (or no such field). */
    val createdSource: String? = null,
    val parentSessionId: String? = null,
    val endReason: String? = null,
    val endedAt: Double? = null,
    /** On a list row that is a compression chain projected onto its tip: the chain's root id, all ids root-first, and the kind. */
    val lineageRootId: String? = null,
    val lineageIds: List<String> = emptyList(),
    val continuationKind: String? = null,
) {
    companion object {
        private fun text(json: JSONObject, key: String): String? =
            if (json.isNull(key)) null else json.optString(key).trim().takeIf { it.isNotEmpty() }

        fun fromJson(json: JSONObject): StoredSession = StoredSession(
            id = json.optString("id"),
            source = json.optString("source"),
            title = json.optString("title").takeUnless { json.isNull("title") }.orEmpty(),
            preview = json.optString("preview").takeUnless { json.isNull("preview") }.orEmpty(),
            startedAt = json.optDouble("started_at", 0.0).takeUnless { it.isNaN() } ?: 0.0,
            lastActive = json.optDouble("last_active", json.optDouble("started_at", 0.0)).takeUnless { it.isNaN() } ?: 0.0,
            messageCount = json.optInt("message_count"),
            archived = json.optBoolean("archived", false),
            createdSource = text(json, "created_source"),
            parentSessionId = text(json, "parent_session_id"),
            endReason = text(json, "end_reason"),
            endedAt = if (json.isNull("ended_at")) null else json.optDouble("ended_at").takeUnless { it.isNaN() },
            lineageRootId = text(json, "_lineage_root_id"),
            lineageIds = json.optJSONArray("_lineage_ids")?.let { ids -> (0 until ids.length()).mapNotNull { ids.optString(it).takeIf(String::isNotEmpty) } }.orEmpty(),
            continuationKind = text(json, "continuation_kind"),
        )
    }
}

data class StoredSessionPage(val sessions: List<StoredSession>, val total: Int)

enum class ArchivedFilter(val wire: String) { EXCLUDE("exclude"), ONLY("only"), INCLUDE("include") }

/** A displayable transcript row. Tool rows, `display_kind: hidden` rows and the server's typed internal rows are dropped by [HistoryPage.parse]. */
data class HistoryMessage(val rowId: Long, val role: String, val text: String, val timestamp: Double, val truncated: Boolean = false,
    val messageUid: String = "", val finishReason: String? = null, val hasToolCalls: Boolean = false) {
    /** An assistant row that is exactly the gateway's silence marker is a control, not a message. */
    val silentControl: Boolean get() = role == "assistant" && !hasToolCalls && SilentControl.isWholeResponse(text)
    /** Explicit stop only. Length/tool/verification continuations and unknown legacy finality are not finals. */
    val final: Boolean get() = role == "assistant" && finishReason == "stop" && !hasToolCalls && text.isNotBlank() && !silentControl
    /** Visible length-limited content is a receipt, not an authoritative final. */
    val received: Boolean get() = role == "assistant" && !hasToolCalls && text.isNotBlank() && !silentControl &&
        (finishReason == null || finishReason == "stop" || finishReason == "length")
}

/** [rawIds] are the ids of every row the server returned, shown or not, in the server's order; [messages] keeps the shown ones only. */
data class HistoryPage(val sessionId: String, val messages: List<HistoryMessage>, val returned: Int, val rawIds: List<Long> = emptyList()) {
    companion object {
        private val VISIBLE_ROLES = setOf("user", "assistant")

        /** Rows the server stamps as its own synthetic input or delivery (background-process completions, delegation deliveries): the model reads them, the transcript does not show them. */
        private val INTERNAL_DISPLAY_KINDS = setOf("hidden", "internal_notification", "async_delegation_complete", "process_complete")

        /** `GET /api/sessions/{id}/messages`: `display_content` (desktop projection) wins over raw `content`. */
        fun parse(json: JSONObject): HistoryPage {
            val rows = json.optJSONArray("messages") ?: JSONArray()
            val messages = (0 until rows.length()).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val role = row.optString("role")
                if (role !in VISIBLE_ROLES || row.optString("display_kind") in INTERNAL_DISPLAY_KINDS) return@mapNotNull null
                val text = contentText(if (row.has("display_content")) row.opt("display_content") else row.opt("content")).trim()
                if (text.isEmpty()) return@mapNotNull null
                if (role == "assistant" && row.optString("display_kind").isEmpty() && row.optJSONArray("tool_calls").let { it == null || it.length() == 0 } &&
                    SilentControl.isWholeResponse(text)) return@mapNotNull null
                HistoryMessage(row.optLong("id", -1), role, text, row.optDouble("timestamp", 0.0).takeIf { it.isFinite() } ?: 0.0,
                    messageUid = row.optString("message_uid").takeUnless { it == "null" }.orEmpty(),
                    finishReason = row.optString("finish_reason").takeUnless { it.isBlank() || it == "null" },
                    hasToolCalls = row.optJSONArray("tool_calls")?.length()?.let { it > 0 } == true)
            }
            val pagination = json.optJSONObject("pagination")
            val rawIds = (0 until rows.length()).mapNotNull { index -> rows.optJSONObject(index)?.takeIf { it.has("id") }?.optLong("id") }
            return HistoryPage(json.optString("session_id"), messages, pagination?.optInt("returned", rows.length()) ?: rows.length(), rawIds)
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
) : HermesSpeechGateway, HermesSessionsApi, StoredFileSource {
    private val refreshLock = Mutex()

    suspend fun exchangeNativeCode(code: String, codeVerifier: String, persist: Boolean = true): HermesBearerSession = withContext(Dispatchers.IO) {
        val body = JSONObject().put("code", code).put("code_verifier", codeVerifier)
        val session = execute(request(endpoint.route("auth/native/token"), "POST", body, bearer = null)).use {
            if (it.code != 200) throw HermesHttpException(it.code, "native token exchange failed (${it.code})")
            HermesBearerSession.fromJson(it.body?.string().orEmpty())
        }
        if (persist) tokens.save(session)
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
            JSONObject().put("data_url", dataUrl).put("mime_type", mime), sttBoundary = true)
        if (!json.optBoolean("ok", false)) throw HermesProtocolException("transcription response not ok")
        return json.optString("transcript").trim()
    }

    override suspend fun speak(text: String): SpokenAudio {
        val clean = text.trim()
        require(clean.isNotEmpty()) { "speech text is empty" }
        val json = authorized(endpoint.route("api/audio/speak"), "POST", JSONObject().put("text", clean), synthesis = true)
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

    /** `GET /api/sessions`, optionally with the server's `source` / `exclude_sources` filters; `source` is a routing label, not access control. */
    override suspend fun listSessions(source: String?, archived: ArchivedFilter, limit: Int, offset: Int, excludeSources: List<String>): StoredSessionPage {
        require(source == null || source.isNotBlank()) { "source filter must not be blank" }
        val url = endpoint.route("api/sessions").newBuilder()
            .apply {
                if (source != null) addQueryParameter("source", source)
                if (excludeSources.isNotEmpty()) addQueryParameter("exclude_sources", excludeSources.joinToString(","))
            }
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
    override suspend fun getMessages(sessionId: String, limit: Int, offset: Int): HistoryPage = historyPage(sessionId,limit,offset,false)
    override suspend fun getReconciliationMessages(sessionId:String,limit:Int,offset:Int):HistoryPage = historyPage(sessionId,limit,offset,true)
    private suspend fun historyPage(sessionId:String,limit:Int,offset:Int,reconciliation:Boolean):HistoryPage {
        val url = endpoint.route("api/sessions").newBuilder().addPathSegment(sessionId).addPathSegment("messages")
            .addQueryParameter("limit", limit.coerceIn(1, 500).toString())
            .addQueryParameter("offset", offset.coerceAtLeast(0).toString())
            .addQueryParameter("order", "latest")
            .apply { if(reconciliation) addQueryParameter("hv_read","reconciliation") }
            .build()
        return HistoryPage.parse(authorized(url, "GET", null))
    }

    /**
     * A stored file named in a message, through the dashboard's existing authenticated routes (bearer header only; the path is
     * the sole query value): images from `GET /api/media?path=`, anything else (or an image that route refuses) from
     * `GET /api/files/read?path=`. Both answer JSON with a base64 `data_url`; the body and the decoded bytes are bounded by
     * [StoredFileSource.MAX_BYTES]. The dashboard decides what it will serve; a refusal is reported, never worked around.
     * A file above that bound is a [StoredFileTooLargeException] carrying the limit of the route that refused it, which
     * [downloadStoredFile] can then receive.
     */
    override suspend fun fetchStoredFile(ref: com.rumi.hermesvoice.core.attachments.AttachmentRef): StoredFile {
        val image = ref.kind == com.rumi.hermesvoice.core.attachments.RefKind.IMAGE ||
            ref.name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp")
        val first = if (image) "api/media" else "api/files/read"
        return try {
            readStoredFile(first, ref)
        } catch (error: HermesHttpException) {
            if (!image || error.status !in setOf(400, 403, 404, 415)) throw error
            readStoredFile("api/files/read", ref)
        }
    }

    private suspend fun readStoredFile(route: String, ref: com.rumi.hermesvoice.core.attachments.AttachmentRef): StoredFile {
        val url = endpoint.route(route).newBuilder().addQueryParameter("path", ref.path).build()
        val json = try {
            authorized(url, "GET", null, scoped = false, maxBytes = StoredFileSource.MAX_BODY_BYTES)
        } catch (error: HermesHttpException) {
            if (error.status != 413) throw error
            throw if (route == "api/media") previewTooLarge() else downloadTooLarge()
        }
        val dataUrl = json.optString("data_url")
        if (!dataUrl.startsWith("data:") || !dataUrl.contains(";base64,")) throw HermesProtocolException("the file response has no base64 data")
        val encoded = dataUrl.substringAfter(";base64,")
        if (encoded.length > (StoredFileSource.MAX_BYTES.toLong() * 4 / 3 + 8)) throw previewTooLarge()
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw HermesProtocolException("the file response is not valid base64", error)
        }
        if (bytes.isEmpty()) throw HermesProtocolException("the file is empty")
        val mime = json.optString("mime_type").ifBlank { dataUrl.substringBefore(";base64,").removePrefix("data:") }
        return StoredFile(ref.name, mime.substringBefore(';').trim().lowercase().ifEmpty { "application/octet-stream" }, bytes)
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
    private suspend fun authorized(url: HttpUrl, method: String, body: JSONObject?, scoped: Boolean = true, synthesis: Boolean = false,
                                   maxBytes: Long = Long.MAX_VALUE, sttBoundary: Boolean = false): JSONObject =
        withContext(Dispatchers.IO) {
            if (!synthesis && !sttBoundary && maxBytes == Long.MAX_VALUE) return@withContext authorizedOnIo(url, method, body, scoped, http, null, maxBytes, false)
            // The synthesis request has no elapsed limit, a transcription is bounded by its caller's remaining budget or a
            // Stop, and a stored-file read (the only caller of [maxBytes]) can be stopped while its body is still
            // arriving, so their live call (a refresh round trip included) is cancelled with the coroutine: this also ends a
            // blocking body read, which coroutine cancellation alone would not.
            val current = java.util.concurrent.atomic.AtomicReference<Call?>()
            coroutineScope {
                val watcher = launch { try { awaitCancellation() } finally { current.get()?.cancel() } }
                try {
                    if (synthesis) authorizedOnIo(url, method, body, scoped, synthesisHttp, current)
                    else authorizedOnIo(url, method, body, scoped, http, current, maxBytes, sttBoundary)
                } finally {
                    watcher.cancel()
                }
            }
        }

    /**
     * `/api/audio/speak` answers one atomic JSON document only after the whole text is synthesized, so no fixed read or
     * call time can tell a healthy long synthesis from a dead one: this client has none (the bounded connect and write
     * timeouts stay). Nothing bounds a request that never answers by time: it ends with the audio, an error or a
     * disconnect, or when its caller's coroutine is cancelled (a Stop), which cancels the live call (an accepted limit:
     * a half-open connection holds its reply's place until then). Every other request (auth, refresh, sessions,
     * transcription) keeps the bounded client.
     */
    private val synthesisHttp: OkHttpClient by lazy {
        http.newBuilder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()
    }

    private suspend fun authorizedOnIo(url: HttpUrl, method: String, body: JSONObject?, scoped: Boolean,
                                       client: OkHttpClient, current: java.util.concurrent.atomic.AtomicReference<Call?>?,
                                       maxBytes: Long = Long.MAX_VALUE, sttBoundary: Boolean = false): JSONObject {
        val target = if (scoped && !profile.isNullOrBlank()) {
            url.newBuilder().addQueryParameter("profile", profile).build()
        } else {
            url
        }
        var session = tokens.load() ?: throw HermesAuthRequiredException("Hermes dashboard sign-in required")
        // At the speech boundary only, the request's own I/O failure (and a body cut short) is classified by type; see
        // [SttTransportClassifier]. Everything else, and every other route, keeps the plain IOException.
        val callRef = current ?: if (sttBoundary) java.util.concurrent.atomic.AtomicReference<Call?>() else null
        // A stored-file read ([maxBytes]) also answers to its caller's Stop, and its refusal is read through a small peek.
        val storedRead = maxBytes != Long.MAX_VALUE
        val ownsCancel = sttBoundary || storedRead
        repeat(2) { attempt ->
            val response = try {
                execute(request(target, method, body, session.accessToken), client, callRef)
            } catch (error: IOException) {
                // An exceptionally resumed await can win dispatch over Stop. At the STT boundary and for a stored file,
                // an inactive caller still owns cancellation, not a failed attempt.
                if (ownsCancel) currentCoroutineContext().ensureActive()
                throw sttTransportOr(error, callRef, sttBoundary)
            }
            response.use {
                when {
                    response.code == 401 && attempt == 0 -> session = refreshAfterRejection(session, callRef)
                    response.code == 401 -> throw HermesAuthRequiredException("Hermes dashboard rejected the refreshed session")
                    response.code !in 200..299 -> throw failure(url, response, read = method == "GET", bounded = storedRead).also { if (ownsCancel) currentCoroutineContext().ensureActive() }
                    else -> return try {
                        parseObject(response, maxBytes)
                    } catch (error: IOException) {
                        if (ownsCancel) currentCoroutineContext().ensureActive()
                        throw sttTransportOr(error, callRef, sttBoundary)
                    }
                }
            }
        }
        throw HermesAuthRequiredException("Hermes dashboard sign-in required")
    }

    private fun sttTransportOr(error: IOException, call: java.util.concurrent.atomic.AtomicReference<Call?>?, sttBoundary: Boolean): Exception {
        if (!sttBoundary) return error
        val transport = SttTransportClassifier.classify(error, call?.get()?.isCanceled() == true) ?: return error
        return HermesSttTransportException(transport, error)
    }

    /** Coalesced: callers that saw the same rejected access token share one refresh round trip. */
    private suspend fun refreshAfterRejection(rejected: HermesBearerSession,
                                              call: java.util.concurrent.atomic.AtomicReference<Call?>? = null): HermesBearerSession = refreshLock.withLock {
        val current = tokens.load() ?: throw HermesAuthRequiredException("Hermes dashboard sign-in required")
        if (current.provider != rejected.provider || current.userId != rejected.userId) {
            throw HermesAuthRequiredException("Hermes dashboard sign-in changed")
        }
        if (current.accessToken != rejected.accessToken) return current
        // The refresh mutex coalesces this client, not a browser sign-in/sign-out. Compare and
        // commit under the token store's own monitor; never retain it over network/body reads.
        fun commitIfCurrent(refreshed: HermesBearerSession?) = synchronized(tokens) {
            if (tokens.load() != current) throw HermesAuthRequiredException("Hermes dashboard sign-in changed during refresh")
            if (refreshed == null) tokens.clear() else tokens.save(refreshed)
        }
        if (current.refreshToken.isBlank()) {
            commitIfCurrent(null)
            throw HermesAuthRequiredException("Hermes dashboard session expired")
        }
        val body = JSONObject().put("refresh_token", current.refreshToken).put("provider", current.provider)
        execute(request(endpoint.route("auth/native/refresh"), "POST", body, bearer = null), http, call).use { response ->
            when (response.code) {
                200 -> HermesBearerSession.fromJson(refreshBody(response, call)).also(::commitIfCurrent)
                400, 401 -> {
                    commitIfCurrent(null)
                    throw HermesAuthRequiredException("Hermes dashboard session expired; sign in again")
                }
                else -> throw HermesHttpException(response.code, "session refresh failed (${response.code})")
            }
        }
    }

    /** A cut-off refresh body (its call cancelled with the caller) is a cancellation, never a half-read session. */
    private suspend fun refreshBody(response: Response, call: java.util.concurrent.atomic.AtomicReference<Call?>?): String = try {
        response.body?.string().orEmpty()
    } catch (error: IOException) {
        if (call != null) currentCoroutineContext().ensureActive()
        throw error
    }

    private fun request(url: HttpUrl, method: String, body: JSONObject?, bearer: String?): Request = Request.Builder()
        .url(url)
        .method(method, body?.toString()?.toRequestBody(JSON))
        .header("Accept", "application/json")
        .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
        .build()

    private fun parseObject(response: Response, maxBytes: Long = Long.MAX_VALUE): JSONObject = try {
        JSONObject(if (maxBytes == Long.MAX_VALUE) response.body?.string().orEmpty() else boundedText(response, maxBytes))
    } catch (error: JSONException) {
        throw HermesProtocolException("dashboard returned non-JSON for ${response.request.url.encodedPath}", error)
    }

    /**
     * The body as text, refusing (before holding more than [maxBytes]) anything larger: a stored file is never read unbounded.
     * Only stored files are read this way, and their JSON is [maxBytes] only when the file itself passes the preview limit.
     */
    private fun boundedText(response: Response, maxBytes: Long): String {
        val body = response.body ?: return ""
        if (body.contentLength() > maxBytes) throw previewTooLarge()
        val source = body.source()
        val buffer = okio.Buffer()
        while (buffer.size <= maxBytes) {
            if (source.read(buffer, 8192) == -1L) break
        }
        if (buffer.size > maxBytes) throw previewTooLarge()
        return buffer.readUtf8()
    }

    private fun previewTooLarge() = StoredFileTooLargeException(
        "the file is larger than the ${StoredFileSource.MAX_BYTES / MIB} MiB preview limit", StoredFileSource.MAX_BYTES.toLong())

    private fun downloadTooLarge() = StoredFileTooLargeException(
        "the file is larger than the ${StoredFileSource.MAX_DOWNLOAD_BYTES / MIB} MiB limit", StoredFileSource.MAX_DOWNLOAD_BYTES)

    /**
     * A stored file of up to [StoredFileSource.MAX_DOWNLOAD_BYTES] streamed from the dashboard's authenticated
     * `GET /api/files/download?path=` to [target]: bearer header only, the path the sole query value, at most one coalesced
     * refresh, and never more than a small buffer in memory whatever the size. The file's name is the reference's own and
     * its type the server's claim (parameters stripped); both are checked later by the previewer, not trusted here. A body
     * that is empty, cut short of its declared length, malformed or above the limit is a failure. Whatever happens
     * (cancellation included), nothing is left at [target].
     */
    override suspend fun downloadStoredFile(ref: com.rumi.hermesvoice.core.attachments.AttachmentRef, target: File): StoredDownload =
        withContext(Dispatchers.IO) {
            val current = java.util.concurrent.atomic.AtomicReference<Call?>()
            try {
                coroutineScope {
                    val watcher = launch { try { awaitCancellation() } finally { current.get()?.cancel() } }
                    try {
                        downloadOnIo(ref, target, current)
                    } finally {
                        watcher.cancel()
                    }
                }
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
        }

    private suspend fun downloadOnIo(ref: com.rumi.hermesvoice.core.attachments.AttachmentRef, target: File,
                                     current: java.util.concurrent.atomic.AtomicReference<Call?>): StoredDownload {
        val url = endpoint.route("api/files/download").newBuilder().addQueryParameter("path", ref.path).build()
        var session = tokens.load() ?: throw HermesAuthRequiredException("Hermes dashboard sign-in required")
        repeat(2) { attempt ->
            val request = Request.Builder().url(url).get()
                .header("Accept", "*/*").header("Accept-Encoding", "identity")
                .header("Authorization", "Bearer ${session.accessToken}").build()
            val response = try {
                execute(request, http, current)
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                throw error
            }
            response.use {
                when {
                    response.code == 401 && attempt == 0 -> session = refreshAfterRejection(session, current)
                    response.code == 401 -> throw HermesAuthRequiredException("Hermes dashboard rejected the refreshed session")
                    response.code == 413 -> throw downloadTooLarge()
                    response.code != 200 -> throw downloadRefusal(url, response)
                    else -> return receiveDownload(response, ref, target, current)
                }
            }
        }
        throw HermesAuthRequiredException("Hermes dashboard sign-in required")
    }

    /** A refusal is read through a small peek, never whole; only the server's missing route (not its "File not found") is told apart. */
    private suspend fun downloadRefusal(url: HttpUrl, response: Response): Exception {
        val text = try { response.peekBody(ERROR_BODY_BYTES).string() } catch (_: IOException) { "" }
        currentCoroutineContext().ensureActive()
        val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
        if (response.code == 404 && detail == "Not Found") {
            return StoredDownloadUnavailableException(
                "this Hermes server cannot send a file larger than ${StoredFileSource.MAX_BYTES / MIB} MiB to the app")
        }
        return failure(url, response.newBuilder().body(text.toResponseBody(response.body?.contentType())).build(), read = true)
    }

    /** Cancels the call first: closing an unread body would otherwise keep draining the sender for a moment. */
    private fun abandoned(current: java.util.concurrent.atomic.AtomicReference<Call?>): StoredFileTooLargeException {
        current.get()?.cancel()
        return downloadTooLarge()
    }

    private suspend fun receiveDownload(response: Response, ref: com.rumi.hermesvoice.core.attachments.AttachmentRef, target: File,
                                        current: java.util.concurrent.atomic.AtomicReference<Call?>): StoredDownload {
        val body = response.body ?: throw HermesProtocolException("the file response has no body")
        val declared = body.contentLength()
        if (declared > StoredFileSource.MAX_DOWNLOAD_BYTES) throw abandoned(current)
        var written = 0L
        try {
            FileOutputStream(target).use { out ->
                val source = body.source()
                val buffer = ByteArray(DOWNLOAD_BUFFER)
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    currentCoroutineContext().ensureActive()
                    written += n
                    if (written > StoredFileSource.MAX_DOWNLOAD_BYTES) throw abandoned(current)
                    out.write(buffer, 0, n)
                }
            }
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            throw error
        }
        if (declared >= 0 && written != declared) throw IOException("the file ended after $written of $declared bytes")
        if (written == 0L) throw HermesProtocolException("the file is empty")
        val mime = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase().ifEmpty { "application/octet-stream" }
        return StoredDownload(ref.name, mime, written)
    }

    private fun retryAfterMillis(response: Response): Long? {
        val raw = response.header("Retry-After")?.trim().orEmpty()
        if (raw.isEmpty()) return null
        raw.toLongOrNull()?.let { return if (it < 0 || it > Long.MAX_VALUE / 1000) null else it * 1000 }
        val date = response.headers.getDate("Retry-After") ?: return null
        return (date.time - System.currentTimeMillis()).coerceAtLeast(0)
    }

    private fun failure(url: HttpUrl, response: Response, read: Boolean = false, bounded: Boolean = false): HermesHttpException {
        val retryAfter = retryAfterMillis(response)
        val detail = runCatching {
            JSONObject(if (bounded) response.peekBody(ERROR_BODY_BYTES).string() else response.body?.string().orEmpty()).optString("detail")
        }.getOrNull()
        val path = url.encodedPath
        // Only the transcription route carries the speech envelope, and only an allowlisted code is taken from it: the
        // server's wording is replaced by the fixed text of that code, never shown. Any other detail on that route (a
        // legacy or proxy answer) is dropped too: it may carry a URL, token or path and must not reach a message or UI.
        if (path.endsWith("/api/audio/transcribe")) {
            val code = SttSafeCode.parse(detail)
            return if (code != null) HermesHttpException(response.code, "$path failed (${response.code}): ${SttSafeCode.message(code)}", code)
            else HermesHttpException(response.code, "$path failed (${response.code})")
        }
        // The speech-synthesis failure text reaches the later-reply status line and the diagnostic log: the server's wording (it may
        // carry a URL, token or path) is not taken there either.
        if (path.endsWith("/api/audio/speak")) return HermesHttpException(response.code, "$path failed (${response.code})")
        return HermesHttpException(response.code, "$path failed (${response.code})" + if (detail.isNullOrBlank()) "" else ": ${detail.take(200)}",
            retryAfterMs = retryAfter, readResponse = read)
    }

    private suspend fun execute(request: Request, client: OkHttpClient = http,
                                current: java.util.concurrent.atomic.AtomicReference<Call?>? = null): Response {
        val call = client.newCall(request)
        current?.set(call)
        return call.await()
    }

    companion object {
        const val MAX_TRANSCRIBE_BYTES = 25 * 1024 * 1024
        private const val MIB = 1024 * 1024
        private const val DOWNLOAD_BUFFER = 64 * 1024
        private const val ERROR_BODY_BYTES = 16L * 1024
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
