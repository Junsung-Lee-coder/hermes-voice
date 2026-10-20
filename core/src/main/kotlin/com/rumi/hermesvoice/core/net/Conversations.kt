package com.rumi.hermesvoice.core.net

import com.rumi.hermesvoice.core.HermesProtocolException
import com.rumi.hermesvoice.core.diag.DiagStage
import com.rumi.hermesvoice.core.diag.diagMark
import com.rumi.hermesvoice.core.voice.RecipientEvent
import com.rumi.hermesvoice.core.voice.VoiceTurnPrompt
import java.util.Base64
import java.util.Locale
import com.rumi.hermesvoice.core.HermesAuthRequiredException
import com.rumi.hermesvoice.core.HermesException
import com.rumi.hermesvoice.core.HermesRpcException
import com.rumi.hermesvoice.core.HermesReplyUnrecoverableException
import com.rumi.hermesvoice.core.SessionInUseException
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

    /** What identifies this accepted turn on the gateway, when its events can be found again after a lost connection ([HermesConversationPort.reattach]); null: not recoverable. */
    val acceptance: TurnAcceptance? get() = null

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

    /**
     * The turn [acceptance] describes, found again on a fresh connection (never sent again): the same runtime session, resumed, and the
     * server's replay of the events after the prompt was accepted. Throws [com.rumi.hermesvoice.core.HermesReplyUnrecoverableException] when
     * the events can't be matched to that request (another server epoch, evicted events, another runtime id).
     */
    suspend fun reattach(storedSessionId: String, acceptance: TurnAcceptance): SubmittedTurn =
        throw HermesProtocolException("this connection can't re-attach an accepted turn")
}

/**
 * `session.resume` (stored id → runtime id), optional attach RPCs, then `prompt.submit` WITHOUT a `queued` flag: the gateway's own
 * busy-input policy decides whether a busy destination steers, redirects or queues it. Events carry no per-prompt id, so attribution is
 * by order for proven sequential `streaming` admissions only. A `queued` ACK may merge/drop or sit behind invisible foreign envelopes:
 * this head-only protocol cannot prove its identity, so every queued send is accepted but NOT attributable. No collector or durable
 * acceptance is armed. Unresolved work is never retired by an idle-shaped queue projection; no prompt is replayed to recover a reply.
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

    /** Known streaming tail plus conservative provisional custody, per runtime and port instance.
     * Local Stop/release never dequeues remote work. Unknown/queued work stays unproved: neither
     * text equality, a timer, a replacement socket nor an idle-shaped head projection can clear it.
     * This is not process-death durability; persisted acceptance has its own admission provenance. */
    private class SubmitOrder {
        val mutex = Mutex()
        var tail: TurnAcceptance? = null
        var unproven = false
        fun custody() = unproven
        fun restore(prior: Boolean) { unproven = prior }
    }
    private val submitOrder = ConcurrentHashMap<String, SubmitOrder>()

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
        diagMark(DiagStage.CONNECT)
        val connection = connections()
        diagMark(DiagStage.RESUME)
        val resumed = connection.call("session.resume",
            params().put("session_id", storedSessionId).put("omit_messages", true))
        val runtimeId = resumed.optString("session_id").ifBlank {
            throw HermesProtocolException("session.resume returned no runtime session id")
        }
        val order = submitOrder.getOrPut(runtimeId) { SubmitOrder() }
        val attached = attach(connection, runtimeId, text, attachments)
        val prompt = attached.prompt
        // This app sends something new to the session: an earlier turn on it no longer follows later
        // replies there (they would be this one's), and one already following stops now.
        open[runtimeId]?.toTypedArray()?.forEach { it.supersede() }
        diagMark(DiagStage.SUBMIT)
        // No `queued` flag: the gateway's own busy-input policy (display.busy_input_mode) decides whether a
        // submit to a running session steers, redirects or queues. The app only reports what it was told.
        return order.mutex.withLock {
            // Subscribe first: every event after the replay basis below must reach this turn,
            // even when a predecessor completes before prompt.submit is accepted.
            val subscription = connection.subscribe(runtimeId)
            try {
                // Only replay of proven streaming admissions can advance known positions.
                // A head-only/omitted queue cannot retire possibly accepted work, even on this socket.
                val basis = if (order.unproven) OrdinalBasis(null, lost = true)
                    else ordinalBasis(connection, runtimeId, listOfNotNull(order.tail))
                val anchor = basis.anchor
                // MAYBE-ACCEPTED custody, recorded under this mutex before the frame can leave. Once it
                // has left the server may have queued it, so every exit that cannot prove otherwise keeps it: Stop/cancellation, a
                // deadline, a lost socket, a protocol error or a reply this client cannot classify. Only a returned acceptance,
                // a definitive refusal (restores the PRIOR state below) or a proven streaming admission replaces it.
                val prior = order.custody()
                order.unproven = true
                val result = try {
                    connection.call("prompt.submit", params().put("session_id", runtimeId).put("text", prompt))
                } catch (notSent: com.rumi.hermesvoice.core.HermesPromptNotSentException) {
                    order.restore(prior)
                    detachQueued(connection, runtimeId, attached.queuedImages)
                    throw notSent
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (refused: HermesRpcException) {
                    if (refused.code == SessionInUseException.CODE && refused.reason == SessionInUseException.REASON) {
                        // Confirmed pre-admission guard: restore only this attempt's provisional custody.
                        order.restore(prior)
                        detachQueued(connection, runtimeId, attached.queuedImages)
                        throw SessionInUseException()
                    }
                    throw com.rumi.hermesvoice.core.HermesPromptUncertainException(refused)
                } catch (uncertain: com.rumi.hermesvoice.core.HermesPromptUncertainException) {
                    throw uncertain
                } catch (error: Exception) {
                    throw com.rumi.hermesvoice.core.HermesPromptUncertainException(error)
                }
                val status = (result.opt("status") as? String).orEmpty()
                if (status !in setOf("streaming", "queued", "steered", "redirected")) {
                    // No supported acceptance proof: retain provisional custody and close via the
                    // outer catch BEFORE ChatService/voice can publish accepted or delivered.
                    throw com.rumi.hermesvoice.core.HermesPromptUncertainException(HermesProtocolException("unsupported prompt submit result"))
                }
                val deliveryUnrecoverable = basis.lost && status in setOf("streaming", "queued")
                // There is no queued envelope identity/cardinality in this protocol. ALL queued
                // acknowledgements close here: no post-ACK guessing RPC, collector, or durable authority.
                val turnsToSkip = when {
                    status == "queued" -> -1
                    deliveryUnrecoverable -> 0
                    status == "streaming" -> basis.remaining
                    else -> -1
                }
                if (turnsToSkip < 0) subscription.close()
                val acceptance = if (!deliveryUnrecoverable && anchor != null && turnsToSkip >= 0) TurnAcceptance(runtimeId, anchor, connection.replayEpoch, turnsToSkip, admission = "streaming-v1") else null
                // The returned reply replaces the provisional marker with the prior custody ...
                order.unproven = prior
                if (acceptance != null) order.tail = acceptance
                // ... except accepted without a provable ordinal: the remote tail now includes this work
                // too. Even restored replay of an older tail cannot silently omit it. A status that is neither
                // an acceptance (streaming/queued) nor a merge into the live turn (steered/redirected: no FIFO position)
                // may still have been accepted: unrecognised answers stay unproven.
                val unclassified = status !in setOf("streaming", "queued", "steered", "redirected")
                if (acceptance == null && (unclassified || status in setOf("streaming", "queued"))) order.unproven = true
                val reopener = acceptance?.let { accepted -> TurnReopener { after -> reopen(storedSessionId, accepted, after) } }
                val turn = GatewaySubmittedTurn(subscription, status, turnsToSkip, status == "queued" && turnsToSkip >= 0, acceptance, reopener,
                    deliveryUnrecoverable = deliveryUnrecoverable) { done -> open[runtimeId]?.remove(done) }
                if (turnsToSkip >= 0 && !deliveryUnrecoverable) open.getOrPut(runtimeId) { ConcurrentHashMap.newKeySet() }.add(turn)
                turn
            } catch (error: Throwable) {
                subscription.close()
                throw error
            }
        }
    }

    private class OrdinalBasis(val anchor: Long?, val remaining: Int = 0, val lost: Boolean = false)

    /** Rebase known local FIFO positions on one server replay snapshot, not on collector progress.
     * This proves only the known same-app FIFO: unknown producers/merged turns are not assigned ids
     * by this calculation. Expired, restarted or ambiguous replay is a visible delivery failure. */
    private suspend fun ordinalBasis(connection: GatewayRpc, runtimeId: String, accepted: List<TurnAcceptance>): OrdinalBasis {
        if (accepted.isEmpty()) return OrdinalBasis(replayAnchor(connection, runtimeId))
        val first = accepted.minOf { it.anchorSeq }
        val since = try {
            connection.call("session.events.since", params().put("session_id", runtimeId).put("last_seen", first), ANCHOR_TIMEOUT_MS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return OrdinalBasis(null, lost = true)
        }
        val anchor = since.optLong("latest_seq", -1L)
        val epoch = since.optString("epoch")
        if (anchor < first || since.optBoolean("truncated") || accepted.any {
            it.epoch.isNotEmpty() && (it.epoch != connection.replayEpoch || (epoch.isNotEmpty() && it.epoch != epoch))
        }) return OrdinalBasis(null, lost = true)
        val frames = since.optJSONArray("events") ?: return OrdinalBasis(null, lost = true)
        val events = (0 until frames.length()).mapNotNull { frames.optJSONObject(it) }
        // No gap/duplicate/out-of-order frame can silently advance an ordinal.
        if (events.size != frames.length() || events.size.toLong() != anchor - first || events.withIndex().any { (i, e) ->
            e.optString("session_id") != runtimeId || e.optLong("seq", -1L) != first + i + 1L
        }) return OrdinalBasis(null, lost = true)
        var remaining = 0
        for (a in accepted) {
            var left = a.skip + 1
            for (e in events) {
                if (e.optLong("seq") <= a.anchorSeq || left == 0) continue
                when (e.optString("type")) {
                    "error" -> return OrdinalBasis(null, lost = true) // bare error need not have a terminal complete
                    "message.complete" -> left--
                }
            }
            remaining = maxOf(remaining, left)
        }
        return OrdinalBasis(anchor, remaining)
    }

    private suspend fun replayAnchor(connection: GatewayRpc, runtimeId: String): Long? = try {
        connection.call("session.events.since", params().put("session_id", runtimeId).put("last_seen", Long.MAX_VALUE), ANCHOR_TIMEOUT_MS)
            .optLong("latest_seq", -1L).takeIf { it >= 0 }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    override suspend fun reattach(storedSessionId: String, acceptance: TurnAcceptance): SubmittedTurn {
        requireStreamingAdmission(acceptance)
        val reopened = reopen(storedSessionId, acceptance, acceptance.anchorSeq)
        return GatewaySubmittedTurn(reopened.subscription, "reattached", acceptance.skip, false, acceptance,
            TurnReopener { after -> reopen(storedSessionId, acceptance, after) }, reopened.replay) { }
    }

    private fun requireStreamingAdmission(a: TurnAcceptance) {
        if (a.admission != "streaming-v1" || a.skip < 0 || a.anchorSeq < 0 || a.runtimeId.isBlank())
            throw com.rumi.hermesvoice.core.HermesReplyUnrecoverableException("This saved request has no proven streaming admission; its reply cannot be safely attributed.")
    }

    /** A new connection to the same runtime session, subscribed, with the server's replay of the events after [afterSeq]. */
    private suspend fun reopen(storedSessionId: String, acceptance: TurnAcceptance, afterSeq: Long): Reopened {
        requireStreamingAdmission(acceptance)
        val connection = connections()
        val resumed = connection.call("session.resume", params().put("session_id", storedSessionId).put("omit_messages", true))
        if (resumed.optString("session_id") != acceptance.runtimeId) {
            throw com.rumi.hermesvoice.core.HermesReplyUnrecoverableException("the conversation now runs under another session on the server")
        }
        if (acceptance.epoch.isNotEmpty() && connection.replayEpoch != acceptance.epoch) {
            throw com.rumi.hermesvoice.core.HermesReplyUnrecoverableException("the server restarted after the message was accepted")
        }
        val subscription = connection.subscribe(acceptance.runtimeId)
        try {
            val since = connection.call("session.events.since", params().put("session_id", acceptance.runtimeId).put("last_seen", afterSeq), ANCHOR_TIMEOUT_MS)
            if (since.optBoolean("truncated")) {
                throw com.rumi.hermesvoice.core.HermesReplyUnrecoverableException("the server no longer holds the events after the message was accepted")
            }
            val epoch = since.optString("epoch")
            if (acceptance.epoch.isNotEmpty() && epoch.isNotEmpty() && epoch != acceptance.epoch) {
                throw com.rumi.hermesvoice.core.HermesReplyUnrecoverableException("the server restarted after the message was accepted")
            }
            val frames = since.optJSONArray("events") ?: JSONArray()
            val replay = (0 until frames.length()).mapNotNull { frames.optJSONObject(it) }.map {
                GatewayEvent(it.optString("type"), it.optString("session_id"), it.optJSONObject("payload"), it.optLong("seq", 0L))
            }
            return Reopened(subscription, replay)
        } catch (error: Throwable) {
            subscription.close()
            throw error
        }
    }

    /** Attaches in order; on any failure, queued images are detached so they cannot leak into a later turn. */
    private suspend fun attach(connection: GatewayRpc, runtimeId: String, text: String, attachments: List<OutgoingAttachment>): AttachedPrompt {
        if (attachments.isEmpty()) return AttachedPrompt(text, emptyList())
        diagMark(DiagStage.UPLOAD)
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
            detachQueued(connection, runtimeId, queuedImages)
            throw error
        }
        if (refs.isEmpty()) return AttachedPrompt(text, queuedImages)
        return AttachedPrompt((refs + text.ifBlank { null }).filterNotNull().joinToString("\n"), queuedImages)
    }

    private class AttachedPrompt(val prompt: String, val queuedImages: List<String>)

    private suspend fun detachQueued(connection: GatewayRpc, runtimeId: String, paths: List<String>) {
        for (path in paths) {
            runCatching { connection.call("image.detach", params().put("session_id", runtimeId).put("path", path)) }
        }
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

private const val ANCHOR_TIMEOUT_MS = 10_000L
private const val RECOVERY_ATTEMPTS = 4
private val RECOVERY_BACKOFF_MS = longArrayOf(500, 1_000, 2_000)

private val KNOWN_TYPES = setOf("message.start", "message.interim", "message.delta", "message.complete", "error")

/** Event types that only keep a connection alive: they are not progress of a response. */
private val KEEP_ALIVE_TYPES = setOf("ping", "pong", "heartbeat", "keepalive", "keep_alive")

/** A new connection's subscription to a runtime session plus the events the server replayed after a watermark. */
private class Reopened(val subscription: GatewaySubscription, val replay: List<GatewayEvent>)

private fun interface TurnReopener {
    suspend fun reopen(afterSeq: Long): Reopened
}

private class GatewaySubmittedTurn(
    @Volatile private var subscription: GatewaySubscription,
    override val submitStatus: String,
    private var turnsToSkip: Int,
    private val queuedAtSubmit: Boolean,
    override val acceptance: TurnAcceptance? = null,
    private val reopener: TurnReopener? = null,
    /** Events the server already replayed for a re-attached turn: processed before any live one. */
    replayed: List<GatewayEvent> = emptyList(),
    /** Accepted, but no trustworthy ordinal can be saved or delivered. Keep the real caller's
     * normal terminal-failure path instead of silently returning Accepted/untracked. */
    private val deliveryUnrecoverable: Boolean = false,
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

    /** The highest replay sequence handled; an event at or below it (delivered live and again by a replay) is handled once. */
    private var lastSeq: Long = acceptance?.anchorSeq ?: 0L
    private val backlog = ArrayDeque(replayed)
    @Volatile private var recoveries = 0

    override fun diagnostics(): String {
        val counts = listOf("message.start", "message.interim", "message.delta", "message.complete", "error")
            .joinToString(" ") { "${it.substringAfter('.')}=${seen[it] ?: 0}" }
        return "submit=$submitStatus skip_left=$turnsToSkip $counts other=${seen["other"] ?: 0} " +
            "owned_ms=$ownedAfterMs ended_ms=$endedAfterMs recoveries=$recoveries end=$ending"
    }

    private sealed class Polled {
        class Got(val event: GatewayEvent) : Polled()
        class Ended(val cause: Throwable?) : Polled()
        object Silent : Polled()
    }

    private suspend fun poll(timeoutMs: Long): Polled {
        if (backlog.isNotEmpty()) return Polled.Got(backlog.removeFirst())
        // Waits for the next event that is progress; a keep-alive does not restart the bound.
        val received = try {
            withTimeoutOrNull(timeoutMs) { nextProgressEvent() }
        } catch (lost: HermesException) {
            return Polled.Ended(lost)
        } ?: return Polled.Silent
        return received.getOrNull()?.let { Polled.Got(it) } ?: Polled.Ended(null)
    }

    /**
     * The connection was lost or went silent: reconnects to the SAME runtime session and reads the server's replay after [lastSeq]
     * (never sending the prompt again), up to a few attempts. False when this turn can't be recovered or the server can't be reached;
     * a reply that can never be matched throws [com.rumi.hermesvoice.core.HermesReplyUnrecoverableException]. After a silence, true only if
     * the replay held something new (a silent but healthy connection is the inactivity bound's business).
     */
    private suspend fun recover(silent: Boolean): Boolean {
        val reopen = reopener ?: return false
        var failures = 0
        while (true) {
            val reopened = try {
                reopen.reopen(lastSeq)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unrecoverable: com.rumi.hermesvoice.core.HermesReplyUnrecoverableException) {
                ending = "unrecoverable"
                throw unrecoverable
            } catch (auth: HermesAuthRequiredException) {
                throw auth
            } catch (_: HermesException) {
                if (++failures >= RECOVERY_ATTEMPTS) return false
                delay(RECOVERY_BACKOFF_MS[failures - 1])
                continue
            } catch (_: java.io.IOException) {
                if (++failures >= RECOVERY_ATTEMPTS) return false
                delay(RECOVERY_BACKOFF_MS[failures - 1])
                continue
            }
            val old = subscription
            subscription = reopened.subscription
            old.close()
            backlog.addAll(reopened.replay)
            recoveries++
            return !silent || reopened.replay.any { it.seq == 0L || it.seq > lastSeq }
        }
    }

    /**
     * [timeoutMs] is an INACTIVITY bound: the longest the destination may stay silent. Every real gateway event (a
     * message start, delta, interim or complete, an error, any other typed event) starts it afresh; keep-alive
     * pings and timers do not. It never runs while [onEvent] runs, so the synthesis and playback of a reply
     * are not part of it, and no total time limit applies to a healthy response.
     * A lost connection is recovered inside this call ([recover]) when the turn can be found again; the events of the
     * normal stream and of the replay are handled once, in sequence order.
     */
    override suspend fun collect(timeoutMs: Long, onEvent: suspend (RecipientEvent) -> Unit) {
        if (!attributable) {
            if (submitStatus == "queued") throw com.rumi.hermesvoice.core.HermesQueuedAttributionException()
            throw HermesProtocolException("prompt.submit status '$submitStatus' cannot be attributed to this turn")
        }
        var completed = false
        val startedAt = System.nanoTime()
        fun elapsed() = (System.nanoTime() - startedAt) / 1_000_000
        try {
            if (deliveryUnrecoverable) {
                ending = "queue_attribution_lost"
                throw com.rumi.hermesvoice.core.HermesReplyUnrecoverableException("the replay no longer proves the accepted prompt's FIFO position")
            }
            var owned = false
            while (true) {
                val event = when (val polled = poll(timeoutMs)) {
                    is Polled.Got -> polled.event
                    is Polled.Ended -> {
                        if (recover(silent = false)) continue
                        ending = "stream_ended"
                        endedAfterMs = elapsed()
                        throw polled.cause ?: com.rumi.hermesvoice.core.HermesStreamEndedException("gateway event stream ended before message.complete")
                    }
                    Polled.Silent -> {
                        if (recover(silent = true)) continue
                        ending = "timeout"
                        endedAfterMs = elapsed()
                        throw com.rumi.hermesvoice.core.HermesInactivityTimeoutException("no gateway activity for ${timeoutMs}ms before message.complete")
                    }
                }
                if (event.type.lowercase() in KEEP_ALIVE_TYPES) {
                    seen.merge("keepalive", 1, Int::plus)
                    continue
                }
                if (event.seq > 0) {
                    if (event.seq <= lastSeq) continue
                    lastSeq = event.seq
                }
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
                                throw com.rumi.hermesvoice.core.HermesTurnFailedException("destination turn failed before it started: " +
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
                            payload.optString("status").ifBlank { null },
                            payload.optJSONObject("persisted_turn")?.optLong("final_assistant_row_id", -1)?.takeIf { it >= 0 }))
                        return
                    }
                    "message.interim" -> if (owned) onEvent(RecipientEvent.Interim(payloadText(event.payload ?: JSONObject())))
                    // Display-only progress of the turn we own: a bounded label, never a response (see ActivityLabels).
                    "tool.start", "tool.generating", "tool.complete", "status.update" -> if (owned) {
                        com.rumi.hermesvoice.core.activity.ActivityLabels.forEvent(event.type, event.payload)?.let { onEvent(RecipientEvent.Activity(it)) }
                    }
                    "error" -> if (owned) {
                        ending = "error"
                        endedAfterMs = elapsed()
                        throw com.rumi.hermesvoice.core.HermesTurnFailedException("destination turn failed: ${event.payload?.optString("message").orEmpty().take(200)}")
                    } else if (turnsToSkip > 0) {
                        // A prior accepted turn may emit only a bare error (no start/complete).
                        // It may also emit a complete afterwards. Neither advancing nor keeping
                        // the ordinal can prove the queued prompt's position: never adopt a neighbour.
                        ending = "queue_attribution_lost"
                        throw com.rumi.hermesvoice.core.HermesReplyUnrecoverableException("an earlier turn failed without a reliable queued-turn boundary")
                    } else if (turnsToSkip == 0) {
                        // Ours, refused or cancelled before it started (tui_gateway emits a bare error, no
                        // message.start: _admit_prompt_turn, "cancelled before the agent was ready").
                        ending = "failed_before_start"
                        endedAfterMs = elapsed()
                        throw com.rumi.hermesvoice.core.HermesTurnFailedException("destination turn failed before it started: " +
                            event.payload?.optString("message").orEmpty().take(160))
                    }
                }
            }
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
                            if (status == null || status == "complete") onLater(RecipientEvent.Complete(payloadText(payload), status,
                                payload.optJSONObject("persisted_turn")?.optLong("final_assistant_row_id", -1)?.takeIf { it >= 0 }))
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
