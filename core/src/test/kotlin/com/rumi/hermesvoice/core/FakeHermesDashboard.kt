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
    val wsProtocolHeaders: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val wsPaths: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile var accessToken = "AT-1"
    @Volatile var refreshToken = "RT-1"
    @Volatile var transcript = "move my 2pm meeting to 3"
    @Volatile var rejectAccessTokens: Set<String> = emptySet()
    @Volatile var refreshAccepted = true
    @Volatile var submitStatus: (String) -> String = { "streaming" }
    @Volatile var resumeQueued = false

    /** How `session.create` behaves for given params: "ok", "rpc_error", "wrong_source" or "drop_after_create". */
    @Volatile var createBehavior: (JSONObject) -> String = { "ok" }

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
                timeline += "transcribe:${Base64.getDecoder().decode(dataUrl.substringAfter(",")).size}"
                json(200, JSONObject().put("ok", true).put("transcript", transcript).put("provider", "fake"))
            }
            "/api/audio/speak" -> if (!authorized(request)) unauthorized() else {
                val text = body.optString("text")
                timeline += "speak:$text"
                val audio = Base64.getEncoder().encodeToString("AUDIO:$text".toByteArray())
                json(200, JSONObject().put("ok", true).put("data_url", "data:audio/mpeg;base64,$audio")
                    .put("mime_type", "audio/mpeg").put("provider", "fake"))
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

    private fun addMessage(stored: String, role: String, content: Any, hiddenKind: Boolean = false) {
        val row = rows[stored] ?: return
        val message = JSONObject().put("id", row.messages.size + 1).put("session_id", stored).put("role", role)
            .put("content", content).put("timestamp", 1_700_000_000.0 + row.messages.size)
        if (hiddenKind) message.put("display_kind", "hidden")
        row.messages += message
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
                    // The server created the session but the answer never arrives (connection lost).
                    if (behavior == "drop_after_create") { webSocket.cancel(); return }
                    reply(JSONObject().put("session_id", runtime).put("stored_session_id", stored).put("message_count", 0)
                        .put("messages", org.json.JSONArray()).put("info", JSONObject()))
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
                    val result = JSONObject().put("session_id", runtime).put("stored_session_id", stored)
                        .put("message_count", 0).put("messages", org.json.JSONArray()).put("info", JSONObject())
                        .put("queued", if (resumeQueued) JSONObject().put("user", "earlier") else JSONObject.NULL)
                    webSocket.send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString())
                }
                "prompt.submit" -> {
                    val runtime = params.getString("session_id")
                    val stored = runtimeIds.entries.first { it.value == runtime }.key
                    val prompt = params.getString("text")
                    require(params.optBoolean("queued")) { "voice client must submit with queued=true" }
                    prompts += stored to prompt
                    consumedImages += stored to (queuedImages.remove(runtime) ?: mutableListOf())
                    addMessage(stored, "user", prompt)
                    timeline += "submit:$stored"
                    val status = submitStatus(stored)
                    if (status == "streaming") webSocket.send(event("message.start", runtime, null))
                    webSocket.send(JSONObject().put("jsonrpc", "2.0").put("id", id)
                        .put("result", JSONObject().put("status", status)).toString())
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
