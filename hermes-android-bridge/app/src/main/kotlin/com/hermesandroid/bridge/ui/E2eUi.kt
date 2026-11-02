package com.hermesandroid.bridge.ui

/**
 * PH-3 F6 (Jäger-Fix): E2E-Sichtbarkeit — KEIN stilles Legacy-Klartext-
 * Laufen. Liefert CryptoEngine.xdhAvailable=false, zeigt Settings-Gruppe 5
 * die Warn-Zeile (E2E auf diesem Gerät fail-closed unmöglich); der
 * BridgeScreen-Header bekommt eine E2E-Kleppe, sobald Sessions aktiv sind
 * (erkennbarer Zustand statt unsichtbarer Verschlüsselung).
 */
object E2eUi {

    /** Warn-Zeile der Settings-Gruppe 5 (Text bewusst stabil für UI-Tests). */
    const val XDH_UNAVAILABLE_WARNING =
        "Verschlüsselung auf diesem Gerät nicht verfügbar"

    /** Settings-Gruppe 5: Warn-Zeile nur wenn XDH fehlt (null = alles ok). */
    fun settingsWarning(xdhAvailable: Boolean): String? =
        if (xdhAvailable) null else XDH_UNAVAILABLE_WARNING

    /** Header-Kleppe: nur bei aktiven E2E-Sessions sichtbar (null = ohne). */
    fun headerBadge(activeSessions: Int): String? =
        if (activeSessions > 0) "E2E" else null
}