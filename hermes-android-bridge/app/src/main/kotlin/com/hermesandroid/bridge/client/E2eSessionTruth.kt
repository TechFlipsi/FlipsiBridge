package com.hermesandroid.bridge.client

/**
 * PH-3 F7 (Jäger-Fix) + PH-FIX-BACKEND-B: relay.state-Wahrheits-Abgleich der
 * E2E-Sessions. Der Relay erweitert den relay.state-Hallo-Frame um
 * session_active je Agent (Wahrheit des Session-Brokers).
 *
 * Zwei Spiegel-Lücken (PIPELINE-BUGS-20261102, Bug 1):
 * - missedReset: Sagt der Relay session_active=false, während das Phone lokal
 *   noch eine aktive Session hält, wurde das crypto.session_reset-Frame auf
 *   einer toten WS verschluckt (Eigenframes werden nicht gespoolt) →
 *   Reset NACHHOLEN (Session verwerfen + Banner).
 * - needsHandshake: Sagt der Relay session_active=true, während das Phone
 *   LOKAL KEINE Session hält (hello_ack-Verify still gescheitert, App-
 *   Neustart o.ä.), ist die App im Deadlock: enc unmöglich (keine Keys),
 *   Klartext = 403-Downgrade-Verwurf am Relay → Neu-Handshake ERZWINGEN.
 *   Ein solcher Handshake, der erneut an der KDF scheitert, läuft danach in
 *   das sichtbare Banner/Retry (HelloAckFailurePolicy) statt Stillstand.
 */
object E2eSessionTruth {

    /**
     * true = ein Reset ist durchgerutscht und muss nachgeholt werden.
     * relaySessionActive == null (älterer Relay ohne Feld) = niemals true
     * (Verhalten bleibt unverändert, kein False-Positive beim Upgrade-Pfad).
     */
    fun missedReset(relaySessionActive: Boolean?, localSessionActive: Boolean): Boolean =
        relaySessionActive == false && localSessionActive

    /**
     * PH-FIX-BACKEND-B (Bug-1-Spiegel-Branch): true = Relay hält eine aktive
     * E2E-Session, das Phone hat lokal KEINE → Neu-Handshake erzwingen.
     * relaySessionActive == null (älterer Relay ohne Feld) = niemals true
     * (kein erzwungener Handshake-Sturm beim Upgrade-Pfad).
     */
    fun needsHandshake(relaySessionActive: Boolean?, localSessionActive: Boolean): Boolean =
        relaySessionActive == true && !localSessionActive
}