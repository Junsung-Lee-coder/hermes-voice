package com.rumi.hermesvoice.core.net

import com.rumi.hermesvoice.core.HermesProtocolException
import com.rumi.hermesvoice.core.voice.RecipientEvent
import java.util.Base64
import java.util.Locale
import com.rumi.hermesvoice.core.HermesAuthRequiredException
import com.rumi.hermesvoice.core.HermesException
import com.rumi.hermesvoice.core.HermesRpcException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** A file the user picked on the Phone, sent through Hermes's own attach RPCs (never a gateway-visible path). */
class OutgoingAttachment(val name: String, val mimeType: String, val bytes: ByteArray) {
    override fun toString(): String = "OutgoingAttachment(name=$name, mimeType=$mimeType, bytes=${bytes.size})"
}

enum class AttachmentKind { IMAGE, FILE }

/**
 * Maps a picked file onto the verified Hermes attachment mechanisms:
 * - images → `image.attach_bytes` (queued on the live session; consumed by the next `prompt.submit`,
 *   snapshotted with the prompt if the session is busy and the prompt is queued),
 * - everything else → `file.attach` with a base64 `data_url` (staged into the session's
 *   `attachments/` dir; the returned `@file:` ref is prepended to the prompt text).
 * `POST /api/files/upload` is deliberately NOT used: it stores a file but does not attach it to a turn.
 */
object AttachmentPolicy {
    /** Per file. Kept under the pre-v5 gateway's 16 MiB WebSocket frame after base64 inflation. */
    const val MAX_BYTES = 10 * 1024 * 1024
    const val MAX_ATTACHMENTS = 6
    private val IMAGE_MIMES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
    private val CONTROL = Regex("[\\p{Cntrl}]+")

    fun kind(attachment: OutgoingAttachment): AttachmentKind =
        if (attachment.mimeType.substringBefore(';').trim().lowercase(Locale.ROOT) in IMAGE_MIMES) AttachmentKind.IMAGE else AttachmentKind.FILE

    /** Basename only, no control characters; Hermes sanitizes again server-side. */
    fun safeName(raw: String): String =
        raw.replace('\\', '/').substringAfterLast('/').replace(CONTROL, "_").trim().trim('.').take(120).ifEmpty { "attachment" }

    fun validate(attachments: List<OutgoingAttachment>) {
        require(attachments.size <= MAX_ATTACHMENTS) { "at most $MAX_ATTACHMENTS attachments per message" }
        attachments.forEach { attachment ->
            require(attachment.bytes.isNotEmpty()) { "${safeName(attachment.name)} is empty" }
            require(attachment.bytes.size <= MAX_BYTES) { "${safeName(attachment.name)} exceeds ${MAX_BYTES / (1024 * 1024)} MiB" }
        }
    }
}

data class CreatedSession(
    val runtimeId: String,
    val storedSessionId: String,
    /** What `session.create` reported for the session's model and provider (its `info`), "" when absent. */
    val model: String = "",
    val provider: String = "",
)

/**
 * A model, provider and reasoning effort pinned to ONE session through tui_gateway's own per-session
 * mechanisms: `session.create`'s `model`/`provider`/`reasoning_effort` overrides (methods_session.
 * _create_overrides: "never a global config write"), or `config.set` key "model" with the
 * `--session` flag on that session's live runtime id (model_switch: `--session` never persists to
 * config.yaml; `--reasoning` rides with the pick and shares its scope). Never the "reasoning" key,
 * which writes the profile's config when its session isn't live.
 */
data class SessionRuntimeSpec(val model: String, val provider: String, val reasoningEffort: String) {
    /** The `config.set` value: `<model> --provider <p> --reasoning <effort> --session`. */
    fun switchValue(): String = "$model --provider $provider --reasoning $reasoningEffort --session"

    /** A stable key recorded once this spec was verified on a session. */
    val key: String get() = "$model|$provider|$reasoningEffort"

    /**
     * Whether a session's reported `info` shows this runtime: model and provider must match;
     * the effort must match when it is reported (a session whose agent isn't built yet omits it).
     */
    fun matches(info: JSONObject?, requireEffort: Boolean): Boolean {
        if (info == null || info.optString("model") != model || info.optString("provider") != provider) return false
        val effort = info.optString("reasoning_effort")
        return if (requireEffort) effort == reasoningEffort else effort.isEmpty() || effort == reasoningEffort
    }
}

/** How [HermesConversationPort.ensureRuntime] went: whether it switched, and how long it waited for the session's agent. */
data class RuntimeSetup(val switched: Boolean, val polls: Int, val waitedMs: Long)

/**
 * The limits of one [GatewayConversationPort.ensureRuntime], all on one monotonic clock. The defaults are the
 * production values; tests inject shorter ones.
 */
data class RuntimeSetupLimits(
    /** The whole setup: connecting, waiting for the agent, at most one switch, and reading it back. */
    val budgetMs: Long = GatewayConversationPort.RUNTIME_SETUP_BUDGET_MS,
    /** Between two reads of a session whose agent isn't built. */
    val pollMs: Long = GatewayConversationPort.RUNTIME_POLL_MS,
    /** Time that must be left before a switch of a built agent is sent; [switchReadbackMs] of it stays for the readback. */
    val switchReserveMs: Long = 5_000L,
    val switchReadbackMs: Long = 2_000L,
) {
    init {
        require(budgetMs > 0) { "budgetMs must be positive" }
        require(pollMs > 0) { "pollMs must be positive (no busy polling)" }
        require(switchReadbackMs > 0 && switchReserveMs > switchReadbackMs) { "switchReserveMs must exceed a positive switchReadbackMs" }
    }
}

/**
 * A per-session runtime could not be set or proven on the server ([reason]: a short machine word, then detail written
 * by the app, never the server's own error text). [stage] is where it stopped (connect, poll, switch, readback);
 * [write] whether a switch was sent ("none", "switch"). Once one was sent its effect may exist even when the answer
 * never came.
 */
class SessionRuntimeException(val reason: String, message: String, val stage: String = "", val write: String = WRITE_NONE) : HermesException(message) {
    companion object {
        const val WRITE_NONE = "none"
        const val WRITE_SWITCH = "switch"
    }
}

/** Why following a session's later replies ended ([SubmittedTurn.collectLater]). */
enum class LaterEnd {
    /** The follow window passed. */
    WINDOW,

    /** This app sent that session something new (its reply is that turn's own). */
    SUPERSEDED,

    /** The gateway connection dropped; nothing re-subscribes, and a reconnect does not replay. */
    DISCONNECTED,

    /** Released (or there was nothing to follow). */
    RELEASED,
}

/** A prompt accepted by a Hermes session; [collect] streams that turn's recipient events until terminal. */
interface SubmittedTurn {
    /** Hermes's `prompt.submit` status (`streaming`, `queued`, ...). */
    val submitStatus: String

    /**
     * False when Hermes accepted the prompt but its events cannot be attributed to it with
     * certainty; [collect] then refuses (fail closed) rather than speak another turn's reply.
     */
    val attributable: Boolean

    suspend fun collect(timeoutMs: Long, onEvent: suspend (RecipientEvent) -> Unit)

    /**
     * Metadata about what [collect] saw so far, for diagnostics only: counts of event types,
     * whether and when the turn was recognized as this one's, how it ended. Never any text or id.
     */
    fun diagnostics(): String = ""

    /**
     * After [collect] returned this turn's reply: the destination session's LATER turns for up to
     * [windowMs] (Hermes delivers a background-process or async-delegation completion, or a goal
     * follow-up, as a new turn on the same session after the submitted one completed). [onLater]
     * gets each later turn's final reply once. It ends at the window, when this app submits anything
     * else to that session, when the connection drops, or when [release]d; then it releases.
     * By default there is nothing to follow.
     */
    suspend fun collectLater(windowMs: Long, onLater: suspend (RecipientEvent.Complete) -> Unit): LaterEnd {
        release()
        return LaterEnd.RELEASED
    }

    fun release()
}

interface HermesConversationPort {
    /**
     * `session.create` with the app's [source] tag and a hidden seed row, so the DB row (with
     * source and title) exists immediately instead of on the first prompt.
     */
    suspend fun create(source: String, title: String, seedInstruction: String, hidden: Boolean): CreatedSession

    /** As [create], with [runtime] pinned to the new session only (see [SessionRuntimeSpec]). */
    suspend fun create(source: String, title: String, seedInstruction: String, hidden: Boolean, runtime: SessionRuntimeSpec): CreatedSession =
        throw SessionRuntimeException("unsupported", "this connection can't pin a session's model")

    /**
     * Makes the EXISTING stored session [storedSessionId] run on [runtime], for that session only,
     * and proves it by reading the session back ([RuntimeSetup.switched]: false when it already was).
     * A session whose build is starting is waited for first, within one bounded budget; nothing is ever
     * sent to a session that isn't built (one that may have failed, or not started, ends the setup).
     * Throws [SessionRuntimeException] when the session isn't confirmed built, the server refused, asked
     * for confirmation, deferred it, reads back anything else or the outcome is unknown: nothing is
     * retried here and nothing falls back silently.
     */
    suspend fun ensureRuntime(storedSessionId: String, runtime: SessionRuntimeSpec): RuntimeSetup =
        throw SessionRuntimeException("unsupported", "this connection can't pin a session's model")

    suspend fun submit(storedSessionId: String, text: String, attachments: List<OutgoingAttachment> = emptyList()): SubmittedTurn
}

/**
 * `session.resume` (stored id → runtime id), optional attach RPCs, then `prompt.submit` with
 * `queued: true`, so a busy destination queues the turn instead of redirecting/steering its live
 * turn. Events carry no per-prompt id, so attribution is by order: a `streaming` submit owns the
 * next `message.start`; a `queued` submit owns the turn after the in-flight one, and only when the
 * resume snapshot shows no other queued prompt. Anything else is returned as delivered but not
 * [SubmittedTurn.attributable].
 */
class GatewayConversationPort(
    private val profile: String? = null,
    /** One [ensureRuntime]: connecting, waiting for the agent, at most one switch and its readback (monotonic). */
    private val runtimeLimits: RuntimeSetupLimits = RuntimeSetupLimits(),
    private val connections: suspend () -> GatewayRpc,
) : HermesConversationPort {
    /** Only the total budget differs from production's limits. */
    constructor(profile: String?, runtimeSetupBudgetMs: Long, connections: suspend () -> GatewayRpc) :
        this(profile, RuntimeSetupLimits(budgetMs = runtimeSetupBudgetMs), connections)

    /** Turns submitted here whose subscription is still open, per runtime session id. */
    private val open = ConcurrentHashMap<String, MutableSet<GatewaySubmittedTurn>>()

    /** Orders this app's `prompt.submit` calls per runtime session, so the position of a queued turn is counted in submit order. Held for the RPC only, never for a reply. */
    private val submitOrder = ConcurrentHashMap<String, Mutex>()

    /** Every RPC is stamped with the same `profile` the REST calls use, so both hit one session store. */
    private fun params(): JSONObject = JSONObject().apply { if (!profile.isNullOrBlank()) put("profile", profile) }

    override suspend fun create(source: String, title: String, seedInstruction: String, hidden: Boolean): CreatedSession =
        createWith(source, title, seedInstruction, hidden, null)

    override suspend fun create(source: String, title: String, seedInstruction: String, hidden: Boolean, runtime: SessionRuntimeSpec): CreatedSession =
        createWith(source, title, seedInstruction, hidden, runtime)

    private suspend fun createWith(source: String, title: String, seedInstruction: String, hidden: Boolean, runtime: SessionRuntimeSpec?): CreatedSession {
        val params = params().put("source", source).put("title", title).put("hidden", hidden)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", seedInstruction)
                .put("display_kind", "hidden")))
        // Per-session overrides of THIS session only (methods_session._create_overrides); absent: the profile's default.
        runtime?.let { params.put("model", it.model).put("provider", it.provider).put("reasoning_effort", it.reasoningEffort) }
        val result = connections().call("session.create", params)
        val runtimeId = result.optString("session_id")
        val storedId = result.optString("stored_session_id")
        if (runtimeId.isBlank() || storedId.isBlank()) throw HermesProtocolException("session.create returned no session ids")
        val info = result.optJSONObject("info")
        return CreatedSession(runtimeId, storedId, info?.optString("model").orEmpty(), info?.optString("provider").orEmpty())
    }

    /** Where one [ensureRuntime] is, so that every way it can end is reported for what it is. */
    private class Setup(val budgetMs: Long) {
        var stage = STAGE_CONNECT
        var write = SessionRuntimeException.WRITE_NONE
        var polls = 0

        /** Reads of the session the gateway answered before any switch. */
        var reads = 0

        fun fail(reason: String, detail: String) = SessionRuntimeException(reason, detail, stage, write)

        /** After a switch was sent, a lost answer or readback leaves its effect unknown; it may have been applied. */
        fun unknown(cause: String) = fail("outcome_unknown",
            "the switch was sent but $cause, so whether it was applied is unknown")

        /** The connection failed or broke (or a call timed out on its own) at this stage. */
        fun lost(error: Throwable): SessionRuntimeException = when {
            write != SessionRuntimeException.WRITE_NONE -> unknown("the connection then failed (${error.javaClass.simpleName})")
            stage == STAGE_CONNECT -> fail("transport", "the gateway couldn't be reached (${error.javaClass.simpleName}); nothing was switched")
            else -> fail("transport", "reading the routing session failed (${error.javaClass.simpleName}); nothing was switched")
        }

        /** The one budget ran out (whatever any single call does with its own timeout). */
        fun timedOut(): SessionRuntimeException = when {
            write != SessionRuntimeException.WRITE_NONE -> unknown("no answer came within the ${budgetMs / 1000} s setup time")
            stage == STAGE_CONNECT -> fail("transport", "the gateway couldn't be reached within ${budgetMs / 1000} s; nothing was switched")
            reads == 0 -> fail("transport", "the gateway didn't answer reading the routing session within ${budgetMs / 1000} s; nothing was switched")
            else -> fail("not_ready", "the routing session's model was still being built after ${budgetMs / 1000} s " +
                "(read $reads times); nothing was switched")
        }
    }

    override suspend fun ensureRuntime(storedSessionId: String, runtime: SessionRuntimeSpec): RuntimeSetup {
        val setup = Setup(runtimeLimits.budgetMs)
        // One monotonic budget for everything (connecting, every call, every wait, the one switch and its readback).
        // A caller's cancellation goes straight through; every other ending is named from the stage it stopped in
        // and from whether a switch was already sent, never from how a single call reports its own timeout.
        try {
            return withTimeoutOrNull(runtimeLimits.budgetMs) { setUp(storedSessionId, runtime, setup) } ?: throw setup.timedOut()
        } catch (error: SessionRuntimeException) {
            throw error
        } catch (error: CancellationException) {
            // Still active: not this caller's cancellation but a call's own timeout or cancellation surfacing here.
            if (currentCoroutineContext().isActive) throw setup.timedOut()
            throw error
        } catch (error: HermesRpcException) {
            // Only the numeric code is kept: the server's own error text (paths, provider details) is never shown.
            if (setup.write != SessionRuntimeException.WRITE_NONE) throw setup.unknown("reading it back was refused (code ${error.code})")
            throw setup.fail("rpc_${error.code}", if (setup.stage == STAGE_CONNECT) "the gateway refused the connection (code ${error.code}); nothing was switched"
                else "the gateway answered reading the routing session with error code ${error.code}; nothing was switched")
        } catch (error: HermesAuthRequiredException) {
            if (setup.write == SessionRuntimeException.WRITE_NONE) throw error
            throw setup.unknown("the connection then needed signing in again")
        } catch (error: HermesException) {
            throw setup.lost(error)
        }
    }

    private suspend fun setUp(storedSessionId: String, runtime: SessionRuntimeSpec, setup: Setup): RuntimeSetup {
        val limits = runtimeLimits
        val started = System.nanoTime()
        val deadline = started + limits.budgetMs * 1_000_000
        fun leftMs() = (deadline - System.nanoTime()) / 1_000_000
        fun done(switched: Boolean) = RuntimeSetup(switched, setup.polls, (System.nanoTime() - started) / 1_000_000)
        val connection = connections()
        setup.stage = STAGE_POLL
        val resume = params().put("session_id", storedSessionId).put("omit_messages", true)
        suspend fun resumeNow(): JSONObject = connection.call("session.resume", resume, leftMs().coerceIn(1, RESUME_TIMEOUT_MS))
        var resumed = resumeNow()
        setup.reads = 1
        // Every later read must be of the same live record: the runtime id and the session key never change in one setup.
        val runtimeId = resumed.optString("session_id").ifBlank { throw HermesProtocolException("session.resume returned no runtime session id") }
        val sessionKey = resumed.optString("session_key")
        fun same(read: JSONObject) = read.optString("session_id") == runtimeId && read.optString("session_key") == sessionKey
        // tui_gateway: a cold resume returns _lazy_resume_info and only schedules the agent build; until it is
        // built, a live resume reports _fallback_session_info (both "lazy": true, no "reasoning_effort").
        // A config.set with an explicit provider does NOT wait for that build (methods_config_set._set_model), and
        // the build then prefers the stored runtime over an override pinned meanwhile. So: wait for a built agent.
        // A live record without an agent that is not "starting" reports "idle" (server._session_live_status) both when
        // its build completed and FAILED and when its build has NOT STARTED yet (the cold resume only schedules it on a
        // timer, and another client's lazy or hydrating record never starts it). No answer tells the two apart, so no
        // switch is ever sent to such a record: recovering a failed build is unsupported here (protocol ambiguity).
        var idleReads = 0
        while (!initialized(resumed.optJSONObject("info"))) {
            // The first read may be the cold resume itself (status "idle" while the build is only scheduled): never judged.
            if (setup.reads > 1) {
                when (buildState(resumed)) {
                    BUILD_STARTING -> idleReads = 0
                    BUILD_IDLE -> idleReads += 1
                    else -> throw setup.fail("build_state_unknown", "the routing session's model isn't built and the gateway " +
                        "didn't say whether it is still being built; nothing was switched")
                }
                if (idleReads >= IDLE_READS_UNCONFIRMED) throw setup.fail("initialization_unconfirmed",
                    "the routing session's model isn't built and the gateway doesn't show it being built (its build may not " +
                        "have started yet, or may have failed; the gateway doesn't say which); nothing was switched, try again shortly")
            }
            if (leftMs() < limits.pollMs + MIN_CALL_MS) throw setup.timedOut()
            delay(limits.pollMs)
            setup.polls += 1
            resumed = resumeNow()
            setup.reads += 1
            if (!same(resumed)) throw setup.fail("identity_changed", "the gateway answered for another live session; nothing was switched")
        }
        if (runtime.matches(resumed.optJSONObject("info"), requireEffort = true)) return done(switched = false)
        setup.stage = STAGE_SWITCH
        if (leftMs() < limits.switchReserveMs) throw setup.fail("insufficient_time",
            "the routing session's model was built with too little setup time left to switch it safely; nothing was switched")
        val result = try {
            switchModel(connection, runtimeId, runtime, setup, SessionRuntimeException.WRITE_SWITCH, leftMs() - limits.switchReadbackMs)
        } catch (error: HermesRpcException) {
            throw setup.fail("refused", "the server answered the switch with error code ${error.code}; whether any of it was applied is unknown")
        }
        checkAnswer(result, runtime, setup)
        // The built agent was switched in place: reading it back must show the model, provider AND effort.
        setup.stage = STAGE_READBACK
        val readback = resumeNow()
        val info = readback.optJSONObject("info")
        return when {
            !same(readback) || !initialized(info) -> throw setup.unknown("reading the routing session back didn't show a built model")
            !runtime.matches(info, requireEffort = true) -> throw setup.fail("readback", "reading it back showed another model or effort")
            else -> done(switched = true)
        }
    }

    /** The app's only `config.set`: key "model" with `--session`, on the live runtime id just resumed, on this connection. */
    private suspend fun switchModel(connection: GatewayRpc, runtimeId: String, runtime: SessionRuntimeSpec, setup: Setup,
                                    write: String, timeoutMs: Long): JSONObject {
        // Marked before sending: once it may have left, its effect may exist whatever happens to the answer.
        setup.write = write
        // Never sessionless (refused for "model"), never the "reasoning" key (a sessionless call writes that to the profile's config).
        return connection.call("config.set", JSONObject().put("session_id", runtimeId).put("key", "model").put("value", runtime.switchValue()),
            timeoutMs.coerceIn(1, CONFIG_SET_TIMEOUT_MS))
    }

    /** The switch's own answer: a confirmation is never given, a deferral or another scope or model is not this switch. */
    private fun checkAnswer(result: JSONObject, runtime: SessionRuntimeSpec, setup: Setup) {
        when {
            result.optBoolean("confirm_required") -> throw setup.fail("confirmation_required",
                "the server asks for a confirmation the app never gives on your behalf, so it wasn't applied")
            result.optBoolean("deferred") -> throw setup.fail("deferred", "the session was busy; the server would apply it only at its next turn")
            result.optString("scope") != "session" -> throw setup.fail("scope", "the server didn't limit the switch to this session")
            result.optString("value") != runtime.model -> throw setup.fail("not_applied", "the server answered with another model")
        }
    }

    override suspend fun submit(storedSessionId: String, text: String, attachments: List<OutgoingAttachment>): SubmittedTurn {
        AttachmentPolicy.validate(attachments)
        val connection = connections()
        val resumed = connection.call("session.resume",
            params().put("session_id", storedSessionId).put("omit_messages", true))
        val runtimeId = resumed.optString("session_id").ifBlank {
            throw HermesProtocolException("session.resume returned no runtime session id")
        }
        val alreadyQueued = resumed.has("queued") && !resumed.isNull("queued")
        val prompt = attach(connection, runtimeId, text, attachments)
        // This app sends something new to the session: an earlier turn on it no longer follows later
        // replies there (they would be this one's), and one already following stops now.
        open[runtimeId]?.toList()?.forEach { it.supersede() }
        val subscription = connection.subscribe(runtimeId)
        try {
            // No `queued` flag: the gateway's own busy-input policy (display.busy_input_mode) decides whether a
            // submit to a running session steers, redirects or queues. The app only reports what it was told.
            return submitOrder.getOrPut(runtimeId) { Mutex() }.withLock {
                val waitingOfOurs = open[runtimeId]?.count { it.waitingToStart } ?: 0
                val result = connection.call("prompt.submit", params().put("session_id", runtimeId).put("text", prompt))
                val status = result.optString("status")
                val turnsToSkip = when {
                    status == "streaming" -> 0
                    // Queued behind the turn in flight and any of this app's own queued turns. The resume snapshot shows
                    // only the head of the gateway's queue: a queue we did not put there, or none where ours should be, is unattributable.
                    status == "queued" && (alreadyQueued == (waitingOfOurs > 0)) -> 1 + waitingOfOurs
                    // "steered" / "redirected" (merged into the running turn, whose reply answers it) or an unknown status.
                    else -> -1
                }
                if (turnsToSkip < 0) subscription.close()
                val turn = GatewaySubmittedTurn(subscription, status, turnsToSkip, status == "queued" && turnsToSkip >= 0) { done -> open[runtimeId]?.remove(done) }
                if (turnsToSkip >= 0) open.getOrPut(runtimeId) { ConcurrentHashMap.newKeySet() }.add(turn)
                turn
            }
        } catch (error: Throwable) {
            subscription.close()
            throw error
        }
    }

    /** Attaches in order; on any failure, queued images are detached so they cannot leak into a later turn. */
    private suspend fun attach(connection: GatewayRpc, runtimeId: String, text: String, attachments: List<OutgoingAttachment>): String {
        if (attachments.isEmpty()) return text
        val queuedImages = mutableListOf<String>()
        val refs = mutableListOf<String>()
        try {
            for (attachment in attachments) {
                val name = AttachmentPolicy.safeName(attachment.name)
                val encoded = Base64.getEncoder().encodeToString(attachment.bytes)
                when (AttachmentPolicy.kind(attachment)) {
                    AttachmentKind.IMAGE -> {
                        val result = connection.call("image.attach_bytes", params().put("session_id", runtimeId)
                            .put("content_base64", encoded).put("filename", name), ATTACH_TIMEOUT_MS)
                        if (!result.optBoolean("attached")) throw HermesProtocolException("image $name was not attached")
                        result.optString("path").takeIf { it.isNotBlank() }?.let(queuedImages::add)
                    }
                    AttachmentKind.FILE -> {
                        val mime = attachment.mimeType.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
                        val result = connection.call("file.attach", params().put("session_id", runtimeId)
                            .put("data_url", "data:$mime;base64,$encoded").put("name", name), ATTACH_TIMEOUT_MS)
                        val ref = result.optString("ref_text")
                        if (!result.optBoolean("attached") || !ref.startsWith("@file:")) {
                            throw HermesProtocolException("file $name was not attached")
                        }
                        refs += ref
                    }
                }
            }
        } catch (error: Throwable) {
            for (path in queuedImages) {
                runCatching { connection.call("image.detach", params().put("session_id", runtimeId).put("path", path)) }
            }
            throw error
        }
        if (refs.isEmpty()) return text
        return (refs + text.ifBlank { null }).filterNotNull().joinToString("\n")
    }

    companion object {
        const val ATTACH_TIMEOUT_MS = 120_000L
        const val CONFIG_SET_TIMEOUT_MS = 90_000L

        /** One [ensureRuntime] as a whole, including every call's own timeout and every wait. */
        const val RUNTIME_SETUP_BUDGET_MS = 90_000L
        const val RUNTIME_POLL_MS = 750L
        private const val RESUME_TIMEOUT_MS = 30_000L
        private const val MIN_CALL_MS = 250L

        private const val STAGE_CONNECT = "connect"
        private const val STAGE_POLL = "poll"
        private const val STAGE_SWITCH = "switch"
        private const val STAGE_READBACK = "readback"

        private const val BUILD_STARTING = "starting"
        private const val BUILD_IDLE = "idle"
        private const val BUILD_UNKNOWN = "unknown"

        /** Later "idle" reads in a row (0.75 s apart in production) after which the setup stops, with no switch sent. */
        private const val IDLE_READS_UNCONFIRMED = 2

        /**
         * A live resume of a session whose agent isn't built (lazy info): tui_gateway's _session_live_status says
         * "starting" while its build runs, and "idle" (not running) when no build is running: after a build that
         * completed without an agent (failed) AND before a build has started. No status, another status, a non-lazy
         * info or no running flag is unknown.
         */
        private fun buildState(read: JSONObject): String {
            val info = read.optJSONObject("info")
            if (info == null || !info.optBoolean("lazy", false)) return BUILD_UNKNOWN
            return when (read.opt("status") as? String) {
                "starting" -> BUILD_STARTING
                "idle" -> if (read.opt("running") == false) BUILD_IDLE else BUILD_UNKNOWN
                else -> BUILD_UNKNOWN
            }
        }

        /**
         * A session whose agent is built: tui_gateway's session.resume reports it with server._session_info, which
         * always carries "reasoning_effort" (possibly "") and never "lazy"; both not-yet-built shapes
         * (_lazy_resume_info, _fallback_session_info) carry "lazy": true and no "reasoning_effort". A bare model
         * name is not readiness.
         */
        fun initialized(info: JSONObject?): Boolean =
            info != null && !info.optBoolean("lazy", false) && info.has("reasoning_effort")
    }
}

private val KNOWN_TYPES = setOf("message.start", "message.interim", "message.delta", "message.complete", "error")

/** Event types that only keep a connection alive: they are not progress of a response. */
private val KEEP_ALIVE_TYPES = setOf("ping", "pong", "heartbeat", "keepalive", "keep_alive")

private class GatewaySubmittedTurn(
    private val subscription: GatewaySubscription,
    override val submitStatus: String,
    private var turnsToSkip: Int,
    private val queuedAtSubmit: Boolean,
    private val onReleased: (GatewaySubmittedTurn) -> Unit,
) : SubmittedTurn {
    override val attributable: Boolean = turnsToSkip >= 0

    /** This turn's own reply has completed; the subscription stays open only for [collectLater]. */
    @Volatile private var ownDone = false

    /** This turn's own `message.start` was seen. */
    @Volatile private var started = false

    /** Submitted while the session was busy and not started yet: a turn submitted after it has to wait for it as well. */
    val waitingToStart: Boolean get() = queuedAtSubmit && !started && !ownDone

    /** A newer submit to the same session: later turns there are no longer this turn's. */
    @Volatile private var superseded = false

    fun supersede() {
        superseded = true
        if (ownDone) release()
    }

    /** Event counts and timings of [collect] (metadata only), see [diagnostics]. */
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile private var ownedAfterMs = -1L
    @Volatile private var endedAfterMs = -1L
    @Volatile private var ending = "waiting"

    override fun diagnostics(): String {
        val counts = listOf("message.start", "message.interim", "message.delta", "message.complete", "error")
            .joinToString(" ") { "${it.substringAfter('.')}=${seen[it] ?: 0}" }
        return "submit=$submitStatus skip_left=$turnsToSkip $counts other=${seen["other"] ?: 0} " +
            "owned_ms=$ownedAfterMs ended_ms=$endedAfterMs end=$ending"
    }

    /**
     * [timeoutMs] is an INACTIVITY bound: the longest the destination may stay silent. Every real gateway event (a
     * message start, delta, interim or complete, an error, any other typed event) starts it afresh; keep-alive
     * pings and timers do not. It never runs while [onEvent] runs, so the synthesis and playback of a reply
     * are not part of it, and no total time limit applies to a healthy response.
     */
    override suspend fun collect(timeoutMs: Long, onEvent: suspend (RecipientEvent) -> Unit) {
        if (!attributable) throw HermesProtocolException("prompt.submit status '$submitStatus' cannot be attributed to this turn")
        var completed = false
        val startedAt = System.nanoTime()
        fun elapsed() = (System.nanoTime() - startedAt) / 1_000_000
        try {
            var owned = false
            while (true) {
                // Waits for the next event that is progress; a keep-alive does not restart the bound.
                val received = withTimeoutOrNull(timeoutMs) { nextProgressEvent() }
                if (received == null) {
                    ending = "timeout"
                    endedAfterMs = elapsed()
                    throw HermesProtocolException("no gateway activity for ${timeoutMs}ms before message.complete")
                }
                val event = received.getOrNull() ?: break
                val known = event.type in KNOWN_TYPES
                seen.merge(if (known) event.type else "other", 1, Int::plus)
                when (event.type) {
                    "message.start" -> if (turnsToSkip == 0 && !owned) {
                        owned = true
                        started = true
                        ownedAfterMs = elapsed()
                    }
                    "message.complete" -> if (!owned) {
                        if (turnsToSkip > 0) turnsToSkip--
                        // This session's next turn is ours (turnsToSkip is 0), and it ended in failure before it
                        // started (tui_gateway's _emit_terminal_turn_error sends no message.start): say so now.
                        else {
                            val failed = event.payload
                            if (failed != null && failed.optString("status") == "error") {
                                ending = "failed_before_start"
                                endedAfterMs = elapsed()
                                throw HermesProtocolException("destination turn failed before it started: " +
                                    failed.optString("error").ifBlank { "error" }.take(160))
                            }
                        }
                    } else {
                        val payload = event.payload ?: JSONObject()
                        completed = true
                        ownDone = true
                        ending = "complete:${payload.optString("status").ifBlank { "none" }}"
                        endedAfterMs = elapsed()
                        if (superseded) release()
                        onEvent(RecipientEvent.Complete(payloadText(payload),
                            payload.optString("status").ifBlank { null }))
                        return
                    }
                    "message.interim" -> if (owned) onEvent(RecipientEvent.Interim(payloadText(event.payload ?: JSONObject())))
                    "error" -> if (owned) {
                        ending = "error"
                        endedAfterMs = elapsed()
                        throw HermesProtocolException("destination turn failed: ${event.payload?.optString("message").orEmpty().take(200)}")
                    } else if (turnsToSkip == 0) {
                        // Ours, refused or cancelled before it started (tui_gateway emits a bare error, no
                        // message.start: _admit_prompt_turn, "cancelled before the agent was ready").
                        ending = "failed_before_start"
                        endedAfterMs = elapsed()
                        throw HermesProtocolException("destination turn failed before it started: " +
                            event.payload?.optString("message").orEmpty().take(160))
                    }
                }
            }
            ending = "stream_ended"
            throw HermesProtocolException("gateway event stream ended before message.complete")
        } finally {
            // Kept open after this turn's own reply only, until [collectLater] or [release].
            if (!completed) release()
        }
    }

    /** The next event that counts as progress (a Result holding it), or a Result holding null when the stream ended. Throws if the stream failed. */
    private suspend fun nextProgressEvent(): Result<GatewayEvent?> {
        while (true) {
            val result = subscription.events.receiveCatching()
            if (result.isClosed) {
                result.exceptionOrNull()?.let { throw it }
                return Result.success(null)
            }
            val event = result.getOrThrow()
            if (event.type.lowercase() !in KEEP_ALIVE_TYPES) return Result.success(event)
            seen.merge("keepalive", 1, Int::plus)
        }
    }

    override suspend fun collectLater(windowMs: Long, onLater: suspend (RecipientEvent.Complete) -> Unit): LaterEnd {
        try {
            if (!ownDone || superseded) return if (superseded) LaterEnd.SUPERSEDED else LaterEnd.RELEASED
            val inWindow = withTimeoutOrNull(windowMs) {
                // A later turn is a message.start followed by its message.complete; a complete without
                // its own start (a replayed frame) is not one, and neither is a failed or cancelled turn.
                var started = false
                for (event in subscription.events) {
                    when (event.type) {
                        "message.start" -> started = true
                        "error" -> started = false
                        "message.complete" -> if (started) {
                            started = false
                            val payload = event.payload ?: JSONObject()
                            val status = payload.optString("status").ifBlank { null }
                            if (status == null || status == "complete") onLater(RecipientEvent.Complete(payloadText(payload), status))
                        }
                    }
                }
                true
            }
            return when {
                inWindow == null -> LaterEnd.WINDOW
                superseded -> LaterEnd.SUPERSEDED
                else -> LaterEnd.RELEASED
            }
        } catch (_: ClosedReceiveChannelException) {
            return LaterEnd.DISCONNECTED
        } catch (_: HermesException) {
            // The connection dropped: nothing more arrives on this subscription.
            return LaterEnd.DISCONNECTED
        } finally {
            release()
        }
    }

    override fun release() {
        subscription.close()
        onReleased(this)
    }

    private fun payloadText(payload: JSONObject): String = when (val text = payload.opt("text")) {
        null, JSONObject.NULL -> ""
        is String -> text
        else -> text.toString()
    }
}
