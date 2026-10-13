package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.auth.*
import com.rumi.hermesvoice.core.net.*
import com.rumi.hermesvoice.core.sessions.*
import com.rumi.hermesvoice.core.settings.*
import com.rumi.hermesvoice.core.voice.*
import com.rumi.hermesvoice.core.watchlink.*

import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject

/**
 * Test double of the Hermes dashboard surfaces this package uses, shaped after the installed
 * Hermes source: hermes_cli/dashboard_auth/routes.py (native token/refresh, ws-ticket),
 * hermes_cli/web_routers/audio.py (transcribe/speak), web_server_chat._ws_auth_reason (ticket
 * subprotocol) and tui_gateway (JSON-RPC frames, `event` notifications, session.resume,
 * prompt.submit → message.start / message.delta / message.interim / message.complete).
 * It is not Hermes; it proves this client's side of those contracts.
 */
class FakeHermesDashboard : AutoCloseable {
    val server = MockWebServer()
    val timeline: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val prompts: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    /**
     * Every prompt exactly as the model would see it (stored id → text). [prompts] holds the same turns with a leading
     * voice marker ([VOICE_MARK]) taken off, so inherited assertions on the user's words stay exact; the marker itself
     * is asserted through this list.
     */
    val rawPrompts: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    val wsProtocolHeaders: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val wsPaths: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile var accessToken = "AT-1"
    @Volatile var refreshToken = "RT-1"
    @Volatile var transcript = "move my 2pm meeting to 3"

    /** How long transcription takes (a slow speech-to-text: the gap before a request's acknowledgement). */
    /** How long `/api/audio/speak` takes to answer (a long synthesis). */
    @Volatile var speakDelayMs = 0L

    /** Every `/api/audio/speak` request received so far, counted on arrival (before its [speakDelayMs]). */
    val speakRequests = java.util.concurrent.atomic.AtomicInteger()

    /** `/api/audio/speak` body progress: each [speakThrottleBytes] bytes of the response body arrive [speakThrottleMs] apart (0: all at once). */
    @Volatile var speakThrottleBytes = 0L
    @Volatile var speakThrottleMs = 0L

    /** `/api/audio/speak` cuts the connection after a part of its body (a true disconnect). */
    @Volatile var speakDisconnectMidBody = false
    @Volatile var speakPadding = 0
    @Volatile var transcribeDelayMs = 0L

    /** The WAV bytes of the latest /api/audio/transcribe request (what the Phone really uploaded). */
    @Volatile var lastTranscribedWav: ByteArray? = null
    @Volatile var rejectAccessTokens: Set<String> = emptySet()
    @Volatile var refreshAccepted = true
    @Volatile var submitStatus: (String) -> String = { "streaming" }
    @Volatile var resumeQueued = false

    /** How `session.create` behaves for given params: "ok", "rpc_error", "wrong_source" or "drop_after_create". */
    @Volatile var createBehavior: (JSONObject) -> String = { "ok" }

    /**
     * How the turn of a `prompt.submit` to stored session [String] runs, after tui_gateway's real
     * orderings (methods_prompt / prompt_turn / session_auto_continue):
     * - "start_first": message.start before the submit reply (the run thread wins the race);
     * - "start_after_reply": the reply first, then message.start (the agent was still building);
     * - "double_start": a queued follow-up's two message.start frames (_dispatch_followup_turn);
     * - "error_before_start": a bare `error` and no message.start (_admit_prompt_turn refusal,
     *   or "Turn cancelled before the agent was ready");
     * - "init_failed": message.complete status=error and no message.start (_emit_terminal_turn_error);
     * - "session_limit": prompt.submit answers JSON-RPC error 4090 (the active session cap).
     */
    @Volatile var turnBehavior: (String) -> String = { "start_first" }

    /** Close the socket right after answering `session.create` (the next call reconnects). */
    @Volatile var dropAfterCreateReply = false

    /**
     * A session's runtime model/provider/reasoning (tui_gateway session record + model_config). A
     * create's `model`/`provider`/`reasoning_effort` are per-session overrides; otherwise the profile default.
     */
    data class Runtime(val model: String, val provider: String, val effort: String)

    val profileDefault = Runtime("profile-default-model", "profile-default-provider", "")
    val runtimes: MutableMap<String, Runtime> = Collections.synchronizedMap(HashMap())

    /**
     * The real cold/lazy states of tui_gateway for a stored session (null: the legacy fixture, always built).
     * N >= 0: the first `session.resume` is a cold resume (_resume_cold: build scheduled, `_lazy_resume_info`
     * = {model: stored, provider: stored, lazy: true}); the next N resumes still find no agent
     * (`_fallback_session_info` = {model: the profile default, lazy: true}, no provider); then the agent is
     * built FROM THE STORED RUNTIME (_deferred_build_agent_kwargs prefers resume_runtime_overrides) and resume
     * reports `_session_info` (model, provider, reasoning_effort, running). N < 0: the build never finishes.
     * A `config.set` with an explicit provider while there is no agent only pins the override in memory
     * (methods_config_set._set_model does not wait), and the build that follows ignores it.
     */
    @Volatile var coldResumesBeforeBuild: (String) -> Int? = { null }
    private val coldLive: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val coldPolls: MutableMap<String, Int> = Collections.synchronizedMap(HashMap())
    private val builtAgents: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** Model switches that arrived while the session had no agent (pinned in memory only, as in the source). */
    val switchesWithoutAgent: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /**
     * B35-R1, a FAILED build (SOURCE-MODELLED, not observed live): how many deferred builds of a stored session fail.
     * server._start_agent_build's `_build` except-branch sets agent_error, `finally` sets agent_ready, and the agent
     * stays None, so every later live resume reports the lazy `_fallback_session_info` with status "idle"
     * (_session_live_status: ready is set), never "starting" again, and nothing rebuilds it except config.set's
     * failed-init path (methods_config_set._set_model:119-141 -> model_switch._restart_completed_failed_agent_build):
     * the switch's model and provider are merged into the stored runtime overrides while the effort stays the STORED
     * one (only model_override and provider_override are merged; the deferred build prefers resume_runtime_overrides),
     * the rebuild is awaited (_cfgset_await_agent, <= 30 s, here [rebuildDelayMs]) and the answer is the switch
     * envelope, or JSON-RPC 5032 with the build error when it fails again. Used with [coldResumesBeforeBuild] >= 0.
     */
    @Volatile var buildFailures: (String) -> Int = { 0 }
    @Volatile var rebuildDelayMs = 0L
    private val failedAgents: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val buildsFailed: MutableMap<String, Int> = Collections.synchronizedMap(HashMap())

    /** Stored sessions whose failed build config.set restarted (each entry is one restart). */
    val rebuilds: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The live resume's `status` for a stored session (null: omitted), from what the source would report. */
    @Volatile var resumeStatus: (String, String) -> String? = { _, status -> status }

    /**
     * The failed record's agent gets built by something outside this app (another client's prompt.submit rebuilds a failed
     * build, methods_prompt.py:652, or the gateway reloads it): from now on a resume reports it built from the stored runtime.
     */
    fun builtElsewhere(stored: String) {
        failedAgents -= stored
        builtAgents += stored
    }

    /** A record already live in the gateway whose earlier build failed (another request or client resumed it before). */
    fun failedLiveBuild(stored: String) {
        coldLive += stored
        failedAgents += stored
        buildsFailed.merge(stored, 1, Int::plus)
    }

    /** The session's agent is built (or the session follows the legacy always-built fixture). */
    fun agentBuilt(stored: String): Boolean = stored !in failedAgents && (coldResumesBeforeBuild(stored) == null || stored in builtAgents)

    /** A create's per-session model override is ignored and not reported back (an older gateway). */
    @Volatile var ignoreCreateOverrides = false

    /**
     * What `config.set` with key "model" does: "ok", "error" (switch failed), "confirm" (selection guard),
     * "deferred", "wrong_model" / "wrong_effort" (applied something else), or "drop": the fixture closes the
     * WebSocket with a close frame (1011) instead of answering: a graceful close the client sees at once, not a
     * TCP reset or a silent half-open connection.
     */
    @Volatile var modelSwitchBehavior: (String) -> String = { "ok" }

    /** The error text of a refused switch ("error"); the app must never show or log it. */
    @Volatile var modelSwitchErrorText: (String) -> String = { model -> "Model switch to $model failed (provider not authenticated)" }

    /** Anything that would have written the profile's config.yaml (a global switch, a sessionless reasoning set). */
    val globalConfigWrites: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The runtime each submitted turn ran on (stored id → model/provider/effort at that moment). */
    val turnModels: MutableList<Pair<String, Runtime>> = Collections.synchronizedList(mutableListOf())

    /** Stored sessions (the dashboard's state.db rows): id → row fields, plus their transcript. */
    class Row(val id: String, val source: String, var title: String, var archived: Boolean, val hidden: Boolean) {
        val messages: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
    }
    /** Script for sessions created with a given source (e.g. the router), set before they exist. */
    val sourceScripts = HashMap<String, (String) -> List<Pair<String, JSONObject?>>>()
    val rows: MutableMap<String, Row> = Collections.synchronizedMap(LinkedHashMap())
    val rpcLog: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
    val restLog: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val createCounter = AtomicInteger()

    /** Per runtime id: images queued by image.attach_bytes, consumed by the next prompt.submit. */
    val queuedImages = HashMap<String, MutableList<String>>()
    val consumedImages: MutableList<Pair<String, List<String>>> = Collections.synchronizedList(mutableListOf())
    val stagedFiles: MutableList<Pair<String, ByteArray>> = Collections.synchronizedList(mutableListOf())
    @Volatile var failFileAttach = false

    /**
     * A session that already exists on the dashboard (made by an earlier app build, e.g. a routing
     * session from before the router-only model): its row, its script and the profile-default runtime.
     */
    fun addStoredSession(source: String, title: String, hidden: Boolean, script: (String) -> List<Pair<String, JSONObject?>>): String {
        val stored = "s${createCounter.incrementAndGet()}"
        rows[stored] = Row(stored, source, title, false, hidden)
        scripts[stored] = script
        runtimes[stored] = profileDefault
        return stored
    }

    /** Adds a row created elsewhere (another client or source) that the app must never touch. */
    fun addForeignRow(id: String, source: String, archived: Boolean = false) {
        rows[id] = Row(id, source, "foreign $id", archived, hidden = false)
        scripts[id] = { listOf(complete("foreign reply")) }
    }

    /** Stored session id → scripted events (type to payload) emitted for each prompt on it. */
    val scripts = HashMap<String, (String) -> List<Pair<String, JSONObject?>>>()

    private val tickets = Collections.synchronizedSet(HashSet<String>())
    private val ticketCounter = AtomicInteger()
    private val refreshCount = AtomicInteger()
    val refreshes: Int get() = refreshCount.get()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        }
        server.start()
    }

    val baseUrl: String get() = server.url("/").toString()

    /** Server-side path → (mime type, bytes) served by the stored-file routes; `/api/media` serves images only. */
    val storedFiles = HashMap<String, Pair<String, ByteArray>>()

    /** Each stored-file request as `route?path=…`, and the Authorization header it carried. */
    val storedFileRequests: MutableList<Pair<String, String?>> = Collections.synchronizedList(mutableListOf())

    /** When set, a stored-file request is answered with this status instead (no body). */
    @Volatile var storedFileStatus: Int? = null

    /** When set, `/api/files/read` redirects to this absolute URL. */
    @Volatile var storedFileRedirect: String? = null

    private fun storedFileRoute(request: RecordedRequest, path: String): MockResponse {
        val wanted = request.requestUrl!!.queryParameter("path").orEmpty()
        storedFileRequests += "$path?path=$wanted" to request.getHeader("Authorization")
        storedFileStatus?.let { return json(it, JSONObject().put("detail", "refused")) }
        if (path == "/api/files/read") storedFileRedirect?.let { return MockResponse().setResponseCode(302).setHeader("Location", it) }
        val (mime, bytes) = storedFiles[wanted] ?: return json(404, JSONObject().put("detail", "not found"))
        if (path == "/api/media" && !mime.startsWith("image/")) return json(415, JSONObject().put("detail", "not an image"))
        val dataUrl = "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
        return json(200, JSONObject().put("name", wanted.substringAfterLast('/')).put("size", bytes.size).put("mime_type", mime).put("data_url", dataUrl))
    }

    fun decodeSpoken(audio: SpokenAudio): String = String(audio.bytes).removePrefix("AUDIO:")

    private fun json(code: Int, body: JSONObject) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body.toString())

    private fun authorized(request: RecordedRequest): Boolean {
        val header = request.getHeader("Authorization") ?: return false
        val token = header.removePrefix("Bearer ")
        return header.startsWith("Bearer ") && token == accessToken && token !in rejectAccessTokens
    }

    private fun unauthorized() = json(401, JSONObject().put("error", "session_expired").put("detail", "Unauthorized"))

    private fun bearerPayload() = JSONObject().put("access_token", accessToken).put("refresh_token", refreshToken)
        .put("token_type", "Bearer").put("expires_at", 1_900_000_000L).put("provider", "basic").put("user_id", "jun")

    private fun handle(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath
        val body = request.body.readUtf8().let { if (it.isBlank()) JSONObject() else JSONObject(it) }
        if (path.startsWith("/api/sessions")) return if (!authorized(request)) unauthorized() else sessionsRoute(request, path, body)
        if (path == "/api/media" || path == "/api/files/read") return if (!authorized(request)) unauthorized() else storedFileRoute(request, path)
        return when (path) {
            "/auth/native/token" ->
                if (body.optString("code") == "gw-code" && PkcePair.challengeFor(body.optString("code_verifier")) == expectedChallenge) {
                    json(200, bearerPayload())
                } else {
                    json(400, JSONObject().put("detail", "Invalid or expired authorization code."))
                }
            "/auth/native/refresh" -> {
                refreshCount.incrementAndGet()
                if (refreshAccepted && body.optString("refresh_token") == refreshToken) {
                    accessToken = "AT-${refreshCount.get() + 1}"
                    refreshToken = "RT-${refreshCount.get() + 1}"
                    json(200, bearerPayload())
                } else {
                    json(401, JSONObject().put("error", "session_expired"))
                }
            }
            "/api/auth/ws-ticket" -> if (!authorized(request)) unauthorized() else {
                val ticket = "ticket-${ticketCounter.incrementAndGet()}"
                tickets += ticket
                json(200, JSONObject().put("ticket", ticket).put("ttl_seconds", 30))
            }
            "/api/audio/transcribe" -> if (!authorized(request)) unauthorized() else {
                val dataUrl = body.optString("data_url")
                require(dataUrl.startsWith("data:audio/wav;base64,")) { "bad data_url" }
                val wav = Base64.getDecoder().decode(dataUrl.substringAfter(","))
                lastTranscribedWav = wav
                timeline += "transcribe:${wav.size}"
                if (transcribeDelayMs > 0) Thread.sleep(transcribeDelayMs)
                json(200, JSONObject().put("ok", true).put("transcript", transcript).put("provider", "fake"))
            }
            "/api/audio/speak" -> if (!authorized(request)) unauthorized() else {
                val text = body.optString("text")
                speakRequests.incrementAndGet()
                if (speakDelayMs > 0) Thread.sleep(speakDelayMs)
                timeline += "speak:$text"
                val audio = Base64.getEncoder().encodeToString("AUDIO:$text".toByteArray())
                val response = json(200, JSONObject().put("ok", true).put("data_url", "data:audio/mpeg;base64,$audio")
                    .put("mime_type", "audio/mpeg").put("provider", "fake").put("pad", "x".repeat(speakPadding)))
                if (speakThrottleBytes > 0) response.throttleBody(speakThrottleBytes, speakThrottleMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (speakDisconnectMidBody) response.setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                response
            }
            "/api/ws" -> {
                wsPaths += request.requestUrl.toString()
                val header = request.getHeader("Sec-WebSocket-Protocol").orEmpty()
                wsProtocolHeaders += header
                val protocols = header.split(",").map { it.trim() }
                val ticket = protocols.singleOrNull { it.startsWith("hermes-gateway-ticket.") }
                    ?.removePrefix("hermes-gateway-ticket.")
                if ("hermes-gateway-v1" !in protocols || ticket == null || !tickets.remove(ticket)) {
                    MockResponse().setResponseCode(403)
                } else {
                    MockResponse().withWebSocketUpgrade(GatewayListener())
                }
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    @Volatile var expectedChallenge: String = ""

    /** hermes_cli/web_routers/sessions.py: list (source/archived/limit/offset), detail, messages, PATCH flags. */
    private fun sessionsRoute(request: RecordedRequest, path: String, body: JSONObject): MockResponse {
        val url = request.requestUrl!!
        restLog += "${request.method} ${url.encodedPath}?${url.encodedQuery.orEmpty()}"
        val segments = url.pathSegments.drop(2)
        if (segments.isEmpty() && request.method == "GET") {
            val archived = url.queryParameter("archived") ?: "exclude"
            val source = url.queryParameter("source")
            val matching = synchronized(rows) { rows.values.toList() }.filter { row ->
                !row.hidden && (source == null || row.source == source) && when (archived) {
                    "only" -> row.archived
                    "include" -> true
                    else -> !row.archived
                }
            }.reversed()
            val limit = url.queryParameter("limit")?.toInt() ?: 20
            val offset = url.queryParameter("offset")?.toInt() ?: 0
            val page = matching.drop(offset).take(limit)
            return json(200, JSONObject().put("sessions", org.json.JSONArray().apply { page.forEach { put(rowJson(it)) } })
                .put("total", matching.size).put("limit", limit).put("offset", offset))
        }
        val row = rows[segments.first()] ?: return json(404, JSONObject().put("detail", "Session not found"))
        return when {
            segments.size == 1 && request.method == "GET" -> json(200, rowJson(row))
            segments.size == 1 && request.method == "PATCH" -> {
                if (body.has("archived")) row.archived = body.getBoolean("archived")
                json(200, JSONObject().put("ok", true).put("title", row.title).put("archived", row.archived))
            }
            segments.size == 2 && segments[1] == "messages" -> {
                val limit = url.queryParameter("limit")?.toInt() ?: 500
                val offset = url.queryParameter("offset")?.toInt() ?: 0
                val all = synchronized(row.messages) { row.messages.toList() }
                val end = (all.size - offset).coerceAtLeast(0)
                val page = all.subList((end - limit).coerceAtLeast(0), end)
                json(200, JSONObject().put("session_id", row.id).put("messages", org.json.JSONArray(page))
                    .put("pagination", JSONObject().put("limit", limit).put("offset", offset).put("order", "latest")
                        .put("returned", page.size)))
            }
            else -> json(404, JSONObject().put("detail", "Not found"))
        }
    }

    private fun rowJson(row: Row) = JSONObject().put("id", row.id).put("source", row.source).put("title", row.title)
        .put("archived", row.archived).put("started_at", 1_700_000_000.0).put("last_active", 1_700_000_100.0)
        .put("message_count", row.messages.size).put("preview", row.messages.lastOrNull()?.optString("content").orEmpty())

    /** A transcript row written by something other than this app (another client, the dashboard): stored verbatim like any row. */
    fun injectRow(stored: String, role: String, content: Any, hiddenKind: Boolean = false) = addMessage(stored, role, content, hiddenKind)

    private fun addMessage(stored: String, role: String, content: Any, hiddenKind: Boolean = false) {
        val row = rows[stored] ?: return
        val message = JSONObject().put("id", row.messages.size + 1).put("session_id", stored).put("role", role)
            .put("content", content).put("timestamp", 1_700_000_000.0 + row.messages.size)
        if (hiddenKind) message.put("display_kind", "hidden")
        row.messages += message
    }

    /** The gateway socket each stored session was last used on, and its runtime id (for [pushLaterTurn]). */
    private val sessionSockets: MutableMap<String, Pair<WebSocket, String>> = Collections.synchronizedMap(HashMap())

    /**
     * Hermes delivering a LATER turn into a live session, as tui_gateway's session_notifications
     * poller does for a background process / async delegation completion (and prompt_turn for a goal
     * follow-up): `message.start`, then [events] (typically a `message.complete`), on the session's
     * runtime id, to the socket the session lives on. [withStart] false models a replayed frame.
     */
    fun pushLaterTurn(stored: String, vararg events: Pair<String, JSONObject?>, withStart: Boolean = true) {
        val (socket, runtime) = sessionSockets.getValue(stored)
        if (withStart) socket.send(event("message.start", runtime, null))
        events.forEach { (type, payload) ->
            if (type == "message.complete") addMessage(stored, "assistant", payload!!.optString("text"))
            socket.send(event(type, runtime, payload))
        }
    }

    private inner class GatewayListener : WebSocketListener() {
        private val runtimeIds = HashMap<String, String>()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(event("gateway.ready", "", JSONObject().put("skin", JSONObject())
                .put("change_events", true).put("replay_epoch", "e1").put("heartbeat", true)))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = JSONObject(text)
            val id = frame.get("id")
            val params = frame.optJSONObject("params") ?: JSONObject()
            rpcLog += JSONObject().put("method", frame.optString("method")).put("params", params)
            fun reply(result: JSONObject) = webSocket.send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString())
            fun fail(code: Int, message: String) = webSocket.send(JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("error", JSONObject().put("code", code).put("message", message)).toString())
            when (frame.optString("method")) {
                "session.create" -> {
                    val behavior = createBehavior(params)
                    if (behavior == "rpc_error") { fail(5001, "create refused"); return }
                    val stored = "s${createCounter.incrementAndGet()}"
                    val runtime = "rt-$stored"
                    runtimeIds[stored] = runtime
                    val seed = params.optJSONArray("messages")
                    // methods_session._seed_row: a seeded parentless session is persisted at create time.
                    if (seed != null && seed.length() > 0) {
                        rows[stored] = Row(stored, if (behavior == "wrong_source") "someone-else" else params.optString("source"),
                            params.optString("title"), false, params.optBoolean("hidden"))
                        for (i in 0 until seed.length()) {
                            val m = seed.getJSONObject(i)
                            addMessage(stored, m.getString("role"), m.getString("content"), m.optString("display_kind") == "hidden")
                        }
                    }
                    scripts.putIfAbsent(stored, sourceScripts[params.optString("source")] ?: { listOf(complete("reply from $stored")) })
                    // methods_session._create_overrides: per-session, never a global config write.
                    val override = params.optString("model").takeIf { it.isNotBlank() && !ignoreCreateOverrides }
                    runtimes[stored] = if (override == null) profileDefault
                        else Runtime(override, params.optString("provider"), params.optString("reasoning_effort"))
                    // The server created the session but the answer never arrives (connection lost).
                    // A graceful close frame (1011) instead of the answer (not a TCP reset, not a half-open connection).
                    if (behavior == "drop_after_create") { webSocket.close(1011, "dropped by fixture"); return }
                    sessionSockets[stored] = webSocket to runtime
                    val info = JSONObject().put("model", runtimes.getValue(stored).model)
                    if (override != null && params.optString("provider").isNotBlank()) info.put("provider", params.optString("provider"))
                    reply(JSONObject().put("session_id", runtime).put("stored_session_id", stored).put("message_count", 0)
                        .put("messages", org.json.JSONArray()).put("info", info))
                    if (dropAfterCreateReply) webSocket.close(1001, "going away")
                }
                "config.set" -> {
                    val key = params.optString("key")
                    val runtime = params.optString("session_id")
                    val stored = runtimeIds.entries.firstOrNull { it.value == runtime && runtime.isNotBlank() }?.key
                    // methods_config_set: a sessionless "reasoning" set writes agent.reasoning_effort to config.yaml.
                    if (key == "reasoning" && stored == null) {
                        globalConfigWrites += "agent.reasoning_effort"
                        reply(JSONObject().put("key", key).put("value", params.optString("value")))
                        return
                    }
                    if (key != "model") { fail(4002, "unknown config key: $key"); return }
                    if (stored == null) { fail(4001, "config.set model requires a live session; use Settings -> Models to change the profile default"); return }
                    // hermes_cli.model_switch.parse_model_switch_args: "<model> --provider P --reasoning R --session|--global".
                    var model = ""
                    var provider = ""
                    var effort = ""
                    var global = false
                    val words = params.optString("value").split(" ").filter { it.isNotBlank() }.iterator()
                    while (words.hasNext()) {
                        when (val w = words.next()) {
                            "--provider" -> provider = if (words.hasNext()) words.next() else ""
                            "--reasoning" -> effort = if (words.hasNext()) words.next() else ""
                            "--global" -> global = true
                            else -> if (!w.startsWith("--") && model.isEmpty()) model = w
                        }
                    }
                    when (modelSwitchBehavior(params.optString("value"))) {
                        "error" -> { fail(5001, modelSwitchErrorText(model)); return }
                        "drop" -> { webSocket.close(1011, "dropped by fixture"); return }
                        "confirm" -> {
                            reply(JSONObject().put("key", key).put("value", model).put("warning", "large context")
                                .put("confirm_required", true).put("confirm_message", "large context").put("scope", "session"))
                            return
                        }
                        "deferred" -> {
                            reply(JSONObject().put("key", key).put("value", model).put("warning", "").put("confirm_required", false)
                                .put("confirm_message", "").put("scope", "session").put("deferred", true))
                            return
                        }
                    }
                    if (global) globalConfigWrites += "model.default"
                    if (stored in failedAgents) {
                        // failed_agent_init: restart the completed failed build with the override merged, await it.
                        rebuilds += stored
                        val stale = runtimes[stored] ?: profileDefault
                        val merged = Runtime(model, provider.ifBlank { stale.provider }, stale.effort)
                        val failsAgain = (buildsFailed[stored] ?: 0) < buildFailures(stored)
                        Thread {
                            if (rebuildDelayMs > 0) Thread.sleep(rebuildDelayMs)
                            if (failsAgain) {
                                buildsFailed.merge(stored, 1, Int::plus)
                                fail(5032, "agent init failed: state.db could not be opened")
                            } else {
                                failedAgents -= stored
                                builtAgents += stored
                                runtimes[stored] = merged  // _persist_live_session_runtime after the awaited rebuild
                                reply(JSONObject().put("key", key).put("value", model).put("warning", "").put("confirm_required", false)
                                    .put("confirm_message", "").put("scope", "session"))
                            }
                        }.apply { isDaemon = true }.start()
                        return
                    }
                    if (!agentBuilt(stored)) {
                        // _set_model with an explicit provider and agent None: the override is pinned in memory only.
                        switchesWithoutAgent += stored
                        reply(JSONObject().put("key", key).put("value", model).put("warning", "").put("confirm_required", false)
                            .put("confirm_message", "").put("scope", if (global) "global" else "session"))
                        return
                    }
                    val before = runtimes[stored] ?: profileDefault
                    // The answer echoes the request; what the agent really runs on can differ (caught only by the readback).
                    val applied = when (modelSwitchBehavior(params.optString("value"))) {
                        "wrong_model" -> Runtime("some-other-model", provider.ifBlank { before.provider }, effort.ifBlank { before.effort })
                        "wrong_effort" -> Runtime(model, provider.ifBlank { before.provider }, "high")
                        else -> Runtime(model, provider.ifBlank { before.provider }, effort.ifBlank { before.effort })
                    }
                    runtimes[stored] = applied
                    reply(JSONObject().put("key", key).put("value", model).put("warning", "").put("confirm_required", false)
                        .put("confirm_message", "").put("scope", if (global) "global" else "session"))
                }
                "image.attach_bytes" -> {
                    val runtime = params.getString("session_id")
                    val bytes = Base64.getDecoder().decode(params.getString("content_base64"))
                    val path = "/home/h/images/upload_${queuedImages.values.sumOf { it.size } + 1}_${params.optString("filename")}"
                    queuedImages.getOrPut(runtime) { mutableListOf() } += path
                    reply(JSONObject().put("attached", true).put("path", path).put("count", queuedImages.getValue(runtime).size)
                        .put("bytes", bytes.size).put("text", "[User attached image]"))
                }
                "file.attach" -> {
                    if (failFileAttach) { fail(5028, "disk full"); return }
                    val dataUrl = params.getString("data_url")
                    require(!params.has("path")) { "remote client must never send a gateway path" }
                    val bytes = Base64.getDecoder().decode(dataUrl.substringAfter(";base64,"))
                    val name = params.getString("name")
                    stagedFiles += name to bytes
                    reply(JSONObject().put("attached", true).put("name", name).put("path", "/ws/attachments/$name")
                        .put("ref_path", "attachments/$name").put("ref_text", "@file:attachments/$name").put("uploaded", true))
                }
                "image.detach" -> {
                    val runtime = params.getString("session_id")
                    val removed = queuedImages[runtime]?.remove(params.getString("path")) ?: false
                    reply(JSONObject().put("detached", removed).put("count", queuedImages[runtime]?.size ?: 0))
                }
                "session.resume" -> {
                    val stored = params.getString("session_id")
                    if (stored !in scripts) {
                        webSocket.send(JSONObject().put("jsonrpc", "2.0").put("id", id)
                            .put("error", JSONObject().put("code", 4007).put("message", "session not found")).toString())
                        return
                    }
                    val runtime = runtimeIds.getOrPut(stored) { "rt-$stored" }
                    sessionSockets[stored] = webSocket to runtime
                    // server._stored_session_runtime_overrides / _session_info: resume reports the session's own runtime.
                    val rt = runtimes.getOrPut(stored) { profileDefault }
                    val plan = coldResumesBeforeBuild(stored)
                    fun built() = JSONObject().put("model", rt.model).put("provider", rt.provider).put("reasoning_effort", rt.effort).put("running", false)
                    fun fallback() = JSONObject().put("model", profileDefault.model).put("lazy", true).put("tools", JSONObject()).put("skills", JSONObject())
                    // `status`: _resume_response's default "idle" for the cold resume; _session_live_status for a live record
                    // ("starting" while the build runs, "idle" once agent_ready is set, built or failed).
                    var status = "idle"
                    val info = when {
                        stored in failedAgents -> fallback()
                        plan == null || stored in builtAgents -> built()
                        stored !in coldLive -> {
                            coldLive += stored
                            JSONObject().put("model", rt.model).put("lazy", true).put("tools", JSONObject()).put("skills", JSONObject())
                                .apply { if (rt.provider.isNotEmpty()) put("provider", rt.provider) }
                        }
                        plan >= 0 && (coldPolls.merge(stored, 1, Int::plus) ?: 0) > plan -> {
                            if ((buildsFailed[stored] ?: 0) < buildFailures(stored)) {
                                buildsFailed.merge(stored, 1, Int::plus)
                                failedAgents += stored
                                fallback()
                            } else {
                                builtAgents += stored
                                built()
                            }
                        }
                        else -> fallback().also { status = "starting" }
                    }
                    val result = JSONObject().put("session_id", runtime).put("stored_session_id", stored).put("session_key", stored)
                        .put("message_count", 0).put("messages", org.json.JSONArray())
                        .put("info", info).put("running", false)
                        .put("queued", if (resumeQueued) JSONObject().put("user", "earlier") else JSONObject.NULL)
                    resumeStatus(stored, status)?.let { result.put("status", it) }
                    webSocket.send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString())
                }
                "prompt.submit" -> {
                    val runtime = params.getString("session_id")
                    val stored = runtimeIds.entries.first { it.value == runtime }.key
                    val rawPrompt = params.getString("text")
                    val prompt = voiceWords(rawPrompt)
                    rawPrompts += stored to rawPrompt
                    prompts += stored to prompt
                    consumedImages += stored to (queuedImages.remove(runtime) ?: mutableListOf())
                    addMessage(stored, "user", rawPrompt)
                    timeline += "submit:$stored"
                    turnModels += stored to (runtimes[stored] ?: profileDefault)
                    val behavior = turnBehavior(stored)
                    if (behavior == "session_limit") {
                        fail(4090, "Hermes is at the active session limit (4/4).")
                        return
                    }
                    val status = submitStatus(stored)
                    if (status == "streaming" && behavior in setOf("start_first", "double_start")) webSocket.send(event("message.start", runtime, null))
                    webSocket.send(JSONObject().put("jsonrpc", "2.0").put("id", id)
                        .put("result", JSONObject().put("status", status)).toString())
                    when (behavior) {
                        "error_before_start" -> { webSocket.send(event("error", runtime, JSONObject().put("message", "Turn cancelled before the agent was ready"))); return }
                        "init_failed" -> {
                            webSocket.send(event("message.complete", runtime, JSONObject().put("text", "Error: agent init failed").put("status", "error")
                                .put("error", "agent init failed").put("recoverable", true)))
                            return
                        }
                        "start_after_reply" -> { Thread.sleep(200); webSocket.send(event("message.start", runtime, null)) }
                        "double_start" -> webSocket.send(event("message.start", runtime, null))
                    }
                    scripts.getValue(stored)(prompt).forEach { (type, payload) ->
                        if (type == "message.complete") addMessage(stored, "assistant", payload!!.optString("text"))
                        webSocket.send(event(type, runtime, payload))
                    }
                }
                else -> webSocket.send(JSONObject().put("jsonrpc", "2.0").put("id", id)
                    .put("error", JSONObject().put("code", -32601).put("message", "unknown method")).toString())
            }
        }
    }

    fun event(type: String, sid: String, payload: JSONObject?): String {
        val params = JSONObject().put("type", type).put("session_id", sid)
        if (payload != null) params.put("payload", payload)
        return JSONObject().put("jsonrpc", "2.0").put("method", "event").put("params", params).toString()
    }

    override fun close() = server.shutdown()

    companion object {
        fun complete(text: String, status: String = "complete"): Pair<String, JSONObject> =
            "message.complete" to JSONObject().put("text", text).put("status", status)
        fun interim(text: String): Pair<String, JSONObject> =
            "message.interim" to JSONObject().put("text", text).put("already_streamed", true)
        fun delta(text: String): Pair<String, JSONObject> = "message.delta" to JSONObject().put("text", text)
    }
}
