package com.hermesandroid.bridge.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.hermesandroid.bridge.ui.FlipsiColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Welle 13 / P12 (Color-Customizer, Sir-Direktive 11.11.2026) — ThemeEngine.
 *
 * Nutzer-Farben persistiert (SharedPreferences „hermes_bridge_prefs“, ARGB
 * als Int): Akzent, Bubble-eigen, Bubble-Agent, Hintergrund. Default =
 * FlipsiColors (Ember/EmberDim/Panel/Void) — die App sieht ohne Nutzerwahl
 * unverändert aus.
 *
 * HARTE NEBENREGEL („die App kann unlesbar gar nicht malen“): Textfarben
 * werden NIE mitgewählt, sondern ALGORITHMSCH abgeleitet — je Fläche wird
 * die relative Luminanz (WCAG) berechnet, der Kontrast gegen den besseren
 * Haus-Textton geprüft und bei Unterschreitung von 4,5:1 automatisch Richtung
 * Schwarz/Weiß nachgeschärft. Reines Schwarz/Weiß erreicht auf JEDER Fläche
 * mindestens ~4,58:1 — die Ableitung terminiert also garantiert über dem
 * Ziel (Beweis: Unit-Tests, inkl. Extrem-Farben und Random-Sweep).
 *
 * [FlipsiScheme] ist das zentrale Theme-Objekt, das die Settings-Screens
 * konsumieren (Live-Preview-Mini-Chat im Abschnitt „Darstellung“ zeigt die
 * Kombination VOR dem Speichern). KEINE neuen Manifest-Berechtigungen, keine
 * privaten Adressen — nur Farb-Ints und Prefs.
 */
object ThemeMath {

    /** Kontrast-Ziel für Fließtext (WCAG-AA, P12-Nebenregel). */
    const val CONTRAST_TARGET = 4.5

    // Haus-Töne als Kandidaten: dunkler Text = FlipsiColors.OnEmber,
    // heller Text = FlipsiColors.TextPrimary. Beide als Basis — die Ableitung
    // wählt den kontraststärkeren; nur wenn KEINER 4,5:1 erreicht, wird
    // Richtung Reinstufe (Schwarz/Weiß) nachgeschärft.
    const val DARK_TEXT_BASE = 0xFF1A1208.toInt()
    const val LIGHT_TEXT_BASE = 0xFFE8EAED.toInt()
    private const val PURE_BLACK = 0xFF000000.toInt()
    private const val PURE_WHITE = 0xFFFFFFFF.toInt()

    /** WCAG-Kanalfaktor: sRGB → linear (c/255 in [0,1]). */
    private fun linearChannel(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    /** Relative Luminanz (WCAG, 0…1) eines ARGB-Ints (Alphakanal ignoriert). */
    fun relativeLuminance(argb: Int): Double {
        val r = linearChannel((argb shr 16) and 0xFF)
        val g = linearChannel((argb shr 8) and 0xFF)
        val b = linearChannel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** WCAG-Kontrastverhältnis zweier ARGB-Ints (1…21). */
    fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * LESBARE Textfarbe auf beliebiger Fläche (die harte Nebenregel):
     * 1. Besserer Haus-Ton (dunkel/hell), wenn er ≥ 4,5:1 erreicht.
     * 2. Sonst Schärfung Richtung Reinstufe — auf Schwarz/Weiß liegt das
     *    Maximum beider Kontraste IMMER ≥ ~4,58:1, die Suche endet sicher.
     * Ergebnis: Kontrast ≥ 4,5:1 für JEDE Eingabe — hell-auf-hell und
     * schwarz-auf-schwarz kann die App konstruktiv nicht malen.
     */
    fun readableTextOn(surface: Int): Int {
        val dark = contrastRatio(surface, DARK_TEXT_BASE)
        val light = contrastRatio(surface, LIGHT_TEXT_BASE)
        if (dark >= light && dark >= CONTRAST_TARGET) return DARK_TEXT_BASE
        if (light >= dark && light >= CONTRAST_TARGET) return LIGHT_TEXT_BASE
        // Mittelgrau-Falle: keiner der Haus-Töne reicht. Richtung NACH DER
        // REINEN ENDSTUFE wählen (Sirs Kontrast-Garantie): mindestens eine der
        // Reinstufen liegt für jede opake Fläche bei ≥ 4,5:1 (Lückenbeweis:
        // Weiß scheitert erst ab L>0,183, Schwarz erst unter L=0,175 — Band
        // leer). Hauston-Vergleich ist hier IRREFÜHREND, weil der bessere
        // Hauston NICHT die besser nachschärfbare Seite markiert.
        val whitePure = contrastRatio(surface, PURE_WHITE)
        val blackPure = contrastRatio(surface, PURE_BLACK)
        // Schärfpfad: vom Haus-Ton der passenden Seite zur Reinstufe.
        val (fromAnchor, toPure) = if (whitePure >= CONTRAST_TARGET)
            LIGHT_TEXT_BASE to PURE_WHITE
        else
            DARK_TEXT_BASE to PURE_BLACK
        var step = 0
        var best = toPure
        var bestRatio = if (whitePure >= CONTRAST_TARGET) whitePure else blackPure
        while (step < 20) {
            step++
            val candidate = lerp(fromAnchor, toPure, step / 20f)
            val ratio = contrastRatio(surface, candidate)
            if (ratio > bestRatio) {
                best = candidate
                bestRatio = ratio
            }
            if (ratio >= CONTRAST_TARGET) return candidate
        }
        // Guarantee-Netz: Reinstufe selbst messen und zurückgeben.
        return if (contrastRatio(surface, toPure) >= CONTRAST_TARGET) best
        else if (whitePure >= blackPure) PURE_WHITE else PURE_BLACK
    }

    /**
     * Sekundär-Text auf einer Fläche: Primär-Ableitung um 22 % Richtung
     * Fläche entsättigen; bleibt der Kontrast darunter, gewinnt die volle
     * Primär-Ableitung (Sekundärtext darf nie unter 4,5:1 fallen).
     */
    fun mutedTextOn(surface: Int): Int {
        val text = readableTextOn(surface)
        val muted = lerp(text, surface, 0.22f)
        return if (contrastRatio(surface, muted) >= CONTRAST_TARGET) muted else text
    }

    /** Kanalweise ARGB-Interpolation (t=0 → from, t=1 → to), Alpha voll. */
    private fun lerp(from: Int, to: Int, t: Float): Int {
        fun ch(f: Int, o: Int): Int = (f + ((o - f) * t).toInt()).coerceIn(0, 255)
        return (0xFF shl 24) or
            (ch((from shr 16) and 0xFF, (to shr 16) and 0xFF) shl 16) or
            (ch((from shr 8) and 0xFF, (to shr 8) and 0xFF) shl 8) or
            ch(from and 0xFF, to and 0xFF)
    }
}

/**
 * Zentrales Theme-Objekt (P12): Nutzer-Flächen + ALLE algorithmisch
 * abgeleiteten Textfarben. Int-basiert (ARGB), damit die Ableitungs-Mathematik
 * ohne Android-Runtime unit-testbar bleibt; Compose-Farben über die
 * *C-Erweiterungen unten.
 */
data class FlipsiScheme(
    val background: Int,
    val accent: Int,
    val bubbleOwn: Int,
    val bubbleAgent: Int,
) {
    /** Fließtext auf dem Hintergrund — automatisch dunkel oder hell. */
    val onBackground: Int get() = ThemeMath.readableTextOn(background)

    /** Sekundärtext auf dem Hintergrund (nie unter 4,5:1). */
    val onBackgroundMuted: Int get() = ThemeMath.mutedTextOn(background)

    /** Text auf der Akzentfläche (z. B. Send-Kreis, aktiver Chip). */
    val onAccent: Int get() = ThemeMath.readableTextOn(accent)

    /** Text in der eigenen Bubble. */
    val onBubbleOwn: Int get() = ThemeMath.readableTextOn(bubbleOwn)

    /** Text in der Agenten-Bubble. */
    val onBubbleAgent: Int get() = ThemeMath.readableTextOn(bubbleAgent)
}

/** Compose-Brücke: FlipsiScheme-Felder als [Color] (ARGB → Compose). */
val FlipsiScheme.backgroundC: Color get() = Color(background)
val FlipsiScheme.accentC: Color get() = Color(accent)
val FlipsiScheme.bubbleOwnC: Color get() = Color(bubbleOwn)
val FlipsiScheme.bubbleAgentC: Color get() = Color(bubbleAgent)
val FlipsiScheme.onBackgroundC: Color get() = Color(onBackground)
val FlipsiScheme.onBackgroundMutedC: Color get() = Color(onBackgroundMuted)
val FlipsiScheme.onAccentC: Color get() = Color(onAccent)
val FlipsiScheme.onBubbleOwnC: Color get() = Color(onBubbleOwn)
val FlipsiScheme.onBubbleAgentC: Color get() = Color(onBubbleAgent)

/**
 * Persistenz + Zustand des Nutzer-Themes. Gleiche Prefs-Instrumentierung wie
 * CapabilityGate/AppLanguage („hermes_bridge_prefs“, init() idempotent —
 * hier zusätzlich mit [ensureInit]-Absicherung direkt im Compose-Pfad, damit
 * die Settings auch ohne Activity-Vorab-Init nie auf Defaults+Leerlauf
 * stehen). Änderungen drücken über [scheme] (StateFlow) sofort in die UI.
 */
object ThemeEngine {

    private const val PREFS_NAME = "hermes_bridge_prefs"
    private const val KEY_ACCENT = "theme_accent"
    private const val KEY_BUBBLE_OWN = "theme_bubble_own"
    private const val KEY_BUBBLE_AGENT = "theme_bubble_agent"
    private const val KEY_BACKGROUND = "theme_background"

    /** Flipsi-Standard (= FlipsiColors, P11-Bubble-Spec: EmberDim/Panel). */
    fun defaultScheme(): FlipsiScheme = FlipsiScheme(
        background = 0xFF0E1013.toInt(), // FlipsiColors.Void
        accent = 0xFFED7931.toInt(),     // FlipsiColors.Ember
        bubbleOwn = 0xFF3A2A1F.toInt(),  // FlipsiColors.EmberDim (P11: eigene Bubble)
        bubbleAgent = 0xFF1A1D23.toInt(), // FlipsiColors.Panel (P11: Agenten-Bubble)
    )

    private var appContext: Context? = null
    private val _scheme = MutableStateFlow(defaultScheme())
    val scheme: StateFlow<FlipsiScheme> = _scheme.asStateFlow()

    /** Idempotentes Prefs-Lesen (von der Activity/Compose vor Nutzung). */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val prefs = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrNull() ?: return
        _scheme.value = FlipsiScheme(
            background = prefs.getInt(KEY_BACKGROUND, defaultScheme().background),
            accent = prefs.getInt(KEY_ACCENT, defaultScheme().accent),
            bubbleOwn = prefs.getInt(KEY_BUBBLE_OWN, defaultScheme().bubbleOwn),
            bubbleAgent = prefs.getInt(KEY_BUBBLE_AGENT, defaultScheme().bubbleAgent),
        )
    }

    /** Compose-Absicherung: noch nicht initialisiert → hier nachholen. */
    fun ensureInit(context: Context) {
        if (appContext == null) init(context)
    }

    private fun persist(scheme: FlipsiScheme) {
        val prefs = runCatching {
            appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrNull() ?: return
        runCatching {
            prefs.edit()
                .putInt(KEY_ACCENT, scheme.accent)
                .putInt(KEY_BUBBLE_OWN, scheme.bubbleOwn)
                .putInt(KEY_BUBBLE_AGENT, scheme.bubbleAgent)
                .putInt(KEY_BACKGROUND, scheme.background)
                .apply()
        }
    }

    private fun update(transform: (FlipsiScheme) -> FlipsiScheme) {
        val next = transform(_scheme.value)
        _scheme.value = next
        persist(next)
    }

    /** Akzentfarbe setzen (ARGB-Int, z. B. aus dem Swatch-Grid). */
    fun setAccent(argb: Int) = update { it.copy(accent = argb) }

    /** Farbe der eigenen Chat-Bubble setzen. */
    fun setBubbleOwn(argb: Int) = update { it.copy(bubbleOwn = argb) }

    /** Farbe der Agenten-Bubble setzen. */
    fun setBubbleAgent(argb: Int) = update { it.copy(bubbleAgent = argb) }

    /** Hintergrund-Ton setzen. */
    fun setBackground(argb: Int) = update { it.copy(background = argb) }

    /** Reset auf Flipsi-Standard (Settings → Darstellung → Reset). */
    fun reset() {
        _scheme.value = defaultScheme()
        val prefs = runCatching {
            appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrNull() ?: return
        runCatching {
            prefs.edit()
                .remove(KEY_ACCENT)
                .remove(KEY_BUBBLE_OWN)
                .remove(KEY_BUBBLE_AGENT)
                .remove(KEY_BACKGROUND)
                .apply()
        }
    }
}

/**
 * Compose-Zugriff für Settings-Screens: garantiert initialisiert und als
 * State — jede set*-Änderung (Swatch-Tap) re-rendert die Live-Preview sofort.
 */
@Composable
fun rememberFlipsiScheme(): FlipsiScheme {
    val context = androidx.compose.ui.platform.LocalContext.current
    ThemeEngine.ensureInit(context)
    val scheme by ThemeEngine.scheme.collectAsState()
    return scheme
}
