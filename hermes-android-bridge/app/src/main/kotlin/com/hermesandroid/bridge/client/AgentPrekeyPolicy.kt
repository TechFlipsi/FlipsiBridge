package com.hermesandroid.bridge.client

import com.google.gson.JsonObject

/**
 * W13-P2 (Jäger-Befund 12.11.26, Emulator-Drill): Antwortet das Relay auf
 * /key_get mit LEEREM spk_pub (Agent-Prekey AGENT_PREKEY fehlt in der
 * Relay-Konfig), warf PhoneCryptoPeer eine stille CryptoFrameException und
 * das Onboarding hing ENDLOS in „Warte auf E2E-Handshake“ — ohne
 * Fehlermeldung, ohne Retry.
 *
 * Entscheidung je key_get mit leerem spk_pub: max. [MAX_ATTEMPTS] Versuche
 * gesamt (Backoff macht der Caller), danach SICHTBARER Fehler im
 * Onboarding-Status mit Handlungshinweis (Relay-Konfiguration/Prekey-
 * Eintrag prüfen) — analog HelloAckFailurePolicy (Bug-1-Härtung).
 */
object AgentPrekeyPolicy {

    /** key_get-Versuche GESAMT je Connect-Zyklus (erster + 2 Retries). */
    const val MAX_ATTEMPTS = 3

    /** Backoff je Retry (Fehlzähler × Basis: 5 s / 10 s). */
    const val RETRY_BACKOFF_MS = 5_000L

    /**
     * true = noch einen Versuch wagen; false = Budget aufgebraucht →
     * sichtbarer Fehler. failedAttempts = Fehlschläge INKLUSIVE des
     * aktuellen (1 = erster → Retry, 3 = dritter → Schluss).
     */
    fun shouldRetry(failedAttempts: Int): Boolean = failedAttempts in 1..(MAX_ATTEMPTS - 1)

    /** Ergebnis eines /key_get (Eckfälle JVM-testbar, siehe AgentPrekeyPolicyTest). */
    sealed class KeyGetResult {
        /** Beide Public-Keys vollständig geliefert — Handshake kann laufen. */
        data class Ok(val ikPub: String, val spkPub: String) : KeyGetResult()

        /** HTTP 200, aber ik_pub/spk_pub fehlen oder sind leer — Prekey-Feuer. */
        object MissingPrekey : KeyGetResult()
    }

    /**
     * /key_get-Antwort klassifizieren: nur vollständig gefüllte ik_pub UND
     * spk_pub sind Ok (getrimmt); fehlende, nicht-primitive, leere oder
     * Whitespace-Felder = MissingPrekey (fail-closed, kein Key-Raten).
     */
    fun classify(obj: JsonObject?): KeyGetResult {
        if (obj == null) return KeyGetResult.MissingPrekey
        val ik = fieldOf(obj, "ik_pub") ?: return KeyGetResult.MissingPrekey
        val spk = fieldOf(obj, "spk_pub") ?: return KeyGetResult.MissingPrekey
        return KeyGetResult.Ok(ik, spk)
    }

    /** Klartext-Feld getrimmt — null bei fehlend/nicht-primitiv/leer. */
    private fun fieldOf(obj: JsonObject, name: String): String? = runCatching {
        obj.get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Sichtbare Meldung nach aufgebrauchtem Budget (Onboarding-Status). */
    fun statusText(agentId: String): String =
        "Relay fehlt Prekey für $agentId — Relay-Konfiguration/Prekey-Eintrag prüfen"
}
