package com.hermesandroid.bridge.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SIRS-01 (P0, Volltest 03.11.2026): Je crypto.session_reset-Reason entscheidet
 * das App-Verhalten — benign (Agent-Neustart: „Zeile + Neu-Handshake“) vs.
 * Sicherheitsereignis (Fingerprint-Abweichung: MitM-Banner, kein Auto-Heal).
 *
 * Vertrag (PoC scratch/sirs_volltest/poc/SIRS-01-agent-restart-session-loss.md):
 * - reason="agent-session-lost" → NEUTRAL: kein rot-Banner, kein Security-Event,
 *   aber startHandshakeNow=true (SOFORT neuer crypto.hello statt stiller toter
 *   Chat wie im Volltest beobachtet).
 * - fingerprint-mismatch / unbekannt / null → Sicherheitsereignis mit Banner,
 *   KEIN Auto-Handshake (User soll die Warnung sehen).
 */
class SessionResetBehaviorTest {

    @Test
    fun benignAgentSessionLostStartsHandshakeWithoutMitmNoise() {
        val decision = sessionResetDecision("agent-session-lost")
        assertNull("Benigner Reset darf KEIN rot-Banner reißen", decision.banner)
        assertFalse("Agent-Neustart ist kein Sicherheitsereignis", decision.isSecurityEvent)
        assertTrue(
            "SIRS-01: Neu-Handshake MUSS sofort angestoßen werden",
            decision.startHandshakeNow,
        )
    }

    @Test
    fun fingerprintMismatchIsASecurityEventWithBannerAndNoAutoHeal() {
        val decision = sessionResetDecision("fingerprint-mismatch")
        assertTrue(decision.isSecurityEvent)
        assertFalse("Sicherheitsereignis heilt sich NICHT selbst weg", decision.startHandshakeNow)
        assertTrue(
            "MitM-Warnung muss benannt bleiben",
            decision.banner!!.contains("Man-in-the-Middle"),
        )
    }

    @Test
    fun crossSessionTakeoverIsSecurityEventWithOwnLanguageNoAutoHeal() {
        // FIX-WELLE-2 B8 (HERZ-Befund 8): Takeover-Sonde des Adapters — eigene
        // Sprache (KEINE MitM-Behauptung), sichtbares Ereignis, kein Auto-
        // Handshake (sonst sofortiges Wieder-Verdrängen durch die übernehmende
        // Session = Ping-Pong).
        val decision = sessionResetDecision("cross-session-takeover")
        assertTrue(decision.isSecurityEvent)
        assertFalse("Takeover heilt sich NICHT selbst weg", decision.startHandshakeNow)
        val banner = decision.banner!!
        assertTrue("Takeover-Banner muss Übernahme benennen", banner.contains("übernommen"))
        assertFalse("Takeover ist KEINE MitM-Behauptung", banner.contains("Man-in-the-Middle"))
    }

    @Test
    fun unknownOrNullReasonFailsClosedToFingerprintMismatch() {
        // Der Relay-Forwarder allowlistet nur bekannte Reasons — für die App
        // gilt dasselbe fail-closed: unbekannt/fehlend = Sicherheitsereignis.
        assertEquals(
            sessionResetDecision("fingerprint-mismatch"),
            sessionResetDecision("kaputter-muell-reason"),
        )
        assertEquals(
            sessionResetDecision("fingerprint-mismatch"),
            sessionResetDecision(null),
        )
    }

    @Test
    fun benignReasonConstantMatchesProtocolValue() {
        // Protokoll-Vertrag mit dem Adapter (adapter.py/_RESET_REASONS): der
        // Reason-Wert ist ein verhandeltes Wire-Format, keine freie Zeichenkette.
        assertEquals("agent-session-lost", REASON_AGENT_SESSION_LOST)
    }
}