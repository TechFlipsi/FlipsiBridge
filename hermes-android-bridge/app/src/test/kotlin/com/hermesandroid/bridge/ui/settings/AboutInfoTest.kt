package com.hermesandroid.bridge.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Welle 13 / P2b: Versionsanzeige-Vertrag — NUR „vX.Y.Z“, Editions-Suffix
 * (-play/-github) fällt weg (QA A: kein Flavor-Leak in der UI).
 */
class AboutInfoTest {

    @Test
    fun play_suffix_faellt_weg() {
        assertEquals("v0.15.2", AboutInfo.versionLabel("0.15.2-play"))
    }

    @Test
    fun github_suffix_faellt_weg() {
        assertEquals("v0.15.2", AboutInfo.versionLabel("0.15.2-github"))
    }

    @Test
    fun plain_version_bleibt() {
        assertEquals("v0.15.2", AboutInfo.versionLabel("0.15.2"))
    }
}
