package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.notify.ConversationPresence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a conversation is really in front of the user: shown AND the app is in the foreground. */
class ConversationPresenceTest {
    private val opened = ArrayList<String>()
    private fun presence() = ConversationPresence().also { it.onOpened = { s -> opened += s } }

    @Test fun `nothing is visible until the conversation is shown in the foreground`() {
        val p = presence()
        p.setShown("sA")
        assertNull(p.visibleSession)
        assertFalse(p.isVisible("sA"))
        assertTrue("selecting in the background is not an open", opened.isEmpty())
        p.setForeground(true)
        assertEquals("sA", p.visibleSession)
        assertTrue(p.isVisible("sA"))
        assertFalse(p.isVisible("sB"))
        assertEquals(listOf("sA"), opened)
    }

    @Test fun `showing a conversation while in the foreground is an open, once`() {
        val p = presence()
        p.setForeground(true)
        p.setShown("sA"); p.setShown("sA")
        assertEquals(listOf("sA"), opened)
        p.setShown("sB")
        assertEquals(listOf("sA", "sB"), opened)
    }

    @Test fun `leaving the screen or the app makes nothing visible and opens nothing`() {
        val p = presence()
        p.setForeground(true); p.setShown("sA")
        p.setShown(null)
        assertNull(p.visibleSession)
        p.setShown("sA"); p.setForeground(false)
        assertNull(p.visibleSession)
        assertEquals(listOf("sA", "sA"), opened)
    }

    @Test fun `returning to the foreground with the same conversation shown is an open again`() {
        val p = presence()
        p.setForeground(true); p.setShown("sA")
        p.setForeground(false)
        p.setForeground(true)
        assertEquals(listOf("sA", "sA"), opened)
        p.setForeground(true)
        assertEquals("a repeated foreground signal is not a new open", listOf("sA", "sA"), opened)
    }

    @Test fun `a throwing listener never breaks the state`() {
        val p = ConversationPresence().also { it.onOpened = { error("boom") } }
        p.setForeground(true); p.setShown("sA")
        assertEquals("sA", p.visibleSession)
    }
}
