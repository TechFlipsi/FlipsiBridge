package com.hermesandroid.bridge.ui.components

/**
 * UI-Datenmodell der Fähigkeiten-Zentrale (v0.12.0).
 * Die Capabilities selbst bleiben im CapabilityGate gespeichert (SSOT der Flags);
 * dieses Modell ist NUR die Anzeige-Schicht (Label/Beschreibung/Gruppierung/Invasiv-Markierung).
 */
data class CapabilityUi(
    val id: String,
    val label: String,
    val description: String,
    val invasive: Boolean = false,
)

/** Sensible Fähigkeiten — beim Master-„Alle an" mit Bestätigungsdialog. */
val INVASIVE_CAPS = setOf("location", "sms", "calls", "contacts", "microphone")

/** Alle 16 nutzersteuerbaren Fähigkeiten (Zählerbasis des Master-Schalters). */
val ALL_CAPABILITY_UIS: List<CapabilityUi> = listOf(
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

/** Vier Gruppen der Fähigkeiten-Zentrale (Reihenfolge = Anzeige-Reihenfolge). */
fun capabilityGroups(): List<Pair<String, List<CapabilityUi>>> = listOf(
    "Ansehen & Zustände" to ALL_CAPABILITY_UIS.filter { it.id in setOf("screen", "screen_capture", "notifications", "clipboard", "files", "health") },
    "Gerät bedienen" to ALL_CAPABILITY_UIS.filter { it.id in setOf("interaction", "device", "notify_reply") },
    "Schreiben & Selbstpflege" to ALL_CAPABILITY_UIS.filter { it.id in setOf("files_write", "selfupdate") },
    "Invasiv — ausdrücklich freischalten" to ALL_CAPABILITY_UIS.filter { it.invasive },
)