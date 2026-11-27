package com.hermesandroid.bridge.client

/**
 * PH-3 F7 (Jäger-Fix) + PH-FIX-BACKEND-B: relay.state-Wahrheits-Abgleich der
 * E2E-Sessions. Der Relay erweitert den relay.state-Hallo-Frame um
 * session_active je Agent (Wahrheit des Session-Brokers).
 *
 * ROADMAP-MULTIGERAETE Kap. 3.2 (Z.319-321): der Hallo-Frame ist je WS eigen —
 * der Relay berichtet die session_active-Wahrheit je VERBINDUNG. Das
 * GERÄTE-EIGENE Feld session_active_own (Session-Lage DES Empfangsgeräts,
 * device_id-Bindung des Session-Brokers) gewinnt vor der GLOBAL-Wahrheit
 * session_active (Bestands-Kontrakt über allen Geräten): ein Zweitgerät
 * ohne eigene Session sieht für denselben Agenten ehrlich false, statt aus
 * der Global-Spielfelder-Wahrheit einen Neu-Handshake-Sturm zu starten.
 * [relaySessionActive] = DIE Eine-Auswahl-Faser (own vor global; fehlt das
 * eigne Feld — alter Relay — gilt die globale Wahrheit weiter, Additiv-Vertrag
 * Kap. 5, Invarianz 4). Die Norm-Funktionen missedReset/needsHandshake
 * bleiben EXAKT (Kap. 3.2).
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
     * DIE Eine-Auswahl-Faser der relay.state-Session-Wahrheit (Roadmap Kap.
     * 3.2): GERÄTE-EIGENES Feld zuerst (session_active_own — die Session-Lage
     * DES Geräts), Global-Wahrheit (session_active) NUR als Fallback für den
     * alter-Relay-Pfad ohne das eigne Feld. Beide fehlen (null/null) = null
     * (Verhalten unverändert, kein False-Path beim Upgrade).
     */
    fun relaySessionActive(own: Boolean?, global: Boolean?): Boolean? =
        if (own != null) own else global

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