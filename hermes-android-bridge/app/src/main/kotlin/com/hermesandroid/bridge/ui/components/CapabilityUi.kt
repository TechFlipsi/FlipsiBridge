package com.hermesandroid.bridge.ui.components

import com.hermesandroid.bridge.BuildConfig

/**
 * UI-Datenmodell der Fähigkeiten-Zentrale (v0.12.0).
 * Die Capabilities selbst bleiben im CapabilityGate gespeichert (SSOT der Flags);
 * dieses Modell ist NUR die Anzeige-Schicht (Label/Beschreibung/Gruppierung/Invasiv-Markierung).
 * PH-7 (11.11.2026, Jäger J3-F1/J4-F1/F2): die Anzeige-Schicht ist edition-aware —
 * play blendet selfupdate aus (Play-Updates-Regel) und benennt SMS ehrlich um
 * (Intent-Entwurf statt Direktversand, auch im Text).
 */
data class CapabilityUi(
    val id: String,
    val label: String,
    val description: String,
    val invasive: Boolean = false,
)

/** Sensible Fähigkeiten — beim Master-„Alle an" mit Bestätigungsdialog.
 *  N2 (11.11.2026, play): der SMS-Entwurf (Intent-Route ohne Direktversand) ist
 *  kein invasiver Pfad — play filtert sms aus der Bestätigungsliste raus. */
val INVASIVE_CAPS: Set<String> by lazy {
    if (BuildConfig.EDITION_PLAY) {
        setOf("location", "calls", "contacts", "microphone")
    } else {
        setOf("location", "sms", "calls", "contacts", "microphone")
    }
}

/** Alle nutzersteuerbaren Fähigkeiten (GitHub 16, play 15 — Zählerbasis des Master-Schalters). */
val ALL_CAPABILITY_UIS: List<CapabilityUi> by lazy {
    val base = listOf(
        CapabilityUi("screen", "Bildschirm lesen", "App/Titel/Texte vom Display ablesen"),
        CapabilityUi("screen_capture", "Screenshots & Aufnahme", "Abbild oder Video des Bildschirms (Android fragt jedes Mal)"),
        CapabilityUi("notifications", "Benachrichtigungen lesen", "Titel/Inhalt eingehender Benachrichtigungen"),
        CapabilityUi("clipboard", "Zwischenablage", "Inhalt lesen und setzen"),
        CapabilityUi("files", "Dateien lesen", "Downloads, Dokumente, Fotos, Musik durchsuchen und holen"),
        CapabilityUi("health", "Gesundheitsdaten", "Health Connect: Schritte, Puls, Schlaf u. a. — nur lesen"),
        CapabilityUi("interaction", "Gerät bedienen", "Tippen, Scrollen, Apps öffnen, Tasten drücken"),
        CapabilityUi("device", "Geräte-Hardware", "Foto, Blitz, Lautstärke, Netz-Status, Wecker/Timer"),
        CapabilityUi("notify_reply", "Antworten senden", "Direkt in Benachrichtigungen antworten (z. B. WhatsApp)"),
        CapabilityUi("files_write", "Dateien schreiben/löschen", "Dateien ablegen oder löschen (destruktiv!)", invasive = false),
        CapabilityUi("selfupdate", "App-Update", "Neue Versionen laden und den Installationsdialog zeigen"),
        CapabilityUi("location", "Standort", "GPS-Position abfragen", invasive = true),
        CapabilityUi("sms", "SMS senden", "Nachrichten in Sirs Namen verschicken", invasive = true),
        CapabilityUi("calls", "Anruf starten", "Anrufe tätigen", invasive = true),
        CapabilityUi("contacts", "Kontakte lesen", "Telefonbuch auslesen", invasive = true),
        CapabilityUi("microphone", "Mikrofon", "Umgebung aufnehmen (Ablage: Aufnahme-Datei)", invasive = true),
    )
    if (BuildConfig.EDITION_PLAY) {
        base.mapNotNull { ui ->
            when (ui.id) {
                // PS-A-11: Play-Edition aktualisiert über den Play Store — der
                // Toggle ist nicht schaltbar existent; SelfUpdateCard oben
                // zeigt den sichtbar-disabled Vertrag (DE-T3).
                "selfupdate" -> null
                // PS-A-14: ehrlicher Text — play öffnet nur den Entwurf.
                "sms" -> ui.copy(
                    label = "SMS-Entwurf öffnen",
                    description = "Öffnet die SMS-App mit vorausgefülltem Entwurf (GitHub-Edition: Direktversand)",
                    invasive = false,
                )
                else -> ui
            }
        }
    } else base
}

/** Vier Gruppen der Fähigkeiten-Zentrale (Reihenfolge = Anzeige-Reihenfolge).
 *  N1 (11.11.2026, play): der play-SMS-Entwurf ist nicht invasiv — er lebt
 *  in der Gruppe „Gerät bedienen", damit er EIN schaltbarer Ort bleibt. */
fun capabilityGroups(): List<Pair<String, List<CapabilityUi>>> = listOf(
    "Ansehen & Zustände" to ALL_CAPABILITY_UIS.filter { it.id in setOf("screen", "screen_capture", "notifications", "clipboard", "files", "health") },
    "Gerät bedienen" to ALL_CAPABILITY_UIS.filter {
        it.id in (
            if (BuildConfig.EDITION_PLAY) {
                setOf("interaction", "device", "notify_reply", "sms")
            } else {
                setOf("interaction", "device", "notify_reply")
            }
            )
    },
    "Schreiben & Selbstpflege" to ALL_CAPABILITY_UIS.filter { it.id in setOf("files_write", "selfupdate") },
    "Invasiv — ausdrücklich freischalten" to ALL_CAPABILITY_UIS.filter { it.invasive },
)