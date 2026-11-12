package com.hermesandroid.bridge.ui

/**
 * PH-3 F6 (Jäger-Fix): E2E-Sichtbarkeit — KEIN stilles Legacy-Klartext-
 * Laufen. Liefert CryptoEngine.xdhAvailable=false, zeigt Settings-Gruppe 5
 * die Warn-Zeile (E2E auf diesem Gerät fail-closed unmöglich); der
 * BridgeScreen-Header bekommt eine E2E-Kleppe, sobald Sessions aktiv sind
 * (erkennbarer Zustand statt unsichtbarer Verschlüsselung).
 *
 * W15 (I18n): Der Warn-TEXT liegt jetzt in R.string.e2e_xdh_unavailable
 * (DE/EN, auflöst live unter dem AppLanguageGate) — die frühere Konstante
 * XDH_UNAVAILABLE_WARNING ist damit raus (kein UI-Test pinnt sie).
 */
object E2eUi {

    /**
     * Settings-Gruppe 5: Warn-Zeile genau dann, wenn XDH fehlt (false = alles
     * ok). Der TEXT selbst liegt in R.string.e2e_xdh_unavailable (W15, DE/EN).
     */
    fun settingsWarning(xdhAvailable: Boolean): Boolean = !xdhAvailable

    /** Header-Kleppe: nur bei aktiven E2E-Sessions sichtbar (null = ohne). */
    fun headerBadge(activeSessions: Int): String? =
        if (activeSessions > 0) "E2E" else null
}