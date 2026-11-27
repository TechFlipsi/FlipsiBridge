package com.hermesandroid.bridge.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Welle 13 / P12: Kontrast-Beweis der ThemeEngine — die harte Nebenregel
 * „die App kann unlesbar gar nicht malen“ als Unit-Test (QA: Kontrast-
 * Berechnungs-Tests ≥ 4,5:1 je generierter Kombination). Reine JVM-Tests:
 * ThemeMath rechnet auf ARGB-Ints ohne Android-Runtime.
 */
class ThemeEngineTest {

    private val target = ThemeMath.CONTRAST_TARGET

    // ── Basis-Mathematik ──────────────────────────────────────────────────

    @Test
    fun kontrast_schwarz_weiss_ist_21() {
        assertEquals(21.0, ThemeMath.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
    }

    @Test
    fun luminanz_weiss_ist_1_schwarz_ist_0() {
        assertEquals(1.0, ThemeMath.relativeLuminance(0xFFFFFFFF.toInt()), 0.001)
        assertEquals(0.0, ThemeMath.relativeLuminance(0xFF000000.toInt()), 0.001)
    }

    // ── Flipsi-Standard: abgeleitete Texte ≥ 4,5:1 ────────────────────────

    @Test
    fun standard_schema_erreicht_kontrastziel_je_flaeche() {
        val s = ThemeEngine.defaultScheme()
        listOf(
            s.onBackground to s.background,
            s.onBackgroundMuted to s.background,
            s.onAccent to s.accent,
            s.onBubbleOwn to s.bubbleOwn,
            s.onBubbleAgent to s.bubbleAgent,
        ).forEach { (text, surface) ->
            val ratio = ThemeMath.contrastRatio(text, surface)
            assertTrue("Kontrast $ratio < 4,5 auf $surface", ratio >= target)
        }
    }

    @Test
    fun standard_schema_trifft_die_flipsi_haustoene() {
        // Bewusst: ohne Nutzerwahl entspricht die Ableitung den Haus-Tönen
        // (dunkler Text auf Ember, heller Text auf Void/EmberDim/Panel).
        val s = ThemeEngine.defaultScheme()
        assertEquals(0xFF1A1208.toInt(), s.onAccent)      // OnEmber
        assertEquals(0xFFE8EAED.toInt(), s.onBubbleOwn)   // TextPrimary
        assertEquals(0xFFE8EAED.toInt(), s.onBubbleAgent) // TextPrimary
        assertEquals(0xFFE8EAED.toInt(), s.onBackground)  // TextPrimary
    }

    // ── Extrem-Farben: hell-auf-hell / dunkel-auf-dunkel korrigiert ──────

    @Test
    fun weisse_flaeche_bekommt_dunklen_text() {
        val text = ThemeMath.readableTextOn(0xFFFFFFFF.toInt())
        assertEquals(0xFF1A1208.toInt(), text) // Haus-Ton reicht auf Weiß
        assertTrue(ThemeMath.contrastRatio(text, 0xFFFFFFFF.toInt()) >= target)
    }

    @Test
    fun schwarze_flaeche_bekommt_hellen_text() {
        val text = ThemeMath.readableTextOn(0xFF000000.toInt())
        assertEquals(0xFFE8EAED.toInt(), text)
        assertTrue(ThemeMath.contrastRatio(text, 0xFF000000.toInt()) >= target)
    }

    @Test
    fun mittelgrau_falle_ergibt_trotzdem_kontrast() {
        // #777777: KEIN Haus-Ton erreicht 4,5:1 — die Nachschärfung muss
        // Richtung Reinstufe greifen (P12-Nebenregel, Extrem-Beweis).
        val gray = 0xFF777777.toInt()
        assertTrue("Basis-Töne müssten hier scheitern", ThemeMath.contrastRatio(gray, 0xFF1A1208.toInt()) < target)
        val text = ThemeMath.readableTextOn(gray)
        assertTrue(
            "Mittelgrau-Korrektur unter dem Ziel",
            ThemeMath.contrastRatio(text, gray) >= target,
        )
    }

    // ── Random-Sweep: JEDE Farbe → lesbar (die App kann unlesbar gar nicht
    //    malen — Beweis über die vollständige Ableitungskette) ─────────────

    @Test
    fun random_sweep_readable_text_immer_ueber_ziel() {
        val random = Random(1337L)
        repeat(20_000) {
            val surface = (0xFF shl 24) or random.nextInt(0x1000000)
            val text = ThemeMath.readableTextOn(surface)
            assertTrue(
                "Kontrast unter Ziel für #${"%06X".format(surface and 0xFFFFFF)}",
                ThemeMath.contrastRatio(text, surface) >= target,
            )
        }
    }

    @Test
    fun random_sweep_muted_text_immer_ueber_ziel() {
        val random = Random(4711L)
        repeat(20_000) {
            val surface = (0xFF shl 24) or random.nextInt(0x1000000)
            val text = ThemeMath.mutedTextOn(surface)
            assertTrue(
                "Muted-Kontrast unter Ziel für #${"%06X".format(surface and 0xFFFFFF)}",
                ThemeMath.contrastRatio(text, surface) >= target,
            )
        }
    }

    @Test
    fun random_sweep_gesamte_kombination_jeder_slot() {
        // Nutzerauswahl in ALLEN vier Slots gleichzeitig (Extrem-Kombi):
        // je Fläche der FlipsiScheme bleibt die Ableitung ≥ 4,5:1.
        val random = Random(20261111L)
        repeat(10_000) {
            fun col() = (0xFF shl 24) or random.nextInt(0x1000000)
            val s = FlipsiScheme(
                background = col(),
                accent = col(),
                bubbleOwn = col(),
                bubbleAgent = col(),
            )
            listOf(
                s.onBackground to s.background,
                s.onBackgroundMuted to s.background,
                s.onAccent to s.accent,
                s.onBubbleOwn to s.bubbleOwn,
                s.onBubbleAgent to s.bubbleAgent,
            ).forEach { (text, surface) ->
                assertTrue(
                    "Kombi-Kontrast unter Ziel",
                    ThemeMath.contrastRatio(text, surface) >= target,
                )
            }
        }
    }

    // ── Hauspaletten-Abdeckung: jede Swatch-Voreinstellung bleibt lesbar ──

    @Test
    fun swatch_kandidaten_aus_der_settings_ui_bleiben_lesbar() {
        // Die kuratierten Voreinstellungen der Darstellungs-Karte (Kopie der
        // ARGB-Werte — Änderungen dort müssen diesen Test mitnehmen).
        val all = listOf(
            0xFFED7931, 0xFF2FBF71, 0xFFD3A02F, 0xFF4C9AFF, 0xFFB57BFF,
            0xFFE5484D, 0xFF141414, 0xFFE8EAED,
            0xFF3A2A1F, 0xFF1F3A2A, 0xFF1F2A3A, 0xFF333742, 0xFF4ECDC4, 0xFFE8DCC8,
            0xFF1A1D23, 0xFF2A2F38, 0xFF243038, 0xFFECEFF4, 0xFF331F2A,
            0xFF0E1013, 0xFF10151D, 0xFF241B2F, 0xFFF3EDE3,
        ).map { it.toInt() }
        all.forEach { surface ->
            assertTrue(
                "Swatch #${"%06X".format(surface and 0xFFFFFF)} nicht lesbar ableitbar",
                ThemeMath.contrastRatio(ThemeMath.readableTextOn(surface), surface) >= target,
            )
        }
    }
}
