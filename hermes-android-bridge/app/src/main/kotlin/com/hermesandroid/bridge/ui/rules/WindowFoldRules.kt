package com.hermesandroid.bridge.ui.rules

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * W24 (23.11.2026, 12-Jäger-Konsens: „Leg einfach los — ordentliche
 * Tablet-Unterstützung"): die pure Falt-Logik der Tablet-Unterstützung —
 * windowWidthSizeClass × windowHeightSizeClass → EIN gemeinsamer Falt-Verband.
 * Muster der Rules-Fasern (DashboardRules/CronScreenRules): reine Logik OHNE
 * Android-Framework-Zwang — die Material-3-Klassen sind value-classes auf
 * einem stabilen Int-Kern, die Verband-Matrix damit JVM-testbar OHNE
 * Robolectric (WindowFoldRulesTest, verifiziert je Gradle am Stand-PC).
 *
 * Verband-Contract (Drill-berichtigte Fassung, 24.11.2026):
 *  - useRail = NUR je MEDIUM-Width (Tablet in Medium-Verband + Handy-Landschaft,
 *    dessen lange Seite ALS Breite Medium ist). Handy-Portrait je Höhe
 *    (Compact-Width) bleibt Bottom-Bar — der Drill am pixel_4 (393x829dp =
 *    COMPACT×MEDIUM) bewies, dass die frühere ODER-Klausel (Compact×Medium-Höhe)
 *    das Portrait-Handy einfasste. EXPANDED bekommt den Rail-Merker BEWUSST
 *    NICHT: dort trägt useTwoPane die Fläche, die Nav-Behandlung entscheidet
 *    der Rail-Konsum (Lane-B-Hook).
 *  - useTwoPane = NUR je EXPANDED-Width (Height faltet NICHT mit — die
 *    Zwei-Spalt-Fläche ist eine Breiten-Frage, nicht eine Höhen-Frage).
 *  - je Route: Chat twoPane je expanded (die EINE TwoPane-Route), Call BEWUSST
 *    Vollband-Vollbild auf JEDEM Verband (kein Falt-Sprung im laufenden
 *    Anruf), Settings/Cron einspaltig mit Content-Cap je expanded.
 *
 * KEIN remember-Konstrukt, KEINE LocalConfiguration-Ableitung (T1/T7-Befund):
 * die Klasse liest die MainActivity am setContent-Kontext über die
 * Activity-API (calculateWindowSizeClass) und wird via
 * [LocalWindowSizeClass] weitergereicht — Configuration-Sprünge (Drehen,
 * Split-Screen) = Activity-Recreate → frische Klasse, kein Stale-Fenster.
 */
object WindowFoldRules {

    /**
     * Content-Cap für die einspaltigen Routen (Settings/Cron) je EXPANDED:
     * Beweis-Einheit der Brief-Bandbreite „600–720dp" — 640 = Band-Mitte
     * (Lesbarkeit der Zeilen statt maximale Breite). Handy/expanded-frei
     * bleibt die Breite offen (null).
     */
    const val EXPANDED_CONTENT_CAP_DP: Int = 640

    /**
     * Der Falt-Verband: EIN Werte-Verbund je Fenster-Situation — Rail-Merker,
     * Zwei-Spalt-Fähigkeit und die Content-Kappe. Die Route-Faltung (chat/
     * call/settings/cron) hängt an diesen Resolvers, NICHT an eigener
     * wenn-Kaskade je Screen (EINE Faser, keine zweiten Verbands-Richter).
     */
    data class Fold(
        /** Navigation auf der Schiene statt Bottom-Bar (Medium/Landschaft-Handy). */
        val useRail: Boolean,
        /** Zwei-Spalt-Fähigkeit — NUR je EXPANDED-Width (Height faltet nicht mit). */
        val useTwoPane: Boolean,
        /** Content-Cap je expanded (Settings/Cron); null = offene Breite. */
        val expandedContentCapDp: Int?,
    )

    /** Die Fenster-Situation → der Falt-Verband (reine Funktion, je Kante getestet). */
    fun compose(
        width: WindowWidthSizeClass,
        height: WindowHeightSizeClass,
    ): Fold {
        // Verband-Drill-Beweis (24.11.2026, Table-Drill am Pixel-C-AVD + pixel_4-Serie):
        // Rail NUR je MEDIUM-Width. Die frühere Zusatz-Klausel (COMPACT-Width +
        // MEDIUM-Height) beschreibt das HOCHFORMAT-Handy (schmale Breite, hohe
        // Höhe — 393x829dp am pixel_4 = COMPACT×MEDIUM) und fraß die Bottom-Bar
        // am Portrait-Handy weg. Das echte Handy-Landschaft hat die lange Seite
        // ALS Breite = MEDIUM-Width und fällt über dieselbe Klausel (829dp → Rail).
        val useRail = width == WindowWidthSizeClass.Medium
        val useTwoPane = width == WindowWidthSizeClass.Expanded
        return Fold(
            useRail = useRail,
            useTwoPane = useTwoPane,
            expandedContentCapDp = if (useTwoPane) EXPANDED_CONTENT_CAP_DP else null,
        )
    }

    /** Chat-Faltung: TwoPane NUR je expanded-Width — sonst einspaltig wie am Handy. */
    fun chatTwoPane(fold: Fold): Boolean = fold.useTwoPane

    /**
     * W28 (Lane B, Inset-SSOT): sitzt die untere Tab-Leiste ÜBER der Gesture-
     * Säule? NUR der kompakte Portrait-Bestand (kein Rail, kein TwoPane) —
     * dieselbe Regel wie am Scaffold-Wrap. Dort trägt die Leiste die System-
     * Säule intern (M3-NavigationBar-Default-Insets) und der Screen darf die
     * Säule NICHT ZUSÄTZLICH in sein contentPadding rechnen (doppelte Boden-
     * Spur). Je Rail-Verband (medium/Handy-Landschaft) und je EXPANDED lebt
     * die Säule am SCREEN (48 dp + WindowInsets.navigationBars). null
     * (Preview-/alt-Faser) fällt fail-soft wie am Wrap: Leiste angenommen.
     */
    fun bottomBarHost(fold: Fold?): Boolean =
        fold?.let { !(it.useRail || it.useTwoPane) } ?: true

    /**
     * W28 (Lane F, Reviewer-B-Auflage P2): BODEN der Route-Flächen — EINE
     * SSOT-Formel je Zustand, exakt die Lane-B-Konsum-Fassung vom Dashboard
     * (DashboardScreen-Konsum je 4dbbb2c) statt der 3 Kopien + 4 fehlenden
     * Stellen:
     *  - Leiste sitzt ÜBER der Gesture-Säule (kompaktes Portrait): die Leiste
     *    trägt die Säule SELBST (M3-Default-Insets) und der Scaffold-Nachlass
     *    ist reine Bar-Geometrie (contentWindowInsets = 0, W28 Lane B) — der
     *    Screen rechnet NUR den Haus-Touch-Raum: 48 dp (K4-Bestand S1
     *    „Screen-Ende knapp“).
     *  - OHNE Leistenslot (Rail/TwoPane, Handy-Landschaft — und je kompakt-
     *    Portrait der leere Bar-Slot bei offener Chat-Conversation): das
     *    Scaffold hebt den Boden NICHT — der Screen trägt die Gesture-Säule
     *    und den Haus-Touch-Raum: 48 dp + navigationBars (K4: „Karten enden
     *    nie hart an der Gesture-Navigationssäule“ = play-36 Rail-Boden).
     * null (Preview-/alt-Faser) fällt fail-soft wie am Wrap: Leiste
     * angenommen → 48 dp. Der Compose-Wickel liest die navigationBars je
     * Composition und delegiert an den reinen Kern [bottomBarFloorDp]
     * (JVM-testbar, kein Robolectric-Zwang — gleiche Faser wie bottomBarHost).
     */
    @Composable
    fun bottomBarFloorPadding(fold: Fold?): Dp =
        bottomBarFloorDp(fold, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())

    /**
     * Reiner Boden-Kern (KEIN Compose-Runtime-Zwang): Formel IDENTISCH zum
     * Lane-B-Konsum der 3 Kopiestellen — je github-34-Verband (Decor-fits,
     * Insets ≡ 0 dp) liefern BEIDE Zweige 48 dp, das 0-dp-Delta ist damit
     * beweisbar (WindowFoldRulesTest, verifiziert je Gradle am Stand-PC).
     */
    fun bottomBarFloorDp(fold: Fold?, navigationBarsBottom: Dp): Dp =
        if (bottomBarHost(fold)) {
            48.dp
        } else {
            48.dp + navigationBarsBottom
        }

    /**
     * W28 (Lane G, Jäger-F1 (HOCH)): RAIL-KOFFER unter der TopBar — der
     * Node-Offset des NavigationRail. Das Rail lebt als eigener Leistenslot
     * LINKS im Scaffold-Nachlauf und trägt seine System-Säulen SELBST
     * (M3-Default-Insets: Status oben + Gesture-Säule unten —
     * NavigationRailDefaults.windowInsets = systemBars Vertical+Start,
     * BOM 2024.09.03). Das Scaffold-Nachlass-Oben enthält die Status-Säule
     * SCHON (die TopAppBar wächst um sie — TopAppBarDefaults.windowInsets =
     * systemBars Horizontal+Top, gleiche Fibel). Der ZUSATZ-Offset des
     * Rail-Nodes ist daher reine TopBar-Geometrie MINUS Status-Säule:
     *   railTop = scaffoldTopBarPadding − statusBarTop (fail-soft ≥ 0 dp)
     * → die Rail-Items sitzen EXAKT auf der Linie des Screen-Contents
     * (beide = TopBar-Boden; github-34: Status ≡ 0 → volle TopBar-Geometrie,
     * play-36: das Status-Delta hebt sich auf — KEIN Doppel der Säule).
     * Bug-Vorher: Rail-Node an (0,0) → Items HINTER der TopAppBar, der
     * oberste Rail-Eintrag war unerreichbar (w26_tv_dash.png: 4/5 Tabs).
     */
    fun railTopDp(scaffoldTopBarPadding: Dp, statusBarTop: Dp): Dp =
        (scaffoldTopBarPadding - statusBarTop).coerceAtLeast(0.dp)

    /** Compose-Wickel: liest die Status-Säule je Composition (Rail-Konsum-Stelle). */
    @Composable
    fun railTopPadding(scaffoldTopBarPadding: Dp): Dp =
        railTopDp(
            scaffoldTopBarPadding,
            WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
        )

    /**
     * W28 (Lane G, Jäger-F3 (MITTEL)): SUCHZUSTAND-Boden — im Chat-Suchmodus
     * lebt der Composer NICHT (W16-Verband: „die Fläche gehört der Suche“)
     * und die ChatInputBar war der EINE navigationBars-Träger der Conversation
     * (W4-01-Contract §343). Die Treffer-Fläche übernimmt den Boden:
     * 48 dp Haus-Touch-Raum + navigationBars. BEWUSST fold-UNABHÄNGIG: der
     * Leistenslot der Conversation ist GATED (die Tab-Leiste lebt nicht,
     * solange die Conversation offen ist — W16 Fixliste 15), die
     * [bottomBarFloorDp]-Faser liest dagegen die FALT-Klasse — hier geht nur
     * die ohne-Slot-Formel direkt. github-34: navigationBars ≡ 0 → 48 dp
     * (0-dp-Delta am Inset-Anteil).
     */
    fun searchFloorDp(navigationBarsBottom: Dp): Dp = 48.dp + navigationBarsBottom

    /** Compose-Wickel: liest die Gesture-Säule je Composition (Suchmodus). */
    @Composable
    fun searchFloorPadding(): Dp =
        searchFloorDp(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())

    /**
     * Call-Faltung: BEWUSST Vollband-Vollbild auf JEDEM Verband — der laufende
     * Anruf darf NIE einen Falt-Sprung mitten im Gespräch erleben; selbst auf
     * EXPANDED bleibt die Call-Oberfläche einspaltig-voll.
     */
    fun callTwoPane(fold: Fold): Boolean = false

    /** Settings-Faltung: einspaltig — Content-Cap NUR je expanded, sonst offene Breite. */
    fun settingsContentCapDp(fold: Fold): Int? = fold.expandedContentCapDp

    /** Cron-Faltung: einspaltig — gleiche Kappe wie Settings (EINE Breiten-Fibel). */
    fun cronContentCapDp(fold: Fold): Int? = fold.expandedContentCapDp
}

/**
 * W24: die WindowSizeClass wird EINMAL am setContent-Kontext über die
 * Activity-API gelesen (MainActivity: calculateWindowSizeClass — die offizielle
 * Material-3-Route) und via CompositionLocal in den Compose-Baum weitergereicht.
 * KEIN remember je Screen, KEINE LocalConfiguration-Ableitung (T1/T7-Befund —
 * remember-Wrapper frieren die Faltung, die Configuration-Ebene faltet die
 * FALSche Ebene: Fensterbreite ≠ Konfig-Dp). null = noch nicht provided
 * (alt-Fasern/Preview-Kontexte) — die Konsumenten lesen fail-soft.
 */
val LocalWindowSizeClass = staticCompositionLocalOf<WindowSizeClass?> { null }
