package com.hermesandroid.bridge.client

/**
 * PH-FIX-BACKEND-B (Bug-1-Härtung, PIPELINE-BUGS-20261102): hello_ack-Verify-
 * Fehler waren STILL (nur Log.w + Session-Verwerfen). Bei persistierender
 * KDF-Abweichung (z. B. asymmetrischer PSK auf der Agent-Seite) versank JEDE
 * Antwort im Relay-403-Downgrade-Guard: kein enc (keine Keys), kein
 * Klartext, kein Banner, kein Retry, kein Reset-Anstoß.
 *
 * Entscheidung je gescheitertem consumeAck: max. [MAX_RETRIES] Neu-Handshakes
 * (Backoff macht der Caller), danach SICHTBARER Fehler (Banner + Status,
 * analog fingerprint-mismatch-Pfad) — keine stillen Verwerfungen mehr.
 */
object HelloAckFailurePolicy {

    /** Neu-Handshake-Versuche je Connect-Zyklus nach hello_ack-Fehlern. */
    const val MAX_RETRIES = 2

    /**
     * true = noch einen Neu-Handshake versuchen; false = Banner/Status statt
     * weiterem Retry. failureCount = Zählerstand INKLUSIVE des aktuellen
     * Fehlschlags (1 = erster Fehlschlag → Retry 1, 3 = dritter → Schluss).
     */
    fun shouldRetry(failureCount: Int): Boolean = failureCount in 1..MAX_RETRIES

    /** Rot-Banner nach dem letzten Fehlschlag (Analogon zum MitM-Banner). */
    const val BANNER =
        "E2E-Handshake fehlgeschlagen (PSK/Key-Abweichung?) — bitte neu verbinden."

    /** Status-Zeile (Service-Notification/UI) — Analogon zum FP-Mismatch-Pfad. */
    const val STATUS = "E2E-Handshake fehlgeschlagen — neu verbinden"
}