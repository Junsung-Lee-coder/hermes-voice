package com.rumi.hermesvoice.core.notify

import com.rumi.hermesvoice.core.KeyValueStore
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.core.voice.DestinationAllowlist
import com.rumi.hermesvoice.core.voice.PlaybackSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class FinalReplySource { OWN, LATER, TEXT }

/**
 * One actual final assistant answer the app received, with what became of its audio. Never carries the reply text.
 * [identity] is the positive terminal identity (turn + position, or a chat send): never derived from the body.
 */
class FinalReply(
    val identity: String,
    val storedSessionId: String,
    /** The device the answer was meant for: the playback target at hand-off (the Phone for a text reply). */
    val target: VoiceOrigin,
    /** Its audio was confirmed played to the end (or the text was already heard through spoken interim text). */
    val heard: Boolean,
    /** The user's Stop (or switching later replies off) ended it: never an alert. */
    val cancelled: Boolean,
    val source: FinalReplySource,
    /** The target device's link, when it is another device (the Watch); null for the Phone itself. */
    val sink: PlaybackSink? = null,
)

/** What an alert carries to a device: identity and the conversation to open, nothing of the answer. */
data class ReplyAlert(val identity: String, val storedSessionId: String) {
    init {
        require(IDENTITY.matches(identity) && DestinationAllowlist.isValidSessionId(storedSessionId)) { "invalid reply alert" }
    }

    companion object {
        private val IDENTITY = Regex("^[A-Za-z0-9._:#-]{1,160}$")
        fun isValidIdentity(identity: String): Boolean = IDENTITY.matches(identity)

        /** "[turnId]#[part]", or a digest of the turn id when it holds characters the notification wire does not carry. */
        fun identityOf(turnId: String, part: String): String {
            val id = "$turnId#$part"
            if (IDENTITY.matches(id)) return id
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(turnId.toByteArray(Charsets.UTF_8))
            return "t" + digest.take(16).joinToString("") { "%02x".format(it) } + "#" + part
        }
    }
}

/** The platform notification surface of THIS device. [show] never throws and reports whether the system accepted it. */
fun interface ReplyAlertPort {
    fun show(alert: ReplyAlert): Boolean
}

enum class ReplyAlertResult { SUPPRESSED_HEARD, SUPPRESSED_CANCELLED, DUPLICATE, INVALID, SHOWN_HERE, NOT_SHOWN_HERE, SENT_TO_WATCH }

/** A bounded durable set of the alert identities already handled, newest last. */
class ReplyAlertLedger(private val store: KeyValueStore, private val max: Int = MAX_ENTRIES) {
    init {
        require(max > 0)
    }

    private val seen = LinkedHashSet<String>().also { set ->
        store.getString(KEY)?.split(' ')?.filter { it.isNotEmpty() }?.takeLast(max)?.let(set::addAll)
    }

    /** True the first time [identity] is claimed (saved before returning); false when it was handled before. */
    @Synchronized
    fun claim(identity: String): Boolean {
        if (identity in seen) return false
        seen += identity
        while (seen.size > max) seen.remove(seen.first())
        store.commitString(KEY, seen.joinToString(" "))
        return true
    }

    companion object {
        const val KEY = "reply_alert_ledger_v1"
        const val MAX_ENTRIES = 256
    }
}

/**
 * Decides and routes the arrival alert for an unspoken final answer. Exactly one alert per identity, even across restarts:
 * a heard or cancelled answer never alerts; a Watch-target answer is alerted by the Watch alone (Phone sends it over the
 * link and only shows its own if the Watch could not be told); a Phone-target or text answer is shown by the Phone.
 */
class ReplyAlerts(
    private val ledger: ReplyAlertLedger,
    private val here: ReplyAlertPort,
    private val scope: CoroutineScope,
) {
    fun onFinalReply(reply: FinalReply) {
        if (reply.heard || reply.cancelled) return
        scope.launch { dispatch(reply) }
    }

    suspend fun dispatch(reply: FinalReply): ReplyAlertResult {
        if (reply.cancelled) return ReplyAlertResult.SUPPRESSED_CANCELLED
        if (reply.heard) return ReplyAlertResult.SUPPRESSED_HEARD
        val alert = runCatching { ReplyAlert(reply.identity, reply.storedSessionId) }.getOrNull() ?: return ReplyAlertResult.INVALID
        if (!ledger.claim(alert.identity)) return ReplyAlertResult.DUPLICATE
        val sink = reply.sink
        if (reply.target == VoiceOrigin.WATCH && sink != null && runCatching { sink.deliverReplyAlert(alert) }.getOrDefault(false)) {
            return ReplyAlertResult.SENT_TO_WATCH
        }
        return if (runCatching { here.show(alert) }.getOrDefault(false)) ReplyAlertResult.SHOWN_HERE else ReplyAlertResult.NOT_SHOWN_HERE
    }
}

/** What both devices show and carry for an arrival alert: generic text only, and the conversation to open. */
object ReplyAlertContent {
    const val CHANNEL_ID = "reply_arrival"
    const val CHANNEL_NAME = "New replies"
    const val TITLE = "New reply"
    const val BODY = "A new reply is ready"
    const val PUBLIC_TITLE = "Hermes Voice"
    const val PUBLIC_BODY = "New reply"
    const val ACTION_OPEN_REPLY = "com.rumi.hermesvoice.action.OPEN_REPLY"
    const val EXTRA_SESSION_ID = "hv_reply_session"
    const val EXTRA_IDENTITY = "hv_reply_identity"

    /** The conversation an open-reply intent names, or null when it carries none that is safe to act on. */
    fun sessionOf(action: String?, sessionId: String?): String? =
        sessionId?.takeIf { action == ACTION_OPEN_REPLY && DestinationAllowlist.isValidSessionId(it) }
}
