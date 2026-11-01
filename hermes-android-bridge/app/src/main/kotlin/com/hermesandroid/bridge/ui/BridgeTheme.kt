package com.hermesandroid.bridge.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * FlipsiBridge-Design (v0.12.0-Erweiterung des v0.11.0-Redesigns).
 *
 * Welt: Techniker-Werkzeug — dunkel, ruhig, Maschinenlicht. EIN Akzent
 * (Ember = Aktion/Kopplung), EIN Grün (verbunden/ok), EIN Rot (Störung).
 * Zustandsinformation NIE nur über Farbe — Icon/Text steht immer daneben (A11y).
 *
 * v0.12.0 (Design-QA, Kontraste selbst gemessen per WCAG-Berechnung):
 *  - SignalRedText: roter TEXT-Ton — SignalRed erreicht auf Panel nur 4,31:1
 *    (< 4,5:1 WCAG-AA). SignalRed ab jetzt NUR für Flächen/Umrandungen.
 *  - LineStrong: sichtbare Trenner — Line ist auf Panel nur 1,26:1.
 *  - OnEmber: Dunkeltext auf Ember-Flächen (vorher hartcodiert im Screen).
 */
object FlipsiColors {
    val Void = Color(0xFF0E1013)      // Grundfläche
    val Panel = Color(0xFF1A1D23)     // Karten/Panel
    val PanelElev = Color(0xFF21262E) // gehobene Fläche
    val Ember = Color(0xFFED7931)     // Aktion
    val EmberDim = Color(0xFF3A2A1F)  // Ember-Hintergrund
    val SignalGreen = Color(0xFF2FBF71)
    val SignalRed = Color(0xFFE5484D)
    val SignalRedText = Color(0xFFF0787C) // roter TEXT (6,18:1 auf Panel)
    val SignalAmber = Color(0xFFD3A02F)
    val TextPrimary = Color(0xFFE8EAED)
    val TextSecondary = Color(0xFF9BA3AE)
    val Line = Color(0xFF2A2F38)
    val LineStrong = Color(0xFF5E6A80)    // sichtbarer Trenner (3,09:1 auf Panel)
    val OnEmber = Color(0xFF1A1208)       // Dunkeltext auf Ember-Flächen
}

private val FlipsiDarkScheme = darkColorScheme(
    primary = FlipsiColors.Ember,
    onPrimary = FlipsiColors.OnEmber,
    primaryContainer = FlipsiColors.EmberDim,
    onPrimaryContainer = Color(0xFFFCE9DC),
    secondary = FlipsiColors.SignalGreen,
    onSecondary = Color(0xFF07130C),
    secondaryContainer = Color(0xFF173327),
    onSecondaryContainer = Color(0xFFBDF0D6),
    tertiary = FlipsiColors.SignalAmber,
    onTertiary = Color(0xFF241B06),
    background = FlipsiColors.Void,
    onBackground = FlipsiColors.TextPrimary,
    surface = FlipsiColors.Panel,
    onSurface = FlipsiColors.TextPrimary,
    surfaceVariant = FlipsiColors.PanelElev,
    onSurfaceVariant = FlipsiColors.TextSecondary,
    surfaceTint = FlipsiColors.Ember,
    error = FlipsiColors.SignalRed,
    onError = Color(0xFF2B0A0C),
    errorContainer = Color(0xFF3A1214),
    onErrorContainer = FlipsiColors.SignalRedText,
    outline = FlipsiColors.LineStrong,
    outlineVariant = FlipsiColors.Line,
)

/** Shape-Scale: die expressive Anhebung läuft über Karteneradien (20dp oben). */
private val FlipsiShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Anzeige-Abstände — Touch-Ziel-Untergrenze als Konvention (≥48dp, HIG ≈ 44pt). */
object FlipsiSpacing {
    val XS = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 16.dp
    val XL = 20.dp
    val XXL = 24.dp
    val RowMin = 48.dp
}

/** Einziger bewusster Display-Stil: der Kopplungs-Code (Monospace-Identität). */
object FlipsiType {
    val Code = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 22.sp,
        letterSpacing = 3.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun FlipsiTheme(content: @Composable () -> Unit) {
    // Die App ist bewusst IMMER dark (Techniker-Werkzeug, Sir-Präferenz),
    // auch bei hellem System — kein Wechsel-Feature in einer Kontroll-App.
    MaterialTheme(
        colorScheme = FlipsiDarkScheme,
        shapes = FlipsiShapes,
        content = content,
    )
}