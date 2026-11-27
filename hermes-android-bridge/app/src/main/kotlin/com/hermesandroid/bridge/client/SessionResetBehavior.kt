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
 * - [REASON_CROSS_SESSION_TAKEOVER] (FIX-WELLE-2 B8, HERZ-Befund 8): Eine
 *   FREMDE Session-ID traf bei aktiver Session ein (zweite Pairing-Code-
 *   Inhaber-Session hat die Phone-Session verdrängt = Übernahme, ODER
 *   Cross-Session-Replay-Sonde). Sicherheitsereignis mit eigener Takeover-
 *   Sprache (KEINE MitM-Behauptung) und bewusst KEIN Auto-Handshake: ein
 *   sofortiger Neu-Handshake würde von der übernehmenden Session sofort
 *   wieder verdrängt (Ping-Pong) — der User soll SEHEN, dass jemand mit
 *   dem Kopplungscode die Session übernommen hat, und neu verbinden.
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

/** FIX-WELLE-2 B8: Fremde Session-ID bei aktiver Session (Übernahme/Replay-Sonde). */
const val REASON_CROSS_SESSION_TAKEOVER = "cross-session-takeover"

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
    } else if (reason == REASON_CROSS_SESSION_TAKEOVER) {
        // FIX-WELLE-2 B8 (HERZ-Befund 8): Takeover statt MitM — eigene Sprache,
        // sichtbares Ereignis, bewusst kein Auto-Heal (Ping-Pong-Gefahr).
        SessionResetDecision(
            banner = "E2E-Sitzung wurde durch eine fremde Session ersetzt — " +
                "jemand hat mit dem Kopplungscode die Sitzung übernommen. " +
                "Sitzung ist ungültig, neu verbinden.",
            isSecurityEvent = true,
            startHandshakeNow = false,
        )
    } else {
        SessionResetDecision(
            banner = "E2E-Sitzung zurückgesetzt — Fingerprint-Abweichung! " +
                "Mögliches Man-in-the-Middle. Sitzung ist ungültig, neu verbinden.",
            isSecurityEvent = true,
            startHandshakeNow = false,
        )
    }