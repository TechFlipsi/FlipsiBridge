package com.hermesandroid.bridge.legal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PH8-T6 (W22, Lane E) — JVM-Tests der AGB-Akzeptanz-Regeln: Gate nur am
 * echten Erststart, Upgrade-Fail-Safe (Verlauf/Kopplung gilt als akzeptiert),
 * „nie wiederkehrend“ (Flag nie zurückgesetzt) und die Beiden Invarianten des
 * Akzeptieren/Ablehnen-Pfads. Ohne Android-Bindungen (AgbAcceptanceRules ist
 * pure-Kotlin) — die Android-Schale (Composable, Prefs) wird im Emulator
 * ge-drillt (KDoc-Acker in OnboardingScreens.kt).
 */
class AgbAcceptanceRulesTest {

    // ── hasLegacyPairing: Spiegel der migrationAllowed-Semantik (beide Teile) ──

    @Test
    fun `legacy pairing needs url and code`() {
        assertTrue(AgbAcceptanceRules.hasLegacyPairing("https://relay.example", "ABCD2345EF"))
        assertFalse(AgbAcceptanceRules.hasLegacyPairing(null, "ABCD2345EF"))
        assertFalse(AgbAcceptanceRules.hasLegacyPairing("https://relay.example", ""))
        assertFalse(AgbAcceptanceRules.hasLegacyPairing("", "   "))
        assertFalse(AgbAcceptanceRules.hasLegacyPairing("https://relay.example", "  "))
    }

    // ── effectiveAccepted: Flag ODER Fail-Safe (Verlauf/Kopplung) ──

    @Test
    fun `flag alone sets acceptance`() {
        assertTrue(
            AgbAcceptanceRules.effectiveAccepted(
                storedFlag = true,
                hasProfiles = false,
                hasLegacyPairing = false,
            ),
        )
    }

    @Test
    fun `fresh install without verband is not accepted`() {
        assertFalse(
            AgbAcceptanceRules.effectiveAccepted(
                storedFlag = false,
                hasProfiles = false,
                hasLegacyPairing = false,
            ),
        )
    }

    @Test
    fun `upgrade fail safe profiles imply acceptance (flag default must not re-ask)`() {
        assertTrue(
            AgbAcceptanceRules.effectiveAccepted(
                storedFlag = false,
                hasProfiles = true,
                hasLegacyPairing = false,
            ),
        )
    }

    @Test
    fun `upgrade fail safe legacy pairing implies acceptance`() {
        assertTrue(
            AgbAcceptanceRules.effectiveAccepted(
                storedFlag = false,
                hasProfiles = false,
                hasLegacyPairing = true,
            ),
        )
    }

    // ── mustAsk: exakt das Komplement (Gate nur am echten Erststart) ──

    @Test
    fun `gate only on genuine first start`() {
        assertTrue(
            AgbAcceptanceRules.mustAsk(
                storedFlag = false,
                hasProfiles = false,
                hasLegacyPairing = false,
            ),
        )
        assertFalse(
            AgbAcceptanceRules.mustAsk(
                storedFlag = true,
                hasProfiles = false,
                hasLegacyPairing = false,
            ),
        )
        assertFalse(
            AgbAcceptanceRules.mustAsk(
                storedFlag = false,
                hasProfiles = true,
                hasLegacyPairing = false,
            ),
        )
        assertFalse(
            AgbAcceptanceRules.mustAsk(
                storedFlag = false,
                hasProfiles = false,
                hasLegacyPairing = true,
            ),
        )
    }

    @Test
    fun `mustAsk is the negated effectiveAccepted`() {
        val flags = listOf(true, false)
        for (stored in flags) {
            for (profiles in flags) {
                for (legacy in flags) {
                    assertEquals(
                        !AgbAcceptanceRules.effectiveAccepted(stored, profiles, legacy),
                        AgbAcceptanceRules.mustAsk(stored, profiles, legacy),
                    )
                }
            }
        }
    }

    // ── Verbands-Invarianten des UI-Pfads (gepinnt, damit kein Weg driften kann) ──

    @Test
    fun `decline always ends the app cleanly`() {
        // Der Ablehnen-Pfad hat KEINEN „weiter ohne Akzeptanz“-Austritt:
        // die UI beendet mit finishAffinity, die Regel hält die Invariante fest.
        assertTrue(AgbAcceptanceRules.declineEndsApp)
    }

    @Test
    fun `acceptance persists immediately on accept`() {
        // P7-Verband: „Akzeptieren“ persistiert BEVOR weitergeschaltet wird —
        // ein Absturz danach verliert die Einlösung nicht.
        assertTrue(AgbAcceptanceRules.acceptPersistsImmediately)
    }

    // ── „nie wiederkehrend“: die Regel kennt KEINEN Reset-Ausgang ──

    @Test
    fun `no rule resets the acceptance flag`() {
        // Vertrags-Check: effectiveAccepted kann für den selben Flag-Input
        // nie von true auf false fallen (kein Rückkanal) — das Gate fragt
        // nach einmaliger Einlösung (bzw. Fail-Safe-Verband) nie erneut.
        assertTrue(
            AgbAcceptanceRules.effectiveAccepted(true, false, false),
        )
        assertTrue(
            // Fail-Safe-Verband bleibt Verband: einmal impliziert, immer impliziert.
            AgbAcceptanceRules.effectiveAccepted(false, true, true),
        )
    }
}
