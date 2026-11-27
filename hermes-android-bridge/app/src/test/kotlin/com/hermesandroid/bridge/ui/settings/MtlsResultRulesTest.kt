package com.hermesandroid.bridge.ui

import com.hermesandroid.bridge.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P42 (AKTIONEN-MIT-ERGEBNIS, Besitzer-Fund): die mTLS-Einschreibung meldete ihren
 * Ausgang an der BRIDGE-Statuszeile — die rendert auf der Einstellungen-Route
 * NIE (unsichtbar, MtlsCard/Einstellungen-Befund). Fix: Snack mit klarem Text
 * je Ausgang NACH dem Aktionsende (MtlsCard → onResult → BridgeScreen.snack ->
 * SnackbarHost). Diese Regeln festnageln, welcher Ausgang welchen Text:
 *
 * - Erfolg (res.ok) → mtls_enroll_ok (mit CN — „Zertifikat installiert …").
 * - Fehler "no-code" → mtls_no_code (Kopplungscode fehlt → am Code nachsetzen).
 * - Fehler enroll:403* → mtls_code_used (Code verbraucht → neuen erzeugen).
 * - alles andere → mtls_enroll_error (WAS fehlgeschlagen + Aktion).
 * - profile==null (kein aktives Serverprofil) → mtls_no_profile (Pre-Aktion).
 */
class MtlsResultRulesTest {

    @Test
    fun successMapsToTheInstallConfirmation() {
        assertEquals(R.string.mtls_enroll_ok, MtlsResultRules.resultRes(ok = true, error = null))
        assertEquals(
            "Erfolg gewinnt über jeden Fehler-Blob",
            R.string.mtls_enroll_ok,
            MtlsResultRules.resultRes(ok = true, error = "etwas-alt"),
        )
    }

    @Test
    fun noCodeErrorPointsAtThePairingCode() {
        assertEquals(
            R.string.mtls_no_code,
            MtlsResultRules.resultRes(ok = false, error = MtlsResultRules.ERR_NO_CODE),
        )
    }

    @Test
    fun usedCodeErrorPointsAtCodeGeneration() {
        assertEquals(
            R.string.mtls_code_used,
            MtlsResultRules.resultRes(ok = false, error = "enroll:403 code-in-use"),
        )
        assertEquals(
            "Exaktes Präfix reicht (Server-Stand)",
            R.string.mtls_code_used,
            MtlsResultRules.resultRes(ok = false, error = "enroll:403"),
        )
    }

    @Test
    fun anyOtherErrorIsTheHonestFailureLine() {
        assertEquals(
            R.string.mtls_enroll_error,
            MtlsResultRules.resultRes(ok = false, error = "enroll:500 boom"),
        )
        assertEquals(
            R.string.mtls_enroll_error,
            MtlsResultRules.resultRes(ok = false, error = null),
        )
    }

    @Test
    fun outcomeResourcesAreDistinct() {
        val success = R.string.mtls_enroll_ok
        val noCode = R.string.mtls_no_code
        val used = R.string.mtls_code_used
        val fail = R.string.mtls_enroll_error
        assertEquals(4, setOf(success, noCode, used, fail).size)
    }
}
