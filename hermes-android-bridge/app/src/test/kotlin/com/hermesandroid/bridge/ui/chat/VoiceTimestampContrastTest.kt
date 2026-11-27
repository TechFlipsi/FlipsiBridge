package com.hermesandroid.bridge.ui.chat

import com.hermesandroid.bridge.ui.theme.ThemeMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W27 (K2): Kontrast-Beweis des Zeitstempel-Verbands — Formel-Stand
 * (relative Luminanz je ThemeMath, Parität zur Python-Akte
 * scratch/w27-evidence/w27_kontrast.py).
 *
 * Gepinnt:
 * 1. der Audit-Vorher-Fail (#444c5c auf #0e1013 = 2,21:1 < 4,5) — die
 *    dünne Grau-Messwelt darf nie wieder als Zeitstempel-Welt durchlaufen;
 * 2. die Bubble-Zeitstempel in TextSecondary (#9BA3AE, Brief-Kandidat)
 *    ≥ 4,5 auf ALLEN drei Bubble-/Hintergrund-Flächen;
 * 3. die Voice-Zeitstempel im VOLLEN Hauston (ohne 0,7-Alpha-Faser) —
 *    die Abdimm-Variante ist hier als FAIL-Anker rechnerisch belegt.
 */
class VoiceTimestampContrastTest {

    private val SOLL = 4.5

    @Test
    fun `Audit-Vorher-Paar dünnGrau auf Void bleibt FAIL-Anker (2,21 zu 1)`() {
        val r = ThemeMath.contrastRatio(0xFF444C5C.toInt(), 0xFF0E1013.toInt())
        assertEquals(2.21, r, 0.05)
        assertTrue("Vorher-Paar rechnet plötzlich PASS — Audit-Welt verschoben", r < SOLL)
    }

    @Test
    fun `Bubble-Zeitstempel TextSecondary erreicht 4,5 auf Void, Panel und EmberDim`() {
        val time = 0xFF9BA3AE.toInt() // FlipsiColors.TextSecondary (Brief-Kandidat)
        val onVoid = ThemeMath.contrastRatio(time, 0xFF0E1013.toInt())
        val onPanel = ThemeMath.contrastRatio(time, 0xFF1A1D23.toInt())
        val onEmberDim = ThemeMath.contrastRatio(time, 0xFF3A2A1F.toInt())
        assertTrue("Void: $onVoid < 4,5", onVoid >= SOLL)
        assertTrue("Panel: $onPanel < 4,5", onPanel >= SOLL)
        assertTrue("EmberDim: $onEmberDim < 4,5", onEmberDim >= SOLL)
        // Formel-Parität zur Python-Akte (K2_NACHHER_Bubble/Panel/EmberDim):
        assertEquals(7.48, onVoid, 0.05)
        assertEquals(6.63, onPanel, 0.05)
        assertEquals(5.39, onEmberDim, 0.05)
    }

    @Test
    fun `Voice-Zeitstempel im vollen Hauston übertrifft die 0,7-Abdimm-Faser`() {
        // Agenten-Bubble (Panel): TextPrimary voll vs. die 0,7-Faser
        val full = ThemeMath.contrastRatio(0xFFE8EAED.toInt(), 0xFF1A1D23.toInt())
        val dim = ThemeMath.contrastRatio(0xFFAAACB0.toInt(), 0xFF1A1D23.toInt())
        assertTrue(full >= SOLL)
        assertTrue("Voll-Ton $full soll die 0,7-Faser ($dim) klar überbieten", dim < full)
        // Formel-Parität: 14,01:1 voll (K2_NACHHER_VoiceAgent_Zeit)
        assertEquals(14.01, full, 0.05)
        // Eigene Bubble (EmberDim): Warm-Variante voll vs. 0,7-Faser
        val fullOwn = ThemeMath.contrastRatio(0xFFFCE9DC.toInt(), 0xFF3A2A1F.toInt())
        val dimOwn = ThemeMath.contrastRatio(0xFFC2B0A3.toInt(), 0xFF3A2A1F.toInt())
        assertTrue(fullOwn >= SOLL)
        assertTrue("Eigene-Bubble-Vollton $fullOwn überbietet die Faser ($dimOwn)", dimOwn < fullOwn)
        assertEquals(11.65, fullOwn, 0.05)
    }
}
