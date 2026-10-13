package com.rumi.hermesvoice.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity between the message paths each listener service actually handles in code and the manifest filters that make Android
 * deliver them (a service is only started for a Wearable message when an intent filter matches its exact path). The handled paths
 * are READ from the `when (event.path)` branches and resolved through [com.rumi.hermesvoice.core.watchlink.WatchLinkPaths]; the
 * senders are read from the Watch's own `sendMessage` calls. A text match proves what the merged manifest source declares - the
 * built APK is audited separately; it never proves that a real Wear bridge delivers anything.
 */
class ManifestMessagePathParityTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun source(path: String) = File(root, path).readText()

    private val phone = "phone/src/main/kotlin/com/rumi/hermesvoice/phone"
    private val watch = "watch/src/main/kotlin/com/rumi/hermesvoice/watch"

    private val constants: Map<String, String> by lazy {
        Regex("""const val (\w+) = "(/hv/v1/[^"]*)"""").findAll(source("core/src/main/kotlin/com/rumi/hermesvoice/core/watchlink/WatchLink.kt"))
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun resolve(names: Collection<String>): Set<String> = names.map { constants.getValue(it) }.toSet()

    /** The constants named by the `when (event.path)` branches of the one `onMessageReceived` in [file]. */
    private fun handledNames(file: String): Set<String> {
        val body = source(file).substringAfter("override fun onMessageReceived(event: MessageEvent)").substringBefore("override fun onDataChanged")
            .substringBefore("\n    /**")
        val branches = Regex("""WatchLinkPaths\.(\w+)\s*->""").findAll(body).map { it.groupValues[1] }.toSet()
        assertTrue("onMessageReceived of $file has when-branches", branches.isNotEmpty())
        return branches
    }

    private class Filter(val actions: List<String>, val exact: List<String>, val prefixes: List<String>, val schemes: List<String>, val hosts: List<String>)

    private fun serviceFilters(manifest: String, service: String): List<Filter> {
        val block = manifest.substringAfter("android:name=\"$service\"").substringBefore("</service>")
        return Regex("""<intent-filter>(.*?)</intent-filter>""", RegexOption.DOT_MATCHES_ALL).findAll(block).map { filter ->
            val text = filter.groupValues[1]
            Filter(
                Regex("""<action android:name="([^"]+)"""").findAll(text).map { it.groupValues[1] }.toList(),
                Regex("""android:path="([^"]+)"""").findAll(text).map { it.groupValues[1] }.toList(),
                Regex("""android:pathPrefix="([^"]+)"""").findAll(text).map { it.groupValues[1] }.toList(),
                Regex("""android:scheme="([^"]+)"""").findAll(text).map { it.groupValues[1] }.toList(),
                Regex("""android:host="([^"]+)"""").findAll(text).map { it.groupValues[1] }.toList(),
            )
        }.toList()
    }

    private val message = "com.google.android.gms.wearable.MESSAGE_RECEIVED"

    private fun deliverable(filters: List<Filter>, path: String) = filters.any { f ->
        message in f.actions && f.schemes == listOf("wear") && f.hosts == listOf("*") &&
            (path in f.exact || f.prefixes.any { path.startsWith(it) })
    }

    @Test
    fun `every message path the Phone listener handles has a MESSAGE_RECEIVED filter for exactly that path`() {
        val handled = resolve(handledNames("$phone/PhoneWatchBridge.kt"))
        val filters = serviceFilters(source("phone/src/main/AndroidManifest.xml"), ".PhoneWatchListenerService")
        val missing = handled.filterNot { deliverable(filters, it) }
        assertTrue("handled in code but not delivered by the Phone manifest: $missing", missing.isEmpty())
        val declared = filters.filter { message in it.actions }.flatMap { it.exact }.toSet()
        assertEquals("the Phone manifest declares exactly the handled message paths", handled, declared)
    }

    @Test
    fun `every message path the Watch sends to the Phone is handled by the Phone listener and delivered by its manifest`() {
        val sent = Regex("""sendMessage\(\s*[^,]+,\s*WatchLinkPaths\.(\w+)""").findAll(
            File(root, watch).walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() },
        ).map { it.groupValues[1] }.toSet()
        assertTrue("the Watch sends messages", sent.isNotEmpty())
        assertTrue("the private-output receipt is one of them", "PRIVATE_AUDIO" in sent)
        val handled = handledNames("$phone/PhoneWatchBridge.kt")
        val filters = serviceFilters(source("phone/src/main/AndroidManifest.xml"), ".PhoneWatchListenerService")
        assertEquals("sent by the Watch but not handled by the Phone: ${sent - handled}", emptySet<String>(), sent - handled)
        val undelivered = resolve(sent).filterNot { deliverable(filters, it) }
        assertTrue("sent by the Watch but not delivered by the Phone manifest: $undelivered", undelivered.isEmpty())
    }

    @Test
    fun `the Watch manifest delivers every message path its listener handles`() {
        val handled = resolve(handledNames("$watch/WatchListenerService.kt"))
        val filters = serviceFilters(source("watch/src/main/AndroidManifest.xml"), ".WatchListenerService")
        val missing = handled.filterNot { deliverable(filters, it) }
        assertTrue("handled by the Watch but not delivered: $missing", missing.isEmpty())
    }

    @Test
    fun `the private-output receipt path is a message filter on the Phone service and the service is still exported`() {
        val manifest = source("phone/src/main/AndroidManifest.xml")
        val service = manifest.substringAfter("android:name=\".PhoneWatchListenerService\"").substringBefore("</service>")
        assertTrue(service.contains("android:exported=\"true\""))
        assertTrue(
            Regex("""<action android:name="$message"\s*/>\s*<data android:scheme="wear" android:host="\*" android:path="/hv/v1/private_audio"\s*/>""")
                .containsMatchIn(service),
        )
    }
}
