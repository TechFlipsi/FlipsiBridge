package com.hermesandroid.bridge.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PH-3 F7 (Jäger-Fix): relay.state-Wahrheits-Abgleich — ein verschlucktes
 * crypto.session_reset (Phone-WS-Reconnect, Eigenframes werden nicht
 * gespoolt) wird beim nächsten relay.state nachgeholt: session_active=false
 * am Relay + lokal aktive Session = Reset nachziehen.
 */
class E2eSessionTruthTest {

    @Test
    fun verschlucktes_reset_wird_als_mismatch_erkannt() {
        assertTrue(
            "Relay sagt inaktiv, Phone hält aktive Session → Reset nachholen",
            E2eSessionTruth.missedReset(relaySessionActive = false, localSessionActive = true),
        )
    }

    @Test
    fun konsistente_und_unbekannte_zustaende_sind_kein_mismatch() {
        assertFalse("beide aktiv = konsistent", E2eSessionTruth.missedReset(true, true))
        assertFalse("beide inaktiv = konsistent", E2eSessionTruth.missedReset(false, false))
        assertFalse("Relay aktiv, Phone nicht (z.B. Neustart) = Handshake-Lauf", E2eSessionTruth.missedReset(true, false))
        assertFalse(
            "Feld fehlt (älterer Relay) = NIE ein Reset (kein False-Positive beim Upgrade)",
            E2eSessionTruth.missedReset(null, true),
        )
    }
}