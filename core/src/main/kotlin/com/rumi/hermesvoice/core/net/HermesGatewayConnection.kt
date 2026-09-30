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
}

/** One `{"method":"event","params":{type, session_id, payload}}` frame from the tui_gateway. */
data class GatewayEvent(val type: String, val sessionId: String, val payload: JSONObject?)

class GatewaySubscription(
    val sessionId: String,
    val channel: Channel<GatewayEvent>,
    private val onClose: (GatewaySubscription) -> Unit,
) : Closeable {
    val events: ReceiveChannel<GatewayEvent> get() = channel
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
        failure?.let { throw it }
        val id = "r${nextId.getAndIncrement()}"
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        try {
            val frame = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
            if (!socket.send(frame.toString())) throw failure ?: HermesProtocolException("gateway socket is closed")
            return try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (timeout: TimeoutCancellationException) {
                throw HermesProtocolException("gateway call $method timed out", timeout)
            }
        } finally {
            pending.remove(id)
        }
    }

    /** Subscribe BEFORE submitting so no event for [sessionId] (the runtime id) can be missed. */
    override fun subscribe(sessionId: String): GatewaySubscription {
        val subscription = GatewaySubscription(sessionId, Channel(Channel.UNLIMITED)) { sub ->
            subscribers[sub.sessionId]?.remove(sub)
        }
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
            if (error != null) {
                waiter.completeExceptionally(HermesRpcException(error.optInt("code"), error.optString("message").take(300)))
            } else {
                waiter.complete(frame.optJSONObject("result") ?: JSONObject())
            }
            return
        }
        if (frame.optString("method") != "event") return
        val params = frame.optJSONObject("params") ?: return
        val event = GatewayEvent(params.optString("type"), params.optString("session_id"), params.optJSONObject("payload"))
        if (event.type == "gateway.ready") {
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
        4403 -> HermesProtocolException("gateway refused the connection (host/origin or embedded chat disabled)")
        else -> HermesProtocolException("gateway closed ($code${if (reason.isBlank()) "" else ": ${reason.take(120)}"})")
    }

    companion object {
        const val PROTOCOL = "hermes-gateway-v1"
        const val TICKET_PROTOCOL_PREFIX = "hermes-gateway-ticket."
        const val DEFAULT_CALL_TIMEOUT_MS = 30_000L

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

