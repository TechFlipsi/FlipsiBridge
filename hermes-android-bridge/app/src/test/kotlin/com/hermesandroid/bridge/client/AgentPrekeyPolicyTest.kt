package com.hermesandroid.bridge.client

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W13-P2 (Jäger-Befund 12.11.26): /key_get-Eckfälle. MIT spk_pub = Ok (der
 * Handshake läuft weiter), OHNE/leer spk_pub = MissingPrekey — das war zuvor
 * der stille Onboarding-Tod („Warte auf E2E-Handshake“ ohne Ende). Die Policy
 * klemmt die Retry-Kappe (max. 3 Versuche gesamt) und die sichtbare
 * Fehlerzeile mit Handlungshinweis fest.
 */
class AgentPrekeyPolicyTest {

    /** /key_get-Body wie ihn das Relay schickt (spkPub null = Feld fehlt). */
    private fun keyGetBody(spkPub: String?): String =
        if (spkPub == null) """{"ik_pub":"ikAA"}"""
        else """{"ik_pub":"ikAA","spk_pub":"$spkPub"}"""

    private fun classify(body: String): AgentPrekeyPolicy.KeyGetResult =
        AgentPrekeyPolicy.classify(JsonParser.parseString(body).asJsonObject)

    @Test
    fun key_get_mit_spk_pub_liefert_beide_keys() {
        val result = classify(keyGetBody("spkBB"))
        assertTrue("vollständige Antwort muss Ok sein", result is AgentPrekeyPolicy.KeyGetResult.Ok)
        result as AgentPrekeyPolicy.KeyGetResult.Ok
        assertEquals("ikAA", result.ikPub)
        assertEquals("spkBB", result.spkPub)
    }

    @Test
    fun key_get_mit_leerem_spk_pub_ist_missing_prekey() {
        // Der W13-Befund: HTTP 200, aber spk_pub = "" → PhoneCryptoPeer warf
        // vorher still die CryptoFrameException.
        val result = classify(keyGetBody(""))
        assertTrue("leerer spk_pub muss MissingPrekey sein", result is AgentPrekeyPolicy.KeyGetResult.MissingPrekey)
    }

    @Test
    fun key_get_ohne_whitespace_oder_feld_ist_missing_prekey() {
        assertTrue(
            "Whitespace-spk_pub muss MissingPrekey sein",
            classify(keyGetBody("   ")) is AgentPrekeyPolicy.KeyGetResult.MissingPrekey,
        )
        assertTrue(
            "ganz fehlendes spk_pub-Feld muss MissingPrekey sein",
            classify("""{"ik_pub":"ikAA"}""") is AgentPrekeyPolicy.KeyGetResult.MissingPrekey,
        )
        assertTrue(
            "leerer ik_pub ist ebenso unbrauchbar → MissingPrekey",
            classify("""{"ik_pub":"","spk_pub":"spkBB"}""") is AgentPrekeyPolicy.KeyGetResult.MissingPrekey,
        )
        assertTrue(
            "kein JSON-Body (Parse-Fehler beim Caller) → MissingPrekey, kein Key-Raten",
            AgentPrekeyPolicy.classify(null) is AgentPrekeyPolicy.KeyGetResult.MissingPrekey,
        )
    }

    @Test
    fun retry_kappe_maximal_drei_versuche_gesamt() {
        assertTrue("nach Versuch 1 → Retry 2", AgentPrekeyPolicy.shouldRetry(1))
        assertTrue("nach Versuch 2 → Retry 3", AgentPrekeyPolicy.shouldRetry(2))
        assertFalse("nach Versuch 3 = Budget leer → sichtbarer Fehler", AgentPrekeyPolicy.shouldRetry(3))
        assertFalse("weit jenseits des Budgets = kein Retry", AgentPrekeyPolicy.shouldRetry(99))
        assertFalse("ungültiger Zähler = kein Retry", AgentPrekeyPolicy.shouldRetry(0))
    }

    @Test
    fun fehlermeldung_nennt_agenten_und_handlungshinweis() {
        val text = AgentPrekeyPolicy.statusText("jarvis@kibot")
        assertTrue("Meldung muss den Agenten nennen: $text", text.contains("Relay fehlt Prekey für jarvis@kibot"))
        assertTrue("Meldung muss den Handlungshinweis tragen: $text", text.contains("Relay-Konfiguration/Prekey-Eintrag prüfen"))
    }
}
