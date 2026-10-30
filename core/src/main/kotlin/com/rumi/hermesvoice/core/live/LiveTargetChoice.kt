package com.rumi.hermesvoice.core.live

import java.text.Normalizer
import java.util.Locale

/** One listed, app-registered conversation the user may name. Display data only, never an access grant. */
data class LiveTarget(val sessionId: String, val title: String, val alias: String)

sealed class LiveChoice {
    data class Chosen(val target: LiveTarget) : LiveChoice()
    data class Ambiguous(val candidates: List<LiveTarget>) : LiveChoice()
    object Unnamed : LiveChoice()
}

/**
 * The conversation the USER named, matched only against the app's current catalog (a title or a registered alias).
 * Provider metadata and assistant speech take no part. A longer name hides a shorter one inside its span (an alias that
 * contains the shared title); equal names stay ambiguous. When nothing is named, only an already bound conversation is the default.
 */
object LiveTargetChoice {
    fun normalize(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val out = StringBuilder(folded.length)
        var gap = true
        for (ch in folded) {
            if (Character.isLetterOrDigit(ch)) { out.append(ch); gap = false } else if (!gap) { out.append(' '); gap = true }
        }
        return out.toString().trimEnd()
    }

    fun resolve(userSpeech: String, targets: List<LiveTarget>, bound: String?): LiveChoice {
        val speech = " " + normalize(userSpeech) + " "
        class Hit(val start: Int, val end: Int, val target: LiveTarget)
        val hits = ArrayList<Hit>()
        for (target in targets) for (name in setOf(normalize(target.title), normalize(target.alias))) {
            if (name.length < 2) continue
            val latin = name.all { it.code < 128 }
            var from = 0
            while (true) {
                val at = speech.indexOf(" $name", from)
                if (at < 0) break
                val end = at + 1 + name.length
                // A Latin name needs a word end; a Hangul/CJK name may carry an attached particle ("알파에", "회의록으로").
                if (!latin || speech[end] == ' ') hits += Hit(at + 1, end, target)
                from = at + 1
            }
        }
        val kept = hits.filter { h -> hits.none { o -> o.target.sessionId != h.target.sessionId && o.start <= h.start && o.end >= h.end && o.end - o.start > h.end - h.start } }
        val named = kept.map { it.target }.distinctBy { it.sessionId }
        return when {
            named.size == 1 -> LiveChoice.Chosen(named.single())
            named.size > 1 -> LiveChoice.Ambiguous(named)
            bound != null -> targets.firstOrNull { it.sessionId == bound }?.let { LiveChoice.Chosen(it) } ?: LiveChoice.Unnamed
            else -> LiveChoice.Unnamed
        }
    }
}
