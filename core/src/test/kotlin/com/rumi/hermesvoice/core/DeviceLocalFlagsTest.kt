package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.background.BackgroundNotice
import com.rumi.hermesvoice.core.background.BackgroundPort
import com.rumi.hermesvoice.core.background.BackgroundSession
import com.rumi.hermesvoice.core.background.DeviceLocalFlags
import com.rumi.hermesvoice.core.settings.AppSettings
import com.rumi.hermesvoice.core.settings.WakeLocation
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The background opt-ins belong to one install: they live in their own preferences file, which
 * backup and device transfer exclude, and a legacy copy in the backed-up settings is never carried
 * over. The session is driven exactly as the Phone app drives its relay.
 */
class DeviceLocalFlagsTest {
    private class Relay(val local: KeyValueStore) {
        val calls = mutableListOf<String>()
        val session = BackgroundSession(local, DeviceLocalFlags.KEY_RELAY, object : BackgroundPort {
            override fun startService(microphone: Boolean): Boolean { calls += "start"; return true }
            override fun retypeService(microphone: Boolean) = !microphone
            override fun stopService() { calls += "stop" }
        }, resumeWhenVisible = true)

        /** PhoneApp.onActivityStarted. */
        fun opened() = session.onVisible(microphoneWanted = false, microphonePermission = false)
    }

    /** A settings file as an earlier build left it: voice settings plus the relay flags it kept there. */
    private fun legacySettings(): InMemoryKeyValueStore = InMemoryKeyValueStore().apply {
        AppSettings(this).apply { dashboardUrl = "https://dash.example"; wakeLocation = WakeLocation.BOTH; vadSilenceSeconds = 3.5 }
        putBoolean(DeviceLocalFlags.KEY_RELAY, true)
        putBoolean(DeviceLocalFlags.KEY_NOTIFICATIONS_ASKED, true)
    }

    @Test
    fun `a restored or updated install never starts the relay from a legacy copy, and keeps its other settings`() {
        val shared = legacySettings()
        // Restored elsewhere, or updated in place: the device-local file is new and empty.
        val local = InMemoryKeyValueStore()
        assertTrue(DeviceLocalFlags.dropLegacy(shared, local))
        val relay = Relay(local)
        assertEquals(BackgroundNotice.OFF, relay.opened().notice)
        assertEquals("opening the app starts nothing", emptyList<String>(), relay.calls)
        assertFalse("the legacy copy is off, so a later backup carries nothing either", shared.getBoolean(DeviceLocalFlags.KEY_RELAY, true))
        assertFalse(shared.getBoolean(DeviceLocalFlags.KEY_NOTIFICATIONS_ASKED, true))
        val settings = AppSettings(shared)
        assertEquals("https://dash.example", settings.dashboardUrl)
        assertEquals(WakeLocation.BOTH, settings.wakeLocation)
        assertEquals(3.5, settings.vadSilenceSeconds, 0.0)
        // Once only: switched on again by the user, it stays on across restarts of this install.
        relay.session.start(visible = true, microphoneWanted = false, microphonePermission = false)
        assertFalse(DeviceLocalFlags.dropLegacy(shared, local))
        val restarted = Relay(local)
        assertEquals(BackgroundNotice.PAUSED, restarted.session.status.notice)
        assertEquals(BackgroundNotice.RUNNING, restarted.opened().notice)
    }

    @Test
    fun `a fresh install starts with the relay off and asks for notifications`() {
        val shared = InMemoryKeyValueStore()
        val local = InMemoryKeyValueStore()
        assertFalse(DeviceLocalFlags.dropLegacy(shared, local))
        assertEquals(BackgroundNotice.OFF, Relay(local).opened().notice)
        assertFalse(local.getBoolean(DeviceLocalFlags.KEY_NOTIFICATIONS_ASKED, false))
    }

    // ── the files really used, and the rules that exclude them ──

    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun excluded(xml: String, section: String?): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(root, xml))
        val scope = if (section == null) doc.documentElement else doc.getElementsByTagName(section).item(0) as Element
        val nodes = scope.getElementsByTagName("exclude")
        return (0 until nodes.length).map { nodes.item(it) as Element }.filter { it.getAttribute("domain") == "sharedpref" }
            .map { it.getAttribute("path") }.toSet()
    }

    @Test
    fun `both apps exclude their device-local file from backup and device transfer, and use it for the opt-ins`() {
        for ((app, file) in listOf("phone" to DeviceLocalFlags.PHONE_PREFERENCES, "watch" to DeviceLocalFlags.WATCH_PREFERENCES)) {
            val manifest = File(root, "$app/src/main/AndroidManifest.xml").readText()
            assertTrue(app, manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\"") &&
                manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
            val rules = "$app/src/main/res/xml/data_extraction_rules.xml"
            for (section in listOf("cloud-backup", "device-transfer")) assertTrue("$app $section", "$file.xml" in excluded(rules, section))
            assertTrue("$app full backup", "$file.xml" in excluded("$app/src/main/res/xml/backup_rules.xml", null))
        }
        assertTrue("the Phone still excludes its sealed credentials", "hermes_voice_secure.xml" in excluded("phone/src/main/res/xml/backup_rules.xml", null))
        val phoneApp = File(root, "phone/src/main/kotlin/com/rumi/hermesvoice/phone/PhoneApp.kt").readText()
        assertTrue(phoneApp.contains("prefs(DeviceLocalFlags.PHONE_PREFERENCES)") && phoneApp.contains("BackgroundSession(localStore, DeviceLocalFlags.KEY_RELAY"))
        assertTrue(phoneApp.contains("localStore.putBoolean(DeviceLocalFlags.KEY_NOTIFICATIONS_ASKED, true)"))
        assertFalse("nothing of the relay is kept in the backed-up settings any more", phoneApp.contains("BackgroundSession(settingsStore"))
        assertTrue(File(root, "watch/src/main/kotlin/com/rumi/hermesvoice/watch/WatchApp.kt").readText()
            .contains("getSharedPreferences(DeviceLocalFlags.WATCH_PREFERENCES, Context.MODE_PRIVATE)"))
    }
}
