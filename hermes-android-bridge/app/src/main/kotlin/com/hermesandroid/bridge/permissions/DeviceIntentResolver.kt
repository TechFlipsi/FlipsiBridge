package com.hermesandroid.bridge.permissions

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.hermesandroid.bridge.BuildConfig

/**
 * PH-2X X-2 — OEM-KOMPATIBILITÄTSPROFILE (Play-Store-Vorbereitung).
 *
 * Ersetzt den MIUI-Autostart-Hardcode (BridgeScreen v0.12.9, Zeilen 1149–1161)
 * durch eine generische Hersteller-Matrix: JE Profil (Xiaomi/Samsung/OPPO/
 * VIVO/Huawei+Honor/Motorola/Nothing/generisch-AOSP) × JE Feature (Autostart/
 * Akku/Overlay/Benachrichtigungen) eine geordnete Kandidaten-Liste.
 *
 * Ablauf beim Nutzer-Tap (launchFeature):
 *  1. je Kandidat `packageManager.resolveActivity(...) != null` prüfen,
 *  2. den ERSTEN auflösbaren Intent starten (runCatching — kein Crash),
 *  3. löst keiner auf: informative Leit-Zeile (fallbackText) statt Crash.
 *
 * Play-Grundregel: die App muss überall launchen — OEM-Komponentennamen sind
 * undokumentiert und versionieren; falsche Kandidaten sind ungefährlich
 * (resolveActivity filtert sie zur Laufzeit raus), Fehlen ist nie fatal.
 */
enum class DeviceFeature { AUTOSTART, BATTERY, OVERLAY, NOTIFICATION }

/**
 * Hersteller-Profil, erkannt über `Build.MANUFACTURER` (case-insensitive).
 * OnePlus/realme laufen unter dem OPPO-Profil (ColorOS-Basis + gleiche
 * Safecenter-Pakete), Honor unter Huawei (com.huawei.systemmanager).
 */
enum class DeviceProfile(val oemLabel: String, val manufacturers: Set<String>) {
    XIAOMI("HyperOS/MIUI", setOf("xiaomi", "redmi", "poco")),
    SAMSUNG("One UI", setOf("samsung")),
    OPPO("ColorOS", setOf("oppo", "realme", "oneplus")),
    VIVO("Funtouch OS", setOf("vivo", "iqoo")),
    HUAWEI("EMUI", setOf("huawei", "honor")),
    MOTOROLA("My UX", setOf("motorola")),
    NOTHING("Nothing OS", setOf("nothing")),
    GENERIC("Android", emptySet());

    companion object {
        /** Hersteller-String → Profil; Unbekannt/leer → GENERIC (AOSP-Weg). */
        fun fromManufacturer(manufacturer: String?): DeviceProfile =
            entries.firstOrNull { profile ->
                profile.manufacturers.any { it.equals(manufacturer?.trim(), ignoreCase = true) }
            } ?: GENERIC
    }
}

/**
 * Ein Intent-Kandidat: entweder Action (+optional Data-URI) oder explizite
 * OEM-Komponente (package/class). `$app` in dataUri/extras wird zur Laufzeit
 * durch den App-Paketnamen ersetzt. `identityKey` dient der Duplikat-Prüfung
 * (Unit-Test: je Profil/Feature keine doppelten Kandidaten).
 */
data class DeviceIntentCandidate(
    val label: String,
    val action: String? = null,
    val pkg: String? = null,
    val cls: String? = null,
    val dataUri: String? = null,
    val extras: Map<String, String> = emptyMap(),
) {
    val isComponent: Boolean get() = pkg != null && cls != null

    fun toIntent(appPackage: String): Intent {
        // Receiver-Shadowing-Falle: Intent hat eigene Properties action/extras —
        // innerhalb von Intent().apply {} würden die Kandidaten-Felder dadurch
        // still verdeckt (extras = Bundle? ohne forEach = Compile-Bruch, action
        // = null-Property = OEM-Action nie gesetzt). Deshalb: vor apply captchen.
        val candAction = action
        val candPkg = pkg
        val candCls = cls
        val candUri = dataUri
        val candExtras = extras
        return Intent().apply {
            candAction?.let { setAction(it) }
            if (candPkg != null && candCls != null) component = ComponentName(candPkg, candCls)
            candUri?.let { data = Uri.parse(it.replace("\$app", appPackage)) }
            candExtras.forEach { (key, value) -> putExtra(key, value.replace("\$app", appPackage)) }
        }
    }

    /** Eindeutiger Schlüssel für die Duplikat-Prüfung. */
    fun identityKey(): String =
        listOfNotNull(action, pkg, cls, dataUri).joinToString("|") +
            extras.keys.sorted().joinToString(",", prefix = "#")
}

/**
 * Resolver: Kandidaten-Listen + erste-auflösbare-Ausführung + Fallback-Text.
 * Reine Lesefunktionen (candidates/fallbackText) sind bewusst Android-frei
 * gehaltene Datenfunktionen — die Unit-Tests prüfen Listen-Integrität ohne
 * Gerät.
 */
object DeviceIntentResolver {

    /** Aktuelles Geräte-Profil (Build.MANUFACTURER — im Emulator GENERIC). */
    fun currentProfile(manufacturer: String? = Build.MANUFACTURER): DeviceProfile =
        DeviceProfile.fromManufacturer(manufacturer)

    /** Kandidaten für Profil × Feature: OEM-Kandidaten zuerst, dann AOSP-Generika. */
    fun candidates(profile: DeviceProfile, feature: DeviceFeature): List<DeviceIntentCandidate> =
        oemCandidates(profile, feature) + genericCandidates(feature)

    // ── OEM-Kandidaten (bewährte Community-/Dokument-Komponenten; runtime-
    // resolveActivity entscheidet — falsche/routineierte Kandidaten fliegen
    // still raus, kein Crash möglich). ───────────────────────────────────

    private fun oemCandidates(profile: DeviceProfile, feature: DeviceFeature): List<DeviceIntentCandidate> =
        when (profile) {
            DeviceProfile.XIAOMI -> when (feature) {
                DeviceFeature.AUTOSTART -> listOf(
                    // Der frühere Hardcode — bleibt ERSTER Kandidat auf Xiaomi.
                    DeviceIntentCandidate(
                        "MIUI-Autostart-Verwaltung", pkg = "com.miui.securitycenter",
                        cls = "com.miui.permcenter.autostart.AutoStartManagementActivity",
                        extras = mapOf("extra_pkgname" to "\$app"),
                    ),
                    DeviceIntentCandidate(
                        "MIUI-Autostart (xiaomi.safecenter-Variante)", pkg = "com.xiaomi.safecenter",
                        cls = "com.miui.permcenter.autostart.AutoStartManagementActivity",
                    ),
                )
                DeviceFeature.BATTERY -> listOf(
                    DeviceIntentCandidate(
                        "MIUI-PowerKeeper (Hintergrund-Ausnahmen)", pkg = "com.miui.powerkeeper",
                        cls = "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                        extras = mapOf("package_name" to "\$app", "package_label" to "FlipsiBridge"),
                    ),
                )
                // Overlay/Notification laufen auf Xiaomi über die Standard-Actions.
                else -> emptyList()
            }
            DeviceProfile.SAMSUNG -> when (feature) {
                DeviceFeature.AUTOSTART, DeviceFeature.BATTERY -> listOf(
                    // One UI hat keinen klassischen Autostart-Schalter — die
                    // Battery-Seite (lox/sm-Deep-Links) ist der Samsung-Weg.
                    DeviceIntentCandidate(
                        "Samsung Gerätepflege → Akku (lox)", pkg = "com.samsung.android.lox",
                        cls = "com.samsung.android.lox.settings.BatterySettingsActivity",
                    ),
                    DeviceIntentCandidate(
                        "Samsung Gerätepflege → Akku (sm, ältere One-UI)", pkg = "com.samsung.android.sm",
                        cls = "com.samsung.android.sm.battery.ui.BatteryActivity",
                    ),
                    DeviceIntentCandidate(
                        "Samsung Gerätepflege → Akku (sm, One-UI-Variante)", pkg = "com.samsung.android.sm",
                        cls = "com.samsung.android.sm.ui.battery.BatteryActivity",
                    ),
                )
                else -> emptyList()
            }
            DeviceProfile.OPPO -> when (feature) {
                DeviceFeature.AUTOSTART -> listOf(
                    DeviceIntentCandidate(
                        "ColorOS StartupManager", pkg = "com.coloros.safecenter",
                        cls = "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                    ),
                    DeviceIntentCandidate(
                        "Oppo Safe (ältere ColorOS)", pkg = "com.oppo.safe",
                        cls = "com.oppo.safe.permission.startup.StartupAppListActivity",
                    ),
                    DeviceIntentCandidate(
                        "OnePlus Security (Autostart/ChainLaunch)", pkg = "com.oneplus.security",
                        cls = "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
                    ),
                )
                DeviceFeature.BATTERY -> listOf(
                    DeviceIntentCandidate(
                        "ColorOS PowerManager", pkg = "com.coloros.oppoguardelf",
                        cls = "com.coloros.powermanager.projection.PowerUsageModelActivity",
                    ),
                )
                else -> emptyList()
            }
            DeviceProfile.VIVO -> when (feature) {
                DeviceFeature.AUTOSTART, DeviceFeature.BATTERY -> listOf(
                    DeviceIntentCandidate(
                        "Funtouch Hintergrund-Start-Manager", pkg = "com.vivo.permissionmanager",
                        cls = "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                    ),
                    DeviceIntentCandidate(
                        "iQOO Secure Hintergrund-Start", pkg = "com.iqoo.secure",
                        cls = "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
                    ),
                )
                else -> emptyList()
            }
            DeviceProfile.HUAWEI -> when (feature) {
                DeviceFeature.AUTOSTART -> listOf(
                    DeviceIntentCandidate(
                        "EMUI Startup-Apps", pkg = "com.huawei.systemmanager",
                        cls = "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    ),
                    DeviceIntentCandidate(
                        "EMUI/Honor Schutz-Aktivitäten (Protected Apps)", pkg = "com.huawei.systemmanager",
                        cls = "com.huawei.systemmanager.optimize.process.ProtectActivity",
                    ),
                )
                DeviceFeature.BATTERY -> listOf(
                    DeviceIntentCandidate(
                        "EMUI/Honor Schutz-Aktivitäten (Protected Apps)", pkg = "com.huawei.systemmanager",
                        cls = "com.huawei.systemmanager.optimize.process.ProtectActivity",
                    ),
                )
                else -> emptyList()
            }
            // Motorola (My UX) und Nothing (Nothing OS) haben keine eigenen
            // Autostart/Batterie-Manager-Komponenten — AOSP-Generika genügen.
            DeviceProfile.MOTOROLA, DeviceProfile.NOTHING, DeviceProfile.GENERIC -> emptyList()
        }

    // ── AOSP-Generika: für JE Feature mindestens 4 Kandidaten, identisches
    // Verhalten wie die v0.12.9-Hardcodes auf AOSP/Emulator. ───────────────

    private fun genericCandidates(feature: DeviceFeature): List<DeviceIntentCandidate> = when (feature) {
        DeviceFeature.AUTOSTART -> listOf(
            DeviceIntentCandidate(
                "App-Details (Autostart liegt je OEM hier oder in der Akku-Seite)",
                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS, dataUri = "package:\$app",
            ),
            DeviceIntentCandidate(
                "App-Liste", action = Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS,
            ),
            DeviceIntentCandidate(
                "App-Verwaltung (API-21-Liste)", action = Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS,
            ),
            DeviceIntentCandidate(
                "Einstellungen (Startseite)", action = Settings.ACTION_SETTINGS,
            ),
        )
        DeviceFeature.BATTERY -> listOfNotNull(
            // PH-7/PS-A-18 (play-Edition): kein REQUEST-Dialog — die Permission
            // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS ist im play-Manifest entfernt;
            // der User schaltet in der Liste selbst um (kein Formular-Risiko).
            // GitHub-Edition behält den Systemdialog als ersten Kandidaten.
            if (BuildConfig.EDITION_PLAY) null else DeviceIntentCandidate(
                "Akku-Optimierung je App (Systemdialog)",
                action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, dataUri = "package:\$app",
            ),
            DeviceIntentCandidate(
                "Akku-Optimierung (Liste)", action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            ),
            DeviceIntentCandidate(
                "App-Details (Akku)", action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS, dataUri = "package:\$app",
            ),
            DeviceIntentCandidate(
                "Einstellungen (Startseite)", action = Settings.ACTION_SETTINGS,
            ),
        )
        DeviceFeature.OVERLAY -> listOf(
            // Gleiches Set wie der v0.12.9-Overlay-Weg (Bug-1-Klasse: mit-URI,
            // ohne-URI, App-Details) + Startseite.
            DeviceIntentCandidate(
                "Overlay je App", action = Settings.ACTION_MANAGE_OVERLAY_PERMISSION, dataUri = "package:\$app",
            ),
            DeviceIntentCandidate(
                "Overlay (Liste)", action = Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            ),
            DeviceIntentCandidate(
                "App-Details (Overlay)", action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS, dataUri = "package:\$app",
            ),
            DeviceIntentCandidate(
                "Einstellungen (Startseite)", action = Settings.ACTION_SETTINGS,
            ),
        )
        DeviceFeature.NOTIFICATION -> listOf(
            DeviceIntentCandidate(
                "Benachrichtigungen je App",
                action = Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                extras = mapOf(Settings.EXTRA_APP_PACKAGE to "\$app"),
            ),
            DeviceIntentCandidate(
                "App-Details (Benachrichtigungen)",
                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS, dataUri = "package:\$app",
            ),
            DeviceIntentCandidate(
                "App-Liste", action = Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS,
            ),
            DeviceIntentCandidate(
                "Einstellungen (Startseite)", action = Settings.ACTION_SETTINGS,
            ),
        )
    }

    // ── Fallback: informative Leit-Zeile je OEM (kein Crash, K5-Regel) ───

    /** Leit-Zeile, wo der Nutzer per Hand hingehen muss (je OEM formuliert). */
    fun fallbackText(profile: DeviceProfile, feature: DeviceFeature): String = when (feature) {
        DeviceFeature.AUTOSTART -> when (profile) {
            DeviceProfile.XIAOMI -> "Autostart nicht gefunden — bitte manuell: Sicherheits-App → Berechtigungen → Autostart → FlipsiBridge erlauben (HyperOS/MIUI)."
            DeviceProfile.SAMSUNG -> "One UI hat keinen Autostart-Schalter — bitte manuell: Einstellungen → Apps → FlipsiBridge → Akku → „Nicht optimieren“."
            DeviceProfile.OPPO -> "Autostart nicht gefunden — bitte manuell: Einstellungen → Apps → FlipsiBridge → Akkuverbrauch → „Jetzt starten“ erlauben (ColorOS)."
            DeviceProfile.VIVO -> "Autostart nicht gefunden — bitte manuell: Einstellungen → Apps → FlipsiBridge → Autostart im Hintergrund erlauben (Funtouch OS)."
            DeviceProfile.HUAWEI -> "Autostart nicht gefunden — bitte manuell: Optimizer/Phone Manager → Startup-Apps → FlipsiBridge erlauben (EMUI/Honor)."
            DeviceProfile.MOTOROLA -> "Motorola hat keinen Autostart-Schalter — bitte prüfen: Einstellungen → Apps → FlipsiBridge → Akku („Keine Einschränkungen“, My UX)."
            DeviceProfile.NOTHING -> "Nothing OS: bitte prüfen: Einstellungen → Apps → FlipsiBridge → Akku, „Unbeschränkt“."
            DeviceProfile.GENERIC -> "Autostart-Schalter nicht gefunden — bitte prüfen: Einstellungen → Apps → FlipsiBridge → Akku/Batterie."
        }
        DeviceFeature.BATTERY -> when (profile) {
            DeviceProfile.XIAOMI -> "Akku-Einstellung nicht gefunden — bitte manuell: Sicherheits-App → Akku → FlipsiBridge „Keine Einschränkungen“ + Hintergrundstart erlauben (HyperOS/MIUI)."
            DeviceProfile.SAMSUNG -> "Akku-Einstellung nicht gefunden — bitte manuell: Einstellungen → Gerätepflege/Device care → Akku → FlipsiBridge nicht optimieren (One UI)."
            DeviceProfile.OPPO -> "Akku-Einstellung nicht gefunden — bitte manuell: Einstellungen → Akku → FlipsiBridge → „Hintergrund ausführen erlauben“ (ColorOS)."
            DeviceProfile.VIVO -> "Akku-Einstellung nicht gefunden — bitte manuell: Einstellungen → Akku → Hoher Stromverbrauch im Hintergrund → FlipsiBridge erlauben (Funtouch OS)."
            DeviceProfile.HUAWEI -> "Akku-Einstellung nicht gefunden — bitte manuell: Einstellungen → Akku → App-Start → FlipsiBridge „Verwalten: aus“ (EMUI/Honor)."
            DeviceProfile.MOTOROLA -> "Akku-Einstellung nicht gefunden — bitte manuell: Einstellungen → Apps → FlipsiBridge → Akku → „Keine Einschränkungen“ (My UX)."
            DeviceProfile.NOTHING -> "Akku-Einstellung nicht gefunden — bitte manuell: Einstellungen → Apps → FlipsiBridge → Akku, „Unbeschränkt“ (Nothing OS)."
            DeviceProfile.GENERIC -> "Akku-Einstellung nicht erreichbar — bitte manuell: Einstellungen → Apps → FlipsiBridge → Akku → Nicht optimieren."
        }
        DeviceFeature.OVERLAY -> when (profile) {
            DeviceProfile.XIAOMI -> "Overlay-Einstellung nicht erreichbar — bitte manuell: Einstellungen → Apps → FlipsiBridge → Andere Berechtigungen → „Über anderen Apps einblenden“ (HyperOS/MIUI)."
            DeviceProfile.SAMSUNG -> "Overlay-Einstellung nicht erreichbar — bitte manuell: Einstellungen → Apps → FlipsiBridge → Als Overlay einblenden (One UI)."
            DeviceProfile.OPPO -> "Overlay-Einstellung nicht erreichbar — bitte manuell: Einstellungen → Apps → FlipsiBridge → „In den Vordergrund“/Display über anderen Apps (ColorOS)."
            DeviceProfile.VIVO -> "Overlay-Einstellung nicht erreichbar — bitte manuell: Einstellungen → Apps → FlipsiBridge → „Schwebende Fenster“ erlauben (Funtouch OS)."
            DeviceProfile.HUAWEI -> "Overlay-Einstellung nicht erreichbar — bitte manuell: Einstellungen → Apps → FlipsiBridge → Weitere Einstellungen → „Über anderen Apps einblenden“ (EMUI/Honor)."
            DeviceProfile.MOTOROLA -> "Overlay-Einstellung nicht erreichbar — bitte manuell: Einstellungen → Apps → FlipsiBridge → Benachrichtigungen/Overlay (My UX)."
            DeviceProfile.NOTHING -> "Overlay-Einstellung nicht erreichbar — bitte manuell: Einstellungen → Apps → FlipsiBridge → Overlay (Nothing OS)."
            DeviceProfile.GENERIC -> "Overlay-Einstellung nicht erreichbar — bitte manuell: Einstellungen → Apps → FlipsiBridge → Erweitert → Über anderen Apps einblenden."
        }
        DeviceFeature.NOTIFICATION -> when (profile) {
            DeviceProfile.XIAOMI -> "Benachrichtigungs-Einstellung nicht erreichbar — bitte manuell: Einstellungen → Apps → FlipsiBridge → Benachrichtigungen (HyperOS/MIUI)."
            DeviceProfile.SAMSUNG -> "Benachrichtigungs-Einstellung nicht erreichbar — bitte manuell: Einstellungen → Apps → FlipsiBridge → Benachrichtigungen (One UI)."
            DeviceProfile.OPPO -> "Benachrichtigungs-Einstellung nicht erreichbar — bitte manuell: Einstellungen → Apps → FlipsiBridge → Benachrichtigungen (ColorOS)."
            DeviceProfile.VIVO -> "Benachrichtigungs-Einstellung nicht erreichbar — bitte manuell: Einstellungen → Apps → FlipsiBridge → Benachrichtigungen (Funtouch OS)."
            DeviceProfile.HUAWEI -> "Benachrichtigungs-Einstellung nicht erreichbar — bitte manuell: Einstellungen → Apps → FlipsiBridge → Benachrichtigungen (EMUI/Honor)."
            DeviceProfile.MOTOROLA -> "Benachrichtigungs-Einstellung nicht erreichbar — bitte manuell: Einstellungen → Apps → FlipsiBridge → Benachrichtigungen (My UX)."
            DeviceProfile.NOTHING -> "Benachrichtigungs-Einstellung nicht erreichbar — bitte manuell: Einstellungen → Apps → FlipsiBridge → Benachrichtigungen (Nothing OS)."
            DeviceProfile.GENERIC -> "Benachrichtigungs-Einstellung nicht erreichbar — bitte manuell: Einstellungen → Apps → FlipsiBridge → Benachrichtigungen."
        }
    }

    /**
     * Startet den ersten auflösbaren Kandidaten. Liefert false, wenn keiner
     * auflöst ODER der Start fehlschlägt — der Aufrufer zeigt dann die
     * informative Leit-Zeile (onUnavailable) statt zu crashen.
     */
    fun launchFeature(
        context: Context,
        activity: Activity?,
        feature: DeviceFeature,
        profile: DeviceProfile = currentProfile(),
        onUnavailable: (String) -> Unit = {},
    ): Boolean {
        val fallback = fallbackText(profile, feature)
        if (activity == null) {
            onUnavailable(fallback)
            return false
        }
        val pm = context.packageManager
        val appPackage = context.packageName
        candidates(profile, feature).forEach { candidate ->
            val intent = candidate.toIntent(appPackage)
            val resolves = runCatching { intent.resolveActivity(pm) != null }.getOrDefault(false)
            if (resolves && runCatching { activity.startActivity(intent) }.isSuccess) return true
        }
        onUnavailable(fallback)
        return false
    }
}