package com.hermesandroid.bridge.client

import com.google.gson.JsonObject

/**
 * B5 (Multi-Gerät, Sir-General-GO 17.11.): Anmelde-Tor-Frames — rein,
 * JVM-testbar (RelayClient sendet/empfängt nur).
 *
 * FRAME-VERTRAG (aus dem Backend gelesen, nicht geraten — Commit 24acda0,
 * tools/android_relay.py):
 * - B1-Anmelde-Tor ZUM Phone (_pairing_request_emit, Relay → Admin-Slots oder
 *   ohne Admin an jeden lebenden Slot):
 *     {"type": "pairing.request", "v": 1, "request_id": <str>,
 *      "device_id_masked": <str>, "ip": <str>, "ts": <int ms>, "ttl_s": <int>}
 *   — Bewusst NUR die MASKIERTE Kennung im Frame (PII-Regel, _mask_id-Parität).
 * - ZUM Relay VOM Phone (_handle_pairing_admin_frame, WS-Bein authentifiziert):
 *     {"type": "pairing.approve", "device_id"?: <raw>, "request_id"?: <str>}
 *     {"type": "pairing.deny", ...}
 *   — Relay-Norm: device_id ODER request_id; die App kennt die Roh-Kennung
 *     NIE (nur device_id_masked) → wir senden ausschließlich request_id-Basis.
 *   — Nur der ADMIN-Slot verwaltet (PH5-T7); Non-Admin-Approve = fail-closed
 *     verworfen am Relay. TTL 10 min, einmalig (pairing.approve).
 */
object PairingFrameRules {

    const val REQUEST_FRAME = "pairing.request"
    const val APPROVE_FRAME = "pairing.approve"
    const val DENY_FRAME = "pairing.deny"

    /** Eine empfangene Kopplungs-Anfrage (nur maskierte Felder — PII-Regel). */
    data class PairingFrame(
        val requestId: String,
        val deviceMasked: String,
        val ip: String,
        val ts: Long,
        val ttlS: Long,
    ) {
        /** Abgelaufen (TTL vom Empfangs-Zeitpunkt gerechnet, ehrlich hart). */
        fun expired(nowMs: Long): Boolean = ttlS in 0..Long.MAX_VALUE &&
            nowMs > ts + ttlS * 1000L
    }

    private fun strOf(json: JsonObject, field: String): String? = runCatching {
        json.get(field)?.takeIf { it.isJsonPrimitive }?.asString
    }.getOrNull()

    private fun longOf(json: JsonObject, field: String): Long? = runCatching {
        json.get(field)?.takeIf { it.isJsonPrimitive }?.asLong
    }.getOrNull()

    /** pairing.request → StateFlow-Inhalt. null = fremde/leere Struktur (verwerfen). */
    fun parse(json: JsonObject): PairingFrame? {
        val type = strOf(json, "type") ?: return null
        if (type != REQUEST_FRAME) return null
        val requestId = strOf(json, "request_id")?.takeIf { it.isNotBlank() } ?: return null
        return PairingFrame(
            requestId = requestId,
            deviceMasked = strOf(json, "device_id_masked").orEmpty(),
            ip = strOf(json, "ip").orEmpty(),
            ts = longOf(json, "ts") ?: System.currentTimeMillis(),
            ttlS = longOf(json, "ttl_s") ?: 0L,
        )
    }

    /** approve/Deny-Draht: NUR request_id (Relay löst die device_id selbst auf). */
    private fun adminFrame(type: String, requestId: String): JsonObject? {
        val id = requestId.trim()
        if (id.isEmpty()) return null
        return JsonObject().apply {
            addProperty("type", type)
            addProperty("request_id", id)
        }
    }

    fun approveFrame(requestId: String): JsonObject? = adminFrame(APPROVE_FRAME, requestId)

    fun denyFrame(requestId: String): JsonObject? = adminFrame(DENY_FRAME, requestId)
}
