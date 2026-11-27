package com.hermesandroid.bridge.ui.rules

import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W24 (Lane A): Verband-Matrix der Falt-Logik — Table-Tests JE Kante:
 * alle 9 width×height-Verwebungen (3 Material-Klassen × 3 Material-Klassen)
 * je Handy- und Tablet-Situation benannt, die Route-Faltungen (Chat/Call/
 * Settings/Cron) je Verband nachgefaltet. Pure JVM — die Material-Klassen
 * sind value-classes ohne Android-Framework-Zwang (KEIN Robolectric nötig).
 */
class WindowFoldRulesTest {

    /** Eine Kante der Verband-Matrix: Situation → erwartete Verband-Werte. */
    private data class Edge(
        val width: WindowWidthSizeClass,
        val height: WindowHeightSizeClass,
        val useRail: Boolean,
        val useTwoPane: Boolean,
        val chatTwoPane: Boolean,
        val contentCapDp: Int?,
        val label: String,
    )

    /** Der Brief-Contract als Kanten-Tabelle (ALLE 9 Verwebungen, je situiert). */
    private fun edges(): List<Edge> = listOf(
        // Handy-Portrait (COMPACT × COMPACT): Bottom-Bar, einspaltig, offene Breite.
        Edge(
            WindowWidthSizeClass.Compact, WindowHeightSizeClass.Compact,
            useRail = false, useTwoPane = false, chatTwoPane = false, contentCapDp = null,
            label = "Handy-Portrait",
        ),
        // Handy-Portrait hoch, 829dp Höhe am pixel_4 (COMPACT × MEDIUM) — der
        // Drill-Kernbefund: Rail-FELHER behoben, Bottom-Bar MUSS bleiben.
        Edge(
            WindowWidthSizeClass.Compact, WindowHeightSizeClass.Medium,
            useRail = false, useTwoPane = false, chatTwoPane = false, contentCapDp = null,
            label = "Handy-Portrait hoch (Compact×Medium)",
        ),
        // Hochgestrecktes Handy/Foldable schmal offen (COMPACT × EXPANDED):
        // Brief-Kante — NUR MEDIUM-Height trägt den Rail-Merker, kein TwoPane.
        Edge(
            WindowWidthSizeClass.Compact, WindowHeightSizeClass.Expanded,
            useRail = false, useTwoPane = false, chatTwoPane = false, contentCapDp = null,
            label = "Handy hochgestreckt (Foldable schmal)",
        ),
        // MEDIUM-Breite × COMPACT-Height = echtes Handy-Landschaft (die lange
        // Seite ALS Breite — 829dp am pixel_4 quer): Rail statt Bottom-Bar.
        Edge(
            WindowWidthSizeClass.Medium, WindowHeightSizeClass.Compact,
            useRail = true, useTwoPane = false, chatTwoPane = false, contentCapDp = null,
            label = "Handy-Landschaft (Medium×Compact)",
        ),
        // MEDIUM × MEDIUM (kleines Tablet quer): Rail, einspaltig.
        Edge(
            WindowWidthSizeClass.Medium, WindowHeightSizeClass.Medium,
            useRail = true, useTwoPane = false, chatTwoPane = false, contentCapDp = null,
            label = "kleines Tablet quer",
        ),
        // Tablet-Portrait (MEDIUM × EXPANDED): Rail, Chat bleibt einspaltig (kein TwoPane).
        Edge(
            WindowWidthSizeClass.Medium, WindowHeightSizeClass.Expanded,
            useRail = true, useTwoPane = false, chatTwoPane = false, contentCapDp = null,
            label = "Tablet-Portrait",
        ),
        // EXPANDED × COMPACT (Tablet quer, gestaucht): TwoPane-Verband, kein Rail.
        Edge(
            WindowWidthSizeClass.Expanded, WindowHeightSizeClass.Compact,
            useRail = false, useTwoPane = true, chatTwoPane = true,
            contentCapDp = WindowFoldRules.EXPANDED_CONTENT_CAP_DP,
            label = "Tablet quer gestaucht",
        ),
        // EXPANDED × MEDIUM (großes Tablet quer): TwoPane-Verband, kein Rail.
        Edge(
            WindowWidthSizeClass.Expanded, WindowHeightSizeClass.Medium,
            useRail = false, useTwoPane = true, chatTwoPane = true,
            contentCapDp = WindowFoldRules.EXPANDED_CONTENT_CAP_DP,
            label = "Tablet quer",
        ),
        // Tablet-Landscape-Verband (EXPANDED × EXPANDED): TwoPane + Content-Cap.
        Edge(
            WindowWidthSizeClass.Expanded, WindowHeightSizeClass.Expanded,
            useRail = false, useTwoPane = true, chatTwoPane = true,
            contentCapDp = WindowFoldRules.EXPANDED_CONTENT_CAP_DP,
            label = "Tablet-Landscape",
        ),
    )

    @Test
    fun foldMatrixMatchesBriefContract() {
        val broken = edges().map { edge ->
            val fold = WindowFoldRules.compose(edge.width, edge.height)
            edge to fold
        }.filterNot { (edge, fold) ->
            fold.useRail == edge.useRail &&
                fold.useTwoPane == edge.useTwoPane &&
                fold.expandedContentCapDp == edge.contentCapDp &&
                WindowFoldRules.chatTwoPane(fold) == edge.chatTwoPane &&
                WindowFoldRules.settingsContentCapDp(fold) == edge.contentCapDp &&
                WindowFoldRules.cronContentCapDp(fold) == edge.contentCapDp
        }
        assertTrue(
            "Verband-Kanten verletzt: " + broken.joinToString("\n  ") { (edge, fold) ->
                "${edge.label}: erwartete rail=${edge.useRail}, twoPane=${edge.useTwoPane}, " +
                    "cap=${edge.contentCapDp}, chat=${edge.chatTwoPane} — " +
                    "bekam rail=${fold.useRail}, twoPane=${fold.useTwoPane}, " +
                    "cap=${fold.expandedContentCapDp}"
            },
            broken.isEmpty(),
        )
    }

    @Test
    fun useRailOnlyFromMediumWidthOrLandscapePhone() {
        // Rail ja: echtes Handy-Landschaft (Medium-Breite × Compact-Höhe) —
        // die lange Seite ALS Breite fällt über dieselbe Medium-Klausel.
        val landscapePhone = WindowFoldRules.compose(
            WindowWidthSizeClass.Medium, WindowHeightSizeClass.Compact,
        )
        assertTrue(landscapePhone.useRail)
        // Rail nein: Handy-Portrait klassisch UND hoch (829dp = Compact×Medium —
        // der Drill-Kernbefund) UND hochgestreckt UND Expanded (TwoPane-Fläche).
        val portraitPhone = WindowFoldRules.compose(
            WindowWidthSizeClass.Compact, WindowHeightSizeClass.Compact,
        )
        assertFalse(portraitPhone.useRail)
        val portraitPhoneHoch = WindowFoldRules.compose(
            WindowWidthSizeClass.Compact, WindowHeightSizeClass.Medium,
        )
        assertFalse(portraitPhoneHoch.useRail)
        val stretchedPhone = WindowFoldRules.compose(
            WindowWidthSizeClass.Compact, WindowHeightSizeClass.Expanded,
        )
        assertFalse(stretchedPhone.useRail)
        val expanded = WindowFoldRules.compose(
            WindowWidthSizeClass.Expanded, WindowHeightSizeClass.Expanded,
        )
        assertFalse(expanded.useRail)
    }

    @Test
    fun twoPaneOnlyFromExpandedWidthHeightFoldsNotAlong() {
        // Height faltet NIE mit: je Height-Verband gilt — Compact/Medium-Breite
        // faltet einspaltig, Expanded-Breite faltet TwoPane.
        for (height in listOf(
            WindowHeightSizeClass.Compact,
            WindowHeightSizeClass.Medium,
            WindowHeightSizeClass.Expanded,
        )) {
            assertFalse(
                WindowFoldRules.compose(WindowWidthSizeClass.Compact, height).useTwoPane,
            )
            assertFalse(
                WindowFoldRules.compose(WindowWidthSizeClass.Medium, height).useTwoPane,
            )
            assertTrue(
                WindowFoldRules.compose(WindowWidthSizeClass.Expanded, height).useTwoPane,
            )
        }
    }

    @Test
    fun chatIsTheOnlyTwoPaneRouteCallDeliberatelyStaysFullscreen() {
        // Chat: TwoPane je expanded — Call: BEWUSST Vollband auf JEDEM Verband
        // (kein Falt-Sprung mitten im laufenden Anruf).
        for (width in listOf(
            WindowWidthSizeClass.Compact,
            WindowWidthSizeClass.Medium,
            WindowWidthSizeClass.Expanded,
        )) {
            for (height in listOf(
                WindowHeightSizeClass.Compact,
                WindowHeightSizeClass.Medium,
                WindowHeightSizeClass.Expanded,
            )) {
                val fold = WindowFoldRules.compose(width, height)
                assertEquals(fold.useTwoPane, WindowFoldRules.chatTwoPane(fold))
                assertFalse(WindowFoldRules.callTwoPane(fold))
            }
        }
    }

    @Test
    fun settingsAndCronCapOnlyOnExpandedWithinBriefBand() {
        // Handy/medium-Verbaende: offene Breite (null — kein Cap am Handy).
        for (width in listOf(WindowWidthSizeClass.Compact, WindowWidthSizeClass.Medium)) {
            val fold = WindowFoldRules.compose(width, WindowHeightSizeClass.Compact)
            assertNull(WindowFoldRules.settingsContentCapDp(fold))
            assertNull(WindowFoldRules.cronContentCapDp(fold))
        }
        // Expanded: EINDEUTIGE Kappe — 640dp, beweisbar in der 600-720dp-Bandbreite.
        val expanded = WindowFoldRules.compose(
            WindowWidthSizeClass.Expanded, WindowHeightSizeClass.Expanded,
        )
        assertEquals(640, WindowFoldRules.settingsContentCapDp(expanded))
        assertEquals(640, WindowFoldRules.cronContentCapDp(expanded))
        assertTrue(WindowFoldRules.EXPANDED_CONTENT_CAP_DP in 600..720)
    }

    /**
     * W28 (Lane B, Inset-SSOT): JE Kante nachgefaßt — die Leiste sitzt über
     * der Gesture-Säule NUR je kompakter Portrait-Bandbreite (bottomBarHost =
     * dieselbe Regel wie am Scaffold-Wrap); je Rail-/TwoPane-Verband liegt
     * die Säule beim SCREEN (48 dp + navigationBars). null fällt fail-soft
     * wie am Wrap (Leiste angenommen).
     */
    @Test
    fun bottomBarHostOnlyOnCompactWidthAllHeightsPlusNullFailSoft() {
        for (height in listOf(
            WindowHeightSizeClass.Compact,
            WindowHeightSizeClass.Medium,
            WindowHeightSizeClass.Expanded,
        )) {
            // kompakte Portrait-Bandbreite: Leiste über der Säule.
            assertTrue(
                WindowFoldRules.bottomBarHost(
                    WindowFoldRules.compose(WindowWidthSizeClass.Compact, height),
                ),
            )
            // Rail/TwoPane-Verband: KEINE Leiste — die Säule lebt am Screen.
            assertFalse(
                WindowFoldRules.bottomBarHost(
                    WindowFoldRules.compose(WindowWidthSizeClass.Medium, height),
                ),
            )
            assertFalse(
                WindowFoldRules.bottomBarHost(
                    WindowFoldRules.compose(WindowWidthSizeClass.Expanded, height),
                ),
            )
        }
        // null-fiber fail-soft (Preview-/alt-Kontext): wie am Wrap = Leiste an.
        assertTrue(WindowFoldRules.bottomBarHost(null))
    }

    /**
     * W28 (Lane F, Reviewer-B-Auflage P2): der BODEN-Helfer je Kante
     * nachgefaßt — geprüft wird der reine Kern
     * [WindowFoldRules.bottomBarFloorDp] (der @Composable-Wickel
     * [WindowFoldRules.bottomBarFloorPadding] liest die navigationBars je
     * Composition und delegiert hierher — dieselbe Faser-Faltung wie der
     * bottomBarHost-Test):
     *  - kompakte Portrait-Bandbreite (Leiste über der Säule, JE Höhe):
     *    NUR Haus-Touch-Raum 48 dp — die Leiste trägt die Säule, ein Extra
     *    läge doppelt (github-34 = 0-dp-Delta am Bar-Bestand).
     *  - Rail je MEDIUM-Width + TwoPane je EXPANDED: 48 dp + navigationBars
     *    (play-36 Rail-Boden, K4: Karten enden nie hart an der Säule).
     *  - null fail-soft: wie am Wrap = Leiste angenommen → 48 dp.
     *  - github-34-Verband (Decor-fits, Insets ≡ 0 dp): BEIDE Zustände
     *    liefern 48 dp — das 0-dp-Delta des Inset-SSOT-Verbands (4dbbb2c)
     *    bleibt beweisbar.
     */
    @Test
    fun bottomBarFloorPaddingEdgesNullFailSoftAndGithub34ZeroDelta() {
        for (height in listOf(
            WindowHeightSizeClass.Compact,
            WindowHeightSizeClass.Medium,
            WindowHeightSizeClass.Expanded,
        )) {
            val navBars = 24.dp
            // Leiste über der Säule (kompakt, JE Höhe): NUR 48 dp.
            assertEquals(
                48.dp,
                WindowFoldRules.bottomBarFloorDp(
                    WindowFoldRules.compose(WindowWidthSizeClass.Compact, height),
                    navBars,
                ),
            )
            // OHNE Leistenslot (Rail je MEDIUM-Width): die Säule kommt dazu.
            assertEquals(
                72.dp,
                WindowFoldRules.bottomBarFloorDp(
                    WindowFoldRules.compose(WindowWidthSizeClass.Medium, height),
                    navBars,
                ),
            )
            // OHNE Leistenslot (TwoPane je EXPANDED): ebenfalls.
            assertEquals(
                72.dp,
                WindowFoldRules.bottomBarFloorDp(
                    WindowFoldRules.compose(WindowWidthSizeClass.Expanded, height),
                    navBars,
                ),
            )
        }
        // null-fiber fail-soft (Preview-/alt-Kontext): Leiste angenommen → 48 dp.
        assertEquals(48.dp, WindowFoldRules.bottomBarFloorDp(null, 24.dp))
        // github-34 (Insets ≡ 0 dp): BEIDE Zustände = 48 dp → 0-dp-Delta bleibt.
        assertEquals(
            48.dp,
            WindowFoldRules.bottomBarFloorDp(
                WindowFoldRules.compose(
                    WindowWidthSizeClass.Compact, WindowHeightSizeClass.Compact,
                ),
                0.dp,
            ),
        )
        assertEquals(
            48.dp,
            WindowFoldRules.bottomBarFloorDp(
                WindowFoldRules.compose(
                    WindowWidthSizeClass.Medium, WindowHeightSizeClass.Compact,
                ),
                0.dp,
            ),
        )
    }

    /**
     * W28 (Lane G, Jäger-F1): Rail-Node-Offset unter der TopBar —
     * topBar-Geometrie MINUS Status-Säule (die Rail trägt sie selbst; das
     * Scaffold-Oben enthält sie schon), fail-soft ≥ 0. github-34 (Status
     * ≡ 0 dp): die volle TopBar-Geometrie bleibt Zusatz-Offset.
     */
    @Test
    fun railTopDpBarGeometryMinusStatusFailSoftZero() {
        // play-36-Verband: TopBar-Nachlass 99 dp (64 + 35 Status) → Node-Offset 64.
        assertEquals(64.dp, WindowFoldRules.railTopDp(99.dp, 35.dp))
        // Anomalie (Status-Nachlass über dem Bar-Nachlass): fail-soft auf 0 dp.
        assertEquals(0.dp, WindowFoldRules.railTopDp(20.dp, 35.dp))
        // github-34 (Status ≡ 0): die volle TopBar-Geometrie bleibt Extra.
        assertEquals(64.dp, WindowFoldRules.railTopDp(64.dp, 0.dp))
        // Leere Rechnung kippt nie: 0 − 0 → 0.
        assertEquals(0.dp, WindowFoldRules.railTopDp(0.dp, 0.dp))
    }

    /**
     * W28 (Lane G, Jäger-F3): Suchzustand-Boden = die fold-FERNE Formel —
     * 48 dp Haus-Touch-Raum + navigationBars je Real-Wert; github-34
     * (Inset ≡ 0 dp) → 48 dp (0-dp-Delta am Inset-Anteil).
     */
    @Test
    fun searchFloorDpAlwaysHouseTouchPlusNavigationBars() {
        assertEquals(72.dp, WindowFoldRules.searchFloorDp(24.dp))
        // github-34-Verband (Insets ≡ 0 dp): NUR der Haus-Touch-Raum bleibt.
        assertEquals(48.dp, WindowFoldRules.searchFloorDp(0.dp))
    }
}
