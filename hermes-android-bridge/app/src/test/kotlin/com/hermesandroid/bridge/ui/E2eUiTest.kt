package com.hermesandroid.bridge.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PH-3 F6 (Jäger-Fix): E2E-Sichtbarkeit — XDH fehlt = Warn-Zeile in
 * Settings-Gruppe 5 (kein stilles Legacy-Klartext-Laufen); Header-Kleppe
 * nur bei aktiven Sessions.
 *
 * W15 (I18n): der Warn-TEXT ist nach R.string.e2e_xdh_unavailable gezogen
 * (DE/EN, auflöst live unter dem AppLanguageGate) — hier bleibt der
 * UI-Vertrag als SEMANTIK gepinnt: Warn-Zeile genau dann, wenn XDH fehlt.
 */
class E2eUiTest {

    @Test
    fun xdh_fehlt_ergibt_warnzeile() {
        assertTrue("XDH fehlt = Warn-Zeile zeigen", E2eUi.settingsWarning(xdhAvailable = false))
        assertFalse("XDH verfügbar = KEINE Warnung", E2eUi.settingsWarning(xdhAvailable = true))
    }

    @Test
    fun header_kleppe_nur_bei_aktiven_sessions() {
        assertEquals("E2E", E2eUi.headerBadge(activeSessions = 1))
        assertEquals("E2E", E2eUi.headerBadge(activeSessions = 3))
        assertNull("keine Session = keine Kleppe", E2eUi.headerBadge(activeSessions = 0))
    }
}
