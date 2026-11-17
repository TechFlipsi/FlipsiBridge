package com.hermesandroid.bridge.ui.chat

/**
 * W19 (17.11.26, Sir-Befunde): reine Regeln des Modell & Denken-Sheets —
 * bewusst OHNE Compose-/Android-Abhängigkeit (JVM-getestet, gleiche Faser
 * wie ChatStatusRank/ReconnectPolicy). Zwei Sir-Anforderungen stecken hier:
 *
 * 1. FREITEXT-FELD IST AUS („das fliegt komplett raus, nur Dropdown, nichts
 *    selbst einzugeben“) — Auswahl ausschließlich aus der Server-Liste
 *    (agent.models). Ein getippter Modell-Name kann den Relay-Weg nicht
 *    mehr erreichen: Stale-Einträge aus Vorgängerversionen werden am Sendepfad
 *    endgültig gefiltert → Auto (Server entscheidet). Generik-Regel immer-on:
 *    KEINE Modell-Namen, KEINE Fallback-Listen im App-Code — die Server-Liste
 *    ist die einzige Quelle.
 * 2. GATEWAY-STAND ALS SUGGESTION — 'effort' (agent.models) schlägt die
 *    Slider-Raste vor, wenn der Nutzer noch KEINE App-Auswahl hat (Befund:
 *    Slider stand bei Neuinstallation dauerhaft auf Raste 0 „aus“, weil
 *    current/effort nie verarbeitet wurden). Die App-Auswahl GEWINNT
 *    weiterhin (setEffort schreibt App-Pref, non-null schlägt den
 *    Gateway-Fallback).
 */

/** Denken-Stufen: Anzeige (DE) + Leitungs-Wert (Gateway-Grammatik). */
val THINK_STAGES = listOf(
    "aus" to null,
    "leicht" to "low",
    "hoch" to "high",
    "max" to "max",
)

/**
 * Slider-Raste: App-Auswahl GEWINNT; ohne App-Pref ist der Gateway-Stand
 * (agent.models 'effort') die Vorschlags-Raste. Unbekannte Wörter (weder
 * App- noch Gateway-Grammatik) fallen fail-soft auf Raste 0 — kein Crash,
 * keine geratenen Stufen.
 */
fun effortStageIndex(appEffort: String?, gatewayEffort: String?): Int {
    val effective = appEffort ?: gatewayEffort ?: return 0
    return THINK_STAGES.indexOfFirst { it.second == effective }.takeIf { it >= 0 } ?: 0
}

/**
 * Sendbares Modell — NUR Werte der Server-Liste (agent.models). null/blanke
 * App-Auswahl = Auto; ein Wert ohne exaktes Listen-Matching (stale getippte
 * Prefs aus Vorgängerversionen, Katalog-Zwischenschritte) wird NICHT
 * gesendet → null = Auto = Gateway-Entscheidung bleibt.
 */
fun sendableModel(appModel: String?, serverModels: List<String>?): String? {
    if (appModel.isNullOrBlank()) return null
    return serverModels.orEmpty().firstOrNull { it == appModel }
}
