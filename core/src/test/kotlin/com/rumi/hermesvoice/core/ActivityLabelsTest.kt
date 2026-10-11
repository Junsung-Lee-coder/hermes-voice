package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.activity.ActivityLabels
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The human-readable, bounded, secret-free one-line projection of what Hermes is doing (never a log, never reasoning). */
class ActivityLabelsTest {
    private fun tool(type: String, name: String, context: String? = null, args: JSONObject? = null, extra: JSONObject.() -> Unit = {}): String? {
        val payload = JSONObject().put("tool_id", "t1").put("name", name)
        if (context != null) payload.put("context", context)
        if (args != null) payload.put("args", args)
        payload.extra()
        return ActivityLabels.forEvent(type, payload)
    }

    @Test fun `the user's own examples read as an icon and a short verb`() {
        assertTrue(tool("tool.start", "execute_code")!!.let { it.startsWith("🐍") && it.contains("Running code") })
        assertTrue(tool("tool.start", "search_files")!!.let { it.startsWith("🔎") && it.contains("Searching files") })
        assertTrue(tool("tool.start", "skill_manage")!!.let { it.startsWith("📝") && it.contains("skill") })
    }

    @Test fun `a safe tool shows its short context preview`() {
        val label = tool("tool.start", "search_files", context = "*.kt in core")!!
        assertTrue(label, label.contains("*.kt in core"))
        assertTrue(tool("tool.start", "web_search", context = "android notification channels")!!.contains("android notification channels"))
    }

    @Test fun `code, commands and raw arguments are never shown`() {
        val code = "print(open('/etc/hosts').read())"
        val label = tool("tool.start", "execute_code", context = code, args = JSONObject().put("code", code))!!
        assertFalse(label, label.contains("etc/hosts") || label.contains("print("))
        val shell = tool("tool.start", "terminal", context = "curl -H 'Authorization: Bearer sk-live-0123456789abcdef0123456789abcdef' https://x.test",
            args = JSONObject().put("command", "curl -H 'Authorization: Bearer sk-live-0123456789abcdef0123456789abcdef'"))!!
        assertFalse(shell, shell.contains("sk-live") || shell.contains("Bearer") || shell.contains("Authorization") || shell.contains("curl"))
        assertTrue(shell, shell.startsWith("💻"))
    }

    @Test fun `a secret-looking preview is dropped but the activity is still shown`() {
        for (secret in listOf("token=abcd1234efgh5678ijkl9012mnop3456", "Authorization: Bearer abc.def.ghi", "password is hunter2hunter2",
            "sk-proj-abcdefghijklmnopqrstuvwxyz0123456789", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abc", "https://user:pw@example.com/x",
            "https://example.com/cb?api_key=ZZZZZZZZZZZZ", "AKIAIOSFODNN7EXAMPLE1234567890abcdefghij")) {
            val label = tool("tool.start", "search_files", context = secret)!!
            assertTrue(label, label.contains("Searching files"))
            assertFalse("secret leaked: $label", label.contains(secret.substringAfter("=").substringAfter(": ").take(12)) || label.contains("hunter2") || label.contains("pw@"))
        }
    }

    @Test fun `a preview is one line, collapsed and bounded`() {
        val label = tool("tool.start", "read_file", context = "line one\n\n   line two\t" + "x".repeat(500))!!
        assertFalse(label, label.contains('\n') || label.contains('\t') || label.contains("  "))
        assertTrue("length ${label.length}: $label", label.length <= ActivityLabels.MAX_LENGTH)
        assertTrue(label, label.endsWith("…"))
    }

    @Test fun `an unknown tool shows only a safe bounded name`() {
        assertEquals("🛠 Using weird_tool", tool("tool.start", "weird_tool"))
        assertEquals("🛠 Using a tool", tool("tool.start", "bad name; rm -rf /"))
        assertEquals("🛠 Using a tool", tool("tool.start", "x".repeat(80)))
        assertEquals("🛠 Using a tool", tool("tool.start", ""))
        assertEquals("🛠 Using a tool", ActivityLabels.forEvent("tool.start", null))
        assertEquals("🛠 Using a tool", ActivityLabels.tool(null, null))
    }

    @Test fun `every label is a single bounded line without control characters`() {
        val nasty = "a\u0000b‮\r\n\u001b[31mred" + "é".repeat(200)
        val all = listOf(tool("tool.start", "terminal", nasty), tool("tool.start", "read_file", nasty), tool("tool.generating", "write_file", nasty),
            ActivityLabels.status(nasty), ActivityLabels.interim(nasty), ActivityLabels.tool("n\u0000ame", nasty))
        for (label in all) {
            assertNotNull(label)
            assertTrue(label!!, label.length <= ActivityLabels.MAX_LENGTH)
            assertTrue(label, label.none { it.isISOControl() || it == '‮' })
        }
    }

    @Test fun `generating, completion and status events map to a replace-in-place label`() {
        assertTrue(tool("tool.generating", "write_file")!!.contains("Writing"))
        assertEquals(ActivityLabels.WORKING, tool("tool.complete", "execute_code", extra = { put("result", "SECRET OUTPUT").put("summary", "SECRET SUMMARY") }))
        assertEquals("ℹ️ Compressing context…", ActivityLabels.forEvent("status.update", JSONObject().put("kind", "compress").put("text", "Compressing context…")))
        assertNull(ActivityLabels.forEvent("status.update", JSONObject().put("kind", "x").put("text", "   ")))
        assertNull(ActivityLabels.forEvent("status.update", JSONObject().put("text", "Bearer abcdefghijklmnopqrstuvwxyz0123456789")))
    }

    @Test fun `a conversational interim becomes its first bounded line`() {
        assertEquals("💬 Checking the file now", ActivityLabels.interim("Checking the file now\nand then the rest"))
        assertNull(ActivityLabels.interim("   "))
        assertNull(ActivityLabels.interim(null))
        assertTrue(ActivityLabels.interim("y".repeat(900))!!.length <= ActivityLabels.MAX_LENGTH)
        assertNull("a secret-bearing interim line is not shown", ActivityLabels.interim("Authorization: Bearer abcdefghijklmnopqrstuvwxyz0123456789"))
    }

    @Test fun `reasoning, deltas and unknown events produce nothing`() {
        for (type in listOf("reasoning.delta", "thinking.delta", "reasoning.available", "message.delta", "message.start", "message.complete", "error", "gateway.ready", "whatever")) {
            assertNull(type, ActivityLabels.forEvent(type, JSONObject().put("text", "private thought").put("name", "execute_code")))
        }
    }

    @Test fun `waiting and working are fixed human labels`() {
        assertEquals("⏳ Waiting for Hermes…", ActivityLabels.WAITING)
        assertEquals("⏳ Working…", ActivityLabels.WORKING)
    }
}
