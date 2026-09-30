package com.rumi.hermesvoice.core.watchlink

import com.rumi.hermesvoice.core.HermesAuthRequiredException
import com.rumi.hermesvoice.core.HermesException
import com.rumi.hermesvoice.core.SessionNotOwnedException
import com.rumi.hermesvoice.core.sessions.AppSessionRepository
import com.rumi.hermesvoice.core.sessions.OwnedRole
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/**
 * Watch conversation reader over the Data Layer. The Watch never calls Hermes: it asks the Phone
 * for the app-owned, unarchived conversations and for pages of one conversation's history, and
 * the Phone answers from the same ownership-checked repository its own UI uses.
 *
 * - `/hv/v1/reader/request`  message, Watch → Phone: [ReaderRequest]
 * - `/hv/v1/reader/response` message, Phone → Watch (the requesting node only): [ReaderResponse]
 *
 * Reading is side-effect free: it never submits, archives, or moves the playback route.
 */
object WatchReaderLimits {
    const val MAX_SESSIONS = 24
    const val MAX_PAGE_MESSAGES = 20
    const val MAX_TEXT_CHARS = 1_200
    const val MAX_TITLE_CHARS = 80
    const val MAX_PREVIEW_CHARS = 120
    const val MAX_REQUEST_BYTES = 1_024

    /** Well under the Data Layer message size limit (~100 KB). */
    const val MAX_RESPONSE_BYTES = 60_000

    const val MAX_CACHED_SESSIONS = 4
    const val MAX_MESSAGES_PER_SESSION = 200
    const val REQUEST_TIMEOUT_MS = 15_000L
}

enum class ReaderKind(val wire: String) {
    SESSIONS("sessions"), HISTORY("history");

    companion object {
        fun parse(raw: String?): ReaderKind? = values().firstOrNull { it.wire == raw }
    }
}

/** Stable error codes the Watch renders; never server text. */
object ReaderError {
    const val NOT_CONFIGURED = "not_configured"
    const val SIGN_IN_REQUIRED = "sign_in_required"
    const val NOT_OWNED = "not_owned"
    const val UNAVAILABLE = "unavailable"
    const val TIMEOUT = "timeout"
    const val OFFLINE = "offline"
}

data class ReaderRequest(
    val reqId: String,
    val kind: ReaderKind,
    val sessionId: String? = null,
    val offset: Int = 0,
    val limit: Int = WatchReaderLimits.MAX_PAGE_MESSAGES,
) {
    fun encode(): ByteArray = JSONObject().put("v", 1).put("req_id", reqId).put("kind", kind.wire)
        .apply { if (sessionId != null) put("session_id", sessionId) }
        .put("offset", offset).put("limit", limit).toString().toByteArray(StandardCharsets.UTF_8)

    companion object {
        private val SESSION_ID = Regex("^[A-Za-z0-9_.:-]{1,128}$")

        fun decode(bytes: ByteArray): ReaderRequest? {
            if (bytes.size > WatchReaderLimits.MAX_REQUEST_BYTES) return null
            val json = runCatching { JSONObject(String(bytes, StandardCharsets.UTF_8)) }.getOrNull() ?: return null
            if (json.optInt("v") != 1) return null
            val reqId = json.optString("req_id").takeIf(WatchLinkPaths::isValidTurnId) ?: return null
            val kind = ReaderKind.parse(json.optString("kind")) ?: return null
            val sessionId = json.optString("session_id").takeIf { it.isNotEmpty() }
            if (kind == ReaderKind.HISTORY && (sessionId == null || !SESSION_ID.matches(sessionId))) return null
            return ReaderRequest(reqId, kind, if (kind == ReaderKind.HISTORY) sessionId else null,
                json.optInt("offset", 0).coerceAtLeast(0),
                json.optInt("limit", WatchReaderLimits.MAX_PAGE_MESSAGES).coerceIn(1, WatchReaderLimits.MAX_PAGE_MESSAGES))
        }
    }
}

data class ReaderSessionRow(val id: String, val title: String, val alias: String, val preview: String, val lastActive: Double) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("alias", alias).put("preview", preview)
        .put("last_active", lastActive)

    companion object {
        fun fromJson(json: JSONObject) = ReaderSessionRow(json.getString("id"), json.optString("title"), json.optString("alias"),
            json.optString("preview"), json.optDouble("last_active", 0.0).takeUnless { it.isNaN() } ?: 0.0)
    }
}

/** One transcript row. [sent] (the user's own message) is drawn right/blue; received rows left/grey. */
data class ReaderMessageRow(val rowId: Long, val role: String, val text: String, val truncated: Boolean) {
    val sent: Boolean get() = role == "user"

    fun toJson(): JSONObject = JSONObject().put("row_id", rowId).put("role", role).put("text", text).put("truncated", truncated)

    companion object {
        fun fromJson(json: JSONObject) = ReaderMessageRow(json.getLong("row_id"), json.getString("role"), json.optString("text"),
            json.optBoolean("truncated"))
    }
}

data class ReaderResponse(
    val reqId: String,
    val kind: ReaderKind,
    val ok: Boolean,
    val error: String? = null,
    val sessions: List<ReaderSessionRow> = emptyList(),
    val sessionId: String? = null,
    val messages: List<ReaderMessageRow> = emptyList(),
    /** Raw-row offset this page was read at; [nextOffset] is where the next older page starts. */
    val offset: Int = 0,
    val nextOffset: Int = 0,
    val hasOlder: Boolean = false,
) {
    fun encode(): ByteArray = JSONObject().put("v", 1).put("req_id", reqId).put("kind", kind.wire).put("ok", ok)
        .apply {
            if (error != null) put("error", error)
            if (sessionId != null) put("session_id", sessionId)
        }
        .put("sessions", JSONArray().apply { sessions.forEach { put(it.toJson()) } })
        .put("messages", JSONArray().apply { messages.forEach { put(it.toJson()) } })
        .put("offset", offset).put("next_offset", nextOffset).put("has_older", hasOlder)
        .toString().toByteArray(StandardCharsets.UTF_8)

    companion object {
        fun decode(bytes: ByteArray): ReaderResponse? = runCatching {
            if (bytes.size > WatchReaderLimits.MAX_RESPONSE_BYTES * 2) return null
            val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
            if (json.optInt("v") != 1) return null
            val reqId = json.getString("req_id").takeIf(WatchLinkPaths::isValidTurnId) ?: return null
            val kind = ReaderKind.parse(json.optString("kind")) ?: return null
            val sessions = json.optJSONArray("sessions") ?: JSONArray()
            val messages = json.optJSONArray("messages") ?: JSONArray()
            ReaderResponse(
                reqId, kind, json.getBoolean("ok"), json.optString("error").ifEmpty { null },
                (0 until minOf(sessions.length(), WatchReaderLimits.MAX_SESSIONS)).map { ReaderSessionRow.fromJson(sessions.getJSONObject(it)) },
                json.optString("session_id").ifEmpty { null },
                (0 until minOf(messages.length(), WatchReaderLimits.MAX_PAGE_MESSAGES)).map { ReaderMessageRow.fromJson(messages.getJSONObject(it)) },
                json.optInt("offset"), json.optInt("next_offset"), json.optBoolean("has_older"),
            )
        }.getOrNull()
    }
}

/**
 * Phone side: answers one reader request from [AppSessionRepository], failing closed on anything
 * that is not an app-owned, unarchived conversation (same registry + server `source` checks as the
 * Phone's own history). It never touches the voice orchestrator, so it cannot change the playback
 * route (latest voice sender) or which conversation voice turns go to.
 */
class PhoneReaderService(private val sessions: AppSessionRepository) {
    /** The encoded response, or null for a request that cannot be decoded (it is ignored). */
    suspend fun handle(requestBytes: ByteArray): ByteArray? {
        val request = ReaderRequest.decode(requestBytes) ?: return null
        val response = try {
            when (request.kind) {
                ReaderKind.SESSIONS -> sessionsResponse(request)
                ReaderKind.HISTORY -> historyResponse(request)
            }
        } catch (_: SessionNotOwnedException) {
            failure(request, ReaderError.NOT_OWNED)
        } catch (_: HermesAuthRequiredException) {
            failure(request, ReaderError.SIGN_IN_REQUIRED)
        } catch (_: HermesException) {
            failure(request, ReaderError.UNAVAILABLE)
        } catch (_: IOException) {
            failure(request, ReaderError.UNAVAILABLE)
        }
        return fit(response)
    }

    private fun failure(request: ReaderRequest, error: String) =
        ReaderResponse(request.reqId, request.kind, ok = false, error = error, sessionId = request.sessionId)

    private suspend fun sessionsResponse(request: ReaderRequest): ReaderResponse {
        val rows = sessions.listConversations(archived = false)
            .filter { !it.owned.archived && !it.stored.archived }
            .sortedByDescending { it.stored.lastActive }
            .take(WatchReaderLimits.MAX_SESSIONS)
            .map {
                ReaderSessionRow(it.owned.storedSessionId, it.stored.title.ifBlank { it.owned.title }.take(WatchReaderLimits.MAX_TITLE_CHARS),
                    it.owned.alias, it.stored.preview.take(WatchReaderLimits.MAX_PREVIEW_CHARS), it.stored.lastActive)
            }
        return ReaderResponse(request.reqId, request.kind, ok = true, sessions = rows)
    }

    private suspend fun historyResponse(request: ReaderRequest): ReaderResponse {
        val sessionId = request.sessionId!!
        val owned = sessions.registry.find(sessionId)
        if (owned == null || owned.role != OwnedRole.CONVERSATION || owned.archived) {
            throw SessionNotOwnedException("not an active conversation of this app")
        }
        val page = sessions.history(sessionId, request.limit, request.offset)
        val messages = page.messages.map { ReaderMessageRow(it.rowId, it.role, it.text, truncated = false) }
        return ReaderResponse(request.reqId, request.kind, ok = true, sessionId = sessionId, messages = messages,
            offset = request.offset, nextOffset = request.offset + page.returned, hasOlder = page.returned >= request.limit)
    }

    /** Shortens message text step by step until the encoded response fits one Data Layer message. */
    private fun fit(response: ReaderResponse): ByteArray {
        var limit = WatchReaderLimits.MAX_TEXT_CHARS
        while (true) {
            val clipped = response.copy(messages = response.messages.map { row ->
                if (row.text.length <= limit) row else row.copy(text = row.text.take(limit - 1) + "…", truncated = true)
            })
            val bytes = clipped.encode()
            if (bytes.size <= WatchReaderLimits.MAX_RESPONSE_BYTES || limit <= MIN_FIT_CHARS) return bytes
            limit /= 2
        }
    }

    companion object {
        private const val MIN_FIT_CHARS = 60
    }
}

enum class ReaderSurface { CHAT, SESSIONS }

enum class LoadStatus { IDLE, LOADING, READY, ERROR, OFFLINE }

data class ReaderSessionsState(
    val rows: List<ReaderSessionRow> = emptyList(),
    val status: LoadStatus = LoadStatus.IDLE,
    val error: String? = null,
    val pendingReqId: String? = null,
)

/** Cached history of one conversation, oldest first, de-duplicated by row id. */
data class ReaderHistory(
    val sessionId: String,
    val messages: List<ReaderMessageRow> = emptyList(),
    /** Raw-row offset of the next older page; stale offsets only cause overlap (de-duplicated), never gaps. */
    val nextOlderOffset: Int = 0,
    val hasOlder: Boolean = false,
    val status: LoadStatus = LoadStatus.IDLE,
    val error: String? = null,
    val pendingReqId: String? = null,
    val loadingOlder: Boolean = false,
) {
    /** Older history exists but the Watch cache is full; the Phone shows it. */
    val olderBlocked: Boolean get() = hasOlder && messages.size + WatchReaderLimits.MAX_PAGE_MESSAGES > WatchReaderLimits.MAX_MESSAGES_PER_SESSION
}

/**
 * The Watch reader's pure state. Every request carries an id; a response is applied only if it
 * answers the request currently pending for that list or conversation, so late, duplicate or
 * foreign responses are dropped. Selecting a conversation only changes what is shown.
 */
data class WatchReaderState(
    val surface: ReaderSurface = ReaderSurface.CHAT,
    val sessions: ReaderSessionsState = ReaderSessionsState(),
    val selectedSessionId: String? = null,
    /** Most recently used last; at most [WatchReaderLimits.MAX_CACHED_SESSIONS]. */
    val histories: List<ReaderHistory> = emptyList(),
) {
    fun historyFor(sessionId: String): ReaderHistory? = histories.firstOrNull { it.sessionId == sessionId }

    val selectedHistory: ReaderHistory? get() = selectedSessionId?.let(::historyFor)

    fun toggleSurface(): WatchReaderState =
        copy(surface = if (surface == ReaderSurface.CHAT) ReaderSurface.SESSIONS else ReaderSurface.CHAT)

    fun showSurface(target: ReaderSurface): WatchReaderState = copy(surface = target)

    fun requestSessions(reqId: String): WatchReaderState =
        copy(sessions = sessions.copy(status = LoadStatus.LOADING, error = null, pendingReqId = reqId))

    /** Opens [sessionId] in chat and asks for its latest page. */
    fun select(sessionId: String, reqId: String): WatchReaderState {
        val history = (historyFor(sessionId) ?: ReaderHistory(sessionId))
            .copy(status = LoadStatus.LOADING, error = null, pendingReqId = reqId, loadingOlder = false)
        return copy(surface = ReaderSurface.CHAT, selectedSessionId = sessionId).putHistory(history)
    }

    /** Re-reads the latest page of the selected conversation; null when nothing is selected. */
    fun refreshLatest(reqId: String): WatchReaderState? {
        val history = selectedHistory ?: return null
        return putHistory(history.copy(status = LoadStatus.LOADING, error = null, pendingReqId = reqId, loadingOlder = false))
    }

    /** Asks for the next older page; null when there is none, the cache is full, or a load is pending. */
    fun requestOlder(reqId: String): WatchReaderState? {
        val history = selectedHistory ?: return null
        if (!history.hasOlder || history.olderBlocked || history.pendingReqId != null) return null
        return putHistory(history.copy(status = LoadStatus.LOADING, error = null, pendingReqId = reqId, loadingOlder = true))
    }

    /** The request for the selected conversation that [reqId] would be: latest or older. */
    fun pendingHistoryRequest(reqId: String): ReaderHistory? = histories.firstOrNull { it.pendingReqId == reqId }

    fun onResponse(response: ReaderResponse): WatchReaderState = when (response.kind) {
        ReaderKind.SESSIONS -> {
            if (response.reqId != sessions.pendingReqId) {
                this
            } else if (response.ok) {
                copy(sessions = ReaderSessionsState(response.sessions.take(WatchReaderLimits.MAX_SESSIONS), LoadStatus.READY))
            } else {
                copy(sessions = sessions.copy(status = LoadStatus.ERROR, error = response.error, pendingReqId = null))
            }
        }
        ReaderKind.HISTORY -> {
            val history = histories.firstOrNull { it.pendingReqId == response.reqId && it.sessionId == response.sessionId }
            when {
                history == null || response.sessionId != selectedSessionId -> this
                response.ok -> putHistory(merge(history, response))
                response.error == ReaderError.NOT_OWNED -> copy(selectedSessionId = null, surface = ReaderSurface.SESSIONS,
                    histories = histories.filterNot { it.sessionId == history.sessionId })
                else -> putHistory(history.copy(status = LoadStatus.ERROR, error = response.error, pendingReqId = null, loadingOlder = false))
            }
        }
    }

    fun onSendFailed(reqId: String): WatchReaderState = settleFailure(reqId, LoadStatus.OFFLINE, ReaderError.OFFLINE)

    fun onTimeout(reqId: String): WatchReaderState = settleFailure(reqId, LoadStatus.ERROR, ReaderError.TIMEOUT)

    private fun settleFailure(reqId: String, status: LoadStatus, error: String): WatchReaderState {
        if (sessions.pendingReqId == reqId) return copy(sessions = sessions.copy(status = status, error = error, pendingReqId = null))
        val history = pendingHistoryRequest(reqId) ?: return this
        return putHistory(history.copy(status = status, error = error, pendingReqId = null, loadingOlder = false))
    }

    private fun merge(history: ReaderHistory, page: ReaderResponse): ReaderHistory {
        val settled = history.copy(status = LoadStatus.READY, error = null, pendingReqId = null, loadingOlder = false)
        val known = history.messages.map { it.rowId }.toHashSet()
        if (history.loadingOlder) {
            val older = page.messages.filter { it.rowId !in known }
            return settled.copy(messages = (older + history.messages).sortedBy { it.rowId }, nextOlderOffset = page.nextOffset,
                hasOlder = page.hasOlder)
        }
        // Latest page. A full page that shares no row with the cache may hide a gap: start over from it.
        val overlaps = page.messages.any { it.rowId in known }
        val merged = (history.messages + page.messages.filter { it.rowId !in known }).sortedBy { it.rowId }
        val fresh = history.messages.isEmpty() || (!overlaps && page.hasOlder)
        return if (fresh || merged.size > WatchReaderLimits.MAX_MESSAGES_PER_SESSION) {
            settled.copy(messages = page.messages.sortedBy { it.rowId }, nextOlderOffset = page.nextOffset, hasOlder = page.hasOlder)
        } else {
            settled.copy(messages = merged)
        }
    }

    private fun putHistory(history: ReaderHistory): WatchReaderState {
        val others = histories.filterNot { it.sessionId == history.sessionId }
        val keep = others.takeLast(WatchReaderLimits.MAX_CACHED_SESSIONS - 1)
        return copy(histories = keep + history)
    }
}

/** Keeps the reader where the user left it; follows new rows only when the newest row was already visible. */
object ReaderScrollPolicy {
    fun followLatest(wasAtLatest: Boolean, before: List<Long>, after: List<Long>): Boolean {
        if (before.isEmpty()) return after.isNotEmpty()
        return wasAtLatest && after.lastOrNull() != before.lastOrNull()
    }
}

enum class SwipeDirection { RIGHT_TO_LEFT, LEFT_TO_RIGHT }

enum class ReaderAction { TOGGLE_SURFACE, BACKGROUND_APP }

/**
 * Horizontal swipe → reader action, as confirmed by the product owner: right-to-left (dx < 0)
 * toggles the session browser and the conversation; left-to-right (dx > 0) sends the app to the
 * background (the task and its state stay alive). Vertical movement always scrolls.
 */
object ReaderGestureMapping {
    const val STATUS = "CONFIRMED"

    fun action(direction: SwipeDirection): ReaderAction = when (direction) {
        SwipeDirection.RIGHT_TO_LEFT -> ReaderAction.TOGGLE_SURFACE
        SwipeDirection.LEFT_TO_RIGHT -> ReaderAction.BACKGROUND_APP
    }
}

/**
 * One pointer's horizontal-swipe arbitration against native scrolling and taps. It claims the
 * gesture (so the caller consumes further movement) only once horizontal travel passes the touch
 * slop while dominating vertical travel; anything another handler consumed first (a list scroll)
 * or that starts vertical is rejected for the rest of the gesture.
 */
class SwipeTracker(
    private val touchSlopPx: Float,
    private val minDistancePx: Float,
    private val dominance: Float = 2f,
) {
    private enum class State { TRACKING, CLAIMED, REJECTED }

    private var state = State.TRACKING

    /** Cumulative travel since touch-down. Returns true while this tracker owns the gesture. */
    fun onMove(dx: Float, dy: Float, consumedByOther: Boolean): Boolean {
        val ax = kotlin.math.abs(dx)
        val ay = kotlin.math.abs(dy)
        if (state == State.TRACKING) {
            state = when {
                consumedByOther -> State.REJECTED
                ay > touchSlopPx && ay >= ax -> State.REJECTED
                ax > touchSlopPx && ax > dominance * ay -> State.CLAIMED
                else -> State.TRACKING
            }
        }
        return state == State.CLAIMED
    }

    fun onUp(dx: Float, dy: Float): SwipeDirection? {
        val claimed = state == State.CLAIMED
        state = State.TRACKING
        val ax = kotlin.math.abs(dx)
        if (!claimed || ax < minDistancePx || ax <= dominance * kotlin.math.abs(dy)) return null
        return if (dx < 0) SwipeDirection.RIGHT_TO_LEFT else SwipeDirection.LEFT_TO_RIGHT
    }
}
