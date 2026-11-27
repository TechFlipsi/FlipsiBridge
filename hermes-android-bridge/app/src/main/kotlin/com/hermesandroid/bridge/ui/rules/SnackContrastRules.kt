package com.hermesandroid.bridge.ui.rules

import com.hermesandroid.bridge.ui.theme.ThemeMath

/**
 * W27 (K1, Design-Kontrast-Fix): das zentrale Farb-Verband des Haus-Snacks.
 *
 * Bestand (M3-Default-Verband): der Snack malte inverseSurface (#E6E0E9-hell)
 * mit dem Aktion-Label im M3-Default — der gemessene Audit-Stand 24.11.2026
 * (Release-Gate-Design-Audit) maß das Paar hell #e6e0e9 zu Ember #ed7931 mit
 * **2,20:1** — unter dem bold-Soll von 3:1 = FAIL. Beide Kandidaten des
 * Audit-Befunds sind beweisbar dasselbe Paar (Kontrast ist symmetrisch), die
 * Re-Formel (Python-Beweis-Akte, relative Luminanz) reproduziert 2,20:1 exakt.
 *
 * Fix (Brief Vorgabe): dunkles Action-Label auf der Ember-Fläche, Kandidat
 * #0e1013 (Void) — **6,70:1** (bold-Soll 3:1 mit großem Abstand erfüllt); der
 * Snack-Body trägt das etablierte Haus-OnEmber (#1A1208, „Dunkeltext auf
 * Ember-Flächen") mit **6,51:1** (Klein-Soll 4,5:1 erfüllt). Die Ember-Fläche
 * folgt der Haus-Rolle „Ember = Aktion/Ergebnis" (BridgeTheme-KDoc) — der
 * Snack IST das Ergebnis-Feedback.
 *
 * Wert-Haftung: die Töne lesen aus [ThemeMath] (EINE Int-Quelle, Spiegel der
 * FlipsiColors-Welt — die Paarung hütet der Literal-Test); der EINE
 * SnackbarHost-Mount (BridgeScreen) liest diese Konstanten — alle
 * Snack-Stellen (cron/invite/error/copy/caps-undo) tragen dieselbe Welt mit
 * einem Griff. Keine neue Dependency, build.gradle unberührt.
 * (W27-Jagd: KDoc ehrlich — die Werte leben nicht „NUR hier", die Quelle ist
 * ThemeMath; die Audit-Vorher-Anker sind test-lokal wie im Schwestertest.)
 */
object SnackContrastRules {

    /** Snack-Fläche = Ember (FlipsiColors.Ember 0xFFED7931). */
    const val CONTAINER = ThemeMath.EMBER

    /** Snack-Body/Dismiss = Haus-OnEmber (FlipsiColors.OnEmber 0xFF1A1208). */
    const val CONTENT = ThemeMath.DARK_TEXT_BASE

    /** Action-Label = Void (FlipsiColors.Void #0E1013, Brief-Kandidat). */
    const val ACTION = ThemeMath.VOID

    /** Klein-Soll für den Snack-Body (WCAG-AA Fließtext = Haus-Ziel). */
    const val SOLL_BODY = ThemeMath.CONTRAST_TARGET

    /** bold-Soll für das Action-Label (Audit-Raster). */
    const val SOLL_ACTION = 3.0

    /** Kontrast Body auf Snack-Fläche — muss ≥ [SOLL_BODY] messen. */
    fun contentRatio(): Double = ThemeMath.contrastRatio(CONTENT, CONTAINER)

    /** Kontrast Action-Label auf Snack-Fläche — muss ≥ [SOLL_ACTION] messen. */
    fun actionRatio(): Double = ThemeMath.contrastRatio(ACTION, CONTAINER)
}
