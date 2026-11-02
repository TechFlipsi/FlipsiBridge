package com.hermesandroid.bridge.client

/**
 * SIRS-01 (P0, Volltest 03.11.2026): Verhalten je crypto.session_reset-Reason.
 *
 * Hintergrund: Der Agent-Prozess verliert bei einem Neustart (Crash/Update/
 * Wartung) seine In-Memory-E2E-Session. Bisher verwurf er weitere chat.send.enc-
 * Frames STILL — das Phone zeigte „✓✓ Zugestellt“ + „E2E“-Badge weiter, der
 * Chat war stumm tot (Heilung nur per App-Restart; Volltest agent-file.log
 * 08:54/08:55). Jetzt stößt der Agent sofort crypto.session_reset
 * (reason=„agent-session-lost“) an — der Relay entfernt die Session und
 * informiert das Phone — und die App reagiert JE nach Reason:
 *
 * - [REASON_AGENT_SESSION_LOST]: BENIGN — der Agent kennt die Sitzung nicht
 *   mehr (Neustart, KEIN Sicherheitsereignis): keine MitM-Sprache, KEIN
 *   rot-Banner, Verbindungsstatus unberührt — stattdessen SOFORT ein neuer
 *   crypto.hello-Handshake (die Sitzung heilt sich selbst, ohne App-Restart).
 * - alles andere (Default: „fingerprint-mismatch“): Sicherheitsereignis wie
 *   bisher — rot-Banner + PAIRING_BROKEN-artige Status-Zeile, KEIN Auto-Heal
 *   (das Ereignis soll gesehen werden, nicht weggeheilt werden).
 *
 * Bewusst als eigene, seiteneffektfreie Funktion (kein Zugriff auf die
 * privaten RelayClient-Interna): JVM-Test-barer Vertrag für den Re-Test-Worker.
 */
data class SessionResetDecision(
    /** Sichtbare rot-Banner-Zeile; null = kein Banner (benigner Fall). */
    val banner: String?,
    /** true = Sicherheitsereignis (MitM-Sprache + PAIRING_BROKEN-Status). */
    val isSecurityEvent: Boolean,
    /** true = SOFORT einen neuen crypto.hello-Handshake anstoßen. */
    val startHandshakeNow: Boolean,
)

/** SIRS-01: Benigner Reset-Reason des Agenten (Session nach Neustart weg). */
const val REASON_AGENT_SESSION_LOST = "agent-session-lost"

/**
 * Je session_reset-Reason das App-Verhalten (SIRS-01). Der Relay leitet nur
 * Allowlist-Reasons 1:1 weiter — unbekannt/fehlend = fail-closed als
 * Sicherheitsereignis (gleiche Sprache wie der bisherige hart-codierte Fall).
 */
fun sessionResetDecision(reason: String?): SessionResetDecision =
    if (reason == REASON_AGENT_SESSION_LOST) {
        SessionResetDecision(
            banner = null,
            isSecurityEvent = false,
            startHandshakeNow = true,
        )
    } else {
        SessionResetDecision(
            banner = "E2E-Sitzung zurückgesetzt — Fingerprint-Abweichung! " +
                "Mögliches Man-in-the-Middle. Sitzung ist ungültig, neu verbinden.",
            isSecurityEvent = true,
            startHandshakeNow = false,
        )
    }