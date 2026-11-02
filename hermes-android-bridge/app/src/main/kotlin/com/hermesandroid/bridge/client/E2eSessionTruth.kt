package com.hermesandroid.bridge.client

/**
 * PH-3 F7 (Jäger-Fix): relay.state-Wahrheits-Abgleich der E2E-Sessions.
 * Der Relay erweitert den relay.state-Hallo-Frame um session_active je
 * Agent (Wahrheit des Session-Brokers). Sagt der Relay session_active=false,
 * während das Phone lokal noch eine aktive Session hält, wurde das
 * crypto.session_reset-Frame auf einer toten WS verschluckt (Eigenframes
 * werden nicht gespoolt) → Reset NACHHOLEN (Session verwerfen + Banner).
 */
object E2eSessionTruth {

    /**
     * true = ein Reset ist durchgerutscht und muss nachgeholt werden.
     * relaySessionActive == null (älterer Relay ohne Feld) = niemals true
     * (Verhalten bleibt unverändert, kein False-Positive beim Upgrade-Pfad).
     */
    fun missedReset(relaySessionActive: Boolean?, localSessionActive: Boolean): Boolean =
        relaySessionActive == false && localSessionActive
}