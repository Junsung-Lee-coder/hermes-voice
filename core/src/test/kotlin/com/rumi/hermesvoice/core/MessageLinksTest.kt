package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.text.MessageLinks
import com.rumi.hermesvoice.core.text.MessageSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F: which parts of a chat message are openable links. */
class MessageLinksTest {
    private fun links(text: String) = MessageLinks.segments(text).filterIsInstance<MessageSegment.Link>().map { it.url }

    @Test
    fun `segments always rebuild the exact message text`() {
        val text = "see https://example.com/a?b=1#c, then (https://example.org/x) and [doc](https://docs.example.com/p). Done."
        assertEquals(text, MessageLinks.segments(text).joinToString("") { it.text })
    }

    @Test
    fun `ordinary http and https links are found and sentence punctuation is left out`() {
        assertEquals(listOf("https://example.com/a?b=1#c"), links("see https://example.com/a?b=1#c, ok"))
        assertEquals(listOf("http://example.com"), links("go to http://example.com."))
        assertEquals(listOf("https://example.com/x"), links("(https://example.com/x)"))
        assertEquals(listOf("https://en.wikipedia.org/wiki/Kotlin_(programming_language)"), links("https://en.wikipedia.org/wiki/Kotlin_(programming_language)."))
        assertEquals(listOf("https://example.com/a", "https://example.org/b"), links("https://example.com/a and https://example.org/b!"))
        assertEquals(listOf("HTTPS://EXAMPLE.COM/Path"), links("HTTPS://EXAMPLE.COM/Path"))
    }

    @Test
    fun `a markdown link stays visible as written and its address is the tappable part`() {
        val segments = MessageLinks.segments("[the docs](https://docs.example.com/p)")
        assertEquals("[the docs](", segments[0].text)
        assertEquals("https://docs.example.com/p", (segments[1] as MessageSegment.Link).url)
        assertEquals(")", segments[2].text)
    }

    @Test
    fun `other schemes, credentials and malformed addresses are never links`() {
        for (bad in listOf("javascript:alert(1)", "intent://scan/#Intent;scheme=zxing;end", "file:///etc/passwd", "data:text/html;base64,AAAA",
            "content://x/y", "ftp://example.com/a", "https://user:pass@example.com/", "https://user@example.com/", "http://example.com:80@evil.example/",
            "https://", "https:///path", "https://exa mple.com", "https://example.com:99999/", "https://example.com:0/", "//example.com", "example.com",
            "https://exa\u0000mple.com", "https://example.com/a\\b")) {
            assertNull(bad, MessageLinks.safeUrl(bad))
        }
        assertTrue(links("javascript:alert(1) file:///etc/passwd data:text/plain,hi intent://x#Intent;end").isEmpty())
        assertTrue(links("https://user:pass@example.com/ is not a link").isEmpty())
    }

    @Test
    fun `a text with a scheme hidden inside a longer word is not turned into a link`() {
        assertTrue(links("xjavascript:alert(1) and notahttps://x and foo.https://y").isEmpty())
    }

    @Test
    fun `long urls are accepted up to the bound and refused beyond it`() {
        val ok = "https://example.com/" + "a".repeat(MessageLinks.MAX_URL - 30)
        assertEquals(ok, MessageLinks.safeUrl(ok))
        assertNull(MessageLinks.safeUrl("https://example.com/" + "a".repeat(MessageLinks.MAX_URL)))
    }

    @Test
    fun `the number of links in one message is bounded`() {
        val many = (1..(MessageLinks.MAX_LINKS + 10)).joinToString(" ") { "https://example.com/$it" }
        assertEquals(MessageLinks.MAX_LINKS, links(many).size)
        assertEquals(many, MessageLinks.segments(many).joinToString("") { it.text })
    }

    @Test
    fun `ipv6 literals, ports, queries and non-ascii hosts are accepted`() {
        assertEquals(listOf("http://[::1]:8080/x"), links("http://[::1]:8080/x"))
        assertEquals(listOf("https://example.com:8443/a?b[]=1&c=|"), links("https://example.com:8443/a?b[]=1&c=|"))
        assertEquals(listOf("https://한글.example/경로"), links("https://한글.example/경로"))
    }

    @Test
    fun `text without links is one plain segment, and empty text has none`() {
        assertTrue(MessageLinks.segments("").isEmpty())
        val only = MessageLinks.segments("no links here, just http: and https//")
        assertEquals(1, only.size)
        assertTrue(only[0] is MessageSegment.Plain)
    }
}
