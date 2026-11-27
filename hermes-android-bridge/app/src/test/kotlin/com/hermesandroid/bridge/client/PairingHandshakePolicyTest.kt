package com.hermesandroid.bridge.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W17 (F1 + F2) — reine Handshake-Policy-Regeln (JVM, kein Android).
 *
 * F1: code-in-use-Antworten (Header/Close-Code) sind KEINE Auth-Ausrutscher.
 * F2: Passwort-Tor stoppt die Auto-Retries und triggert den App-Prompt;
 *     Passwort-Form spiegt die /password_set-Validierung des Relays.
 */
class PairingHandshakePolicyTest {

    @Test
    fun `nur bekannte Maschinen-Codes kommen durch den Header-Peer`() {
        assertEquals(
            PairingHandshakePolicy.ERROR_CODE_IN_USE,
            PairingHandshakePolicy.handshakeError("code-in-use"),
        )
        assertEquals(
            PairingHandshakePolicy.ERROR_PASSWORD_REQUIRED,
            PairingHandshakePolicy.handshakeError("password-required"),
        )
        assertEquals(
            PairingHandshakePolicy.ERROR_PASSWORD_INVALID,
            PairingHandshakePolicy.handshakeError("password-invalid"),
        )
        // Müll/Leer: KEIN Signal (kein falscher Prompt, kein falscher Snack).
        assertEquals(null, PairingHandshakePolicy.handshakeError(null))
        assertEquals(null, PairingHandshakePolicy.handshakeError(""))
        assertEquals(null, PairingHandshakePolicy.handshakeError("code-in-use "))
        assertEquals(null, PairingHandshakePolicy.handshakeError("HACKED"))
    }

    @Test
    fun `code-in-use und Passwort-Tor korrekt klassifiziert, kein Streak-Bump`() {
        assertTrue(
            PairingHandshakePolicy.isCodeInUse(PairingHandshakePolicy.ERROR_CODE_IN_USE),
        )
        assertTrue(
            PairingHandshakePolicy.isPasswordGate(PairingHandshakePolicy.ERROR_PASSWORD_REQUIRED),
        )
        assertTrue(
            PairingHandshakePolicy.isPasswordGate(PairingHandshakePolicy.ERROR_PASSWORD_INVALID),
        )
        assertFalse(
            PairingHandshakePolicy.isPasswordGate(PairingHandshakePolicy.ERROR_CODE_IN_USE),
        )
        // F1-Kern: code-in-use ist KEIN Auth-Fehlversuch (der Code war korrekt).
        assertFalse(PairingHandshakePolicy.shouldBumpAuthStreak(PairingHandshakePolicy.ERROR_CODE_IN_USE))
        // Passwort-Tor ebenfalls nicht (separater Prompt-Pfad statt Sperre).
        assertFalse(PairingHandshakePolicy.shouldBumpAuthStreak(PairingHandshakePolicy.ERROR_PASSWORD_REQUIRED))
        // Fehlender Code (null) bleibt im hergebrachten Auth-Pfad.
        assertTrue(PairingHandshakePolicy.shouldBumpAuthStreak(null))
    }

    @Test
    fun `race-slot close-code 4403 = code-in-use-Semantik`() {
        assertTrue(PairingHandshakePolicy.closeCodeMeansCodeInUse(4403))
        assertFalse(PairingHandshakePolicy.closeCodeMeansCodeInUse(1000))
        assertFalse(PairingHandshakePolicy.closeCodeMeansCodeInUse(1006))
        assertFalse(PairingHandshakePolicy.closeCodeMeansCodeInUse(4404))
    }

    @Test
    fun `passwort-form spiegt die server-validierung (8 bis 256)`() {
        assertFalse(PairingHandshakePolicy.passwordAllowed(null))
        assertFalse(PairingHandshakePolicy.passwordAllowed(""))
        assertFalse(PairingHandshakePolicy.passwordAllowed("kurz"))
        assertTrue(PairingHandshakePolicy.passwordAllowed("12345678"))
        // Passworte mit Leerzeichen sind legitime Passworte (kein trim) —
        // 8 Spaces = Länge 8 = durchgelassen (der Hash rechnet exakt).
        assertTrue(PairingHandshakePolicy.passwordAllowed("        "))
        assertTrue(PairingHandshakePolicy.passwordAllowed("a 234567"))
        assertTrue(PairingHandshakePolicy.passwordAllowed("x".repeat(256)))
        assertFalse(PairingHandshakePolicy.passwordAllowed("x".repeat(257)))
    }
}
