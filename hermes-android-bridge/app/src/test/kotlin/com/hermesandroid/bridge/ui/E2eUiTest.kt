package com.hermesandroid.bridge.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PH-3 F6 (Jäger-Fix): E2E-Sichtbarkeit — XDH fehlt = Warn-Zeile in
 * Settings-Gruppe 5 (kein stilles Legacy-Klartext-Laufen); Header-Kleppe
 * nur bei aktiven Sessions. Text-Konstanten sind bewusst stabil (UI-Vertrag).
 */
class E2eUiTest {

    @Test
    fun xdh_fehlt_ergibt_warnzeile_gleicher_wortlaut() {
        assertEquals(
            "Verschlüsselung auf diesem Gerät nicht verfügbar",
            E2eUi.settingsWarning(xdhAvailable = false),
        )
        assertNull("XDH verfügbar = KEINE Warnung", E2eUi.settingsWarning(xdhAvailable = true))
    }

    @Test
    fun header_kleppe_nur_bei_aktiven_sessions() {
        assertEquals("E2E", E2eUi.headerBadge(activeSessions = 1))
        assertEquals("E2E", E2eUi.headerBadge(activeSessions = 3))
        assertNull("keine Session = keine Kleppe", E2eUi.headerBadge(activeSessions = 0))
    }
}