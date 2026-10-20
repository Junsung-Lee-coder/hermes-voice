package com.rumi.hermesvoice.core.net

import com.rumi.hermesvoice.core.HermesAuthRequiredException
import com.rumi.hermesvoice.core.HermesException
import com.rumi.hermesvoice.core.HermesHttpException
import com.rumi.hermesvoice.core.HermesProtocolException
import com.rumi.hermesvoice.core.HermesRpcException
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject

/** The two operations the conversation layer needs from a gateway socket (a seam for tests). */
interface GatewayRpc {
    suspend fun call(method: String, params: JSONObject, timeoutMs: Long = HermesGatewayConnection.DEFAULT_CALL_TIMEOUT_MS): JSONObject
    fun subscribe(sessionId: String): GatewaySubscription

    /** The server's replay epoch announced in `gateway.ready` (it changes when the server process restarts); "" when unknown. */
    val replayEpoch: String get() = ""
}

/** One `{"method":"event","params":{type, session_id, payload, seq}}` frame from the tui_gateway; [seq] is the per-session replay sequence (0: none). */
data class GatewayEvent(val type: String, val sessionId: String, val payload: JSONObject?, val seq: Long = 0L)

class GatewaySubscription(
    val sessionId: String,
    val channel: Channel<GatewayEvent>,
    private val onClose: (GatewaySubscription) -> Unit,
) : Closeable {
    val events: ReceiveChannel<GatewayEvent> get() = channel

    /**
     * The connection this subscription was opened on (null: the opener cannot say). Compared by reference only, never inspected: it lets
     * a caller tell "the same socket" from "a replacement" through any wrapper that forwards [GatewayRpc.subscribe].
     */
    var origin: Any? = null
        internal set

    override fun close() {
        onClose(this)
        channel.close()
    }
}

/**
 * JSON-RPC 2.0 over the dashboard's `/api/ws` gateway sidecar, one JSON object per text frame.
 * Gated-mode auth uses a single-use `/api/auth/ws-ticket` ticket carried in the
 * `hermes-gateway-ticket.<ticket>` subprotocol (never in the URL). Server-to-client requests
 * (approvals, clarifications) are not answered by this client.
 */
class HermesGatewayConnection private constructor() : GatewayRpc, Closeable {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val subscribers = ConcurrentHashMap<String, CopyOnWriteArrayList<GatewaySubscription>>()
    private val ready = CompletableDeferred<Unit>()
    private val nextId = AtomicLong(1)
    @Volatile private var failure: Throwable? = null
    @Volatile override var replayEpoch: String = ""
        private set
    private lateinit var socket: WebSocket

    val isOpen: Boolean get() = failure == null

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) = handleFrame(text)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            fail(closeError(code, reason))
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = fail(closeError(code, reason))

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            fail(when (code) {
                401 -> HermesAuthRequiredException("gateway rejected the WebSocket ticket")
                403 -> HermesHttpException(403,
                    "gateway refused the WebSocket upgrade (ticket, host/origin guard, or embedded chat disabled)")
                null -> HermesProtocolException("gateway connection failed: ${t.javaClass.simpleName}", t)
                else -> HermesHttpException(code, "gateway upgrade failed ($code)")
            })
        }
    }

    override suspend fun call(method: String, params: JSONObject, timeoutMs: Long): JSONObject {
        failure?.let { if (method == "prompt.submit") throw com.rumi.hermesvoice.core.HermesPromptNotSentException(it) else throw it }
        val id = "r${nextId.getAndIncrement()}"
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        var sent = false
        try {
            val frame = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
            if (!socket.send(frame.toString())) {
                val error = failure ?: HermesProtocolException("gateway socket is closed")
                if (method == "prompt.submit") throw com.rumi.hermesvoice.core.HermesPromptNotSentException(error) else throw error
            }
            sent = true
            return try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (timeout: TimeoutCancellationException) {
                throw HermesProtocolException("gateway call $method timed out", timeout)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (refused: HermesRpcException) {
            // An error envelope proves a response, not the absence of prompt effects. Only this
            // installed-server guard is known to run before queue/db/build admission.
            if (method == "prompt.submit" && sent &&
                !(refused.code == com.rumi.hermesvoice.core.SessionInUseException.CODE && refused.reason == com.rumi.hermesvoice.core.SessionInUseException.REASON)) {
                throw com.rumi.hermesvoice.core.HermesPromptUncertainException(refused)
            }
            throw refused
        } catch (error: Exception) {
            if (method == "prompt.submit" && sent) throw com.rumi.hermesvoice.core.HermesPromptUncertainException(error)
            throw error
        } finally {
            pending.remove(id)
        }
    }

    /** Subscribe BEFORE submitting so no event for [sessionId] (the runtime id) can be missed. */
    override fun subscribe(sessionId: String): GatewaySubscription {
        val subscription = GatewaySubscription(sessionId, Channel(Channel.UNLIMITED)) { sub ->
            subscribers[sub.sessionId]?.remove(sub)
        }
        subscription.origin = this
        subscribers.getOrPut(sessionId) { CopyOnWriteArrayList() }.add(subscription)
        failure?.let { subscription.channel.close(it) }
        return subscription
    }

    override fun close() {
        fail(HermesProtocolException("gateway connection closed by client"))
        if (::socket.isInitialized) socket.close(1000, null)
    }

    private fun handleFrame(text: String) {
        val frame = try {
            JSONObject(text)
        } catch (_: JSONException) {
            return
        }
        val id = frame.opt("id")?.takeUnless { it == JSONObject.NULL }?.toString()
        if (id != null && (frame.has("result") || frame.has("error"))) {
            val waiter = pending[id] ?: return
            val error = frame.optJSONObject("error")
            if (frame.has("error")) {
                if (error == null || frame.has("result")) {
                    waiter.completeExceptionally(HermesProtocolException("malformed gateway RPC response"))
                    return
                }
                val reason = (error.optJSONObject("data")?.opt("reason") as? String).orEmpty().take(MAX_REASON_CHARS)
                // Do not coerce a string/fractional code into the exact known refusal's authority.
                val code = error.opt("code") as? Int ?: 0
                waiter.completeExceptionally(HermesRpcException(code, error.optString("message").take(300), reason))
            } else {
                waiter.complete(frame.optJSONObject("result") ?: JSONObject())
            }
            return
        }
        if (frame.optString("method") != "event") return
        val params = frame.optJSONObject("params") ?: return
        val event = GatewayEvent(params.optString("type"), params.optString("session_id"), params.optJSONObject("payload"), params.optLong("seq", 0L))
        if (event.type == "gateway.ready") {
            replayEpoch = event.payload?.optString("replay_epoch").orEmpty()
            ready.complete(Unit)
            return
        }
        subscribers[event.sessionId]?.forEach { it.channel.trySend(event) }
    }

    private fun fail(error: Throwable) {
        if (failure == null) failure = error
        val cause = failure ?: error
        ready.completeExceptionally(cause)
        pending.values.forEach { it.completeExceptionally(cause) }
        subscribers.values.forEach { list -> list.forEach { it.channel.close(cause) } }
    }

    private fun closeError(code: Int, reason: String): HermesException = when (code) {
        4401 -> HermesAuthRequiredException("gateway rejected the WebSocket credential")
        4403 -> com.rumi.hermesvoice.core.HermesGatewayClosedException(4403, "gateway refused the connection (host/origin or embedded chat disabled)")
        else -> com.rumi.hermesvoice.core.HermesGatewayClosedException(code, "gateway closed ($code${if (reason.isBlank()) "" else ": ${reason.take(120)}"})")
    }

    companion object {
        const val PROTOCOL = "hermes-gateway-v1"
        const val TICKET_PROTOCOL_PREFIX = "hermes-gateway-ticket."
        const val DEFAULT_CALL_TIMEOUT_MS = 30_000L
        private const val MAX_REASON_CHARS = 64

        suspend fun open(http: OkHttpClient, url: HttpUrl, ticket: String, readyTimeoutMs: Long = 15_000): HermesGatewayConnection {
            require(ticket.isNotBlank() && ticket.none { it == ',' || it.isWhitespace() }) { "invalid ws ticket" }
            val connection = HermesGatewayConnection()
            val request = Request.Builder().url(url)
                .header("Sec-WebSocket-Protocol", "$PROTOCOL, $TICKET_PROTOCOL_PREFIX$ticket")
                .build()
            connection.socket = http.newWebSocket(request, connection.listener)
            try {
                withTimeout(readyTimeoutMs) { connection.ready.await() }
            } catch (error: Throwable) {
                connection.close()
                if (error is TimeoutCancellationException) throw HermesProtocolException("gateway.ready not received", error)
                throw error
            }
            return connection
        }
    }
}

/** Reuses one gateway socket; a dead socket is replaced using a freshly minted single-use ticket. */
class HermesGatewayConnector(
    private val dashboard: HermesDashboardClient,
    private val http: OkHttpClient,
) {
    private val lock = Mutex()
    private var connection: HermesGatewayConnection? = null

    suspend fun connection(): HermesGatewayConnection = lock.withLock {
        connection?.takeIf { it.isOpen }?.let { return it }
        val ticket = dashboard.mintWsTicket()
        HermesGatewayConnection.open(http, dashboard.endpoint.route("api/ws"), ticket).also { connection = it }
    }

    fun close() {
        connection?.close()
        connection = null
    }
}

