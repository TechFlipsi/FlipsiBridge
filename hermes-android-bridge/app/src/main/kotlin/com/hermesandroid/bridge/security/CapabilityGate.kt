package com.hermesandroid.bridge.security

import android.content.Context
import android.content.SharedPreferences

/**
 * FlipsiBridge Capability-Gating (Issue #107-Umsetzung).
 *
 * Jede Geräte-Fähigkeit ist einzeln opt-in. Standard: alles AUS.
 * Gesperrte Endpunkte liefern 403 mit klarer Fehlermeldung — der Server
 * registriert idealerweise nur Tools der freigeschalteten Capabilities,
 * aber dieses Gate ist die letzte Verteidigungslinie im Gerät selbst.
 *
 * Kategorie "core" (ping, battery, apps, current_app, screen-lesen) ist
 * immer erlaubt — sie ist für den Verbindungstest nötig und nicht invasiv.
 */
object CapabilityGate {

    /** Alle Endpunkte mit ihrer Capability-Kategorie. */
    private val routeCapability: Map<Pair<String, String>, String> = mapOf(
        // core — immer erlaubt
        Pair("GET", "/ping") to "core",
        Pair("GET", "/battery") to "core",
        Pair("GET", "/apps") to "core",
        Pair("GET", "/current_app") to "core",
        Pair("GET", "/screen") to "screen",
        Pair("GET", "/screen_hash") to "screen",
        Pair("POST", "/find_nodes") to "screen",
        Pair("POST", "/describe_node") to "screen",
        Pair("POST", "/diff_screen") to "screen",
        // interaction — Bedienen des Geräts
        Pair("POST", "/tap") to "interaction",
        Pair("POST", "/tap_text") to "interaction",
        Pair("POST", "/type") to "interaction",
        Pair("POST", "/swipe") to "interaction",
        Pair("POST", "/scroll") to "interaction",
        Pair("POST", "/long_press") to "interaction",
        Pair("POST", "/drag") to "interaction",
        Pair("POST", "/pinch") to "interaction",
        Pair("POST", "/wait") to "interaction",
        Pair("POST", "/press_key") to "interaction",
        Pair("POST", "/open_app") to "interaction",
        Pair("POST", "/intent") to "interaction",
        Pair("POST", "/broadcast") to "interaction",
        Pair("POST", "/media") to "interaction",
        // screen_capture — MediaProjection
        Pair("GET", "/screenshot") to "screen_capture",
        Pair("POST", "/screen_record") to "screen_capture",
        // notifications
        Pair("GET", "/notifications") to "notifications",
        Pair("GET", "/events") to "notifications",
        Pair("POST", "/events/stream") to "notifications",
        // clipboard
        Pair("GET", "/clipboard") to "clipboard",
        Pair("POST", "/clipboard") to "clipboard",
        // INVASIV — standardmäßig nicht mal sichtbar in der UI:
        Pair("GET", "/location") to "location",
        Pair("POST", "/send_sms") to "sms",
        Pair("POST", "/call") to "calls",
        Pair("GET", "/contacts") to "contacts",
        Pair("POST", "/mic_start") to "microphone",
        Pair("POST", "/mic_stop") to "microphone",
        Pair("GET", "/mic_status") to "microphone",
        Pair("GET", "/mic_file") to "microphone",
        // files (Phase 4) — lesen/suchen im gemeinsamen Speicher
        Pair("GET", "/files") to "files",
        Pair("GET", "/files_search") to "files",
        Pair("GET", "/files_count") to "files",
        Pair("GET", "/files_permission") to "files",
        Pair("POST", "/files_permission") to "files",
        Pair("GET", "/file") to "files",
        // selfupdate (Phase 4) — APK herunterladen + Installer starten
        Pair("POST", "/files_push") to "files",
        Pair("POST", "/files_delete") to "files",
        Pair("POST", "/apk_install") to "selfupdate",
        // misc ohne eigene Kategorie
        Pair("POST", "/speak") to "interaction",
        Pair("POST", "/stop_speaking") to "interaction",
        Pair("GET", "/widgets") to "screen",
    )

    /** Anzeigbare Capabilities (für die UI-Liste). */
    val userFacing: List<Pair<String, String>> = listOf(
        "screen" to "Bildschirm lesen",
        "interaction" to "Gerät bedienen (tippen, swipen)",
        "screen_capture" to "Screenshots & Aufnahme",
        "notifications" to "Benachrichtigungen lesen",
        "clipboard" to "Zwischenablage",
        "location" to "Standort (invasiv)",
        "sms" to "SMS senden (invasiv)",
        "calls" to "Anrufe (invasiv)",
        "contacts" to "Kontakte lesen (invasiv)",
        "microphone" to "Mikrofon (invasiv)",
        "files" to "Dateien lesen (Download, Dokumente, Fotos …)",
        "selfupdate" to "App-Update installieren (mit Bestätigung)",
    )

    private const val PREFS = "flipsibridge_caps"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun isEnabled(capability: String): Boolean {
        if (capability == "core") return true
        // Migration: Wer das Original-Setup nutzte, hatte implizit alles —
        // aber FlipsiBridge-Neuinstallationen starten strikt bei AUS.
        return prefs?.getBoolean("cap_$capability", false) ?: false
    }

    fun setEnabled(capability: String, enabled: Boolean) {
        prefs?.edit()?.putBoolean("cap_$capability", enabled)?.apply()
    }

    /** Prüft einen Endpunkt. Gibt null zurück wenn erlaubt, sonst Fehlermeldung. */
    fun checkEndpoint(method: String, path: String): String? {
        val cap = routeCapability[Pair(method.uppercase(), path)] ?: return null
        return if (isEnabled(cap)) null else "Capability '$cap' ist deaktiviert. In der FlipsiBridge-App freischalten."
    }

    /** Welche Capabilities sind aktiv — fürs /ping-Handshake (Server-Tool-Filterung). */
    fun activeCapabilities(): List<String> {
        return userFacing.map { it.first }.filter { isEnabled(it) } + "core"
    }
}