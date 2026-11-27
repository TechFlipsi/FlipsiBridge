package com.hermesandroid.bridge.ui.rules

import com.hermesandroid.bridge.ui.theme.ThemeMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W27 (K1): Kontrast-Beweis des Haus-Snack-Verbands — Formel-Stand wie im
 * Spec-Format (relative Luminanz je ThemeMath, identisch zur WCAG-Formel,
 * die die Python-Beweis-Akte je relativer Luminanz rechnet). Die
 * Audit-Vorher-Paare sind als FAIL-Anker gepinnt: jede Rückfall-Faser
 * (wieder helle Fläche, wieder M3-Default) bricht hier zuerst.
 * (W27-Jagd: Vorher-Anker leben test-lokal wie im Schwestertest — die
 * Prod-Regel bleibt auf der Nachher-Welt.)
 */
class SnackContrastRulesTest {

    /** Audit-Vorher: Snack-Hell (M3 inverseSurface-Gerendert #E6E0E9). */
    private val beforeLight = 0xFFE6E0E9.toInt()

    /** Audit-Vorher: das Ember desselben Paars (0xFFED7931). */
    private val beforeEmber = 0xFFED7931.toInt()

    @Test
    fun `Action-Label Void auf Ember erreicht das bold-Soll mit Abstand`() {
        val r = SnackContrastRules.actionRatio()
        assertTrue("Action-Kontrast $r < 3 (bold-Soll)", r >= SnackContrastRules.SOLL_ACTION)
        // Formel-Parität zur Python-Akte: 6,70:1 (K1_NACHHER_Aktion)
        assertEquals(6.70, r, 0.05)
    }

    @Test
    fun `Snack-Body OnEmber auf Ember erreicht das Klein-Soll`() {
        val r = SnackContrastRules.contentRatio()
        assertTrue("Body-Kontrast $r < 4,5 (Klein-Soll)", r >= SnackContrastRules.SOLL_BODY)
        // Formel-Parität zur Python-Akte: 6,51:1 (K1_NACHHER_Body)
        assertEquals(6.51, r, 0.05)
    }

    @Test
    fun `Auch das Action-Label liegt über dem Klein-Soll (Sicherheitsspiel)`() {
        assertTrue(
            "Action-Kontrast unterschreitet sogar das Klein-Soll",
            SnackContrastRules.actionRatio() >= SnackContrastRules.SOLL_BODY,
        )
    }

    @Test
    fun `Audit-Vorher-Paar hell zu Ember bleibt FAIL-Anker (2,20 zu 1)`() {
        val r = ThemeMath.contrastRatio(beforeLight, beforeEmber)
        // gemessener Audit-Stand 24.11.2026: 2,20:1 — unter dem bold-Soll.
        assertEquals(2.20, r, 0.05)
        assertTrue(
            "Vorher-Paar rechnet plötzlich PASS — die Audit-Welt hat sich verschoben",
            r < SnackContrastRules.SOLL_ACTION,
        )
    }

    @Test
    fun `Wert-Verband trägt die Haus-Farben exakt (Ember, OnEmber, Void)`() {
        assertEquals(0xFFED7931.toInt(), SnackContrastRules.CONTAINER)
        assertEquals(0xFF1A1208.toInt(), SnackContrastRules.CONTENT)
        assertEquals(0xFF0E1013.toInt(), SnackContrastRules.ACTION)
    }

    @Test
    fun `Soll-Welt liest das Haus-Ziel (WCAG-AA Fließtext 4,5)`() {
        assertEquals(4.5, SnackContrastRules.SOLL_BODY, 0.0)
    }
}
