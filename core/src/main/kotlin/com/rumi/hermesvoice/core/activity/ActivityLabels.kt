package com.rumi.hermesvoice.core.activity

import org.json.JSONObject

/**
 * The one-line, human-readable projection of what Hermes is doing while a request is in flight. It is built from display fields
 * only (tool name, a short context preview, a status text, the first line of a conversational interim) and is bounded, control
 * free and secret screened. Raw tool output, results, arguments and reasoning are never read here.
 */
object ActivityLabels {
    const val WAITING = "⏳ Waiting for Hermes…"
    const val WORKING = "⏳ Working…"
    const val MAX_LENGTH = 80
    private const val UNKNOWN_TOOL = "🛠 Using a tool"

    private class Known(val icon: String, val verb: String, val showsContext: Boolean = false)

    private val known = mapOf(
        "execute_code" to Known("🐍", "Running code"),
        "terminal" to Known("💻", "Running a command"),
        "shell" to Known("💻", "Running a command"),
        "bash" to Known("💻", "Running a command"),
        "process" to Known("⚙️", "Managing a process"),
        "search_files" to Known("🔎", "Searching files", showsContext = true),
        "read_file" to Known("📖", "Reading a file", showsContext = true),
        "write_file" to Known("✏️", "Writing a file"),
        "patch" to Known("🩹", "Editing a file"),
        "web_search" to Known("🌐", "Searching the web", showsContext = true),
        "web_extract" to Known("🌐", "Reading a web page"),
        "skill_manage" to Known("📝", "Updating a skill"),
        "skill_view" to Known("📚", "Looking at a skill"),
        "skills_list" to Known("📚", "Listing skills"),
        "memory" to Known("🧠", "Using memory"),
        "todo" to Known("✅", "Updating the to-do list"),
        "delegate_task" to Known("🤝", "Delegating a task"),
        "vision_analyze" to Known("👁", "Looking at an image"),
        "image_generate" to Known("🎨", "Generating an image"),
        "cronjob" to Known("⏰", "Scheduling a task"),
    )

    private val safeName = Regex("^[A-Za-z0-9_.:-]{1,40}$")
    private val secret = Regex(
        "(?i)(token|secret|passw(or)?d|passwd|api[_-]?key|apikey|authorization|bearer|credential|private[_-]?key|cookie|session[_-]?id)" +
            "|sk-[A-Za-z0-9_-]{10,}|eyJ[A-Za-z0-9_-]{5,}\\.|AKIA[0-9A-Z]{8,}|://[^/\\s]*@|\\b[A-Fa-f0-9]{32,}\\b")

    private fun clean(text: String): String {
        val kept = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch.isWhitespace() || ch == '\u0085' -> kept.append(' ')
                ch.isISOControl() || ch in '​'..'‏' || ch in '‪'..'‮' || ch in '⁦'..'⁩' || ch == '﻿' -> Unit
                else -> kept.append(ch)
            }
        }
        return kept.toString().replace(Regex(" {2,}"), " ").trim()
    }

    private fun bounded(text: String): String {
        if (text.length <= MAX_LENGTH) return text
        var end = MAX_LENGTH - 1
        if (Character.isHighSurrogate(text[end - 1])) end -= 1
        return text.substring(0, end).trimEnd() + "…"
    }

    private fun preview(raw: String?): String? {
        if (raw.isNullOrBlank() || secret.containsMatchIn(raw)) return null
        return clean(raw).ifBlank { null }
    }

    fun tool(name: String?, context: String?): String {
        val cleanName = name?.takeIf { safeName.matches(it) && !secret.containsMatchIn(it) }
        val entry = cleanName?.let { known[it] ?: if (it.startsWith("browser_")) Known("🧭", "Browsing", showsContext = false) else null }
        if (entry == null) return if (cleanName == null) UNKNOWN_TOOL else bounded("🛠 Using $cleanName")
        val shown = if (entry.showsContext) preview(context) else null
        return bounded(if (shown == null) "${entry.icon} ${entry.verb}" else "${entry.icon} ${entry.verb}: $shown")
    }

    fun status(text: String?): String? {
        val shown = preview(text) ?: return null
        return bounded("ℹ️ $shown")
    }

    fun interim(text: String?): String? {
        val line = text?.lineSequence()?.map { clean(it) }?.firstOrNull { it.isNotEmpty() } ?: return null
        if (secret.containsMatchIn(line)) return null
        return bounded("💬 $line")
    }

    fun forEvent(type: String, payload: JSONObject?): String? = when (type) {
        "tool.start" -> tool(payload.text("name"), payload.text("context"))
        "tool.generating" -> tool(payload.text("name"), null)
        "tool.complete" -> WORKING
        "status.update" -> status(payload.text("text"))
        else -> null
    }

    private fun JSONObject?.text(key: String): String? = if (this == null || isNull(key)) null else optString(key, "")
}
