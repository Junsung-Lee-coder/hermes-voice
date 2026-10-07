package com.rumi.hermesvoice.core

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * The share path's static Android configuration, read as XML (not matched as text): a not-exported provider with per-URI read
 * grants over one narrow cache folder, and the Watch/Phone message routes the diagnostics and per-request Stop use. What the
 * share sheet then does on a device is not exercised here.
 */
class DiagnosticsManifestTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun xml(path: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(root, path)).documentElement

    private fun Element.all(tag: String): List<Element> =
        (0 until getElementsByTagName(tag).length).map { getElementsByTagName(tag).item(it) as Element }

    private fun Element.attr(name: String) = getAttributeNS("http://schemas.android.com/apk/res/android", name).ifEmpty { getAttribute("android:$name") }

    @Test
    fun `the only provider is not exported, grants per URI and shares one narrow cache folder read-only`() {
        val manifest = xml("phone/src/main/AndroidManifest.xml")
        val providers = manifest.all("provider")
        assertEquals(1, providers.size)
        val provider = providers.single()
        assertEquals("androidx.core.content.FileProvider", provider.attr("name"))
        assertEquals("false", provider.attr("exported"))
        assertEquals("true", provider.attr("grantUriPermissions"))
        assertEquals("\${applicationId}.diagshare", provider.attr("authorities"))
        val meta = provider.all("meta-data").single()
        assertEquals("android.support.FILE_PROVIDER_PATHS", meta.attr("name"))
        assertEquals("@xml/diag_share_paths", meta.attr("resource"))

        val paths = xml("phone/src/main/res/xml/diag_share_paths.xml")
        val entries = (0 until paths.childNodes.length).map { paths.childNodes.item(it) }.filter { it.nodeType == Node.ELEMENT_NODE }.map { it as Element }
        assertEquals("exactly one shared folder", 1, entries.size)
        assertEquals("cache-path", entries.single().tagName)
        assertEquals("diag/", entries.single().getAttribute("path"))
    }

    @Test
    fun `no permission or component is added for the share, and the share is not an exported receiver`() {
        val manifest = xml("phone/src/main/AndroidManifest.xml")
        val permissions = manifest.all("uses-permission").map { it.attr("name") }
        assertFalse(permissions.any { it.contains("STORAGE") || it.contains("MANAGE_EXTERNAL") })
        val providerNames = manifest.all("provider").map { it.attr("name") }
        assertTrue(providerNames.none { it.contains("Diag") })
    }

    @Test
    fun `the Phone listens for the Watch's per-request cancel and diagnostics answer`() {
        val manifest = xml("phone/src/main/AndroidManifest.xml")
        val paths = manifest.all("data").map { it.attr("path") }
        assertTrue(paths.contains("/hv/v1/cancel"))
        assertTrue(paths.contains("/hv/v1/diag/response"))
    }

    @Test
    fun `the Watch listens for the Phone's diagnostics request`() {
        val manifest = xml("watch/src/main/AndroidManifest.xml")
        val prefixes = manifest.all("data").map { it.attr("pathPrefix") }
        assertTrue("/hv/v1/diag/request" .startsWith(prefixes.first { it.isNotEmpty() && "/hv/v1/diag/request".startsWith(it) }))
    }
}
