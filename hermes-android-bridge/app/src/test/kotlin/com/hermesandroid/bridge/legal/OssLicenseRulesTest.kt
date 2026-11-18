package com.hermesandroid.bridge.legal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PH8-T7 (W22, Lane E) — JVM-Tests der kuratierten OSS-Liste: Integrität
 * (10-20 Einträge, je Zeile vollständig, keine Doppel), Editions-Siebung
 * (billing-ktx nur Play) und die Zeilen-Formatierung „Name — Version —
 * Lizenz“. Die Quelle (libs.versions.toml + app/build.gradle.kts) ist
 * kuratiert und wird gepinnt — ein Abhängigkeits-Add/Drop ohne Listen-Pflege
 * fällt NICHT hierauf auf (Kuratierung ist bewusst manuell), aber Format-
 * und Sortier-Drift werden hier gehalten.
 */
class OssLicenseRulesTest {

    @Test
    fun `curation is valid and within the briefed range`() {
        assertTrue(OssLicenseRules.curationValid())
    }

    @Test
    fun `full list holds 10 to 20 entries`() {
        assertTrue(OssLicenseRules.components.size in 10..20)
        println("OSS-Liste: ${OssLicenseRules.components.size} Einträge")
    }

    @Test
    fun `play only artifact is excluded from github edition and included in play`() {
        val github = OssLicenseRules.sortedDisplay(editionPlay = false)
        val play = OssLicenseRules.sortedDisplay(editionPlay = true)
        assertTrue(github.none { it.name == OssLicenseRules.PLAY_ONLY_ARTIFACT })
        assertTrue(play.any { it.name == OssLicenseRules.PLAY_ONLY_ARTIFACT })
        assertEquals(OssLicenseRules.sortedDisplay(editionPlay = false).size + 1, play.size)
    }

    @Test
    fun `display list is alphabetical case-insensitive`() {
        val names = OssLicenseRules.sortedDisplay(editionPlay = true).map { it.name }
        assertEquals(names, names.sortedBy { it.lowercase() })
    }

    @Test
    fun `line label format is name version license`() {
        val c = OssComponent("com.example:test", "1.2.3", "MIT", "Copyright (C) Example")
        assertEquals("com.example:test — 1.2.3 — MIT", OssLicenseRules.lineLabel(c))
    }

    @Test
    fun `every entry has license and copyright and names are unique`() {
        val comps = OssLicenseRules.components
        assertEquals(comps.size, comps.map { it.name }.distinct().size)
        assertTrue(comps.all { it.license.isNotBlank() && it.copyright.isNotBlank() })
        // Alle Runtimre-Lizenznamen sind Eigenamen (hier: Apache-2.0-Verband).
        assertTrue(OssLicenseRules.licensesUsed().all { it.isNotBlank() })
    }

    @Test
    fun `core stack okhttp ktor gson are listed`() {
        val names = OssLicenseRules.components.map { it.name }
        assertTrue(names.any { it.startsWith("com.squareup.okhttp3:okhttp") })
        assertTrue(names.any { it.startsWith("io.ktor:ktor-server-netty") })
        assertTrue(names.any { it.startsWith("com.google.code.gson:gson") })
        assertNotEquals(-1, names.indexOfFirst { it.startsWith("androidx.compose.material3:material3") })
    }
}
