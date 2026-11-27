package com.hermesandroid.bridge.client

import com.google.gson.JsonObject

/**
 * W23-9 (Punkt W23-9, Arbeits-Brief +
 * VERTRAGS-KORREKTUR aus dem Koordinator-Chat): Umbenennen-Frames — rein,
 * JVM-testbar (RelayClient sendet/empfängt nur; die Python-Seite baut der
 * Koordinator separat — der Client bleibt fail-soft, wenn der Frame fehlt).
 *
 * FRAME-VERTRAG (KORREKTUR — maßgeblich, ersetzt die erste Brief-Fassung):
 *
 * - ZUM Relay vom Phone:
 *     {"type": "pairing.device_alias",
 *      "request_id": <PRIMARY-Zielkennung — das eindeutige request_id-Handle
 *                     aus der relay.state-Zeile des Ziel-Geräts>,
 *      "alias": <hygienisch gekürzt ≤ 32>,
 *      "device_id"?: <Maske "<masked:N>" — NUR Info, NICHT server-eindeutig>}
 *
 *   BEGRÜNDUNG der Korrektur: die relay.state-Verbands-Maske trägt nur die
 *   LÄNGE der rohen Kennung (_mask_id) — an einer Registry mit zwei
 *   36-Zeichen-UUID-Geräten kollidieren beide auf "<masked:36>". Die Maske
 *   ist am Server NICHT eindeutig auflösbar; das einzig eindeutige
 *   Zielträger-Konzept je Kachel ist das (ephemere, je Gerät eindeutige)
 *   request_id-Revoke-Handle. Deshalb: request_id = PRIMARY, Maske nur als
 *   Info-Feld daneben (das Relay kann die Zeile über das Handle auflösen).
 *
 * - ZUM Phone vom Relay (Antwort):
 *     {"type": "pairing.device_alias_ok",
 *      "ok": <bool>,
 *      "error"?: <"denied"|"bad"|…>,
 *      "request_id": <Echo des gesandten Handles (Korrelations-ID)>,
 *      "alias": <Echo>,
 *      "device_id_masked"?: <Maske der betroffenen Kachel>}
 *
 *   Fail-soft-Verband: Frame unvollständig (type/request_id fehlt) → verwerfen;
 *   ok-Feld fehlt → als NICHT-Erfolg gelesen (nie ein Fake-„gespeichert“);
 *   leeres error-Feld → null (die UI fällt dann auf die „später“-Zeile).
 *
 * Anzeige-Verband (Brief TEIL c): relay.state devices[] liefern alias bereits
 * (RelayClient.parseRelayStateDevices liest es); Fehl-Feld = leer → die UI
 * fällt generisch auf „Gerät N“ zurück (DeviceRules.sanitizeAlias).
 */
object DeviceAliasRules {

    const val ALIAS_FRAME = "pairing.device_alias"
    const val ALIAS_OK_FRAME = "pairing.device_alias_ok"

    /**
     * Warten-Deckel auf die device_alias_ok-Antwort (kein UI-Schleudern):
     * kein Ack bis dahin = ehrliche „später“-Zeile, KEIN Wiederhol-Senden.
     */
    const val ACK_TIMEOUT_MS = 5_000L

    /**
     * Alias-Deckel am Draht — Parität DeviceRules.ALIAS_MAX_CHARS (32;
     * Anzeige-Hygiene und Frame-Hygiene bleiben synchron, deshalb bewusst
     * dieselbe Zahl an BEIDEN Stellen mit gegenseitigem KDoc-Verweis).
     */
    const val ALIAS_MAX_CHARS = 32

    /** Bestätigung der Umbenennen-Route (Verband der ok-Antwort). */
    data class AliasAck(
        /** Korrelations-/Ziel-Handle — Echo des gesandten request_id. */
        val requestId: String,
        /** Server-Urteil: false = abgelehnt/fehlgeschlagen (Feld fehlt = false). */
        val ok: Boolean,
        /** Abweisungs-Grund des Relays ("denied"/"bad", …) — null = keiner. */
        val error: String?,
        /** Echo des gespeicherten Alias (Info der UI). */
        val alias: String,
        /** Maske der betroffenen Kachel (Info; die App hält nur Masken — PII). */
        val deviceIdMasked: String,
    )

    private fun strOf(json: JsonObject, field: String): String? = runCatching {
        json.get(field)?.takeIf { it.isJsonPrimitive }?.asString
    }.getOrNull()

    private fun boolOf(json: JsonObject, field: String): Boolean? = runCatching {
        json.get(field)?.takeIf { it.isJsonPrimitive }?.asBoolean
    }.getOrNull()

    /**
     * Frame-Hygiene des Alias (Parität DeviceRules.sanitizeAlias-Mechanik,
     * OHNE deren „Gerät N“-Fallback — leer am Draht bleibt leer, der Aufrufer
     * entscheidet): Steuerzeichen fliegen, Whitespace kollabiert, Länge
     * ≤ [ALIAS_MAX_CHARS]. Rückgabe TRIMMED-nichtleer oder leerer String.
     */
    fun normalizeAliasInput(raw: String?): String =
        (raw ?: "")
            .filter { it.isLetterOrDigit() || it.isWhitespace() }
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(ALIAS_MAX_CHARS)

    /**
     * Send-Vertrag des Umbenennen-Frames: request_id-Handle als PRIMARY-Ziel
     * (eindeutig an der Registry), Maske NUR als Info. null = Frame unvoll-
     * ständig (kein Handle/kein wirksamer Alias) — der Aufrufer sendet dann
     * BEWUSST NICHT (fail-soft: ein Blind-Frame nützt dem Relay nichts).
     *
     * @param targetRequestId Eindeutiges Handle aus der relay.state-Zeile des
     *        Ziel-Geräts (W23-9-KORREKTUR: die einzig server-eindeutige
     *        Zielkennung; die Maske allein reicht NICHT).
     * @param alias Gewünschter Name (wird hier hygienisch veredelt).
     * @param deviceIdMasked Maske der Kachel (reines Info-Feld, optional).
     */
    fun aliasFrame(
        targetRequestId: String,
        alias: String,
        deviceIdMasked: String?,
    ): JsonObject? {
        val handle = targetRequestId.trim()
        val name = normalizeAliasInput(alias)
        if (handle.isEmpty() || name.isEmpty()) return null
        return JsonObject().apply {
            addProperty("type", ALIAS_FRAME)
            addProperty("request_id", handle)
            addProperty("alias", name)
            if (!deviceIdMasked.isNullOrBlank()) addProperty("device_id", deviceIdMasked)
        }
    }

    /**
     * Ack-Verband: null = fremde/unvollständige Struktur (verwerfen —
     * fail-soft, KEIN Crash und kein UI-Zustands-Wechsel).
     */
    fun parse(json: JsonObject): AliasAck? {
        val type = strOf(json, "type") ?: return null
        if (type != ALIAS_OK_FRAME) return null
        // Ohne Echo-Handle gibt es keine Zuordnung zur Anfrage — verwerfen.
        val requestId = strOf(json, "request_id")?.takeIf { it.isNotBlank() } ?: return null
        return AliasAck(
            requestId = requestId,
            // Fehlendes ok-Feld = nie ein Fake-„gespeichert“ (ehrlich falsch
            // statt stiller Erfolg — die UI zeigt dann die „später“-Zeile).
            ok = boolOf(json, "ok") ?: false,
            error = strOf(json, "error")?.takeIf { it.isNotBlank() },
            alias = strOf(json, "alias").orEmpty(),
            deviceIdMasked = strOf(json, "device_id_masked").orEmpty(),
        )
    }
}
